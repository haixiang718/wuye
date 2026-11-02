package com.java110.front.smo.payment.impl;

import com.java110.front.smo.payment.IToPaySMO;
import org.springframework.stereotype.Service;

@Service("toPaySMOImpl")
public class ToPaySMOImpl extends PublishedBillToPaySMO implements IToPaySMO {
    @Override protected String orderType() { return "SINGLE"; }
}
