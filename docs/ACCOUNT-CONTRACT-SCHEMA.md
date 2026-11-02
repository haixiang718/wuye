# 账户与合同兼容结构

日期：2026-11-02。分支：`codex/account-contract-schema`，基于业务修复提交 `c7eb617`。经用户授权，根据当前 0.8 代码自行设计兼容结构；不是原厂升级脚本，也没有恢复或生成历史余额、流水。

## 结构与行为

| 对象 | 用途及约定 |
| --- | --- |
| `account` | 当前账户；`acct_id` 主键，金额 `DECIMAL(18,2)` |
| `business_account` | 账户业务变更版本；主键为 `(acct_id,b_id,operate)`，允许同一业务的 ADD/DEL 版本并存 |
| `account_detail` | 账户交易明细；`detail_id` 主键，金额 `DECIMAL(18,2)` |
| `business_account_detail` | 明细业务变更版本；主键为 `(detail_id,b_id,operate)` |
| `contract` | 新增可空字段 `b_id`、`contract_parent_id`、`obj_name`、`obj_person_id`、`obj_person_name`、`start_user_id`，并增加商户/父合同/状态索引 |

沿用应用已有金额单位，不做元/分换算。账户/明细有效状态默认 `0`，创建时间不会随更新自动改变。表使用 InnoDB；多态对象、历史软删除不适合直接加跨旧表外键，因此本次不增加这些外键。允许负数用于原有退款/调账表达。

同时修复两份生产账户 Mapper：账户直接更新必须指定账户 ID，业主条件正确参与 WHERE；所有金额空值判断兼容数据库返回的 BigDecimal，允许将余额或明细更新为 `0.00`。

## 执行与保护

```powershell
# 默认只读，不改变数据
python scripts/migrate_business_schema.py

# 维护期间停止本地业务写入，保留中间件
python scripts/local.py stop front api business job order eureka web

# 预检、完整备份目标库、执行增量 DDL、核对合同原有数据
python scripts/migrate_business_schema.py --apply

# 再次执行应为 0 条变更；检查字段齐全
python scripts/migrate_business_schema.py --apply
python scripts/business_schema_check.py

# Java Mapper 修改需先重新构建 service-business 及依赖，再恢复
python scripts/local.py start all --profile merged
```

执行器只连接项目独立 MySQL `127.0.0.1:13306`，CLI 固定目标 `TT`；测试 API 另允许严格随机命名的 `audit_schema_<12位十六进制>`。不访问电脑原有的 MySQL 数据目录。

先核对已存在表的列类型、可空性、默认值、额外行为、主键、引擎和索引。遇到不可省略的额外必填列或其他不兼容结构会停止，避免 `IF NOT EXISTS` 掩盖错误；兼容的可空扩展列可以保留。

有变更时，必须先生成完整目标库备份，确认导出结束标志并计算 SHA256，随后才执行建表或加列。备份和审计记录保存在忽略目录 `.runtime/backups/business-schema/TT-<时间>-<随机标识>/`，包含 `before.sql`、`backup.json`、`applied.sql`、`migration.json`。备份含数据库资料，不提交 Git。

DDL 不能用普通业务事务整体回滚；若中途失败，已执行的加表/加列可能保留。再次运行会检查并补齐剩余兼容结构。进程互斥锁阻止同时迁移；异常退出后须确认原进程已结束再处理 `.runtime/account-contract-migration.lock`。

## 备份恢复

自动化测试把 `before.sql` 导入另一个随机隔离数据库，核对原合同数据存在、备份时尚未创建的账户表不存在，验证备份可恢复。

真实库需要恢复时，先停止写入并保留当前状态，在独立空库恢复、核对后再决定切换或修复；不要直接覆盖已有新流水。备份不包含 `CREATE DATABASE/USE`，恢复目标由 MySQL 客户端显式选择；恢复旧备份也不会自动删除迁移后新增的表，不能把导入备份当作完整撤销 DDL。

## 验证与边界

本地 TT 实际迁移完成：完整备份 75,230,669 字节，执行 11 条 DDL，重复执行 0 条；迁移前后 30 份合同的原字段 SHA256 一致，四张新表均为空。迁移测试 12/12、生产 MyBatis 测试 14/14 通过。受影响的合并业务服务及依赖打包成功，恢复六 Java 进程与管理后台后，真实接口检查 24/24 通过，包含之前失败的合同入口、真实商户合同查询和账户查询。定时任务仍停用。

```powershell
python -m unittest discover -s scripts/tests -p test_business_schema_migration.py
python scripts/tests/run_account_contract_java.py
python scripts/business_schema_check.py
node scripts/tests/consolidation-runtime-smoke.cjs --profile merged --label schema-repair
```

迁移测试覆盖重复执行、旧合同保留、备份失败阻止 DDL、备份恢复、错误类型/主键/默认值/时间行为/额外必填列拒绝、目标库限制。真实 MyBatis 测试覆盖账户与流水保存查询、金额精度和归零、重复主键、业务历史、更新隔离、四表显式事务回滚、新旧合同及跨商户父合同关联。

四表回滚测试验证的是同一 Spring 事务内的数据库能力，不表示两个独立 HTTP 请求已具备余额与流水原子过账能力。账户充值、扣款、退款、对账、财务凭证和微信支付闭环仍需逐项业务验收。不会因补齐表结构就自动建立余额或启用任务。

本轮最终执行结果见 `docs/validation/2026-11-02-account-contract-schema.json`；此前业务修复报告中的缺表失败记录保留为历史证据。
