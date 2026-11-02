# 账户与合同兼容结构设计

用户在获知可根据当前代码重建结构、先备份再验证的方案后，明确要求开始创建。本设计落实该授权，不等待原厂 SQL。

范围：独立本地 MySQL 127.0.0.1:13306 的 TT 库；创建 account、business_account、account_detail、business_account_detail，向 contract 增加六列及查询索引。以当前 Account/AccountDetail/Contract 实体、生产 MyBatis 映射器和业务 ADD/DEL 变更流程为契约；属于本项目自行设计的兼容迁移，不声称恢复原厂定义。

账户余额和流水金额使用 DECIMAL(18,2)，沿用当前 amount 数值单位，不做元/分转换。实体主键分别为 acct_id、detail_id（varchar(30)）；业务记录以实体 ID、b_id、operate 联合主键区分同一事务中的旧/新版本。对象和类型代码沿用 varchar(30)/varchar(12)，状态默认 0，时间默认当前时间且不随更新自动变化。余额和流水不设非负约束，以保留退款/调账表达能力；不伪造历史余额。对象为多态引用且既有系统使用软删除，不新增跨旧表外键；验证关联读写，不能将此替代完整的账务过账设计。

合同新增 b_id、contract_parent_id、obj_person_id、start_user_id 为 varchar(30)，obj_name 为 varchar(200)，obj_person_name 为 varchar(64)，均默认 NULL；旧合同现有字段不改写。新增 (store_id,contract_parent_id,status_cd) 索引用于父合同查询。

采用可审阅的 SQL 文件和本地执行器。执行器仅连接项目独立实例，先检查既有结构；有待执行 DDL 时必须成功备份目标数据库后才能执行。检查列类型、主键和金额精度；已有不兼容表立即停止，不以 IF NOT EXISTS 掩盖错误。DDL 可能部分提交，允许重复运行补齐；没有自动删除表/列的回退操作，新增财务数据后禁止盲目删表。

验证：先复现缺表/字段；在随机命名隔离数据库上创建旧合同结构，运行迁移并重复执行，检查历史数据不变、异常结构拒绝及备份失败不执行 DDL；生产 MyBatis 语句验证账户和明细业务落表、金额、主键、更新隔离、事务回滚、合同读写与父合同关联。最后备份并迁移 TT，检查原有合同摘要不变及真实后台/业主接口。通知、支付和自动任务保持关闭。

账户直接更新原 SQL 在 WHERE 中缺 AND 且遗留逗号，按 acct_id 必选、obj_id 可选过滤修复。验证无 acct_id 不会批量更新。无新增“自动更新余额+流水”的公共接口，底层事务测试不代表两个独立 HTTP 接口已具备原子过账能力。
