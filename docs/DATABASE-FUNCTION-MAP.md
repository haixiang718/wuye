# 数据表与功能对应表（2026-11-06）

这里的 392 是两个数据库的物理表总数：TT 327 张、hc_community 65 张，不是 392 个业务功能。以下按主要用途将每张表归到一个功能组；功能组划分是阅读代码与表名的说明口径，不等于最近访问统计。

其中 94 张以 business_ 开头，另有拼写为 buiness_small_wechat 的同类表，主要是原有业务事务的中间/变更配套结构，不能一概当作备份删除；58 张 act_ 属于两个库中的 Activiti 工作流结构，11 张 qrtz_ 属于定时任务调度。

业务功能被隐藏或服务被停用，并不会自动删除这些表。同名表位于不同数据库，也不代表其中一张可以直接删除。清理判断沿用源码/动态 SQL/框架引用盘点；待确认的旧表只作用途归类，不承诺其具体历史用法。

| 功能组 | TT | hc_community | 合计 | 当前情况 |
| --- | ---: | ---: | ---: | --- |
| 小区、楼栋、房屋与业主 | 28 | 9 | 37 | 当前核心功能，含部分旧关联表 |
| 收费、账单、支付与账户 | 44 | 0 | 44 | 当前核心功能；退款只保留保护，尚未实现完整微信退款 |
| 公众号配置与消息 | 12 | 0 | 12 | 当前公众号接入、菜单与账单通知相关 |
| 后台账号、组织与权限 | 25 | 0 | 25 | 当前共享基础 |
| 物业公司与商户 | 14 | 0 | 14 | 当前共享基础，含旧关联结构 |
| 合同 | 6 | 0 | 6 | 后台保留；H5 合同费用入口已移除 |
| 车辆与停车 | 13 | 0 | 13 | 保留车辆/停车费相关结构及接口 |
| 报修、投诉与访客 | 13 | 0 | 13 | 裁剪模块遗留结构 |
| 设备与门禁 | 10 | 0 | 10 | 裁剪模块遗留结构 |
| 巡检 | 14 | 0 | 14 | 裁剪模块遗留结构 |
| 采购、仓库与物资 | 12 | 0 | 12 | 裁剪模块遗留结构，含未确认旧表 |
| 商城与团购 | 16 | 20 | 36 | 商城已裁剪，数据库结构保留 |
| 活动、公告、广告与二手需求 | 13 | 0 | 13 | 智慧服务相关裁剪/隐藏模块的遗留结构 |
| 房屋出租 | 5 | 0 | 5 | H5 入口已移除，旧结构保留 |
| 工作流、审批与通用任务 | 43 | 29 | 72 | 引擎及通用任务结构；存在不等于当前全部启用 |
| 定时任务调度 | 11 | 0 | 11 | Quartz 框架结构；任务是否启用由配置决定 |
| 公共配置、文件与日志 | 44 | 7 | 51 | 共享底层及少量示例/旧配置表 |
| 历史快照与备份 | 4 | 0 | 4 | 仅表名/引用支持历史判断，继续保留 |
| **合计** | **327** | **65** | **392** | |

## 小区、楼栋、房屋与业主（37 张）

当前核心功能，含部分旧关联表。

