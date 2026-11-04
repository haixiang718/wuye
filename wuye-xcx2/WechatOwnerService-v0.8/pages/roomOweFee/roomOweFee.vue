<template>
	<view>
		<!-- #ifdef H5 -->
		<wechat-binding-gate />
		<!-- #endif -->
		<view v-if="!wechatPropertyLocked">
		<scroll-view scroll-y>
			<view class="block__title">房屋信息</view>
			<view class="cu-list menu">
				<view class="cu-item">
					<view class="content">
						<text class="text-grey">小区名称</text>
					</view>
					<view class="action">
						<text class="text-grey text-sm">{{communityName}}</text>
					</view>
				</view>
				<view class="cu-item">
					<view class="content">
						<text class="text-grey">房屋</text>
					</view>
					<view class="action">
						<text class="text-grey text-sm">{{floorNum}}-{{unitNum}}-{{roomNum}}</text>
					</view>
				</view>
				<view class="cu-item">
					<view class="content">
						<text class="text-grey">建筑面积</text>
					</view>
					<view class="action">
						<text class="text-grey text-sm">{{builtUpArea + '平方米'}}</text>
					</view>
				</view>
			</view>
			<view class="block__title" v-if="fees.length > 0">欠费信息</view>
			<view class="cu-list menu"  v-for="(item,index) in fees" :key="index" :data-item="item">
				<view class="cu-item">
					<view class="content padding-tb-sm">
						<view>
							<view class="text-cut" style="width:220px">{{item.feeName}}</view>
						</view>
						<view class="text-gray text-sm">
							<text class="margin-right-xs">{{item.endTime}}至{{item.deadlineTime}}</text></view>
					</view>
					<view class="action">
						<text class="text-grey text-sm">应缴:￥{{item.feePrice}}</text>
					</view>
				</view>
			</view>
		</scroll-view>
		<view v-if="fees.length > 0" class="bg-white  border flex justify-end" style="position: fixed;width: 100%;bottom: 0;">

			<view class="action text-orange margin-right line-height">
				合计：{{receivableAmount}}元
			</view>
			<view class="btn-group">
				<!-- #ifdef H5 || MP-WEIXIN -->
				<button class="cu-btn bg-red shadow-blur lgplus sharp" :disabled="paymentSubmitting || !paymentEnabled" @click="onPayFee()">{{ paymentEnabled ? '提交订单' : '微信支付未开通' }}</button>
				<!-- #endif -->
				<!-- #ifdef APP-PLUS -->
				<button class="cu-btn bg-red shadow-blur lgplus sharp" :disabled="paymentSubmitting || !paymentEnabled" @click="_payWxApp()">{{ paymentEnabled ? '提交订单' : '微信支付未开通' }}</button>
				<!-- #endif -->
			</view>
		</view>
		</view>
	</view>

</template>

