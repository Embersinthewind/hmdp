package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;
import static com.hmdp.utils.SystemConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final UserMapper userMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    public UserServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //1.1 不符合
            return Result.fail("手机号格式不符合要求");
        }
        //1.2 符合,生成验证码
        String code = RandomUtil.randomNumbers(6);
        //2.保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //3.发送验证码
        log.debug("发送短信验证码成功，验证码为:" + code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不符合要求");

        }
        //2.校验验证码
        String cacheCode = (String) session.getAttribute(CODE_SESSION_KEY); //正确验证码（session）
        Long expireTime = (Long) session.getAttribute("CODE_EXPIRE_TIME"); // 取出过期时间
        String code = loginForm.getCode(); //待验证验证码（前端传入）
        // 2.1 验证码过期
        if (expireTime == null || System.currentTimeMillis() > expireTime) {
            return Result.fail("验证码已过期，请重新获取");
        }
        //2.1 验证码错误
        if (RegexUtils.isCodeInvalid(code)) {
            return Result.fail("验证码格式错误");
        }
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误");
        }
        //2.2 验证码一致
        //3.根据手机号查询用户
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        //4.用户不存在，先注册
        if (user == null) {
            user = createUserWithPhone(phone);
        }
        //5.保存用户信息到session （使用魔法值）
        session.setAttribute(USER_SESSION_KEY, user);
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);//设置手机号
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));//设置默认昵称
        user.setIcon(""); //设置默认头像
        //2.保存用户到数据库
        save(user);
        return user;
    }
}
