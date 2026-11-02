package com.java110.front.smo.api.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.java110.core.context.IPageData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;
import java.util.*;

/** Public front boundary: authenticate first, then authorize against server-side bindings. */
@Service
public class OwnerDataAccessService {
    private final RestTemplate restTemplate;
    private static final Set<String> PUBLIC = new HashSet<>(Arrays.asList(
        "area.listAreas", "community.listCommunitys", "communitys/queryCommunityMenu", "floor.queryFloors", "unit.queryUnits",
        "user.userSendSms", "owner.ownerRegister", "owner.ownerRegisterWxPhoto"));

    @Autowired public OwnerDataAccessService(RestTemplate restTemplate) { this.restTemplate = restTemplate; }

    public void authorize(IPageData pd, String service, JSONObject params) {
        String code = service.startsWith("/") ? service.substring(1) : service;
        if (PUBLIC.contains(code)) return;
        String userId = pd == null ? null : pd.getUserId();
        require(userId != null && !userId.isEmpty() && !"-1".equals(userId));
        JSONObject userQuery = object("userId", userId); userQuery.put("statusCd", "0");
        JSONArray users = query("user-service/userApi/getUsers", userQuery);
        require(users.size() == 1 && userId.equals(users.getJSONObject(0).getString("userId")));
        String level = users.getJSONObject(0).getString("levelCd");
        require(level != null && !level.isEmpty());
        if (!"02".equals(level) && !"03".equals(level)) return; // Existing staff privilege checks remain in force.
        // Some legacy management accounts use level 02. A persisted store relation,
        // rather than client headers or the level alone, identifies those staff.
        if (!query("store-service/storeApi/getStoreUserInfo", object("userId", userId)).isEmpty()) return;

        if ("owner.listAppUserBindingOwners".equals(code)) {
            JSONObject scoped = object("userId", userId);
            for (String key : Arrays.asList("page", "row", "communityId", "appType"))
                if (params.containsKey(key)) scoped.put(key, params.get(key));
            params.clear(); params.putAll(scoped);
            return;
        }
        if ("user.service.logout".equals(code)) { params.put("userId", userId); return; }
        if ("owner.appUserBindingOwner".equals(code)) {
            params.put("userId", userId);
            params.remove("state"); params.remove("openId"); params.remove("appUserId");
            return; // Identity matching + verification/approval are handled by the binding business.
        }
        JSONArray bindings = query("user-service/ownerAppUserApi/queryOwnerAppUsers", object("userId", userId));
        if ("owner.deleteAppUserBindingOwner".equals(code)) {
            boolean own = false;
            for (Object value : bindings) {
                JSONObject b = (JSONObject) value;
                if (userId.equals(b.getString("userId")) && equal(params.getString("appUserId"), b.getString("appUserId"))) own = true;
            }
            require(own); return;
        }
        String community = params.getString("communityId");
        require(community != null && !community.isEmpty());
        Set<String> owners = new HashSet<>();
        Set<String> members = new HashSet<>();
        for (Object value : bindings) {
            JSONObject binding = (JSONObject) value;
            if (userId.equals(binding.getString("userId")) && community.equals(binding.getString("communityId"))
                    && "12000".equals(binding.getString("state")) && binding.getString("memberId") != null) {
                owners.add(binding.getString("ownerId") == null ? binding.getString("memberId") : binding.getString("ownerId"));
                members.add(binding.getString("memberId"));
            }
        }
        require(!owners.isEmpty());
        if ("room.queryRoomsByOwner".equals(code)) {
            require(owners.contains(params.getString("ownerId"))); return;
        }
        if ("owner.queryOwnerCars".equals(code) || "parkingSpace.queryParkingSpacesByOwner".equals(code)) {
            require(members.contains(params.getString("ownerId")));
            Set<String> allowed = new HashSet<>(Arrays.asList("communityId", "ownerId", "page", "row", "carNum", "carId", "carTypeCd", "state"));
            params.keySet().retainAll(allowed);
            return;
        }
        if ("fee.listFee".equals(code)) {
            if (params.getString("payerObjType") == null || params.getString("payerObjType").isEmpty()) params.put("payerObjType", "3333");
            require(ownsObject(community, owners, members, params.getString("payerObjType"), params.getString("payerObjId"))); return;
        }
        if ("fee.queryFeeDetail".equals(code) || "feeDiscount/computeFeeDiscount".equals(code)) {
            require(params.getString("feeId") != null && !params.getString("feeId").isEmpty());
            JSONObject q = object("feeId", params.getString("feeId")); q.put("communityId", community);
            JSONArray fees = query("fee-service/feeApi/queryFees", q);
            require(fees.size() == 1);
            JSONObject fee = fees.getJSONObject(0);
            require(community.equals(fee.getString("communityId")) && params.getString("feeId").equals(fee.getString("feeId")));
            require(ownsObject(community, owners, members, fee.getString("payerObjType"), fee.getString("payerObjId")));
            if ("feeDiscount/computeFeeDiscount".equals(code)) {
                params.put("payerObjType", fee.getString("payerObjType"));
                params.put("payerObjId", fee.getString("payerObjId"));
            }
            return;
        }
        if ("feeApi/listOweFees".equals(code)) {
            require(ownsObject(community, owners, members, params.getString("payObjType"), params.getString("payObjId"))); return;
        }
        if ("fee.queryFeeByParkingSpace".equals(code)) {
            require(ownsObject(community, owners, members, "parking", params.getString("psId"))); return;
        }
        // Do not let a resident choose a different client AppID to reach management APIs.
        throw new SecurityException("业主账号无权访问该功能");
    }

    private boolean ownsObject(String community, Set<String> owners, Set<String> members, String type, String id) {
        if (id == null || id.isEmpty()) return false;
        for (String owner : ("3333".equals(type) ? owners : members)) {
            JSONObject q = object("ownerId", owner); q.put("communityId", community);
            if ("3333".equals(type)) {
                for (Object value : query("community-service/roomApi/queryRoomsByOwner", q))
                    if (id.equals(((JSONObject)value).getString("roomId"))) return true;
            } else if ("6666".equals(type) || "parking".equals(type)) {
                for (Object value : query("user-service/ownerCarApi/queryOwnerCars", q)) {
                    JSONObject car = (JSONObject)value;
                    if (id.equals(car.getString("parking".equals(type) ? "psId" : "carId"))) return true;
                }
            }
        }
        return false;
    }

    private JSONArray query(String endpoint, JSONObject params) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON_UTF8);
        JSONArray values = JSON.parseArray(restTemplate.postForObject("http://" + endpoint,
                new HttpEntity<>(params.toJSONString(), headers), String.class));
        if (values == null) throw new SecurityException("暂时无法核验业主权限，请稍后重试");
        return values;
    }
    private static JSONObject object(String key, String value) { JSONObject q = new JSONObject(); q.put(key, value); return q; }
    private static boolean equal(String a, String b) { return a != null && !a.isEmpty() && a.equals(b); }
    private static void require(boolean condition) { if (!condition) throw new SecurityException("未绑定该房产或无权查看该费用"); }
}
