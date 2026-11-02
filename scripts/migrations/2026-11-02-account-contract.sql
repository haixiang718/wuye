-- Project-designed compatibility schema for the current 0.8 mappers.
-- Apply through migrate_business_schema.py so backup and compatibility checks run first.
-- Monetary values keep the application's existing unit with two decimal places.
CREATE TABLE IF NOT EXISTS `account` (
  `acct_id` varchar(30) NOT NULL,
  `b_id` varchar(30) NOT NULL,
  `acct_name` varchar(200) NOT NULL,
  `acct_type` varchar(12) NOT NULL,
  `obj_id` varchar(30) NOT NULL,
  `obj_type` varchar(12) DEFAULT NULL,
  `amount` decimal(18,2) NOT NULL DEFAULT 0.00,
  `status_cd` varchar(2) NOT NULL DEFAULT '0',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`acct_id`),
  KEY `idx_account_object` (`obj_id`,`obj_type`,`status_cd`,`acct_type`),
  KEY `idx_account_business` (`b_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `business_account` (
  `acct_id` varchar(30) NOT NULL,
  `b_id` varchar(30) NOT NULL,
  `operate` varchar(4) NOT NULL,
  `acct_name` varchar(200) NOT NULL,
  `acct_type` varchar(12) NOT NULL,
  `obj_id` varchar(30) NOT NULL,
  `obj_type` varchar(12) DEFAULT NULL,
  `amount` decimal(18,2) NOT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`acct_id`,`b_id`,`operate`),
  KEY `idx_business_account_batch` (`b_id`,`operate`,`obj_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `account_detail` (
  `detail_id` varchar(30) NOT NULL,
  `b_id` varchar(30) NOT NULL,
  `acct_id` varchar(30) NOT NULL,
  `detail_type` varchar(12) NOT NULL,
  `obj_id` varchar(30) NOT NULL,
  `obj_type` varchar(12) DEFAULT NULL,
  `amount` decimal(18,2) NOT NULL,
  `order_id` varchar(30) DEFAULT NULL,
  `rel_acct_id` varchar(30) DEFAULT NULL,
  `remark` varchar(1000) DEFAULT NULL,
  `status_cd` varchar(2) NOT NULL DEFAULT '0',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`detail_id`),
  KEY `idx_account_detail_account` (`acct_id`,`obj_id`,`status_cd`,`create_time`),
  KEY `idx_account_detail_order` (`order_id`),
  KEY `idx_account_detail_business` (`b_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `business_account_detail` (
  `detail_id` varchar(30) NOT NULL,
  `b_id` varchar(30) NOT NULL,
  `operate` varchar(4) NOT NULL,
  `acct_id` varchar(30) NOT NULL,
  `detail_type` varchar(12) NOT NULL,
  `obj_id` varchar(30) NOT NULL,
  `obj_type` varchar(12) DEFAULT NULL,
  `amount` decimal(18,2) NOT NULL,
  `order_id` varchar(30) DEFAULT NULL,
  `rel_acct_id` varchar(30) DEFAULT NULL,
  `remark` varchar(1000) DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`detail_id`,`b_id`,`operate`),
  KEY `idx_business_account_detail_batch` (`b_id`,`operate`,`obj_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE `contract` ADD COLUMN `b_id` varchar(30) DEFAULT NULL;
ALTER TABLE `contract` ADD COLUMN `contract_parent_id` varchar(30) DEFAULT NULL;
ALTER TABLE `contract` ADD COLUMN `obj_name` varchar(200) DEFAULT NULL;
ALTER TABLE `contract` ADD COLUMN `obj_person_id` varchar(30) DEFAULT NULL;
ALTER TABLE `contract` ADD COLUMN `obj_person_name` varchar(64) DEFAULT NULL;
ALTER TABLE `contract` ADD COLUMN `start_user_id` varchar(30) DEFAULT NULL;
ALTER TABLE `contract` ADD INDEX `idx_contract_store_parent` (`store_id`,`contract_parent_id`,`status_cd`);
