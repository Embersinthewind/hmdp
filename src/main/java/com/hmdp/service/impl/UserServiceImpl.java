package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.text.DateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
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
        //从redis中取出验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        //待验证验证码（前端传入）
        String code = loginForm.getCode();
        //2.1 验证码错误
        if (RegexUtils.isCodeInvalid(code)) {
            return Result.fail("验证码格式错误");
        }
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误");
        }
        //2.2 验证码正确
        //3.根据手机号查询用户
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        //4.用户不存在，先注册
        if (user == null) {
            user = createUserWithPhone(phone);
        }
        //5.保存用户信息到redis
        //生成随机token
        String token = UUID.randomUUID().toString(true);   //去掉uuid产生的横线
        //将用户信息转为Hash
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true) // 忽略null值
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())); // 将所有值转为String
        //将用户信息存入redis
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        //6.设置过期时间(根据用户是否在活跃)：当前活跃时间+36000L
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        //7.将token返回给前端
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);//设置手机号
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));//设置默认昵称
        user.setIcon("https://gss0.baidu.com/94o3dSag_xI4khGko9WTAnF6hhy/zhidao/wh%3D450%2C600/sign=bee1e21540ed2e73fcbc8e28b2318dbd/4610b912c8fcc3ce4b66c4a29845d688d53f20f8.jpg"); //设置默认头像
        //2.保存用户到数据库
        save(user);
        return user;
    }

    @Override
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();

        //2.获取日期
        LocalDateTime today = LocalDateTime.now();

        //3.拼接key
        String keySuffix = today.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;

        //4.获取今天是本月的第几天    OFFSET是0~30，差1
        int dayOfMonth = today.getDayOfMonth();

        //5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();

        //2.获取日期
        LocalDateTime today = LocalDateTime.now();

        //3.拼接key
        String keySuffix = today.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;

        //4.获取今天是本月的第几天    OFFSET是0~30，差1
        int dayOfMonth = today.getDayOfMonth();

        //5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字     BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }

        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        //6.循环遍历
        int count = 0;//计数器
        while (true) {
            //7.让这个数字与1做与运算，得到数字的最后一个bit位判断这个bit位是否为0
            if ((num & 1) == 0) {
                //如果为0，说明未签到，结束
                break;
            } else {
                //如果不为⊙，说明已签到，计数器+1
                count++;
            }
            num = num >> 1;
        }
        return Result.ok(count);
    }


}
