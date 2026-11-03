<template>
	<view>
		<!-- DIRECT-WECHAT-FORM -->
		<view v-if="wechatDirect" class="padding">
			<view class="text-xl margin-bottom">绑定我的房产</view>
			<view class="text-gray margin-bottom">请填写与物业登记一致的资料，验证成功后即可查看房屋和账单。</view>
			<view v-if="!wechatIdentityReady" class="padding bg-white">
				<text>请先在微信中完成授权，无需注册账号。</text>
				<button class="cu-btn bg-green margin-top" @click="authorizeWechat">微信授权</button>
			</view>
			<view v-else>
				<view class="cu-form-group"><view class="title">姓名</view><input v-model="directName" maxlength="50" placeholder="物业登记的业主姓名" /></view>
				<view class="cu-form-group"><view class="title">手机号</view><input v-model="directPhone" type="number" maxlength="11" placeholder="物业登记的手机号" /></view>
				<view class="cu-form-group"><view class="title">身份证号</view><input v-model="directIdCard" maxlength="18" placeholder="物业登记的身份证号" /></view>
				<view v-if="directError" class="text-red padding-top">{{directError}}</view>
				<button class="cu-btn bg-green lg margin-top" style="width:100%" :disabled="directSubmitting" @click="bindOwnerWechat">{{directSubmitting ? '正在验证资料' : '验证并绑定房产'}}</button>
			</view>
		</view>
		<!-- END-DIRECT-WECHAT-FORM -->
		<view v-else>
		<view class="block__title">小区信息</view>
		<view class="cu-form-group">
			<view class="title">市/区</view>
			<pickerAddress @change="change">{{areaName}}</pickerAddress>
		</view>
		<view class="cu-form-group">
			<view class="title">小区名称</view>
			<input :value="communityName" @input="bindInput" data-name="communityName" required readonly label="小区名称"
			 placeholder="请选择小区" name="communityName" icon="arrow" @tap.native.stop="chooseCommunity"></input>
			<text class='cuIcon-right'></text>
		</view>

		<view class="block__title">业主信息</view>
		<view class="cu-form-group">
			<view class="title">姓名</view>
			<input :value="appUserName" @input="bindInput" data-name="appUserName" required label="姓名" clearable placeholder="请输入名称"
			 name="appUserName"></input>
		</view>
		<view class="cu-form-group">
			<view class="title">身份证</view>
			<input :value="idCard" @input="bindInput" data-name="idCard" required label="身份证" clearable placeholder="请输入身份证"
			 name="idCard"></input>
		</view>
		<view class="cu-form-group">
			<view class="title">手机号</view>
			<input :value="link" @input="bindInput" data-name="link" required label="手机号" clearable placeholder="请输入手机号" name="link"></input>
		</view>
		<view class="cu-form-group" v-if="smsEnabled">
			<view class="title">验证码</view>
			<input :value="msgCode" @input="bindInput" data-name="msgCode" required label="验证码" clearable placeholder="请输入短信验证码"
			 name="msgCode"></input>
			<button class="cu-btn sm" slot="button" type="number" size="small" @tap.native.stop="sendMsgCode">发送验证码</button>
		</view>
		<view class="padding text-gray text-sm">请填写与物业登记一致的信息，提交后需由物业核验并审核绑定。</view>
		<view class="button_up_blank">
			<button class="cu-btn bg-green lg margin-top" @tap.native.stop="bindOwner">绑定业主</button>
		</view>
		</view>
	</view>
</template>

