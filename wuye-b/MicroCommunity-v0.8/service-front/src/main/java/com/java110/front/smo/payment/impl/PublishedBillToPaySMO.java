package com.java110.front.smo.payment.impl;

import com.alibaba.fastjson.*;
import com.java110.core.context.IPageData;
import com.java110.dto.app.AppDto;
import com.java110.dto.owner.OwnerAppUserDto;
import com.java110.dto.smallWeChat.SmallWeChatDto;
import com.java110.front.properties.WechatAuthProperties;
import com.java110.front.smo.AppAbstractComponentSMO;
import com.java110.front.smo.payment.adapt.IPayAdapt;
import com.java110.front.smo.payment.v3.WechatPayV3Service;
import com.java110.intf.fee.IFeePaymentOrderInnerServiceSMO;
import com.java110.utils.cache.MappingCache;
import com.java110.utils.constant.*;
import com.java110.utils.factory.ApplicationContextFactory;
import com.java110.utils.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import java.math.BigDecimal;
import java.util.*;

/** All entry points charge the exact published snapshot, without accepting client pricing. */
public abstract class PublishedBillToPaySMO extends AppAbstractComponentSMO {
    @Autowired private RestTemplate restTemplate;
    @Autowired private RestTemplate outRestTemplate;
    @Autowired private WechatAuthProperties wechatAuthProperties;
    @Autowired private IFeePaymentOrderInnerServiceSMO feePaymentOrderInnerServiceSMO;
    @Autowired(required=false) private WechatPayV3Service wechatPayV3Service;
    protected abstract String orderType();
    protected boolean qr() { return false; }
    public ResponseEntity<String> toPay(IPageData pd) { return super.businessProcess(pd); }

    /** Merchant lookup responses contain credentials and must not use the legacy body logger. */
    @Override protected ResponseEntity<String> callCenterService(RestTemplate client,IPageData pd,String body,String url,HttpMethod method) {
        HttpHeaders headers=new HttpHeaders();headers.add(CommonConstant.HTTP_APP_ID.toLowerCase(),pd.getAppId());
        headers.add(CommonConstant.HTTP_USER_ID.toLowerCase(),pd.getUserId());headers.add(CommonConstant.HTTP_TRANSACTION_ID.toLowerCase(),pd.getTransactionId());
        headers.add(CommonConstant.HTTP_REQ_TIME.toLowerCase(),pd.getRequestTime());headers.add(CommonConstant.HTTP_SIGN.toLowerCase(),"");
        headers.setContentType(MediaType.APPLICATION_JSON);
        try { return client.exchange(url,method,new HttpEntity<String>(body,headers),String.class); }
        catch(HttpStatusCodeException failure){return new ResponseEntity<String>("{}",failure.getStatusCode());}
        catch(Exception failure){return new ResponseEntity<String>("{}",HttpStatus.INTERNAL_SERVER_ERROR);}
    }

    @Override protected void validate(IPageData pd,JSONObject input) {
        Assert.hasLength(pd.getUserId(),"请先登录");
        Assert.hasLength(input.getString("communityId"),"未包含小区");
        Assert.hasLength(input.getString("SINGLE".equals(orderType())?"feeId":"roomId"),"未包含支付范围");
        if(!qr())Assert.hasLength(input.getString("appId"),"未包含应用");
        if("ARREARS".equals(orderType()))selectedOwes(input);
    }

