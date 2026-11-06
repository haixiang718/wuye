import com.alibaba.fastjson.JSONObject;
import com.java110.api.bmo.fee.impl.BillGenerationService;
import com.java110.api.listener.fee.GenerateBillListener;
import com.java110.core.context.DataFlowContext;
import com.java110.core.event.service.api.ServiceDataFlowEvent;
import com.java110.dto.CommunityMemberDto;
import com.java110.dto.fee.FeeConfigDto;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

public class ManualBillAccessTest {
    private BillGenerationService service;
    private CommunityMemberDto relation;
    private List<CommunityMemberDto> relations;
    private int calls;
    private FeeConfigDto command;
    private JSONObject reply;
    private boolean failure;
    @Before public void setup()throws Exception {
        service=new BillGenerationService();calls=0;failure=false;command=null;
        relation=new CommunityMemberDto();relation.setCommunityId("community");relation.setMemberId("property");
        relation.setMemberTypeCd(CommunityMemberDto.MEMBER_TYPE_PROPERTY);relation.setAuditStatusCd(CommunityMemberDto.AUDIT_STATUS_NORMAL);
        relations=Collections.singletonList(relation);
        reply=new JSONObject();reply.put("code",0);reply.put("msg","generated");
        for(String name:new String[]{"communityInnerServiceSMOImpl","billGenerateInnerServiceSMOImpl"}) {
            Field field=BillGenerationService.class.getDeclaredField(name);field.setAccessible(true);
            field.set(service,Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{field.getType()},(p,m,a)->{
                if(m.getName().equals("getCommunityMembers"))return relations;
                if(m.getName().equals("generate")){calls++;command=(FeeConfigDto)a[0];if(failure)throw new IllegalStateException("private server detail");return reply;}
                if(m.getName().equals("status")){calls++;command=(FeeConfigDto)a[0];JSONObject state=new JSONObject();state.put("state","OK");state.put("pending",1);return state;}
                throw new IllegalStateException(m.getName());
            }));
        }
    }
    private JSONObject run(){return service.generate("community","config","property","staff");}
    @Test public void employeeForManagedCommunityCanGenerateOnlyScopedConfig(){
        assertEquals(0,run().getIntValue("code"));assertEquals(1,calls);
        assertEquals("community",command.getCommunityId());assertEquals("config",command.getConfigId());
        assertNull(command.getAdditionalAmount());assertNull(command.getBillType());
    }
    @Test public void queueStatusRequiresManagedCommunityAndTrustedScope(){
        assertEquals("OK",service.status("community","property","staff").getString("state"));assertEquals("community",command.getCommunityId());assertNull(command.getConfigId());
        relation.setCommunityId("other");assertEquals("UNAVAILABLE",service.status("community","property","staff").getString("state"));assertEquals(1,calls);
    }
    @Test public void anonymousCannotReadQueueStatus(){assertEquals("UNAVAILABLE",service.status("community","property","-1").getString("state"));assertEquals(0,calls);}
    @Test public void missingOrAnonymousEmployeeCannotGenerate(){
        assertEquals(403,service.generate("community","config","property","-1").getIntValue("code"));
        assertEquals(403,service.generate("community","config",null,"staff").getIntValue("code"));assertEquals(0,calls);
    }
    @Test public void anotherCommunityCannotGenerate(){relation.setCommunityId("other");assertEquals(403,run().getIntValue("code"));assertEquals(0,calls);}
    @Test public void anotherPropertyCannotGenerate(){relation.setMemberId("other");assertEquals(403,run().getIntValue("code"));assertEquals(0,calls);}
    @Test public void rejectedOrInactivePropertyCannotGenerate(){
        relation.setAuditStatusCd("1000");assertEquals(403,run().getIntValue("code"));
        relation.setAuditStatusCd("1100");relation.setStatusCd("1");assertEquals(403,run().getIntValue("code"));assertEquals(0,calls);
    }
    @Test public void emptyRelationsAndMalformedIdsCannotGenerate(){
        relations=Collections.emptyList();assertEquals(403,run().getIntValue("code"));
        assertEquals(400,service.generate("community","config/other","property","staff").getIntValue("code"));assertEquals(0,calls);
    }
    @Test public void jobFailureCannotReportSuccessOrRevealInternalDetails(){
        failure=true;JSONObject result=run();assertEquals(500,result.getIntValue("code"));assertFalse(result.toJSONString().contains("private server detail"));
        failure=false;reply=null;assertEquals(500,run().getIntValue("code"));
    }
    @Test public void meaningfulJobRejectionsArePreserved(){reply.put("code",409);reply.put("msg","no due fees");assertEquals(409,run().getIntValue("code"));}
    @Test public void listenerRejectsOwnerAppEvenWhenBrowserClaimsStaff(){
        class Exposed extends GenerateBillListener {
            void check(String app){
                DataFlowContext context=(DataFlowContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DataFlowContext.class},(p,m,a)->m.getName().equals("getAppId")?app:null);
                JSONObject input=new JSONObject();input.put("communityId","community");input.put("configId","config");
                validate(new ServiceDataFlowEvent(this,context,null),input);
            }
        }
        Exposed listener=new Exposed();listener.check("8000418004");
        try{listener.check("992020061452450002");fail("Owner app must not generate bills");}catch(IllegalArgumentException expected){}
    }
}
