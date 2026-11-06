-- Durable fee-to-draft events. All statements apply inside the selected database.
CREATE TABLE IF NOT EXISTS automatic_bill_queue (
    community_id varchar(30) NOT NULL,
    config_id varchar(30) NOT NULL,
    event_version bigint NOT NULL DEFAULT 1,
    processed_version bigint NOT NULL DEFAULT 0,
    next_run datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_token varchar(36) DEFAULT NULL,
    lease_until datetime(3) DEFAULT NULL,
    task_state varchar(20) NOT NULL DEFAULT 'QUEUED',
    attempts int NOT NULL DEFAULT 0,
    result_bill_id varchar(30) DEFAULT NULL,
    last_error varchar(200) DEFAULT NULL,
    updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (community_id, config_id),
    KEY idx_auto_bill_due (next_run, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DELIMITER $$
CREATE TRIGGER automatic_bill_fee_insert AFTER INSERT ON pay_fee FOR EACH ROW
BEGIN
    IF NEW.status_cd = '0' AND NEW.state = '2008001' THEN
        INSERT INTO automatic_bill_queue (community_id, config_id, next_run)
        VALUES (NEW.community_id, NEW.config_id, DATE_ADD(NOW(3), INTERVAL 1 SECOND))
        ON DUPLICATE KEY UPDATE event_version = event_version + 1,
            next_run = LEAST(next_run, DATE_ADD(NOW(3), INTERVAL 1 SECOND));
    END IF;
END$$
CREATE TRIGGER automatic_bill_fee_update AFTER UPDATE ON pay_fee FOR EACH ROW
BEGIN
    IF NEW.status_cd = '0' AND NEW.state = '2008001'
       AND (NOT (NEW.end_time <=> OLD.end_time) OR NOT (NEW.start_time <=> OLD.start_time)
            OR NOT (NEW.config_id <=> OLD.config_id) OR NOT (NEW.state <=> OLD.state)
            OR NOT (NEW.status_cd <=> OLD.status_cd)) THEN
        INSERT INTO automatic_bill_queue (community_id, config_id, next_run)
        VALUES (NEW.community_id, NEW.config_id, DATE_ADD(NOW(3), INTERVAL 1 SECOND))
        ON DUPLICATE KEY UPDATE event_version = event_version + 1,
            next_run = LEAST(next_run, DATE_ADD(NOW(3), INTERVAL 1 SECOND));
    END IF;
END$$
DELIMITER ;
