package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
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

    @Override
    @Transactional
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
        //3.库存是否充足 ①优惠券id正确 ②stock前后未发生改变（线程安全）
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
        UserDTO user = UserHolder.getUser();
        voucherOrder.setUserId(user.getId());
        //代金券id
        voucherOrder.setVoucherId(voucherId);

        //6.将订单写入数据库
        save(voucherOrder);

        //7.返回订单id
        return Result.ok(orderId);
    }
}
