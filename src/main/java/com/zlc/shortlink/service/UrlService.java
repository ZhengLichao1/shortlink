package com.zlc.shortlink.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zlc.shortlink.constant.RedisKeys;
import com.zlc.shortlink.entity.Link;
import com.zlc.shortlink.mapper.LinkMapper;
import com.zlc.shortlink.util.Base62;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
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

    private final LinkMapper linkMapper;
    private final StringRedisTemplate redis;

    public UrlService(LinkMapper linkMapper, StringRedisTemplate redis) {
        this.linkMapper = linkMapper;
        this.redis = redis;
    }

    /**
     * 生成短码并写入数据库。
     *
     * @param originUrl 已校验过的原始长链
     * @return 短码
     */
    public String createShortLink(String originUrl) {
        Long seq = redis.opsForValue().increment(RedisKeys.SEQ);
        if (seq == null) {
            throw new IllegalStateException("发号失败：Redis 不可用");
        }
        String code = Base62.encode(seq);

        Link link = new Link();
        link.setShortUri(code);
        link.setOriginUrl(originUrl);
        link.setDelFlag(0);
        linkMapper.insert(link);

        return code;
    }

    /**
     * 根据短码查询原始长链。
     * 先查 Redis 缓存，未命中再回源 MySQL 并回填缓存（Cache Aside）。
     *
     * @param code 短码
     * @return 原始长链；短码不存在或已删除时返回 null
     */
    public String resolve(String code) {
        String cacheKey = RedisKeys.code(code);

        String originUrl = redis.opsForValue().get(cacheKey);
        if (originUrl != null) {
            return originUrl;
        }

        Link link = linkMapper.selectOne(new LambdaQueryWrapper<Link>()
                .eq(Link::getShortUri, code)
                .eq(Link::getDelFlag, 0));
        if (link == null) {
            return null;
        }

        long ttlMinutes = TTL_BASE_MINUTES + ThreadLocalRandom.current().nextInt(TTL_RANDOM_MINUTES);
        redis.opsForValue().set(cacheKey, link.getOriginUrl(), Duration.ofMinutes(ttlMinutes));

        return link.getOriginUrl();
    }
    /**
     * 记录一次点击：只在Redis累加，不写进数据库。
     * 真正的落库交给定时任务批量处理。
     */
    public void incrementClick(String code) {
        redis.opsForValue().increment(RedisKeys.click(code));
        redis.opsForSet().add(RedisKeys.CLICK_DIRTY,code);
    }
}
