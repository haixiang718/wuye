# SQL 参数与数据库盘点（2026-11-06）

范围：本项目 `127.0.0.1:13306` 的 `TT`、`hc_community`；Java 主源码、MyBatis mapper、启用的 `c_service_sql` 配置。此次数据库操作仅有元数据、计数与 EXPLAIN 查询，未执行 DDL、删表、删除业务记录、支付或消息发送。

机器可读证据见 [database-inventory.json](2026-11-06-database-inventory.json)，其中包含每张表的精确行数、分配空间、分类理由、源码引用、启用的动态 SQL 配置引用数、索引、外键、查询原文及 EXPLAIN。

## 已修复的 SQL 注入入口

原实现 `like '%${feeName}%'` 把搜索词直接嵌入 SQL。查询词包含单引号时可能改变 SQL 结构，例如离线测试字符串 `审计' OR 1=1 OR 'x'='x`。无需执行攻击查询即可通过真实 MyBatis BoundSql 验证。

已确认的两条外部调用链：

| 入口 | 参数传递 | 原危险位置 |
| --- | --- | --- |
| `feeConfig.listFeeConfigs` GET | `ListFeeConfigsListener:68` → `FeeConfigInnerServiceSMOImpl:46/55` → DAO | `FeeConfigServiceDaoImplMapper.xml:171/303` 的 `feeName`；同文件业务查询 `:54` 同类问题 |
| `ownerRepair.listOwnerRepairs` GET | 业主前端 `WechatOwnerService-v0.8/constant/UrlConstant.js:21/78` → `ListOwnerRepairsListener:77/96/101` → `RepairInnerServiceSMOImpl:43/49` | `RepairServiceDaoImplMapper.xml:166/304` 的 `repairName` |

17 个 mapper 的 41 处 LIKE 插值已统一改为 `like concat('%', #{parameter}, '%')`，涵盖费用配置、费用报表、报修/回访、装修、巡检和库存查询。百分号与下划线仍作为 LIKE 通配符；没有擅自增加通配符转义。引号、反斜杠、美元符号和问号均作为 JDBC 字符串值传递。

验证命令：

```powershell
python scripts/security/run_mapper_parameter_binding.py
```

该测试只在独立临时目录编译一个测试类，解析真实 mapper 并通过 `DefaultParameterHandler` 捕获 JDBC 参数；不加载 Spring 服务、不打开数据库连接、不使用共享 `.runtime/billing-audit`。修复前以 `search input changed executable SQL` 失败；修复后覆盖 17 个 mapper、33 条语句、41 个搜索位置、8 种输入，共 328 次参数绑定检查通过。输入含中文、恒真条件、普通英文单引号、`%`、`_`、反斜杠、`$?`、换行。

## 剩余动态 SQL 的分类

| 位置 | 证据与处理边界 |
| --- | --- |
| `QueryServiceDAOImplMapper.xml` 中 `${value}` 两处 | 当前 `QueryServiceDAOImpl.executeSql/updateSql` 已改走 JDBC `PreparedStatement`；这两条 mapper 调用仅留在注释中。属于旧映射，不能据出现 `${}` 就当作当前外部入口。 |
| 同 mapper `${procNameInfo}` 两处 | 旧存储过程路径会合并请求参数，且 DAO 拼接过程参数；当前 `c_service_sql` 没有启用过程配置，两库也没有 routine。属于潜在风险，启用前需要过程名白名单及参数绑定，不能宣称已修复。 |
| `IHcFtpFileDAO.xml` `${functionname}` | 一处为 FTP 任务前置过程名，来源于任务配置；另一处在 XML 注释内。当前两库无 routine；没有证据证明外部搜索参数可达该名称。启用时应限制为已配置允许的过程名。 |
| 排序 | 本次 mapper 与核心查询源码扫描未发现外部 `sort/orderBy` 直接拼接；不是对所有动态 Java 脚本的穷尽证明。 |
| `Java110MybatisInterceptor` / `/businessApi/fallBack` | 全局插件现使用 AST 和原 MyBatis 参数映射读取快照，补偿值采用受限编码、标识符校验及 NULL-safe 比较，兼容旧日志；隔离 MySQL 验证通过。内部回滚入口仍只供内部服务使用，需继续保持本机／内网及网关路由边界。上述 LIKE 测试本身不覆盖服务认证。 |

启用的 `c_service_sql` 配置为 44 条 SQL 模式、1 条 JavaScript 模式、0 条过程模式。SQL 模式普通查询参数经过 `?` + JDBC 绑定。此结论不等于允许任意用户修改 SQL/JavaScript 配置。

另执行 `python scripts/security/run_rollback_sql_security.py`：隔离数据库中实际运行费用配置查询，12 次检查覆盖普通单引号、中文、特殊搜索文本、通配符及小区隔离；默认 SQL 模式与 NO_BACKSLASH_ESCAPES 下结果一致。该脚本还验证事务快照、SET 表达式、批量 VALUES、INSERT SELECT、三类补偿及旧日志兼容，未连接真实业务表。

## 表规模与分类

| 库 | 表数 | 精确行数 | 精确空表数 | 分配空间 | 有依赖/在用 | 历史保留 | 疑似废弃 | 需确认 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| TT | 327 | 218943 | 174 | 121.11 MiB | 301 | 4 | 11 | 11 |
| hc_community | 65 | 19769 | 18 | 11.89 MiB | 63 | 0 | 0 | 2 |

