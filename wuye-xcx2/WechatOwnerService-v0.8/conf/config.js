/**
 * HC智慧家园配置文件
 * 
 * 本项目只有这里修改相应配置信息，如果不是二次开发 请不要修改其他文件内容
 * 
 * @website http://www.homecommunity.cn/
 * @author 吴学文
 * @QQ 928255095
 */

import h5Runtime from '../config/h5-runtime.js';
const runtimeConfig = h5Runtime.publicConfig();
// #ifdef H5
// 服务器域名 公众号时，配置为 / 就可以
const baseUrl = '/';
// #endif
const commonBaseUrl = 'http://127.0.0.1:8012/';




// #ifndef H5
//服务器域名 小程序 或者 app 时 后端地址
const baseUrl = 'http://127.0.0.1:8012/';
// #endif

//默认的小区ID和名称，用户还没有登录时显示的小区信息
const DEFAULT_COMMUNITY_ID = runtimeConfig.communityId || "7020181217000001";
const DEFAULT_COMMUNITY_NAME = runtimeConfig.communityName || "丰仪家园";

// APP 或者 公众号  appId
const wAppId = runtimeConfig.appId || "";

//app支付时这里需要填写支付秘钥
const appPayKey = "";

const logLevel = "WARN"; // 登录请求中含有凭据，不记录请求内容

export default {
	baseUrl: baseUrl,
	commonBaseUrl: commonBaseUrl,
	DEFAULT_COMMUNITY_ID: DEFAULT_COMMUNITY_ID,
	DEFAULT_COMMUNITY_NAME: DEFAULT_COMMUNITY_NAME,
	wAppId: wAppId,
	logLevel: logLevel,
	appPayKey: appPayKey
}
