package com.java110.api.bmo.fee.impl;

import com.alibaba.fastjson.*;
import com.java110.dto.CommunityMemberDto;
import com.java110.dto.fee.*;
import com.java110.dto.owner.OwnerAppUserDto;
import com.java110.dto.smallWeChat.SmallWeChatDto;
import com.java110.dto.smallWechatAttr.SmallWechatAttrDto;
import java.lang.reflect.*;
import java.util.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.*;
import org.springframework.http.*;
import org.springframework.http.client.*;
import org.springframework.web.client.RestTemplate;
import static org.junit.Assert.*;

public class BillNotificationServiceTest {
    private Fake service;
    private BillOweFeeDto bill;
    private OwnerAppUserDto owner;
    private boolean authorized, template, account;
    private int sends, updates;
    private String response;
    private boolean timeout, updateFailure, secondOwnerFailure, templateLookupFailure;
    private String selectedContent, sentPayload;
    private static final String MODERN_CONTENT = "业主姓名:{{thing2.DATA}}\n账单金额:{{amount4.DATA}}\n缴费类型:{{thing9.DATA}}\n账单名称:{{thing10.DATA}}\n提醒时间:{{time12.DATA}}\n";
    private final List<OwnerAppUserDto> owners = new ArrayList<OwnerAppUserDto>();
    private final String request = "request-0000000001";

