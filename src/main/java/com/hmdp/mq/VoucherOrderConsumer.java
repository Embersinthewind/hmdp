package com.hmdp.mq;

import com.alibaba.fastjson.JSON;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
@RocketMQMessageListener(
        topic = "hmdp-seckill-order-topic",
        consumerGroup = "hmdp-seckill-order-consumer-group",
        maxReconsumeTimes = 3
)
public class VoucherOrderConsumer implements RocketMQListener<String> {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Override
    public void onMessage(String message) {
        VoucherOrder voucherOrder =
                JSON.parseObject(message, VoucherOrder.class);

        voucherOrderService.createVoucherOrder(voucherOrder);
    }
}

