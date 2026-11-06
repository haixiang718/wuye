package com.java110.api.listener.fee;

import com.alibaba.fastjson.JSONObject;
import com.java110.api.bmo.fee.impl.BillReviewService;
import com.java110.api.bmo.fee.impl.BillPublicationService;
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
    @Autowired private BillPublicationService billPublicationService;
    @Override public String getServiceCode() { return "fee.reviewBill"; }
    @Override public HttpMethod getHttpMethod() { return HttpMethod.POST; }
    @Override protected void validate(ServiceDataFlowEvent event,JSONObject request) {
        String action=action(request);
        if(!"APPROVE".equals(action)&&!"REJECT".equals(action)&&!"RESUBMIT".equals(action))throw new IllegalArgumentException("不支持的账单审核操作");
        if((request.getBooleanValue("notify")||!"APPROVE".equals(action))&&!"8000418004".equals(event.getDataFlowContext().getAppId()))throw new IllegalArgumentException("此功能仅供物业后台使用");
        if(!"APPROVE".equals(action)&&request.getBooleanValue("notify"))throw new IllegalArgumentException("审核不通过或重新提交不能发送通知");
        Assert.hasKeyAndValue(request,"communityId","缺少小区信息");
        Assert.hasKeyAndValue(request,"billId","缺少账单编号");
    }
    @Override protected void doSoService(ServiceDataFlowEvent event,DataFlowContext context,JSONObject request) {
        Map<String,String> headers = context.getRequestCurrentHeaders();
        String action=action(request);
        JSONObject result;
        if("REJECT".equals(action))result=billReviewService.reject(request.getString("communityId"),request.getString("billId"),request.getString("remark"),headers.get("store-id"),headers.get("user-id"));
        else if("RESUBMIT".equals(action))result=billReviewService.resubmit(request.getString("communityId"),request.getString("billId"),request.getString("remark"),headers.get("store-id"),headers.get("user-id"));
        else result = request.getBooleanValue("notify")
            ? billPublicationService.publishAndNotify(request.getString("communityId"),request.getString("billId"),request.getString("remark"),headers.get("store-id"),headers.get("user-id"),request.getBooleanValue("retryNotifications"))
            : billReviewService.publish(request.getString("communityId"),request.getString("billId"),request.getString("remark"),headers.get("store-id"),headers.get("user-id"));
        context.setResponseEntity(new ResponseEntity<String>(result.toJSONString(),HttpStatus.OK));
    }
    private static String action(JSONObject request) {String value=request.getString("action");return value==null||value.trim().isEmpty()?"APPROVE":value;}
}
