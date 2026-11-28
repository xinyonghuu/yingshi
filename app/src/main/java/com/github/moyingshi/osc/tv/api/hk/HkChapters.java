package com.github.moyingshi.osc.tv.api.hk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 选集收编的官形实现（第 3 步第 2 件）。
 * <p>
 * 参照被删实现 {@code b056322b} 的 HkChapters（211 行）移植：除包名外与原文一致，
 * 只取 toVod 用的段收编与准入部分。海阔 8.83 ArticleListFragment.getChapters
 * 逐行移植。官方从不逐条判「是不是集数」——以被点条目为锚，只收与它相邻的同
 * col_type 连续段（或 cls 含 playlist 的同组条目），段外条目结构上进不来；收编时
 * 只查四个排除项（header 型、url 空、url 含 ignoreVideo/ignoreMusic/
 * "@rule="），url 原样保留、播到该章时才求值。此前旧形三处收集器是
 * 「整页扫＋逐条资格判定」，整页扫必然拉进别的区块，只能不断加判定堵漏
 * （他点名的「遇一个补一个」），本类即其结构替代。
 * <p>
 * yingshi 适配（与原文不同）：原文的播放器侧页面收集器 {@code collect()}
 * （HkItem 列表＋被点下标，锚点收段）未移植——它的消费方是原文的
 * HkPageActivity 章节链（routeEpisode/startPlayerChapters），yingshi
 * 没有这条链；详情选集只走 toVod 的段收编。播放器内换线路（第 3 步第 6 件）
 * 需要章节收集时再视情况补。
 */
public final class HkChapters {

    private HkChapters() {
    }

    /** 官后向段排除：header 型／url 空／url 含 ignoreVideo、ignoreMusic、"@rule="。 */
    public static boolean isExcluded(String colType, String url) {
        if ("header".equals(colType)) return true;
        return isExcludedUrl(url);
    }

    /** 官前向段排除（无 header 项）：url 空／含 ignoreVideo、ignoreMusic、"@rule="——先于断段判定，排除项不断段。 */
    public static boolean isExcludedUrl(String url) {
        if (url == null || url.isEmpty()) return true;
        return url.contains("ignoreVideo") || url.contains("ignoreMusic") || url.contains("@rule=");
    }

    /** 可播形态（toVod 段内候选门）：http(s)／@lazyRule=／js:／磁力三形。 */
    public static boolean isPlayForm(String url) {
        if (url == null) return false;
        String t = url.trim();
        String low = t.toLowerCase(Locale.ROOT);
        if (low.startsWith("http://") || low.startsWith("https://")) return true;
        if (t.contains("@lazyRule=")) return true;
        if (low.startsWith("js:")) return true;
        return low.startsWith("magnet:") || low.startsWith("ed2k:") || low.startsWith("thunder:");
    }

    /**
     * toVod 用（官无此转换，按同一结构原则落，账 §十.3）：详情条目序列按
     * col_type 切连续段，段内只收可播形态且未被四排除的条目（排除项跳过
     * 不断段、type 变即断段）；按段返回（空段不回）。干跑 ACTION 复核由
     * 调用方按标做；分拣（HkEpisodeSorter）以段为单位运行，不许跨段并簇
     * ——旧形只记 runType 不切段、全页平铺合并，段界形同虚设（账 §十一）。
     */
    public static List<List<HkDetailItem>> collectEpisodeRuns(List<HkDetailItem> rawItems) {
        List<List<HkDetailItem>> runs = new ArrayList<>();
        if (rawItems == null) return runs;
        String runType = null;
        List<HkDetailItem> run = null;
        for (HkDetailItem di : rawItems) {
            String type = di.getColType() == null ? "" : di.getColType();
            if (!type.equals(runType)) {
                runType = type;
                run = new ArrayList<>();
                runs.add(run);
            }
            // 头卡是封面元数据（已落 vod.pic），url 常是封面图链，不成集
            if (HkEpisodeQualifier.isHeaderColType(di.getColType())) continue;
            String url = di.getUrl() == null ? "" : di.getUrl().trim();
            if (isExcluded(di.getColType(), url)) continue;
            if (!isPlayForm(url)) continue;
            run.add(di);
        }
        runs.removeIf(List::isEmpty);
        return runs;
    }

