package com.github.moyingshi.osc.tv.api.hk;

import com.github.catvod.crawler.SpiderDebug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 跨规则搜索执行器，第 2 步第 3 件。
 * <p>
 * 等价于 APK 的混淆类 h62（HkSearchActivity 的 c1() 每搜一次就 new 一个 h62，
 * 逐条规则串行求值，结果按规则分组，经 EventBus i62 事件逐条回推 UI）。
 * <p>
 * 本实现只做引擎部分：对规则列表逐条调 {@link HkListEvalTask#evalSearch}，
 * 串行（不并行——规则运行时共用 Rhino scope 与 HkEvalGuard 序列，并行会互相超驰），
 * 结果按规则分组返回。APK 的逐条回推（i62/onStatusEvent）不在此做：
 * 单规则搜索页一次取一组结果直接渲染；多规则搜索页的逐组渲染与每规则页码留待第 3 步。
 */
public final class HkSearchEngine {

    private HkSearchEngine() {
    }

    /** 一条规则的一组搜索结果（规则 + 该规则搜出的卡片；空列表表示该规则无结果）。 */
    public static final class Group {
        public final HkRule rule;
        public final List<HkItem> items;

        public Group(HkRule rule, List<HkItem> items) {
            this.rule = rule;
            this.items = items == null ? new ArrayList<>() : items;
        }
    }

    /**
     * 多规则搜索（h62 等价）：逐条串行求值，按规则分组返回。
     * 无搜索能力的规则（!hasSearch()）直接跳过并记日志；单条规则求值失败不阻断其余规则。
     * 同步方法，调用方负责放后台线程。
     */
    public static List<Group> search(List<HkRule> rules, String keyword, int page) {
        List<Group> groups = new ArrayList<>();
        if (rules == null || rules.isEmpty()) return groups;
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) return groups;
        for (HkRule rule : rules) {
            if (rule == null) continue;
            if (!rule.hasSearch()) {
                SpiderDebug.log("hk", "search skip rule without search: %s", rule.title());
                continue;
            }
            List<HkItem> items;
            try {
                HkJsRuntime runtime = HkEngine.getOrCreateRuntime(rule);
                HkEngine.ensureInit(runtime);
                items = HkListEvalTask.evalSearch(runtime, rule, kw, page);
            } catch (Throwable e) {
                SpiderDebug.log("hk", "search rule failed %s: %s", rule.title(), e.toString());
                items = new ArrayList<>();
            }
            groups.add(new Group(rule, items));
        }
        return groups;
    }

    /** 单规则搜索的便捷入口（搜索页单规则模式用）：只返回该规则的卡片列表。 */
    public static List<HkItem> searchSingle(HkRule rule, String keyword, int page) {
        if (rule == null) return new ArrayList<>();
        List<Group> groups = search(Collections.singletonList(rule), keyword, page);
        return groups.isEmpty() ? new ArrayList<>() : groups.get(0).items;
    }
}
