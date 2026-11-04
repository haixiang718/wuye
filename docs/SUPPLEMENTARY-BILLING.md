# 本期新增费用的补充出账

后台「费用管理 → 费用账单 → 生成待审核账单」，选择收费项目并确认。第一次出账生成原账单；本期已有账单时，自动排除已经出账的费用，只为新增费用生成名称带“（补充）”的独立草稿。核对后仍通过该行的“审核并推送”发布到 H5 并发送公众号通知。

重复生成且没有新增可出账费用时返回已有账单，不增加草稿。原账单即使已审核、已通知或已支付，也不会被补充操作改写；同一费用在当前批次只出现一次。有未审核补充单时继续新增费用，也可以再次补充，分别审核。生成成功后若原来停留在往期或旧名称筛选，页面会切回当期第一页，显示新草稿。

人工托收和缴费状态回写按费用实际所属账单处理，兼容同一收费项目多张当前账单。补充生成不延长原有滚动账期；正常进入下一期时，原批次整体转历史，新一期仍复用原计算规则。目前定时任务保持关闭，客户尚未确定最终账期规则；商户支付保持关闭。

## 部署顺序

1. 完整备份业务库和当前服务包。
2. 先运行 `python scripts/migrate_supplementary_billing.py`，在本项目专用本机 TT 库添加可空字段 `bill.base_bill_id`。工具会核验原账单已有字段内容不变；重复运行不会重复添加字段。正式服务器应按实际库连接方式应用等价的增量 DDL，再启动新版本。
3. 更新 java110-bean、java110-core、java110-db 依赖及业务、job、API、front 包，并发布后台静态页面。当前云服务器仍为 HTTPS/SSH 转发入口，业务和数据库在本机。此前 Linux 准备包需要重建。
4. 更新后运行健康检查及隔离验证。需要回退时可使用本轮备份的旧服务包；保留可空关联字段和已有补充账单数据，不删除数据库记录。旧版本不支持多张当前账单，存在补充账单后不要直接以旧版本恢复收费操作，应先评估兼容性。

`base_bill_id` 在原账单中为空，在补充单中固定指向原账单；事务校验整个批次关联。配置行锁保护原子保存，锁内再次检查已出账 feeId；并发或结果不确定时，按本次尝试的具体费用确认实际落库账单，避免同秒排序返回错单。

## 验证

2026-11-04 本机运行版已更新：后台65项、手动出账194项、原出账20项、真实SQL事务/查询27项通过；公网后台浏览器42项、隔离SQL/H5 15项及健康21项通过。随机资料已清理，15张原业务表数量与内容摘要一致。迁移只添加可空关联列，原账单已有字段单独核验一致。详细记录见[验收结果](validation/2026-11-04-supplementary-billing.json)。

用户14:09新增的50元费用已单独生成草稿 `732026110453030010`，一张补充单、一条未通知明细，原50元已发布账单保持不变。没有代用户审核或发送，用户可直接在费用账单页核对该行。

回归包含原账单发布后补充、草稿后再次补充、重复请求、并发冲突、同秒不同编号、写入失败回滚、下期整批历史化、人工托收及缴费状态定位。

~~~powershell
python scripts/billing/verify_manual_generation.py
python scripts/billing/verify.py
python scripts/billing/verify_snapshot.py
python scripts/tests/run_bill_review_database.py
# 在 wuye-f/MicroCommunityWeb-v0.8 下运行 npm test
python scripts/simulation/supplementary_bill_flow.py prepare
python scripts/simulation/supplementary_bill_flow.py exercise
python scripts/simulation/supplementary_bill_flow.py cleanup
python scripts/runtime_ops.py health
~~~

模拟工具使用随机隔离资料及合成微信身份，不发送通知、不调用真实付款。为了单独验收 H5 草稿隔离和已发布查询，测试账单的发布状态只在来源可验证的隔离记录上通过 SQL 转换，不冒充手机真实 OAuth、公众号收件或商户收款验收。
