'use strict';
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../../h5');
const types = {'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8',
  '.css':'text/css; charset=utf-8','.png':'image/png','.jpg':'image/jpeg','.svg':'image/svg+xml',
  '.woff':'font/woff','.woff2':'font/woff2','.ttf':'font/ttf','.ico':'image/x-icon'};
http.createServer((req, res) => {
  if (!['GET', 'HEAD'].includes(req.method)) return res.writeHead(405).end();
  let name;
  try { name = decodeURIComponent(new URL(req.url, 'http://localhost').pathname); }
  catch { return res.writeHead(400).end(); }
  const file = path.resolve(root, '.' + (name === '/' ? '/index.html' : name));
  if (!file.startsWith(root + path.sep) || name.includes('\\') || !types[path.extname(file)]) return res.writeHead(404).end();
  fs.stat(file, (error, info) => {
    if (error || !info.isFile()) return res.writeHead(404).end();
    res.writeHead(200, {'Content-Type': types[path.extname(file)], 'X-Content-Type-Options':'nosniff', 'Cache-Control':'no-cache'});
    if (req.method === 'HEAD') return res.end();
    fs.createReadStream(file).on('error', () => res.destroy()).pipe(res);
  });
}).listen(8000, '127.0.0.1', () => console.log('Owner static listener ready on loopback.'));
