import com.java110.fee.dao.PaymentOrderRepository;
import com.java110.fee.smo.impl.FeePaymentOrderInnerServiceSMOImpl;
import com.java110.intf.fee.IFeePaymentOrderInnerServiceSMO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.junit.*;
import java.io.*;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Uses actual schema clones, production SQL, real JDBC transactions, and synthetic rows only. */
public class PaymentOrderDatabaseTest {
    static JdbcTemplate sql;static SqlSessionTemplate mapper;static DataSourceTransactionManager manager;
    static IFeePaymentOrderInnerServiceSMO service;
    static final String APP="wx-simulation-only",MCH="synthetic-merchant";
    static Map<String,Object> data(Object... pairs){Map<String,Object> value=new HashMap<>();for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);return value;}
    static class FaultRepository extends PaymentOrderRepository {
        String failAt;
        @Override public void write(String statement,Map<String,Object> row){if(statement.equals(failAt)){failAt=null;throw new IllegalStateException("Synthetic SQL boundary failure");}super.write(statement,row);}
    }
    static IFeePaymentOrderInnerServiceSMO instance(PaymentOrderRepository repository)throws Exception {
        repository.setSessionTemplate(mapper);
        FeePaymentOrderInnerServiceSMOImpl real=new FeePaymentOrderInnerServiceSMOImpl();
        java.lang.reflect.Field field=real.getClass().getDeclaredField("paymentOrderRepository");field.setAccessible(true);field.set(real,repository);
        ProxyFactory factory=new ProxyFactory(real);factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        return (IFeePaymentOrderInnerServiceSMO)factory.getProxy();
    }
    @BeforeClass public static void setup()throws Exception {
        String database=System.getProperty("schema.audit","");if(!database.matches("audit_schema_[a-f0-9]{12}"))throw new IllegalArgumentException("Invalid audit database");
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/"+database+"?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false","root","");source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        sql=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
        Configuration config=new Configuration(new Environment("payment-test",new SpringManagedTransactionFactory(),source));
        try(InputStream stream=Files.newInputStream(Paths.get(System.getProperty("schema.mapper.root"),"fee/PaymentOrderMapper.xml"))){new XMLMapperBuilder(stream,config,"paymentOrder",config.getSqlFragments()).parse();}
        mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));service=instance(new PaymentOrderRepository());
    }
    /** Supply synthetic values for legacy mandatory columns; honor defaults and nullability. */
    static void insert(String table,Map<String,Object> values){
        Map<String,Object> row=new LinkedHashMap<>();
        for(Map<String,Object> column:sql.queryForList("SHOW COLUMNS FROM `"+table+"`")) {
            String name=column.get("Field").toString(),type=column.get("Type").toString().toLowerCase();
            if(values.containsKey(name)){row.put(name,values.get(name));continue;}
            if("YES".equals(column.get("Null")) || column.get("Default")!=null || String.valueOf(column.get("Extra")).contains("auto_increment"))continue;
            Object value="x";
            if(type.contains("int")||type.contains("decimal")||type.contains("float")||type.contains("double"))value=1;
            else if(type.contains("date")||type.contains("timestamp"))value="2026-10-01 00:00:00";
            else if(type.startsWith("time"))value="00:00:00";
            row.put(name,value);
        }
        StringJoiner columns=new StringJoiner(","),marks=new StringJoiner(",");for(String key:row.keySet()){columns.add("`"+key+"`");marks.add("?");}
        sql.update("INSERT INTO `"+table+"`("+columns+") VALUES ("+marks+")",row.values().toArray());
    }
    @Before public void reset() {
        for(String table:Arrays.asList("payment_order_reservation","payment_order_item","payment_order","fee_receipt_detail","fee_receipt","pay_fee_detail","bill_owe_fee","bill","pay_fee","pay_fee_config","owner_app_user","building_owner","small_wechat"))sql.update("DELETE FROM `"+table+"`");
        insert("small_wechat",data("appId",APP,"mch_id",MCH,"obj_id","community","obj_type","1000","status_cd","0","wechat_id","merchant-id","pay_password","synthetic-only"));
        insert("building_owner",data("owner_id","owner","member_id","member","community_id","community","status_cd","0"));
        insert("owner_app_user",data("app_user_id","binding","member_id","member","user_id","user","community_id","community","state","12000","status_cd","0"));
        insert("pay_fee_config",data("config_id","config","community_id","community","status_cd","0","fee_name","Synthetic fee","end_time","2030-01-01 00:00:00"));
        insert("bill",data("bill_id","bill","community_id","community","config_id","config","review_state","PUBLISHED","cur_bill","T","receivable",new BigDecimal("1.00"),"receipts",BigDecimal.ZERO,"cur_receivable",new BigDecimal("1.00"),"bill_time","2026-10-01 00:00:00"));
        addFee("fee","owe");
    }
    static void addFee(String fee,String owe){
        insert("pay_fee",data("fee_id",fee,"community_id","community","config_id","config","status_cd","0","payer_obj_id","room","payer_obj_type","3333","end_time","2026-10-01 00:00:00","state","2008001","fee_flag","1003006"));
        insert("bill_owe_fee",data("owe_id",owe,"bill_id","bill","fee_id",fee,"community_id","community","state","1000","owner_id","owner","owner_name","Synthetic owner","owner_tel","19900000000","payer_obj_name","Synthetic room","payer_obj_type","3333","fee_end_time","2026-10-01 00:00:00","deadline_time","2026-11-01 00:00:00","bill_amount_owed",new BigDecimal("1.00"),"amount_owed",new BigDecimal("1.00")));
    }
    static Map<String,Object> request(String fee){return data("communityId","community","userId","user","appId",APP,"mchId",MCH,"orderType","SINGLE","feeId",fee,"receivedAmount","0.01","cycles","999");}
    static Map<String,Object> selected(String... ids){Map<String,Object> q=request("fee");q.remove("feeId");q.put("orderType","ARREARS");q.put("roomId","room");q.put("payObjType","3333");q.put("oweIds",Arrays.asList(ids));return q;}
    @Test public void selectedArrearsOnlyChargesAndPostsChosenItems(){
        addFee("fee-two","owe-two");sql.update("UPDATE bill SET receivable=2.00,bill_time=bill_time");
        Map<String,Object> order=service.create(selected("owe"));assertEquals(100L,((Number)order.get("totalFee")).longValue());
        assertEquals(1,com.alibaba.fastjson.JSON.parseArray(order.get("payload").toString()).size());assertEquals(1,count("payment_order_reservation"));
        service.confirm(payment(order,"selected-transaction"));assertEquals(1,count("pay_fee_detail"));assertEquals(1,count("fee_receipt_detail"));
        assertEquals("1000",sql.queryForObject("SELECT state FROM bill_owe_fee WHERE owe_id='owe-two'",String.class));
        assertEquals(new BigDecimal("1.00"),sql.queryForObject("SELECT receipts FROM bill",BigDecimal.class));
        assertEquals(100L,((Number)service.create(selected("owe-two")).get("totalFee")).longValue());
    }
    @Test public void arrearsCannotImplicitlyChargeAllOrAcceptMalformedSelection(){
        for(Object ids:Arrays.asList(Collections.emptyList(),Arrays.asList("owe","owe"),Arrays.asList("owe",null),"owe",Arrays.asList(1),Arrays.asList(" owe"))) {
            Map<String,Object> q=selected("owe");q.put("oweIds",ids);rejected(()->service.create(q));
        }
        Map<String,Object> missing=selected("owe");missing.remove("oweIds");rejected(()->service.create(missing));
        assertEquals(0,count("payment_order"));assertEquals(0,count("payment_order_reservation"));
    }
    @Test public void anyMissingUnpublishedPaidOrForeignSelectionRejectsWholeOrder(){
        rejected(()->service.create(selected("owe","missing")));
        addFee("fee-two","owe-two");sql.update("UPDATE bill_owe_fee SET owner_id='foreign',fee_end_time=fee_end_time WHERE owe_id='owe-two'");
        rejected(()->service.create(selected("owe","owe-two")));
        sql.update("UPDATE bill_owe_fee SET owner_id='owner',state='2000',fee_end_time=fee_end_time WHERE owe_id='owe-two'");rejected(()->service.create(selected("owe","owe-two")));
        sql.update("UPDATE bill_owe_fee SET state='1000',fee_end_time=fee_end_time WHERE owe_id='owe-two'");sql.update("UPDATE pay_fee SET payer_obj_id='other-room' WHERE fee_id='fee-two'");rejected(()->service.create(selected("owe","owe-two")));
        assertEquals(0,count("payment_order"));assertEquals(0,count("payment_order_item"));
    }
    @Test public void pendingSelectedOrdersRequireIdenticalItemSet(){
        addFee("fee-two","owe-two");Map<String,Object> order=service.create(selected("owe","owe-two"));
        assertEquals(order.get("orderId"),service.create(selected("owe-two","owe")).get("orderId"));
        rejected(()->service.create(selected("owe")));assertEquals(1,count("payment_order"));assertEquals(2,count("payment_order_item"));
    }
    static Map<String,Object> payment(Map<String,Object> order,String transaction){return data("orderId",order.get("orderId"),"transactionId",transaction,"appId",APP,"mchId",MCH,"orderType",order.get("orderType"),"totalFee",order.get("totalFee"));}
    static int count(String table){return sql.queryForObject("SELECT count(*) FROM `"+table+"`",Integer.class);}
    static void rejected(Runnable call){try{call.run();fail("Expected safe rejection");}catch(IllegalArgumentException|IllegalStateException expected){}}
    @Test public void createUsesTrustedPublishedAmountAndReusesPendingOrder(){Map<String,Object> order=service.create(request("fee"));assertEquals(100L,((Number)order.get("totalFee")).longValue());assertEquals(order.get("orderId"),service.create(request("fee")).get("orderId"));assertEquals(1,count("payment_order"));assertEquals(1,count("payment_order_item"));assertEquals(1,count("payment_order_reservation"));}
    @Test public void unpublishedAndUnapprovedCannotCreate(){sql.update("UPDATE bill SET review_state='DRAFT'");rejected(()->service.create(request("fee")));sql.update("UPDATE bill SET review_state='PUBLISHED'");sql.update("UPDATE owner_app_user SET state='11000'");rejected(()->service.create(request("fee")));assertEquals(0,count("payment_order"));}
    @Test public void otherUserAndCommunityCannotCreate(){Map<String,Object> request=request("fee");request.put("userId","other-user");rejected(()->service.create(request));request.put("userId","user");request.put("communityId","other-community");rejected(()->service.create(request));}
    @Test public void merchantMismatchAndAmbiguityCannotCreate(){Map<String,Object> request=request("fee");request.put("mchId","wrong");rejected(()->service.create(request));Map<String,Object> duplicate=sql.queryForMap("SELECT * FROM small_wechat");duplicate.put("wechat_id","other-config");insert("small_wechat",duplicate);rejected(()->service.create(request("fee")));}
    @Test public void postingAtomicallySettlesFeeAndWritesReceipts(){Map<String,Object> order=service.create(request("fee"));Map<String,Object> paid=payment(order,"transaction");assertEquals("POSTED",service.confirm(paid).get("state"));assertEquals(1,count("pay_fee_detail"));assertEquals(1,count("fee_receipt_detail"));assertEquals(1,count("fee_receipt"));assertEquals(0,count("payment_order_reservation"));assertEquals(new BigDecimal("1.00"),sql.queryForObject("SELECT receipts FROM bill",BigDecimal.class));assertEquals("2000",sql.queryForObject("SELECT state FROM bill_owe_fee",String.class));assertEquals("2026-11-01 00:00:00",sql.queryForObject("SELECT DATE_FORMAT(end_time,'%Y-%m-%d %H:%i:%s') FROM pay_fee",String.class));assertEquals("2026-10-01 00:00:00",sql.queryForObject("SELECT DATE_FORMAT(fee_end_time,'%Y-%m-%d %H:%i:%s') FROM bill_owe_fee",String.class));assertEquals("2026-10-01 00:00:00",sql.queryForObject("SELECT DATE_FORMAT(bill_time,'%Y-%m-%d %H:%i:%s') FROM bill",String.class));}
    @Test public void duplicateSurvivesNewServiceInstanceAndNoCache()throws Exception {Map<String,Object> order=service.create(request("fee"));Map<String,Object> paid=payment(order,"transaction");service.confirm(paid);assertEquals("POSTED",instance(new PaymentOrderRepository()).confirm(paid).get("state"));assertEquals(1,count("pay_fee_detail"));assertEquals(1,count("fee_receipt"));}
    @Test public void lateFailureRollsBackAllPostingAndRetryWorks()throws Exception {Map<String,Object> order=service.create(request("fee"));FaultRepository repository=new FaultRepository();repository.failAt="insertReceipt";IFeePaymentOrderInnerServiceSMO faulty=instance(repository);rejected(()->faulty.confirm(payment(order,"transaction")));assertEquals(0,count("pay_fee_detail"));assertEquals(0,count("fee_receipt_detail"));assertEquals(0,count("fee_receipt"));assertEquals("1000",sql.queryForObject("SELECT state FROM bill_owe_fee",String.class));assertEquals(BigDecimal.ZERO.setScale(2),sql.queryForObject("SELECT receipts FROM bill",BigDecimal.class));assertEquals("CREATED",service.query(data("orderId",order.get("orderId"))).get("state"));assertNull(sql.queryForObject("SELECT transaction_id FROM payment_order",String.class));assertEquals(1,count("payment_order_reservation"));assertEquals("POSTED",instance(new PaymentOrderRepository()).confirm(payment(order,"transaction")).get("state"));assertEquals(1,count("pay_fee_detail"));}
    @Test public void confirmationRechecksAmountMerchantAndTransaction(){Map<String,Object> order=service.create(request("fee"));Map<String,Object> paid=payment(order,"transaction");paid.put("totalFee",1);rejected(()->service.confirm(paid));paid.put("totalFee",100);paid.put("mchId","wrong");rejected(()->service.confirm(paid));paid.put("mchId",MCH);service.confirm(paid);paid.put("transactionId","different");rejected(()->service.confirm(paid));assertEquals(1,count("pay_fee_detail"));}
    @Test public void uniqueTransactionCannotPayTwoOrders(){addFee("fee-two","owe-two");Map<String,Object> one=service.create(request("fee")),two=service.create(request("fee-two"));service.confirm(payment(one,"transaction"));try{service.confirm(payment(two,"transaction"));fail("duplicate transaction accepted");}catch(org.springframework.dao.DataIntegrityViolationException expected){}assertEquals(1,count("pay_fee_detail"));assertEquals("CREATED",service.query(data("orderId",two.get("orderId"))).get("state"));}
    @Test public void concurrentDuplicateCommitsOnlyOnce()throws Exception {Map<String,Object> order=service.create(request("fee"));ExecutorService pool=Executors.newFixedThreadPool(2);try{Callable<Map<String,Object>> post=()->service.confirm(payment(order,"transaction"));Future<Map<String,Object>> a=pool.submit(post),b=pool.submit(post);assertEquals("POSTED",a.get().get("state"));assertEquals("POSTED",b.get().get("state"));assertEquals(1,count("pay_fee_detail"));assertEquals(1,count("fee_receipt"));}finally{pool.shutdownNow();}}
    @Test public void concurrentPlacementReusesSameNumber()throws Exception {ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<Map<String,Object>> a=pool.submit(()->service.create(request("fee"))),b=pool.submit(()->service.create(request("fee")));assertEquals(a.get().get("orderId"),b.get().get("orderId"));assertEquals(1,count("payment_order"));}finally{pool.shutdownNow();}}
    @Test public void pendingOldBillBlocksNewOverlappingBillButCanStillPost(){Map<String,Object> old=service.create(request("fee"));sql.update("UPDATE bill SET cur_bill='F',bill_time=bill_time");Map<String,Object> bill=sql.queryForMap("SELECT * FROM bill");bill.put("bill_id","next-bill");bill.put("cur_bill","T");insert("bill",bill);Map<String,Object> owe=sql.queryForMap("SELECT * FROM bill_owe_fee");owe.put("owe_id","next-owe");owe.put("bill_id","next-bill");owe.put("deadline_time","2026-12-01 00:00:00");insert("bill_owe_fee",owe);rejected(()->service.create(request("fee")));assertEquals(1,count("payment_order"));assertEquals("POSTED",service.confirm(payment(old,"transaction")).get("state"));rejected(()->service.create(request("fee")));assertEquals(1,count("pay_fee_detail"));}
    @Test public void missingOrderNeverMeansPaid(){rejected(()->service.confirm(data("orderId","absent","transactionId","transaction","appId",APP,"mchId",MCH,"orderType","SINGLE","totalFee",100)));assertEquals(0,count("pay_fee_detail"));}
    @Test public void receiptOverflowRejectedBeforeCollection(){sql.update("UPDATE bill_owe_fee SET bill_amount_owed=10000000.00,fee_end_time=fee_end_time");rejected(()->service.create(request("fee")));assertEquals(0,count("payment_order"));}
    @Test public void changedPendingPeriodCannotReuseOrder(){Map<String,Object> order=service.create(request("fee"));sql.update("UPDATE bill_owe_fee SET deadline_time='2026-12-01 00:00:00',fee_end_time=fee_end_time");rejected(()->service.create(request("fee")));assertEquals(1,count("payment_order"));assertEquals(order.get("orderId"),sql.queryForObject("SELECT order_id FROM payment_order",String.class));}
    @Test public void unchangedTotalCannotHidePendingFeeReallocation(){addFee("fee-two","owe-two");sql.update("UPDATE bill SET receivable=2.00,bill_time=bill_time");Map<String,Object> request=selected("owe","owe-two");service.create(request);sql.update("UPDATE bill_owe_fee SET bill_amount_owed=case when fee_id='fee' then 0.50 else 1.50 end,fee_end_time=fee_end_time");rejected(()->service.create(request));assertEquals(1,count("payment_order"));assertEquals(2,count("payment_order_item"));}
    static Map<String,Object> v3Request(String fee){Map<String,Object> row=request(fee);row.put("providerVersion","V3");return row;}
    @SuppressWarnings("unchecked") static <T> T newApi(IFeePaymentOrderInnerServiceSMO target,String name,Map<String,Object> row){
        try{return (T)IFeePaymentOrderInnerServiceSMO.class.getMethod(name,Map.class).invoke(target,row);}
        catch(java.lang.reflect.InvocationTargetException ex){if(ex.getCause() instanceof RuntimeException)throw (RuntimeException)ex.getCause();throw new AssertionError(ex.getCause());}
        catch(ReflectiveOperationException ex){throw new AssertionError("Missing order lifecycle API: "+name,ex);}
    }
    static Map<String,Object> closeRequest(Map<String,Object> order){return data("orderId",order.get("orderId"),"appId",APP,"mchId",MCH,"providerState","CLOSED");}
    @Test public void providerVersionIsImmutableAndDefaultsToV2(){
        Map<String,Object> legacy=service.create(request("fee"));assertEquals("V2",legacy.get("providerVersion"));
        assertEquals("V2",service.query(data("orderId",legacy.get("orderId"))).get("providerVersion"));
        rejected(()->service.create(v3Request("fee")));assertEquals(1,count("payment_order"));
        Map<String,Object> invalid=request("fee");invalid.put("providerVersion","V4");rejected(()->service.create(invalid));
    }
    @Test public void confirmedCloseReleasesReservationsPreservesHistoryAndAllowsRetry()throws Exception {
        Map<String,Object> order=service.create(v3Request("fee"));assertEquals("V3",order.get("providerVersion"));
        assertEquals(order.get("orderId"),service.create(v3Request("fee")).get("orderId"));
        Map<String,Object> closed=newApi(service,"close",closeRequest(order));assertEquals(0,closed.get("code"));assertEquals("CLOSED",closed.get("state"));
        assertEquals("CLOSED",((Map<String,Object>)newApi(instance(new PaymentOrderRepository()),"close",closeRequest(order))).get("state"));
        assertEquals(0,count("payment_order_reservation"));assertEquals(0,count("pay_fee_detail"));assertEquals(0,count("fee_receipt"));assertEquals(0,count("fee_receipt_detail"));
        assertEquals("1000",sql.queryForObject("SELECT state FROM bill_owe_fee",String.class));assertEquals(BigDecimal.ZERO.setScale(2),sql.queryForObject("SELECT receipts FROM bill",BigDecimal.class));
        rejected(()->service.confirm(payment(order,"late-transaction")));
        Map<String,Object> next=service.create(v3Request("fee"));assertNotEquals(order.get("orderId"),next.get("orderId"));assertEquals(2,count("payment_order_item"));assertEquals(2,count("payment_order"));
        newApi(service,"close",closeRequest(order));assertEquals(1,count("payment_order_reservation"));
        assertEquals("POSTED",service.confirm(payment(next,"transaction")).get("state"));assertEquals("POSTED",service.confirm(payment(next,"transaction")).get("state"));assertEquals(1,count("pay_fee_detail"));
        rejected(()->newApi(service,"close",closeRequest(next)));assertEquals("POSTED",service.query(data("orderId",next.get("orderId"))).get("state"));
    }
    @Test public void closeRequiresConfirmedProviderStateAndV3MerchantOwnership(){
        Map<String,Object> order=service.create(v3Request("fee"));
        for(String field:Arrays.asList("appId","mchId","providerState")) {Map<String,Object> request=closeRequest(order);request.put(field,"wrong");rejected(()->newApi(service,"close",request));request.remove(field);rejected(()->newApi(service,"close",request));}
        assertEquals(1,count("payment_order_reservation"));assertEquals("CREATED",service.query(data("orderId",order.get("orderId"))).get("state"));
        newApi(service,"close",closeRequest(order));Map<String,Object> legacy=service.create(request("fee"));rejected(()->newApi(service,"close",closeRequest(legacy)));assertEquals(1,count("payment_order_reservation"));
    }
    @Test public void closeFailureRollsBackStateAndReservationAndRetryWorks()throws Exception {
        Map<String,Object> order=service.create(v3Request("fee"));FaultRepository repository=new FaultRepository();repository.failAt="markClosed";
        IFeePaymentOrderInnerServiceSMO faulty=instance(repository);rejected(()->newApi(faulty,"close",closeRequest(order)));
        assertEquals(1,count("payment_order_reservation"));assertEquals("CREATED",service.query(data("orderId",order.get("orderId"))).get("state"));
        assertEquals("CLOSED",((Map<String,Object>)newApi(service,"close",closeRequest(order))).get("state"));assertEquals(0,count("payment_order_reservation"));
    }
    @Test public void pendingV3IsDurableScopedOldestFirstAndBounded()throws Exception {
        Map<String,Object> sample=service.create(v3Request("fee"));
        for(int i=0;i<55;i++){Map<String,Object> row=sql.queryForMap("SELECT * FROM payment_order WHERE order_id=?",sample.get("orderId"));row.put("order_id",String.format("synthetic-%03d",i));row.put("create_time",String.format("2026-10-01 00:00:%02d",i));insert("payment_order",row);}
        sql.update("UPDATE payment_order SET create_time='2026-11-01 00:00:00' WHERE order_id=?",sample.get("orderId"));
        sql.update("UPDATE payment_order SET state='CLOSED' WHERE order_id='synthetic-000'");sql.update("UPDATE payment_order SET state='POSTED' WHERE order_id='synthetic-001'");
        sql.update("UPDATE payment_order SET provider_version='V2' WHERE order_id='synthetic-002'");sql.update("UPDATE payment_order SET app_id='other' WHERE order_id='synthetic-003'");sql.update("UPDATE payment_order SET mch_id='other' WHERE order_id='synthetic-004'");
        List<Map<String,Object>> pending=newApi(instance(new PaymentOrderRepository()),"pendingV3",data("appId",APP,"mchId",MCH,"limit",999));
        assertEquals(50,pending.size());assertEquals("synthetic-005",pending.get(0).get("orderId"));assertEquals("V3",pending.get(0).get("providerVersion"));
        assertEquals(2,((List<?>)newApi(service,"pendingV3",data("appId",APP,"mchId",MCH,"limit",2))).size());
        assertEquals(50,((List<?>)newApi(service,"pendingV3",data("appId",APP,"mchId",MCH))).size());
        rejected(()->newApi(service,"pendingV3",data("appId",APP)));rejected(()->newApi(service,"pendingV3",data("mchId",MCH)));
        for(Object limit:Arrays.asList(0,-1,"1 OR 1=1","1.5"))rejected(()->newApi(service,"pendingV3",data("appId",APP,"mchId",MCH,"limit",limit)));
    }
    @Test public void pendingV3CursorVisitsAllUnresolvedOrdersAcrossPagesAndRestarts()throws Exception {
        Map<String,Object> sample=service.create(v3Request("fee"));
        Set<String> expected=new TreeSet<>();expected.add(sample.get("orderId").toString());
        for(int i=0;i<61;i++){
            Map<String,Object> row=sql.queryForMap("SELECT * FROM payment_order WHERE order_id=?",sample.get("orderId"));
            String id=String.format("recovery-%03d",i);row.put("order_id",id);row.put("create_time",String.format("2026-10-%02d 00:00:00",28-i%28));insert("payment_order",row);expected.add(id);
        }
        Map<String,Object> excluded=sql.queryForMap("SELECT * FROM payment_order WHERE order_id=?",sample.get("orderId"));
        excluded.put("order_id","excluded-other-app");excluded.put("app_id","other-app");insert("payment_order",excluded);
        excluded.put("order_id","excluded-v2");excluded.put("app_id",APP);excluded.put("provider_version","V2");insert("payment_order",excluded);
        String cursor="";List<String> visited=new ArrayList<>(),firstPage=new ArrayList<>();int pages=0;
        while(pages++<4){
            List<Map<String,Object>> rows=newApi(instance(new PaymentOrderRepository()),"pendingV3",data("appId",APP,"mchId",MCH,"limit",50,"afterOrderId",cursor));
            if(rows.isEmpty())break;
            assertTrue(rows.size()<=50);
            for(Map<String,Object> row:rows){String id=row.get("orderId").toString();assertTrue(id.compareTo(cursor)>0);visited.add(id);cursor=id;if(pages==1)firstPage.add(id);}
        }
        assertEquals(3,pages);assertEquals(new ArrayList<>(expected),visited);assertEquals(50,firstPage.size());
        // Every order remains CREATED, modeling provider failures; advancing the cursor still reaches order 51+.
        assertEquals(64,sql.queryForObject("SELECT count(*) FROM payment_order WHERE state='CREATED'",Integer.class).intValue());
        List<Map<String,Object>> restarted=newApi(service,"pendingV3",data("appId",APP,"mchId",MCH,"afterOrderId",""));
        List<String> restartedIds=new ArrayList<>();for(Map<String,Object> row:restarted)restartedIds.add(row.get("orderId").toString());assertEquals(firstPage,restartedIds);
    }
    @Test public void pendingV3RejectsMalformedCursor(){
        for(Object cursor:Arrays.asList(null,1,true,"bad space","../order","abcdefghijklmnopqrstuvwxyz1234567","' OR 1=1 --"))
            rejected(()->newApi(service,"pendingV3",data("appId",APP,"mchId",MCH,"afterOrderId",cursor)));
    }
    @Test public void internalCollectionCapRejectsLargerBillBeforeAnyOrderWritesAndAcceptsOneFen(){
        Map<String,Object> request=request("fee");request.put("maxTotalFee",1);
        rejected(()->service.create(request));assertEquals(0,count("payment_order"));assertEquals(0,count("payment_order_item"));assertEquals(0,count("payment_order_reservation"));
        sql.update("UPDATE bill_owe_fee SET bill_amount_owed=0.01,amount_owed=0.01,fee_end_time=fee_end_time");
        Map<String,Object> order=service.create(request);assertEquals(1L,((Number)order.get("totalFee")).longValue());
        assertEquals(order.get("orderId"),service.create(request).get("orderId"));assertEquals(1,count("payment_order"));assertEquals(1,count("payment_order_reservation"));
    }
    @Test public void pendingOrderReuseCannotBypassLoweredInternalCollectionCap(){
        Map<String,Object> order=service.create(request("fee"));Map<String,Object> capped=request("fee");capped.put("maxTotalFee",1);
        rejected(()->service.create(capped));assertEquals(1,count("payment_order"));assertEquals(1,count("payment_order_item"));assertEquals(1,count("payment_order_reservation"));
        assertEquals(order.get("orderId"),service.query(data("orderId",order.get("orderId"))).get("orderId"));
    }
    @Test public void internalCollectionCapMustBePositiveIntegerFen(){
        for(Object cap:Arrays.asList(null,0,-1,"0.01","1.0","unbounded","1 OR 1=1")){
            Map<String,Object> request=request("fee");request.put("maxTotalFee",cap);rejected(()->service.create(request));
        }
        assertEquals(0,count("payment_order"));assertEquals(0,count("payment_order_item"));assertEquals(0,count("payment_order_reservation"));
    }
}
