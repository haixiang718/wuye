-- Existing databases: use python scripts/migrate_wechat_pay_v3.py (read-only plan by default).
-- Quiesce payment writers, review the plan, then use --apply after authorization.
-- The runner backs up first, checks column/index definitions and skips completed steps.
-- Fresh installs already receive both changes from 2026-11-02-payment-order.sql.
-- Do not execute this file blindly: ADD COLUMN / DROP PRIMARY KEY are not repeatable SQL.
ALTER TABLE payment_order ADD COLUMN provider_version varchar(4) NOT NULL DEFAULT 'V2';
ALTER TABLE payment_order_item DROP PRIMARY KEY, ADD PRIMARY KEY (owe_id,order_id);