“有依赖/在用”指当前源码、启用的 SQL 配置或 Activiti/Quartz 框架依赖，不能证明近期有流量。精确行数来自 COUNT；`information_schema.TABLE_ROWS` 的初始估计为 183/19 张空表，与实际 174/18 不同，不能用于删表判断。并发写入会使后续计数发生变化。

以下 **11 张 TT 空表** 在主源码与启用 SQL 模板中未找到直接引用，列为疑似废弃候选，仍未获准删除：

```text
activities_event, business_activities_event,
business_resource_item, business_resource_order,
property_fee_time, property_fee_type,
resource_item, resource_order,
s_store_house_attr, u_location, wechat_attr
```

以下 **4 张历史/备份样式表** 保留，合计仅约 0.078 MiB，应先确认来源和保留期限：

| TT 表 | 精确行数 | 说明 |
| --- | ---: | --- |
| m_menu_20200316 | 92 | 带日期的菜单快照命名，无主源码/启用 SQL 直接引用 |
| m_menu_org | 109 | 疑似原始菜单备份；当前主表为 `m_menu` |
| p_privilege_0731 | 142 | 带日期样式的权限快照；当前主表为 `p_privilege` |
| c_service_business_31 | 0 | 疑似业务配置历史副本；当前主表为 `c_service_business` |

以下有数据但没有上述直接依赖证据，列为 **需确认**：

```text
TT: c_cache_2_user(3), c_order_type(2), c_status(24), community_member_type(6),
    property_user_rel(2), s_store_fee(2), s_store_house(3), store_fee_time(3),
    store_fee_type(6), store_user_rel(2), user_level(3)
hc_community: c_status(16), community_member_type(4)
```

不能清理的反例：

- `c_cache`、`c_template` 虽没有主源码表名字面量，启用的 `c_service_sql` 各有两条引用。
- `payment_order_reservation` 当前为空，但支付流程使用它防止同一费用被并发预占，不能删。
- `demo`、退款相关空表、Activiti/Quartz 空表仍有代码或框架依赖。
- 最大的 `transaction_log_message`（50.61 MiB）、`application_key`（21.56 MiB）、`u_user_login`（9.41 MiB）均有源码依赖；日志/密钥过期数据若需归档，应另定保留策略，不能当作无用表直接删除。

没有发现业务库视图、触发器、存储过程。已采集外键，主要属于 Activiti/Quartz；没有外键不表示没有应用层关系。外部运维脚本、未接入代码、运行期拼出的表名及历史合规要求仍需业务负责人确认。

## 账单查询索引证据与建议

以下为迁移前证据，原始 SQL 与 EXPLAIN 见 JSON。使用合成标识符，只取得计划，不返回业主信息。当前账单规模仅 5 条，不能据此声称线上已存在明显延迟或量化提速。

| 查询 | 迁移前计划 | 原因 |
| --- | --- | --- |
| 欠费明细列表 `queryBillOweFees` | `bill_owe_fee` 为 `ALL`，估计 5 行；`Using temporary; Using filesort` | `bill_owe_fee` 无任何索引 |
| 精确 `owe_id + community_id + bill_id` | `ALL`，无 possible_keys | 同上，支付锁定路径也使用这组条件 |
| 支付账单快照列表 | `bof=ALL`、`owner_app_user=ALL` | 快照无索引，绑定表没有 user/community 入口索引 |
| 费用按房屋和小区查询 | `pay_fee=ALL`，估计 12 行 | 只有重复的 `fee_id` 唯一索引 |
| 字典类型查询/账单字典关联 | `t_dict=ALL`，估计 394 行 | 只有 id 主键，没有业务查找组合索引 |
| 当前账期列表 | `bill` 使用 `idx_bill_review` 的 community 前缀，仍 filesort | 现有索引无法满足仅 community/cur_bill 加 create_time 的顺序 |

必要索引建议（**本审计未执行任何 DDL**）：

1. 优先为 `bill_owe_fee(owe_id)` 和 `bill(bill_id)` 增加唯一索引或主键。当前 duplicate_bill_id=0、duplicate_owe_id=0、孤立欠费账单=0；执行迁移前须重新检查。ID 点查尤其影响 `FOR UPDATE` 扫描与锁定范围。
2. 欠费批次管理可考虑 `(community_id,bill_id,state,create_time)`；业主欠费查询可考虑 `(community_id,owner_id,state,create_time)`。`state IN (...)` 与连接顺序仍可能使排序不能完全靠索引消除，应以代表性数据重新 EXPLAIN，不盲目同时增加冗余索引。
3. `owner_app_user(user_id,community_id,state,status_cd,member_id)` 支持已登录用户绑定关系查询；`pay_fee(community_id,payer_obj_id,status_cd)` 支持房屋费用查找，可根据真实过滤条件加入 payer_obj_type。
4. `t_dict(table_name,table_columns,status_cd)` 支持账单、费用配置等业务字典关联。
5. 账期列表若数据增长，再评估 `bill(community_id,cur_bill,create_time)`；当前 `idx_bill_review` 保留审核查询用途。

验证流程：备份 → 在隔离恢复库应用候选索引 → 用足够数据量对照 EXPLAIN 与点查/并发锁行为 → 审核回滚方案后执行迁移。此文档提供索引建议，不替代主代理单独维护的实际迁移与验证结果。

## 重跑盘点

```powershell
python scripts/security/database_inventory.py --output docs/validation/database-inventory-latest.json
```

脚本限制查询类型为 SELECT/SHOW/EXPLAIN，并只使用本项目固定连接。输出只包含元数据、计数、代码路径与使用合成标识符的执行计划，不包含业务记录或配置正文。
