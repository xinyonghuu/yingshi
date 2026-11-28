package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;
import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.ui.hk.HkSiteAdapter;
import com.github.moyingshi.osc.tv.ui.hk.HkUrlDetector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 海阔路由：dealKind 分流 + 详情预分流 + 播放分流链（第 3 步第 1 件）。
 * <p>
 * 参照被删实现 {@code b056322b} 的 HkRouter（1550 行）移植：分流表、分流顺序、
 * 出口收口、懒链语义照原文；以下按 yingshi 第 1/2 步底座做了适配改写（原文
 * 依赖的类我们没有——HkEngineManager/HkSelector/HkEngine 实例/HkSearchEngine/
 * HkSubpage/HkDetailBridge（第 2 件才有）/HkWebkitFetcher（第 5 件才有））：
 * <ul>
 *   <li>运行时：直接取 {@link HkEngine#getOrCreateRuntime} 的按规则常驻运行时
 *       （与原文构造器的修法 A 同一收益：点击求值与刷新求值落同一运行时，
 *       putMyVar 分类号不丢）；无 HkEngine 实例，destroy() 为空操作（常驻运行
 *       时不许销毁）。</li>
 *   <li>选择器分支：无 HkSelector 引擎。非 JS 懒规则走 HkJsRuntime.evalField
 *       （本件新增，复用运行时 selectChain）；详情选择器分支不自己拼 rawItems，
 *       而是委托 {@link HkSiteAdapter#detail} 一次装成标准 Vod（第 2 步已实现）；
 *       computeLastChapter 的选择器分支回 ""。</li>
 *   <li>搜索来路：无折叠引擎，fromSearch 形参保留但不折叠 sdetail 段，恒用规则
 *       的 detail 段（与第 2 步 HkSiteAdapter.detail 同口径）。</li>
 *   <li>子页面：无 HkSubpage 展开；hiker://page/ 串进播放链时由 checkPlayExit
 *       按 scheme 正表拦下并抛可见错，不静默塞给播放器。</li>
 *   <li>点击位置：playRoute 不直接读 HkDetailBridge（第 2 件才有），而是由
 *       {@link #play(String, String, int)} 形参传入；两参版传 -1（无点击语境）。
 *       第 2 件的 HkDetailBridge.setClickPosition/consumeClickPosition 将调三参版。</li>
 *   <li>网页嗅探：resolveWebRule 的取址缝 {@link #fetchWebRule} 第 5 件已接
 *       HkWebkitFetcher.executeWebRule；其余结构（拆串/去引号/嵌套守卫/JSON/
 *       拆增强）已按原文写好。</li>
 *   <li>干跑分类（classifyDetailItems）与 scopeRule 抓取（captureScopeRule）
 *       本件不做：分类是第 2 件 toVod 的四件套工作；scopeRule 在我们这里
 *       无消费方（VideoActivity 无详情就绪点）。</li>
 *   <li>novel://：原文 detail() 无此分支（小说由阅读器另路处理）；我们无阅读器，
 *       卡片地址是 novel:// 时 detail() 置 dealKind="novel"，调用方 toast
 *       「小说阅读暂不支持」，不推空详情。</li>
 *   <li>日志：原文 Logger.t(TAG) 一律换 SpiderDebug.log("hk", …)。
 *       规则访问器按我们 HkRule 的短名（title()/type()/detailFindRule()/
 *       detailColType()/lastChapterRule()/userAgent()）。</li>
 *   <li>fromJsDetail：我们无 parseDetailRaw/setEvalResCodeBody；预抓用
 *       runtime.fetchDocument（同时给 getResCode/getCode 备正文），求值用
 *       evalRuleJs + getResults（与 HkSiteAdapter.evalDetail 的 JS 支同形），
 *       并用 setDetailParams（本件在 HkJsRuntime 新增）传条目 extra 作 MY_PARAMS。</li>
 *   <li>isVideoOrMusicUrl：后缀门诊断日志（suffixShape）未移植，只留判定逻辑。</li>
 *   <li>clearTag：HkParserKit 在我们树里不存在（只在被删那套里）；按原文
 *       HkUrlDetector.clearTag 同款移植进 {@link HkUrlDetector#clearTag}，
 *       parsePicsInto 调它。</li>
 * </ul>
 * 未验证：APK 的 dealWithUrl 支序与被删实现的差异点（§12.4 待核对项）——
 * 本件以被删实现为准，见文档 item 22。
 */
public class HkRouter {

    private static final String TAG = "hk";

    private final HkRule rule;
    private final HkJsRuntime runtime;

    /**
     * @param rule 所属规则；null 时只做纯分类（dealKind/谓词等静态能力），
     *             需要求值的方法会返回空结果或抛可见错。
     */
    public HkRouter(HkRule rule) {
        this.rule = rule;
        HkJsRuntime rt = rule == null ? null : HkEngine.getOrCreateRuntime(rule);
        if (rt != null) {
            try {
                HkEngine.ensureInit(rt);
            } catch (Throwable e) {
                SpiderDebug.log(TAG, "router init failed rule=%s: %s", rule.title(), e.toString());
            }
        }
        this.runtime = rt;
    }

    /** 按规则名打开路由（规则不存在抛异常；取数口与 HkSession.forRule 同为 HkRuleStore）。 */
    public static HkRouter open(String title) {
        HkRule r = HkRuleStore.load(title);
        if (r == null) throw new IllegalArgumentException("hk rule not found: " + title);
        return new HkRouter(r);
    }

    public HkRule getRule() {
        return rule;
    }

    public HkJsRuntime getRuntime() {
        return runtime;
    }

    /**
     * 详情预分流 + 取数（原文 detail，①②③④ 四段）。
     * <p>
     * 返回的 HkDetail 只带一种有效出口：directPlayUrl（直接播）/ dealKind+dealUrl
     * （按种类分流）/ tabSwitch（重刷列表）/ textResult（弹窗文本）/ actionUrl
     * （动作处理）/ lazyParseFailed（解析失败，不推空详情）/ vod（V4 标准详情）
     * / rawItems（@rule=js: 入口规则求出的原始条目，调用方按 col_type 渲染；
     * 规则级 JS 详情规则走 V4 委托，不出 rawItems）。
     * 取数/解析失败时抛 IllegalStateException（调用方 toast，不要推空详情冒充成功）。
     */
    public HkDetail detail(String itemUrl, HkItem item, boolean fromSearch) {
        HkDetail detail = new HkDetail();
        if (item != null) {
            detail.setTitle(item.getTitle());
            detail.setPic(item.getPic());
            detail.setContent(item.getDesc());
        }
        try {
            if (rule != null) rule.validate();
            // 适配：无折叠引擎，搜索来路也用规则 detail 段（与第 2 步同口径）。
            final String detailFindRule = rule == null ? null : rule.detailFindRule();
            final String detailColType = rule == null ? "" : rule.detailColType();
            String u = itemUrl == null ? "" : itemUrl.trim();

            // P1：hiker://page/ 子页面不进 V4（应由 openPage 打开新列表页）；
            // 若走到这里说明调用方漏拦截，直接返回解析失败避免"0条线路"空 V4。
            if (u.startsWith("hiker://page/")) {
                detail.setLazyParseFailed(true);
                return detail;
            }

            // novel:// 小说章节地址：我们无阅读器，置 dealKind 交调用方 toast，
            // 不推空详情（原文无此分支，见类注）。
            if (u.startsWith("novel://")) {
                detail.setDealKind("novel");
                detail.setDealUrl(u);
                return detail;
            }

            // ① @lazyRule= 条目：先求值 → dealWithUrl 分流（官方：不进 V4）
            int lr = u.indexOf("@lazyRule=");
            if (lr >= 0) {
                // 官方 clickItem → dealLazyRule：分类切换类回调（putMyVar + refreshPage）
                // 只起副作用，靠重刷列表，不进 V4。这里做"变化检测"：
                // 仅当本次求值新产生了 refreshPage 请求时才标记为分类切换，
                // 避免把之前残留的标记误判（consume 保证每次只消费一次）。
                boolean hadRefresh = runtime != null && runtime.isRefreshRequested();
                String v = evalEntryLazy(u, lr);
                if (runtime != null && runtime.consumeRefreshRequest() && !hadRefresh) {
                    detail.setTabSwitch(true);
                    return detail;
                }
                // lazyRule 求值失败（null/空）：标记解析失败，调用方不应推空 V4
                if (v == null || v.trim().isEmpty() || "hiker://empty".equals(v.trim())) {
                    detail.setLazyParseFailed(true);
                    return detail;
                }
                String vt = v.trim();
                String kind = dealKind(vt);
                if (!kind.isEmpty()) {
                    // 官方 dealWithUrl：video 直接播放；pics/x5/web/image/magnet 按种类分流，均不进 V4
                    if ("video".equals(kind)) detail.setDirectPlayUrl(vt);
                    else {
                        detail.setDealKind(kind);
                        detail.setDealUrl(vt);
                    }
                    return detail;
                }
                // 动作 URL（如 toast://）：绝不能被当成直接播放地址。
                if (isActionUrl(vt)) {
                    detail.setActionUrl(vt);
                    return detail;
                }
                // 未知种类：回退旧逻辑（无详情规则则直接播放，否则继续 V4 流程）
                if (vt.contains("#isVideo=true#") || isEmptyDetailRule() || looksLikeMediaUrl(vt)) {
                    detail.setDirectPlayUrl(vt);
                    return detail;
                }
                // @lazyRule= 有结果但无法识别种类：如果结果是有效 HTTP URL，默认按直接播放处理，
                // 避免落入 V4 空详情（官方 dealWithUrl 对未知 http URL 走 web 分流，不进 V4）
                if (vt.toLowerCase().startsWith("http")) {
                    // 毛病1：产出＝页址本身（降级吐回）时不许冒充直播址
                    if (isLazyDegraded(vt, u.substring(0, lr))) {
                        SpiderDebug.log(TAG, "detail lazy degraded (page echoed) rule=%s host=%s",
                                rule == null ? "" : rule.title(), hostOf(vt));
                        detail.setLazyParseFailed(true);
                        return detail;
                    }
                    detail.setDirectPlayUrl(vt);
                    return detail;
                }
                // 非 http 结果也标记为解析失败，不进 V4 空页面
                detail.setLazyParseFailed(true);
                return detail;
            }

            // ② 纯 js: 条目（$.toString 生成）：求值得到真实 URL
            if (u.startsWith("js:")) {
                // 与 @lazyRule= 分支一致：本次求值新产生了 refreshPage 请求 → 标记分类切换，不进 V4
                boolean hadRefresh = runtime != null && runtime.isRefreshRequested();
                String v = evalEntryJs(u);
                if (runtime != null && runtime.consumeRefreshRequest() && !hadRefresh) {
                    detail.setTabSwitch(true);
                    return detail;
                }
                if (v != null && !v.trim().isEmpty()) {
                    String vt = v.trim();
                    // 文本型结果（非 URL、非规则标记）：设为 textResult，调用方弹窗显示，不进 V4/播放
                    String kind = dealKind(vt);
                    boolean isUrl = vt.toLowerCase().startsWith("http") || vt.contains("@rule=")
                            || vt.contains("@lazyRule=") || vt.contains("@inputRule=")
                            || vt.contains("@confirmRule=") || vt.startsWith("js:")
                            || !kind.isEmpty();
                    if (!isUrl) {
                        detail.setTextResult(vt);
                        return detail;
                    }
                    u = vt;
                } else {
                    // 纯 js: 求值空：与 ① @lazyRule= 分支一致，标记解析失败，
                    // 不带 js: 串落入详情规则流程（否则空详情/误播）。
                    detail.setLazyParseFailed(true);
                    return detail;
                }
            }

            // ③ @rule=js: 条目：pageUrl 为 MY_URL，js 部分求值出详情
            int ar = u.indexOf("@rule=");
            if (ar >= 0) {
                // 8.83 原版：传完整 URL 给 JS（含 # 后真实地址），clean 只给非 JS 分支用
                String rawPageUrl = u.substring(0, ar).trim();
                String pageUrl = cleanDetailUrl(rawPageUrl);
                String entryRule = u.substring(ar + 6).trim();
                if (entryRule.startsWith("js:")) {
                    if (item != null && runtime != null) runtime.setDetailParams(item.getExtra());
                    // 8.83 原版：详情条目直接进通用列表渲染，不做线路/选集分类
                    List<HkDetailItem> jsItems = fromJsDetail(entryRule, rawPageUrl, detailColType);
                    if (jsItems == null || jsItems.isEmpty()) {
                        // @rule=js: 求值出空详情：与 ① 分支一致，标记解析失败，不推空 V4
                        detail.setLazyParseFailed(true);
                        return detail;
                    }
                    detail.setPageUrl(pageUrl);
                    detail.setRawItems(jsItems);
                    return detail;
                }
                u = pageUrl;
            }

            // ④ 原有 detail_find_rule 流程
            String ruleText = effectiveDetailRule(detailFindRule);
            if (ruleText == null || ruleText.trim().isEmpty()) {
                // 无详情规则：若条目 URL 本身形如媒体直链 → 直接播放，避免 V4"加载失败"
                String cu = u.trim();
                if (looksLikeMediaUrl(cu)) detail.setDirectPlayUrl(cu);
                // 同 @lazyRule= 分支的 dealWithUrl 口径：裸磁力／ed2k／thunder／ftp 不进 V4，
                // 以 dealKind=magnet 交调用方走播放器（isP2pPlayLink 门），
                // 否则这里回空详情、调用方只能报"加载失败"。
                else if (isP2pPlayLink(cu)) {
                    detail.setDealKind("magnet");
                    detail.setDealUrl(cu);
                }
                return detail;
            }
            // 8.83 原版：JS 分支拿完整 URL（含 #），clean 只给选择器分支用
            String url = cleanDetailUrl(u);
            // 适配：JS 与选择器详情规则都委托第 2 步 HkSiteAdapter.detail 一次装成
            // 标准 Vod（其 evalDetail 已含 JS 支 evalRuleJs + 选择器支
            // parseDomForArrayJava + 选集懒解析 + 回落；原文 fromJsDetail 的
            // rawItems 渲染模型在我们这里没有消费方——VideoActivity 只接 Vod）。
            // 只有 @rule=js: 入口规则（③）才走 fromJsDetail 出 rawItems（第 2 件
            // toVod 再把它装成 Vod）。u 可能被 ②③ 改写过（js: 求值结果、@rule= 前段），
            // 而 detail() 按 item.getUrl() 取详情地址——改写过时传一份换了地址的 item 副本。
            HkItem detailItem = item;
            if (item != null && !u.equals(item.getUrl())) {
                detailItem = new HkItem(item.getTitle(), u, item.getPic(), item.getDesc());
                detailItem.setColType(item.getColType());
                detailItem.setExtra(item.getExtra());
            }
            Vod vod = HkSiteAdapter.detail(rule, detailItem);
            detail.setPageUrl(url);
            detail.setVod(vod);
            // 5d：最新章节（旧 HkExecutor.detailOutcome.lastChapter 同款；只对
            // 小说/漫画类且 last_chapter_rule 非空时算；无现成页正文时多抓一次，
            // 失败回 ""，绝不炸详情页）。
            detail.setLastChapter(computeLastChapter(rule, url, null));
        } catch (Throwable e) {
            // 取数/解析失败不许带着只有基础信息的 detail 冒充成功回：
            // 调用方应对此抛错走"加载失败"toast/错误页。
            SpiderDebug.log(TAG, "detail failed: %s", e.getMessage());
            throw new IllegalStateException("海阔详情加载失败：" + e.getMessage(), e);
        }
        return detail;
    }

    /**
     * 非影视类规则类型谓词（7a：自 HkNonVideo 逐字移入）。规则 type 是不是非影视类
     * （read/cartoon/music/picture）；all/video/tool 等走原视频路。
     */
    public static boolean isNonVideoRule(HkRule rule) {
        String t = typeOf(rule);
        return "read".equals(t) || "cartoon".equals(t) || "music".equals(t) || "picture".equals(t);
    }

    /** 小说/漫画类（章节形态相同，共用 sdetail + 正文/图集两路出口）。 */
    public static boolean isReadRule(HkRule rule) {
        String t = typeOf(rule);
        return "read".equals(t) || "cartoon".equals(t);
    }

    /** 音乐类。 */
    public static boolean isMusicRule(HkRule rule) {
        return "music".equals(typeOf(rule));
    }

    /** 图片类。 */
    public static boolean isPictureRule(HkRule rule) {
        return "picture".equals(typeOf(rule));
    }

    private static String typeOf(HkRule rule) {
        if (rule == null) return "";
        return rule.type().trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 最新章节（5d：旧 {@code HkExecutor.lastChapter} 逐字复刻，调用方由
     * {@code HkExecutor.detailOutcome} 换成 {@link #detail}）。对已抓的详情页正文求值，
     * 不另发请求（pageText 为空时 JS 分支多抓一次）；JS 规则走 evalRule，
     * 选择器规则走 evalField——我们无 HkSelector 引擎，选择器分支回 ""。
     * 任何失败都回 ""（装饰信息，不能炸详情页）。
     */
    private String computeLastChapter(HkRule rule, String detailUrl, String pageText) {
        String field = rule == null ? "" : rule.lastChapterRule();
        if (!isReadRule(rule) || TextUtils.isEmpty(field)) return "";
        String text = pageText;
        if (TextUtils.isEmpty(text)) {
            try {
                // fetchDocument 既取正文又给 getResCode()/getCode() 备 currentDoc；
                // 与 fromJsDetail 的预抓同口，只对小说/漫画类且 last_chapter_rule
                // 非空时多抓一次（V4 支无法复用 HkSiteAdapter 内部的取数）。
                text = runtime == null ? "" : runtime.fetchDocument(detailUrl);
            } catch (Throwable e) {
                SpiderDebug.log(TAG, "lastChapter fetch failed rule=%s: %s", rule.title(), e.getMessage());
                return "";
            }
        }
        if (TextUtils.isEmpty(text)) return "";
        try {
            if (isJsRule(field)) return runtime.evalRule(field).trim();
            // 选择器型 last_chapter_rule：无 HkSelector 引擎，无法求值，回 ""。
            return "";
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "lastChapter eval failed rule=%s: %s", rule.title(), e.getMessage());
            return "";
        }
    }

    /**
     * 条目 URL 里的 {@code @lazyRule=} 求值（与 play() 的 resolveLazyRule 同逻辑，
     * 返回原始求值结果；失败返回 null）。非 JS 懒规则走运行时 evalField
     * （选择器单值提取，复用 selectChain；原文走 HkSelector.evalField）。
     */
    private String evalEntryLazy(String fullUrl, int lrIndex) {
        try {
            String pageUrl = fullUrl.substring(0, lrIndex).trim();
            String r = decodeConflict(fullUrl.substring(lrIndex + 10).trim()).trim();
            boolean js = false;
            if (r.startsWith(".js:")) {
                r = r.substring(4);
                js = true;
            } else if (r.startsWith("js:")) {
                r = r.substring(3);
                js = true;
            }
            if (js) return runtime.evalLazy(r, pageUrl, -1);
            String html = getMarked(pageUrl);
            return runtime.evalField(html, r, pageUrl);
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "entry lazyRule eval failed: %s", e.getMessage());
            return null;
        }
    }

    /** 纯 js: 条目求值（$.toString 生成），期望返回真实 URL 字符串。 */
    private String evalEntryJs(String u) {
        try {
            return runtime.evalLazy(u.substring(3), u, -1);
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "entry js eval failed: %s", e.getMessage());
            return null;
        }
    }

    /**
     * 播放地址完整分流链（M4，设计文档 §6）。
     * <p>
     * 顺序：
     * <ol>
     *   <li>剥 {@code #isVideo=true#} / {@code #noLoading#} 等页面标识；</li>
     *   <li>拆 {@code ;} URL 增强（{@code url;method;encoding;{H@V&&H2@V2}}），header 进结果；</li>
     *   <li>{@code video://} 剥协议；</li>
     *   <li>{@code @lazyRule=}：{@code .js:} 走 JS 独立作用域求值，否则按选择器对剧集页提地址；</li>
     *   <li>{@code @rule=js:}：按 JS 求值；</li>
     *   <li>{@code js:} 整段：按 JS 求值；</li>
     *   <li>{@code {urls:[],names:[],headers:[],subtitle}} JSON：取首地址 + headers；</li>
     *   <li>直链或兜底：清理后的原样地址（播放器/嗅探继续处理）。</li>
     * </ol>
     * <p>
     * 分流本体（playRoute）的全部出口最后统一过 {@link #checkPlayExit}
     * 出口收口：动作形态与不可播 scheme 不许原样交播放器。
     */
    public HkPlay play(String episodeUrl) {
        return play(episodeUrl, null);
    }

    /**
     * 带条目 extra 的播放——extra 只作形参经 playRoute → resolveWebRule /
     * resolveLazyRule / applyLazyResult 逐段传递，不落实例字段（原文环34：
     * Router 按规则复用时，并发/重入的两次 play 会互相覆写、串 extra）。
     * extra 只由持条目的调用方传入，解析器内不自造 extra 来源。
     */
    public HkPlay play(String episodeUrl, String itemExtra) {
        return play(episodeUrl, itemExtra, -1);
    }

    /**
     * 带点击位置的播放（三参版）：clickPos 由 HkDetailBridge（第 2 件）
     * 在点击时按（规则名＋被点集址）预放、此处入口领走；-1=无点击语境
     * （官 parse 无 position 形的缺省实参）。链式懒段段同位传递。
     */
    public HkPlay play(String episodeUrl, String itemExtra, int clickPos) {
        HkPlay out = playRoute(episodeUrl, itemExtra, clickPos);
        checkPlayExit(out);
        return out;
    }

    /**
     * 播放出口收口（普查 G4/G5）：playRoute 的每条返回路都过同一张表：
     * <ul>
     *   <li>动作形态（{@link #isActionUrl}：toast:// / copy:// / input:// /
     *       confirm:// / select:// / download:// / hiker://empty）——与详情
     *       预解析路同等拦截：动作 URL 绝不能被当成直接播放地址。
     *       toast:// 的载荷就是给用户看的话，原样作报错文案；其余动作形态给通用文案。</li>
     *   <li>scheme 正表外的协议（hiker:// 导航串 home@/page/ 等 G5 漏网、
     *       以及任何未登记伪协议）——抛可见错「未解析成播放地址」，不再
     *       透传给播放器静默死（mpv 无此协议处理器、-13）。</li>
     * </ul>
     * 放行不动的：pics 漫画结果（地址位无意义、走阅读分流）、已标 playType
     * 的 P2P（ed2k/magnet/thunder/ftp 原样透传是既定语义）、空结果（维持
     * 既有空语义、各调用方自理）、无协议头的相对址/本地路径（播放器与嗅探
     * 的既有处理面）。
     */
    private void checkPlayExit(HkPlay out) {
        if (out == null) return;
        // dealLazyRule 收口结果（refreshOnly）：回址已在 playRoute 清空、
        // 语义是「重刷当前列表」，不许再按动作地址/未解析拦它。
        if (out.isRefreshOnly()) return;
        if (!out.getPics().isEmpty()) return;
        if (!out.getPlayType().isEmpty()) return;
        String u = out.getUrl();
        if (u == null || u.trim().isEmpty()) return;
        u = u.trim();
        if (isActionUrl(u)) {
            if (u.regionMatches(true, 0, "toast://", 0, "toast://".length())) {
                String msg = u.substring("toast://".length()).trim();
                if (!msg.isEmpty()) throw new IllegalStateException(msg);
            }
            throw new IllegalStateException("海阔规则返回了动作地址，无法直接播放，请换一条试试");
        }
        String scheme = schemeOf(u);
        if (scheme == null) return;
        // 毛病1 出口侧：懒产出来源标记在身、且产出就是那一页本身
        // （非媒体直链形）＝降级吐回的剧集网页——协议头合法也拦，
        // 按未解析明错收口，不许喂给播放器当媒体。
        String stagePage = out.getLazyStagePage();
        if (!stagePage.isEmpty() && u.equals(stagePage) && !looksLikeMediaUrl(u)) {
            SpiderDebug.log(TAG, "play exit blocked lazy-degraded rule=%s host=%s",
                    rule == null ? "" : rule.title(), hostOf(u));
            throw new IllegalStateException("海阔懒解析未返回播放地址，请换一条试试");
        }
        if (PLAYABLE_SCHEMES.contains(scheme)) return;
        // P2P 四形未标 playType 时（如懒函数直回 magnet）同为合法终形：
        // hkPlayerContent 的 isP2pPlayLink 门会按实际地址分流迅雷边下边播
        if ("ed2k".equals(scheme) || "magnet".equals(scheme)
                || "thunder".equals(scheme) || "ftp".equals(scheme)) return;
        throw new IllegalStateException("海阔地址未解析成播放地址，请换一条试试");
    }

    /**
     * 地址的协议头（小写）：有 :// 取其头；只有冒号的（magnet:/ed2k: 型）头
     * 须是纯协议词（不含点号——带点号的是 host:port 形主机名、按无协议头
     * 处理）；都不合回 null（相对址/本地路径，维持透传）。
     */
    private static String schemeOf(String u) {
        int sep = u.indexOf("://");
        if (sep > 0) return u.substring(0, sep).toLowerCase();
        int colon = u.indexOf(':');
        if (colon > 0) {
            String head = u.substring(0, colon).toLowerCase();
            if (head.matches("[a-z][a-z0-9+-]*")) return head;
        }
        return null;
    }

    /** 出口正表：播放器/嗅探真能打开的协议。表外一律视为未解析（见 checkPlayExit）。 */
    private static final Set<String> PLAYABLE_SCHEMES = new HashSet<>(
            Arrays.asList("http", "https", "rtsp", "rtmp", "rtmps", "udp", "rtp",
                    "mms", "srt", "smb", "file", "content"));

    private HkPlay playRoute(String episodeUrl, String itemExtra, int clickPos) {
        HkPlay out = new HkPlay();
        if (episodeUrl == null) return out;
        String u = episodeUrl.trim();
        // -2. x5Play:// 剥壳（定根账 hk-sniff-route-miswire-20261113）：官家
        // x5Play 是播放器地址——播放器取址口先 clearTag 剥前缀再 getPlayData；余量是
        // 播放数据 JSON（包装型规则 lazy 的标准回址）或普通网址。不剥则
        // "{...}" 载荷在步骤 2 被 splitEnhancement 当 ;{请求头} 块吃掉、只剩
        // "x5Play://" 裸串被出口正表抛「未解析成播放地址」，永远播不了。
        if (u.startsWith("x5Play://")) u = u.substring("x5Play://".length()).trim();
        // -1. webRule:// / x5Rule:// 网页嗅探（8.83 WebkitParser；G36：官
        // X5WebViewParseRulePair 两前缀同判同处理，咱家无 X5、系统 WebView
        // 顶替同走一条嗅探路）：必须在 @lazyRule/普通分流之前判定，
        // 否则伪协议串会被后续分流原样塞给播放器
        if (isWebSniffRule(u)) {
            // 与步骤 4 @lazyRule= 同口径：嗅探没出地址就是失败、停住抛可见错，
            // 不回空 HkPlay（调用方会把空地址组装成空 Result 静默死 00:00、无报错）
            if (!resolveWebRule(u, out, itemExtra) || out.isEmpty()) {
                throw new IllegalStateException("海阔网页嗅探未返回播放地址，请换一条试试");
            }
            return out;
        }
        // 0. @lazyRule= 优先提取（必须在 splitEnhancement 之前：JS 代码里含 {}/; 等字符）
        int lr0 = u.indexOf("@lazyRule=");
        String lazyRuleText = null;
        String lazyPageUrl = null;
        if (lr0 >= 0) {
            lazyPageUrl = u.substring(0, lr0).trim();
            lazyRuleText = decodeConflict(u.substring(lr0 + 10).trim());
            u = lazyPageUrl;
        }
        // 0.05 子页面选集：原文走 HkSubpage.expand 展开（我们无 HkSubpage）。
        // hiker://page/ 串落到步骤 8 兜底后由 checkPlayExit 按 scheme 正表拦下
        // 并抛可见错（见类注），不静默塞给播放器。
        // 0.1 播放数据 JSON 必须最先认：urls 里的地址常带 #isVideo=true# 等标记，
        // 若先走下面第 1 步按 # 切串，JSON 会被拦腰截断、解析失败后整串塞给播放器（白屏）
        // 入场门照官 HttpParser.getPlayData 本体：仅 startsWith("{")＋endsWith("}")，
        // 无 "\"urls\"" 含串预卡——url-only JSON（只有单数 url）同样进 parsePlayJson；
        // 六处调用点（本处、@rule=js:/js: 结果、步骤 7、resolveWebRule、applyLazyResult）同形。
        if (lazyRuleText == null && u.startsWith("{") && u.endsWith("}")) {
            if (parsePlayJson(u, out)) return out;
        }
        // 0.5 P1：页面标识里的播放标记（剥 # 之前先提取）
        // #isM3u8# 强制 m3u8；#concat# 多段拼接；#fastPlayMode#/#threads=N# 极速模式
        if (u.contains("#isM3u8#")) out.setForceM3u8(true);
        if (u.contains("#concat#")) out.setConcat(true);
        if (u.contains("#fastPlayMode#")) out.setFastPlayMode(true);
        java.util.regex.Matcher tm = java.util.regex.Pattern.compile("#threads=(\\d+)#").matcher(u);
        if (tm.find()) {
            try {
                out.setThreads(Integer.parseInt(tm.group(1)));
                out.setFastPlayMode(true);
            } catch (Throwable ignored) {
            }
        }
        // 1.5 P1：pics:// 漫画多图模式（非视频，记入 pics 列表，UI 提示不支持但不崩）
        if (u.startsWith("pics://")) {
            parsePicsInto(u, out);
            return out;
        }
        // 1.6 P1：特殊协议识别（ed2k/magnet/thunder/ftp），原样透传给播放器。
        // 口径与 isP2pPlayLink / Thunder.match 的 scheme 判定看齐：只认 scheme 前缀，
        // 不要求 "//"（ed2k:/thunder: 无双斜杠形态同样进迅雷线，不走系统选应用）。
        String low = u.toLowerCase();
        if (low.startsWith("ed2k:")) out.setPlayType("ed2k");
        else if (low.startsWith("magnet:")) out.setPlayType("magnet");
        else if (low.startsWith("thunder:")) out.setPlayType("thunder");
        else if (low.startsWith("ftp://")) out.setPlayType("ftp");
        // 2. ; URL 增强（必须在 # 截断之前：8.83 是先从完整串解析 ;{...} 请求头块、
        // 再剥标记；旧顺序先按 # 一刀切，url#标记;{Referer@x} 的请求头块会被连带丢掉）
        u = splitEnhancement(u, out);
        // 2.5 # 页面标识截断（#threads 等已在 0.5 提取完毕）
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash).trim();
        // 3. video://
        if (u.startsWith("video://")) u = u.substring(8).trim();
        // 4. @lazyRule=（已在步骤0提取）
        if (lazyRuleText != null) {
            // 官 dealLazyRule 收口：普通行 @lazyRule= 回调里 putMyVar + refreshPage 的那类
            // （轻合集容器设置页排序两段式的第一点、启用/禁用翻转）只起副作用，靠重刷
            // 当前列表让重渲后的新闭包生效，本身不是播放。与 detail() 的
            // 变化检测同口径合并：求值前记 hadRefresh，求值后消费标记、
            // 仅新产生才算命中（consume 保证只消费一次，不误判残留）。
            // 命中即清掉回址置 refreshOnly 交调用方重刷——回址多为陪衬的
            // hiker://empty，堵在此处不许再往下被当播放地址送；不进播放链，
            // 也不走下方「没出地址」的抛错。
            boolean hadRefresh = runtime != null && runtime.isRefreshRequested();
            resolveLazyRule(lazyPageUrl, lazyRuleText, out, 0, itemExtra, clickPos);
            if (runtime != null && runtime.consumeRefreshRequest() && !hadRefresh) {
                out.setUrl("");
                out.setRefreshOnly(true);
                SpiderDebug.log(TAG, "lazy refresh-only rule=%s", rule == null ? "" : rule.title());
                return out;
            }
            // 照 8.83：懒解析没出地址就是失败、停住报错；
            // 旧代码继续拿剧集页地址走兜底，最终把网页塞给播放器（白屏/解析失败）。
            // 空结果不再回空 HkPlay：调用方会把空地址组装成空 Result 静默死
            // （00:00、无报错），故这里直接抛可见错。
            if (out.isEmpty()) {
                throw new IllegalStateException("海阔懒解析未返回播放地址，请换一条试试");
            }
            return out;
        }
        // 5. @rule=js:
        int ar = u.indexOf("@rule=");
        if (ar >= 0) {
            String pageUrl = u.substring(0, ar).trim();
            String ruleText = u.substring(ar + 6).trim();
            if (ruleText.startsWith("js:")) {
                String real = evalJsPlay(ruleText.substring(3), pageUrl);
                // 与步骤 4 @lazyRule= 同口径：js: 求值空就是失败、停住抛可见错，
                // 不落 pageUrl 兜底（否则网页被塞给播放器白屏/解析失败）
                if (real == null || real.isEmpty()) {
                    throw new IllegalStateException("海阔 @rule=js: 求值未返回播放地址，请换一条试试");
                }
                real = real.trim();
                if (real.startsWith("{") && real.endsWith("}") && parsePlayJson(real, out)) return out;
                // js: 结果是 webRule:// / x5Rule:// 嗅探串时走网页嗅探（P1/G36），不再原样塞播放器
                if (isWebSniffRule(real)) {
                    if (!resolveWebRule(real, out, itemExtra) || out.isEmpty()) {
                        throw new IllegalStateException("海阔网页嗅探未返回播放地址，请换一条试试");
                    }
                    return out;
                }
                applyLazyResult(real, out, 0, pageUrl, itemExtra, clickPos);
                if (out.isEmpty()) {
                    throw new IllegalStateException("海阔 @rule=js: 懒解析未返回播放地址，请换一条试试");
                }
                return out;
            }
            u = pageUrl;
        }
        // 6. js: 整段
        if (u.startsWith("js:")) {
            String real = evalJsPlay(u.substring(3), u);
            // 与步骤 4 @lazyRule= 同口径：js: 求值空就是失败、停住抛可见错，
            // 不落步骤 8 兜底（否则 js: 原串会被原样塞给播放器）
            if (real == null || real.isEmpty()) {
                throw new IllegalStateException("海阔 js: 求值未返回播放地址，请换一条试试");
            }
            real = real.trim();
            if (real.startsWith("{") && real.endsWith("}") && parsePlayJson(real, out)) return out;
            // js: 结果是 webRule:// / x5Rule:// 嗅探串时走网页嗅探（P1/G36），不再原样塞播放器
            if (isWebSniffRule(real)) {
                if (!resolveWebRule(real, out, itemExtra) || out.isEmpty()) {
                    throw new IllegalStateException("海阔网页嗅探未返回播放地址，请换一条试试");
                }
                return out;
            }
            // js: 整段以自身为 MY_URL 求值，没有真实宿主页面，产出页传空
            applyLazyResult(real, out, 0, "", itemExtra, clickPos);
            if (out.isEmpty()) {
                throw new IllegalStateException("海阔 js: 懒解析未返回播放地址，请换一条试试");
            }
            return out;
        }
        // 7. 播放数据 JSON（官 getPlayData 门：首 "{" 尾 "}"，无 urls 预卡）
        if (u.startsWith("{") && u.endsWith("}")) {
            if (parsePlayJson(u, out)) return out;
        }
        // 8. 兜底
        out.setUrl(u);
        return out;
    }

    /**
     * V2 分类 tab 点击：求值条目 url 里的 {@code @lazyRule=}，只为其副作用
     * （如 {@code putMyVar} 设置分类变量），不取播放地址。调用后由上层重新
     * 刷新列表。求值异常抛 IllegalStateException 给上层（调用方既有 catch 会
     * toast「页签切换失败」）。
     *
     * @return 规则回调里是否调了 refreshPage（调用方据此决定是否重刷；当前
     * 调用方在 evalTab 后本来就会刷新列表，故恒为 true 语义）
     */
    public boolean evalTab(String tabUrl) {
        if (tabUrl == null) return false;
        int lr = tabUrl.indexOf("@lazyRule=");
        if (lr < 0) return false;
        try {
            String r = decodeConflict(tabUrl.substring(lr + 10).trim()).trim();
            if (r.startsWith(".js:")) r = r.substring(4);
            else if (r.startsWith("js:")) r = r.substring(3);
            // 官方 LazyRuleParser.parseByJs：input = @lazyRule= 前的 URL 部分（lazyRule[0]）
            String prefix = tabUrl.substring(0, lr).trim();
            runtime.evalLazy(r, prefix.isEmpty() ? null : prefix, -1);
        } catch (Throwable e) {
            // 求值失败不许照常 return true 冒充成功，抛出去走调用方既有失败出口。
            SpiderDebug.log(TAG, "tab eval failed: %s", e.getMessage());
            throw new IllegalStateException("页签分类求值失败：" + e.getMessage(), e);
        }
        // 规则标准写法是 setItem(...); refreshPage(true); —— 无论是否显式调用，
        // tab 点击后都需要重刷；显式调用时打日志便于排障。
        boolean asked = runtime != null && runtime.consumeRefreshRequest();
        SpiderDebug.log(TAG, "tab eval done, refreshPage asked=%s", String.valueOf(asked));
        return true;
    }

    /**
     * 拆 {@code ;} URL 增强：{@code url;method;encoding;{K@V&&K2@V2}}。
     * header 块进结果，method/encoding 丢弃，只保留纯 url。
     */
    private String splitEnhancement(String u, HkPlay out) {
        int brace = u.indexOf('{');
        int braceEnd = u.lastIndexOf('}');
        if (brace > 0 && braceEnd > brace) {
            String inner = u.substring(brace + 1, braceEnd);
            for (String kv : inner.split("&&")) {
                int at = kv.indexOf('@');
                // 请求头值照 8.83 过 decodeConflictStr（＆＆/；；等全角转义要还原），否则发错头
                if (at > 0) out.putHeader(kv.substring(0, at).trim(), decodeConflict(kv.substring(at + 1).trim()));
            }
            u = (u.substring(0, brace) + u.substring(braceEnd + 1)).trim();
        }
        int semi = u.indexOf(';');
        if (semi >= 0) u = u.substring(0, semi).trim();
        return u;
    }

    /**
     * 网页嗅探串判据（G36）：webRule:// 与 x5Rule:// 两前缀同判——官
     * X5WebViewParseRulePair 对两前缀同判同处理；咱家不接 X5，x5Rule 走系统
     * WebView 顶替、与 webRule 同入 {@link #resolveWebRule} 一条嗅探路。
     * 大小写敏感（官 WebkitParser/X5WebViewParser 均 startsWith 敏感）。
     */
    private static boolean isWebSniffRule(String s) {
        return s != null && (s.startsWith("webRule://") || s.startsWith("x5Rule://"));
    }

    /**
     * webRule:// / x5Rule:// 分流（8.83 WebkitParser.parse）：整串交嗅探全承载，
     * 经嗅探回真实地址；失败/超时回空并留痕，绝不把伪协议串原样塞给播放器。
     * 拿到真实地址后与 lazy 同口径：先认 {urls} JSON，再当直链。
     * <p>
     * 适配：取址缝 {@link #fetchWebRule} 第 5 件已接 HkWebkitFetcher.executeWebRule
     * （系统 WebView 后台抓取；依赖全桥 finishParse 的嗅探式规则仍回空——我方无全桥）；
     * 拆串（页@解析js）与 type 按前缀真值都归解析器侧，本处不再自拆自传。
     */
    private boolean resolveWebRule(String webRuleUrl, HkPlay out, String itemExtra) {
        String real = "";
        {
            int prefixLen = webRuleUrl.startsWith("x5Rule://") ? 9 : 10;
            String body = webRuleUrl.length() > prefixLen ? webRuleUrl.substring(prefixLen) : "";
            int at = body.indexOf('@');
            String loadUrl = (at >= 0 ? body.substring(0, at) : body).trim();
            String ruleJs = at >= 0 ? body.substring(at + 1) : "";
            if (!loadUrl.isEmpty() && !ruleJs.isEmpty()) {
                try {
                    real = fetchWebRule(loadUrl, ruleJs);
                    if (real != null) {
                        real = real.trim();
                        if (real.length() >= 2 && real.startsWith("\"") && real.endsWith("\"")) {
                            try {
                                real = new org.json.JSONTokener(real).nextString('"');
                            } catch (Throwable ignored) {
                                real = real.substring(1, real.length() - 1);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    real = "";
                }
            }
        }
        if (TextUtils.isEmpty(real)) {
            // 留痕用页址只为日志 host：按官前缀剥位后取首个 '@' 前段，不参与解析
            int prefixLen = webRuleUrl.startsWith("x5Rule://") ? 9 : 10;
            String body = webRuleUrl.length() > prefixLen ? webRuleUrl.substring(prefixLen) : "";
            int at = body.indexOf('@');
            String loadUrl = (at >= 0 ? body.substring(0, at) : body).trim();
            SpiderDebug.log(TAG, "webRule parse empty rule=%s host=%s",
                    rule == null ? "" : rule.title(), hostOf(loadUrl));
            return false;
        }
        real = real.trim();
        // x5Play:// 剥壳（同 playRoute 入口，三口齐）：嗅探结果同为一个取址口，
        // 剥在嵌套检查之前——叠形 x5Play://webRule:// 剥后仍是嗅探串、由下面
        // 嵌套支按失败收口，不许穿过去落 setUrl 当地址
        if (real.startsWith("x5Play://")) real = real.substring("x5Play://".length()).trim();
        if (isWebSniffRule(real)) {
            // 解析结果又是 webRule：不再递归嗅探，按空处理（防死循环）
            SpiderDebug.log(TAG, "webRule nested result rule=%s", rule == null ? "" : rule.title());
            return false;
        }
        if (real.startsWith("{") && real.endsWith("}") && parsePlayJson(real, out)) return true;
        out.setUrl(splitEnhancement(real, out));
        return true;
    }

    /**
     * 网页嗅探取址缝（第 5 件已接）：系统 WebView 后台加载 loadUrl、1 秒后执行
     * ruleJs，返回执行结果（HkWebkitFetcher.executeWebRule，无全桥/无注入、
     * type 写死 webkit）。空结果由调用方按原逻辑处理（resolveWebRule 的
     * 空串分支）。
     */
    private String fetchWebRule(String loadUrl, String ruleJs) {
        try {
            return HkWebkitFetcher.executeWebRule(loadUrl, ruleJs, null);
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "webRule fetch failed: %s", e.toString());
            return "";
        }
    }

    /**
     * pics:// 串解析（play 步骤 1.5 与 applyLazyResult 的 pics 分支共用同一口径，
     * 防两处漂移）：清标记后先按行拆、再按字面 "&&" 拆——8.83 按字面 "&&" 分隔；
     * 旧写法 "[\\n&&]+" 是字符类，图片地址查询参数里的 & 会被切碎。
     *
     * @param picsUrl 以 {@code pics://} 开头的完整串
     */
    private static void parsePicsInto(String picsUrl, HkPlay out) {
        out.setPicsAttempted(true);
        String rest = HkUrlDetector.clearTag(picsUrl.substring(7).trim());
        for (String line : rest.split("\\n")) {
            for (String p : line.split("&&")) {
                String t = p.trim();
                if (!t.isEmpty()) out.addPic(t);
            }
        }
    }

    /**
     * pics:// 串拆分的共用出口（列表页/搜索页/详情页各入口一律走这里，
     * 不许再各自裸 split("&&")）：内部就是 {@link #parsePicsInto} 同一口径
     * （清标记、先按行拆、再按字面 "&&" 拆、trim 滤空段）。
     *
     * @param picsUrl 以 {@code pics://} 开头的完整串（大小写不敏感，调用方已判）
     * @return 拆出的图片条目（可空表，绝不为 null）
     */
    public static List<String> parsePics(String picsUrl) {
        HkPlay tmp = new HkPlay();
        if (picsUrl != null && picsUrl.length() >= 7) parsePicsInto(picsUrl, tmp);
        return tmp.getPics();
    }

    /**
     * 拆完是否有可加载的图：至少一条以 http:// 或 https:// 开头
     * （条目可带 {@code @Referer=} 等后缀，不影响前缀判定）。
     * 空体（"pics://" 后无内容）与全是非 http 条目都算无图——
     * 此判据只许作各入口的强制留底判据，**不许据此拦入口**
     * （fix4）：阅读器自带回退解析，入口判空只是入口这一刻的拆分结果，判死
     * 会把整条回退链一并掐断。
     */
    public static boolean hasLoadablePic(List<String> pics) {
        if (pics == null) return false;
        for (String p : pics) {
            if (p == null) continue;
            String t = p.trim();
            if (t.regionMatches(true, 0, "http://", 0, 7)
                    || t.regionMatches(true, 0, "https://", 0, 8)) return true;
        }
        return false;
    }

    /**
     * 解析结果再分流：结果本身又是 {@code @lazyRule=} 串时递归解析下一段
     * （短剧两段式：第一段脚本返回的还是带懒规则的地址，海阔原版是循环解析到
     * 干净地址为止；旧实现直接对整串做 splitEnhancement，花括号匹配会跨进
     * 脚本里、把第二段懒规则连同地址一起毁掉，最终把 uri: 串塞给播放器报
     * Malformed URL）。深度上限 3 防死循环。
     *
     * @param pageUrl 本次求值实际跑的页面地址（产出该结果的页面），
     *                结果是 pics 时记入 {@link HkPlay#setPageUrl}；调用方
     *                拿不到真实页面（如 js: 整段以自身为 MY_URL）时传空串。
     */
    private void applyLazyResult(String v, HkPlay out, int depth, String pageUrl, String itemExtra, int clickPos) {
        // 毛病1：记下本段产出来源（净形页址），供出口 checkPlayExit
        // 判降级产物；js: 整段形 pageUrl 为空、标记随之为空＝不启用。
        out.setLazyStagePage(lazyPageClean(pageUrl));
        v = v.trim();
        // x5Play:// 剥壳（同 playRoute 入口）：懒函数回 x5Play://{播放数据}
        // 是包装型规则的标准回址，先剥壳再过下面的 JSON 门
        if (v.startsWith("x5Play://")) v = v.substring("x5Play://".length()).trim();
        // 懒解析直接返回播放数据 JSON 时当场拆开，否则整串会被当播放地址塞给播放器
        if (v.startsWith("{") && v.endsWith("}") && parsePlayJson(v, out)) return;
        // 懒解析结果是 webRule:// / x5Rule:// 嗅探串时走网页嗅探，不再原样塞播放器
        if (isWebSniffRule(v)) {
            resolveWebRule(v, out, itemExtra);
            return;
        }
        // 懒解析现算出的 pics:// 是漫画多图（与步骤 1.5 同语义）：记入 pics 列表
        // 供上层分流进阅读器，绝不许落到末尾当视频地址吞掉。
        // 产出页（本次求值实际跑的页面）一并记入 out，供分流时作会话页面地址。
        if (v.startsWith("pics://")) {
            parsePicsInto(v, out);
            if (pageUrl != null && !pageUrl.isEmpty()) out.setPageUrl(pageUrl);
            SpiderDebug.log(TAG, "lazy resolved pics rule=%s pics=%d host=%s",
                    rule == null ? "" : rule.title(), out.getPics().size(), hostOf(out.getPageUrl()));
            return;
        }
        int lr = v.indexOf("@lazyRule=");
        if (lr >= 0) {
            if (depth >= 3) {
                SpiderDebug.log(TAG, "lazy chain too deep rule=%s", rule == null ? "" : rule.title());
                return;
            }
            String page = v.substring(0, lr).trim();
            String nextRule = decodeConflict(v.substring(lr + 10).trim());
            // 页址部分的 ;增强段先拆（此时串里已无脚本，花括号匹配安全）
            page = splitEnhancement(page, out);
            int hash = page.indexOf('#');
            if (hash >= 0) page = page.substring(0, hash).trim();
            SpiderDebug.log(TAG, "lazy chain depth=%d rule=%s host=%s",
                    depth + 1, rule == null ? "" : rule.title(), hostOf(page));
            resolveLazyRule(page, nextRule, out, depth + 1, itemExtra, clickPos);
            return;
        }
        // 毛病1 产出侧：懒段产出＝页址本身（净形逐字相同）＝规则体残缺
        // 时的降级吐回，不许当播放地址写进 out——playRoute 后续步骤
        // （空 url 判定）会按既有「未返回播放地址」明错收口。
        if (isLazyDegraded(v, pageUrl)) {
            SpiderDebug.log(TAG, "lazy degraded (page echoed) rule=%s host=%s",
                    rule == null ? "" : rule.title(), hostOf(v));
            return;
        }
        out.setUrl(splitEnhancement(v, out));
        SpiderDebug.log(TAG, "lazy resolved rule=%s -> %s", rule == null ? "" : rule.title(), hostOf(out.getUrl()));
    }

    /**
     * JS 懒发入参页址清理（与 playRoute 步骤 2/2.5 对 u 的清理同口径：
     * 拆 {@code ;} 增强——头已由 playRoute 步骤 2 收进 out，此处重拆只为
     * 剥串、同值覆写无副作用；再剥 # 标记段）。选择器支不经此清理：其
     * getMarked→HkHttp.get 自会拆增强，两支入参口径由此分开。
     * 链式懒段页址已经此清理、重清为无操作。
     */
    private String cleanLazyPageUrl(String pageUrl, HkPlay out) {
        if (pageUrl == null) return "";
        String u = splitEnhancement(pageUrl, out);
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash).trim();
        return u;
    }

    /** 纯串版拆 {@code ;} 增强（不写 out、无副作用）：与 splitEnhancement 的串处理同口径，只取 url 本体。 */
    private static String stripEnhancementText(String u) {
        if (u == null) return "";
        int brace = u.indexOf('{');
        int braceEnd = u.lastIndexOf('}');
        if (brace > 0 && braceEnd > brace) {
            u = (u.substring(0, brace) + u.substring(braceEnd + 1)).trim();
        }
        int semi = u.indexOf(';');
        if (semi >= 0) u = u.substring(0, semi).trim();
        return u;
    }

    /** 懒段页址的净形（与 cleanLazyPageUrl 同口径：拆增强＋剥 # 段），纯串版；另剥 @lazyRule= 尾，自足于整串入参。 */
    private static String lazyPageClean(String pageUrl) {
        String u = stripEnhancementText(pageUrl == null ? "" : pageUrl.trim());
        int lr = u.indexOf("@lazyRule=");
        if (lr >= 0) u = u.substring(0, lr).trim();
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash).trim();
        return u;
    }

    /**
     * 懒降级产物判定（毛病1）：本段懒求值的产出（净形）与喂给本段的页址净形
     * 逐字相同＝懒函数没解析出任何东西、把剧集网页原样吐回——规则体远程取回
     * 失败/残缺时的降级形。豁免：产出或页址带 isVideo=true 标记（声明页址本身
     * 即视频）、或页址形如媒体直链（懒包裹直链加头回吐原址是合法终形）。
     */
    private static boolean isLazyDegraded(String resolved, String pageRaw) {
        if (resolved == null || pageRaw == null) return false;
        if (resolved.contains("isVideo=true") || pageRaw.contains("isVideo=true")) return false;
        String r = stripEnhancementText(resolved.trim());
        int h = r.indexOf('#');
        if (h >= 0) r = r.substring(0, h).trim();
        if (r.isEmpty()) return false;
        return r.equals(lazyPageClean(pageRaw)) && !looksLikeMediaUrl(r);
    }

    /**
     * 解析 {@code @lazyRule=}：{@code .js:}/{@code js:} 开头走 JS 独立作用域求值，
     * 否则把规则当选择器对剧集页提单个地址（{@code .js:} 值后缀由选择器内部处理）。
     * 非 JS 支走运行时 evalField（复用 selectChain；原文走 HkSelector.evalField）。
     * 求值异常按空结果收场（官 evalJS 把求值异常吞成 "error:…" 串回 LazyRuleParser
     * 走失败回调），不在半路抛横幅中止整条解析。
     */
    private void resolveLazyRule(String pageUrl, String ruleText, HkPlay out, int depth, String itemExtra, int clickPos) {
        try {
            String r = ruleText.trim();
            boolean js = false;
            if (r.startsWith(".js:")) {
                r = r.substring(4);
                js = true;
            } else if (r.startsWith("js:")) {
                r = r.substring(3);
                js = true;
            }
            String v;
            if (js) {
                // 本链点击下标以形参直传本发（每段同值）；选择器支不求值 JS、无 MY_POSITION。
                v = runtime.evalLazy(r, cleanLazyPageUrl(pageUrl, out), clickPos);
            } else {
                String html = getMarked(pageUrl);
                v = runtime.evalField(html, r, pageUrl);
            }
            if (!TextUtils.isEmpty(v)) {
                applyLazyResult(v, out, depth, pageUrl, itemExtra, clickPos);
            } else {
                SpiderDebug.log(TAG, "lazy play empty rule=%s host=%s",
                        rule == null ? "" : rule.title(), hostOf(pageUrl));
            }
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "lazy play failed rule=%s host=%s err=%s",
                    rule == null ? "" : rule.title(), hostOf(pageUrl), String.valueOf(e));
        }
    }

    private static String hostOf(String url) {
        if (url == null) return "";
        try {
            String h = java.net.URI.create(url.trim()).getHost();
            return h == null ? "" : h;
        } catch (Throwable e) {
            return "";
        }
    }

    private String evalJsPlay(String code, String pageUrl) {
        try {
            return runtime.evalLazy(code, pageUrl, -1);
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "js play failed rule=%s host=%s err=%s",
                    rule == null ? "" : rule.title(), hostOf(pageUrl), String.valueOf(e));
            return "";
        }
    }

    /**
     * 解析多线路/字幕 JSON：{@code {urls:[], names:[], headers:[], subtitle:'...'}}。
     * 三列按 8.83 PlayData 语义逐下标对齐保全在 HkPlay：urls 全收、names 空补「线路1..N」、
     * headers 元素两种形态都认（{@code "Cookie@xxx"} 字符串属同线路多对累积，
     * {@code {"Referer":"..."}} 对象即 8.83 的单线路头表）——绝不跨线路合并。
     * P1/G49：同时解析 audioUrls（音视频分离，整表）与 danmu/lyric/subtitle 附带字段。
     * <p>
     * 第 3 步第 3 件：解析与装配改走 {@link HkPlayData#toPlay(HkPlay)}（语义与旧体逐项
     * 对照一致，见 HkPlayData 类注）；本方法只剩入口与返回值。
     */
    private boolean parsePlayJson(String u, HkPlay out) {
        HkPlayData data = HkPlayData.fromJson(u);
        if (data == null) {
            SpiderDebug.log(TAG, "play json failed: unparseable");
            return false;
        }
        return data.toPlay(out);
    }

    /**
     * 冲突字符解码（官方 StringUtil.decodeConflictStr）：
     * {@code ？？}→{@code ?}、{@code ＆＆}→{@code &}、{@code ；；}→{@code ;}、{@code ，，}→{@code ,}。
     */
    private String decodeConflict(String s) {
        if (s == null) return "";
        return s.replace("？？", "?").replace("＆＆", "&").replace("；；", ";").replace("，，", ",");
    }

    /**
     * 销毁：无操作。我们用的 HkEngine 常驻运行时按规则共享，绝不许随一次
     * 路由销毁（原文 engine.destroy() 由 adopted 标记护住常驻运行时同理）。
     */
    public void destroy() {
    }

    // ================= 内部 =================

    /** 详情规则收口：最终选中的 find_rule 为空/"*" 视作无详情规则。 */
    private String effectiveDetailRule(String findRule) {
        if (findRule == null || findRule.trim().isEmpty() || "*".equals(findRule.trim())) return null;
        return findRule;
    }

    /** 规则是否没有详情解析规则（此时 V4 必空，条目 lazyRule 求值结果应直接播放）。 */
    private boolean isEmptyDetailRule() {
        if (rule == null) return true;
        String r = rule.detailFindRule();
        return r == null || r.trim().isEmpty() || "*".equals(r.trim());
    }

    /** 结果是否形如媒体直链（去 # 标记后看 http 前缀与常见媒体后缀/关键字）。 */
    private static boolean looksLikeMediaUrl(String url) {
        if (url == null) return false;
        String l = url.toLowerCase();
        int h = l.indexOf('#');
        if (h >= 0) l = l.substring(0, h);
        l = l.trim();
        if (!l.startsWith("http")) return false;
        return l.contains(".m3u8") || l.contains(".mp4") || l.contains(".flv")
                || l.contains(".ts?") || l.contains(".ts&") || l.endsWith(".ts")
                || l.endsWith(".mkv") || l.endsWith(".avi") || l.contains("mime=video")
                || l.contains("mime=audio") || l.contains(".mp3") || l.contains(".wav");
    }

    /**
     * 官方 UrlDetector.isVideoOrMusic 同款判定（静态规则部分；含 @rule=/@lazyRule= 的返回 false，
     * 因需先解析）。后缀门诊断日志（原文 suffixShape）未移植，只留判定逻辑。
     */
    public static boolean isVideoOrMusicUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String u = url.trim();
        if (u.contains("ignoreVideo=true") || u.contains("#ignoreMusic=true#")) return false;
        if (u.contains("isVideo=true") || u.contains("isMusic=true")) return true;
        String low = u.toLowerCase();
        if (low.startsWith("x5play://")) return true;
        if (u.contains("@rule=") || u.contains("@lazyRule=")) return false;
        if (low.startsWith("rtmp://") || low.startsWith("rtsp://") || u.contains("video://")) return true;
        if (low.contains(".mp4.jp") || low.contains(".mp4.png")) return false;
        return looksLikeMediaUrl(u);
    }

    public static boolean isMagnetLink(String url) {
        return isMagnetLink(url, false);
    }

    public static boolean isMagnetLink(String url, boolean ignoreCase) {
        if (url == null) return false;
        String u = ignoreCase ? url.toLowerCase() : url;
        return u.startsWith("magnet:") || u.startsWith("thunder://");
    }

    /**
     * P2P 正表单支（照官 ThunderManager.isMagnetOrTorrent 第三支）：
     * 以 ";" 切首段以 .torrent 结尾（带 ;{…} 增强段的种子址同形）。
     * 全树分发面种子支共用此一处。大小写口径同 isMagnetLink（官敏感）。
     */
    public static boolean isTorrentLink(String url) {
        return isTorrentLink(url, false);
    }

    public static boolean isTorrentLink(String url, boolean ignoreCase) {
        if (url == null) return false;
        String first = url.split(";")[0];
        return ignoreCase ? first.toLowerCase().endsWith(".torrent") : first.endsWith(".torrent");
    }

    /**
     * 磁力/种子合判正表（照官 ThunderManager.isMagnetOrTorrent 本体三支合形，
     * 大小写敏感）。
     */
    public static boolean isMagnetOrTorrent(String url) {
        return isMagnetLink(url) || isTorrentLink(url);
    }

    /**
     * 是否为动作 URL（8.83 StringUtil 动作 URL 表同款：toast://、copy://、input://、
     * confirm://、select://、download:// 并列）。
     * 这类 URL 只应走 handleActionUrl，绝不能被当成直接播放地址或进 V4。
     */
    public static boolean isActionUrl(String url) {
        if (url == null) return false;
        String l = url.trim().toLowerCase();
        return l.startsWith("toast://") || l.startsWith("copy://")
                || l.startsWith("input://") || l.startsWith("confirm://")
                || l.startsWith("select://") || l.startsWith("download://")
                || l.startsWith("hiker://empty");
    }

    /**
     * dealWithUrl 分流（官方 ArticleListFragment.dealWithUrl 顺序）：
     * video=直接播放 / pics=漫画 / x5=webview规则 / web=网页 / image=图片 / magnet=分享 / ""=未知。
     */
    public static String dealKind(String url) {
        if (url == null || url.trim().isEmpty()) return "";
        String u = url.trim();
        // 8.83 H2：@lazyRule= 优先（dealLazyRule），绝不按普通 URL 分流；
        // 只看 @rule= 前段是否含 @lazyRule=（与 clickItem 顺序一致）。
        int ar = u.indexOf("@rule=");
        String pre = ar >= 0 ? u.substring(0, ar) : u;
        if (pre.contains("@lazyRule=")) return "";
        String low = u.toLowerCase();
        if (low.startsWith("pics://")) return "pics";
        if (low.startsWith("x5://")) return "x5";
        // 支序照官序：pics 支后即磁力支 → isImage 支 → isVideoOrMusic 支
        // → 尾链 http/file 网页支。磁力判据收归正表
        // （isP2pPlayLink 函数族 scheme 四类＋isTorrentLink 种子支），种子亦归 magnet 名义。
        // 大小写口径归官分发面：官 dealWithUrl 本体 Java startsWith 大小写敏感、
        // ThunderManager.isMagnetOrTorrent 亦敏感，故本面传 ignoreCase=false；
        // 单参 isP2pPlayLink 的不敏感口径只属播放门，两口径同族双参共存。
        if (isP2pPlayLink(u, false) || isTorrentLink(u)) return "magnet";
        // 图片判据直调正表 HkUrlDetector.isImage（官 UrlDetector 唯一一张 IMAGES 表），
        // 不许再内嵌简化版。
        if (HkUrlDetector.isImage(u)) return "image";
        if (isVideoOrMusicUrl(u)) return "video";
        if (low.startsWith("http://") || low.startsWith("https://") || low.startsWith("file://")) return "web";
        return "";
    }

    /**
     * 海阔磁力／ed2k／thunder／ftp 链接一律走播放器（经 SiteApi.hkPlayerContent 的
     * Source.fetch → Thunder.fetch 迅雷转换），不维持原版、不走系统选应用。
     * 口径只认 scheme 前缀（magnet:/ed2k:/thunder:/ftp://），不要求 "//"，
     * 与 Thunder.match 的 scheme 判定、SiteApi.isThunderId、
     * VideoActivity.isThunderUrl 看齐。.torrent 不入本门（SiteApi 播放门不扩面），
     * 种子判据单列 isTorrentLink、只供分发面（dealKind/fragment）用。
     */
    public static boolean isP2pPlayLink(String url) {
        return isP2pPlayLink(url, true);
    }

    /**
     * P2P scheme 四类判据的唯一实现（函数族本体）：ignoreCase=true 为播放门口径
     * （与 VideoActivity.isThunderUrl／Thunder.match 的 scheme 小写归一看齐），
     * false 为分发面口径（官 dealWithUrl／ThunderManager 大小写敏感，dealKind 用）。
     * 两口径只许在此一处分叉，不许调用方再自写前缀判据。
     */
    public static boolean isP2pPlayLink(String url, boolean ignoreCase) {
        if (url == null) return false;
        String u = url.trim();
        if (ignoreCase) u = u.toLowerCase();
        return u.startsWith("magnet:") || u.startsWith("ed2k:") || u.startsWith("thunder:") || u.startsWith("ftp://");
    }

    private String cleanDetailUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash).trim();
        return u;
    }

    /** 带按源定向打点的取数：请求归属当前规则（UA 取规则 userAgent）。 */
    private String getMarked(String url) throws IOException {
        return HkHttp.get(url, rule == null ? null : rule.userAgent());
    }

    /** JS 规则是不是 js: 形（原文 HkSelector.isJsRule：trim 后 startsWith("js:")）。 */
    private static boolean isJsRule(String rule) {
        return rule != null && rule.trim().startsWith("js:");
    }

    /**
     * JS 详情求值（原文 fromJsDetail 的 yingshi 等价）。
     * R2：8.83 详情 JS 求值前先抓详情 HTML，getResCode()/getCode() 可取正文——
     * 我们无 setEvalResCodeBody，预抓用 runtime.fetchDocument（既取正文又备 currentDoc）。
     * 求值用 evalRuleJs + getResults（与 HkSiteAdapter.evalDetail 的 JS 支同形，
     * 含 nextToken/bootstrapListEval/restoreEvalContext 上下文）。
     * 预抓失败且求值出空时按失败抛、不冒充规则真空。
     */
    private List<HkDetailItem> fromJsDetail(String jsCode, String url, String defaultColType) throws Exception {
        if (runtime == null) throw new IllegalStateException("无可用规则运行时");
        String fetchUrl = cleanDetailUrl(url);
        boolean prefetchFailed = false;
        if (!fetchUrl.isEmpty() && (fetchUrl.startsWith("http://") || fetchUrl.startsWith("https://"))) {
            try {
                String body = runtime.fetchDocument(fetchUrl);
                if (body == null || body.isEmpty()) prefetchFailed = true;
            } catch (Throwable e) {
                // 预抓失败则详情 JS 拿不到正文、解析可能为空，此前无痕与「规则本身
                // 出空」不可区分；按本文件既有失败日志形留痕，并置标：下方求值若因此出空，
                // 按失败抛、不冒充规则真空。
                prefetchFailed = true;
                SpiderDebug.log(TAG, "detail prefetch failed rule=%s host=%s err=%s",
                        rule == null ? "" : rule.title(), hostOf(fetchUrl), String.valueOf(e));
            }
        }
        runtime.clearResults();
        runtime.setPage(1);
        String jsToken = runtime.nextToken();
        runtime.bootstrapListEval(jsToken);
        runtime.restoreEvalContext(jsToken);
        runtime.evalRuleJs(jsCode);
        List<HkItem> raw = runtime.getResults();
        List<HkDetailItem> items = new ArrayList<>();
        if (raw != null) {
            for (HkItem m : raw) {
                HkDetailItem it = new HkDetailItem();
                it.setTitle(m.getTitle());
                it.setUrl(m.getUrl());
                String pic = m.getPic();
                it.setPic(pic);
                it.setDesc(m.getDesc());
                it.setLine(m.getLine());
                String col = m.getColType();
                // 官方 dealRule：detail_col_type 作为整页默认样式，无 col_type 的条目回退到它
                if (col == null || col.isEmpty()) col = defaultColType;
                it.setColType(col);
                items.add(it);
            }
        }
        if (prefetchFailed && items.isEmpty()) {
            throw new IllegalStateException("详情正文预抓失败，JS 详情无正文可解析");
        }
        return items;
    }

}
