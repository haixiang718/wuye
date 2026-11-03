"""Read-only official-account readiness; never sends messages or publishes menus."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ENDPOINTS = {'token': '/cgi-bin/stable_token',
             'templates': '/cgi-bin/template/get_all_private_template',
             'menu': '/cgi-bin/menu/get'}


def transport(endpoint, token=None, data=None):
    path = ENDPOINTS[endpoint]
    if token:
        path += '?' + urllib.parse.urlencode({'access_token': token})
    body = None if data is None else json.dumps(data).encode('utf-8')
    request = urllib.request.Request('https://api.weixin.qq.com' + path, data=body,
                                    headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=25) as response:
        raw = response.read(1024 * 1024 + 1)
    if len(raw) > 1024 * 1024:
        raise ValueError('Oversized response')
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise ValueError('Unexpected response')
    return value


def template_summary(template):
    fields = sorted(set(re.findall(r'\{\{([A-Za-z0-9_]+)\.DATA\}\}', template.get('content', ''))))
    return {'title': template.get('title', ''), 'fields': fields,
            'compatibleWithCurrentBillSender': {'keyword1', 'keyword2', 'keyword3'}.issubset(fields)
            and set(fields).issubset({'first', 'keyword1', 'keyword2', 'keyword3', 'remark'})}


def menu_summary(button):
    value = {'name': button.get('name', ''), 'type': button.get('type', '')}
    try:
        url = urllib.parse.urlsplit(button.get('url', ''))
        value['targetsFixedH5'] = (url.scheme == 'https' and url.netloc == 'h5.jiaanfuwu.cn')
    except ValueError:
        value['targetsFixedH5'] = False
    if isinstance(button.get('sub_button'), list):
        value['children'] = [menu_summary(item) for item in button['sub_button']]
    return value


def inspect(config, request=transport):
    if not config.get('appId') or not config.get('appSecret'):
        raise ValueError('Fill the private official-account configuration first')
    report = {'checkedAt': datetime.now(timezone.utc).isoformat(), 'checks': [], 'templates': [],
              'menus': [], 'messagesSent': 0, 'menuPublishedByThisCheck': False,
              'hasCompatibleBillTemplate': False, 'billNotificationReady': False,
              'templateConfiguredAndPhoneDelivery': 'requires separate verification'}

    def probe(name, token=None, data=None):
        try:
            result = request(name, token=token, data=data)
            code = int(result.get('errcode', 0))
            passed = code == 0
            if name == 'token': passed = passed and bool(result.get('access_token'))
            if name == 'templates': passed = passed and isinstance(result.get('template_list'), list)
            if name == 'menu': passed = passed and isinstance(result.get('menu'), dict)
            report['checks'].append({'name': name, 'passed': passed, 'errorCode': code})
            return result if passed else None
        except Exception:
            # Provider error strings and network exceptions may contain credentials in URLs.
            report['checks'].append({'name': name, 'passed': False, 'error': 'request_or_response_failed'})
            return None

    auth = probe('token', data={'grant_type': 'client_credential', 'appid': config['appId'],
                               'secret': config['appSecret'], 'force_refresh': False})
    if auth:
        templates = probe('templates', token=auth['access_token'])
        if templates:
            report['templates'] = [template_summary(item) for item in templates['template_list']]
            report['hasCompatibleBillTemplate'] = any(item['compatibleWithCurrentBillSender'] for item in report['templates'])
        menus = probe('menu', token=auth['access_token'])
        if menus:
            report['menus'] = [menu_summary(item) for item in menus['menu'].get('button', [])]
    report['apiChecksPassed'] = len(report['checks']) == 3 and all(item['passed'] for item in report['checks'])
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=ROOT / '.runtime/wechat-domain.json')
    parser.add_argument('--output', type=Path, default=ROOT / '.runtime/nonpayment-readiness/wechat-readiness.json')
    args = parser.parse_args()
    try:
        config = json.loads(args.config.read_text(encoding='utf-8-sig'))
        report = inspect(config)
    except Exception:
        print('Unable to read the private account configuration; no credentials are displayed.')
        return 2
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=True))
    # A compatible template is only a candidate; configuration and real phone delivery remain separate.
    return 0 if report['apiChecksPassed'] else 1


if __name__ == '__main__': raise SystemExit(main())
