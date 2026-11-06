# 数据库结构优化（2026-11-06）

范围为项目专用 MySQL `127.0.0.1:13306` 的 `TT` 与 `hc_community`。这是上一轮 7 个账单查询索引之后的结构整理，不修改收费金额、账期、支付状态或财务记录。

两个库共 392 张表的功能分组、逐表用途线索及保留判断，见 [数据表与功能对应表](DATABASE-FUNCTION-MAP.md)。功能归类不等于当前启用状态，也不作为直接删表依据。

## 本轮调整

1. 给 `bill.bill_id` 和 `bill_owe_fee.owe_id` 增加主键。执行前检查列非空且现存编号无重复；数据库以后也能阻止重复账单编号。移除由主键替代的两个旧身份查询索引。
2. 清理业务范围内 39 个完全重复的索引，保留对应的主键、唯一约束及原 InnoDB 聚簇索引。相同列顺序、类型、前缀长度和可见性才视为重复；涉及外键或显式索引提示时拒绝清理。
3. 增加 `bill(community_id,cur_bill,create_time)`，供当前账单列表过滤与按日期倒序查询。5,000 条模拟账单的测试中额外排序消失，查询结果一致；不是实际生产并发能力测试。
4. 更新旧的 `migrate_billing_indexes.py`，识别新账单主键，不在后续重跑时把两个退役索引重新创建出来。

只调整明确的业务表清单。Activiti、Quartz、历史备份表以及来源待确认的表均不参与本次索引清理。11 张疑似废弃空表、4 张历史表、13 张待确认表继续保留；没有删除表或业务行。日志、登录和密钥表没有明确的保留期限，未自动清空或归档。

## 迁移与回退

脚本默认只列出计划：

```powershell
python scripts/migrate_database_optimization.py
python scripts/migrate_database_optimization.py --database hc_community
```

执行使用 `--apply`，限定上述本机实例和两个库。每个库先完成全量一致性备份及文件校验，然后按表执行 DDL。逆向操作与逐步结果保存在忽略的 `.runtime/backups/database-optimization`；每张表的 ALTER 在 MySQL 8 中是原子的，跨表不是单一事务。

- `forward.sql`：本次全部计划。
- `rollback-plan.sql`：完整计划的逆向操作，仅供核对，不能直接当作中途失败后的回退文件。
- `rollback.sql`：仅含已知提交成功的表，按逆序生成并原子替换文件。
- `result.json`：完成数量、数据校验结果，以及执行中的表。
- `manifest.json` / `database.sql`：完整备份清单与备份数据。

若 `needsSchemaInspection=true` 或 `inProgress` 非空，需要先检查对应表的实际结构及服务端 DDL 是否仍在执行。客户端超时不等于数据库已取消，不能直接执行完整逆向计划。确认后在维护窗口选择逐表逆向 DDL，或将完整备份恢复到独立库核对；不要自动覆盖真实业务库。

## 验证

- 8 项独立 MySQL 测试覆盖主键唯一约束、原数据不变、聚簇索引保留、重复运行、备份失败、外键/框架表排除、日期排序、中途失败及回包丢失。
- 旧索引迁移增加新主键兼容检查。
- 从真实库复制受影响的 33 张表、29,566 行到独立临时库，完整执行正向和逆向 DDL。两库副本的行数、内容校验和原索引结构均恢复一致，演练副本随后清除；源库未被演练修改。
- 实际变更前暂停业务写入，核对没有待确认微信订单；变更后核对全部业务表和财务表，再恢复服务。

重跑验证：

```powershell
python -m unittest discover -s scripts/tests -p test_database_optimization.py -v
python -m unittest discover -s scripts/tests -p test_billing_index_migration.py -v
python scripts/tests/rehearse_database_optimization.py
```

迁移测试共享本机迁移锁，应逐个运行。演练程序只在自己新建的随机 `ops_restore_` 库中修改或清除副本，不修改源库。

## 实际执行结果

优化已在两库实施，业务服务已经恢复。TT 索引数量 499 → 464，hc_community 为 151 → 148；全部 392 张表在维护窗口内的行数、内容校验值均保持一致。

备份：TT 完整备份 104,631,540 字节，hc_community 为 10,318,087 字节，均通过清单与 SHA-256 校验。实际备份目录、逐表 DDL、回退语句和执行计划见 [验证记录](validation/2026-11-06-database-optimization.json)。备份数据留在本机忽略目录，不上传仓库。

8 项新迁移测试、4 项旧迁移兼容测试、40 项支付数据库测试、8 项缴费历史测试、10 项账单审核测试全部通过；最终运行检查 21/21。实际账单列表执行计划使用 `idx_billing_current_date` 倒序索引扫描，不再显示 filesort。重跑新旧索引迁移均无待执行操作。

这次减少的是重复索引的存储和维护成本；InnoDB 释放的索引页可能留在表空间中，不将其描述为操作系统磁盘立即减少同等字节，也未扩大数据库内存配置。
