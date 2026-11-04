-- Draft generation only; property-admin authentication and community scope are checked again in the listener.
START TRANSACTION;
INSERT INTO c_service(service_id,service_code,name,business_type_cd,messageQueueName,is_instance,url,method)
SELECT 'local_20261104_001','fee.generateBill','Manual draft bill generation','API','','N','','POST'
WHERE NOT EXISTS (SELECT 1 FROM c_service WHERE service_code='fee.generateBill');
INSERT INTO c_route(app_id,service_id,order_type_cd,invoke_limit_times,invoke_model,status_cd)
SELECT '8000418004',s.service_id,'Q',1000,'S','0' FROM c_service s
WHERE s.service_code='fee.generateBill' AND s.status_cd='0'
AND NOT EXISTS (SELECT 1 FROM c_route r WHERE r.app_id='8000418004' AND r.service_id=s.service_id AND r.status_cd='0');
COMMIT;
