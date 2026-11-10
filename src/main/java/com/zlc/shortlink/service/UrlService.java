package com.zlc.shortlink.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.hash.BloomFilter;
import com.zlc.shortlink.config.CacheConfig;
import com.zlc.shortlink.constant.RedisKeys;
import com.zlc.shortlink.entity.Link;
import com.zlc.shortlink.mapper.LinkMapper;
import com.zlc.shortlink.util.Base62;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 短链业务逻辑：发号、入库、缓存读写。
 * Controller 只负责收参数和返回结果，业务都在这里。
 */
@Service
public class UrlService {

    /** 缓存基础过期时间（分钟） */
    private static final int TTL_BASE_MINUTES = 30;

    /** 过期时间随机抖动范围（分钟），用于打散过期时间，避免缓存雪崩 */
    private static final int TTL_RANDOM_MINUTES = 10;

    /** 空值缓存的过期时间（分钟），比正常值短很多，避免长期占着 key */
    private static final int NULL_TTL_MINUTES = 1;

    /** 回源互斥锁的持有时间（秒），防止持锁方宕机后锁不释放 */
    private static final int LOCK_TTL_SECONDS = 3;

    /** 没抢到锁时的等待时间（毫秒），等持锁方回填缓存 */
    private static final long LOCK_WAIT_MILLIS = 50L;

    /** 空值标记：Caffeine 不允许存 null，用空串表示「这个短码查过了，库里没有」 */
    private static final String NULL_MARK = "";

    /**
     * 释放锁的 Lua 脚本：先比对 token 再删，避免误删别人持有的锁。
     * 「查 + 删」两步如果分开做，中间锁可能已过期并被别人重新获取。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final LinkMapper linkMapper;
    private final StringRedisTemplate redis;
    private final Cache<String, String> localCache;
    private final BloomFilter<String> bloomFilter;
    private final CacheConfig cacheConfig;
    private final SegmentIdGenerator segmentIdGenerator;

    public UrlService(LinkMapper linkMapper,
                      StringRedisTemplate redis,
                      Cache<String, String> localCache,
                      BloomFilter<String> bloomFilter,
                      CacheConfig cacheConfig,
                      SegmentIdGenerator segmentIdGenerator) {
        this.linkMapper = linkMapper;
        this.redis = redis;
        this.localCache = localCache;
        this.bloomFilter = bloomFilter;
        this.cacheConfig = cacheConfig;
        this.segmentIdGenerator = segmentIdGenerator;
    }

    /**
     * 生成短码并写入数据库。
     *
     * @param originUrl 已校验过的原始长链
     * @return 短码
     */
    public String createShortLink(String originUrl) {
        // 号段模式：一次向 Redis 申请 1000 个号存在本地，发号时纯内存自增
        long seq = segmentIdGenerator.nextId();
        String code = Base62.encode(seq);

        Link link = new Link();
        link.setShortUri(code);
        link.setOriginUrl(originUrl);
        link.setDelFlag(0);
        linkMapper.insert(link);

        // 新短码必须立刻进布隆过滤器，否则刚生成的链接会被判成「不存在」
        // Guava 的 BloomFilter 写操作非线程安全，这里加锁
        synchronized (bloomFilter) {
            bloomFilter.put(code);
        }

        return code;
    }

    /**
     * 根据短码查询原始长链：L1 Caffeine → L2 Redis → 布隆过滤器 → 互斥锁回源 MySQL。
     *
     * @param code 短码
     * @return 原始长链；短码不存在或已删除时返回 null
     */
    public String resolve(String code) {
        String cacheKey = RedisKeys.code(code);

        // L1：进程内缓存，纳秒级
        if (cacheConfig.isL1Enabled()) {
            String l1Value = localCache.getIfPresent(code);
            if (l1Value != null) {
                return NULL_MARK.equals(l1Value) ? null : l1Value;
            }
        }

        // L2：Redis，一次网络往返
        if (cacheConfig.isL2Enabled()) {
            String l2Value = redis.opsForValue().get(cacheKey);
            if (l2Value != null) {
                if (cacheConfig.isL1Enabled()) {
                    localCache.put(code, l2Value);
                }
                return NULL_MARK.equals(l2Value) ? null : l2Value;
            }
        }

        // 布隆过滤器：判定不存在就直接返回，不用查库
        // 它只会误判「存在」（假阳性），不会漏判，所以这里拦下来的都是真的不存在
        if (!bloomFilter.mightContain(code)) {
            return null;
        }

        // 击穿保护：同一短码并发失效时只放一个请求回源
        return loadFromDbWithMutex(code, cacheKey);
    }

