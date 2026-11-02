-- Apply only through the supervised billing repair migration after backup.
-- No credentials or OpenIDs. CREATED remains retryable until the atomic posting commits.
CREATE TABLE IF NOT EXISTS payment_order (
  order_id varchar(32) NOT NULL,
  community_id varchar(30) NOT NULL,
  user_id varchar(30) NOT NULL,
  app_id varchar(64) NOT NULL,
  mch_id varchar(64) NOT NULL,
  order_type varchar(12) NOT NULL,
  total_fee bigint NOT NULL,
  payload longtext NOT NULL,
  state varchar(12) NOT NULL DEFAULT 'CREATED',
  transaction_id varchar(64) DEFAULT NULL,
  create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  posted_time timestamp NULL DEFAULT NULL,
  PRIMARY KEY (order_id),
  UNIQUE KEY uk_payment_transaction (transaction_id),
  KEY idx_payment_owner (community_id,user_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Active reservations protect a fee across billing periods. Release only after atomic posting
-- (or a future provider-confirmed close); never release on a timeout or cache expiry.
CREATE TABLE IF NOT EXISTS payment_order_reservation (
  community_id varchar(30) NOT NULL,
  fee_id varchar(30) NOT NULL,
  order_id varchar(32) NOT NULL,
  PRIMARY KEY (community_id,fee_id),
  KEY idx_payment_reservation_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- A snapshot can be sent to the provider under only one order number. Repeat placement reuses it.
CREATE TABLE IF NOT EXISTS payment_order_item (
  owe_id varchar(30) NOT NULL,
  order_id varchar(32) NOT NULL,
  PRIMARY KEY (owe_id),
  KEY idx_payment_item_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Already referenced by the 0.8 receipt mappers, absent from some historical deployments.
-- The supervised migration checks SHOW COLUMNS before applying these two ALTER statements.
ALTER TABLE fee_receipt ADD COLUMN pay_obj_id varchar(30) DEFAULT NULL;
ALTER TABLE fee_receipt ADD COLUMN pay_obj_name varchar(100) DEFAULT NULL;
