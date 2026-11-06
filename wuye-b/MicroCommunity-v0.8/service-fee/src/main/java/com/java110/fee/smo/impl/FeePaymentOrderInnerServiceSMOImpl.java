package com.java110.fee.smo.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.java110.fee.dao.PaymentOrderRepository;
import com.java110.intf.fee.IFeePaymentOrderInnerServiceSMO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.*;

/** Published snapshot charging, with the posting marker committed with every accounting write. */
@RestController
public class FeePaymentOrderInnerServiceSMOImpl implements IFeePaymentOrderInnerServiceSMO {
    @Autowired private PaymentOrderRepository paymentOrderRepository;

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> create(@RequestBody Map<String,Object> request) {
        JSONObject input = new JSONObject(new HashMap<>(request));
        String providerVersion = input.containsKey("providerVersion") ? required(input,"providerVersion") : "V2";
        if (!Arrays.asList("V2", "V3").contains(providerVersion)) throw new IllegalArgumentException("Invalid provider version");
        input.put("providerVersion",providerVersion);
        // Trusted caller policy only: never accept a browser-controlled collection cap.
        String requestedCap = input.containsKey("maxTotalFee") ? required(input,"maxTotalFee") : "999999999";
        if (!requestedCap.matches("[1-9][0-9]*")) throw new IllegalArgumentException("Invalid collection amount cap");
        java.math.BigInteger maxTotalFee = new java.math.BigInteger(requestedCap);
        for (String key : Arrays.asList("communityId", "userId", "appId", "mchId", "orderType")) required(input, key);
        if (!Arrays.asList("SINGLE", "ARREARS").contains(input.getString("orderType"))) throw new IllegalArgumentException("Invalid payment type");
        if (empty(input.getString("feeId")) && empty(input.getString("roomId"))) throw new IllegalArgumentException("Payment scope missing");
        Set<String> selected = null;
        if ("ARREARS".equals(input.getString("orderType"))) {
            required(input,"roomId");
            selected = selectedOwes(input.get("oweIds"));
            input.put("oweIds",new ArrayList<>(selected));
        } else input.remove("oweIds");
        if (!paymentOrderRepository.merchantMatches(input)) throw new IllegalArgumentException("Merchant does not match community");
        List<Map<String,Object>> snapshots = paymentOrderRepository.snapshots(input);
        if (snapshots.isEmpty()) throw new IllegalArgumentException("No published unpaid bill");
        if (selected != null) {
            Set<String> matched = new HashSet<>();
            for (Map<String,Object> snapshot : snapshots) matched.add(String.valueOf(snapshot.get("oweId")));
            if (!selected.equals(matched) || snapshots.size() != selected.size()) throw new IllegalArgumentException("所选账单已变化或无权支付，请刷新后重新选择");
        }
        if ("SINGLE".equals(input.getString("orderType")) && snapshots.size() != 1) throw new IllegalArgumentException("Select one published bill");
        long total = 0;
        Set<String> fees = new HashSet<>();
        Set<String> reservations = new HashSet<>();
        String payer = null;
        for (Map<String,Object> row : snapshots) {
            JSONObject fee = new JSONObject(row);
            if (!fees.add(required(fee, "feeId"))) throw new IllegalStateException("Multiple current snapshots for fee");
            total = Math.addExact(total, cents(fee.getString("billAmountOwed")));
            row.put("feePrice", fee.getString("billAmountOwed"));
            cycles(required(fee,"feeEndTime"),required(fee,"deadlineTime"));
            String object = required(fee,"payerObjType") + ":" + required(fee,"payerObjId");
            if(payer!=null && !payer.equals(object))throw new IllegalArgumentException("One payment must belong to one payer object");
            payer=object;
            String reserved=paymentOrderRepository.reservation(row);
            if(reserved!=null)reservations.add(reserved);
        }
        // fee_receipt.amount is DECIMAL(9,2); reject overflow before a provider can collect money.
        if (total <= 0 || total > 999999999L) throw new IllegalArgumentException("Payment amount exceeds receipt limits");
        if (java.math.BigInteger.valueOf(total).compareTo(maxTotalFee) > 0) throw new IllegalArgumentException("Payment amount exceeds collection cap");
        if(!reservations.isEmpty()) {
            if(reservations.size()!=1)throw new IllegalStateException("Overlapping pending payments exist");
            Map<String,Object> key=new HashMap<>();key.put("orderId",reservations.iterator().next());
            Map<String,Object> previous=paymentOrderRepository.find(key,false);
            if(previous==null)throw new IllegalStateException("Missing reserved payment order");
            JSONObject existing=new JSONObject(previous);
            Set<String> oldOwes=new HashSet<>(),newOwes=new HashSet<>();
            Map<String,JSONObject> oldSnapshots=new HashMap<>();
            for(JSONObject fee:JSON.parseArray(existing.getString("payload"),JSONObject.class)) {
                oldOwes.add(fee.getString("oweId"));oldSnapshots.put(fee.getString("oweId"),fee);
            }
            for(Map<String,Object> fee:snapshots)newOwes.add(String.valueOf(fee.get("oweId")));
            if(!newOwes.equals(oldOwes) || total!=existing.getLongValue("totalFee") || !"CREATED".equals(existing.getString("state")))throw new IllegalStateException("An overlapping payment must be resolved first");
            for(String field:Arrays.asList("userId","communityId","appId","mchId","orderType","providerVersion"))if(!input.getString(field).equals(existing.getString(field)))throw new IllegalStateException("An overlapping payment must be resolved first");
            for(Map<String,Object> row:snapshots) {
                JSONObject live=new JSONObject(row),frozen=oldSnapshots.get(live.getString("oweId"));
                for(String field:Arrays.asList("oweId","billId","feeId","communityId","ownerId","payerObjId","payerObjType",
                        "feeEndTime","deadlineTime","feeName","ownerName","payerObjName")) {
                    if(!Objects.equals(frozen.getString(field),live.getString(field)))throw new IllegalStateException("Pending payment snapshot changed");
                }
                if(cents(frozen.getString("billAmountOwed"))!=cents(live.getString("billAmountOwed")))throw new IllegalStateException("Pending payment amount changed");
            }
            return previous;
        }
        Map<String,Object> order = new HashMap<>();
        order.put("orderId", UUID.randomUUID().toString().replace("-", ""));
        for (String key : Arrays.asList("communityId", "userId", "appId", "mchId", "orderType")) order.put(key, input.getString(key));
        order.put("providerVersion", providerVersion);
        order.put("totalFee", total); order.put("state", "CREATED");
        order.put("payload", JSON.toJSONString(snapshots));
        paymentOrderRepository.insert(order);
        for(Map<String,Object> snapshot:snapshots) {
            snapshot.put("orderId",order.get("orderId"));
            paymentOrderRepository.write("reserveSnapshot",snapshot);
            paymentOrderRepository.write("reserveFee",snapshot);
        }
        return order;
    }

