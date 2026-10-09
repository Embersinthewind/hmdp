package com.hmdp.mq;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class RocketMQTestProducer implements CommandLineRunner {

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Override
    public void run(String... args) {
        rocketMQTemplate.convertAndSend(
                "hmdp-test-topic",
                "Hello RocketMQ"
        );
        System.out.println("RocketMQ 测试消息发送完成");
    }
}