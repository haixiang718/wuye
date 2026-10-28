# 业务服务合并实施计划

> 执行方式：沿用 codex/service-consolidation 分支和现有本地依赖。2026-10-28 恢复执行，完成独立规格/代码审查、四轮真实回归和内存采样；证据见 docs/SERVICE-CONSOLIDATION.md。

**Goal:** 七个业务模块合并为一个可回退进程，将默认合并配置的 Java 进程数由 12 降至 6，并验证核心业务和内存占用。

**Architecture:** 新 service-business 模块重用原业务源码，统一 Spring 容器、数据源、监听器和 Kafka 连接配置；保留 Feign/HTTP 隔离边界并为旧服务名配置指向统一端口的静态路由。原 property/full 模式继续可用。

**Tech Stack:** Java 8、Spring Boot 2.0、Spring Cloud Finchley、Maven、Python unittest、Playwright、MySQL、Redis、Kafka。

## 1. 基线与回退准备

- [x] 恢复隔离中间件和 property 模式，用现有后台登录及八项查询脚本建立运行基线。
- [x] 保存原模式和回退原模式的内存快照，最终对比采用 `.runtime/memory-property-rollback-warmed-20261028.json`。

## 2. 配置保护测试（先失败）

- [x] 新增 `scripts/tests/test_merged_runtime.py`，验证 merged 仅选择 business/eureka/order/api/front/job/web；验证 property/full 仍选择原服务。
- [x] 验证七个旧服务名均在 merged 生成的配置中禁用 Eureka Ribbon 发现并指向 business 端口，原模式不包含这些重定向。
- [x] 验证切换前检查 JAR、仅切换项目 Java/Web，保留中间件、会话和公众号入口。
- [x] 运行 `python -m unittest discover -s scripts/tests -p test_merged_runtime.py -v`；本轮 13 项通过，未重放暂停前的红阶段。另以冷缓存监听器测试实际复现失败后修复，最终容器 JUnit 8 项通过。

## 3. 统一容器和构建

- [x] 增加 `wuye-b/MicroCommunity-v0.8/service-business/pom.xml`，重用业务源码及 common 工作流资源，排除原启动类和环境配置，设置独立启动类。
- [x] 新增 `BusinessApplication`：明确组件扫描范围、业务监听器扫描范围、Feign 客户端范围、唯一 RestTemplate 和安全的自动配置排除。暂停前已扩展至七模块，本轮对最终容器和原独立服务进行双向验证。
- [x] 新增独立的 application 配置，沿用已核对的 TT 默认数据源和公共业务接口；不得依赖原环境域名。
- [x] 使用项目 JDK8 和 Maven 定向构建 `-pl service-business -am package`，启动检查 Bean、接口、监听器及注入冲突并逐项解决。

## 4. 启动与回退模式

- [x] 修改 `scripts/local.py`，增加 business 端口与 merged 配置、静态服务名路由、专用数据源和合并进程堆配置。
- [x] 为切换提供预检，阻止合并与分布式消费者同时运行；支持一条命令切回 property，不停止基础组件，不删除数据。
- [x] 重新运行启动配置原有和新测试，全部通过后切换实际服务。

## 5. 回归和测量

- [x] 后台重新登录，执行房屋、业主、收费配置、账单、公众号、停车、缴费记录、报表八项真实查询。
- [x] 检查合同、账户、区域和任务配置；如实记录账户缺表和合同外层 404 两项原有缺口，合同内部查询正常。通知/授权/裁剪测试通过，不向微信发送真实消息，不进行支付。
- [x] 验证标记测试记录的写入/读取并核对事务结果，不改真实业务记录。
- [x] 验证切回 property 后相同查询可用，再切到 merged 并重复核心回归。
- [x] 在相近预热条件下记录 after-merge 快照，报告进程数量和实测内存变化。
- [x] 将实际结果、启动/回退命令、限制写入 `docs/LOCAL-DEVELOPMENT.md` 和专用合并说明，检查 diff 后提交试验分支。

2026-10-28 验收：四轮运行每轮 22 项检查均为 20 通过、2 项原有缺口，未观察到新增回归。原模式可回退，最终保留 merged 运行。Java 工作集 5542.7 → 3030.6 MiB，减少 45.3%。本轮未修复独立账单验证报告中的业务异常问题，也未启用任务或切换正式公众号。
