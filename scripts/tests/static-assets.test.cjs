'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const os = require('node:os');
const {spawn} = require('node:child_process');

function request(server, url, headers = {}, method = 'GET', body) {
  return new Promise((resolve, reject) => {
    const req = http.request({hostname:'127.0.0.1', port:server.address().port, path:url, headers, method}, res => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => resolve({status:res.statusCode, headers:res.headers, body:Buffer.concat(chunks)}));
    });
    req.on('error', reject);
    req.end(body);
  });
}

test('admin compresses static scripts while preserving ranges, HTML freshness and API responses', async t => {
  const received = [];
  const upstream = http.createServer(async (req, res) => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    received.push({url:req.url,method:req.method,body:Buffer.concat(chunks),cookie:req.headers.cookie});
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({privateData:'synthetic-only'}));
  });
  await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve));
  const oldBackend = process.env.BACKEND_URL;
  process.env.BACKEND_URL = 'http://127.0.0.1:' + upstream.address().port;
  const appPath = require.resolve('../../wuye-f/MicroCommunityWeb-v0.8/app');
  delete require.cache[appPath];
  const server = require(appPath).listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(() => {
    server.close(); upstream.close(); delete require.cache[appPath];
    if (oldBackend === undefined) delete process.env.BACKEND_URL; else process.env.BACKEND_URL = oldBackend;
  });
  const url = '/js/plugins/echart/echarts.min.js';
  const original = fs.readFileSync(path.resolve(__dirname, '../../wuye-f/MicroCommunityWeb-v0.8/public' + url));
  const compressed = await request(server, url, {'Accept-Encoding':'gzip'});
  assert.equal(compressed.status, 200);
  assert.equal(compressed.headers['content-encoding'], 'gzip');
  assert.deepEqual(zlib.gunzipSync(compressed.body), original);
  assert.ok(compressed.body.length < original.length / 2);
  assert.match(compressed.headers.vary, /Accept-Encoding/i);
  assert.match(compressed.headers['cache-control'], /no-cache/);
  const fresh = await request(server, url, {'Accept-Encoding':'gzip', 'If-None-Match':compressed.headers.etag});
  assert.equal(fresh.status, 304);
  assert.equal(fresh.body.length, 0);
  const head = await request(server, url, {'Accept-Encoding':'gzip'}, 'HEAD');
  assert.equal(head.status, 200);
  assert.equal(head.body.length, 0);
  assert.equal(Number(head.headers['content-length']), compressed.body.length);
  const plain = await request(server, url, {'Accept-Encoding':'gzip;q=0, *;q=1'});
  assert.equal(plain.headers['content-encoding'], undefined);
  assert.deepEqual(plain.body, original);
  const range = await request(server, url, {Range:'bytes=0-9', 'Accept-Encoding':'gzip'});
  assert.equal(range.status, 206);
  assert.deepEqual(range.body, original.subarray(0,10));
  assert.equal(range.headers['content-encoding'], undefined);
  const html = await request(server, '/admin.html', {'Accept-Encoding':'gzip'});
  assert.match(html.headers['cache-control'], /no-cache|no-store/);
  assert.doesNotMatch(html.headers['cache-control'], /immutable/);
  const api = await request(server, '/app/fee.listFee', {'Accept-Encoding':'gzip'});
  assert.equal(api.headers['cache-control'], 'no-store');
  assert.equal(api.headers['content-encoding'], undefined);
  assert.deepEqual(JSON.parse(api.body), {privateData:'synthetic-only'});
  const scaffold = await request(server, '/users');
  assert.equal(scaffold.status,404);
  assert.doesNotMatch(scaffold.body.toString(),/respond with a resource/);
  const json = Buffer.from('{ "synthetic": "preserve original bytes" }\n');
  const submitted = await request(server,'/app/payment/v3/query',{'Content-Type':'application/json',
    'Content-Length':String(json.length),Cookie:'synthetic_session=local-only'},'POST',json);
  assert.equal(submitted.status,200);
  assert.deepEqual(received.at(-1),{url:'/app/payment/v3/query',method:'POST',body:json,cookie:'synthetic_session=local-only'});
  const missing = await request(server,'/missing-static-file.js');
  assert.equal(missing.status,404,'Legacy error template still renders after compatible dependency patches');
  assert.doesNotMatch(missing.body.toString(),/MicroCommunityWeb|node_modules|app\.js:\d|at (?:Layer|Function)|[A-Z]:[\\/]/,
    'An unauthenticated missing path must not disclose filesystem paths or stack frames');
  assert.equal((await request(server, '/components/property/addMachine/addMachine.js')).status,404);
});

