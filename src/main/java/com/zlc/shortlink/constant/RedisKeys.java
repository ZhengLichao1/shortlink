package com.zlc.shortlink.constant;

/**
 * Redis key 统一在这里定义，避免散落在各处拼错。
 * 命名规范：业务:对象:标识，便于按前缀批量查询和清理。
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 发号器：每生成一条短链自增 1 */
    public static final String SEQ = "link:seq";

    /** 短码 → 原始地址 的缓存前缀 */
    public static final String CODE_PREFIX = "link:code:";

    /** 短码 → 点击次数 的计数前缀（后续使用） */
    public static final String CLICK_PREFIX = "link:click:";

    /** 代刷盘的短码集合：跳转时把短码塞进来，定时任务按这个集合消费 */
    public static final String CLICK_DIRTY = "link:dirty:click";

    public static String code(String shortUri) {
        return CODE_PREFIX + shortUri;
    }

    public static String click(String shortUri) {
        return CLICK_PREFIX + shortUri;
    }

    /** 回源互斥锁前缀：防止缓存击穿，同一短码同时只有一个请求查库 */
    public static final String LOCK_PREFIX = "link:lock:";

    public static String lock(String shortUri) {
        return LOCK_PREFIX + shortUri;
    }
}
