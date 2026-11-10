package com.zlc.shortlink.service;

import com.zlc.shortlink.constant.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 号段发号器：一次向 Redis 申请 1000 个号，在本地内存里逐个发放。
 * 相比每个号都 INCR 一次，Redis 往返减少到千分之一；
 * 且当前号段没用完时，Redis 短暂不可用也能继续发号。
 */
@Slf4j
@Component
public class SegmentIdGenerator {
    /** 每次申请的号段大小 */
    private  static final long STEP = 1000;

    private final StringRedisTemplate redis;

    /** 下一个要发的号 */
    private long next = 1;

    /** 当前号段的上界；next > max 表示这一段已用完，需要换段 */
    private long max = 0;

    public SegmentIdGenerator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 取下一个号。
     * 号段还有余量时纯内存自增；用完才去 Redis 申请新的一段。
     */
    public synchronized long nextId() {
        if (next > max) {
            applyNewSegment();
        }
        return next++;
    }

    /** 申请新号段：INCRBY 返回的是这一段的最大号，往前减 STEP 就是这段的起点。 */
    private void applyNewSegment() {
        Long end = redis.opsForValue().increment(RedisKeys.SEQ,STEP);
        if (end == null) {
            throw new IllegalStateException("发号失败：Redis 不可用");
        }

        max = end;
        next = end - STEP + 1;
        log.info("申请号段成功：{} ~ {}",next,max);
    }
}
