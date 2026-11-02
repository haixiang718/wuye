# 账户与合同兼容迁移实施

状态：已执行完成。12 项迁移测试、14 项生产 MyBatis 测试通过；已备份并迁移 TT，11 条 DDL、重跑 0 条，30 份合同原字段摘要一致。合并业务服务及依赖构建成功，恢复六 Java 服务后真实接口 24/24 通过。独立审查发现的金额归零、额外必填列两项问题已按失败/通过回归修正，复审通过。结果见 `docs/validation/2026-11-02-account-contract-schema.json`。

方案已由用户的“请你开始创建吧”授权；在当前绑定运行目录执行，保留已有订阅方案和上线计划未提交改动。

1. 创建失败回归：`scripts/tests/test_business_schema_migration.py` 在随机测试库重建旧合同结构，断言迁移、重跑、数据保留、结构拒绝及备份失败阻止 DDL；先观察缺少执行器导致失败。
2. 编写 `scripts/migrations/2026-11-02-account-contract.sql` 和 `scripts/migrate_business_schema.py`，提供只读计划及带备份的应用命令。执行器拒绝非 TT/随机测试库，校验结构，记录备份 SHA256 与前后合同摘要，使用精确目标执行增量 DDL。
3. 新增真实 MyBatis/MySQL 账户合同回归；先复现旧账户更新 SQL 拼接失败，再修复 WHERE 条件。验证业务变更记录及实例表、同 ID 不同变更历史、金额精度、重复主键、查询归属、更新隔离、事务回滚、旧/新合同和父合同关系。
4. 停止会写入业务库的 Java 服务，备份 TT，应用迁移，再执行一次确认零新增 DDL。检查账户表为空、原合同摘要不变、前置结构检查通过；没有删除历史数据。
5. 打包受影响的合并业务服务并恢复 merged 六 Java 应用，运行真实接口回归；刷新脱敏验证 JSON 和当前状态文档。独立代码审查后提交本轮变更。

验收命令：`python -m unittest discover -s scripts/tests -p test_business_schema_migration.py`；`python scripts/tests/run_account_contract_java.py`；`python scripts/business_schema_check.py`；`node scripts/tests/consolidation-runtime-smoke.cjs --profile merged --label schema-repair`。数据库测试仅随机测试库，运行日志、SQL 备份和凭据留在忽略的 `.runtime`。
