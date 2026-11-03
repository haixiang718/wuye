'use strict';
const fs = require('node:fs');
const path = require('node:path');
const {createGateway} = require('../../gateway/wechat-test-gateway.cjs');
const directory = process.env.WUYE_CONFIG_DIR || '/etc/wuye';
if (!fs.existsSync(path.join(directory, 'READY_FOR_NONPAYMENT'))) throw new Error('Reviewed configuration marker missing.');
function config() {
  const text = fs.readFileSync(path.join(directory, 'wechat.json'), 'utf8');
  if (/REPLACE_IN_PRODUCTION|PRODUCTION_HOST_REQUIRED/.test(text)) throw new Error('Configuration still contains a placeholder.');
  const value = JSON.parse(text);
  const origin = new URL(value.publicBaseUrl);
  if (origin.protocol !== 'https:' || origin.origin !== value.publicBaseUrl || origin.username || origin.password
    || !value.appId || !value.communityId || !value.callbackPath || !value.token
    || typeof value.accessCode !== 'string' || value.accessCode.length < 16
    || typeof value.sessionSecret !== 'string' || value.sessionSecret.length < 32) throw new Error('Incomplete restricted gateway config.');
  return value;
}
config();
createGateway({config}).listen(8080, '127.0.0.1', () => console.log('Restricted non-payment gateway ready on loopback.'));
