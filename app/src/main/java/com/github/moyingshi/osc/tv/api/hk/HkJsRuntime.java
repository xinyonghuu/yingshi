package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;
import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.utils.Notify;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ImporterTopLevel;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.commonjs.module.Require;
import org.mozilla.javascript.commonjs.module.provider.StrongCachingModuleScriptProvider;
import org.mozilla.javascript.commonjs.module.provider.UrlModuleSourceProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * 海阔小程序 JS 运行时，第 1 步最小核。
 * <p>
 * 等价于 APK 的混淆类 f42（HkJsRuntime，21,489 行）。本步只实现列表求值路径：
 * Rhino scope（解释模式 -1，ES6）+ T1() 注册 + D0() require + P()/M() 求值 +
 * parseDom 族（jsoup）+ HTTP 入口 + 存储 + setResult 回填。
 * <p>
 * 行为依据：REPLICATION.md §4（§4.2 初始化、§4.4 注册表、§4.6/§4.7 分发器、
 * §4.9 require、§4.10 求值入口、§4.11 列表求值）与 l22/x12 反编译原文。
 * <p>
 * 线程模型：每个公开求值入口都在调用线程上 Context.enter()/exit()，并对同一把
 * 锁串行（Rhino scope 不支持并发求值；APK 用单线程池 c，本步用调用方线程 + 串行锁等价）。
 */
public class HkJsRuntime {

    /** 解释模式（l22：setOptimizationLevel(-1)）。 */
    private static final int OPT_LEVEL = -1;
    /** ES6（l22：setLanguageVersion(200)）。 */
    private static final int LANG_VERSION = 200;

    /** 附录 A 的 194 个 K1 注册名（空格分隔）+ 补两个分发器名 fetch/require。 */
    private static final String ALL_API_NAMES =
            "__b64decode __b64encodeToString __hkRawRequest __hkShowUpdateLog __hkStoreGet __hkStoreSet "
                    + "_findItem _findItemsByCls _pd _pdfa _pdfh _pdfl addItemAfter addItemBefore addListener "
                    + "addWebProxyRule aesDecode aesEncode atob back backToHome base64Decode base64Encode "
                    + "batchCacheM3u8 batchExecute batchFetch bcm be bf btoa buildUrl buildWebDav cacheCode0 cacheM3u8 "
                    + "checkPrivacyPassword clearInterval clearItem clearMyVar clearPublicItem clearTimeout clearVar "
                    + "closeMe confirm copyFiles createQRCode createQRCodeToFile decodeStr decodeToString deleteCache "
                    + "deleteFile deleteItem deleteItemByCls downloadFile downloadPlugins encodeStr encodeToString ewr "
                    + "executeWebRule exist fc fcbw fetch fetchCache fetchCodeByWebView fetchCookie fetchPC fileExist "
                    + "findJavaClass fixM3u8 getAppVersion getCode getColTypes getCookie getCpuAbi getCryptoJS "
                    + "getCurrentActivity getEpubChapters getEpubContent0 getEpubMetadata getHome getHomeSub getIP "
                    + "getItem getJsLazyPlugin getJsPlugin getJuconfig getLastRules getMyType getMyVar getPageTitle "
                    + "getParam getPastes getPath getPrivacyPasswordLen getPrivateJS getPublicItem getResCode getRule "
                    + "getRuleCount getSearchMode getUaObject getUrl getVar goTo hasHomeSub hexToBase64 hexToBytes "
                    + "hideLoading initChaquopy initConfig ipping isLogin isPageReversed isShorthand isVideoOrMusic "
                    + "joinUrl justTestSign listMyVarKeys listen loadJavaClass log md5 openAppIntent parseDom "
                    + "parseDomForArray parseDomForHtml parsePaste pd pdfa pdfh png2Ts post postPC postRequest "
                    + "proxyClearM3u8 publishRule putMyVar putVar putVar2 readDir readFile refresh refreshPage "
                    + "refreshReadData refreshVideoSource refreshVideoUrl refreshWebProxyRule refreshX5Desc refreshX5WebView registerDNS "
                    + "registerTask removeWebProxyRule request require requireDownload rsaDecrypt rsaEncrypt saveFile "
                    + "saveImage searchContains setError setHomeResult setInterval setItem setJkSort setLastChapterResult "
                    + "setLastChapterRule setPageParams setPagePicUrl setPageReverse setPageTitle setPreResult "
                    + "setPublicItem setResult setSearchMode setSearchResult setStrResult setTimeout shareDirectory "
                    + "sharePaste showLoading showSelectOptions sleep startProxyServer startQRScanPage syncExecute "
                    + "toCorrectJSONString toast unRegisterTask updateItem writeFile writeHexFile xlog xpa xpath xpathArray";

