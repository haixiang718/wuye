import com.alibaba.fastjson.*;
import com.java110.api.listener.owner.BindWechatIdentityListener;
import com.java110.core.context.DataFlowContext;
import com.java110.core.event.service.api.ServiceDataFlowEvent;
import com.java110.dto.app.AppDto;
import com.java110.dto.community.CommunityDto;
import com.java110.dto.owner.*;
import com.java110.dto.smallWeChat.SmallWeChatDto;
import com.java110.dto.user.UserDto;
import com.java110.po.owner.OwnerAppUserPo;
import com.java110.po.userAttr.UserAttrPo;
import com.java110.vo.ResultVo;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

public class WechatIdentityBindingTest {
    private Fake service;private JSONObject input,verified;private OwnerDto owner;private UserDto user;
    private SmallWeChatDto account;private CommunityDto community;
    private final List<OwnerDto> ownerRows=new ArrayList<>();private final List<OwnerAppUserDto> rows=new ArrayList<>();
    private ResponseHolder holder;private int registered,ownerQueries,releases;private boolean locked,expired,commitFailure;private long attempts;
    private JSONObject registeredUser;private String appId;private final List<Object> writes=new ArrayList<>();
    private class Fake extends BindWechatIdentityListener {
        String attemptKey;
        protected JSONObject identity(String token){if(expired)throw new IllegalArgumentException("expired");return verified;}
        protected long attempts(String key){attemptKey=key;return attempts;}
        protected boolean acquire(String key,String nonce){return !locked;}
        protected void release(String key,String nonce){releases++;}
        @Override public void insert(DataFlowContext context,Object value,String business){writes.add(value);}
        @Override public void update(DataFlowContext context,Object value,String business){writes.add(value);}
        @Override public void commit(DataFlowContext context){
            if(commitFailure)throw new IllegalStateException("synthetic failure");
            if(registeredUser!=null&&user==null){user=new UserDto();user.setUserId(registeredUser.getString("userId"));user.setLevelCd("02");}
            for(Object value:writes){
                if(value instanceof UserAttrPo && user!=null)user.setKey(((UserAttrPo)value).getValue());
                if(value instanceof OwnerAppUserPo){OwnerAppUserPo po=(OwnerAppUserPo)value;rows.clear();rows.add(JSON.parseObject(JSON.toJSONString(po),OwnerAppUserDto.class));}
            }
            context.setResponseEntity(ResultVo.createResponseEntity(new JSONObject()));
        }
        JSONObject call(JSONObject command){
            DataFlowContext context=(DataFlowContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DataFlowContext.class},(p,m,a)->{
                switch(m.getName()){
                    case "getAppId":return appId;
                    case "getResponseEntity":return holder.response;
                    case "setResponseEntity":holder.response=(org.springframework.http.ResponseEntity<String>)a[0];return null;
                    case "getRequestCurrentHeaders":return new HashMap<String,String>();
                    default:return null;
                }
            });
            ServiceDataFlowEvent event=new ServiceDataFlowEvent(this,context,null);
            try{validate(event,command);doSoService(event,context,command);}
            catch(IllegalArgumentException expected){return JSON.parseObject("{\"code\":400}");}
            return JSON.parseObject(holder.response.getBody());
        }
    }
    private static class ResponseHolder {org.springframework.http.ResponseEntity<String> response;}
    @Before public void before()throws Exception {
        service=new Fake();holder=new ResponseHolder();appId=AppDto.WECHAT_OWNER_APP_ID;
        input=new JSONObject();input.put("wechatSession",UUID.randomUUID().toString());input.put("name","Synthetic Owner");input.put("phone","19900000001");input.put("idCard","34010119900101001X");
        verified=new JSONObject();verified.put("wechatAppId","wx-synthetic");verified.put("openId","synthetic-openid");verified.put("communityId","community");
        owner=new OwnerDto();owner.setName("Synthetic Owner");owner.setLink("19900000001");owner.setIdCard("34010119900101001X");owner.setCommunityId("community");owner.setOwnerId("owner");owner.setMemberId("member");ownerRows.clear();ownerRows.add(owner);
        account=new SmallWeChatDto();account.setAppId("wx-synthetic");account.setWeChatType("1100");account.setObjType("1000");account.setObjId("community");
        community=new CommunityDto();community.setCommunityId("community");community.setName("Synthetic community");rows.clear();user=null;registered=ownerQueries=releases=0;locked=expired=commitFailure=false;attempts=1;registeredUser=null;writes.clear();
        for(String field:new String[]{"smallWeChatInnerServiceSMOImpl","communityInnerServiceSMOImpl","ownerInnerServiceSMOImpl","ownerAppUserInnerServiceSMOImpl","userInnerServiceSMOImpl","userBMOImpl"}){
            Field f=BindWechatIdentityListener.class.getDeclaredField(field);f.setAccessible(true);
            f.set(service,Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{f.getType()},(p,m,a)->{
                switch(m.getName()){
                    case "querySmallWeChats":return Collections.singletonList(account);
                    case "queryCommunitys":return community==null?Collections.emptyList():Collections.singletonList(community);
                    case "queryOwnerMembers":ownerQueries++;return ownerRows;
                    case "queryOwnerAppUsers":return rows;
                    case "getUsers":return user==null?Collections.emptyList():Collections.singletonList(user);
                    case "registerUser":registered++;registeredUser=(JSONObject)a[0];return null;
                    default:throw new IllegalStateException(m.getName());
                }
            }));
        }
    }
    private JSONObject call(){holder.response=null;return service.call(input);}
    private void oldBinding(String state,String openId){OwnerAppUserDto row=new OwnerAppUserDto();row.setState(state);row.setOpenId(openId);row.setUserId("old-user");row.setMemberId("member");row.setCommunityId("community");row.setAppType("WECHAT");row.setAppUserId("old-binding");rows.add(row);user=new UserDto();user.setUserId("old-user");user.setLevelCd("02");user.setKey("old-key");}
    @Test public void exactTripleCreatesApprovedBindingAndRandomCredential(){
        input.put("openId","attacker");input.put("userId","staff");input.put("state","10000");input.put("ownerId","another");
        JSONObject result=call();assertEquals(0,result.getIntValue("code"));assertEquals(1,registered);assertEquals(2,writes.size());
        assertEquals("member",result.getJSONObject("data").getString("memberId"));assertEquals("owner",result.getJSONObject("data").getString("ownerId"));
        assertTrue(registeredUser.getString("password").length()>60);assertNotEquals(input.getString("phone"),registeredUser.getString("password"));
        OwnerAppUserPo binding=(OwnerAppUserPo)writes.get(1);assertEquals("12000",binding.getState());assertEquals("member",binding.getMemberId());assertEquals("synthetic-openid",binding.getOpenId());assertTrue(binding.getUserId().startsWith("wx"));assertEquals(1,releases);
    }
    @Test public void eachWrongFactCannotCreateUserOrBinding(){for(String field:new String[]{"name","phone","idCard"}){JSONObject wrong=JSON.parseObject(input.toJSONString());wrong.put(field,"phone".equals(field)?"19900000002":"idCard".equals(field)?"34010119900101002X":"Wrong");assertEquals(400,service.call(wrong).getIntValue("code"));}assertEquals(0,registered);assertTrue(writes.isEmpty());}
    @Test public void missingIdentityNeverQueriesOwner(){expired=true;assertEquals(400,call().getIntValue("code"));assertEquals(0,ownerQueries);assertTrue(writes.isEmpty());}
    @Test public void missingCardCannotMatchBroaderNameAndPhone(){input.remove("idCard");assertEquals(400,call().getIntValue("code"));assertEquals(0,ownerQueries);}
    @Test public void duplicatePersonFactsAreAmbiguous(){ownerRows.add(owner);assertEquals(400,call().getIntValue("code"));assertEquals(0,registered);}
    @Test public void returnedOwnerOutsideCommunityIsRejected(){owner.setCommunityId("other");assertEquals(400,call().getIntValue("code"));assertTrue(writes.isEmpty());}
    @Test public void unconfiguredPublicAccountCannotQueryOwners(){account.setAppId("wx-other");assertEquals(400,call().getIntValue("code"));assertEquals(0,ownerQueries);}
    @Test public void clientCannotSelectUnrelatedCommunity(){input.put("communityId","other");assertEquals(400,call().getIntValue("code"));assertEquals(0,ownerQueries);}
    @Test public void invalidCommunityCannotBind(){community=null;assertEquals(400,call().getIntValue("code"));assertEquals(0,registered);}
    @Test public void ordinaryRegistrationAppCannotUseEndpoint(){appId="untrusted-app";assertEquals(400,call().getIntValue("code"));assertEquals(0,ownerQueries);}
    @Test public void sameRequestIsIdempotentAfterCommit(){assertEquals(0,call().getIntValue("code"));int count=writes.size();assertEquals(0,call().getIntValue("code"));assertEquals(1,registered);assertEquals(count,writes.size());}
    @Test public void concurrentIdentityCannotSubmitSecondTransaction(){locked=true;assertEquals(409,call().getIntValue("code"));assertEquals(0,registered);assertEquals(0,releases);}
    @Test public void attemptLimitCannotBeResetByNewSessionToken(){attempts=11;assertEquals(429,call().getIntValue("code"));String key=service.attemptKey;input.put("wechatSession",UUID.randomUUID().toString());assertEquals(429,call().getIntValue("code"));assertEquals(key,service.attemptKey);assertEquals(0,registered);}
    @Test public void pendingBindingRequiresTripleBeforePromotion(){oldBinding("10000","synthetic-openid");input.put("name","Wrong");assertEquals(400,call().getIntValue("code"));assertTrue(writes.isEmpty());input.put("name","Synthetic Owner");assertEquals(0,call().getIntValue("code"));assertEquals(0,registered);assertEquals(1,writes.size());assertEquals("old-binding",((OwnerAppUserPo)writes.get(0)).getAppUserId());assertEquals("12000",((OwnerAppUserPo)writes.get(0)).getState());}
    @Test public void rejectedOwnBindingMayBeVerifiedAgain(){oldBinding("13000","synthetic-openid");assertEquals(0,call().getIntValue("code"));assertEquals("12000",((OwnerAppUserPo)writes.get(0)).getState());}
    @Test public void anotherWechatBindingIsNeverMoved(){oldBinding("12000","another-openid");user=null;assertEquals(0,call().getIntValue("code"));OwnerAppUserPo binding=(OwnerAppUserPo)writes.get(1);assertEquals("-1",binding.getAppUserId());assertNotEquals("old-user",binding.getUserId());assertEquals("synthetic-openid",binding.getOpenId());}
    @Test public void staffUserCannotBeReusedAsTechnicalOwner(){oldBinding("10000","synthetic-openid");user.setLevelCd("00");assertEquals(400,call().getIntValue("code"));assertTrue(writes.isEmpty());}
    @Test public void commitFailureDoesNotReturnSuccessfulIdentity(){commitFailure=true;assertEquals(500,call().getIntValue("code"));assertEquals(1,releases);}
    @Test public void onlyPermittedWhitespaceAndCardCaseNormalize(){input.put("name"," Synthetic Owner ");input.put("phone"," 19900000001 ");input.put("idCard"," 34010119900101001x ");assertEquals(0,call().getIntValue("code"));}
}
