package com.java110.fee.dao;

import com.java110.core.base.dao.BaseServiceDao;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
    public boolean isOnlinePaymentDetail(Map<String,Object> scope) {
        return sqlSessionTemplate.selectOne(NS + "isOnlinePaymentDetail",scope)!=null;
    }
    /** Called while snapshot placement holds the same fee row lock as legacy collection. */
    public boolean hasCollectedOverlap(Map<String,Object> snapshot) {
        return sqlSessionTemplate.selectOne(NS + "collectedOverlap",snapshot)!=null;
    }
    public void requireLegacyWriteAllowed(Map<String,Object> scope) {
        requireTransaction();
        require(scope,"communityId");require(scope,"feeId");
        if(sqlSessionTemplate.selectOne(NS+"lockFee",scope)==null)throw new IllegalArgumentException("费用不存在");
        requireUnreserved(scope);
    }
    /** Mirrors the legacy update's feeId/bId selectors, including every row of a batch. */
    public void requireLegacyFeeUpdatesAllowed(Map<String,Object> selector) {
        requireTransaction();
        if(empty(selector.get("feeId"))&&empty(selector.get("bId")))throw new IllegalArgumentException("费用修改范围缺失");
        List<Map<String,Object>> rows=sqlSessionTemplate.selectList(NS+"lockLegacyFeeUpdates",selector);
        for(Map<String,Object> row:rows)requireUnreserved(row);
    }
    public void requireLegacyDetailUpdatesAllowed(Map<String,Object> selector) {
        requireTransaction();
        if(empty(selector.get("detailId"))&&empty(selector.get("bId")))throw new IllegalArgumentException("缴费明细修改范围缺失");
        List<Map<String,Object>> rows=sqlSessionTemplate.selectList(NS+"lockLegacyDetailUpdates",selector);
        for(Map<String,Object> row:rows) {
            requireLegacyWriteAllowed(row);
            if(isOnlinePaymentDetail(row))throw new IllegalStateException("微信账单缴费明细不可通过旧退费或编辑接口修改");
            Map<String,Object> destination=new HashMap<>(row);
            for(String key:Arrays.asList("communityId","feeId"))if(!empty(selector.get(key)))destination.put(key,selector.get(key));
            if(!Objects.equals(row.get("communityId"),destination.get("communityId"))||!Objects.equals(row.get("feeId"),destination.get("feeId")))
                requireLegacyWriteAllowed(destination);
        }
    }
    private void requireUnreserved(Map<String,Object> scope) {
        if(sqlSessionTemplate.selectOne(NS+"lockedReservation",scope)!=null)
            throw new IllegalStateException("该费用存在待确认的微信支付，请先确认支付结果或关闭订单");
    }
    private static void requireTransaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("费用写入必须处于数据库事务中");
    }
    private static void require(Map<String,Object> scope,String key){if(empty(scope.get(key)))throw new IllegalArgumentException("Missing payment field: "+key);}
    private static boolean empty(Object value){return value==null||value.toString().trim().isEmpty();}
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
