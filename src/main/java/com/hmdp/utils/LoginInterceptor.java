package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    //前置拦截（进入Controller之前）——校验登录状态
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        UserDTO userDTO = UserHolder.getUser();
        //判断用户是否存在
        if (userDTO == null) {
            //用户不存在,拦截
            response.setStatus(401);
            return false;
        }
        //用户存在，放行
        return true;
    }

}
