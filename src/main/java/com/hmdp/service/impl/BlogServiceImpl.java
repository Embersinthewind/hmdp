package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    /**
     * 分页查询热门博客
     * 多条
     *
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            //查询每篇博客的作者
            queryBlogUser(blog);
            //查询当前登录用户对每篇博客是否点过赞
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }


    /**
     * 根据 id 查询一条博客详情
     * 单条
     *
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        // 根据id查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在!");
        }
        // 根据blog查询作者
        queryBlogUser(blog);
        //判断当前用户是否对该篇博客点过赞
        isBlogLiked(blog);
        // 返回blog
        return Result.ok(blog);
    }

    /**
     * 点赞 / 取消点赞
     *
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        //1.查询当前用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();

        String key = BLOG_LIKED_KEY + id;
        //2.判断redis中set集合是否存在该用户
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score != null) {
            //2.1 存在,取消点赞
            // 数据库点赞数 - 1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).gt("liked", 0).update();
            if (isSuccess) {
                // 从set集合中移除该用户
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        } else {
            //2.2 不存在,可以点赞
            // 数据库点赞数 + 1
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            if (isSuccess) {
                // 将该用户加入set集合
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }

        return Result.ok();
    }

    /**
     * 判断当前登录用户有没有给这条博客点过赞
     *
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 未登录，不设置 isLike
            return;
        }
        Long userId = user.getId();
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet()
                .score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 根据博客里的 userId，去用户表查出这个用户的昵称和头像，然后塞回博客对象里，这样前端就能显示“这篇博客是谁发的”
     *
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        // 防止历史脏数据（user_id 对应用户不存在）导致空指针
        if (user != null) {
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
    }


    /**
     * 点赞排行榜前5
     *
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;

        // 1. 从 ZSet 取 Top5  userId（按点赞时间升序）
        Set<String> top5 = stringRedisTemplate.opsForZSet()
                .range(key, 0, 4);

        if (CollUtil.isEmpty(top5)) {
            return Result.ok(Collections.emptyList());
        }

        // 2. 转成 Long 列表
        List<Long> userIds = top5.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());

        // 3. 用 FIELD 函数保证顺序
        String userIdsStr = StrUtil.join(",", userIds);
        List<UserDTO> userVOList = userService.query()
                .in("id", userIds)
                .last("ORDER BY FIELD(id, " + userIdsStr + ")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userVOList);
    }

    @Override
    public Result saveBlog(Blog blog) {
        //1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录");
        }
        Long userId = user.getId();
        blog.setUserId(userId);
        //2.保存探店笔记
        save(blog);
        //3.查询笔记作者的所有粉丝
        List<Follow> fans = followService.query().eq("follow_user_id", userId).list();
        //4.推送笔记id给所有粉丝
        for (Follow fan : fans) {
            //获取粉丝id
            Long fanId = fan.getUserId();
            //每个粉丝一个收件箱
            String fanKey = RedisConstants.FEED_KEY + fanId;
            //推送
            stringRedisTemplate.opsForZSet().add(fanKey, blog.getId().toString(), System.currentTimeMillis());
        }
        //5.返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.FEED_KEY + userId;
        // 2.滚动分页查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            //收件箱是空的
            return Result.ok();
        }

        // 4.解析数据：blogId、minTime（时间戳）、offset
        //保存blogId集合
        List<Long> ids = new ArrayList<>();
        //保存最小时间
        long minTime = 0;
        //保存偏移量     最小值为1（因为上一次滚动查询的最小值至少有1个）
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            // 4.1获取id
            ids.add(Long.valueOf(tuple.getValue()));
            // 4.2获取score（时间戳）
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                //有和当前最小时间重复的值,偏移量+1
                os++;
            } else {
                //当前时间不是最小值,进行更换,重置偏移量
                minTime = time;
                os = 1;
            }

        }

        // 5.根据blogId查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();
        for (Blog blog : blogs) {
            // 5.1根据blog查询作者
            queryBlogUser(blog);
            // 5.2判断当前用户是否对该篇博客点过赞
            isBlogLiked(blog);
        }


        // 6.封装并返回
        ScrollResult result = new ScrollResult();
        result.setList(blogs);
        result.setMinTime(minTime);
        result.setOffset(os);
        return Result.ok(result);
    }
}
