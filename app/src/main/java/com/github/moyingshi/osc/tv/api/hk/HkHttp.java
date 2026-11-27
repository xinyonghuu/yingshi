package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 海阔小程序 HTTP 层，第 1 步。
 * <p>
 * 等价于 APK 的混淆类 h12（682 行，全 static）。本步只实现列表求值用到的部分：
 * OkHttp 客户端族 + URL 模板解析 j()/i() + 取数 k() 的 GET 快路与通用分支。
 * DNS/DoH（a/b/d/e）、字符集解析 f()、Request/Call 构造 m()/q() 留待后续。
 * <p>
 * j/i/k 的行为按反编译原文逐行核对（REPLICATION.md §7）。
 */
public final class HkHttp {

    /** h12.a：[firstPage=...] 标记。 */
    private static final Pattern FIRST_PAGE = Pattern.compile("\\[firstPage=([^\\]]*)\\]");
    /** h12.b：fypage@算术@ 占位符。 */
    private static final Pattern FYPAGE_ARITH = Pattern.compile("fypage@((?:[-+*/]\\d+@)+)");
    /** h12.c：单个算术操作。 */
    private static final Pattern ARITH_OP = Pattern.compile("([-+*/])(\\d+)@");

    private static final MediaType FORM = MediaType.parse("application/x-www-form-urlencoded; charset=utf-8");

    private static volatile OkHttpClient client;

    private HkHttp() {
    }

    /** h12.h()：默认客户端（超时值未从反编译确认，本步取 15s/30s/15s 保守值）。 */
    public static OkHttpClient client() {
        OkHttpClient c = client;
        if (c == null) {
            synchronized (HkHttp.class) {
                c = client;
                if (c == null) {
                    c = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(15, TimeUnit.SECONDS)
                            .build();
                    client = c;
                }
            }
        }
        return c;
    }

