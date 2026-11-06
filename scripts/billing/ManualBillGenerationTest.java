import com.alibaba.fastjson.JSONObject;
import com.java110.dto.fee.*;
import com.java110.job.task.fee.GenerateBillProTemplate;
import com.java110.utils.util.BillCycleWindow;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real generator and fee calculator, with isolated service/cache boundaries. */
public class ManualBillGenerationTest {
    static AtomicInteger checks=new AtomicInteger();
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks.incrementAndGet(); System.out.println("PASS " + message);
    }
    static class Memory extends BillingWorkflowAudit.Memory {
        int saves, configQueries;
        boolean mismatchCommunity, mismatchConfig, inactive, missingConfig, failConfig, failFees;
        boolean failSaveWithoutWinner, zeroSave;
        String expectedType;
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
            String name=method.getName();
            if (name.equals("queryFeeConfigs")) {
                configQueries++;
                if (failConfig) throw new IllegalStateException("Config unavailable");
                FeeConfigDto q=(FeeConfigDto)args[0];
                check(config.getCommunityId().equals(q.getCommunityId()) && config.getConfigId().equals(q.getConfigId())
                    && "0".equals(q.getStatusCd()), "config lookup scoped to valid community and config");
                if (missingConfig) return Collections.emptyList();
                FeeConfigDto trusted=BillingWorkflowAudit.copy(config,FeeConfigDto.class);
                if (mismatchCommunity) trusted.setCommunityId("other-community");
                if (mismatchConfig) trusted.setConfigId("other-config");
                if (inactive) trusted.setStatusCd("1");
                return Collections.singletonList(trusted);
            }
            if (name.equals("queryFees")) {
                FeeDto q=(FeeDto)args[0];
                check(config.getCommunityId().equals(q.getCommunityId())&&config.getConfigId().equals(q.getConfigId()),
                    "fee lookup scoped to selected community and config");
                if (failFees) throw new IllegalStateException("Fee unavailable");
            }
            if (name.equals("queryBills")) {
                BillDto q=(BillDto)args[0];
                check(config.getCommunityId().equals(q.getCommunityId())&&config.getConfigId().equals(q.getConfigId()),
                    "bill lookup scoped to selected community and config");
                if (q.getCurBillTime()!=null && expectedType!=null) {
                    Date cutoff=BillCycleWindow.start(expectedType,new Date());
                    Date actual=BillingWorkflowAudit.FORMAT.parse(q.getCurBillTime());
                    check(Math.abs(cutoff.getTime()-actual.getTime())<3000,"original rolling cycle retained for "+expectedType);
                }
            }
            if (name.equals("saveBillSnapshot")) {
                saves++;
                if (failSaveWithoutWinner) throw new IllegalStateException("Unknown save outcome");
                if (zeroSave) return 0;
                BillSnapshotDto s=(BillSnapshotDto)args[0];
                check(!s.getDetails().isEmpty(),"atomic snapshot has due details");
            }
            return super.invoke(proxy,method,args);
        }
        Object service() throws Exception {
            Class<?> type;
            try { type=Class.forName("com.java110.job.smo.impl.BillGenerateInnerServiceSMOImpl"); }
            catch (ClassNotFoundException absent) { throw new AssertionError("Manual bill generation entry is missing",absent); }
            Object service=type.newInstance();
            for (String name:Arrays.asList("feeConfigInnerServiceSMOImpl")) {
                Field f=type.getDeclaredField(name);BillingWorkflowAudit.inject(service,name,proxy(f.getType()));
            }
            BillingWorkflowAudit.inject(service,"generateBillProTemplate",generator());
            return service;
        }
        JSONObject call() throws Exception {
            return call(false);
        }
        JSONObject call(boolean automatic) throws Exception {
            FeeConfigDto input=new FeeConfigDto();input.setCommunityId(config.getCommunityId());input.setConfigId(config.getConfigId());
            input.setBillType("unsupported-input");input.setPaymentCycle("999");input.setFeeName("untrusted-input");
            input.setAdditionalAmount("999999");input.setStatusCd("1");
            Object target=service();
            try { return (JSONObject)target.getClass().getMethod(automatic?"generateAutomatic":"generate",FeeConfigDto.class).invoke(target,input); }
            catch (InvocationTargetException e) { if (e.getCause() instanceof Exception) throw (Exception)e.getCause(); throw e; }
        }
    }
    static void successAndDuplicate() throws Exception {
        Memory m=new Memory();m.config.setBillType("005");m.expectedType="005";
        m.fees.get(0).setFeeFlag(FeeDto.FEE_FLAG_ONCE);m.fees.get(0).setAdditionalAmount("50");
        JSONObject first=m.call();
        check(first.getIntValue("code")==0&&!first.getJSONObject("data").getBooleanValue("existing"),"005 creates a new bill");
        check(m.saves==1&&m.bills.size()==1&&m.details.size()==1&&BillingWorkflowAudit.amount(m.bills.get(0).getReceivable(),"50"),
            "005 reuses real fee calculator for 50 yuan");
        check("DRAFT".equals(first.getJSONObject("data").getString("reviewState")),"new header explicitly remains draft");
        check("1000".equals(m.details.get(0).getState()),"new detail remains unnotified");
        m.bills.get(0).setReviewState("PUBLISHED");m.details.get(0).setState("2000");
        JSONObject second=m.call();
        check(second.getIntValue("code")==0&&second.getJSONObject("data").getBooleanValue("existing")&&m.saves==1,
            "second call returns existing snapshot without saving");
        check(first.getJSONObject("data").getString("billId").equals(second.getJSONObject("data").getString("billId"))
            &&"PUBLISHED".equals(second.getJSONObject("data").getString("reviewState"))&&"2000".equals(m.details.get(0).getState()),
            "existing published and notified bill is preserved");
        for(String type:Arrays.asList("001","002","003","004")) {
            m=new Memory();m.config.setBillType(type);m.expectedType=type;
            check(m.call().getIntValue("code")==0,"legacy type "+type+" still generates");
        }
    }
    static void supplementaryBills() throws Exception {
        Memory m=new Memory();m.config.setBillType("005");
        m.fees.get(0).setFeeFlag(FeeDto.FEE_FLAG_ONCE);m.fees.get(0).setAdditionalAmount("50");
        JSONObject first=m.call();String original=first.getJSONObject("data").getString("billId");
        m.bills.get(0).setReviewState("PUBLISHED");m.details.get(0).setState("3000");
        String published=com.alibaba.fastjson.JSON.toJSONString(m.bills.get(0));
        FeeDto extra=m.fee("fee-2","room-2");extra.setFeeFlag(FeeDto.FEE_FLAG_ONCE);extra.setAdditionalAmount("50");m.fees.add(extra);
        JSONObject second=m.call();
        check(second.getIntValue("code")==0&&!original.equals(second.getJSONObject("data").getString("billId")),
            "new fee after publication creates a separate supplementary draft");
        check(m.bills.size()==2&&m.details.size()==2&&m.saves==2,"supplement contains only one newly billed fee");
        check(published.equals(com.alibaba.fastjson.JSON.toJSONString(m.bills.get(0)))&&"3000".equals(m.details.get(0).getState()),
            "published header and notified detail remain unchanged and current");
        check("DRAFT".equals(m.bills.get(1).getReviewState())&&"1000".equals(m.details.get(1).getState())
            &&BillingWorkflowAudit.amount(m.bills.get(1).getReceivable(),"50"),"supplement awaits manual review with correct amount");
        m.details.get(1).setState("2000");
        JSONObject repeat=m.call();
        check(repeat.getJSONObject("data").getBooleanValue("existing")&&m.saves==2&&m.details.size()==2,
            "repeat excludes both notified and paid fees");
        FeeDto third=m.fee("fee-3","room-3");third.setFeeFlag(FeeDto.FEE_FLAG_ONCE);third.setAdditionalAmount("25");m.fees.add(third);
        check(m.call().getIntValue("code")==0&&m.bills.size()==3&&m.details.size()==3&&m.saves==3,
            "another new fee permits a second supplement even when first supplement is draft");
        check(m.call().getJSONObject("data").getBooleanValue("existing")&&m.saves==3,"multiple supplementary bills remain idempotent");
    }
    static void automaticReviewSafety() throws Exception {
        Memory future=new Memory();future.config.setBillType("004");
        future.fees.get(0).setFeeFlag(FeeDto.FEE_FLAG_ONCE);future.fees.get(0).setStartTime(BillingWorkflowAudit.month(1));
        future.fees.get(0).setEndTime(BillingWorkflowAudit.month(1));
        check(future.call().getIntValue("code")==1&&future.bills.isEmpty(),"future one-time fee waits until its billing start");
        for(String state:Arrays.asList("DRAFT","REJECTED","PUBLISHED")) {
            Memory m=new Memory();m.call();m.bills.get(0).setReviewState(state);
            m.bills.get(0).setBillTime(BillingWorkflowAudit.format(BillingWorkflowAudit.month(-3)));
            String old=com.alibaba.fastjson.JSON.toJSONString(m.bills.get(0));
            check(m.call().getJSONObject("data").getBooleanValue("existing")&&m.bills.size()==1,
                "unresolved old "+state+" bill is not replaced by the next automatic cycle");
            check(old.equals(com.alibaba.fastjson.JSON.toJSONString(m.bills.get(0))),"old review state and amount preserved for "+state);
            m.fees.add(m.fee("extra","room-extra"));
            check(m.call().getIntValue("code")==0&&m.bills.size()==2&&"T".equals(m.bills.get(0).getCurBill()),
                "new fee supplements unresolved old "+state+" without altering original");
        }
    }
    static void invalidFeeDoesNotBlockOtherOwners() throws Exception {
        Memory m=new Memory();m.fees.add(m.fee("bad-fee","room-2"));m.failSecondFee=true;
        JSONObject r=m.call(true);
        check(r.getIntValue("code")==202&&m.bills.size()==1&&m.details.size()==1,"automatic invoice saves valid room while retaining invalid room for retry");
        check("fee-1".equals(m.details.get(0).getFeeId())&&BillingWorkflowAudit.amount(m.bills.get(0).getReceivable(),"100"),"partial draft contains only valid fee and its correct total");
        m.failSecondFee=false;
        check(m.call(true).getIntValue("code")==0&&m.bills.size()==2&&m.details.size()==2,"correcting invalid room automatically creates separate draft without duplicating valid room");
    }
    static void paidOwnerCanEnterNextPeriodWhileAnotherOwnerIsUnpaid() throws Exception {
        Memory m=new Memory();m.fees.add(m.fee("other-fee","room-2"));m.call();
        m.bills.get(0).setReviewState("PUBLISHED");m.bills.get(0).setBillTime(BillingWorkflowAudit.format(BillingWorkflowAudit.month(-3)));
        m.details.get(0).setState("2000");m.details.get(0).setDeadlineTime(BillingWorkflowAudit.format(BillingWorkflowAudit.month(-1)));
        m.details.get(0).setFeeEndTime(BillingWorkflowAudit.format(BillingWorkflowAudit.month(-2)));
        m.fees.get(0).setStartTime(BillingWorkflowAudit.month(-2));m.fees.get(0).setEndTime(BillingWorkflowAudit.month(-1));
        JSONObject next=m.call(true);
        check(next.getIntValue("code")==0&&m.bills.size()==2&&m.details.size()==3,"paid owner gets next-period draft while another old owner remains unpaid");
        check("fee-1".equals(m.details.get(2).getFeeId())&&"T".equals(m.bills.get(0).getCurBill()),"next-period draft preserves unpaid original snapshot");
        check(m.call(true).getJSONObject("data").getBooleanValue("existing")&&m.details.size()==3,"next-period repeat is deduplicated by fee and frozen period");
    }
    static void supplementaryFailureAndConcurrency() throws Exception {
        final Memory m=new Memory();m.call();
        m.bills.get(0).setReviewState("PUBLISHED");String base=com.alibaba.fastjson.JSON.toJSONString(m.bills.get(0));
        m.fees.add(m.fee("fee-2","room-2"));m.failSaveWithoutWinner=true;
        boolean failed=false;try{m.call();}catch(Exception expected){failed=true;}
        check(failed&&m.bills.size()==1&&m.details.size()==1,"supplement failure cannot report old header as successful generation");
        m.failSaveWithoutWinner=false;m.race=new CountDownLatch(2);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Callable<JSONObject> task=new Callable<JSONObject>(){public JSONObject call()throws Exception{return m.call();}};
            Future<JSONObject> a=pool.submit(task),b=pool.submit(task);JSONObject first=a.get(20,TimeUnit.SECONDS),second=b.get(20,TimeUnit.SECONDS);
            check(first.getIntValue("code")==0&&second.getIntValue("code")==0&&m.bills.size()==2&&m.details.size()==2,
                "concurrent supplements resolve exactly one new header and detail");
            check(first.getJSONObject("data").getString("billId").equals(second.getJSONObject("data").getString("billId")),
                "concurrent supplement responses identify the same winner");
            check(base.equals(com.alibaba.fastjson.JSON.toJSONString(m.bills.get(0))),"concurrent supplement keeps original published header intact");
        }finally{pool.shutdownNow();}
    }
    static void sameSecondSiblingWinner() throws Exception {
        final Memory m=new Memory();m.call();m.fees.add(m.fee("fee-2","room-2"));m.call();
        String time=BillingWorkflowAudit.format(new Date());
        m.bills.get(0).setBillId("base");m.bills.get(0).setBillTime(time);m.details.get(0).setBillId("base");
        m.bills.get(1).setBillId("z-extra");m.bills.get(1).setBaseBillId("base");m.bills.get(1).setBillTime(time);m.details.get(1).setBillId("z-extra");
        m.fees.add(m.fee("fee-3","room-3"));m.race=new CountDownLatch(2);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Callable<JSONObject> task=new Callable<JSONObject>(){public JSONObject call()throws Exception{return m.call();}};
            Future<JSONObject> a=pool.submit(task),b=pool.submit(task);JSONObject first=a.get(20,TimeUnit.SECONDS),second=b.get(20,TimeUnit.SECONDS);
            String winner=m.details.get(2).getBillId();
            check(winner.equals(first.getJSONObject("data").getString("billId"))&&winner.equals(second.getJSONObject("data").getString("billId")),
                "same-second sibling conflict returns actual header containing newly billed fee");
        }finally{pool.shutdownNow();}
    }
    static void emptyAndInvalid() throws Exception {
        for(int mode=0;mode<5;mode++) {
            Memory m=new Memory();
            if(mode==0)m.fees.clear();
            if(mode==1)m.fees.get(0).setState(FeeDto.STATE_FINISH);
            if(mode==2)m.fees.get(0).setEndTime(BillingWorkflowAudit.month(1));
            if(mode==3)m.fees.get(0).setAdditionalAmount("0");
            if(mode==4)m.fees.get(0).setPaymentCd("2100");
            JSONObject response=m.call();
            check(response.getIntValue("code")!=0&&!response.getString("msg").isEmpty()&&m.saves==0&&m.bills.isEmpty(),
                "no due details gives meaningful non-success without empty header, case "+mode);
            m.generate();
            check(m.saves==0&&m.bills.isEmpty(),"original scheduled generator also skips empty header, case "+mode);
        }
        for(int mode=0;mode<5;mode++) {
            Memory m=new Memory();
            if(mode==0)m.mismatchCommunity=true;
            if(mode==1)m.mismatchConfig=true;
            if(mode==2)m.inactive=true;
            if(mode==3)m.missingConfig=true;
            if(mode==4)m.config.setBillType("INVALID");
            boolean blocked=false;try{blocked=m.call().getIntValue("code")!=0;}catch(Exception expected){blocked=true;}
            check(blocked&&m.saves==0,"untrusted or unsupported config rejected, case "+mode);
        }
    }
    static void failures() throws Exception {
        for(int mode=0;mode<8;mode++) {
            Memory m=new Memory();
            if(mode==0)m.failHeader=true;
            if(mode==1)m.failConfig=true;
            if(mode==2)m.failFees=true;
            if(mode==3)m.missingOwner=true;
            if(mode==4)m.failSaveWithoutWinner=true;
            if(mode==5)m.zeroSave=true;
            if(mode==6)m.fees.get(0).setCommunityId("other-community");
            if(mode==7){m.fees.add(m.fee("fee-2","room-2"));m.failSecondFee=true;}
            boolean failed=false;try{failed=m.call().getIntValue("code")!=0;}catch(Exception expected){failed=true;}
            check(failed&&m.bills.isEmpty()&&m.details.isEmpty(),"service failure cannot report success or persist partial bill, case "+mode);
        }
    }
    static void concurrency() throws Exception {
        final Memory m=new Memory();m.race=new CountDownLatch(2);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Callable<JSONObject> task=new Callable<JSONObject>() { public JSONObject call()throws Exception{return m.call();} };
            Future<JSONObject> a=pool.submit(task),b=pool.submit(task);
            JSONObject one=a.get(20,TimeUnit.SECONDS),two=b.get(20,TimeUnit.SECONDS);
            check(one.getIntValue("code")==0&&two.getIntValue("code")==0&&m.bills.size()==1&&m.details.size()==1,
                "concurrent calls persist one atomic bill and resolve existing winner");
            check(one.getJSONObject("data").getBooleanValue("existing")!=two.getJSONObject("data").getBooleanValue("existing"),
                "concurrent loser explicitly returns winner as existing");
        }finally{pool.shutdownNow();}
    }
    public static void main(String[] args)throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));BillingWorkflowAudit.cache();
        automaticReviewSafety();
        invalidFeeDoesNotBlockOtherOwners();
        paidOwnerCanEnterNextPeriodWhileAnotherOwnerIsUnpaid();
        supplementaryBills();supplementaryFailureAndConcurrency();sameSecondSiblingWinner();successAndDuplicate();emptyAndInvalid();failures();concurrency();
        System.out.println("Manual bill generation: "+checks+" checks passed. No database or notification calls.");
    }
}
