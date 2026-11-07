package com.zlc.shortlink.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zlc.shortlink.entity.Link;
import com.zlc.shortlink.mapper.LinkMapper;
import com.zlc.shortlink.util.Base62;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RestController
public class UrlController {
    private static final String SEQ_KEY = "link:seq";
    private static final String DOMAIN = "http://localhost:8080";
    private static final String LINK_CACHE_KEY = "link:code:";   //缓存key前缀

    private  final LinkMapper linkMapper;
    private final StringRedisTemplate redis;

    public UrlController(LinkMapper linkMapper, StringRedisTemplate redis) {
        this.linkMapper = linkMapper;
        this.redis = redis;
    }

    /** 生成短链 */
    @PostMapping("/api/links")
    public Map<String,Object> create (@RequestParam String url){
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return Map.of("error","url 必须以 http:// 或 https:// 开头");
        }
        Long seq = redis.opsForValue().increment(SEQ_KEY);
        String code = Base62.encode(seq == null ? 0 : seq);

        Link link = new Link();
        link.setShortUri(code);
        link.setOriginUrl(url);
        link.setDelFlag(0);
        linkMapper.insert(link);

        return Map.of("code",code,"shortUrl",DOMAIN + "/" + code);
    }
    /** 跳转 */
    @GetMapping("/{code:[0-9A-Za-z]{1,8}}")
    public void jump(@PathVariable String code, HttpServletResponse response) throws IOException {
        String cacheKey = LINK_CACHE_KEY + code;

        //1.先查缓存
        String originUrl = redis.opsForValue().get(cacheKey);

        //2.缓存没命中，才查数据库
        if (originUrl == null) {
            Link link = linkMapper.selectOne(new LambdaQueryWrapper<Link>()
                    .eq(Link::getShortUri, code)
                    .eq(Link::getDelFlag,0));

            if (link == null) {
                response.sendError(404, "短链不存在");
                return;
            }

            originUrl = link.getOriginUrl();

            //3.回填缓存，TTL 加随机抖动，防止同一时刻大批key一起过期
            long ttlMinutes = 30 + ThreadLocalRandom.current().nextInt(10);
            redis.opsForValue().set(cacheKey,originUrl, Duration.ofMinutes(ttlMinutes));
        }

        response.setStatus(302);
        response.setHeader("Location",originUrl);
    }
}