    /** 指定超时的客户端（h12.k() 里 i2 > 0 时用同值建新客户端）。 */
    public static OkHttpClient clientWithTimeout(long timeoutMs) {
        return client().newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * h12.i()：j() + 对 arg 做 URL 编码后替换 "**"。
     */
    public static String resolveUrlEncoded(int type, String url, String arg, boolean stripEmpty) {
        String resolved = resolveUrl(type, url, "", stripEmpty);
        if (arg == null) arg = "";
        try {
            arg = URLEncoder.encode(arg, "UTF-8");
        } catch (Exception ignored) {
        }
        return resolved.replace("**", arg);
    }

    /**
     * h12.j()：URL 模板解析。
     * <ul>
     *   <li>stripEmpty 且以 {@code hiker://empty#} 开头 → 去掉 14 字符前缀；</li>
     *   <li>含 {@code [firstPage=X]}：type &le; 1 时整个 URL 取 X，type &gt; 1 时去掉该标记；</li>
     *   <li>{@code fyclass} → arg，{@code fyarea/fyyear/fysort} → ""，{@code fyAll} → arg；</li>
     *   <li>{@code fypage@+N@/-N@/*N@//N@} 按 type 做算术后替换；剩余 {@code fypage} → type。</li>
     * </ul>
     */
    public static String resolveUrl(int type, String url, String arg, boolean stripEmpty) {
        if (url == null) return "";
        String result = url.trim();
        if (stripEmpty && result.startsWith("hiker://empty#")) {
            result = result.substring(14);
        }
        Matcher firstPage = FIRST_PAGE.matcher(result);
        if (firstPage.find()) {
            result = firstPage.group(1);
            if (type > 1) {
                result = firstPage.replaceAll("");
            }
            if (stripEmpty && result.startsWith("hiker://empty#")) {
                result = result.substring(14);
            }
        }
        if (arg == null) arg = "";
        String replaced = result.replace("fyclass", arg).replace("fyarea", "").replace("fyyear", "").replace("fysort", "");
        Matcher arith = FYPAGE_ARITH.matcher(replaced.replace("fyAll", arg));
        StringBuffer out = new StringBuffer();
        while (arith.find()) {
            String ops = arith.group(1);
            int page = type;
            if (ops != null) {
                Matcher op = ARITH_OP.matcher(ops);
                while (op.find()) {
                    try {
                        int n = Integer.parseInt(op.group(2));
                        String symbol = op.group(1);
                        if ("*".equals(symbol)) {
                            page *= n;
                        } else if ("+".equals(symbol)) {
                            page += n;
                        } else if ("-".equals(symbol)) {
                            page -= n;
                        } else if ("/".equals(symbol) && n != 0) {
                            page /= n;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            arith.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(page)));
        }
        arith.appendTail(out);
        return out.toString().replace("fypage", String.valueOf(type));
    }

    /**
     * h12.k(timeoutMs, url, userAgent)：取数入口。
     * URL 格式：{@code baseUrl;METHOD;body}，头参数写在 {@code ;} 与 {@code {} 之间，
     * 以 {@code &&} 连接，每项 {@code key@value}。
     * <p>
     * 本步实现：GET 且无 body/无头参数走快路；其余走通用分支（POST body 按表单编码）。
     * 原文 k() 465 行之后的分支未全读，通用分支是大体等价实现，见任务文档。
     */
    public static String fetch(int timeoutMs, String url, String userAgent) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String body = "";
        String target = "";
        String method = "GET";
        if (url == null) {
            target = "";
        } else {
            String trimmed = url.trim();
            int semi = trimmed.indexOf(';');
            int brace = trimmed.lastIndexOf('{');
            if (semi > 0 && brace > semi) {
                for (String pair : trimmed.substring(semi + 1, brace).split("&&")) {
                    int at = pair.indexOf('@');
                    if (at > 0) headers.put(pair.substring(0, at).trim(), pair.substring(at + 1).trim());
                }
                trimmed = trimmed.substring(0, semi).concat(trimmed.substring(brace + 1)).trim();
            }
            String[] parts = trimmed.split(";", -1);
            target = parts[0].trim();
            if (parts.length > 1 && !parts[1].trim().isEmpty()) method = parts[1].trim().toUpperCase();
            if (parts.length > 2 && !parts[2].trim().isEmpty()) body = parts[2].trim();
        }
        boolean isGet = "GET".equals(method);
        if (isGet && body.isEmpty() && headers.isEmpty()) {
            OkHttpClient c = timeoutMs > 0 ? clientWithTimeout(timeoutMs) : client();
            if (target == null || !(target.startsWith("http://") || target.startsWith("https://"))) {
                SpiderDebug.log("hk", "不支持的链接协议（需 M2 JS 运行时）：%s", target);
                return null;
            }
            Request request = new Request.Builder()
                    .url(target)
                    .header("User-Agent", TextUtils.isEmpty(userAgent) ? HkRule.UA_MOBILE : userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .build();
            return executeString(c, request, target);
        }
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            SpiderDebug.log("hk", "不支持的链接协议（需 M2 JS 运行时）：%s", target);
            return null;
        }
        Request.Builder builder = new Request.Builder().url(target)
                .header("User-Agent", TextUtils.isEmpty(userAgent) ? HkRule.UA_MOBILE : userAgent);
        for (Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        if (isGet) {
            builder.get();
        } else if (body.isEmpty()) {
            builder.method(method, RequestBody.create("", FORM));
        } else {
            builder.method(method, RequestBody.create(body, FORM));
        }
        OkHttpClient c = timeoutMs > 0 ? clientWithTimeout(timeoutMs) : client();
        return executeString(c, builder.build(), target);
    }

    /** 简化取数：GET，规则 UA。 */
    public static String get(String url, String userAgent) throws IOException {
        return fetch(0, url, userAgent);
    }

    /** 执行请求并返回响应体字符串；非 2xx 抛 IOException（原文行为）。 */
    public static String executeString(OkHttpClient c, Request request, String url) throws IOException {
        Response response = c.newCall(request).execute();
        try {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " " + url);
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("空响应 " + url);
            }
            return responseBody.string();
        } finally {
            response.close();
        }
    }
}
