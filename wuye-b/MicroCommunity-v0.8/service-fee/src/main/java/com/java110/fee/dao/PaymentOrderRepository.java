package com.java110.fee.dao;

import com.java110.core.base.dao.BaseServiceDao;
import org.springframework.stereotype.Repository;
import java.util.*;

/** All writes use the same local SqlSession and Spring transaction. */
@Repository
public class PaymentOrderRepository extends BaseServiceDao {
    private static final String NS = "paymentOrder.";
    public Map<String,Object> find(Map<String,Object> key, boolean lock) {
        return sqlSessionTemplate.selectOne(NS + (lock ? "lockOrder" : "queryOrder"), key);
    }
    public List<Map<String,Object>> pendingV3(Map<String,Object> scope) {
        return sqlSessionTemplate.selectList(NS + "pendingV3", scope);
    }
    public List<Map<String,Object>> snapshots(Map<String,Object> scope) {
        return sqlSessionTemplate.selectList(NS + "snapshots", scope);
    }
    public boolean merchantMatches(Map<String,Object> scope) {
        Integer matches = sqlSessionTemplate.selectOne(NS + "merchantMatches", scope);
        return Integer.valueOf(1).equals(matches);
    }
    public String reservation(Map<String,Object> snapshot) {
        return sqlSessionTemplate.selectOne(NS + "reservation", snapshot);
    }
    public Map<String,Object> lockSnapshot(Map<String,Object> key) {
        return sqlSessionTemplate.selectOne(NS + "lockSnapshot", key);
    }
    public void insert(Map<String,Object> order) { write("insertOrder", order); }
    public void write(String statement, Map<String,Object> data) {
        if (sqlSessionTemplate.update(NS + statement, data) != 1) {
            throw new IllegalStateException("Payment posting write failed: " + statement);
        }
    }
}
