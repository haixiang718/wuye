import com.alibaba.fastjson.*;
import com.java110.api.bmo.owner.impl.OwnerBMOImpl;
import com.java110.api.bmo.owner.impl.OwnerRoomCreationService;
import com.java110.api.bmo.room.impl.RoomBMOImpl;
import com.java110.core.context.DataFlowContext;
import com.java110.dto.RoomDto;
import com.java110.dto.owner.*;
import com.java110.intf.community.IRoomInnerServiceSMO;
import com.java110.intf.user.*;
import org.junit.Test;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

public class OwnerRoomCreationTest {
    static class Memory extends OwnerRoomCreationService {
        Set<String> held=new HashSet<>(), denied=new HashSet<>();
        List<String> released=new ArrayList<>();
        protected boolean acquire(String key,String token){return !denied.contains(key)&&held.add(key);}
        protected void release(String key,String token){held.remove(key);released.add(key);}
    }
    static JSONObject request() {
        return JSON.parseObject("{\"communityId\":\"c1\",\"ownerTypeCd\":\"1001\",\"ownerId\":\"new-owner\",\"userId\":\"staff\",\"storeId\":\"store\",\"name\":\"测试业主\",\"link\":\"19900000999\",\"idCard\":\"11010519491231002X\",\"roomIds\":[\"r1\",\"r2\"]}");
    }
    static void inject(Object target,String field,Object value)throws Exception{
        Field f=OwnerRoomCreationService.class.getDeclaredField(field);f.setAccessible(true);f.set(target,value);
    }
    static <T> T stub(Class<T> type,InvocationHandler fn){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},fn));}
    static Memory service()throws Exception {
        Memory s=new Memory();
        inject(s,"roomBMOImpl",new RoomBMOImpl());
        inject(s,"roomInnerServiceSMOImpl",stub(IRoomInnerServiceSMO.class,(p,m,a)->{
            RoomDto q=(RoomDto)a[0];assertEquals("c1",q.getCommunityId());assertEquals("0",q.getStatusCd());
            RoomDto r=new RoomDto();r.setRoomId(q.getRoomId());r.setCommunityId("c1");
            return Collections.singletonList(r);
        }));
        inject(s,"ownerRoomRelInnerServiceSMOImpl",stub(IOwnerRoomRelInnerServiceSMO.class,(p,m,a)->{
            OwnerRoomRelDto q=(OwnerRoomRelDto)a[0];assertEquals("c1",q.getCommunityId());return 0;
        }));
        inject(s,"ownerInnerServiceSMOImpl",stub(IOwnerInnerServiceSMO.class,(p,m,a)->{
            OwnerDto q=(OwnerDto)a[0];assertEquals("c1",q.getCommunityId());assertEquals("1001",q.getOwnerTypeCd());
            assertEquals("11010519491231002X",q.getIdCard());return Collections.emptyList();
        }));
        return s;
    }
    static void rejected(Runnable action,String text){
        try{action.run();fail("Expected rejection: "+text);}catch(IllegalArgumentException e){assertTrue(e.getMessage(),e.getMessage().contains(text));}
    }
    @Test public void validatesEveryRoomWithinCurrentCommunity()throws Exception{
        service().validate(request());
    }
    @Test public void rejectsDuplicateAndMalformedRoomLists()throws Exception{
        Memory s=service();JSONObject r=request();r.put("roomIds",JSON.parseArray("[\"r1\",\"r1\"]"));
        rejected(()->s.validate(r),"重复");
        r.put("roomIds","r1");rejected(()->s.validate(r),"数组");
        r.put("roomIds",JSON.parseArray("[123]"));rejected(()->s.validate(r),"房屋");
        JSONArray many=new JSONArray();for(int i=0;i<51;i++)many.add("r"+i);r.put("roomIds",many);
        rejected(()->s.validate(r),"50");
    }
    @Test public void memberAndMixedLegacyParametersAreRejected()throws Exception{
        Memory s=service();JSONObject r=request();r.put("ownerTypeCd","1002");
        rejected(()->s.validate(r),"业主");
        r.put("ownerTypeCd","1001");r.put("roomId","legacy");rejected(()->s.validate(r),"同时");
    }
    @Test public void missingVerificationFieldsCannotCreateBoundOwner()throws Exception{
        Memory s=service();for(String field:Arrays.asList("name","link","idCard")){
            JSONObject r=request();r.put(field,"");rejected(()->s.validate(r),"填写");
        }
    }
    @Test public void unavailableAndOtherCommunityRoomsAreRejected()throws Exception{
        Memory s=service();
        inject(s,"roomInnerServiceSMOImpl",stub(IRoomInnerServiceSMO.class,(p,m,a)->Collections.emptyList()));
        rejected(()->s.validate(request()),"房屋");
        inject(s,"roomInnerServiceSMOImpl",stub(IRoomInnerServiceSMO.class,(p,m,a)->{
            RoomDto r=new RoomDto();r.setRoomId("r1");r.setCommunityId("other");return Collections.singletonList(r);
        }));
        rejected(()->s.validate(request()),"小区");
    }
    @Test public void existingOwnerAndExistingBindingAreRejected()throws Exception{
        Memory s=service();
        inject(s,"ownerRoomRelInnerServiceSMOImpl",stub(IOwnerRoomRelInnerServiceSMO.class,(p,m,a)->1));
        rejected(()->s.validate(request()),"已绑定");
        Memory duplicate=service();inject(duplicate,"ownerInnerServiceSMOImpl",stub(IOwnerInnerServiceSMO.class,(p,m,a)->Collections.singletonList(new OwnerDto())));
        rejected(()->duplicate.validate(request()),"已存在");
    }
    @Test public void successfulCommitHoldsAllLocksUntilOneCommitReturns()throws Exception{
        Memory s=service();int[] committed={0};
        s.commit(request(),()->{assertEquals(3,s.held.size());committed[0]++;});
        assertEquals(1,committed[0]);assertTrue(s.held.isEmpty());assertEquals(3,s.released.size());
    }
    @Test public void concurrentConflictReleasesAcquiredLocksWithoutCommit()throws Exception{
        Memory s=service();s.denied.add("OWNER_CREATE_ROOM:c1:r2");int[] committed={0};
        rejected(()->s.commit(request(),()->committed[0]++),"处理");
        assertEquals(0,committed[0]);assertTrue(s.held.isEmpty());
    }
    @Test public void bindingIsCheckedAgainUnderLockBeforeCommit()throws Exception{
        Memory s=service();s.validate(request());
        inject(s,"ownerRoomRelInnerServiceSMOImpl",stub(IOwnerRoomRelInnerServiceSMO.class,(p,m,a)->1));
        int[] committed={0};rejected(()->s.commit(request(),()->committed[0]++),"已绑定");
        assertEquals(0,committed[0]);assertTrue(s.held.isEmpty());
    }
    @Test public void exceptionsReleaseAllLocks()throws Exception{
        Memory s=service();
        try{s.commit(request(),()->{throw new IllegalStateException("synthetic commit failure");});fail();}
        catch(IllegalStateException expected){assertTrue(s.held.isEmpty());}
    }
    @Test public void multiRoomBusinessUsesSameOwnerAndExplicitValidityTimes()throws Exception{
        JSONArray business=new JSONArray();
        DataFlowContext context=stub(DataFlowContext.class,(p,m,a)->{
            if(m.getName().equals("getRequestCurrentHeaders"))return Collections.singletonMap("user_id","staff");
            if(m.getName().equals("addServiceBusiness"))business.add(a[0]);return null;
        });
        service().append(request(),context,new OwnerBMOImpl());
        assertEquals(4,business.size());
        for(int i=0;i<2;i++){
            JSONObject row=business.getJSONObject(i*2).getJSONObject("datas").getJSONArray("OwnerRoomRelPo").getJSONObject(0);
            assertEquals("new-owner",row.getString("ownerId"));assertEquals("r"+(i+1),row.getString("roomId"));
            assertEquals("2001",row.getString("state"));
            assertNotNull(row.getString("startTime"));assertEquals("2037-01-01 00:00:00",row.getString("endTime"));
            JSONObject room=business.getJSONObject(i*2+1).getJSONObject("datas").getJSONArray("RoomPo").getJSONObject(0);
            assertEquals("r"+(i+1),room.getString("roomId"));assertEquals("2001",room.getString("state"));
        }
    }
    @Test public void legacySingleRoomUsesSameLocksAndAvailabilityGuard()throws Exception{
        Memory s=service();JSONObject r=request();r.remove("roomIds");r.put("roomId","r1");
        r.remove("idCard");int[] count={0};
        s.commit(r,()->{assertTrue(s.held.contains("OWNER_CREATE_ROOM:c1:r1"));count[0]++;});
        assertEquals(1,count[0]);assertTrue(s.held.isEmpty());
        inject(s,"ownerRoomRelInnerServiceSMOImpl",stub(IOwnerRoomRelInnerServiceSMO.class,(p,m,a)->1));
        rejected(()->s.commit(r,()->count[0]++),"已绑定");
        assertEquals(1,count[0]);
    }
    @Test public void legacyOwnerWithoutRoomsDoesNotAcquireLocksOrQueryRooms()throws Exception{
        Memory s=new Memory();JSONObject r=request();r.remove("roomIds");s.validate(r);
        int[] count={0};s.commit(r,()->count[0]++);assertEquals(1,count[0]);assertTrue(s.held.isEmpty());
    }
}
