# 业主 H5 本地构建与验证

2026-11-03 登录流程更新：H5 在微信内直接授权登录，不再要求注册或填写账号密码。未绑定时无房产、账单权限；姓名、手机号、身份证号与后台同一条有效业主资料精确匹配后立即绑定。本轮公众号只关联一个小区，同一业主记录的多套房屋自动显示，独立业主记录分别验证后可切换。旧密码和小程序接口审批规则保留，历史待审记录不会仅凭微信登录自动通过。详见 [微信直接登录说明](WECHAT-DIRECT-LOGIN.md)。

沿用 `wuye-xcx2/WechatOwnerService-v0.8` 的 Vue 2 / uni-app 页面，不需要 HBuilderX 或微信开发者工具。未重新注册已裁剪页面。CLI、Vue、Sass 等版本记录在 package.json / package-lock.json。

手动账单通知链接使用 `roomFeeListNew?roomId=...`。该页现在优先选择指定的自有房屋；无匹配房屋则停止费用查询并提示，无参数时沿用首套房屋。6项定位用例及 H5 构建通过，多房屋手机实测待验收。

以下 PowerShell 命令均从仓库根目录执行：

```powershell
# 为旧版 webpack 准备独立 Node，不改变机器的全局 Node。
npm install --prefix .tools/owner-h5-node --no-audit --no-fund node@18.20.8
npm --prefix wuye-xcx2/WechatOwnerService-v0.8 ci --legacy-peer-deps --no-audit --no-fund
node scripts/owner-h5.cjs build
node scripts/owner-h5.cjs serve
```

构建产物为 `wuye-xcx2/WechatOwnerService-v0.8/unpackage/dist/build/h5`。静态服务器只监听 `127.0.0.1:8000`；可用 `OWNER_H5_PORT` 改端口。`Ctrl+C` 停止。构建脚本优先使用上述独立 Node，且确认产生新的 index.html；编译器出现错误却返回 0 时也不会误报成功。可选热更新命令为 `node scripts/owner-h5.cjs dev`。

直接打开 `http://127.0.0.1:8000/#/pages/index/index` 可检查页面。静态服务器不代理业务接口；完整联调从专用测试入口打开，使 H5、OAuth 和 `/app` 请求保持同源。开发期 manifest 中的 `/app`、`/callComponent` 代理目标仍为本机 front 端口 8012。

## 测试入口契约

HTML 在应用启动前加载 `/wechat-test/config.js`，其中只有可公开字段：

```javascript
window.__WUYE_WECHAT_TEST__ = {
  appId: '测试号 AppID',
  communityId: '当前小区 ID',
  communityName: '当前小区名称',
  paymentEnabled: false
};
```

AppSecret、回调 Token、入口访问码不能放进这个响应。静态服务器自身只返回 `paymentEnabled:false` 的默认配置；测试入口负责生成完整公开配置。页面参数 `wAppId` 优先，其次公开配置和本地存储。

H5 使用 hash 路由。主要接口为 `/app/refreshToken`、`/app/loginOwnerWechatAuth`、`/app/loginOwnerByKey`、`/app/loginOwner`，以及房屋、业主绑定、小区、楼栋、单元和费用查询接口。入口应按实际保留业务接口开放，避免开放整个后端。

OAuth 返回 `#/pages/…?wAppId=…&authTicket=…`。H5 用 POST `/app/loginOwnerByKey` 的 `{authTicket}` 交换会话，成功后移除地址中的票据。未绑定返回 `hasBinding:false` 和受限 `wechatSession`，不返回业主 token；三项资料通过 `/app/bindOwnerWechat` 核验成功后才取得房产会话。已绑定返回选中 `owner`、已核验 `owners` 列表和 token。没有真实微信授权时，不能把浏览器回归结果当成真实微信身份验证。

浏览器禁止手动发送 Cookie 请求头，所以 H5 从原有会话存储读取 `token` 并发送同名请求头；专用入口通过访问校验后，将它转成后端现有的 `_java110_token_` Cookie。浏览器自动发送的入口访问 Cookie 仍由浏览器管理。生产静态服务器不提供这种转换。

支付默认不可用：提交按钮显示“微信支付未开通”并禁用，H5 拦截下单请求及 `requestPayment`。后端和入口仍应独立阻止真实支付；此界面开关不是权限校验。

## 回归与验证范围

