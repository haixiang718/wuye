<template>
  <view class="history-page">
    <!-- #ifdef H5 -->
    <wechat-binding-gate />
    <!-- #endif -->
    <view v-if="!wechatPropertyLocked">
      <view class="history-heading">缴费历史<text class="history-subtitle">当前小区 · 已缴费用</text></view>
      <view v-for="item in feeDetails" :key="item.detailId" class="history-card">
        <view class="history-top"><view class="history-name">{{item.feeName || '费用'}}</view><view class="history-amount">¥{{money(item.receivedAmount) || '金额待核实'}}</view></view>
        <view class="history-row"><text>房屋 / 车辆</text><text>{{item.payerObjName || '—'}}</text></view>
        <view class="history-row"><text>缴费时间</text><text>{{item.createTime || '—'}}</text></view>
        <view class="history-row"><text>费用账期</text><text>{{date(item.startTime)}} 至 {{date(item.endTime)}}</text></view>
        <view class="history-row"><text>缴费编码</text><text>{{item.detailId}}</text></view>
        <view v-if="item.remark" class="history-row"><text>备注</text><text>{{item.remark}}</text></view>
      </view>
      <view v-if="loading" class="history-state">正在加载缴费记录…</view>
      <view v-else-if="error" class="history-state"><view>{{error}}</view><button class="cu-btn line-green margin-top" @click="retry">重新加载</button></view>
      <view v-else-if="noData" class="history-state">暂无缴费记录</view>
      <view v-else-if="feeDetails.length" class="history-state"><button v-if="hasMore" class="cu-btn line-green" @click="loadMore">加载更多</button><text v-else>已显示全部记录</text></view>
    </view>
  </view>
</template>
<script>
const context = require('../../context/Java110Context.js');
import {getCurOwner} from '../../api/owner/ownerApi.js';
import feeDisplay from '../../utils/fee-display.js';
export default {
  data() {
    return {feeDetails:[],communityId:'',feeId:'',page:0,row:20,total:0,noData:false,loading:false,error:'',requestId:0,initialized:false,active:false};
  },
  computed: {hasMore() {return this.page * this.row < this.total;}},
  onLoad(options) {
    this.active=true;
    if(options.fee) {try {this.feeId=JSON.parse(options.fee).feeId || '';} catch (_) {this.error='费用信息无效，请从缴费历史重新进入';return;}}
    this._sessionChanged=()=>{this.requestId++;this.feeDetails=[];this.page=0;this.loading=false;this.noData=false;if(this.initialized && this.active)this.reload();};
    if(uni.$on)uni.$on('owner-wechat-session-changed',this._sessionChanged);
    context.onLoad(options,()=>{this.initialized=true;if(this.active)this.reload();});
    // #ifndef H5
    this.initialized=true;this.reload();
    // #endif
  },
  onShow() {this.active=true;if(this.initialized)this.reload();},
  onHide() {this.active=false;this.requestId++;this.loading=false;this.feeDetails=[];},
  onUnload() {this.active=false;this.requestId++;if(uni.$off)uni.$off('owner-wechat-session-changed',this._sessionChanged);},
  onReachBottom() {this.loadMore();},
  onPullDownRefresh() {return this.reload().finally(()=>uni.stopPullDownRefresh());},
  methods: {
    money:feeDisplay.money,date:feeDisplay.date,
    reload() {this.requestId++;this.feeDetails=[];this.page=0;this.total=0;this.noData=false;this.error='';this.loading=false;return this.loadPage(1);},
    retry() {return this.page ? this.loadPage(this.page+1) : this.reload();},
    loadMore() {if(!this.loading && this.page * this.row < this.total)return this.loadPage(this.page+1);},
    async loadPage(page) {
      if(this.loading)return;
      const id=++this.requestId;
      this.loading=true;this.error='';
      try {
        if(this.wechatDirect && this.wechatPropertyLocked)throw new Error('请先绑定房产后查看');
        const owner=await getCurOwner();
        if(id!==this.requestId)return;
        if(!owner || !owner.communityId)throw new Error('请先绑定房产后查看');
        this.communityId=owner.communityId;
        const data={communityId:this.communityId,page,row:this.row};
        if(this.feeId)data.feeId=this.feeId;
        const response=await new Promise((resolve,reject)=>context.request({url:context.constant.url.queryFeeDetail,method:'GET',data,
          success:resolve,fail:()=>reject(new Error('网络连接失败，请稍后重试'))}));
        if(id!==this.requestId)return;
        const body=response.data || {};
        if(response.statusCode!==200 || body.code!=0 || !Array.isArray(body.feeDetails))throw new Error(response.statusCode===403?'房产绑定已失效，请重新绑定后查看':'缴费记录加载失败，请稍后重试');
        this.feeDetails=page===1?body.feeDetails:this.feeDetails.concat(body.feeDetails);
        this.total=Number(body.total)||0;this.page=page;this.noData=this.feeDetails.length===0;
      } catch(error) {
        if(id===this.requestId){this.error=error && error.message || '缴费记录加载失败，请稍后重试';this.noData=false;}
      } finally {if(id===this.requestId)this.loading=false;}
    }
  }
};
</script>
<style scoped>
.history-page{padding:24rpx;}
.history-heading{font-size:32rpx;font-weight:600;margin:8rpx 0 28rpx;}
.history-subtitle{display:block;font-size:24rpx;color:#7b8493;font-weight:400;margin-top:8rpx;}
.history-card{background:#fff;border-radius:16rpx;padding:28rpx;margin-bottom:20rpx;}
.history-top{display:flex;justify-content:space-between;gap:20rpx;border-bottom:1px solid #edf0f4;padding-bottom:22rpx;margin-bottom:20rpx;align-items:flex-start;}
.history-name{font-size:30rpx;min-width:0;overflow-wrap:anywhere;}
.history-amount{font-size:34rpx;color:#14836b;font-weight:600;flex-shrink:0;}
.history-row{display:flex;justify-content:space-between;gap:24rpx;font-size:24rpx;margin-top:14rpx;color:#737e8c;}
.history-row text:first-child{flex-shrink:0;}
.history-row text:last-child{text-align:right;color:#384454;overflow-wrap:anywhere;min-width:0;}
.history-state{text-align:center;color:#7b8493;padding:32rpx 0;}
</style>
