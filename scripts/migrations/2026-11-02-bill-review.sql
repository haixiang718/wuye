-- Additive schema. Legacy bills remain DRAFT until a property employee explicitly reviews them.
ALTER TABLE `bill` ADD COLUMN `review_state` varchar(12) NOT NULL DEFAULT 'DRAFT';
ALTER TABLE `bill` ADD COLUMN `review_user_id` varchar(30) DEFAULT NULL;
ALTER TABLE `bill` ADD COLUMN `review_time` timestamp NULL DEFAULT NULL;
ALTER TABLE `bill` ADD COLUMN `review_remark` varchar(500) DEFAULT NULL;
ALTER TABLE `bill` ADD INDEX `idx_bill_review` (`community_id`,`review_state`,`cur_bill`,`bill_id`);
