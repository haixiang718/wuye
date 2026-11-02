package com.java110.front.smo.payment.impl;

import com.java110.front.smo.payment.IToQrPayOweFeeSMO;
import org.springframework.stereotype.Service;

@Service("toQrPayOweFeeSMOImpl")
public class ToQrPayOweFeeSMOImpl extends PublishedBillToPaySMO implements IToQrPayOweFeeSMO {
    @Override protected String orderType() { return "ARREARS"; }
    @Override protected boolean qr() { return true; }
}
