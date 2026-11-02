# 账单流程修复实施计划

> 使用根因排查、TDD、独立任务执行与审查；用户已经明确要求修复，按本计划持续实施。

**目标：** 修复诊断报告中的注册、审核发布、欠费页面和支付确认问题，并重跑隔离流程。

**架构：** 保持六 Java 服务合并运行方式。审核状态独立于通知/缴费状态，业主展示来自已发布快照，支付确认依靠可信订单及持久化处理结果。

**技术：** Java 8、Spring Boot、MyBatis、MySQL 13306/TT、Vue/uni-app、JUnit、Playwright。

- [x] 注册与欠费页：在 `scripts/tests/OwnerDataAccessTest.java` 与 H5 运行测试补充匿名注册转发及页面初始化、空结果、重复加载、错误处理；先看到失败，修复 `ApiSMOImpl.java` 和 `pages/roomOweFee/roomOweFee.vue` 后验证通过。保持其他权限校验。
- [x] 审核持久化：在 `java110-bean` 的 BillDto/BillOweFeeDto、fee interface、FeeServiceDaoImplMapper 与 fee service 中增加发布及快照查询所需字段与原子审核接口。新迁移脚本仅新增 bill 审核字段/索引，先验证待审默认值、审核幂等与小区条件，备份后应用于隔离库。
- [x] 后台审核及推送：增加 `fee.reviewBill` 员工接口与账单管理“审核发布”操作，验证物业小区关系；BillNotificationService 在外发前拒绝待审账单。测试未授权、跨小区、重复发布与未审不发消息。
- [x] 业主已发布账单：H5 费用和欠费信息只显示已发布快照的金额/账期；旧费用接口不可绕过发布。权限测试覆盖匿名、他人房产、伪造身份、未发布及已通知未付仍可查询。
- [x] 支付确认：对遗留 V2 回调保留现有入口，修复 wId 传递、签名/商户/订单/金额校验、业务失败应答及持久化幂等重试。测试正确与错误金额、错误商户、错误签名、同笔重复、下游 HTTP/业务错误后重试、缓存消失、并发和凭据脱敏。支付开关继续关闭。
- [x] 集成与交付：执行相关定向测试和 H5 生产构建，重新打包启动 merged 服务，用随机资料重新验证注册→绑定审批→出账→待审拦截→发布→模拟通知→查询及隔离回调/记账；失败不标通过。清理并核对数据，独立审查需求与质量，更新 `docs/BILLING-FLOW-SIMULATION.md` 和 `docs/PROJECT-STATUS.md`，本地提交。

常用验证命令：`python scripts/tests/run_owner_access_java.py`、`python scripts/tests/run_bill_notify_java.py`、`python scripts/simulation/run_payment_audit.py`、`node scripts/simulation/browser_flow.cjs ...`。Java 验证不并行；所有 credentials、会话、原始响应仅在忽略的 `.runtime`。