    /** 真实接线的核心 API（列表求值路径 + 种子规则实际调用到的）。其余只记日志 stub。 */
    private static final Set<String> CORE_APIS = new HashSet<>(Arrays.asList(
            "request", "fetch", "fetchCache",
            "parseDom", "parseDomForArray", "parseDomForHtml",
            "pdfh", "pd", "_pdfh", "_pd",
            "setResult", "getJsPlugin", "getJsLazyPlugin", "getCryptoJS",
            "log", "xlog", "toast", "require", "getRule",
            "getVar", "putVar", "getItem", "setItem", "getMyVar", "putMyVar",
            "fileExist", "md5", "joinUrl", "buildUrl",
            "sleep", "getPageTitle", "setTitle", "getUrl"));

    /**
     * $ fallback 垫片 + method_* 适配对象（x12 bootstrap 的等价实现）。
     * Hikerurl.js 正常加载时 $ 已存在，垫片不生效；method_* 是 Java↔JS 双向桥的函数包装。
     */
    private static final String BOOTSTRAP_SHIM =
            "if (typeof $ !== 'function') {"
                    + "  var $ = function(u) { return { url: u, lazyRule: function(f) { return u; } }; };"
                    + "  $.toString = function(f) { return '' + f; };"
                    + "}"
                    + "if (typeof $ === 'function' && typeof $.toString !== 'function') { $.toString = function(f) { return '' + f; }; }"
                    + "if (typeof method_setResult !== 'function') { var method_setResult = function(items) { return setResult(items); }; }"
                    + "if (typeof method_request !== 'function') { var method_request = function() { return request.apply(null, arguments); }; }"
                    + "if (typeof method_require !== 'function') { var method_require = function(u) { return require(u); }; }"
                    + "if (typeof method_addItemAfter !== 'function') { var method_addItemAfter = function() { return null; }; }"
                    + "if (typeof method_addItemBefore !== 'function') { var method_addItemBefore = function() { return null; }; }";

    /** f42.d：当前规则。 */
    private volatile HkRule rule;
    /** f42.M/N：作用域（init 后建立）。 */
    private Scriptable scope;
    /** f42.W：是否已初始化。 */
    private volatile boolean initialized;
    /** f42.j/l：结果卡片列表（setResult 回填）。 */
    private final List<HkItem> results = new ArrayList<>();
    private final List<HkItem> results2 = new ArrayList<>();
    /** f42.D：当前页。 */
    private volatile int page = 1;
    /** f42.U：当前 URL（getUrl 返回它）。 */
    private volatile String currentUrl = "";
    /** f42.x：当前标题（setTitle 写，getPageTitle 读）。 */
    private volatile String currentTitle = "";
    /** f42.C1：当前文档（l2 取数后准备）。 */
    private volatile Document currentDoc;
    private volatile String currentDocUrl = "";
    /** 上一次 request 的 HTTP 状态码（getResCode 用）。 */
    private volatile int lastResponseCode;
    /** k22 case 1/2：作用域内临时变量。 */
    private final Map<String, Object> vars = new ConcurrentHashMap<>();
    /** putMyVar/getMyVar：第 1 步为运行时内存态（不持久化，持久化是第 2 步）。 */
    private final Map<String, Object> myVars = new ConcurrentHashMap<>();
    /** setItem/getItem：第 1 步为运行时内存态（不持久化，持久化是第 2 步）。 */
    private final Map<String, String> items = new ConcurrentHashMap<>();
    /** fetchCache：第 1 步为内存缓存（无 TTL，留待第 2 步）。 */
    private final Map<String, String> fetchCache = new ConcurrentHashMap<>();
    /** 本次求值的 token（f42.Z() 自增）。 */
    private final AtomicInteger tokenSeq = new AtomicInteger();
    private final Map<String, String> tokenContext = new ConcurrentHashMap<>();
    /** 串行求值锁。 */
    private final Object evalLock = new Object();
    /** 已记过“未实现”日志的 stub 名（防刷屏）。 */
    private final Set<String> loggedStubs = Collections.synchronizedSet(new HashSet<>());
    /** getJsPlugin/getJsLazyPlugin/getCryptoJS 的 assets 文本缓存。 */
    private String jsPluginText;
    private String jsLazyPluginText;
    private String cryptoJsText;

    public HkJsRuntime(HkRule rule) {
        this.rule = rule;
    }

    public HkRule getRuleObject() {
        return rule;
    }

    public void setRule(HkRule rule) {
        this.rule = rule;
    }

    public boolean isInitialized() {
        return initialized;
    }

    // ------------------------------------------------------------------
    // 初始化：l22 case 0（全量）/ case 1（极简）
    // ------------------------------------------------------------------

    /** C0()：全量初始化（a02.a() 调它）。 */
    public void init() throws Exception {
        synchronized (evalLock) {
            if (initialized) return;
            Context cx = enterContext();
            try {
                scope = cx.initStandardObjects();
                try {
                    ImporterTopLevel.init(cx, scope, false);
                } catch (Throwable ignored) {
                    // importClass/importPackage 不可用不阻断初始化。
                }
                registerApis(cx, scope);
                initRequire(cx, scope);
                defineConstants(cx, scope);
                evalBestEffort(cx, getCryptoJs(), "aes.js");
                evalBestEffort(cx, getJsPlugin(), "Hikerurl.js");
                // f1()/e1() 读配置：原文未逐行核对，本步跳过。
                runPreRule();
                initialized = true;
            } finally {
                Context.exit();
            }
        }
    }

