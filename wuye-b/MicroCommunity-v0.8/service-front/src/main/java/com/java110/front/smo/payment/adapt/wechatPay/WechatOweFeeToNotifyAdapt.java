package com.java110.front.smo.payment.adapt.wechatPay;

import com.java110.front.smo.payment.adapt.IOweFeeToNotifyAdapt;
import org.springframework.stereotype.Component;

@Component("wechatOweFeeToNotifyAdapt")
public class WechatOweFeeToNotifyAdapt extends WechatFeeCallbackSupport implements IOweFeeToNotifyAdapt {
    @Override protected String orderType() { return "ARREARS"; }
}
