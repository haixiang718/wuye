'use strict';
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const routes = new Map([
  ['/app/refreshToken', 'GET'], ['/app/loginOwnerWechatAuth', 'GET'],
  ['/app/wechat/gateway', 'GET,POST'], ['/app/loginOwner', 'POST'],
  ['/app/loginOwnerByKey', 'POST'], ['/app/owner/ownerLoginByKey', 'POST'],
  ['/app/bindOwnerWechat', 'POST'],
  ['/app/area.listAreas', 'GET'], ['/app/community.listCommunitys', 'GET'],
  ['/app/communitys/queryCommunityMenu', 'GET'],
  ['/app/room.queryRoomsByOwner', 'GET'], ['/app/floor.queryFloors', 'GET'],
  ['/app/unit.queryUnits', 'GET'], ['/app/owner.listAppUserBindingOwners', 'GET'],
  ['/app/owner.ownerRegister', 'POST'], ['/app/owner.appUserBindingOwner', 'POST'],
  ['/app/owner.deleteAppUserBindingOwner', 'POST'], ['/app/user.service.logout', 'POST'],
  ['/app/fee.listFee', 'GET'], ['/app/fee.queryFeeDetail', 'GET'],
  ['/app/feeApi/listOweFees', 'GET'], ['/app/feeDiscount/computeFeeDiscount', 'GET'],
  ['/app/parkingSpace.queryParkingSpacesByOwner', 'GET'], ['/app/fee.queryFeeByParkingSpace', 'GET'],
  ['/app/owner.queryOwnerCars', 'GET'], ['/app/userLogin/generatorHcCode', 'GET'],
  ['/app/payment/toPay', 'POST'], ['/app/payment/toOweFeePay', 'POST'],
  ['/app/payment/v3/notify', 'POST'], ['/app/payment/v3/query', 'POST'], ['/app/payment/v3/close', 'POST'],
]);
const publicCallbacks = new Set(['/app/wechat/gateway', '/app/loginOwnerWechatAuth', '/app/payment/v3/notify']);

function equal(a, b) {
  const x = Buffer.from(String(a)), y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}
