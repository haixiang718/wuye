<template>
	<view>
		<!-- #ifdef H5 -->
		<wechat-binding-gate />
		<!-- #endif -->
		<view v-if="!wechatPropertyLocked">
		<scroll-view scroll-y class="selection-body">
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
			<view class="block__title" v-if="fees.length > 0">请选择需要缴纳的账单（可多选）</view>
			<view v-if="selectionError" class="selection-error">{{selectionError}}</view>
			<view class="cu-list menu"  v-for="item in fees" :key="item.oweId" :data-item="item">
				<view class="cu-item">
					<checkbox-group class="selection-check" @change="toggleFee(item)"><checkbox :value="item.oweId" :checked="isSelected(item)" :disabled="paymentSubmitting || !isSelectable(item)" color="#14836b" /></checkbox-group>
					<view class="content padding-tb-sm selection-content">
						<view class="selection-heading"><view class="selection-name">{{item.feeName}}</view><text class="selection-price">¥{{money(item.feePrice) || '金额待核实'}}</text></view>
						<view class="selection-period">{{date(item.feeEndTime || item.endTime)}} 至 {{date(item.deadlineTime)}}</view>
					</view>
				</view>
			</view>
		</scroll-view>
		<view v-if="fees.length > 0" class="bg-white selection-footer">
			<checkbox-group class="selection-all" @change="toggleAll"><label><checkbox value="all" :checked="allSelected()" :disabled="paymentSubmitting" color="#14836b" />全选</label></checkbox-group>
			<view class="selection-summary"><view>已选 {{selectedOweIds.length}} 项</view><view class="selection-total">合计 ¥{{receivableAmount}}</view></view>
			<view class="btn-group">
				<!-- #ifdef H5 || MP-WEIXIN -->
				<button class="cu-btn bg-red sharp" :disabled="paymentSubmitting || !paymentEnabled || !selectedOweIds.length" @click="onPayFee()">{{ paymentEnabled ? '确认缴费' : '微信支付未开通' }}</button>
				<!-- #endif -->
				<!-- #ifdef APP-PLUS -->
				<button class="cu-btn bg-red sharp" :disabled="paymentSubmitting || !paymentEnabled || !selectedOweIds.length" @click="_payWxApp()">{{ paymentEnabled ? '确认缴费' : '微信支付未开通' }}</button>
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
	import billSelection from '../../utils/bill-selection.js';
	import feeDisplay from '../../utils/fee-display.js';


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
				selectedOweIds: [],
				requestedOweIds: [],
				selectionError: '',
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
			try {this.requestedOweIds=options.oweIds ? billSelection.parse(options.oweIds) : [];} catch(error) {this.selectionError=error.message;uni.showToast({icon:'none',title:error.message});return Promise.resolve();}
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
			...billSelection.methods('fees'),
			money:feeDisplay.money,
			date:feeDisplay.date,

			_loadRoomOweFee: function() {
				let _that =this;
				this.fees = [];
				this.resetSelection();
				this.selectionError='';
				let _objData = {
					payObjId: this.roomId,
					payObjType:'3333',
					page: 1,
					row: 100,
					communityId: this.communityId
				}

				return getRoomOweFees(_objData)
					.then(function(_fees) {
						_that.fees = Array.isArray(_fees) ? _fees : [];
						try {_that.resetSelection(_that.requestedOweIds);} catch(error) {_that.resetSelection();_that.selectionError=error.message;uni.showToast({icon:'none',title:error.message});}
						_that.requestedOweIds=[];
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
				if (!this.hasSelection()) {uni.showToast({icon:'none',title:'请先勾选需要缴纳的账单'});return;}
				let _receivedAmount = this.receivableAmount;
				wx.showLoading({
					title: '支付中'
				});

				let _tradeType = 'APP';
				let _objData = {
					cycles: this.feeMonth,
					communityId: this.communityId,
					roomId: this.roomId,
					oweIds: this.selectedOweIds.slice(),
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
				if (!this.hasSelection()) {uni.showToast({icon:'none',title:'请先勾选需要缴纳的账单'});return Promise.resolve({state:'EMPTY_SELECTION'});}
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
				if (!this.hasSelection()) {uni.showToast({icon:'none',title:'请先勾选需要缴纳的账单'});return Promise.resolve({state:'EMPTY_SELECTION'});}
				let _receivedAmount = this.receivableAmount;
				wx.showLoading({
					title: '支付中'
				});
				let _tradeType = 'JSAPI';
				let _objData = {
					cycles: this.feeMonth,
					communityId: this.communityId,
					roomId: this.roomId,
					oweIds: this.selectedOweIds.slice(),
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
	@import "../../style/bill-selection.css";
	.selection-body{padding-bottom:calc(110rpx + env(safe-area-inset-bottom));}
	.selection-check{flex-shrink:0;margin-right:16rpx;}
	.selection-content{flex:1;min-width:0;}
	.selection-heading{display:flex;justify-content:space-between;gap:16rpx;}
	.selection-name{min-width:0;overflow-wrap:anywhere;}
	.selection-price{flex-shrink:0;color:#d45235;}
	.selection-period{font-size:24rpx;color:#7b8493;margin-top:12rpx;}
	.selection-error{padding:20rpx;color:#b54d28;}
	.selection-footer{position:fixed;bottom:0;left:0;width:100%;display:flex;align-items:center;gap:16rpx;padding:16rpx 20rpx calc(16rpx + env(safe-area-inset-bottom));z-index:5;border-top:1px solid #edf0f4;}
	.selection-all{flex-shrink:0;font-size:26rpx;}
	.selection-summary{flex:1;min-width:0;font-size:22rpx;color:#7b8493;}
	.selection-total{font-size:28rpx;font-weight:600;color:#d45235;}
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
