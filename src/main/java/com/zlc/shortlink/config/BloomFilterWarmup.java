package com.zlc.shortlink.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.hash.BloomFilter;
import com.zlc.shortlink.entity.Link;
import com.zlc.shortlink.mapper.LinkMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时把已有短码灌进布隆过滤器：它只在内存里，重启就空了，不预热会把已有短码全判成不存在。
 * 现在是全表读，几万条以内没问题；到百万级要改按 id 游标分批查，多实例则要换成共享布隆。
 */
@Slf4j
@Component
public class BloomFilterWarmup implements ApplicationRunner {

    private final LinkMapper linkMapper;
    private final BloomFilter<String> bloomFilter;

    public BloomFilterWarmup(LinkMapper linkMapper, BloomFilter<String> bloomFilter) {
        this.linkMapper = linkMapper;
        this.bloomFilter = bloomFilter;
    }

    @Override
    public void run(ApplicationArguments args) {
        long start = System.currentTimeMillis();

        List<Link> links = linkMapper.selectList(new LambdaQueryWrapper<Link>()
                .select(Link::getShortUri)
                .eq(Link::getDelFlag, 0));

        // 启动阶段是单线程，不需要考虑 BloomFilter 的写并发问题
        for (Link link : links) {
            bloomFilter.put(link.getShortUri());
        }

        log.info("布隆过滤器预热完成：{} 条短码，耗时 {} ms",
                links.size(), System.currentTimeMillis() - start);
    }
}
