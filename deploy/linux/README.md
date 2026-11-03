# Linux 非支付发布准备包

此包用于后续生产服务器部署演练，尚未完成 Linux 业务运行验收。当前 `118.31.186.6` 的 2GB 云主机继续只提供 HTTPS 入口；本轮不安装或启动本包。域名可用不代表已迁移业务。

包内有六个合并模式 Java JAR、管理端源码及锁文件、H5 静态产物、受限业主网关、配置模板和 systemd 示例。无 Java/Node/MySQL/Redis/Kafka/ZooKeeper 运行时、无 node_modules、无数据库数据、无本机 `.runtime` 或私钥。运行时版本、容量、文件存储和安全策略应在目标环境单独验证。

## 在本机生成并检查

```powershell
python scripts/build_release.py build
python scripts/build_release.py verify --archive C:\Users\32090\wuye\.runtime\releases\实际文件名.tar.gz
```

构建使用现有 JAR，不会运行 Maven。`sourceCommit` 与工作区是否有修改记录在清单中；它不证明所有 JAR 均来自同一提交。正式发布前必须从选定提交重新构建，重跑验收，再生成候选包。

包内项目配置资源（包括嵌套项目 JAR）中的历史密码、密钥及字面远程主机被替换为显式占位符，保留 Spring Boot 嵌套 JAR 存储方式；源 JAR 不变。无法安全识别的敏感多行配置会终止构建。此检查不代替人工代码审查，包仍应按内部制品管理，不公开分发。清单 SHA-256 校验用于发现损坏，不是制品签名。

## 目标机准备和部署演练

1. 准备有足够资源的独立 Ubuntu 主机。六 Java 的现有 Windows 内存测量不能当 Linux 容量保证。准备独立兼容 Java 8、受支持并经应用验证的 Node（建议从 Node 22 验证）、Python 3，以及 MySQL、Redis、Kafka、ZooKeeper；内部端口仅回环/受控内网。基础设施不由本包自动安装。
2. 上传制品，先核对传输前后的 SHA-256。放入 `/opt/wuye/releases/<版本>/`，使用 `tar -tzf` 检查后解压到新的空目录。不要以 root 解压未知来源文件。运行 `python3 bin/release.py verify-tree --directory .`；不要跳过校验。
3. 由管理员创建系统用户 `wuye`，准备 `/var/lib/wuye`、`/var/log/wuye`、`/etc/wuye`，权限分别为 `wuye:wuye` 可写运行目录、`root:wuye` 的 0750 配置目录。制品保持 root 所有，将版本目录及文件所属组设置为 `wuye`、目录权限 0750、普通文件 0640、脚本 0750，让服务用户只读；不要授予服务用户写制品的权限。
4. 用 `python3 linux/prepare_config.py --directory /etc/wuye` 生成 `.example`，填写后去掉扩展名。脚本不覆盖已有文件，不读取本机密钥，也不创建放行标记。包内五份数据源模板保留原分片规则，必须逐个设置本机 MySQL URL、账号、密码，检查 `TT` 和 `hc_community` 双库映射；不要整体复制演示数据库。保留 Sharding-JDBC 的 `${...}` 分片表达式。
5. 每个服务的 JSON 单独审核。生产凭据只进入 `/etc/wuye` 的 0640 文件。Redis、Kafka、ZooKeeper 默认模板端口为 6379/9092/2181，与当前开发机端口不同。设置存储/FTP 生产值；未准备的功能不开放。`SPRING_APPLICATION_JSON` 由启动器注入，避免把密码写入进程命令行。六个 Java 强制只监听回环；七个业务域强制路由到 business:8015。支付和全局定时任务由启动器强制关闭。启动命令通过 Spring Boot 的 `--logging.config` 强制使用安全日志模板，避免内置 DEBUG 配置在 Boot 重置日志时重新生效；不要删除该参数。
6. 将 `linux/config/wechat.json.example` 复制到 `/etc/wuye/wechat.json`，填正式公众号的 AppID、后台回调路径、Token、业主小区等字段，生成独立随机访问码（至少 16 字符）和会话密钥（至少 32 字符）。AppSecret 保存在经过核验的后台配置中，不需要在入站网关文件再保存。`smsEnabled:false` 必须与后台明确 `SMS_SEND_SWITCH=OFF` 的人工核验注册方案对齐；不能在生产库自动添加这个设置。短信开通后须一起切换并验收。网关继续保留访问码，不是完全开放的正式生产入口。
7. 在 `/opt/wuye/deps` 安装后台依赖：复制管理端 `package.json`、`package-lock.json`，运行 `npm ci --omit=dev`。不要在已校验的版本目录里创建 node_modules 或修改文件；独立依赖由 systemd 的 `NODE_PATH` 指向。
8. 建立 `/opt/wuye/current` 指向该版本目录。人工审核配置后，才创建 `/etc/wuye/READY_FOR_NONPAYMENT` 标记。它是管理员签认，不代表工具已经完成 Linux 验收。逐个运行 `python3 /opt/wuye/current/linux/bin/start_service.py <服务名> --check`；输出不含凭据。准备基础设施健康检查、磁盘配额及日志轮转。
9. 审核并复制 `linux/systemd/` 三份文件至 `/etc/systemd/system/`，执行 `systemd-analyze verify` 与 `systemctl daemon-reload`。先按基础设施→eureka→business/order→api/front/job→静态 H5/后台/网关的顺序启动，逐一检查业务健康和 Eureka 注册。`control.sh start` 提供显式整体启动动作，并拒绝小于 3GiB 的主机；这只是防止误用当前小机，不保证 3GiB 足够。首次部署不要直接 enable 自动开机，验收恢复能力后再启用 target。
10. 按 `linux/nginx/owner.conf.example` 审核配置 Nginx 的 HTTPS、微信校验文件和回调，核实证书路径后执行 `nginx -t`，通过后才 reload；另配置证书自动续期及重载。该模板尚未在新的目标机做语法和联调验收，不能直接覆盖当前入口站点。业主只代理到 127.0.0.1:8080，静态服务为 8000；后台 3000 默认不公开。独立服务器直接使用网关，不继续依赖开发电脑反向隧道。正式出口 IP 加入微信白名单，核对数据库映射、缓存和 OAuth 来源后，再邀请测试人员登录。

## 验收、停止和回退

- `control.sh status`、`journalctl -u 'wuye-java@*' -u 'wuye-node@*'` 检查状态与日志；进程存在不等于业务健康。必须逐项验收登录、房产隔离、账单审核/查询、禁止支付、任务关闭、重启恢复、备份恢复及故障回退。
- `control.sh stop` 停止应用 target，不停止数据库或消息队列。检查实例也停止，不能只看 target 状态。
- 回退前暂停业务写入，确认数据库兼容性，保留新流水，再切换 current 到已核验版本并复验。不能用旧数据库快照覆盖新业务流水。
- 数据库和附件必须做独立备份与恢复演练，本包不是数据备份。不把运行日志、密钥、数据库或证书加回制品目录。
- 当前商户号未就绪，不开展收款、支付回调、退款、对账或超时关单。本包也不启用自动出账、通知或旧 goods 任务。