| 数据库 | 表名 | 用途/线索 | 清理判断 |
| --- | --- | --- | --- |
| TT | `building_owner` | 业主及成员基本资料 | 有代码/配置/框架依赖 |
| TT | `building_owner_attr` | 业主扩展属性 | 有代码/配置/框架依赖 |
| TT | `building_owner_room_rel` | 业主与房屋绑定关系 | 有代码/配置/框架依赖 |
| TT | `building_room` | 房屋基本资料 | 有代码/配置/框架依赖 |
| TT | `building_room_attr` | 房屋扩展属性 | 有代码/配置/框架依赖 |
| TT | `building_unit` | 单元资料 | 有代码/配置/框架依赖 |
| TT | `business_building_owner` | 业务事务配套；业主及成员基本资料 | 有代码/配置/框架依赖 |
| TT | `business_building_owner_attr` | 业务事务配套；业主扩展属性 | 有代码/配置/框架依赖 |
| TT | `business_building_owner_room_rel` | 业务事务配套；业主与房屋绑定关系 | 有代码/配置/框架依赖 |
| TT | `business_building_room` | 业务事务配套；房屋基本资料 | 有代码/配置/框架依赖 |
| TT | `business_building_room_attr` | 业务事务配套；房屋扩展属性 | 有代码/配置/框架依赖 |
| TT | `business_building_unit` | 业务事务配套；单元资料 | 有代码/配置/框架依赖 |
| TT | `business_community` | 业务事务配套；小区基础资料 | 有代码/配置/框架依赖 |
| TT | `business_community_attr` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_community_location` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_community_member` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_community_photo` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_floor` | 业务事务配套；楼栋资料 | 有代码/配置/框架依赖 |
| TT | `business_owner_app_user` | 业务事务配套；业主与移动端/微信账号的绑定 | 有代码/配置/框架依赖 |
| TT | `community_location` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `community_member_type` | 该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 有数据，用途需确认 |
| TT | `f_floor` | 楼栋资料 | 有代码/配置/框架依赖 |
| TT | `owner_app_user` | 业主与移动端/微信账号的绑定 | 有代码/配置/框架依赖 |
| TT | `property_user_rel` | 该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 有数据，用途需确认 |
| TT | `s_community` | 小区基础资料 | 有代码/配置/框架依赖 |
| TT | `s_community_attr` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `s_community_member` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `s_community_photo` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `business_community` | 业务事务配套；小区基础资料 | 有代码/配置/框架依赖 |
| hc_community | `business_community_attr` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `business_community_member` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `business_community_photo` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `community_member_type` | 该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 有数据，用途需确认 |
| hc_community | `s_community` | 小区基础资料 | 有代码/配置/框架依赖 |
| hc_community | `s_community_attr` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `s_community_member` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `s_community_photo` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |

## 收费、账单、支付与账户（44 张）

当前核心功能；退款只保留保护，尚未实现完整微信退款。

| 数据库 | 表名 | 用途/线索 | 清理判断 |
| --- | --- | --- | --- |
| TT | `account` | 账户主记录 | 有代码/配置/框架依赖 |
| TT | `account_detail` | 账户流水 | 有代码/配置/框架依赖 |
| TT | `bill` | 账单批次、账期、应收/实收及审核状态 | 有代码/配置/框架依赖 |
| TT | `bill_owe_fee` | 业主/房屋每笔欠费的发布快照 | 有代码/配置/框架依赖 |
| TT | `business_account` | 业务事务配套；账户主记录 | 有代码/配置/框架依赖 |
| TT | `business_account_detail` | 业务事务配套；账户流水 | 有代码/配置/框架依赖 |
| TT | `business_meter_water` | 业务事务配套；水电抄表读数与用量 | 有代码/配置/框架依赖 |
| TT | `business_pay_fee` | 业务事务配套；房屋或车辆的费用记录 | 有代码/配置/框架依赖 |
| TT | `business_pay_fee_attrs` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_pay_fee_config` | 业务事务配套；收费项目、价格与计算规则 | 有代码/配置/框架依赖 |
| TT | `business_pay_fee_detail` | 业务事务配套；每次实收的缴费明细 | 有代码/配置/框架依赖 |
| TT | `business_pay_fee_detail_discount` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_return_pay_fee` | 业务事务配套；旧退费申请与处理记录 | 有代码/配置/框架依赖 |
| TT | `fee_discount` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `fee_discount_rule` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `fee_discount_rule_spec` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `fee_discount_spec` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `fee_formula` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `fee_manual_collection` | 人工托收批次 | 有代码/配置/框架依赖 |
| TT | `fee_manual_collection_detail` | 人工托收明细 | 有代码/配置/框架依赖 |
| TT | `fee_print_spec` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `fee_receipt` | 收据主记录 | 有代码/配置/框架依赖 |
| TT | `fee_receipt_detail` | 收据分项及缴费明细关联 | 有代码/配置/框架依赖 |
| TT | `import_fee` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `import_fee_detail` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `meter_water` | 水电抄表读数与用量 | 有代码/配置/框架依赖 |
| TT | `pay_fee` | 房屋或车辆的费用记录 | 有代码/配置/框架依赖 |
| TT | `pay_fee_attrs` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `pay_fee_audit` | 缴费审核记录 | 有代码/配置/框架依赖 |
| TT | `pay_fee_config` | 收费项目、价格与计算规则 | 有代码/配置/框架依赖 |
| TT | `pay_fee_config_discount` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `pay_fee_detail` | 每次实收的缴费明细 | 有代码/配置/框架依赖 |
| TT | `pay_fee_detail_discount` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `payment_order` | 微信支付订单、金额和处理状态 | 有代码/配置/框架依赖 |
| TT | `payment_order_item` | 支付订单与所选账单的关系 | 有代码/配置/框架依赖 |
| TT | `payment_order_reservation` | 待确认支付的费用占用，防止重复收费 | 有代码/配置/框架依赖 |
| TT | `prestore_fee` | 预存费用记录 | 有代码/配置/框架依赖 |
| TT | `property_fee_time` | 该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 空表且未找到直接引用 |
| TT | `property_fee_type` | 该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 空表且未找到直接引用 |
| TT | `report_fee_month_statistics` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `return_pay_fee` | 旧退费申请与处理记录 | 有代码/配置/框架依赖 |
| TT | `s_store_fee` | 该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 有数据，用途需确认 |
| TT | `store_fee_time` | 该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 有数据，用途需确认 |
| TT | `store_fee_type` | 该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 有数据，用途需确认 |

