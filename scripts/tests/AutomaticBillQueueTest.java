import com.alibaba.fastjson.JSONObject;
import com.java110.dto.fee.FeeConfigDto;
import com.java110.job.billing.AutomaticBillQueue;
import org.junit.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.Assert.*;

public class AutomaticBillQueueTest {
    static JdbcTemplate sql;
    static DriverManagerDataSource source;
    AutomaticBillQueue queue;
    @BeforeClass public static void setup() {
        String db=System.getProperty("queue.audit","");
        if(!db.matches("audit_queue_[a-f0-9]{12}"))throw new IllegalArgumentException("Unsafe schema");
        source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/"+db+"?serverTimezone=Asia/Shanghai&useSSL=false","root","");
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");sql=new JdbcTemplate(source);
    }
    @Before public void reset() throws Exception {
        sql.update("DELETE FROM pay_fee");sql.update("DELETE FROM automatic_bill_queue");
        queue=new AutomaticBillQueue();Field f=queue.getClass().getDeclaredField("jdbc");f.setAccessible(true);f.set(queue,sql);
    }
    void insert(String id,String config) {sql.update("INSERT INTO pay_fee(fee_id,community_id,config_id,state,status_cd,start_time,end_time) VALUES(?,'c',?,'2008001','0',NOW(),NOW())",id,config);}
    void due() {sql.update("UPDATE automatic_bill_queue SET next_run=DATE_SUB(NOW(),INTERVAL 1 SECOND)");}
    Map<String,Object> claim() {due();List<Map<String,Object>> rows=queue.due();assertFalse(rows.isEmpty());return queue.claim(rows.get(0));}
    @Test public void rollbackLeavesNoGhostApprovalEvent() {
        new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->{insert("f","cfg");status.setRollbackOnly();return null;});
        assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM automatic_bill_queue",Integer.class).intValue());
    }
    @Test public void everyInsertPathCoalescesByConfigWithoutLosingVersion() {
        insert("f","cfg");insert("f2","cfg");insert("f3","cfg2");
        assertEquals(2,sql.queryForObject("SELECT COUNT(*) FROM automatic_bill_queue",Integer.class).intValue());
        assertEquals(2,sql.queryForObject("SELECT event_version FROM automatic_bill_queue WHERE config_id='cfg'",Integer.class).intValue());
    }
    @Test public void onlyOneWorkerCanClaimAnEvent() {
        insert("f","cfg");Map<String,Object> task=claim();assertNotNull(task);assertNull(queue.claim(task));
    }
    @Test public void aLaterInsertIsNotAcknowledgedByEarlierGeneration() {
        insert("f","cfg");Map<String,Object> task=claim();insert("f2","cfg");queue.finish(task,"bill",false);
        assertEquals(1L,sql.queryForObject("SELECT processed_version FROM automatic_bill_queue",Long.class).longValue());
        assertNotNull(claim());
    }
    @Test public void expiredLeaseResumesAfterWorkerRestartAndOldWorkerCannotAck() {
        insert("f","cfg");Map<String,Object> old=claim();
        sql.update("UPDATE automatic_bill_queue SET lease_until=DATE_SUB(NOW(),INTERVAL 1 SECOND)");
        Map<String,Object> resumed=claim();assertNotNull(resumed);queue.finish(old,"old",false);
        assertEquals(resumed.get("lease_token"),sql.queryForObject("SELECT lease_token FROM automatic_bill_queue",String.class));
        queue.finish(resumed,"bill",false);assertEquals("READY",sql.queryForObject("SELECT task_state FROM automatic_bill_queue",String.class));
    }
    @Test public void failureRemainsDurableAndRetryableWithoutPublishing() {
        insert("f","cfg");Map<String,Object> task=claim();queue.fail(task);
        assertEquals("FAILED",sql.queryForObject("SELECT task_state FROM automatic_bill_queue",String.class));
        assertEquals(0L,sql.queryForObject("SELECT processed_version FROM automatic_bill_queue",Long.class).longValue());
        assertNull(sql.queryForObject("SELECT lease_token FROM automatic_bill_queue",String.class));assertNotNull(claim());
    }
    @Test public void zeroDueWaitsForNextDayRatherThanCreatingEmptyInvoice() {
        insert("f","cfg");queue.finish(claim(),null,true);
        assertEquals("WAITING",sql.queryForObject("SELECT task_state FROM automatic_bill_queue",String.class));assertTrue(queue.due().isEmpty());
    }
    @Test public void settledInsertAndUnrelatedUpdatesDoNotCreateWork() {
        sql.update("INSERT INTO pay_fee(fee_id,community_id,config_id,state,status_cd) VALUES('f','c','cfg','2009001','0')");
        assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM automatic_bill_queue",Integer.class).intValue());
    }
    @Test public void feeReactivationWakesAutomaticApproval() {
        insert("f","cfg");Map<String,Object> t=claim();queue.finish(t,"bill",false);
        sql.update("UPDATE pay_fee SET end_time=DATE_ADD(end_time,INTERVAL 1 DAY) WHERE fee_id='f'");
        assertEquals(2L,sql.queryForObject("SELECT event_version FROM automatic_bill_queue",Long.class).longValue());
    }
    @Test public void statusOnlySummarizesRequestedCommunity() {
        insert("f","cfg");assertEquals(1,((Number)queue.status("c").get("pending")).intValue());
        assertEquals(0,((Number)queue.status("other").get("pending")).intValue());
        queue.fail(claim());assertEquals(1,((Number)queue.status("c").get("failed")).intValue());
    }
}