    /**
     * 记录一次点击：只在Redis累加，不写进数据库。
     * 真正的落库交给定时任务批量处理。
     */
    public void incrementClick(String code) {
        redis.opsForValue().increment(RedisKeys.click(code));
        redis.opsForSet().add(RedisKeys.CLICK_DIRTY, code);
    }

    /** 加互斥锁回源，防止缓存击穿：热点短码过期瞬间只让一个请求查库，其余等它回填缓存。 */
    private String loadFromDbWithMutex(String code, String cacheKey) {
        String lockKey = RedisKeys.lock(code);
        String token = UUID.randomUUID().toString();

        Boolean locked = redis.opsForValue()
                .setIfAbsent(lockKey, token, Duration.ofSeconds(LOCK_TTL_SECONDS));

        if (Boolean.TRUE.equals(locked)) {
            try {
                // 双重检查：拿到锁的瞬间，别人可能刚好回填了缓存
                if (cacheConfig.isL2Enabled()) {
                    String again = redis.opsForValue().get(cacheKey);
                    if (again != null) {
                        if (cacheConfig.isL1Enabled()) {
                            localCache.put(code, again);
                        }
                        return NULL_MARK.equals(again) ? null : again;
                    }
                }
                return queryAndFill(code, cacheKey);
            } finally {
                redis.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), token);
            }
        }

        // 没抢到锁：短暂等待后重读缓存，大部分情况下持锁方已经回填完成
        try {
            Thread.sleep(LOCK_WAIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (cacheConfig.isL2Enabled()) {
            String retry = redis.opsForValue().get(cacheKey);
            if (retry != null) {
                if (cacheConfig.isL1Enabled()) {
                    localCache.put(code, retry);
                }
                return NULL_MARK.equals(retry) ? null : retry;
            }
        }

        // 还没等到：为了不返回错误，降级直接查库（量很小，因为只有没抢到锁的请求会走到这里）
        Link link = queryDb(code);
        return link == null ? null : link.getOriginUrl();
    }

    /** 查库并回填两级缓存；查不到时写空值缓存 */
    private String queryAndFill(String code, String cacheKey) {
        Link link = queryDb(code);
        if (link == null) {
            writeNullCache(code, cacheKey);
            return null;
        }
        String originUrl = link.getOriginUrl();
        writeCache(code, cacheKey, originUrl);
        return originUrl;
    }

    private Link queryDb(String code) {
        return linkMapper.selectOne(new LambdaQueryWrapper<Link>()
                .eq(Link::getShortUri, code)
                .eq(Link::getDelFlag, 0));
    }

    /** 正常值写两级缓存：Redis 用随机 TTL，L1 用固定短 TTL */
    private void writeCache(String code, String cacheKey, String originUrl) {
        if (cacheConfig.isL2Enabled()) {
            long ttlMinutes = TTL_BASE_MINUTES + ThreadLocalRandom.current().nextInt(TTL_RANDOM_MINUTES);
            redis.opsForValue().set(cacheKey, originUrl, Duration.ofMinutes(ttlMinutes));
        }
        if (cacheConfig.isL1Enabled()) {
            localCache.put(code, originUrl);
        }
    }

    /** 空值写两级缓存，TTL 取短的，避免脏数据长期驻留 */
    private void writeNullCache(String code, String cacheKey) {
        if (cacheConfig.isL2Enabled()) {
            redis.opsForValue().set(cacheKey, NULL_MARK, Duration.ofMinutes(NULL_TTL_MINUTES));
        }
        if (cacheConfig.isL1Enabled()) {
            localCache.put(code, NULL_MARK);
        }
    }
}
