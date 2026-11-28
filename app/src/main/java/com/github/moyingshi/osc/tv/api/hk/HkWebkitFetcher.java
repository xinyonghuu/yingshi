package com.github.moyingshi.osc.tv.api.hk;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.moyingshi.osc.tv.App;

import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * WebView 后台抓取（8.83 WebkitFetcher/WebkitExecutor 的简化移植）。
 * 8.83 原文：WebkitFetcher.smali（1082 行）+ WebkitExecutor.smali（895 行）+ WebkitRunner.smali（603 行）
 * 8.83 语义：
 *   fetchCodeByWebView(url, options)：WebView 后台加载 url，取 JS 渲染后的页面源码；
 *     options.timeout（1000-30000ms，默认 30000ms）；同步等待 CountDownLatch；返回源码字符串。
 *   executeWebRule(url, rule, options)：WebView 里执行另一条 web 规则；同上超时机制。
 * 星辰实现：在主线程创建 WebView（WebView 必须在主线程），用 CountDownLatch 同步等待。
 * <p>
 * 移植自星辰 {@code app/src/main/java/com/fongmi/android/tv/api/hk/HkWebkitFetcher.java}（354 行），
 * 除包名与三处适配外与原文一致。yingshi 适配：(1) 星辰的切后台分发点
 * BackgroundSilence 我方无，{@link #destroyLiveOnBackground()} 改由
 * {@code App.onTrimMemory(TRIM_MEMORY_UI_HIDDEN)} 调用（见 App.java 注）；
 * (2) onReceivedError 除参照的旧签名外另覆写新签名（API 23+），只认主帧错误，
 * 子资源错误不收口；(3) headersOf 除 Map 外也认 Rhino Scriptable——JS 传来的
 * options 对象是 Scriptable 不是 Map，instanceof Map 恒 false 会把请求头
 * 整段丢掉（参照坑，见 HkJsRuntime 的 scriptableToMap 注）；(4) 星辰注里
 * 「Router 播放路改走 HkIWebViewParser.parseSync」一段不适用（yingshi 无此
 * 类），我方 webRule 路是 HkRouter.resolveWebRule → fetchWebRule → executeWebRule。
 * <p>
 * 简化版：无全桥、无 JS 注入、type 写死 webkit（无 X5）。
 * 调用方：{@link HkRouter#fetchWebRule}（webRule:// / x5Rule:// 播放路）、
 * {@link HkJsRuntime} 的 fetchCodeByWebView / executeWebRule 分支。
 */
public class HkWebkitFetcher {

    // 切后台静默：在册登记——本类的 WebView 每调现建、错误/完成路自毁，但纯超时路不毁、残件
    // 会续载。登记只为切后台时把在册的全毁掉（等待线程走既有超时/错误收口）；弱引用集，正常自毁的件不留尸。
    private static final Set<WebView> LIVE_VIEWS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static void registerLive(WebView webView) {
        if (webView != null) LIVE_VIEWS.add(webView);
    }

    /** 切后台调用（须主线程，App.onTrimMemory(TRIM_MEMORY_UI_HIDDEN) 即主线程）：
     * 在册 WebView 全 destroy 并清册。新调用只来自前台流，后台不新起，本口无需回前台恢复。 */
    public static void destroyLiveOnBackground() {
        List<WebView> snapshot;
        synchronized (LIVE_VIEWS) {
            snapshot = new ArrayList<>(LIVE_VIEWS);
            LIVE_VIEWS.clear();
        }
        for (WebView webView : snapshot) {
            try { webView.destroy(); } catch (Throwable ignored) {}
        }
    }

    private static final int DEFAULT_TIMEOUT = 30000;
    private static final int MIN_TIMEOUT = 1000;
    private static final int MAX_TIMEOUT = 30000;

    /** 8.83 fetchCodeByWebView 语义：返回 JS 渲染后的页面源码 */
    public static String fetchCodeByWebView(String url, Map<String, Object> options) {
        int timeout = getTimeout(options);
        // 8.83：options 的 checkJs（String，WebkitFetcher.smali:449 区 check-cast
        // 亲读）——页面 finish 后须轮询至该脚本求值非 null 才取码（G16）；空/缺＝无。
        // 非 null 非 String 官抛 ClassCastException、由上层 putFunction 的 catch 压成 ""（官失败形）；
        // 我方 dispatch 同样吞异常回 null，等价收口。
        final String checkJs;
        {
            Object cj = options == null ? null : options.get("checkJs");
            if (cj != null && !(cj instanceof String)) {
                throw new ClassCastException("checkJs: " + cj.getClass().getName() + " cannot be cast to String");
            }
            String s = (String) cj;
            checkJs = s == null || s.isEmpty() ? null : s;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("");
        Handler mainHandler = new Handler(Looper.getMainLooper());

        mainHandler.post(() -> {
            WebView webView = null;
            try {
                webView = new WebView(App.get());
                registerLive(webView);
                webView.getSettings().setJavaScriptEnabled(true);
                webView.getSettings().setDomStorageEnabled(true);
                webView.getSettings().setBlockNetworkImage(true);
                applyUa(webView, options);
                List<?> rules = blockRulesOf(options);
                WebView finalWv = webView;
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        if (rules != null && request != null && request.getUrl() != null
                                && blocked(request.getUrl().toString(), rules)) {
                            return new WebResourceResponse(null, null, null);
                        }
                        return super.shouldInterceptRequest(view, request);
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        if (checkJs != null) {
                            // 带 checkJs：走官轮询段（条件未成不取码），不走固定延迟
                            pollCheckJs(view, checkJs, 120, result, latch, finalWv);
                            return;
                        }
                        // 等 JS 渲染完成，延迟 1 秒后取源码
                        view.postDelayed(() -> fetchOuterHtml(view, result, latch, finalWv), 1000);
                    }

                    @Override
                    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                        latch.countDown();
                        try { finalWv.destroy(); } catch (Throwable ignored) {}
                    }

                    @Override
                    public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                        // 新签名（API 23+）：只认主帧错误收口；子资源错误不中断抓取。
                        if (request != null && request.isForMainFrame()) {
                            latch.countDown();
                            try { finalWv.destroy(); } catch (Throwable ignored) {}
                        }
                    }
                });
                Map<String, String> hdrs = headersOf(options);
                if (hdrs != null) webView.loadUrl(url, hdrs); else webView.loadUrl(url);
            } catch (Throwable t) {
                latch.countDown();
                if (webView != null) {
                    try { webView.destroy(); } catch (Throwable ignored) {}
                }
            }
        });

        try {
            latch.await(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String html = result.get();
        return html != null ? html : "";
    }

    /**
     * 8.83 WebkitFetcher.fetchCode 的 checkJs 轮询（官包 WebkitFetcher.smali 的
     * FinishPageConsumer＋fetchCode 段亲读）：包装脚本原文
     * (function(){ try { return eval('&lt;checkJs 转义&gt;')!= null? 'ok': 'no'; }
     * catch(e) { return 'no'; } })()，回调值 "ok"/"\"ok\"" 才取码定稿；
     * 否则 250ms 后重试，retry 自 120 递减、耗尽回 "" 并 destroy；异常同 ""。
     */
    private static void pollCheckJs(WebView view, String checkJs, int retry,
                                    AtomicReference<String> result, CountDownLatch latch, WebView wv) {
        String script = "(function(){ \ntry { \n return eval('" + escapeJsString(checkJs) + "')!= null? 'ok': 'no'; \n} catch(e) { \n return 'no'; \n}\n})()";
        try {
            view.evaluateJavascript(script, value -> {
                if ("ok".equals(value) || "\"ok\"".equals(value)) {
                    fetchOuterHtml(view, result, latch, wv);
                } else if (retry > 0) {
                    view.postDelayed(() -> pollCheckJs(view, checkJs, retry - 1, result, latch, wv), 250);
                } else {
                    latch.countDown();
                    try { wv.destroy(); } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            latch.countDown();
            try { wv.destroy(); } catch (Throwable ignored) {}
        }
    }

    /** 取 document.documentElement.outerHTML 定稿（fetchCodeByWebView 取码段）。 */
    private static void fetchOuterHtml(WebView view, AtomicReference<String> result, CountDownLatch latch, WebView wv) {
        try {
            view.evaluateJavascript(
                "(function(){return document.documentElement.outerHTML;})();",
                html -> {
                    if (html != null) {
                        // evaluateJavascript 返回 JSON 转义字符串，去引号
                        if (html.length() >= 2 && html.startsWith("\"") && html.endsWith("\"")) {
                            try {
                                html = new org.json.JSONTokener(html).nextString('"');
                            } catch (Exception e) {
                                html = html.substring(1, html.length() - 1);
                            }
                        }
                        result.set(html);
                    }
                    latch.countDown();
                    try { wv.destroy(); } catch (Throwable ignored) {}
                });
        } catch (Throwable t) {
            latch.countDown();
            try { wv.destroy(); } catch (Throwable ignored) {}
        }
    }

    /** 8.83 executeWebRule 语义：在 WebView 里加载 url 后执行 JS 规则，返回执行结果 */
    public static String executeWebRule(String url, String ruleJs, Map<String, Object> options) {
        int timeout = getTimeout(options);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("");
        Handler mainHandler = new Handler(Looper.getMainLooper());

        mainHandler.post(() -> {
            WebView webView = null;
            try {
                webView = new WebView(App.get());
                registerLive(webView);
                webView.getSettings().setJavaScriptEnabled(true);
                webView.getSettings().setDomStorageEnabled(true);
                applyUa(webView, options);
                List<?> rules = blockRulesOf(options);
                WebView finalWv = webView;
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        if (rules != null && request != null && request.getUrl() != null
                                && blocked(request.getUrl().toString(), rules)) {
                            return new WebResourceResponse(null, null, null);
                        }
                        return super.shouldInterceptRequest(view, request);
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        view.postDelayed(() -> {
                            try {
                                view.evaluateJavascript(ruleJs, value -> {
                                    result.set(value != null ? value : "");
                                    latch.countDown();
                                    try { finalWv.destroy(); } catch (Throwable ignored) {}
                                });
                            } catch (Throwable t) {
                                latch.countDown();
                                try { finalWv.destroy(); } catch (Throwable ignored) {}
                            }
                        }, 1000);
                    }

                    @Override
                    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                        latch.countDown();
                        try { finalWv.destroy(); } catch (Throwable ignored) {}
                    }

                    @Override
                    public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                        // 新签名（API 23+）：只认主帧错误收口；子资源错误不中断执行。
                        if (request != null && request.isForMainFrame()) {
                            latch.countDown();
                            try { finalWv.destroy(); } catch (Throwable ignored) {}
                        }
                    }
                });
                Map<String, String> hdrs = headersOf(options);
                if (hdrs != null) webView.loadUrl(url, hdrs); else webView.loadUrl(url);
            } catch (Throwable t) {
                latch.countDown();
                if (webView != null) {
                    try { webView.destroy(); } catch (Throwable ignored) {}
                }
            }
        });

        try {
            latch.await(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String r = result.get();
        return r != null ? r : "";
    }

    /**
     * 嵌入 eval('...') 单引号串的转义。照官 adblockplus
     * Utils.escapeJavaScriptString 逐字重写（官 smali 亲读）——逐字扫：'\n'→"\\n"、
     * '\r'→"\\r"（转义、不删除）、'"'/'\''/'\\'→反斜杠＋原字、其余原样；扫完再把
     * U+2028/U+2029 字符替换成 "\\u2028"/"\\u2029" 转义文本。旧形把 '\r' 直接吞掉、
     * 不转 '"'、无 U+2028/2029 段，含回车的 checkJs 会被改义。
     */
    private static String escapeJsString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '"': sb.append("\\\""); break;
                case '\'': sb.append("\\'"); break;
                case '\\': sb.append("\\\\"); break;
                default: sb.append(c);
            }
        }
        return sb.toString()
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
    }

    private static int getTimeout(Map<String, Object> options) {
        if (options != null) {
            try {
                Object t = options.get("timeout");
                if (t != null) {
                    // JS 数字到 Java 是 Double（"5000.0"），Integer.parseInt 会失败掉回默认 30 秒
                    int timeout = (int) Double.parseDouble(String.valueOf(t));
                    if (timeout < MIN_TIMEOUT) return MIN_TIMEOUT;
                    if (timeout > MAX_TIMEOUT) return MAX_TIMEOUT;
                    return timeout;
                }
            } catch (Throwable ignored) {
            }
        }
        return DEFAULT_TIMEOUT;
    }

    /** 8.83 X5Extra.blockRules：命中任一条（正则 find，编译失败退化为包含匹配）即拦截、回空响应。 */
    private static boolean blocked(String url, List<?> rules) {
        if (url == null || rules == null) return false;
        for (Object r : rules) {
            if (r == null) continue;
            String rule = String.valueOf(r);
            if (rule.isEmpty()) continue;
            try {
                if (Pattern.compile(rule).matcher(url).find()) return true;
            } catch (Throwable e) {
                if (url.contains(rule)) return true;
            }
        }
        return false;
    }

    private static List<?> blockRulesOf(Map<String, Object> options) {
        if (options == null) return null;
        Object b = options.get("blockRules");
        return b instanceof List ? (List<?>) b : null;
    }

    private static Map<String, String> headersOf(Map<String, Object> options) {
        if (options == null) return null;
        Map<String, String> out = new HashMap<>();
        Object h = options.get("headers");
        // headers 可能是 Java Map，也可能是 Rhino Scriptable（JS 对象字面量）；
        // 后者 instanceof Map 恒 false，必须经 Scriptable 读键（参照坑）。
        for (Map.Entry<String, String> e : toStringMap(h).entrySet()) {
            out.put(e.getKey(), e.getValue());
        }
        Object ua = options.get("ua");
        if (ua != null && !String.valueOf(ua).isEmpty()) out.put("User-Agent", String.valueOf(ua));
        return out.isEmpty() ? null : out;
    }

    /** Map 或 Rhino Scriptable → String 键值表（跳过函数与未定义；其余值字符串化）。 */
    private static Map<String, String> toStringMap(Object arg) {
        Map<String, String> map = new HashMap<>();
        if (arg instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) arg).entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    map.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        } else if (arg instanceof Scriptable) {
            Scriptable obj = (Scriptable) arg;
            for (Object id : obj.getIds()) {
                if (!(id instanceof String)) continue;
                Object v = ScriptableObject.getProperty(obj, (String) id);
                if (v == null || v == Scriptable.NOT_FOUND || v instanceof Undefined) continue;
                if (v instanceof org.mozilla.javascript.NativeFunction) continue;
                map.put((String) id, String.valueOf(v));
            }
        }
        return map;
    }

    private static void applyUa(WebView webView, Map<String, Object> options) {
        if (options == null) return;
        Object ua = options.get("ua");
        if (ua != null && !String.valueOf(ua).isEmpty()) {
            webView.getSettings().setUserAgentString(String.valueOf(ua));
        }
    }
}
