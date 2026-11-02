package com.java110.api.listener.fee;

import com.alibaba.fastjson.JSONObject;
import com.java110.api.bmo.fee.impl.BillReviewService;
import com.java110.api.listener.AbstractServiceApiListener;
import com.java110.core.annotation.Java110Listener;
import com.java110.core.context.DataFlowContext;
import com.java110.core.event.service.api.ServiceDataFlowEvent;
import com.java110.utils.util.Assert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.Map;

@Java110Listener("reviewBillListener")
public class ReviewBillListener extends AbstractServiceApiListener {
    @Autowired private BillReviewService billReviewService;
    @Override public String getServiceCode() { return "fee.reviewBill"; }
    @Override public HttpMethod getHttpMethod() { return HttpMethod.POST; }
    @Override protected void validate(ServiceDataFlowEvent event,JSONObject request) {
        Assert.hasKeyAndValue(request,"communityId","缺少小区信息");
        Assert.hasKeyAndValue(request,"billId","缺少账单编号");
    }
    @Override protected void doSoService(ServiceDataFlowEvent event,DataFlowContext context,JSONObject request) {
        Map<String,String> headers = context.getRequestCurrentHeaders();
        JSONObject result = billReviewService.publish(request.getString("communityId"),request.getString("billId"),
            request.getString("remark"),headers.get("store-id"),headers.get("user-id"));
        context.setResponseEntity(new ResponseEntity<String>(result.toJSONString(),HttpStatus.OK));
    }
}
