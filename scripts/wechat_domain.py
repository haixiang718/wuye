"""Start and inspect the fixed-domain WeChat demo without exposing private configuration."""
import argparse
import html
import json
from pathlib import Path
import shutil

import local
import server_tunnel

CONFIG=local.RUN/'wechat-domain.json'


def read_config():
    config=json.loads(CONFIG.read_text(encoding='utf-8-sig'))
    if config.get('publicBaseUrl')!='https://h5.jiaanfuwu.cn':
        raise RuntimeError('Unexpected public origin in fixed-domain configuration')
    return config


def start():
    read_config()
    if not local.listening(8012):
        raise RuntimeError('Start the merged local business services before starting the domain ingress')
    node=shutil.which('node')
    if not node:raise RuntimeError('Node.js is required')
    local.spawn('owner-h5',[node,str(local.ROOT/'scripts/owner-h5.cjs'),'serve'],8000)
    local.spawn('wechat-domain',[node,str(local.ROOT/'scripts/wechat-domain-gateway.cjs')],8080)
    server_tunnel.start()


def stop():
    server_tunnel.stop()
    local.stop(['wechat-domain','owner-h5'])


def handoff():
    config=read_config()
    rows=[('手机微信内打开','https://h5.jiaanfuwu.cn/'),('联调访问码',config['accessCode']),
        ('网页授权域名','h5.jiaanfuwu.cn'),('JS 接口安全域名','h5.jiaanfuwu.cn'),
        ('服务器配置 → URL','https://wx.jiaanfuwu.cn/wechat/callback'),('服务器配置 → Token',config['token']),
        ('消息加解密方式','联调阶段先选择明文模式；EncodingAESKey 如平台要求可点击随机生成。')]
    content=''.join('<dt>'+html.escape(name)+'</dt><dd><pre>'+html.escape(value)+'</pre></dd>' for name,value in rows)
    text='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>固定域名公众号联调配置</title><style>body{font:16px sans-serif;max-width:900px;margin:32px auto;padding:24px;color:#1e293b;line-height:1.7}pre{padding:12px;background:#f1f5f9;white-space:pre-wrap;overflow-wrap:anywhere;user-select:all}dt{font-weight:bold}dd{margin-left:0}</style>
<h1>固定域名公众号联调</h1><p>此说明页包含本次联调访问码和回调 Token，仅保存在本机，请勿上传公开网站或代码仓库。</p>
<p>服务器提供 HTTPS，本机承载业务；测试期间电脑和隧道需持续在线。管理后台：https://admin.jiaanfuwu.cn/ ，使用后台账号登录；本机 http://127.0.0.1:3000/ 仍可使用。</p><dl>'''+content+'''</dl>
<p>请先关注正式服务号，再在微信内打开 H5、输入访问码，页面会自动进行微信授权。未绑定时进入“我的房屋”填写物业登记的姓名、手机号、身份证号，三项完全匹配后立即绑定，无需注册或密码。已有测试号绑定不等于正式号身份授权。</p>
<p>当前微信支付未开通。正式号已添加“物业费用账单缴纳提醒”模板，人工推送字段已适配；仍需向指定测试业主完成正式号手机收件、点击及数据归属验收。自动通知任务继续关闭。</p>
<p>服务器配置通常位于“设置与开发 → 基本配置/开发接口管理”。保存成功和启用结果需在微信平台核对；手机授权结果须另行确认。</p></html>'''
    target=local.RUN/'domain-ingress/handoff.html'
    target.parent.mkdir(exist_ok=True)
    target.write_text(text,encoding='utf-8')
    print('Private local guide: '+str(target))


def status():
    for name,port in [('Owner H5',8000),('Fixed-domain gateway',8080),('Local management',3000)]:
        print(name+': '+('LISTENING' if local.listening(port) else 'STOPPED'))
    record=server_tunnel.STATE
    print('Tunnel supervisor: '+('RUNNING' if record.exists() and server_tunnel.alive(json.loads(record.read_text())) else 'STOPPED'))
    print('Public owner entry: https://h5.jiaanfuwu.cn/')
    print('Public management entry: https://admin.jiaanfuwu.cn/')
    print('Local listeners do not prove mobile OAuth success; verify in WeChat separately.')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['start','stop','status','handoff'])
    args=parser.parse_args()
    globals()[args.action]()
