import com.alibaba.fastjson.*;
import com.java110.api.bmo.fee.impl.BillNotificationService;
import com.java110.dto.CommunityMemberDto;
import com.java110.dto.fee.BillOweFeeDto;
import com.java110.dto.owner.OwnerAppUserDto;
import com.java110.dto.smallWeChat.SmallWeChatDto;
import com.java110.dto.smallWechatAttr.SmallWechatAttrDto;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Same generated bill: real local database reads/writes; WeChat transport and OAuth identity are synthetic. */
public class LinkedNotificationAudit {
    static JSONObject fixture;
    static RestTemplate http;
    static int sends,updates;
    static String link;
    static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    static class Sender extends BillNotificationService {
        final Map<String,String> cache=new HashMap<>();final Set<String> locks=new HashSet<>();
        @Override protected String cached(String key){return cache.get(key);}
        @Override protected void remember(String key,String value,int seconds){cache.put(key,value);}
        @Override protected boolean acquire(String key,String token){return locks.add(key);}
        @Override protected void release(String key,String token){locks.remove(key);}
        @Override protected String accessToken(SmallWeChatDto ignored){return "simulation-token";}
        @Override protected String templateContent(String token,String templateId){return "{{first.DATA}}{{keyword1.DATA}}{{keyword2.DATA}}{{keyword3.DATA}}{{remark.DATA}}";}
        @Override protected String feePage(){return "https://simulation.invalid/#/pages/roomFeeListNew/roomFeeListNew?roomId=";}
        @Override protected ResponseEntity<String> deliver(String token,String payload){
            JSONObject message=JSON.parseObject(payload);
            require("simulation-openid".equals(message.getString("touser")),"Must never use a real recipient");
            require("0.01".equals(message.getJSONObject("data").getJSONObject("keyword2").getString("value")),"Wrong fixture amount");
            link=message.getString("url");require(link.contains("roomId="+fixture.getJSONObject("ids").getString("room")),"Wrong notification room");
            sends++;return ResponseEntity.ok("{\"errcode\":0,\"msgid\":10001}");
        }
    }
    static Object invoke(Method method,Object body) {
        String endpoint=method.getDeclaringClass().getAnnotation(RequestMapping.class).value()[0]+method.getAnnotation(RequestMapping.class).value()[0];
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON_UTF8);
        String result=http.postForObject("http://127.0.0.1:8015"+endpoint,new HttpEntity<>(JSON.toJSONString(body),headers),String.class);
        return JSON.parseObject(result,method.getGenericReturnType());
    }
    public static void main(String[] args)throws Exception {
        fixture=JSON.parseObject(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8));
        require(fixture.getString("prefix").matches("SIM[a-f0-9]{12}"),"Only random simulation fixtures allowed");
        require("LOCAL FULL FLOW SIMULATION ONLY - DO NOT PAY".equals(fixture.getString("label")),"Unexpected fixture label");
        SimpleClientHttpRequestFactory transport=new SimpleClientHttpRequestFactory();transport.setConnectTimeout(10000);transport.setReadTimeout(20000);http=new RestTemplate(transport);
        final Sender sender=new Sender();
        for(String name:Arrays.asList("communityInnerServiceSMOImpl","feeInnerServiceSMOImpl","smallWeChatInnerServiceSMOImpl","smallWechatAttrInnerServiceSMOImpl","ownerAppUserInnerServiceSMOImpl")) {
            Field field=BillNotificationService.class.getDeclaredField(name);field.setAccessible(true);
            field.set(sender,Proxy.newProxyInstance(field.getType().getClassLoader(),new Class[]{field.getType()},(p,method,values)->{
                String operation=method.getName();
                if(operation.equals("getCommunityMembers"))return Collections.singletonList(new CommunityMemberDto());
                if(operation.equals("querySmallWeChats")){SmallWeChatDto account=new SmallWeChatDto();account.setAppId("wx-simulation-only");account.setWeChatId("simulation-account");return Collections.singletonList(account);}
                if(operation.equals("querySmallWechatAttrs")){SmallWechatAttrDto attr=new SmallWechatAttrDto();attr.setValue("simulation-template");return Collections.singletonList(attr);}
                if(operation.equals("queryOwnerAppUsers")) {
                    OwnerAppUserDto query=(OwnerAppUserDto)values[0];query.setUserId(fixture.getString("createdUserId"));
                    List<OwnerAppUserDto> rows=(List<OwnerAppUserDto>)invoke(method,query);
                    require(rows.size()==1&&fixture.getJSONObject("ids").getString("owner").equals(rows.get(0).getOwnerId()),"Unexpected owner binding");
                    require("12000".equals(rows.get(0).getState()),"Unapproved binding");
                    // Replace only the external identity in memory, never write an invented WeChat binding into TT.
                    rows.get(0).setOpenId("simulation-openid");return rows;
                }
                if(operation.equals("queryBillOweFees")||operation.equals("updateBillOweFees")) {
                    BillOweFeeDto value=(BillOweFeeDto)values[0];
                    require(fixture.getString("communityId").equals(value.getCommunityId())&&fixture.getString("oweId").equals(value.getOweId()),"Unscoped bill operation");
                    if(operation.equals("updateBillOweFees")) {
                        require(fixture.getJSONObject("ids").getString("fee").equals(value.getFeeId())&&fixture.getString("billId").equals(value.getBillId())&&"3000".equals(value.getState()),"Unexpected bill update");updates++;
                    }
                    return invoke(method,value);
                }
                throw new AssertionError("Unexpected operation "+operation);
            }));
        }
        JSONObject receipt=sender.send(fixture.getString("communityId"),fixture.getString("oweId"),fixture.getString("prefix")+"-notify", "simulation-property", "simulation-staff");
        if(args.length>2 && "draft".equals(args[2])) {
            require(receipt.getIntValue("code")==400&&sends==0&&updates==0,"Unreviewed bill was not rejected before delivery");
            JSONObject report=new JSONObject(true);report.put("passed",true);report.put("mode","same-real-draft-bill-rejected-before-synthetic-transport");
            report.put("simulatedAcceptances",sends);report.put("realFixtureStateUpdates",updates);report.put("realWechatRequests",0);
            Files.write(Paths.get(args[1]),JSON.toJSONString(report,true).getBytes(StandardCharsets.UTF_8));
            System.out.println("Draft notification rejected: no delivery and no bill state update.");return;
        }
        require(receipt.getIntValue("code")==0&&sends==1&&updates==1,"Notification simulation did not complete");
        JSONObject repeated=sender.send(fixture.getString("communityId"),fixture.getString("oweId"),fixture.getString("prefix")+"-notify", "simulation-property", "simulation-staff");
        require(repeated.getIntValue("code")==0&&sends==1&&updates==1,"Repeated request sent twice");
        JSONObject report=new JSONObject(true);report.put("mode","same-real-generated-bill-with-synthetic-notification-transport-and-oauth-identity");
        report.put("passed",true);report.put("simulatedAcceptances",sends);report.put("realFixtureStateUpdates",updates);report.put("sameRequestDeduplicated",true);report.put("realWechatRequests",0);report.put("billStateAfter","3000");report.put("linkRoomMatches",true);report.put("databaseOAuthBindingModified",false);
        Files.write(Paths.get(args[1]),JSON.toJSONString(report,true).getBytes(StandardCharsets.UTF_8));
        System.out.println("Linked notification simulation passed: same 0.01 bill marked notified, same-request replay did not send again; no real WeChat request.");
    }
}
