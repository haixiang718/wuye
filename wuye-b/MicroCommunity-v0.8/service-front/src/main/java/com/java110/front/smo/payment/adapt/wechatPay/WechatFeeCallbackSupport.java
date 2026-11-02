package com.java110.front.smo.payment.adapt.wechatPay;

import com.alibaba.fastjson.*;
import com.java110.core.factory.WechatFactory;
import com.java110.dto.smallWeChat.SmallWeChatDto;
import com.java110.intf.fee.IFeePaymentOrderInnerServiceSMO;
import com.java110.utils.constant.*;
import com.java110.utils.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.client.*;
import java.util.*;

/** Shared V2 validation. Durable fee-side posting is the only success evidence. */
public abstract class WechatFeeCallbackSupport {
    @Autowired private RestTemplate restTemplate;
    @Autowired private IFeePaymentOrderInnerServiceSMO feePaymentOrderInnerServiceSMO;
    protected abstract String orderType();

    public String confirmPayFee(String xml,String wId) {
        try {
            if(xml==null || xml.length()>65536 || xml.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")
                    || xml.toUpperCase(Locale.ROOT).contains("<!ENTITY"))return reply(false);
            Map<String,Object> values=PayUtil.getMapFromXML(xml);
            values.put("wId",wId);
            return reply(confirmPayFee(values)==1);
        } catch(Exception invalid) {
            // Notifications contain payer identity and signature material; do not log them.
            return reply(false);
        }
    }

    public int confirmPayFee(Map<String,Object> values) {
        if(!"SUCCESS".equals(values.get("return_code")) || !"SUCCESS".equals(values.get("result_code")))return 0;
        String wId=value(values,"wId");
        if(wId==null)return 0;
        String appId=WechatFactory.getAppId(wId.replace(" ","+"));
        String id=value(values,"out_trade_no");
        if(id==null)return 0;
        Map<String,Object> order;
        try { order=queryPaymentOrder(id); }catch(Exception unavailable){return 0;}
        if(order==null || !appId.equals(value(order,"appId")) || value(order,"communityId")==null)return 0;
        SmallWeChatDto merchant=getSmallWechat(appId,value(order,"communityId"));
        if(merchant==null || !appId.equals(merchant.getAppId()))return 0;
        SortedMap<String,String> signed=new TreeMap<>();
        for(Map.Entry<String,Object> entry:values.entrySet())if(!"wId".equals(entry.getKey()) && entry.getValue()!=null)signed.put(entry.getKey(),entry.getValue().toString());
        if(merchant.getPayPassword()==null || !PayUtil.createSign(signed,merchant.getPayPassword()).equals(value(values,"sign")))throw new IllegalArgumentException("Invalid payment signature");
        if(!merchant.getAppId().equals(value(values,"appid")) || merchant.getMchId()==null || !merchant.getMchId().equals(value(values,"mch_id")))return 0;
        String transaction=value(values,"transaction_id"), amount=value(values,"total_fee");
        if(id==null || transaction==null || amount==null || !amount.matches("[1-9][0-9]*"))return 0;
        long total;
        try { total=Long.parseLong(amount); }catch(NumberFormatException invalid){return 0;}
        try {
            if(order==null || !appId.equals(value(order,"appId")) || !merchant.getMchId().equals(value(order,"mchId"))
                    || !orderType().equals(value(order,"orderType")) || total!=Long.parseLong(value(order,"totalFee")))return 0;
            Map<String,Object> paid=new HashMap<>();
            paid.put("orderId",id);paid.put("transactionId",transaction);paid.put("totalFee",total);
            paid.put("appId",appId);paid.put("mchId",merchant.getMchId());paid.put("orderType",orderType());
            Map<String,Object> result=postPaymentOrder(paid);
            return result!=null && "0".equals(String.valueOf(result.get("code"))) && "POSTED".equals(result.get("state")) ? 1 : 0;
        } catch(Exception retryable) { return 0; }
    }

    protected Map<String,Object> queryPaymentOrder(String id) {
        Map<String,Object> request=new HashMap<>();request.put("orderId",id);
        return feePaymentOrderInnerServiceSMO.query(request);
    }
    protected Map<String,Object> postPaymentOrder(Map<String,Object> paid) { return feePaymentOrderInnerServiceSMO.confirm(paid); }

    private SmallWeChatDto getSmallWechat(String appId,String communityId) {
        ResponseEntity<String> response=callCenterService(restTemplate,"-1","",ServiceConstant.SERVICE_API_URL+
                "/api/smallWeChat.listSmallWeChats?appId="+appId+"&communityId="+communityId+"&page=1&row=2&statusCd=0",HttpMethod.GET);
        if(response.getStatusCode()!=HttpStatus.OK)return null;
        JSONObject body=JSONObject.parseObject(response.getBody());
        JSONArray rows=body.getJSONArray("smallWeChats");
        if(rows!=null && rows.size()==1)return BeanConvertUtil.covertBean(rows.get(0),SmallWeChatDto.class);
        return null;
    }

    protected ResponseEntity<String> callCenterService(RestTemplate client,String user,String body,String url,HttpMethod method) {
        HttpHeaders headers=new HttpHeaders();headers.add(CommonConstant.HTTP_APP_ID.toLowerCase(),"992020011134400001");
        headers.add(CommonConstant.HTTP_USER_ID.toLowerCase(),user);headers.add(CommonConstant.HTTP_TRANSACTION_ID.toLowerCase(),UUID.randomUUID().toString());
        headers.add(CommonConstant.HTTP_REQ_TIME.toLowerCase(),DateUtil.getDefaultFormateTimeString(new Date()));headers.add(CommonConstant.HTTP_SIGN.toLowerCase(),"");
        try { return client.exchange(url,method,new HttpEntity<String>(body,headers),String.class); }
        catch(HttpStatusCodeException failure){return new ResponseEntity<String>("{}",failure.getStatusCode());}
        catch(Exception failure){return new ResponseEntity<String>("{}",HttpStatus.INTERNAL_SERVER_ERROR);}
    }
    private static String value(Map<String,Object> values,String key) { Object value=values.get(key);return value==null || value.toString().trim().isEmpty()?null:value.toString(); }
    private static String reply(boolean success) { return "<xml><return_code><![CDATA["+(success?"SUCCESS":"FAIL")+"]]></return_code><return_msg><![CDATA["+(success?"OK":"RETRY")+"]]></return_msg></xml>"; }
}
