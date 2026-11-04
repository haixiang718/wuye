-- Read-only progress for a published bill; property-admin app only.
START TRANSACTION;
INSERT INTO c_service(service_id,service_code,name,business_type_cd,messageQueueName,is_instance,url,method)
SELECT 'local_20261104_002','fee.billNotificationStatus','Bill notification progress','API','','N','','GET'
WHERE NOT EXISTS (SELECT 1 FROM c_service WHERE service_code='fee.billNotificationStatus');
INSERT INTO c_route(app_id,service_id,order_type_cd,invoke_limit_times,invoke_model,status_cd)
SELECT '8000418004',s.service_id,'Q',1000,'S','0' FROM c_service s
WHERE s.service_code='fee.billNotificationStatus' AND s.status_cd='0'
AND NOT EXISTS (SELECT 1 FROM c_route r WHERE r.app_id='8000418004' AND r.service_id=s.service_id AND r.status_cd='0');
COMMIT;
