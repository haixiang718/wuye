

import url from '../constant/url.js'
import {requestNoAuth} from '../api/java110Request.js'
import {isNull} from '../utils/StringUtil.js'
import {getWAppId} from '../api/init/initApi.js'
import directWechat from './wechat-session.js';
import returnTarget from './return-target.js';

let authorizing = false;
export function finishLegacyEntry() {
	const identity=directWechat.read(uni);
	if(!identity)return;
	const target=identity.hasBinding===false?'/pages/bindOwner/bindOwner':returnTarget.consume(uni)||'/pages/my/myHouse';
	uni.redirectTo({url:target});
}
export function isWechatBrowser() {
	return typeof navigator !== 'undefined' && /MicroMessenger/i.test(navigator.userAgent || '');
}
export function ensureWechatEntry(options = {}, callback = () => {}) {
	if (typeof window === 'undefined' || options.authTicket || options.key) return;
	if(options.oauthError){callback();return;}
	const identity = directWechat.read(uni);
	const login = uni.getStorageSync('loginFlag');
	if (identity && (identity.hasBinding === false || login && login.expireTime > Date.now())) {callback();return;}
	if (!isWechatBrowser()) {callback();return;}
	wechatRefreshToken();
}


/**
 * 
 * H5 刷新鉴权登录
 * @param {Object} errorUrl 登录失败时的跳转页面
 * @param {Object} _login  
 * 
 * add by 吴学文 QQ 928255095
 */
export function wechatRefreshToken(errorUrl, _login) {
		if (!isWechatBrowser()) {
			uni.showToast({icon:'none',title:'请在微信中打开公众号页面'});
			return;
		}
		if (authorizing) return;
		authorizing = true;
		returnTarget.remember(window.location.hash,uni);
		let _errorUrl = errorUrl;
		if (errorUrl == null || errorUrl == undefined || errorUrl == '') {
			_errorUrl = '/#/pages/showlogin/showlogin?oauthError=1&wAppId='+getWAppId();
		}
		
		if(isNull(_login)){
			_login = 0; // 不是登录页面鉴权
		}
		requestNoAuth({
			url: url.wechatRefrashToken,
			method: 'get',
			data: {
				redirectUrl: window.location.href, // 当前页地址
				errorUrl: _errorUrl,
				loginFlag: _login
			},
			success: function(res) {
				let _param = res.data;
				if (_param.code == 0) {
					window.location.href = _param.data.openUrl;
					return;
				}
				authorizing = false;
				uni.showToast({icon:'none',title:_param && _param.msg || '微信授权暂不可用，请重试'});
			},
			fail: function(error) {
				authorizing = false;
				// 调用服务端登录接口失败
				if (error.statusCode == 401) {
					return;
				}
			}
		});
	}
