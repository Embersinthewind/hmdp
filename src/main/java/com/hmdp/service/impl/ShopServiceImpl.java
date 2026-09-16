package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private ShopMapper shopMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        //缓存穿透——①返回空对象
        // Shop shop = queryWithPassThrough(id);

        //缓存击穿——①互斥锁
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
    }


    /**
     * 缓存击穿
     *
     * @param id
     * @return
     */
    @Override
    public Shop queryWithMutex(Long id) {
        String shopKey = CACHE_SHOP_KEY + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        //2.判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            // 2.1 命中
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //检查空字符串（防缓存穿透）
        if (shopJson != null && shopJson.equals("")) {
            return null;
        }

        // 2.2 未命中——缓存击穿👉实现缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = false;

        try {
            isLock = tryLock(lockKey);
            // 3. 判断是否获取锁成功
            if (!isLock) {
                // 3.1 失败，休眠 并 重试
                Thread.sleep(50);
                return queryWithMutex(id); // 注意：这里递归返回后，会走最外层的 finally
            }

            // 3.2 成功
            // 再次从redis中查询商铺缓存是否存在（双重检查）
            shopJson = stringRedisTemplate.opsForValue().get(shopKey);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            // 也要检查空字符串（防穿透）
            if (shopJson != null && shopJson.isEmpty()) {
                return null;
            }

            // 从数据库中查商铺
            Shop shop = shopMapper.selectOne(new LambdaQueryWrapper<Shop>().eq(Shop::getId, id));
            // 判断商铺是否存在
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(shopKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            //模拟重建（重写redis）延时
            Thread.sleep(200);

            // 4.商铺存在，写入redis
            stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            // 返回数据
            return shop;

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // ✅ 修复：只有真正拿到锁的线程才去释放锁
            if (isLock) {
                //5. 释放锁
                unLock(lockKey);
            }
        }
    }

    /**
     * 缓存穿透
     *
     * @param id
     * @return
     */
    @Override
    public Shop queryWithPassThrough(Long id) {
        String shopKey = CACHE_SHOP_KEY + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        //2.判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            //2.1 缓存命中，直接返回商铺信息(Json转Java对象)
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //②缓存穿透——先判断商铺是否为空对象(这里用的是空字符串)，是则直接结束
        if (shopJson != null && shopJson.equals("")) {
            return null;
        }
        //2.2 缓存未命中
        //3.从数据库中查商铺
        Shop shop = shopMapper.selectOne(new LambdaQueryWrapper<Shop>().eq(Shop::getId, id));
        //4.判断商铺是否存在
        if (shop == null) {
            //4.1商铺不存在，返回404
            // return Result.fail("商铺不存在");
            //①缓存穿透——商铺不存在时，向缓存写入null对象(这里使用空字符串，并且更改缓存时间（无意义对象不需要缓存太长时间）)
            stringRedisTemplate.opsForValue().set(shopKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //4.2商铺存在，写入redis
        stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // stringRedisTemplate.expire(shopKey,CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //返回商铺信息
        return shop;
    }

    /**
     * 更新店铺信息
     *
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺信息不存在！");
        }
        //1.写数据库
        updateById(shop); //Service 层继承来的方法，Service层优先使用
        // shopMapper.updateById(shop); //直接调用 Mapper（DAO）层继承BaseMapper 自带方法
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }


    /**
     * 获取锁
     *
     * @param key
     * @return
     */
    @Override
    public boolean tryLock(String key) {
        //尝试获取锁——setnx 👉 setIfAbsent
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     *
     * @param key
     * @return
     */
    @Override
    public void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
