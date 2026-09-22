package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;


public class SimpleRedisLock {
    /**
     * 锁前缀
     */
    private final String key_Prefix = "lock:";

    /**
     * 业务名称
     * 参数，由用户传入，可变，不能为final
     */
    private String name;

    /**
     * spring下的redis客户端
     */
    private final StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 获取锁
     *
     * @param timeoutSed
     * @return
     */
    public boolean tryLock(long timeoutSed) {
        //获取当前线程id 作为 线程标识
        long threadId = Thread.currentThread().getId();
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key_Prefix + name, threadId + "", timeoutSed, TimeUnit.SECONDS);
        //装箱拆箱问题，不能直接返回success（容易空指针）
        return Boolean.TRUE.equals(success);
    }


    /**
     * 释放锁
     */
    public void unlock() {
        stringRedisTemplate.delete(key_Prefix + name);
    }
}
