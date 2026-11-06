package com.java110.fee.dao;

import com.java110.core.base.dao.BaseServiceDao;
import org.springframework.stereotype.Repository;
import java.util.*;

/** Actual paid fee details; no dependence on outstanding bills or optional owner fee attributes. */
@Repository
public class OwnerPaymentHistoryRepository extends BaseServiceDao {
    private static final String NS = "OwnerPaymentHistory.";

    public Map<String,Object> history(Map<String,Object> input) {
        Map<String,Object> scope = new HashMap<>();
        for (String key : Arrays.asList("userId","communityId")) {
            Object value = input.get(key);
            if (value == null || value.toString().trim().isEmpty()) throw new IllegalArgumentException("Missing history scope");
            scope.put(key,value.toString());
        }
        int page = Integer.parseInt(String.valueOf(input.getOrDefault("page",1)));
        int row = Integer.parseInt(String.valueOf(input.getOrDefault("row",30)));
        long offset = (long)(page - 1) * row;
        if (page < 1 || row < 1 || row > 100 || offset > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid history pagination");
        scope.put("offset",(int)offset); scope.put("row",row);
        if (input.get("feeId") != null && !input.get("feeId").toString().isEmpty()) scope.put("feeId",input.get("feeId").toString());
        Number total = sqlSessionTemplate.selectOne(NS + "count",scope);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("code",0); result.put("total",total.intValue()); result.put("records",(total.longValue() + row - 1) / row);
        result.put("feeDetails",sqlSessionTemplate.selectList(NS + "list",scope));
        return result;
    }
}
