package com.java110.api.listener.owner;

import com.alibaba.fastjson.JSONObject;
import com.java110.api.bmo.user.IUserBMO;
import com.java110.api.listener.AbstractServiceApiPlusListener;
import com.java110.core.annotation.Java110Listener;
import com.java110.core.context.DataFlowContext;
import com.java110.core.event.service.api.ServiceDataFlowEvent;
import com.java110.core.factory.OwnerWechatSession;
import com.java110.dto.app.AppDto;
import com.java110.dto.community.CommunityDto;
import com.java110.dto.owner.OwnerAppUserDto;
import com.java110.dto.owner.OwnerDto;
import com.java110.dto.smallWeChat.SmallWeChatDto;
import com.java110.dto.user.UserAttrDto;
import com.java110.dto.user.UserDto;
import com.java110.intf.community.ICommunityInnerServiceSMO;
import com.java110.intf.store.ISmallWeChatInnerServiceSMO;
import com.java110.intf.user.IOwnerAppUserInnerServiceSMO;
import com.java110.intf.user.IOwnerInnerServiceSMO;
import com.java110.intf.user.IUserInnerServiceSMO;
import com.java110.po.owner.OwnerAppUserPo;
import com.java110.po.userAttr.UserAttrPo;
import com.java110.utils.constant.BusinessTypeConstant;
import com.java110.utils.factory.ApplicationContextFactory;
import com.java110.utils.util.StringUtil;
import com.java110.vo.ResultVo;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import java.util.*;

/** Only an OAuth-verified identity plus three exact property facts may self-bind. */
@Java110Listener("bindWechatIdentityListener")
public class BindWechatIdentityListener extends AbstractServiceApiPlusListener {
    public static final String SERVICE_CODE="owner.bindWechatIdentity";
    private static final String MISMATCH="资料不匹配或登记信息不完整，请核对后联系物业";
    @Autowired private ISmallWeChatInnerServiceSMO smallWeChatInnerServiceSMOImpl;
    @Autowired private ICommunityInnerServiceSMO communityInnerServiceSMOImpl;
    @Autowired private IOwnerInnerServiceSMO ownerInnerServiceSMOImpl;
    @Autowired private IOwnerAppUserInnerServiceSMO ownerAppUserInnerServiceSMOImpl;
    @Autowired private IUserInnerServiceSMO userInnerServiceSMOImpl;
    @Autowired private IUserBMO userBMOImpl;

