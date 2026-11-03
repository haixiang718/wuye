'use strict';
const identityKey='owner_wechat_identity';
const ownersKey='owner_verified_bindings';
function read(runtime,now=Date.now()) {
  const identity=runtime && runtime.getStorageSync && runtime.getStorageSync(identityKey);
  if(identity && Number.isFinite(identity.expiresAt) && identity.expiresAt<=now)clear(runtime);
  return identity && typeof identity.wechatSession==='string' && identity.wechatSession.length>0
    && Number.isFinite(identity.expiresAt) && identity.expiresAt>now ? identity : null;
}
function hasIdentity(runtime,now){return !!read(runtime,now);}
function isGuest(runtime,now){const identity=read(runtime,now);return !!identity && identity.hasBinding===false;}
function clear(runtime){if(runtime && runtime.removeStorageSync){runtime.removeStorageSync(identityKey);runtime.removeStorageSync(ownersKey);}}
function ownerKey(owner){return String(owner.communityId||'')+':'+String(owner.memberId||owner.ownerId||'');}
function descriptors(list,userId){
  const seen=new Set();
  return (Array.isArray(list)?list:[]).filter(owner=>{
    if(!owner || owner.userId!==userId || owner.state!=='12000' || !owner.ownerId || !owner.communityId)return false;
    const key=ownerKey(owner);if(seen.has(key))return false;seen.add(key);return true;
  }).map(owner=>{
    const clean={};
    ['appUserId','ownerId','memberId','userId','communityId','communityName','appUserName','state','appType','nickName','headImgUrl'].forEach(key=>{if(owner[key]!=null)clean[key]=owner[key];});
    return clean;
  });
}
function owners(runtime,mapping,now=Date.now()){
  const identity=read(runtime,now),active=runtime.getStorageSync(mapping.OWNER_INFO);
  if(!identity || identity.hasBinding!==true || !active || !active.userId)return [];
  return descriptors(runtime.getStorageSync(ownersKey),active.userId);
}
function writeOwner(owner,runtime,mapping){
  runtime.setStorageSync(mapping.OWNER_INFO,owner);
  runtime.setStorageSync(mapping.USER_INFO,JSON.stringify(owner));
  runtime.setStorageSync(mapping.CURRENT_COMMUNITY_INFO,{communityId:owner.communityId,communityName:owner.communityName});
}
function selectOwner(key,runtime,mapping){
  const owner=owners(runtime,mapping).find(owner=>ownerKey(owner)===key);
  if(!owner)throw new Error('未找到已验证的房产绑定身份，请重新授权');
  writeOwner(owner,runtime,mapping);
  if(runtime.$emit)runtime.$emit('owner-wechat-session-changed');
  return owner;
}
function apply(payload,runtime,mapping,now=Date.now()) {
  if(!payload || payload.code!=null && payload.code!=0)throw new Error(payload && payload.msg || '微信登录失败，请重新授权');
  const guest=payload.hasBinding===false;
  if(guest && (!payload.wechatSession || typeof payload.wechatSession!=='string'))throw new Error('微信授权凭据缺失，请重新授权');
  if(!guest && (!payload.owner || !payload.owner.userId || !payload.token))throw new Error('登录凭据缺失，请重新授权');
  const verified=guest?[]:descriptors(payload.owners || [payload.owner],payload.owner.userId);
  let owner=payload.owner;
  if(!guest && Array.isArray(payload.owners)){
    owner=verified.find(candidate=>ownerKey(candidate)===ownerKey(payload.owner));
    if(!owner)throw new Error('登录房产绑定身份无效，请重新授权');
  }
  const seconds=Number(payload.expiresIn);
  if(payload.wechatSession)runtime.setStorageSync(identityKey,{wechatSession:payload.wechatSession,hasBinding:!guest,expiresAt:now+(Number.isFinite(seconds)&&seconds>0?Math.min(seconds,7200):1800)*1000});
  else clear(runtime);
  const propertyKeys=[mapping.OWNER_INFO,mapping.USER_INFO,mapping.CURRENT_COMMUNITY_INFO,mapping.LOGIN_FLAG,mapping.TOKEN,mapping.OWNER_KEY,ownersKey].filter(Boolean);
  propertyKeys.forEach(key=>runtime.removeStorageSync(key));
  if(guest){if(runtime.$emit)runtime.$emit('owner-wechat-session-changed');return false;}
  writeOwner(owner,runtime,mapping);
  if(verified.length)runtime.setStorageSync(ownersKey,verified);
  runtime.setStorageSync(mapping.LOGIN_FLAG,{sessionKey:owner.userId,expireTime:now+60*60*1000});
  runtime.setStorageSync(mapping.TOKEN,payload.token);
  if(runtime.$emit)runtime.$emit('owner-wechat-session-changed');
  return true;
}
function bindingPayload(fields,runtime) {
  const identity=read(runtime);
  if(!identity)throw new Error('微信身份已过期，请重新授权');
  const name=String(fields.name||'').trim(),phone=String(fields.phone||'').trim(),idCard=String(fields.idCard||'').trim().toUpperCase();
  if(!name)throw new Error('请填写业主姓名');
  if(!/^1\d{10}$/.test(phone))throw new Error('请填写正确的手机号');
  if(!/^(?:\d{15}|\d{17}[\dX])$/.test(idCard))throw new Error('请填写正确的身份证号');
  return {wechatSession:identity.wechatSession,name,phone,idCard};
}
module.exports={read,hasIdentity,isGuest,clear,apply,bindingPayload,identityKey,ownersKey,owners,ownerKey,selectOwner};
