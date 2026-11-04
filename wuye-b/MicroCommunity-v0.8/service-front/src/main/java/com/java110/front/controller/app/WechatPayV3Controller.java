package com.java110.front.controller.app;

import com.alibaba.fastjson.JSONObject;
import com.java110.core.context.IPageData;
import com.java110.front.smo.payment.v3.WechatPayV3Service;
import com.java110.utils.constant.CommonConstant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Public signed notifications and authenticated owner recovery are deliberately separate. */
@RestController
@RequestMapping("/app/payment/v3")
public class WechatPayV3Controller {
    @Autowired private WechatPayV3Service service;
    @RequestMapping(value="/notify",method=RequestMethod.POST)
    public ResponseEntity<String> notify(@RequestBody String raw,HttpServletRequest request) {
        if(raw==null||raw.getBytes(StandardCharsets.UTF_8).length>65536)return reply(413,"FAIL","通知过大");
        Map<String,String> headers=new HashMap<>();
        for(String name:Arrays.asList("Wechatpay-Serial","Wechatpay-Signature","Wechatpay-Timestamp","Wechatpay-Nonce","Wechatpay-Signature-Type"))headers.put(name,request.getHeader(name));
        try {
            Map<String,Object> result=service.notification(raw,headers);
            if(!"POSTED".equals(result.get("state")))return reply(500,"FAIL","入账尚未完成");
            return new ResponseEntity<String>("",HttpStatus.NO_CONTENT);
        }catch(Exception retryable){return reply(500,"FAIL","支付通知校验或入账失败");}
    }
    @RequestMapping(value="/query",method=RequestMethod.POST)
    public ResponseEntity<String> query(@RequestBody String raw,HttpServletRequest request){return ownerAction(raw,request,false);}
    @RequestMapping(value="/close",method=RequestMethod.POST)
    public ResponseEntity<String> close(@RequestBody String raw,HttpServletRequest request){return ownerAction(raw,request,true);}
    private ResponseEntity<String> ownerAction(String raw,HttpServletRequest request,boolean close) {
        IPageData pd=(IPageData)request.getAttribute(CommonConstant.CONTEXT_PAGE_DATA);
        if(pd==null||pd.getUserId()==null||pd.getUserId().trim().isEmpty()||"-1".equals(pd.getUserId()))return reply(401,"401","请先登录");
        try {
            JSONObject body=JSONObject.parseObject(raw);
            String orderId=body.getString("orderId");
            Map<String,Object> result=close?service.close(pd.getUserId(),orderId):service.query(pd.getUserId(),orderId);
            return new ResponseEntity<String>(JSONObject.toJSONString(result),HttpStatus.OK);
        }catch(SecurityException denied){return reply(403,"403","无权访问此支付订单");}
        catch(Exception retryable){return reply(503,"503","支付结果确认中，请稍后重试");}
    }
    private static ResponseEntity<String> reply(int status,String code,String message) {
        JSONObject body=new JSONObject();body.put("code",code);body.put("message",message);body.put("msg",message);
        return new ResponseEntity<String>(body.toJSONString(),HttpStatus.valueOf(status));
    }
}
