const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const {createGateway} = require('../wechat-test-gateway.cjs');

test('V3 callback bypasses the access gate, preserves raw signature data and stays available with collection off', async t => {
  const received = [];
  const backend = http.createServer(async (req,res) => {
    const chunks=[]; for await (const chunk of req) chunks.push(chunk);
    received.push({path:req.url,headers:req.headers,body:Buffer.concat(chunks).toString()});
    res.writeHead(204);res.end();
  });
  await new Promise(r=>backend.listen(0,'127.0.0.1',r));
  t.after(()=>backend.close());
  const config={sessionSecret:'a'.repeat(64),accessCode:'synthetic-only',appId:'wx-synthetic',paymentEnabled:false};
  const gateway=createGateway({config:()=>config,backend:'http://127.0.0.1:'+backend.address().port});
  await new Promise(r=>gateway.listen(0,'127.0.0.1',r));t.after(()=>gateway.close());
  const base='http://127.0.0.1:'+gateway.address().port;
  const raw='{ "event_type": "TRANSACTION.SUCCESS", "resource": {"ciphertext":"synthetic"} }\n';
  const headers={'content-type':'application/json','wechatpay-timestamp':'1234567890','wechatpay-nonce':'synthetic-nonce',
    'wechatpay-serial':'PUB_KEY_ID_synthetic','wechatpay-signature':'a'.repeat(344)};
  assert.equal((await fetch(base+'/app/payment/v3/notify',{method:'POST',headers,body:raw})).status,204);
  assert.equal(received.length,1);assert.equal(received[0].body,raw);
  for(const name of ['wechatpay-timestamp','wechatpay-nonce','wechatpay-serial','wechatpay-signature']) assert.equal(received[0].headers[name],headers[name]);
  assert.equal((await fetch(base+'/app/payment/v3/notify')).status,405);
  assert.equal((await fetch(base+'/app/payment/v3/notify',{method:'POST',body:raw})).status,403);
  assert.equal((await fetch(base+'/app/payment/v3/notify',{method:'POST',headers,body:'x'.repeat(65537)})).status,413);
  assert.equal(received.length,1);
  assert.equal((await fetch(base+'/app/payment/v3/query',{method:'POST',body:'{}'})).status,403);
  const enter=await fetch(base+'/wechat-test/enter',{method:'POST',body:'code=synthetic-only',redirect:'manual'});
  const auth={cookie:enter.headers.get('set-cookie').split(';')[0]};
  assert.equal((await fetch(base+'/app/payment/toPay',{method:'POST',headers:auth,body:'{}'})).status,503);
  assert.equal((await fetch(base+'/app/payment/v3/query',{method:'POST',headers:auth,body:'{}'})).status,204);
  assert.equal((await fetch(base+'/app/payment/v3/close',{method:'POST',headers:auth,body:'{}'})).status,204);
  config.paymentEnabled=true;
  assert.equal((await fetch(base+'/app/payment/toPay',{method:'POST',headers:auth,body:'{}'})).status,204);
  const script=await(await fetch(base+'/wechat-test/config.js',{headers:auth})).text();
  assert.match(script,/"paymentEnabled":true/);
  assert.equal((await fetch(base+'/app/payment/v3/query',{headers:auth})).status,405);
  assert.equal((await fetch(base+'/app/payment/arbitrary',{method:'POST',headers:auth})).status,404);
  assert.equal((await fetch(base+'/paymentOrderApi/confirm',{method:'POST',headers:auth})).status,405);
});
