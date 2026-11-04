package com.java110.front.smo.payment.v3;

import com.alibaba.fastjson.JSONObject;
import com.java110.intf.fee.IFeePaymentOrderInnerServiceSMO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.util.*;

/** Coordinates authenticated provider evidence with the existing atomic accounting boundary. */
@Service
public class WechatPayV3Service {
    private static final Logger LOG=LoggerFactory.getLogger(WechatPayV3Service.class);
    @Autowired private IFeePaymentOrderInnerServiceSMO orders;
    @Value("${java110.payment.v3.config:}") private String configFile="";
    private WechatPayV3Provider injectedProvider;
    private WechatPayV3Settings injectedSettings;
    private volatile ClientSnapshot cached;
    private String pendingCursor="";
    private final Object recoveryLock=new Object();

    public WechatPayV3Service() { }
    /** Isolated offline tests supply a synthetic provider; Spring uses the no-argument constructor. */
    public WechatPayV3Service(IFeePaymentOrderInnerServiceSMO orders,WechatPayV3Provider provider,WechatPayV3Settings settings) {
        this.orders=orders;this.injectedProvider=provider;this.injectedSettings=settings;
    }
    private static class ClientSnapshot {
        final byte[] bytes;final WechatPayV3Settings settings;final WechatPayV3Provider provider;
        ClientSnapshot(byte[] bytes,WechatPayV3Settings settings,WechatPayV3Provider provider){this.bytes=bytes;this.settings=settings;this.provider=provider;}
    }
    public boolean configured(){return injectedSettings!=null || (configFile!=null&&!configFile.trim().isEmpty());}
    private synchronized ClientSnapshot client() {
        if(injectedSettings!=null)return new ClientSnapshot(new byte[0],injectedSettings,injectedProvider);
        if(!configured())throw new IllegalStateException("微信支付尚未配置");
        try {
            Path path=Paths.get(configFile);
            byte[] bytes=Files.readAllBytes(path);
            if(cached==null || !Arrays.equals(cached.bytes,bytes)) {
                WechatPayV3Settings settings=WechatPayV3Settings.load(path);
                WechatPayV3Provider provider=new WechatPayV3Client(settings);
                cached=new ClientSnapshot(bytes,settings,provider);
            }
            return cached;
        } catch(Exception invalid) { throw new IllegalStateException("微信支付配置不可用"); }
    }
    public void requireNewPayment(String userId,String appId,String mchId) {
        ClientSnapshot c=client();
        if(!c.settings.permitsUser(userId))throw new SecurityException("当前账号尚未开放微信支付");
        if(!Objects.equals(c.settings.appId(),appId)||!Objects.equals(c.settings.mchId(),mchId))throw new SecurityException("支付商户与项目配置不一致");
    }
    public long maximumTotalFee(){return client().settings.maximumTotalFee();}
    public Map<String,String> prepay(Map<String,Object> order,String description,String openId,String userId) {
        ClientSnapshot c=client();
        requireNewPayment(userId,value(order,"appId"),value(order,"mchId"));
        verifyOrder(order,c.settings);
        if(!userId.equals(value(order,"userId"))||!"CREATED".equals(value(order,"state")))throw new SecurityException("订单归属或状态不正确");
        if(openId==null||openId.trim().isEmpty())throw new SecurityException("微信支付身份缺失");
        if(positiveInteger(order.get("totalFee"))>c.settings.maximumTotalFee())throw new SecurityException("本次联调只开放指定的小额测试账单");
        return c.provider.prepay(description,value(order,"orderId"),positiveInteger(order.get("totalFee")),openId);
    }
    public Map<String,Object> notification(String raw,Map<String,String> headers) {
        ClientSnapshot c=client();
        JSONObject transaction=c.provider.notification(raw,headers);
        Map<String,Object> order=lookup(transaction.getString("out_trade_no"));
        verifyOrder(order,c.settings);
        if(!"SUCCESS".equals(transaction.getString("trade_state")))throw new IllegalArgumentException("支付通知状态无效");
        return applyTransaction(c,order,transaction);
    }
    public Map<String,Object> query(String userId,String orderId) {
        Map<String,Object> order=owned(userId,orderId);
        return reconcile(client(),order);
    }
    public Map<String,Object> close(String userId,String orderId) {
        Map<String,Object> order=owned(userId,orderId);
        ClientSnapshot c=client();
        Map<String,Object> status=reconcile(c,order);
        if(!"CREATED".equals(status.get("state")))return status;
        String state=String.valueOf(status.get("providerState"));
        if(!Arrays.asList("NOTPAY","USERPAYING").contains(state))return status;
        c.provider.close(orderId);
        // A provider close response alone is insufficient: query signed CLOSED or SUCCESS.
        return reconcile(c,lookup(orderId));
    }
    private Map<String,Object> owned(String userId,String id) {
        if(userId==null||userId.trim().isEmpty()||"-1".equals(userId))throw new SecurityException("请先登录");
        Map<String,Object> order=lookup(id);
        if(!userId.equals(value(order,"userId")))throw new SecurityException("无权访问此支付订单");
        verifyOrder(order,client().settings);
        return order;
    }
    private Map<String,Object> lookup(String id) {
        if(id==null||!id.matches("[a-zA-Z0-9_-]{1,32}"))throw new IllegalArgumentException("支付订单号无效");
        Map<String,Object> key=new HashMap<>();key.put("orderId",id);
        Map<String,Object> order=orders.query(key);
        if(order==null)throw new IllegalArgumentException("支付订单不存在");
        return order;
    }
    private Map<String,Object> reconcile(ClientSnapshot c,Map<String,Object> order) {
        verifyOrder(order,c.settings);
        if(Arrays.asList("POSTED","CLOSED").contains(value(order,"state")))return status(order,null);
        if(!"CREATED".equals(value(order,"state")))throw new IllegalStateException("支付订单状态无效");
        return applyTransaction(c,order,c.provider.query(value(order,"orderId")));
    }
    private Map<String,Object> applyTransaction(ClientSnapshot c,Map<String,Object> order,JSONObject tx) {
        verifyOrder(order,c.settings);
        for(String[] field:new String[][]{{"appid","appId"},{"mchid","mchId"},{"out_trade_no","orderId"}}) {
            if(!Objects.equals(value(order,field[1]),tx.getString(field[0])))throw new SecurityException("微信交易归属不匹配");
        }
        String providerState=tx.getString("trade_state");
        // WeChat omits trade_type on genuinely unpaid/closed orders. It is mandatory for posting.
        String tradeType=tx.getString("trade_type");
        if(("SUCCESS".equals(providerState)||tradeType!=null)&&!"JSAPI".equals(tradeType))throw new IllegalArgumentException("微信交易类型不匹配");
        JSONObject amount=tx.getJSONObject("amount");
        // Signed CLOSED responses may omit amount entirely. Their scoped order identity
        // above is sufficient to release a reservation, never to post a payment.
        if(!("CLOSED".equals(providerState)&&amount==null)
            &&(amount==null||!"CNY".equals(amount.getString("currency"))||positiveInteger(amount.get("total"))!=positiveInteger(order.get("totalFee"))))throw new IllegalArgumentException("微信交易金额或币种不匹配");
        Map<String,Object> evidence=new HashMap<>();
        for(String field:Arrays.asList("orderId","appId","mchId","orderType"))evidence.put(field,order.get(field));
        if("SUCCESS".equals(providerState)) {
            String transaction=tx.getString("transaction_id");
            if(transaction==null||!transaction.matches("[a-zA-Z0-9_-]{1,64}"))throw new IllegalArgumentException("微信交易号缺失或无效");
            evidence.put("transactionId",transaction);evidence.put("totalFee",positiveInteger(order.get("totalFee")));
            Map<String,Object> posted=orders.confirm(evidence);
            requireResult(posted,"POSTED");
            return status(posted,providerState);
        }
        if("CLOSED".equals(providerState)) {
            evidence.put("providerState","CLOSED");
            Map<String,Object> closed=orders.close(evidence);
            requireResult(closed,"CLOSED");
            return status(closed,providerState);
        }
        if(!Arrays.asList("NOTPAY","USERPAYING","PAYERROR","REFUND","REVOKED").contains(providerState))throw new IllegalArgumentException("微信交易状态无效");
        // Unknown/failed payment states never release local reservations or imply a refund.
        return status(order,providerState);
    }
    private static void requireResult(Map<String,Object> result,String state) {
        if(result==null||!"0".equals(String.valueOf(result.get("code")))||!state.equals(result.get("state")))throw new IllegalStateException("支付结果尚未完成入账，请重试查单");
    }
    private static void verifyOrder(Map<String,Object> order,WechatPayV3Settings settings) {
        if(order==null||!"V3".equals(value(order,"providerVersion"))||!Objects.equals(settings.appId(),value(order,"appId"))||!Objects.equals(settings.mchId(),value(order,"mchId")))throw new SecurityException("支付订单商户或接口版本不匹配");
    }
    private static Map<String,Object> status(Map<String,Object> order,String providerState) {
        Map<String,Object> result=new HashMap<>();result.put("code",0);result.put("orderId",order.get("orderId"));result.put("state",order.get("state"));
        if(providerState!=null)result.put("providerState",providerState);
        return result;
    }
    private static String value(Map<String,Object> object,String key){Object v=object.get(key);return v==null?null:v.toString();}
    private static long positiveInteger(Object amount) {
        if(amount==null||!amount.toString().matches("[1-9][0-9]{0,8}"))throw new IllegalArgumentException("支付金额格式无效");
        return Long.parseLong(amount.toString());
    }
    @Scheduled(initialDelay=60000,fixedDelay=60000)
    public void reconcilePending() {
        // The batch may wait on network I/O. Never hold the client/config monitor here.
        synchronized(recoveryLock) { reconcileBatch(); }
    }
    private void reconcileBatch() {
        if(!configured())return;
        ClientSnapshot c=client();
        Map<String,Object> scope=new HashMap<>();scope.put("appId",c.settings.appId());scope.put("mchId",c.settings.mchId());scope.put("limit",50);scope.put("afterOrderId",pendingCursor);
        List<Map<String,Object>> pending=orders.pendingV3(scope);
        if(pending.isEmpty()){pendingCursor="";return;}
        int failed=0;
        for(Map<String,Object> order:pending) {
            try{reconcile(c,order);}catch(Exception retryable){failed++;}
            // Advance even for unresolved orders so they cannot starve later payments.
            pendingCursor=value(order,"orderId");
        }
        if(failed>0)LOG.warn("WeChat V3 recovery: {} pending orders require another query; no reservations released",failed);
    }
}
