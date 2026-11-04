const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname, '../factory/WexinPayFactory.js'), 'utf8');
const payment = { orderId: 'order-1', appId: 'wx-app', timeStamp: '123', nonceStr: 'nonce', package: 'prepay_id=test', signType: 'RSA', sign: 'rsa-sign' };
function setup(options = {}) {
  const storage = options.storage || new Map([['wAppId', 'wx-app'], ['userInfo', JSON.stringify({userId:'user-1'})]]);
  const timers = new Map(), listeners = new Map(), calls = [], requests = [], toasts = [], modals = [];
  let timerId = 0;
  const bridge = {invoke(method, args, done) { calls.push({method,args,done}); }};
  const window = {WeixinJSBridge: options.noBridge ? undefined : bridge};
  const context = {
    constant: {mapping:{W_APP_ID:'wAppId',USER_INFO:'userInfo'},url:{preOrder:'https://example.invalid/api/app/payment/toPay'}},
    getUserInfo: () => JSON.parse(storage.get('userInfo') || '{}'),
    getHeaders: () => ({cookie:'test-session'}),
    request(req) {requests.push(req); if (options.respond) options.respond(req, requests.length);}
  };
  const sandbox = {module:{exports:{}},require(name){if(name.includes('Java110Context'))return context; return {config(){},ready(){},error(){}};},
    window, navigator:{userAgent: options.outside ? 'Chrome' : 'MicroMessenger'},
    document:{addEventListener(name,fn){listeners.set(name,fn);},removeEventListener(name,fn){if(listeners.get(name)===fn)listeners.delete(name);}},
    uni:{getStorageSync:key=>storage.get(key),setStorageSync:(key,value)=>storage.set(key,value),removeStorageSync:key=>storage.delete(key),showToast:toast=>toasts.push(toast),
      showModal(modal){modals.push(modal); if(options.modal)options.modal(modal);}},
    setTimeout(fn,ms){const id=++timerId;timers.set(id,{fn,ms});return id;},clearTimeout(id){timers.delete(id);},console
  };
  vm.runInNewContext(source, sandbox);
  return {factory:sandbox.module.exports,storage,timers,listeners,calls,requests,toasts,modals,window,bridge,
    async flush(){for(let i=0;i<15;i++)await Promise.resolve();},
    async tick(ms){for(const [id,timer] of [...timers])if(timer.ms===ms){timers.delete(id);timer.fn();}for(let i=0;i<15;i++)await Promise.resolve();}};
}
function answer(req, state, orderId='order-1') {req.success({statusCode:200,data:{code:0,orderId,state,providerState:state==='POSTED'?'SUCCESS':'NOTPAY'}});}

test('uses native RSA bridge parameters; only server POSTED triggers success', async () => {
  const s=setup(); let success=0;
  const promise=s.factory.wexinPay(payment,()=>success++);
  assert.equal(typeof promise?.then,'function'); await s.flush();
  assert.equal(s.calls[0]?.method,'getBrandWCPayRequest');
  assert.deepEqual(JSON.parse(JSON.stringify(s.calls[0].args)),{appId:'wx-app',timeStamp:'123',nonceStr:'nonce',package:'prepay_id=test',signType:'RSA',paySign:'rsa-sign'});
  s.calls[0].done({err_msg:'get_brand_wcpay_request:ok'}); await s.flush();
  assert.equal(success,0); assert.equal(s.requests[0].method,'POST');
  assert.equal(s.requests[0].url,'https://example.invalid/api/app/payment/v3/query');
  assert.equal(s.requests[0].header.cookie,'test-session'); assert.equal(s.requests[0].data.orderId,'order-1');
  answer(s.requests[0],'POSTED'); await promise; assert.equal(success,1);
});

test('polls CREATED with bounded retries and leaves a recovery order', async () => {
  const s=setup({respond:req=>answer(req,'CREATED')}); let success=0;
  const promise=s.factory.wexinPay(payment,()=>success++); await s.flush();
  s.calls[0].done({err_msg:'get_brand_wcpay_request:ok'}); await s.flush();
  for(let i=0;i<4;i++)await s.tick(1500);
  const result=await promise; assert.equal(result.state,'CREATED'); assert.equal(s.requests.length,5); assert.equal(success,0);
  assert.equal(s.toasts.at(-1).title,'支付结果确认中，请稍后刷新账单');
  assert.ok([...s.storage.values()].some(v=>v?.orderId==='order-1'));
});

