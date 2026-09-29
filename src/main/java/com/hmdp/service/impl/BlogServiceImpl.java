package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

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

    /**
     * 分页查询热门博客
     * 多条
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
        Boolean flag = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if (Boolean.TRUE.equals(flag)) {
            //2.1 存在,取消点赞
            // 数据库点赞数 - 1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).gt("liked", 0).update();
            if (isSuccess) {
                // 从set集合中移除该用户
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
            }
        } else {
            //2.2 不存在,可以点赞
            // 数据库点赞数 + 1
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            if (isSuccess) {
                // 将该用户加入set集合
                stringRedisTemplate.opsForSet().add(key, userId.toString());
            }
        }

        return Result.ok();
    }

    /**
     * 判断当前登录用户有没有给这条博客点过赞
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
        Boolean isLiked = stringRedisTemplate.opsForSet()
                .isMember(key, userId.toString());
        blog.setIsLike(Boolean.TRUE.equals(isLiked));
    }

    /**
     * 根据博客里的 userId，去用户表查出这个用户的昵称和头像，然后塞回博客对象里，这样前端就能显示“这篇博客是谁发的”
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
}
