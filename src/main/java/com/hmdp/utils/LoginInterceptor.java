package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.beans.BeanUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import static com.hmdp.utils.SystemConstants.USER_SESSION_KEY;

public class LoginInterceptor implements HandlerInterceptor {
    //前置拦截（进入Controller之前）——将用户保存在线程中
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //cookie中含有sessionId
        //1.从session中获取用户
        HttpSession session = request.getSession();
        Object user = session.getAttribute(USER_SESSION_KEY);
        //2.判断用户是否存在
        if (user == null) {
            //2.1 用户不存在，拦截 返回401状态码（未授权）
            response.setStatus(401);
            return false;
        }
        //2.2 用户存在，保存到ThreadLocal中
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        UserHolder.saveUser(userDTO);
        //3.放行
        return true;
    }

    //渲染之后拦截——从线程中删除用户，防止信息泄露
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
