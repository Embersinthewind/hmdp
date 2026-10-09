
package com.hmdp.mq;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
        topic = "hmdp-test-topic",
        consumerGroup = "hmdp-test-consumer-group"
)
public class RocketMQTestConsumer
        implements RocketMQListener<String> {

    @Override
    public void onMessage(String message) {
        System.out.println("收到 RocketMQ 消息：" + message);
    }
}