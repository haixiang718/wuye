const context = require('../context/Java110Context.js');
const PENDING_MESSAGE = '支付结果确认中，请稍后刷新账单';
const active = new Map();

function toast(title) {
	uni.showToast({ icon: 'none', title, duration: 3000 });
}

function scope(appId) {
	try {
		const currentApp = uni.getStorageSync(context.constant.mapping.W_APP_ID);
		const user = context.getUserInfo();
		if (!currentApp || !user || !user.userId || (appId && appId !== currentApp)) return null;
		const queryUrl = context.constant.url.preOrder.replace(/app\/payment\/[^/?]+(?:\?.*)?$/, 'app/payment/v3/query');
		if (queryUrl === context.constant.url.preOrder) return null;
		return { key: 'payment-v3-pending:' + JSON.stringify([queryUrl, currentApp, user.userId]), queryUrl };
	} catch (error) {
		return null;
	}
}

function isCurrent(owner) {
	const current = scope();
	return current && current.key === owner.key;
}

function readPending(owner) {
	try {
		const saved = uni.getStorageSync(owner.key);
		return saved && typeof saved.orderId === 'string' && saved.orderId ? saved : null;
	} catch (error) {
		return null;
	}
}

function remember(owner, orderId) {
	try { uni.setStorageSync(owner.key, { orderId }); } catch (error) { /* Confirmation still works if storage is full. */ }
}

function forget(owner, orderId) {
	try {
		const saved = readPending(owner);
		if (saved && saved.orderId === orderId) uni.removeStorageSync(owner.key);
	} catch (error) { /* A retained ID will be safely rechecked next time. */ }
}

function waitForBridge() {
	return new Promise((resolve, reject) => {
		if (typeof window === 'undefined' || typeof document === 'undefined') return reject(new Error('BRIDGE_UNAVAILABLE'));
		if (window.WeixinJSBridge && typeof window.WeixinJSBridge.invoke === 'function') return resolve(window.WeixinJSBridge);
		let timer;
		const cleanup = () => {
			clearTimeout(timer);
			document.removeEventListener('WeixinJSBridgeReady', ready);
		};
		const ready = () => {
			if (!window.WeixinJSBridge || typeof window.WeixinJSBridge.invoke !== 'function') return;
			cleanup();
			resolve(window.WeixinJSBridge);
		};
		document.addEventListener('WeixinJSBridgeReady', ready);
		timer = setTimeout(() => { cleanup(); reject(new Error('BRIDGE_TIMEOUT')); }, 8000);
		ready();
	});
}

function invokePayment(data, owner) {
	return waitForBridge().then(bridge => new Promise(resolve => {
		if (owner && !isCurrent(owner)) return resolve({ err_msg: 'identity:changed' });
		let finished = false;
		const finish = result => {
			if (finished) return;
			finished = true;
			clearTimeout(timer);
			resolve(result || {});
		};
		const timer = setTimeout(() => finish({ err_msg: 'bridge:timeout' }), 120000);
		try {
			bridge.invoke('getBrandWCPayRequest', {
				appId: data.appId,
				timeStamp: String(data.timeStamp),
				nonceStr: data.nonceStr,
				package: data.package,
				signType: data.signType || 'MD5',
				paySign: data.paySign || data.sign
			}, finish);
		} catch (error) { finish({ err_msg: 'bridge:fail' }); }
	})).catch(() => ({ err_msg: 'bridge:unavailable' }));
}

function query(owner, orderId, action = 'query') {
	return new Promise((resolve, reject) => {
		if (!isCurrent(owner)) return reject(new Error('IDENTITY_CHANGED'));
		let finished = false;
		const finish = (error, data) => {
			if (finished) return;
			finished = true;
			clearTimeout(timer);
			if (error) reject(error); else resolve(data);
		};
		const timer = setTimeout(() => finish(new Error('QUERY_TIMEOUT')), 12000);
		try {
			context.request({
				url: action === 'close' ? owner.queryUrl.replace(/\/query$/, '/close') : owner.queryUrl,
				method: 'POST', header: context.getHeaders(), data: { orderId }, timeout: 10000,
				success(res) {
					const data = res && res.data;
					if (!isCurrent(owner) || res.statusCode !== 200 || !data || String(data.code) !== '0'
						|| data.orderId !== orderId || ['POSTED', 'CREATED', 'CLOSED'].indexOf(data.state) < 0) {
						finish(new Error('UNCONFIRMED_PAYMENT'));
					} else finish(null, data);
				},
				fail(error) { finish(error || new Error('QUERY_FAILED')); }
			});
		} catch (error) { finish(error); }
	});
}

async function confirm(owner, orderId, bridgeResult, success, fail) {
	let result = { orderId, state: 'PENDING' };
	const message = bridgeResult && bridgeResult.err_msg;
	const bridgeSucceeded = message === 'get_brand_wcpay_request:ok';
	const attempts = !bridgeResult || bridgeSucceeded ? 5 : 1;
	for (let attempt = 0; attempt < attempts; attempt++) {
		try { result = await query(owner, orderId); } catch (error) { break; }
		if (result.state === 'POSTED' || result.state === 'CLOSED') break;
		if (attempt + 1 < attempts) await new Promise(resolve => setTimeout(resolve, 1500));
	}
	return report(owner, result, message, success, fail);
}

