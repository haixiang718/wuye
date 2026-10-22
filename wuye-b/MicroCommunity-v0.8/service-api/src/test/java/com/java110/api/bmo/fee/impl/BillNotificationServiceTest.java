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
    private boolean timeout, updateFailure, secondOwnerFailure;
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
                JSONObject message=JSON.parseObject(payload);
                assertEquals("0.01",message.getJSONObject("data").getJSONObject("keyword2").getString("value"));
                assertTrue(message.getString("url").contains("roomId=room"));
                if(timeout) throw new IllegalStateException("synthetic network failure");
                if(secondOwnerFailure && sends==2) return ResponseEntity.ok("{\"errcode\":43004}");
                return ResponseEntity.ok(response);
            }
        };
        authorized=true;template=true;account=true;timeout=false;updateFailure=false;secondOwnerFailure=false;sends=0;updates=0;
        response="{\"errcode\":0,\"msgid\":123}";
        bill=new BillOweFeeDto();bill.setOweId("owe");bill.setBillId("bill");bill.setFeeId("fee");bill.setCommunityId("community");bill.setOwnerId("owner");bill.setState("1000");bill.setBillAmountOwed("0.01");bill.setAmountOwed("0.01");bill.setPayerObjType("3333");bill.setPayObjId("room");bill.setPayerObjName("TEST101");bill.setFeeEndTime("2026-10-01 00:00:00");bill.setDeadlineTime("2026-11-01 00:00:00");bill.setFeeTypeName("物业费");
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
