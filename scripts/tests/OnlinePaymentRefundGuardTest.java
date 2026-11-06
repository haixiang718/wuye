import com.alibaba.fastjson.JSONObject;
import com.java110.api.listener.returnPayFee.SaveReturnPayFeeListener;
import com.java110.api.listener.returnPayFee.UpdateReturnPayFeeListener;
import com.java110.core.event.service.api.ServiceDataFlowEvent;
import com.java110.dto.fee.FeeDetailDto;
import com.java110.intf.fee.IFeeDetailInnerServiceSMO;
import com.java110.intf.fee.IFeePaymentOrderInnerServiceSMO;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real refund validators with synthetic service replies; no HTTP or database operations. */
public class OnlinePaymentRefundGuardTest {
    interface Action {void run()throws Exception;}
    static void rejected(Action action)throws Exception {try{action.run();fail("Online or unknown payment must not enter legacy refund");}catch(IllegalArgumentException|IllegalStateException expected){}}
    static void inject(Object target,String name,Object value)throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    static JSONObject request(){return JSONObject.parseObject("{\"communityId\":\"community\",\"feeId\":\"fee\",\"detailId\":\"detail\",\"reason\":\"synthetic test\",\"returnFeeId\":\"refund\",\"state\":\"1100\",\"primeRate\":\"1\",\"remark\":\"cash\"}");}
    static void validate(Object target,Boolean online,boolean unavailable,List<Map<String,Object>> scopes)throws Exception {
        FeeDetailDto detail=new FeeDetailDto();detail.setCommunityId("community");detail.setFeeId("fee");detail.setDetailId("detail");detail.setPrimeRate("1");detail.setRemark("edited server label");
        inject(target,"feeDetailInnerServiceSMOImpl",Proxy.newProxyInstance(IFeeDetailInnerServiceSMO.class.getClassLoader(),new Class[]{IFeeDetailInnerServiceSMO.class},(p,m,a)->Collections.singletonList(detail)));
        inject(target,"feePaymentOrderInnerServiceSMO",Proxy.newProxyInstance(IFeePaymentOrderInnerServiceSMO.class.getClassLoader(),new Class[]{IFeePaymentOrderInnerServiceSMO.class},(p,m,a)->{
            if(!"isOnlinePaymentDetail".equals(m.getName()))throw new AssertionError("Unexpected payment mutation");
            scopes.add(new HashMap<>((Map<String,Object>)a[0]));if(unavailable)throw new IllegalStateException("Synthetic unavailable service");return online;
        }));
        Method method=target.getClass().getDeclaredMethod("validate",ServiceDataFlowEvent.class,JSONObject.class);method.setAccessible(true);
        try{method.invoke(target,null,request());}catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception)throw (Exception)failure.getCause();throw new AssertionError(failure.getCause());}
    }
    @Test public void applicationAndApprovalRejectAssociatedOnlineDetailDespiteCashLabels()throws Exception {
        for(Object target:Arrays.asList(new SaveReturnPayFeeListener(),new UpdateReturnPayFeeListener())){
            List<Map<String,Object>> scopes=new ArrayList<>();rejected(()->validate(target,true,false,scopes));
            assertEquals(1,scopes.size());assertEquals(new HashSet<>(Arrays.asList("communityId","feeId","detailId")),scopes.get(0).keySet());assertEquals("detail",scopes.get(0).get("detailId"));
        }
    }
    @Test public void applicationAndApprovalRetainOfflineRefunds()throws Exception {
        validate(new SaveReturnPayFeeListener(),false,false,new ArrayList<>());validate(new UpdateReturnPayFeeListener(),false,false,new ArrayList<>());
    }
    @Test public void applicationAndApprovalFailClosedWhenAssociationCannotBeVerified()throws Exception {
        for(Object target:Arrays.asList(new SaveReturnPayFeeListener(),new UpdateReturnPayFeeListener())) {
            rejected(()->validate(target,null,false,new ArrayList<>()));rejected(()->validate(target,false,true,new ArrayList<>()));
        }
    }
}
