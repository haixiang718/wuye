package com.java110.front.smo.payment.adapt.wechatPay;

import com.java110.front.smo.payment.adapt.IPayNotifyAdapt;
import org.springframework.stereotype.Component;

@Component("wechatPayNotifyAdapt")
public class WechatPayNotifyAdapt extends WechatFeeCallbackSupport implements IPayNotifyAdapt {
    @Override protected String orderType() { return "SINGLE"; }
}
