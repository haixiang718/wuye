import com.alibaba.fastjson.*;
import com.java110.core.context.*;
import com.java110.entity.component.ComponentValidateResult;
import com.java110.front.smo.api.impl.ApiSMOImpl;
import com.java110.utils.constant.CommonConstant;
import org.junit.*;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

public class OwnerDataAccessTest {
    private boolean approved=true;
    private String level="02";
    private boolean storeMember=false;
    private int forwarded;
    private String forwardedBody;
    private String forwardedUrl;
    private HttpHeaders forwardedHeaders;
    private JSONObject billQuery;
    private JSONObject historyQuery;
    private String upstreamReply="{\"code\":0}";
    private final RestTemplate template=new RestTemplate(){
        @Override public <T> T postForObject(String url,Object body,Class<T> type,Object... vars){
            String reply;
            if(url.endsWith("/getUsers")) reply="[{\"userId\":\"me\",\"levelCd\":\""+level+"\"}]";
            else if(url.endsWith("/getStoreUserInfo"))reply=storeMember?"[{\"storeId\":\"verified-store\"}]":"[]";
            else if(url.endsWith("/queryOwnerAppUsers"))reply="[{\"appUserId\":\"binding-me\",\"userId\":\"me\",\"communityId\":\"community\",\"ownerId\":\"owner-me\",\"memberId\":\"family-member\",\"state\":\""+(approved?"12000":"10000")+"\"}]";
            else if(url.endsWith("/queryRoomsByOwner"))reply="[{\"roomId\":\"room-1\"},{\"roomId\":\"room-2\"}]";
            else if(url.endsWith("/queryBillOweFeeCount"))reply="1";
            else if(url.endsWith("/queryOwnerPaymentHistory")) {
                historyQuery=JSON.parseObject(String.valueOf(((HttpEntity<?>)body).getBody()));
                reply="{\"code\":0,\"total\":1,\"records\":1,\"feeDetails\":[{\"detailId\":\"paid-1\",\"receivedAmount\":\"0.01\",\"feeName\":\"Paid fee\"}]}";
            }
            else if(url.endsWith("/queryBillOweFees")) {
                billQuery=JSON.parseObject(String.valueOf(((HttpEntity<?>)body).getBody()));
                reply="[{\"oweId\":\"owe-1\",\"billId\":\"bill-1\",\"feeId\":\"fee-own\",\"ownerId\":\"owner-me\",\"communityId\":\"community\",\"payObjId\":\"room-2\",\"payerObjType\":\"3333\",\"feeName\":\"Published fee\",\"billAmountOwed\":\"0.01\",\"feeEndTime\":\"2026-10-01\",\"deadlineTime\":\"2026-11-01\",\"reviewState\":\"PUBLISHED\",\"state\":\"1000\"}]";
            }
            else if(url.endsWith("/queryOwnerCars")) {
                JSONObject q=JSON.parseObject(String.valueOf(((HttpEntity<?>)body).getBody()));
                reply="family-member".equals(q.getString("ownerId"))?"[{\"carId\":\"family-car\",\"psId\":\"family-parking\"}]":"[]";
            }
            else if(url.endsWith("/queryFees")) {
                JSONObject query=JSON.parseObject(String.valueOf(((HttpEntity<?>)body).getBody()));
                boolean car="car-fee".equals(query.getString("feeId"));
                reply="[{\"feeId\":\""+query.getString("feeId")+"\",\"communityId\":\"community\",\"payerObjType\":\""+(car?"6666":"3333")+"\",\"payerObjId\":\""+(car?"family-car":"fee-other".equals(query.getString("feeId"))?"room-other":"room-2")+"\"}]";
            } else throw new AssertionError("Unexpected lookup "+url);
            return type.cast(reply);
        }
        @Override public <T> ResponseEntity<T> exchange(String url,HttpMethod method,HttpEntity<?> entity,Class<T> type,Object... vars){
            forwarded++;forwardedUrl=url;forwardedBody=String.valueOf(entity.getBody());forwardedHeaders=entity.getHeaders();
            return new ResponseEntity<>(type.cast(upstreamReply),HttpStatus.OK);
        }
    };

