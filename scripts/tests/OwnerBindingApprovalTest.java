import com.alibaba.fastjson.*;
import com.java110.api.bmo.owner.impl.OwnerBMOImpl;
import com.java110.core.context.DataFlowContext;
import com.java110.dto.community.CommunityDto;
import com.java110.dto.owner.OwnerDto;
import com.java110.dto.owner.OwnerAppUserDto;
import com.java110.dto.user.UserDto;
import com.java110.dto.app.AppDto;
import com.java110.api.listener.owner.AppUserBindingOwnerListener;
import com.java110.core.event.service.api.ServiceDataFlowEvent;
import com.java110.intf.user.*;
import com.java110.intf.community.ICommunityInnerServiceSMO;
import com.java110.api.bmo.owner.IOwnerBMO;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.Assert.*;

public class OwnerBindingApprovalTest {
    static class Binding extends AppUserBindingOwnerListener {
        void run(DataFlowContext context,JSONObject input){doSoService(new ServiceDataFlowEvent(this,context,null),context,input);}
    }
    private static void inject(Object target,String name,Object value)throws Exception {
        Field field=AppUserBindingOwnerListener.class.getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static <T> T stub(Class<T> type,java.lang.reflect.InvocationHandler handler){
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler));
    }
    @Test public void duplicateBindingLookupUsesResolvedIdentityAndClientType()throws Exception {
        Binding listener=new Binding();
        JSONObject input=JSON.parseObject("{\"communityId\":\"forged-community\",\"userId\":\"forged-user\",\"memberId\":\"forged-owner\",\"appType\":\"forged\",\"communityName\":\"Synthetic\",\"areaCode\":\"000\",\"idCard\":\"synthetic\",\"appUserName\":\"Synthetic\",\"link\":\"19900000999\"}");
        UserDto user=new UserDto();user.setUserId("trusted-user");user.setOpenId("trusted-openid");
        CommunityDto community=new CommunityDto();community.setCommunityId("resolved-community");
        OwnerDto owner=new OwnerDto();owner.setMemberId("resolved-member");
        final OwnerAppUserDto[] captured=new OwnerAppUserDto[1];final JSONObject[] created=new JSONObject[1];
        inject(listener,"userInnerServiceSMOImpl",stub(IUserInnerServiceSMO.class,(p,m,a)->Collections.singletonList(user)));
        inject(listener,"communityInnerServiceSMOImpl",stub(ICommunityInnerServiceSMO.class,(p,m,a)->Collections.singletonList(community)));
        inject(listener,"ownerInnerServiceSMOImpl",stub(IOwnerInnerServiceSMO.class,(p,m,a)->Collections.singletonList(owner)));
        inject(listener,"ownerAppUserInnerServiceSMOImpl",stub(IOwnerAppUserInnerServiceSMO.class,(p,m,a)->{captured[0]=(OwnerAppUserDto)a[0];return Collections.emptyList();}));
        inject(listener,"ownerBMOImpl",stub(IOwnerBMO.class,(p,m,a)->{created[0]=(JSONObject)a[0];return null;}));
        DataFlowContext context=stub(DataFlowContext.class,(p,m,a)->{
            if("getRequestCurrentHeaders".equals(m.getName()))return Collections.singletonMap("user_id","trusted-user");
            if("getReqData".equals(m.getName()))return input.toJSONString();
            if("getAppId".equals(m.getName()))return AppDto.WECHAT_OWNER_APP_ID;
            return null;
        });
        listener.run(context,input);
        assertEquals("trusted-user",captured[0].getUserId());assertEquals("resolved-community",captured[0].getCommunityId());
        assertEquals("resolved-member",captured[0].getMemberId());assertArrayEquals(new String[]{"10000","12000"},captured[0].getStates());
        assertEquals(OwnerAppUserDto.APP_TYPE_WECHAT,created[0].getString("appType"));assertEquals("trusted-openid",created[0].getString("openId"));
    }
    @Test public void selfServiceBindingRequiresPropertyApprovalDespiteClientState() {
        final JSONArray calls=new JSONArray();
        DataFlowContext context=(DataFlowContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DataFlowContext.class},
            (proxy,method,args)->{if("addServiceBusiness".equals(method.getName()))calls.add(args[0]);return null;});
        CommunityDto community=new CommunityDto();community.setCommunityId("community");community.setName("Synthetic");
        OwnerDto owner=new OwnerDto();owner.setMemberId("owner");owner.setName("Synthetic");owner.setLink("19900000999");owner.setIdCard("synthetic");
        JSONObject input=new JSONObject();input.put("userId","user");input.put("state","12000");input.put("appType","WECHAT");
        new OwnerBMOImpl().addOwnerAppUser(input,community,owner,context);
        JSONObject created=calls.getJSONObject(0).getJSONObject("datas").getJSONArray("OwnerAppUserPo").getJSONObject(0);
        assertEquals("10000",created.getString("state"));assertEquals("owner",created.getString("memberId"));
    }
}