    /**
     * toVod 准入（账 playpage-episode-pollution-dig §十三，他 2026-11-13
     * 22:19 准入规矩落码）：收编出的段先过本门才许进选集表——「只有认
     * 得出是线路属下选集段的才放行」，不再收进来再逐条剔。认据只认
     * 段级结构（三选一）：①段内有干跑实证可播（PLAYABLE）的条目；
     * ②段内有 ≥2 条引擎解析型入口（@lazyRule=/js:/磁力三形——集数格
     * 的典型构成）；③段内 http 条目有 ≥2 条同模板成簇、且簇模板与
     * 详情页自身链接模板不同（详情互链格与本页同模板，是导航不是
     * 集数）。干跑 ACTION 在本门先剔（与 toVod 旧口径同，此后归本门
     * 一处所有）。
     * 全页无段合格时只剩一种保底：全页可播形态条目恰一条（单集源的
     * 整页形状），放行其所在段；多条落单互不相认即整页不认——宁可
     * 空表，也不许拿搜索入口/排序钮/互链格凑集数（128 截图案）。
     */
    public static List<List<HkDetailItem>> admitEpisodeRuns(List<List<HkDetailItem>> runs, String pageUrl) {
        List<List<HkDetailItem>> admitted = new ArrayList<>();
        if (runs == null) return admitted;
        List<List<HkDetailItem>> cleaned = new ArrayList<>();
        int totalItems = 0;
        for (List<HkDetailItem> run : runs) {
            List<HkDetailItem> kept = new ArrayList<>();
            for (HkDetailItem di : run) {
                if (di.getDryVerdict() == HkEpisodeDryClassifier.Verdict.ACTION) continue;
                kept.add(di);
            }
            if (!kept.isEmpty()) {
                cleaned.add(kept);
                totalItems += kept.size();
            }
        }
        String pageTemplate = HkEpisodeSorter.template(pageUrl == null ? "" : pageUrl);
        for (List<HkDetailItem> run : cleaned) {
            if (isEpisodeRun(run, pageTemplate)) admitted.add(run);
        }
        if (!admitted.isEmpty()) return admitted;
        // 单集保底：全页恰一条可播形态条目时放行其段（见方法注）。
        if (totalItems == 1) return cleaned;
        return admitted;
    }

    /** 段级认据（见 admitEpisodeRuns 注）：三选一，皆段级结构、不逐条判形态。 */
    private static boolean isEpisodeRun(List<HkDetailItem> run, String pageTemplate) {
        int ruleEntries = 0;
        Map<String, Integer> httpTemplates = new LinkedHashMap<>();
        for (HkDetailItem di : run) {
            if (di.getDryVerdict() == HkEpisodeDryClassifier.Verdict.PLAYABLE) return true;
            String url = di.getUrl() == null ? "" : di.getUrl().trim();
            String low = url.toLowerCase(Locale.ROOT);
            if (url.contains("@lazyRule=") || low.startsWith("js:")
                    || low.startsWith("magnet:") || low.startsWith("ed2k:")
                    || low.startsWith("thunder:")) {
                ruleEntries++;
            } else if (low.startsWith("http://") || low.startsWith("https://")) {
                httpTemplates.merge(HkEpisodeSorter.template(url), 1, Integer::sum);
            }
        }
        if (ruleEntries >= 2) return true;
        for (Map.Entry<String, Integer> e : httpTemplates.entrySet()) {
            if (e.getValue() >= 2 && !e.getKey().equals(pageTemplate)) return true;
        }
        return false;
    }
}