```powershell
node --test wuye-xcx2/WechatOwnerService-v0.8/test/*.test.cjs
node scripts/owner-h5/smoke.cjs
node scripts/owner-h5/layout-smoke.cjs
```

浏览器回归使用 `.tools/browser/node_modules/playwright-core` 及系统 Edge。新机器可先安装 `npm install --prefix .tools/browser --no-audit --no-fund playwright-core@1.64.1`；浏览器路径可由 `BROWSER_PATH` 覆盖。`OWNER_H5_URL` 可指定已有测试入口，但入口访问校验需先完成。

该浏览器脚本明确使用合成 API 数据，检查首页、登录、注册、延迟 OAuth 票据交换、房屋、费用查询和不可支付状态，并捕获页面 JavaScript 异常。它曾复现并覆盖混合模块导致首页崩溃、票据交换前查询业主的竞态，以及未配置支付仍可下单的问题。

手机截图曾暴露全局导入 CSS 未转换 upx/rpx、H5 运行时样式未加载的问题。PostCSS 现先通过 `postcss-import` 展开导入，再做 uni-app 转换；HTML 加载编译器实际输出的带 hash 的 `static/index.<hash>.css`。布局回归检查首页和“我的”在 360/390/430px 宽度下的底部导航定位、图标尺寸、菜单触控高度、单位转换和资源请求，并生成本机截图。文本可见或没有脚本异常不能代替这些布局检查。

真实后端登录、绑定、费用数据和微信端授权需另行在本机后端及测试号验证。构建当前只有约 292 KiB 的入口包体积提示；不影响构建成功。工具、node_modules、构建产物和运行配置均不入库。

## 可选的本地合成数据

```powershell
python scripts/local.py migrate
python scripts/owner-h5/fixture.py create
# 后端就绪后，用现有注册接口生成这个测试业主的账号：
python scripts/owner-h5/fixture.py register
# 不再需要时，仅删除脚本记录的测试数据：
python scripts/owner-h5/fixture.py cleanup
```

此工具固定使用独立数据库 `127.0.0.1:13306/TT`，使用配置中的既有小区。它生成前缀 `WXTEST20261021` 的 7 条楼栋、单元、房屋、业主、关系、收费配置及一次性 0.01 元账单记录，并标记为“仅联调测试”。它不会运行出账任务，不能用于证明真实账单生成。账号使用假号码 `19900000000` 和随机密码，记录仅保存在忽略的 `.runtime/wechat-test-fixture.json`，不会打印密码。

创建、注册和清理均要求 `SMS_SEND_SWITCH=OFF`；注册直接调用接口，不发送短信。发现手机号已有账号时拒绝注册，避免接管原有账号。清理要求固定测试 ID、生成账号的记录和实际业主绑定、手机号、标记一致；账号出现其他业主绑定时拒绝清理。账号通过真实微信授权关联之前，合成数据与 UI 回归都不代表已经验证真实微信身份。

账号已创建、后端和专用入口启动后，可执行 `node scripts/owner-h5/backend-smoke.cjs`。该检查通过系统既有 APP 客户端的本地密码登录取得真实后端会话，然后在浏览器中通过受限入口请求真实房屋和费用接口；不会制造 OAuth 票据。已验证房屋 `TEST101` 和这条合成的 0.01 元账单可查询、页面无 JavaScript 异常，未发出支付请求。它证明本地认证和查询接口集成，真实微信身份仍须用户在微信中授权。

2026-10-21 真机补充：用户已通过手机微信实际授权并登录测试业主账号，确认房屋及测试账单均可见；独立数据库确认该业主 WECHAT 绑定保存了有效 OpenID。随后已限定该业主完成原业务出账及一次通知发送，微信 API 受理成功，数据库为已通知未支付；发送后 H5 真实后端查询仍通过。卡片暂链接既有房屋费用列表，用户已确认收到通知、点击后能查看测试费用；原欠费页面、会话失效后自动登录、真实客户账单确认及支付尚未验收。完整边界见 [WECHAT-TEST.md](WECHAT-TEST.md)。

清理保护测试：`python -m unittest discover -s scripts/owner-h5 -p 'test_*.py' -v`。设置环境变量 `WUYE_LOCAL_DB_TESTS=1` 后，还会用独立 MySQL 执行当前三个 Mapper 的真实 SELECT/JOIN 投影，确认业主、房屋、费用路径的列兼容性（只读）。
