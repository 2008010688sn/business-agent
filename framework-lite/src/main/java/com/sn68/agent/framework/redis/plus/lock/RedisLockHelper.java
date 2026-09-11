package com.sn68.agent.framework.redis.plus.lock;


import com.sn68.agent.framework.redis.plus.exception.RedisLockException;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * @author levin
 */
@Slf4j
@RequiredArgsConstructor
public class RedisLockHelper {

    private static final String DEFAULT_MESSAGE = "请求过快,数据已被其他用户处理,请刷新后重新尝试";

    private final RedissonClient redissonClient;


    public boolean tryLock(String key, long timeWindow) {
        RLock rLock = redissonClient.getLock(key);
        try {
            return rLock.tryLock(timeWindow, -1L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            log.error("Redis try lock InterruptedException", e);
            throw new RedisLockException("线程中断" + e.getLocalizedMessage());
        }
    }

    /**
     * 获取多个锁并执行操作
     * @param keys 锁
     * @param action 执行逻辑
     * @param <T>    返回类型
     * @return 执行结果
     */
    public <T> T withLocks(List<String> keys, Supplier<T> action) {
        return withLocks(keys, 0, -1, TimeUnit.SECONDS, action, DEFAULT_MESSAGE);
    }

    /**
     * 获取多个锁并执行操作
     * @param ids ids
     * @param action 执行逻辑
     * @param <T>    返回类型
     * @return 执行结果
     */
    public <T> T withLocks(String prefix, List<String> ids, Supplier<T> action) {
        var keys = ids.stream().map(id -> prefix + id).distinct().toList();
        return withLocks(keys, 0, -1, TimeUnit.SECONDS, action, DEFAULT_MESSAGE);
    }

    /**
     * 获取多个锁并执行操作
     * @param ids ids
     * @param action 执行逻辑
     * @param <T>    返回类型
     * @return 执行结果
     */
    public <T> T withLocks(String prefix, List<Object> ids, Supplier<T> action, String msg) {
        var keys = ids.stream().map(x -> prefix + ":" + ids).distinct().toList();
        return withLocks(keys, 0, -1, TimeUnit.SECONDS, action, msg);
    }

    /**
     * 获取多个锁并执行操作
     * @param keys 锁
     * @param action 执行逻辑
     * @param <T>    返回类型
     * @return 执行结果
     */
    public <T> T withLocks(List<String> keys, Supplier<T> action, String msg) {
        return withLocks(keys, 0, -1, TimeUnit.SECONDS, action, msg);
    }

    /**
     * 获取多个锁并执行操作
     * @param keys 锁
     * @param waitTime  等待时间
     * @param leaseTime 租期
     * @param unit  单位
     * @param action 执行逻辑
     * @param <T>    返回类型
     * @return 执行结果
     */
    public <T> T withLocks(List<String> keys, long waitTime, long leaseTime, TimeUnit unit, Supplier<T> action) {
        return withLocks(keys, waitTime, leaseTime, unit, action, DEFAULT_MESSAGE);
    }

    /**
     * 获取多个锁并执行操作
     * @param keys 锁
     * @param waitTime  等待时间
     * @param leaseTime 租期
     * @param unit  单位
     * @param msg 上锁失败消息
     * @param action 执行逻辑
     * @param <T>    返回类型
     * @return 执行结果
     */
    @SneakyThrows
    public <T> T withLocks(List<String> keys, long waitTime, long leaseTime, TimeUnit unit, Supplier<T> action, String msg) {
        // 组合锁名
        List<RLock> locks = keys.stream().map(redissonClient::getLock).toList();
        RLock multiLock = redissonClient.getMultiLock(locks.toArray(new RLock[0]));
        boolean locked = false;
        try {
            locked = multiLock.tryLock(waitTime, leaseTime, unit);
            if (!locked) {
                log.warn("未获取到锁，keys => {}", keys);
                throw new RuntimeException(msg);
            }
            log.info("成功获取锁 keys => {}", keys);
            return action.get();
        } catch (Exception e) {
            log.error("执行业务逻辑异常，keys={}，原因：{}", keys, e.getMessage(), e);
            throw e;
        } finally {
            if (locked) {
                try {
                    multiLock.unlock();
                    log.info("释放锁 keys={}", keys);
                } catch (Exception e) {
                    log.error("释放锁失败 keys={}，原因：{}", keys, e.getMessage(), e);
                }
            }
        }
    }


    /**
     * 不带回调的锁
     *
     * @param key  分布式锁KEY
     * @param func 回调函数
     */
    public void execute(String key, Runnable func) {
        RLock lock = redissonClient.getLock(key);
        boolean tryLock = lock.tryLock();
        log.info("当前锁 - {} , 上锁状态 - {}", key, tryLock);
        if (!tryLock) {
            throw new RedisLockException("Redis 已被获取,请勿重复操作");
        }
        try {
            func.run();
        } finally {
            unlock(List.of(lock));
        }
    }

    /**
     * 函数回调执行
     *
     * @param key      分布式锁KEY
     * @param waitTime 等待时间
     * @param unit     时间单位
     * @param func     回调函数
     * @param <T>      T
     * @return 执行结果
     */
    public <T> T execute(String key, long waitTime, TimeUnit unit, Supplier<T> func) {
        return execute(key, waitTime, -1L, unit, func);
    }

    /**
     * 函数回调执行
     *
     * @param key       分布式锁KEY
     * @param waitTime  等待时间
     * @param leaseTime 分布式锁租期 -1 就是开启 watch dog 自动续签
     * @param unit      时间单位
     * @param func      执行回调的函数
     * @param <T>       T
     * @return 执行结果
     */
    public <T> T execute(String key, long waitTime, long leaseTime, TimeUnit unit, Supplier<T> func) {
        RLock rLock = redissonClient.getLock(key);
        try {
            final boolean success = rLock.tryLock(waitTime, leaseTime, unit);
            if (!success) {
                throw new RedisLockException("Redis 锁获取失败");
            }
            return func.get();
        } catch (InterruptedException e) {
            log.error("Redis try lock InterruptedException", e);
            throw new RedisLockException("线程中断" + e.getLocalizedMessage());
        } finally {
            unlock(List.of(rLock));
        }
    }


    /**
     * 解锁
     *
     * @param locks 加锁时保存的锁集合
     */
    public void unlock(List<RLock> locks) {
        if (CollectionUtils.isEmpty(locks)) {
            return;
        }
        for (RLock lock : locks) {
            if (lock == null || !lock.isHeldByCurrentThread()) {
                continue;
            }
            lock.unlock();
        }
    }

}
