# 业务服务合并

2026-11-02 补充：业务修复在 `codex/business-fixes` 继续，账单生成和业主权限已修复。合同外层失败进一步追踪为旧数据库缺字段；此前空商户范围只验证数量分支。最新真实回归为 24 项中 21 项通过，3 项失败分别对应合同入口、合同真实商户查询及账户缺表；详见 [业务修复记录](BUSINESS-REPAIRS.md)。以下保留合并阶段的历史证据。

**2026-10-28：本机合并、回退及内存对比已完成。** 当前运行显式 `merged` 模式，Java 应用进程由 12 个降为 6 个。四轮真实回归均为 20 项通过、2 项原有缺口；没有把原数据库缺表及合同外层路由问题记为通过。2026-10-26 暂停期间的配置和业务数据均保留。

本轮在 `codex/service-consolidation` 分支实施，将 user、community、fee、acct、store、common、report 七个模块装入一个 `service-business` JVM。原模块源码和启动方式保留，`merged` 为显式选用模式，启动器默认仍为 `property`。

## 进程与接口

| 模式 | Java 应用进程 | 业务部署 |
| --- | --- | --- |
| property | 12 | 原七个业务服务独立运行 |
| merged | 6 | business + eureka、order、api、front、job |
| full | 13 | property 加 goods，开启原完整运行配置 |

以上数量不包括 Kafka、ZooKeeper 自身的 JVM，也不包括 MySQL、Redis、Node Web 和业主 H5。business 监听 `127.0.0.1:8015`；后台仍为 `http://127.0.0.1:3000`，业主本地入口仍为 8080。

## 构建、启动和回退

在项目根目录执行。已有业务数据不需要重新导入或迁移。

```powershell
# 首次编译或修改 Java 后：仅停止项目 Java/Web，保留数据库、中间件和 H5
python scripts/local.py stop eureka order user acct common store report api community fee front job goods business web
python scripts/local.py build

# 按需恢复隔离中间件
python scripts/local.py infra --profile merged

# 启动或切换到合并模式
python scripts/local.py restart all --profile merged

# 回退到原服务模式
python scripts/local.py restart all --profile property
```

切换必须使用 `restart all` 刷新所有调用方的路由。部分启动/重启会拒绝跨模式切换；启动前检查目标 JAR。CLI 使用文件锁阻止同时切换，停止时核对 PID 和命令，确认进程退出后才移除记录。失败时先查看 `.runtime/logs/`，不要同时手工运行两套业务消费者。

`restart all` 保留 MySQL、Redis 会话和通知记录、Kafka/ZooKeeper 及业主 H5/公众号联调网关；不会删除消息和消费位点。全部自动任务默认暂停，支付仍按既有未开通状态处理。启用 `WUYE_ENABLE_SCHEDULED_TASKS=1` 会恢复 goods 依赖，此时 Java 数量相应增加。

## 保留业务语义的处理

- 统一容器只加载一个启动类，复用七个模块源码和 common 工作流、文件存储资源。原 `property/full` 启动类不进入合并扫描。
- 旧服务名通过仅在 merged 生效的 Ribbon 静态配置指向 8015，Feign 和 HTTP 序列化边界保留。过滤 Feign 代理的 MVC 注册，避免它与真实控制器竞争；兼容旧接口只在接口方法标注 `@RequestBody` 的情况。
- 默认业务、community、report 分别保留原数据源 YAML。community 的小区表继续按小区 ID 奇偶分片；report 继续使用其 TT 默认库。路由在事务取得连接之前进入，异常和线程复用后恢复上下文；拒绝活动事务中跨数据源切换。
- 每个域保留原 Kafka topic 和消费组，六个消费者使用独立 Bean 名；不新建替代消费组，不重置 offset。
- 通用 `/businessApi/*` 查询、执行和补偿接口通过内部逻辑服务名选择原数据源。RestTemplate 在 Ribbon 改写地址前携带原服务名；事务日志继续记录原服务名，兼容切回原部署后的补偿。该内部接口只监听本机，缺少合法服务名会拒绝请求。
- 冷启动首次发现监听器时，需要跨域读取监听器的业务类型及排序元数据。这两种只读元数据方法不切换数据源；实际业务执行仍在事务取得连接之前选路，并拒绝事务中切到其他数据源。回归覆盖冷缓存发现、排序、回滚及线程上下文清理。

## 验证方式

```powershell
python -m unittest discover -s scripts/tests -p 'test_*.py'
python scripts/tests/run_business_java.py
python scripts/tests/run_bill_notify_java.py
python scripts/runtime_memory.py --label current --output .runtime/memory-current.json

# 真实查询；报告包含原数据库缺口时返回 1，不代表全部通过
node scripts/tests/consolidation-runtime-smoke.cjs --profile merged --label current

# 明确允许已证明的原有缺口继续测试合成房屋写入，仍保留失败结果
node scripts/tests/consolidation-runtime-smoke.cjs --profile merged --label write --write --allow-known-baseline-gaps
```

