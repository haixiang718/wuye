# 正式号账单模板适配

沿用已授权的非支付完善方案。实际模板为“物业费用账单缴纳提醒”：业主姓名 thing2、账单金额 amount4、缴费类型 thing9、账单名称 thing10、提醒时间 time12。

架构：人工推送按钮仍读取小区公众号及欠费模板 ID；发送前只读查询该账号模板列表，按选中模板内容选择已支持格式。原 keyword1/2/3 格式继续兼容，未知字段或字段含义不匹配时拒绝发送。模板列表失败不写不确定发送记录。通知不代表支付，支付及自动任务继续关闭。

- [x] 扩展现有 JUnit：新版字段、真实姓名、金额、北京时间、20字符限制、拒绝未知模板及查询失败，并先验证失败。
- [x] 最小修改 BillNotificationService，更新脱敏 readiness 识别及模拟入口。
- [x] 定向运行 Java/Python 回归，再打包 service-api 并重启该服务。
- [x] 备份并定向配置当前正式号欠费模板，检查实际 API 模板和本机健康；不发送消息。
- [x] 记录结果及待指定收件人的手机验收。

结果：首次新增六项回归失败，完成格式适配后通过；实际只读 Java 探测发现 UTF-8 内容被默认 ISO-8859-1 解码，新增无 charset 响应回归先失败，显式 UTF-8 修复后 32 项 Java、8 项 Python 通过。第二次 Maven 定向打包成功，从最终 JAR 提取类与依赖后调用正式号模板列表并生成五字段 payload 验证通过。配置仅更新一个欠费模板属性，旧值备份在忽略目录；真实发送为 0，手机验收待指定测试业主。

验证命令：`python scripts/tests/run_bill_notify_java.py`；`python -m unittest discover -s scripts/tests -p test_wechat_readiness.py`；`python scripts/wechat_readiness.py`；`python scripts/runtime_ops.py health`。
