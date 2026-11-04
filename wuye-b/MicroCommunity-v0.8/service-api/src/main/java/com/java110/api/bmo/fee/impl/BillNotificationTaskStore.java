package com.java110.api.bmo.fee.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.java110.utils.factory.ApplicationContextFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import java.util.*;

/** Non-expiring tasks and an active index; every write is fenced by the task lease. */
@Service
public class BillNotificationTaskStore {
    public static final String PREFIX="BILL_PUBLICATION_TASK:";
    public static final String ACTIVE="BILL_PUBLICATION_ACTIVE";
    private Jedis redis(){return ApplicationContextFactory.getBean("jedisPool",JedisPool.class).getResource();}
    public JSONObject get(String key){try(Jedis redis=redis()){String raw=redis.get(key);return raw==null?null:JSON.parseObject(raw);}}
    public boolean lock(String key,String nonce){try(Jedis redis=redis()){return "OK".equals(redis.set(key+":LOCK",nonce,"NX","EX",600));}}
    public void unlock(String key,String nonce){try(Jedis redis=redis()){redis.eval("if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",Collections.singletonList(key+":LOCK"),Collections.singletonList(nonce));}}
    public boolean save(String key,String nonce,JSONObject task,boolean active){
        try(Jedis redis=redis()){
            Object saved=redis.eval("if redis.call('get',KEYS[1]) ~= ARGV[1] then return 0 end redis.call('set',KEYS[2],ARGV[2]); if ARGV[3] == '1' then redis.call('sadd',KEYS[3],KEYS[2]) else redis.call('srem',KEYS[3],KEYS[2]) end return 1",
                Arrays.asList(key+":LOCK",key,ACTIVE),Arrays.asList(nonce,task.toJSONString(),active?"1":"0"));
            return Long.valueOf(1).equals(saved);
        }
    }
    public Set<String> active(){try(Jedis redis=redis()){return redis.smembers(ACTIVE);}}
}
