import com.alibaba.fastjson.JSONObject;
import com.java110.api.listener.owner.OwnerRegisterListener;
import com.java110.api.listener.owner.AppUserBindingOwnerListener;
import com.java110.core.context.DataFlowContext;
import com.java110.core.event.service.api.ServiceDataFlowEvent;
import com.java110.dto.msg.SmsDto;
import com.java110.intf.common.ISmsInnerServiceSMO;
import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

public class OwnerRegistrationSmsTest {
    static class Registration extends OwnerRegisterListener {
        final String mode;
        Registration(String mode) { this.mode=mode; }
        protected String smsSwitch() { return mode; }
        void check(JSONObject input) { validate(null,input); }
    }
    static class Binding extends AppUserBindingOwnerListener {
        final String mode;
        Binding(String mode) { this.mode=mode; }
        protected String smsSwitch() { return mode; }
        void check(JSONObject input) {
            DataFlowContext context=(DataFlowContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DataFlowContext.class},
                (p,m,a)->"getRequestCurrentHeaders".equals(m.getName())?java.util.Collections.singletonMap("user_id","trusted-user"):null);
            validate(new ServiceDataFlowEvent(this,context,null),input);
        }
    }
    private JSONObject input() {
        JSONObject value=new JSONObject();
        value.put("communityName","Synthetic");value.put("areaCode","000");
        value.put("appUserName","Synthetic");value.put("link","19900000001");
        value.put("password","synthetic-only-password");
        return value;
    }
    private Registration listener(String mode,final boolean valid,final int[] calls)throws Exception {
        Registration registration=new Registration(mode);
        Field field=OwnerRegisterListener.class.getDeclaredField("smsInnerServiceSMOImpl");field.setAccessible(true);
        field.set(registration,Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ISmsInnerServiceSMO.class},(p,m,a)->{
            calls[0]++;
            SmsDto response=new SmsDto();response.setSuccess(valid);response.setMsg("Invalid verification code");return response;
        }));
        return registration;
    }
    @Test public void explicitlyDisabledSmsDoesNotRequireCodeOrCallSmsService()throws Exception {
        int[] calls={0};listener("OFF",false,calls).check(input());assertEquals(0,calls[0]);
    }
    @Test public void disabledSmsStillRequiresPassword()throws Exception {
        JSONObject value=input();value.remove("password");
        try { listener("OFF",false,new int[1]).check(value);fail("Missing password accepted"); }
        catch(IllegalArgumentException expected) { }
    }
    @Test public void enabledSmsRequiresCodeBeforeCallingVerifier()throws Exception {
        int[] calls={0};
        try { listener("ON",true,calls).check(input());fail("Missing code accepted"); }
        catch(IllegalArgumentException expected) { assertEquals(0,calls[0]); }
    }
    @Test public void enabledSmsRejectsInvalidCode()throws Exception {
        JSONObject value=input();value.put("msgCode","123456");
        int[] calls={0};
        try { listener("ON",false,calls).check(value);fail("Invalid code accepted"); }
        catch(IllegalArgumentException expected) { assertEquals(1,calls[0]); }
    }
    @Test public void enabledSmsAcceptsValidCode()throws Exception {
        JSONObject value=input();value.put("msgCode","123456");
        int[] calls={0};listener("ON",true,calls).check(value);assertEquals(1,calls[0]);
    }
    @Test public void missingOrUnknownSwitchNeverDisablesVerification()throws Exception {
        for(String mode:new String[]{null,"","off","UNKNOWN"}) {
            try {listener(mode,true,new int[1]).check(input());fail("Unknown switch bypassed verification");}
            catch(IllegalArgumentException expected) { }
        }
    }
    @Test public void explicitlyDisabledSmsAlsoAllowsBindingWithoutCode()throws Exception {
        JSONObject value=input();value.put("idCard","synthetic-id");
        new Binding("OFF").check(value);
    }
    @Test public void bindingWithUnknownSmsStillRequiresCode()throws Exception {
        JSONObject value=input();value.put("idCard","synthetic-id");
        try {new Binding(null).check(value);fail("Binding bypassed missing verification");}
        catch(IllegalArgumentException expected) { }
    }
}
