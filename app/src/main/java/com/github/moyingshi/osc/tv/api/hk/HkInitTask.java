package com.github.moyingshi.osc.tv.api.hk;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * 引擎初始化 Callable，第 1 步。
 * <p>
 * 等价于 APK 的混淆类 l22：f42 构造时提交到线程池。
 * case 0 = 带规则的全量初始化（T1 + D0 + 垫片 + 注入 + preRule）；
 * case 1 = imageEngine 极简初始化（最小 scope，不跑规则初始化）。
 */
public final class HkInitTask implements Callable<Void> {

    public static final int MODE_FULL = 0;
    public static final int MODE_MINIMAL = 1;

    private final HkJsRuntime runtime;
    private final int mode;

    public HkInitTask(HkJsRuntime runtime, int mode) {
        this.runtime = runtime;
        this.mode = mode;
    }

    @Override
    public Void call() throws Exception {
        if (mode == MODE_MINIMAL) {
            runtime.initMinimal();
        } else {
            runtime.init();
        }
        return null;
    }

    public static Future<Void> submit(ExecutorService pool, HkJsRuntime runtime, int mode) {
        return pool.submit(new HkInitTask(runtime, mode));
    }
}
