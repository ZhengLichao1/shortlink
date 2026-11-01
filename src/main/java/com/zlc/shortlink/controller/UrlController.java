package com.zlc.shortlink.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zlc.shortlink.entity.Link;
import com.zlc.shortlink.mapper.LinkMapper;
import com.zlc.shortlink.util.Base62;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
public class UrlController {
    private static final String SEQ_KEY = "link:seq";
    private static final String DOMAIN = "http://localhost:8080";

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

        return Map.of("code",code,"shortUri",DOMAIN + "/" + code);
    }
    /** 跳转 */
    @GetMapping("/{code:[0-9A-Za-z]{1,8}}")
    public void jump(@PathVariable String code, HttpServletResponse response) throws IOException {
        Link link = linkMapper.selectOne(new LambdaQueryWrapper<Link>()
                .eq(Link::getShortUri, code)
                .eq(Link::getDelFlag, 0));

        if (link == null) {
            response.sendError(404, "短链不存在");
            return;
        }
        response.setStatus(302);
        response.setHeader("Location", link.getOriginUrl());
    }
}
