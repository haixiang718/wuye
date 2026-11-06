# 微信支付 V3 接入与运维

## 当前验收状态（2026-11-06）

**已完成指定业主的 0.01 元真实支付及自动入账验收。** 用户手机确认支付成功；随后通过官方 SDK 验签查单确认 `SUCCESS`、`JSAPI`、人民币 1 分，公众号、商户、订单及微信交易号与本地记录一致。

数据库核对：唯一成功订单为 `POSTED`，欠费明细状态为已缴 `2000`，账单实收为 `0.01`；缴费明细一条、收据明细一条、收据一份，金额均为 `0.01`；一次性费用已完成、缴费截止时间已推进，费用占用已释放。前两笔失败尝试均保留为 `CLOSED`，未产生交易号或重复入账。脱敏证据见 `validation/2026-11-06-wechat-pay-v3-live-acceptance.json`。

支付授权目录经用户确认添加保存后，本次手机支付通过。该结果仅覆盖一个指定账号的一笔小额 JSAPI 支付，保持试点限额及账号限制；不代表全量收款、退款、平台账单对账或长期独立部署验收完成。

## 首轮联调与修复记录（2026-11-04）

已部署 V3 JSAPI 下单、签名通知、主动查单、确认关单和入账补偿。当前仅开放指定测试账号，单笔上限 **1 分**，未向全部业主开放收款。

手机已调起微信收银台，但首次尝试报错，尚未完成真实付款及自动入账验收。微信验签查单确认该次订单为 `NOTPAY`，金额 1 分，后台未入账。**开发测试通过不代表真实支付验收通过。**

该次联调发现微信未支付查单响应可能不含 `trade_type`。已修正为：成功交易必须是 `JSAPI`；未成功交易允许该字段缺省，字段存在时仍校验。用户随后关闭旧订单，微信验签查单确认 `CLOSED`，但关闭结果还可能完全不含 `amount`。已补充允许这种关闭结果释放对应订单占用；提供金额时仍校验匹配，成功交易仍必须包含正确金额。H5 已增加持续错误弹窗，以便记录收银台失败原因，不能据此认定最初的微信收银台报错已经解决。

上述两处修复均已构建部署。后台查单补偿已将原测试订单同步为 `CLOSED`，数据库核对订单占用为 0，微信交易号为空，没有入账。待手机重新发起 0.01 元订单验收。

## JSAPI cashier authorization directory

The phone retry showed WeChat's native "current page URL is not registered" error for `https://h5.jiaanfuwu.cn/#/pages/roomFee/roomFee`. Configure the associated WeChat Pay merchant's **JSAPI payment authorization directory** as `https://h5.jiaanfuwu.cn/` (including the final slash). This is separate from the official account's JS interface security domain and OAuth domain. Do not add the hash route or payment callback as an authorization directory.

At this diagnostic check the first pilot order was CLOSED and the next was CREATED; neither had a transaction ID and no pilot order was POSTED. Cashier acceptance remains pending merchant configuration and another user-confirmed CNY 0.01 attempt.

## 业务链路

1. 已登录业主选择已发布账单。服务端重新验证房产权限、费用、商户和当前微信身份，并按数据库金额创建持久化订单。
2. front 通过官方 `wechatpay-java 0.2.17` SDK 发起 JSAPI 预下单，H5 使用 `WeixinJSBridge` 调起微信收银台。
3. 微信通知或服务端主动查单须通过签名校验，并匹配 AppID、商户、订单、币种、金额和交易类型。成功交易还须具有微信交易号。
4. 只有费用服务事务入账完成、订单成为 `POSTED`，H5 才提示支付成功。重复通知或查单不会重复生成缴费流水和收据。
5. 用户取消、超时或查询异常保留待确认订单。用户再次支付可主动确认关闭旧订单；只有微信签名查询确认 `CLOSED`，本地才释放占用。页面展示、恢复和取消支付本身不会自动关单。
6. 每轮后台补偿最多查询 50 个待确认 V3 订单，使用游标轮转，失败订单不会永久阻塞后续订单。该任务独立于停用的旧出账/催缴任务。

## 入口与部署方式

