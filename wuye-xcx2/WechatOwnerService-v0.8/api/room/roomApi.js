/**
 * 房屋信息处理 api 接口类
 * 
 * add by 吴学文 QQ 928255095
 */


import {
	request
} from '../java110Request.js'
import
url
from '../../constant/url.js'

import
mapping
from '../../constant/MappingConstant.js'

import {
	getCurOwner
} from '../owner/ownerApi.js'
import directWechat from '../../auth/wechat-session.js';

/**
 * 查询业主房屋
 */
function queryOwnerRooms(owner) {
  return new Promise((resolve,reject)=>request({
    url:url.queryRoomsByOwner,method:'GET',
    data:{communityId:owner.communityId,ownerId:owner.ownerId},
    success(res){
      if(res.statusCode===200 && res.data && Array.isArray(res.data.rooms))resolve({...res.data,owner});
      else reject(new Error(res.data && res.data.msg || '房屋查询失败，请稍后重试'));
    },fail:reject
  }));
}
export function getRooms(requestedRoomId = '') {
  return getCurOwner().then(async owner=>{
    const result=await queryOwnerRooms(owner);
    if(requestedRoomId && !result.rooms.some(room=>room.roomId===requestedRoomId)) {
      // #ifdef H5
      const token=uni.getStorageSync(mapping.TOKEN);
      const alternatives=directWechat.owners(uni,mapping).filter(candidate=>directWechat.ownerKey(candidate)!==directWechat.ownerKey(owner));
      for(const candidate of alternatives){
        let other;
        try{other=await queryOwnerRooms(candidate);}catch(_){continue;}
        if(!other.rooms.some(room=>room.roomId===requestedRoomId))continue;
        const current=uni.getStorageSync(mapping.OWNER_INFO)||{};
        if(uni.getStorageSync(mapping.TOKEN)!==token || directWechat.ownerKey(current)!==directWechat.ownerKey(owner)){
          const changed=new Error('已切换房产，请刷新后查看');changed.code='OWNER_CHANGED';throw changed;
        }
        directWechat.selectOwner(directWechat.ownerKey(candidate),uni,mapping);
        return other;
      }
      // #endif
    }
    if(!result.rooms.length)throw new Error('当前已验证业主暂无房屋信息');
    return result;
  });
};