test('cancel still queries and reports success when already POSTED', async () => {
  const s=setup({respond:req=>answer(req,'POSTED')}); let success=0;
  const promise=s.factory.wexinPay(payment,()=>success++); await s.flush();
  s.calls[0].done({err_msg:'get_brand_wcpay_request:cancel'}); await promise;
  assert.equal(success,1); assert.equal(s.requests.length,1); assert.equal(s.toasts.length,0);
});

test('cancel with unpaid server state only reports cancellation and retains recovery', async () => {
  const s=setup({respond:req=>answer(req,'CREATED')}); let success=0;
  const promise=s.factory.wexinPay(payment,()=>success++); await s.flush();
  s.calls[0].done({err_msg:'get_brand_wcpay_request:cancel'}); await promise;
  assert.equal(success,0); assert.equal(s.requests.length,1); assert.equal(s.toasts.at(-1).title,'已取消支付');
  assert.ok([...s.storage.values()].some(v=>v?.orderId==='order-1'));
});

test('query failure never reports success or cancellation', async () => {
  const s=setup({respond:req=>req.fail({errMsg:'offline'})}); let success=0;
  const promise=s.factory.wexinPay(payment,()=>success++); await s.flush();
  s.calls[0].done({err_msg:'get_brand_wcpay_request:cancel'}); await promise;
  assert.equal(success,0); assert.equal(s.toasts.at(-1).title,'支付结果确认中，请稍后刷新账单');
});

test('explicit bridge fail with authenticated CREATED persists a safe error modal and recovery',async()=>{
  const s=setup({respond:req=>answer(req,'CREATED')}); let success=0;
  const promise=s.factory.wexinPay(payment,()=>success++); await s.flush();
  const err_msg='get_brand_wcpay_request:fail '+ '支付校验失败\u0001'.repeat(40);
  s.calls[0].done({err_msg,debug:'DO_NOT_DISPLAY',sign:'PRIVATE_SIGNATURE'}); const result=await promise;
  assert.equal(result.state,'CREATED'); assert.equal(success,0); assert.equal(s.modals.length,1);
  const modal=s.modals[0]; assert.equal(modal.title,'微信未完成支付'); assert.equal(modal.showCancel,false);
  assert.match(modal.content,/尚未确认收款/); assert.match(modal.content,/关闭待支付订单.*重试/);
  const detail=modal.content.split('\n')[0].replace('微信提示：','');
  assert.ok(detail.length<=160); assert.match(detail,/get_brand_wcpay_request:fail/); assert.doesNotMatch(detail,/[\u0000-\u001f\u007f]/);
  assert.doesNotMatch(modal.content,/DO_NOT_DISPLAY|PRIVATE_SIGNATURE|rsa-sign|prepay_id=test/);
  assert.ok([...s.storage.values()].some(v=>v?.orderId==='order-1')); assert.equal(s.timers.size,0);
});

test('bridge fail cannot show the failure modal when server already POSTED',async()=>{
  const s=setup({respond:req=>answer(req,'POSTED')}); let success=0;
  const promise=s.factory.wexinPay(payment,()=>success++); await s.flush();
  s.calls[0].done({err_msg:'get_brand_wcpay_request:fail'}); await promise;
  assert.equal(success,1); assert.equal(s.modals.length,0);
});

test('bridge fail with failed query retains pending without showing an unpaid failure modal',async()=>{
  const s=setup({respond:req=>req.fail({errMsg:'offline'})}); let success=0;
  const promise=s.factory.wexinPay(payment,()=>success++); await s.flush();
  s.calls[0].done({err_msg:'get_brand_wcpay_request:fail'}); await promise;
  assert.equal(success,0); assert.equal(s.modals.length,0); assert.equal(s.toasts.at(-1).title,'支付结果确认中，请稍后刷新账单');
  assert.ok([...s.storage.values()].some(v=>v?.orderId==='order-1'));
});

