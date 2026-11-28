package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 海阔小程序规则会话，第 1 步最小核。
 * <p>
 * 等价于 APK 的混淆类 n52（1,568 行）中列表/点卡路径用到的部分：
 * URL 白名单、相对地址补齐 a()、懒解析执行器 b()、列表入口 h()。
 * pics://、novel:// 两路与 x5 播放器细节是第 2/3 步，本步只记日志；
 * webRule:// 于第 3 步第 1 件接进 {@link HkRouter#play}（web 嗅探链）。
 * <p>
 * 行为依据：REPLICATION.md §6.4 与 n52.java 反编译原文（a/b/z 已逐行核对）。
 */
public final class HkSession {

    /** n52.c：播放地址必须命中其一才放行。 */
    public static final Set<String> PLAYABLE_SCHEMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "http", "https", "rtsp", "rtmp", "rtmps", "udp", "rtp", "mms", "srt", "smb", "file", "content")));

    /** 懒解析回调（n52 的 r16 回调持有者的第 1 步等价）。 */
    public interface HkLazyCallback {
        /** 解析出可播地址：url 为地址，linesJson 为多线路 JSONArray 字符串（无多线路时 ""），lineIndex 为选中下标。 */
        void onResolved(String url, String linesJson, int lineIndex);

        /** 未解析出地址。 */
        void onEmpty();
    }

    private static final ConcurrentHashMap<String, HkSession> SESSIONS = new ConcurrentHashMap<>();

    private final HkRule rule;
    private final HkJsRuntime runtime;

    private HkSession(HkRule rule) {
        this.rule = rule;
        this.runtime = HkEngine.getOrCreateRuntime(rule);
    }

    /** n52.q(title)：按规则标题取会话。 */
    public static HkSession forRule(String title) {
        HkRule rule = HkRuleStore.load(title);
        if (rule == null) return null;
        return forRule(rule);
    }

    /** 第 2 步：按标题驱逐会话缓存（HkRuleManager.clearRuleCache 调）。 */
    public static void evictRule(String title) {
        if (TextUtils.isEmpty(title)) return;
        SESSIONS.remove(title);
    }

    public static HkSession forRule(HkRule rule) {
        if (rule == null) return null;
        HkSession session = SESSIONS.get(rule.title());
        if (session == null) {
            HkSession created = new HkSession(rule);
            session = SESSIONS.putIfAbsent(rule.title(), created);
            if (session == null) session = created;
        }
        return session;
    }

    public HkRule getRule() {
        return rule;
    }

    public HkJsRuntime getRuntime() {
        return runtime;
    }

    /**
     * n52.a()：相对地址补齐。绝对 http(s) 原样返回；其余按规则首页 URL（去 ## 后）
     * 做相对解析；解析失败返回原串。
     */
    public String parse(String input) {
        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isEmpty()) return "";
        if (trimmed.regionMatches(true, 0, "http://", 0, 7) || trimmed.regionMatches(true, 0, "https://", 0, 8)) {
            return trimmed;
        }
        String base = rule == null ? "" : rule.url();
        int hash = base.indexOf("##");
        if (hash >= 0) base = base.substring(0, hash);
        if (base.startsWith("http://") || base.startsWith("https://")) {
            try {
                return new URL(new URL(base), trimmed).toExternalForm();
            } catch (Throwable ignored) {
            }
        }
        return trimmed;
    }

    /**
     * n52.z() 的静态等价：剥 #noLoading#/#noHistory#/#noRecordHistory#/#noRecord#/
     * #noRefresh#/#noBack# 标记（最多 8 个），再去掉 ;...{...} 头参数块，最后在第一个
     * ';' 处截断，得到裸 URL。
     */
    public static String stripLazyMarkers(String input) {
        String trimmed = input == null ? "" : input.trim();
        for (int i = 0; i < 8 && trimmed.startsWith("#"); i++) {
            int end = trimmed.indexOf('#', 1);
            if (end > 1) {
                String marker = trimmed.substring(1, end);
                if (!"noLoading".equals(marker) && !"noHistory".equals(marker)
                        && !"noRecordHistory".equals(marker) && !"noRecord".equals(marker)
                        && !"noRefresh".equals(marker) && !"noBack".equals(marker)) {
                    break;
                }
                // 有效标记：跳过闭合 '#' 之后（原文 qu0.h(end, 1, str) 的语义）。
                trimmed = trimmed.substring(end + 1);
            } else {
                trimmed = trimmed.substring(1);
            }
        }
        int semi = trimmed.indexOf(';');
        int brace = semi >= 0 ? trimmed.indexOf('{', semi) : -1;
        if (semi > 0 && brace > semi && !trimmed.substring(semi, brace).contains("@lazyRule=")) {
            // 头参数块（key@value 以 && 连接）本步忽略，只去掉该块本身。
            trimmed = trimmed.substring(0, semi).concat(trimmed.substring(brace + 1)).trim();
        }
        int cut = trimmed.indexOf(';');
        return cut >= 0 ? trimmed.substring(0, cut).trim() : trimmed;
    }

    /** n52.e() 的第 1 步等价：URL 解码（原文未逐行核对，失败返回原串）。 */
    public static String unescape(String input) {
        if (input == null) return "";
        try {
            return URLDecoder.decode(input, "UTF-8");
        } catch (Throwable ignored) {
            return input;
        }
    }

    /**
     * n52.b()：懒解析执行器（第 1 步）。
     * 分流：x5Play://、{"urls":...} 多线路 JSON、webRule://、pics://、novel://、
     * video://、@lazyRule= 链（深度上限 3）、其余按普通 URL 解析。
     */
    public void lazyParse(int depth, HkLazyCallback cb, String input, String extra) {
        if (cb == null) return;
        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isEmpty()) {
            cb.onEmpty();
            return;
        }
        if (trimmed.regionMatches(true, 0, "x5Play://", 0, 9)) {
            String rest = trimmed.substring(9).trim();
            if (rest.startsWith("{") && rest.contains("urls")) {
                MultiLine lines = parseMultiLine(rest);
                if (lines != null && !lines.urls.isEmpty()) {
                    // 第 3 步第 3 件：透传 PlayData 对象形原文（不是重发的 JSONArray），
                    // 分线请求头/subtitle/danmu/lyric 才到得了 HkPlay.play 的 HkPlayData。
                    cb.onResolved(lines.urls.get(0), rest, 0);
                    return;
                }
            }
            cb.onResolved(parse(stripLazyMarkers(rest)), "", 0);
            return;
        }
        if (trimmed.startsWith("{") && trimmed.contains("\"urls\"")) {
            MultiLine lines = parseMultiLine(trimmed);
            if (lines != null && !lines.urls.isEmpty()) {
                // 同上：透传对象形原文。
                cb.onResolved(lines.urls.get(0), trimmed, 0);
                return;
            }
        }
        if (trimmed.regionMatches(true, 0, "webRule://", 0, 10)) {
            // 第 3 步第 1 件：接进 HkRouter.play（web 嗅探链）——webRule:// 是
            // 规则指定的"用 webview 嗅探取播放地址"入口；路由器内部按需调
            // HkWebkitFetcher（第 5 件才有真实现，第 1 件时 resolveWebRule 恒 false）。
            try {
                HkPlay out = new HkRouter(rule).play(trimmed);
                if (out == null || out.isEmpty()) {
                    cb.onEmpty();
                    return;
                }
                // 第 3 步第 3 件：经 HkPlayData 透传对象形（分线请求头等才保得住），
                // 单线时仍回 ""（与旧口径一致）。
                HkPlayData data = HkPlayData.fromPlay(out);
                cb.onResolved(out.getUrl(0), data.isMultiLine() ? data.toJson() : "", 0);
            } catch (Throwable e) {
                SpiderDebug.log("hk", "lazy webRule router failed: %s", e.toString());
                cb.onEmpty();
            }
            return;
        }
        if (trimmed.startsWith("pics://")) {
            SpiderDebug.log("hk", "lazy pics 未接（第 2/3 步）：%s", ruleTitle());
            cb.onEmpty();
            return;
        }
        if (trimmed.startsWith("novel://")) {
            SpiderDebug.log("hk", "lazy novel 未接（第 2/3 步）：%s", ruleTitle());
            cb.onEmpty();
            return;
        }
        if (trimmed.regionMatches(true, 0, "video://", 0, 8)) {
            String rest = stripLazyMarkers(trimmed.substring(8).trim());
            cb.onResolved(parse(rest), "", 0);
            return;
        }
        int lazyIndex = trimmed.indexOf("@lazyRule=");
        if (lazyIndex < 0) {
            cb.onResolved(parse(stripLazyMarkers(trimmed)), "", 0);
            return;
        }
        if (depth >= 3) {
            SpiderDebug.log("hk", "lazy chain too deep rule=%s", ruleTitle());
            cb.onEmpty();
            return;
        }
        String head = trimmed.substring(0, lazyIndex).trim();
        String lazyJs = unescape(trimmed.substring(lazyIndex + 10).trim());
        String headResolved = parse(stripLazyMarkers(head));
        // 第 1 步近似：把 lazyRule 部分当 JS 求值（js:/.js: 前缀），结果即播放地址；
        // 原文 x() 的完整链式语义（含二次取数）是第 2/3 步。
        String evaluated = evalLazyJs(lazyJs);
        String target = TextUtils.isEmpty(evaluated) ? headResolved : parse(stripLazyMarkers(evaluated));
        if (TextUtils.isEmpty(target)) {
            cb.onEmpty();
            return;
        }
        SpiderDebug.log("hk", "lazy chain depth=%d rule=%s host=%s", depth + 1, ruleTitle(), target);
        cb.onResolved(target, "", 0);
    }

    /** 同步解析结果（HkRouter.resolveLazyRule 的同步包装用）。 */
    public static final class Resolved {
        public final String url;
        public final String linesJson;
        public final int lineIndex;
        public final boolean empty;

        private Resolved(String url, String linesJson, int lineIndex) {
            this.url = url == null ? "" : url;
            this.linesJson = linesJson == null ? "" : linesJson;
            this.lineIndex = lineIndex;
            this.empty = TextUtils.isEmpty(this.url);
        }

        public static Resolved empty() {
            return new Resolved("", "", 0);
        }
    }

    /**
     * 同步版 lazyParse（第 3 步第 1 件）：把回调式解析包成一次返回，供
     * HkRouter.resolveLazyRule / HkDetailBridge 的同步装配用。
     * 当前 lazyParse 全分支同步回调；为防将来有异步分支，用 30 秒 latch
     * 兜底——超时按空处理，不阻塞调用线程。
     */
    public Resolved resolveSync(String url) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] box = new String[]{"", "", "0"};
        final boolean[] got = new boolean[]{false};
        lazyParse(0, new HkLazyCallback() {
            @Override
            public void onResolved(String u, String linesJson, int lineIndex) {
                box[0] = u;
                box[1] = linesJson;
                box[2] = String.valueOf(lineIndex);
                got[0] = true;
                latch.countDown();
            }

            @Override
            public void onEmpty() {
                latch.countDown();
            }
        }, url, "");
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!got[0]) return Resolved.empty();
        int idx = 0;
        try {
            idx = Integer.parseInt(box[2]);
        } catch (NumberFormatException ignored) {
        }
        return new Resolved(box[0], box[1], idx);
    }

    private String evalLazyJs(String js) {
        if (TextUtils.isEmpty(js)) return "";
        String code = js.trim();
        try {
            HkEngine.ensureInit(runtime);
            if (code.startsWith(".js:")) code = code.substring(1);
            if (code.startsWith("js:")) return runtime.evalRule(code);
            return runtime.evalJs(code, "lazyRule");
        } catch (Throwable e) {
            SpiderDebug.log("hk", "lazyRule eval failed: %s", e.toString());
            return "";
        }
    }

    private String ruleTitle() {
        return rule == null ? "" : rule.title();
    }

    /** 多线路包（n52.s() 解析 {"urls": [...], "names": [...]}）。 */
    public static class MultiLine {
        public final List<String> urls = new ArrayList<>();
        public final List<String> names = new ArrayList<>();

        /** 拼 f6 第 8 参用的 JSONArray 字符串（元素 {name, url}；精确键名待核对，见 §11）。 */
        public String toJson() {
            try {
                JSONArray arr = new JSONArray();
                for (int i = 0; i < urls.size(); i++) {
                    JSONObject o = new JSONObject();
                    o.put("name", i < names.size() ? names.get(i) : ("线路" + (i + 1)));
                    o.put("url", urls.get(i));
                    arr.put(o);
                }
                return arr.toString();
            } catch (Throwable e) {
                return "";
            }
        }
    }

    /** n52.s()：解析 {"urls": [...]} 多线路 JSON（urls 可为数组或单个字符串）。 */
    public static MultiLine parseMultiLine(String json) {
        if (TextUtils.isEmpty(json)) return null;
        try {
            JSONObject o = new JSONObject(json);
            MultiLine lines = new MultiLine();
            if (o.has("urls")) {
                Object urls = o.get("urls");
                if (urls instanceof JSONArray) {
                    JSONArray arr = (JSONArray) urls;
                    for (int i = 0; i < arr.length(); i++) lines.urls.add(arr.optString(i));
                } else {
                    lines.urls.add(o.optString("urls"));
                }
            } else if (o.has("url")) {
                lines.urls.add(o.optString("url"));
            }
            if (o.has("names")) {
                Object names = o.get("names");
                if (names instanceof JSONArray) {
                    JSONArray arr = (JSONArray) names;
                    for (int i = 0; i < arr.length(); i++) lines.names.add(arr.optString(i));
                } else {
                    lines.names.add(o.optString("names"));
                }
            }
            return lines.urls.isEmpty() ? null : lines;
        } catch (Throwable e) {
            return null;
        }
    }

    /** n52.h()：列表入口（第 1 步直接走 HkListEvalTask；extra 为页码数字时用它）。 */
    public List<HkItem> list(String url, String extra) {
        int p = 1;
        try {
            if (!TextUtils.isEmpty(extra)) p = Integer.parseInt(extra.trim());
        } catch (Throwable ignored) {
        }
        try {
            HkEngine.ensureInit(runtime);
        } catch (Throwable e) {
            SpiderDebug.log("hk", "session list init failed: %s", e.toString());
            return new ArrayList<>();
        }
        return HkListEvalTask.evalList(runtime, rule, url, p);
    }

    /** 是否命中播放白名单。 */
    public static boolean isPlayable(String url) {
        if (TextUtils.isEmpty(url)) return false;
        int colon = url.indexOf(':');
        if (colon <= 0) return false;
        return PLAYABLE_SCHEMES.contains(url.substring(0, colon).toLowerCase());
    }
}