## 公众号配置与消息（12 张）

当前公众号接入、菜单与账单通知相关。

| 数据库 | 表名 | 用途/线索 | 清理判断 |
| --- | --- | --- | --- |
| TT | `buiness_small_wechat` | 业务事务配套；公众号/小程序基础配置 | 有代码/配置/框架依赖 |
| TT | `business_msg` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_msg_read` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_small_wechat_attr` | 业务事务配套；微信配置扩展字段 | 有代码/配置/框架依赖 |
| TT | `business_wechat_menu` | 业务事务配套；公众号自定义菜单 | 有代码/配置/框架依赖 |
| TT | `msg` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `msg_read` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `small_wechat` | 公众号/小程序基础配置 | 有代码/配置/框架依赖 |
| TT | `small_wechat_attr` | 微信配置扩展字段 | 有代码/配置/框架依赖 |
| TT | `wechat_attr` | 该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 空表且未找到直接引用 |
| TT | `wechat_menu` | 公众号自定义菜单 | 有代码/配置/框架依赖 |
| TT | `wechat_sms_template` | 公众号消息模板配置 | 有代码/配置/框架依赖 |

## 后台账号、组织与权限（25 张）

当前共享基础。

| 数据库 | 表名 | 用途/线索 | 清理判断 |
| --- | --- | --- | --- |
| TT | `business_org` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_org_community` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_org_staff_rel` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_user` | 业务事务配套；后台用户账号 | 有代码/配置/框架依赖 |
| TT | `business_user_attr` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_user_credentials` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_user_tag` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `m_menu` | 后台功能菜单 | 有代码/配置/框架依赖 |
| TT | `m_menu_group` | 后台菜单分组 | 有代码/配置/框架依赖 |
| TT | `p_privilege` | 权限定义 | 有代码/配置/框架依赖 |
| TT | `p_privilege_group` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `p_privilege_rel` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `p_privilege_user` | 用户权限关系 | 有代码/配置/框架依赖 |
| TT | `staff_app_auth` | 员工移动端授权 | 有代码/配置/框架依赖 |
| TT | `u_location` | 该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 空表且未找到直接引用 |
| TT | `u_org` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `u_org_community` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `u_org_staff_rel` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `u_user` | 后台用户账号 | 有代码/配置/框架依赖 |
| TT | `u_user_address` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `u_user_attr` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `u_user_credentials` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `u_user_login` | 登录记录/会话相关数据 | 有代码/配置/框架依赖 |
| TT | `u_user_tag` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `user_level` | 该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 有数据，用途需确认 |

## 物业公司与商户（14 张）

当前共享基础，含旧关联结构。

| 数据库 | 表名 | 用途/线索 | 清理判断 |
| --- | --- | --- | --- |
| TT | `business_member_store` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_store` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_store_attr` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_store_cerdentials` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_store_photo` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_store_user` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `s_member_store` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `s_store` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `s_store_attr` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `s_store_cerdentials` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `s_store_photo` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `s_store_user` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `store_type` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `store_user_rel` | 该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 有数据，用途需确认 |

