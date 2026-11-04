import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;
import com.java110.core.smo.impl.ComputeFeeSMOImpl;
import com.java110.dto.fee.BillDto;
import com.java110.dto.fee.BillOweFeeDto;
import com.java110.dto.fee.FeeDto;
import com.java110.intf.fee.IFeeInnerServiceSMO;
import com.java110.utils.util.BeanConvertUtil;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

/** Production bill mapper against an exact random database; never touches TT rows. */
public class BillReviewDatabaseTest {
    static JdbcTemplate sql;
    static SqlSessionTemplate mapper;
    static Map<String,Object> data(Object... pairs) {
        Map<String,Object> value=new HashMap<>();for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);return value;
    }
    @BeforeClass public static void connect() throws Exception {
        String db=System.getProperty("schema.audit","");
        if(!db.matches("audit_schema_[a-f0-9]{12}"))throw new IllegalArgumentException("Invalid test database");
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/"+db+"?serverTimezone=Asia/Shanghai&useSSL=false","root","");
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");sql=new JdbcTemplate(source);
        Configuration config=new Configuration(new Environment("bill-review",new SpringManagedTransactionFactory(),source));
        try(InputStream stream=Files.newInputStream(Paths.get(System.getProperty("schema.mapper")))) {
            new XMLMapperBuilder(stream,config,"FeeServiceDaoImplMapper.xml",config.getSqlFragments()).parse();
        }
        mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
    }
    @Before public void fixture() {
        for(String table:Arrays.asList("bill_owe_fee","bill","pay_fee","pay_fee_config"))sql.update("DELETE FROM "+table);
        sql.update("INSERT INTO pay_fee_config(config_id,b_id,community_id,fee_type_cd,square_price,additional_amount,start_time,end_time,fee_flag,fee_name,computing_formula,computing_formula_text,bill_type,payment_cycle,payment_cd,status_cd) VALUES ('config','audit','community','888','0','0.01','2026-11-01','2027-11-01','1003006','Audit fee','2002','Test','002','1','1200','0')");
        sql.update("INSERT INTO pay_fee(fee_id,b_id,fee_type_cd,community_id,payer_obj_id,income_obj_id,start_time,end_time,amount,user_id,fee_flag,state,config_id,payer_obj_type,status_cd) VALUES ('fee','audit','888','community','room','store','2026-11-01','2026-11-01','0.01','staff','1003006','2008001','config','3333','0')");
        sql.update("INSERT INTO bill(bill_id,bill_name,receivable,cur_receivable,receipts,bill_time,community_id,config_id,remark,cur_bill) VALUES ('bill','Audit bill',0.01,0.01,0,'2026-11-02','community','config','Test','T')");
        sql.update("INSERT INTO bill_owe_fee(owe_id,bill_id,fee_id,bill_amount_owed,amount_owed,fee_end_time,owner_id,owner_name,owner_tel,payer_obj_name,payer_obj_type,community_id,state,deadline_time) VALUES ('owe','bill','fee',0.01,0.01,'2026-11-01','owner','Audit','19900000000','Room','3333','community','1000','2026-12-01')");
    }
    static Map<String,Object> query() {
        return data("communityId","community","reviewState","PUBLISHED","curBill","T","outstandingOnly","T",
            "ownerIds",new String[]{"owner"},"payObjId","room","payerObjType","3333","page",-1);
    }
    static Map<String,Object> review() {return data("communityId","community","billId","bill","reviewUserId","staff","reviewRemark","Checked");}
    static List<Map<String,Object>> visible(Map<String,Object> q) {return mapper.selectList("feeServiceDaoImpl.queryBillOweFees",q);}
    static int total(Map<String,Object> q) {Map<String,Object> v=mapper.selectOne("feeServiceDaoImpl.queryBillOweFeeCount",q);return ((Number)v.get("count")).intValue();}
    @Test public void newBillsDefaultToDraftAndDoNotAppearInEitherQuery() {
        assertEquals("DRAFT",sql.queryForObject("SELECT review_state FROM bill",String.class));
        assertTrue(visible(query()).isEmpty());assertEquals(0,total(query()));
    }
    @Test public void approvalPreservesBillDateAndPersistsAuditWithoutSending() {
        Object before=sql.queryForObject("SELECT bill_time FROM bill",Object.class);
        assertEquals(1,mapper.update("feeServiceDaoImpl.reviewBill",review()));
        assertEquals(before,sql.queryForObject("SELECT bill_time FROM bill",Object.class));
        assertEquals("staff",sql.queryForObject("SELECT review_user_id FROM bill",String.class));
        assertEquals("Checked",sql.queryForObject("SELECT review_remark FROM bill",String.class));
        assertEquals("1000",sql.queryForObject("SELECT state FROM bill_owe_fee",String.class));
        List<Map<String,Object>> rows=visible(query());assertEquals(1,rows.size());assertEquals(1,total(query()));
        assertEquals("PUBLISHED",rows.get(0).get("reviewState"));assertEquals("Audit fee",rows.get(0).get("feeName"));
    }
    @Test public void duplicateApprovalCannotOverwriteReviewerOrTimestamp() {
        assertEquals(1,mapper.update("feeServiceDaoImpl.reviewBill",review()));
        Object time=sql.queryForObject("SELECT review_time FROM bill",Object.class);
        Map<String,Object> again=review();again.put("reviewUserId","other");
        assertEquals(0,mapper.update("feeServiceDaoImpl.reviewBill",again));
        assertEquals(time,sql.queryForObject("SELECT review_time FROM bill",Object.class));assertEquals("staff",sql.queryForObject("SELECT review_user_id FROM bill",String.class));
    }
    @Test public void communityOwnerObjectAndPaidFiltersMatchListAndCount() {
        mapper.update("feeServiceDaoImpl.reviewBill",review());
        for(String field:Arrays.asList("communityId","payObjId","payerObjType")) {
            Map<String,Object> q=query();q.put(field,"other");assertTrue(visible(q).isEmpty());assertEquals(0,total(q));
        }
        Map<String,Object> q=query();q.put("ownerIds",new String[]{"other-owner"});assertTrue(visible(q).isEmpty());assertEquals(0,total(q));
        sql.update("UPDATE bill_owe_fee SET state='3000',fee_end_time=fee_end_time");assertEquals(1,visible(query()).size());
        sql.update("UPDATE bill_owe_fee SET state='2000',fee_end_time=fee_end_time");assertTrue(visible(query()).isEmpty());assertEquals(0,total(query()));
    }
    @Test public void cannotReviewForeignExpiredOrEmptyBills() {
        Map<String,Object> foreign=review();foreign.put("communityId","other");assertEquals(0,mapper.update("feeServiceDaoImpl.reviewBill",foreign));
        sql.update("UPDATE bill SET cur_bill='F',bill_time=bill_time");assertEquals(0,mapper.update("feeServiceDaoImpl.reviewBill",review()));
        sql.update("UPDATE bill SET cur_bill='T',bill_time=bill_time");sql.update("DELETE FROM bill_owe_fee");assertEquals(0,mapper.update("feeServiceDaoImpl.reviewBill",review()));
    }
    private IFeeInnerServiceSMO feeService() {
        return (IFeeInnerServiceSMO)Proxy.newProxyInstance(
            IFeeInnerServiceSMO.class.getClassLoader(),new Class[]{IFeeInnerServiceSMO.class},(proxy,method,args)->{
                Map<String,Object> params=BeanConvertUtil.beanCovertMap(args[0]);
                if(method.getName().equals("queryBills")) {
                    return BeanConvertUtil.covertBeanList(mapper.selectList("feeServiceDaoImpl.queryBills",params),BillDto.class);
                }
                if(method.getName().equals("queryBillOweFees")) {
                    return BeanConvertUtil.covertBeanList(mapper.selectList("feeServiceDaoImpl.queryBillOweFees",params),BillOweFeeDto.class);
                }
                if(method.getName().equals("updateBillOweFees"))return mapper.update("feeServiceDaoImpl.updateBillOweFees",params);
                throw new UnsupportedOperationException(method.getName());
            });
    }
    private FeeDto computeCollectionFee() throws Exception {
        ComputeFeeSMOImpl compute=new ComputeFeeSMOImpl();
        Field field=ComputeFeeSMOImpl.class.getDeclaredField("feeInnerServiceSMOImpl");
        field.setAccessible(true);field.set(compute,feeService());
        FeeDto fee=new FeeDto();fee.setFeeId("fee");fee.setConfigId("config");
        fee.setCommunityId("community");fee.setBillType("002");
        compute.computeOweFee(fee);return fee;
    }
    @Test public void collectionIncludesNotifiedButUnpaidBills() throws Exception {
        mapper.update("feeServiceDaoImpl.reviewBill",review());
        sql.update("UPDATE bill_owe_fee SET state='3000',fee_end_time=fee_end_time");
        assertEquals(0.01,computeCollectionFee().getFeePrice(),0.000001);
    }
    @Test public void collectionIncludesUnnotifiedUnpaidBillsAndExcludesPaidBills() throws Exception {
        assertEquals(0.01,computeCollectionFee().getFeePrice(),0.000001);
        sql.update("UPDATE bill_owe_fee SET state='2000',fee_end_time=fee_end_time");
        assertEquals(0.0,computeCollectionFee().getFeePrice(),0.000001);
    }
    @Test public void collectionExcludesZeroBalanceBills() throws Exception {
        sql.update("UPDATE bill_owe_fee SET bill_amount_owed=0,amount_owed=0,fee_end_time=fee_end_time");
        assertEquals(0.0,computeCollectionFee().getFeePrice(),0.000001);
    }
    private void newerSupplement() {
        sql.update("INSERT INTO bill(bill_id,bill_name,receivable,cur_receivable,receipts,bill_time,community_id,config_id,remark,cur_bill,create_time) VALUES ('supplement','Supplement',0.02,0.02,0,NOW(),'community','config','Test','T',DATE_ADD(NOW(),INTERVAL 1 MINUTE))");
        sql.update("INSERT INTO bill_owe_fee(owe_id,bill_id,fee_id,bill_amount_owed,amount_owed,fee_end_time,owner_id,owner_name,owner_tel,payer_obj_name,payer_obj_type,community_id,state,deadline_time) VALUES ('owe-2','supplement','fee-2',0.02,0.02,'2026-11-01','owner','Audit','19900000000','Room','3333','community','1000','2026-12-01')");
    }
    @Test public void collectionStillFindsOriginalFeeAfterSupplement() throws Exception {
        newerSupplement();sql.update("UPDATE bill_owe_fee SET state='3000',fee_end_time=fee_end_time WHERE fee_id='fee'");
        assertEquals(0.01,computeCollectionFee().getFeePrice(),0.000001);
    }
    @Test public void paymentStatusTargetsOriginalFeeWithoutChangingSupplement() throws Exception {
        newerSupplement();
        com.java110.fee.bmo.impl.PayOweFeeImpl target=new com.java110.fee.bmo.impl.PayOweFeeImpl();
        Field field=target.getClass().getDeclaredField("feeInnerServiceSMOImpl");field.setAccessible(true);field.set(target,feeService());
        java.lang.reflect.Method method=target.getClass().getDeclaredMethod("modifyFeeBill",com.alibaba.fastjson.JSONObject.class);method.setAccessible(true);
        com.alibaba.fastjson.JSONObject input=new com.alibaba.fastjson.JSONObject();input.put("communityId","community");input.put("configId","config");input.put("feeId","fee");input.put("billType","002");
        method.invoke(target,input);
        assertEquals("2000",sql.queryForObject("SELECT state FROM bill_owe_fee WHERE fee_id='fee'",String.class));
        assertEquals("1000",sql.queryForObject("SELECT state FROM bill_owe_fee WHERE fee_id='fee-2'",String.class));
    }
}
