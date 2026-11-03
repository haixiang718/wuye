import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import wechat_readiness as readiness


class WechatReadinessTests(unittest.TestCase):
    def test_current_property_template_is_supported_by_meaning_and_fields(self):
        content = '\u4e1a\u4e3b\u59d3\u540d:{{thing2.DATA}}\n\u8d26\u5355\u91d1\u989d:{{amount4.DATA}}\n\u7f34\u8d39\u7c7b\u578b:{{thing9.DATA}}\n\u8d26\u5355\u540d\u79f0:{{thing10.DATA}}\n\u63d0\u9192\u65f6\u95f4:{{time12.DATA}}'
        self.assertTrue(readiness.template_summary({'content': content})['compatibleWithCurrentBillSender'])
        self.assertFalse(readiness.template_summary({'content': content.replace('\u4e1a\u4e3b\u59d3\u540d', 'Phone')})['compatibleWithCurrentBillSender'])
        self.assertFalse(readiness.template_summary({'content': content + '{{thing99.DATA}}'})['compatibleWithCurrentBillSender'])

    def test_only_current_legacy_bill_fields_are_compatible(self):
        report = readiness.template_summary({'title': 'Bill', 'content': '{{keyword1.DATA}} {{keyword2.DATA}} {{keyword3.DATA}}'})
        self.assertTrue(report['compatibleWithCurrentBillSender'])
        self.assertFalse(readiness.template_summary({'title': 'Default', 'content': '{{content.DATA}}'})['compatibleWithCurrentBillSender'])
        self.assertFalse(readiness.template_summary({'title': 'New', 'content': '{{thing1.DATA}} {{amount2.DATA}}'})['compatibleWithCurrentBillSender'])

    def test_report_does_not_disclose_credentials_tokens_or_menu_query(self):
        calls = []
        def transport(endpoint, token=None, data=None):
            calls.append(endpoint)
            if endpoint == 'token': return {'access_token': 'SECRET-TOKEN'}
            if endpoint == 'templates': return {'template_list': [{'title': 'Default', 'content': '{{content.DATA}}', 'template_id': 'PRIVATE-ID'}]}
            return {'menu': {'button': [{'name': 'Home', 'type': 'view', 'url': 'https://h5.jiaanfuwu.cn/?secret=PRIVATE-QUERY'}]}}
        report = readiness.inspect({'appId': 'PUBLIC-ID', 'appSecret': 'SECRET-KEY'}, transport)
        encoded = json.dumps(report)
        for secret in ['SECRET-TOKEN', 'SECRET-KEY', 'PRIVATE-QUERY', 'PRIVATE-ID']:
            self.assertNotIn(secret, encoded)
        self.assertEqual(calls, ['token', 'templates', 'menu'])
        self.assertFalse(report['billNotificationReady'])
        self.assertEqual(report['messagesSent'], 0)

    def test_wechat_api_error_never_uses_provider_error_text(self):
        def transport(endpoint, token=None, data=None):
            return {'errcode': 40164, 'errmsg': 'secret appSecret=DO-NOT-PRINT'}
        report = readiness.inspect({'appId': 'id', 'appSecret': 'key'}, transport)
        self.assertEqual(report['checks'][0]['errorCode'], 40164)
        self.assertFalse(report['apiChecksPassed'])
        self.assertNotIn('DO-NOT-PRINT', json.dumps(report))

    def test_templates_and_menu_are_independent_checks(self):
        def transport(endpoint, token=None, data=None):
            if endpoint == 'token': return {'access_token': 'token'}
            if endpoint == 'templates': return {'errcode': 48001, 'errmsg': 'no permission'}
            return {'menu': {'button': []}}
        report = readiness.inspect({'appId': 'id', 'appSecret': 'key'}, transport)
        self.assertEqual([c['name'] for c in report['checks']], ['token', 'templates', 'menu'])
        self.assertTrue(report['checks'][-1]['passed'])
        self.assertFalse(report['billNotificationReady'])

    def test_candidate_does_not_mean_template_configured_or_notification_delivered(self):
        def transport(endpoint, token=None, data=None):
            if endpoint == 'token': return {'access_token': 'token'}
            if endpoint == 'templates': return {'template_list': [{'title': 'Bill', 'content': '{{keyword1.DATA}}{{keyword2.DATA}}{{keyword3.DATA}}'}]}
            return {'menu': {'button': []}}
        report = readiness.inspect({'appId': 'id', 'appSecret': 'key'}, transport)
        self.assertTrue(report['hasCompatibleBillTemplate'])
        self.assertFalse(report['billNotificationReady'])
        self.assertEqual(report['messagesSent'], 0)

    def test_transport_exception_is_redacted(self):
        def transport(endpoint, token=None, data=None):
            raise RuntimeError('https://api.weixin.qq.com/?access_token=HIDE')
        report = readiness.inspect({'appId': 'id', 'appSecret': 'key'}, transport)
        self.assertFalse(report['apiChecksPassed'])
        self.assertNotIn('HIDE', json.dumps(report))

    def test_empty_config_is_rejected_before_network(self):
        def transport(*args, **kwargs): self.fail('No network call expected')
        with self.assertRaises(ValueError): readiness.inspect({'appId': 'id', 'appSecret': ''}, transport)


if __name__ == '__main__': unittest.main()
