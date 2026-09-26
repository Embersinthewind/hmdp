package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.RedisConstants.*;

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

    @Resource
    private RedissonClient redissonClient;

    /**
     * 提前加载lua脚本
     */
    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        //ClassPathResource加载resources文件夹下的lua脚本
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        //设置返回类型
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //@PostConstruct——类初始化时就加载
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());

    }

    //异步实现创建订单
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            //死循环，不断尝试获取订单需求
            while (true) {
                try {
                    //1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }

        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户 (多线程中不能使用UserHolder取用户)
        Long userId = voucherOrder.getUserId();

        Long voucherId = voucherOrder.getVoucherId();

        // 2.创建锁对象
        RLock lock = redissonClient.getLock(LOCK_ORDER_KEY + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        if (!isLock) {
            //失败
            log.error("同一用户不能重复下单！");
            return;
        }
        //成功
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }


    //创建代理对象
    private IVoucherOrderService proxy;
    /**
     * 主线程
     *
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //1.执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );

        int flag = result.intValue();
        //2.判断返回结果
        if (flag != 0) {
            //2.1 不为0，代表没有购买资格。 为1代表库存不足，为2代表重复下单
            return Result.fail(flag == 1 ? "库存不足！" : "同一用户不能重复下单！");
        }
        //2.2 为0，将 优惠券id，用户id，订单id 存入阻塞队列（用于后续子线程创建订单）
        //保存为VoucherOrder对象存入阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        long orderId = redisIdWorker.nextId(ORDER_SECKILL_KEY);
        voucherOrder.setId(orderId);
        //用户id
        voucherOrder.setUserId(userId);
        //优惠券id
        voucherOrder.setVoucherId(voucherId);
        //放入阻塞队列
        orderTasks.add(voucherOrder);

        //3.初始化代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //4.返回订单id
        return Result.ok(voucherId);
    }

    /**
     * 从阻塞队列中传入voucherOrder对象
     * voucherId，userId，orderId从voucherOrder获取
     *
     * @param voucherOrder
     * @return
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long voucherId = voucherOrder.getVoucherId();
        Long userId = voucherOrder.getUserId();

        Long count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        //判断用户是否已经下过单
        if (count > 0) {
            log.error("用户已经抢过该秒杀券！");
            return;
        }
        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId).gt("stock", 0) //①voucher_id=voucherId    ②stock > 0
                .update();
        if (!success) {
            //库存不足
            log.error("库存不足!");
            return;
        }
        //库存充足

        //将订单写入数据库
        save(voucherOrder);
    }


    /**
     * 秒杀优惠券
     * 核查优惠券（判断优惠券是否存在 + 秒杀是否开始） + 一人一单（分布式锁） + 创建订单
     *
     * @param voucherId
     * @return
     */
    /*@Override
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
        // SimpleRedisLock lock = new SimpleRedisLock(LOCK_ORDER_KEY + userId, stringRedisTemplate);

        // 使用Redisson创建分布式锁
        RLock lock = redissonClient.getLock(LOCK_ORDER_KEY + userId);

        // 获取锁
        boolean isLock = lock.tryLock();
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
    }*/
    /*@Transactional
    public Result createVoucherOrder(Long voucherId, Long userId) {
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
        long orderId = redisIdWorker.nextId(ORDER_SECKILL_KEY);
        voucherOrder.setId(orderId);

        //用户id
        voucherOrder.setUserId(userId);

        //代金券id
        voucherOrder.setVoucherId(voucherId);

        //6.将订单写入数据库
        save(voucherOrder);

        //7.返回订单id
        return Result.ok(orderId);
    }*/

}
