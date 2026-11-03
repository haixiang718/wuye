'use strict';
const key = 'owner_return_target';
const routes = new Set(['/pages/roomFeeListNew/roomFeeListNew', '/pages/roomOweFee/roomOweFee']);
function normalize(value) {
  if (typeof value !== 'string' || value.length > 2048) return '';
  const parts = value.replace(/^#/, '').split('?');
  if (parts.length > 2 || !routes.has(parts[0])) return '';
  const params = [];
  try {
    for (const entry of (parts[1] || '').split('&').filter(Boolean)) {
      const split = entry.indexOf('=');
      const name = decodeURIComponent(split < 0 ? entry : entry.slice(0, split));
      const value = decodeURIComponent(split < 0 ? '' : entry.slice(split + 1));
      if (name === 'roomId') {
        if (!/^[a-zA-Z0-9_-]{1,100}$/.test(value) || params.length) return '';
        params.push('roomId=' + encodeURIComponent(value));
      }
    }
  } catch (_) { return ''; }
  return parts[0] + (params.length ? '?' + params.join('&') : '');
}
function remember(value, runtime) {
  const route = normalize(value);
  if (route) runtime.setStorageSync(key, {route, createdAt:Date.now()});
}
function consume(runtime) {
  const record = runtime.getStorageSync(key);
  runtime.removeStorageSync(key);
  if (!record || !Number.isFinite(record.createdAt) || record.createdAt > Date.now() || Date.now() - record.createdAt > 30 * 60 * 1000) return '';
  return normalize(record.route);
}
module.exports = {remember, consume};
