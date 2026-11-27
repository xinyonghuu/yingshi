package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;

import java.io.File;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 海阔小程序引擎门面，第 1 步。
 * <p>
 * 等价于 APK 的混淆类 a02（227 行，全 static）：按规则名缓存运行时、
 * 规则 JSON 文件 mtime 一变就丢弃重建、.js: 惰性 URL 求值、初始化守卫。
 * 行为依据：REPLICATION.md §3（已逐行核对 a02.java 全文）。
 */
public final class HkEngine {

    /** a02.a：规则键 → 运行时。 */
    private static final ConcurrentHashMap<String, HkJsRuntime> RUNTIMES = new ConcurrentHashMap<>();
    /** a02.b：已初始化的运行时集合（初始化守卫）。 */
    private static final Set<HkJsRuntime> INITIALIZED = ConcurrentHashMap.newKeySet();
    /** a02.c：规则键 → 规则 JSON 文件 lastModified。 */
    private static final ConcurrentHashMap<String, Long> MTIMES = new ConcurrentHashMap<>();
    /** a02.d：当前规则（供日志/回调取用）。 */
    private static final ThreadLocal<HkRule> CURRENT_RULE = new ThreadLocal<>();

    /** a02.e：页码算术占位符 ###fypage@...@。 */
    private static final Pattern FYPAGE_ARITH = Pattern.compile("###fypage((?:@[-+*/]\\d+)*@)?");
    /** 算术操作。 */
    private static final Pattern ARITH_OP = Pattern.compile("([-+*/])(\\d+)@");

    private HkEngine() {
    }

    /** a02.c()：缓存键 = 规则标题（非空用标题，否则用规则 URL）。 */
    public static String ruleKey(HkRule rule) {
        if (rule == null) return "";
        String title = rule.title();
        return !TextUtils.isEmpty(title) ? title : rule.url();
    }

    /** a02.d()：取或建运行时（缓存 + mtime 失效）。 */
    public static HkJsRuntime getOrCreateRuntime(HkRule rule) {
        String key = ruleKey(rule);
        File ruleFile = HkRuleStore.ruleFile(rule == null ? "" : rule.title());
        long mtime = ruleFile != null && ruleFile.exists() ? ruleFile.lastModified() : 0;
        Long cached = MTIMES.get(key);
        if (cached != null && cached != mtime) {
            // 规则 JSON 文件 mtime 变了 → 三处同时驱逐，重建。
            RUNTIMES.remove(key);
            MTIMES.remove(key);
            SpiderDebug.log("HkJsRuntime", "rule changed, runtime evicted: %s", key);
        }
        HkJsRuntime runtime = RUNTIMES.get(key);
        if (runtime == null) {
            HkJsRuntime created = new HkJsRuntime(rule);
            runtime = RUNTIMES.putIfAbsent(key, created);
            if (runtime == null) {
                runtime = created;
                MTIMES.put(key, mtime);
            }
        }
        runtime.setRule(rule);
        CURRENT_RULE.set(rule);
        return runtime;
    }

    /** a02.e()：只查缓存，不创建。 */
    public static HkJsRuntime peekRuntime(HkRule rule) {
        return RUNTIMES.get(ruleKey(rule));
    }

    /** a02.a()：初始化守卫（不在集合中才调 C0()，双重检查防重复初始化）。 */
    public static void ensureInit(HkJsRuntime runtime) throws Exception {
        if (runtime == null || INITIALIZED.contains(runtime)) return;
        synchronized (runtime) {
            if (INITIALIZED.contains(runtime)) return;
            runtime.init();
            INITIALIZED.add(runtime);
        }
    }

    /** 当前规则（ThreadLocal）。 */
    public static HkRule currentRule() {
        return CURRENT_RULE.get();
    }

    /**
     * a02.b()：.js: 惰性 URL 求值。
     * 形如 {@code 前缀.js:JS代码} 的 URL（.js: 前一位不是 '@' 才认）走这里：
     * 页码 &gt; 0 时先剥 #noRecordHistory# / #noHistory# 标记，再把
     * {@code ###fypage@-1@*20@} 这类算术应用到页码上，然后把 JS 代码交给运行时求值，
     * 返回求值结果（通常是真实 URL）。
     * <p>
     * 非 .js: URL 原样返回（fypage 等模板占位符由 HkHttp.resolveUrl 在调用方处理）。
     */
    public static String evalLazyUrl(HkRule rule, String url, int page) {
        if (TextUtils.isEmpty(url)) return "";
        int jsIndex = findJsMarker(url);
        if (jsIndex < 0) return url;
        String prefix = url.substring(0, jsIndex);
        String code = url.substring(jsIndex + 4);
        if (page > 0) {
            prefix = prefix.replace("#noRecordHistory#", "").replace("#noHistory#", "");
            Matcher m = FYPAGE_ARITH.matcher(prefix);
            StringBuffer out = new StringBuffer();
            while (m.find()) {
                String ops = m.group(1);
                int p = page;
                if (ops != null) {
                    Matcher op = ARITH_OP.matcher(ops);
                    while (op.find()) {
                        try {
                            int n = Integer.parseInt(op.group(2));
                            String symbol = op.group(1);
                            if ("*".equals(symbol)) {
                                p *= n;
                            } else if ("+".equals(symbol)) {
                                p += n;
                            } else if ("-".equals(symbol)) {
                                p -= n;
                            } else if ("/".equals(symbol) && n != 0) {
                                p /= n;
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                m.appendReplacement(out, Matcher.quoteReplacement("###" + p));
            }
            m.appendTail(out);
            prefix = out.toString();
        }
        HkJsRuntime runtime = getOrCreateRuntime(rule);
        try {
            ensureInit(runtime);
        } catch (Throwable e) {
            SpiderDebug.log("HkJsRuntime", "evalLazyUrl init failed: %s", e.toString());
            return prefix;
        }
        try {
            String result = runtime.evalJs(code, "lazyUrl");
            return TextUtils.isEmpty(result) ? prefix : result;
        } catch (Throwable e) {
            SpiderDebug.log("HkJsRuntime", "evalLazyUrl failed: %s", e.toString());
            return prefix;
        }
    }

    /** 找 ".js:" 出现位置（前一位是 '@' 的不认，如 "@.js:"）。 */
    private static int findJsMarker(String url) {
        int from = 0;
        while (true) {
            int i = url.indexOf(".js:", from);
            if (i < 0) return -1;
            if (i == 0 || url.charAt(i - 1) != '@') return i;
            from = i + 4;
        }
    }
}