test('cancel and timeout with CREATED never show an explicit failure modal',async()=>{
  for(const message of ['get_brand_wcpay_request:cancel','bridge:timeout','bridge:fail']){
    const s=setup({respond:req=>answer(req,'CREATED')}); const promise=s.factory.wexinPay(payment); await s.flush();
    s.calls[0].done({err_msg:message}); await promise; assert.equal(s.modals.length,0);
  }
});

test('mismatched order or unauthenticated query cannot confirm payment', async () => {
  for(const response of [{statusCode:200,data:{code:0,orderId:'other',state:'POSTED'}},{statusCode:401,data:{code:0,orderId:'order-1',state:'POSTED'}}]){
    const s=setup({respond:req=>req.success(response)}); let success=0;
    const promise=s.factory.wexinPay(payment,()=>success++); await s.flush(); s.calls[0].done({err_msg:'fail'}); await promise; assert.equal(success,0);
  }
});

test('waits for bridge ready and cleans up the listener', async () => {
  const s=setup({noBridge:true,respond:req=>answer(req,'POSTED')});
  const promise=s.factory.wexinPay(payment); await s.flush(); assert.equal(s.calls.length,0);
  assert.ok(s.listeners.has('WeixinJSBridgeReady')); s.window.WeixinJSBridge=s.bridge; s.listeners.get('WeixinJSBridgeReady')(); await s.flush();
  assert.equal(s.listeners.size,0); s.calls[0].done({err_msg:'ok'}); await promise;
});

test('bridge readiness times out, cleans listeners, queries once and settles', async () => {
  const s=setup({noBridge:true,respond:req=>answer(req,'CREATED')});
  const promise=s.factory.wexinPay(payment); await s.flush(); await s.tick(8000); await promise;
  assert.equal(s.listeners.size,0); assert.equal(s.requests.length,1); assert.equal(s.calls.length,0);
});

test('outside WeChat returns a Promise with an actionable message', async () => {
  const s=setup({outside:true}); const result=await s.factory.wexinPay(payment);
  assert.equal(s.calls.length,0); assert.equal(result.state,'UNAVAILABLE'); assert.match(s.toasts.at(-1).title,/微信/);
});

test('duplicate taps share one operation and one bridge invocation', async () => {
  const s=setup({respond:req=>answer(req,'POSTED')}); let success=0;
  const a=s.factory.wexinPay(payment,()=>success++), b=s.factory.wexinPay(payment,()=>success++);
  assert.equal(a,b); await s.flush(); assert.equal(s.calls.length,1); s.calls[0].done({err_msg:'get_brand_wcpay_request:ok'}); await a; assert.equal(success,1);
});

test('saved pending recovers without opening the bridge; user and app are isolated', async () => {
  const s=setup({respond:req=>answer(req,'CREATED')}); const promise=s.factory.wexinPay(payment); await s.flush(); s.calls[0].done({err_msg:'cancel'}); await promise;
  const reload=setup({storage:s.storage,respond:req=>answer(req,'POSTED')});
  reload.storage.set('userInfo',JSON.stringify({userId:'user-2'})); assert.equal((await reload.factory.resumePending()).state,'NONE'); assert.equal(reload.requests.length,0);
  reload.storage.set('userInfo',JSON.stringify({userId:'user-1'})); reload.storage.set('wAppId','other-app'); assert.equal((await reload.factory.resumePending()).state,'NONE');
  reload.storage.set('wAppId','wx-app'); let success=0; await reload.factory.resumePending(()=>success++);
  assert.equal(success,1); assert.equal(reload.calls.length,0); assert.equal(reload.requests.length,1);
  assert.equal((await reload.factory.resumePending()).state,'NONE');
});

test('account change during payment prevents querying or success for the previous account', async () => {
  const s=setup({respond:req=>answer(req,'POSTED')}); let success=0;
  const promise=s.factory.wexinPay(payment,()=>success++); await s.flush(); s.storage.set('userInfo',JSON.stringify({userId:'user-2'}));
  s.calls[0].done({err_msg:'get_brand_wcpay_request:ok'}); await promise; assert.equal(success,0); assert.equal(s.requests.length,0);
});

test('RSA payment without orderId fails closed; legacy non-RSA bridge success remains supported', async () => {
  const s=setup(); let success=0; const invalid={...payment}; delete invalid.orderId;
  await s.factory.wexinPay(invalid,()=>success++); assert.equal(success,0); assert.equal(s.calls.length,0);
  const legacy=s.factory.wexinPay({...invalid,signType:'MD5'},()=>success++); await s.flush();
  s.calls[0].done({err_msg:'get_brand_wcpay_request:ok'}); await legacy; assert.equal(success,1); assert.equal(s.requests.length,0);
});