    private ResponseEntity<String> call(String code,String json)throws Exception {
        return callAs(code,json,"me");
    }
    private ResponseEntity<String> callAs(String code,String json,String userId)throws Exception {
        ApiSMOImpl api=new ApiSMOImpl(){
            @Override protected ComponentValidateResult validateStoreStaffCommunityRelationship(IPageData pd,RestTemplate ignored){
                return new ComponentValidateResult("","","",pd.getUserId(),"Synthetic");
            }
        };
        api.setRestTemplate(template);
        // Let this regression run against the old implementation and fail on behavior.
        try {
            Class<?> guard=Class.forName("com.java110.front.smo.api.impl.OwnerDataAccessService");
            Object instance=guard.getConstructor(RestTemplate.class).newInstance(template);
            Field field=ApiSMOImpl.class.getDeclaredField("ownerDataAccessService");field.setAccessible(true);field.set(api,instance);
        } catch(ClassNotFoundException expectedOldCode) { }
        IPageData pd=PageData.newInstance().builder(userId,"Synthetic","",json,"","","","","spoofed-client-app");
        HttpServletRequest request=(HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletRequest.class},
            (proxy,method,args)->"getAttribute".equals(method.getName())?pd:null);
        Map<String,String> headers=new HashMap<>();headers.put(CommonConstant.HTTP_SERVICE,code);headers.put(CommonConstant.HTTP_METHOD,"GET");
        headers.put("user-id","other");headers.put("userid","other");headers.put("user_id","other");
        headers.put("USER-ID","other");headers.put("User_Name","forged-name");headers.put("Store-Id","forged-store");
        return api.doApi(json,headers,request);
    }
    private void forbidden(String code,String json)throws Exception {
        assertEquals(HttpStatus.FORBIDDEN,call(code,json).getStatusCode());assertEquals(0,forwarded);
    }
    @Test public void staffWithForgedClientAppCannotReadMerchantSecrets()throws Exception {
        storeMember=true;
        upstreamReply="{\"smallWeChats\":[{\"appId\":\"public-id\",\"mchId\":\"merchant-id\",\"appSecret\":\"synthetic-secret\",\"payPassword\":\"synthetic-key\"}]}";
        for(String route:Arrays.asList("smallWeChat.listSmallWeChats","/smallWeChat.listSmallWeChats")) {
            ResponseEntity<String> response=call(route,"{\"communityId\":\"community\"}");
            assertEquals(HttpStatus.OK,response.getStatusCode());
            assertFalse(response.getBody().contains("synthetic-secret"));assertFalse(response.getBody().contains("synthetic-key"));
            assertTrue(response.getBody().contains("public-id"));assertTrue(response.getBody().contains("merchant-id"));
        }
    }
    @Test public void malformedMerchantResponseFailsClosed()throws Exception {
        storeMember=true;upstreamReply="synthetic-secret from failed upstream";
        ResponseEntity<String> response=call("smallWeChat.listSmallWeChats","{}");
        assertEquals(HttpStatus.BAD_GATEWAY,response.getStatusCode());assertFalse(response.getBody().contains("synthetic-secret"));
    }
    @Test public void otherOwnerRoomsAreRejected()throws Exception{forbidden("room.queryRoomsByOwner","{\"communityId\":\"community\",\"ownerId\":\"other\"}");}
    @Test public void ownSecondRoomIsAllowed()throws Exception{assertEquals(HttpStatus.OK,call("fee.listFee","{\"communityId\":\"community\",\"payerObjId\":\"room-2\",\"payerObjType\":\"3333\"}").getStatusCode());}
    @Test public void existingH5RoomRequestWithoutTypeIsScoped()throws Exception{assertEquals(HttpStatus.OK,call("fee.listFee","{\"communityId\":\"community\",\"payerObjId\":\"room-2\"}").getStatusCode());assertEquals("3333",billQuery.getString("payerObjType"));}
    @Test public void familyMemberCanReadApprovedOwnerRooms()throws Exception{assertEquals(HttpStatus.OK,call("room.queryRoomsByOwner","{\"communityId\":\"community\",\"ownerId\":\"owner-me\"}").getStatusCode());}
    @Test public void otherRoomFeesAreRejected()throws Exception{forbidden("fee.listFee","{\"communityId\":\"community\",\"payerObjId\":\"room-other\",\"payerObjType\":\"3333\"}");}
    @Test public void unscopedFeeListIsRejected()throws Exception{forbidden("fee.listFee","{\"communityId\":\"community\"}");}
    @Test public void otherFeeHistoryIsRejected()throws Exception{forbidden("fee.queryFeeDetail","{\"communityId\":\"community\",\"feeId\":\"fee-other\"}");}
    @Test public void ownFeeHistoryIsAllowed()throws Exception{assertEquals(HttpStatus.OK,call("fee.queryFeeDetail","{\"communityId\":\"community\",\"feeId\":\"fee-own\"}").getStatusCode());}
    @Test public void allOwnedPaymentHistoryDoesNotRequireOneUnpaidFee()throws Exception{
        ResponseEntity<String> response=call("fee.queryFeeDetail","{\"communityId\":\"community\",\"page\":1,\"row\":20}");
        assertEquals(HttpStatus.OK,response.getStatusCode());
        assertEquals("paid-1",JSON.parseObject(response.getBody()).getJSONArray("feeDetails").getJSONObject(0).getString("detailId"));
        assertEquals(0,forwarded);assertEquals("me",historyQuery.getString("userId"));
    }
    @Test public void historyNeverForwardsClientChosenOwnerOrFilters()throws Exception{
        call("fee.queryFeeDetail","{\"communityId\":\"community\",\"ownerId\":\"other\",\"userId\":\"other\",\"statusCd\":\"1\",\"state\":\"1000\",\"page\":2,\"row\":20}");
        assertNotNull(historyQuery);assertEquals("me",historyQuery.getString("userId"));
        assertFalse(historyQuery.containsKey("ownerId"));assertFalse(historyQuery.containsKey("statusCd"));assertFalse(historyQuery.containsKey("state"));
        assertEquals(2,historyQuery.getIntValue("page"));assertEquals(20,historyQuery.getIntValue("row"));
    }
    @Test public void pendingOwnerCannotReadAllHistory()throws Exception{approved=false;forbidden("fee.queryFeeDetail","{\"communityId\":\"community\"}");assertNull(historyQuery);}
    @Test public void historyRejectsInvalidPagination()throws Exception{forbidden("fee.queryFeeDetail","{\"communityId\":\"community\",\"page\":0,\"row\":10000}");assertNull(historyQuery);}
    @Test public void managementHistoryKeepsExistingEndpoint()throws Exception{level="01";call("fee.queryFeeDetail","{\"communityId\":\"community\"}");assertEquals(1,forwarded);assertNull(historyQuery);}
    @Test public void pendingBindingCannotReadFees()throws Exception{approved=false;forbidden("fee.listFee","{\"communityId\":\"community\",\"payerObjId\":\"room-1\",\"payerObjType\":\"3333\"}");}
    @Test public void otherCommunityIsRejected()throws Exception{forbidden("room.queryRoomsByOwner","{\"communityId\":\"other-community\",\"ownerId\":\"owner-me\"}");}
    @Test public void ownerCannotCallManagementApi()throws Exception{forbidden("fee.pushBill","{\"communityId\":\"community\"}");}
    @Test public void staffKeepsManagementAccess()throws Exception{level="01";assertEquals(HttpStatus.OK,call("fee.pushBill","{\"communityId\":\"community\"}").getStatusCode());}
    @Test public void legacyOrdinaryLevelWithVerifiedStaffRelationKeepsManagementAccess()throws Exception{storeMember=true;assertEquals(HttpStatus.OK,call("fee.pushBill","{\"communityId\":\"community\"}").getStatusCode());}
    @Test public void resourceApiDoesNotProduceDoubleSlashInGatewayPath()throws Exception{level="01";call("/contract/queryContract","{\"communityId\":\"community\"}");assertTrue(forwardedUrl.startsWith("http://api-service/api/contract/queryContract?"));}
    @Test public void bindingQueryUsesAuthenticatedUser()throws Exception{
        call("owner.listAppUserBindingOwners","{\"userId\":\"other\",\"openId\":\"other\"}");
        assertEquals("me",JSON.parseObject(forwardedBody).getString("userId"));assertFalse(JSON.parseObject(forwardedBody).containsKey("openId"));
    }
    @Test public void forgedIdentityHeadersAreOverwritten()throws Exception{
        call("owner.listAppUserBindingOwners","{}");
        for(String name:Arrays.asList("user-id","user_id","userid"))assertEquals(Collections.singletonList("me"),forwardedHeaders.get(name));
    }
    @Test public void ownBindingListPreservesApprovalFilterButReplacesIdentity()throws Exception{
        call("owner.listAppUserBindingOwners","{\"userId\":\"other\",\"state\":\"12000\",\"communityId\":\"community\",\"appType\":\"WECHAT\"}");
        JSONObject query=JSON.parseObject(forwardedBody);assertEquals("me",query.getString("userId"));assertEquals("12000",query.getString("state"));
    }
    @Test public void cannotUnbindOthers()throws Exception{forbidden("owner.deleteAppUserBindingOwner","{\"appUserId\":\"binding-other\"}");}
    @Test public void anonymousRegistrationCanRequestSmsThroughExistingBusiness()throws Exception{assertEquals(HttpStatus.OK,callAs("user.userSendSms","{\"tel\":\"19900000999\"}",null).getStatusCode());assertEquals(1,forwarded);}
    @Test public void anonymousWechatPhotoRegistrationReachesExistingBusiness()throws Exception{assertEquals(HttpStatus.OK,callAs("owner.ownerRegisterWxPhoto","{}",null).getStatusCode());assertEquals(1,forwarded);}
    @Test public void anonymousOwnerRegistrationForwardsServerDefaultIdentity()throws Exception{
        assertEquals(HttpStatus.OK,callAs("owner.ownerRegister","{}",null).getStatusCode());
        assertEquals(1,forwarded);
        for(String name:Arrays.asList("user-id","user_id","userid"))assertEquals(Collections.singletonList(CommonConstant.ORDER_DEFAULT_USER_ID),forwardedHeaders.get(name));
        assertNull(forwardedHeaders.getFirst("user-name"));assertNull(forwardedHeaders.getFirst("user_name"));
        assertNotEquals("forged-store",forwardedHeaders.getFirst("store-id"));
    }
    @Test public void anonymousDefaultIdentityAlsoSupportsPublicSmsBusiness()throws Exception{
        callAs("user.userSendSms","{}",null);
        assertEquals(CommonConstant.ORDER_DEFAULT_USER_ID,forwardedHeaders.getFirst("user-id"));
    }
    @Test public void blankAndDefaultSessionsCannotPromoteForgedIdentity()throws Exception{
        for(String user:Arrays.asList("",CommonConstant.ORDER_DEFAULT_USER_ID)) {
            callAs("/owner.ownerRegister","{}",user);
            assertEquals(CommonConstant.ORDER_DEFAULT_USER_ID,forwardedHeaders.getFirst("user-id"));
        }
    }
    @Test public void anonymousProtectedBusinessRemainsForbidden()throws Exception{
        assertEquals(HttpStatus.FORBIDDEN,callAs("fee.listFee","{}",null).getStatusCode());assertEquals(0,forwarded);
    }
    @Test public void discountPayerIsTakenFromVerifiedFee()throws Exception{
        assertEquals(HttpStatus.OK,call("/feeDiscount/computeFeeDiscount","{\"communityId\":\"community\",\"feeId\":\"fee-own\",\"payerObjId\":\"room-other\",\"payerObjType\":\"6666\"}").getStatusCode());
        JSONObject actual=JSON.parseObject(forwardedBody);assertEquals("room-2",actual.getString("payerObjId"));assertEquals("3333",actual.getString("payerObjType"));
    }
    @Test public void familyVehicleListUsesMemberIdentity()throws Exception{assertEquals(HttpStatus.OK,call("owner.queryOwnerCars","{\"communityId\":\"community\",\"ownerId\":\"family-member\"}").getStatusCode());}
    @Test public void familyVehicleFeesAreAccessible()throws Exception{assertEquals(HttpStatus.OK,call("fee.listFee","{\"communityId\":\"community\",\"payerObjId\":\"family-car\",\"payerObjType\":\"6666\"}").getStatusCode());}
    @Test public void familyVehicleHistoryIsAccessible()throws Exception{assertEquals(HttpStatus.OK,call("fee.queryFeeDetail","{\"communityId\":\"community\",\"feeId\":\"car-fee\"}").getStatusCode());}
    @Test public void oldParkingFeeEndpointCannotExposeUnreviewedCharges()throws Exception{forbidden("fee.queryFeeByParkingSpace","{\"communityId\":\"community\",\"psId\":\"family-parking\"}");}
    @Test public void staffRetainsParkingIntegration()throws Exception{level="01";assertEquals(HttpStatus.OK,call("fee.queryFeeByParkingSpace","{\"communityId\":\"community\",\"psId\":\"family-parking\"}").getStatusCode());}
    @Test public void unrelatedVehicleFeesAreRejected()throws Exception{forbidden("fee.listFee","{\"communityId\":\"community\",\"payerObjId\":\"foreign-car\",\"payerObjType\":\"6666\"}");}
    @Test public void ownerCarQueryStripsUnneededSearchExpressions()throws Exception{
        call("owner.queryOwnerCars","{\"communityId\":\"community\",\"ownerId\":\"family-member\",\"ownerName\":\"malicious-search-expression\",\"page\":1,\"row\":10}");
        assertEquals(1,forwarded);assertFalse(JSON.parseObject(forwardedBody).containsKey("ownerName"));
    }
}
