package com.java110.job.billing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** Small durable queue; fee triggers commit an event together with the fee row. */
@Component
public class AutomaticBillQueue {
    @Autowired private JdbcTemplate jdbc;

    public Map<String,Object> status(String community) {
        return jdbc.queryForMap("SELECT COALESCE(SUM(task_state='FAILED'),0) failed,"
            + "COALESCE(SUM(task_state='WAITING'),0) waiting,"
            + "COALESCE(SUM(task_state<>'FAILED' AND (event_version>processed_version OR task_state='PROCESSING')),0) pending "
            + "FROM automatic_bill_queue WHERE community_id=?",community);
    }

    public List<Map<String,Object>> due() {
        return jdbc.queryForList("SELECT * FROM automatic_bill_queue WHERE next_run<=NOW(3) "
            + "AND (lease_until IS NULL OR lease_until<NOW(3)) ORDER BY next_run LIMIT 10");
    }
    public Map<String,Object> claim(Map<String,Object> row) {
        String token=UUID.randomUUID().toString();
        int updated=jdbc.update("UPDATE automatic_bill_queue SET lease_token=?,lease_until=DATE_ADD(NOW(3),INTERVAL 2 MINUTE),"
            + "task_state='PROCESSING' WHERE community_id=? AND config_id=? AND event_version=? "
            + "AND next_run<=NOW(3) AND (lease_until IS NULL OR lease_until<NOW(3))",
            token,row.get("community_id"),row.get("config_id"),row.get("event_version"));
        if(updated!=1)return null;
        Map<String,Object> task=new HashMap<>(row);task.put("lease_token",token);return task;
    }
    public void finish(Map<String,Object> task,String billId,boolean waiting) {
        jdbc.update("UPDATE automatic_bill_queue SET processed_version=?,lease_token=NULL,lease_until=NULL,attempts=0,last_error=NULL,"
            + "result_bill_id=?,task_state=IF(event_version>?, 'QUEUED', ?),"
            + "next_run=IF(event_version>?,NOW(3),DATE_ADD(CURRENT_DATE,INTERVAL 1 DAY)) "
            + "WHERE community_id=? AND config_id=? AND lease_token=?",
            task.get("event_version"),billId,task.get("event_version"),waiting?"WAITING":"READY",task.get("event_version"),
            task.get("community_id"),task.get("config_id"),task.get("lease_token"));
    }
    public void fail(Map<String,Object> task) {
        jdbc.update("UPDATE automatic_bill_queue SET lease_token=NULL,lease_until=NULL,attempts=attempts+1,"
            + "task_state='FAILED',last_error='自动送审暂未完成，请核对费用、计费日期和业主资料',"
            + "next_run=DATE_ADD(NOW(3),INTERVAL 30 SECOND) WHERE community_id=? AND config_id=? AND lease_token=?",
            task.get("community_id"),task.get("config_id"),task.get("lease_token"));
    }
}
