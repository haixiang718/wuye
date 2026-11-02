# 账单全流程诊断模拟

目标：按用户确认的业务顺序，用隔离测试资料实际走注册、绑定审批、计费、H5 查询，再以测试进程内的公众号和微信支付替身检查通知及支付回调。记录阻塞和失败，不增加生产支付入口，不把数据库手工改状态当成支付成功。

范围已由用户“进行一次全流程模拟，看看可能出现的问题”授权。使用 `writing-plans` 组织验证，并按 `systematic-debugging` 复现问题；这是诊断工作，不扩展实现完整审核/支付功能。

- [x] 新增 `scripts/simulation/billing_flow.py`：固定本地 MySQL 13306 及已有本地服务，生成随机标识的测试资料和私有凭据记录；记录开始前已有数据摘要；复用 `scripts/owner-h5/fixture.py` 的真实注册 API 及 `scripts/billing/LocalMonthlyBillingAudit.java` 的生产计费任务。
- [x] 新增 `scripts/simulation/browser_flow.cjs`：在真实本地后台执行该测试绑定的物业审批；确认待审批无法登录、审批后可以登录；编译 H5 使用审批后的真实密码登录业务会话查询本次房屋和费用（没有真实 OAuth；通知进程仅在内存替换外发身份）；验证匿名和跨房屋访问拒绝、支付关闭。
- [x] 新增 `scripts/simulation/PaymentCallbackAudit.java` 和运行器：用生产微信回调适配器与进程内 RestTemplate/Redis 替身，覆盖合法回调、参数位置、错误签名、金额/商户错误、业务失败、重复回调和重试；所有微信身份、密钥、订单均为人工合成，禁止外部请求。
- [x] 运行 `python scripts/tests/run_bill_notify_java.py`、`python scripts/tests/run_owner_oauth_java.py` 补充通知及授权边界证据。Java 验证串行，避免共享编译目录冲突；公众号外发全部使用替身。
- [x] 核查人工审核发布门槛及遗留支付回调确认边界，区分动态复现、静态发现与无法验证：本轮没有执行真实支付下单、下游销账和财务入账，不声称支付闭环通过。
- [x] 清理仅本次随机标识资料，核对既有合同、账户、其他业主/房屋/费用/账单未改；生成 `docs/BILLING-FLOW-SIMULATION.md` 和脱敏 JSON，记录各步骤结果、证据和修复优先级。

运行日志、会话、原始响应和测试凭据只保存在忽略的 `.runtime`。账户余额不手工增加；不调用真实支付接口、不发送真实微信或短信、不启用定时任务、不改变正式服务号。

执行结果：公开注册链路、审核发布门槛、欠费页及遗留支付回调存在阻塞；真实出账、审批后查询和模拟通知可用。测试资料已清理，15 张业务表行数及 SHA-256 恢复；订单与操作审计痕迹保留。报告见 `docs/BILLING-FLOW-SIMULATION.md`。