function report(owner, result, message, success, fail) {
	const orderId = result.orderId;
	if (!isCurrent(owner)) return { orderId, state: 'IDENTITY_CHANGED' };
	if (result.state === 'POSTED') {
		forget(owner, orderId);
		if (typeof success === 'function') success(result);
		return result;
	}
	if (result.state === 'CLOSED') {
		forget(owner, orderId);
		toast('订单已关闭，请刷新账单');
	} else if (result.state === 'CREATED' && /cancel/.test(message || '')) {
		toast('已取消支付');
	} else if (result.state === 'CREATED' && typeof message === 'string'
		&& /^get_brand_wcpay_request:fail(?:$|[\s_:])/.test(message)) {
		// Only display the bridge's error text after the authenticated server confirms unpaid state.
		const detail = message.replace(/[\u0000-\u001f\u007f]/g, ' ').slice(0, 160);
		uni.showModal({
			title: '微信未完成支付',
			content: '微信提示：' + detail + '\n服务端尚未确认收款。可再次点击支付，选择关闭待支付订单后重试。',
			showCancel: false,
			confirmText: '知道了'
		});
	} else {
		toast(PENDING_MESSAGE);
	}
	if (typeof fail === 'function') fail(result);
	return result;
}

function single(owner, operation) {
	if (active.has(owner.key)) return active.get(owner.key);
	const promise = Promise.resolve().then(operation);
	const settled = promise.then(result => { active.delete(owner.key); return result; }, error => { active.delete(owner.key); throw error; });
	active.set(owner.key, settled);
	return settled;
}

class WexinPayFactory {
	wexinPay(data, success, fail) {
		data = data || {};
		if (typeof navigator === 'undefined' || !/MicroMessenger/i.test(navigator.userAgent || '')) {
			toast('请在微信中打开此页面后支付');
			return Promise.resolve({ state: 'UNAVAILABLE' });
		}
		const v3 = !!data.orderId || data.signType === 'RSA';
		if (!data.appId || !data.timeStamp || !data.nonceStr || !data.package || !(data.paySign || data.sign)
			|| (v3 && (!data.orderId || data.signType !== 'RSA'))) {
			toast('支付参数不完整，请刷新账单后重试');
			return Promise.resolve({ state: 'INVALID' });
		}
		if (!v3) {
			return invokePayment(data).then(result => {
				if (result.err_msg === 'get_brand_wcpay_request:ok') {
					if (typeof success === 'function') success(result);
				} else {
					toast(/cancel/.test(result.err_msg || '') ? '已取消支付' : '支付失败，请在微信中重试');
					if (typeof fail === 'function') fail(result);
				}
				return result;
			});
		}
		const owner = scope(data.appId);
		if (!owner) {
			toast('登录状态已变化，请重新登录后支付');
			return Promise.resolve({ state: 'LOGIN_REQUIRED' });
		}
		return single(owner, async () => {
			const saved = readPending(owner);
			if (saved && saved.orderId !== data.orderId) return confirm(owner, saved.orderId, null, success, fail);
			remember(owner, data.orderId);
			const bridgeResult = await invokePayment(data, owner);
			return confirm(owner, data.orderId, bridgeResult, success, fail);
		});
	}

	resumePending(success, fail) {
		const owner = scope();
		if (!owner) return Promise.resolve({ state: 'NONE' });
		const saved = readPending(owner);
		if (!saved) return Promise.resolve({ state: 'NONE' });
		return single(owner, () => confirm(owner, saved.orderId, null, success, fail));
	}

	preparePayment() {
		const owner = scope();
		if (!owner) return Promise.resolve({ state: 'NONE' });
		const saved = readPending(owner);
		if (!saved) return Promise.resolve({ state: 'NONE' });
		return single(owner, async () => {
			const result = await confirm(owner, saved.orderId, null);
			if (result.state !== 'CREATED') return result;
			const closeRequested = await new Promise(resolve => {
				try {
					uni.showModal({
						title: '存在待确认的支付订单',
						content: '可继续等待支付结果，或请求关闭待支付订单后重新下单。关闭操作不会扣款或发起退款；若支付已完成，以服务端入账结果为准。',
						confirmText: '关闭待支付订单', cancelText: '继续等待',
						success: choice => resolve(choice.confirm === true),
						fail: () => resolve(false)
					});
				} catch (error) { resolve(false); }
			});
			if (!isCurrent(owner)) return { orderId: saved.orderId, state: 'IDENTITY_CHANGED' };
			if (!closeRequested) return result;
			let closed = { orderId: saved.orderId, state: 'PENDING' };
			try { closed = await query(owner, saved.orderId, 'close'); } catch (error) { /* Keep recovery until server confirmation. */ }
			return report(owner, closed);
		});
	}
}

module.exports = new WexinPayFactory();