    static class Fake extends BillNotificationService {
        final Map<String,String> cache = new HashMap<String,String>();
        final Set<String> locks = new HashSet<String>();
        final Map<String,Integer> ttls = new HashMap<String,Integer>();
        @Override protected String cached(String key) { return cache.get(key); }
        @Override protected void remember(String key, String value, int seconds) { cache.put(key,value); ttls.put(key,seconds); }
        @Override protected boolean acquire(String key,String token) { return locks.add(key); }
        @Override protected void release(String key,String token) { locks.remove(key); }
        @Override protected String accessToken(SmallWeChatDto account) { return "synthetic-token"; }
        @Override protected String feePage() { return "https://example.test/#/pages/roomFeeListNew/roomFeeListNew?roomId="; }
    }
    @Before public void setup() throws Exception {
        service = new Fake() {
            @Override protected ResponseEntity<String> deliver(String token,String payload) {
                sends++;
                sentPayload=payload;
                JSONObject message=JSON.parseObject(payload);
                JSONObject amount=message.getJSONObject("data").getJSONObject("keyword2");
                if(amount==null)amount=message.getJSONObject("data").getJSONObject("amount4");
                assertEquals("0.01",amount.getString("value"));
                assertTrue(message.getString("url").contains("roomId=room"));
                if(timeout) throw new IllegalStateException("synthetic network failure");
                if(secondOwnerFailure && sends==2) return ResponseEntity.ok("{\"errcode\":43004}");
                return ResponseEntity.ok(response);
            }
            @Override protected String templateContent(String token,String templateId) {
                if(templateLookupFailure)throw new IllegalStateException("synthetic lookup failure");
                return selectedContent;
            }
        };
        selectedContent="{{first.DATA}}{{keyword1.DATA}}{{keyword2.DATA}}{{keyword3.DATA}}{{remark.DATA}}";
        sentPayload=null;
        templateLookupFailure=false;
        authorized=true;template=true;account=true;timeout=false;updateFailure=false;secondOwnerFailure=false;sends=0;updates=0;
        response="{\"errcode\":0,\"msgid\":123}";
        bill=new BillOweFeeDto();bill.setOweId("owe");bill.setBillId("bill");bill.setFeeId("fee");bill.setCommunityId("community");bill.setOwnerId("owner");bill.setState("1000");bill.setBillAmountOwed("0.01");bill.setAmountOwed("0.01");bill.setPayerObjType("3333");bill.setPayObjId("room");bill.setPayerObjName("TEST101");bill.setFeeEndTime("2026-10-01 00:00:00");bill.setDeadlineTime("2026-11-01 00:00:00");bill.setFeeTypeName("物业费");
        bill.setReviewState("PUBLISHED");
        bill.setOwnerName("测试业主乙");bill.setFeeName("十一月物业费");
        owner=new OwnerAppUserDto();owner.setOwnerId("owner");owner.setMemberId("different-member");owner.setOpenId("synthetic-openid");owner.setState("12000");owner.setAppType("WECHAT");owners.clear();owners.add(owner);
        inject("communityInnerServiceSMOImpl");inject("feeInnerServiceSMOImpl");inject("smallWeChatInnerServiceSMOImpl");inject("smallWechatAttrInnerServiceSMOImpl");inject("ownerAppUserInnerServiceSMOImpl");
    }
    private void inject(String name)throws Exception{
        Field field=BillNotificationService.class.getDeclaredField(name);field.setAccessible(true);
        field.set(service,Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{field.getType()},new InvocationHandler(){
            public Object invoke(Object p,Method m,Object[] args){
                String n=m.getName();
                if(n.equals("getCommunityMembers"))return authorized?Collections.singletonList(new CommunityMemberDto()):Collections.emptyList();
                if(n.equals("queryBillOweFees")){
                    BillOweFeeDto q=(BillOweFeeDto)args[0];assertEquals("community",q.getCommunityId());assertEquals("owe",q.getOweId());assertEquals("T",q.getCurBill());return Collections.singletonList(bill);
                }
                if(n.equals("querySmallWeChats")){SmallWeChatDto a=new SmallWeChatDto();a.setAppId("wx-test");a.setWeChatId("wechat");a.setAppSecret("synthetic");return account?Collections.singletonList(a):Collections.emptyList();}
                if(n.equals("querySmallWechatAttrs")){SmallWechatAttrDto a=new SmallWechatAttrDto();a.setValue("template");return template?Collections.singletonList(a):Collections.emptyList();}
                if(n.equals("queryOwnerAppUsers"))return owners;
                if(n.equals("updateBillOweFees")){assertEquals("owe",((BillOweFeeDto)args[0]).getOweId());updates++;if(updateFailure)throw new IllegalStateException("synthetic DB failure");bill.setState("3000");return 1;}
                throw new IllegalStateException("Unexpected "+n);
            }
        }));
    }
    private JSONObject run(){return service.send("community","owe",request,"store","staff");}
    @Test public void modernBillTemplateUsesActualOwnerAndFrozenAmount(){
        selectedContent=MODERN_CONTENT;
        assertEquals(0,run().getIntValue("code"));
        JSONObject data=JSON.parseObject(sentPayload).getJSONObject("data");
        assertEquals(5,data.size());
        assertEquals("测试业主乙",data.getJSONObject("thing2").getString("value"));
        assertEquals("物业费",data.getJSONObject("thing9").getString("value"));
        assertTrue(data.getJSONObject("thing10").getString("value").contains("十一月物业费"));
        assertTrue(data.getJSONObject("time12").getString("value").matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"));
        try {
            java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            assertTrue(Math.abs(System.currentTimeMillis()-format.parse(data.getJSONObject("time12").getString("value")).getTime())<65000);
        }catch(java.text.ParseException e){throw new AssertionError(e);}
        assertEquals("3000",bill.getState());
    }
    @Test public void modernTemplateLimitsThingsByUnicodeCharacters(){
        selectedContent=MODERN_CONTENT;bill.setOwnerName("业主姓名很长业主姓名很长业主姓名很长业主姓名很长");
        bill.setFeeName("abcdefghijklmnopqrstuvwxyz");
        assertEquals(0,run().getIntValue("code"));
        JSONObject data=JSON.parseObject(sentPayload).getJSONObject("data");
        for(String field:Arrays.asList("thing2","thing9","thing10")){
            String value=data.getJSONObject(field).getString("value");assertTrue(value.codePointCount(0,value.length())<=20);
        }
    }
    @Test public void missingOwnerNameDoesNotBecomeRoomName(){selectedContent=MODERN_CONTENT;bill.setOwnerName(null);assertEquals(400,run().getIntValue("code"));assertEquals(0,sends);assertEquals(0,updates);}
    @Test public void unknownTemplateRejectedBeforeTransport(){selectedContent="{{thing99.DATA}}";assertEquals(400,run().getIntValue("code"));assertEquals(0,sends);assertEquals(0,updates);assertFalse(service.cache.containsKey("BILL_NOTIFY_PROGRESS:community:owe"));}
    @Test public void sameKeysWithDifferentMeaningRejected(){selectedContent=MODERN_CONTENT.replace("业主姓名","手机号码");assertEquals(400,run().getIntValue("code"));assertEquals(0,sends);}
    @Test public void missingTemplateContentDoesNotLeaveUncertainDelivery(){selectedContent=null;assertEquals(400,run().getIntValue("code"));assertEquals(0,sends);assertFalse(service.cache.containsKey("BILL_NOTIFY_PROGRESS:community:owe"));}
    @Test public void templateLookupFailureDoesNotBecomeUncertainSend(){templateLookupFailure=true;assertEquals(400,run().getIntValue("code"));assertEquals(0,sends);assertEquals(0,updates);assertFalse(service.cache.containsKey("BILL_NOTIFY_PROGRESS:community:owe"));}
    private RestTemplate templateClient(final String body) {
        return new RestTemplate(new ClientHttpRequestFactory() {
            public ClientHttpRequest createRequest(final URI uri,final HttpMethod method) {
                assertEquals(HttpMethod.GET,method);
                return new ClientHttpRequest(){
                    public URI getURI(){return uri;}
                    public HttpMethod getMethod(){return method;}
                    public String getMethodValue(){return method.name();}
                    public HttpHeaders getHeaders(){return new HttpHeaders();}
                    public OutputStream getBody(){return new ByteArrayOutputStream();}
                    public ClientHttpResponse execute(){return new ClientHttpResponse(){
                        public HttpStatus getStatusCode(){return HttpStatus.OK;}
                        public int getRawStatusCode(){return 200;}
                        public String getStatusText(){return "OK";}
                        public void close(){}
                        public HttpHeaders getHeaders(){HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);return headers;}
                        public InputStream getBody(){return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));}
                    };}
                };
            }
        });
    }
    @Test public void officialTemplateLookupDecodesUtf8WithoutCharsetHeader(){
        JSONObject templateRow=new JSONObject();templateRow.put("template_id","selected-template");templateRow.put("content",MODERN_CONTENT);
        JSONObject reply=new JSONObject();reply.put("template_list",Arrays.asList(templateRow));
        final RestTemplate client=templateClient(reply.toJSONString());
        BillNotificationService sender=new BillNotificationService(){@Override protected RestTemplate notificationClient(){return client;}};
        assertEquals(MODERN_CONTENT,sender.templateContent("synthetic-token","selected-template"));
        assertNull(sender.templateContent("synthetic-token","different-template"));
    }
    @Test public void unreviewedBillCannotSend(){
        // Old DTOs lack the review field; that must never be treated as approval.
        try { bill.getClass().getMethod("setReviewState",String.class).invoke(bill,"DRAFT"); }
        catch (NoSuchMethodException oldDto) { }
        catch (Exception e) { throw new AssertionError(e); }
        assertNotEquals(0,run().getIntValue("code"));assertEquals(0,sends);assertEquals(0,updates);
    }
    @Test public void chineseNotificationReachesHttpTransportAsUtf8() throws Exception {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final HttpHeaders headers = new HttpHeaders();
        RestTemplate client = new RestTemplate(new ClientHttpRequestFactory() {
            public ClientHttpRequest createRequest(final URI uri, final HttpMethod method) {
                return new ClientHttpRequest() {
                    public URI getURI() { return uri; }
                    public HttpMethod getMethod() { return method; }
                    public String getMethodValue() { return method.name(); }
                    public HttpHeaders getHeaders() { return headers; }
                    public OutputStream getBody() { return body; }
                    public ClientHttpResponse execute() {
                        return new ClientHttpResponse() {
                            public HttpStatus getStatusCode() { return HttpStatus.OK; }
                            public int getRawStatusCode() { return 200; }
                            public String getStatusText() { return "OK"; }
                            public void close() { }
                            public HttpHeaders getHeaders() { HttpHeaders h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON_UTF8);return h; }
                            public InputStream getBody() { return new ByteArrayInputStream("{\"errcode\":0,\"msgid\":123}".getBytes(StandardCharsets.UTF_8)); }
                        };
                    }
                };
            }
        });
        final RestTemplate transport = client;
        BillNotificationService sender = new BillNotificationService() {
            @Override protected RestTemplate notificationClient() { return transport; }
        };
        String payload = "{\"data\":{\"first\":{\"value\":\"物业费提醒\"},\"keyword1\":{\"value\":\"业主乙 BIND202\"}}}";
        sender.deliver("synthetic-token", payload);
        assertEquals(payload, new String(body.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(MediaType.APPLICATION_JSON_UTF8, headers.getContentType());
    }
    @Test public void sendsExactOwnerAndMarksOnlyAfterAccepted(){assertEquals(0,run().getIntValue("code"));assertEquals(1,sends);assertEquals(1,updates);}
    @Test public void deniesUnrelatedProperty(){authorized=false;assertNotEquals(0,run().getIntValue("code"));assertEquals(0,sends);}
    @Test public void deniesAnonymous(){assertNotEquals(0,service.send("community","owe",request,"","").getIntValue("code"));assertEquals(0,sends);}
    @Test public void paidBillCannotSend(){bill.setState("2000");assertNotEquals(0,run().getIntValue("code"));assertEquals(0,sends);}
    @Test public void zeroDebtCannotSend(){bill.setBillAmountOwed("0");assertNotEquals(0,run().getIntValue("code"));assertEquals(0,sends);}
    @Test public void missingTemplateCannotSend(){template=false;assertNotEquals(0,run().getIntValue("code"));assertEquals(0,sends);}
    @Test public void missingAccountCannotSend(){account=false;assertNotEquals(0,run().getIntValue("code"));assertEquals(0,sends);}
    @Test public void wrongOwnerBindingCannotSend(){owner.setOwnerId("other-owner");assertNotEquals(0,run().getIntValue("code"));assertEquals(0,sends);}
    @Test public void unapprovedBindingCannotSend(){owner.setState("10000");assertNotEquals(0,run().getIntValue("code"));assertEquals(0,sends);}
    @Test public void missingOpenIdCannotSend(){owner.setOpenId("");assertNotEquals(0,run().getIntValue("code"));assertEquals(0,sends);}
    @Test public void rejectedMessageDoesNotMarkBill(){response="{\"errcode\":40037}";assertNotEquals(0,run().getIntValue("code"));assertEquals(0,updates);}
    @Test public void missingMessageIdDoesNotMarkBill(){response="{\"errcode\":0}";assertEquals(202,run().getIntValue("code"));assertEquals(0,updates);}
    @Test public void sameRequestDoesNotSendTwice(){run();run();assertEquals(1,sends);}
    @Test public void acceptedRecipientNotRetriedAfterDatabaseFailure(){updateFailure=true;assertEquals(202,run().getIntValue("code"));updateFailure=false;assertEquals(0,service.send("community","owe","request-0000000002","store","staff").getIntValue("code"));assertEquals(1,sends);}
    @Test public void timeoutDoesNotAutomaticallyResend(){timeout=true;assertEquals(202,run().getIntValue("code"));timeout=false;assertEquals(202,service.send("community","owe","request-0000000002","store","staff").getIntValue("code"));assertEquals(1,sends);assertEquals(0,updates);}
    @Test public void partialRetrySkipsAlreadyAcceptedRecipient(){OwnerAppUserDto second=new OwnerAppUserDto();second.setOwnerId("owner");second.setState("12000");second.setOpenId("second-openid");second.setAppType("WECHAT");owners.add(second);secondOwnerFailure=true;assertEquals(202,run().getIntValue("code"));secondOwnerFailure=false;assertEquals(0,service.send("community","owe","request-0000000002","store","staff").getIntValue("code"));assertEquals(3,sends);}
    @Test public void concurrentBillRequestCannotSend(){service.locks.add("BILL_NOTIFY_LOCK:community:owe");assertNotEquals(0,run().getIntValue("code"));assertEquals(0,sends);}
    @Test public void cooldownRejectsNewRequest(){run();assertNotEquals(0,service.send("community","owe","request-0000000002","store","staff").getIntValue("code"));assertEquals(1,sends);}
    @Test public void nullErrorCodeIsUncertain(){response="{\"errcode\":null,\"msgid\":123}";assertEquals(202,run().getIntValue("code"));assertEquals(0,updates);}
    @Test public void unfinishedProgressDoesNotExpire(){timeout=true;run();assertEquals(Integer.valueOf(0),service.ttls.get("BILL_NOTIFY_PROGRESS:community:owe"));}
    @Test public void duplicateBindingsSendOnce(){owners.add(owner);assertEquals(0,run().getIntValue("code"));assertEquals(1,sends);}
    @Test public void explicitResendAfterCooldown(){run();service.cache.remove("BILL_NOTIFY_COOLDOWN:community:owe");assertEquals(0,service.send("community","owe","request-0000000002","store","staff").getIntValue("code"));assertEquals(2,sends);}
}
