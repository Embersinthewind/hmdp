package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    /**
     * 开始时间戳
     */
    public static final long BEGIN_TIMESTAMP = 1767225600L;
    public static final int COUNT_BITS = 32;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成订单ID
     * @param keyPrefix
     * @return
     */
    public long nextId(String keyPrefix) {
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSeconds = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSeconds - BEGIN_TIMESTAMP;
        //2.生成序列号
        //2.1 获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        //2.2 自增长
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date); //(icr:keyPrefix:date) date从当天0开始增长
        //3.拼接 符号位(1bit) 时间戳(31bit) 序列号(32bit)
        return timestamp << COUNT_BITS | count; //时间戳左移32bit，低位32bit与序列号进行或运算 → 高位：时间戳 低位：序列号
    }

    public static void main(String[] args) {


    }
}