    @Override
    public Map<String,Object> query(@RequestBody Map<String,Object> request) {
        required(new JSONObject(request), "orderId");
        Map<String,Object> order = paymentOrderRepository.find(request, false);
        if (order == null) throw new IllegalArgumentException("Unknown payment order");
        return order;
    }

    @Override
    public List<Map<String,Object>> pendingV3(@RequestBody Map<String,Object> request) {
        JSONObject input = new JSONObject(new HashMap<>(request));
        required(input,"appId"); required(input,"mchId");
        if (input.containsKey("afterOrderId")) {
            Object cursor = input.get("afterOrderId");
            if (!(cursor instanceof String) || !((String)cursor).matches("[A-Za-z0-9_-]{0,32}")) {
                throw new IllegalArgumentException("Invalid pending order cursor");
            }
        }
        String requested = input.containsKey("limit") ? required(input,"limit") : "50";
        if (!requested.matches("[1-9][0-9]*")) throw new IllegalArgumentException("Invalid pending order limit");
        input.put("limit",new java.math.BigInteger(requested).min(java.math.BigInteger.valueOf(50)).intValue());
        return paymentOrderRepository.pendingV3(input);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> close(@RequestBody Map<String,Object> request) {
        JSONObject input = new JSONObject(request);
        required(input,"orderId"); required(input,"appId"); required(input,"mchId");
        if (!"CLOSED".equals(required(input,"providerState"))) throw new IllegalArgumentException("Provider has not confirmed close");
        Map<String,Object> stored = paymentOrderRepository.find(request,true);
        if (stored == null) throw new IllegalArgumentException("Unknown payment order");
        JSONObject order = new JSONObject(stored);
        for (String field : Arrays.asList("appId","mchId")) {
            if (!required(order,field).equals(input.getString(field))) throw new IllegalArgumentException("Payment ownership mismatch");
        }
        if (!"V3".equals(order.getString("providerVersion"))) throw new IllegalArgumentException("Only V3 orders may be closed");
        String state = order.getString("state");
        if (!"CLOSED".equals(state)) {
            if (!"CREATED".equals(state)) throw new IllegalArgumentException("Invalid payment state for close");
            List<JSONObject> fees = JSON.parseArray(order.getString("payload"),JSONObject.class);
            fees.sort(Comparator.comparing(f -> f.getString("oweId")));
            for (JSONObject fee : fees) {
                fee.put("communityId",order.getString("communityId"));
                fee.put("orderId",order.getString("orderId"));
                // Serialize release with placement's snapshot locks; history rows remain immutable.
                paymentOrderRepository.lockSnapshot(fee);
                paymentOrderRepository.write("releaseFee",fee);
            }
            paymentOrderRepository.write("markClosed",request);
        }
        Map<String,Object> result = new HashMap<>();
        result.put("code",0); result.put("state","CLOSED"); result.put("orderId",order.getString("orderId"));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> confirm(@RequestBody Map<String,Object> request) {
        JSONObject paid = new JSONObject(request);
        required(paid, "orderId"); required(paid, "transactionId");
        if (!required(paid,"totalFee").matches("[1-9][0-9]*")) throw new IllegalArgumentException("Invalid paid amount");
        Map<String,Object> stored = paymentOrderRepository.find(request, true);
        if (stored == null) throw new IllegalArgumentException("Unknown payment order");
        JSONObject order = new JSONObject(stored);
        for (String key : Arrays.asList("appId", "mchId", "orderType")) {
            if (!required(order,key).equals(required(paid,key))) throw new IllegalArgumentException("Payment ownership mismatch");
        }
        if (order.getLongValue("totalFee") != paid.getLongValue("totalFee") || paid.getLongValue("totalFee") <= 0) throw new IllegalArgumentException("Payment amount mismatch");
        if ("POSTED".equals(order.getString("state"))) {
            if (!paid.getString("transactionId").equals(order.getString("transactionId"))) throw new IllegalArgumentException("Payment transaction mismatch");
            return success(order.getString("orderId"));
        }
        if (!"CREATED".equals(order.getString("state"))) throw new IllegalArgumentException("Invalid payment state");
        // Unique transaction_id is reserved before posting. A rollback releases it for a retry.
        paymentOrderRepository.write("reserveTransaction", request);
        List<JSONObject> fees = JSON.parseArray(order.getString("payload"), JSONObject.class);
        fees.sort(Comparator.comparing(f -> f.getString("oweId")));
        String receiptId = id();
        long sum = 0;
        for (JSONObject fee : fees) {
            fee.put("communityId", order.getString("communityId"));
            Map<String,Object> current = paymentOrderRepository.lockSnapshot(fee);
            if (current == null) throw new IllegalStateException("Published bill is no longer payable");
            JSONObject live = new JSONObject(current);
            for (String key : Arrays.asList("feeId","billId","feeEndTime","deadlineTime")) {
                if (!Objects.equals(fee.getString(key),live.getString(key))) throw new IllegalStateException("Bill snapshot changed");
            }
            if (cents(fee.getString("billAmountOwed")) != cents(live.getString("billAmountOwed"))) throw new IllegalStateException("Bill amount changed");
            sum = Math.addExact(sum, cents(fee.getString("billAmountOwed")));
            fee.put("detailId", id()); fee.put("receiptId", receiptId);
            fee.put("orderId", order.getString("orderId"));
            fee.put("cycles", cycles(fee.getString("feeEndTime"),fee.getString("deadlineTime")));
            paymentOrderRepository.write("insertDetail", fee);
            paymentOrderRepository.write("insertReceiptDetail", fee);
            paymentOrderRepository.write("advanceFee", fee);
            paymentOrderRepository.write("settleSnapshot", fee);
            paymentOrderRepository.write("increaseBillReceipts", fee);
            paymentOrderRepository.write("releaseFee", fee);
        }
        if (sum != order.getLongValue("totalFee")) throw new IllegalStateException("Stored payment total mismatch");
        JSONObject receipt = fees.get(0);
        receipt.put("receiptId", receiptId); receipt.put("amount", BigDecimal.valueOf(sum,2).toPlainString());
        paymentOrderRepository.write("insertReceipt", receipt);
        paymentOrderRepository.write("markPosted", request);
        return success(order.getString("orderId"));
    }

    private static Map<String,Object> success(String id) {
        Map<String,Object> result = new HashMap<>(); result.put("code",0);result.put("state","POSTED");result.put("orderId",id);return result;
    }
    private static String id() { return UUID.randomUUID().toString().replace("-", "").substring(0,30); }
    private static Set<String> selectedOwes(Object value) {
        if (!(value instanceof List) || ((List<?>)value).isEmpty() || ((List<?>)value).size() > 100)
            throw new IllegalArgumentException("请选择1至100项账单后缴费");
        Set<String> selected = new LinkedHashSet<>();
        for (Object id : (List<?>)value) {
            if (!(id instanceof String) || !((String)id).matches("[A-Za-z0-9_-]{1,30}") || !selected.add((String)id))
                throw new IllegalArgumentException("所选账单编号无效或重复，请重新选择");
        }
        return selected;
    }
    private static String required(JSONObject data,String key) {
        String value=data.getString(key);if(empty(value))throw new IllegalArgumentException("Missing payment field: " + key);return value;
    }
    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }
    private static String cycles(String start,String end) {
        java.time.format.DateTimeFormatter format=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        java.time.LocalDateTime from=java.time.LocalDateTime.parse(start,format),to=java.time.LocalDateTime.parse(end,format);
        if(!to.isAfter(from))throw new IllegalArgumentException("Invalid published bill period");
        long months=java.time.temporal.ChronoUnit.MONTHS.between(from,to);
        java.time.LocalDateTime anchor=from.plusMonths(months);
        BigDecimal fraction=BigDecimal.valueOf(java.time.temporal.ChronoUnit.SECONDS.between(anchor,to)).divide(
                BigDecimal.valueOf(java.time.temporal.ChronoUnit.SECONDS.between(anchor,anchor.plusMonths(1))),6,java.math.RoundingMode.HALF_UP);
        BigDecimal cycles=BigDecimal.valueOf(months).add(fraction).setScale(2,java.math.RoundingMode.HALF_UP);
        if(cycles.signum()<=0 || cycles.compareTo(new BigDecimal("9999.99"))>0)throw new IllegalArgumentException("Bill period exceeds receipt limits");
        return cycles.toPlainString();
    }
    private static long cents(String value) {
        if(empty(value))throw new IllegalArgumentException("Missing bill amount");
        long cents = new BigDecimal(value).movePointRight(2).longValueExact();
        if(cents<=0)throw new IllegalArgumentException("Invalid bill amount");return cents;
    }
}
