const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function page(smsEnabled, file='register/register') {
  const requests = [], messages = [], redirects = [], logs = [];
  const source = fs.readFileSync(path.join(__dirname,'../pages/'+file+'.vue'),'utf8').match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/import\s[\s\S]*?from\s*['"][^'"]+['"];?/g,'').replace('export default','module.exports =');
  const context = {constant:{mapping:{CURRENT_OPEN_ID:'open'},url:{ownerRegiter:'/app/register',appUserBindingOwner:'/app/bind'}},getHeaders:()=>({}),request:options=>requests.push(options)};
  const runtime = {getStorageSync:()=>'',showToast:options=>messages.push(options.title),redirectTo:options=>redirects.push(options.url),showLoading(){},hideLoading(){},request:options=>requests.push(options)};
  const sandbox = {module:{exports:{}},require:()=>context,pickerAddress:{},uni:runtime,wx:runtime,console:{log:(...args)=>logs.push(args)}};
  vm.runInNewContext(source,sandbox);
  const definition=sandbox.module.exports;
  return {component:{...definition.data(),...definition.methods,smsEnabled,areaCode:'340102',communityId:'community-1',communityName:'小区',appUserName:'业主',idCard:'340102199001010000',link:'13800000000',password:'password',rePassword:'password'},requests,messages,redirects,logs};
}

test('SMS-off registration submits an empty code for server-controlled approval without a fake code', () => {
  const p = page(false);p.component._doRegister();
  assert.equal(p.requests.length,1);
  assert.equal(p.requests[0].data.msgCode,'');
});

test('binding business rejection cannot redirect to a successful binding page',()=>{
  const p=page(false,'bindOwner/bindOwner');p.component.bindOwner();
  p.requests[0].success({statusCode:200,data:{code:403,msg:'绑定资料未通过核验'}});
  assert.equal(p.redirects.length,0);assert.equal(p.messages[0],'绑定资料未通过核验');
});

test('binding input and submit do not log owner personal information',()=>{
  const p=page(false,'bindOwner/bindOwner');
  p.component.bindInput({currentTarget:{dataset:{name:'link'}},detail:{value:'13800000000'}});
  p.component.bindOwner();
  assert.equal(p.logs.length,0);
});

test('SMS-off existing-account binding submits no fake code and SMS-on still requires verification', () => {
  const disabled=page(false,'bindOwner/bindOwner');disabled.component.bindOwner();
  assert.equal(disabled.requests.length,1);assert.equal(disabled.requests[0].data.msgCode,'');
  const enabled=page(true,'bindOwner/bindOwner');enabled.component.bindOwner();
  assert.equal(enabled.requests.length,0);assert.match(enabled.messages[0],/验证码/);
  disabled.component.sendMsgCode();assert.equal(disabled.requests.length,1);assert.match(disabled.messages[0],/未开通/);
});

test('SMS-on registration still requires a code and SMS-off send cannot contact the provider', () => {
  const enabled=page(true);enabled.component._doRegister();
  assert.equal(enabled.requests.length,0);assert.match(enabled.messages[0],/验证码/);
  const disabled=page(false);disabled.component.sendMsgCode();
  assert.equal(disabled.requests.length,0);assert.match(disabled.messages[0],/未开通/);
});
