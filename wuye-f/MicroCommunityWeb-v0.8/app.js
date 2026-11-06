var createError = require('http-errors');
var express = require('express');
var path = require('path');
var cookieParser = require('cookie-parser');
var logger = require('morgan');

const proxy  = require('express-http-proxy');

var app = express();

app.use(logger('dev'));
// 反向代理（这里把需要进行反代的路径配置到这里即可）
let opts = {
  preserveHostHdr: true,
  reqAsBuffer: true,
//转发之前触发该方法
  proxyReqPathResolver: function(req, res) {
    //这个代理会把匹配到的url（下面的 ‘/api’等）去掉，转发过去直接404，这里手动加回来，
    req.url = req.baseUrl+req.url;
    //console.log(1,req,res)
    return require('url').parse(req.url).path;
  },

}


// Default to the local Java API. Set BACKEND_URL explicitly for another environment.
const backendTarget = process.env.BACKEND_URL || 'http://127.0.0.1:8012';
app.set('backendTarget', backendTarget);
const localFeatures = require('./public/js/local-features');
// Enforce before proxy and static middleware; hiding menu entries alone is insufficient.
app.use(function (req, res, next) {
  if (localFeatures.isDisabled(req.originalUrl)) {
    return res.status(404).json({ code: 'FEATURE_DISABLED', message: 'This module is disabled in the local edition.' });
  }
  next();
});
app.use('/callComponent', proxy(backendTarget, opts));
app.use('/app', proxy(backendTarget, opts));

//app.listen(3000);
app.use(express.json());
app.use(express.urlencoded({ extended: false }));
app.use(cookieParser());
app.use(require('./lib/static-assets.cjs').createStaticAssets(path.join(__dirname, 'public')));
app.use(express.static(path.join(__dirname, 'public')));

// catch 404 and forward to error handler
app.use(function(req, res, next) {
  next(createError(404));
});

// error handler
app.use(function(err, req, res, next) {
  // Local development is also reachable through the public reverse tunnel.
  // Keep internal error messages and stack traces out of every browser response.
  var status = Number.isInteger(err.status) && err.status >= 400 && err.status <= 599 ? err.status : 500;
  var message = require('http').STATUS_CODES[status] || 'Request failed';
  res.setHeader('Cache-Control', 'no-store');
  res.status(status).type('html').send('<!doctype html><html lang="en"><meta charset="utf-8"><title>' +
    status + '</title><body><h1>' + status + ' ' + message + '</h1></body></html>');
});

module.exports = app;
