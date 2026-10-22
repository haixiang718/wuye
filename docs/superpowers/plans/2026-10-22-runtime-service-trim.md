# 运行服务精简实施计划

**Goal:** 减少无用常驻进程与空闲连接，保持保留业务及恢复能力。

**Architecture:** 在现有启动器加入 property/full 配置；条件注册 API 文档。业务服务边界与数据不重构。

**Tech Stack:** Python unittest、Java8/Spring Boot2条件配置、Playwright、本机Windows进程统计。

- [x] 核实依赖：goods 唯一内部消费者为拼团任务；核心服务保留。启动原配置记录基线。
- [x] 编写启动配置测试：默认/完整列表、自动任务补回 goods、显式 goods、trim 仅停无用服务、配置切换及连接池参数，运行确认失败。
- [x] 修改 scripts/local.py：选择配置、生成资源参数、增加 trim 动作与状态显示；不改变堆上限。
- [x] 将 service-api 的 Swagger Bean 和 EnableSwagger2 移至条件配置，默认启动通过环境配置关闭，full 保持开启。
- [x] 定向构建 service-api，运行 Python 回归；实际 full/property 验证 API 文档开关与业务接口。
- [x] 运行后台和业主端查询、账单按钮取消/无效明细验证；记录前后内存与限制，不真实发送。
- [x] 更新启动说明及项目状态，审查 diff；提交和同步结果以当前功能分支历史为准。

审查发现并修复了已运行 job 开启自动任务、当前终端清除开关后误停 goods 的情况；新增失败用例后修复，11项启动器测试通过，只读复核通过。实际查询与资源采样结果见 `docs/PROJECT-STATUS.md` 第5.5节。
