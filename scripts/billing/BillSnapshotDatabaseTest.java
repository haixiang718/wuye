import com.java110.dto.fee.*;
import com.java110.fee.dao.impl.FeeServiceDaoImpl;
import com.java110.fee.smo.impl.BillSnapshotService;
import com.java110.utils.util.DateUtil;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.junit.*;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real mapper + Spring transaction + isolated MySQL; only uniquely named test tables are written. */
public class BillSnapshotDatabaseTest {
    static JdbcTemplate sql;
    static BillSnapshotService service;
    static String prefix;

    @BeforeClass public static void setup() throws Exception {
        // Fixed project-isolated instance. Never accept a remote JDBC URL.
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/TT?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false", "root", "");
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        sql=new JdbcTemplate(source);
        prefix="audit_repair_"+UUID.randomUUID().toString().replace("-", "").substring(0,12)+"_";
        for(String table:Arrays.asList("bill", "bill_owe_fee", "pay_fee_config"))
            sql.execute("CREATE TABLE "+prefix+table+" LIKE "+table);
        String mapper=new String(Files.readAllBytes(Paths.get(System.getProperty("billing.mapper"))),StandardCharsets.UTF_8);
        // Exercise the production statements, redirecting table names only.
        for(String table:Arrays.asList("bill_owe_fee", "pay_fee_config", "bill"))
            mapper=mapper.replaceAll("\\b"+table+"\\b",prefix+table);
        Configuration config=new Configuration(new Environment("billing-test",new SpringManagedTransactionFactory(),source));
        new XMLMapperBuilder(new ByteArrayInputStream(mapper.getBytes(StandardCharsets.UTF_8)),config,"fee.xml",config.getSqlFragments()).parse();
        FeeServiceDaoImpl dao=new FeeServiceDaoImpl();
        dao.setSessionTemplate(new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)));
        BillSnapshotService target=new BillSnapshotService();
        Field field=BillSnapshotService.class.getDeclaredField("feeServiceDaoImpl");field.setAccessible(true);field.set(target,dao);
        ProxyFactory factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        service=(BillSnapshotService)factory.getProxy();
    }

    @AfterClass public static void cleanup() {
        if(prefix==null||!prefix.matches("audit_repair_[a-f0-9]{12}_"))return;
        for(String table:Arrays.asList("bill_owe_fee", "bill", "pay_fee_config"))sql.execute("DROP TABLE IF EXISTS "+prefix+table);
    }

    @Before public void reset() {
        for(String table:Arrays.asList("bill_owe_fee", "bill", "pay_fee_config"))sql.update("DELETE FROM "+prefix+table);
        // Use the authoritative existing schema, without copying client/configuration data.
        sql.update("INSERT INTO "+prefix+"pay_fee_config(config_id,b_id,fee_type_cd,fee_name,fee_flag,start_time,end_time,community_id,bill_type,payment_cycle,payment_cd,computing_formula,square_price,additional_amount,is_default,computing_formula_text) VALUES ('cfg','-1','888800010001','Synthetic','1003006',NOW(),DATE_ADD(NOW(),INTERVAL 1 YEAR),'a','002','1','1200','2002','0','1','F','Synthetic')");
    }

    static BillSnapshotDto snapshot(String id) {
        BillDto bill=new BillDto();bill.setBillId(id);bill.setBillName("Synthetic");bill.setCommunityId("a");bill.setConfigId("cfg");
        bill.setBillTime(DateUtil.getFormatTimeString(new Date(),DateUtil.DATE_FORMATE_STRING_A));bill.setCurBill("T");
        bill.setReceivable("0.03");bill.setCurReceivable("0.03");bill.setReceipts("0");bill.setRemark("Synthetic repair regression");
        List<BillOweFeeDto> details=new ArrayList<>();
        for(int i=1;i<=2;i++) {
            BillOweFeeDto detail=new BillOweFeeDto();detail.setOweId(id+"-"+i);detail.setFeeId("fee-"+i);detail.setBillId(id);
            detail.setCommunityId("a");detail.setOwnerId("owner-"+i);detail.setOwnerName("Synthetic");detail.setOwnerTel("19900000999");
            detail.setPayerObjName("room-"+i);detail.setPayerObjType("3333");detail.setState("1000");
            detail.setBillAmountOwed(i==1?"0.01":"0.02");detail.setAmountOwed(detail.getBillAmountOwed());
            detail.setFeeEndTime(bill.getBillTime());detail.setDeadlineTime(bill.getBillTime());details.add(detail);
        }
        BillSnapshotDto snapshot=new BillSnapshotDto();snapshot.setBill(bill);snapshot.setDetails(details);snapshot.setBillType("002");return snapshot;
    }

    static int count(String table) {return sql.queryForObject("SELECT count(*) FROM "+prefix+table,Integer.class);}
    static void rejects(BillSnapshotDto snapshot) {
        try{service.save(snapshot);fail("Expected snapshot rejection");}catch(RuntimeException expected){}
    }

    @Test public void savesHeaderAndAllDetails() {
        assertEquals(1,service.save(snapshot("ok")));assertEquals(1,count("bill"));assertEquals(2,count("bill_owe_fee"));
    }
    @Test public void partialDetailFailureRollsBackAndRetryRecovers() {
        BillSnapshotDto bad=snapshot("retry");bad.getDetails().get(1).setOwnerName(null); // NOT NULL fails after header + first detail.
        rejects(bad);assertEquals(0,count("bill"));assertEquals(0,count("bill_owe_fee"));
        assertEquals(1,service.save(snapshot("retry")));assertEquals(2,count("bill_owe_fee"));
    }
    @Test public void headerFailurePreservesPreviousCurrentFlag() {
        service.save(snapshot("prior"));sql.update("UPDATE "+prefix+"bill SET bill_time=DATE_SUB(NOW(),INTERVAL 2 MONTH)");
        BillSnapshotDto bad=snapshot("fail-header");bad.setPreviousBillId("prior");bad.getBill().setRemark(null);
        rejects(bad);assertEquals(1,count("bill"));
        assertEquals("T",sql.queryForObject("SELECT cur_bill FROM "+prefix+"bill WHERE bill_id='prior'",String.class));
    }
    @Test public void successfulNextSnapshotPreservesPreviousBillTime() {
        service.save(snapshot("prior"));
        sql.update("UPDATE "+prefix+"bill SET bill_time=DATE_SUB(NOW(),INTERVAL 2 MONTH)");
        String before=sql.queryForObject("SELECT CAST(bill_time AS CHAR) FROM "+prefix+"bill WHERE bill_id='prior'",String.class);
        BillSnapshotDto next=snapshot("next");next.setPreviousBillId("prior");service.save(next);
        assertEquals(before,sql.queryForObject("SELECT CAST(bill_time AS CHAR) FROM "+prefix+"bill WHERE bill_id='prior'",String.class));
        assertEquals("F",sql.queryForObject("SELECT cur_bill FROM "+prefix+"bill WHERE bill_id='prior'",String.class));
    }
    @Test public void concurrentIndependentCallsCommitOnlyOneSnapshot() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> calls=new ArrayList<>();
            for(final String id:Arrays.asList("parallel-1","parallel-2"))calls.add(pool.submit(()->{start.await();try{service.save(snapshot(id));return true;}catch(IllegalStateException expected){return false;}}));
            start.countDown();int accepted=0;for(Future<Boolean> call:calls)if(call.get(15,TimeUnit.SECONDS))accepted++;
            assertEquals(1,accepted);assertEquals(1,count("bill"));assertEquals(2,count("bill_owe_fee"));
        } finally {pool.shutdownNow();}
    }
    @Test public void onceMonthlyTypeHasSameDuplicateProtection() {
        sql.update("UPDATE "+prefix+"pay_fee_config SET bill_type='005'");
        BillSnapshotDto one=snapshot("once-1");one.setBillType("005");service.save(one);
        BillSnapshotDto two=snapshot("once-2");two.setBillType("005");rejects(two);assertEquals(1,count("bill"));
    }
    @Test public void cannotChangeOtherCommunityCurrentBill() {
        service.save(snapshot("other"));sql.update("UPDATE "+prefix+"bill SET community_id='b'");
        service.save(snapshot("own"));assertEquals("T",sql.queryForObject("SELECT cur_bill FROM "+prefix+"bill WHERE bill_id='other'",String.class));
    }
    @Test public void staleCalculationIsRejected() {
        BillSnapshotDto snapshot=snapshot("stale");snapshot.setPreviousBillId("not-current");rejects(snapshot);assertEquals(0,count("bill"));
    }
    @Test public void missingOwnerIsRejectedWithoutWrites() {
        BillSnapshotDto snapshot=snapshot("missing");snapshot.getDetails().get(0).setOwnerId(null);rejects(snapshot);assertEquals(0,count("bill"));
    }
    @Test public void crossCommunityDetailIsRejected() {
        BillSnapshotDto snapshot=snapshot("cross");snapshot.getDetails().get(1).setCommunityId("b");rejects(snapshot);assertEquals(0,count("bill"));
    }
    @Test public void incorrectTotalIsRejected() {
        BillSnapshotDto snapshot=snapshot("sum");snapshot.getBill().setReceivable("0.04");rejects(snapshot);assertEquals(0,count("bill"));
    }
    @Test public void duplicateFeeIsRejected() {
        BillSnapshotDto snapshot=snapshot("duplicate");snapshot.getDetails().get(1).setFeeId("fee-1");rejects(snapshot);assertEquals(0,count("bill"));
    }
}
