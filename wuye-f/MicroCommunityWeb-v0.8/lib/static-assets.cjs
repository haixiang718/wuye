'use strict';
const fs = require('node:fs');
const path = require('node:path');
const {pipeline} = require('node:stream');
const {promisify} = require('node:util');
const gzip = promisify(require('node:zlib').gzip);

const types = {'.js':'application/javascript; charset=utf-8', '.css':'text/css; charset=utf-8',
  '.html':'text/html; charset=utf-8', '.svg':'image/svg+xml'};
const immutable = /^\/static\/[a-zA-Z0-9_./@~-]+\.[a-f0-9]{8,64}\.(?:js|css)$/;

function acceptsGzip(value) {
  const encodings = String(value || '').toLowerCase().split(',').map(value => {
    const [name, ...parameters] = value.trim().split(';');
    const quality = parameters.find(parameter => /^\s*q\s*=/.test(parameter));
    return {name:name.trim(), quality:quality === undefined ? 1 : Number(quality.split('=')[1])};
  });
  const match = encodings.find(item => item.name === 'gzip') || encodings.find(item => item.name === '*');
  return !!match && match.quality > 0 && match.quality <= 1;
}

// Optimize text assets only. Existing static middleware retains directory, range
// and binary-file behavior. The bounded cache holds compressed bytes, never API data.
function createStaticAssets(directory) {
  const root = path.resolve(directory);
  let realRoot;
  const cache = new Map();
  let cacheBytes = 0;
  function evict() {
    while (cache.size > 64 || cacheBytes > 8 * 1024 * 1024) {
      const [key, entry] = cache.entries().next().value;
      cache.delete(key);
      cacheBytes -= entry.bytes;
    }
  }
  function compressed(file, info) {
    const key = file + ':' + info.mtimeMs + ':' + info.size;
    if (cache.has(key)) return cache.get(key).promise;
    const entry = {bytes:0};
    entry.promise = fs.promises.readFile(file).then(data => gzip(data)).then(data => {
      if (cache.get(key) === entry) {entry.bytes = data.length; cacheBytes += data.length; evict();}
      return data;
    }, error => {if (cache.get(key) === entry) cache.delete(key); throw error;});
    cache.set(key, entry);
    evict();
    return entry.promise;
  }
  return function staticAssets(req, res, next) {
    if (!['GET','HEAD'].includes(req.method) || res.hasHeader('Set-Cookie')) return next();
    let name;
    try {name = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);}
    catch (_) {return next();}
    if (name === '/') name = '/index.html';
    if (name.includes('\0') || name.split(/[\\/]/).some(part => part.startsWith('.'))) return next();
    const file = path.resolve(root, '.' + name);
    const type = types[path.extname(file).toLowerCase()];
    if (!file.startsWith(root + path.sep)) return next();
    (async () => {
      // Check real paths even for binary/range fallbacks, so a directory link
      // cannot expose files outside the public root through legacy middleware.
      const canonicalRoot = realRoot || (realRoot = await fs.promises.realpath(root));
      const canonicalFile = await fs.promises.realpath(file);
      if (canonicalFile !== canonicalRoot && !canonicalFile.startsWith(canonicalRoot + path.sep)) {
        res.writeHead(403, {'Cache-Control':'no-store'}); res.end(); return;
      }
      if (!type || req.headers.range) return next();
      const info = await fs.promises.stat(canonicalFile);
      if (!info.isFile() || info.size > 2 * 1024 * 1024) return next();
      const etag = 'W/"' + info.size.toString(16) + '-' + info.mtimeMs.toString(16) + '"';
      const html = path.extname(file).toLowerCase() === '.html';
      const headers = {'Content-Type':type, 'ETag':etag, 'Last-Modified':info.mtime.toUTCString(),
        'Vary':'Accept-Encoding', 'Cache-Control':html ? 'no-store' : immutable.test(name)
          ? 'private, max-age=31536000, immutable' : 'private, no-cache'};
      const matches = req.headers['if-none-match'];
      const fresh = matches ? matches.split(',').some(tag => tag.trim() === '*' ||
        tag.trim().replace(/^W\//, '') === etag.replace(/^W\//, ''))
        : req.headers['if-modified-since'] && Math.floor(info.mtimeMs / 1000) * 1000 <= Date.parse(req.headers['if-modified-since']);
      if (!html && fresh) {res.writeHead(304, headers); res.end(); return;}
      const encoded = info.size >= 1024 && acceptsGzip(req.headers['accept-encoding']);
      const body = encoded ? await compressed(canonicalFile, info) : null;
      if (res.destroyed) return;
      headers['Content-Length'] = body ? body.length : info.size;
      if (body) headers['Content-Encoding'] = 'gzip';
      res.writeHead(200, headers);
      if (req.method === 'HEAD') res.end();
      else if (body) res.end(body);
      else pipeline(fs.createReadStream(canonicalFile), res, () => {});
    })().catch(error => {
      if (res.headersSent) return res.destroy(error);
      if (error.code === 'ENOENT' || error.code === 'ENOTDIR') return next();
      next(error);
    });
  };
}

module.exports = {createStaticAssets};
