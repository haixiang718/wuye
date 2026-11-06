import com.alibaba.fastjson.JSONObject;
import com.java110.dto.fee.FeeConfigDto;
import com.java110.job.billing.*;
import com.java110.job.smo.impl.BillGenerateInnerServiceSMOImpl;
import org.junit.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

public class AutomaticBillWorkerTest {
    static class Queue extends AutomaticBillQueue {
        int done,failed;boolean waiting,claimed=true;String bill;
        public List<Map<String,Object>> due(){Map<String,Object> t=new HashMap<>();t.put("community_id","c");t.put("config_id","cfg");return Collections.singletonList(t);}
        public Map<String,Object> claim(Map<String,Object> row){return claimed?row:null;}
        public void finish(Map<String,Object> t,String id,boolean wait){done++;bill=id;waiting=wait;}
        public void fail(Map<String,Object> t){failed++;}
    }
    static class Generator extends BillGenerateInnerServiceSMOImpl {
        int calls;JSONObject reply;boolean fail;
        public JSONObject generateAutomatic(FeeConfigDto input) {
            calls++;assertEquals("c",input.getCommunityId());assertEquals("cfg",input.getConfigId());
            assertNull(input.getAdditionalAmount());assertNull(input.getBillType());
            if(fail)throw new IllegalStateException("Synthetic job failure");return reply;
        }
    }
    AutomaticBillWorker worker;Queue queue;Generator generator;
    @Before public void setup()throws Exception {
        worker=new AutomaticBillWorker();queue=new Queue();generator=new Generator();
        for(String f:new String[]{"queue","generator"}){Field field=worker.getClass().getDeclaredField(f);field.setAccessible(true);field.set(worker,f.equals("queue")?queue:generator);}
    }
    @Test public void successfulGenerationAcknowledgesWithoutReviewOrNotification() {
        generator.reply=JSONObject.parseObject("{\"code\":0,\"data\":{\"billId\":\"b\",\"reviewState\":\"DRAFT\"}}");
        worker.process();assertEquals(1,queue.done);assertEquals("b",queue.bill);assertFalse(queue.waiting);assertEquals(0,queue.failed);
    }
    @Test public void noDueAmountWaitsRatherThanBeingAProcessingFailure() {
        generator.reply=JSONObject.parseObject("{\"code\":1}");worker.process();assertEquals(1,queue.done);assertTrue(queue.waiting);assertNull(queue.bill);
    }
    @Test public void unknownOutcomeRetainsRetryableTask() {
        worker.process();assertEquals(1,queue.failed);assertEquals(0,queue.done);
    }
    @Test public void exceptionDoesNotAcknowledgeMissingDraft() {
        generator.fail=true;worker.process();assertEquals(1,queue.failed);assertEquals(0,queue.done);
    }
    @Test public void concurrentClaimLoserDoesNotGenerate() {
        queue.claimed=false;worker.process();assertEquals(0,generator.calls);assertEquals(0,queue.done);
    }
}
