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
 * 搜索求值是第 2 步（HkSearchActivity + h62），本步只做列表。
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

    /** 搜索求值：第 2 步（HkSearchActivity + h62），本步未实现。 */
    public static List<HkItem> evalSearch(HkJsRuntime runtime, HkRule rule, String keyword, int page) {
        SpiderDebug.log("HkJsRuntime", "evalSearch 未实现（第 2 步）：%s", rule == null ? "" : rule.title());
        return new ArrayList<>();
    }

    public static boolean isJsRule(HkRule rule) {
        return rule != null && !TextUtils.isEmpty(rule.findRule()) && rule.findRule().trim().startsWith("js:");
    }
}
