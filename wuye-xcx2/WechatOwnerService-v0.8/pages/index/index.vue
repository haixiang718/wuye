<template>
	<view>
		<view class="margin-bottom-xs">
			<uni-notice-bar showIcon="true" scrollable="true" single="true" speed="30" text="欢迎访问HC智慧家园"></uni-notice-bar>
		</view>

        <view class="home-services bg-white">
            <view v-for="(items,group) in categoryList" :key="group" class="home-grid">
                <view v-for="item in items" :key="item.href" class="home-service" @tap="toPage(item.href)">
                    <image :src="item.src" class="category-image"></image>
                    <view class="category-text">{{item.name}}</view>
                </view>
            </view>
        </view>
	</view>
</template>

<script>
	import uniNoticeBar from '@/components/uni-notice-bar/uni-notice-bar.vue'
	import {getCategoryList} from '../../api/index/indexApi.js'

	import {getCurOwner} from '../../api/owner/ownerApi.js'

	import {hasLogin} from '../../auth/Java110Auth.js'
	export default {
		data() {
			return {
				communityId: '',
				categoryList: {},
				selected: 0,
				mask1Hidden: true,
				mask2Hidden: true,
				animationData: "",
				location: "",
				characteristicSelected: [false, false, false, false, false, false, false],
				discountSelected: null,
				selectedNumb: 0,
				sortSelected: "综合排序"
			};
		},
		components: {
			uniNoticeBar
		},
		/**
		 * 生命周期函数--监听页面加载
		 */
		onLoad: function(options) {
			let _that = this;
			this.vc.onLoad(options);		
			//查询目录
			this.categoryList = getCategoryList();
		},

		/**
		 * 生命周期函数--监听页面初次渲染完成
		 */
		onReady: function() {},
		onShareAppMessage:function() {
			return {
				title: '首页',
				path: '/pages/index/index?wAppId='+this.vc.getWAppId(),
				success: function(res) {
					// 转发成功
				},
				fail: function(res) {
					// 转发失败
				}
			}
		},
		/**
		 * 生命周期函数--监听页面显示
		 */
		onShow: function() {
			let _that = this;
			_that.location = this.vc.getStorageSync('location');
			if (hasLogin()) {
				_that.judgeBindOwnerFun();
			}
			this._initIndexData();
		},

		methods: {
			_initIndexData: function() {
				let _that = this;
				//查询当前小区信息
				this.vc.getCurCommunity()
				.then(function(_communityInfo){
					_that.communityId = _communityInfo.communityId;
					//查询小区活动信息

				})
			},
			judgeBindOwnerFun: function() {
				//查询业主
				getCurOwner();
			},
			showModal: function(e) {
				this.vc.navigateTo({
					url: '../bindOwner/bindOwner'
				});
			},
			toPage: function(pageUrl) {
				this.vc.navigateTo({
					url: pageUrl
				});
			},
		}
	};
</script>
<style>
	@import "./index.css";
.home-services{padding:32rpx 12rpx;border-radius:16rpx;margin:20rpx;}
.home-grid{display:flex;}
.home-service{flex:1;min-width:0;text-align:center;padding:16rpx 0;}

	.bock-icon {
		height: 34upx;
		width: 14upx;
		line-height: 100upx;
		background-color: #00AA00;
	}

	.margin-top-1 {
		margin-top: 2upx;
	}

	.tec-height {
		height: 120upx;
	}
</style>