function cookieValue(headers, name) {
  return (headers.cookie || '').split(';').map(x => x.trim()).find(x => x.startsWith(name + '='))?.slice(name.length + 1);
}
function ticket(config) {
  const value = String(Date.now() + 8 * 60 * 60 * 1000);
  return value + '.' + crypto.createHmac('sha256', config.sessionSecret).update(value).digest('base64url');
}
function authenticated(req, config) {
  const value = cookieValue(req.headers, 'wuye_test_gate') || '';
  const [expiry, signature] = value.split('.');
  if (!expiry || !signature || Number(expiry) <= Date.now()) return false;
  const expected = crypto.createHmac('sha256', config.sessionSecret).update(expiry).digest('base64url');
  return equal(expected, signature);
}
function json(res, status, value) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(value));
}
function createGateway(options) {
  const backend = new URL(options.backend || 'http://127.0.0.1:8012');
  const owner = new URL(options.owner || 'http://127.0.0.1:8000');
  return http.createServer(async (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    let config;
    try { config = options.config(); } catch { return json(res, 503, { msg: '测试配置不可用' }); }
    if (!config.sessionSecret || !config.accessCode) return json(res, 503, { msg: '尚未配置测试入口' });
    const host = req.headers.host || '';
    const publicHost = config.publicBaseUrl ? new URL(config.publicBaseUrl).host : '';
    if (!/^127\.0\.0\.1(?::\d+)?$/.test(host) && !/^localhost(?::\d+)?$/.test(host) && host !== publicHost) {
      return json(res, 421, { msg: '入口地址不匹配' });
    }
    // Reject encoded path separators, matrix parameters and traversal before routing.
    const rawPath = req.url.split('?')[0];
    if (!rawPath.startsWith('/') || rawPath.startsWith('//') || /[%\\;]|\.{2}/.test(rawPath)) return json(res, 404, { msg: '路径不可用' });
    let url;
    try { url = new URL(req.url, 'http://localhost'); }
    catch { return json(res, 404, { msg: '路径不可用' }); }
    let notificationBody;
    if (url.pathname === '/app/payment/v3/notify') {
      if (req.method !== 'POST') return json(res, 405, {msg:'请求方法不可用'});
      // Keep the signed body intact. Actual signature verification and decryption are server-side.
      const names=['wechatpay-timestamp','wechatpay-nonce','wechatpay-serial','wechatpay-signature'];
      if (names.some(name => typeof req.headers[name] !== 'string' || !req.headers[name] || req.headers[name].length > 2048)) {
        return json(res, 403, {msg:'支付通知签名头缺失或无效'});
      }
      if (Number(req.headers['content-length']) > 65536) return json(res, 413, {msg:'请求过大'});
      const chunks=[]; let size=0;
      try {
        for await (const chunk of req) {
          size+=chunk.length;
          if (size>65536) return json(res,413,{msg:'请求过大'});
          chunks.push(chunk);
        }
      } catch { return json(res,400,{msg:'请求读取失败'}); }
      notificationBody=Buffer.concat(chunks);
    }
    if (url.pathname === '/wechat-test/callback') {
      if (req.method === 'GET') {
        const signature = url.searchParams.get('signature') || '';
        const timestamp = url.searchParams.get('timestamp') || '';
        const nonce = url.searchParams.get('nonce') || '';
        const echo = url.searchParams.get('echostr');
        if (!config.token || !timestamp || !nonce || echo === null || echo.length > 1024
          || !/^[a-f0-9]{40}$/.test(signature)) return json(res, 403, { msg: '微信校验参数无效' });
        const expected = crypto.createHash('sha1').update([config.token, timestamp, nonce].sort().join('')).digest('hex');
        if (!equal(signature, expected)) return json(res, 403, { msg: '微信签名校验失败' });
        res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
        return res.end(echo);
      }
      if (req.method !== 'POST') return json(res, 405, { msg: '请求方法不可用' });
      let callback;
      try { callback = new URL(config.callbackPath, 'http://localhost'); }
      catch { return json(res, 503, { msg: '后台回调尚未配置' }); }
      if (callback.pathname !== '/app/wechat/gateway') return json(res, 503, { msg: '后台回调尚未配置' });
      for (const [key, value] of url.searchParams) {
        if (key !== 'wId' && key !== 'java110AppId') callback.searchParams.set(key, value);
      }
      url = callback;
    }
    if (url.pathname === '/wechat-test/enter') {
      if (req.method !== 'POST') return json(res, 405, { msg: '请通过测试入口提交访问码' });
      let body = '';
      try {
        for await (const chunk of req) {
          body += chunk;
          if (body.length > 4096) return json(res, 413, { msg: '请求过大' });
        }
      } catch { return json(res, 400, { msg: '请求读取失败' }); }
      const value = new URLSearchParams(body).get('code') || '';
      if (!equal(value, config.accessCode)) return json(res, 403, { msg: '测试访问码不正确' });
      const secure = req.headers['x-forwarded-proto'] === 'https' ? '; Secure' : '';
      res.writeHead(303, { Location: '/', 'Set-Cookie': 'wuye_test_gate=' + ticket(config) + '; Path=/; HttpOnly; SameSite=Lax; Max-Age=28800' + secure });
      return res.end();
    }
    if (!publicCallbacks.has(url.pathname) && !authenticated(req, config)) {
      if (url.pathname !== '/' || req.method !== 'GET') return json(res, 403, { msg: '请先在首页输入测试访问码' });
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'" });
      return res.end('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>物业系统测试入口</title><style>body{font:16px sans-serif;max-width:420px;margin:15vh auto;padding:24px;color:#1e293b}input,button{box-sizing:border-box;width:100%;padding:14px;margin-top:16px;font:inherit}button{background:#0d9488;color:white;border:0;border-radius:6px}</style><h1>物业系统联调</h1><p>请输入本次测试访问码。</p><form action="/wechat-test/enter" method="post"><input name="code" type="password" autocomplete="off" required placeholder="测试访问码"><button>进入测试</button></form></html>');
    }
    if (url.pathname === '/wechat-test/config.js' && req.method === 'GET') {
      res.writeHead(200, { 'Content-Type': 'application/javascript; charset=utf-8' });
      return res.end('window.__WUYE_WECHAT_TEST__=' + JSON.stringify({ appId: config.appId, communityId: config.communityId,
        communityName: config.communityName || '丰仪家园', smsEnabled: config.smsEnabled === true, paymentEnabled: config.paymentEnabled === true }) + ';');
    }
    if (['/app/payment/toPay','/app/payment/toOweFeePay'].includes(url.pathname) && config.paymentEnabled !== true) {
      return json(res, 503, { code: 'PAYMENT_NOT_CONFIGURED', msg: '微信支付暂未启用，仍可查询账单' });
    }
    const isBackend = url.pathname.startsWith('/app/');
    if (isBackend) {
      if (!routes.has(url.pathname)) return json(res, 404, { msg: '该接口不在本次联调范围内' });
      if (!routes.get(url.pathname).split(',').includes(req.method)) return json(res, 405, { msg: '请求方法不可用' });
    } else if (req.method !== 'GET' && req.method !== 'HEAD') {
      return json(res, 405, { msg: '请求方法不可用' });
    } else if (url.pathname !== '/' && !/^\/(?:static|assets|js|css|fonts|img|images)\/[a-zA-Z0-9_./@~-]+$/.test(url.pathname)
      && !['/index.html', '/favicon.ico'].includes(url.pathname)) {
      return json(res, 404, { msg: '该路径不可用' });
    }
    const target = isBackend ? backend : owner;
    const headers = { ...req.headers, host: target.host };
    delete headers.authorization;
    delete headers['proxy-authorization'];
    const cookies = (headers.cookie || '').split(';').map(x => x.trim()).filter(x => x.startsWith('_java110_token_='));
    const token = headers.token;
    if (isBackend && token) {
      if (!/^[a-zA-Z0-9._~-]{1,4096}$/.test(token)) return json(res, 400, { msg: '登录凭据格式不正确' });
      cookies.splice(0, cookies.length, '_java110_token_=' + token);
    }
    delete headers.token;
    if (cookies.length) headers.cookie = cookies.join('; '); else delete headers.cookie;
    if (isBackend) {
      headers['w-app-id'] = config.appId;
      headers['app-id'] = '992020061452450002';
      headers['app_id'] = '992020061452450002';
      const origin = config.publicBaseUrl || 'http://' + host;
      headers['x-forwarded-host'] = new URL(origin).host;
      headers['x-forwarded-proto'] = new URL(origin).protocol.slice(0, -1);
    }
    const proxy = http.request({ hostname: target.hostname, port: target.port, method: req.method,
      path: url.pathname + url.search, headers, timeout: 25000 }, upstream => {
      res.writeHead(upstream.statusCode, { ...upstream.headers, 'cache-control': 'no-store', 'referrer-policy': 'no-referrer' });
      upstream.pipe(res);
    });
    proxy.on('timeout', () => proxy.destroy(new Error('upstream timeout')));
    proxy.on('error', () => { if (!res.headersSent) json(res, 502, { msg: '本地服务尚未就绪，请稍后重试' }); else res.destroy(); });
    req.on('aborted', () => proxy.destroy());
    if (notificationBody) proxy.end(notificationBody); else req.pipe(proxy);
  });
}

if (require.main === module) {
  const configFile = path.resolve(__dirname, '../.runtime/wechat-test.json');
  const config = () => JSON.parse(fs.readFileSync(configFile, 'utf8').replace(/^\uFEFF/, ''));
  const port = config().gatewayPort || 8080;
  createGateway({ config, owner: 'http://127.0.0.1:' + (config().ownerH5Port || 8000) })
    .listen(port, '127.0.0.1', () => console.log('WeChat test gateway: http://127.0.0.1:' + port));
}
module.exports = { createGateway };
