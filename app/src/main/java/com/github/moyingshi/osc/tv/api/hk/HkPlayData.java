package com.github.moyingshi.osc.tv.api.hk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 海阔 PlayData 多线路 JSON 模型（第 3 步第 3 件）。
 * <p>
 * 两种入形态（都认）：
 * <ol>
 * <li>PlayData 对象形 {@code {"url","urls":[],"names":[],"header":{},"headers":[],"audioUrls":[],
 * "danmu":"","lyric":"","subtitle":""}}——8.83 PlayData 9 字段（参照
 * {@code b056322b} HkPlayData；{@code headers} 元素两种形态都认：{@code {"Referer":"..."}}
 * 对象即单线路头表，{@code "Cookie@xxx"} 字符串属同线路多对累积，与
 * {@link HkRouter#parsePlayJson} 旧解析同口径）。</li>
 * <li>多线路 JSONArray 形 {@code [{"name":..,"url":..}]}——{@link HkSession.MultiLine#toJson()}
 * 的 f6 第 8 参形；元素键名按 REPLICATION.md §11/§14.3 仍待真机核对，本类只认
 * {@code name}/{@code url}，不猜别的键。</li>
 * </ol>
 * <p>
 * 行为约定：
 * <ul>
 * <li>解析是纯结构动作：不跑规则、不联网、不抛错——解析失败回 null，调用方按单地址处理。</li>
 * <li>请求头值过官方 {@code decodeConflictStr}（全角 ？？/＆＆/；；/，，还原），与旧
 * {@code parsePlayJson} 同口径；{@link HkDetailBridge} 的旧展开路不解码，改走本类后
 * 一并按此口径（含字符串形 headers 旧路会丢，本类会收——那是旧路的漏，不是新行为）。</li>
 * <li>{@link #toSingleLineJson(int)} 的迷你 JSON 打包口径与旧 {@code HkDetailBridge.expandLineUrl}
 * 一致：取第 line 条地址，配该线请求头（line 0 另拼全局 header）、subtitle/audioUrls[line]/
 * danmu/lyric；无任何附加字段时直接回干净地址。</li>
 * <li>PUSH 直播没有请求头通道（{@code PushParser} 只带 url|title，{@code SiteApi.pushPlayer}
 * 只播裸地址）：本类解析出的分线请求头只进 {@link HkPlay} 模型与日志，播不出去——
 * 需要 Referer/Cookie 才能播的地址仍可能失败，这是接线层缺口，不是本类能补的，记名。</li>
 * </ul>
 */
public final class HkPlayData {

    private String url = "";
    private final List<String> urls = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    /** 全局头表（PlayData.header，单数 Map）。 */
    private final Map<String, String> header = new LinkedHashMap<>();
    /** 逐线路头表（PlayData.headers；元素少于 urls 时按 HkPlay.getHeaders 越界回首条）。 */
    private final List<Map<String, String>> headers = new ArrayList<>();
    private final List<String> audioUrls = new ArrayList<>();
    private String danmu = "";
    private String lyric = "";
    private String subtitle = "";
    /** 解析来源原文（toSingleLineJson 地址为空时回它；非 JSON 来源时 ""）。 */
    private String sourceJson = "";

    private HkPlayData() {
    }

    // ================= 解析 =================

    /** 两种形态都认：对象形与 JSONArray 形；不是多线路 JSON 回 null（调用方按单地址处理）。 */
    public static HkPlayData fromJson(String json) {
        if (json == null) return null;
        String t = json.trim();
        if (t.isEmpty()) return null;
        try {
            if (t.startsWith("[")) {
                return fromArray(new JSONArray(t));
            }
            if (t.startsWith("{")) {
                return fromObject(new JSONObject(t));
            }
            return null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 从已解析的 HkPlay 结果模型建 PlayData（playRouted 的路由器结果 → play() 的 JSON 口）。 */
    public static HkPlayData fromPlay(HkPlay play) {
        HkPlayData d = new HkPlayData();
        if (play == null) return d;
        d.url = play.getUrl() == null ? "" : play.getUrl().trim();
        for (String u : play.getUrls()) {
            if (u != null) d.urls.add(u.trim());
        }
        for (String n : play.getNames()) {
            d.names.add(n == null ? "" : n.trim());
        }
        d.header.putAll(play.getHeaders());
        for (Map<String, String> h : play.getLineHeaders()) {
            d.headers.add(h == null ? new HashMap<>() : new LinkedHashMap<>(h));
        }
        for (String a : play.getAudioUrls()) {
            if (a != null) d.audioUrls.add(a.trim());
        }
        d.danmu = play.getDanmu();
        d.lyric = play.getLyric();
        d.subtitle = play.getSubtitle();
        return d;
    }

    /** PlayData 对象形解析（8.83 HttpParser.getPlayData 语义，见类注）。 */
    public static HkPlayData fromObject(JSONObject o) {
        if (o == null) return null;
        HkPlayData d = new HkPlayData();
        d.sourceJson = o.toString();
        d.url = jsonString(o.opt("url"));
        JSONArray urls = o.optJSONArray("urls");
        if (urls != null) {
            for (int i = 0; i < urls.length(); i++) d.urls.add(jsonString(urls.opt(i)));
        } else {
            // urls 单串形（与 HkSession.parseMultiLine 同口径容忍）。
            String singleUrls = jsonString(o.opt("urls"));
            if (!singleUrls.isEmpty()) d.urls.add(singleUrls);
        }
        JSONArray names = o.optJSONArray("names");
        if (names != null) {
            for (int i = 0; i < names.length(); i++) d.names.add(jsonString(names.opt(i)));
        } else {
            String singleName = jsonString(o.opt("names"));
            if (!singleName.isEmpty()) d.names.add(singleName);
        }
        JSONObject headerObj = o.optJSONObject("header");
        if (headerObj != null) {
            for (String k : jsonKeys(headerObj)) {
                String kk = k == null ? "" : k.trim();
                String vv = decodeConflict(jsonString(headerObj.opt(k)));
                if (!kk.isEmpty() && !vv.isEmpty()) d.header.put(kk, vv);
            }
        }
        // headers：对象元素＝一条线路的头表（先结算之前累积的同线路多对，再单独成表）；
        // "K@V" 字符串元素＝同线路多对累积进当前表；其余形态跳过。
        JSONArray headers = o.optJSONArray("headers");
        if (headers != null) {
            Map<String, String> cur = new LinkedHashMap<>();
            for (int i = 0; i < headers.length(); i++) {
                Object el = headers.opt(i);
                if (el instanceof JSONObject) {
                    if (!cur.isEmpty()) {
                        d.headers.add(cur);
                        cur = new LinkedHashMap<>();
                    }
                    JSONObject ho = (JSONObject) el;
                    Map<String, String> m = new LinkedHashMap<>();
                    for (String k : jsonKeys(ho)) {
                        String kk = k == null ? "" : k.trim();
                        String vv = decodeConflict(jsonString(ho.opt(k)));
                        if (!kk.isEmpty() && !vv.isEmpty()) m.put(kk, vv);
                    }
                    d.headers.add(m);
                } else {
                    String kv = jsonString(el);
                    int at = kv.indexOf('@');
                    if (at > 0) {
                        cur.put(kv.substring(0, at).trim(), decodeConflict(kv.substring(at + 1).trim()));
                    }
                }
            }
            if (!cur.isEmpty()) d.headers.add(cur);
        }
        JSONArray audioUrls = o.optJSONArray("audioUrls");
        if (audioUrls != null) {
            for (int i = 0; i < audioUrls.length(); i++) d.audioUrls.add(jsonString(audioUrls.opt(i)));
        }
        d.subtitle = jsonString(o.opt("subtitle"));
        d.danmu = jsonString(o.opt("danmu"));
        d.lyric = jsonString(o.opt("lyric"));
        return d;
    }

    /** 多线路 JSONArray 形解析（元素 {name, url}）；全是空地址时回 null。 */
    public static HkPlayData fromArray(JSONArray arr) {
        if (arr == null) return null;
        HkPlayData d = new HkPlayData();
        d.sourceJson = arr.toString();
        boolean anyUrl = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String u = jsonString(o.opt("url"));
            if (!u.isEmpty()) anyUrl = true;
            d.urls.add(u);
            d.names.add(jsonString(o.opt("name")));
        }
        if (!anyUrl) return null;
        d.url = d.urls.get(0);
        return d;
    }

    // ================= 取值 =================

    public String getUrl() {
        return url == null ? "" : url;
    }

    public List<String> getUrls() {
        return urls;
    }

    public List<String> getNames() {
        return names;
    }

    public Map<String, String> getHeader() {
        return header;
    }

    public List<Map<String, String>> getHeaders() {
        return headers;
    }

    public List<String> getAudioUrls() {
        return audioUrls;
    }

    public String getDanmu() {
        return danmu == null ? "" : danmu;
    }

    public String getLyric() {
        return lyric == null ? "" : lyric;
    }

    public String getSubtitle() {
        return subtitle == null ? "" : subtitle;
    }

    /** 线路数（urls 条数；单数 url 形计 0，调用方用 url 字段）。 */
    public int lineCount() {
        return urls.size();
    }

    /** 是不是多线路（urls ≥ 2）。 */
    public boolean isMultiLine() {
        return urls.size() > 1;
    }

    /** 按下标取某条线路的地址（越界回首条；无多线时回 url 字段）。 */
    public String urlAt(int line) {
        if (urls.isEmpty()) return getUrl();
        return urls.get(line >= 0 && line < urls.size() ? line : 0);
    }

    /** 按下标取某条线路的名称（缺名补「线路N」，与 HkDetailBridge 旧展开同口径）。 */
    public String nameAt(int line) {
        int i = line >= 0 && line < urls.size() ? line : 0;
        if (i < names.size()) {
            String n = names.get(i);
            if (n != null && !n.trim().isEmpty()) return n.trim();
        }
        return "线路" + (i + 1);
    }

    /**
     * 按下标取某条线路的请求头（全局 header 与分线 headers 合并，分线优先；无多线时回全局表）。
     * 合并口径与旧 {@code HkDetailBridge.expandLineUrl} 一致：line 0 另拼全局 header。
     */
    public Map<String, String> headersAt(int line) {
        Map<String, String> out = new LinkedHashMap<>(header);
        if (!headers.isEmpty()) {
            Map<String, String> m = headers.get(line >= 0 && line < headers.size() ? line : 0);
            if (m != null) out.putAll(m);
        }
        return out;
    }

    /** 按下标取某条线路的音频地址（越界回首条；无则 ""）。 */
    public String audioAt(int line) {
        if (audioUrls.isEmpty()) return "";
        return audioUrls.get(line >= 0 && line < audioUrls.size() ? line : 0);
    }

    // ================= 输出 =================

    /**
     * 填进 HkPlay 结果模型（旧 {@link HkRouter#parsePlayJson} 的装配语义）：
     * urls 全收、url 取 urls[0]（urls 为空才取单数 url）、names 为空补「线路1..N」、
     * 多于 urls 的截断、headers/全局 header/audioUrls/subtitle/danmu/lyric 全量入模。
     * 返回填完的模型是否非空（与旧 parsePlayJson 返回值同口径）。
     */
    public boolean toPlay(HkPlay out) {
        if (out == null) return false;
        for (String u : urls) out.addUrl(u == null ? "" : u.trim());
        if (!urls.isEmpty()) {
            out.setUrl(urls.get(0) == null ? "" : urls.get(0).trim());
        } else if (out.getUrl().isEmpty() && !url.isEmpty()) {
            out.setUrl(url.trim());
        }
        for (String n : names) out.addName(n == null ? "" : n.trim());
        if (out.getNames().isEmpty()) {
            for (int i = 0; i < out.getUrls().size(); i++) out.addName("线路" + (i + 1));
        }
        while (!out.getUrls().isEmpty() && out.getNames().size() > out.getUrls().size() && !out.getNames().isEmpty()) {
            out.getNames().remove(out.getNames().size() - 1);
        }
        for (Map<String, String> h : headers) out.addLineHeaders(h);
        for (Map.Entry<String, String> e : header.entrySet()) out.putHeader(e.getKey(), e.getValue());
        for (String a : audioUrls) out.addAudioUrl(a == null ? "" : a.trim());
        if (!subtitle.isEmpty()) out.setSubtitle(subtitle);
        if (!danmu.isEmpty()) out.setDanmu(danmu);
        if (!lyric.isEmpty()) out.setLyric(lyric);
        return !out.isEmpty();
    }

    /** 单线迷你 JSON（旧 HkDetailBridge.expandLineUrl 的打包口径，见类注）。 */
    public String toSingleLineJson(int line) {
        String u = urlAt(line);
        if (u == null) u = "";
        u = u.trim();
        if (u.isEmpty()) return sourceJson == null ? "" : sourceJson;
        try {
            List<String> kvs = new ArrayList<>();
            for (Map.Entry<String, String> e : headersAt(line).entrySet()) {
                String kk = e.getKey() == null ? "" : e.getKey().trim();
                String vv = e.getValue() == null ? "" : e.getValue().trim();
                if (!kk.isEmpty() && !vv.isEmpty()) kvs.add(kk + "@" + vv);
            }
            String audio = audioAt(line);
            if (audio != null) audio = audio.trim();
            if (audio == null) audio = "";
            String sub = subtitle == null ? "" : subtitle.trim();
            String dm = danmu == null ? "" : danmu.trim();
            String ly = lyric == null ? "" : lyric.trim();
            if (kvs.isEmpty() && sub.isEmpty() && audio.isEmpty() && dm.isEmpty() && ly.isEmpty()) return u;
            JSONObject mini = new JSONObject();
            JSONArray mu = new JSONArray();
            mu.put(u);
            mini.put("urls", mu);
            if (!kvs.isEmpty()) {
                JSONArray mh = new JSONArray();
                for (String kv : kvs) mh.put(kv);
                mini.put("headers", mh);
            }
            if (!sub.isEmpty()) mini.put("subtitle", sub);
            if (!dm.isEmpty()) mini.put("danmu", dm);
            if (!ly.isEmpty()) mini.put("lyric", ly);
            if (!audio.isEmpty()) {
                JSONArray ma = new JSONArray();
                ma.put(audio);
                mini.put("audioUrls", ma);
            }
            return mini.toString();
        } catch (Throwable e) {
            return u;
        }
    }

    /** PlayData 对象形 JSON（urls/names/headers/header/audioUrls/subtitle/danmu/lyric/url，非空字段才写）。 */
    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            if (!url.isEmpty()) o.put("url", url);
            if (!urls.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (String u : urls) arr.put(u == null ? "" : u);
                o.put("urls", arr);
            }
            if (!names.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (String n : names) arr.put(n == null ? "" : n);
                o.put("names", arr);
            }
            if (!header.isEmpty()) {
                JSONObject ho = new JSONObject();
                for (Map.Entry<String, String> e : header.entrySet()) ho.put(e.getKey(), e.getValue());
                o.put("header", ho);
            }
            if (!headers.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (Map<String, String> h : headers) {
                    JSONObject ho = new JSONObject();
                    if (h != null) {
                        for (Map.Entry<String, String> e : h.entrySet()) ho.put(e.getKey(), e.getValue());
                    }
                    arr.put(ho);
                }
                o.put("headers", arr);
            }
            if (!audioUrls.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (String a : audioUrls) arr.put(a == null ? "" : a);
                o.put("audioUrls", arr);
            }
            if (!subtitle.isEmpty()) o.put("subtitle", subtitle);
            if (!danmu.isEmpty()) o.put("danmu", danmu);
            if (!lyric.isEmpty()) o.put("lyric", lyric);
            return o.toString();
        } catch (Throwable e) {
            return sourceJson == null ? "" : sourceJson;
        }
    }

    /** 多线路 JSONArray 形（元素 {name, url}，与 HkSession.MultiLine.toJson 同形）。 */
    public String toMultiLineJson() {
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < urls.size(); i++) {
                JSONObject o = new JSONObject();
                o.put("name", nameAt(i));
                o.put("url", urls.get(i) == null ? "" : urls.get(i));
                arr.put(o);
            }
            return arr.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    // ================= 小工具 =================

    /** 冲突字符解码（官方 StringUtil.decodeConflictStr）：？？→?、＆＆→&、；；→;、，，→,。 */
    static String decodeConflict(String s) {
        if (s == null) return "";
        return s.replace("？？", "?").replace("＆＆", "&").replace("；；", ";").replace("，，", ",");
    }

    /** JSONObject 键表。 */
    private static List<String> jsonKeys(JSONObject o) {
        List<String> out = new ArrayList<>();
        if (o == null) return out;
        Iterator<String> it = o.keys();
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    /** JSON 值 → 字符串：null 归 ""，串原样（去首尾空白），余者 String.valueOf。 */
    private static String jsonString(Object v) {
        if (v == null || JSONObject.NULL.equals(v)) return "";
        if (v instanceof String) return ((String) v).trim();
        return String.valueOf(v).trim();
    }
}
