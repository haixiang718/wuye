# 数据表与功能对应表（V2 / 2026-11-06）

V2 当前共 **318 张表**：TT **273 张**、hc_community **45 张**。V1 的 392 张表中，已移除 74 张指定模块专属表。

[V2 裁剪记录](V2-MODULE-PRUNING.md) | [V1 的 392 张表说明](https://github.com/haixiang718/wuye/blob/v1.0.0/docs/DATABASE-FUNCTION-MAP.md)

按主要用途归类，不等于最近访问统计。保留的事务配套表、Activiti 与 Quartz 框架结构不能仅因为空或没有页面就删除。用途未确认的历史表继续保留。

| 功能组 | TT | hc_community | 合计 |
| --- | ---: | ---: | ---: |
| 小区、楼栋、房屋与业主 | 28 | 9 | 37 |
| 收费、账单、支付与账户 | 44 | 0 | 44 |
| 公众号配置与消息 | 12 | 0 | 12 |
| 后台账号、组织与权限 | 25 | 0 | 25 |
| 物业公司与商户 | 14 | 0 | 14 |
| 合同 | 6 | 0 | 6 |
| 车辆与停车 | 13 | 0 | 13 |
| 停车共享设备结构 | 6 | 0 | 6 |
| 采购、仓库与物资 | 12 | 0 | 12 |
| 活动、公告与广告 | 11 | 0 | 11 |
| 工作流、审批与通用任务 | 43 | 29 | 72 |
| 定时任务调度 | 11 | 0 | 11 |
| 公共配置、文件与日志 | 44 | 7 | 51 |
| 历史快照与备份 | 4 | 0 | 4 |
| **合计** | **273** | **45** | **318** |

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

## 停车共享设备结构（6 张）

设备管理和门禁业务已移除；以下 6 张表由保留的停车设备校验和指令接口使用。

| 数据库 | 表名 | 用途/线索 | 清理判断 |
| --- | --- | --- | --- |
| TT | `business_machine` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_machine_attrs` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `business_machine_translate` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `machine` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `machine_attrs` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `machine_translate` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |

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

## 活动、公告与广告（11 张）

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
| TT | `business_notice` | 业务事务配套；该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |
| TT | `n_notice` | 该功能组的配套结构；详细引用见数据库盘点 JSON | 有代码/配置/框架依赖 |

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

来源：V1 数据盘点、代码引用和 V2 实际迁移记录。逐表集合已与迁移完成后的两库清单比对。