Maven 打包跳过项目原测试；上述配置、容器、路由和通知测试单独执行。真实接口回归使用本机隔离数据库和已有标记测试房产，不向微信发送真实通知或发起支付。外部文件存储配置以打包资源一致性验证，不连接原 FTP 服务。

## 原有功能缺口

2026-10-28 在原 `property` 模式先建立基线，确认以下两项并非合并引入：

- 账户内部查询返回 HTTP 500，隔离数据库 `TT` 缺少 Mapper 依赖的 `account` 表。此次不擅自创建版本来源不明的财务表。
- 后台 `/app/contract/queryContract` 外层调用返回业务码 404；原 store 服务内部 `/contract/queryContract` 返回正常的空列表。内部测试使用合成的 `store-id: TEST-SMOKE`，不代表已验证真实合同数据和完整合同操作。

验证工具会保留这两个失败，最终 `passed:false`、退出码 1。显式的 `--allow-known-baseline-gaps` 仅允许在账户缺表已由独立 SQL 证明、合同内部查询成功且没有其他失败时继续合成房屋写入，不会把整个报告改成通过。

实际写入仅修改 `BIND202` 标记测试房屋的备注；先验证无效面积被拒绝且字段未变，再验证有效修改可查询，最后逐字段恢复。日志及业务流水作为审计记录保留。该验证不等于已实测每个跨服务补偿分支；事务回滚与路由保护另由容器单元测试覆盖。

## 验收记录（2026-10-28）

可分享的脱敏证据见 [验证结果 JSON](validation/2026-10-28-consolidation-results.json)。完整运行日志、会话及测试快照仅留在忽略的 `.runtime`。

- 22 个 Maven 模块重新打包成功；Maven 原测试跳过，不能以打包成功代替测试。
- Python 32 项：29 通过、3 跳过；容器/路由/事务 JUnit 8 项通过；手动账单通知 JUnit 23 项通过；OAuth/未开通支付 JUnit 10 项通过；Java 裁剪边界 61 项通过；Node 测试 37 项通过。
- 四轮实际流程：原 `property` 基线 → 第一次 `merged` → 回退 `property` → 最终 `merged`。每轮 22 项检查结果一致：20 通过、上文 2 项原有缺口；未观察到新增回归。
- 通过范围包括房屋、业主、收费配置、账单、公众号配置、停车查询、缴费记录、费用报表、任务、楼栋、区域、合同内部空列表查询，以及合成业主账号登录后查房/查费、匿名请求拒绝、测试房屋写入及逐字段恢复。
- 小区列表与两个原数据库的 SQL 结果集一致，实际覆盖 123 条小区记录及奇偶分片查询。未把只查到一侧分片算作通过。
- 合并后原七个独立业务端口均关闭；尝试在合并模式旁启动原 user 服务被拒绝，调用方配置文件未改变。
- 最终切换前后 MySQL、Redis、ZooKeeper、Kafka、H5 和受限公众号入口 PID 均未变且端口可用；原四个 Kafka 消费组仍可查询。没有重置消费位点。H5 本机首页实测无脚本错误和资源失败。
- 定时任务 JVM 总开关仍关闭；通用内部业务接口缺少逻辑服务身份时返回 400。未向真实微信发送通知、发布菜单、发起支付或切换正式服务号，也未用手机重新验证 OAuth。

### 同机内存测量

使用同一次构建，分别在回退后的 `property` 和最终 `merged` 运行相同查询及测试房屋写入/恢复后采样。两次均包含同样的数据库、中间件、Web、H5 及受限公众号入口；采样时浏览器测试进程已退出。

| 指标 | property | merged | 减少 |
| --- | ---: | ---: | ---: |
| Java 应用进程 | 12 | 6 | 6 |
| Java 工作集 | 5542.7 MiB | 3030.6 MiB | 2512.1 MiB，45.3% |
| Java 私有提交内存 | 5857.3 MiB | 3201.5 MiB | 2655.8 MiB |
| 全部已记录进程工作集 | 6094.8 MiB | 3579.7 MiB | 2515.1 MiB |
| 全部已记录进程私有提交内存 | 6533.9 MiB | 3907.2 MiB | 2626.7 MiB |

Windows 工作集包含共享页、JIT 和缓存波动，不能作为 Linux 生产容量或并发保证；本轮也不表示全部保留功能都已完成增删改验收。

此前 [账单生成验证](BILLING-VALIDATION.md) 中的并发防重、异常恢复及部分失败处理问题不属于本轮合并修复，自动出账/推送仍未启用。