## 合同（6 张）

后台保留；H5 合同费用入口已移除。

| 数据库 | 表名 | 用途/线索 | 清理判断 |
| --- | --- | --- | --- |
| TT | `contract` | 合同主记录 | 有代码/配置/框架依赖 |
| TT | `contract_attr` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `contract_room` | 合同与房屋的关联 | 有代码/配置/框架依赖 |
| TT | `contract_type` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `contract_type_spec` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `contract_type_template` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |

## 车辆与停车（13 张）

保留车辆/停车费相关结构及接口。

| 数据库 | 表名 | 用途/线索 | 清理判断 |
| --- | --- | --- | --- |
| TT | `business_car_black_white` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_car_inout` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_car_inout_detail` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_owner_car` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_parking_area` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_parking_space` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `car_black_white` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `car_inout` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `car_inout_detail` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `owner_car` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `p_parking_space` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `parking_area` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `parking_space` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |

## 报修、投诉与访客（13 张）

裁剪模块遗留结构。

| 数据库 | 表名 | 用途/线索 | 清理判断 |
| --- | --- | --- | --- |
| TT | `appraise` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_complaint` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_repair_pool` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_repair_setting` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_repair_type_user` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_repair_user` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_visit_info` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `complaint` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `r_repair_pool` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `r_repair_setting` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `r_repair_type_user` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `r_repair_user` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `s_visit_info` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |

## 设备与门禁（10 张）

裁剪模块遗留结构。

| 数据库 | 表名 | 用途/线索 | 清理判断 |
| --- | --- | --- | --- |
| TT | `application_key` | 门禁开门申请/凭证（不是微信商户密钥表） | 有代码/配置/框架依赖 |
| TT | `business_application_key` | 业务事务配套；门禁开门申请/凭证（不是微信商户密钥表） | 有代码/配置/框架依赖 |
| TT | `business_machine` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_machine_attrs` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_machine_record` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_machine_translate` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `machine` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `machine_attrs` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `machine_record` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `machine_translate` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |

## 巡检（14 张）

裁剪模块遗留结构。

| 数据库 | 表名 | 用途/线索 | 清理判断 |
| --- | --- | --- | --- |
| TT | `business_inspection_plan` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_inspection_plan_staff` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_inspection_point` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_inspection_route` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_inspection_route_point_rel` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_inspection_task` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_inspection_task_detail` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `inspection_plan` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `inspection_plan_staff` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `inspection_point` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `inspection_route` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `inspection_route_point_rel` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `inspection_task` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `inspection_task_detail` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |

## 采购、仓库与物资（12 张）

裁剪模块遗留结构，含未确认旧表。

| 数据库 | 表名 | 用途/线索 | 清理判断 |
| --- | --- | --- | --- |
| TT | `business_purchase_apply` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_purchase_apply_detail` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_resource_item` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 空表且未找到直接引用 |
| TT | `business_resource_order` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 空表且未找到直接引用 |
| TT | `business_resource_store` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `purchase_apply` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `purchase_apply_detail` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `resource_item` | 该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 空表且未找到直接引用 |
| TT | `resource_order` | 该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 空表且未找到直接引用 |
| TT | `resource_store` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `s_store_house` | 该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 有数据，用途需确认 |
| TT | `s_store_house_attr` | 该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 空表且未找到直接引用 |

## 商城与团购（36 张）

商城已裁剪，数据库结构保留。

