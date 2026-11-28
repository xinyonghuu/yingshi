package com.github.moyingshi.osc.tv.api.hk;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;
import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.theme.WebHtvAlertDialogBuilder;
import com.github.moyingshi.osc.tv.utils.Notify;
import com.github.moyingshi.osc.tv.utils.Task;
import com.google.gson.Gson;

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
import java.lang.ref.WeakReference;
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
            "getPublicItem", "setPublicItem", "clearPublicItem",
            "clearItem", "deleteItem", "clearMyVar", "listMyVarKeys",
            "refresh", "refreshPage",
            "fileExist", "md5", "joinUrl", "buildUrl",
            "sleep", "getPageTitle", "setTitle", "getUrl",
            "showSelectOptions", "__hkStoreGet", "__hkStoreSet");

    /**
     * $ fallback 垫片 + method_* 适配对象（x12 bootstrap 的等价实现）。
     * Hikerurl.js 正常加载时 $ 已存在，垫片不生效；method_* 是 Java↔JS 双向桥的函数包装。
     * storage0 兜底：plugin.js 加载失败时才定义（与 plugin.js 281–347 行同语义，ES5 写法）；
     * plugin.js 正常加载时 storage0 已存在，守卫跳过。
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
                    + "if (typeof method_addItemBefore !== 'function') { var method_addItemBefore = function() { return null; }; }"
                    + "if (typeof storage0 === 'undefined') {"
                    + "  var __hkToJsonObj = function(a) {"
                    + "    if (!a) { return a; }"
                    + "    if (typeof a === 'string' && a.charAt(0) === '{' && a.charAt(a.length - 1) === '}') { try { return JSON.parse(a); } catch (e) {} }"
                    + "    if (typeof a === 'string' && a.charAt(0) === '[' && a.charAt(a.length - 1) === ']') { try { return JSON.parse(a); } catch (e) {} }"
                    + "    return a;"
                    + "  };"
                    + "  var __hkToJsonStr = function(obj) {"
                    + "    if (obj == null) { obj = ''; }"
                    + "    if (typeof obj !== 'string') { obj = JSON.stringify(obj); }"
                    + "    return obj;"
                    + "  };"
                    + "  var storage0 = {"
                    + "    getItem: function(key, defaultVal) { return __hkToJsonObj(getItem(key, __hkToJsonStr(defaultVal))); },"
                    + "    setItem: function(key, obj) { setItem(key, __hkToJsonStr(obj)); },"
                    + "    getPublicItem: function(key, defaultVal) { return __hkToJsonObj(getPublicItem(key, __hkToJsonStr(defaultVal))); },"
                    + "    setPublicItem: function(key, obj) { setPublicItem(key, __hkToJsonStr(obj)); },"
                    + "    getVar: function(key, defaultVal) { return __hkToJsonObj(getVar(key, __hkToJsonStr(defaultVal))); },"
                    + "    putVar: function(key, obj) { putVar(key, __hkToJsonStr(obj)); },"
                    + "    getMyVar: function(key, defaultVal) { return __hkToJsonObj(getMyVar(key, __hkToJsonStr(defaultVal))); },"
                    + "    putMyVar: function(key, obj) { putMyVar(key, __hkToJsonStr(obj)); }"
                    + "  };"
                    + "}";

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
    /** k22 case 1/2：作用域内临时变量（§4.12：不持久化，仍是每运行时一份）。 */
    private final Map<String, Object> vars = new ConcurrentHashMap<>();
    /**
     * putMyVar/getMyVar：第 2 步起持久化。内存表是 {@link HkKvStore} 的共享 static
     * map（构造时/首次访问时从磁盘读进；键已按 plugin.js §2.2 加
     * {@code "<标题>@"} 前缀隔离）。内存里留活对象（JS 值原样），落盘时另取字符串形。
     */
    private final Map<String, Object> myVars;
    /** setItem/getItem：第 2 步起持久化（内存表与落盘见 {@link HkKvStore}，全局键）。 */
    private final Map<String, String> items;
    /** setPublicItem/getPublicItem：第 2 步起持久化（同上，全局键）。 */
    private final Map<String, String> publicItems;
    /**
     * refreshPage/refresh 监听（第 2 步 extras 第 6 件）：按运行时实例挂一个，
     * 由列表 Fragment 设置、销毁时清除；dispatch 里收到刷新调用时调它，
     * 监听自己负责切回主线程重跑列表。未设置时刷新调用是空操作，不抛错。
     */
    public interface RefreshListener {
        void onRefreshPage();
    }

    private volatile RefreshListener refreshListener;
    /**
     * refreshPage/refresh 曾被调用的粘性标记（第 3 步第 1 件）：dispatch 里收到
     * refreshPage 时置位，HkRouter 的分类切换检测（evalTab / 详情预解析）用它
     * 判断求值期间是否发生了刷新请求；读完后由消费方用 consumeRefreshRequest()
     * 取走并清零。监听仍按原样调用，本标记只多记一笔，不改变原有行为。
     */
    private volatile boolean refreshRequested;
    /**
     * __hkStoreGet/__hkStoreSet：跨作用域对象仓库（REPLICATION.md §4.12，APK 的 i0 map
     * 的第 2 步等价）。进程内内存态，不持久化；值可能是 NativeFunction 闭包（含 hikerPop
     * 回调），原样存放；key 用 String.valueOf 归一化（规格书的 f42.h(obj, false)
     * 归一化细节未核对，见任务文档 §5）。
     */
    private final Map<String, Object> hkStore = new ConcurrentHashMap<>();
    /**
     * 当前前台 Activity（showSelectOptions 弹窗用）。静态弱引用，各运行时实例共用一个
     * 持有器；HkListActivity / HkSearchActivity 在 onResume 登记、onPause 清除。
     * 弱引用 + 仅登记前台 Activity，不持有已销毁的 Activity，不漏 Activity。
     */
    private static WeakReference<Activity> currentActivityRef;
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
        // KV 三张表取 HkKvStore 的共享内存副本（首次访问时从磁盘读进）；
        // vars（临时变量）仍是每运行时一份，不进持久化。
        this.myVars = HkKvStore.myVars();
        this.items = HkKvStore.items();
        this.publicItems = HkKvStore.publicItems();
    }

    /** 第 2 步 extras 第 6 件：挂/换 refreshPage 监听；传 null 即清除。 */
    public void setRefreshListener(RefreshListener listener) {
        this.refreshListener = listener;
    }

    /** 取当前 refreshPage 监听（Fragment 销毁时只清自己的那一个用）。 */
    public RefreshListener getRefreshListener() {
        return refreshListener;
    }

    /** 本次求值周期内是否收到过 refreshPage/refresh 调用（粘性标记）。 */
    public boolean isRefreshRequested() {
        return refreshRequested;
    }

    /** 取走并清零 refreshPage 粘性标记（返回取走前的值）。 */
    public boolean consumeRefreshRequest() {
        boolean had = refreshRequested;
        refreshRequested = false;
        return had;
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
                case "getVar": {
                    // 与 getItem 同理：键不存在时回第二个参数（默认值）；storage0.getVar 靠它。
                    Object varVal = vars.get(argString(args, 0, ""));
                    return varVal == null ? (args.length > 1 ? args[1] : null) : varVal;
                }
                case "putVar":
                    // ConcurrentHashMap 不接受 null 值；JS 的 null/undefined 视为删键。
                    putVarValue(vars, argString(args, 0, ""), args.length > 1 ? args[1] : null);
                    return null;
                case "getItem": {
                    // 键不存在时回第二个参数（默认值），与 getMyVar 一致；storage0.getItem 靠它。
                    String itemVal = items.get(argString(args, 0, ""));
                    return itemVal == null ? (args.length > 1 ? args[1] : null) : itemVal;
                }
                case "setItem": {
                    // 先改内存表（与 HkKvStore 共享的同一批对象），再写穿落盘。
                    String itemKey = argString(args, 0, "");
                    String itemValue = argString(args, 1, "");
                    items.put(itemKey, itemValue);
                    HkKvStore.putItem(itemKey, itemValue);
                    return null;
                }
                case "deleteItem": {
                    // 删指定键（内存 + 磁盘）；键不存在时静默成功。
                    String deleteKey = argString(args, 0, "");
                    items.remove(deleteKey);
                    HkKvStore.removeItem(deleteKey);
                    return null;
                }
                case "clearItem": {
                    // 有参清指定键（AI 生成器长按菜单的 clearItem("list") 即此形），
                    // 无参清全部；内存 + 磁盘一起清。
                    if (args.length > 0) {
                        String clearKey = argString(args, 0, "");
                        items.remove(clearKey);
                        HkKvStore.removeItem(clearKey);
                    } else {
                        items.clear();
                        HkKvStore.clearItems();
                    }
                    return null;
                }
                case "getPublicItem": {
                    String pubVal = publicItems.get(argString(args, 0, ""));
                    return pubVal == null ? (args.length > 1 ? args[1] : null) : pubVal;
                }
                case "setPublicItem": {
                    String pubKey = argString(args, 0, "");
                    String pubValue = argString(args, 1, "");
                    publicItems.put(pubKey, pubValue);
                    HkKvStore.putPublicItem(pubKey, pubValue);
                    return null;
                }
                case "clearPublicItem":
                    // 有参清指定键，无参清全部；内存 + 磁盘一起清。
                    if (args.length > 0) {
                        String clearPubKey = argString(args, 0, "");
                        publicItems.remove(clearPubKey);
                        HkKvStore.removePublicItem(clearPubKey);
                    } else {
                        publicItems.clear();
                        HkKvStore.clearPublicItems();
                    }
                    return null;
                case "getMyVar": {
                    // 键按 plugin.js §2.2 加 "<标题>@" 前缀隔离；不存在回默认值。
                    Object my = myVars.get(HkKvStore.myVarKey(ruleTitle(), argString(args, 0, "")));
                    return my == null ? (args.length > 1 ? args[1] : null) : my;
                }
                case "putMyVar": {
                    // 内存留活对象（JS 值原样），落盘取字符串形；null/undefined 视为删键。
                    String myKey = HkKvStore.myVarKey(ruleTitle(), argString(args, 0, ""));
                    Object myValue = args.length > 1 ? args[1] : null;
                    if (myValue == null || myValue == Scriptable.NOT_FOUND || myValue instanceof Undefined) {
                        myVars.remove(myKey);
                        HkKvStore.removeMyVar(myKey);
                    } else {
                        myVars.put(myKey, myValue);
                        HkKvStore.putMyVar(myKey, myVarPersistString(myValue));
                    }
                    return null;
                }
                case "clearMyVar": {
                    // 有参清当前规则的指定键，无参清当前规则的全部键；不动别的规则的变量。
                    if (args.length > 0) {
                        String clearMyKey = HkKvStore.myVarKey(ruleTitle(), argString(args, 0, ""));
                        myVars.remove(clearMyKey);
                        HkKvStore.removeMyVar(clearMyKey);
                    } else {
                        String prefix = ruleTitle() + "@";
                        for (String key : new HashSet<>(myVars.keySet())) {
                            if (key.startsWith(prefix)) myVars.remove(key);
                        }
                        HkKvStore.clearMyVarsForTitle(ruleTitle());
                    }
                    return null;
                }
                case "listMyVarKeys": {
                    // 只列当前规则的键，去掉 "<标题>@" 前缀后以 NativeArray 返回。
                    List<String> keys = HkKvStore.listMyVarKeysForTitle(ruleTitle());
                    return cx.newArray(scope, keys.toArray());
                }
                case "refreshPage":
                case "refresh":
                    // 第 2 步 extras 第 6 件：真实现——调 Fragment 挂的监听（监听内切主线程
                    // 重跑 loadPage(1)）；未挂监听时空操作。dispatch 仍在求值线程上，
                    // 这里只转发，不直接碰 UI。
                    // 第 3 步第 1 件：同时置 refreshRequested 粘性标记，供 HkRouter
                    // 的分类切换检测读取（监听仍按原样调用）。
                    refreshRequested = true;
                    RefreshListener listener = refreshListener;
                    if (listener != null) listener.onRefreshPage();
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
                case "__hkStoreGet":
                    // §4.12：跨作用域取对象；key 不存在回 null（不回默认值参数，APK 的
                    // i0 map 语义就是查无则 null；hikerPop 的 __hpInvokeJs 查无时自行处理）。
                    return hkStore.get(storeKey(args, 0));
                case "__hkStoreSet":
                    // null/undefined 视为删键（ConcurrentHashMap 不接受 null 值，与 putVar 同款）。
                    putVarValue(hkStore, storeKey(args, 0), args.length > 1 ? args[1] : null);
                    return null;
                case "showSelectOptions":
                    return doShowSelectOptions(args);
                case "openAppIntent":
                    return doOpenAppIntent(args);
                case "publishRule":
                    return doPublishRule(args);
                case "fetchCodeByWebView":
                    return HkWebkitFetcher.fetchCodeByWebView(argString(args, 0, ""), scriptableToMap(args != null && args.length > 1 ? args[1] : null));
                case "executeWebRule":
                    return HkWebkitFetcher.executeWebRule(argString(args, 0, ""), argString(args, 1, ""), scriptableToMap(args != null && args.length > 2 ? args[2] : null));
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

    /** 当前规则标题（myVars 的 "<标题>@" 前缀用；rule 为空时 ""）。 */
    private String ruleTitle() {
        return rule == null ? "" : rule.title();
    }

    /**
     * myVars 落盘用的字符串形：字符串原样；数字/布尔 {@code toString}；
     * JS 对象/数组经 {@link HkItem#jsToJava} 转 Java 结构再 Gson 序列化为 JSON 字符串
     * （与卡片 extra 的序列化同一路；storage0 取值时对 {@code {…}/[…]} 形自动
     * {@code JSON.parse}，故经 storage0 读回仍是对象）；函数存源码字符串；其余
     * {@code Context.toString}。这只是持久化近似：读回的不再是活 JS 对象，
     * 见任务文档 §7 未验证 1。
     */
    private static String myVarPersistString(Object value) {
        if (value instanceof CharSequence) return value.toString();
        if (value instanceof Number || value instanceof Boolean) return Context.toString(value);
        if (value instanceof Scriptable) {
            try {
                Object javaValue = HkItem.jsToJava(value);
                if (javaValue == null) return Context.toString(value);
                return new Gson().toJson(javaValue);
            } catch (Throwable ignored) {
                return Context.toString(value);
            }
        }
        return Context.toString(value);
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

    /** 详情求值用的 MY_PARAMS（条目 extra，JSON 串或纯文本；原文 HkJsRuntime.setDetailParams 的 myParams 同款）。 */
    private volatile String myParams = "";

    /** 设置详情求值用的 MY_PARAMS（条目 extra）；下次 evalJs/evalLazy/evalLazyDry 求值时带入作用域。粘性值，下次调用覆盖。 */
    public void setDetailParams(String extra) {
        myParams = extra == null ? "" : extra;
    }

    /**
     * 选择器单值提取（Java 侧）：对给定 HTML 按选择器链取首个值（@attr/text/html/outerHtml 后缀）。
     * 第 3 步第 1 件供 HkRouter 的选择器型懒规则与 computeLastChapter 用（doParseDom 的
     * Java 等价，不经过 JS 分发；js: 字段不由本方法处理，调用方先分流）。
     */
    public String evalField(String html, String field, String url) {
        if (TextUtils.isEmpty(field)) return "";
        Document doc = Jsoup.parse(html == null ? "" : html, url == null ? "" : url);
        return extractFirst(selectChain(doc, field));
    }

    /** P()：通用 JS 求值入口（a02.b 的 .js: 求值调它），返回字符串结果。 */
    public String evalJs(String js, String baseUrl) {
        if (TextUtils.isEmpty(js)) return "";
        synchronized (evalLock) {
            ensureScope();
            if (scope == null) return "";
            Context cx = enterContext();
            try {
                if (!myParams.isEmpty()) ScriptableObject.putProperty(scope, "MY_PARAMS", myParams);
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

    /**
     * evalLazy：懒规则求值（第 3 步第 1 件，参照被删实现 HkJsRuntime.evalLazy）。
     * 先把求值上下文写进作用域再求值：{@code MY_URL}=懒段产出的页面地址、
     * {@code MY_POSITION}=点击位置（-1=无位置，如分类 tab）；同时把
     * {@code currentUrl} 设为 pageUrl（getUrl() 在求值期间读到它）。
     * <p>
     * 适配：我们没有被删那套的 getJsLazyPlugin/lazy 插件拼装——懒段求值直接跑
     * code 本身（与 HkEngine.evalLazyUrl 同口径）；干跑见 {@link #evalLazyDry}
     * （本方法内自置 dryRun 标记，求值完恢复）。
     */
    public String evalLazy(String code, String pageUrl, int position) {
        if (TextUtils.isEmpty(code)) return "";
        synchronized (evalLock) {
            ensureScope();
            if (scope == null) return "";
            if (pageUrl != null) currentUrl = pageUrl;
            Context cx = enterContext();
            try {
                ScriptableObject.putProperty(scope, "MY_URL", pageUrl == null ? "" : pageUrl);
                ScriptableObject.putProperty(scope, "MY_POSITION", position);
                if (!myParams.isEmpty()) ScriptableObject.putProperty(scope, "MY_PARAMS", myParams);
                Object result = cx.evaluateString(scope, code, "lazy", 1, null);
                if (result == null || result instanceof Undefined) return "";
                return Context.toString(result);
            } catch (Throwable e) {
                SpiderDebug.log("HkJsRuntime", "evalLazy failed: %s", e.toString());
                throw new RuntimeException(e);
            } finally {
                Context.exit();
            }
        }
    }

    /**
     * evalLazyDry：懒规则干跑（第 3 步第 2 件的分类器用）：求值但不产生播放副
     * 作用——置 {@code dryRun} 全局标记（规则里的 fetch/网络调用在干跑期间
     * 应被跳过，见 HkEpisodeDryClassifier 的约定），求值完恢复。
     * 返回求值结果字符串；失败返回 ""。
     */
    public String evalLazyDry(String code, String pageUrl, int position) {
        if (TextUtils.isEmpty(code)) return "";
        synchronized (evalLock) {
            ensureScope();
            if (scope == null) return "";
            if (pageUrl != null) currentUrl = pageUrl;
            Context cx = enterContext();
            try {
                ScriptableObject.putProperty(scope, "MY_URL", pageUrl == null ? "" : pageUrl);
                ScriptableObject.putProperty(scope, "MY_POSITION", position);
                ScriptableObject.putProperty(scope, "dryRun", true);
                if (!myParams.isEmpty()) ScriptableObject.putProperty(scope, "MY_PARAMS", myParams);
                Object result = cx.evaluateString(scope, code, "lazyDry", 1, null);
                if (result == null || result instanceof Undefined) return "";
                return Context.toString(result);
            } catch (Throwable e) {
                return "";
            } finally {
                try {
                    ScriptableObject.putProperty(scope, "dryRun", false);
                } catch (Throwable ignored) {
                }
                Context.exit();
            }
        }
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
     * hiker:// / file:// 内置协议先走 readInternalUrl（与 doFetch 同款一行，
     * 不能处理的回 null 再走 OkHttp），所以 Hikerurl.js 的 getScript 经 request()
     * 取 {@code hiker://files/libs/hikerPop.js} 时拿到的是种子文件内容而不是空串。
     */
    private Object doRequest(Object[] args) {
        String url = argString(args, 0, "");
        if (TextUtils.isEmpty(url)) return "";
        String internal = readInternalUrl(url);
        if (internal != null) return internal;
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
     * b22 code 12：require / 动态加载 JS（第 2 步第 4 件接内置 hikerPop）。
     * hikerPop.js 直返 {@link HkHikerPopBridge} 的内置实现（返回 JS 文本，调用方
     * eval——APK 的 case 12 同样只返回字面量文本，不在 Java 侧 eval；见
     * HkHikerPopBridge 上的 (a)(b) 两项核实结论）；hiker://files/libs/ 先读文本，
     * 命中官方原版三标记时丢弃原版改返内置实现；其余分支原样不动。
     */
    private Object doRequire(Object[] args) {
        String url = argString(args, 0, "");
        if (TextUtils.isEmpty(url)) return "";
        int q = url.indexOf('?');
        if (q >= 0) url = url.substring(0, q);
        int hash = url.indexOf('#');
        if (hash >= 0) url = url.substring(0, hash);
        if (url.endsWith("hikerPop.js") || "hikerPop.js".equals(url)) {
            SpiderDebug.log("HkJsRuntime", "hikerPop -> builtin src=%s", url);
            return HkHikerPopBridge.builtinSource(this);
        }
        if (url.startsWith("hiker://files/libs/")) {
            String text = readInternalUrl(url);
            if (HkHikerPopBridge.isOfficialOriginal(text)) {
                SpiderDebug.log("HkJsRuntime", "hikerPop -> builtin (lib cache) len=%d", text.length());
                return HkHikerPopBridge.builtinSource(this);
            }
            return text == null ? "" : text;
        }
        if (url.startsWith("hiker://assets/")) {
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
    // 当前 Activity 持有器 + showSelectOptions 原生弹窗（第 2 步第 5 件）
    // ------------------------------------------------------------------

    /** HkListActivity / HkSearchActivity 在 onResume 登记自己为当前 Activity。 */
    public static void setCurrentActivity(Activity activity) {
        currentActivityRef = activity == null ? null : new WeakReference<>(activity);
    }

    /**
     * onPause 清除登记；只有持有者正是这个 Activity 时才清，避免后一个 Activity
     * 已登记、而前一个 Activity 的 onPause 把它误清掉（Activity 切换时序）。
     */
    public static void clearCurrentActivity(Activity activity) {
        if (activity == null || currentActivityRef == null) return;
        if (currentActivityRef.get() == activity) currentActivityRef = null;
    }

    /** 取当前 Activity：无登记、已被回收、或正在结束/已销毁时一律回 null（调用方不弹不闪退）。 */
    private static Activity getCurrentActivity() {
        if (currentActivityRef == null) return null;
        Activity activity = currentActivityRef.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        return activity;
    }

    /**
     * k22 case 0 showSelectOptions（REPLICATION.md §4.5）：入参 Map
     * {js, options[], title, selectedIndex} → ez3.Q 弹原生选择列表。
     * <p>
     * 本实现：WebHtvAlertDialogBuilder（应用唯一的 Material 弹窗构造点，主题已绑定）
     * + setItems 列表，点一项即选中并关闭（与 APK 的 ez3.Q 点选即确认一致）；
     * selectedIndex 只作初始高亮参考，setItems 不支持高亮故忽略（已解析并夹取到合法范围，
     * 仅留档；见任务文档 §5 未验证 2）。
     * <p>
     * dispatch 本身立即返回 null，不阻塞规则求值线程；选中结果走 replayJs 异步回放。
     * 入参容错：arg0 不是对象、js 为空、options 为空时直接返回 null 并记日志，不抛错。
     */
    private Object doShowSelectOptions(Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof Scriptable)) {
            SpiderDebug.log("HkJsRuntime", "showSelectOptions 入参不是对象，忽略");
            return null;
        }
        Scriptable opts = (Scriptable) args[0];
        String js = argJsString(opts, "js", "");
        String title = argJsString(opts, "title", "");
        // selectedIndex 缺省默认 0（首项）；越界夹取到合法范围；setItems 不用它做高亮。
        int selectedIndex = (int) argJsLong(opts, "selectedIndex", 0);
        List<String> options = new ArrayList<>();
        Object rawOptions = ScriptableObject.getProperty(opts, "options");
        if (rawOptions instanceof NativeArray) {
            NativeArray arr = (NativeArray) rawOptions;
            for (int i = 0; i < arr.getLength(); i++) {
                Object el = arr.get(i, arr);
                options.add(el == null || el == Scriptable.NOT_FOUND || el instanceof Undefined
                        ? "" : Context.toString(el));
            }
        } else if (rawOptions instanceof Scriptable) {
            // 类数组对象：按 length 读下标（规则手写 {0: 'a', 1: 'b', length: 2} 时兜底）。
            Scriptable arrayLike = (Scriptable) rawOptions;
            long len = argJsLong(arrayLike, "length", 0);
            for (int i = 0; i < len; i++) {
                Object el = ScriptableObject.getProperty(arrayLike, i);
                options.add(el == null || el == Scriptable.NOT_FOUND || el instanceof Undefined
                        ? "" : Context.toString(el));
            }
        }
        if (TextUtils.isEmpty(js) || options.isEmpty()) {
            SpiderDebug.log("HkJsRuntime", "showSelectOptions 入参不完整（js 为空或 options 为空），忽略");
            return null;
        }
        if (selectedIndex < 0) selectedIndex = 0;
        if (selectedIndex >= options.size()) selectedIndex = options.size() - 1;
        Activity activity = getCurrentActivity();
        if (activity == null) {
            SpiderDebug.log("HkJsRuntime", "showSelectOptions 无可用 Activity，忽略（title=%s）", title);
            return null;
        }
        final String[] items = options.toArray(new String[0]);
        final String dialogTitle = title;
        final String replayCode = js;
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            new WebHtvAlertDialogBuilder(activity)
                    .setTitle(dialogTitle)
                    .setItems(items, (dialog, which) -> {
                        if (which >= 0 && which < items.length) {
                            replayJs(replayCode, items[which], which);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
        return null;
    }

    /**
     * showSelectOptions 选中后的回放（§4.5：选中后在页面引擎里按 (input, MY_INDEX) 回放 js）。
     * <p>
     * 双通道：先在作用域里置 {@code input}（选中值字符串）与 {@code MY_INDEX}（选中下标）
     * 两个全局变量——照顾像 AI 生成器 onChange 那种引用自由变量 input 的箭头函数；
     * 再求值 js：js 是函数源码时以 {@code (<js>)(<input 的 JSON 字符串>, <i>)} 形式调用，
     * 否则直接当代码 eval（如 hikerPop 的 {@code __hpInvokeJs(id)} 调用表达式）。
     * 两种都 best-effort，失败只记日志。
     * <p>
     * 回放一律走 Task 后台线程，绝不在 UI 线程求值 JS；并在 evalLock 下进 Context
     * （与 evalJs/evalRule 同一把串行锁，Rhino scope 不支持并发求值）。
     * 注意：回放只在用户点选后触发，此时原求值已返回、evalLock 已释放，故回放线程取锁不会死锁。
     */
    public void replayJs(String js, String input, int index) {
        if (TextUtils.isEmpty(js)) return;
        final String code = js;
        final String value = input == null ? "" : input;
        final int idx = index;
        Task.execute(() -> {
            synchronized (evalLock) {
                ensureScope();
                if (scope == null) return;
                Context cx = enterContext();
                try {
                    ScriptableObject.putProperty(scope, "input", value);
                    ScriptableObject.putProperty(scope, "MY_INDEX", idx);
                    if (isFunctionSource(code)) {
                        cx.evaluateString(scope,
                                "(" + code + ")(" + toJsonString(value) + ", " + idx + ");",
                                "showSelectOptions", 1, null);
                    } else {
                        cx.evaluateString(scope, code, "showSelectOptions", 1, null);
                    }
                } catch (Throwable e) {
                    SpiderDebug.log("HkJsRuntime", "showSelectOptions 回放失败: %s", e.toString());
                } finally {
                    Context.exit();
                }
            }
        });
    }

    /** 函数源码判定：function 开头，或箭头函数（(a, b) => … / a => …）；其余当代码直接 eval。 */
    private static boolean isFunctionSource(String code) {
        String t = code.trim();
        if (t.startsWith("function")) return true;
        return t.matches("(?s)^\\(?[\\w\\s,$]*\\)?\\s*=>.*");
    }

    /** 最小 JSON 字符串转义（回放调用形时把 input 值拼进代码用；不引 org.json，避免新依赖）。 */
    private static String toJsonString(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                    break;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** __hkStoreGet/Set 的 key 归一化：最简单的字符串化（§4.12 的 f42.h(obj, false) 细节未核对）。 */
    private String storeKey(Object[] args, int i) {
        return argString(args, i, "");
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

    /**
     * Rhino JS 对象 → Java Map（原始值：String/Number/Boolean/Scriptable；
     * 跳过函数与未定义）。与 {@link #argMap} 的区别：不字符串化，保留类型，
     * 供 openAppIntent 的 param（按值类型 putExtra）与 fetchCodeByWebView
     * 的 options 用。参照坑（见 HkWebkitFetcher）：Rhino 的 JS 对象是
     * Scriptable 不是 java.util.Map，instanceof Map 恒 false，必须经此转换。
     */
    private Map<String, Object> scriptableToMap(Object arg) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (!(arg instanceof Scriptable)) return map;
        Scriptable obj = (Scriptable) arg;
        for (Object id : obj.getIds()) {
            if (!(id instanceof String)) continue;
            Object v = ScriptableObject.getProperty(obj, (String) id);
            if (v == null || v == Scriptable.NOT_FOUND || v instanceof Undefined) continue;
            if (v instanceof org.mozilla.javascript.NativeFunction) continue;
            map.put((String) id, v);
        }
        return map;
    }

    // ------------------------------------------------------------------
    // openAppIntent / publishRule（第 3 步第 4 件）
    // ------------------------------------------------------------------

    /**
     * openAppIntent(p, a, param, ruleTitleKey)：打开第三方应用的指定 Activity。
     * 移植自参照 openAppIntent 全形（见 HkAuthManager 类头注的官姿态）：
     * <ul>
     * <li>ruleTitle 为空 → 直接回 Boolean.FALSE，不做任何检查；</li>
     * <li>p/a 只能是字符串：非字符串（数字/对象）参照抛 JS 异常「参数类型只能是
     * 字符串」——我方 dispatch 统一吞异常回 null，抛不到规则里，故此处回
     * FALSE 并记日志（与参照差一档，见注）；</li>
     * <li>action 恒 VIEW、flags 恒 0x10008000、component = 包名 + 类名；</li>
     * <li>先查装机（未装回 FALSE），再消费 param；param 仅 JSONObject 形有效，
     * 逐键 putExtra（intentData → setData；Integer/Double/Boolean 按类型，
     * 其余转字符串）；</li>
     * <li>整段过 HkAuthManager.checkOpenAppIntent 的每回确认 + 1 秒频控门，
     * 未确认/频控未过回 FALSE；启动异常 toast「出错：」+ 异常信息，回 FALSE；</li>
     * <li>返回值是真 Boolean（不是字符串 "false"）。</li>
     * </ul>
     */
    private Boolean doOpenAppIntent(Object[] args) {
        String title = ruleTitle();
        if (title.isEmpty()) return Boolean.FALSE;
        Object pArg = args != null && args.length > 0 ? args[0] : null;
        Object aArg = args != null && args.length > 1 ? args[1] : null;
        if ((pArg != null && !(pArg instanceof String) && pArg != Scriptable.NOT_FOUND && !(pArg instanceof Undefined))
                || (aArg != null && !(aArg instanceof String) && aArg != Scriptable.NOT_FOUND && !(aArg instanceof Undefined))) {
            SpiderDebug.log("HkJsRuntime", "openAppIntent 参数类型只能是字符串");
            return Boolean.FALSE;
        }
        String pkg = argString(args, 0, "");
        String cls = argString(args, 1, "");
        if (pkg.isEmpty() || cls.isEmpty()) return Boolean.FALSE;
        Object param = args != null && args.length > 2 ? args[2] : null;
        final boolean[] result = {false};
        boolean allowed = HkAuthManager.checkOpenAppIntent(title, pkg, () -> {
            try {
                if (!isAppInstalled(pkg)) {
                    Notify.show("找不到对应的软件，请确认已安装");
                    return;
                }
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.addFlags(0x10008000);
                Map<String, Object> extras = scriptableToMap(param);
                for (Map.Entry<String, Object> e : extras.entrySet()) {
                    String k = e.getKey();
                    Object v = e.getValue();
                    if ("intentData".equals(k)) {
                        intent.setData(Uri.parse(Context.toString(v)));
                    } else if (v instanceof Integer) {
                        intent.putExtra(k, (Integer) v);
                    } else if (v instanceof Double) {
                        intent.putExtra(k, (Double) v);
                    } else if (v instanceof Boolean) {
                        intent.putExtra(k, (Boolean) v);
                    } else if (v instanceof Number) {
                        intent.putExtra(k, ((Number) v).doubleValue());
                    } else {
                        intent.putExtra(k, Context.toString(v));
                    }
                }
                intent.setClassName(pkg, cls);
                App.get().startActivity(intent);
                result[0] = true;
            } catch (Throwable e) {
                Notify.show("出错：" + e.getMessage());
                SpiderDebug.log("HkJsRuntime", "openAppIntent failed: %s", e.toString());
            }
        });
        return allowed && result[0] ? Boolean.TRUE : Boolean.FALSE;
    }

    private boolean isAppInstalled(String pkg) {
        try {
            App.get().getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * publishRule()：把当前规则发布为云口令（分享给别人导入）。
     * 参照的落点是云仓库提交（HkPublishHelper，整套账号/提交/JS 评测子系统，
     * yingshi 没有，不照搬）——且参照自己的 publishRule 也只弹确认、上传动作
     * 记名未落（官生态后端，星辰无此服务）；我方落点是云剪贴上传：
     * 先过 HkAuthManager.checkAuth 持久授权门，通过后把当前规则 JSON 经
     * HkCloudClip 上传（默认云 6 pasteme，其 {@code 云6oooole/xxxxxx/{path}}
     * 码可直接被我方 resolveCloudPassword 导入）；成功回分享码，失败回 ""。
     * 规则为空时回 ""。
     */
    private String doPublishRule(Object[] args) {
        String title = ruleTitle();
        if (title.isEmpty() || rule == null) return "";
        final String[] result = {""};
        boolean allowed = HkAuthManager.checkAuth(title, "publishRule",
                "将规则「" + title + "」发布为云口令（上传到云剪贴板，供他人导入）", () -> {
                    try {
                        String json = rule.toJson();
                        result[0] = HkCloudClip.upload(json, 6);
                    } catch (Throwable e) {
                        SpiderDebug.log("HkJsRuntime", "publishRule upload failed: %s", e.toString());
                        result[0] = "";
                    }
                });
        return allowed ? result[0] : "";
    }
}
