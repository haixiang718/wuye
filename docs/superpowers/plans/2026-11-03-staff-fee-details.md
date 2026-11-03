# 员工收费明细实施计划

**Goal:** 员工收费页逐条显示实际收费记录及时间。

**Architecture:** 保留原接口与权限，替换聚合查询模板；前端改用流水明细展示。迁移只写查询配置，收费业务数据只读。

**Tech Stack:** Vue 2、Java 8、MySQL 8、现有动态 SQL 服务、Node 测试、Playwright。

- [x] 复现：从真实后台读取 `staffFeeManage/list`，确认旧数据为按员工汇总且没有收费时间。
- [x] 测试：添加 `test/staff-fee.test.js`，验证逐笔返回、分页、空结果及筛选；添加 `StaffFeeDetailsDatabaseTest.java` 和 `run_staff_fee_database.py`，使用随机隔离库验证生产动态 SQL 与真实数据库。
- [x] 查询：创建 `scripts/staff-fee-query.json`，以 `detail_id` 为粒度；创建 `scripts/migrate_staff_fee.py`，备份后替换 `api.getStaffFee` SQL 和响应模板并刷新 `SERVICE_SQL`。
- [x] 页面：修改 `public/pages/property/staffFeeManage/staffFeeManage.{html,js}` 展示逐笔项目、对象、支付方式、金额和时间；修正分页并提供本页 CSV 导出。
- [x] 后台：修改 `ListStaffFeeSMOImpl.java` 的分页参数，把 `row=page*row` 修正为请求的每页条数；仅更新本地 front 服务。
- [x] 验证：运行 `npm test`、`python scripts/tests/run_staff_fee_database.py`；真实后台检查逐笔金额、收费时间、员工/日期筛选、两页结果和导出，核对收费业务表哈希不变，提交本任务文件。

验证记录（2026-11-03）：前端测试 23 项通过；动态 SQL、真实 MySQL 及双数据源 ShardingSphere 测试 8 项通过。后台服务构建成功并已重启。本地浏览器返回 8 笔收费明细，拆分前后应收/实收合计均为 538.46 元；每页 2 条时连续 4 页无遗漏或重复，员工筛选、日期筛选、空结果和 CSV 下载均通过。收费时间精确到秒。8 张财务业务表哈希保持一致；迁移重复执行无更新。完整数据库备份保存在 `.runtime/backups/staff-fee/`，旧服务包备份保存在 `.runtime/staff-fee/service-front-before.jar`。

联调发现原子查询写法与现有 ShardingSphere 路由不兼容，最终改用平面 JOIN 和明细去重；测试使用两套独立隔离库，验证查询始终路由到正确的数据源。
