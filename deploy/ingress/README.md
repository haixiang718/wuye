# 固定域名联调入口

这套入口用于用户已确认的“2GB 云服务器提供 HTTPS，本机承载业务”方案。电脑及隧道必须持续在线。运行密钥位于 Git 忽略的 `.runtime`，不得上传。

1. 添加 `h5`、`wx`、`admin` 三条 A 记录到 `118.31.186.6`。
2. 使用 `python scripts/server_ssh.py preflight` 只读检查 Ubuntu、权限、已有站点与端口。
3. 将本目录文件上传到服务器独立目录。以 root 执行 `bash setup.sh http`，只添加 `wuye-domain-ingress` 站点；配置校验失败时恢复原文件。
4. 确保云防火墙允许 TCP 80/443，三个域名从公网均可访问，再执行证书签发：

```bash
certbot certonly --webroot -w /var/www/wuye-ingress/acme --cert-name wuye-ingress \
  -d h5.jiaanfuwu.cn -d wx.jiaanfuwu.cn -d admin.jiaanfuwu.cn \
  --non-interactive --agree-tos --register-unsafely-without-email
bash setup.sh https
```

5. 创建独立的隧道 Ed25519 密钥，仅上传公钥为 `tunnel.pub`，注释必须为 `wuye-domain-tunnel`。执行 `bash setup-tunnel.sh`，随后用专用账户从本机建立转发：

```text
ssh -N -T -o ExitOnForwardFailure=yes -o ServerAliveInterval=20 -o ServerAliveCountMax=3 -i <本机隧道私钥> -R 127.0.0.1:18080:127.0.0.1:8080 -R 127.0.0.1:18081:127.0.0.1:3000 wuye-tunnel@118.31.186.6
```

6. 本机受限网关的公开来源使用 `https://h5.jiaanfuwu.cn`，配置保存在 `.runtime/wechat-domain.json`，保留访问码和精确接口白名单。H5 静态服务为 8000，受限网关为 8080，后台业务为 8012 等本机端口。业务已就绪时运行 `python scripts/wechat_domain.py start`，查看状态用 `python scripts/wechat_domain.py status`，停止入口用 `python scripts/wechat_domain.py stop`。隧道进程断线后每 5 秒重试，电脑重启后需要重新启动入口。
7. 微信验证文件放在 `/var/www/wuye-ingress/verify/`。回调 URL 为 `https://wx.jiaanfuwu.cn/wechat/callback`，网页授权域名为 `h5.jiaanfuwu.cn`；JS 接口安全域名按实际使用的 H5 来源配置。Token 从本机私有配置取值，不写入本文。

2026-11-04 已接通管理后台：<https://admin.jiaanfuwu.cn/>。使用原后台账号、密码及验证码登录，原有小区和操作权限保持有效。HTTP 自动跳转 HTTPS；后台走独立的服务器回环端口 18081，不经过业主 H5 的访问码网关。上传请求大小限制为 20MB。此配置没有启用支付，也不自动发布公众号菜单、发送通知或启动计费定时任务。

恢复入口：先启动本机后台及业务，再运行 `python scripts/wechat_domain.py start`。已运行旧隧道时，升级服务器 SSH 配置后依次执行 `python scripts/server_tunnel.py stop`、`python scripts/server_tunnel.py start`；这个操作会短暂重连 H5 与后台两个入口。数据库及 Java 服务不需要因隧道重连而重启。

本次服务器备份：Nginx 为 `/var/backups/wuye-ingress/20261104T052719Z-7894/site.conf`；SSH 配置和原公钥限制为 `/var/backups/wuye-ingress/ssh-20261104T052701Z-7746/`。撤销后台公网入口时先恢复 Nginx 备份、执行 `sudo nginx -t && sudo systemctl reload nginx`，后台恢复 403。若同时撤销 18081 隧道，须同步还原本机转发列表、服务器 SSH 配置及公钥限制，校验 SSH 配置后再重连；不可只撤销服务器端口许可，否则双端口隧道将整体连接失败。

验证必须包括 DNS、证书、Nginx 配置、反向端口仅监听回环、受限页面、正确/错误微信签名以及手机 OAuth。证书自动续期需验证 `certbot renew --dry-run`，并在续期成功后重载 Nginx。

本次公网后台验证：HTTP 跳转、浏览器登录页、未登录账单查询被重定向到登录页、已有会话下小区及账单查询、页面资源加载等 8 项通过；公众号 H5 及签名回调 7 项通过。已有会话验证使用本机保存的授权会话，不等同外部用户首次输入密码的完整登录验收。未发送通知、未收款。电脑及隧道必须持续在线。
