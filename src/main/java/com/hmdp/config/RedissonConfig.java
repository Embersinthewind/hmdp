package com.hmdp.config;


import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class RedissonConfig {
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        //配置类
        Config config = new Config();
        //添加redis相关配置(地址/密码等)
        config.useSingleServer().setAddress("redis://localhost:6379");
        //创建客户端
        return Redisson.create(config);
    }
}