| 用途 | 地址 |
| --- | --- |
| 业主 H5 | `https://h5.jiaanfuwu.cn/` |
| 管理后台 | `https://admin.jiaanfuwu.cn/` |
| 公众号消息回调 | `https://wx.jiaanfuwu.cn/wechat/callback` |
| 支付 V3 通知 | `https://wx.jiaanfuwu.cn/app/payment/v3/notify` |

支付通知与公众号消息回调是独立接口，不能混填。支付通知不需要测试访问码或业主登录，但必须验证微信签名。订单查询和关闭必须由订单所属的已登录用户发起。

云服务器当前承载 nginx HTTPS 入口，通过 SSH 反向隧道连接本机网关。业务服务、数据库和 H5 仍在本机；本机及隧道离线时，支付通知、查单和入账不能正常完成。当前形态用于联调，尚未完成业务服务整体迁移上线。

## 私有配置与开关

配置文件为 `.runtime/wechat-pay.json`，证书与密钥位于 `.runtime/wechat-pay/`。这些文件均不提交 Git；不要把完整配置、证书私钥、API 密钥或 SDK 签名报文放入日志或聊天。

| 字段 | 作用 |
| --- | --- |
| `enabled` | 是否允许新下单 |
| `liveForAllOwners` | 是否明确开放全部业主；当前为 `false` |
| `pilotUserIds` | 获准参与测试的用户 ID 列表 |
| `pilotMaxTotalFen` | 试点金额上限，单位分；当前为 `1` |
| `appId`、`mchId` | 已关联授权的公众号与商户 |
| `apiV3Key` | 32 字节 API V3 密钥 |
| `merchantCertificatePath`、`merchantPrivateKeyPath` | 商户证书与私钥路径 |
| `wechatPayPublicKeyId`、`wechatPayPublicKeyPath` | 微信支付公钥 ID 与公钥路径 |
| `notifyUrl` | 上表所列支付通知地址 |

`scripts/local.py configure --profile merged` 生成 front 的配置文件路径和开关。SDK 日志保持 WARN，禁止在联调中打开包含请求签名或完整报文的 DEBUG 日志。

只关闭新收款时，将私有配置 `enabled` 设为 `false`，重新生成本机配置并重启 front；网关按配置读取新单开关。保留证书、公钥、已有订单查单、通知入口和补偿任务，以便处理此前发起的支付。不要通过删表、清空订单或删除已支付流水来回退。

## 数据库与回退

升级脚本：`scripts/migrations/2026-11-04-wechat-pay-v3.sql`。执行器 `scripts/migrate_wechat_pay_v3.py` 默认生成计划，显式 `--apply` 才备份并升级。当前 TT 已执行，保留升级前完整备份。

升级增加 V2/V3 订单版本、待确认订单查询与确认关单能力，保留关闭订单的历史，使账单可再次发起新订单。原 V2 订单维持原有标记和处理逻辑。

构建与回退 JAR 路径记录在私有 `.runtime/wechat-pay-v3-build.json`，云 nginx 的回退信息记录在 `.runtime/domain-ingress/wechat-pay-v3-ingress.json`。如果仍有 V3 待处理订单，应先关闭新下单并使用兼容 V3 的构建排查，不能直接切回不认识 V3 订单的旧程序。涉及数据库恢复必须先处理付款及新增业务记录，不能直接覆盖当前库。

## 验证与未完成范围

本地验证包含官方 SDK 的签名/解密测试、业务协议审计、隔离 MySQL 订单生命周期、H5、网关和构建检查。真实微信只读查单已确认商户认证和响应签名有效。最新针对问题的回归为业务检查 87 项、SDK 18 项、H5 116 项通过，公网 H5 入口资源与本机构建一致。关闭结果字段缺省的修复已独立复查，包含错误商户/公众号/订单不得释放占用的测试。

0.01 元测试账单为独立手工验收数据，详见 `WECHAT-PAY-V3-PILOT-PREPARATION.md`，并不验证正常出账和审核链路。2026-11-06 已通过用户真实付款及微信验签查单核对本地 `POSTED`、账单已缴、缴费明细与收据金额一致且无重复；未对真实交易额外重放通知或执行关闭操作，重复请求幂等及并发竞态的证据仍来自隔离测试。

退款、自动退款回写、微信账单下载对账、全量收款开放、支付服务整体迁移及长期运维监控不在本次已完成验收范围内。付款记录和测试收据也属于审计历史，支付后不得直接删除。