    @Override public String getServiceCode(){return SERVICE_CODE;}
    @Override public HttpMethod getHttpMethod(){return HttpMethod.POST;}
    @Override protected void validate(ServiceDataFlowEvent event,JSONObject input){
        if(!AppDto.WECHAT_OWNER_APP_ID.equals(event.getDataFlowContext().getAppId()))throw new IllegalArgumentException("此入口仅供公众号房产绑定使用");
        identity(input.getString("wechatSession"));
        if(normal(input.getString("name")).isEmpty()||!normal(input.getString("phone")).matches("1[0-9]{10}")
                ||!card(input.getString("idCard")).matches("([0-9]{15}|[0-9]{17}[0-9X])"))throw new IllegalArgumentException(MISMATCH);
    }
    @Override protected void doSoService(ServiceDataFlowEvent event,DataFlowContext context,JSONObject input){
        JSONObject verified=identity(input.getString("wechatSession"));
        String identityHash=DigestUtils.sha256Hex(verified.getString("wechatAppId")+":"+verified.getString("openId"));
        if(attempts("OWNER_WECHAT_BIND_ATTEMPTS:"+identityHash)>10){context.setResponseEntity(ResultVo.createResponseEntity(429,"尝试次数过多，请十五分钟后再试"));return;}
        String lock="OWNER_WECHAT_BIND_LOCK:"+identityHash,nonce=UUID.randomUUID().toString();
        if(!acquire(lock,nonce)){context.setResponseEntity(ResultVo.createResponseEntity(409,"正在处理房产绑定，请稍后重试"));return;}
        try {
            String communityId=authorizedCommunity(verified,input.getString("communityId"));
            CommunityDto cq=new CommunityDto();cq.setCommunityId(communityId);cq.setState("1100");
            List<CommunityDto> communities=communityInnerServiceSMOImpl.queryCommunitys(cq);
            if(communities==null||communities.size()!=1||!communityId.equals(communities.get(0).getCommunityId()))throw new IllegalArgumentException(MISMATCH);
            String name=normal(input.getString("name")),phone=normal(input.getString("phone")),idCard=card(input.getString("idCard"));
            OwnerDto query=new OwnerDto();query.setCommunityId(communityId);query.setName(name);query.setLink(phone);query.setIdCard(idCard);
            List<OwnerDto> owners=ownerInnerServiceSMOImpl.queryOwnerMembers(query);
            if(owners==null||owners.size()!=1)throw new IllegalArgumentException(MISMATCH);
            OwnerDto owner=owners.get(0);
            if(!communityId.equals(owner.getCommunityId())||!name.equals(normal(owner.getName()))||!phone.equals(normal(owner.getLink()))
                    ||!idCard.equals(card(owner.getIdCard()))||StringUtil.isEmpty(owner.getMemberId())||StringUtil.isEmpty(owner.getOwnerId()))throw new IllegalArgumentException(MISMATCH);
            OwnerAppUserDto bq=new OwnerAppUserDto();bq.setOpenId(verified.getString("openId"));bq.setAppType(OwnerAppUserDto.APP_TYPE_WECHAT);
            List<OwnerAppUserDto> previous=ownerAppUserInnerServiceSMOImpl.queryOwnerAppUsers(bq);
            Set<String> allowed=authorizedCommunities(verified);
            Set<String> users=new HashSet<String>();OwnerAppUserDto same=null;
            if(previous!=null)for(OwnerAppUserDto row:previous){
                if(!verified.getString("openId").equals(row.getOpenId())||!OwnerAppUserDto.APP_TYPE_WECHAT.equals(row.getAppType())||!allowed.contains(row.getCommunityId()))continue;
                if(!StringUtil.isEmpty(row.getUserId()))users.add(row.getUserId());
                if(communityId.equals(row.getCommunityId())&&owner.getMemberId().equals(row.getMemberId())){
                    if(same!=null)throw new IllegalArgumentException(MISMATCH);same=row;
                }
            }
            if(users.size()>1)throw new IllegalArgumentException("微信绑定信息存在冲突，请联系物业处理");
            String userId=users.isEmpty()?"wx"+identityHash.substring(0,28):users.iterator().next();
            UserDto uq=new UserDto();uq.setUserId(userId);List<UserDto> existing=userInnerServiceSMOImpl.getUsers(uq);
            String loginKey=null;
            if(users.isEmpty()){
                if(existing!=null&&!existing.isEmpty())throw new IllegalArgumentException("微信身份记录存在冲突，请联系物业处理");
                JSONObject technical=new JSONObject();technical.put("userId",userId);technical.put("name","微信业主"+identityHash.substring(0,12));
                technical.put("tel",phone);technical.put("password",UUID.randomUUID().toString()+UUID.randomUUID());technical.put("openId","-1");
                userBMOImpl.registerUser(technical,context);
            }else if(existing==null||existing.size()!=1||!userId.equals(existing.get(0).getUserId())
                    ||!("02".equals(existing.get(0).getLevelCd())||"03".equals(existing.get(0).getLevelCd())))throw new IllegalArgumentException(MISMATCH);
            if(users.isEmpty()||StringUtil.isEmpty(existing.get(0).getKey())){
                loginKey=UUID.randomUUID().toString();UserAttrPo attr=new UserAttrPo();attr.setAttrId("-1");attr.setUserId(userId);attr.setSpecCd(UserAttrDto.SPEC_KEY);attr.setValue(loginKey);
                insert(context,attr,BusinessTypeConstant.BUSINESS_TYPE_SAVE_USER_ATTR_INFO);
            }
            if(same==null||!OwnerAppUserDto.STATE_AUDIT_SUCCESS.equals(same.getState())){
                OwnerAppUserPo binding=new OwnerAppUserPo();binding.setAppUserId(same==null?"-1":same.getAppUserId());
                binding.setMemberId(owner.getMemberId());binding.setCommunityId(communityId);binding.setCommunityName(communities.get(0).getName());
                binding.setAppUserName(owner.getName());binding.setIdCard(owner.getIdCard());binding.setLink(owner.getLink());binding.setOpenId(verified.getString("openId"));
                binding.setUserId(userId);binding.setAppType(OwnerAppUserDto.APP_TYPE_WECHAT);binding.setAppTypeCd("10010");binding.setState(OwnerAppUserDto.STATE_AUDIT_SUCCESS);
                binding.setRemark("微信授权后三项资料精确核验绑定");
                if(same==null)insert(context,binding,BusinessTypeConstant.BUSINESS_TYPE_SAVE_OWNER_APP_USER);
                else update(context,binding,BusinessTypeConstant.BUSINESS_TYPE_UPDATE_OWNER_APP_USER);
            }
            // Keep the identity lock until the existing order transaction has actually returned.
            commit(context);
            if(context.getResponseEntity()!=null){
                JSONObject result=JSONObject.parseObject(String.valueOf(context.getResponseEntity().getBody()));
                if(result==null||result.getIntValue("code")!=0)throw new IllegalStateException("Binding transaction failed");
            }
            JSONObject result=new JSONObject();result.put("userId",userId);result.put("communityId",communityId);
            result.put("memberId",owner.getMemberId());result.put("ownerId",owner.getOwnerId());
            context.setResponseEntity(ResultVo.createResponseEntity(result));
        }catch(IllegalArgumentException failure){context.setServiceBusiness(null);context.setResponseEntity(ResultVo.createResponseEntity(400,failure.getMessage()));}
        catch(Exception failure){context.setServiceBusiness(null);context.setResponseEntity(ResultVo.createResponseEntity(500,"绑定暂未完成，请稍后重试或联系物业"));}
        finally{release(lock,nonce);}
    }
    private Set<String> authorizedCommunities(JSONObject identity){
        SmallWeChatDto query=new SmallWeChatDto();query.setAppId(identity.getString("wechatAppId"));query.setWeChatType(SmallWeChatDto.WECHAT_TYPE_PUBLIC);query.setObjType(SmallWeChatDto.OBJ_TYPE_COMMUNITY);
        List<SmallWeChatDto> accounts=smallWeChatInnerServiceSMOImpl.querySmallWeChats(query);Set<String> allowed=new HashSet<String>();
        if(accounts!=null)for(SmallWeChatDto account:accounts)if(identity.getString("wechatAppId").equals(account.getAppId())&&SmallWeChatDto.WECHAT_TYPE_PUBLIC.equals(account.getWeChatType())
                &&SmallWeChatDto.OBJ_TYPE_COMMUNITY.equals(account.getObjType())&&!StringUtil.isEmpty(account.getObjId()))allowed.add(account.getObjId());
        return allowed;
    }
    private String authorizedCommunity(JSONObject identity,String requested){
        Set<String> allowed=authorizedCommunities(identity);String configured=identity.getString("communityId");
        String chosen=StringUtil.isEmpty(requested)?configured:requested;
        if(StringUtil.isEmpty(chosen)&&allowed.size()==1)chosen=allowed.iterator().next();
        if(StringUtil.isEmpty(chosen)||!allowed.contains(chosen))throw new IllegalArgumentException("请选择该公众号对应的小区");
        return chosen;
    }
    private static String normal(String value){return value==null?"":value.trim();}
    private static String card(String value){return normal(value).toUpperCase(Locale.ROOT);}
    protected JSONObject identity(String token){return OwnerWechatSession.read(token);}
    protected long attempts(String key){try(Jedis redis=redis()){return ((Number)redis.eval("local n=redis.call('incr',KEYS[1]);if n==1 then redis.call('expire',KEYS[1],900) end;return n",Collections.singletonList(key),Collections.<String>emptyList())).longValue();}}
    protected boolean acquire(String key,String nonce){try(Jedis redis=redis()){return "OK".equals(redis.set(key,nonce,"NX","EX",600));}}
    protected void release(String key,String nonce){try(Jedis redis=redis()){redis.eval("if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",Collections.singletonList(key),Collections.singletonList(nonce));}}
    private Jedis redis(){return ApplicationContextFactory.getBean("jedisPool",JedisPool.class).getResource();}
}
