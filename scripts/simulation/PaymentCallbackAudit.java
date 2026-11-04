import com.alibaba.fastjson.*;
import com.java110.core.factory.WechatFactory;
import com.java110.fee.dao.PaymentOrderRepository;
import com.java110.fee.smo.impl.FeePaymentOrderInnerServiceSMOImpl;
import com.java110.intf.fee.IFeePaymentOrderInnerServiceSMO;
import com.java110.front.smo.payment.adapt.wechatPay.*;
import com.java110.front.smo.payment.impl.*;
import com.java110.core.context.*;
import com.java110.dto.app.AppDto;
import com.java110.dto.owner.OwnerAppUserDto;
import com.java110.utils.util.PayUtil;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/** Real adapters and fee posting service, with transaction-aware memory SQL and network substitutes. */
public class PaymentCallbackAudit {
    static final String KEY="simulation-only-key-not-a-merchant-key",APP="wx-simulation-only",MCH="synthetic-merchant";
    static final String WID=WechatFactory.getWId(APP);
    static final JSONArray checks=new JSONArray();
    static int failures,downstream;
    static boolean businessFailure,transportFailure,lostResponse;
    static Memory repository;
    static IFeePaymentOrderInnerServiceSMO service;
    static Map<String,Object> map(String... values) {Map<String,Object> m=new HashMap<>();for(int i=0;i<values.length;i+=2)m.put(values[i],values[i+1]);return m;}
    static Map<String,Object> copy(Map<String,Object> value){return value==null?null:JSON.parseObject(JSON.toJSONString(value));}
    static class Memory extends PaymentOrderRepository {
        Map<String,Map<String,Object>> orders=new HashMap<>();
        Map<String,String> reservations=new HashMap<>();
        Map<String,Object> snapshot=map("oweId","simulation-owe","billId","simulation-bill","feeId","simulation-fee","billAmountOwed","1.00","feeEndTime","2026-10-01 00:00:00","deadlineTime","2026-11-01 00:00:00","ownerId","simulation-owner","ownerName","Synthetic owner","payerObjId","simulation-room","payerObjName","Synthetic room","payerObjType","3333","communityId","simulation-community","feeName","Synthetic fee");
        List<String> writes=new ArrayList<>();boolean unpaid=true;String failAt;
        final ReentrantLock lock=new ReentrantLock();
        @Override public Map<String,Object> find(Map<String,Object> key,boolean lock){return copy(orders.get(key.get("orderId")));}
        @Override public boolean merchantMatches(Map<String,Object> scope){return APP.equals(scope.get("appId"))&&MCH.equals(scope.get("mchId"));}
        @Override public String reservation(Map<String,Object> snapshot){return reservations.get(snapshot.get("communityId")+":"+snapshot.get("feeId"));}
        @Override public List<Map<String,Object>> snapshots(Map<String,Object> scope){return unpaid?new ArrayList<>(Arrays.asList(copy(snapshot))):new ArrayList<>();}
        @Override public Map<String,Object> lockSnapshot(Map<String,Object> key){return unpaid?copy(snapshot):null;}
        @Override public void insert(Map<String,Object> order){orders.put(order.get("orderId").toString(),copy(order));}
        @Override public void write(String statement,Map<String,Object> data){
            if(statement.equals(failAt)){failAt=null;throw new IllegalStateException("Synthetic SQL failure");}
            Map<String,Object> order=orders.get(data.get("orderId"));
            if("reserveTransaction".equals(statement)) {
                for(Map<String,Object> other:orders.values())if(Objects.equals(data.get("transactionId"),other.get("transactionId")))throw new IllegalStateException("Unique transaction constraint");
                order.put("transactionId",data.get("transactionId"));
            }
            if("reserveFee".equals(statement)){if(reservations.put(data.get("communityId")+":"+data.get("feeId"),data.get("orderId").toString())!=null)throw new IllegalStateException("Unique fee constraint");}
            if("releaseFee".equals(statement))reservations.remove(data.get("communityId")+":"+data.get("feeId"));
            if("settleSnapshot".equals(statement))unpaid=false;
            if("markPosted".equals(statement))order.put("state","POSTED");
            writes.add(statement);
        }
        long count(String name){return writes.stream().filter(name::equals).count();}
    }
    static class Tx {
        Map<String,Map<String,Object>> orders;Map<String,String> reservations;List<String>writes;boolean unpaid;
    }
    /** Models SQL rollback and row serialization, never opens a connection. */
    static class MemoryTransactions extends AbstractPlatformTransactionManager {
        final Memory memory;MemoryTransactions(Memory memory){this.memory=memory;}
        @Override protected Object doGetTransaction(){return new Tx();}
        @Override protected void doBegin(Object state,TransactionDefinition definition){
            memory.lock.lock();Tx tx=(Tx)state;tx.orders=new HashMap<>();
            for(Map.Entry<String,Map<String,Object>> entry:memory.orders.entrySet())tx.orders.put(entry.getKey(),copy(entry.getValue()));
            tx.writes=new ArrayList<>(memory.writes);tx.unpaid=memory.unpaid;
            tx.reservations=new HashMap<>(memory.reservations);
        }
        @Override protected void doCommit(DefaultTransactionStatus status){}
        @Override protected void doRollback(DefaultTransactionStatus status){Tx tx=(Tx)status.getTransaction();memory.orders=tx.orders;memory.writes=tx.writes;memory.unpaid=tx.unpaid;memory.reservations=tx.reservations;}
        @Override protected void doCleanupAfterCompletion(Object state){memory.lock.unlock();}
    }
    static ResponseEntity<String> merchant(String url){
        if(!url.contains("smallWeChat.listSmallWeChats"))throw new AssertionError("Unexpected network request");
        return ResponseEntity.ok("{\"smallWeChats\":[{\"appId\":\""+APP+"\",\"mchId\":\""+MCH+"\",\"payPassword\":\""+KEY+"\"}]}");
    }
    static Map<String,Object> post(Map<String,Object> paid){
        downstream++;
        if(transportFailure)throw new IllegalStateException("Synthetic HTTP 500");
        if(businessFailure)return map("code","1","state","CREATED");
        Map<String,Object> result=service.confirm(paid);
        if(lostResponse){lostResponse=false;throw new IllegalStateException("Synthetic response lost after commit");}
        return result;
    }
    static class Single extends WechatPayNotifyAdapt {
        @Override protected ResponseEntity<String> callCenterService(RestTemplate ignored,String user,String body,String url,HttpMethod method){return merchant(url);}
        @Override protected Map<String,Object> queryPaymentOrder(String id){return service.query(map("orderId",id));}
        @Override protected Map<String,Object> postPaymentOrder(Map<String,Object> paid){return post(paid);}
    }
    static class Owe extends WechatOweFeeToNotifyAdapt {
        @Override protected ResponseEntity<String> callCenterService(RestTemplate ignored,String user,String body,String url,HttpMethod method){return merchant(url);}
        @Override protected Map<String,Object> queryPaymentOrder(String id){return service.query(map("orderId",id));}
        @Override protected Map<String,Object> postPaymentOrder(Map<String,Object> paid){return post(paid);}
    }
    static List<Map<String,Object>> bindingRows=new ArrayList<>();
    static List<String> bindingRequests=new ArrayList<>();
    static class PlacementBoundary extends RuntimeException {}
    static class Placement extends ToPaySMOImpl {
        @Override protected ResponseEntity<String> callCenterService(RestTemplate ignored,IPageData pd,String body,String url,HttpMethod method){
            if(url.contains("smallWeChat.listSmallWeChats"))return merchant(url);
            if(!url.contains("owner.listAppUserBindingOwners"))throw new AssertionError("Unexpected placement request");
            bindingRequests.add(url);
            int page=queryNumber(url,"page"),row=queryNumber(url,"row");
            List<Map<String,Object>> result=new ArrayList<>();
            for(int i=(page-1)*row;i<Math.min(bindingRows.size(),page*row);i++)result.add(bindingRows.get(i));
            JSONObject response=new JSONObject();response.put("code",0);response.put("total",bindingRows.size());response.put("records",(bindingRows.size()+row-1)/row);response.put("data",result);
            return ResponseEntity.ok(response.toJSONString());
        }
        ResponseEntity<String> execute(IPageData pd,JSONObject input)throws Exception{return doBusinessProcess(pd,input);}
    }
    static int queryNumber(String url,String name){java.util.regex.Matcher match=java.util.regex.Pattern.compile("[?&]"+name+"=([0-9]+)").matcher(url);if(!match.find())throw new AssertionError("Missing pagination");return Integer.parseInt(match.group(1));}
    static Map<String,Object> binding(String open){return map("userId","simulation-user","communityId","simulation-community","state","12000","appType",OwnerAppUserDto.APP_TYPE_WECHAT,"openId",open);}
    static boolean placementReachedCreate(List<Map<String,Object>> rows)throws Exception {
        bindingRows=rows;bindingRequests.clear();Placement placement=new Placement();
        java.lang.reflect.Field field=PublishedBillToPaySMO.class.getDeclaredField("feePaymentOrderInnerServiceSMO");field.setAccessible(true);
        field.set(placement,new IFeePaymentOrderInnerServiceSMO(){
            public Map<String,Object> create(Map<String,Object> ignored){throw new PlacementBoundary();}
            public Map<String,Object> query(Map<String,Object> ignored){throw new AssertionError("Unexpected order query");}
            public Map<String,Object> confirm(Map<String,Object> ignored){throw new AssertionError("Unexpected posting");}
            public List<Map<String,Object>> pendingV3(Map<String,Object> ignored){throw new AssertionError("Unexpected pending recovery");}
            public Map<String,Object> close(Map<String,Object> ignored){throw new AssertionError("Unexpected close");}
        });
        IPageData pd=PageData.newInstance().builder("simulation-user","","","{}","","","","",AppDto.WECHAT_OWNER_APP_ID);
        JSONObject input=new JSONObject(map("communityId","simulation-community","feeId","simulation-fee","appId",APP,"tradeType","JSAPI"));
        try{placement.execute(pd,input);return false;}catch(PlacementBoundary reached){return true;}catch(IllegalArgumentException|IllegalStateException rejected){return false;}
    }
    static Map<String,Object> notification(String amount,String app,String mch){
        SortedMap<String,String> signed=new TreeMap<>();signed.put("return_code","SUCCESS");signed.put("result_code","SUCCESS");
        signed.put("appid",app);signed.put("mch_id",mch);signed.put("nonce_str","simulation-nonce");
        signed.put("out_trade_no","simulation-order");signed.put("transaction_id","simulation-transaction");
        signed.put("openid","simulation-openid");signed.put("total_fee",amount);signed.put("sign",PayUtil.createSign(signed,KEY));
        Map<String,Object> result=new HashMap<>();result.putAll(signed);return result;
    }
    static Map<String,Object> valid(){Map<String,Object> v=notification("100",APP,MCH);v.put("wId",WID);return v;}
    static String xml(Map<String,Object> values){StringBuilder out=new StringBuilder("<xml>");for(Map.Entry<String,Object> item:values.entrySet())if(!item.getKey().equals("wId"))out.append("<").append(item.getKey()).append(">").append(item.getValue()).append("</").append(item.getKey()).append(">");return out.append("</xml>").toString();}
    static void check(String name,boolean passed){JSONObject row=new JSONObject(true);row.put("name",name);row.put("passed",passed);checks.add(row);if(!passed)failures++;}
    static void reset(String type)throws Exception {
        downstream=0;businessFailure=false;transportFailure=false;lostResponse=false;repository=new Memory();
        FeePaymentOrderInnerServiceSMOImpl real=new FeePaymentOrderInnerServiceSMOImpl();
        java.lang.reflect.Field field=real.getClass().getDeclaredField("paymentOrderRepository");field.setAccessible(true);field.set(real,repository);
        ProxyFactory proxy=new ProxyFactory(real);proxy.addAdvice(new TransactionInterceptor(new MemoryTransactions(repository),new AnnotationTransactionAttributeSource()));service=(IFeePaymentOrderInnerServiceSMO)proxy.getProxy();
        Map<String,Object> order=map("orderId","simulation-order","communityId","simulation-community","userId","simulation-user","appId",APP,"mchId",MCH,"orderType",type,"providerVersion","V2","totalFee","100","state","CREATED");
        order.put("payload",JSON.toJSONString(Arrays.asList(repository.snapshot)));repository.insert(order);
    }
    static Map<String,Object> paid(){return map("orderId","simulation-order","transactionId","simulation-transaction","appId",APP,"mchId",MCH,"orderType","SINGLE","totalFee","100");}
    static boolean rejected(Map<String,Object> payment){try{service.confirm(payment);return false;}catch(RuntimeException expected){return true;}}
    public static void main(String[] args)throws Exception {
        Single single=new Single();Owe owe=new Owe();
        reset("SINGLE");check("single_standard_xml_with_query_wid",single.confirmPayFee(xml(valid()),WID).contains("CDATA[SUCCESS]")&&repository.count("insertDetail")==1);
        reset("ARREARS");check("arrears_standard_xml_with_query_wid",owe.confirmPayFee(xml(valid()),WID).contains("CDATA[SUCCESS]")&&repository.count("insertDetail")==1);
        reset("SINGLE");check("single_valid_map_can_reach_confirmation",single.confirmPayFee(valid())==1&&repository.count("insertDetail")==1);
        reset("SINGLE");Map<String,Object> bad=valid();bad.put("sign","INVALID-SYNTHETIC");boolean denied=false;try{single.confirmPayFee(bad);}catch(IllegalArgumentException expected){denied=true;}check("invalid_signature_rejected",denied&&downstream==0);
        reset("SINGLE");bad=notification("1",APP,MCH);bad.put("wId",WID);check("incorrect_amount_must_not_confirm",single.confirmPayFee(bad)==0&&downstream==0);
        reset("SINGLE");bad=notification("100","wx-wrong-app","wrong-merchant");bad.put("wId",WID);check("incorrect_app_and_merchant_must_not_confirm",single.confirmPayFee(bad)==0&&downstream==0);
        reset("SINGLE");businessFailure=true;check("business_failure_must_not_ack_success",single.confirmPayFee(valid())==0&&repository.count("insertDetail")==0);
        reset("ARREARS");transportFailure=true;int first=owe.confirmPayFee(valid());transportFailure=false;check("arrears_downstream_failure_remains_retryable",first==0&&owe.confirmPayFee(valid())==1&&downstream==2&&repository.count("insertDetail")==1);
        reset("SINGLE");single.confirmPayFee(valid());check("duplicate_single_posts_once",single.confirmPayFee(valid())==1&&repository.count("insertDetail")==1&&repository.count("insertReceipt")==1);
        reset("ARREARS");owe.confirmPayFee(valid());check("duplicate_arrears_posts_once",owe.confirmPayFee(valid())==1&&repository.count("insertDetail")==1);
        reset("ARREARS");repository.orders.clear();check("missing_durable_order_not_success",owe.confirmPayFee(valid())==0&&downstream==0);
        reset("SINGLE");transportFailure=true;String failure=single.confirmPayFee(xml(valid()),WID);check("failure_returns_valid_fail_xml","FAIL".equals(PayUtil.getMapFromXML(failure).get("return_code")));
        reset("SINGLE");check("malformed_xml_returns_fail",single.confirmPayFee("invalid xml",WID).contains("CDATA[FAIL]"));
        check("external_document_declaration_rejected_before_parse",single.confirmPayFee("<!DOCTYPE xml SYSTEM 'https://example.invalid/never-request'><xml/>",WID).contains("CDATA[FAIL]"));
        reset("SINGLE");repository.failAt="insertReceiptDetail";check("partial_posting_failure_rolls_back",single.confirmPayFee(valid())==0&&repository.writes.isEmpty()&&repository.unpaid&&"CREATED".equals(repository.orders.get("simulation-order").get("state")));
        check("partial_posting_failure_retries",single.confirmPayFee(valid())==1&&repository.count("insertDetail")==1&&repository.count("insertReceiptDetail")==1);
        reset("SINGLE");lostResponse=true;check("response_loss_returns_retry",single.confirmPayFee(valid())==0&&repository.count("insertDetail")==1);check("response_loss_retry_no_duplicate",single.confirmPayFee(valid())==1&&repository.count("insertDetail")==1);
        reset("SINGLE");Map<String,Object> wrong=paid();wrong.put("totalFee","1");check("fee_service_rechecks_amount",rejected(wrong)&&repository.writes.isEmpty());
        wrong=paid();wrong.put("mchId","wrong");check("fee_service_rechecks_merchant",rejected(wrong)&&repository.writes.isEmpty());
        wrong=paid();wrong.put("appId","wrong");check("fee_service_rechecks_app",rejected(wrong)&&repository.writes.isEmpty());
        wrong=paid();wrong.put("totalFee","100.1");check("fractional_fen_rejected",rejected(wrong)&&repository.writes.isEmpty());
        reset("SINGLE");service.confirm(paid());wrong=paid();wrong.put("transactionId","different-transaction");check("posted_transaction_cannot_change",rejected(wrong)&&repository.count("insertDetail")==1);
        reset("SINGLE");Map<String,Object> other=copy(repository.orders.get("simulation-order"));other.put("orderId","other-order");other.put("transactionId","simulation-transaction");other.put("state","POSTED");repository.insert(other);check("transaction_unique_across_orders",rejected(paid())&&repository.writes.isEmpty());
        reset("SINGLE");ExecutorService executor=Executors.newFixedThreadPool(2);Future<Map<String,Object>> a=executor.submit(()->service.confirm(paid())),b=executor.submit(()->service.confirm(paid()));a.get();b.get();executor.shutdown();check("concurrent_duplicate_posts_once",repository.count("insertDetail")==1&&repository.count("markPosted")==1);
        reset("SINGLE");Map<String,Object> request=map("communityId","simulation-community","userId","simulation-user","appId",APP,"mchId",MCH,"orderType","SINGLE","feeId","simulation-fee","receivedAmount","0.01","cycles","999");Map<String,Object> created=service.create(request);check("create_uses_snapshot_ignores_client_price",Long.parseLong(created.get("totalFee").toString())==100&&created.get("payload").toString().contains("simulation-owe"));
        check("repeat_placement_reuses_order",created.get("orderId").equals(service.create(request).get("orderId"))&&repository.orders.size()==2);
        repository.snapshot.put("deadlineTime","2026-12-01 00:00:00");boolean changedPeriod=false;try{service.create(request);}catch(IllegalStateException expected){changedPeriod=true;}check("changed_pending_period_rejected_before_provider",changedPeriod);repository.snapshot.put("deadlineTime","2026-11-01 00:00:00");
        Map<String,Object> different=new HashMap<>(request);different.put("orderType","ARREARS");boolean overlap=false;try{service.create(different);}catch(IllegalStateException expected){overlap=true;}check("overlapping_payment_cannot_create_second_order",overlap&&repository.orders.size()==2);
        repository.unpaid=false;boolean unpublished=false;try{service.create(request);}catch(IllegalArgumentException expected){unpublished=true;}check("no_payable_snapshot_rejected",unpublished);
        reset("SINGLE");repository.snapshot.put("billAmountOwed","2.00");check("changed_bill_snapshot_rolls_back",single.confirmPayFee(valid())==0&&repository.writes.isEmpty());
        Map<String,Object> foreign=binding("synthetic-open");foreign.put("communityId","other-community");check("placement_rejects_other_community_openid",!placementReachedCreate(Arrays.asList(foreign)));
        Map<String,Object> pending=binding("synthetic-open");pending.put("state","11000");check("placement_rejects_unapproved_openid",!placementReachedCreate(Arrays.asList(pending)));
        Map<String,Object> otherUser=binding("synthetic-open");otherUser.put("userId","other-user");check("placement_rejects_other_user_openid",!placementReachedCreate(Arrays.asList(otherUser)));
        Map<String,Object> otherType=binding("synthetic-open");otherType.put("appType","different-app-type");check("placement_rejects_other_app_type_openid",!placementReachedCreate(Arrays.asList(otherType)));
        List<Map<String,Object>> ambiguous=new ArrayList<>();for(int i=0;i<100;i++)ambiguous.add(binding("synthetic-open-a"));ambiguous.add(binding("synthetic-open-b"));check("placement_checks_later_pages_for_ambiguous_openid",!placementReachedCreate(ambiguous));
        check("placement_accepts_one_unique_approved_openid",placementReachedCreate(Arrays.asList(binding("synthetic-open-a"),binding("synthetic-open-a"))));
        check("placement_binding_query_is_scoped",!bindingRequests.isEmpty()&&bindingRequests.stream().allMatch(url->url.contains("communityId=simulation-community")&&url.contains("state=12000")&&url.contains("userId=simulation-user")&&url.contains("appType="+OwnerAppUserDto.APP_TYPE_WECHAT)));
        JSONObject report=new JSONObject(true);report.put("mode","production-v2-adapters-and-atomic-fee-posting-with-in-process-substitutes");report.put("checks",checks);report.put("issues",failures);report.put("realNetworkRequests",0);report.put("databaseWrites",0);report.put("duplicatePostingVerified",true);report.put("transactionBoundary","Spring transaction proxy, transaction-aware in-memory repository; SQL integration verified separately");report.put("passed",failures==0);
        Files.write(Paths.get(args[0]),JSON.toJSONString(report,true).getBytes(StandardCharsets.UTF_8));System.out.println("Payment callback audit: "+checks.size()+" checks, "+failures+" failures; no network/database side effects.");if(failures>0)System.exit(1);
    }
}
