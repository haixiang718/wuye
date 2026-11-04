package com.java110.api.bmo.owner.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.java110.api.bmo.owner.IOwnerBMO;
import com.java110.api.bmo.room.IRoomBMO;
import com.java110.core.context.DataFlowContext;
import com.java110.dto.RoomDto;
import com.java110.dto.owner.OwnerDto;
import com.java110.dto.owner.OwnerRoomRelDto;
import com.java110.intf.community.IRoomInnerServiceSMO;
import com.java110.intf.user.IOwnerInnerServiceSMO;
import com.java110.intf.user.IOwnerRoomRelInnerServiceSMO;
import com.java110.utils.factory.ApplicationContextFactory;
import com.java110.utils.util.DateUtil;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import java.util.*;

/** Validates and submits a new owner and multiple room relations as one existing order. */
@Service
public class OwnerRoomCreationService {
    @Autowired private IRoomInnerServiceSMO roomInnerServiceSMOImpl;
    @Autowired private IOwnerRoomRelInnerServiceSMO ownerRoomRelInnerServiceSMOImpl;
    @Autowired private IOwnerInnerServiceSMO ownerInnerServiceSMOImpl;
    @Autowired private IRoomBMO roomBMOImpl;

    private List<String> roomIds(JSONObject input) {
        Object raw=input.get("roomIds");
        if(raw==null && !input.containsKey("roomIds"))return Collections.emptyList();
        if(!(raw instanceof JSONArray))throw new IllegalArgumentException("房屋列表必须是数组");
        JSONArray values=(JSONArray)raw;
        if(values.size()>50)throw new IllegalArgumentException("一次最多绑定50套房屋");
        List<String> ids=new ArrayList<String>();
        for(Object value:values){
            if(!(value instanceof String)||!((String)value).matches("[A-Za-z0-9_-]{1,64}"))
                throw new IllegalArgumentException("房屋编号无效，请重新选择");
            if(ids.contains(value))throw new IllegalArgumentException("不能重复选择同一套房屋");
            ids.add((String)value);
        }
        return ids;
    }

    public void validate(JSONObject input) {
        List<String> ids=roomIds(input);
        if(ids.isEmpty())return;
        if(input.containsKey("roomId"))throw new IllegalArgumentException("不能同时提交单房和多房绑定参数");
        if(!"1001".equals(input.getString("ownerTypeCd")))
            throw new IllegalArgumentException("请在添加业主时选择房屋，成员不支持此操作");
        for(String field:Arrays.asList("name","link","idCard","communityId","storeId")){
            String value=input.getString(field);
            if(value==null||value.trim().length()==0)throw new IllegalArgumentException("请完整填写姓名、手机号、身份证和所属小区信息");
            input.put(field,value.trim());
        }
        input.put("idCard",input.getString("idCard").toUpperCase(Locale.ROOT));
        if(!input.getString("idCard").matches("([1-9][0-9]{14}|[1-9][0-9]{16}[0-9X])"))
            throw new IllegalArgumentException("请填写有效的身份证号");
        if(!input.getString("link").matches("1[0-9]{10}"))
            throw new IllegalArgumentException("请填写有效的手机号");
        OwnerDto owner=new OwnerDto();
        owner.setCommunityId(input.getString("communityId"));
        owner.setOwnerTypeCd("1001");
        owner.setIdCard(input.getString("idCard"));
        List<OwnerDto> existing=ownerInnerServiceSMOImpl.queryOwnerMembers(owner);
        if(existing==null)throw new IllegalStateException("业主信息校验失败，请稍后重试");
        if(!existing.isEmpty())throw new IllegalArgumentException("该身份证已存在业主档案，请在原业主的房屋绑定中追加房屋");
        validateRooms(input,ids);
    }

    private void validateRooms(JSONObject input,List<String> ids) {
        for(String id:ids){
            RoomDto room=new RoomDto();
            room.setRoomId(id);room.setCommunityId(input.getString("communityId"));room.setStatusCd("0");
            List<RoomDto> rooms=roomInnerServiceSMOImpl.queryRooms(room);
            if(rooms==null||rooms.size()!=1)throw new IllegalArgumentException("房屋不存在或已停用，请重新选择");
            if(!input.getString("communityId").equals(rooms.get(0).getCommunityId())
                    ||!id.equals(rooms.get(0).getRoomId()))
                throw new IllegalArgumentException("只能绑定当前小区的房屋");
            OwnerRoomRelDto relation=new OwnerRoomRelDto();
            relation.setCommunityId(input.getString("communityId"));relation.setRoomId(id);
            if(ownerRoomRelInnerServiceSMOImpl.queryOwnerRoomRelsCount(relation)>0)
                throw new IllegalArgumentException("房屋已绑定业主，请刷新房屋列表后重新选择");
        }
    }

    public void append(JSONObject input,DataFlowContext context,IOwnerBMO ownerBMO) {
        String start=DateUtil.getNow(DateUtil.DATE_FORMATE_STRING_A);
        for(String id:roomIds(input)){
            JSONObject relation=new JSONObject();
            relation.put("ownerId",input.getString("ownerId"));
            relation.put("communityId",input.getString("communityId"));
            relation.put("roomId",id);relation.put("state","2001");
            relation.put("startTime",start);relation.put("endTime","2037-01-01 00:00:00");
            relation.put("remark",input.getString("remark"));
            ownerBMO.sellRoom(relation,context);
            // Match the existing room.sellRoom binding behavior in the same order.
            JSONObject room=new JSONObject();
            room.put("roomId",id);room.put("communityId",input.getString("communityId"));
            room.put("state","2001");
            roomBMOImpl.updateShellRoom(room,context);
        }
    }

    /** Locks cover the synchronous order commit, not merely the preliminary validation. */
    public void commit(JSONObject input,Runnable submit) {
        List<String> ids=roomIds(input);
        boolean multiple=!ids.isEmpty();
        if(ids.isEmpty()&&input.containsKey("roomId")){
            String id=input.getString("roomId");
            if(id==null||!id.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("房屋编号无效");
            ids=Collections.singletonList(id);
        }
        if(ids.isEmpty()){submit.run();return;}
        List<String> keys=new ArrayList<String>();
        String community=input.getString("communityId");
        String card=input.getString("idCard");
        if(multiple){
            if(card==null)throw new IllegalArgumentException("请填写身份证号");
            keys.add("OWNER_CREATE_ID:"+community+":"+DigestUtils.sha256Hex(card.trim().toUpperCase(Locale.ROOT)));
        }
        for(String id:ids)keys.add("OWNER_CREATE_ROOM:"+community+":"+id);
        Collections.sort(keys);
        List<String> held=new ArrayList<String>();String token=UUID.randomUUID().toString();
        try{
            for(String key:keys){
                if(!acquire(key,token))throw new IllegalArgumentException("该业主或房屋正在处理，请稍后核对保存结果");
                held.add(key);
            }
            validate(input);
            if(!multiple)validateRooms(input,ids);
            submit.run();
        }finally{
            for(String key:held){
                try{release(key,token);}catch(RuntimeException ignored){/* bounded lease expires if Redis disconnects */}
            }
        }
    }
    private Jedis redis(){return ApplicationContextFactory.getBean("jedisPool",JedisPool.class).getResource();}
    protected boolean acquire(String key,String token){
        try(Jedis redis=redis()){return "OK".equals(redis.set(key,token,"NX","EX",600));}
    }
    protected void release(String key,String token){
        try(Jedis redis=redis()){
            redis.eval("if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",
                Collections.singletonList(key),Collections.singletonList(token));
        }
    }
}
