import localFeatures from './config/local-features.js'
localFeatures.install(uni)
import Vue from 'vue';
// #ifdef H5
import h5Runtime from './config/h5-runtime.js';
import directWechat from './auth/wechat-session.js';
import wechatBindingGate from './components/wechat-binding-gate.vue';
h5Runtime.install(uni);
Vue.component('wechat-binding-gate',wechatBindingGate);
Vue.mixin({
  data(){return {wechatIdentityRevision:0};},
  created(){this._refreshWechatIdentity=()=>{this.wechatIdentityRevision++;};uni.$on('owner-wechat-session-changed',this._refreshWechatIdentity);},
  onShow(){this.wechatIdentityRevision++;},
  beforeDestroy(){uni.$off('owner-wechat-session-changed',this._refreshWechatIdentity);},
  computed:{
    paymentEnabled(){return h5Runtime.publicConfig().paymentEnabled === true;},smsEnabled(){return h5Runtime.publicConfig().smsEnabled === true;},
    wechatDirect(){return true;},
    wechatNeedsBinding(){this.wechatIdentityRevision;return directWechat.isGuest(uni);},
    wechatIdentityReady(){this.wechatIdentityRevision;return directWechat.hasIdentity(uni);},
    wechatPropertyLocked(){return this.wechatNeedsBinding || !this.wechatIdentityReady;}
  }
});
// #endif
// #ifndef H5
Vue.mixin({computed:{paymentEnabled(){return true;},smsEnabled(){return true;},wechatDirect(){return false;},wechatNeedsBinding(){return false;},wechatIdentityReady(){return false;},wechatPropertyLocked(){return false;}}});
// #endif
import App from './App';

import vc from './core/vcFramework.js'
// 小程序全局分享设置
import share from './common/mixin/shareMixin.js'
Vue.mixin(share)

Vue.config.productionTip = false;

Vue.prototype.vc = vc;  

App.mpType = 'app';

const app = new Vue({
    ...App
});
app.$mount();
