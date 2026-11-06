import com.java110.dto.fee.BillDto;
import com.java110.fee.smo.impl.FeeInnerServiceSMOImpl;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

public class FeeReviewValidationTest {
    FeeInnerServiceSMOImpl service;int calls;Map command;
    @Before public void setup() throws Exception {
        service=new FeeInnerServiceSMOImpl();calls=0;
        Field field=service.getClass().getDeclaredField("feeServiceDaoImpl");field.setAccessible(true);
        field.set(service,Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{field.getType()},(p,m,a)->{
            if(!m.getName().equals("reviewBill"))throw new AssertionError(m.getName());calls++;command=(Map)a[0];return 1;
        }));
    }
    private BillDto input(String state,String reason){BillDto dto=new BillDto();dto.setBillId("bill");dto.setCommunityId("community");dto.setReviewUserId("staff");dto.setReviewState(state);dto.setReviewRemark(reason);return dto;}
    @Test public void allThreeTargetsReachOneConditionalUpdate(){
        for(String state:Arrays.asList("PUBLISHED","REJECTED","DRAFT")) {assertEquals(1,service.reviewBill(input(state,"Checked")));assertEquals(state,command.get("reviewState"));}
        assertEquals(3,calls);
    }
    @Test public void missingReasonAndOversizedRemarksNeverReachDatabase(){
        for(String reason:Arrays.asList(null,"","  ",String.join("",Collections.nCopies(501,"x")))) {
            try{service.reviewBill(input("REJECTED",reason));fail("Invalid rejection reached database");}catch(IllegalArgumentException expected){}
        }
        try{service.reviewBill(input("UNKNOWN","Checked"));fail("Unknown transition reached database");}catch(IllegalArgumentException expected){}
        assertEquals(0,calls);
    }
}
