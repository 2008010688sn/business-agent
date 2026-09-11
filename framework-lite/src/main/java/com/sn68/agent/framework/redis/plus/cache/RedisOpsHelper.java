package com.sn68.agent.framework.redis.plus.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Redis 操作助手
 *
 * @author Levin
 */
@Slf4j
@SuppressWarnings(value = "all")
public class RedisOpsHelper {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisOpsHelper(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // --------------------------- String Operations ---------------------------

    /**
     * 设置字符串值（对应 SET）
     *
     * @param key   键，不可为 null
     * @param value 字符串值
     */
    public void set(String key, Object value) {
        Assert.notNull(key, "Key must not be null");
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * 设置带过期时间的字符串值（对应 SETEX）
     *
     * @param key   键
     * @param value 值
     * @param ttl   过期时间
     */
    public void setEx(String key, Object value, Duration ttl) {
        Assert.notNull(key, "Key must not be null");
        Assert.notNull(ttl, "TTL must not be null");
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    /**
     * 获取字符串值（对应 GET）
     *
     * @return Optional 包装的值
     */
    public Optional<Object> get(String key) {
        Assert.notNull(key, "Key must not be null");
        return Optional.ofNullable(redisTemplate.opsForValue().get(key));
    }

    // --------------------------- Hash Operations ---------------------------

    /**
     * 设置哈希字段值（对应 HSET）
     *
     * @param key   外层键
     * @param field 字段键
     * @param value 字段值
     */
    public void hashPut(String key, Object field, Object value) {
        Assert.notNull(key, "Key must not be null");
        Assert.notNull(field, "Field must not be null");
        redisTemplate.opsForHash().put(key, field, value);
    }

    /**
     * 设置哈希字段值（对应 HSET）
     *
     * @param key 外层键
     * @param map map
     */
    public void hashPutAll(String key, Map<String, Object> map) {
        redisTemplate.opsForHash().putAll(key, map);
    }

    /**
     * 获取哈希字段值（对应 HGET）
     *
     * @return Optional 包装的字段值
     */
    public Optional<Object> hashGet(String key, Object field) {
        Assert.notNull(key, "Key must not be null");
        Assert.notNull(field, "Field must not be null");
        return Optional.ofNullable((Object) redisTemplate.opsForHash().get(key, field));
    }

    /**
     * 删除哈希中的某一个key(对应HDEL)
     *
     * @return Optional 删除的数量
     */
    public Long hashDel(String key, Object field) {
        Assert.notNull(key, "Key must not be null");
        Assert.notNull(field, "Field must not be null");
        return redisTemplate.opsForHash().delete(key, field);    }

    /**
     * 获取哈希所有字段（对应 HGETALL）
     *
     * @return 完整哈希结构，可能返回空 Map
     */
    public Map<Object, Object> hGetAll(String key) {
        Assert.notNull(key, "Key must not be null");
        return redisTemplate.opsForHash().entries(key);
    }

    // --------------------------- Set Operations ---------------------------

    /**
     * 添加集合元素（对应 SADD）
     *
     * @return 成功添加的元素数量
     */
    public Long sAdd(String key, Object... values) {
        Assert.notNull(key, "Key must not be null");
        Assert.notEmpty(values, "Values must not be empty");
        return redisTemplate.opsForSet().add(key, values);
    }

    /**
     * 获取集合所有元素（对应 SMEMBERS）
     *
     * @return 元素集合（可能为空）
     */
    public Set<Object> sMembers(String key) {
        Assert.notNull(key, "Key must not be null");
        return redisTemplate.opsForSet().members(key);
    }

    /**
     * 检查集合包含元素（对应 SISMEMBER）
     *
     * @return 是否存在
     */
    public Boolean sIsMember(String key, Object value) {
        Assert.notNull(key, "Key must not be null");
        return redisTemplate.opsForSet().isMember(key, value);
    }

    // --------------------------- ZSet Operations ---------------------------

    /**
     * 添加有序集合元素（对应 ZADD）
     *
     * @param score 排序分数
     * @return 是否添加成功
     */
    public Boolean zAdd(String key, Object value, double score) {
        Assert.notNull(key, "Key must not be null");
        return redisTemplate.opsForZSet().add(key, value, score);
    }

    /**
     * 按索引范围获取有序集合（对应 ZRANGE）
     *
     * @param start 起始索引
     * @param end   结束索引（-1 表示到最后）
     * @return 元素集合（按分数升序）
     */
    public Set<Object> zRange(String key, long start, long end) {
        Assert.notNull(key, "Key must not be null");
        return redisTemplate.opsForZSet().range(key, start, end);
    }

    /**
     * 按分数范围获取有序集合（对应 ZRANGEBYSCORE）
     *
     * @param min 最小分数
     * @param max 最大分数
     * @return 符合范围元素集合
     */
    public Set<Object> zRangeByScore(String key, double min, double max) {
        Assert.notNull(key, "Key must not be null");
        return redisTemplate.opsForZSet().rangeByScore(key, min, max);
    }

    // --------------------------- Key Operations ---------------------------

    /**
     * 删除键（对应 DEL）
     *
     * @return 是否删除成功
     */
    public Boolean del(String key) {
        Assert.notNull(key, "Key must not be null");
        return redisTemplate.delete(key);
    }

    /**
     * 检查键是否存在（对应 EXISTS）
     */
    public Boolean exists(String key) {
        Assert.notNull(key, "Key must not be null");
        return redisTemplate.hasKey(key);
    }
}