| 数据库 | 表名 | 用途/线索 | 清理判断 |
| --- | --- | --- | --- |
| TT | `business_shop` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_shop_attr` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_shop_attr_param` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_shop_catalog` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_shop_desc` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_shop_photo` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_shop_preferential` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `s_buy_shop` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `s_buy_shop_attr` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `s_shop` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `s_shop_attr` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `s_shop_attr_param` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `s_shop_catalog` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `s_shop_desc` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `s_shop_photo` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `s_shop_preferential` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `group_buy` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `group_buy_batch` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `group_buy_product` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `group_buy_product_spec` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `group_buy_setting` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `product` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `product_attr` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `product_category` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `product_detail` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `product_label` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `product_spec` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `product_spec_detail` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `product_spec_value` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `store_cart` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `store_order` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `store_order_address` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `store_order_cart` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `store_order_cart_event` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `store_order_cart_return` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `store_order_cart_return_event` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |

## 活动、公告、广告与二手需求（13 张）

智慧服务相关裁剪/隐藏模块的遗留结构。

| 数据库 | 表名 | 用途/线索 | 清理判断 |
| --- | --- | --- | --- |
| TT | `activities` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `activities_event` | 该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 空表且未找到直接引用 |
| TT | `activities_type` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `advert` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `advert_item` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_activities` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_activities_event` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 空表且未找到直接引用 |
| TT | `business_advert` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_advert_item` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_junk_requirement` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_notice` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `junk_requirement` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `n_notice` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |

## 房屋出租（5 张）

H5 入口已移除，旧结构保留。

| 数据库 | 表名 | 用途/线索 | 清理判断 |
| --- | --- | --- | --- |
| TT | `renting_appointment` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `renting_config` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `renting_pool` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `renting_pool_attr` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `renting_pool_flow` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |

## 工作流、审批与通用任务（72 张）

引擎及通用任务结构；存在不等于当前全部启用。

| 数据库 | 表名 | 用途/线索 | 清理判断 |
| --- | --- | --- | --- |
| TT | `act_evt_log` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_evt_log_copy` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_ge_bytearray` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_ge_property` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_hi_actinst` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_hi_attachment` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_hi_comment` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_hi_detail` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_hi_identitylink` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_hi_procinst` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_hi_taskinst` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_hi_varinst` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_id_group` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_id_info` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_id_membership` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_id_user` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_procdef_info` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_re_deployment` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_re_model` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_re_procdef` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_ru_deadletter_job` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_ru_event_subscr` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_ru_execution` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_ru_identitylink` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_ru_job` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_ru_suspended_job` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_ru_task` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_ru_timer_job` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `act_ru_variable` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `audit_user` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_audit_user` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_task` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_task_attr` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_workflow` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_workflow_step` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_workflow_step_staff` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `task` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `task_attr` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `task_template` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `task_template_spec` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `workflow` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `workflow_step` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `workflow_step_staff` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `act_evt_log` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_evt_log_copy` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_ge_bytearray` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_ge_property` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_hi_actinst` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_hi_attachment` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_hi_comment` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_hi_detail` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_hi_identitylink` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_hi_procinst` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_hi_taskinst` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_hi_varinst` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_id_group` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_id_info` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_id_membership` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_id_user` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_procdef_info` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_re_deployment` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_re_model` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_re_procdef` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_ru_deadletter_job` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_ru_event_subscr` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_ru_execution` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_ru_identitylink` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_ru_job` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_ru_suspended_job` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_ru_task` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_ru_timer_job` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |
| hc_community | `act_ru_variable` | Activiti 流程定义、运行/历史、任务或身份结构；按后缀区分 | 有代码/配置/框架依赖 |

## 定时任务调度（11 张）

Quartz 框架结构；任务是否启用由配置决定。

| 数据库 | 表名 | 用途/线索 | 清理判断 |
| --- | --- | --- | --- |
| TT | `qrtz_blob_triggers` | Quartz 调度任务、触发器、执行状态或锁；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `qrtz_calendars` | Quartz 调度任务、触发器、执行状态或锁；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `qrtz_cron_triggers` | Quartz 调度任务、触发器、执行状态或锁；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `qrtz_fired_triggers` | Quartz 调度任务、触发器、执行状态或锁；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `qrtz_job_details` | Quartz 调度任务、触发器、执行状态或锁；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `qrtz_locks` | Quartz 调度任务、触发器、执行状态或锁；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `qrtz_paused_trigger_grps` | Quartz 调度任务、触发器、执行状态或锁；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `qrtz_scheduler_state` | Quartz 调度任务、触发器、执行状态或锁；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `qrtz_simple_triggers` | Quartz 调度任务、触发器、执行状态或锁；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `qrtz_simprop_triggers` | Quartz 调度任务、触发器、执行状态或锁；按后缀区分 | 有代码/配置/框架依赖 |
| TT | `qrtz_triggers` | Quartz 调度任务、触发器、执行状态或锁；按后缀区分 | 有代码/配置/框架依赖 |

## 公共配置、文件与日志（51 张）

共享底层及少量示例/旧配置表。

| 数据库 | 表名 | 用途/线索 | 清理判断 |
| --- | --- | --- | --- |
| TT | `attr_spec` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `attr_value` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_bus` | 业务事务配套；业务类型配置的事务配套表 | 有代码/配置/框架依赖 |
| TT | `business_demo` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_file_rel` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `c_app` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `c_business` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `c_business_attrs` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `c_business_databus` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `c_business_type` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `c_cache` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `c_cache_2_user` | 该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 有数据，用途需确认 |
| TT | `c_mapping` | 公共配置映射 | 有代码/配置/框架依赖 |
| TT | `c_order_item` | 业务事务分项与补偿记录 | 有代码/配置/框架依赖 |
| TT | `c_order_type` | 该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 有数据，用途需确认 |
| TT | `c_orders` | 原系统业务事务记录（不是微信支付订单） | 有代码/配置/框架依赖 |
| TT | `c_orders_attrs` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `c_route` | 服务路由配置 | 有代码/配置/框架依赖 |
| TT | `c_service` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `c_service_business` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `c_service_sql` | 通用服务的动态查询配置 | 有代码/配置/框架依赖 |
| TT | `c_status` | 该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 有数据，用途需确认 |
| TT | `c_template` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `c_template_col` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `city_area` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `credentials` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `demo` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `file_rel` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `hc_file` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `hc_ftp_item_spec` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `hc_ftp_log` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `hc_ftp_task` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `hc_ftp_task_attr` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `hc_ftp_tasklog` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `hc_ftp_tasklog_detail` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `l_transaction_log_message` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `log_system_error` | 系统异常日志 | 有代码/配置/框架依赖 |
| TT | `spec` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `sys_document` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `t_dict` | 业务字典 | 有代码/配置/框架依赖 |
| TT | `tag` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `transaction_log` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `transaction_log_message` | 事务交互日志消息 | 有代码/配置/框架依赖 |
| TT | `unitem_log` | 事务分项历史日志 | 有代码/配置/框架依赖 |
| hc_community | `attr_spec` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `attr_value` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `c_mapping` | 公共配置映射 | 有代码/配置/框架依赖 |
| hc_community | `c_status` | 该功能组的配套结构；详细引用见数据库盘点 JSON；旧表用途仍需确认 | 有数据，用途需确认 |
| hc_community | `city_area` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `spec` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| hc_community | `t_dict` | 业务字典 | 有代码/配置/框架依赖 |

## 历史快照与备份（4 张）

仅表名/引用支持历史判断，继续保留。

| 数据库 | 表名 | 用途/线索 | 清理判断 |
| --- | --- | --- | --- |
| TT | `c_service_business_31` | 旧菜单、权限或服务配置快照，保留来源待确认 | 历史来源待确认 |
| TT | `m_menu_20200316` | 旧菜单、权限或服务配置快照，保留来源待确认 | 历史来源待确认 |
| TT | `m_menu_org` | 旧菜单、权限或服务配置快照，保留来源待确认 | 历史来源待确认 |
| TT | `p_privilege_0731` | 旧菜单、权限或服务配置快照，保留来源待确认 | 历史来源待确认 |

来源：`docs/validation/2026-11-06-database-inventory.json` 的每表引用记录、当前裁剪策略 `wuye-f/MicroCommunityWeb-v0.8/public/js/local-features.js` 及 H5 页面注册清单。已只读复核当前两库的表名集合仍与这 392 张完全一致。
