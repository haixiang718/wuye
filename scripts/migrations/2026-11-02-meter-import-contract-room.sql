-- Compatibility schema for ContractRoomServiceDaoImplMapper.xml.
-- Back up and apply only through migrate_meter_import.py; no historical links are invented.
CREATE TABLE IF NOT EXISTS `contract_room` (
  `cr_id` varchar(30) NOT NULL,
  `b_id` varchar(30) DEFAULT NULL,
  `contract_id` varchar(30) NOT NULL,
  `store_id` varchar(30) NOT NULL,
  `room_id` varchar(30) NOT NULL,
  `room_name` varchar(200) DEFAULT NULL,
  `owner_id` varchar(30) DEFAULT NULL,
  `owner_name` varchar(200) DEFAULT NULL,
  `status_cd` varchar(2) NOT NULL DEFAULT '0',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`cr_id`),
  KEY `idx_contract_room_room` (`room_id`,`status_cd`),
  KEY `idx_contract_room_store_contract` (`store_id`,`contract_id`,`status_cd`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