    @Override protected ResponseEntity<String> doBusinessProcess(IPageData pd,JSONObject input)throws Exception {
        List<String> selected = "ARREARS".equals(orderType()) ? selectedOwes(input) : null;
        String merchantUrl=ServiceConstant.SERVICE_API_URL+"/api/smallWeChat.listSmallWeChats?page=1&row=2&statusCd=0&communityId="+input.getString("communityId");
        merchantUrl+=qr()?"&weChatType=1100":"&appId="+input.getString("appId");
        ResponseEntity<String> response=callCenterService(restTemplate,pd,"",merchantUrl,HttpMethod.GET);
        if(response.getStatusCode()!=HttpStatus.OK)return response;
        JSONArray merchants=JSONObject.parseObject(response.getBody()).getJSONArray("smallWeChats");
        if(merchants==null || merchants.size()!=1)throw new IllegalArgumentException("微信支付未开通");
        SmallWeChatDto merchant=BeanConvertUtil.covertBean(merchants.get(0),SmallWeChatDto.class);
        boolean v3=wechatPayV3Service!=null && wechatPayV3Service.configured();
        if(StringUtil.isEmpty(merchant.getAppId()) || StringUtil.isEmpty(merchant.getMchId()) || (!v3 && StringUtil.isEmpty(merchant.getPayPassword())))throw new IllegalArgumentException("微信支付未开通");
        if(v3) {
            if(qr() || !WechatAuthProperties.TRADE_TYPE_JSAPI.equals(input.getString("tradeType")))throw new IllegalArgumentException("当前仅开放公众号账单支付");
            wechatPayV3Service.requireNewPayment(pd.getUserId(),merchant.getAppId(),merchant.getMchId());
        }
        String openId="";
        if(!qr()) {
            String appType=AppDto.WECHAT_OWNER_APP_ID.equals(pd.getAppId())?OwnerAppUserDto.APP_TYPE_WECHAT:
                (AppDto.WECHAT_MINA_OWNER_APP_ID.equals(pd.getAppId())?OwnerAppUserDto.APP_TYPE_WECHAT_MINA:OwnerAppUserDto.APP_TYPE_APP);
            openId=approvedPayerOpenId(pd,input.getString("communityId"),appType);
        }
        Map<String,Object> scope=new HashMap<>();
        for(String key:Arrays.asList("communityId","feeId","roomId","billId","oweId"))if(input.containsKey(key))scope.put(key,input.getString(key));
        if(selected != null)scope.put("oweIds",selected);
        scope.put("payObjType",input.getString("payObjType")==null?"3333":input.getString("payObjType"));
        scope.put("userId",pd.getUserId());scope.put("appId",merchant.getAppId());scope.put("mchId",merchant.getMchId());scope.put("orderType",orderType());
        scope.put("providerVersion",v3?"V3":"V2");
        if(v3)scope.put("maxTotalFee",wechatPayV3Service.maximumTotalFee());
        // Commit the trusted order BEFORE a provider call; even a timeout leaves a recoverable record.
        Map<String,Object> order=feePaymentOrderInnerServiceSMO.create(scope);
        JSONArray snapshots=JSON.parseArray(String.valueOf(order.get("payload")));
        String description=snapshots.getJSONObject(0).getString("feeName");
        if(v3)return new ResponseEntity<String>(JSON.toJSONString(wechatPayV3Service.prepay(order,description,openId,pd.getUserId())),HttpStatus.OK);
        double money=BigDecimal.valueOf(Long.parseLong(order.get("totalFee").toString()),2).doubleValue();
        if(Double.compare(PayUtil.getPayAmountByEnv(MappingCache.getValue("HC_ENV"),money),money)!=0)throw new IllegalStateException("环境支付金额与已发布账单不一致");
        String adapt=MappingCache.getValue(WechatConstant.WECHAT_DOMAIN,WechatConstant.PAY_ADAPT);
        adapt=StringUtil.isEmpty(adapt)?DEFAULT_PAY_ADAPT:adapt;
        if(!DEFAULT_PAY_ADAPT.equals(adapt))throw new IllegalArgumentException("当前支付适配器不支持可信账单订单");
        IPayAdapt provider=ApplicationContextFactory.getBean(adapt,IPayAdapt.class);
        String trade=qr()?WechatAuthProperties.TRADE_TYPE_NATIVE:input.getString("tradeType");
        Map result;
        if("ARREARS".equals(orderType()))result=provider.java110Payment(outRestTemplate,description,trade,order.get("orderId").toString(),money,openId,merchant,wechatAuthProperties.getOweFeeNotifyUrl());
        else result=provider.java110Payment(outRestTemplate,description,trade,order.get("orderId").toString(),money,openId,merchant);
        return new ResponseEntity<String>(JSON.toJSONString(result),HttpStatus.OK);
    }

    private List<String> selectedOwes(JSONObject input) {
        Object value=input.get("oweIds");
        if(!(value instanceof List) || ((List<?>)value).isEmpty() || ((List<?>)value).size()>100)
            throw new IllegalArgumentException("请选择1至100项账单后缴费");
        Set<String> ids=new LinkedHashSet<>();
        for(Object id:(List<?>)value) {
            if(!(id instanceof String) || !((String)id).matches("[A-Za-z0-9_-]{1,30}") || !ids.add((String)id))
                throw new IllegalArgumentException("所选账单编号无效或重复，请重新选择");
        }
        return new ArrayList<>(ids);
    }

    private String approvedPayerOpenId(IPageData pd,String communityId,String appType)throws Exception {
        Set<String> identities=new HashSet<>();
        int expected=-1,seen=0,page=1;
        while(expected<0 || seen<expected) {
            String url=ServiceConstant.SERVICE_API_URL+"/api/"+ServiceCodeConstant.LIST_APPUSERBINDINGOWNERS+
                "?userId="+java.net.URLEncoder.encode(pd.getUserId(),"UTF-8")+
                "&communityId="+java.net.URLEncoder.encode(communityId,"UTF-8")+
                "&appType="+java.net.URLEncoder.encode(appType,"UTF-8")+"&state=12000&page="+page+"&row=100";
            ResponseEntity<String> response=callCenterService(restTemplate,pd,"",url,HttpMethod.GET);
            if(response.getStatusCode()!=HttpStatus.OK)throw new IllegalStateException("微信身份查询失败");
            JSONObject result=JSONObject.parseObject(response.getBody());
            if(result==null || !"0".equals(result.getString("code")) || !result.containsKey("total"))throw new IllegalStateException("微信身份查询失败");
            int total=result.getIntValue("total");
            if(total<1 || total>100000 || (expected>=0 && expected!=total))throw new IllegalArgumentException("微信身份列表为空或已改变，请重试");
            expected=total;
            JSONArray rows=result.getJSONArray("data");
            if(rows==null || rows.isEmpty() || rows.size()>100 || seen+rows.size()>expected)throw new IllegalStateException("微信身份查询不完整");
            for(Object item:rows) {
                JSONObject binding=(JSONObject)item;
                if(!pd.getUserId().equals(binding.getString("userId")) || !communityId.equals(binding.getString("communityId"))
                        || !appType.equals(binding.getString("appType")) || !"12000".equals(binding.getString("state"))
                        || StringUtil.isEmpty(binding.getString("openId")))throw new IllegalArgumentException("微信身份归属或审核状态不匹配");
                identities.add(binding.getString("openId"));
                if(identities.size()>1)throw new IllegalArgumentException("微信支付身份不唯一，请核对绑定");
            }
            seen+=rows.size();page++;
        }
        if(identities.size()!=1)throw new IllegalArgumentException("微信支付身份不唯一");
        return identities.iterator().next();
    }
}
