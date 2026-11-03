# 微信直接登录实施计划

> 使用 subagent-driven-development：前后端按已约定接口分别实现，根代理集成并验证；不在任务间重复请求用户批准。

目标：微信直接授权进入 H5，三项资料完全匹配后立即绑定房产，省去用户注册与账号密码输入。设计见 `docs/superpowers/specs/2026-11-03-wechat-direct-login-design.md`，自动绑定已由用户明确选择。

- [x] 后端：扩展 OAuth 单次票据与受限未绑定身份会话，增加 `/app/bindOwnerWechat` 和后端可信绑定处理；先失败测试，再实现，覆盖会话隔离、三项匹配、重复并发和旧权限。
- [x] H5：自动授权、区分微信登录与房产绑定；绑定表单三项资料、未绑定提示与错误重试；移除注册/密码入口，保留返回原账单、多房切换；先回归再实现。
- [x] 集成：核对前后端响应、网关 allowlist、后端房产权限与旧绑定兼容。定向 Maven 和 H5 构建，备份原制品后恢复运行，验证真实本地新身份匹配及错误拒绝，测试资料清理。
- [x] 审查：分别检查设计符合性和代码质量，修复问题后运行必要回归。更新说明和手机验收步骤，提交本轮代码，不携带密钥、不发送微信消息。

结果见 `docs/WECHAT-DIRECT-LOGIN.md`。本轮按正式号已配置单小区实现，多小区界面未实现；手机首次真实 OAuth 留待用户验收。集成发现的多业主默认选择及 Fastjson `$ref` 兼容问题均已修复并回归。

关键文件：`service-front/.../OwnerAppLoginSMOImpl.java`、新微信绑定控制器/SMO，`service-api/.../owner` 与既有 `OwnerBMOImpl`；H5 的 `auth`、`config/h5-runtime.js`、`pages/bindOwner`、`pages/my`、`pages/login`、`pages/showlogin`，以及 `scripts/wechat-test-gateway.cjs` 公开路由限制。

验证命令：后端独立 Java 测试工具和现有 `run_owner_access_java.py`、`run_bill_notify_java.py`；H5 `npm test`、`npm run build:h5`；`python scripts/runtime_ops.py health`。真实手机 OAuth 不由本地替身代替。
