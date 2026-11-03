import url from '../constant/url.js'
import {
	requestNoAuth
} from '../api/java110Request.js'
import mapping from '../constant/MappingConstant.js'
import {getWAppId} from '../api/init/initApi.js'
import ownerSession from './owner-session.js';
import directWechat from './wechat-session.js';

/**
 * 
 * app 自登陆处理
 * @param {Object} _key 自登陆key
 * add by  wuxw QQ 928255095
 */
export function doLoginOwnerByKey(_key, callback = () => {}) {
	const complete = ownerSession.begin();
	requestNoAuth({
		url: url.loginOwnerByKey,
		method: 'post',
		data: typeof _key === 'object' ? {authTicket:_key.authTicket} : {key:_key},
		success: function(res) {
			let _param = res.data || {};
			if (res.statusCode != 200 || _param.code != 0) {
				complete();
				uni.navigateTo({
					url: '/pages/showlogin/showlogin?wAppId=' + getWAppId()
				});
				return;
			}
			// #ifdef H5
			try {
				directWechat.apply(_param, uni, mapping);
				const cleanUrl = window.location.href.replace(/([?&])(?:authTicket|key|code)=[^&#]*&?/g, '$1').replace(/[?&](?=#|$)/g, '');
				window.history.replaceState(null, '', cleanUrl);
				complete();
				callback(_param);
				return;
			} catch (failure) {
				complete();
				uni.showToast({icon:'none',title:failure.message});
				return;
			}
			// #endif

			let _ownerInfo = _param.owner;
			wx.setStorageSync(mapping.OWNER_INFO, _ownerInfo);
			wx.setStorageSync(mapping.USER_INFO, JSON.stringify(_ownerInfo));
			let _currentCommunityInfo = {
				communityId: _ownerInfo.communityId,
				communityName: _ownerInfo.communityName
			};
			wx.setStorageSync(mapping.CURRENT_COMMUNITY_INFO, _currentCommunityInfo);

			let date = new Date();
			let year = date.getFullYear(); //获取当前年份
			let mon = date.getMonth(); //获取当前月份
			let da = date.getDate(); //获取当前日
			let h = date.getHours() + 1; //获取小时
			let m = date.getMinutes(); //获取分钟
			let s = date.getSeconds(); //获取秒
			let afterOneHourDate = new Date(year, mon, da, h, m, s); //30s之后的时间
			wx.setStorageSync(mapping.LOGIN_FLAG, {
				sessionKey: _ownerInfo.userId,
				expireTime: afterOneHourDate.getTime()
			});
			wx.setStorageSync(mapping.TOKEN, _param.token);
			// #ifndef H5
			wx.setStorageSync(mapping.OWNER_KEY, _param.key);
			// #endif
			// #ifdef H5
			const cleanUrl = window.location.href.replace(/([?&])(?:authTicket|key)=[^&#]*&?/g, '$1').replace(/[?&](?=#|$)/g, '');
			window.history.replaceState(null, '', cleanUrl);
			// #endif
			complete();
			callback();
		},
		fail: function(error) {
			complete();
			// 调用服务端登录接口失败
			uni.navigateTo({
				url: '/pages/showlogin/showlogin?wAppId=' + getWAppId()
			});
		}
	});
}
