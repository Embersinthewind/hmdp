package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static sun.security.provider.certpath.BuildStep.FOLLOW;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1.获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("用户未登录！");
        }
        Long userId = user.getId();
        String key = RedisConstants.FOLLOW + userId.toString();
        //2.判断是否关注
        if (isFollow) {
            //true 未关注👉点击关注 新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                // 把关注用户的id，放入redis的set集合 sadd userId followerUserId
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            //已关注👉点击取消关注 DELETE FROM tb_follow WHERE user_id = ? AND follow_user_id = ?
            boolean isSuccess = remove(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, userId)
                    .eq(Follow::getFollowUserId, followUserId));
            if (isSuccess) {
                // 把关注用户的id从redis集合中移除
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }


        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //1.获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("用户未登录！");
        }
        Long userId = user.getId();
        //2.判断是否关注 查数据库
        // Long count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        long count = count(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFollowUserId, followUserId));
        //3.count>0 有记录则已关注，返回true，反之未关注返回false
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommon(Long followUserId) {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.登录用户的关注
        String key1 = RedisConstants.FOLLOW + userId.toString();
        //3.另一个用户的关注
        String key2 = RedisConstants.FOLLOW + followUserId.toString();
        //4.求交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (intersect != null && intersect.isEmpty()) {
            //无交集
            return Result.ok(Collections.emptyList());
        }
        //有交集
        //5.解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //6.根据id集合查询用户并做数据脱敏
        List<UserDTO> users = userService.listByIds(ids).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(users);
    }


}
