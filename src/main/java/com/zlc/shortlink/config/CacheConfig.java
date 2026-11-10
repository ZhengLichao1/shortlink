package com.zlc.shortlink.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 缓存与布隆过滤器的装配。
 * L1 = Caffeine（进程内，纳秒级，多实例不共享），L2 = Redis（跨实例共享，一次网络往返）。
 * L1 的 TTL 故意比 L2 短：本地缓存没有失效通知，多实例改了数据只能等它过期，用命中率换一致性。
 */
@Configuration
@ConfigurationProperties(prefix = "shortlink.cache")
public class CacheConfig {

    /** 是否启用 L1 本地缓存。压测时用来关掉，做三组对比 */
    private boolean l1Enabled = true;

    /** 是否启用 L2 Redis 缓存 */
    private boolean l2Enabled = true;

    /** L1 最大条目数，防止本地内存无限增长 */
    private long l1MaxSize = 10_000;

    /** L1 过期时间（秒） */
    private long l1TtlSeconds = 10;

    /** 布隆过滤器预计插入数量，用来算位数组大小 */
    private long bloomExpectedInsertions = 1_000_000;

    /** 布隆过滤器允许的误判率 */
    private double bloomFalsePositiveRate = 0.01;

    @Bean
    public Cache<String, String> localCache() {
        return Caffeine.newBuilder()
                .maximumSize(l1MaxSize)
                .expireAfterWrite(Duration.ofSeconds(l1TtlSeconds))
                .recordStats()
                .build();
    }

    /**
     * 布隆过滤器：判断「这个短码一定不存在」。它不存原始地址，只存 bit 序列，内存开销可以忽略。
     * Guava 的 BloomFilter 写操作非线程安全，新增短码时要加锁（见 UrlService#createShortLink）。
     */
    @Bean
    public BloomFilter<String> shortCodeBloomFilter() {
        return BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                bloomExpectedInsertions,
                bloomFalsePositiveRate);
    }

    public boolean isL1Enabled() {
        return l1Enabled;
    }

    public void setL1Enabled(boolean l1Enabled) {
        this.l1Enabled = l1Enabled;
    }

    public boolean isL2Enabled() {
        return l2Enabled;
    }

    public void setL2Enabled(boolean l2Enabled) {
        this.l2Enabled = l2Enabled;
    }

    public long getL1MaxSize() {
        return l1MaxSize;
    }

    public void setL1MaxSize(long l1MaxSize) {
        this.l1MaxSize = l1MaxSize;
    }

    public long getL1TtlSeconds() {
        return l1TtlSeconds;
    }

    public void setL1TtlSeconds(long l1TtlSeconds) {
        this.l1TtlSeconds = l1TtlSeconds;
    }

    public long getBloomExpectedInsertions() {
        return bloomExpectedInsertions;
    }

    public void setBloomExpectedInsertions(long bloomExpectedInsertions) {
        this.bloomExpectedInsertions = bloomExpectedInsertions;
    }

    public double getBloomFalsePositiveRate() {
        return bloomFalsePositiveRate;
    }

    public void setBloomFalsePositiveRate(double bloomFalsePositiveRate) {
        this.bloomFalsePositiveRate = bloomFalsePositiveRate;
    }
}
