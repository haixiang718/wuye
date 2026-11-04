package com.java110.api.bmo.fee.impl;

import com.alibaba.fastjson.*;
import com.java110.dto.CommunityMemberDto;
import com.java110.dto.fee.*;
import com.java110.intf.community.ICommunityInnerServiceSMO;
import com.java110.intf.fee.IFeeInnerServiceSMO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

/** A staff publication queues a bill-scoped, resumable first notification; reads never enqueue. */
@Service
public class BillPublicationService {
    @Autowired private BillReviewService billReviewService;
    @Autowired private BillNotificationService billNotificationService;
    @Autowired private BillNotificationTaskStore billNotificationTaskStore;
    @Autowired private ICommunityInnerServiceSMO communityInnerServiceSMOImpl;
    @Autowired private IFeeInnerServiceSMO feeInnerServiceSMOImpl;
    private ScheduledExecutorService worker;

    @EventListener(ApplicationReadyEvent.class) public synchronized void startWorker(){
        if(worker!=null)return;
        worker=Executors.newSingleThreadScheduledExecutor(new ThreadFactory(){
            @Override public Thread newThread(Runnable runnable){Thread thread=new Thread(runnable,"bill-publication-notifications");thread.setDaemon(true);return thread;}
        });
        worker.scheduleWithFixedDelay(new Runnable(){
            @Override public void run(){try{processPendingTasks();}catch(Exception ignored){/* durable active index is retried on next turn */}}
        },10,1,TimeUnit.SECONDS);
    }
    @PreDestroy public void stopWorker(){if(worker!=null)worker.shutdownNow();}

    public JSONObject publishAndNotify(String community,String billId,String remark,String store,String user,boolean retry){
        if(!allowed(community,billId,store,user))return result(403,"无权操作该小区账单",null);
        JSONObject reviewed=billReviewService.publish(community,billId,remark,store,user);
        if(reviewed==null||reviewed.getInteger("code")==null||reviewed.getIntValue("code")!=0)return reviewed==null?result(500,"审核结果未知，请刷新核对",null):reviewed;
        String key=key(community,billId),nonce=UUID.randomUUID().toString();
        boolean locked=false;
        try {
            locked=billNotificationTaskStore.lock(key,nonce);
            if(!locked)return result(409,"账单已审核，通知任务正在处理，请查看通知状态",data(billId,"PUBLISHED",summary(billNotificationTaskStore.get(key),true)));
            JSONObject task=billNotificationTaskStore.get(key);
            if(task==null)task=createTask(community,billId,store,user);
            else if(!matches(task,community,billId))throw new IllegalStateException("Invalid task scope");
            else if(retry&&!active(task)){
                boolean changed=false;
                for(Object entry:task.getJSONArray("items")){
                    JSONObject item=(JSONObject)entry;
                    if("FAILED".equals(item.getString("state"))&&item.getBooleanValue("retryable")){
                        item.put("state","PENDING");item.put("requestId",UUID.randomUUID().toString());item.remove("msg");item.put("retryable",false);changed=true;
                    }
                }
                if(changed){task.put("state","QUEUED");task.put("storeId",store);task.put("userId",user);}
            }
            if(!billNotificationTaskStore.save(key,nonce,task,active(task)))throw new IllegalStateException("Task lease expired");
            return result(0,"账单已审核发布，公众号通知状态请查看通知进度",data(billId,"PUBLISHED",summary(task,true)));
        }catch(Exception failure){
            JSONObject failed=emptySummary("FAILED",true);failed.put("failed",1);
            JSONArray errors=new JSONArray();errors.add(error("","通知任务暂未创建或更新，请刷新核对后重试",true));failed.put("errors",errors);
            return result(503,"账单已审核发布，通知任务暂未确认，请查看通知状态后重试",data(billId,"PUBLISHED",failed));
        }finally{if(locked)try{billNotificationTaskStore.unlock(key,nonce);}catch(Exception ignored){}}
    }

    public JSONObject status(String community,String billId,String store,String user){
        if(!allowed(community,billId,store,user))return result(403,"无权查看该小区账单通知",null);
        try{
            BillDto bill=findBill(community,billId);
            if(bill==null)return result(404,"未找到本小区账单",null);
            JSONObject task=billNotificationTaskStore.get(key(community,billId));
            if(task!=null&&!matches(task,community,billId))throw new IllegalStateException("Invalid task scope");
            return result(0,"通知状态查询成功",data(billId,bill.getReviewState(),summary(task,"PUBLISHED".equals(bill.getReviewState())&&"T".equals(bill.getCurBill()))));
        }catch(Exception failure){return result(503,"通知状态暂时无法读取，请稍后刷新核对",null);}
    }

