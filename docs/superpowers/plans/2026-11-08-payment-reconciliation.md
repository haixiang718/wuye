# 资金核对基础实施计划

> **For agentic workers:** 使用 executing-plans 在当前会话逐项执行；本轮不派发子代理。

**Goal:** 在不改变账款的前提下提供微信日账单核对及本地支付入账一致性报告。

**Architecture:** 纯 Python 解析/核对逻辑与本地 MySQL 只读适配分离，复用现有受控本地连接。文件报告进入私有运行目录，公开验证仅记录计数。

**Tech Stack:** Python 标准库 csv、decimal、unittest；MySQL 8 只读一致快照；现有 payment_order 及收据/费用表。

## Task 1: 回归和文件契约

- [x] 确认干净工作区并建立 `codex/commercial-readiness` 分支。
- [x] 运行 `python -m unittest discover -s scripts/tests -p test_runtime_ops.py -q` 和 `npm.cmd test --prefix wuye-f/MicroCommunityWeb-v0.8`，基线 14 + 84 项通过。
- [x] 新建 `scripts/tests/test_payment_reconciliation.py`，先运行确认缺实现导致失败；覆盖正确账单、精确金额、错商户/日期、重复成功、文件截断、同商户其他 AppID、退款、跨日及多项目流水等。

## Task 2: 只读核对

- [x] 新建 `scripts/payment_reconciliation.py`，公开接口 `parse_bill(content, bill_date, app_id, mch_id)`、`reconcile(provider, orders)` 和 `local_audit(orders)`。
- [x] 金额以整数分核对；错误为固定说明，不回显 CSV 行或数据库异常。只有无差异才标为范围内一致，退款和无法识别状态单列核查。
- [x] 实现 CLI `local --date YYYY-MM-DD --app-id ... --mch-id ...` 和 `bill --date ... --app-id ... --mch-id ... --file ...`。查询固定专用库，所有外部值编码成 MySQL 十六进制 UTF-8 文本字面量，使用 `START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY`。
- [x] 读取订单的既有微信订单号与流水/收据关联，不读取业主身份信息。JSON/CSV/中文 HTML 写入唯一新建私有目录，控制台只报计数，不覆盖已有文件。

## Task 3: 验证和交付

- [x] 运行 `python -m unittest discover -s scripts/tests -p test_payment_reconciliation.py -v`，36 项针对性测试通过；增加 ALL 格式识别、汇总金额、退款字段交叉检查及官方零编号占位格式。
- [x] 随机隔离库复制所需 9 张表结构、只插合成资料，14 项真实 SQL 测试通过，结束仅删除本轮创建的随机库。
- [x] 对实际专用库运行 `local` 核对，11 月 6 日一笔已支付订单无差异；没有实际官方 CSV，不写官方对账通过。
- [x] 再跑支付订单数据库 40 项、后台 84 项、H5 129 项测试，确认未改动业务接口。
- [x] 新建 `docs/PAYMENT-RECONCILIATION.md`、`docs/COMMERCIAL-READINESS.md`，在项目现状文档顶部链接最新基线，列明当前已交付及未完成项。

## Task 4: 持续回归

- [x] 新建 `.github/workflows/property-quality.yml`，后台和 H5 逻辑测试及对账/备份/部署保护离线测试自动执行，无生产凭据、数据库或商户 API。
- [x] 本机验证与工作流相同的测试命令；发布包保护 11 项（Windows 跳过符号链接 1 项）、Linux 启动保护 6 项、公众号只读检查 8 项、运维保护 14 项通过。
- [ ] GitHub 首次远程工作流运行仍待分支上传后确认，当前不声称已通过远程 CI。
