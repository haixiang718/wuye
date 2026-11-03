import localFeatures from './config/local-features.js'
localFeatures.install(uni)
import Vue from 'vue';
// #ifdef H5
import h5Runtime from './config/h5-runtime.js';
h5Runtime.install(uni);
Vue.mixin({computed:{paymentEnabled(){return h5Runtime.publicConfig().paymentEnabled === true;},smsEnabled(){return h5Runtime.publicConfig().smsEnabled === true;}}});
// #endif
// #ifndef H5
Vue.mixin({computed:{paymentEnabled(){return true;},smsEnabled(){return true;}}});
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