test('identity change while waiting for bridge prevents opening the previous user payment', async () => {
  const s=setup({noBridge:true}); const promise=s.factory.wexinPay(payment); await s.flush();
  s.storage.set('userInfo',JSON.stringify({userId:'user-2'})); s.window.WeixinJSBridge=s.bridge;
  s.listeners.get('WeixinJSBridgeReady')(); await s.flush();
  assert.equal(s.calls.length,0); await promise;
});

test('query wrapper that never calls back settles after timeout without false success', async () => {
  const s=setup(); let success=0; const promise=s.factory.wexinPay(payment,()=>success++); await s.flush();
  s.calls[0].done({err_msg:'get_brand_wcpay_request:ok'}); await s.flush(); await s.tick(12000); await promise;
  assert.equal(success,0); assert.equal(s.toasts.at(-1).title,'支付结果确认中，请稍后刷新账单');
});

async function saveUnpaid(s) {
  const promise=s.factory.wexinPay(payment); await s.flush(); s.calls[0].done({err_msg:'cancel'}); await promise;
}
async function advanceConfirmation(s) {
  await s.flush(); for(let i=0;i<4;i++)await s.tick(1500);
}

test('explicit payment preparation offers closing an unpaid order; refusal retains it',async()=>{
  const s=setup({respond:req=>answer(req,'CREATED'),modal:modal=>modal.success({confirm:false})});
  assert.equal(typeof s.factory.preparePayment,'function'); await saveUnpaid(s);
  const resultPromise=s.factory.preparePayment(); await advanceConfirmation(s); const result=await resultPromise;
  assert.equal(result.state,'CREATED'); assert.equal(s.modals.length,1); assert.equal(s.modals[0].confirmText,'关闭待支付订单');
  assert.match(s.modals[0].content,/不会扣款.*退款/); assert.equal(s.requests.filter(r=>r.url.endsWith('/close')).length,0);
  assert.ok([...s.storage.values()].some(v=>v?.orderId==='order-1'));
});

for(const closeState of ['CREATED','CLOSED','POSTED']) {
  test(`explicit close ${closeState} preserves authoritative state and recovery`,async()=>{
    const s=setup({respond:req=>answer(req,req.url.endsWith('/close')?closeState:'CREATED'),modal:modal=>modal.success({confirm:true})});
    await saveUnpaid(s); const resultPromise=s.factory.preparePayment(); await advanceConfirmation(s); const result=await resultPromise;
    const closing=s.requests.filter(r=>r.url.endsWith('/close')); assert.equal(closing.length,1);
    assert.equal(closing[0].url,'https://example.invalid/api/app/payment/v3/close'); assert.equal(closing[0].method,'POST');
    assert.equal(closing[0].data.orderId,'order-1'); assert.equal(closing[0].header.cookie,'test-session'); assert.equal(result.state,closeState);
    assert.equal([...s.storage.values()].some(v=>v?.orderId==='order-1'),closeState==='CREATED');
    if(closeState==='CREATED')assert.equal(s.toasts.at(-1).title,'支付结果确认中，请稍后刷新账单');
  });
}

test('close timeout retains pending recovery and cannot permit a new order',async()=>{
  const s=setup({respond:req=>{if(!req.url.endsWith('/close'))answer(req,'CREATED');},modal:modal=>modal.success({confirm:true})});
  await saveUnpaid(s); const promise=s.factory.preparePayment(); await advanceConfirmation(s); await s.tick(12000);
  assert.equal((await promise).state,'PENDING'); assert.ok([...s.storage.values()].some(v=>v?.orderId==='order-1'));
});

test('identity change while close confirmation is open cannot close the previous account order',async()=>{
  const s=setup({respond:req=>answer(req,'CREATED')}); await saveUnpaid(s);
  const promise=s.factory.preparePayment(); await advanceConfirmation(s); s.storage.set('userInfo',JSON.stringify({userId:'user-2'}));
  s.modals[0].success({confirm:true}); assert.equal((await promise).state,'IDENTITY_CHANGED');
  assert.equal(s.requests.filter(r=>r.url.endsWith('/close')).length,0);
});

