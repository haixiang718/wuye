package com.java110.front.smo.payment.impl;

import com.java110.front.smo.payment.IToPayOweFeeSMO;
import org.springframework.stereotype.Service;

@Service("toPayOweFeeSMOImpl")
public class ToPayOweFeeSMOImpl extends PublishedBillToPaySMO implements IToPayOweFeeSMO {
    @Override protected String orderType() { return "ARREARS"; }
}
