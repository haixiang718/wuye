/**
 * 首页相关 数据封装API
 * add by 吴学文 2020-09-07
 * QQ 92825595
 * 
 */

import {
	request
} from '../java110Request.js'
import 
	url
 from '../../constant/url.js'

import mapping from '../../constant/MappingConstant.js'
import ownerSession from '../../auth/owner-session.js';
import directWechat from '../../auth/wechat-session.js';

/**
 * 查询当前业主信息
 */
export function getCurOwner() {
	return ownerSession.ready().then(() => new Promise(
		(resolve, reject) => {
			// #ifdef H5
			if (directWechat.isGuest(uni)) {
				const error = new Error('请先绑定房产后查看');error.code='BINDING_REQUIRED';reject(error);return;
			}
			// #endif
			let _ownerInfo = wx.getStorageSync(mapping.OWNER_INFO);
			if (_ownerInfo) {
				resolve(_ownerInfo);
			} else {
				request({
					url: url.queryAppUserBindingOwner,
					data: {},
					success: function(res) {
						let _json = res.data || {};
						if (res.statusCode === 200 && _json.code == 0 && Array.isArray(_json.data)) {
							_ownerInfo = _json.data[0];
							if (_ownerInfo == null || _ownerInfo == undefined) {
								//没有业主信息
								reject(new Error('尚未绑定业主，请先绑定并等待物业审核'));
								return;
							}
							if (_ownerInfo.state == '12000') {
								wx.setStorageSync(mapping.OWNER_INFO, _ownerInfo);
								let _currentCommunityInfo = {
									communityId: _ownerInfo.communityId,
									communityName: _ownerInfo.communityName
								};
								wx.setStorageSync(mapping.CURRENT_COMMUNITY_INFO, _currentCommunityInfo);
							}
							resolve(_json.data[0]);
							return;
						}
						reject(new Error(_json.msg || '业主信息加载失败，请稍后重试'));
					},
					fail: function(error) {
						// 查询失败
						reject();
					}
				});
			}
		}));
}


/**
 * add by wuxw
 * @param {Object} _data 删除家庭成员
 */
export function deleteOwnerMember(_data){
	
	return new Promise((resolve, reject) => {
		
		if(_data.memberId == ''){
			reject("成员ID不能为空");
			return ;
		}else if(_data.communityId == ''){
			reject("小区不能为空");
			return ;
		}
		request({
			url: url.deleteOwner,
			method: "POST",
			data: _data, //动态数据
			success: function(res) {
				let _data = res.data;
				if (_data.code == 0) {
					resolve(_data);
					return ;
				}
				reject(_data.msg);
			},
			fail: function(e) {
				reject(e);
			}
		});
	})
}