<script>
	// pages/payParkingFee/payParkingFee.js
	const context = require("../../context/Java110Context.js");
	const constant = context.constant;


	// #ifdef H5

	const WexinPayFactory = require('../../factory/WexinPayFactory.js');

	// #endif

	import {
		addMonth,
		formatDate
	} from '../../utils/DateUtil.js'

	import {
		getCurCommunity
	} from '../../api/community/communityApi.js'

	import {
		getRoomOweFees
	} from '../../api/fee/feeApi.js'
	
	import {
		getRooms
	} from '../../api/room/roomApi.js'
	export default {
		data() {
			return {
				scrollLeft: 0,
				amount: 0,
				receivableAmount: '0.00',
				communityId: '',
				communityName: '',
				floorNum: '',
				unitNum: '',
				roomNum: '',
				builtUpArea: '',
				additionalAmount: "",
				appId: '',
				paymentSubmitting: false,
				fees: [],
				rooms: [],
				curRoom: {},
				roomId: ''
			};
		},
		/**
		 * 生命周期函数--监听页面加载
		 */
		onLoad: function(options) {
			let _that = this;
			context.onLoad(options);
			// #ifdef MP-WEIXIN
			let accountInfo = uni.getAccountInfoSync();
			this.appId = accountInfo.miniProgram.appId;
			// #endif
			// #ifdef H5
			this.appId = uni.getStorageSync(constant.mapping.W_APP_ID)
			// #endif
			this.roomId = options.roomId || '';
			this.fees = [];
			this.receivableAmount = '0.00';

			return Promise.all([getCurCommunity(), getRooms(this.roomId)]).then(function(results) {
				let _curCommunity = results[1].owner || results[0];
				_that.communityId = _curCommunity.communityId;
				_that.communityName = _curCommunity.communityName;
				_that.rooms = Array.isArray(results[1].rooms) ? results[1].rooms : [];
				let _room = _that.roomId
					? _that.rooms.find(room => room.roomId === _that.roomId)
					: _that.rooms[0];
				_that.curRoom = _room || {};
				if (!_room) {
					uni.showToast({ icon: 'none', title: '未找到可查询的房屋' });
					return;
				}
				_that.roomId = _room.roomId;
				_that.floorNum = _room.floorNum;
				_that.unitNum = _room.unitNum;
				_that.roomNum = _room.roomNum;
				_that.builtUpArea = _room.builtUpArea;
				return _that._loadRoomOweFee();
			}).catch(function(error) {
				_that.fees = [];
				_that.receivableAmount = '0.00';
				if(error && error.code==='BINDING_REQUIRED')return;
				uni.showToast({ icon: 'none', title: '房屋信息加载失败，请稍后重试' });
			});
		},
		onShow: function() {
			// #ifdef H5
			if (this.paymentSubmitting) return Promise.resolve({ state: 'BUSY' });
			this.paymentSubmitting = true;
			return WexinPayFactory.resumePending().then(result => {
				if (result.state === 'POSTED') {
					uni.showToast({ icon: 'none', title: '支付成功，请刷新账单' });
				}
				return result;
			}).finally(() => { this.paymentSubmitting = false; });
			// #endif
		},
		methods: {

			_loadRoomOweFee: function() {
				let _that =this;
				this.fees = [];
				this.receivableAmount = '0.00';
				let _objData = {
					payObjId: this.roomId,
					payObjType:'3333',
					page: 1,
					row: 50,
					communityId: this.communityId
				}

				return getRoomOweFees(_objData)
					.then(function(_fees) {
						_that.fees = Array.isArray(_fees) ? _fees : [];
						let _totalCents = _that.fees.reduce(function(total, item) {
							let _price = Number(item.feePrice);
							return total + (Number.isFinite(_price) ? Math.round((_price + Number.EPSILON) * 100) : 0);
						}, 0);
						_that.receivableAmount = (_totalCents / 100).toFixed(2);
					}).catch(function() {
						_that.fees = [];
						_that.receivableAmount = '0.00';
						uni.showToast({
							icon:'none',
							title:'暂无欠费信息或加载失败，请稍后重试'
						})
					});
			},

			_payWxApp: function(_data) {
				if (!this.paymentEnabled) {
					uni.showToast({ icon: 'none', title: '微信支付未开通' });
					return;
				}
				let _receivedAmount = this.receivableAmount;
				wx.showLoading({
					title: '支付中'
				});

				let _tradeType = 'APP';
				let _objData = {
					cycles: this.feeMonth,
					communityId: this.communityId,
					roomId: this.roomId,
					feeName: '物业费',
					receivedAmount: _receivedAmount,
					tradeType: _tradeType,
					appId: this.appId
				};
				context.request({
					url: constant.url.toOweFeePay,
					header: context.getHeaders(),
					method: "POST",
					data: _objData,
					//动态数据
					success: function(res) {

						if (res.statusCode == 200 && res.data.code == '0') {
							let data = res.data; //成功情况下跳转
							let obj = {
								appid: data.appId,
								noncestr: data.nonceStr,
								package: 'Sign=WXPay', // 固定值，以微信支付文档为主
								partnerid: data.partnerid,
								prepayid: data.prepayid,
								timestamp: data.timeStamp,
								sign: data.sign // 根据签名算法生成签名
							}
							// 第二种写法，传对象字符串
							let orderInfo = JSON.stringify(obj)
							uni.requestPayment({
								provider: 'wxpay',
								orderInfo: orderInfo, //微信、支付宝订单数据
								success: function(res) {
									uni.showToast({
										title: "支付成功",
										duration: 2000
									});
									uni.navigateBack({});
								},
								fail: function(err) {
									console.log('fail:' + JSON.stringify(err));
								}
							});
							wx.hideLoading();
							return;
						}

						wx.hideLoading();
						wx.showToast({
							title: "缴费失败",
							icon: 'none',
							duration: 2000
						});
					},
					fail: function(e) {
						wx.hideLoading();
						wx.showToast({
							title: "服务器异常了",
							icon: 'none',
							duration: 2000
						});
					}
				});
			},
			onPayFee: function() {
				if (!this.paymentEnabled) {
					uni.showToast({ icon: 'none', title: '微信支付未开通' });
					return Promise.resolve({ state: 'UNAVAILABLE' });
				}
				// #ifdef H5
				if (this.paymentSubmitting) return Promise.resolve({ state: 'BUSY' });
				if (!/MicroMessenger/i.test(navigator.userAgent || '')) {
					uni.showToast({ icon: 'none', title: '请在微信中打开此页面后支付' });
					return Promise.resolve({ state: 'UNAVAILABLE' });
				}
				this.paymentSubmitting = true;
				return WexinPayFactory.preparePayment().then(result => {
					if (result.state === 'NONE' || result.state === 'CLOSED') return this._submitPayFee();
					if (result.state === 'POSTED') uni.showToast({ icon: 'none', title: '支付成功，请刷新账单' });
					return result;
				}).finally(() => { this.paymentSubmitting = false; });
				// #endif
				// #ifndef H5
				return this._submitPayFee();
				// #endif
			},
			_submitPayFee: function() {
				if (!this.paymentEnabled) {
					uni.showToast({ icon: 'none', title: '微信支付未开通' });
					return;
				}
				let _receivedAmount = this.receivableAmount;
				wx.showLoading({
					title: '支付中'
				});
				let _tradeType = 'JSAPI';
				let _objData = {
					cycles: this.feeMonth,
					communityId: this.communityId,
					roomId: this.roomId,
					feeName: '物业费',
					receivedAmount: _receivedAmount,
					tradeType: _tradeType,
					appId: this.appId
				};
				return new Promise(resolve => context.request({
					url: constant.url.toOweFeePay,
					header: context.getHeaders(),
					method: "POST",
					data: _objData,
					//动态数据
					success: function(res) {
						if (res.statusCode == 200 && res.data.code == '0') {
							let data = res.data; //成功情况下跳转
							// #ifdef MP-WEIXIN
							uni.requestPayment({
								'timeStamp': data.timeStamp,
								'nonceStr': data.nonceStr,
								'package': data.package,
								'signType': data.signType,
								'paySign': data.sign,
								'success': function(res) {
									uni.showToast({
										title: "支付成功",
										duration: 2000
									});
									uni.navigateBack({});
								},
								'fail': function(res) {
									console.log('fail:' + JSON.stringify(res));
								}
							});
							// #endif
							// #ifdef H5
							WexinPayFactory.wexinPay(data, function() {
								uni.showToast({
									title: "支付成功",
									duration: 2000
								});
								uni.navigateBack({});
							}).then(resolve, resolve);
							// #endif
							wx.hideLoading();
							// #ifndef H5
							resolve();
							// #endif
							return;
						}

						wx.hideLoading();
						wx.showToast({
							title: "缴费失败",
							icon: 'none',
							duration: 2000
						});
						resolve({ state: 'FAILED' });
					},
					fail: function(e) {
						wx.hideLoading();
						wx.showToast({
							title: "服务器异常了",
							icon: 'none',
							duration: 2000
						});
						resolve({ state: 'FAILED' });
					}
				}));
			}
		}
	};
</script>
<style>
	.ppf_item {
		padding: 0rpx 0rpx 0rpx 0rpx;
	}

	.block__title {
		margin: 0;
		font-weight: 400;
		font-size: 14px;
		color: rgba(69, 90, 100, .6);
		padding: 40rpx 30rpx 20rpx;
	}

	.button_up_blank {
		height: 40rpx;
	}

	.block__bottom {
		height: 180rpx;
	}

	.fee-last {
		margin-bottom: 200upx;
	}

	.cu-btn.lgplus {
		padding: 0 20px;
		font-size: 18px;
		height: 100upx;

	}

	.cu-btn.sharp {
		border-radius: 0upx;
	}

	.line-height {
		line-height: 100upx;
	}
</style>
