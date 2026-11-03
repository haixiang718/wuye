const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function load(file, names, dependencies) {
  let source = fs.readFileSync(path.join(__dirname, '..', file), 'utf8');
  const active = [true];
  source = source.split('\n').filter(line => {
    const directive = line.match(/\/\/\s*#(ifdef|ifndef|endif)\s*(.*)/);
    if (directive) {
      if (directive[1] === 'endif') active.pop();
      else {
        const matches = directive[2].split('||').some(item => item.trim() === 'H5');
        active.push(active.at(-1) && (directive[1] === 'ifdef' ? matches : !matches));
      }
      return false;
    }
    return active.at(-1);
  }).join('\n').replace(/import\s[\s\S]*?from\s*['"][^'"]+['"];?/g, '').replace(/export function/g, 'function');
  const sandbox = {module:{exports:{}}, console, ...dependencies};
  vm.runInNewContext(source + '\nmodule.exports={' + names.join(',') + '};', sandbox);
  return sandbox.module.exports;
}

function requestApi(checkSession = ()=>Promise.resolve()) {
  const requests = [], storage = new Map([['token', 'session-token']]), refreshed = [];
  const runtime = {getStorageSync:key=>storage.get(key),removeStorageSync:key=>storage.delete(key),setStorageSync:(key,value)=>storage.set(key,value), request:options=>requests.push(options), showLoading(){}, hideLoading(){},showToast(){}};
  const dependencies = {
    uni:runtime, wx:runtime, localFeatures:{blockRequest:()=>false}, mapping:{W_APP_ID:'wAppId',LOGIN_FLAG:'loginFlag',TOKEN:'token'},
    app:{appId:'owner-client'}, coreUtil:{wxuuid:()=> 'request-id'}, getDateYYYYMMDDHHMISS:()=> 'timestamp',
    checkSession, url:{NEED_NOT_LOGIN_URL:[]}, debug(){}, wechatRefreshToken:()=>refreshed.push(true),
    returnTarget:require('../auth/return-target.js'),window:{location:{hash:'#/pages/roomFeeListNew/roomFeeListNew?roomId=room-2'}}
  };
  return {...load('api/java110Request.js', ['request','requestNoAuth'], dependencies), requests, storage, refreshed};
}

for (const mode of ['request', 'requestNoAuth']) test(mode + ' merges required headers by name and preserves caller completion', async () => {
  const api = requestApi();
  let completed = 0;
  api[mode]({url:'/app/room.queryRoomsByOwner',method:'POST',header:{'Content-Type':'application/json'},complete:()=>completed++});
  await new Promise(setImmediate);
  const request = api.requests[0];
  assert.equal(request.header['app-id'], 'owner-client');
  assert.equal(request.header['Content-Type'], 'application/json');
  assert.equal(request.header.key, undefined);
  request.complete({statusCode:200});
  assert.equal(completed, 1);
});

test('server-expired session clears the token and starts reauthorization only once for concurrent requests', async () => {
  const api = requestApi();
  api.storage.set('loginFlag', {expireTime:Date.now()+60000});
  let responses = 0;
  for (let i=0;i<2;i++) api.request({url:'/app/rooms',success:()=>responses++});
  await new Promise(setImmediate);
  api.requests.forEach(options=>options.success({statusCode:401,data:{msg:'expired'}}));
  assert.equal(api.storage.get('token'), undefined);
  assert.equal(api.storage.get('loginFlag'), undefined);
  assert.equal(api.refreshed.length, 1);
  assert.equal(responses, 2);
});

test('local session expiry settles waiting callbacks before starting reauthorization', async () => {
  const api = requestApi(()=>Promise.reject(new Error('expired')));
  let failure, completed = 0;
  api.request({url:'/app/rooms',method:'POST',fail:error=>failure=error,complete:()=>completed++});
  await new Promise(setImmediate);
  assert.equal(api.requests.length, 0);
  assert.equal(failure.code, 'LOGIN_REQUIRED');
  assert.equal(completed, 1);
  assert.equal(api.refreshed.length, 1);
  assert.match(api.storage.get('owner_return_target').route, /roomId=room-2/);
});

test('an old unauthorized response cannot remove a newly established login token', async () => {
  const api=requestApi();
  api.request({url:'/app/rooms'});
  await new Promise(setImmediate);
  api.storage.set('token','new-session-token');
  api.requests[0].success({statusCode:401});
  assert.equal(api.storage.get('token'),'new-session-token');
  assert.equal(api.refreshed.length,0);
});

test('successful login still writes the real owner token and association', async () => {
  const storage = new Map();
  const owner = {userId:'user-1',communityId:'community-1',communityName:'小区'};
  const runtime = {showLoading(){},hideLoading(){},showToast(){},setStorageSync:(key,value)=>storage.set(key,value)};
  const api = load('api/user/userApi.js', ['userLogin'], {
    uni:runtime,wx:runtime,url:{},requestNoAuth:options=>options.success({statusCode:200,data:{owner,token:'real-owner-token'}}),
    require:()=>({constant:{mapping:{OWNER_INFO:'owner',USER_INFO:'user',CURRENT_COMMUNITY_INFO:'community',LOGIN_FLAG:'login',TOKEN:'token',OWNER_KEY:'key'}}})
  });
  await api.userLogin({});
  assert.equal(storage.get('owner'), owner);
  assert.equal(storage.get('token'), 'real-owner-token');
  assert.equal(storage.get('community').communityId, 'community-1');
});

test('network login failures show a retry message and reject the request', async () => {
  const messages = [];
  const runtime = {showLoading(){},hideLoading(){},showToast:options=>messages.push(options.title)};
  const api = load('api/user/userApi.js', ['userLogin'], {
    uni:runtime,wx:runtime,url:{},require:()=>({constant:{mapping:{}}}),requestNoAuth:options=>options.fail({errMsg:'offline'})
  });
  await assert.rejects(api.userLogin({}), /网络|重试/);
  assert.match(messages[0], /网络|重试/);
});

test('non-success owner response rejects promptly instead of leaving dependent pages loading', async () => {
  const api = load('api/owner/ownerApi.js', ['getCurOwner'], {
    ownerSession:{ready:()=>Promise.resolve()},wx:{getStorageSync:()=>null},mapping:{OWNER_INFO:'owner'},
    url:{queryAppUserBindingOwner:'/app/owners'},request:options=>options.success({statusCode:403,data:{code:403,msg:'业主绑定尚未审核'}})
  });
  const pending = api.getCurOwner();
  await assert.rejects(Promise.race([pending,new Promise((resolve,reject)=>setTimeout(()=>reject(new Error('request never settled')),30))]), /尚未审核/);
});

test('owner lookup rejection is propagated by room lookup rather than leaving it pending', async () => {
  const problem = new Error('binding pending');
  const api = load('api/room/roomApi.js', ['getRooms'], {getCurOwner:()=>Promise.reject(problem)});
  await assert.rejects(api.getRooms(), error=>error===problem);
});

test('malformed room response is rejected with a useful error', async () => {
  const api = load('api/room/roomApi.js', ['getRooms'], {
    getCurOwner:()=>Promise.resolve({ownerId:'owner-1',communityId:'community-1'}),
    url:{queryRoomsByOwner:'/app/rooms'},
    request:options=>options.success({statusCode:200,data:{code:403,msg:'无权查询此房屋'}})
  });
  await assert.rejects(api.getRooms(), /无权查询此房屋/);
});

test('business login rejection does not write a fake session or crash on missing owner', async () => {
  const writes = [], messages = [];
  const runtime = {showLoading(){},hideLoading(){},showToast:options=>messages.push(options.title),setStorageSync:(...args)=>writes.push(args)};
  const api = load('api/user/userApi.js', ['userLogin'], {
    uni:runtime,wx:runtime,require:()=>({constant:{mapping:{}}}),url:{loginOwnerUrl:'/app/loginOwner'},
    requestNoAuth:options=>options.success({statusCode:200,data:{code:401,msg:'账号或密码错误'}})
  });
  await assert.rejects(api.userLogin({username:'owner',password:'wrong'}), /账号或密码错误/);
  assert.deepEqual(writes, []);
  assert.equal(messages[0], '账号或密码错误');
});
