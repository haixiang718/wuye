package com.java110.job.task.wechat;

import com.java110.dto.community.CommunityDto;
import com.java110.dto.fee.BillOweFeeDto;
import com.java110.dto.owner.OwnerAppUserDto;
import com.java110.dto.smallWeChat.SmallWeChatDto;
import com.java110.dto.smallWechatAttr.SmallWechatAttrDto;
import com.java110.dto.task.TaskDto;
import com.java110.entity.mapping.Mapping;
import com.java110.utils.cache.MappingCache;
import com.java110.utils.factory.ApplicationContextFactory;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestTemplate;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

public class PublicWeChatPushMessageTemplateTest {
    private PublicWeChatPushMessageTemplate task;
    private final Map<String,byte[]> cache = new HashMap<String,byte[]>();
    private final OwnerAppUserDto owner = new OwnerAppUserDto();
    private int sends, updates;
    private String reply;
    private boolean transportFailure;
    private boolean returnUnmatchedBill;
    private String reviewState;
    private String queriedReviewState;
    private HttpStatus responseStatus;

    @Before public void setup() throws Exception {
        sends = 0; updates = 0; reply = "{\"errcode\":0,\"msgid\":123}"; transportFailure = false;
        returnUnmatchedBill = false; responseStatus = HttpStatus.OK;
        reviewState = "PUBLISHED"; queriedReviewState = null;
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("jedisPool", new JedisPool() {
            @Override public Jedis getResource() { return new Jedis() {
                @Override public String get(String key) { return "synthetic-access-token"; }
                @Override public byte[] get(byte[] key) { return cache.get(new String(key)); }
                @Override public String set(byte[] key, byte[] value) { cache.put(new String(key), value); return "OK"; }
                @Override public void close() { }
            }; }
        });
        ApplicationContextFactory.setApplicationContext(context);
        mapping("OWE_FEE_PAGE", "https://example.test/#/pages/roomOweFee/roomOweFee?roomId=");
        mapping("OWE_CAR_FEE_PAGE", "https://example.test/");
        task = new PublicWeChatPushMessageTemplate();
        owner.setMemberId("member"); owner.setOwnerId("owner"); owner.setOpenId("synthetic-open-id");
        inject("feeInnerServiceSMOImpl"); inject("smallWeChatInnerServiceSMOImpl");
        inject("smallWechatAttrInnerServiceSMOImpl"); inject("ownerAppUserInnerServiceSMOImpl");
        Field field = task.getClass().getDeclaredField("outRestTemplate"); field.setAccessible(true);
        field.set(task, new RestTemplate() {
            @Override public <T> ResponseEntity<T> postForEntity(String url, Object request, Class<T> type, Object... args) {
                sends++;
                if (transportFailure) throw new IllegalStateException("synthetic network failure");
                return new ResponseEntity<T>(type.cast(reply), responseStatus);
            }
        });
    }
    private void mapping(String key, String value) {
        Mapping mapping = new Mapping(); mapping.setDomain("WECHAT"); mapping.setKey(key); mapping.setValue(value);
        MappingCache.setVaule(mapping);
    }
    private void inject(String name) throws Exception {
        Field field = task.getClass().getDeclaredField(name); field.setAccessible(true);
        field.set(task, Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{field.getType()}, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                String methodName = method.getName();
                if (methodName.equals("querySmallWeChats")) {
                    SmallWeChatDto config = new SmallWeChatDto(); config.setObjId("community"); config.setAppId("wx-synthetic");
                    return Collections.singletonList(config);
                }
                if (methodName.equals("querySmallWechatAttrs")) {
                    SmallWechatAttrDto attr = new SmallWechatAttrDto(); attr.setValue("synthetic-template");
                    return Collections.singletonList(attr);
                }
                if (methodName.equals("queryOwnerAppUsers")) return Collections.singletonList(owner);
                if (methodName.equals("queryBillOweFees")) {
                    BillOweFeeDto query = (BillOweFeeDto) args[0];
                    queriedReviewState = query.getReviewState();
                    if (!returnUnmatchedBill && !Arrays.asList(query.getOwnerIds()).contains("owner")) return Collections.emptyList();
                    BillOweFeeDto fee = new BillOweFeeDto(); fee.setOwnerId("owner"); fee.setFeeId("fee"); fee.setBillId("bill");
                    fee.setFeeEndTime("2026-10-01 00:00:00"); fee.setDeadlineTime("2026-11-01 00:00:00");
                    fee.setBillAmountOwed("0.01"); fee.setPayerObjType("3333");
                    fee.setReviewState(reviewState);
                    return Collections.singletonList(fee);
                }
                if (methodName.equals("updateBillOweFees")) { updates++; return 1; }
                throw new IllegalStateException("Unexpected dependency call: " + methodName);
            }
        }));
    }
    private void run() throws Exception {
        Method method = task.getClass().getDeclaredMethod("publishMsg", TaskDto.class, CommunityDto.class);
        method.setAccessible(true);
        CommunityDto community = new CommunityDto(); community.setCommunityId("community");
        try { method.invoke(task, new TaskDto(), community); }
        catch (InvocationTargetException failure) {
            if (!transportFailure && reply != null && reply.startsWith("{")) throw failure;
        }
    }
    @Test public void successfulWechatReplyMarksBillSent() throws Exception { run(); assertEquals(1,sends); assertEquals(1,updates); }
    @Test public void wechatErrorDoesNotMarkBillSent() throws Exception {
        reply="{\"errcode\":40037,\"errmsg\":\"invalid template_id\"}";
        run(); assertEquals(1,sends); assertEquals(0,updates);
    }
    @Test public void emptyReplyDoesNotMarkBillSent() throws Exception { reply=""; run(); assertEquals(0,updates); }
    @Test public void malformedReplyDoesNotMarkBillSent() throws Exception { reply="invalid json"; run(); assertEquals(0,updates); }
    @Test public void transportErrorDoesNotMarkBillSent() throws Exception { transportFailure=true; run(); assertEquals(0,updates); }
    @Test public void unmatchedOwnerDoesNotMarkBillSent() throws Exception { owner.setOwnerId("another-owner"); returnUnmatchedBill=true; run(); assertEquals(0,sends); assertEquals(0,updates); }
    @Test public void missingOpenIdDoesNotSendOrMarkBill() throws Exception { owner.setOpenId(""); run(); assertEquals(0,sends); assertEquals(0,updates); }
    @Test public void missingErrorCodeDoesNotMarkBillSent() throws Exception { reply="{\"msgid\":123}"; run(); assertEquals(1,sends); assertEquals(0,updates); }
    @Test public void missingMessageIdDoesNotMarkBillSent() throws Exception { reply="{\"errcode\":0}"; run(); assertEquals(1,sends); assertEquals(0,updates); }
    @Test public void nonSuccessHttpDoesNotMarkBillSent() throws Exception { responseStatus=HttpStatus.SERVICE_UNAVAILABLE; run(); assertEquals(1,sends); assertEquals(0,updates); }
    @Test public void jobQueryRequiresPublishedBill() throws Exception { run(); assertEquals("PUBLISHED",queriedReviewState); }
    @Test public void draftReturnedByDependencyIsNeverSent() throws Exception { reviewState="DRAFT"; run(); assertEquals(0,sends); assertEquals(0,updates); }
}
