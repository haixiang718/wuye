import com.alibaba.fastjson.*;
import com.java110.front.smo.payment.v3.*;
import com.java110.intf.fee.IFeePaymentOrderInnerServiceSMO;
import com.java110.front.smo.payment.impl.ToPaySMOImpl;
import com.java110.core.context.*;
import com.java110.dto.app.AppDto;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Offline business protocol audit: no merchant credentials, provider calls or business DB. */
public class WechatPayV3FlowAudit {
    static int checks;
    static final String APP="wxSynthetic000001",MCH="1900000001",USER="synthetic-owner",ORDER="0123456789abcdef0123456789abcdef";
    static void check(boolean condition,String description){checks++;if(!condition)throw new AssertionError(description);}
    static void denied(Runnable action,String name){try{action.run();throw new AssertionError(name);}catch(IllegalArgumentException|IllegalStateException|SecurityException expected){checks++;}}
    static class Orders implements IFeePaymentOrderInnerServiceSMO {
        public Boolean isOnlinePaymentDetail(Map<String,Object> request){throw new AssertionError("Unexpected refund query");}
        JSONObject order=JSON.parseObject("{\"orderId\":\""+ORDER+"\",\"appId\":\""+APP+"\",\"mchId\":\""+MCH+"\",\"userId\":\""+USER+"\",\"orderType\":\"SINGLE\",\"providerVersion\":\"V3\",\"totalFee\":100,\"state\":\"CREATED\"}");
        int posts,closes;boolean failPosting;
        public Map<String,Object> create(Map<String,Object> request){throw new AssertionError("Query must not create an order");}
        public Map<String,Object> query(Map<String,Object> request){if(!ORDER.equals(request.get("orderId")))throw new IllegalArgumentException();return new JSONObject(order);}
        public Map<String,Object> confirm(Map<String,Object> request){
            if(failPosting)throw new IllegalStateException("synthetic posting failure");
            if(!"POSTED".equals(order.getString("state")))posts++;
            order.put("state","POSTED");order.put("transactionId",request.get("transactionId"));
            return new JSONObject(order).fluentPut("code",0);
        }
        public Map<String,Object> close(Map<String,Object> request){
            check("CLOSED".equals(request.get("providerState")),"Only provider-confirmed close");
            if(!"CLOSED".equals(order.getString("state")))closes++;
            order.put("state","CLOSED");return new JSONObject(order).fluentPut("code",0);
        }
        public List<Map<String,Object>> pendingV3(Map<String,Object> request){return Arrays.asList(new JSONObject(order));}
    }
    static class Provider implements WechatPayV3Provider {
        int queries,closeCalls;boolean queryFailure,closePending;Set<String> queried=new HashSet<>();
        JSONObject tx=JSON.parseObject("{\"appid\":\""+APP+"\",\"mchid\":\""+MCH+"\",\"out_trade_no\":\""+ORDER+"\",\"transaction_id\":\"synthetic-transaction\",\"trade_type\":\"JSAPI\",\"trade_state\":\"SUCCESS\",\"amount\":{\"total\":100,\"currency\":\"CNY\"}}");
        public Map<String,String> prepay(String description,String orderId,long cents,String openId){throw new AssertionError("No prepay during recovery");}
        public JSONObject query(String orderId){queries++;queried.add(orderId);if(queryFailure)throw new IllegalStateException();return JSON.parseObject(tx.toJSONString());}
        public void close(String orderId){closeCalls++;if(!closePending)tx.put("trade_state","CLOSED");}
        public JSONObject notification(String raw,Map<String,String> headers){if(!"synthetic-authenticated".equals(raw))throw new SecurityException();return JSON.parseObject(tx.toJSONString());}
    }
    static WechatPayV3Settings settings;
    static Orders orders;static Provider provider;static WechatPayV3Service service;
    static void reset(){orders=new Orders();provider=new Provider();service=new WechatPayV3Service(orders,provider,settings);}
    static void inject(Object target,String name,Object value)throws Exception{
        for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())try{java.lang.reflect.Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(target,value);return;}catch(NoSuchFieldException ignored){}
        throw new AssertionError("Missing field "+name);
    }
    static class Placement extends ToPaySMOImpl {
        boolean ambiguous;
        boolean arrears;
        @Override protected String orderType(){return arrears?"ARREARS":"SINGLE";}
        ResponseEntity<String> execute(JSONObject input)throws Exception{
            return doBusinessProcess(PageData.newInstance().builder(USER,"","","{}","","","","",AppDto.WECHAT_OWNER_APP_ID),input);
        }
        @Override protected ResponseEntity<String> callCenterService(RestTemplate client,IPageData pd,String body,String url,HttpMethod method){
            if(url.contains("smallWeChat.listSmallWeChats"))return new ResponseEntity<>("{\"smallWeChats\":[{\"appId\":\""+APP+"\",\"mchId\":\""+MCH+"\"}]}",HttpStatus.OK);
            JSONObject row=JSON.parseObject("{\"userId\":\""+USER+"\",\"communityId\":\"synthetic-community\",\"appType\":\"WECHAT\",\"state\":\"12000\",\"openId\":\"synthetic-open-id\"}");
            JSONArray rows=new JSONArray();rows.add(row);
            if(ambiguous)rows.add(JSON.parseObject(row.toJSONString()).fluentPut("openId","other-open-id"));
            return new ResponseEntity<>(new JSONObject().fluentPut("code",0).fluentPut("total",rows.size()).fluentPut("data",rows).toJSONString(),HttpStatus.OK);
        }
    }
    static class PlacementOrders extends Orders {
        int creates;
        Map<String,Object> scope;
        @Override public Map<String,Object> create(Map<String,Object> request){
            scope=new HashMap<>(request);
            creates++;check("V3".equals(request.get("providerVersion")),"Placement marks the durable V3 order");
            check(Long.valueOf(100).equals(request.get("maxTotalFee")),"Pilot amount limit is supplied by server policy");
            check(USER.equals(request.get("userId")),"Payer comes from authenticated context");
            return new JSONObject(new HashMap<>(order)).fluentPut("payload","[{\"feeName\":\"Synthetic fee\"}]");
        }
    }
    static class PlacementV3 extends WechatPayV3Service {
        boolean permitted=true;int prepayments;
        @Override public boolean configured(){return true;}
        @Override public long maximumTotalFee(){return 100L;}
        @Override public void requireNewPayment(String user,String app,String mch){if(!permitted)throw new SecurityException();check(USER.equals(user)&&APP.equals(app)&&MCH.equals(mch),"Project merchant checked before placement");}
        @Override public Map<String,String> prepay(Map<String,Object> order,String description,String openId,String user){
            prepayments++;check("100".equals(String.valueOf(order.get("totalFee"))),"Provider receives frozen amount, not browser amount");
            check("synthetic-open-id".equals(openId),"Payer OpenID comes from approved binding");
            Map<String,String> result=new HashMap<>();result.put("code","0");result.put("orderId",ORDER);return result;
        }
    }
    public static void main(String[] args)throws Exception {
        Path file=Files.createTempFile("wuye-v3-flow-",".json");
        try {
            Files.write(file,("{\"enabled\":false,\"appId\":\""+APP+"\",\"mchId\":\""+MCH+"\",\"notifyUrl\":\"https://wx.example.test/app/payment/v3/notify\"}").getBytes(StandardCharsets.UTF_8));
            settings=WechatPayV3Settings.load(file);
        }finally{Files.delete(file);}
        reset();check("POSTED".equals(service.query(USER,ORDER).get("state")),"Signed success posts");check(orders.posts==1,"One posting");
        service.query(USER,ORDER);check(orders.posts==1&&provider.queries==1,"Posted queries avoid provider and duplicate writes");
        reset();denied(()->service.query("different-owner",ORDER),"Other owner rejected");check(provider.queries==0&&orders.posts==0,"No provider call or write before ownership check");
        reset();denied(()->service.notification("unsigned",new HashMap<>()),"Unsigned callback rejected");check(orders.posts==0,"Unsigned callback no posting");
        reset();service.notification("synthetic-authenticated",new HashMap<>());check(orders.posts==1,"Authenticated callback posts while new collection disabled");
        service.notification("synthetic-authenticated",new HashMap<>());check(orders.posts==1,"Duplicate callback no duplicate ledger");
        for(String field:Arrays.asList("appid","mchid","out_trade_no","trade_type")){
            reset();provider.tx.put(field,"wrong");denied(()->service.query(USER,ORDER),field+" rejected");check(orders.posts==0,field+" no posting");
        }
        for(String amount:Arrays.asList("99","100.5","-1","0","1000000000000000000000")){
            reset();provider.tx.getJSONObject("amount").put("total",amount);denied(()->service.query(USER,ORDER),"Amount mismatch/format rejected");check(orders.posts==0,"Invalid amount no posting");
        }
        reset();provider.tx.getJSONObject("amount").put("currency","USD");denied(()->service.query(USER,ORDER),"Currency rejected");
        reset();provider.tx.remove("transaction_id");denied(()->service.query(USER,ORDER),"Missing transaction rejected");
        reset();orders.failPosting=true;denied(()->service.notification("synthetic-authenticated",new HashMap<>()),"Posting failure remains retryable");check(!"POSTED".equals(orders.order.get("state")),"Never claim POSTED after failed transaction");
        reset();provider.tx.put("trade_state","NOTPAY");check("CREATED".equals(service.query(USER,ORDER).get("state")),"Unpaid remains pending");check(orders.posts==0&&orders.closes==0,"Query unpaid does not release reservations");
        reset();provider.tx.put("trade_state","NOTPAY");provider.tx.remove("trade_type");
        check("CREATED".equals(service.query(USER,ORDER).get("state")),"WeChat unpaid query may omit trade_type");
        check("CLOSED".equals(service.close(USER,ORDER).get("state")),"Unpaid and closed queries may omit trade_type without blocking confirmed close");
        reset();provider.tx.put("trade_state","CLOSED");provider.tx.remove("trade_type");provider.tx.remove("amount");provider.tx.remove("transaction_id");
        check("CLOSED".equals(service.query(USER,ORDER).get("state")),"Real signed CLOSED query omits amount and trade type");
        check(orders.closes==1&&orders.posts==0,"Minimal CLOSED evidence releases only the matching order without posting");
        for(String field:Arrays.asList("appid","mchid","out_trade_no")){
            reset();provider.tx.put("trade_state","CLOSED");provider.tx.remove("amount");provider.tx.remove("trade_type");provider.tx.put(field,"wrong");
            denied(()->service.query(USER,ORDER),"Minimal CLOSED ownership still required");check(orders.closes==0&&orders.posts==0,"Mismatched CLOSED cannot release or post");
        }
        reset();provider.tx.put("trade_state","CLOSED");provider.tx.getJSONObject("amount").put("total",99);
        denied(()->service.query(USER,ORDER),"CLOSED amount must match when supplied");check(orders.closes==0,"Mismatched supplied amount cannot release order");
        reset();provider.tx.remove("amount");denied(()->service.query(USER,ORDER),"Successful payment always requires amount");check(orders.posts==0,"Missing success amount cannot post");
        reset();provider.tx.remove("trade_type");denied(()->service.query(USER,ORDER),"Successful payment still requires JSAPI trade type");check(orders.posts==0,"Missing successful trade type never posts");
        reset();provider.queryFailure=true;denied(()->service.close(USER,ORDER),"Unknown provider state cannot close");check(orders.closes==0&&provider.closeCalls==0,"No premature close on timeout");
        reset();provider.tx.put("trade_state","NOTPAY");check("CLOSED".equals(service.close(USER,ORDER).get("state")),"Provider-confirmed close");check(provider.closeCalls==1&&orders.closes==1,"Close then query before releasing");
        reset();provider.tx.put("trade_state","NOTPAY");provider.closePending=true;check("CREATED".equals(service.close(USER,ORDER).get("state")),"Unconfirmed close remains pending");check(orders.closes==0,"No release without CLOSED evidence");
        reset();check("POSTED".equals(service.close(USER,ORDER).get("state")),"Payment wins close race");check(provider.closeCalls==0&&orders.posts==1,"Already paid never closed");
        reset();orders.order.put("providerVersion","V2");denied(()->service.query(USER,ORDER),"Legacy orders cannot be polled through V3");
        reset();orders.order.put("mchId","wrong");denied(()->service.query(USER,ORDER),"Other merchant order rejected before provider");check(provider.queries==0,"Merchant guard precedes provider");
        reset();service.reconcilePending();check(orders.posts==1,"Background recovery posts missed callbacks");
        reset();provider.queryFailure=true;
        Orders queue=new Orders(){
            @Override public List<Map<String,Object>> pendingV3(Map<String,Object> scope){
                List<Map<String,Object>> list=new ArrayList<>();String after=(String)scope.get("afterOrderId");
                check(after!=null,"Scheduler must always request a fair cursor page");
                for(int i=1;i<=66;i++){
                    String id=String.format("%032d",i);
                    if(id.compareTo(after)>0 && list.size()<50)list.add(new JSONObject(new HashMap<>(order)).fluentPut("orderId",id));
                }
                return list;
            }
        };
        service=new WechatPayV3Service(queue,provider,settings);
        service.reconcilePending();check(provider.queried.size()==50,"One scan is bounded to 50 queries");
        service.reconcilePending();check(provider.queried.size()==66,"Unresolved first page cannot starve later payments");
        service.reconcilePending();service.reconcilePending();check(provider.queries==116,"Empty page resets the scan for subsequent retries");
        Placement placement=new Placement();PlacementOrders placementOrders=new PlacementOrders();PlacementV3 placementV3=new PlacementV3();
        inject(placement,"feePaymentOrderInnerServiceSMO",placementOrders);inject(placement,"wechatPayV3Service",placementV3);
        JSONObject input=JSON.parseObject("{\"communityId\":\"synthetic-community\",\"feeId\":\"synthetic-fee\",\"appId\":\""+APP+"\",\"tradeType\":\"JSAPI\",\"receivedAmount\":\"999999.99\",\"openId\":\"browser-forged-open-id\"}");
        check(placement.execute(input).getStatusCode()==HttpStatus.OK&&placementV3.prepayments==1,"V3 placement works without a V2 payPassword");
        placement.arrears=true;input.remove("feeId");input.put("roomId","synthetic-room");input.put("oweIds",Arrays.asList("selected-a","selected-b"));
        check(placement.execute(input).getStatusCode()==HttpStatus.OK,"Selected arrears reaches V3 placement");
        check(Arrays.asList("selected-a","selected-b").equals(placementOrders.scope.get("oweIds")),"Public boundary forwards exact selected bill identifiers");
        int selectionBefore=placementOrders.creates;
        for(Object invalid:Arrays.asList(Collections.emptyList(),Arrays.asList("selected-a","selected-a"),"selected-a",Arrays.asList(1))) {
            input.put("oweIds",invalid);try{placement.execute(input);throw new AssertionError("Invalid selection accepted");}catch(IllegalArgumentException expected){checks++;}
        }
        input.remove("oweIds");try{placement.execute(input);throw new AssertionError("Missing selection accepted");}catch(IllegalArgumentException expected){checks++;}
        check(placementOrders.creates==selectionBefore,"Invalid selection cannot reserve a payment order");
        placement.arrears=false;input.put("feeId","synthetic-fee");input.remove("roomId");
        int before=placementOrders.creates;placement.ambiguous=true;
        try{placement.execute(input);throw new AssertionError("Ambiguous payer accepted");}catch(IllegalArgumentException expected){checks++;}
        check(placementOrders.creates==before,"Ambiguous identity cannot reserve or charge a bill");placement.ambiguous=false;
        input.put("tradeType","APP");try{placement.execute(input);throw new AssertionError("APP trade accepted");}catch(IllegalArgumentException expected){checks++;}
        check(placementOrders.creates==before,"Unsupported trade cannot create an order");input.put("tradeType","JSAPI");
        placementV3.permitted=false;try{placement.execute(input);throw new AssertionError("Pilot guard bypassed");}catch(SecurityException expected){checks++;}
        check(placementOrders.creates==before,"Disabled pilot creates no durable order");
        reset();
        CountDownLatch querying=new CountDownLatch(1),release=new CountDownLatch(1);
        Provider slow=new Provider(){@Override public JSONObject query(String id){querying.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException();}return super.query(id);}};
        WechatPayV3Service concurrent=new WechatPayV3Service(orders,slow,settings);
        ExecutorService threads=Executors.newFixedThreadPool(2);
        Future<?> scan=threads.submit(()->concurrent.reconcilePending());
        try{
            check(querying.await(2,TimeUnit.SECONDS),"Background query started");
            Future<Map<String,Object>> notification=threads.submit(()->concurrent.notification("synthetic-authenticated",new HashMap<>()));
            check("POSTED".equals(notification.get(1,TimeUnit.SECONDS).get("state")),"Slow background provider cannot block foreground notification");
        }finally{release.countDown();scan.get(5,TimeUnit.SECONDS);threads.shutdownNow();}
        System.out.println("V3 business flow audit: "+checks+" checks passed; no external calls or business data writes.");
    }
}
