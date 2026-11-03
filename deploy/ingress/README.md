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
ssh -N -T -o ExitOnForwardFailure=yes -o ServerAliveInterval=20 -o ServerAliveCountMax=3 -i <本机隧道私钥> -R 127.0.0.1:18080:127.0.0.1:8080 wuye-tunnel@118.31.186.6
```

6. 本机受限网关的公开来源使用 `https://h5.jiaanfuwu.cn`，配置保存在 `.runtime/wechat-domain.json`，保留访问码和精确接口白名单。H5 静态服务为 8000，受限网关为 8080，后台业务为 8012 等本机端口。业务已就绪时运行 `python scripts/wechat_domain.py start`，查看状态用 `python scripts/wechat_domain.py status`，停止入口用 `python scripts/wechat_domain.py stop`。隧道进程断线后每 5 秒重试，电脑重启后需要重新启动入口。
7. 微信验证文件放在 `/var/www/wuye-ingress/verify/`。回调 URL 为 `https://wx.jiaanfuwu.cn/wechat/callback`，网页授权域名为 `h5.jiaanfuwu.cn`；JS 接口安全域名按实际使用的 H5 来源配置。Token 从本机私有配置取值，不写入本文。

`admin.jiaanfuwu.cn` 默认返回 403；本阶段仍用本机后台操作。此配置没有启用支付，也不自动发布公众号菜单、发送通知或启动计费定时任务。

验证必须包括 DNS、证书、Nginx 配置、反向端口仅监听回环、受限页面、正确/错误微信签名以及手机 OAuth。证书自动续期需验证 `certbot renew --dry-run`，并在续期成功后重载 Nginx。
