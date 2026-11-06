package com.java110.job.billing;

import com.alibaba.fastjson.JSONObject;
import com.java110.dto.fee.FeeConfigDto;
import com.java110.job.smo.impl.BillGenerateInnerServiceSMOImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

/** Only builds DRAFT snapshots; never approves, notifies or charges a customer. */
@Component
public class AutomaticBillWorker {
    private static final Logger logger=LoggerFactory.getLogger(AutomaticBillWorker.class);
    @Autowired private AutomaticBillQueue queue;
    @Autowired private BillGenerateInnerServiceSMOImpl generator;
    @Value("${java110.billing.autoReviewEnabled:true}") private boolean enabled;
    private ScheduledExecutorService worker;
    private long lastWarning;

    @EventListener(ApplicationReadyEvent.class) public synchronized void start() {
        if(!enabled||worker!=null)return;
        worker=Executors.newSingleThreadScheduledExecutor(new ThreadFactory(){
            public Thread newThread(Runnable r){Thread t=new Thread(r,"automatic-bill-review");t.setDaemon(true);return t;}
        });
        worker.scheduleWithFixedDelay(new Runnable(){public void run(){
            try{process();}catch(Exception failure){warn();}
        }},5,2,TimeUnit.SECONDS);
    }
    public void process() {
        for(Map<String,Object> candidate:queue.due()) {
            if(Thread.currentThread().isInterrupted())return;
            Map<String,Object> task=queue.claim(candidate);if(task==null)continue;
            try {
                FeeConfigDto input=new FeeConfigDto();
                input.setCommunityId(String.valueOf(task.get("community_id")));input.setConfigId(String.valueOf(task.get("config_id")));
                JSONObject result=generator.generateAutomatic(input);
                if(result==null||result.getInteger("code")==null)throw new IllegalStateException("Missing generation result");
                int code=result.getIntValue("code");
                if(code!=0&&code!=1)throw new IllegalStateException("Generation failed");
                JSONObject data=result.getJSONObject("data");
                if(code==0&&(data==null||data.getString("billId")==null))throw new IllegalStateException("Missing generated bill");
                queue.finish(task,data==null?null:data.getString("billId"),code==1);
            }catch(Exception failure){queue.fail(task);warn();}
        }
    }
    private void warn(){long now=System.currentTimeMillis();if(now-lastWarning>60000){lastWarning=now;logger.warn("自动账单送审暂未完成，队列保留并重试；请检查数据库迁移和费用资料");}}
    @PreDestroy public synchronized void stop(){if(worker!=null)worker.shutdownNow();}
}
