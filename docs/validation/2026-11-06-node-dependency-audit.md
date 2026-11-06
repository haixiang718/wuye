# Node 依赖审计与兼容补丁

2026-11-06 使用 npm 官方安全公告接口检查已锁定依赖。现用镜像未实现 audit 接口，因此仅在本次命令中指定 `--registry=https://registry.npmjs.org`，没有修改机器的默认 registry。告警数量按 npm 的受影响依赖项统计，包含上游传递影响，不等于独立漏洞或已证实可利用入口数量。

| 范围 | 修复前 | 修复后/当前状态 |
| --- | --- | --- |
| 管理后台生产依赖，`npm audit --omit=dev` | 19：严重 6、高危 5、中危 1、低危 7 | 0：兼容补丁后再移除了不用的 Jade 模板依赖链 |
| H5 完整锁定依赖，`npm audit` | 106：严重 7、高危 28、中危 65、低危 6 | 本轮未升级 uni-app/Vue 2 构建体系，仍为 106 |
| H5 包清单中的生产依赖，`npm audit --omit=dev` | 0 | 仅此统计口径为 0；不能据此声称浏览器产物没有漏洞 |

## 已应用的后台兼容补丁

| 依赖 | 原版本 | 当前锁定版本 |
| --- | --- | --- |
| express | 4.16.4 | 4.22.3 |
| express-http-proxy | 1.6.0 | 1.6.3 |
| cookie-parser | 1.4.4 | 1.4.7 |
| morgan | 1.9.1 | 1.12.1 |
| body-parser（Express 依赖） | 1.18.3 | 1.20.9 |
| qs（Express 依赖） | 6.5.2 | 6.16.2 |
| proxy-addr | 2.0.6 | 2.0.8 |
| debug（代理依赖） | 3.2.6 | 3.2.7 |
| Jade 及其旧模板依赖链 | Jade 1.11.0 | 移除，减少 47 个包 |

直接依赖保持原主版本。传递依赖按其父包允许的兼容范围更新，没有执行 `npm audit fix --force`。npm 同步更新了锁文件格式与完整性元数据。

修复覆盖请求体解析拒绝服务、查询参数解析、重定向、代理地址信任、Cookie 格式和日志注入等公告，例如 [body-parser 压缩请求体问题](https://github.com/advisories/GHSA-27r3-jxmh-p828)、[qs 拒绝服务](https://github.com/advisories/GHSA-4mjr-xmp4-gh2g)、[proxy-addr 地址伪造](https://github.com/advisories/GHSA-jqcg-44mw-7w3h)。这表示对应已锁定版本的公告已消除，未宣称项目所有安全风险已消除。

## 仍需处理的技术债

兼容补丁后曾剩 Jade、constantinople、transformers、uglify-js、clean-css 五项。进一步确认这些依赖仅供 Express 示例首页及错误模板使用：实际后台是已有静态页面。因此删除示例路由与三个 Jade 文件，错误页改为仅含固定 HTTP 状态短语的 HTML，卸载 Jade 及 47 个依赖包；再次审计生产依赖为 0 项。未按 audit 的破坏性降级提示更改模板引擎。

H5 使用 Vue 2.6.14、vue-template-compiler 2.6.14、Webpack 4.47.0、webpack-dev-server 3.11.3、PostCSS 7.0.39。106 项主要来自旧构建与开发服务依赖，但 **Vue 虽列在 devDependencies 中，实际仍进入浏览器产物**。因此生产依赖口径为 0 不代表 H5 运行代码安全。Vue 的 [解析器 ReDoS](https://github.com/advisories/GHSA-5j4c-8p2g-v4jx)、[模板编译 XSS](https://github.com/advisories/GHSA-g3ch-rx76-35fx) 等应纳入后续 uni-app/Vue 迁移验证。当前对外入口使用构建后的静态文件，未将 Webpack 开发服务作为公网入口。

手工放在后台 `public/js` 的第三方脚本不由 npm 锁文件管理，也不在本次 npm audit 的完整覆盖范围内。未以 npm 告警减少代替这些静态库的后续清点。

## 同时验证的服务边界

- 静态文件先校验真实路径，越界的符号链接/Windows junction 返回 403；二进制文件与 Range 请求也先经过此检查，再回落到原静态处理。
- 默认开发环境下，未登录请求不存在路径曾收到服务器绝对路径和堆栈；已统一只返回 HTTP 状态短语，移除堆栈和内部异常消息，错误响应标记 `no-store`，未修改 `NODE_ENV`。
- 静态 gzip 缓存只计已保留的压缩结果：最多 64 项、8 MiB，单文件最多 2 MiB。**这不是进程内存上限**；并发首次请求的文件读取、zlib 工作缓冲区和其他服务内存不计在 8 MiB 中。

验证：后台既有 Node 测试 65 项通过；静态/网关真实 HTTP 测试 7 项通过，包括 gzip 内容一致、HEAD、304、Range、文件更新失效、配置/API 缓存隔离、POST 原始 JSON 与 Cookie 转发、404 信息隐藏以及真实 junction 越界；最终发布包与运维脚本 Python 测试共 25 项，其中 24 通过、1 项因 Windows 文件符号链接权限跳过。目录 junction 回归在本机实际执行通过，未跳过。

所有转发测试使用合成回包与临时本地端口，没有真实业主登录、支付、退款或消息发送。浏览器资源测量见同目录 `2026-11-06-static-performance.json`。