    private JSONObject createTask(String community,String billId,String store,String user){
        BillDto bill=findBill(community,billId);
        if(bill==null||!"PUBLISHED".equals(bill.getReviewState())||!"T".equals(bill.getCurBill()))throw new IllegalStateException("Bill changed");
        BillOweFeeDto query=new BillOweFeeDto();query.setCommunityId(community);query.setBillId(billId);query.setCurBill("T");
        List<BillOweFeeDto> rows=feeInnerServiceSMOImpl.queryBillOweFees(query);
        if(rows==null)throw new IllegalStateException("Missing details");
        Set<String> ids=new HashSet<>();JSONArray items=new JSONArray();
        for(BillOweFeeDto row:rows){
            if(row==null||!community.equals(row.getCommunityId())||!billId.equals(row.getBillId())||!identifier(row.getOweId())||!ids.add(row.getOweId()))throw new IllegalStateException("Unexpected detail scope");
            JSONObject item=new JSONObject();item.put("oweId",row.getOweId());item.put("requestId",UUID.randomUUID().toString());item.put("state","PENDING");item.put("retryable",false);items.add(item);
        }
        JSONObject task=new JSONObject();task.put("communityId",community);task.put("billId",billId);task.put("storeId",store);task.put("userId",user);task.put("items",items);task.put("state",items.isEmpty()?"COMPLETED":"QUEUED");
        return task;
    }

    /** One detail per active bill per turn, so a large bill cannot consume an HTTP request. */
    public void processPendingTasks(){
        for(String key:billNotificationTaskStore.active()){
            if(!key.startsWith(BillNotificationTaskStore.PREFIX))continue;
            String nonce=UUID.randomUUID().toString();boolean locked=false;
            try{
                locked=billNotificationTaskStore.lock(key,nonce);if(!locked)continue;
                JSONObject task=billNotificationTaskStore.get(key);
                if(task==null||!active(task))continue;
                if(!identifier(task.getString("communityId"))||!identifier(task.getString("billId"))||!key.equals(key(task.getString("communityId"),task.getString("billId"))))throw new IllegalStateException("Invalid task identity");
                JSONObject item=null;
                for(Object entry:task.getJSONArray("items")){JSONObject candidate=(JSONObject)entry;if("PENDING".equals(candidate.getString("state"))||"WORKING".equals(candidate.getString("state"))){item=candidate;break;}}
                if(item==null){finish(task);billNotificationTaskStore.save(key,nonce,task,false);continue;}
                item.put("state","WORKING");task.put("state","RUNNING");
                if(!billNotificationTaskStore.save(key,nonce,task,true))continue;
                JSONObject reply;
                try{reply=billNotificationService.sendInitial(task.getString("communityId"),item.getString("oweId"),item.getString("requestId"),task.getString("storeId"),task.getString("userId"));}
                catch(Exception failure){reply=null;}
                if(reply==null||reply.getInteger("code")==null){item.put("state","UNCERTAIN");item.put("retryable",false);item.put("msg","发送结果尚不确定，请核查微信收件情况后联系管理员，勿重复发送");}
                else {
                    int code=reply.getIntValue("code");JSONObject details=reply.getJSONObject("data");
                    boolean skipped=details!=null&&details.getBooleanValue("skipped");
                    boolean retryable=details!=null&&details.getBooleanValue("retryable");
                    item.put("state",code==0?(skipped?"SKIPPED":"ACCEPTED"):(code==202&&!retryable||code>=500?"UNCERTAIN":"FAILED"));
                    item.put("retryable","FAILED".equals(item.getString("state")));
                    item.put("msg",safeMessage(reply.getString("msg")));
                }
                finish(task);billNotificationTaskStore.save(key,nonce,task,active(task));
            }catch(Exception ignored){/* saved WORKING item resumes with its original request ID */}
            finally{if(locked)try{billNotificationTaskStore.unlock(key,nonce);}catch(Exception ignored){}}
        }
    }