test('passive page recovery never offers or requests closing unpaid orders',async()=>{
  const s=setup({respond:req=>answer(req,'CREATED')}); await saveUnpaid(s);
  const promise=s.factory.resumePending(); await advanceConfirmation(s); await promise;
  assert.equal(s.modals.length,0); assert.equal(s.requests.filter(r=>r.url.endsWith('/close')).length,0);
});

function pageHarness(name, resumeResult={state:'NONE'}) {
  let code=fs.readFileSync(path.resolve(__dirname,`../pages/${name}/${name}.vue`),'utf8').split('<script>')[1].split('</script>')[0];
  const conditions=[];
  code=code.split('\n').filter(line=>{
    if(line.includes('// #ifdef')){conditions.push(line.includes('H5'));return false;}
    if(line.includes('// #ifndef')){conditions.push(!line.includes('H5'));return false;}
    if(line.includes('// #endif')){conditions.pop();return false;}
    return conditions.every(Boolean);
  }).join('\n');
  code=code.replace(/import[\s\S]*?from\s*['"][^'"]+['"]/g,'').replace('export default','module.exports =');
  let pendingResolve, requests=0, recoveries=0, preparations=0;
  const factory={resumePending(){recoveries++;return Promise.resolve(resumeResult);},preparePayment(){recoveries++;preparations++;return Promise.resolve(resumeResult);},wexinPay(){return new Promise(resolve=>{pendingResolve=resolve;});}};
  const context={constant:{url:{preOrder:'/preOrder',toOweFeePay:'/owe'},mapping:{}},getHeaders(){return {};},request(req){requests++;req.success({statusCode:200,data:{code:0,...payment}});}};
  const sandbox={module:{exports:{}},require:name=>name.includes('WexinPayFactory')?factory:context,vcDiscount:{},Promise,
    navigator:{userAgent:'MicroMessenger'},uni:{showToast(){},navigateBack(){}},wx:{showLoading(){},hideLoading(){}}};
  vm.runInNewContext(code,sandbox);
  const definition=sandbox.module.exports;
  const page={...definition.data(),...definition.methods,paymentEnabled:true,_canPayFee:()=>true,appId:'wx-app'};
  return {page,definition,requests:()=>requests,recoveries:()=>recoveries,preparations:()=>preparations,finish:()=>pendingResolve({state:'POSTED'}),async flush(){for(let i=0;i<20;i++)await Promise.resolve();}};
}

for(const name of ['roomFee','roomOweFee']) {
  test(`${name} rejects disabled payment before recovery or preorder`,async()=>{
    const s=pageHarness(name); s.page.paymentEnabled=false; s.page._canPayFee=()=>false;
    await s.page.onPayFee(); assert.equal(s.requests(),0); assert.equal(s.recoveries(),0);
  });
  test(`${name} disables duplicate preorder taps until confirmation finishes`,async()=>{
    const s=pageHarness(name); const first=s.page.onPayFee(); s.page.onPayFee(); await s.flush();
    assert.equal(s.requests(),1); assert.equal(s.page.paymentSubmitting,true);
    s.finish(); await first; assert.equal(s.page.paymentSubmitting,false);
  });
  test(`${name} checks recovery on show and before creating another order`,async()=>{
    const s=pageHarness(name,{state:'CREATED'});
    assert.equal(typeof s.definition.onShow,'function'); await s.definition.onShow.call(s.page);
    await s.page.onPayFee(); assert.equal(s.requests(),0); assert.equal(s.recoveries(),2);
    assert.equal(s.preparations(),1);
  });
  test(`${name} permits a new preorder only after explicit preparation returns CLOSED`,async()=>{
    const s=pageHarness(name,{state:'CLOSED'}); const promise=s.page.onPayFee(); await s.flush();
    assert.equal(s.preparations(),1); assert.equal(s.requests(),1); s.finish(); await promise;
  });
  test(`${name} payment winning the close race does not create another preorder`,async()=>{
    const s=pageHarness(name,{state:'POSTED'}); await s.page.onPayFee(); assert.equal(s.requests(),0); assert.equal(s.preparations(),1);
  });
}
