<template>
	<view>
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
						<text class="text-grey text-sm">{{floorNum + '号楼'+unitNum+ '单元'+roomNum + '室'}}</text>
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
			<view class="block__title">{{billSnapshot ? '账单信息' : '费用信息'}}</view>
			<view class="block__title" v-if="billSnapshot && billMessage">{{billMessage}}</view>
			<view class="cu-list menu fee-last">
				<view class="cu-item">
					<view class="content">
						<text class="text-grey">{{billSnapshot ? '账单编号' : '费用编号'}}</text>
					</view>
					<view class="action">
						<text class="text-grey text-sm">{{billSnapshot ? billId : feeId}}</text>
					</view>
				</view>
				<view class="cu-item"  v-if="!billSnapshot && feeFlag == '1003006'">
					<view class="content">
						<text class="text-grey">金额</text>
					</view>
					<view class="action">
						<text class="text-grey text-sm">{{amount + '元/月' }}</text>
					</view>
				</view>
				<view class="cu-item"  v-else>
					<view class="content">
						<text class="text-grey">金额</text>
					</view>
					<view class="action">
						<text class="text-grey text-sm">{{amount + '元' }}</text>
					</view>
				</view>
				<view class="cu-item" v-if="billSnapshot">
					<view class="content">
						<text class="text-grey">账期</text>
					</view>
					<view class="action">
						<text class="text-grey text-sm">{{feeEndTime}} 至 {{deadlineTime}}</text>
					</view>
				</view>
				<view class="cu-item arrow" v-if="!billSnapshot && feeFlag == '1003006'">
					<view class="content">
						<text class="text-grey">周期</text>
					</view>
					<view class="action">
						<picker bindchange="PickerChange" :value="index" :range="feeMonthList" @change="dateChange">
							<view class="picker">
								{{feeMonthName}}
							</view>
						</picker>
					</view>
				</view>
				<view class="cu-item" v-if="!billSnapshot && feeFlag != '2006012'">
					<view class="content">
						<text class="text-grey">到期时间</text>
					</view>
					<view class="action">
						<text class="text-grey text-sm">{{endTime }}</text>
					</view>
				</view>
				<vcDiscount v-if="!billSnapshot" ref="vcDiscountRef" @computeFeeDiscount="computeFeeDiscount" payerObjType="3333" :payerObjId="roomId" :endTime="formatEndTime" :feeId="feeId" :cycles="feeMonth" :communityId="communityId"></vcDiscount>
			</view>
			
		</scroll-view>
		<view class=" bg-white  border flex justify-end" style="position: fixed;width: 100%;bottom: 0;">

			<view class="action text-orange margin-right line-height">
				合计：{{receivableAmount}}元
			</view>
			<view class="btn-group">
				<!-- #ifdef H5 || MP-WEIXIN -->
				<button class="cu-btn bg-red shadow-blur lgplus sharp" :disabled="paymentSubmitting || !paymentEnabled || (billSnapshot && !billReady)" @click="onPayFee()">{{ paymentEnabled ? (billSnapshot && !billReady ? '账单不可支付' : '提交订单') : '微信支付未开通' }}</button>
				<!-- #endif -->
				<!-- #ifdef APP-PLUS -->
				<button class="cu-btn bg-red shadow-blur lgplus sharp" :disabled="paymentSubmitting || !paymentEnabled || (billSnapshot && !billReady)" @click="_payWxApp()">{{ paymentEnabled ? (billSnapshot && !billReady ? '账单不可支付' : '提交订单') : '微信支付未开通' }}</button>
				<!-- #endif -->
			</view>


		</view>
	</view>

</template>

