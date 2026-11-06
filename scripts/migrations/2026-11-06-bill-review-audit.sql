-- Additive only: no historical bill is rewritten or retrospectively approved.
CREATE TABLE IF NOT EXISTS `bill_review_record` (
  `record_id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `bill_id` varchar(30) NOT NULL,
  `community_id` varchar(30) NOT NULL,
  `from_state` varchar(12) NOT NULL,
  `to_state` varchar(12) NOT NULL,
  `review_user_id` varchar(30) NOT NULL,
  `review_remark` varchar(500) DEFAULT NULL,
  `review_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`record_id`),
  KEY `idx_bill_review_record` (`community_id`,`bill_id`,`record_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DELIMITER $$
CREATE TRIGGER `tr_bill_review_record` AFTER UPDATE ON `bill`
FOR EACH ROW
BEGIN
  IF NOT (OLD.review_state <=> NEW.review_state) THEN
    IF NOT ((OLD.review_state='DRAFT' AND NEW.review_state IN ('PUBLISHED','REJECTED'))
        OR (OLD.review_state='REJECTED' AND NEW.review_state='DRAFT')) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid bill review transition';
    END IF;
    IF NEW.review_user_id IS NULL OR TRIM(NEW.review_user_id)='' OR NEW.review_user_id='-1'
        OR (NEW.review_state='REJECTED' AND (NEW.review_remark IS NULL OR TRIM(NEW.review_remark)='')) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Bill review actor and rejection reason are required';
    END IF;
    INSERT INTO bill_review_record(bill_id,community_id,from_state,to_state,review_user_id,review_remark,review_time)
    VALUES(NEW.bill_id,NEW.community_id,OLD.review_state,NEW.review_state,NEW.review_user_id,NEW.review_remark,CURRENT_TIMESTAMP);
  END IF;
END$$
CREATE TRIGGER `tr_bill_review_record_no_update` BEFORE UPDATE ON `bill_review_record`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Bill review history is immutable'$$
CREATE TRIGGER `tr_bill_review_record_no_delete` BEFORE DELETE ON `bill_review_record`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Bill review history is immutable'$$
DELIMITER ;
