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

    public static String code(String shortUri) {
        return CODE_PREFIX + shortUri;
    }

    public static String click(String shortUri) {
        return CLICK_PREFIX + shortUri;
    }
}
