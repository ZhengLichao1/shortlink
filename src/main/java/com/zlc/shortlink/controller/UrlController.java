package com.zlc.shortlink.controller;

import com.zlc.shortlink.service.UrlService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/**
 * 短链接口层：只做参数校验、调用 Service、组织返回。
 * 业务逻辑见 {@link UrlService}。
 */
@RestController
public class UrlController {

    private final UrlService urlService;

    /** 短链域名，从配置文件读取，部署时改配置即可 */
    private final String domain;

    public UrlController(UrlService urlService,
                         @Value("${app.domain:http://localhost:8080}") String domain) {
        this.urlService = urlService;
        this.domain = domain;
    }

    /** 生成短链 */
    @PostMapping("/api/links")
    public Map<String, Object> create(@RequestParam String url) {
        // 校验失败抛异常，交给 GlobalExceptionHandler 统一转成 400 + JSON
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("url 必须以 http:// 或 https:// 开头");
        }
        // 与 t_link.origin_url 的字段长度保持一致，超长直接拒绝，否则插入时会抛 DataTooLong
        if (url.length() > 1024) {
            throw new IllegalArgumentException("链接过长，最多 1024 个字符");
        }

        String code = urlService.createShortLink(url);
        return Map.of("code", code, "shortUrl", domain + "/" + code);
    }

    /** 跳转：短码 → 原始地址 → 302 */
    @GetMapping("/{code:[0-9A-Za-z]{1,8}}")
    public void jump(@PathVariable String code, HttpServletResponse response) throws IOException {
        String originUrl = urlService.resolve(code);

        if (originUrl == null) {
            response.sendError(404, "短链不存在");
            return;
        }

        urlService.incrementClick(code);

        response.setStatus(302);
        response.setHeader("Location", originUrl);
    }
}
