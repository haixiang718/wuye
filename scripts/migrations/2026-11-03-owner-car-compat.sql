-- 0.8 OwnerCar mapper requires these columns in both instance and order staging tables.
-- 1001 denotes a primary vehicle, not the separate vehicle/body-type field car_type.
-- Historical member identifiers remain unknown; do not invent or overwrite them.
ALTER TABLE `owner_car` ADD COLUMN `car_type_cd` varchar(4) NOT NULL DEFAULT '1001';
ALTER TABLE `owner_car` ADD COLUMN `member_id` varchar(30) DEFAULT NULL;
ALTER TABLE `business_owner_car` ADD COLUMN `car_type_cd` varchar(4) NOT NULL DEFAULT '1001';
ALTER TABLE `business_owner_car` ADD COLUMN `member_id` varchar(30) DEFAULT NULL;
