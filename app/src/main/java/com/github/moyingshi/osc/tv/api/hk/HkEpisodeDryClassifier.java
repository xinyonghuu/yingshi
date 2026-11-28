package com.github.moyingshi.osc.tv.api.hk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 海阔详情选集干跑分类器（第 3 步第 2 件）。
 * <p>
 * 参照被删实现 {@code b056322b} 的 HkEpisodeDryClassifier（141 行）移植：
 * {@link #classify}、{@link #isActionResult}、{@link #isPlayableResult}、
 * {@link #isJsLazy} 与原文一致；{@link #classifyLazyItem} 按我方运行时接口适配。
 * <p>
 * 干跑约定：懒函数在 dryRun 标记下求值——规则里的 fetch/网络调用在干跑期间
 * 应被跳过（见 {@link HkJsRuntime#evalLazyDry}），求值只看返回值与副作用，
 * 不产生播放。分类只写标不删条目——详情页本体渲染仍用全量 rawItems，动作条目
 * 在详情页可点、只是不进选集。
 * <p>
 * yingshi 适配（与原文不同）：
 * <ul>
 * <li>原文的干跑求值 {@code evalLazyDry} 返回 {@code DryEvalResult{error,
 * returnValue, refreshRequested, resultUrls}}；我方第 1 件的
 * {@link HkJsRuntime#evalLazyDry(String, String, int)} 只返回求值结果字符串。
 * 本类在外围补齐三件：① refresh 标记用 {@code HkRouter.detail} ①/② 分支同款
 * hadRefresh 快照口径（求值前读、求值后 consume、仅当求值前无残留才算本次新产生），
 * 不另消费调用方的标记；② resultUrls 取求值前后 {@code getResults()} 的 URL
 * 差集（干跑里 setResult 是整表替换，差集口径与原文「只取增量」同义）；
 * ③ 求值后用 {@code setResult(before)} 还原调用方的结果表——干跑的 setResult
 * 不许污染详情求值的结果。</li>
 * <li>{@code classifyLazyItem} 多一个 {@code position} 形参（条目在 rawItems 里的
 * 下标）：我方 evalLazyDry 要一个 MY_POSITION 实参，干跑按条目下标给；原文无此参、
 * 干跑 position 恒为缺省。</li>
 * <li>原文的冲突解码走 {@code HkSelector.decodeConflict}；yingshi 没有 HkSelector，
 * 本类自带同款四替换（与 {@link HkRouter} 的私有同名方法同义，不另立共享处）。</li>
 * </ul>
 * <p>
 * 残留口径（与 HkRouter.detail ①/② 同款）：hadRefresh 快照下，详情求值残留的
 * refresh 标记会遮住干跑本次的新请求（本次 refresh 误判为无，分类退到返回值口径）。
 * 要同时满足「详情求值调过 refreshPage」与「干跑条目本身也调 refreshPage」
 * 才触发；动作条目多半返回值本身就是动作串，仍会被判 ACTION。
 */
public final class HkEpisodeDryClassifier {

    public enum Verdict {
        ACTION, PLAYABLE, UNKNOWN
    }

    private HkEpisodeDryClassifier() {
    }

    /** 纯函数：按干跑证据判类（可离线单测）。 */
    public static Verdict classify(String returnValue, boolean refreshRequested, List<String> resultUrls) {
        if (refreshRequested) return Verdict.ACTION;
        String rv = returnValue == null ? "" : returnValue.trim();
        if (!rv.isEmpty() && !"undefined".equals(rv) && !"null".equals(rv)) {
            if (isActionResult(rv)) return Verdict.ACTION;
            if (isPlayableResult(rv)) return Verdict.PLAYABLE;
        }
        if (resultUrls != null) {
            boolean anyPlayable = false;
            for (String u : resultUrls) {
                if (u == null) continue;
                String t = u.trim();
                if (t.isEmpty()) continue;
                if (isActionResult(t)) return Verdict.ACTION;
                if (isPlayableResult(t)) anyPlayable = true;
            }
            if (anyPlayable) return Verdict.PLAYABLE;
        }
        return Verdict.UNKNOWN;
    }

    /** 动作/导航串正表：动作 scheme 复用 HkRouter.isActionUrl，再补内部导航与分发系。 */
    public static boolean isActionResult(String v) {
        if (v == null) return false;
        String t = v.trim();
        String low = t.toLowerCase(Locale.ROOT);
        if (HkRouter.isActionUrl(t)) return true;
        // 框架自发的 copy: 单冒号形（影视合集 66eb 库 quickSearch 配置原文
        // "copy:https://…")：与 copy:// 同语义（复制导入串），isActionUrl
        // 正表只认双斜杠形，此处补齐（账 §十一）。
        if (low.startsWith("copy:")) return true;
        // hiker:// 内部导航全形（page/search/home/empty 等）都是页面动作、不是播放地址
        if (low.startsWith("hiker://")) return true;
        return low.startsWith("javascript:")
                || low.startsWith("share://")
                || low.startsWith("web://")
                || low.startsWith("x5://")
                || low.startsWith("x5webview://")
                || low.startsWith("pics://")
                || low.startsWith("code://")
                || low.startsWith("rule://");
    }

    /** 可播串正表：与资格判定的放行口径同族（http/磁力三形）＋再解析型与视频标记。 */
    public static boolean isPlayableResult(String v) {
        if (v == null) return false;
        String t = v.trim();
        String low = t.toLowerCase(Locale.ROOT);
        if (low.contains("#isvideo=true#")) return true;
        if (t.contains("@lazyRule=")) return true;
        if (low.startsWith("magnet:") || low.startsWith("ed2k:") || low.startsWith("thunder:")) {
            return true;
        }
        if (low.startsWith("video://")) return true;
        return low.startsWith("http://") || low.startsWith("https://");
    }

    /** 条目 url 是否为可干跑的 JS 型 lazy（@lazyRule=.js:/js: 或纯 js: 条目）。 */
    public static boolean isJsLazy(String url) {
        if (url == null) return false;
        String u = url.trim();
        if (u.startsWith("js:")) return true;
        int lr = u.indexOf("@lazyRule=");
        if (lr < 0) return false;
        String body = u.substring(lr + "@lazyRule=".length()).trim();
        return body.startsWith(".js:") || body.startsWith("js:");
    }

    /**
     * 对单个条目 url 干跑分类：提取 JS lazy 函数体（与 HkRouter.evalEntryLazy
     * 同解析口径：decodeConflict＋剥 .js:/js: 前缀），在给定运行时上干跑求值。
     * 非 JS 型 lazy（选择器求值需先 fetch 页面）回 UNKNOWN——干跑不取数、判不了。
     * 干跑失败（求值抛错/运行时未初始化）同样回 UNKNOWN，不抛错。
     *
     * @param position 条目在 rawItems 里的下标，作干跑的 MY_POSITION
     */
    public static Verdict classifyLazyItem(HkJsRuntime rt, String itemUrl, String pageUrl, int position) {
        if (rt == null || itemUrl == null) return Verdict.UNKNOWN;
        String u = itemUrl.trim();
        String jsCode;
        String evalPageUrl;
        int lr = u.indexOf("@lazyRule=");
        if (lr >= 0) {
            String body = u.substring(lr + "@lazyRule=".length()).trim();
            if (body.startsWith(".js:")) {
                jsCode = decodeConflict(body.substring(4).trim());
            } else if (body.startsWith("js:")) {
                jsCode = decodeConflict(body.substring(3).trim());
            } else {
                return Verdict.UNKNOWN;
            }
            evalPageUrl = u.substring(0, lr).trim();
        } else if (u.startsWith("js:")) {
            jsCode = u.substring(3);
            evalPageUrl = pageUrl == null || pageUrl.isEmpty() ? u : pageUrl;
        } else {
            return Verdict.UNKNOWN;
        }
        if (jsCode.isEmpty()) return Verdict.UNKNOWN;
        List<HkItem> before = null;
        List<String> resultUrls = null;
        boolean refresh = false;
        String rv = "";
        try {
            HkEngine.ensureInit(rt);
            before = rt.getResults();
            boolean hadRefresh = rt.isRefreshRequested();
            rv = rt.evalLazyDry(jsCode, evalPageUrl, position);
            refresh = rt.consumeRefreshRequest() && !hadRefresh;
            resultUrls = deltaUrls(before, rt.getResults());
        } catch (Throwable e) {
            return Verdict.UNKNOWN;
        } finally {
            // 干跑的 setResult 若替换了结果表，还原调用方的表（见类注）。
            if (before != null) {
                try {
                    rt.setResult(before);
                } catch (Throwable ignored) {
                }
            }
        }
        return classify(rv, refresh, resultUrls);
    }

    /** 求值后结果表里新增的 URL（按串去重）：干跑 setResult 增量口径。 */
    private static List<String> deltaUrls(List<HkItem> before, List<HkItem> after) {
        Set<String> seen = new HashSet<>();
        if (before != null) {
            for (HkItem it : before) {
                if (it != null && it.getUrl() != null) seen.add(it.getUrl());
            }
        }
        List<String> out = new ArrayList<>();
        if (after != null) {
            for (HkItem it : after) {
                if (it == null || it.getUrl() == null) continue;
                if (seen.add(it.getUrl())) out.add(it.getUrl());
            }
        }
        return out;
    }

    /**
     * 冲突解码：全角转义还原（？？→?、＆＆→&、；；→;、，，→,）。与 HkRouter 的
     * 私有同名方法同义；yingshi 没有 HkSelector，此处自带一份。
     */
    private static String decodeConflict(String s) {
        if (s == null) return "";
        return s.replace("？？", "?").replace("＆＆", "&").replace("；；", ";").replace("，，", ",");
    }
}
