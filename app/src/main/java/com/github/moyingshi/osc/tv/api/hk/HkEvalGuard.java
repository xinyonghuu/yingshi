package com.github.moyingshi.osc.tv.api.hk;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 列表求值超驰监控，第 1 步。
 * <p>
 * 等价于 APK 的混淆类 w32：同一规则的新求值排队时，旧求值直接抛异常终止
 * （原文错误串 "hk js list eval superseded (queued)"）。
 * 本步实现为每规则自增序号 + 检查点抛 {@link HkSupersededException}。
 */
public final class HkEvalGuard {

    private static final ConcurrentHashMap<String, AtomicLong> SEQUENCES = new ConcurrentHashMap<>();

    private HkEvalGuard() {
    }

    /** 开始一次求值，返回本次序号。 */
    public static long begin(String ruleKey) {
        return SEQUENCES.computeIfAbsent(ruleKey, k -> new AtomicLong()).incrementAndGet();
    }

    /** 是否已被更新的同规则求值超驰。 */
    public static boolean isSuperseded(String ruleKey, long token) {
        AtomicLong seq = SEQUENCES.get(ruleKey);
        return seq != null && seq.get() != token;
    }

    /** 超驰检查点：被超驰时抛异常终止本次求值。 */
    public static void check(String ruleKey, long token) {
        if (isSuperseded(ruleKey, token)) {
            throw new HkSupersededException(ruleKey);
        }
    }

    /** 超驰异常（原文错误串）。 */
    public static class HkSupersededException extends RuntimeException {
        public HkSupersededException(String ruleKey) {
            super("hk js list eval superseded (queued): " + ruleKey);
        }
    }
}
