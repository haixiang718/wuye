<template>
	<view>
		<!-- #ifdef H5 -->
		<wechat-binding-gate />
		<!-- #endif -->
		<view v-if="!wechatPropertyLocked">
		<view class="header_fixed">
			<scroll-view v-if="rooms.length <5 && rooms.length >1" class="bg-white nav">
				<view class="flex text-center">
					<view class="cu-item flex-sub" :class="item.roomId==curRoom.roomId?'text-green cur':''" v-for="(item,index) in rooms"
					 :key="index" @tap="switchRoom(item)" :data-id="index">
						{{item.floorNum}}栋{{item.roomNum}}室
					</view>
				</view>
			</scroll-view>
			<scroll-view v-if="rooms.length >4" scroll-x class="bg-white nav" scroll-with-animation scroll-left="true">
				<view class="cu-item flex-sub" :class="item.roomId==curRoom.roomId?'text-green cur':''" v-for="(item,index) in rooms"
				 :key="index" @tap="switchRoom(item)" :data-id="index">
					{{item.floorNum}}栋{{item.roomNum}}室
				</view>
			</scroll-view>
		</view>
		<view v-if="rooms.length == 1" class="block__title">{{rooms[0].floorNum}}栋{{rooms[0].roomNum}}室</view>
		<view v-if="rooms.length > 1" class="margin-header-top"></view>
		<view v-if="noData == false">
			<view class="cu-list menu" v-if="moreRooms.length > 0" v-for="(fee, idx) in moreRooms" :key="idx" :data-item="fee"
			 @click="payFee(fee)">
				<view class="cu-item arrow">
					<view class="content padding-tb-sm">
						<view>
							<view class="text-cut" style="width:220px">{{fee.feeName}}</view>
						</view>
					</view>
				</view>
			</view>
		</view>
		<view v-else>
			<no-data-page></no-data-page>
		</view>
		<view class=" bg-white  border flex justify-end" style="position: fixed;width: 100%; bottom: 0;">
			<view class="btn-group line-height">
				<button class="cu-btn bg-red shadow-blur lgplus sharp" @click="toRoomOweFee()">欠费缴费</button>
				
			</view>
		</view>
		</view>
	</view>
</template>

<script>
	// pages/payParkingFeeList/payParkingFeeList.js
	const context = require("../../context/Java110Context.js");
	const constant = context.constant;
	import noDataPage from '@/components/no-data-page/no-data-page.vue'

	import {
		getRooms
	} from '../../api/room/roomApi.js'

	import {
		getRoomFees
	} from '../../api/fee/feeApi.js'
	

	export default {
		data() {
			return {
				rooms: [],
				curRoom: {},
				requestedRoomId: '',
				moreRooms: [],
				feeRequestId: 0,
				needFefresh: true,
				noData: false
			};
		},

		components: {
			noDataPage
		},
		/**
		 * 生命周期函数--监听页面加载
		 */
		onLoad: function(options) {
			this.requestedRoomId = options.roomId || '';
			context.onLoad(options,()=>{if(options.authTicket || options.key)this.loadRoomBills();});
		},
		/**
		 * 生命周期函数--监听页面显示
		 */
		onShow: function() {return this.loadRoomBills();},
		methods: {
			loadRoomBills: function() {
			let _that = this;
			if(this.wechatDirect && this.wechatPropertyLocked){this.rooms=[];this.moreRooms=[];this.noData=true;return;}
			if (!this.needFefresh) {
				this.needFefresh = true;
				return;
			}
			this.noData = false;
			this.moreRooms = [];
			let _roomRequestId = ++this.feeRequestId;
			return getRooms(this.requestedRoomId).then(data => {
				if (_roomRequestId !== _that.feeRequestId) return;
				let _rooms = data.rooms;
				this.rooms = _rooms;
				let _owner = data.owner;
				_that.moreRooms = [];
				_rooms.forEach(function(_room) {
					_room.communityId = _owner.communityId;
					_room.communityName = _owner.communityName;
				});
				let _selectedRoom = _that.requestedRoomId
					? _rooms.find(_room => _room.roomId == _that.requestedRoomId) : _rooms[0];
				if (!_selectedRoom) {
					_that.curRoom = {};
					_that.noData = true;
					if (_that.requestedRoomId) {
						uni.showToast({ icon: 'none', title: '通知中的房屋不存在或未绑定，请核实房屋信息' });
					}
					return;
				}
				_that.curRoom = _selectedRoom;
				return _that._loadRoomFee();
			}).catch(() => {
				if (_roomRequestId !== _that.feeRequestId) return;
				_that.rooms = [];
				_that.curRoom = {};
				_that.moreRooms = [];
				_that.noData = true;
				uni.showToast({ icon: 'none', title: '房屋信息加载失败，请稍后重试' });
			});
		},
			payFee: function(_item) {
				wx.navigateTo({
					url: '/pages/roomFee/roomFee?fee=' + JSON.stringify(_item),
				})
			},
			_loadRoomFee: function() {
				let _that = this;
				let _room = this.curRoom;
				let _requestId = ++this.feeRequestId;
				let _objData = {
					page: 1,
					row: 30,
					payerObjId: _room.roomId,
					communityId: _room.communityId,
					state: '2008001'
				}
				_that.moreRooms = [];
				return getRoomFees(_objData, _room)
					.then((moreRooms) => {
						if (_requestId !== _that.feeRequestId || _room.roomId !== _that.curRoom.roomId) return;
						_that.moreRooms = Array.isArray(moreRooms) ? moreRooms : [];
						_that.noData = _that.moreRooms.length === 0;
					}, () => {
						if (_requestId !== _that.feeRequestId || _room.roomId !== _that.curRoom.roomId) return;
						_that.noData = true;
						uni.showToast({ icon: 'none', title: '费用加载失败，请稍后重试' });
					})

			},
			payFeeDetail: function(_item) {
				wx.navigateTo({
					url: '/pages/payFeeDetail/payFeeDetail?fee=' + JSON.stringify(_item),
				});
			},
			switchRoom: function(_room) {
				this.requestedRoomId = _room.roomId;
				this.curRoom = _room;
				this.noData = false;
				this._loadRoomFee();
			},
			toRoomOweFee:function(){
				if(this.vc.isEmpty(this.curRoom.roomId)){
					uni.showToast({
						icon:'none',
						title:'没有房屋'
					});
					return;
				}
				this.vc.navigateTo({
					url:'/pages/roomOweFee/roomOweFee?roomId='+this.curRoom.roomId
				});
			}
		}
	};
</script>
<style>
	@import "./roomFeeListNew.css";

	.solid-bottom::after {
		border-bottom: 2upx solid rgba(0, 0, 0, 0.1);
	}

	.solid-top::after {
		border-top: 2upx solid rgba(0, 0, 0, 0.1);
	}

	.margin-header-top {
		height: 100upx;
	}

	/*  #ifdef  APP-PLUS || MP-WEIXIN  */
	.header_fixed {
		position: fixed;
		top: 0upx;
		left: 0;
		width: 100%;
		z-index: 2;
	}

	/*  #endif  */
	/*  #ifdef  H5  */
	.header_fixed {
		position: fixed;
		top: 80upx;
		left: 0;
		width: 100%;
		z-index: 2;
	}

	/*  #endif  */
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
