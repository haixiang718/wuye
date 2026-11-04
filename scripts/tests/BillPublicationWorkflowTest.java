import com.alibaba.fastjson.*;
import com.java110.api.bmo.fee.impl.*;
import com.java110.dto.CommunityMemberDto;
import com.java110.dto.fee.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

public class BillPublicationWorkflowTest {
    static class Memory extends BillNotificationTaskStore {
        Map<String,String> values=new HashMap<>(),locks=new HashMap<>();Set<String> active=new HashSet<>();boolean broken,loseFence;
        @Override public JSONObject get(String key){String raw=values.get(key);return raw==null?null:JSON.parseObject(raw);}
        @Override public boolean lock(String key,String nonce){if(locks.containsKey(key))return false;locks.put(key,nonce);return true;}
        @Override public void unlock(String key,String nonce){if(nonce.equals(locks.get(key)))locks.remove(key);}
        @Override public boolean save(String key,String nonce,JSONObject task,boolean running){
            if(broken)throw new IllegalStateException("PRIVATE URL token=never-return");
            if(loseFence||!nonce.equals(locks.get(key)))return false;
            values.put(key,task.toJSONString());if(running)active.add(key);else active.remove(key);return true;
        }
        @Override public Set<String> active(){return new HashSet<>(active);}
    }
    private Memory memory;private BillPublicationService service;private BillDto bill;private List<BillOweFeeDto> rows;
    private boolean authorized,foreignRelation,approved=true,throwSend,crash;private int reviews,sends;private List<String> requestIds;private Map<String,JSONObject> replies;
    private static void inject(Object target,String field,Object value)throws Exception{Field f=target.getClass().getDeclaredField(field);f.setAccessible(true);f.set(target,value);}
    @Before public void setup()throws Exception {
        memory=new Memory();rows=new ArrayList<>();requestIds=new ArrayList<>();replies=new HashMap<>();authorized=true;foreignRelation=false;approved=true;throwSend=false;crash=false;reviews=0;sends=0;
        bill=new BillDto();bill.setBillId("bill");bill.setCommunityId("community");bill.setReviewState("DRAFT");bill.setCurBill("T");
        service=new BillPublicationService();inject(service,"billNotificationTaskStore",memory);
        BillReviewService review=new BillReviewService(){@Override public JSONObject publish(String c,String b,String remark,String store,String user){reviews++;JSONObject result=new JSONObject();result.put("code",approved?0:409);result.put("msg","review");if(approved)bill.setReviewState("PUBLISHED");return result;}};
        inject(service,"billReviewService",review);
        inject(service,"billNotificationService",new BillNotificationService(){@Override public JSONObject sendInitial(String c,String owe,String request,String store,String user){
            sends++;requestIds.add(request);if(crash)throw new AssertionError("simulated process crash");if(throwSend)throw new IllegalStateException("PRIVATE");
            return replies.containsKey(owe)?replies.get(owe):reply(0,false,false);
        }});
        for(String field:Arrays.asList("communityInnerServiceSMOImpl","feeInnerServiceSMOImpl")){
            Field f=BillPublicationService.class.getDeclaredField(field);f.setAccessible(true);f.set(service,Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{f.getType()},(p,m,a)->{
                if(m.getName().equals("getCommunityMembers")){
                    CommunityMemberDto member=new CommunityMemberDto();member.setCommunityId(foreignRelation?"foreign":"community");member.setMemberId("store");member.setMemberTypeCd(CommunityMemberDto.MEMBER_TYPE_PROPERTY);member.setAuditStatusCd(CommunityMemberDto.AUDIT_STATUS_NORMAL);member.setStatusCd("0");return authorized?Collections.singletonList(member):Collections.emptyList();
                }
                if(m.getName().equals("queryBills")){BillDto q=(BillDto)a[0];assertEquals("community",q.getCommunityId());assertEquals("bill",q.getBillId());return Collections.singletonList(bill);}
                if(m.getName().equals("queryBillOweFees")){BillOweFeeDto q=(BillOweFeeDto)a[0];assertEquals("community",q.getCommunityId());assertEquals("bill",q.getBillId());assertEquals(-1,q.getPage());assertNull(q.getState());return rows;}
                throw new AssertionError(m.getName());
            }));
        }
        add("owe-1");
    }
    private void add(String id){BillOweFeeDto row=new BillOweFeeDto();row.setCommunityId("community");row.setBillId("bill");row.setOweId(id);row.setState("1000");row.setBillAmountOwed("50");rows.add(row);}
    private static JSONObject reply(int code,boolean retryable,boolean skipped){JSONObject r=new JSONObject();r.put("code",code);r.put("msg",code==0?"accepted":"safe failure");JSONObject d=new JSONObject();d.put("retryable",retryable);d.put("skipped",skipped);r.put("data",d);return r;}
    private JSONObject publish(boolean retry){return service.publishAndNotify("community","bill","Checked","store","staff",retry);}
    private JSONObject status(){return service.status("community","bill","store","staff").getJSONObject("data").getJSONObject("notification");}
    @Test public void publicationQueuesOnlyThenWorkerSends(){JSONObject r=publish(false);assertEquals(0,r.getIntValue("code"));assertEquals("PUBLISHED",r.getJSONObject("data").getString("reviewState"));assertEquals("QUEUED",status().getString("state"));assertEquals(0,sends);service.processPendingTasks();assertEquals(1,sends);assertEquals(1,status().getIntValue("accepted"));assertEquals("COMPLETED",status().getString("state"));}
    @Test public void repeatedReviewDoesNotRequeueOrResend(){publish(false);publish(false);service.processPendingTasks();publish(false);service.processPendingTasks();assertEquals(1,sends);assertEquals(1,memory.values.size());}
    @Test public void rejectedReviewCannotCreateTask(){approved=false;assertEquals(409,publish(false).getIntValue("code"));assertTrue(memory.values.isEmpty());assertEquals(0,sends);}
    @Test public void propertyScopeMustMatchReturnedRelationship(){foreignRelation=true;assertEquals(403,publish(false).getIntValue("code"));assertEquals(0,reviews);assertTrue(memory.values.isEmpty());assertEquals(403,service.status("community","bill","store","staff").getIntValue("code"));}
    @Test public void unrelatedAndAnonymousCannotPublishOrInspect(){authorized=false;assertEquals(403,publish(false).getIntValue("code"));assertEquals(403,service.status("community","bill","store","staff").getIntValue("code"));assertEquals(403,service.publishAndNotify("community","bill","","","",false).getIntValue("code"));assertEquals(0,reviews);}
    @Test public void badIdentifiersAreRejected(){assertNotEquals(0,service.status("community:other","bill","store","staff").getIntValue("code"));assertEquals(0,reviews);}
    @Test public void queueFailurePreservesPublishedAndSanitizesError(){memory.broken=true;JSONObject r=publish(false);assertNotEquals(0,r.getIntValue("code"));assertEquals("PUBLISHED",r.getJSONObject("data").getString("reviewState"));assertEquals("FAILED",r.getJSONObject("data").getJSONObject("notification").getString("state"));assertTrue(r.getJSONObject("data").getJSONObject("notification").getBooleanValue("canRetry"));assertFalse(r.toJSONString().contains("PRIVATE"));assertEquals(0,sends);}
    @Test public void batchProcessesOneDetailPerTaskTurn(){for(int i=2;i<=101;i++)add("owe-"+i);publish(false);service.processPendingTasks();assertEquals(1,sends);assertEquals(101,status().getIntValue("total"));for(int i=1;i<101;i++)service.processPendingTasks();assertEquals(101,sends);assertEquals(101,status().getIntValue("processed"));}
    @Test public void changedOrForeignDetailCannotEnterQueue(){rows.get(0).setCommunityId("other");JSONObject r=publish(false);assertNotEquals(0,r.getIntValue("code"));assertEquals(0,sends);assertTrue(memory.values.isEmpty());}
    @Test public void terminalFailureRequiresExplicitRetry(){replies.put("owe-1",reply(400,false,false));publish(false);service.processPendingTasks();assertEquals("FAILED",status().getString("state"));assertTrue(status().getBooleanValue("canRetry"));publish(false);service.processPendingTasks();assertEquals(1,sends);replies.clear();publish(true);service.processPendingTasks();assertEquals(2,sends);assertNotEquals(requestIds.get(0),requestIds.get(1));assertEquals(1,status().getIntValue("accepted"));}
    @Test public void retriesDoNotIncludeAcceptedOrUncertainDetails(){add("owe-2");add("owe-3");replies.put("owe-2",reply(202,false,false));replies.put("owe-3",reply(202,true,false));publish(false);for(int i=0;i<3;i++)service.processPendingTasks();assertEquals(1,status().getIntValue("accepted"));assertEquals(1,status().getIntValue("uncertain"));assertEquals(1,status().getIntValue("failed"));replies.clear();publish(true);for(int i=0;i<3;i++)service.processPendingTasks();assertEquals(4,sends);assertEquals(2,status().getIntValue("accepted"));assertEquals(1,status().getIntValue("uncertain"));}
    @Test public void skippedMessagesNeverRetry(){replies.put("owe-1",reply(0,false,true));publish(false);service.processPendingTasks();assertEquals(1,status().getIntValue("skipped"));publish(true);service.processPendingTasks();assertEquals(1,sends);}
    @Test public void crashRecoveryRetainsSamePerItemRequestId(){publish(false);crash=true;try{service.processPendingTasks();fail();}catch(AssertionError expected){}assertEquals(1,sends);crash=false;service.processPendingTasks();assertEquals(2,sends);assertEquals(requestIds.get(0),requestIds.get(1));assertEquals("COMPLETED",status().getString("state"));}
    @Test public void freshProcessResumesPersistedWorkingItemWithoutNewRequest()throws Exception{
        publish(false);crash=true;try{service.processPendingTasks();fail();}catch(AssertionError expected){}
        BillPublicationService restarted=new BillPublicationService();
        for(String name:Arrays.asList("billReviewService","billNotificationService","billNotificationTaskStore","communityInnerServiceSMOImpl","feeInnerServiceSMOImpl")){
            Field field=BillPublicationService.class.getDeclaredField(name);field.setAccessible(true);field.set(restarted,field.get(service));
        }
        crash=false;restarted.processPendingTasks();assertEquals(2,sends);assertEquals(requestIds.get(0),requestIds.get(1));assertEquals("COMPLETED",status().getString("state"));
    }
    @Test public void activeTaskLeaseBlocksConcurrentReviewAndWorkerMutation(){
        publish(false);String key=memory.values.keySet().iterator().next();String before=memory.values.get(key);memory.locks.put(key,"another-worker");
        assertEquals(409,publish(true).getIntValue("code"));service.processPendingTasks();assertEquals(0,sends);assertEquals(before,memory.values.get(key));
    }
    @Test public void adminAppIsRequiredForStatusAndCombinedReview(){
        class Status extends com.java110.api.listener.fee.BillNotificationStatusListener {
            void check(String app){validate(event(this,app),input());}
        }
        class Review extends com.java110.api.listener.fee.ReviewBillListener {
            void check(String app){JSONObject request=input();request.put("notify",true);validate(event(this,app),request);}
        }
        new Status().check("8000418004");new Review().check("8000418004");
        try{new Status().check("992020061452450002");fail();}catch(IllegalArgumentException expected){}
        try{new Review().check("992020061452450002");fail();}catch(IllegalArgumentException expected){}
    }
    private static JSONObject input(){JSONObject request=new JSONObject();request.put("communityId","community");request.put("billId","bill");return request;}
    private static com.java110.core.event.service.api.ServiceDataFlowEvent event(Object source,String app){
        com.java110.core.context.DataFlowContext context=(com.java110.core.context.DataFlowContext)Proxy.newProxyInstance(BillPublicationWorkflowTest.class.getClassLoader(),new Class[]{com.java110.core.context.DataFlowContext.class},(p,m,a)->m.getName().equals("getAppId")?app:null);
        return new com.java110.core.event.service.api.ServiceDataFlowEvent(source,context,null);
    }
    @Test public void exceptionIsUncertainAndNeverAutomaticRetry(){throwSend=true;publish(false);service.processPendingTasks();assertEquals(1,status().getIntValue("uncertain"));assertFalse(status().getBooleanValue("canRetry"));publish(true);service.processPendingTasks();assertEquals(1,sends);assertFalse(status().toJSONString().contains("PRIVATE"));}
    @Test public void taskFenceLossCannotStartNotification(){publish(false);memory.loseFence=true;service.processPendingTasks();assertEquals(0,sends);}
    @Test public void statusWithoutTaskDoesNotEnqueue(){bill.setReviewState("PUBLISHED");assertEquals("NONE",status().getString("state"));assertTrue(memory.values.isEmpty());assertEquals(0,sends);}
}
