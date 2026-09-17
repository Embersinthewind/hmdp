package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 写入Redis
     * 将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
     *
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        //将任意Java对象 序列化 为json并存储
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 逻辑过期
     * 将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
     *
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期时间 封装到RedisData
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //将RedisData 序列化 为Json 
        String JsonValue = JSONUtil.toJsonStr(redisData);
        //写入Redis 
        stringRedisTemplate.opsForValue().set(key, JsonValue);
    }


    /**
     * 缓存穿透
     * 根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
     *
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断缓存是否命中
        if (StrUtil.isNotBlank(json)) {
            //2.1 缓存命中，直接返回信息(Json转Java对象)
            return JSONUtil.toBean(json, type);
        }
        //②缓存穿透——先判断json是否为空对象(这里用的是空字符串)，是则直接结束
        if (json != null) {
            //返回空值
            return null;
        }
        //2.2 缓存未命中
        //3.从数据库中查（函数式编程——查询函数由参数Function决定）
        R r = dbFallback.apply(id);
        //4.判断商铺是否存在
        if (r == null) {
            //①缓存穿透——数据不存在时，向缓存写入null对象(这里使用空字符串，并且更改缓存时间（无意义对象不需要缓存太长时间）)
            stringRedisTemplate.opsForValue().set(key, "", time, unit);
            return null;
        }
        //4.2数据存在，写入redis
        this.set(key, r, time, unit);

        //返回信息
        return r;
    }


    //创建线程池（逻辑过期——缓存重建）
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicalExpire(String keyPrefix, String lockPrefix, ID id, Class<R> type,
                                            Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 查缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        // 2. 反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 3. 未过期直接返回
        if (expireTime.isAfter(LocalDateTime.now())) {
            return JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), type);
        }

        // 4. 过期，尝试重建
        String lockKey = lockPrefix + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            // 4.1 双重检查：重新读缓存
            String newJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(newJson)) {
                RedisData newRedisData = JSONUtil.toBean(newJson, RedisData.class);
                if (newRedisData.getExpireTime().isAfter(LocalDateTime.now())) {
                    unLock(lockKey);
                    return JSONUtil.toBean(JSONUtil.toJsonStr(newRedisData.getData()), type);
                }
            }
            // 4.2 提交异步重建
            try {
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        saveDataToRedis(keyPrefix, id, time, unit, dbFallback);
                    } finally {
                        unLock(lockKey);
                    }
                });
            } catch (Exception e) {
                unLock(lockKey); // ✅ 提交失败，主线程释放锁防死锁
                log.error("线程池提交失败，已释放锁", e);
            }
        }
        // 5. 未抢到锁，直接返回旧数据
        return r;
    }

    public <R, ID> void saveDataToRedis(String keyPrefix, ID id, Long time, TimeUnit unit,
                                        Function<ID, R> dbFallback) {
        String key = keyPrefix + id;
        R r = dbFallback.apply(id);
        if (r == null) {
            log.warn("预热数据缓存失败，数据不存在，id = {}", id);
            return;
        }
        // 逻辑过期时间 = 当前时间 + time 单位
        long seconds = unit.toSeconds(time);
        RedisData redisData = new RedisData(LocalDateTime.now().plusSeconds(seconds), r);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 获取锁
     *
     * @param key
     * @return
     */
    public boolean tryLock(String key) {
        //尝试获取锁——setnx 👉 setIfAbsent
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     *
     * @param key
     * @return
     */
    public void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
