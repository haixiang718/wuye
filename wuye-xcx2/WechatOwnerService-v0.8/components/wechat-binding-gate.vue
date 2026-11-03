<template>
  <view v-if="wechatDirect && (wechatNeedsBinding || !wechatIdentityReady)" class="padding margin bg-white radius text-center">
    <view class="text-lg margin-bottom">{{wechatNeedsBinding ? '微信已登录，尚未绑定房产' : (inWechat ? '请完成微信授权' : '请在微信中打开')}}</view>
    <view class="text-gray margin-bottom">{{wechatNeedsBinding ? '填写与物业登记一致的姓名、手机号和身份证号，即可查看自己的房屋和账单。' : '从公众号菜单进入，完成微信授权后即可绑定房产，无需注册账号。'}}</view>
    <button v-if="wechatNeedsBinding" class="cu-btn bg-green" @click="bind">绑定房产</button>
    <button v-else-if="inWechat" class="cu-btn bg-green" @click="authorize">重新微信授权</button>
  </view>
</template>
<script>
import returnTarget from '../auth/return-target.js';
import {wechatRefreshToken,isWechatBrowser} from '../auth/H5Login.js';
export default {computed:{inWechat(){return isWechatBrowser();}},methods:{authorize(){wechatRefreshToken();},bind(){
  if(typeof window!=='undefined')returnTarget.remember(window.location.hash,uni);
  uni.navigateTo({url:'/pages/bindOwner/bindOwner'});
}}};
</script>
