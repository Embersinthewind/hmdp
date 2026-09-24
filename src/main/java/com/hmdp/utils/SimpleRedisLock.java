package com.hmdp.utils;


import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;


public class SimpleRedisLock {
    /**
     * 锁前缀
     */
    private final String Key_Prefix = "lock:";
    /**
     * Id前缀
     * UUID随机数 + '-'
     */
    private final String ID_Prefix = UUID.randomUUID().toString(true) + "-";

    /**
     * 业务名称
     * 参数，由用户传入，可变，不能为final
     */
    private String name;

    /**
     * spring下的redis客户端
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 提前加载lua脚本
     */
    public static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        //ClassPathResource加载resources文件夹下的lua脚本
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        //设置返回类型
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


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
        //线程标识: Id前缀 - 线程id
        String threadId = ID_Prefix + Thread.currentThread().getId();
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(Key_Prefix + name, threadId, timeoutSed, TimeUnit.SECONDS);
        //装箱拆箱问题，不能直接返回success（容易空指针）
        return Boolean.TRUE.equals(success);
    }

    /**
     * Lua脚本实现释放锁
     * 保证了 判断标识 与 释放锁 操作的原子性和一致性
     * 使用一行代码实现
     */
    public void unlock() {
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(Key_Prefix + name),
                ID_Prefix + Thread.currentThread().getId()
        );
    }


    /**
     * 释放锁
     */
    // public void unlock() {
    //     // 判断线程标识是否一致
    //     // 获取当前线程标识
    //     String threadId = ID_Prefix + Thread.currentThread().getId();
    //     // 获取锁的线程标识
    //     String lockId = stringRedisTemplate.opsForValue().get(Key_Prefix + name);
    //     if (lockId.equals(threadId)) {
    //         // 一致，释放锁
    //         stringRedisTemplate.delete(Key_Prefix + name);
    //     }
    // }
}
