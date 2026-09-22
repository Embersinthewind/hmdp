package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.User;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.jetbrains.annotations.NotNull;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券信息
        SeckillVoucher seckillVoucher = seckillVoucherService
                .query()
                .eq("voucher_id", voucherId)
                .one();
        if (seckillVoucher == null) {
            //不存在
            return Result.fail("优惠券不存在");
        }
        //存在
        //2.秒杀是否开始
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        LocalDateTime endTime = seckillVoucher.getEndTime();
        LocalDateTime nowTime = LocalDateTime.now();
        if (nowTime.isBefore(beginTime)) {
            // 秒杀尚未开始
            return Result.fail("秒杀尚未开始!");
        }
        if (nowTime.isAfter(endTime)) {
            // 秒杀已结束
            return Result.fail("秒杀已结束!");
        }
        // 秒杀进行中
        Long userId = UserHolder.getUser().getId();

        // 创建分布式锁对象
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        // 获取锁
        boolean isLock = lock.tryLock(1200);
        if (!isLock) {
            //失败
            return Result.fail("同一用户不能重复下单！");
        }
        //成功
        try {
            //获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId, userId);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId, Long userId) {
        //3.一人一单
        //3.一人一单
        //3.1 根据 优惠券id 和 用户id 查询订单

        //3.2 判断订单是否存在
        Long count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if (count > 0) {
            // 存在   用户已经购买过
            return Result.fail("用户已经抢过该秒杀券！");
        }
        //不存在
        //4.库存是否充足
        // ①优惠券id正确 ②stock前后未发生改变（线程安全）
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId).gt("stock", 0) //①voucher_id=voucherId    ②stock > 0
                .update();
        if (!success) {
            //库存不足
            return Result.fail("库存不足!");
        }
        //库存充足

        //5.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        long orderId = redisIdWorker.nextId("order:seckill:");
        voucherOrder.setId(orderId);

        //用户id
        voucherOrder.setUserId(userId);

        //代金券id
        voucherOrder.setVoucherId(voucherId);

        //6.将订单写入数据库
        save(voucherOrder);

        //7.返回订单id
        return Result.ok(orderId);
    }

}
