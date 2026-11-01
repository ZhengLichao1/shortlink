package com.zlc.shortlink.controller;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/ping")
public class PingController {

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public PingController(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @GetMapping
    public String ping() {
        return "pong";
    }

    @GetMapping("/db")
    public Map<String, Object> db() {
        Integer n = jdbc.queryForObject("select count(*) from t_link", Integer.class);
        return Map.of("mysql", "ok", "t_link_rows", n);
    }

    @GetMapping("/redis")
    public Map<String, Object> redis() {
        Long v = redis.opsForValue().increment("ping:counter");
        return Map.of("redis", "ok", "counter", v);
    }
}
