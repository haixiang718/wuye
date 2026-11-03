/**
 * 首页相关 数据封装API
 * add by 吴学文 2020-09-07
 * QQ 92825595
 * 
 */

import {
	requestNoAuth
} from '../java110Request.js';
import url from '../../constant/url.js';
const context = require("../../context/Java110Context.js");
const constant = context.constant;


import {
	dateTimeStringToDateString
} from '../../utils/DateUtil.js'


export function timer(_that) {
	let second = 60;
	let promise = new Promise((resolve, reject) => {
		let setTimer = setInterval(
			() => {
				second = second - 1;
				_that.btnValue = second + '秒';
				_that.btnDisabled = true;
				if (second <= 0) {
					second = 60;
					_that.btnValue = '获取验证码';
					_that.btnDisabled = false;
					resolve(setTimer)
				}
			}, 1000)
	})
	promise.then((setTimer) => {
		clearInterval(setTimer)
	})
}
/**
 * 查询活动列表
 */
export function sendSmsCode(_link, _that) {
	let obj = {
		tel: _link
	};
	if (obj.tel == '') {
		wx.showToast({
			title: '请输入手机号',
			icon: 'none',
			duration: 2000
		});
		return;
	}
	uni.showLoading({
		title: '加载中',
		mask: true
	});
	return new Promise(
		(resolve, reject) => {
			requestNoAuth({
				url: url.userSendSms,
				method: "POST",
				data: obj,
				//动态数据
				success: function(res) {
					uni.hideLoading();
					if (res.statusCode == 200) {
						wx.showToast({
							title: '验证码下发成功',
							icon: 'none',
							duration: 2000
						});
						wx.hideLoading();
						timer(_that);
						resolve();
						return;
					}
					wx.showToast({
						title: res.data,
						icon: 'none',
						duration: 2000
					});
					reject();
				},
				fail: function(e) {
					uni.hideLoading();
					reject();
				}
			});
		})
}


/**
 * 查询活动列表
 */
export function userLogin(_obj) {
	uni.showLoading({
		title: '加载中',
		mask: true
	});
	return new Promise(
		(resolve, reject) => {
			requestNoAuth({
				url: url.loginOwnerUrl,
				method: "POST",
				data: JSON.stringify(_obj),
				//动态数据
				success: function(res) {
					uni.hideLoading();
					//将用户信息携入缓存中
					uni.hideLoading();
					let _data = res.data || {};
					if (res.statusCode != 200 || (_data.code != null && _data.code != 0) || !_data.owner || !_data.token) {
						let _message = typeof res.data === 'string' ? res.data : _data.msg || '登录失败，请检查账号或稍后重试';
						uni.showToast({
							icon: 'none',
							title: _message
						});
						reject(new Error(_message));
						return;
					}

					let _ownerInfo = _data.owner;
					wx.setStorageSync(constant.mapping.OWNER_INFO, _ownerInfo);
					wx.setStorageSync(constant.mapping.USER_INFO, JSON.stringify(_ownerInfo));
					let _currentCommunityInfo = {
						communityId: _ownerInfo.communityId,
						communityName: _ownerInfo.communityName
					};
					wx.setStorageSync(constant.mapping.CURRENT_COMMUNITY_INFO, _currentCommunityInfo);

					let date = new Date();
					let year = date.getFullYear(); //获取当前年份

					let mon = date.getMonth(); //获取当前月份

					let da = date.getDate(); //获取当前日

					let h = date.getHours() + 1; //获取小时

					let m = date.getMinutes(); //获取分钟

					let s = date.getSeconds(); //获取秒


					let afterOneHourDate = new Date(year, mon, da, h, m, s); //30s之后的时间

					wx.setStorageSync(constant.mapping.LOGIN_FLAG, {
						sessionKey: _ownerInfo.userId,
						expireTime: afterOneHourDate.getTime()
					});
					wx.setStorageSync(constant.mapping.TOKEN, _data.token);
					//保存临时 钥匙
					wx.setStorageSync(constant.mapping.OWNER_KEY, _data.key);
					resolve();
				},
				fail: function(e) {
					uni.hideLoading();
					const message = '网络连接失败，请稍后重试';
					uni.showToast({icon:'none',title:message});
					reject(new Error(message));
				}
			});
		})
}
