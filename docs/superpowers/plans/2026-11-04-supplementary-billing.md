# 补充出账实施计划

目标：新增费用可以生成独立待审核补充账单，同时保留原账单并阻止重复出账。

架构：手动生成复用 GenerateBillProTemplate；BillSnapshotService 在原配置行锁事务中防重，补充插入保留原当前账单。最早当前账单固定滚动周期。Java7生产源码、Java8运行时、Spring/MyBatis/MySQL、现有Vue后台。

- [x] 在 scripts/billing/ManualBillGenerationTest.java 增加真实计算器的已发布后补充及重复生成检查，运行 `python scripts/billing/verify_manual_generation.py`，先确认旧实现返回旧账单而失败。
- [x] 在 scripts/billing/BillSnapshotDatabaseTest.java 增加补充保留原账单、重叠fee拒绝、并发、回滚及下期普通生成测试。使用随机测试表，不操作原业务表。
- [x] 修改 java110-bean 的 BillSnapshotDto、service-job 的 GenerateBillProTemplate/BillGenerateInnerServiceSMOImpl、service-fee 的 BillSnapshotService/IFeeServiceDao/FeeServiceDaoImpl 和 FeeServiceDaoImplMapper.xml：查询原批次排除已出账费用，事务内再次核验，补充插入跳过历史化。
- [x] 新增 bill.base_bill_id 及幂等迁移，保留原账单已有字段，明确同秒出账的根关联；增加失败测试后修复人工托收及付款状态回写，增加同秒兄弟账单的并发结果定位检查。
- [x] 更新后台 billManage 的提示及测试，说明再次生成只包含尚未出账费用。运行前端测试、手动生成/原出账/真实MySQL事务检查。
- [x] 完整备份，先在独立目录构建 business、job、api、front，再短暂停机切换包；验证健康检查、真实浏览器两次新增费用及重复生成、H5待审核隔离、原已发布账单仍可查看。
- [x] 清理随机资料，核验原表摘要；在用户原项目只生成缺失的新费用草稿，保留审核/发送给用户操作；写使用和验收文档，提交相关修复。
