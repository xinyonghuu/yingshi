package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 列表求值 Callable，第 1 步。
 * <p>
 * 等价于 APK 的混淆类 x12 的列表分支（REPLICATION.md §4.11）：
 * 超驰检查 → 清空结果 → l2(url) 取数 → token + bootstrap JS →
 * t2(find_rule) 求值 → 快照 f42.j 作为结果。
 * <p>
 * 搜索求值是第 2 步第 3 件（HkSearchActivity + h62），见 {@link #evalSearch}。
 * <p>
 * 注意：x12 原文里 switch 的字段 c 被 jadx 反编译为恒 1（case 0 分支在原文中
 * 不可达），本步不抄那个 switch，直接把列表与搜索写成两个显式方法。
 */
public final class HkListEvalTask implements Callable<List<HkItem>> {

    private final HkJsRuntime runtime;
    private final HkRule rule;
    private final String url;
    private final int page;

    public HkListEvalTask(HkJsRuntime runtime, HkRule rule, String url, int page) {
        this.runtime = runtime;
        this.rule = rule;
        this.url = url;
        this.page = page;
    }

    /** 列表求值入口（同步；调用方负责放后台线程）。 */
    public static List<HkItem> evalList(HkJsRuntime runtime, HkRule rule, String url, int page) {
        return new HkListEvalTask(runtime, rule, url, page).call();
    }

    @Override
    public List<HkItem> call() {
        String key = HkEngine.ruleKey(rule);
        long token = HkEvalGuard.begin(key);
        try {
            runtime.clearResults();
            runtime.setPage(Math.max(1, page));
            HkEvalGuard.check(key, token);
            // 取数：先解 .js: 惰性 URL，再解 fypage 等模板占位符，最后 l2() 准备文档。
            String listUrl = HkEngine.evalLazyUrl(rule, url, page);
            listUrl = HkHttp.resolveUrl(page, listUrl, "", false);
            runtime.fetchDocument(listUrl);
            HkEvalGuard.check(key, token);
            // Bootstrap：插件 + $ 垫片 + method_* + token 上下文。
            String jsToken = runtime.nextToken();
            runtime.bootstrapListEval(jsToken);
            runtime.restoreEvalContext(jsToken);
            HkEvalGuard.check(key, token);
            // 求值：js: 规则直接 eval；选择器规则走 parseDomForArray 回填。
            String findRule = rule.findRule();
            if (findRule.trim().startsWith("js:")) {
                runtime.evalRuleJs(findRule);
            } else if (!findRule.trim().isEmpty()) {
                List<HkItem> items = runtime.parseDomForArrayJava(listUrl, findRule);
                runtime.setResult(items);
            } else {
                SpiderDebug.log("HkJsRuntime", "rule %s has empty find_rule", key);
            }
            HkEvalGuard.check(key, token);
            List<HkItem> result = runtime.getResults();
            if (result.isEmpty()) {
                SpiderDebug.log("HkJsRuntime", "list empty rule=%s url=%s", key, listUrl);
            }
            return new ArrayList<>(result);
        } catch (HkEvalGuard.HkSupersededException e) {
            SpiderDebug.log("HkJsRuntime", "list eval superseded rule=%s", key);
            return new ArrayList<>();
        } catch (Throwable e) {
            SpiderDebug.log("HkJsRuntime", "list eval failed rule=%s: %s", key, e.toString());
            return new ArrayList<>();
        }
    }

    /**
     * 搜索求值：第 2 步第 3 件（HkSearchActivity + h62）。
     * <p>
     * 范式与 {@link #call()} 的列表求值相同，只换两处：求值 URL 是搜索 URL
     *（先 {@code HkEngine.evalLazyUrl} 解 .js:，再
     * {@code HkHttp.resolveUrlEncoded(page, searchUrl, keyword, false)}：
     * {@code **} 换 URL 编码后的关键词，fypage 按 page 算），求值规则串是
     * {@code rule.searchFind()}。
     * <p>
     * searchFind 回落结论（已按 APK 核对，不回落）：APK 的
     * {@code HkSiteAdapter.search} 里 searchFind 为空直接返回空列表，且
     * {@code o52.E()}（即 {@link HkRule#hasSearch()}）要求 search_url 与
     * searchFind 同时非空；故 searchFind 为空时不回落 find_rule，直接返回空列表
     *（见任务文档 §3 的核对记录）。
     * <p>
     * 另两处与 APK 的 x12 搜索分支一致：求值前把关键词置进作用域
     * {@code MY_KEYWORD}；结果直接复用 {@link HkItem}（APK 的 r65 与 w12
     * 同构子集，type="video"，点击分流不读 type，故不另建 r65 类）。
     * <p>
     * 同步方法，调用方负责放后台线程；结果为空不抛错，返回空列表。
     */
    public static List<HkItem> evalSearch(HkJsRuntime runtime, HkRule rule, String keyword, int page) {
        if (runtime == null || rule == null) return new ArrayList<>();
        String key = HkEngine.ruleKey(rule);
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            SpiderDebug.log("HkJsRuntime", "search empty keyword rule=%s", key);
            return new ArrayList<>();
        }
        if (!rule.hasSearch()) {
            SpiderDebug.log("HkJsRuntime", "search not supported rule=%s searchUrl=%s searchFind=%s", key, rule.searchUrl(), rule.searchFind());
            return new ArrayList<>();
        }
        long token = HkEvalGuard.begin(key);
        try {
            runtime.clearResults();
            runtime.setPage(Math.max(1, page));
            HkEvalGuard.check(key, token);
            // 取数：先解 .js: 惰性 URL，再解 fypage 等模板占位符（** 换编码后的关键词）。
            String searchUrl = HkEngine.evalLazyUrl(rule, rule.searchUrl(), page);
            searchUrl = HkHttp.resolveUrlEncoded(page, searchUrl, kw, false);
            runtime.fetchDocument(searchUrl);
            HkEvalGuard.check(key, token);
            // x12 搜索分支：MY_KEYWORD 置进作用域，再走同一套 bootstrap。
            setKeywordBestEffort(runtime, kw);
            String jsToken = runtime.nextToken();
            runtime.bootstrapListEval(jsToken);
            runtime.restoreEvalContext(jsToken);
            HkEvalGuard.check(key, token);
            // 求值：js: 规则直接 eval；选择器规则走 parseDomForArray 回填。
            // searchFind 为空已在 hasSearch() 门挡掉（见上，不回落 find_rule）。
            String findRule = rule.searchFind();
            if (findRule.trim().startsWith("js:")) {
                runtime.evalRuleJs(findRule);
            } else if (!findRule.trim().isEmpty()) {
                List<HkItem> items = runtime.parseDomForArrayJava(searchUrl, findRule);
                runtime.setResult(items);
            } else {
                SpiderDebug.log("HkJsRuntime", "rule %s has empty searchFind", key);
            }
            HkEvalGuard.check(key, token);
            List<HkItem> result = runtime.getResults();
            if (result.isEmpty()) {
                SpiderDebug.log("HkJsRuntime", "search empty rule=%s keyword=%s url=%s", key, kw, searchUrl);
            }
            return new ArrayList<>(result);
        } catch (HkEvalGuard.HkSupersededException e) {
            SpiderDebug.log("HkJsRuntime", "search eval superseded rule=%s", key);
            return new ArrayList<>();
        } catch (Throwable e) {
            SpiderDebug.log("HkJsRuntime", "search eval failed rule=%s: %s", key, e.toString());
            return new ArrayList<>();
        }
    }

    /** MY_KEYWORD 置进 Rhino 作用域（x12 搜索分支）；失败只记日志，不阻断求值。 */
    private static void setKeywordBestEffort(HkJsRuntime runtime, String keyword) {
        try {
            runtime.evalJs("var MY_KEYWORD = " + jsStringLiteral(keyword) + ";", "search");
        } catch (Throwable e) {
            SpiderDebug.log("HkJsRuntime", "set MY_KEYWORD failed: %s", e.toString());
        }
    }

    /** JS 单引号字符串字面量（\ / ' / 换行 / 回车 / 制表转义）。 */
    private static String jsStringLiteral(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "'";
    }

    public static boolean isJsRule(HkRule rule) {
        return rule != null && !TextUtils.isEmpty(rule.findRule()) && rule.findRule().trim().startsWith("js:");
    }
}
