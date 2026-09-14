package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
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
        String shopKey = CACHE_SHOP_KEY + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        //2.判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            //2.1 缓存命中，直接返回商铺信息(Json转Java对象)
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //②缓存穿透——先判断商铺是否为空对象(这里用的是空字符串)，是则直接结束
        if (shopJson != null && shopJson.equals("")) {
            return Result.fail("商铺不存在");
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
            return Result.fail("商铺不存在");
        }
        //4.2商铺存在，写入redis
        stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // stringRedisTemplate.expire(shopKey,CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //返回商铺信息
        return Result.ok(shop);
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
}
