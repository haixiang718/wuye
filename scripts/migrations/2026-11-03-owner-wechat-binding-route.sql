-- Only the existing internal official-account owner app may invoke this listener.
-- The listener independently verifies the opaque server-issued WeChat identity session.
START TRANSACTION;
INSERT INTO c_service(service_id,service_code,name,business_type_cd,messageQueueName,is_instance,url,method)
SELECT 'local_20261103_002','owner.bindWechatIdentity','Verified WeChat owner binding','API','','Y','http://order-service/orderApi/service','POST'
WHERE NOT EXISTS (SELECT 1 FROM c_service WHERE service_code='owner.bindWechatIdentity');
INSERT INTO c_route(app_id,service_id,order_type_cd,invoke_limit_times,invoke_model,status_cd)
SELECT '992020061452450002',s.service_id,'D',1000,'S','0' FROM c_service s
WHERE s.service_code='owner.bindWechatIdentity' AND s.status_cd='0'
AND NOT EXISTS (SELECT 1 FROM c_route r WHERE r.app_id='992020061452450002' AND r.service_id=s.service_id AND r.status_cd='0');
COMMIT;
