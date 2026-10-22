# 本地开发

原始代码已保存在 Git 提交 `01c3d84`。裁剪在 `feat/local-trim` 分支进行，已上传至 [GitHub 私有仓库](https://github.com/haixiang718/wuye)。项目阶段和验证边界见 [项目现状说明](PROJECT-STATUS.md)。

## 功能范围

Web、员工端和业主端停用智慧服务（投诉、公告、广告、活动、访客、门禁）、报修、采购及物资仓库、设备、巡检的入口、页面和请求。旧菜单缓存也会过滤。Java 网关和业务服务有相应拦截，相关定时任务停止执行。

保留收费、账单、业主、房屋、抄表、财务、权限、公众号配置和支付相关代码；停车接口保留，包括共用 `machineTranslate` 协议的车位查询、车辆进出、道闸判定、心跳、任务及结果回执，以及车辆同步任务和必要的内部设备查询接口。设备管理页面与人脸门禁功能停用。共享 Java 类型、原始页面源文件和历史数据库表保留，以免影响跨模块引用。停用页面不能通过原有访问入口使用。

公众号是刚注册、尚未认证的服务号，微信支付尚未开通。客户已有服务器，尚无可用的备案域名，服务器地域待确认。本轮只处理裁剪和本地运行，财务核对发布、公众号账单推送及真实支付闭环仍需后续开发与联调。

## 工具和隔离

命令均在项目根目录的 PowerShell 执行。使用现有 Python 3 和 Node.js；启动器使用项目内 Java 8，不修改系统 Java 21。

本机已准备的工具（均被 Git 忽略）：

- `.tools/zulu8.96.0.205-ca-jdk8.0.504-win_x64`：Java 8u504。
- `.tools/apache-maven-3.9.9`：Maven 3.9.9。
- `.tools/redis/tools`：Redis 3.0.503 Windows 程序。
- `.tools/kafka_2.13-2.8.2`：Kafka 2.8.2，包含 ZooKeeper。
- MySQL 8.0.45 程序位于 `D:\MySQL\MySQL Server 8.0\bin`。其他机器可在首次生成配置前设置 `$env:WUYE_MYSQL_BIN='实际路径'`，或修改 `.runtime/local-settings.json` 中的 `mysql_bin`。

运行数据、随机生成的数据库密码、PID、日志和生成的配置全部位于 `.runtime`，不提交 Git。MySQL 只使用 `.runtime/mysql/data`，不会复用 `D:\MySQLData` 中的原有数据。

| 服务 | 本地端口 |
| --- | --- |
| Web | 3000 |
| Eureka | 8761 |
| order / user / acct | 8001 / 8002 / 8003 |
| common / store / report / api | 8005 / 8006 / 8007 / 8008 |
| community / fee / front / job / goods | 8009 / 8010 / 8012 / 8013 / 8014 |
| MySQL / Redis / ZooKeeper / Kafka | 13306 / 16379 / 12181 / 19092 |

Java 和中间件绑定 `127.0.0.1`。Web 默认代理 `http://127.0.0.1:8012`，可通过 `BACKEND_URL` 环境变量覆盖。

## 启动和停止

首次在这台机器初始化时：

```powershell
python scripts/local.py infra
python scripts/local.py import-db
python scripts/local.py build
python scripts/local.py start all
```

Web 如未安装依赖，在 `wuye-f/MicroCommunityWeb-v0.8` 目录执行 `npm install`。

数据库导入使用附带的两个商业版 SQL：`hc_community0.6_商业版.sql` 和 `tt0.6_商业版.sql`，导入 65 + 319 张表。成功后生成导入标记，重复执行不会覆盖本地业务数据。演示系统的 API 地址和业主页面地址改为本地。MySQL 以兼容旧 SQL 的模式启动。

以后启动：

```powershell
python scripts/local.py infra
python scripts/local.py start all
python scripts/local.py status
```

### 运行资源精简（2026-10-22）

默认 `property` 模式启动12个 Java 服务和 Web，商品/拼团 `goods` 不再默认常驻。房屋业主、收费账单、公众号、账户财务、报表、任务配置、合同、权限及停车依赖仍保留。`job` 仍运行，但自动任务继续默认暂停。显式设置 `WUYE_ENABLE_SCHEDULED_TASKS=1` 后，启动包含 job 的组合会自动补回 goods，避免历史拼团任务缺少依赖。若已运行的 job 开启了任务，即使当前终端已清除变量，`trim` 也保留 goods；使用 `restart all` 才会将当前任务开关应用到 JVM。

property 关闭 API Swagger 文档扫描和 Spring JMX，Redis 最大空闲连接由5降到2，Druid 初始/最小空闲连接由1降到0并关闭预编译语句缓存；连接仍可按需创建，最大连接数和 JVM 堆上限保持原值。不合并微服务，不删库表和业务代码。

```powershell
# 启动时默认采用精简配置；all 指所选配置中的服务集合
python scripts/local.py start all

# 已经运行时切换配置，需要重启 Java 服务和 Web
# 此命令保留中间件、Redis 通知记录/会话及业主 H5 入口
python scripts/local.py restart all --profile property

# 恢复此前全部13个 Java 服务、API 文档和连接池配置
python scripts/local.py restart all --profile full

# 仅停掉精简配置排除的已记录服务，不重启其他服务
python scripts/local.py trim

# 单独恢复商品服务
python scripts/local.py start goods

# Windows 本机进程内存读数，不打印命令行或密钥
python scripts/runtime_memory.py --label current --output .runtime/memory-current.json
```

`start` 不会重启已监听端口的服务；只改配置文件不会改变已有 JVM，应用资源配置请使用 `restart`。`stop` 不带服务名会停止包括 Redis 在内的全部已记录进程，不适合只切换运行配置。完整模式只恢复运行服务和参数，不恢复已裁剪的业务入口，也不会自动启用任务。

本机 Kafka 2.8 曾因 Windows 不允许重命名映射中的索引文件而退出。本地 Windows 配置关闭 log cleaner 避免再次触发，不删除消息或消费位点；长期运行需监控本机 Kafka 磁盘。该兼容项不用于 Linux 正式部署，Linux 应保留日志压缩。

2026-10-22 本机在两种配置下执行相同8项后台查询后的快照：Java 工作集从6195.9 MiB降至5776.8 MiB，减少419.1 MiB（约6.8%）；全部已记录进程工作集从6727.1降至6313.7 MiB。采样包含冷启动、JIT及缓存差异，工作集求和可能重复计算共享页，不作为 Linux 生产容量保证。详细验证见[项目现状说明](PROJECT-STATUS.md#55-运行资源精简验证2026-10-22)。

浏览器进入 `http://127.0.0.1:3000`。也可按需启动，如 `python scripts/local.py start user front web`；调用业务还需要对应依赖服务。

附带的物业管理员账号：`wuxw`，密码：`admin`，验证码按登录页填写。该账号已通过实际浏览器登录验证。默认进入丰仪家园；附带 SQL 没有房屋、业主、费用或缴费明细，相关列表为空，需要录入自己的基础资料。

### 旧数据库兼容

源码为 0.8，附带数据库为 0.6。导入时会自动执行幂等兼容迁移，也可以单独执行：

```powershell
python scripts/local.py migrate
```

迁移清单在 `scripts/local-schema.json` 和 `scripts/local-routes.json`：补齐商户状态、小区联系电话、房屋类型字段，以及小区查询和全部缴费类型统计的路由元数据。`scripts/local-payments-query.json` 为缺失的全部缴费查询提供 SQL，按物业和小区限定范围，统一分页、笔数及金额合计。只补缺失内容，不生成业主、房屋或账单演示数据。

如果在 order 服务运行中新增了路由，重启 order 后生效。首次导入发生在服务启动前，无须额外操作。

停止：

```powershell
python scripts/local.py stop
```

也可只停止某个 Java 服务，例如 `python scripts/local.py stop front`。停止时检查记录的 PID 和命令，避免结束其他项目进程。

修改 Java 后需要先停止 Java 服务再重新构建；Windows 会锁定正在运行的 JAR。日志位于 `.runtime/logs/<服务名>.log` 和 `*-spring.log`，`status` 仅表示端口状态，不能代替业务验证。

## 定时任务与公众号

本地默认暂停**全部定时任务**，避免导入的演示任务自动发送通知或操作演示配置；因此默认不会自动生成账单或发送费用提醒。收费规则及手动收费相关代码保留。

完成本地任务配置并确认要执行后，可以显式启用未被裁剪的任务：

```powershell
python scripts/local.py stop job
$env:WUYE_ENABLE_SCHEDULED_TASKS='1'
python scripts/local.py start job
```

报修、设备、巡检等被裁剪任务始终停用。恢复默认时删除该环境变量并重启 job。真实公众号推送、微信 OAuth 和支付回调需要认证服务号、商户号及可公网访问的合法域名；本地启动不代表已完成这些联调。

## 员工端和业主端

两个目录分别为 `wuye-xcx/PropertyApp-v0.8` 和 `wuye-xcx2/WechatOwnerService-v0.8`。使用 HBuilderX 打开，选择运行到浏览器或对应小程序开发工具；H5 的 `/app` 和 `/callComponent` 代理已改为本地 `8012`。源码没有提供独立 npm 构建工具链。

员工和业主身份使用各自原有登录流程，不能拿管理后台账号直接登录。真机不能用 `127.0.0.1` 访问电脑，需要另行配置电脑局域网地址及代理；微信内公众号身份验证还需要实际服务号参数。

本次未运行 HBuilderX 编译或小程序真机联调，两个移动端已验证页面注册、菜单过滤和请求边界。

## 验证命令

```powershell
python -m unittest discover -s scripts/tests -p 'test_*.py'
node --test wuye-xcx/PropertyApp-v0.8/test/local-features.test.cjs wuye-xcx2/WechatOwnerService-v0.8/test/local-features.test.cjs
```

Web 在其目录执行 `npm test`。Java 功能边界测试可直接用项目内 Java 8 编译运行 `scripts/tests/DisabledFeaturesTest.java` 及 `DisabledFeatures.java`。全量构建执行 `python scripts/local.py build`，其中跳过项目原有测试；功能边界与 HTTP 验证另行执行。

真实 MySQL 查询回归测试需先启动隔离数据库，再设置 `$env:WUYE_TEST_MYSQL='1'` 后执行上述 Python 测试；未设置时只运行配置测试。查询测试使用连接级临时表，不向本地业务表添加测试记录。

## 本机验证记录（2026-10-21）

- Maven 21 个模块全量打包成功；12 个业务服务均在 Eureka 注册为 UP，Web 和中间件已启动。
- 实际浏览器登录并查询首页、业主、房屋、收费项目、缴费记录、公众号配置、车辆进出记录，页面无脚本异常或失败 HTTP 响应。缴费记录返回成功及零笔、零金额。
- Web 和两个移动端共 28 项检查通过；Python 5 项（含实际 MySQL 查询）通过；Java 功能边界 61 项和房屋查询 JUnit 4 项通过。
- 对 front 和 api 服务直接请求，30 项 HTTP 边界检查通过：停用功能返回 410，保留的费用、公众号和六项停车协议操作未被拦截。
- 验证范围为启动、已有页面查询和功能裁剪；未执行全部剩余业务的增删改、自动计费、真实公众号通知、真实支付或小程序编译。附带数据库仍可能存在其他历史版本差异，需要在后续业务开发时继续验证。
