import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.java110.dto.fee.*;
import com.java110.dto.task.TaskDto;
import com.java110.job.task.fee.GenerateBillProTemplate;
import org.springframework.http.*;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Opt-in fixture-only calls to existing local services. Never sends messages. */
public class LocalMonthlyBillingAudit {
    static class Local extends BillingWorkflowAudit.Memory {
        final JSONObject fixture;
        final RestTemplate http;
        int writes;
        Local(JSONObject fixture) {
            this.fixture=fixture;
            config.setCommunityId(fixture.getString("communityId"));config.setConfigId(fixture.getString("configId"));
            config.setFeeName("AUDIT monthly fee - do not pay");config.setBillType("002");
            SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(10000);factory.setReadTimeout(20000);
            http=new RestTemplate(factory);http.getMessageConverters().set(1,new StringHttpMessageConverter(StandardCharsets.UTF_8));
        }
        void require(boolean condition,String msg) {if(!condition)throw new IllegalStateException(msg);}
        @Override public Object invoke(Object ignored,Method method,Object[] args)throws Exception {
            String name=method.getName();JSONObject body=(JSONObject)JSON.toJSON(args[0]);
            Set<String> allowed=new HashSet<String>(Arrays.asList("queryBills","queryFees","queryRooms","queryOwnerRoomRels","queryFeeDetails","saveBillSnapshot"));
            require(allowed.contains(name),"Unexpected local audit operation");
            if(name.equals("saveBillSnapshot")) {
                JSONObject bill=body.getJSONObject("bill");
                require(fixture.getString("communityId").equals(bill.getString("communityId")),"Wrong snapshot community");
                require(fixture.getString("configId").equals(bill.getString("configId")),"Unscoped snapshot config");
                require(body.getJSONArray("details").size()==1,"Unexpected snapshot detail count");
                JSONObject detail=body.getJSONArray("details").getJSONObject(0);
                require(fixture.getString("communityId").equals(detail.getString("communityId")),"Wrong detail community");
                require(fixture.getString("feeId").equals(detail.getString("feeId")),"Unscoped snapshot fee");
                require(fixture.getString("ownerId").equals(detail.getString("ownerId")),"Unexpected generated owner");
                require(detail.getBigDecimal("billAmountOwed").compareTo(new java.math.BigDecimal("0.01"))==0,"Unexpected audit amount");
                if(failHeader)throw new IllegalStateException("Synthetic interruption before atomic snapshot HTTP request");
            } else {
                require(fixture.getString("communityId").equals(body.getString("communityId")),"Wrong fixture community");
                if(name.equals("queryFees")||name.equals("queryBills"))require(fixture.getString("configId").equals(body.getString("configId")),"Unscoped audit config");
                if(name.equals("queryRooms")||name.equals("queryOwnerRoomRels"))require(fixture.getString("roomId").equals(body.getString("roomId")),"Unscoped audit room");
                if(name.equals("queryFeeDetails"))require(fixture.getString("feeId").equals(body.getString("feeId")),"Unscoped audit fee");
            }
            Class<?> type=method.getDeclaringClass();String root=type.getAnnotation(RequestMapping.class).value()[0];
            String endpoint=method.getAnnotation(RequestMapping.class).value()[0];
            int port=fixture.getBooleanValue("merged")?8015:type.getName().contains(".fee.")?8010:type.getName().contains(".community.")?8009:8002;
            String url="http://127.0.0.1:"+port+root+endpoint;
            HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON_UTF8);
            String response=http.postForObject(url,new HttpEntity<String>(body.toJSONString(),headers),String.class);
            if(name.equals("saveBillSnapshot"))writes++;
            return JSON.parseObject(response,method.getGenericReturnType());
        }
        @SuppressWarnings("unchecked") List<BillDto> bills()throws Exception {
            Class<?> type=Class.forName("com.java110.intf.fee.IFeeInnerServiceSMO");
            BillDto query=new BillDto();query.setCommunityId(fixture.getString("communityId"));query.setConfigId(fixture.getString("configId"));
            return (List<BillDto>)invoke(null,type.getMethod("queryBills",BillDto.class),new Object[]{query});
        }
        List<BillOweFeeDto> details(BillDto bill) {
            // Known internal endpoint, always constrained to the isolated audit fee and bill.
            BillOweFeeDto q=new BillOweFeeDto();q.setCommunityId(fixture.getString("communityId"));q.setFeeId(fixture.getString("feeId"));q.setBillId(bill.getBillId());
            HttpHeaders h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON_UTF8);
            String raw=http.postForObject("http://127.0.0.1:"+(fixture.getBooleanValue("merged")?8015:8010)+"/feeApi/queryBillOweFees",new HttpEntity<String>(JSON.toJSONString(q),h),String.class);
            return JSON.parseArray(raw,BillOweFeeDto.class);
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length<2||args.length>3)throw new IllegalArgumentException("Expected fixture input, result output and optional snapshot-failure scenario");
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));BillingWorkflowAudit.cache();
        JSONObject fixture=JSON.parseObject(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8));
        Local local=new Local(fixture);local.require(local.bills().isEmpty(),"Audit config already has a bill");
        if(args.length==3) {
            local.require("snapshot-failure".equals(args[2]),"Unknown audit scenario");
            local.failHeader=true;boolean failed=false;try{local.generate();}catch(IllegalStateException expected){failed=true;}
            local.require(failed&&local.bills().isEmpty(),"Expected interruption before snapshot write");
            local.failHeader=false;local.generate();local.require(local.bills().size()==1,"Retry did not generate one bill header");
            JSONObject result=new JSONObject(true);result.put("mode","actual-local-services-with-injected-snapshot-interruption");
            result.put("firstFailurePropagated",true);result.put("headersAfterFailure",0);result.put("headersAfterRetry",1);result.put("messageRequests",0);
            Files.write(Paths.get(args[1]),JSON.toJSONString(result,true).getBytes(StandardCharsets.UTF_8));
            System.out.println("Snapshot interruption and retry executed; inspect actual fixture-scoped SQL counts.");return;
        }
        local.generate();List<BillDto> bills=local.bills();local.require(bills.size()==1,"Expected exactly one generated bill");
        List<BillOweFeeDto> details=local.details(bills.get(0));local.require(details.size()==1,"Expected exactly one generated bill detail");
        local.require(BillingWorkflowAudit.amount(details.get(0).getBillAmountOwed(),"0.01"),"Wrong generated amount");
        local.require(fixture.getString("ownerId").equals(details.get(0).getOwnerId()),"Generated detail targets another owner");
        boolean blocked=false;
        try {local.generate();} catch(com.java110.utils.exception.TaskTemplateException expected){blocked=true;}
        local.require(blocked&&local.bills().size()==1&&local.details(bills.get(0)).size()==1&&local.writes==1,"Sequential rerun was not idempotent");
        JSONObject result=new JSONObject(true);result.put("mode","actual-local-business-services");result.put("headers",1);result.put("details",1);
        result.put("amount","0.01");result.put("state",details.get(0).getState());result.put("repeatBlocked",true);
        result.put("matchingOwner",true);result.put("deadline",details.get(0).getDeadlineTime());result.put("messageRequests",0);
        Files.write(Paths.get(args[1]),JSON.toJSONString(result,true).getBytes(StandardCharsets.UTF_8));
        System.out.println("PASS actual local generation: one CNY 0.01 monthly bill; second run blocked; no notification request.");
    }
}