    /** l22 case 1：imageEngine 极简初始化（无规则，只建最小 scope）。 */
    public void initMinimal() throws Exception {
        synchronized (evalLock) {
            if (initialized) return;
            Context cx = enterContext();
            try {
                scope = cx.initStandardObjects();
                registerApis(cx, scope);
                defineConstants(cx, scope);
                initialized = true;
            } finally {
                Context.exit();
            }
        }
    }

    /** MY_UA / MOBILE_UA / PC_UA 常量 + MY_RULE（规则 JSON 即 JS 对象字面量）。 */
    private void defineConstants(Context cx, Scriptable scope) {
        try {
            ScriptableObject.putProperty(scope, "MY_UA", HkRule.UA_MOBILE);
            ScriptableObject.putProperty(scope, "MOBILE_UA", HkRule.UA_MOBILE);
            ScriptableObject.putProperty(scope, "PC_UA", HkRule.UA_PC);
            if (rule != null) {
                cx.evaluateString(scope, "var MY_RULE = " + rule.toJson() + ";", "MY_RULE", 1, null);
            }
        } catch (Throwable e) {
            SpiderDebug.log("HkJsRuntime", "defineConstants failed: %s", e.toString());
        }
    }

    /** M()：跑 preRule（初始化时）；失败只记日志，不阻断（与 l22 的 O0(th) 保守语义一致）。 */
    private void runPreRule() {
        if (rule == null) return;
        String pre = rule.preRule();
        if (TextUtils.isEmpty(pre)) return;
        try {
            evalRule(pre);
        } catch (Throwable e) {
            SpiderDebug.log("HkJsRuntime", "preRule failed: %s", e.toString());
        }
    }

    private Context enterContext() {
        Context cx = Context.enter();
        cx.setOptimizationLevel(OPT_LEVEL);
        cx.setLanguageVersion(LANG_VERSION);
        return cx;
    }

    private void evalBestEffort(Context cx, String js, String sourceName) {
        if (TextUtils.isEmpty(js) || scope == null) return;
        try {
            cx.evaluateString(scope, js, sourceName, 1, null);
        } catch (Throwable e) {
            SpiderDebug.log("HkJsRuntime", "eval %s failed: %s", sourceName, e.toString());
        }
    }

    // ------------------------------------------------------------------
    // T1()：194 个宿主 API 注册
    // ------------------------------------------------------------------

