package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.USER_SESSION_KEY;

public class LoginInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    //前置拦截（进入Controller之前）——将用户保存在线程中
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.从浏览器（前端）获取token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            // token不存在，拦截 返回401状态码（未授权）
            response.setStatus(401);
            return false;
        }
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        //2.从redis中获取用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);
        //3.判断用户是否存在
        if (userMap.isEmpty()) {
            //3.1 用户不存在，拦截 返回401状态码（未授权）
            response.setStatus(401);
            return false;
        }
        //将userMap转为user对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //3.2 用户存在，保存到ThreadLocal中
        UserHolder.saveUser(userDTO);
        //4.刷新登录过期时间（token有效期） （当前活跃时间+36000L）
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //5.放行
        return true;
    }

    //渲染之后拦截——从线程中删除用户，防止信息泄露
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