    private void finish(JSONObject task){
        boolean pending=false,failed=false;
        for(Object entry:task.getJSONArray("items")){String state=((JSONObject)entry).getString("state");pending|="PENDING".equals(state)||"WORKING".equals(state);failed|="FAILED".equals(state)||"UNCERTAIN".equals(state);}
        task.put("state",pending?"RUNNING":failed?"FAILED":"COMPLETED");
    }
    private JSONObject summary(JSONObject task,boolean mayRetry){
        if(task==null)return emptySummary("NONE",mayRetry);
        JSONObject out=emptySummary(task.getString("state"),false);JSONArray errors=new JSONArray();
        JSONArray items=task.getJSONArray("items");out.put("total",items.size());
        int processed=0,accepted=0,failed=0,uncertain=0,skipped=0;boolean retry=false;
        for(Object entry:items){JSONObject item=(JSONObject)entry;String state=item.getString("state");
            if("ACCEPTED".equals(state)){processed++;accepted++;}
            else if("SKIPPED".equals(state)){processed++;skipped++;}
            else if("FAILED".equals(state)||"UNCERTAIN".equals(state)){processed++;if("FAILED".equals(state))failed++;else uncertain++;boolean can=item.getBooleanValue("retryable");retry|=can;if(errors.size()<50)errors.add(error(item.getString("oweId"),safeMessage(item.getString("msg")),can));}
        }
        out.put("processed",processed);out.put("accepted",accepted);out.put("failed",failed);out.put("uncertain",uncertain);out.put("skipped",skipped);out.put("errors",errors);out.put("canRetry",mayRetry&&!active(task)&&retry);return out;
    }
    private JSONObject emptySummary(String state,boolean retry){JSONObject out=new JSONObject();out.put("state",state);for(String name:Arrays.asList("total","processed","accepted","failed","uncertain","skipped"))out.put(name,0);out.put("errors",new JSONArray());out.put("canRetry",retry);return out;}
    private boolean allowed(String community,String billId,String store,String user){
        if(!identifier(community)||!identifier(billId)||!identifier(store)||!identifier(user)||"-1".equals(store)||"-1".equals(user))return false;
        try{
            CommunityMemberDto query=new CommunityMemberDto();query.setCommunityId(community);query.setMemberId(store);query.setMemberTypeCd(CommunityMemberDto.MEMBER_TYPE_PROPERTY);query.setAuditStatusCd(CommunityMemberDto.AUDIT_STATUS_NORMAL);
            List<CommunityMemberDto> rows=communityInnerServiceSMOImpl.getCommunityMembers(query);
            if(rows!=null)for(CommunityMemberDto row:rows)if(row!=null&&community.equals(row.getCommunityId())&&store.equals(row.getMemberId())&&CommunityMemberDto.MEMBER_TYPE_PROPERTY.equals(row.getMemberTypeCd())&&CommunityMemberDto.AUDIT_STATUS_NORMAL.equals(row.getAuditStatusCd())&&"0".equals(row.getStatusCd()))return true;
        }catch(Exception ignored){}return false;
    }
    private BillDto findBill(String community,String billId){BillDto query=new BillDto();query.setCommunityId(community);query.setBillId(billId);List<BillDto> rows=feeInnerServiceSMOImpl.queryBills(query);if(rows==null||rows.size()!=1)return null;BillDto bill=rows.get(0);return bill!=null&&community.equals(bill.getCommunityId())&&billId.equals(bill.getBillId())?bill:null;}
    private static boolean identifier(String value){return value!=null&&value.matches("[A-Za-z0-9_-]{1,30}");}
    private static boolean matches(JSONObject task,String community,String bill){return community.equals(task.getString("communityId"))&&bill.equals(task.getString("billId"));}
    private static String key(String community,String bill){return BillNotificationTaskStore.PREFIX+community+":"+bill;}
    private static boolean active(JSONObject task){return "QUEUED".equals(task.getString("state"))||"RUNNING".equals(task.getString("state"));}
    private static String safeMessage(String message){if(message==null||message.length()>300||message.matches("(?is).*(https?://|access_token|appsecret|openid|token=).*"))return "通知未完成，请核对公众号配置、微信绑定或收件情况";return message;}
    private static JSONObject error(String owe,String msg,boolean retry){JSONObject out=new JSONObject();out.put("oweId",owe);out.put("msg",msg);out.put("retryable",retry);return out;}
    private static JSONObject data(String bill,String review,JSONObject notification){JSONObject out=new JSONObject();out.put("billId",bill);out.put("reviewState",review);out.put("notification",notification);return out;}
    private static JSONObject result(int code,String msg,JSONObject data){JSONObject out=new JSONObject();out.put("code",code);out.put("msg",msg);if(data!=null)out.put("data",data);return out;}
}