test('static validators follow file changes and reject private paths without sharing cached bytes', async t => {
  const {createStaticAssets} = require('../../wuye-f/MicroCommunityWeb-v0.8/lib/static-assets.cjs');
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'wuye-static-'));
  const file = path.join(directory, 'static/js/index.1234abcd.js');
  fs.mkdirSync(path.dirname(file),{recursive:true});
  fs.writeFileSync(file,'const version = "one";\n'.repeat(100));
  fs.writeFileSync(path.join(directory,'.private.js'),'private-only');
  const middleware = createStaticAssets(directory);
  const server = http.createServer((req,res) => middleware(req,res,error => {
    res.writeHead(error ? 500 : 404,{'Cache-Control':'no-store'}); res.end();
  }));
  await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
  t.after(() => {server.close(); fs.rmSync(directory,{recursive:true,force:true});});
  const url = '/static/js/index.1234abcd.js';
  const first = await request(server,url,{'Accept-Encoding':'br, gzip;q=0.8'});
  assert.equal(first.status,200);
  assert.equal(first.headers['cache-control'],'private, max-age=31536000, immutable');
  const same = await request(server,url,{'If-Modified-Since':first.headers['last-modified']});
  assert.equal(same.status,304);
  const strong = first.headers.etag.replace(/^W\//,'');
  assert.equal((await request(server,url,{'If-None-Match':'"another", '+strong})).status,304);
  fs.writeFileSync(file,'const version = "two";\n'.repeat(100));
  const future = new Date(Date.now()+2000);
  fs.utimesSync(file,future,future);
  const updated = await request(server,url,{'Accept-Encoding':'gzip','If-None-Match':first.headers.etag});
  assert.equal(updated.status,200);
  assert.notEqual(updated.headers.etag,first.headers.etag);
  assert.match(zlib.gunzipSync(updated.body).toString(),/version = "two"/);
  for (const url of ['/.private.js','/%2eprivate.js','/%2e%2e%2fsecret.js','/missing.js','/static/js/%00.js']) {
    assert.equal((await request(server,url)).status,404,url);
  }
  assert.equal((await request(server,url,{},'POST')).status,404);
});

test('owner H5 serve command applies static compression and keeps runtime configuration private', async t => {
  const probe = http.createServer();
  await new Promise(resolve => probe.listen(0,'127.0.0.1',resolve));
  const port = probe.address().port;
  await new Promise(resolve => probe.close(resolve));
  const child = spawn(process.execPath,[path.resolve(__dirname,'../owner-h5.cjs'),'serve'],{
    env:{...process.env,OWNER_H5_PORT:String(port)},stdio:['ignore','pipe','pipe']
  });
  t.after(() => child.kill());
  await new Promise((resolve,reject) => {
    const timer = setTimeout(() => reject(new Error('H5 static server did not start')),10000);
    child.once('error',error=>{clearTimeout(timer);reject(error);});
    child.once('exit',code=>{clearTimeout(timer);reject(new Error('H5 static server exited: '+code));});
    child.stdout.once('data',()=>{clearTimeout(timer);resolve();});
  });
  const server = {address:()=>({port})};
  const config = await request(server,'/wechat-test/config.js',{'Accept-Encoding':'gzip'});
  assert.equal(config.status,200);
  assert.equal(config.headers['cache-control'],'no-store');
  assert.match(config.body.toString(),/paymentEnabled:false/);
  const output = path.resolve(__dirname,'../../wuye-xcx2/WechatOwnerService-v0.8/unpackage/dist/build/h5');
  // The repository excludes build output; check a compiled asset when available.
  if (!fs.existsSync(path.join(output,'index.html'))) return;
  const html = await request(server,'/');
  assert.equal(html.headers['cache-control'],'no-store');
  const name = fs.readdirSync(path.join(output,'static/js')).find(name=>/^chunk-vendors\.[a-f0-9]+\.js$/.test(name));
  assert.ok(name,'compiled vendor chunk');
  const asset = await request(server,'/static/js/'+name,{'Accept-Encoding':'gzip'});
  assert.equal(asset.headers['content-encoding'],'gzip');
  assert.equal(asset.headers['cache-control'],'private, max-age=31536000, immutable');
  assert.deepEqual(zlib.gunzipSync(asset.body),fs.readFileSync(path.join(output,'static/js',name)));
});

test('static middleware denies directory links escaping its root before the legacy fallback', async t => {
  const {createStaticAssets} = require('../../wuye-f/MicroCommunityWeb-v0.8/lib/static-assets.cjs');
  const directory = fs.mkdtempSync(path.join(os.tmpdir(),'wuye-static-link-'));
  const root = path.join(directory,'public'), outside = path.join(directory,'private');
  fs.mkdirSync(root); fs.mkdirSync(outside);
  fs.writeFileSync(path.join(outside,'private.js'),'private-only');
  fs.writeFileSync(path.join(outside,'private.png'),'private-binary');
  try {fs.symlinkSync(outside,path.join(root,'linked'),process.platform==='win32'?'junction':'dir');}
  catch (error) {fs.rmSync(directory,{recursive:true,force:true}); t.skip('Directory links unavailable: '+error.code); return;}
  let fallback = 0;
  const middleware = createStaticAssets(root);
  const server = http.createServer((req,res)=>middleware(req,res,error=>{
    fallback++; res.writeHead(error?500:200);res.end('fallback');
  }));
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(()=>{server.close();fs.rmSync(directory,{recursive:true,force:true});});
  for (const url of ['/linked/private.js','/linked/private.png']) {
    const response = await request(server,url,{'Accept-Encoding':'gzip'});
    assert.equal(response.status,403,url);
    assert.equal(response.headers['cache-control'],'no-store');
  }
  assert.equal(fallback,0,'Legacy static fallback must not expose a linked private file');
});
