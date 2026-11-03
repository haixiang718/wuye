package com.java110.core.factory;

import com.alibaba.fastjson.JSONObject;
import com.java110.utils.cache.CommonCache;
import com.java110.utils.util.StringUtil;
import java.util.UUID;

/** OAuth-verified identity only: this credential never grants property access by itself. */
public final class OwnerWechatSession {
    public static final int TTL = 1800;
    public static final String PREFIX = "owner-wechat-identity:";
    private OwnerWechatSession() { }

    public static String issue(String appId,String openId,String communityId) {
        if(StringUtil.isEmpty(appId)||StringUtil.isEmpty(openId))throw new IllegalArgumentException("微信身份无效，请重新授权");
        String token=UUID.randomUUID().toString();
        JSONObject identity=new JSONObject();identity.put("wechatAppId",appId);identity.put("openId",openId);
        identity.put("communityId",communityId);identity.put("expiresAt",System.currentTimeMillis()+TTL*1000L);
        CommonCache.setValue(PREFIX+token,identity.toJSONString(),TTL);
        return token;
    }

    public static JSONObject read(String token) {
        if(token==null||!token.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
            throw new IllegalArgumentException("微信登录已过期，请重新授权");
        String raw=CommonCache.getValue(PREFIX+token);
        if(StringUtil.isEmpty(raw))throw new IllegalArgumentException("微信登录已过期，请重新授权");
        JSONObject identity=JSONObject.parseObject(raw);
        if(identity==null||StringUtil.isEmpty(identity.getString("wechatAppId"))||StringUtil.isEmpty(identity.getString("openId"))
                || identity.getLongValue("expiresAt")<=System.currentTimeMillis())
            throw new IllegalArgumentException("微信登录已过期，请重新授权");
        return identity;
    }
}
