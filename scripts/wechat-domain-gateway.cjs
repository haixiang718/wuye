'use strict';
const fs = require('node:fs');
const path = require('node:path');
const {createGateway} = require('./wechat-test-gateway.cjs');

// The fixed-domain demo uses a separate profile; the historical test profile is preserved.
const file = path.resolve(__dirname, '../.runtime/wechat-domain.json');
function config() {
  const value = JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
  if (value.publicBaseUrl !== 'https://h5.jiaanfuwu.cn' || value.gatewayPort !== 8080 || value.ownerH5Port !== 8000) {
    throw new Error('Unexpected fixed-domain ingress profile');
  }
  return value;
}
if (require.main === module) {
  config();
  createGateway({config, owner: 'http://127.0.0.1:8000'})
    .listen(8080, '127.0.0.1', () => console.log('Fixed-domain demo gateway: 127.0.0.1:8080'));
}
