import com.alibaba.fastjson.JSONObject;
import com.java110.api.bmo.fee.impl.BillReviewService;
import com.java110.api.listener.fee.ReviewBillListener;
import com.java110.core.context.DataFlowContext;
import com.java110.core.event.service.api.ServiceDataFlowEvent;
import com.java110.dto.CommunityMemberDto;
import com.java110.dto.fee.BillDto;
import com.java110.dto.store.StoreUserDto;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

public class BillReviewTransitionTest {
    BillReviewService service; BillDto bill, command;
    CommunityMemberDto member; StoreUserDto staff;
    int updates; boolean casLost;
    @Before public void setup() throws Exception {
        service=new BillReviewService(); updates=0;casLost=false;
        bill=new BillDto();bill.setBillId("bill");bill.setCommunityId("community");bill.setCurBill("T");bill.setReviewState("DRAFT");
        member=new CommunityMemberDto();member.setCommunityId("community");member.setMemberId("store");member.setMemberTypeCd(CommunityMemberDto.MEMBER_TYPE_PROPERTY);member.setAuditStatusCd(CommunityMemberDto.AUDIT_STATUS_NORMAL);
        staff=new StoreUserDto();staff.setStoreId("store");staff.setUserId("staff");staff.setStatusCd("0");staff.setStoreUserId("membership");
        for(Field f:BillReviewService.class.getDeclaredFields())if(f.getType().isInterface()) {
            f.setAccessible(true);f.set(service,Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{f.getType()},(p,m,a)->{
                if(m.getName().equals("getCommunityMembers"))return Collections.singletonList(member);
                if(m.getName().equals("getStoreUserInfo")){StoreUserDto q=(StoreUserDto)a[0];assertEquals("store",q.getStoreId());assertEquals("staff",q.getUserId());return Collections.singletonList(staff);}
                if(m.getName().equals("queryBills"))return Collections.singletonList(bill);
                if(m.getName().equals("reviewBill")){updates++;command=(BillDto)a[0];if(casLost){bill.setReviewState("PUBLISHED");return 0;}bill.setReviewState(command.getReviewState());return 1;}
                throw new AssertionError(m.getName());
            }));
        }
    }
    JSONObject action(String name,String reason) throws Exception {
        Method method;
        try {method=BillReviewService.class.getMethod(name,String.class,String.class,String.class,String.class,String.class);}
        catch(NoSuchMethodException missing){fail("Missing review action: "+name);return null;}
        return (JSONObject)method.invoke(service,"community","bill",reason,"store","staff");
    }
    @Test public void rejectThenExplicitResubmitKeepsSnapshotAndScopedActor() throws Exception {
        assertEquals(0,action("reject","Incorrect period").getIntValue("code"));assertEquals("REJECTED",bill.getReviewState());
        assertEquals("Incorrect period",command.getReviewRemark());assertEquals("staff",command.getReviewUserId());
        assertEquals(0,action("resubmit","").getIntValue("code"));assertEquals("DRAFT",bill.getReviewState());assertEquals(2,updates);
    }
    @Test public void rejectionNeedsNonBlankReasonOfAtMost500Characters() throws Exception {
        for(String reason:Arrays.asList(null,"","  ",String.join("",Collections.nCopies(501,"x"))))assertEquals(400,action("reject",reason).getIntValue("code"));
        assertEquals(0,updates);
    }
    @Test public void rejectedBillCannotBeApprovedWithoutExplicitResubmission() {
        bill.setReviewState("REJECTED");assertEquals(409,service.publish("community","bill","","store","staff").getIntValue("code"));assertEquals(0,updates);
    }
    @Test public void publishedBillCannotBeRejectedOrReopened() throws Exception {
        bill.setReviewState("PUBLISHED");assertEquals(409,action("reject","Wrong").getIntValue("code"));assertEquals(409,action("resubmit","").getIntValue("code"));assertEquals(0,updates);
    }
    @Test public void unrelatedOrInactiveStaffAndForeignPropertyRelationsAreDenied() {
        staff.setStoreId("other");assertEquals(403,service.publish("community","bill","","store","staff").getIntValue("code"));
        staff.setStoreId("store");staff.setState("48002");assertEquals(403,service.publish("community","bill","","store","staff").getIntValue("code"));
        staff.setState("48001");member.setCommunityId("other");assertEquals(403,service.publish("community","bill","","store","staff").getIntValue("code"));assertEquals(0,updates);
    }
    @Test public void actualStoreLookupShapeReturnsPropertyOwnerIdAndOmitsStatus() {
        staff.setUserId("property-owner");staff.setStatusCd(null);staff.setStoreUserId(null);
        assertEquals(0,service.publish("community","bill","","store","staff").getIntValue("code"));assertEquals(1,updates);
    }
    @Test public void lostRejectOrResubmitRaceNeverReportsAnotherActionAsSuccess() throws Exception {
        casLost=true;assertEquals(409,action("reject","Wrong").getIntValue("code"));
        bill.setReviewState("REJECTED");assertEquals(409,action("resubmit","").getIntValue("code"));assertEquals(2,updates);
    }
    @Test public void rejectAndResubmitRequireAdminAppAndCannotNotify() {
        class Exposed extends ReviewBillListener {
            void check(String action,String app,boolean notify){
                DataFlowContext context=(DataFlowContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DataFlowContext.class},(p,m,a)->m.getName().equals("getAppId")?app:null);
                JSONObject request=new JSONObject();request.put("communityId","community");request.put("billId","bill");request.put("action",action);request.put("notify",notify);
                validate(new ServiceDataFlowEvent(this,context,null),request);
            }
        }
        Exposed listener=new Exposed();
        for(String action:Arrays.asList("REJECT","RESUBMIT")) {
            listener.check(action,"8000418004",false);
            try{listener.check(action,"owner-app",false);fail("Owner app may not "+action);}catch(IllegalArgumentException expected){}
            try{listener.check(action,"8000418004",true);fail("Transition must not notify");}catch(IllegalArgumentException expected){}
        }
        try{listener.check("UNKNOWN","8000418004",false);fail("Unknown action accepted");}catch(IllegalArgumentException expected){}
    }
}
