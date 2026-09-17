package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Shop   queryWithLogicalExpire(Long id);

    Shop queryWithMutex(Long id);

    Shop queryWithPassThrough(Long id);

    Result updateShop(Shop shop);

    boolean tryLock(String key);

    void unLock(String key);

    void saveShopToRedis(Long id, Long expireSeconds) throws InterruptedException;
}