    /** T1()：全部用 K1(name, dispatcher) 注册；核心 23+ 真实接线，其余记日志 stub。 */
    public void registerApis(Context cx, Scriptable scope) {
        for (String name : ALL_API_NAMES.split(" ")) {
            final String apiName = name;
            final boolean core = CORE_APIS.contains(apiName);
            ScriptableObject.putProperty(scope, apiName, new BaseFunction() {
                @Override
                public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                    if (!core) {
                        if (loggedStubs.add(apiName)) {
                            SpiderDebug.log("HkJsRuntime", "API %s 未实现（第 2/3 步）", apiName);
                        }
                        return null;
                    }
                    return dispatch(cx, scope, apiName, args);
                }

                @Override
                public String getFunctionName() {
                    return apiName;
                }
            });
        }
    }

    /** 核心 API 分发（b22/d22/k22 三个分发器的第 1 步等价实现）。 */
    private Object dispatch(Context cx, Scriptable scope, String name, Object[] args) {
        try {
            switch (name) {
                case "request":
                    return doRequest(args);
                case "fetch":
                    return doFetch(args, false);
                case "fetchCache":
                    return doFetch(args, true);
                case "parseDom":
                    return doParseDom(args);
                case "parseDomForArray":
                    return doParseDomForArray(cx, scope, args);
                case "parseDomForHtml":
                    return doParseDomForHtml(args);
                case "pdfh":
                case "pd":
                case "_pdfh":
                case "_pd":
                    return doPdfh(args);
                case "setResult":
                    setResultFromJs(args.length > 0 ? args[0] : null);
                    return null;
                case "getJsPlugin":
                    return getJsPlugin();
                case "getJsLazyPlugin":
                    return getJsLazyPlugin();
                case "getCryptoJS":
                    return getCryptoJs();
                case "log":
                case "xlog":
                    SpiderDebug.log("hk-js", "%s", args.length > 0 ? Context.toString(args[0]) : "");
                    return null;
                case "toast":
                    if (args.length > 0) Notify.show(Context.toString(args[0]));
                    return null;
                case "require":
                    return doRequire(args);
                case "getRule":
                    return getRuleJsObject();
                case "getVar":
                    return vars.get(argString(args, 0, ""));
                case "putVar":
                    // ConcurrentHashMap 不接受 null 值；JS 的 null/undefined 视为删键。
                    putVarValue(vars, argString(args, 0, ""), args.length > 1 ? args[1] : null);
                    return null;
                case "getItem":
                    return items.get(argString(args, 0, ""));
                case "setItem":
                    items.put(argString(args, 0, ""), argString(args, 1, ""));
                    return null;
                case "getMyVar":
                    Object my = myVars.get(argString(args, 0, ""));
                    return my == null ? (args.length > 1 ? args[1] : null) : my;
                case "putMyVar":
                    putVarValue(myVars, argString(args, 0, ""), args.length > 1 ? args[1] : null);
                    return null;
                case "fileExist":
                    return fileExists(argString(args, 0, ""));
                case "md5":
                    return md5(argString(args, 0, ""));
                case "joinUrl":
                case "buildUrl":
                    return joinUrl(argString(args, 0, ""), argString(args, 1, ""));
                case "sleep": {
                    long ms = argLong(args, 0, 0);
                    if (ms > 10000) ms = 10000; // d22 case 17：上限 10 秒。
                    if (ms > 0) Thread.sleep(ms);
                    return null;
                }
                case "getPageTitle":
                    return currentTitle;
                case "setTitle":
                    currentTitle = argString(args, 0, "");
                    return null;
                case "getUrl":
                    return currentUrl;
                default:
                    return null;
            }
        } catch (Throwable e) {
            SpiderDebug.log("HkJsRuntime", "API %s failed: %s", name, e.toString());
            return null;
        }
    }

    /** putVar/putMyVar 的存值：null/undefined 视为删键（ConcurrentHashMap 不接受 null）。 */
    private static void putVarValue(Map<String, Object> map, String key, Object value) {
        if (value == null || value == Scriptable.NOT_FOUND || value instanceof Undefined) {
            map.remove(key);
        } else {
            map.put(key, value);
        }
    }

    // ------------------------------------------------------------------
    // D0()：CommonJS require
    // ------------------------------------------------------------------

    /**
     * D0()：Rhino 自带的 CommonJS Require + StrongCachingModuleScriptProvider。
     * 模块根 = file://&lt;规则目录&gt;/libs/（f0() 内置根未确认，本步不接）。
     * 注册为 "requirejs"（与 APK 一致；规则直接调的 "require" 另走 b22 code 12 分发）。
     */
    public void initRequire(Context cx, Scriptable scope) {
        try {
            String title = rule == null ? "" : rule.title();
            List<URI> roots = new ArrayList<>();
            File libs = new File(HkRuleStore.rulesDir(), "libs");
            if (!libs.exists()) libs.mkdirs();
            roots.add(libs.toURI());
            Require require = new Require(cx, scope,
                    new StrongCachingModuleScriptProvider(new UrlModuleSourceProvider(roots, null)),
                    null, null, false);
            ScriptableObject.putProperty(scope, "requirejs", require);
            if (!TextUtils.isEmpty(title)) {
                SpiderDebug.log("HkJsRuntime", "require ready title=%s libs=%s", title, libs.getAbsolutePath());
            }
        } catch (Throwable e) {
            SpiderDebug.log("HkJsRuntime", "initRequire failed: %s", e.toString());
        }
    }

    // ------------------------------------------------------------------
    // 求值入口：P() / M() / l2() / t2() / j0() / Z()
    // ------------------------------------------------------------------

    /** P()：通用 JS 求值入口（a02.b 的 .js: 求值调它），返回字符串结果。 */
    public String evalJs(String js, String baseUrl) {
        if (TextUtils.isEmpty(js)) return "";
        synchronized (evalLock) {
            ensureScope();
            if (scope == null) return "";
            Context cx = enterContext();
            try {
                Object result = cx.evaluateString(scope, js, baseUrl == null ? "eval" : baseUrl, 1, null);
                if (result == null || result instanceof Undefined) return "";
                return Context.toString(result);
            } catch (Throwable e) {
                SpiderDebug.log("HkJsRuntime", "evalJs failed: %s", e.toString());
                throw new RuntimeException(e);
            } finally {
                Context.exit();
            }
        }
    }

    /** M()：跑 preRule / .js: 代码用。剥 "js:" 前缀后求值。 */
    public String evalRule(String js) {
        if (js == null) return "";
        String code = js.trim();
        if (code.startsWith("js:")) code = code.substring(3);
        return evalJs(code, "rule");
    }

    /** t2()：把规则 JS 文本 eval 进作用域（列表/搜索求值用）。先剥 "js:" 前缀。 */
    public void evalRuleJs(String js) {
        if (js == null) return;
        String code = js.trim();
        if (code.startsWith("js:")) code = code.substring(3);
        evalJs(code, "rule");
    }

    /** l2(url)：取数并准备文档（x12 列表求值前调）。hiker://empty 不取数，给空文档。 */
    public String fetchDocument(String url) {
        synchronized (evalLock) {
            currentUrl = url == null ? "" : url;
            if (TextUtils.isEmpty(url) || url.startsWith("hiker://empty")) {
                currentDoc = Jsoup.parse("", url == null ? "" : url);
                currentDocUrl = currentUrl;
                return "";
            }
            if (url.startsWith("http://") || url.startsWith("https://")) {
                try {
                    String html = HkHttp.get(url, rule == null ? null : rule.userAgent());
                    currentDoc = Jsoup.parse(html == null ? "" : html, url);
                    currentDocUrl = url;
                    return html == null ? "" : html;
                } catch (Throwable e) {
                    SpiderDebug.log("HkJsRuntime", "l2 fetch failed %s: %s", url, e.toString());
                    currentDoc = Jsoup.parse("", url);
                    currentDocUrl = url;
                    return "";
                }
            }
            currentDoc = Jsoup.parse("", url);
            currentDocUrl = url;
            return "";
        }
    }

    /** j0(token)：按 token 恢复本次求值的上下文（h0/i0 map 的第 1 步等价：只记 token）。 */
    public void restoreEvalContext(String token) {
        if (token == null) return;
        tokenContext.put(token, rule == null ? "" : rule.title());
        synchronized (evalLock) {
            ensureScope();
            if (scope == null) return;
            Context cx = enterContext();
            try {
                cx.evaluateString(scope, "var __hkToken = '" + token + "';", "__hkToken", 1, null);
            } catch (Throwable ignored) {
            } finally {
                Context.exit();
            }
        }
    }

    /** Z()：生成本次求值的 token（a0 自增）。 */
    public String nextToken() {
        return "hk" + tokenSeq.incrementAndGet();
    }

    /**
     * x12 bootstrap：依次 eval getJsPlugin() + getJsLazyPlugin() + $ fallback 垫片 +
     * method_* 适配对象 + j0(token)。全部 best-effort，单项失败不阻断列表求值。
     */
    public void bootstrapListEval(String token) {
        synchronized (evalLock) {
            ensureScope();
            if (scope == null) return;
            Context cx = enterContext();
            try {
                evalBestEffort(cx, getJsPlugin(), "Hikerurl.js");
                evalBestEffort(cx, getJsLazyPlugin(), "plugin.js");
                evalBestEffort(cx, BOOTSTRAP_SHIM, "hk-shim");
                if (token != null) {
                    evalBestEffort(cx, "var __hkToken = '" + token + "';", "__hkToken");
                }
            } finally {
                Context.exit();
            }
        }
    }

    private void ensureScope() {
        if (scope == null && !initialized) {
            try {
                init();
            } catch (Throwable e) {
                SpiderDebug.log("HkJsRuntime", "lazy init failed: %s", e.toString());
            }
        }
    }

    // ------------------------------------------------------------------
    // HTTP 执行入口：m() / V() / R()（b22 code 3/4、5、0）
    // ------------------------------------------------------------------

    /**
     * m()：主请求执行器。request(url[, options])，options 为 JS 对象
     * {method, headers, body, timeout}；返回响应体字符串，失败记日志返回 ""。
     */
    private Object doRequest(Object[] args) {
        String url = argString(args, 0, "");
        if (TextUtils.isEmpty(url)) return "";
        String method = "GET";
        Map<String, String> headers = new HashMap<>();
        String body = null;
        long timeoutMs = 0;
        if (args.length > 1 && args[1] instanceof Scriptable) {
            Scriptable opts = (Scriptable) args[1];
            method = argJsString(opts, "method", "GET").toUpperCase();
            body = argJsString(opts, "body", null);
            timeoutMs = argJsLong(opts, "timeout", 0);
            Object h = ScriptableObject.getProperty(opts, "headers");
            if (h instanceof Scriptable) headers = argMap((Scriptable) h);
        }
        try {
            Request.Builder builder = new Request.Builder().url(url);
            for (Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
            if (!headers.containsKey("User-Agent")) {
                builder.header("User-Agent", rule == null ? HkRule.UA_MOBILE : rule.userAgent());
            }
            if ("GET".equals(method)) {
                builder.get();
            } else if (body == null || body.isEmpty()) {
                builder.method(method, RequestBody.create("", (okhttp3.MediaType) null));
            } else {
                builder.method(method, RequestBody.create(body, (okhttp3.MediaType) null));
            }
            OkHttpClient c = timeoutMs > 0 ? HkHttp.clientWithTimeout(timeoutMs) : HkHttp.client();
            okhttp3.Response response = c.newCall(builder.build()).execute();
            try {
                lastResponseCode = response.code();
                if (!response.isSuccessful()) return "";
                okhttp3.ResponseBody responseBody = response.body();
                return responseBody == null ? "" : responseBody.string();
            } finally {
                response.close();
            }
        } catch (Throwable e) {
            SpiderDebug.log("HkJsRuntime", "request %s failed: %s", url, e.toString());
            return "";
        }
    }

    /** V() / R()：字符串取数；fetchCache 多一层内存缓存。 */
    private Object doFetch(Object[] args, boolean useCache) {
        String url = argString(args, 0, "");
        if (TextUtils.isEmpty(url)) return "";
        if (useCache) {
            String cached = fetchCache.get(url);
            if (cached != null) return cached;
        }
        String result = readInternalUrl(url);
        if (result == null) {
            try {
                result = HkHttp.get(url, rule == null ? null : rule.userAgent());
            } catch (Throwable e) {
                SpiderDebug.log("HkJsRuntime", "fetch %s failed: %s", url, e.toString());
                return "";
            }
        }
        if (result == null) result = "";
        if (useCache) fetchCache.put(url, result);
        return result;
    }

    /**
     * T()：hiker:// / file:// 内置协议取数。不能处理的内置协议返回 null（走 HTTP），
     * 明确不支持的（hiker://page/、hiker://home@）返回 "" 并记日志（第 2/3 步再接）。
     */
    private String readInternalUrl(String url) {
        if (url.startsWith("hiker://empty")) return "";
        if (url.startsWith("hiker://files/")) {
            File f = new File(App.get().getFilesDir(), url.substring("hiker://files/".length()));
            return readFileText(f);
        }
        if (url.startsWith("hiker://assets/")) {
            return readAssetText(url.substring("hiker://assets/".length()));
        }
        if (url.startsWith("hiker://page/") || url.startsWith("hiker://home@") || url.startsWith("hiker://search")) {
            SpiderDebug.log("HkJsRuntime", "内置协议未接（第 2/3 步）：%s", url);
            return "";
        }
        if (url.startsWith("file://")) {
            return readFileText(new File(url.substring("file://".length())));
        }
        return null;
    }

    private String readFileText(File f) {
        if (f == null || !f.exists()) return "";
        try (InputStream in = new java.io.FileInputStream(f)) {
            return readStream(in);
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * b22 code 12：require / 动态加载 JS（第 1 步简化分流）。
     * hikerPop 走内置实现（第 2 步的 HkHikerPopBridge），本步记日志返回 ""；
     * hiker://files/libs/ 与 hiker://assets/ 读文本；http(s) 取数；其余读文件。
     */
    private Object doRequire(Object[] args) {
        String url = argString(args, 0, "");
        if (TextUtils.isEmpty(url)) return "";
        int q = url.indexOf('?');
        if (q >= 0) url = url.substring(0, q);
        int hash = url.indexOf('#');
        if (hash >= 0) url = url.substring(0, hash);
        if (url.endsWith("hikerPop.js") || "hikerPop.js".equals(url)) {
            SpiderDebug.log("HkJsRuntime", "hikerPop -> builtin（第 2 步接宿主桥）：%s", url);
            return "";
        }
        if (url.startsWith("hiker://files/libs/") || url.startsWith("hiker://assets/")) {
            String text = readInternalUrl(url);
            return text == null ? "" : text;
        }
        if (url.startsWith("hiker://page/")) {
            SpiderDebug.log("HkJsRuntime", "require hiker://page 未接（第 2/3 步）：%s", url);
            return "";
        }
        if (url.startsWith("hiker://") || url.startsWith("file://")) {
            String text = readInternalUrl(url);
            return text == null ? "" : text;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            try {
                String text = HkHttp.get(url, rule == null ? null : rule.userAgent());
                return text == null ? "" : text;
            } catch (Throwable e) {
                SpiderDebug.log("HkJsRuntime", "require %s failed: %s", url, e.toString());
                return "";
            }
        }
        File f = resolveFile(url);
        return f == null ? "" : readFileText(f);
    }

    // ------------------------------------------------------------------
    // 解析：parseDom 族 + pdfh 族（jsoup，第 1 步近似实现）
    // ------------------------------------------------------------------

    /** 选择器链结果：匹配到的元素 + 末尾提取后缀（text/html/outerHtml/@attr）。 */
    private static class ChainResult {
        Elements elements;
        String extraction;
    }

    /**
     * 按 "&&" 拆选择器链：前面的段是 CSS 选择器（支持 jsoup 的 :eq()），
     * 末段是提取后缀时停下。完整 Hiker 选择器语法（§11）是第 2/3 步工作。
     */
    private ChainResult selectChain(Document doc, String rule) {
        ChainResult result = new ChainResult();
        if (doc == null || TextUtils.isEmpty(rule)) {
            result.elements = new Elements();
            return result;
        }
        Elements current = null;
        String extraction = null;
        for (String raw : rule.split("&&")) {
            String part = raw.trim();
            if (part.isEmpty()) continue;
            if ("text".equals(part) || "html".equals(part) || "outerHtml".equals(part) || part.startsWith("@")) {
                extraction = part;
                break;
            }
            try {
                current = current == null ? doc.select(part) : current.select(part);
            } catch (Throwable e) {
                SpiderDebug.log("HkJsRuntime", "selector failed [%s]: %s", part, e.toString());
                current = new Elements();
                break;
            }
        }
        result.elements = current == null ? new Elements() : current;
        result.extraction = extraction;
        return result;
    }

    private String extractFirst(ChainResult chain) {
        if (chain.elements == null || chain.elements.isEmpty()) return "";
        Element first = chain.elements.first();
        if (first == null) return "";
        String extraction = chain.extraction;
        if (extraction == null) return first.text();
        if ("text".equals(extraction)) return first.text();
        if ("html".equals(extraction)) return first.html();
        if ("outerHtml".equals(extraction)) return first.outerHtml();
        if (extraction.startsWith("@")) return first.attr(extraction.substring(1));
        return first.text();
    }

    /** 取文档：http(s) 取数后解析；空或 hiker://empty 用当前文档（l2 已准备）。 */
    private Document docFor(String url) {
        if (!TextUtils.isEmpty(url) && (url.startsWith("http://") || url.startsWith("https://"))) {
            fetchDocument(url);
        }
        if (currentDoc == null) {
            currentDoc = Jsoup.parse("", url == null ? "" : url);
            currentDocUrl = url == null ? "" : url;
        }
        return currentDoc;
    }

    /** parseDom(url, rule[, extra])：单值（h.f）。 */
    private Object doParseDom(Object[] args) {
        String url = argString(args, 0, "");
        String rule = argString(args, 1, "");
        if (rule.trim().startsWith("js:")) {
            SpiderDebug.log("HkJsRuntime", "parseDom 收到 js: 规则，本步不支持：%s", rule);
            return "";
        }
        return extractFirst(selectChain(docFor(url), rule));
    }

    /**
     * parseDomForArray(url, rule[, ...])：列表（h.d/h.e）。第 1 步按启发式拼卡片。
     * 返回 Rhino NativeArray（元素为 HkItem Java 对象；setResult 能直接收）。
     */
    private Object doParseDomForArray(Context cx, Scriptable scope, Object[] args) {
        List<HkItem> items = selectItems(argString(args, 0, ""), argString(args, 1, ""));
        return cx.newArray(scope, items.toArray());
    }

    /** Java 侧的 parseDomForArray：列表求值（选择器规则）直接用，返回卡片列表。 */
    public List<HkItem> parseDomForArrayJava(String url, String rule) {
        return selectItems(url, rule);
    }

    /** 选择器链取卡片列表（parseDomForArray 的共享实现；第 1 步启发式拼卡片）。 */
    private List<HkItem> selectItems(String url, String rule) {
        ChainResult chain = selectChain(docFor(url), rule);
        List<HkItem> items = new ArrayList<>();
        if (chain.elements != null) {
            for (Element el : chain.elements) {
                Element link = el.select("a").first();
                Element img = el.select("img").first();
                String title = link != null ? link.text() : el.text();
                String href = link != null ? link.absUrl("href") : el.absUrl("href");
                String pic = img != null ? img.absUrl("src") : "";
                items.add(new HkItem(title.trim(), href, pic, ""));
            }
        }
        return items;
    }

    /** parseDomForHtml(url, rule)：首个匹配元素的 outerHtml。 */
    private Object doParseDomForHtml(Object[] args) {
        String url = argString(args, 0, "");
        String rule = argString(args, 1, "");
        ChainResult chain = selectChain(docFor(url), rule);
        if (chain.elements == null || chain.elements.isEmpty()) return "";
        Element first = chain.elements.first();
        return first == null ? "" : first.outerHtml();
    }

    /** pdfh/pd/_pdfh/_pd(html, rule)：在给定 HTML 上做选择器提取。 */
    private Object doPdfh(Object[] args) {
        String html = argString(args, 0, "");
        String rule = argString(args, 1, "");
        Document doc = Jsoup.parse(html == null ? "" : html);
        return extractFirst(selectChain(doc, rule));
    }

    // ------------------------------------------------------------------
    // setResult 回填与结果存取
    // ------------------------------------------------------------------

    /** method_setResult：JS 的 setResult(d) 把卡片数组写回 f42.j。 */
    public void setResultFromJs(Object value) {
        List<HkItem> items = new ArrayList<>();
        if (value instanceof NativeArray) {
            NativeArray arr = (NativeArray) value;
            for (int i = 0; i < arr.getLength(); i++) {
                Object el = arr.get(i, arr);
                if (el instanceof HkItem) {
                    items.add((HkItem) el);
                } else if (el instanceof Scriptable) {
                    HkItem item = HkItem.fromJs((Scriptable) el);
                    if (item != null) items.add(item);
                }
            }
        } else if (value instanceof HkItem) {
            items.add((HkItem) value);
        } else if (value instanceof Scriptable) {
            HkItem item = HkItem.fromJs((Scriptable) value);
            if (item != null) items.add(item);
        }
        synchronized (evalLock) {
            results.clear();
            results.addAll(items);
        }
    }

    public void setResult(List<HkItem> items) {
        synchronized (evalLock) {
            results.clear();
            if (items != null) results.addAll(items);
        }
    }

    public List<HkItem> getResults() {
        synchronized (evalLock) {
            return new ArrayList<>(results);
        }
    }

    public List<HkItem> getResults2() {
        synchronized (evalLock) {
            return new ArrayList<>(results2);
        }
    }

    public void clearResults() {
        synchronized (evalLock) {
            results.clear();
            results2.clear();
        }
    }

    public void setPage(int page) {
        this.page = Math.max(1, page);
    }

    public int getPage() {
        return page;
    }

    public Document getCurrentDocument() {
        return currentDoc;
    }

    public String getCurrentDocUrl() {
        return currentDocUrl;
    }

    public int getLastResponseCode() {
        return lastResponseCode;
    }

    // ------------------------------------------------------------------
    // assets 文本：getJsPlugin / getJsLazyPlugin / getCryptoJS
    // ------------------------------------------------------------------

    public String getJsPlugin() {
        if (jsPluginText == null) jsPluginText = readAssetText("Hikerurl.js");
        return jsPluginText == null ? "" : jsPluginText;
    }

    public String getJsLazyPlugin() {
        if (jsLazyPluginText == null) jsLazyPluginText = readAssetText("plugin.js");
        return jsLazyPluginText == null ? "" : jsLazyPluginText;
    }

    public String getCryptoJs() {
        if (cryptoJsText == null) cryptoJsText = readAssetText("aes.js");
        return cryptoJsText == null ? "" : cryptoJsText;
    }

    public String readAssetText(String name) {
        try (InputStream in = App.get().getAssets().open(name)) {
            return readStream(in);
        } catch (Throwable e) {
            SpiderDebug.log("HkJsRuntime", "asset %s missing: %s", name, e.toString());
            return "";
        }
    }

    private String readStream(InputStream in) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Throwable e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // 文件路径解析 Y1() / fileExist / md5 / joinUrl
    // ------------------------------------------------------------------

    /**
     * Y1(path)：规则相对路径 → 绝对路径。hiker://files/ → 应用 filesDir；
     * hiker://assets/ 不对应文件（返回 null）；绝对路径原样；其余当规则目录相对路径。
     */
    public File resolveFile(String path) {
        if (TextUtils.isEmpty(path)) return null;
        String p = path.trim();
        if (p.startsWith("hiker://files/")) {
            return new File(App.get().getFilesDir(), p.substring("hiker://files/".length()));
        }
        if (p.startsWith("hiker://assets/")) return null;
        if (p.startsWith("file://")) p = p.substring("file://".length());
        File f = new File(p);
        if (f.isAbsolute()) return f;
        return new File(HkRuleStore.rulesDir(), p);
    }

    /** b22 code 19（简版）：文件存在，或 assets 里有同名资源。 */
    public boolean fileExists(String path) {
        if (TextUtils.isEmpty(path)) return false;
        File f = resolveFile(path);
        if (f != null && f.exists()) return true;
        String probe = path.trim();
        if (probe.startsWith("hiker://assets/")) probe = probe.substring("hiker://assets/".length());
        try (InputStream in = App.get().getAssets().open(probe)) {
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public String md5(String input) {
        if (input == null) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    /** d22 code 3：joinUrl（URI resolve；hiker:// 等非标准 scheme 用字符串回退）。 */
    public String joinUrl(String base, String rel) {
        if (TextUtils.isEmpty(rel)) return base == null ? "" : base;
        if (rel.startsWith("http://") || rel.startsWith("https://") || rel.startsWith("hiker://")) return rel;
        if (TextUtils.isEmpty(base)) return rel;
        try {
            return new java.net.URL(new java.net.URL(base), rel).toExternalForm();
        } catch (Throwable ignored) {
        }
        int slash = base.lastIndexOf('/');
        if (slash >= 0) return base.substring(0, slash + 1) + rel;
        return base + "/" + rel;
    }

    /** getRule()：作用域里的 MY_RULE 对象（init 时由规则 JSON 定义）。 */
    public Object getRuleJsObject() {
        if (scope == null) return null;
        Object v = ScriptableObject.getProperty(scope, "MY_RULE");
        if (v == Scriptable.NOT_FOUND || v instanceof Undefined) return null;
        return v;
    }

    // ------------------------------------------------------------------
    // JS 参数转换小工具
    // ------------------------------------------------------------------

    private String argString(Object[] args, int i, String def) {
        if (args == null || i >= args.length) return def;
        Object v = args[i];
        if (v == null || v == Scriptable.NOT_FOUND || v instanceof Undefined) return def;
        return Context.toString(v);
    }

    private long argLong(Object[] args, int i, long def) {
        if (args == null || i >= args.length) return def;
        Object v = args[i];
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(Context.toString(v));
        } catch (Throwable ignored) {
            return def;
        }
    }

    private String argJsString(Scriptable obj, String name, String def) {
        Object v = ScriptableObject.getProperty(obj, name);
        if (v == null || v == Scriptable.NOT_FOUND || v instanceof Undefined) return def;
        return Context.toString(v);
    }

    private long argJsLong(Scriptable obj, String name, long def) {
        Object v = ScriptableObject.getProperty(obj, name);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v == null || v == Scriptable.NOT_FOUND || v instanceof Undefined) return def;
        try {
            return Long.parseLong(Context.toString(v));
        } catch (Throwable ignored) {
            return def;
        }
    }

    private Map<String, String> argMap(Scriptable obj) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Object id : obj.getIds()) {
            if (!(id instanceof String)) continue;
            Object v = ScriptableObject.getProperty(obj, (String) id);
            if (v == null || v == Scriptable.NOT_FOUND || v instanceof Undefined) continue;
            if (v instanceof org.mozilla.javascript.NativeFunction) continue;
            map.put((String) id, Context.toString(v));
        }
        return map;
    }
}
