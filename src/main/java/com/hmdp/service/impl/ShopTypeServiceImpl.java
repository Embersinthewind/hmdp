package com.hmdp.service.impl;


import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private ShopTypeMapper shopTypeMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result listShopTypes() {
        String key = CACHE_SHOP_TYPE_KEY;
        //1.读redis缓存
        // 获取范围：0 到 -1 表示全部
        List<String> cacheList = stringRedisTemplate.opsForList().range(key, 0, -1);
        //2.判断缓存是否命中
        if (cacheList != null && !cacheList.isEmpty()) {
            //2.1 缓存命中，返回商铺类型集合
            // opsForList().range() 返回的是 JSON 字符串列表，直接 Result.ok(cacheList) 返回给前端就是一堆字符串，不是对象。
            // 需要反序列化
            List<ShopType> list = cacheList.stream()
                    .map(json -> JSONUtil.toBean(json, ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(list);
        }
        //2.2 缓存未命中
        //3.从数据库中查商铺类型,按 sort 升序
        List<ShopType> shopTypeList = shopTypeMapper.selectList(new LambdaQueryWrapper<ShopType>().orderByAsc(ShopType::getSort));
        //4.判断商铺类型是否存在
        if (shopTypeList == null || shopTypeList.isEmpty()) {
            //4.2商铺类型不存在，返回提示
            return Result.fail("商铺类型不存在");
        }
        //4.2商铺存在，写入redis，需要序列化
        List<String> jsonList = shopTypeList.stream()
                .map(shopType -> JSONUtil.toJsonStr(shopType))
                .collect(Collectors.toList());
        stringRedisTemplate.delete(key); // 1. 清空旧 List
        stringRedisTemplate.opsForList().rightPushAll(key, jsonList); // 2. 写入新 List
        stringRedisTemplate.expire(key, CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES); // 3. 设置过期
        //返回商铺信息
        return Result.ok(shopTypeList);
    }
}
