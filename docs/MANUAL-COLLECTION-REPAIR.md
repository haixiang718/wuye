# 人工托收修复（2026-11-03）

## 原因

房屋收费点击 BIND202 的“人工托收”会在生成托收清单前返回数据库错误：`Unknown column 't.car_type_cd' in 'field list'`。此功能会查询该业主的车辆欠费，即使没有车辆也需要执行 SQL。0.8 车辆 mapper 引用的 `car_type_cd`、`member_id` 在项目附带的旧 0.6 数据库中缺失。

BIND201 当前没有符合查询条件的费用，接口提前返回空数组，前端照常跳转，所以两行看起来表现不同。

补齐字段后进一步确认，`ComputeFeeSMOImpl.computeOweFee` 只查询状态 `1000`，漏掉了已通知但未缴费的状态 `3000`，导致 BIND202 已通知的 0.01 元测试账单被排除。

## 修改

- `scripts/migrate_owner_car.py`：仅连接本地 `127.0.0.1:13306` 的 TT 或隔离测试库，先检查兼容性、整库备份，再执行增量 SQL，最后核对原车辆字段的行数和内容哈希。
- `scripts/migrations/2026-11-03-owner-car-compat.sql`：为 `owner_car`、`business_owner_car` 各增加两个字段。`car_type_cd` 默认 `1001`（主车辆，与车身类型 `car_type` 不同）；未知的历史 `member_id` 保持 NULL，不编造历史成员关系，不改车辆主键。
- 欠费计算使用既有 mapper 的 `outstandingOnly=T` 筛选，保留 `1000`、`3000` 的正余额账单，排除已缴费及零余额账单。按周期出账的费用仍需先生成当前账单，不绕过出账规则。

## 执行

```powershell
python scripts/migrate_owner_car.py
python scripts/migrate_owner_car.py --apply
python -m unittest scripts.tests.test_owner_car_migration
python scripts/tests/run_bill_review_database.py
```

默认命令仅显示计划；`--apply` 应先完成备份。迁移可重复执行，遇到已有不兼容字段会停止，不强制转换。部署时还需正常构建并更新依赖 `java110-core` 的业务服务。

## 验证

- 真实后台会话复现 BIND202 点击失败，确认错误发生在车辆查询，尚未收款。
- 5 项隔离数据库迁移测试覆盖只读计划、备份、原数据保留、重复执行、备份失败不执行 DDL，以及不兼容字段和非授权库拒绝。
- 8 项真实 MyBatis/数据库账单测试通过。其中“已通知未缴费可托收”在旧逻辑下失败，修改后通过；已缴费和零余额排除检查通过。
- 合并业务服务构建成功并在本地更新后，浏览器实测 BIND201、BIND202 两行均返回业务码 0 并正常跳转；BIND202 的托收列表及明细显示 0.01 元。
- 为 BIND202 正常生成一条待托收清单和一条待托收明细，状态均为 `1010`。原有托收记录未变，8 张原有财务表内容哈希不变，未提交收费或推送消息。
- 备份、请求响应、页面截图及本地核对报告保存在忽略目录 `.runtime/manual-collection/`、`.runtime/backups/owner-car/`。

“人工托收”准备的是待收款清单，不会调用微信支付或把账单直接登记为已收款。
