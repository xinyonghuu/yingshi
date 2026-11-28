package com.github.moyingshi.osc.tv.api.hk;

import com.github.catvod.crawler.SpiderDebug;
import com.github.moyingshi.osc.tv.api.SiteApi;
import com.github.moyingshi.osc.tv.bean.Episode;
import com.github.moyingshi.osc.tv.bean.Flag;
import com.github.moyingshi.osc.tv.bean.Site;
import com.github.moyingshi.osc.tv.bean.Vod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 海阔详情 → 标准 Vod 数据模型桥接（第 3 步第 2 件）。
 * <p>
 * 参照被删实现 {@code b056322b} 的 HkDetailBridge（281 行）移植：toVod 装配逻辑
 * 与点击下标槽照原文；站点键与 PlayData 展开、解析门按 yingshi 底座适配（见下）。
 * <p>
 * yingshi 适配（与原文不同）：
 * <ul>
 * <li>站点 key 取 {@link SiteApi#PUSH}（{@code push_agent}），不是原文的
 * {@code hk://<规则名>}：yingshi 里 {@code hk://} 站点会落到 SpiderNull，详情页
 * 取空；第 2 步 {@code HkSiteAdapter.detail()} 同款口径，选集点击走
 * {@code playerContent(PUSH)} → {@code pushPlayer} 直播。</li>
 * <li>原文的分类环（{@code classifyDetailItems}）挂在路由器 detail() 里，本类
 * 的 toVod 在开头自己跑一遍——两个调用方（HkSiteAdapter.detail 的 V4 路、
 * HkListFragment 的 @rule=js: rawItems 路）都经过 toVod，标只落一次。</li>
 * <li>原文 toVod 不解析分集地址（APK 播放时由 player() 懒解析）；yingshi
 * 的选集点击走 playerContent(PUSH)，没有懒解析这一步，所以 toVod 在建集前
 * 用 {@link HkSession#resolveSync} 把最终集址同步解析一次（第 2 步同款口径，
 * 解析不出/不可播的集直接丢弃）。归并判定（HkEpisodeMerger.plan）仍喂
 * 原始地址（归并的入参契约：已解析的干净地址与原始条目不同桶，喂进来会把
 * 同组劈开），解析只对归并后的最终集址做一次、同地址带缓存。</li>
 * <li>PlayData 展开走 {@link HkPlayData}（第 3 步第 3 件改走它；展开后的单线迷你 JSON 口径与原文
 * {@code HkHttpParser.expandLineUrl} 一致）。改走后的行为差（相对旧 org.json 最小解析，
 * 都是向路由器 {@code parsePlayJson} 口径对齐）：字符串形 headers 旧路会丢（{@code optJSONObject}
 * 对串元素回 null），本类现在会收；请求头值旧路不解码，现在过 {@code decodeConflictStr}。</li>
 * <li>原文 toVod 只设 flags；本类另按第 2 步 HkSiteAdapter.detail() 同款
 * $$$ 段拼 playFrom/playUrl——我方 {@code Vod.setFlags(List)} 不从 flags
 * 反推这两串，而详情/播放链有读它们的口径，两路输出形状须一致。</li>
 * <li>原文的直达历史行标记（DIRECT_KEY_MARK/isDirectKeyId/stripDirectKeyMark/
 * isDirectForm）未移植：消费方是原文 VideoActivity 的 hk 历史键钩子，yingshi
 * 没有；点击下标槽与 toVod 不依赖它。</li>
 * </ul>
 */
public class HkDetailBridge {

    // ================= 点击下标一次性承接槽 =================

    /**
     * 点击下标一次性承接槽（参照原文）：HkRouter.play 入口的 MY_POSITION 取值
     * 在点击方（列表/选集面）手里，play 标准入参（key/flag/id）不带下标、
     * api/hk 层无现成载体可达。点击方在发起播放前调
     * {@link #setClickPosition(int, String, String)} 放下标（带规则名＋被点集址身份），
     * {@link HkRouter#play(String, String, int)} 的调用方以
     * {@link #consumeClickPosition(String, String)} 按身份取走（读后复位 -1）；
     * 未放/身份不合时恒 -1＝官 LazyRuleParser.parse 无 position 重载的缺省实参。
     * <p>
     * 旧形是裸 int 全局单槽：无身份、无清口——预热/历史预解析/下一集预加载等推测性
     * 解析与真播同抢一槽（预热先行时真播丢位），被分流的起播（小说早分流等）又留残值
     * 被无关解析领走。现形三件：①身份不合即残值，首个接触者清掉回 -1；②推测性解析
     * 经 begin/endClickPositionSuppressed 抑制对包裹，期间 consume 不领不清（真播的
     * 槽留给真播）；③被分流/失败的起播经 {@link #clearClickPosition} 主动清口，不留残值。
     * <p>
     * 当前接线方（第 3 步第 2 件）：置位在 HkListFragment 卡片点击链（卡片在列表里的
     * 下标，身份＝将要传给 play 的那条地址）；领走在同文件的 playRouted。
     * 详情页选集点击与播放器内切集的置位点（原文在 HkDetailFragment 与
     * VideoActivity）yingshi 尚未接——详情页是标准 DetailActivity、切集在播放器
     * 核心，第 6 件核对播放器线路切换时一并定。
     */
    private static volatile int clickPosition = -1;
    private static volatile String clickPositionRule = "";
    private static volatile String clickPositionUrl = "";
    private static final ThreadLocal<Boolean> CLICK_POSITION_SUPPRESSED =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void setClickPosition(int position, String ruleTitle, String episodeUrl) {
        clickPositionRule = ruleTitle == null ? "" : ruleTitle;
        clickPositionUrl = episodeUrl == null ? "" : episodeUrl;
        clickPosition = position;
    }

    public static int consumeClickPosition(String ruleTitle, String episodeUrl) {
        if (Boolean.TRUE.equals(CLICK_POSITION_SUPPRESSED.get())) return -1;
        int p = clickPosition;
        String r = clickPositionRule;
        String u = clickPositionUrl;
        clearClickPosition();
        if (p < 0) return -1;
        boolean match = (ruleTitle == null ? "" : ruleTitle).equals(r)
                && (episodeUrl == null ? "" : episodeUrl).equals(u);
        return match ? p : -1;
    }

    /** 起播被分流/失败等不经 playRoute 的收口：主动清槽，不留残值。 */
    public static void clearClickPosition() {
        clickPosition = -1;
        clickPositionRule = "";
        clickPositionUrl = "";
    }

    /**
     * 推测性解析抑制对（begin/end 须同线程 try/finally 配对）：抑制期间
     * consumeClickPosition 恒回 -1 且不清槽——预热/预解析/预加载不是
     * 真播，不许领走（也不许销毁）真播的点击位。begin 回旧值供 end 还原
     * （嵌套安全）。
     */
    public static boolean beginClickPositionSuppressed() {
        boolean prev = Boolean.TRUE.equals(CLICK_POSITION_SUPPRESSED.get());
        CLICK_POSITION_SUPPRESSED.set(Boolean.TRUE);
        return prev;
    }

    public static void endClickPositionSuppressed(boolean prev) {
        CLICK_POSITION_SUPPRESSED.set(prev);
    }

    // ================= 详情 → Vod =================

    /**
     * 详情条目装成标准 Vod（参照原文 toVod 的装配顺序）。
     * <p>
     * ① 干跑分类（写标）→ ② 段收编＋准入 → ③ 逐段按线路分组（PlayData 多线
     * 条目先展开成逐线条目）→ ④ 逐（段，线路）分拣 → ⑤ 跨段同名线路合并 →
     * ⑥ 逐线路同片多址归并（原始地址判定）→ ⑦ 最终集址同步解析（带缓存，
     * 解析不出/不可播的集丢弃）→ ⑧ 建 Flag（空线路不建）。
     * <p>
     * 调用方必须在后台线程调（干跑＋逐集解析是耗时操作）。返回的 Vod 可能
     * flags 为空（无段准入或解析全灭）；调用方遇空 flags 按无分集处理，
     * 不推空详情。
     */
    public static Vod toVod(HkRule rule, String itemUrl, HkDetail detail) {
        Vod vod = new Vod();
        vod.setId(itemUrl == null ? "" : itemUrl);
        vod.setName(detail == null ? "" : detail.getTitle());
        vod.setPic(detail == null ? "" : detail.getPic());
        vod.setContent(detail == null ? "" : detail.getContent());
        Site site = new Site();
        // 站点 key 必须是 PUSH：hk:// 站点在 yingshi 里落到 SpiderNull（见类注）。
        site.setKey(SiteApi.PUSH);
        site.setName(rule == null ? "" : rule.title());
        vod.setSite(site);
        if (rule == null || detail == null || detail.getRawItems() == null || detail.getRawItems().isEmpty()) {
            vod.setFlags(new ArrayList<>());
            return vod;
        }
        String pageUrl = detail.getPageUrl() != null && !detail.getPageUrl().isEmpty()
                ? detail.getPageUrl() : (itemUrl == null ? "" : itemUrl);
        // ① 干跑分类：只写标不删条目，准入门读标。
        classifyDetailItems(rule, detail);
        // ② 段收编＋准入（段级结构认据，不逐条判形态）。
        Map<String, List<HkDetailItem>> byLine = new LinkedHashMap<>();
        for (List<HkDetailItem> run : HkChapters.admitEpisodeRuns(
                HkChapters.collectEpisodeRuns(detail.getRawItems()), pageUrl)) {
            Map<String, List<HkDetailItem>> runByLine = new LinkedHashMap<>();
            for (HkDetailItem di : run) {
                // ③ 集址本身是 PlayData 多线 JSON 时，收编期就按线展开成逐线条目
                // （线名＝PlayData 线名）——旧形整串塞进条目自带的那一条线，播放时
                // 恒取首线、其余线（连带各自请求头）结构上不可达。
                List<HkDetailItem> expanded = expandPlayDataItem(di);
                if (expanded != null) {
                    for (HkDetailItem ex : expanded) {
                        runByLine.computeIfAbsent(ex.getLine(), k -> new ArrayList<>()).add(ex);
                    }
                    continue;
                }
                String lineName = di.getLine() == null || di.getLine().isEmpty() ? "默认" : di.getLine();
                runByLine.computeIfAbsent(lineName, k -> new ArrayList<>()).add(di);
            }
            // ④ 分拣以（段，线路）为单位运行：聚类只在段内整理，不许跨段并簇；
            // 分拣后的幸存者再按线路合并建 Flag（同名线路一面）。
            for (Map.Entry<String, List<HkDetailItem>> re : runByLine.entrySet()) {
                List<HkDetailItem> items = re.getValue();
                List<String> urls = new ArrayList<>(items.size());
                for (HkDetailItem di : items) urls.add(di.getUrl() == null ? "" : di.getUrl().trim());
                boolean[] keep = HkEpisodeSorter.select(urls, pageUrl);
                List<HkDetailItem> keptLine = byLine.computeIfAbsent(re.getKey(), k -> new ArrayList<>());
                for (int i = 0; i < items.size(); i++) {
                    if (keep[i]) keptLine.add(items.get(i));
                }
            }
        }
        // ⑥⑦⑧ 逐线路建 Flag：归并在原始地址上判定，解析只对最终集址做一次。
        HkSession session = HkSession.forRule(rule);
        Map<String, String> resolvedCache = new LinkedHashMap<>();
        List<Flag> flags = new ArrayList<>();
        for (Map.Entry<String, List<HkDetailItem>> e : byLine.entrySet()) {
            List<HkDetailItem> items = e.getValue();
            // 候选表（保序）：集名/地址/回退名标记/原始标题
            List<String> candNames = new ArrayList<>();
            List<String> candUrls = new ArrayList<>();
            List<Boolean> candFallback = new ArrayList<>();
            List<String> candTitles = new ArrayList<>();
            for (HkDetailItem di : items) {
                boolean fallbackName = di.getTitle() == null || di.getTitle().isEmpty();
                candNames.add(fallbackName ? ("第" + (candNames.size() + 1) + "集") : di.getTitle());
                candUrls.add(di.getUrl() == null ? "" : di.getUrl().trim());
                candFallback.add(fallbackName);
                candTitles.add(di.getTitle() == null ? "" : di.getTitle());
            }
            if (candUrls.isEmpty()) continue;
            // 同片多址归并（治本通用层）：分拣治「该剔的」，归并治「该并的」——
            // 同组条目折成一集、组内地址展开成同名同序的多条线路。
            // 判定窄口径、拿不准不并；不并时与改前逐字一致地建单个 Flag。
            HkEpisodeMerger.Plan plan = HkEpisodeMerger.plan(candUrls, candTitles, -1);
            if (!plan.merge) {
                Flag flag = Flag.create(e.getKey());
                for (int i = 0; i < candUrls.size(); i++) {
                    String resolved = resolveEpisode(session, candUrls.get(i), resolvedCache);
                    if (resolved.isEmpty()) continue;
                    Episode episode = Episode.create(candNames.get(i), resolved);
                    // 源没给集名的回退序号必须打标（与 Flag/VideoActivity 推送直建同口径）：
                    // Vod.setFlags 末尾 applySingleEpisodeTitle 据此把单集选集显示换成片名全名，
                    // 未打标时海阔单集内容选集只剩「第1集」（他 2026-10-23 单集显名定案）。
                    if (candFallback.get(i)) episode.setNumberName(true);
                    flag.getEpisodes().add(episode);
                }
                if (!flag.getEpisodes().isEmpty()) flags.add(flag);
                continue;
            }
            // 收缩集表：每集代表（折叠集取线路序首位、非折叠集取自身），
            // 每集在各线路下的地址下标（折叠集＝组内成员序，非折叠集恒为自身）。
            int episodeCount = 0;
            for (int v : plan.episodeIndexOf) episodeCount = Math.max(episodeCount, v + 1);
            boolean[] inGroup = new boolean[candUrls.size()];
            int[] repOf = new int[episodeCount];
            java.util.Arrays.fill(repOf, -1);
            int[][] urlIndexOf = new int[episodeCount][];
            for (HkEpisodeMerger.Group g : plan.groups) {
                int epIdx = plan.episodeIndexOf[g.members[0]];
                repOf[epIdx] = g.members[0];
                urlIndexOf[epIdx] = g.members;
                for (int m : g.members) inGroup[m] = true;
            }
            for (int i = 0; i < candUrls.size(); i++) {
                int epIdx = plan.episodeIndexOf[i];
                if (!inGroup[i] && repOf[epIdx] < 0) repOf[epIdx] = i;
                if (urlIndexOf[epIdx] == null) urlIndexOf[epIdx] = new int[]{i};
            }
            for (int line = 0; line < plan.lineNames.length; line++) {
                Flag flag = Flag.create(plan.lineNames[line]);
                for (int j = 0; j < episodeCount; j++) {
                    int[] srcs = urlIndexOf[j];
                    int src = srcs[Math.min(line, srcs.length - 1)];
                    String resolved = resolveEpisode(session, candUrls.get(src), resolvedCache);
                    if (resolved.isEmpty()) continue;
                    Episode episode = Episode.create(candNames.get(repOf[j]), resolved);
                    if (candFallback.get(repOf[j])) episode.setNumberName(true);
                    flag.getEpisodes().add(episode);
                }
                if (!flag.getEpisodes().isEmpty()) flags.add(flag);
            }
        }
        vod.setFlags(flags);
        // playFrom/playUrl 与 flags 同形拼出（见类注）；空 flags 时两串皆空。
        StringBuilder playFrom = new StringBuilder();
        StringBuilder playUrl = new StringBuilder();
        for (Flag flag : flags) {
            if (flag.getEpisodes().isEmpty()) continue;
            if (playFrom.length() > 0) {
                playFrom.append("$$$");
                playUrl.append("$$$");
            }
            playFrom.append(flag.getFlag());
            StringBuilder segment = new StringBuilder();
            for (Episode ep : flag.getEpisodes()) {
                if (segment.length() > 0) segment.append('#');
                segment.append(ep.getName()).append('$').append(ep.getUrl());
            }
            playUrl.append(segment);
        }
        vod.setPlayFrom(playFrom.toString());
        vod.setPlayUrl(playUrl.toString());
        int episodes = 0;
        for (Flag f : flags) episodes += f.getEpisodes().size();
        SpiderDebug.log("hk", "toVod ok rule=%s id=%s flags=%d episodes=%d",
                rule.title(), vod.getId(), flags.size(), episodes);
        return vod;
    }

    /**
     * 直播多线路装成标准 Vod（一线一 Flag，每线单集 "01"），供播放器内切线用。
     * <p>
     * 背景（第 3 步第 6 件）：直链卡片（magnet/video 形）走 playRouted → HkPlay.play
     * 的单地址 push 直播，那条路只播第 0 线——多线路 JSON 里其余线在播放器里
     * 不可达（PUSH 详情只有一个 Push 旗）。多线路时改走本法：把已解析的各线
     * 地址装成详情 Vod（全部 Flag 线路），经 startDirectResolved 推播放器，
     * 切线就是标准 Flag 切换（缓存 Vod 直出，不重新取详情；切集后按集址经
     * pushPlayer 直播，视频格式 parse=0）。单线时不走本法，老路不变。
     * <p>
     * 口径与 {@link #toVod} 同形：站点 key 取 PUSH（hk:// 站点落 SpiderNull）；
     * 集名 "01" 是回退名，按 toVod 同口径打 numberName 标（选集显示换片名全名）；
     * playFrom/playUrl 与 flags 同形拼出。调用方在 UI 线程调（无 I/O，地址已解析）。
     *
     * @return 线路 Vod；data 为空/各线地址全空时 flags 为空，调用方按无分集处理
     */
    public static Vod toVodFromPlayData(HkRule rule, HkItem item, HkPlayData data) {
        Vod vod = new Vod();
        vod.setId(item == null || item.getUrl() == null ? "" : item.getUrl());
        vod.setName(item == null ? "" : item.getTitle());
        vod.setPic(item == null ? "" : item.getPic());
        Site site = new Site();
        site.setKey(SiteApi.PUSH);
        site.setName(rule == null ? "" : rule.title());
        vod.setSite(site);
        List<Flag> flags = new ArrayList<>();
        if (data != null) {
            for (int i = 0; i < data.lineCount(); i++) {
                String url = data.urlAt(i);
                if (url == null || url.trim().isEmpty()) continue;
                Flag flag = Flag.create(data.nameAt(i));
                Episode episode = Episode.create("01", url.trim());
                episode.setNumberName(true);
                flag.getEpisodes().add(episode);
                flags.add(flag);
            }
        }
        vod.setFlags(flags);
        StringBuilder playFrom = new StringBuilder();
        StringBuilder playUrl = new StringBuilder();
        for (Flag flag : flags) {
            if (flag.getEpisodes().isEmpty()) continue;
            if (playFrom.length() > 0) {
                playFrom.append("$$$");
                playUrl.append("$$$");
            }
            playFrom.append(flag.getFlag());
            StringBuilder segment = new StringBuilder();
            for (Episode ep : flag.getEpisodes()) {
                if (segment.length() > 0) segment.append('#');
                segment.append(ep.getName()).append('$').append(ep.getUrl());
            }
            playUrl.append(segment);
        }
        vod.setPlayFrom(playFrom.toString());
        vod.setPlayUrl(playUrl.toString());
        return vod;
    }

    /**
     * 干跑分类环（参照原文 HkRouter.classifyDetailItems，落在本类 toVod 开头）：
     * 逐条隔离——单条干跑抛错只废该条（留 UNKNOWN 口径保留），不许整环中止令
     * 其后条目全数无标。打标门与 toVod 段收编候选同口径：可播形态＋未被四排除
     * ＋JS 型 lazy 才值得干跑；其余条目不会进选集、不必验。
     */
    private static void classifyDetailItems(HkRule rule, HkDetail detail) {
        try {
            if (detail == null || detail.getRawItems() == null) return;
            HkJsRuntime rt = HkEngine.getOrCreateRuntime(rule);
            String pageUrl = detail.getPageUrl() == null ? "" : detail.getPageUrl();
            List<HkDetailItem> raw = detail.getRawItems();
            for (int i = 0; i < raw.size(); i++) {
                HkDetailItem di = raw.get(i);
                String u = di.getUrl() == null ? "" : di.getUrl().trim();
                if (!HkChapters.isPlayForm(u) || HkChapters.isExcluded(di.getColType(), u)) continue;
                if (!HkEpisodeDryClassifier.isJsLazy(u)) continue;
                try {
                    di.setDryVerdict(HkEpisodeDryClassifier.classifyLazyItem(rt, u, pageUrl, i));
                } catch (Throwable itemError) {
                    // 干跑抛错必须落 UNKNOWN 标（本环既定口径），不许留 null
                    // 与「未分类」同形。
                    di.setDryVerdict(HkEpisodeDryClassifier.Verdict.UNKNOWN);
                    SpiderDebug.log("hk", "classifyDetailItems item failed url=%s: %s", u, itemError.toString());
                }
            }
        } catch (Throwable e) {
            SpiderDebug.log("hk", "classifyDetailItems failed: %s", e.toString());
            // 整环失败：还没落标的干跑候选统一补 UNKNOWN，与「分类成功、
            // 本就无标」区分开。
            if (detail != null && detail.getRawItems() != null) {
                for (HkDetailItem di : detail.getRawItems()) {
                    if (di.getDryVerdict() != null) continue;
                    String u = di.getUrl() == null ? "" : di.getUrl().trim();
                    if (!HkChapters.isPlayForm(u) || HkChapters.isExcluded(di.getColType(), u)) continue;
                    if (!HkEpisodeDryClassifier.isJsLazy(u)) continue;
                    di.setDryVerdict(HkEpisodeDryClassifier.Verdict.UNKNOWN);
                }
            }
        }
    }

    /**
     * 集址是 PlayData JSON（urls ≥2）时展开成逐线条目（线名取 PlayData
     * 线名）；非 PlayData 或单线回 null（原条目照旧）。线名归一口径与
     * HkListActivity.namesOf 同形（空名补「线路N」、重名后缀序号）。
     * <p>
     * 经 {@link HkPlayData} 解析（第 3 步第 3 件改走它；行为差见类注）。
     */
    private static List<HkDetailItem> expandPlayDataItem(HkDetailItem di) {
        String raw = di.getUrl() == null ? "" : di.getUrl().trim();
        if (!raw.startsWith("{")) return null;
        HkPlayData data = HkPlayData.fromJson(raw);
        if (data == null || !data.isMultiLine()) return null;
        List<String> lineNames = new ArrayList<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (int i = 0; i < data.lineCount(); i++) {
            String n = data.nameAt(i);
            if (!used.add(n)) n = n + " " + (i + 1);
            used.add(n);
            lineNames.add(n);
        }
        List<HkDetailItem> out = new ArrayList<>();
        for (int i = 0; i < data.lineCount(); i++) {
            HkDetailItem copy = new HkDetailItem();
            copy.setTitle(di.getTitle());
            copy.setPic(di.getPic());
            copy.setDesc(di.getDesc());
            copy.setColType(di.getColType());
            copy.setUrl(data.toSingleLineJson(i));
            copy.setLine(lineNames.get(i));
            out.add(copy);
        }
        return out;
    }

    /** 最终集址同步解析（第 2 步同款口径）：解析不出或解析后不可播的集丢弃。 */
    private static String resolveEpisode(HkSession session, String url, Map<String, String> cache) {
        if (url == null) return "";
        String u = url.trim();
        if (u.isEmpty()) return "";
        if (cache.containsKey(u)) return cache.get(u);
        String out = "";
        if (session != null) {
            try {
                HkSession.Resolved r = session.resolveSync(u);
                if (r != null && !r.empty) {
                    String resolved = r.url == null ? "" : r.url.trim();
                    // 解析门：第 2 步的 isPlayable || 磁力类；ftp 也放行（第 1 件
                    // playRoute 有 ftp playType；原文段收编不收 ftp 形态，解析
                    // 后才现形的 ftp 按可播论）。
                    if (!resolved.isEmpty() && (HkSession.isPlayable(resolved) || HkRouter.isP2pPlayLink(resolved))) {
                        out = resolved;
                    }
                }
            } catch (Throwable e) {
                SpiderDebug.log("hk", "toVod resolve failed url=%s: %s", u, e.toString());
            }
        }
        cache.put(u, out);
        return out;
    }
}
