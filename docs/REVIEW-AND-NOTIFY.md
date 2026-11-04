# 审核账单与公众号通知合并

用户已确认此前的 50 元账单通知在公众号收到。本轮将新账单的 H5 发布与公众号缴费通知合并到一次后台操作，不补发此前已收到的通知。

## 操作

「费用管理 → 费用账单」生成草稿并核对欠费清单后，点击 **审核并推送**。审核事务成功后，H5 即可查看账单，后台自动为这张账单建立通知任务，逐条处理业主通知。关闭页面不影响后台任务。

已审核账单可查看 **通知状态**，显示处理数量、微信受理、失败、结果待核对、跳过及错误明细。尚未绑定微信的业主会显示明确原因；资料修正后点击 **重试失败通知**，只处理允许重试的失败项。结果不确定的通知先人工核实，避免盲目补发。

历史账单没有合并通知任务时显示 **通知业主**；这是明确的人工操作，读取或刷新状态不会触发发送。已经通知、已经缴费或没有待缴金额的明细会跳过，原欠费清单仍保留单个业主补发入口。

微信接口受理成功不代表业主已经收到或阅读。部分失败不撤销已审核账单，H5 查询不依赖公众号通知成功。

## 实现和部署

- 保留 `fee.reviewBill`；后台新增 `notify:true` 后走发布及排队流程，旧内部调用不传该字段时仍只审核。只有明确重试操作传 `retryNotifications:true`。
- `fee.billNotificationStatus` 是物业后台专用 GET 接口；后端重新核验物业对小区的有效管理关系及账单归属。响应仅含明细 ID、统计及经过过滤的错误，不返回微信身份或密钥。
- API 应用就绪后启动一个后台线程，每轮每张活动账单处理一条明细。未启用旧定时出账/催缴，不新增 Java 服务进程。
- Redis 中 `BILL_PUBLICATION_TASK:<communityId>:<billId>` 持久保存任务和逐项请求 ID，`BILL_PUBLICATION_ACTIVE` 保存活动任务，任务锁和写入使用令牌校验。发送前保存 WORKING 状态，API 重启后复用请求 ID 继续处理。
- 初次发送与原手工推送共用明细发送锁，锁内重新核对是否已通知、已支付。复用已有微信受理记录与不确定进度，明确重试时跳过已受理收件人。
- Redis 不是可随意清空的纯缓存。当前本机已备份 RDB 并启用 AOF，启动脚本改为 `appendonly yes`、`appendfsync everysec`；应监控磁盘并一起备份任务及通知进度。每秒刷盘仍可能在主机断电时丢失最近约一秒写入，本轮不承诺跨外部微信接口的绝对一次投递。
- 意外中止时任务锁最长约 10 分钟失效，再由后台恢复；不要为加速恢复而直接删除不确定发送进度。审核成功但创建队列失败会明确显示已发布及任务未确认，核对后可补建。

后续独立部署需从当前代码重新构建 API 和依赖、发布后台静态文件，在业务库执行 `scripts/migrations/2026-11-04-bill-notification-status-route.sql` 并刷新 `APP_ROUTE_SERVICE`。目标 Redis 也需配置持久化；此前 Linux 准备包不包含本轮改动。

## 验证方式

2026-11-04 验证通过：API 及依赖 Maven 构建成功；工作流 Java 19 项、通知 Java 38 项、原审核/业主发布回归 13 项、后台 Node 47 项通过。真实后台组合操作、重复提交、状态自动刷新、无绑定失败提示和明确重试 10 项通过；随后合成微信身份下绑定及 H5 查询 22 项通过。隔离测试资料和任务已清理，16 张原业务表行数及摘要一致，订单审计保留；本机健康检查 21 项、正式域名检查 7 项通过。

微信受理、部分收件人恢复及 API 重启后的未完成任务恢复使用确定性的假微信测试边界验证；本轮没有重新向真实业主发送通知或进行手机 OAuth 验收。用户原已通知账单保持 `PUBLISHED/3000/50.00`，支付及旧定时任务继续关闭。机器可读证据见[验证记录](validation/2026-11-04-review-and-notify.json)。

```powershell
python scripts/tests/run_bill_publication_workflow_java.py
python scripts/tests/run_bill_notify_java.py
python scripts/tests/run_bill_publication_java.py
# 管理后台目录执行 npm test
python scripts/simulation/review_and_notify_flow.py prepare
python scripts/simulation/review_and_notify_flow.py exercise
python scripts/simulation/review_and_notify_flow.py cleanup
python scripts/runtime_ops.py health
```

真实本地流程使用随机且未绑定微信的测试业主。先验证实际按钮审核、排队、无绑定失败和明确重试，确认任务停止后才建立合成身份用于 H5 读账。不会使用正式 OpenID，不向已有业主发送消息。任何中途失败都应检查并清理来源受限的测试资料与任务；不要在有活动通知任务时创建模拟微信绑定。旧的 review-only 浏览器模拟脚本没有合并操作的通知隔离约束，不用于本轮验收；使用本页列出的新脚本。
