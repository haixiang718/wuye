#!/usr/bin/env node
'use strict';
const path = require('node:path');
const fs = require('node:fs');
const {spawnSync} = require('node:child_process');
const root = path.resolve(__dirname, '..');
const app = path.join(root, 'wuye-xcx2/WechatOwnerService-v0.8');
const mode = process.argv[2] || 'build';
const portable = path.join(root, '.tools/owner-h5-node/node_modules/node/bin', process.platform === 'win32' ? 'node.exe' : 'node');
const node = fs.existsSync(portable) ? portable : process.execPath;
if (mode === 'serve') {
  const http = require('node:http');
  const output = path.join(app, 'unpackage/dist/build/h5');
  const staticAssets = require('../wuye-f/MicroCommunityWeb-v0.8/lib/static-assets.cjs').createStaticAssets(output);
  const types = {'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.png':'image/png','.jpg':'image/jpeg','.svg':'image/svg+xml','.woff':'font/woff','.woff2':'font/woff2','.ttf':'font/ttf'};
  http.createServer((req, res) => {
    let name;
    try { name = decodeURIComponent(new URL(req.url, 'http://localhost').pathname); }
    catch (_) { res.writeHead(400).end(); return; }
    if (name === '/wechat-test/config.js') {
      res.writeHead(200, {'Content-Type':types['.js'],'Cache-Control':'no-store'});
      res.end('window.__WUYE_WECHAT_TEST__ = {paymentEnabled:false};');
      return;
    }
    staticAssets(req, res, error => {
      if (error) { res.writeHead(500, {'Cache-Control':'no-store'}).end(); return; }
      const file = path.resolve(output, '.' + (name === '/' ? '/index.html' : name));
      if (!file.startsWith(output + path.sep)) { res.writeHead(403).end(); return; }
      fs.stat(file, (err, info) => {
        if (err || !info.isFile()) { res.writeHead(404).end(); return; }
        res.writeHead(200, {'Content-Type':types[path.extname(file)] || 'application/octet-stream','Cache-Control':'no-cache','Content-Length':info.size});
        if (req.method === 'HEAD') res.end(); else fs.createReadStream(file).pipe(res);
      });
    });
  }).listen(Number(process.env.OWNER_H5_PORT || 8000), '127.0.0.1', () => console.log('Owner H5: http://127.0.0.1:' + (process.env.OWNER_H5_PORT || 8000)));
} else if (mode === 'build' || mode === 'dev') {
  const started = Date.now();
  const result = spawnSync(node, [path.join(app, 'node_modules/@vue/cli-service/bin/vue-cli-service.js'), 'uni-' + (mode === 'build' ? 'build' : 'serve')], {
    cwd:app, stdio:'inherit', env:{...process.env, NODE_ENV:mode === 'build' ? 'production':'development', UNI_PLATFORM:'h5', UNI_INPUT_DIR:app, UNI_OUTPUT_DIR:path.join(app, 'unpackage/dist',mode === 'build'?'build':'dev','h5'), NODE_OPTIONS:'--openssl-legacy-provider'}
  });
  if (result.error) console.error(result.error.message);
  process.exitCode = result.status == null ? 1 : result.status;
  if (mode === 'build' && !process.exitCode) {
    const index = path.join(app, 'unpackage/dist/build/h5/index.html');
    if (!fs.existsSync(index) || fs.statSync(index).mtimeMs < started - 1000) {
      console.error('H5 compiler did not produce a fresh index.html.');
      process.exitCode = 1;
    }
  }
} else { console.error('Usage: node scripts/owner-h5.cjs build|dev|serve'); process.exitCode = 1; }
