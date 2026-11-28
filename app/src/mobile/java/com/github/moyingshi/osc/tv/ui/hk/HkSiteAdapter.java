package com.github.moyingshi.osc.tv.ui.hk;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;
import com.github.moyingshi.osc.tv.api.SiteApi;
import com.github.moyingshi.osc.tv.api.hk.HkDetail;
import com.github.moyingshi.osc.tv.api.hk.HkDetailBridge;
import com.github.moyingshi.osc.tv.api.hk.HkDetailItem;
import com.github.moyingshi.osc.tv.api.hk.HkEngine;
import com.github.moyingshi.osc.tv.api.hk.HkEvalGuard;
import com.github.moyingshi.osc.tv.api.hk.HkHttp;
import com.github.moyingshi.osc.tv.api.hk.HkItem;
import com.github.moyingshi.osc.tv.api.hk.HkJsRuntime;
import com.github.moyingshi.osc.tv.api.hk.HkRule;
import com.github.moyingshi.osc.tv.api.hk.HkSession;
import com.github.moyingshi.osc.tv.bean.Episode;
import com.github.moyingshi.osc.tv.bean.Flag;
import com.github.moyingshi.osc.tv.bean.Site;
import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.utils.Sniffer;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 海阔小程序站源适配器（第 2 步第 2 件：标准详情）。
 * <p>
 * 等价于 APK 的 {@code HkSiteAdapter.detail()}（1,979 行中的详情分支，见 REPLICATION.md §8.3/§8.4）：
 * 对卡片地址取数并求值规则的详情解析规则，把求值出的卡片按线路分组装成标准
 * {@code Vod/Flag/Episode}，走现有详情页与播放器；不另起一套详情 Activity 体系。
 * <p>
 * 与 APK 的差别（均已在 docs/hk-replicate-1-haikuo-step1.md 第 2 步第 2 节记清）：
 * <ul>
 * <li>APK 的 {@code detail()} 只读 {@code detail_find_rule}（{@code o52.j()}）；
 * 本实现在它为空时回落到 {@code sdetail_find_rule}（任务要求；APK 反编译里
 * {@code sdetail_find_rule} 没有任何读取点）。</li>
 * <li>APK 把分集地址原样存进 Episode，播放时由 {@code player()}（{@code n52.t} 懒解析）
 * 再解析；yingshi 的选集点击走 {@code playerContent(PUSH)}，没有懒解析这一步，
 * 所以组装时就把分集地址同步解析一次（第 3 步第 2 件起由
 * {@link HkDetailBridge#toVod} 做，经 {@link HkSession#resolveSync}，解析不出
 * 的分集直接丢弃）。</li>
 * <li>APK 的 Vod 站点是 {@code hk://标题} 的 Spider 站；yingshi 里 {@code hk://} 站点
 * 会落到 {@code SpiderNull}，所以本站点 key 取 {@link SiteApi#PUSH}，选集点击走
 * {@code playerContent(PUSH)} → {@code pushPlayer} 直播。</li>
 * <li>APK 详情里的同源多址归并（{@code ar1.F} 的 {@code bh} 分组）第 3 步第 2 件
 * 起由 {@link com.github.moyingshi.osc.tv.api.hk.HkEpisodeMerger} 复刻（窄口径
 * 判定，见该类）；多线路不再只按 {@link HkItem#getLine()} 分组。</li>
 * </ul>
 * <p>
 * 调用方必须在后台线程调 {@link #detail(HkRule, HkItem)}（取数 + Rhino 求值是耗时操作），
 * 见 {@link HkListFragment} 的第 4 路分流。
 */
public final class HkSiteAdapter {

    private static final String TAG = "hk-site";

    /** APK 常量：子页面链接前缀（toVods 改写、detail 展开都认它）。 */
    private static final String SUB_PREFIX = "hiker://page/";
    /** APK 常量：subPageId 里真实 URL 的参数名（{@code ?hk_u=}）。 */
    private static final String SUB_TAG = "?hk_u=";
    /** APK 常量：subPageId 里标题的参数名（{@code &hk_t=}）。 */
    private static final String TITLE_TAG = "&hk_t=";

    /** 无线路名时进的默认线路（APK detail() 的分组默认值）。 */
    private static final String DEFAULT_LINE = "默认";

    private HkSiteAdapter() {
    }

    /**
     * 标准详情：对卡片地址取数并求值详情规则，装成标准 Vod。
     *
     * @param rule 所属规则（取 {@code detail_find_rule}，为空回落 {@code sdetail_find_rule}）
     * @param item 被点卡片（取标题/图/简介作回落，取地址作详情页地址）
     * @return 装好的 Vod；规则无详情规则、取数/求值失败、求值结果为空或无可用分集时返回 null
     */
    public static Vod detail(HkRule rule, HkItem item) {
        if (rule == null || item == null) return null;
        String detailRule = rule.detailFindRule();
        boolean sdetailFallback = false;
        if (detailRule == null || detailRule.trim().isEmpty()) {
            detailRule = rule.sdetailFindRule();
            sdetailFallback = true;
        }
        if (detailRule == null || detailRule.trim().isEmpty()) {
            SpiderDebug.log(TAG, "detail skipped: no detail_find_rule rule=%s", rule.title());
            return null;
        }
        String detailUrl = resolveDetailUrl(item);
        if (TextUtils.isEmpty(detailUrl)) {
            SpiderDebug.log(TAG, "detail skipped: no detail url rule=%s item=%s", rule.title(), item.getTitle());
            return null;
        }
        List<HkItem> cards = evalDetail(rule, detailUrl, detailRule);
        if (cards == null || cards.isEmpty()) {
            SpiderDebug.log(TAG, "detail empty rule=%s url=%s sdetail=%s", rule.title(), detailUrl, sdetailFallback);
            return null;
        }
        // 详情信息卡：求值结果里没有可播地址、但带标题/图/简介的那张（不是分集）。
        HkItem info = null;
        for (HkItem card : cards) {
            if (card == null) continue;
            String url = card.getUrl() == null ? "" : card.getUrl().trim();
            if (url.isEmpty() || !isEpisodeUrl(url)) {
                if (info == null && (!card.getTitle().isEmpty() || !card.getPic().isEmpty() || !card.getDesc().isEmpty())) {
                    info = card;
                }
                continue;
            }
        }
        // 标题/图/简介：详情信息卡优先，原卡片回落（APK detail() 直接取缓存的原卡片）。
        String name = info != null && !info.getTitle().isEmpty() ? info.getTitle() : item.getTitle();
        String pic = info != null && !info.getPic().isEmpty() ? info.getPic() : item.getPic();
        String content = info != null && !info.getDesc().isEmpty() ? info.getDesc() : item.getDesc();
        if (info != null && !info.getContent().isEmpty() && content.isEmpty()) content = info.getContent();
        if (info == null && !item.getContent().isEmpty() && content.isEmpty()) content = item.getContent();
        // 第 3 步第 2 件：选集装配改走 HkDetailBridge.toVod——段收编＋准入＋
        // 分拣＋同片多址归并＋逐集同步解析，不再是本方法的按 line 分组＋逐集
        // lazyParse。HkDetail 只装标题/图/简介/页址与原始条目。
        HkDetail detail = new HkDetail();
        detail.setTitle(name);
        detail.setPic(pic);
        detail.setContent(content);
        detail.setPageUrl(detailUrl);
        List<HkDetailItem> rawItems = new ArrayList<>();
        for (HkItem card : cards) rawItems.add(HkDetailItem.fromHkItem(card));
        detail.setRawItems(rawItems);
        Vod vod = HkDetailBridge.toVod(rule, item.getUrl(), detail);
        if (vod != null && vod.getFlags() != null && !vod.getFlags().isEmpty()) {
            int episodes = 0;
            for (Flag f : vod.getFlags()) episodes += f.getEpisodes().size();
            SpiderDebug.log(TAG, "detail ok rule=%s id=%s flags=%d episodes=%d sdetail=%s",
                    rule.title(), vod.getId(), vod.getFlags().size(), episodes, sdetailFallback);
            return vod;
        }
        // APK 的单地址回落：无分集、但详情地址本身就是可播地址时，拼一个默认线路单集。
        // 注意 APK 那里的 strTrim2 是入口懒解析出的播放地址，本实现里 detailUrl 是
        // 详情页地址，所以只在它是视频格式（Sniffer.isVideoFormat，与 #8 同款判定）
        // 时才回落——普通 http 详情页绝不能当成单集，否则点开会去播 HTML 页。
        // 本接线里这路基本走不到（可播地址早在点卡分流第 2 路直接播了），只作安全网。
        HkSession session = HkSession.forRule(rule);
        String direct = resolveEpisodeUrl(session, detailUrl);
        if (!direct.isEmpty() && Sniffer.isVideoFormat(direct)) {
            Vod fallback = new Vod();
            fallback.setId(item.getUrl() == null ? "" : item.getUrl());
            fallback.setName(name);
            fallback.setPic(pic);
            fallback.setContent(content);
            Site site = new Site();
            site.setKey(SiteApi.PUSH);
            site.setName(rule.title());
            fallback.setSite(site);
            Flag flag = Flag.create(DEFAULT_LINE);
            String episodeName = item.getTitle().isEmpty() ? "播放" : item.getTitle();
            flag.getEpisodes().add(Episode.create(episodeName, direct));
            List<Flag> flags = new ArrayList<>();
            flags.add(flag);
            fallback.setFlags(flags);
            fallback.setPlayFrom(DEFAULT_LINE);
            fallback.setPlayUrl(episodeName + '$' + direct);
            SpiderDebug.log(TAG, "detail ok (single fallback) rule=%s id=%s sdetail=%s",
                    rule.title(), fallback.getId(), sdetailFallback);
            return fallback;
        }
        SpiderDebug.log(TAG, "detail no episodes rule=%s url=%s", rule.title(), detailUrl);
        return null;
    }

    /**
     * 详情页真实取数地址。
     * <ul>
     * <li>{@code hiker://empty##<真实地址>}：剥掉前缀用 remainder（空则无地址）。</li>
     * <li>含 {@code ?hk_u=}（toVods 的 subPageId 写法）：先取卡片 {@code extra["url"]}，
     * 空则从 {@code ?hk_u=} 解回真实 URL（APK {@code expandPage} 的顺序）。</li>
     * <li>裸 {@code hiker://page/xxx}：只认卡片 {@code extra["url"]}；APK 的
     * {@code expandPage} 还会按路径查规则的页面规则表（{@code b52}），yingshi 没有
     * 这张表，查不到就返回空。</li>
     * <li>其余：原地址。</li>
     * </ul>
     */
    private static String resolveDetailUrl(HkItem item) {
        String raw = item.getUrl() == null ? "" : item.getUrl().trim();
        if (raw.isEmpty()) return "";
        if (raw.startsWith("hiker://empty")) {
            String rest = raw.substring("hiker://empty".length());
            while (rest.startsWith("#")) rest = rest.substring(1);
            return rest.trim();
        }
        if (raw.contains(SUB_TAG)) {
            String extraUrl = item.extra("url");
            if (!TextUtils.isEmpty(extraUrl)) return extraUrl.trim();
            return subPageRealUrl(raw);
        }
        if (raw.startsWith(SUB_PREFIX)) {
            String extraUrl = item.extra("url");
            if (!TextUtils.isEmpty(extraUrl)) return extraUrl.trim();
            SpiderDebug.log(TAG, "detail subpage without real url: %s", raw);
            return "";
        }
        return raw;
    }

    /** APK subPageRealUrl：取 {@code ?hk_u=} 与 {@code &hk_t=} 之间的 URL 解码值。 */
    private static String subPageRealUrl(String id) {
        int start = id.indexOf(SUB_TAG);
        if (start < 0) return "";
        int from = start + SUB_TAG.length();
        int end = id.indexOf(TITLE_TAG, from);
        String encoded = end < 0 ? id.substring(from) : id.substring(from, end);
        try {
            return URLDecoder.decode(encoded, "UTF-8");
        } catch (Throwable ignored) {
            return encoded;
        }
    }

    /**
     * 详情求值：照 {@link com.github.moyingshi.osc.tv.api.hk.HkListEvalTask} 的范式，
     * 只把求值规则从 {@code find_rule} 换成详情规则：取数 → bootstrap → 求值 → 快照结果。
     * 同步方法，调用方负责放后台线程。
     */
    private static List<HkItem> evalDetail(HkRule rule, String detailUrl, String detailRule) {
        HkSession session = HkSession.forRule(rule);
        if (session == null) return new ArrayList<>();
        HkJsRuntime runtime = session.getRuntime();
        String key = HkEngine.ruleKey(rule);
        long token = HkEvalGuard.begin(key);
        try {
            HkEngine.ensureInit(runtime);
            runtime.clearResults();
            runtime.setPage(1);
            HkEvalGuard.check(key, token);
            String url = HkEngine.evalLazyUrl(rule, detailUrl, 1);
            url = HkHttp.resolveUrl(1, url, "", false);
            runtime.fetchDocument(url);
            HkEvalGuard.check(key, token);
            String jsToken = runtime.nextToken();
            runtime.bootstrapListEval(jsToken);
            runtime.restoreEvalContext(jsToken);
            HkEvalGuard.check(key, token);
            String trimmed = detailRule.trim();
            if (trimmed.startsWith("js:")) {
                runtime.evalRuleJs(detailRule);
            } else {
                List<HkItem> items = runtime.parseDomForArrayJava(url, detailRule);
                runtime.setResult(items);
            }
            HkEvalGuard.check(key, token);
            List<HkItem> result = runtime.getResults();
            if (result.isEmpty()) {
                SpiderDebug.log(TAG, "detail eval empty rule=%s url=%s", key, url);
            }
            return new ArrayList<>(result);
        } catch (HkEvalGuard.HkSupersededException e) {
            SpiderDebug.log(TAG, "detail eval superseded rule=%s", key);
            return new ArrayList<>();
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "detail eval failed rule=%s: %s", key, e.toString());
            return new ArrayList<>();
        }
    }

    /**
     * APK detail() 分组过滤的等价谓词：动作 URL（{@code javascript:/hiker://empty/
     * copy://toast://download://video://}）不要；可播地址、懒解析链、{@code js:}、
     * 磁力/电驴/迅雷地址才当分集。
     */
    private static boolean isEpisodeUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:") || lower.equals("hiker://empty")
                || lower.startsWith("copy://") || lower.startsWith("toast://")
                || lower.startsWith("download://") || lower.startsWith("video://")) {
            return false;
        }
        return lower.startsWith("http://") || lower.startsWith("https://")
                || url.contains("@lazyRule=") || lower.startsWith("js:")
                || lower.startsWith("magnet:") || lower.startsWith("ed2k:")
                || lower.startsWith("thunder:");
    }

    /**
     * 单地址回落用的分集地址同步解析：第 1 步 {@link HkSession#lazyParse} 的各分支
     * 都是同步回调，所以这里能用一个 holder 在当前线程拿到结果；解析不出
     * （webRule/pics/novel、空地址、懒解析链过深）返回 ""，调用方丢弃。
     * 多线路 JSON（{@code {"urls": [...]}}）只取第一条地址，其余线路丢弃——
     * 多线路是第 3 步 HkPlay 的事。
     */
    private static String resolveEpisodeUrl(HkSession session, String url) {
        if (session == null || TextUtils.isEmpty(url)) return "";
        final String[] resolved = new String[1];
        final boolean[] empty = new boolean[1];
        final String[] linesJson = new String[1];
        session.lazyParse(0, new HkSession.HkLazyCallback() {
            @Override
            public void onResolved(String url, String linesJsonValue, int lineIndex) {
                resolved[0] = url;
                linesJson[0] = linesJsonValue;
            }

            @Override
            public void onEmpty() {
                empty[0] = true;
            }
        }, url, "");
        if (empty[0]) return "";
        if (linesJson[0] != null && !linesJson[0].isEmpty()) {
            SpiderDebug.log(TAG, "detail multiline collapsed to first url: %s", url);
        }
        return resolved[0] == null ? "" : resolved[0].trim();
    }
}
