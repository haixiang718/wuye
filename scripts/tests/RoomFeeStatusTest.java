import com.java110.api.bmo.fee.impl.RoomFeeStatusService;
import com.java110.core.smo.IComputeFeeSMO;
import com.java110.dto.RoomDto;
import com.java110.dto.fee.*;
import com.java110.intf.fee.IFeeInnerServiceSMO;
import com.java110.vo.api.ApiRoomDataVo;
import org.junit.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

public class RoomFeeStatusTest {
    private RoomFeeStatusService service;
    private final List<FeeDto> fees = new ArrayList<>();
    private final List<BillOweFeeDto> bills = new ArrayList<>();
    private boolean failQuery, failCompute;
    private int queries, computations;
    @Before public void setup() throws Exception {
        service = new RoomFeeStatusService();
        inject("feeInnerServiceSMOImpl", IFeeInnerServiceSMO.class, (p,m,a)-> {
            queries++;
            if (failQuery) throw new IllegalStateException("synthetic outage");
            if (m.getName().equals("queryFees")) {
                FeeDto q=(FeeDto)a[0]; assertEquals("c",q.getCommunityId());
                assertEquals("3333",q.getPayerObjType()); assertEquals(-1,q.getPage());
                assertArrayEquals(new String[]{"r","empty"},q.getPayerObjIds());
                return fees;
            }
            if (m.getName().equals("queryBillOweFees")) {
                BillOweFeeDto q=(BillOweFeeDto)a[0]; assertEquals("c",q.getCommunityId());
                assertEquals("3333",q.getPayerObjType()); assertEquals(-1,q.getPage());
                assertArrayEquals(new String[]{"r","empty"},q.getPayObjIds());
                return bills;
            }
            throw new AssertionError(m.getName());
        });
        inject("computeFeeSMOImpl", IComputeFeeSMO.class, (p,m,a)-> {
            assertEquals("computeEveryOweFee",m.getName()); computations++;
            assertEquals("r",((RoomDto)a[1]).getRoomId());
            if(failCompute) throw new IllegalStateException("synthetic invalid formula");
            return null;
        });
    }
    private void inject(String field,Class<?> type,InvocationHandler h) throws Exception {
        Field f=RoomFeeStatusService.class.getDeclaredField(field);f.setAccessible(true);
        f.set(service,Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{type},h));
    }
    private FeeDto fee(String state,double amount) {
        FeeDto f=new FeeDto(); f.setFeeId("fee"+fees.size());f.setCommunityId("c");f.setPayerObjId("r");
        f.setPayerObjType("3333");f.setState(state);f.setEndTime(new Date(0));f.setFeePrice(amount);fees.add(f);return f;
    }
    private void bill(String state,String amount) {
        BillOweFeeDto b=new BillOweFeeDto();b.setFeeId("snapshot");b.setCommunityId("c");b.setPayObjId("r");
        b.setPayerObjType("3333");b.setState(state);b.setBillAmountOwed(amount);bills.add(b);
    }
    private String status() {
        RoomDto r=new RoomDto();r.setRoomId("r");RoomDto e=new RoomDto();e.setRoomId("empty");
        ApiRoomDataVo v=new ApiRoomDataVo();v.setRoomId("r");ApiRoomDataVo empty=new ApiRoomDataVo();empty.setRoomId("empty");
        service.populate("c",Arrays.asList(r,e),Arrays.asList(v,empty));
        assertEquals(failQuery?"UNKNOWN":"NO_BILLS",empty.getFeePaymentStatus());
        return v.getFeePaymentStatus();
    }
    @Test public void noFeesRemainNeutral() {assertEquals("NO_BILLS",status());assertEquals(2,queries);}
    @Test public void anyUnpaidItemWinsOverPaidItems() {fee(FeeDto.STATE_FINISH,0);fee(FeeDto.STATE_DOING,.01);assertEquals("OUTSTANDING",status());}
    @Test public void allPaidIsSettled() {fee(FeeDto.STATE_FINISH,99);assertEquals("SETTLED",status());assertEquals(0,computations);}
    @Test public void activeZeroBalanceIsSettled() {fee(FeeDto.STATE_DOING,0);assertEquals("SETTLED",status());}
    @Test public void prepaidFuturePeriodIsNotOutstanding() {fee(FeeDto.STATE_DOING,99).setEndTime(new Date(Long.MAX_VALUE));assertEquals("SETTLED",status());assertEquals(0,computations);}
    @Test public void sentUnpaidSnapshotIsStillOutstanding() {bill("3000","0.01");assertEquals("OUTSTANDING",status());}
    @Test public void unsentUnpaidSnapshotIsOutstanding() {bill("1000","0.01");assertEquals("OUTSTANDING",status());}
    @Test public void paidSnapshotDoesNotUseHistoricalAmount() {bill("2000","99");assertEquals("SETTLED",status());}
    @Test public void invalidFormulaNeverAppearsSettled() {fee(FeeDto.STATE_DOING,0);failCompute=true;assertEquals("UNKNOWN",status());}
    @Test public void failedReadNeverAppearsSettled() {failQuery=true;assertEquals("UNKNOWN",status());}
    @Test public void missingDateNeverAppearsSettled() {fee(FeeDto.STATE_DOING,99).setEndTime(null);assertEquals("UNKNOWN",status());}
    @Test public void invalidAmountNeverAppearsSettled() {fee(FeeDto.STATE_DOING,Double.NaN);assertEquals("UNKNOWN",status());}
    @Test public void debtWinsOverInvalidRecord() {fee(FeeDto.STATE_DOING,0).setEndTime(null);bill("3000","1");assertEquals("OUTSTANDING",status());}
    @Test public void otherCommunitiesTypesAndRoomsAreIgnored() {
        fee(FeeDto.STATE_DOING,99).setCommunityId("other");fee(FeeDto.STATE_DOING,99).setPayerObjType("6666");
        fee(FeeDto.STATE_DOING,99).setPayerObjId("not-on-page");assertEquals("NO_BILLS",status());assertEquals(0,computations);
    }
    @Test public void noRoomsDoesNotQueryAllFees() {service.populate("c",Collections.emptyList(),Collections.emptyList());assertEquals(0,queries);}
    @Test public void paymentIsReflectedOnNextReadWithoutCachedDebt() {
        fee(FeeDto.STATE_DOING,.01);assertEquals("OUTSTANDING",status());
        fees.get(0).setState(FeeDto.STATE_FINISH);assertEquals("SETTLED",status());assertEquals(4,queries);
    }
    @Test public void malformedSnapshotAmountIsNotSettled() {bill("3000","invalid");assertEquals("UNKNOWN",status());}
    @Test public void foreignSnapshotCannotColorThisRoom() {
        bill("3000","1");bills.get(0).setCommunityId("other");assertEquals("NO_BILLS",status());
    }
}
