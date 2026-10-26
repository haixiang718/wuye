import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.java110.core.smo.impl.ComputeFeeSMOImpl;
import com.java110.dto.RoomDto;
import com.java110.dto.fee.*;
import com.java110.dto.owner.OwnerRoomRelDto;
import com.java110.dto.task.TaskDto;
import com.java110.job.task.fee.GenerateBillProTemplate;
import com.java110.utils.factory.ApplicationContextFactory;
import org.springframework.context.support.StaticApplicationContext;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Executes existing business code; only its service and cache boundaries are in memory. */
public class BillingWorkflowAudit {
    static final JSONArray checks = new JSONArray();
    static final SimpleDateFormat FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    static Date month(int offset) {
        Calendar c=Calendar.getInstance(); c.set(Calendar.DAY_OF_MONTH,1);
        c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);
        c.add(Calendar.MONTH,offset); return c.getTime();
    }
    static String format(Date value) { synchronized(FORMAT) { return FORMAT.format(value); } }
    static <T> T copy(T value,Class<T> type) { return JSON.parseObject(JSON.toJSONString(value),type); }
    static boolean amount(String value,String expected) { return value!=null&&new BigDecimal(value).compareTo(new BigDecimal(expected))==0; }
    static void check(String id,boolean passed,String evidence) {
        JSONObject out=new JSONObject(true);out.put("id",id);out.put("status",passed?"PASS":"ISSUE");out.put("evidence",evidence);checks.add(out);
        System.out.println((passed?"PASS ":"ISSUE ")+id+": "+evidence);
    }
    static void inject(Object target,String field,Object value)throws Exception {
        Field f=target.getClass().getDeclaredField(field);f.setAccessible(true);f.set(target,value);
    }
    static class Memory implements InvocationHandler {
        final List<FeeDto> fees=new ArrayList<FeeDto>();
        final List<BillDto> bills=new ArrayList<BillDto>();
        final List<BillOweFeeDto> details=new ArrayList<BillOweFeeDto>();
        final FeeConfigDto config=new FeeConfigDto();
        final List<FeeDetailDto> receipts=new ArrayList<FeeDetailDto>();
        boolean failHeader,failSecondFee,missingOwner,missingRoom;
        CountDownLatch race;
        Memory() {
            config.setConfigId("audit-config");config.setCommunityId("audit-community");config.setFeeName("Synthetic monthly audit");
            config.setBillType("002");config.setPaymentCycle("1");
            fees.add(fee("fee-1","room-1"));
        }
        FeeDto fee(String id,String room) {
            FeeDto fee=new FeeDto();fee.setFeeId(id);fee.setPayerObjId(room);fee.setCommunityId("audit-community");
            fee.setConfigId("audit-config");fee.setPayerObjType("3333");fee.setState("2008001");fee.setFeeFlag(FeeDto.FEE_FLAG_CYCLE);
            fee.setPaymentCycle("1");fee.setPaymentCd("1200");fee.setStartTime(month(0));fee.setEndTime(month(0));
            fee.setConfigEndTime(month(12));fee.setComputingFormula("2002");fee.setAdditionalAmount("100");fee.setSquarePrice("0");return fee;
        }
        Object proxy(Class<?> type) { return Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},this); }
        GenerateBillProTemplate generator()throws Exception {
            GenerateBillProTemplate generator=new GenerateBillProTemplate();
            for(String name:Arrays.asList("feeInnerServiceSMOImpl","feeDetailInnerServiceSMOImpl","roomInnerServiceSMOImpl","ownerRoomRelInnerServiceSMOImpl")) {
                Field f=generator.getClass().getDeclaredField(name);inject(generator,name,proxy(f.getType()));
            }
            ComputeFeeSMOImpl compute=new ComputeFeeSMOImpl();
            Field f=compute.getClass().getDeclaredField("roomInnerServiceSMOImpl");inject(compute,"roomInnerServiceSMOImpl",proxy(f.getType()));
            inject(generator,"computeFeeSMOImpl",compute);return generator;
        }
        void generate()throws Exception { generate(generator()); }
        void generate(GenerateBillProTemplate generator)throws Exception {
            Method method=generator.getClass().getDeclaredMethod("GenerateBillByFeeConfig",TaskDto.class,FeeConfigDto.class);method.setAccessible(true);
            try {method.invoke(generator,new TaskDto(),config);} catch(InvocationTargetException e) {
                if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw e;
            }
        }
        public Object invoke(Object proxy,Method method,Object[] args)throws Exception {
            String name=method.getName();
            if(name.equals("queryBills")) {
                BillDto q=(BillDto)args[0];List<BillDto> result=new ArrayList<BillDto>();
                synchronized(this) { for(BillDto b:bills)if((q.getCurBill()==null||q.getCurBill().equals(b.getCurBill()))
                    &&(q.getCurBillTime()==null||b.getBillTime().compareTo(q.getCurBillTime())>0))result.add(copy(b,BillDto.class)); }
                if(q.getCurBillTime()!=null&&race!=null) {race.countDown();if(!race.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Audit race timed out");}
                return result;
            }
            if(name.equals("queryFees"))return fees;
            if(name.equals("queryFeeDetails"))return receipts;
            if(name.equals("queryRooms")) {
                if(missingRoom)return Collections.emptyList();
                RoomDto q=(RoomDto)args[0];
                if(failSecondFee&&"room-2".equals(q.getRoomId()))throw new IllegalStateException("Synthetic second-room outage");
                RoomDto r=new RoomDto();r.setRoomId(q.getRoomId());r.setRoomType(RoomDto.ROOM_TYPE_ROOM);
                r.setFloorNum("T");r.setUnitNum("1");r.setRoomNum(q.getRoomId());r.setBuiltUpArea("90");return Collections.singletonList(r);
            }
            if(name.equals("queryOwnerRoomRels")) {
                if(missingOwner)return Collections.emptyList();
                OwnerRoomRelDto q=(OwnerRoomRelDto)args[0];OwnerRoomRelDto r=new OwnerRoomRelDto();
                r.setOwnerId("owner-"+q.getRoomId());r.setOwnerName("Synthetic owner");r.setLink("19900000999");return Collections.singletonList(r);
            }
            if(name.equals("insertBillOweFees")) { synchronized(this){details.add(copy((BillOweFeeDto)args[0],BillOweFeeDto.class));}return 1; }
            if(name.equals("insertBill")) {
                if(failHeader)throw new IllegalStateException("Synthetic bill-header failure");
                synchronized(this) {for(BillDto b:bills)b.setCurBill("F");bills.add(copy((BillDto)args[0],BillDto.class));}return 1;
            }
            throw new IllegalStateException("Unexpected audit dependency: "+name);
        }
    }
    static void baseline()throws Exception {
        Memory m=new Memory();m.generate();
        check("monthly_fixed_fee",m.bills.size()==1&&m.details.size()==1&&amount(m.details.get(0).getBillAmountOwed(),"100"),"headers="+m.bills.size()+", details="+m.details.size()+", amount="+m.details.get(0).getBillAmountOwed());
        check("owner_and_room",m.details.get(0).getOwnerId().equals("owner-room-1")&&m.details.get(0).getPayerObjName().contains("room-1"),"detail targets synthetic room-1 and its owner");
        check("deadline_matches_cycle",format(month(1)).equals(m.details.get(0).getDeadlineTime()),"deadline="+m.details.get(0).getDeadlineTime());
        check("new_bill_unnotified", "1000".equals(m.details.get(0).getState()),"state="+m.details.get(0).getState());
        check("header_matches_details",amount(m.bills.get(0).getReceivable(),m.details.get(0).getBillAmountOwed()),"header="+m.bills.get(0).getReceivable());
        boolean blocked=false;try{m.generate();}catch(Exception e){blocked=true;}
        check("sequential_monthly_rerun",blocked&&m.bills.size()==1&&m.details.size()==1,"second call blocked="+blocked+", headers="+m.bills.size()+", details="+m.details.size());
        m=new Memory();m.fees.get(0).setComputingFormula("1001");m.fees.get(0).setSquarePrice("1.5");m.fees.get(0).setAdditionalAmount("5");m.generate();
        check("area_formula",amount(m.details.get(0).getBillAmountOwed(),"140"),"90 square metres * 1.5 + 5 = "+m.details.get(0).getBillAmountOwed());
        m=new Memory();m.fees.add(m.fee("fee-2","room-2"));m.generate();
        check("multiple_rooms",m.details.size()==2&&amount(m.bills.get(0).getReceivable(),"200")&&!m.details.get(0).getOwnerId().equals(m.details.get(1).getOwnerId()),"two rooms/two owners, header="+m.bills.get(0).getReceivable());
        m=new Memory();m.fees.get(0).setStartTime(month(-1));m.fees.get(0).setEndTime(month(-1));m.generate();
        check("arrears_accumulate",amount(m.details.get(0).getBillAmountOwed(),"200")&&amount(m.details.get(0).getAmountOwed(),"100"),"total outstanding="+m.details.get(0).getBillAmountOwed()+", cycle amount="+m.details.get(0).getAmountOwed());
        m=new Memory();m.fees.get(0).setEndTime(month(1));m.generate();
        check("prepaid_fee_skipped",m.details.isEmpty(),"prepaid through next cycle; details="+m.details.size());
        m=new Memory();m.fees.get(0).setState(FeeDto.STATE_FINISH);m.generate();
        check("finished_fee_skipped",m.details.isEmpty(),"finished fee; details="+m.details.size());
        m=new Memory();m.fees.get(0).setPaymentCd("2100");m.generate();
        check("postpaid_current_cycle_skipped",m.details.isEmpty(),"postpaid current cycle; details="+m.details.size());
        m=new Memory();m.fees.get(0).setAdditionalAmount("0");m.generate();
        check("zero_fee_skipped",m.details.isEmpty(),"zero price; details="+m.details.size());
        m=new Memory();BillDto old=new BillDto();old.setBillTime(format(month(-2)));old.setCurBill("T");old.setBillId("prior-bill");m.bills.add(old);m.generate();
        check("later_cycle_rolls_current_bill",m.bills.size()==2&&"F".equals(m.bills.get(0).getCurBill())&&"T".equals(m.bills.get(1).getCurBill()),"old current flag="+m.bills.get(0).getCurBill()+", new="+m.bills.get(1).getCurBill());
    }
    static void failureChecks()throws Exception {
        Memory m=new Memory();m.failHeader=true;boolean threw=false;try{m.generate();}catch(Exception e){threw=true;}
        check("header_failure_leaves_no_orphans",threw&&m.details.isEmpty(),"failure propagated="+threw+", headers="+m.bills.size()+", persisted details="+m.details.size());
        m.failHeader=false;m.generate();
        check("retry_after_header_failure_no_duplicates",m.details.size()==1,"after retry headers="+m.bills.size()+", details="+m.details.size());
        m=new Memory();m.fees.add(m.fee("fee-2","room-2"));m.failSecondFee=true;m.generate();
        int partial=m.details.size();m.failSecondFee=false;boolean blocked=false;try{m.generate();}catch(Exception e){blocked=true;}
        check("failed_fee_can_recover_same_cycle",m.details.size()==2,"partial details="+partial+", retry blocked="+blocked+", final details="+m.details.size());
        m=new Memory();m.missingOwner=true;m.generate();
        check("missing_owner_has_no_invalid_detail",m.details.isEmpty(),"details="+m.details.size()+", owner="+(m.details.isEmpty()?"none":m.details.get(0).getOwnerId())+", oweId="+(m.details.isEmpty()?"none":m.details.get(0).getOweId()));
        m=new Memory();m.config.setBillType("005");m.fees.get(0).setFeeFlag(FeeDto.FEE_FLAG_ONCE);m.generate();
        // Existing query uses a strict greater-than comparison against now for bill type 005.
        boolean blockedOnce=false;try{m.generate();}catch(Exception e){blockedOnce=true;}
        check("one_time_fee_not_generated_twice",blockedOnce&&m.details.size()==1,"headers="+m.bills.size()+", details="+m.details.size());
        final Memory concurrent=new Memory();concurrent.race=new CountDownLatch(2);
        final GenerateBillProTemplate one=concurrent.generator(),two=concurrent.generator();ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> a=pool.submit(new Callable<Object>(){public Object call()throws Exception{concurrent.generate(one);return null;}});
            Future<?> b=pool.submit(new Callable<Object>(){public Object call()throws Exception{concurrent.generate(two);return null;}});
            a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
            check("concurrent_monthly_execution_no_duplicates",concurrent.bills.size()==1&&concurrent.details.size()==1,"concurrent headers="+concurrent.bills.size()+", details="+concurrent.details.size());
        } finally {pool.shutdownNow();}
    }
    static void cache() {
        StaticApplicationContext context=new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("jedisPool",new JedisPool(){@Override public Jedis getResource(){return new Jedis(){
            @Override public byte[] get(byte[] key){return null;}
            @Override public void close(){}
        };}});ApplicationContextFactory.setApplicationContext(context);
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected an output JSON path");
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));FORMAT.setTimeZone(TimeZone.getDefault());cache();baseline();failureChecks();
        JSONObject report=new JSONObject(true);report.put("mode","existing-business-code-with-memory-boundaries");report.put("checks",checks);
        int issues=0;for(Object item:checks)if(!"PASS".equals(((JSONObject)item).getString("status")))issues++;
        report.put("issues",issues);Files.write(Paths.get(args[0]),JSON.toJSONString(report,true).getBytes(StandardCharsets.UTF_8));
        System.out.println("Audit completed: "+checks.size()+" checks, "+issues+" issues. No WeChat API or database called.");
        System.exit(issues==0?0:1);
    }
}
