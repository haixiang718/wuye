package com.java110.api.bmo.fee.impl;

import com.alibaba.fastjson.*;
import com.java110.core.factory.WechatFactory;
import com.java110.dto.CommunityMemberDto;
import com.java110.dto.fee.*;
import com.java110.dto.owner.OwnerAppUserDto;
import com.java110.dto.smallWeChat.SmallWeChatDto;
import com.java110.dto.smallWechatAttr.SmallWechatAttrDto;
import com.java110.entity.wechat.*;
import com.java110.intf.community.ICommunityInnerServiceSMO;
import com.java110.intf.fee.IFeeInnerServiceSMO;
import com.java110.intf.store.*;
import com.java110.intf.user.IOwnerAppUserInnerServiceSMO;
import com.java110.utils.cache.MappingCache;
import com.java110.utils.factory.ApplicationContextFactory;
import com.java110.utils.util.StringUtil;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import redis.clients.jedis.*;
import java.math.BigDecimal;
import java.net.*;
import java.util.*;

/** Sends only a selected current bill; never starts the community-wide scheduled task. */
@Service
public class BillNotificationService {
    @Autowired private ICommunityInnerServiceSMO communityInnerServiceSMOImpl;
    @Autowired private IFeeInnerServiceSMO feeInnerServiceSMOImpl;
    @Autowired private ISmallWeChatInnerServiceSMO smallWeChatInnerServiceSMOImpl;
    @Autowired private ISmallWechatAttrInnerServiceSMO smallWechatAttrInnerServiceSMOImpl;
    @Autowired private IOwnerAppUserInnerServiceSMO ownerAppUserInnerServiceSMOImpl;
    private static final int RECEIPT_TTL = 86400;

