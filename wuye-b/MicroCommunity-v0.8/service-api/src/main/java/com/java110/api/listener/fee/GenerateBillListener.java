package com.java110.api.listener.fee;

import com.alibaba.fastjson.JSONObject;
import com.java110.api.bmo.fee.impl.BillGenerationService;
import com.java110.api.listener.AbstractServiceApiListener;
import com.java110.core.annotation.Java110Listener;
import com.java110.core.context.DataFlowContext;
import com.java110.core.event.service.api.ServiceDataFlowEvent;
import com.java110.utils.util.Assert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.Map;

@Java110Listener("generateBillListener")
public class GenerateBillListener extends AbstractServiceApiListener {
    @Autowired private BillGenerationService billGenerationService;
    @Override public String getServiceCode(){return "fee.generateBill";}
    @Override public HttpMethod getHttpMethod(){return HttpMethod.POST;}
    @Override protected void validate(ServiceDataFlowEvent event,JSONObject request){
        if(!"8000418004".equals(event.getDataFlowContext().getAppId()))throw new IllegalArgumentException("此功能仅供物业后台使用");
        Assert.hasKeyAndValue(request,"communityId","请选择小区");
        Assert.hasKeyAndValue(request,"configId","请选择收费项目");
    }
    @Override protected void doSoService(ServiceDataFlowEvent event,DataFlowContext context,JSONObject request){
        Map<String,String> headers=context.getRequestCurrentHeaders();
        JSONObject result=billGenerationService.generate(request.getString("communityId"),request.getString("configId"),headers.get("store-id"),headers.get("user-id"));
        context.setResponseEntity(new ResponseEntity<String>(result.toJSONString(),HttpStatus.OK));
    }
}