<script>
	// pages/payParkingFee/payParkingFee.js
	const context = require("../../context/Java110Context.js");
	const constant = context.constant;
	
	import vcDiscount from '@/components/vc-discount/vc-discount.vue'

	
	// #ifdef H5
	
	const WexinPayFactory = require('../../factory/WexinPayFactory.js');
	
	// #endif
	
	// #ifdef APP-PLUS
	import {getPayInfo} from '../../factory/WexinAppPayFactory.js'
	// #endif
	
	import {addMonth,formatDate,date2String} from '../../utils/DateUtil.js'
	import {getRoomFees} from '../../api/fee/feeApi.js'
	export default {
		components:{
			vcDiscount
		},
		data() {
			return {
				date: '2018-12-25',
				index: 0,
				active: 0,
				tablist: ['缴费', '历史'],
				TabCur: 0,
				scrollLeft: 0,
				showFeeMonth: false,
				feeMonthList: [],
				feeMonthName: '',
				feeMonth: 1,
				endTime: '',
				ordEndTime: '',
				formatEndTime: '',
				amount: 0,
				receivableAmount: 0.00,
				communityId: '',
				communityName: '',
				feeId: '',
				billSnapshot: false,
				billReady: false,
				billMessage: '',
				billId: '',
				oweId: '',
				feeEndTime: '',
				deadlineTime: '',
				roomId: '',
				floorNum: '',
				unitNum: '',
				roomNum: '',
				layer: '',
				builtUpArea: '',
				costList: [{}, {}], //费用清单
				additionalAmount: "",
				appId: '',
				paymentSubmitting: false,
				feeFlag:'',
				paymentCycle:1,
				squarePrice: 0,
			};
		},
		
		/**
		 * 生命周期函数--监听页面加载
		 */
		onLoad: function(options) {
			context.onLoad(options);
			// #ifdef MP-WEIXIN
			let accountInfo = uni.getAccountInfoSync();
			this.appId = accountInfo.miniProgram.appId;
			// #endif
			// #ifdef H5
				this.appId = uni.getStorageSync(constant.mapping.W_APP_ID)
			// #endif
			let _fee = JSON.parse(options.fee);
			let _communityInfo = context.getCurrentCommunity();
			this.communityId = _communityInfo.communityId;
			this.communityName = _communityInfo.communityName;
			this.billSnapshot = _fee.billSnapshot === true || !!_fee.oweId || !!_fee.billId;
			if (this.billSnapshot) {
				this.feeId = _fee.feeId;
				this.oweId = _fee.oweId;
				this.billId = _fee.billId;
				this.roomId = _fee.roomId;
				this.floorNum = _fee.floorNum;
				this.unitNum = _fee.unitNum;
				this.roomNum = _fee.roomNum;
				this.builtUpArea = _fee.builtUpArea;
				return this._loadBillSnapshot(_fee);
			}
			let _amount = _fee.amount;
			let _receivableAmount = _amount;
			if(_fee.feeFlag == "2006012"){ // 周期性费用
				_receivableAmount = _amount;
			}else{ // 一次性费用
				_receivableAmount = ((_fee.builtUpArea * _fee.squarePrice + parseFloat(_fee.additionalAmount)) * _fee.paymentCycle).toFixed(2);
			}
			let _lastDate = new Date(_fee.endTime);
			this.receivableAmount = _receivableAmount;
			this.communityId = _communityInfo.communityId;
			this.communityName = _communityInfo.communityName;
			this.floorNum = _fee.floorNum;
			this.unitNum = _fee.unitNum;
			this.roomNum = _fee.roomNum;
			this.roomId = _fee.roomId;
			this.layer = _fee.layer;
			this.builtUpArea = _fee.builtUpArea;
			this.feeId = _fee.feeId;
			this.amount = _amount;
			this.additionalAmount = _fee.additionalAmount;
			this.ordEndTime = _fee.endTime;
			this.formatEndTime = date2String(_fee.endTime);
			this.feeFlag = _fee.feeFlag;
			this.squarePrice = _fee.squarePrice;
			if(this.feeFlag == '2006012'){
				return;
			}
			this.paymentCycle = _fee.paymentCycle;	
			for (let _index = 1; _index < 7; _index++) {
				this.feeMonthList.push(_index * this.paymentCycle + '个月')
			}
			this.feeMonthName = this.paymentCycle + '个月';
			this.feeMonth = this.paymentCycle;
			let _endTime = addMonth(_lastDate, parseInt(this.feeMonth));
			this.endTime = formatDate(_endTime);
			
			this.$nextTick(() => {
				this.$refs.vcDiscountRef._loadFeeDiscount(this.feeId,this.communityId,this.feeMonth);
			})
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
			_rejectBillSnapshot: function() {
				this.billReady = false;
				this.amount = '0.00';
				this.receivableAmount = '0.00';
				this.feeEndTime = '';
				this.deadlineTime = '';
				this.endTime = '';
				this.billMessage = '账单已失效、已缴清或加载失败，请返回刷新';
				uni.showToast({ icon: 'none', title: this.billMessage });
			},
			_loadBillSnapshot: function(_room) {
				this.billReady = false;
				this.amount = '0.00';
				this.receivableAmount = '0.00';
				this.feeMonthList = [];
				this.billMessage = '账单核验中';
				if (!this.communityId || !this.roomId || !this.feeId || !this.oweId || !this.billId) {
					this._rejectBillSnapshot();
					return Promise.resolve();
				}
				let _params = { page: 1, row: 1, communityId: this.communityId,
					payerObjId: this.roomId, payerObjType: '3333',
					feeId: this.feeId, oweId: this.oweId, billId: this.billId };
				return getRoomFees(_params, _room).then(_fees => {
					let _bill = Array.isArray(_fees) && _fees.length === 1 ? _fees[0] : null;
					let _amount = _bill ? Number(_bill.amount) : NaN;
					if (!_bill || _bill.billSnapshot !== true || _bill.feeId !== this.feeId
						|| _bill.oweId !== this.oweId || _bill.billId !== this.billId
						|| !Number.isFinite(_amount) || _amount <= 0 || !_bill.feeEndTime || !_bill.deadlineTime) {
						this._rejectBillSnapshot();
						return;
					}
					this.amount = _amount.toFixed(2);
					this.receivableAmount = this.amount;
					this.feeEndTime = _bill.feeEndTime;
					this.deadlineTime = _bill.deadlineTime;
					this.ordEndTime = _bill.feeEndTime;
					this.formatEndTime = _bill.feeEndTime;
					this.endTime = _bill.deadlineTime;
					this.billMessage = '';
					this.billReady = true;
				}).catch(() => this._rejectBillSnapshot());
			},
			_canPayFee: function() {
				if (this.paymentEnabled !== true) {
					uni.showToast({ icon: 'none', title: '微信支付未开通' });
					return false;
				}
				if (this.billSnapshot && !this.billReady) {
					uni.showToast({ icon: 'none', title: this.billMessage || '账单不可支付，请返回刷新' });
					return false;
				}
				return true;
			},
			computeFeeDiscount:function(_price){
				if (this.billSnapshot) return;
				// this.receivableAmount = this.receivableAmount - _price;
				this.receivableAmount = (parseFloat(this.receivableAmount) + parseFloat(_price)).toFixed(2);
			},
			dateChange: function(e) {
				if (this.billSnapshot) return;
				let _feeMonthName = null;
				_feeMonthName = this.feeMonthList[e.detail.value];;
				let _feeMonth = _feeMonthName.replace("个月","");
				// let _receivableAmount = _feeMonth * this.amount;
				let _receivableAmount = _feeMonth * (this.builtUpArea * this.squarePrice + parseFloat(this.additionalAmount));
				let _lastDate = new Date(this.ordEndTime);
				let _newDate = addMonth(_lastDate, parseInt(_feeMonth));
				this.showFeeMonth = false;
				this.feeMonthName = _feeMonthName;
				this.receivableAmount = _receivableAmount.toFixed(2);
				this.feeMonth = _feeMonth;
				this.endTime = formatDate(_newDate);
				this.$refs.vcDiscountRef._loadFeeDiscount(this.feeId,this.communityId,this.feeMonth);
			},
			onFeeMonthChange: function(e) {
				console.log(e);
			},
			onFeeMonthCancel: function(e) {
				this.showFeeMonth = false;
			},
			_payWxApp: function(_data) {
				if (!this._canPayFee()) return;
				let _receivedAmount = this.receivableAmount;
				wx.showLoading({
					title: '支付中'
				});

				let _tradeType = 'APP';
				let _objData = {
					cycles: this.feeMonth,
					communityId: this.communityId,
					feeId: this.feeId,
					oweId: this.oweId,
					billId: this.billId,
					feeName: '物业费',
					receivedAmount: _receivedAmount,
					tradeType: _tradeType,
					appId: this.appId,
					endTime: this.formatEndTime
				};
				context.request({
					url: constant.url.preOrder,
					header: context.getHeaders(),
					method: "POST",
					data: _objData,
					//动态数据
					success: function(res) {
						if (res.statusCode == 200 && res.data.code == '0') {
							let data = res.data; //成功情况下跳转
							
							let obj = {};
							let orderInfo = {};
							// #ifdef MP-WEIXIN
							 obj = {
								appid: data.appId,
								noncestr: data.nonceStr,
								package: 'Sign=WXPay', // 固定值，以微信支付文档为主
								partnerid: data.partnerid,
								prepayid: data.prepayid,
								timestamp: data.timeStamp,
								sign: data.sign // 根据签名算法生成签名
							}
							// #endif
							// #ifdef APP-PLUS
							obj = getPayInfo(data);
							// #endif
							
							// 第二种写法，传对象字符串
							orderInfo = JSON.stringify(obj)
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
				if (!this._canPayFee()) return Promise.resolve({ state: 'UNAVAILABLE' });
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
				if (!this._canPayFee()) return;
				let _receivedAmount = this.receivableAmount;
				wx.showLoading({
					title: '支付中'
				});
				let _tradeType = 'JSAPI';
				let _objData = {
					cycles: this.feeMonth,
					communityId: this.communityId,
					feeId: this.feeId,
					oweId: this.oweId,
					billId: this.billId,
					feeName: '物业费',
					receivedAmount: _receivedAmount,
					tradeType: _tradeType,
					appId: this.appId,
					payerObjId: this.roomId,
					payerObjType: 3333,
					endTime: this.formatEndTime
				};
				return new Promise(resolve => context.request({
					url: constant.url.preOrder,
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
								WexinPayFactory.wexinPay(data,function(){
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
	@import "./roomFee.css";

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