    public JSONObject send(String communityId, String oweId, String requestId, String storeId, String userId) {
        try {
            required(communityId,"缺少小区信息"); required(oweId,"缺少账单明细");
            required(storeId,"请先使用物业员工账号登录"); required(userId,"请先使用物业员工账号登录");
            if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,80}")) throw new IllegalArgumentException("请求标识无效，请刷新页面");
            CommunityMemberDto member = new CommunityMemberDto();
            member.setCommunityId(communityId); member.setMemberId(storeId);
            member.setMemberTypeCd(CommunityMemberDto.MEMBER_TYPE_PROPERTY); member.setAuditStatusCd(CommunityMemberDto.AUDIT_STATUS_NORMAL);
            List<CommunityMemberDto> memberships = communityInnerServiceSMOImpl.getCommunityMembers(member);
            if (memberships == null || memberships.isEmpty()) return result(403,"无权操作该小区账单",0,false);
            BillOweFeeDto query = new BillOweFeeDto(); query.setCommunityId(communityId); query.setOweId(oweId); query.setCurBill("T");
            List<BillOweFeeDto> bills = feeInnerServiceSMOImpl.queryBillOweFees(query);
            if (bills == null || bills.size() != 1) return result(400,"账单不存在、已过期或不属于当前小区",0,false);
            BillOweFeeDto bill = bills.get(0);
            if (!communityId.equals(bill.getCommunityId()) || !oweId.equals(bill.getOweId())) return result(403,"账单不属于当前小区",0,false);
            if (!("1000".equals(bill.getState()) || "3000".equals(bill.getState()))) return result(400,"该账单已支付或不允许推送",0,false);
            if (new BigDecimal(bill.getBillAmountOwed()).signum() <= 0) return result(400,"账单没有待缴金额",0,false);
            return sendLocked(bill, query, requestId);
        } catch (IllegalArgumentException e) {
            return result(400,e.getMessage(),0,false);
        } catch (Exception e) {
            // Never expose HTTP exception text, access tokens, account secrets or OpenIDs.
            return result(500,"账单通知处理失败，请核查后台配置和发送记录后再操作",0,false);
        }
    }

    private JSONObject sendLocked(BillOweFeeDto bill, BillOweFeeDto query, String requestId) throws Exception {
        String suffix = bill.getCommunityId() + ":" + bill.getOweId();
        String receiptKey = "BILL_NOTIFY_REQUEST:" + suffix + ":" + requestId;
        String previous = cached(receiptKey);
        if (!StringUtil.isEmpty(previous)) {
            JSONObject receipt = JSON.parseObject(previous);
            if (receipt.getIntValue("code") != 202 || !receipt.getJSONObject("data").getBooleanValue("retryable")) return receipt;
        }
        String lock = "BILL_NOTIFY_LOCK:" + suffix;
        String nonce = UUID.randomUUID().toString();
        if (!acquire(lock,nonce)) return result(409,"该账单正在推送，请稍后核对结果",0,false);
        try {
            // Check again after acquiring the lock: a concurrent request may have completed.
            previous = cached(receiptKey);
            if (!StringUtil.isEmpty(previous)) {
                JSONObject receipt = JSON.parseObject(previous);
                if (receipt.getIntValue("code") != 202 || !receipt.getJSONObject("data").getBooleanValue("retryable")) return receipt;
            }
            if (!StringUtil.isEmpty(cached("BILL_NOTIFY_COOLDOWN:" + suffix))) return result(429,"刚刚已推送该账单，请至少间隔一分钟再操作",0,false);
            SmallWeChatDto accountQuery = new SmallWeChatDto(); accountQuery.setObjId(bill.getCommunityId());
            accountQuery.setObjType(SmallWeChatDto.OBJ_TYPE_COMMUNITY); accountQuery.setWeChatType("1100");
            List<SmallWeChatDto> accounts = smallWeChatInnerServiceSMOImpl.querySmallWeChats(accountQuery);
            if (accounts == null || accounts.size() != 1) return result(400,"请为当前小区配置唯一的公众号账号",0,false);
            SmallWeChatDto account = accounts.get(0);
            SmallWechatAttrDto attr = new SmallWechatAttrDto(); attr.setCommunityId(bill.getCommunityId());
            attr.setWechatId(account.getWeChatId()); attr.setSpecCd(SmallWechatAttrDto.SPEC_CD_OWE_FEE_TEMPLATE);
            List<SmallWechatAttrDto> attrs = smallWechatAttrInnerServiceSMOImpl.querySmallWechatAttrs(attr);
            if (attrs == null || attrs.size() != 1 || StringUtil.isEmpty(attrs.get(0).getValue()) || attrs.get(0).getValue().contains("请")) return result(400,"请先配置公众号欠费推送模板 ID",0,false);
            OwnerAppUserDto ownerQuery = new OwnerAppUserDto(); ownerQuery.setCommunityId(bill.getCommunityId());
            ownerQuery.setAppType(OwnerAppUserDto.APP_TYPE_WECHAT); ownerQuery.setState(OwnerAppUserDto.STATE_AUDIT_SUCCESS);
            List<OwnerAppUserDto> bindings = ownerAppUserInnerServiceSMOImpl.queryOwnerAppUsers(ownerQuery);
            Set<String> recipients = new LinkedHashSet<String>();
            if (bindings != null) for (OwnerAppUserDto binding : bindings) {
                if (bill.getOwnerId() != null && bill.getOwnerId().equals(binding.getOwnerId())
                        && OwnerAppUserDto.APP_TYPE_WECHAT.equals(binding.getAppType())
                        && OwnerAppUserDto.STATE_AUDIT_SUCCESS.equals(binding.getState()) && !StringUtil.isEmpty(binding.getOpenId())) recipients.add(binding.getOpenId());
            }
            if (recipients.isEmpty()) return result(400,"该业主尚未完成公众号微信绑定，无法推送",0,false);
            if (recipients.size() > 10) return result(400,"该业主微信绑定数量异常，请先核对绑定信息",0,false);
            String link = link(bill,account);
            String progressKey = "BILL_NOTIFY_PROGRESS:" + suffix;
            String raw = cached(progressKey);
            JSONObject progress = StringUtil.isEmpty(raw) ? new JSONObject() : JSON.parseObject(raw);
            if (!account.getAppId().equals(progress.getString("appId")) || "ACCEPTED".equals(progress.getString("status"))) progress = new JSONObject();
            progress.put("appId",account.getAppId());
            JSONObject delivered = progress.getJSONObject("recipients");
            if (delivered == null) { delivered = new JSONObject(); progress.put("recipients",delivered); }
            for (String openId : recipients) if ("UNKNOWN".equals(delivered.getString(DigestUtils.sha256Hex(openId)))) {
                return persist(receiptKey,result(202,"上次发送结果尚不确定，请核查微信收件情况，勿重复发送",accepted(delivered),false));
            }
            String token = accessToken(account);
            if (StringUtil.isEmpty(token)) return result(400,"无法获取公众号访问凭证，请核对 AppID、密钥和微信接口权限",0,false);
            int rejected = 0;
            for (String openId : recipients) {
                String recipient = DigestUtils.sha256Hex(openId);
                if ("ACCEPTED".equals(delivered.getString(recipient))) continue;
                // Persist the uncertain state BEFORE network I/O, allowing safe recovery after a crash.
                delivered.put(recipient,"UNKNOWN"); progress.put("status","PENDING");
                remember(progressKey,progress.toJSONString(),0);
                JSONObject reply;
                try {
                    ResponseEntity<String> response = deliver(token,message(bill,openId,attrs.get(0).getValue(),link));
                    reply = JSONObject.parseObject(response.getBody());
                    if (!response.getStatusCode().is2xxSuccessful() || reply == null || reply.getInteger("errcode") == null) throw new IllegalStateException("Uncertain WeChat response");
                    if (reply.getIntValue("errcode") != 0) { delivered.remove(recipient); rejected++; }
                    else if (StringUtil.isEmpty(reply.getString("msgid"))) throw new IllegalStateException("Missing WeChat message ID");
                    else delivered.put(recipient,"ACCEPTED");
                } catch (Exception e) {
                    return persist(receiptKey,result(202,"微信发送结果尚不确定，请核查收件情况后联系管理员，勿重复发送",accepted(delivered),false));
                }
                remember(progressKey,progress.toJSONString(),0);
            }
            int count = accepted(delivered);
            if (rejected > 0) return persist(receiptKey,result(count > 0 ? 202 : 400,
                    count > 0 ? "部分通知已被微信受理，剩余收件人被微信拒绝；再次操作仅重试未受理收件人" : "微信拒绝发送，请核对模板、关注状态和公众号权限后重试",count,true));
            try {
                List<BillOweFeeDto> latest = feeInnerServiceSMOImpl.queryBillOweFees(query);
                if (latest == null || latest.size() != 1 || "2000".equals(latest.get(0).getState())) return persist(receiptKey,result(202,"通知已被微信受理，但账单状态已变化，请刷新核对",count,false));
                BillOweFeeDto update = new BillOweFeeDto(); update.setCommunityId(bill.getCommunityId());
                update.setOweId(bill.getOweId()); update.setFeeId(bill.getFeeId()); update.setBillId(bill.getBillId()); update.setState(BillOweFeeDto.STATE_SEND_OWNER);
                if (feeInnerServiceSMOImpl.updateBillOweFees(update) != 1) throw new IllegalStateException("Bill state not updated");
            } catch (Exception e) {
                return persist(receiptKey,result(202,"微信已受理通知，后台状态暂未同步；可再次操作同步状态，不会重复通知已受理收件人",count,true));
            }
            JSONObject success = result(0,"微信已受理账单通知，后台发送状态已更新",count,false);
            persist(receiptKey,success);
            remember("BILL_NOTIFY_COOLDOWN:" + suffix,"1",60);
            progress.put("status","ACCEPTED"); remember(progressKey,progress.toJSONString(),RECEIPT_TTL);
            return success;
        } finally { release(lock,nonce); }
    }

    private int accepted(JSONObject recipients) { int count=0; for (Object value:recipients.values()) if ("ACCEPTED".equals(value)) count++; return count; }
    private JSONObject persist(String key,JSONObject receipt) { remember(key,receipt.toJSONString(),RECEIPT_TTL); return receipt; }
    private JSONObject result(int code,String msg,int count,boolean retryable) {
        JSONObject out=new JSONObject(); out.put("code",code); out.put("msg",msg);
        JSONObject data=new JSONObject(); data.put("acceptedCount",count); data.put("retryable",retryable); out.put("data",data); return out;
    }
    private void required(String value,String message) { if (StringUtil.isEmpty(value)) throw new IllegalArgumentException(message); }
    private String link(BillOweFeeDto bill,SmallWeChatDto account) throws Exception {
        String page = FeeDto.PAYER_OBJ_TYPE_ROOM.equals(bill.getPayerObjType()) ? feePage() : MappingCache.getValue("WECHAT","OWE_CAR_FEE_PAGE");
        required(page,"请先配置业主账单页面公网地址");
        URI uri = new URI(page);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getHost().equals("localhost") || uri.getHost().matches("[0-9.]+") || uri.getUserInfo()!=null) throw new IllegalArgumentException("账单页面必须配置可供微信访问的 HTTPS 公网域名");
        if (FeeDto.PAYER_OBJ_TYPE_ROOM.equals(bill.getPayerObjType())) page = new URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),"/",null,null).toString()+"#/pages/roomFeeListNew/roomFeeListNew?roomId=";
        return page + URLEncoder.encode(bill.getPayObjId(),"UTF-8") + "&wAppId=" + URLEncoder.encode(account.getAppId(),"UTF-8");
    }
    private String message(BillOweFeeDto bill,String openId,String template,String link) {
        PropertyFeeTemplateMessage message=new PropertyFeeTemplateMessage(); message.setTouser(openId); message.setTemplate_id(template); message.setUrl(link);
        Data data=new Data(); data.setFirst(new Content(bill.getFeeTypeName()+"提醒")); data.setKeyword1(new Content(bill.getPayerObjName()));
        data.setKeyword2(new Content(new BigDecimal(bill.getBillAmountOwed()).setScale(2,BigDecimal.ROUND_HALF_UP).toPlainString()));
        data.setKeyword3(new Content(bill.getFeeEndTime()+"至"+bill.getDeadlineTime())); data.setRemark(new Content("请核对账单详情，如有疑问请联系物业")); message.setData(data);
        return JSON.toJSONString(message);
    }
    protected String feePage() { return MappingCache.getValue("WECHAT","OWE_FEE_PAGE"); }
    protected String accessToken(SmallWeChatDto account) { return WechatFactory.getAccessToken(account.getAppId(),account.getAppSecret()); }
    protected RestTemplate notificationClient() {
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(10000); factory.setReadTimeout(20000);
        return new RestTemplate(factory);
    }
    protected ResponseEntity<String> deliver(String token,String payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON_UTF8);
        return notificationClient().postForEntity("https://api.weixin.qq.com/cgi-bin/message/template/send?access_token="+token,new HttpEntity<String>(payload,headers),String.class);
    }
    private Jedis redis() { return ApplicationContextFactory.getBean("jedisPool",JedisPool.class).getResource(); }
    protected String cached(String key) { try (Jedis redis=redis()) { return redis.get(key); } }
    // Incomplete progress must survive until reconciled; expiry could resend an uncertain message.
    protected void remember(String key,String value,int seconds) { try (Jedis redis=redis()) { if (seconds > 0) redis.setex(key,seconds,value); else redis.set(key,value); } }
    protected boolean acquire(String key,String token) { try (Jedis redis=redis()) { return "OK".equals(redis.set(key,token,"NX","EX",600)); } }
    protected void release(String key,String token) { try (Jedis redis=redis()) { redis.eval("if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",Collections.singletonList(key),Collections.singletonList(token)); } }
}
