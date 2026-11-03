const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const crypto = require('node:crypto');
const { createGateway } = require('../wechat-test-gateway.cjs');

test('test ingress restricts routes and sessions, hides credentials, and preserves OAuth callbacks', async t => {
  const received = [];
  const upstream = http.createServer((req, res) => {
    received.push({ url: req.url, cookie: req.headers.cookie });
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({ ok: true }));
  });
  await new Promise(r => upstream.listen(0, '127.0.0.1', r));
  t.after(() => upstream.close());
  const config = { appId: 'wxtest123456789012', appSecret: 'SECRET-DO-NOT-EXPOSE', communityId: 'community',
    token: 'PRIVATE_CALLBACK_TOKEN', accessCode: 'test-access-code-123', sessionSecret: 'a'.repeat(64), publicBaseUrl: '',
    callbackPath: '/app/wechat/gateway?java110AppId=992020061452450002&wId=abc%2Bdef' };
  const target = 'http://127.0.0.1:' + upstream.address().port;
  const gateway = createGateway({ config: () => config, backend: target, owner: target });
  await new Promise(r => gateway.listen(0, '127.0.0.1', r));
  t.after(() => gateway.close());
  const base = 'http://127.0.0.1:' + gateway.address().port;
  const query = new URLSearchParams({ timestamp: '123456', nonce: 'wx-test-nonce', echostr: '1234567890' });
  query.set('signature', crypto.createHash('sha1').update([config.token, query.get('timestamp'), query.get('nonce')].sort().join('')).digest('hex'));
  const callback = await fetch(base + '/wechat-test/callback?' + query);
  assert.equal(callback.status, 200);
  assert.equal(await callback.text(), '1234567890');
  assert.equal(received.length, 0, 'Server verification must not wait for backend business calls');
  query.set('signature', 'invalid-signature');
  assert.equal((await fetch(base + '/wechat-test/callback?' + query)).status, 403);
  const invalidTargetStatus = await new Promise((resolve, reject) => {
    const request = http.get(base, { path: '//[' }, response => {
      response.resume(); resolve(response.statusCode);
    });
    request.on('error', reject);
  });
  assert.equal(invalidTargetStatus, 404);
  assert.equal((await fetch(base + '/app/fee.listFee')).status, 403);
  const enter = await fetch(base + '/wechat-test/enter', { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'code=test-access-code-123', redirect: 'manual' });
  assert.equal(enter.status, 303);
  const cookie = enter.headers.get('set-cookie').split(';')[0];
  assert.match(enter.headers.get('set-cookie'), /HttpOnly/);
  assert.match(enter.headers.get('set-cookie'), /SameSite=Lax/);
  const headers = { cookie };
  const configResponse = await fetch(base + '/wechat-test/config.js', { headers });
  assert.equal(configResponse.headers.get('cache-control'), 'no-store');
  const browserConfig = await configResponse.text();
  assert.match(browserConfig, /paymentEnabled/);
  let publicConfig = JSON.parse(browserConfig.slice(browserConfig.indexOf('=')+1).replace(/;$/, ''));
  assert.equal(publicConfig.smsEnabled, false, 'SMS must be explicitly enabled');
  config.communityName = 'Synthetic community'; config.smsEnabled = true;
  const enabledConfig = await (await fetch(base + '/wechat-test/config.js', { headers })).text();
  publicConfig = JSON.parse(enabledConfig.slice(enabledConfig.indexOf('=')+1).replace(/;$/, ''));
  assert.equal(publicConfig.communityName, 'Synthetic community');
  assert.equal(publicConfig.smsEnabled, true);
  assert.ok(!browserConfig.includes(config.appSecret));
  assert.ok(!browserConfig.includes(config.token));
  assert.ok(!browserConfig.includes(config.accessCode));
  for (const path of ['/admin.html', '/api/fee.listFee', '/app/smallWeChat.listSmallWeChats',
    '/callComponent/menu/getMenus', '/.runtime/wechat-test.json', '/app/repair.listRepairSettings',
    '/app%2ffoo', '/app;x/fee.listFee', '/app/fee.listFee/extra']) {
    assert.equal((await fetch(base + path, { headers })).status, 404, path);
  }
  assert.equal(received.length, 0, 'Forbidden operations must never reach upstream');
  assert.equal((await fetch(base + '/static/js/pages-index-index~pages-location-location.1974b4b3.js', { headers })).status, 200);
  assert.equal((await fetch(base + '/app/payment/toOweFeePay', { method: 'POST', headers })).status, 503);
  assert.equal((await fetch(base + '/app/fee.listFee', { headers: { ...headers, token: 'test-owner-token' } })).status, 200);
  assert.match(received.at(-1).cookie, /_java110_token_=test-owner-token/);
  assert.ok(!received.at(-1).cookie.includes('wuye_test_gate'));
  assert.equal((await fetch(base + '/app/fee.listFee', { method: 'POST', headers })).status, 405);
  assert.equal((await fetch(base + '/app/loginOwnerWechatAuth?state=expected&code=test')).status, 200);
  assert.equal((await fetch(base + '/app/wechat/gateway?signature=test&echostr=123')).status, 200);
  assert.equal((await fetch(base + '/wechat-test/callback?signature=test&timestamp=123&nonce=wx&wId=attacker', { method: 'POST', body: '<xml/>' })).status, 200);
  const forwardedCallback = new URL(received.at(-1).url, base);
  assert.equal(forwardedCallback.pathname, '/app/wechat/gateway');
  assert.equal(forwardedCallback.searchParams.get('wId'), 'abc+def');
  assert.equal(forwardedCallback.searchParams.get('signature'), 'test');
  assert.equal((await fetch(base + '/wechat-test/config.js', { headers: { cookie: cookie + 'tampered' } })).status, 403);
  const foreignHostStatus = await new Promise((resolve, reject) => {
    const request = http.get(base + '/', { headers: { host: 'attacker.example', cookie } }, response => {
      response.resume(); resolve(response.statusCode);
    });
    request.on('error', reject);
  });
  assert.equal(foreignHostStatus, 421);
});
