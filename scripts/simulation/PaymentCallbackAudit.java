import com.alibaba.fastjson.*;
import com.java110.core.factory.WechatFactory;
import com.java110.dto.fee.FeeDto;
import com.java110.front.smo.payment.adapt.wechatPay.WechatPayNotifyAdapt;
import com.java110.front.smo.payment.adapt.wechatPay.WechatOweFeeToNotifyAdapt;
import com.java110.utils.factory.ApplicationContextFactory;
import com.java110.utils.util.PayUtil;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import redis.clients.jedis.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Diagnostic only: production callback code, synthetic credentials, no network or database writes. */
public class PaymentCallbackAudit {
    static final String KEY="simulation-only-key-not-a-merchant-key";
    static final String APP="wx-simulation-only";
    static final String WID=WechatFactory.getWId(APP);
    static final Map<String,String> cache=new HashMap<>();
    static final JSONArray checks=new JSONArray();
    static int downstream, failures;
    static boolean businessFailure, transportFailure;
    static ResponseEntity<String> response(String url) {
        if(url.contains("smallWeChat.listSmallWeChats"))return ResponseEntity.ok("{\"smallWeChats\":[{\"appId\":\""+APP+"\",\"mchId\":\"synthetic-merchant\",\"payPassword\":\""+KEY+"\"}]}");
        if(url.contains("owner.listAppUserBindingOwners"))return ResponseEntity.ok("{\"data\":[{\"userId\":\"simulation-user\"}]}");
        if(url.endsWith("/api/fee.payFeeConfirm")||url.endsWith("/api/fee.payOweFee")) {
            downstream++;
            if(transportFailure)return new ResponseEntity<>("{}",HttpStatus.INTERNAL_SERVER_ERROR);
            return ResponseEntity.ok(businessFailure?"{\"code\":1,\"msg\":\"Synthetic posting failure\"}":"{\"code\":0}");
        }
        throw new AssertionError("Unexpected service call: all network calls must remain stubbed");
    }
    static class Single extends WechatPayNotifyAdapt {
        @Override protected ResponseEntity<String> callCenterService(RestTemplate ignored,String user,String body,String url,HttpMethod method){return response(url);}
    }
    static class Owe extends WechatOweFeeToNotifyAdapt {
        @Override protected ResponseEntity<String> callCenterService(RestTemplate ignored,String user,String body,String url,HttpMethod method){return response(url);}
    }
    static Map<String,Object> notification(String amount,String app,String merchant) {
        SortedMap<String,String> signed=new TreeMap<>();
        signed.put("return_code","SUCCESS");signed.put("result_code","SUCCESS");
        signed.put("appid",app);signed.put("mch_id",merchant);signed.put("nonce_str","simulation-nonce");
        signed.put("out_trade_no","simulation-order");signed.put("transaction_id","simulation-transaction");
        signed.put("openid","simulation-openid");signed.put("total_fee",amount);
        signed.put("sign",PayUtil.createSign(signed,KEY));
        Map<String,Object> result=new HashMap<>();result.putAll(signed);return result;
    }
    static Map<String,Object> valid(){return notification("100",APP,"synthetic-merchant");}
    static Map<String,Object> mapEntry(Map<String,Object> value){value.put("wId",WID);return value;}
    static String xml(Map<String,Object> values) {
        StringBuilder out=new StringBuilder("<xml>");
        for(Map.Entry<String,Object> item:values.entrySet())out.append("<").append(item.getKey()).append(">").append(item.getValue()).append("</").append(item.getKey()).append(">");
        return out.append("</xml>").toString();
    }
    static void check(String name,boolean passed,String actual,String boundary){
        JSONObject item=new JSONObject(true);item.put("name",name);item.put("passed",passed);item.put("actual",actual);item.put("boundary",boundary);checks.add(item);if(!passed)failures++;
    }
    static void reset(){downstream=0;businessFailure=false;transportFailure=false;cache.clear();}
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected sanitized output path");
        StaticApplicationContext context=new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("jedisPool",new JedisPool(){@Override public Jedis getResource(){return new Jedis(){
            @Override public byte[] get(byte[] key){return null;}
            @Override public String getSet(String key,String value){String old=cache.get(key);cache.put(key,value);return old;}
            @Override public Long del(String key){return cache.remove(key)==null?0L:1L;}
            @Override public void close(){}
        };}});ApplicationContextFactory.setApplicationContext(context);
        Single single=new Single();Owe owe=new Owe();
        reset();String reply=single.confirmPayFee(xml(valid()),WID);
        check("single_standard_xml_with_query_wid",reply.contains("[SUCCESS]")||reply.contains("CDATA[SUCCESS]"),"downstream confirmations="+downstream,"Production public XML method; wId supplied as URL parameter");
        reset();reply=owe.confirmPayFee(xml(valid()),WID);
        check("arrears_standard_xml_with_query_wid",reply.contains("CDATA[SUCCESS]"),"downstream confirmations="+downstream,"Production public XML method; no nonstandard wId in WeChat body");
        reset();int result=single.confirmPayFee(mapEntry(valid()));
        check("single_valid_map_can_reach_confirmation",result==1&&downstream==1,"confirmation requests="+downstream,"Map method isolates behavior AFTER missing URL-parameter propagation; downstream is a stub");
        reset();Map<String,Object> invalid=mapEntry(valid());invalid.put("sign","INVALID-SIMULATION-SIGNATURE");boolean rejected=false;
        try{single.confirmPayFee(invalid);}catch(IllegalArgumentException expected){rejected=true;}
        check("invalid_signature_rejected",rejected&&downstream==0,"confirmation requests="+downstream,"Map method, synthetic key only");
        reset();result=single.confirmPayFee(mapEntry(notification("1",APP,"synthetic-merchant")));
        check("incorrect_amount_must_not_confirm",result!=1&&downstream==0,"signed amount=1 cent, expected synthetic order=100 cents, confirmations="+downstream,"Adapter does not load expected order or forward the paid amount for comparison");
        reset();result=single.confirmPayFee(mapEntry(notification("100","wx-wrong-app","wrong-merchant")));
        check("incorrect_app_and_merchant_must_not_confirm",result!=1&&downstream==0,"confirmation requests="+downstream,"Signed synthetic payload has appId and merchant differing from selected configuration");
        reset();businessFailure=true;result=single.confirmPayFee(mapEntry(valid()));
        check("business_failure_must_not_ack_success",result!=1,"adapter result="+result,"Downstream HTTP 200 body code=1; no real posting performed");
        reset();cache.put(FeeDto.REDIS_PAY_OWE_FEE+"simulation-order","{\"fees\":[]}");transportFailure=true;
        int first=owe.confirmPayFee(mapEntry(valid()));transportFailure=false;int retry=owe.confirmPayFee(mapEntry(valid()));
        check("arrears_downstream_failure_remains_retryable",first==0&&retry==1&&downstream==2,"first result="+first+", retry result="+retry+", total downstream requests="+downstream,"Synthetic Redis GETSET/DEL semantics; retry must call posting again after initial HTTP 500");
        reset();single.confirmPayFee(mapEntry(valid()));single.confirmPayFee(mapEntry(valid()));
        JSONObject report=new JSONObject(true);report.put("mode","production-legacy-v2-adapters-with-in-process-network-and-redis-stubs");report.put("checks",checks);report.put("issues",failures);
        report.put("duplicateSingleCallbackForwarded",downstream);report.put("duplicatePostingVerified",false);
        report.put("realNetworkRequests",0);report.put("databaseWrites",0);report.put("passed",failures==0);
        Files.write(Paths.get(args[0]),JSON.toJSONString(report,true).getBytes(StandardCharsets.UTF_8));
        System.out.println("Payment callback diagnostic: "+checks.size()+" checks, "+failures+" reproduced issues; no network/database side effects.");
    }
}
