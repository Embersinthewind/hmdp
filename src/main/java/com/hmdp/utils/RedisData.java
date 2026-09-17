package com.hmdp.utils;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RedisData {
    //逻辑过期时间
    private LocalDateTime expireTime;
    //实体对象
    private Object data;
}