<script>
	// pages/enterCommunity/enterCommunity.js
	const context = require("../../context/Java110Context.js");
	import pickerAddress from "../../components/pickerAddress/pickerAddress.vue"
	import {sendSmsCode} from '../../api/user/userApi.js';
	import directWechat from '../../auth/wechat-session.js';
	import returnTarget from '../../auth/return-target.js';
	import {requestNoAuth} from '../../api/java110Request.js';
	import {wechatRefreshToken} from '../../auth/H5Login.js';
	const constant = context.constant;

	export default {
		data() {
			return {
				directName:'',directPhone:'',directIdCard:'',directError:'',directSubmitting:false,
				areaCode: '',
				communityName: '',
				appUserName: '',
				idCard: '',
				link: '',
				msgCode: '',
				areaShow: false,
				areaList: {
					province_list: {},
					city_list: {},
					county_list: {}
				},
				province_list: {},
				city_list: {},
				county_list: {},
				multiArray: [
					['安徽', '湖北'],
					['合肥', '淮南', '六安'],
					['蜀山', '高新']
				],
				areaName:null
			};
		},

		components: {pickerAddress},
		props: {},

		/**
		 * 生命周期函数--监听页面加载
		 */
		onLoad: function(options) {
			let _that = this;
			 context.onLoad(options);
			// #ifdef H5
			return;
			// #endif

			let _location = context.getLocation();

			let _currentLocation = context.getCurrentLocation() || {};
			console.log(_currentLocation+"    444444");
			let _areaName = (_currentLocation.city || '') + (_currentLocation.district || '');
			// console.log(_areaName);
			// let _areaCode = _currentLocation.adcode; //加载省份

			context._loadArea('', '', function(_areaList) {
				_that.areaList = _areaList,
					console.log("地址列表", _areaList);
				_that.communityName = _location
					// _that.areaCode = _areaCode,
					_that.areaName = _areaName
				// _that.setData({
				//   areaList: _areaList,
				//   communityName: _location,
				//   areaCode: _areaCode,
				//   areaName: _areaName
				// });
			});
		},

		/**
		 * 生命周期函数--监听页面初次渲染完成
		 */
		onReady: function() {},

		/**
		 * 生命周期函数--监听页面显示
		 */
		onShow: function() {
			// #ifdef H5
			return;
			// #endif
			if (this.areaCode == '' || this.areaCode == undefined) {
				let _currentLocation = context.getCurrentLocation() || {};

				let _areaName = _currentLocation.city + _currentLocation.district;

				let _areaCode = _currentLocation.adcode;
				this.areaCode = _areaCode || '';
				//this.areaName = _areaName;
				// this.setData({
				//   areaCode: _areaCode,
				//   areaName: _areaName
				// });
			}
		},

		/**
		 * 生命周期函数--监听页面隐藏
		 */
		onHide: function() {},

		/**
		 * 生命周期函数--监听页面卸载
		 */
		onUnload: function() {},

		/**
		 * 页面相关事件处理函数--监听用户下拉动作
		 */
		onPullDownRefresh: function() {},

		/**
		 * 页面上拉触底事件的处理函数
		 */
		onReachBottom: function() {},

		/**
		 * 用户点击右上角分享
		 */
		onShareAppMessage: function() {},
		methods: {
			authorizeWechat(){wechatRefreshToken();},
			bindOwnerWechat(){
				if(this.directSubmitting)return;
				let data;
				try{data=directWechat.bindingPayload({name:this.directName,phone:this.directPhone,idCard:this.directIdCard},uni);}
				catch(error){this.directError=error.message;this.wechatIdentityRevision++;return;}
				this.directError='';this.directSubmitting=true;
				requestNoAuth({url:'/app/bindOwnerWechat',method:'POST',data,
					success:res=>{
						const payload=res.data||{};
						if(res.statusCode!==200 || payload.code!=0){
							this.directError=typeof res.data==='string'?res.data:payload.msg||'验证失败，请核对资料后重试';
							if(res.statusCode===401 || payload.code==401){directWechat.clear(uni);uni.$emit('owner-wechat-session-changed');}
							return;
						}
						try{
							directWechat.apply(payload,uni,constant.mapping);
							this.directName='';this.directPhone='';this.directIdCard='';
							const target=returnTarget.consume(uni)||'/pages/my/myHouse';
							uni.redirectTo({url:target});
						}catch(error){this.directError=error.message;}
					},
					fail:()=>{this.directError='网络连接失败，请稍后重试';},
					complete:()=>{this.directSubmitting=false;}
				});
			},
			change:function(e){
				console.log(e);
				let city ='';
				for(let c in e.data){
					console.log(e.data[c]);
					city +=e.data[c].name;
				}
				this.$data.areaName = city;
				this.areaCode = e.data[2].code;
			},
			bindInput: function(e) {

				let _that = this;

				let dataset = e.currentTarget.dataset;
				let value = e.detail.value;
				let name = dataset.name;
				_that[name] = value; //  

			},
			sendMsgCode: function() {
				if (!this.smsEnabled) {
					uni.showToast({icon:'none',title:'短信验证未开通，请提交资料由物业核验'});
					return;
				}
				sendSmsCode(this.link, this).catch(() => {});
			},
			bindOwner: function(e) {
				let obj = {
					"areaCode": this.areaCode,
					"communityName": this.communityName,
					"appUserName": this.appUserName,
					"idCard": this.idCard,
					"link": this.link,
					"msgCode": this.msgCode
				};
				let msg = "";

				if (obj.areaCode == "") {
					msg = "请选择地区";
				} else if (obj.communityName == "") {
					msg = "请填写小区名称";
				} else if (obj.appUserName == "") {
					msg = "请填写业主名称";
				} else if (obj.idCard == "") {
					msg = "请填写身份证";
				} else if (obj.link == "") {
					msg = "请填写手机号";
				} else if (this.smsEnabled && obj.msgCode == "") {
					msg = "请填写验证码";
				}

				if (msg != "") {
					wx.showToast({
						title: msg,
						icon: 'none',
						duration: 2000
					});
				} else {
					wx.showLoading({
						title: '绑定中'
					});
					context.request({
						url: constant.url.appUserBindingOwner,
						header: context.getHeaders(),
						method: "POST",
						data: obj,
						//动态数据
						success: function(res) {
							const data = res.data || {};
							if (res.statusCode == 200 && data.code == 0) {
								wx.hideLoading();
								wx.redirectTo({
									url: "/pages/viewBindOwner/viewBindOwner"
								});
								return;
							}

							wx.hideLoading();
							wx.showToast({
								title: typeof res.data === 'string' ? res.data : data.msg || '绑定失败，请核实资料或稍后重试',
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
				}
			},
			onConfirm: function(e) {
				console.log("onConfirm", e);
				let _areaCode = e.detail.values[2].code;

				let _areaName = e.detail.values[1].name + e.detail.values[2].name;
				this.areaCode = _areaCode,
					this.areaName = _areaName,
					this.areaShow = false

				// this.setData({
				//   areaCode: _areaCode,
				//   areaName: _areaName,
				//   areaShow: false
				// });
			},
			onChange: function(e) {
				console.log(e);
			},
			onCancel: function(e) {
				this.areaShow = false;
				// this.setData({
				//   areaShow = false
				// });
			},

			/**
			 * 选择小区
			 */
			chooseCommunity: function(e) {
				if (this.areaCode == '' || this.areaCode == undefined) {
					wx.showToast({
						title: '请先选择地区',
						icon: 'none'
					});
					return;
				}

				wx.navigateTo({
					url: '/pages/viewCommunitys/viewCommunitys?areaCode=' + this.areaCode
				});
			}
		}
	};
</script>
<style>
	@import "./bindOwner.css";
</style>
