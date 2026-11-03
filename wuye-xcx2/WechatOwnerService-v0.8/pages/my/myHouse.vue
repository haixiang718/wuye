<template>
	<view class="tab-container bg-white">
		<!-- #ifdef H5 -->
		<wechat-binding-gate />
		<!-- #endif -->
		<view class="cu-list menu" v-if="!wechatPropertyLocked">
			<view v-if="wechatDirect" class="padding"><button class="cu-btn line-green" @click="bindAdditional">继续绑定房产</button></view>
			<picker v-if="wechatDirect && ownerOptions.length>1" :range="ownerOptions" range-key="label" :value="activeOwnerIndex" @change="changeOwner">
				<view class="cu-item arrow"><view class="content">切换已绑定房产</view><view class="action text-gray">{{ownerOptions[activeOwnerIndex].label}}</view></view>
			</picker>
			<view class="cu-item arrow" v-if="rooms.length > 0" v-for="(item, key) in rooms" :key="key" :data-item="item" @click="myHouseDetail(item)">
				<view class="content padding-tb-sm">
					<view>
						<text class="cuIcon-homefill text-green margin-right-xs"></text> {{item.floorNum}}号楼{{item.unitNum}}单元{{item.roomNum}}室</view>
					<view class="text-gray text-sm">
						<text class="cuIcon-right margin-right-xs"></text> {{item.roomId}}</view>
				</view>
				<view class="action">

				</view>
			</view>
			<view class="cu-item" v-if="rooms.length === 0">
				<view class="content">
					<text class="cuIcon-warn text-green"></text>
					<text class="text-grey">{{loading ? '房屋信息加载中' : loadError || '暂无房屋信息'}}</text>
				</view>
				<view class="action">
					<button v-if="loadError" class="cu-btn sm line-green" @click="loadOwnerHouse">重试</button>
				</view>
			</view>
		</view>
	</view>
</template>

<script>
	// pages/my/myHouse.js
	const context = require("../../context/Java110Context.js");
	const constant = context.constant;
	import {getRooms} from '../../api/room/roomApi.js';
	import directWechat from '../../auth/wechat-session.js';

	export default {
		data() {
			return {
				rooms: [],
				loading: false,
				loadError: '',
				roomRequestId: 0,
				ownerOptions:[],activeOwnerIndex:0
			};
		},

		components: {},
		props: {},

		/**
		 * 生命周期函数--监听页面加载
		 */
		onLoad: function(options) {
			context.onLoad(options,()=>this.loadOwnerHouse());
			// #ifdef H5
			return;
			// #endif
			return this.loadOwnerHouse();
		},

		/**
		 * 生命周期函数--监听页面初次渲染完成
		 */
		onReady: function() {},

		/**
		 * 生命周期函数--监听页面显示
		 */
		onShow: function() {},

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
		onPullDownRefresh: function() {
			return this.loadOwnerHouse().then(() => uni.stopPullDownRefresh());
		},

		/**
		 * 页面上拉触底事件的处理函数
		 */
		onReachBottom: function() {},

		/**
		 * 用户点击右上角分享
		 */
		onShareAppMessage: function() {},
		methods: {
			bindAdditional(){uni.navigateTo({url:'/pages/bindOwner/bindOwner'});},
			changeOwner(event){
				const option=this.ownerOptions[Number(event.detail.value)];if(!option)return;
				try{directWechat.selectOwner(option.key,uni,constant.mapping);return this.loadOwnerHouse();}
				catch(error){uni.showToast({icon:'none',title:error.message});}
			},
			myHouseDetail: function(_item) {
				this.vc.navigateTo({
					url: '/pages/my/myHouseDetail?room=' + JSON.stringify(_item)
				});
			},

			/**
			 * 加载业主房屋信息
			 */
			loadOwnerHouse: function() {
				let _that = this;
				const requestId = ++this.roomRequestId;
				this.loading = true;
				this.loadError = '';
				this.rooms = [];
				if(this.wechatDirect && this.wechatPropertyLocked){this.loading=false;return Promise.resolve();}
				// #ifdef H5
				if(this.wechatDirect){
					const active=uni.getStorageSync(constant.mapping.OWNER_INFO)||{};
					this.ownerOptions=directWechat.owners(uni,constant.mapping).map(owner=>({key:directWechat.ownerKey(owner),label:(owner.communityName||'已绑定小区')+' · '+(owner.appUserName||'业主')}));
					this.activeOwnerIndex=Math.max(0,this.ownerOptions.findIndex(option=>option.key===directWechat.ownerKey(active)));
				}
				// #endif
				return getRooms().then(res => {
					if (requestId !== _that.roomRequestId) return;
					_that.rooms = res && Array.isArray(res.rooms) ? res.rooms : [];
					_that.loading = false;
				}).catch(() => {
					if (requestId !== _that.roomRequestId) return;
					_that.rooms = [];
					_that.loading = false;
					_that.loadError = '房屋信息加载失败，请重试或联系物业核实登记资料';
				});
			}
		}
	};
</script>
<style>
	@import "./myHouse.css";
</style>
