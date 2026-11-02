import com.alibaba.fastjson.*;
import com.java110.api.bmo.fee.impl.BillReviewService;
import com.java110.core.context.*;
import com.java110.dto.CommunityMemberDto;
import com.java110.dto.fee.BillDto;
import com.java110.front.smo.api.impl.OwnerDataAccessService;
import com.java110.front.controller.app.PaymentController;
import org.junit.*;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

public class BillPublicationTest {
    private BillDto bill;
    private BillReviewService review;
    private boolean authorized=true;
    private int updates;
    @Before public void setup() throws Exception {
        bill=new BillDto();bill.setBillId("bill");bill.setCommunityId("community");bill.setCurBill("T");bill.setReviewState("DRAFT");
        review=new BillReviewService();
        for(String name:Arrays.asList("communityInnerServiceSMOImpl","feeInnerServiceSMOImpl")) {
            Field f=BillReviewService.class.getDeclaredField(name);f.setAccessible(true);
            f.set(review,Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{f.getType()},(proxy,method,args)->{
                if(method.getName().equals("getCommunityMembers"))return authorized?Collections.singletonList(new CommunityMemberDto()):Collections.emptyList();
                if(method.getName().equals("queryBills"))return Collections.singletonList(bill);
                if(method.getName().equals("reviewBill")) {
                    BillDto value=(BillDto)args[0];assertEquals("staff",value.getReviewUserId());
                    assertEquals("community",value.getCommunityId());assertEquals("PUBLISHED",value.getReviewState());
                    updates++;bill.setReviewState("PUBLISHED");return 1;
                }
                throw new AssertionError("Unexpected method "+method.getName());
            }));
        }
    }
    @Test public void staffPublishesAndRepeatDoesNotRewriteAudit() {
        assertEquals(0,review.publish("community","bill","Checked","store","staff").getIntValue("code"));
        assertEquals(0,review.publish("community","bill","Changed","store","staff").getIntValue("code"));
        assertEquals(1,updates);
    }
    @Test public void unrelatedPropertyCannotPublish() {authorized=false;assertEquals(403,review.publish("community","bill","","store","staff").getIntValue("code"));assertEquals(0,updates);}
    @Test public void ownerWithoutStoreCannotPublish() {assertEquals(403,review.publish("community","bill","","","owner").getIntValue("code"));assertEquals(0,updates);}
    @Test public void mismatchedCommunityCannotPublish() {bill.setCommunityId("other");assertNotEquals(0,review.publish("community","bill","","store","staff").getIntValue("code"));assertEquals(0,updates);}
    @Test public void oversizedReviewCannotPublish() {assertNotEquals(0,review.publish("community","bill",String.join("",Collections.nCopies(501,"x")),"store","staff").getIntValue("code"));}

    static class Boundary extends RestTemplate {
        boolean published,paid,staff;
        JSONObject billQuery;
        @Override public <T> T postForObject(String url,Object entity,Class<T> type,Object... args) {
            JSONObject params=JSON.parseObject(String.valueOf(((HttpEntity<?>)entity).getBody()));
            String response;
            if(url.endsWith("getUsers"))response="[{\"userId\":\"owner-user\",\"levelCd\":\"02\"}]";
            else if(url.endsWith("getStoreUserInfo"))response=staff?"[{\"storeId\":\"store\"}]":"[]";
            else if(url.endsWith("queryOwnerAppUsers"))response="[{\"userId\":\"owner-user\",\"communityId\":\"community\",\"ownerId\":\"owner\",\"memberId\":\"member\",\"state\":\"12000\"}]";
            else if(url.endsWith("queryRoomsByOwner"))response="[{\"roomId\":\"room\"}]";
            else if(url.endsWith("queryBillOweFees")) {
                billQuery=params;
                response=!published||paid?"[]":"[{\"communityId\":\"community\",\"ownerId\":\"owner\",\"payObjId\":\"room\",\"feeId\":\"fee\",\"oweId\":\"owe\",\"billId\":\"bill\",\"reviewState\":\"PUBLISHED\",\"state\":\"3000\",\"billAmountOwed\":\"12.34\",\"feeName\":\"Monthly fee\",\"feeEndTime\":\"2026-11-01 00:00:00\",\"deadlineTime\":\"2026-12-01 00:00:00\",\"ownerTel\":\"PRIVATE\"}]";
            } else if(url.endsWith("queryBillOweFeeCount"))response=published&&!paid?"1":"0";
            else throw new AssertionError("Unexpected "+url);
            return type.cast(response);
        }
    }
    private JSONObject ownerResponse(Boundary transport,String service,JSONObject params) {
        IPageData pd=PageData.newInstance().builder("owner-user","Synthetic","","{}","","","","","owner-app");
        OwnerDataAccessService guard=new OwnerDataAccessService(transport);
        guard.authorize(pd,service,params);
        ResponseEntity<String> reply=guard.ownerBillResponse(pd,service,params);
        return reply==null?null:JSON.parseObject(reply.getBody());
    }
    private JSONObject params() {return JSON.parseObject("{\"communityId\":\"community\",\"payerObjId\":\"room\",\"page\":1,\"row\":30}");}
    @Test public void noBillAndDraftBillRemainHidden() {
        Boundary b=new Boundary();JSONObject result=ownerResponse(b,"fee.listFee",params());
        assertEquals(0,result.getJSONArray("fees").size());assertEquals(0,result.getIntValue("total"));
        assertEquals("PUBLISHED",b.billQuery.getString("reviewState"));assertEquals("T",b.billQuery.getString("outstandingOnly"));
    }
    @Test public void publishedAmountAndPeriodAreFrozenAndPrivateFieldsRemoved() {
        Boundary b=new Boundary();b.published=true;JSONObject result=ownerResponse(b,"fee.listFee",params());
        JSONObject fee=result.getJSONArray("fees").getJSONObject(0);
        assertEquals("12.34",fee.getString("feePrice"));assertEquals("owe",fee.getString("oweId"));
        assertEquals("2026-12-01 00:00:00",fee.getString("deadlineTime"));assertTrue(fee.getBooleanValue("billSnapshot"));
        assertFalse(fee.containsKey("ownerTel"));assertEquals("2006012",fee.getString("feeFlag"));
    }
    @Test public void ownerCannotInjectReviewOrOwnerFilters() {
        Boundary b=new Boundary();JSONObject p=params();p.put("ownerIds",Arrays.asList("other"));p.put("reviewState","DRAFT");
        ownerResponse(b,"fee.listFee",p);assertEquals("PUBLISHED",b.billQuery.getString("reviewState"));
        assertEquals("owner",b.billQuery.getJSONArray("ownerIds").getString(0));
    }
    @Test public void paidBillDisappearsFromPayableList() {Boundary b=new Boundary();b.published=true;b.paid=true;assertTrue(ownerResponse(b,"fee.listFee",params()).getJSONArray("fees").isEmpty());}
    @Test public void notifiedUnpaidRemainsVisibleInArrears() {
        Boundary b=new Boundary();b.published=true;JSONObject p=params();p.put("payObjId","room");p.put("payObjType","3333");
        assertEquals("12.34",ownerResponse(b,"feeApi/listOweFees",p).getJSONArray("data").getJSONObject(0).getString("feePrice"));
    }
    @Test public void staffRetainsFeeManagementView() {Boundary b=new Boundary();b.staff=true;assertNull(ownerResponse(b,"fee.listFee",params()));}
    @Test public void anotherRoomIsRejectedBeforeSnapshotQuery() {
        Boundary b=new Boundary();JSONObject p=params();p.put("payerObjId","other-room");
        try {ownerResponse(b,"fee.listFee",p);fail("foreign room accepted");}catch(SecurityException expected){}
        assertNull(b.billQuery);
    }
    @Test public void paymentEndpointsRemainDisabledAtServerEvenWithoutFrontend() throws Exception {
        PaymentController controller=new PaymentController();
        HttpServletRequest request=(HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletRequest.class},(p,m,a)->null);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,controller.toPay("{}",request).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,controller.toOweFeePay("{}",request).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,controller.toQrOweFeePay("{}",request).getStatusCode());
    }
}
