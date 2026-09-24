-- 1.获取缓存中锁的key  "local:order:userId"
local lockKey = keys[1]

-- 2.获取锁的线程标识 get key
local id = redis.call('get', lockKey)

-- 获取当前线程标识  "uuid-线程id"
local threadId = args[1]

-- 判断当前线程标识与缓存中锁的标识是否一致 if( = ) then
if (id == threadId) then
    -- 释放锁 del key
    return redis.call('del', lockKey)
end
-- 释放锁失败
return 0




