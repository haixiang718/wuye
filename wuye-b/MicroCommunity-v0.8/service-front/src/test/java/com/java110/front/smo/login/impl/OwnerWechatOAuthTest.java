package com.java110.front.smo.login.impl;

import com.alibaba.fastjson.JSONObject;
import com.java110.core.context.IPageData;
import com.java110.core.context.PageData;
import com.java110.front.properties.WechatAuthProperties;
import com.java110.utils.factory.ApplicationContextFactory;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;
import com.java110.utils.constant.ServiceCodeConstant;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import static org.junit.Assert.*;

public class OwnerWechatOAuthTest {
    private final Map<String,String> cache = new HashMap<>();
    private final OwnerAppLoginSMOImpl service = new OwnerAppLoginSMOImpl() {
        @Override protected ResponseEntity<String> callCenterService(RestTemplate template, IPageData pd,
                String input, String url, HttpMethod method) {
            if(url.contains("smallWeChat.listSmallWeChats"))return ResponseEntity.ok("{\"smallWeChats\":[{\"appId\":\"wx-test\",\"appSecret\":\"test-secret\",\"weChatType\":\"1100\",\"objType\":\"1000\",\"objId\":\"test-community\"}]}");
            if(url.contains("owner.bindWechatIdentity")){
                bindCalls++;JSONObject command=JSONObject.parseObject(input);
                assertFalse(command.containsKey("ownerId"));assertFalse(command.containsKey("openId"));assertFalse(command.containsKey("state"));
                return ResponseEntity.ok("{\"code\":0,\"data\":{\"userId\":\"test-user\",\"communityId\":\"test-community\",\"memberId\":\"member-two\",\"ownerId\":\"owner-two\"}}");
            }
            if (url.contains(ServiceCodeConstant.SERVICE_CODE_USER_LOGIN)) {
                loginCalls++;
                assertEquals("test-user-key", JSONObject.parseObject(input).getString("key"));
                return ResponseEntity.ok("{\"code\":0,\"data\":{\"userId\":\"test-user\",\"token\":\"test-login-token\",\"key\":\"test-user-key\"}}");
            }
            if (url.contains(ServiceCodeConstant.QUERY_USER_SECRET)) {
                return ResponseEntity.ok("{\"code\":0,\"data\":{\"key\":\"test-user-key\"}}");
            }
            if (url.contains("openId=test-open-id")) assertTrue(url, url.contains("appType=WECHAT"));
            if(bindingRows!=null)return ResponseEntity.ok("{\"code\":0,\"data\":"+bindingRows+"}");
            return ResponseEntity.ok("{\"code\":0,\"data\":[{\"userId\":\"test-user\",\"openId\":\""+bindingOpenId+"\",\"communityId\":\""+bindingCommunity+"\",\"idCard\":\"private-card\",\"appType\":\"WECHAT\",\"state\":\"" + bindingState + "\"}]}");
        }
    };
    private final WechatAuthProperties properties = new WechatAuthProperties();
    private int externalCalls;
    private String tokenReply;
    private int loginCalls;
    private int bindCalls;
    private String bindingCommunity="test-community",bindingOpenId="test-open-id";
    private String bindingState = "12000";
    private String bindingRows;
    @Before public void setup() throws Exception {
        properties.setWechatAppId("wx-test"); properties.setWechatAppSecret("test-secret");
        inject("wechatAuthProperties", properties);
        inject("outRestTemplate", new RestTemplate() {
            @Override public <T> ResponseEntity<T> getForEntity(String url, Class<T> type, Object... vars) {
                externalCalls++;
                if (tokenReply == null) throw new ResourceAccessException("request contained test-secret");
                String body = externalCalls % 2 == 1 ? tokenReply : "{\"nickname\":\"Test\",\"headimgurl\":\"\"}";
                return new ResponseEntity<>(type.cast(body), HttpStatus.OK);
            }
        });
        tokenReply = "{\"openid\":\"test-open-id\",\"access_token\":\"test-access-token\"}";
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("jedisPool", new JedisPool() {
            @Override public Jedis getResource() { return new Jedis() {
                @Override public String get(String key) { return cache.get(key); }
                @Override public byte[] get(byte[] key) { return null; }
                @Override public String set(String key, String value) { cache.put(key,value); return "OK"; }
                @Override public String getSet(String key, String value) { return cache.put(key,value); }
                @Override public Long del(String... keys) { for(String key: keys) cache.remove(key); return 1L; }
                @Override public Long del(String key) { cache.remove(key); return 1L; }
                @Override public Long expire(String key, int ttl) { return 1L; }
                @Override public void close() { }
            }; }
        });
        ApplicationContextFactory.setApplicationContext(context);
    }
    private void inject(String name, Object value) throws Exception {
        Field field = OwnerAppLoginSMOImpl.class.getDeclaredField(name); field.setAccessible(true); field.set(service,value);
    }
    private HttpServletRequest request() {
        return (HttpServletRequest) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class[]{HttpServletRequest.class}, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) { return null; }
            });
    }
    private IPageData pd(JSONObject data) {
        return PageData.newInstance().builder("", "", "", data.toJSONString(), "", "", "", "", "992020061452450002");
    }
    private Map<String,String> query(String input) throws Exception {
        Map<String,String> values = new HashMap<>();
        for(String part : input.substring(input.indexOf('?')+1).split("&")) {
            String[] pair = part.split("=",2); values.put(pair[0], URLDecoder.decode(pair.length>1?pair[1]:"", "UTF-8"));
        }
        return values;
    }
    private JSONObject begin() throws Exception {
        ResponseEntity<String> reply = service.refreshToken(pd(new JSONObject()),
            "https://example.test/#/pages/my/myHouse?wAppId=wx-test", "https://example.test/#/pages/showlogin/showlogin?wAppId=wx-test", "1", request(), null);
        String open = JSONObject.parseObject(reply.getBody()).getJSONObject("data").getString("openUrl");
        Map<String,String> auth = query(open.substring(0,open.indexOf('#')));
        JSONObject callback = new JSONObject(); callback.putAll(query(auth.get("redirect_uri")));
        callback.put("state", auth.get("state")); callback.put("code", "test-code");
        return callback;
    }
    @Test public void oauthUsesRandomStateAndContainsNoSecret() throws Exception {
        JSONObject first=begin(), second=begin();
        assertNotEquals("1",first.getString("state"));
        assertNotEquals(first.getString("state"),second.getString("state"));
        assertEquals("992020061452450002",first.getString("appId"));
        assertFalse(first.toJSONString().contains("test-secret"));
    }
    @Test public void callbackRejectsWrongStateBeforeWeChatRequest() throws Exception {
        JSONObject callback=begin(); callback.put("state","wrong");
        service.getPageAccessToken(pd(callback),request()); assertEquals(0,externalCalls);
    }
    @Test public void loginTicketIsInsertedInsideH5FragmentAndCallbackCannotReplay() throws Exception {
        JSONObject callback=begin(); ResponseEntity<String> reply=service.getPageAccessToken(pd(callback),request());
        String location=reply.getHeaders().getFirst("Location");
        assertTrue(location,location.contains("#/pages/my/myHouse?wAppId=wx-test&authTicket="));
        assertFalse(location.contains("test-open-id")); assertFalse(location.contains("access_token"));
        service.getPageAccessToken(pd(callback),request()); assertEquals(2,externalCalls);
    }
    @Test public void callbackBindsLoginFlagFromServerCache() throws Exception {
        JSONObject callback=begin(); callback.put("loginFlag","0"); callback.put("wId","tampered");
        ResponseEntity<String> reply=service.getPageAccessToken(pd(callback),request());
        assertTrue(reply.getHeaders().getFirst("Location").contains("&authTicket="));
    }
    @Test public void weChatErrorDoesNotRequestUserInfo() throws Exception {
        JSONObject callback=begin(); tokenReply="{\"errcode\":40029,\"errmsg\":\"invalid code\"}";
        ResponseEntity<String> reply=service.getPageAccessToken(pd(callback),request());
        assertEquals(1,externalCalls); assertTrue(reply.getHeaders().getFirst("Location").contains("showlogin"));
    }
    @Test public void rejectsCrossOriginErrorRedirectAndMissingCredentials() throws Exception {
        try { service.refreshToken(pd(new JSONObject()), "https://example.test/#/home", "https://evil.test/", "1",request(),null); fail("cross-origin redirect accepted"); }
        catch(IllegalArgumentException expected) { }
        properties.setWechatAppSecret("");
        try { begin(); fail("missing credentials accepted"); } catch(IllegalArgumentException expected) { }
    }
    @Test public void unknownOrExpiredAuthTicketFailsWithoutLoginCall() {
        JSONObject data=new JSONObject(); data.put("authTicket","expired");
        assertEquals(HttpStatus.UNAUTHORIZED,service.doLoginByKey(pd(data)).getStatusCode());
    }
    @Test public void boundOwnerReceivesOneUseTicketInsteadOfUserKey() throws Exception {
        JSONObject callback = begin();
        JSONObject cached = JSONObject.parseObject(cache.get(callback.getString("urlCode")));
        cached.put("loginFlag", "0"); cache.put(callback.getString("urlCode"),cached.toJSONString());
        ResponseEntity<String> result = service.getPageAccessToken(pd(callback),request());
        String location = result.getHeaders().getFirst("Location");
        assertFalse(location.contains("test-user-key")); assertFalse(location.contains("key="));
        String ticket = query(location.substring(location.indexOf('#')+1)).get("authTicket");
        assertNotNull(ticket);
        JSONObject data = new JSONObject(); data.put("authTicket",ticket);
        ResponseEntity<String> login = service.doLoginByKey(pd(data));
        assertEquals(HttpStatus.OK,login.getStatusCode());
        assertFalse(login.getBody().contains("test-user-key"));
        assertFalse(login.getBody().contains("private-card"));assertFalse(login.getBody().contains("test-open-id"));
        assertEquals(HttpStatus.UNAUTHORIZED,service.doLoginByKey(pd(data)).getStatusCode());
        assertEquals(1,loginCalls);
    }
    @Test public void pendingBindingOnlyReceivesRestrictedWechatIdentity() throws Exception {
        bindingState = "10000";
        JSONObject callback = begin();
        JSONObject cached = JSONObject.parseObject(cache.get(callback.getString("urlCode")));
        cached.put("loginFlag", "0"); cache.put(callback.getString("urlCode"),cached.toJSONString());
        String location = service.getPageAccessToken(pd(callback),request()).getHeaders().getFirst("Location");
        String ticket=query(location.substring(location.indexOf('#')+1)).get("authTicket");assertNotNull(ticket);
        JSONObject input=new JSONObject();input.put("authTicket",ticket);
        ResponseEntity<String> result=service.doLoginByKey(pd(input));
        assertEquals(HttpStatus.OK,result.getStatusCode());
        JSONObject response=JSONObject.parseObject(result.getBody());
        assertFalse(response.getBooleanValue("hasBinding"));assertNull(response.get("owner"));assertNull(response.get("token"));
        assertNotNull(response.getString("wechatSession"));assertEquals(0,loginCalls);
        assertEquals(HttpStatus.UNAUTHORIZED,service.doLoginByKey(pd(input)).getStatusCode());
    }
    @Test public void pendingBindingCannotExchangeLoginTicket() throws Exception {
        bindingState = "10000";
        cache.put("owner-wechat-login:pending", "{\"appId\":\"992020061452450002\",\"key\":\"test-user-key\"}");
        JSONObject input = new JSONObject();input.put("authTicket", "pending");
        assertEquals(HttpStatus.FORBIDDEN,service.doLoginByKey(pd(input)).getStatusCode());
    }
    @Test public void upstreamFailureReturnsSafeRedirect() throws Exception {
        JSONObject callback=begin(); tokenReply=null;
        ResponseEntity<String> result=service.getPageAccessToken(pd(callback),request());
        assertEquals(HttpStatus.FOUND,result.getStatusCode());
        assertFalse(result.toString().contains("test-secret"));
    }
    private JSONObject guestExchange()throws Exception {
        bindingState="10000";JSONObject callback=begin();
        String location=service.getPageAccessToken(pd(callback),request()).getHeaders().getFirst("Location");
        JSONObject exchange=new JSONObject();exchange.put("authTicket",query(location.substring(location.indexOf('#')+1)).get("authTicket"));
        return JSONObject.parseObject(service.doLoginByKey(pd(exchange)).getBody());
    }
    @Test public void exactBindingFrontReturnsImmediateOwnerSessionAndHidesIdentity()throws Exception {
        JSONObject guest=guestExchange();bindingState="12000";
        JSONObject input=new JSONObject();input.put("wechatSession",guest.getString("wechatSession"));input.put("name","Test");input.put("phone","19900000001");input.put("idCard","34010119900101001X");
        input.put("openId","forged");input.put("ownerId","forged");input.put("state","12000");
        ResponseEntity<String> response=service.bindOwnerWechat(pd(input));assertEquals(HttpStatus.OK,response.getStatusCode());
        JSONObject result=JSONObject.parseObject(response.getBody());assertTrue(result.getBooleanValue("hasBinding"));assertNotNull(result.getString("token"));assertNotNull(result.getJSONObject("owner"));assertEquals(1,bindCalls);
        for(String secret:new String[]{"test-user-key","private-card","test-open-id"})assertFalse(response.getBody().contains(secret));
    }
    @Test public void expiredGuestCannotReachBindingService()throws Exception {
        JSONObject guest=guestExchange();String session=guest.getString("wechatSession");
        JSONObject identity=JSONObject.parseObject(cache.get("owner-wechat-identity:"+session));identity.put("expiresAt",1);cache.put("owner-wechat-identity:"+session,identity.toJSONString());
        JSONObject input=new JSONObject();input.put("wechatSession",session);
        assertEquals(HttpStatus.UNAUTHORIZED,service.bindOwnerWechat(pd(input)).getStatusCode());assertEquals(0,bindCalls);
    }
    @Test public void anotherAccountCommunityCannotGiveOwnerToken()throws Exception {
        bindingCommunity="another-account-community";
        JSONObject callback=begin();String location=service.getPageAccessToken(pd(callback),request()).getHeaders().getFirst("Location");
        JSONObject exchange=new JSONObject();exchange.put("authTicket",query(location.substring(location.indexOf('#')+1)).get("authTicket"));
        JSONObject result=JSONObject.parseObject(service.doLoginByKey(pd(exchange)).getBody());assertFalse(result.getBooleanValue("hasBinding"));assertNull(result.get("token"));assertEquals(0,loginCalls);
    }
    @Test public void wrongWechatIdentityCannotGiveOwnerToken()throws Exception {
        bindingOpenId="another-openid";
        JSONObject callback=begin();String location=service.getPageAccessToken(pd(callback),request()).getHeaders().getFirst("Location");
        JSONObject exchange=new JSONObject();exchange.put("authTicket",query(location.substring(location.indexOf('#')+1)).get("authTicket"));
        JSONObject result=JSONObject.parseObject(service.doLoginByKey(pd(exchange)).getBody());assertFalse(result.getBooleanValue("hasBinding"));assertNull(result.get("token"));
    }
    private void multipleBindings() {
        bindingRows="[{\"userId\":\"test-user\",\"memberId\":\"member-one\",\"ownerId\":\"owner-one\",\"openId\":\"test-open-id\",\"communityId\":\"test-community\",\"idCard\":\"private-card\",\"appType\":\"WECHAT\",\"state\":\"12000\"},"
            +"{\"userId\":\"test-user\",\"memberId\":\"member-two\",\"ownerId\":\"owner-two\",\"openId\":\"test-open-id\",\"communityId\":\"test-community\",\"idCard\":\"private-card-two\",\"appType\":\"WECHAT\",\"state\":\"12000\"},"
            +"{\"userId\":\"test-user\",\"memberId\":\"foreign-member\",\"ownerId\":\"foreign-owner\",\"openId\":\"foreign-open-id\",\"communityId\":\"test-community\",\"appType\":\"WECHAT\",\"state\":\"12000\"}]";
    }
    private JSONObject boundTicket(String identity,String preferred) {
        JSONObject ticket=new JSONObject();ticket.put("appId","992020061452450002");ticket.put("key","test-user-key");ticket.put("wechatSession",identity);
        if(preferred!=null)ticket.put("preferredMemberId",preferred);
        String id=UUID.randomUUID().toString();cache.put("owner-wechat-login:"+id,ticket.toJSONString());
        JSONObject input=new JSONObject();input.put("authTicket",id);return input;
    }
    @Test public void boundResponseContainsAllSanitizedVerifiedOwners()throws Exception {
        JSONObject guest=guestExchange();multipleBindings();
        String raw=service.doLoginByKey(pd(boundTicket(guest.getString("wechatSession"),null))).getBody();
        // JSON.parse in the browser does not resolve Fastjson's repeated-object references.
        assertFalse("Browser payload must contain complete owner descriptors",raw.contains("\"$ref\""));
        JSONObject result=JSONObject.parseObject(raw);
        assertNotNull(result.getJSONArray("owners"));assertEquals(2,result.getJSONArray("owners").size());
        assertEquals("member-one",result.getJSONObject("owner").getString("memberId"));
        for(String secret:new String[]{"private-card","test-open-id","foreign-member","foreign-open-id"})assertFalse(result.toJSONString().contains(secret));
    }
    @Test public void newlyVerifiedBindingSelectsNewMemberAndIgnoresClientPreference()throws Exception {
        JSONObject guest=guestExchange();multipleBindings();
        JSONObject input=new JSONObject();input.put("wechatSession",guest.getString("wechatSession"));input.put("preferredMemberId","member-one");
        JSONObject result=JSONObject.parseObject(service.bindOwnerWechat(pd(input)).getBody());
        assertEquals("member-two",result.getJSONObject("owner").getString("memberId"));
        assertEquals(2,result.getJSONArray("owners").size());
    }
    @Test public void clientPreferenceCannotOverrideTicketOrSelectUnverifiedOwner()throws Exception {
        JSONObject guest=guestExchange();multipleBindings();
        JSONObject input=boundTicket(guest.getString("wechatSession"),"member-two");input.put("preferredMemberId","foreign-member");
        JSONObject result=JSONObject.parseObject(service.doLoginByKey(pd(input)).getBody());
        assertEquals("member-two",result.getJSONObject("owner").getString("memberId"));
        input=boundTicket(guest.getString("wechatSession"),"foreign-member");input.put("preferredMemberId","member-two");
        result=JSONObject.parseObject(service.doLoginByKey(pd(input)).getBody());
        assertEquals("member-one",result.getJSONObject("owner").getString("memberId"));
    }
}
