package com.java110.front.smo.payment.v3;

import com.alibaba.fastjson.JSONObject;
import java.util.Map;

/** Verified WeChat Pay transport. Business posting belongs to the payment service. */
public interface WechatPayV3Provider {
    Map<String, String> prepay(String description, String orderId, long cents, String openId);
    JSONObject query(String orderId);
    void close(String orderId);
    JSONObject notification(String rawBody, Map<String, String> headers);
}
