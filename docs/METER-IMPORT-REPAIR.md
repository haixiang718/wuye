# 水电抄表批量导入修复

2026-11-02，针对用户提供的原始下载模板、填写后的 `1.xlsx` 及后台报错截图进行核对。

## 原因

填写文件保留了“房屋费用信息”工作表和八列顺序。三行房屋为 BIND201、BIND202、TEST101，上期读数均为 0，本期读数分别为 10、11、12，日期为 2026-11-01 至 2026-11-02。Excel 将日期保存为数值 46327/46328；生产导入解析器能转换这种格式。这份文件的解析没有引起截图中的故障。

抄表导入在创建费用前，会查询房屋是否属于合同，以选择付款对象。当前数据库没有代码要求的 `contract_room` 表，因此查询报错。转发层此前将解析、权限和后台调用包在同一异常处理块，所有异常都显示为“模板数据有误”，并把内部 SQL、路径及异常响应直接显示在页面。

## 修复

- 按生产 ContractRoom Mapper、PO 和查询条件补充独立的增量迁移：只创建空的 `contract_room` 及必要索引，不推断历史合同关联。
- 复用已有迁移工具的目标限制、全库备份、兼容性检查和幂等机制；此前的账户与合同迁移保持原有默认行为。
- 分开权限、工作簿解析、后台保存阶段的错误。缺少必填项返回简短的 400 校验提示；后台异常返回 500，提示管理员先核对导入结果，页面不再输出 SQL 或内部路径。
- 工作簿读取后释放资源。未改动用户上传的两份文件，也未代用户导入这三行电费。

## 验证

共 28 项定向检查通过：生产解析器及错误分层 5 项（含实际 `1.xlsx` 的三行解析）、真实 MyBatis 合同房屋 SQL 5 项、新迁移 6 项、原迁移回归 12 项。测试中的保存边界被替换，数据库读写只发生在随机临时测试库；不会把用户电费写入现有本地业务表。

本地独立数据库 `127.0.0.1:13306/TT` 在完整备份后补建一张表；备份为 78,530,563 字节，保存在忽略的 `.runtime/backups/business-schema`。重复迁移无待执行项。

实际运行的合同房屋查询由 500（缺表）恢复为 200（未关联合同的房屋返回空列表）。迁移前后 `pay_fee`、`meter_water`、`pay_fee_attrs`、`bill`、`bill_owe_fee` 和 `contract` 六张业务表的行数与内容摘要一致。

front 及依赖共 8 模块 Maven 打包通过；定向测试单独执行，打包使用跳过测试参数。front 已按 merged 配置重新启动，其余服务继续运行。通过管理后台真实上传路径提交原始未填写模板，收到 400“模板数据有误：3本期度数不能为空”，无 SQL、JAR 路径或异常堆栈。实际模板下载仍为 200，返回有效 XLSX。此上传在解析阶段被拒绝，不写入业务数据。

这轮验证不等于已将用户的三行电费导入，不包含微信公众号通知或支付。导入接口仍沿用原有分批保存方式；后续批次失败时应核对已保存记录，再决定如何重试。

## 重现验证

```powershell
python scripts/tests/run_meter_import_java.py C:\Users\32090\Downloads\1.xlsx
python scripts/tests/run_contract_room_database.py
python -m unittest discover -s scripts/tests -p test_meter_import_migration.py -v
python -m unittest discover -s scripts/tests -p test_business_schema_migration.py -v
python scripts/migrate_meter_import.py
python scripts/business_schema_check.py
```

测试与迁移会使用同一个迁移锁；逐个运行。Java 定向测试与 Maven 打包也应逐个运行。
