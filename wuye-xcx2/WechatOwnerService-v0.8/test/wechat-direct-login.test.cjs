const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const session=require('../auth/wechat-session.js');
function runtime(){const storage=new Map();return {storage,getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,v),removeStorageSync:k=>storage.delete(k)};}
const mapping={OWNER_INFO:'owner',USER_INFO:'user',CURRENT_COMMUNITY_INFO:'community',LOGIN_FLAG:'loginFlag',TOKEN:'token',OWNER_KEY:'key'};
test('WeChat guest identity never creates an owner token or property identity',()=>{
 const r=runtime();r.storage.set('owner',{ownerId:'old'});r.storage.set('token','old-token');
 session.apply({code:0,hasBinding:false,owner:null,wechatSession:'guest-credential',expiresIn:1800},r,mapping,1000);
 assert.equal(session.isGuest(r,1001),true);assert.equal(r.storage.has('owner'),false);assert.equal(r.storage.has('token'),false);assert.equal(r.storage.has('loginFlag'),false);
});
test('successful verified binding writes the real owner session and retains WeChat identity',()=>{
 const r=runtime(),owner={ownerId:'owner-1',userId:'user-1',communityId:'community-1'};
 session.apply({code:0,hasBinding:true,owner,token:'owner-token',wechatSession:'bound-credential',expiresIn:1800},r,mapping,1000);
 assert.equal(session.isGuest(r,1001),false);assert.equal(session.hasIdentity(r,1001),true);assert.equal(r.storage.get('owner'),owner);assert.equal(r.storage.get('token'),'owner-token');
});
test('missing, failed and expired identity cannot claim a property binding',()=>{
 const r=runtime();
 assert.throws(()=>session.apply({code:403,msg:'mismatch'},r,mapping),/mismatch/);
 assert.throws(()=>session.apply({code:0,hasBinding:true,owner:{}},r,mapping),/登录|凭据/);
 session.apply({code:0,hasBinding:false,wechatSession:'credential',expiresIn:1},r,mapping,1000);
 assert.equal(session.hasIdentity(r,2001),false);
});
test('binding payload contains only three normalized fields and opaque session',()=>{
 const r=runtime();session.apply({code:0,hasBinding:false,wechatSession:'guest',expiresIn:1800},r,mapping);
 const payload=session.bindingPayload({name:' 业主乙 ',phone:' 13800000000 ',idCard:' 12345678901234567x ',ownerId:'other',openId:'forged'},r);
 assert.deepEqual(payload,{name:'业主乙',phone:'13800000000',idCard:'12345678901234567X',wechatSession:'guest'});
});
test('binding cannot submit empty or expired credentials',()=>{
 const r=runtime();assert.throws(()=>session.bindingPayload({name:'业主',phone:'13800000000',idCard:'1'},r),/微信|授权/);
});
test('new H5 binding form has no account password, SMS or location requirement',()=>{
 const source=fs.readFileSync(path.join(__dirname,'../pages/bindOwner/bindOwner.vue'),'utf8');
 const direct=source.match(/<!-- DIRECT-WECHAT-FORM -->[\s\S]*?<!-- END-DIRECT-WECHAT-FORM -->/);
 assert.ok(direct,'direct WeChat form exists');
 for(const field of ['directName','directPhone','directIdCard'])assert.ok(direct[0].includes(field));
 assert.doesNotMatch(direct[0],/password|msgCode|areaCode/);
 assert.match(source,/bindOwnerWechat/);
});
function load(file,names,dependencies){
 let source=fs.readFileSync(path.join(__dirname,'..',file),'utf8');
 const active=[true];source=source.split('\n').filter(line=>{const directive=line.match(/\/\/\s*#(ifdef|ifndef|endif)\s*(.*)/);if(directive){if(directive[1]==='endif')active.pop();else{const match=directive[2].split('||').some(s=>s.trim()==='H5');active.push(active.at(-1)&&(directive[1]==='ifdef'?match:!match));}return false;}return active.at(-1);}).join('\n');
 source=source.replace(/import\s[\s\S]*?from\s*['"][^'"]+['"];?/g,'').replace(/export function/g,'function');
 const sandbox={module:{exports:{}},console,directWechat:session,...dependencies};
 vm.runInNewContext(source+'\nmodule.exports={'+names.join(',')+'};',sandbox);return sandbox.module.exports;
}
test('OAuth guest ticket exchange completes without storing an invented owner or token',()=>{
 const r=runtime();r.showToast=()=>{};
 let finished=0,callback=0,cleanUrl='';
 const api=load('auth/AppLogin.js',['doLoginOwnerByKey'],{uni:r,wx:r,mapping,url:{loginOwnerByKey:'/app/loginOwnerByKey'},
  ownerSession:{begin:()=>()=>finished++},getWAppId:()=> 'wx-app',
  window:{location:{href:'https://h5.example/#/pages/index/index?authTicket=private-ticket'},history:{replaceState:(a,b,url)=>cleanUrl=url}},
  requestNoAuth:options=>options.success({statusCode:200,data:{code:0,hasBinding:false,owner:null,wechatSession:'guest-ticket',expiresIn:1800}})});
 api.doLoginOwnerByKey({authTicket:'one-time'},()=>callback++);
 assert.equal(finished,1);assert.equal(callback,1);assert.equal(session.isGuest(r),true);assert.equal(r.storage.has('owner'),false);assert.equal(r.storage.has('token'),false);assert.doesNotMatch(cleanUrl,/private-ticket|authTicket/);
});
test('guest protected request settles as binding-required without network or repeated OAuth',async()=>{
 const r=runtime();Object.assign(r,{showLoading(){},hideLoading(){},request(){throw new Error('guest must not fetch property data');}});
 session.apply({code:0,hasBinding:false,wechatSession:'guest',expiresIn:1800},r,mapping);
 let reauth=0,failure,complete=0;
 const api=load('api/java110Request.js',['request'],{uni:r,wx:r,mapping,app:{appId:'owner-client'},coreUtil:{wxuuid:()=> 'id'},getDateYYYYMMDDHHMISS:()=>'',
  localFeatures:{blockRequest:()=>false},url:{NEED_NOT_LOGIN_URL:[]},debug(){},checkSession:()=>Promise.reject(),wechatRefreshToken:()=>reauth++});
 api.request({url:'/app/room.queryRoomsByOwner',fail:error=>failure=error,complete:()=>complete++});await new Promise(setImmediate);
 assert.equal(failure.code,'BINDING_REQUIRED');assert.equal(complete,1);assert.equal(reauth,0);
});
test('guest current-owner lookup refuses stale cached owner and makes no query',async()=>{
 const r=runtime();session.apply({code:0,hasBinding:false,wechatSession:'guest',expiresIn:1800},r,mapping);r.storage.set('owner',{ownerId:'stale-owner'});
 let requests=0;
 const api=load('api/owner/ownerApi.js',['getCurOwner'],{uni:r,wx:r,mapping,ownerSession:{ready:()=>Promise.resolve()},request:()=>requests++});
 await assert.rejects(api.getCurOwner(),error=>error.code==='BINDING_REQUIRED');assert.equal(requests,0);
});
function oauthRuntime(ua){
 const r=runtime(),requests=[];r.showToast=()=>{};
 const window={location:{href:'https://h5.example/#/pages/roomFeeListNew/roomFeeListNew?roomId=room-2',hash:'#/pages/roomFeeListNew/roomFeeListNew?roomId=room-2'}};
 const api=load('auth/H5Login.js',['ensureWechatEntry','wechatRefreshToken'],{uni:r,window,navigator:{userAgent:ua},url:{wechatRefrashToken:'/app/refreshToken'},getWAppId:()=> 'wx-app',isNull:v=>v==null||v==='',returnTarget:require('../auth/return-target.js'),requestNoAuth:options=>requests.push(options)});
 return {r,requests,...api};
}
test('initial WeChat entry starts OAuth once and preserves the exact room destination',()=>{
 const api=oauthRuntime('MicroMessenger');api.ensureWechatEntry({});api.ensureWechatEntry({});
 assert.equal(api.requests.length,1);assert.match(api.r.storage.get('owner_return_target').route,/roomId=room-2/);assert.match(api.requests[0].data.errorUrl,/oauthError=1/);
});
test('valid guest entry stays logged into WeChat without reauthorizing or fetching rooms',()=>{
 const api=oauthRuntime('MicroMessenger');session.apply({code:0,hasBinding:false,wechatSession:'guest',expiresIn:1800},api.r,mapping);
 let ready=0;api.ensureWechatEntry({},()=>ready++);assert.equal(ready,1);assert.equal(api.requests.length,0);
});
test('desktop and denied OAuth return show guidance rather than an automatic authorization loop',()=>{
 for(const [ua,options] of [['Desktop',{}],['MicroMessenger',{oauthError:'1'}]]){
  const api=oauthRuntime(ua);let ready=0;api.ensureWechatEntry(options,()=>ready++);assert.equal(ready,1);assert.equal(api.requests.length,0);
 }
});
test('multiple verified owners can switch property context without replacing the user token',()=>{
 const r=runtime();
 const first={ownerId:'owner-1',memberId:'member-1',userId:'user-1',communityId:'community-1',communityName:'小区甲',state:'12000'};
 const second={ownerId:'owner-2',memberId:'member-2',userId:'user-1',communityId:'community-2',communityName:'小区乙',state:'12000'};
 session.apply({code:0,hasBinding:true,owner:second,owners:[first,second],token:'owner-token',wechatSession:'bound',expiresIn:1800},r,mapping);
 assert.equal(r.storage.get('owner').ownerId,'owner-2');
 session.selectOwner(session.ownerKey(first),r,mapping);
 assert.equal(r.storage.get('owner').ownerId,'owner-1');assert.equal(r.storage.get('community').communityId,'community-1');assert.equal(r.storage.get('token'),'owner-token');
 assert.equal(session.owners(r,mapping).length,2);
 assert.equal(r.storage.get('owner').ownerId,'owner-1','page reload reads persisted active owner');
});
test('unapproved and other-user owner descriptors cannot enter the switching list',()=>{
 const r=runtime();
 const selected={ownerId:'owner-1',memberId:'member-1',userId:'user-1',communityId:'community-1',state:'12000'};
 session.apply({code:0,hasBinding:true,owner:selected,owners:[selected,{...selected,memberId:'pending',state:'10000'},{...selected,memberId:'foreign',userId:'user-other'}],token:'token',wechatSession:'bound',expiresIn:1800},r,mapping);
 assert.equal(session.owners(r,mapping).length,1);
 assert.throws(()=>session.selectOwner('community-1:foreign',r,mapping),/绑定|身份/);
});
test('guest transition and identity expiry clear the saved owner switching list',()=>{
 const r=runtime();const selected={ownerId:'owner-1',memberId:'member-1',userId:'user-1',communityId:'community-1',state:'12000'};
 const bound={code:0,hasBinding:true,owner:selected,owners:[selected],token:'token',wechatSession:'bound',expiresIn:1};
 session.apply(bound,r,mapping,1000);assert.equal(session.owners(r,mapping,1001).length,1);
 assert.equal(session.owners(r,mapping,2001).length,0);assert.equal(r.storage.has(session.ownersKey),false);
 session.apply({...bound,expiresIn:1800},r,mapping);
 session.apply({code:0,hasBinding:false,wechatSession:'guest',expiresIn:1800},r,mapping);
 assert.equal(r.storage.has(session.ownersKey),false);
});
test('a notification room searches only verified owner records and selects its exact matching owner',async()=>{
 const r=runtime();
 const first={ownerId:'owner-1',memberId:'member-1',userId:'user-1',communityId:'community-1',state:'12000'},second={...first,ownerId:'owner-2',memberId:'member-2'};
 session.apply({code:0,hasBinding:true,owner:first,owners:[first,second],token:'token',wechatSession:'bound',expiresIn:1800},r,mapping);
 const calls=[];
 const api=load('api/room/roomApi.js',['getRooms'],{uni:r,mapping,url:{queryRoomsByOwner:'/app/rooms'},getCurOwner:async()=>r.storage.get('owner'),request:options=>{
  calls.push(options.data);options.success({statusCode:200,data:{rooms:[{roomId:options.data.ownerId==='owner-2'?'wanted-room':'other-room'}]}});
 }});
 const result=await api.getRooms('wanted-room');
 assert.equal(result.owner.ownerId,'owner-2');assert.equal(r.storage.get('owner').ownerId,'owner-2');assert.equal(calls.length,2);assert.ok(calls.every(call=>call.communityId==='community-1'));
});
test('unknown notification room leaves selected owner unchanged and never invents room access',async()=>{
 const r=runtime();const owner={ownerId:'owner-1',memberId:'member-1',userId:'user-1',communityId:'community-1',state:'12000'};
 session.apply({code:0,hasBinding:true,owner,owners:[owner],token:'token',wechatSession:'bound',expiresIn:1800},r,mapping);
 const api=load('api/room/roomApi.js',['getRooms'],{uni:r,mapping,url:{},getCurOwner:async()=>owner,request:options=>options.success({statusCode:200,data:{rooms:[{roomId:'owned-room'}]}})});
 const result=await api.getRooms('not-owned');assert.equal(result.rooms.some(room=>room.roomId==='not-owned'),false);assert.equal(r.storage.get('owner').ownerId,'owner-1');
});
