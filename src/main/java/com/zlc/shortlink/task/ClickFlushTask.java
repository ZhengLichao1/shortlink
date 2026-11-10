package com.zlc.shortlink.task;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zlc.shortlink.constant.RedisKeys;
import com.zlc.shortlink.entity.Link;
import com.zlc.shortlink.mapper.LinkMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 点击量刷盘任务：把 Redis 里累积的点击次数批量写回数据库。
 * 跳转链路只做内存自增，避免每次点击都 UPDATE 同一行造成行锁竞争。
 */
@Slf4j
@Component
public class ClickFlushTask {

    private final StringRedisTemplate redis;
    private final LinkMapper linkMapper;

    public ClickFlushTask(StringRedisTemplate redis, LinkMapper linkMapper) {
        this.redis = redis;
        this.linkMapper = linkMapper;
    }

    /** 每30秒执行一次：fixedDelay 表示"上次跑完 30 秒后再跑下一次"*/
    @Scheduled(fixedDelay = 30_000)
    public void flush() {
        Set<String> codes = redis.opsForSet().members(RedisKeys.CLICK_DIRTY);
        if (codes == null || codes.isEmpty()) {
            return;
        }

        long total = 0;
        for (String code : codes) {
            //先移出集合，再取走计数：这段时间有新点击会重新把短码塞进集合，不会漏
            redis.opsForSet().remove(RedisKeys.CLICK_DIRTY, code);

            String val = redis.opsForValue().getAndDelete(RedisKeys.click(code));
            if (val == null) {
                continue;
            }
            long delta = Long.parseLong(val);
            if (delta <= 0) {
                continue;
            }

            try {
                linkMapper.update(null, new LambdaUpdateWrapper<Link>()
                        .eq(Link::getShortUri, code)
                        .setSql("click_num = click_num + " + delta));

                total += delta;
                log.info("刷盘 code={} 写入 {} 次点击", code, delta);
            } catch (Exception e) {
                // 更新失败就把取走的计数还回去，并放回待刷盘集合，等下一轮重试；
                // 否则计数已经从 Redis 删掉，这批点击就永久丢了
                redis.opsForValue().increment(RedisKeys.click(code), delta);
                redis.opsForSet().add(RedisKeys.CLICK_DIRTY, code);
                log.error("刷盘失败 code={}，已回滚 {} 次点击等待重试", code, delta, e);
            }
        }

        log.info("本次刷盘完成，共写入 {} 次点击", total);
    }
}
