package com.github.moyingshi.osc.tv.api.hk;

import android.text.TextUtils;

import com.github.moyingshi.osc.tv.utils.Notify;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

import java.lang.reflect.Type;

/**
 * 海阔小程序规则模型，第 1 步。
 * <p>
 * 等价于 APK 的混淆类 o52（字段表见 REPLICATION.md §5，1:1 建模）。
 * 复刻源是反编译规格书，不是被删的 b056322b 引擎。
 * <p>
 * 命名用可读名；方法签名照规格书 §13.2（x()/A()/G()/E()/b()/b0() 的行为，
 * 在 yingshi 风格下写作 title()/url()/userAgent()/hasSearch()/derive()/validate()）。
 */
public class HkRule {

    private static final Gson GSON = new GsonBuilder().create();

    /** 内置 mobile UA（o52.G() 的 mobile 分支原文）。 */
    public static final String UA_MOBILE = "Mozilla/5.0 (Linux; Android 15; 2407FRK8EC Build/AP3A.240617.008; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.127 Mobile Safari/537.36";
    /** 内置 PC UA（o52.G() 的 pc 分支原文）。 */
    public static final String UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    @SerializedName("title")
    private String title;

    @SerializedName("author")
    private String author;

    @SerializedName("version")
    @JsonAdapter(VersionAdapter.class)
    private String version;

    @SerializedName("type")
    private String type;

    @SerializedName("group")
    private String group;

    @SerializedName("ua")
    private String ua;

    @SerializedName("url")
    private String url;

    @SerializedName("class_name")
    private String className;

    @SerializedName("class_url")
    private String classUrl;

    @SerializedName("area_name")
    private String areaName;

    @SerializedName("area_url")
    private String areaUrl;

    @SerializedName("year_name")
    private String yearName;

    @SerializedName("year_url")
    private String yearUrl;

    @SerializedName("sort_name")
    private String sortName;

    @SerializedName("sort_url")
    private String sortUrl;

    @SerializedName("col_type")
    private String colType;

    @SerializedName("find_rule")
    private String findRule;

    @SerializedName("search_url")
    private String searchUrl;

    @SerializedName("searchFind")
    private String searchFind;

    @SerializedName("detail_find_rule")
    private String detailFindRule;

    @SerializedName("sdetail_find_rule")
    private String sdetailFindRule;

    @SerializedName("detail_col_type")
    private String detailColType;

    @SerializedName("sdetail_col_type")
    private String sdetailColType;

    @SerializedName("preRule")
    private String preRule;

    @SerializedName("pages")
    private String pages;

    @SerializedName("last_chapter_rule")
    private String lastChapterRule;

    @SerializedName("icon")
    private String icon;

    @SerializedName("chapter_find")
    private String chapterFind;

    @SerializedName("movie_find")
    private String movieFind;

    @SerializedName("firstHeader")
    private String firstHeader;

    @SerializedName("params")
    private String params;

    @SerializedName("pinYin")
    private String pinYin;

    @SerializedName("proxy")
    private String proxy;

    @SerializedName("titleColor")
    private String titleColor;

    @SerializedName("storage")
    private String storage;

    @SerializedName("displayName")
    private String displayName;

    @SerializedName("weight")
    private int weight;

    /** 运行时状态，不序列化（o52 的 transient L/M）。 */
    public transient String L;
    public transient String M;

    /** version 字段的三类型 TypeAdapter：容忍 bool / number / string。 */
    public static class VersionAdapter implements JsonSerializer<String>, JsonDeserializer<String> {
        @Override
        public String deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) return null;
            if (!json.isJsonPrimitive()) return json.toString();
            JsonPrimitive p = json.getAsJsonPrimitive();
            if (p.isBoolean()) return String.valueOf(p.getAsBoolean());
            if (p.isNumber()) return p.getAsNumber().toString();
            return p.getAsString();
        }

        @Override
        public JsonElement serialize(String src, Type typeOfSrc, JsonSerializationContext context) {
            return src == null ? null : new JsonPrimitive(src);
        }
    }

    public static HkRule fromJson(String json) {
        if (TextUtils.isEmpty(json)) return null;
        try {
            return GSON.fromJson(json, HkRule.class);
        } catch (Exception e) {
            return null;
        }
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /** o52.x()：标题（清洗后；空返回 ""）。 */
    public String title() {
        return title == null ? "" : title.trim();
    }

    /** o52.A()：首页 URL，空返回 ""。 */
    public String url() {
        return url == null ? "" : url;
    }

    /** o52.G()：UA 解析（空 → 内置 mobile UA；"mobile"/"pc" 取对应内置值；其余原样）。 */
    public String userAgent() {
        if ("mobile".equalsIgnoreCase(ua)) return UA_MOBILE;
        if ("pc".equalsIgnoreCase(ua)) return UA_PC;
        return TextUtils.isEmpty(ua) ? UA_MOBILE : ua.trim();
    }

    /** o52.E()：是否有搜索（search_url 与 searchFind 均非空）。 */
    public boolean hasSearch() {
        return !TextUtils.isEmpty(searchUrl) && !TextUtils.isEmpty(searchFind);
    }

    public String author() {
        return author == null ? "" : author;
    }

    public String version() {
        return version == null ? "" : version;
    }

    public String type() {
        return type == null ? "" : type;
    }

    public String group() {
        return group == null ? "" : group;
    }

    public String className() {
        return className == null ? "" : className;
    }

    public String classUrl() {
        return classUrl == null ? "" : classUrl;
    }

    public String areaName() {
        return areaName == null ? "" : areaName;
    }

    public String areaUrl() {
        return areaUrl == null ? "" : areaUrl;
    }

    public String yearName() {
        return yearName == null ? "" : yearName;
    }

    public String yearUrl() {
        return yearUrl == null ? "" : yearUrl;
    }

    public String sortName() {
        return sortName == null ? "" : sortName;
    }

    public String sortUrl() {
        return sortUrl == null ? "" : sortUrl;
    }

    public String colType() {
        return colType == null ? "" : colType;
    }

    public String findRule() {
        return findRule == null ? "" : findRule;
    }

    public String searchUrl() {
        return searchUrl == null ? "" : searchUrl;
    }

    public String searchFind() {
        return searchFind == null ? "" : searchFind;
    }

    public String detailFindRule() {
        return detailFindRule == null ? "" : detailFindRule;
    }

    public String sdetailFindRule() {
        return sdetailFindRule == null ? "" : sdetailFindRule;
    }

    public String detailColType() {
        return detailColType == null ? "" : detailColType;
    }

    public String sdetailColType() {
        return sdetailColType == null ? "" : sdetailColType;
    }

    public String preRule() {
        return preRule == null ? "" : preRule;
    }

    public String pages() {
        return pages == null ? "" : pages;
    }

    public String lastChapterRule() {
        return lastChapterRule == null ? "" : lastChapterRule;
    }

    public String icon() {
        return icon == null ? "" : icon;
    }

    public String chapterFind() {
        return chapterFind == null ? "" : chapterFind;
    }

    public String movieFind() {
        return movieFind == null ? "" : movieFind;
    }

    public String firstHeader() {
        return firstHeader == null ? "" : firstHeader;
    }

    public String params() {
        return params == null ? "" : params;
    }

    public String pinYin() {
        return pinYin == null ? "" : pinYin;
    }

    public String proxy() {
        return proxy == null ? "" : proxy;
    }

    public String titleColor() {
        return titleColor == null ? "" : titleColor;
    }

    public String storage() {
        return storage == null ? "" : storage;
    }

    public String displayName() {
        return displayName == null ? "" : displayName;
    }

    public int weight() {
        return weight;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setFindRule(String findRule) {
        this.findRule = findRule;
    }

    public void setColType(String colType) {
        this.colType = colType;
    }

    /**
     * o52.b(url, findRule, colType)：派生子规则副本（子页面自我递归用）。
     * 其余字段全部照抄，只换 url / find_rule / col_type 三项。
     */
    public HkRule derive(String url, String findRule, String colType) {
        HkRule r = new HkRule();
        r.title = this.title;
        r.author = this.author;
        r.version = this.version;
        r.type = this.type;
        r.group = this.group;
        r.ua = this.ua;
        r.url = url;
        r.className = this.className;
        r.classUrl = this.classUrl;
        r.areaName = this.areaName;
        r.areaUrl = this.areaUrl;
        r.yearName = this.yearName;
        r.yearUrl = this.yearUrl;
        r.sortName = this.sortName;
        r.sortUrl = this.sortUrl;
        r.colType = colType;
        r.findRule = findRule;
        r.searchUrl = this.searchUrl;
        r.searchFind = this.searchFind;
        r.detailFindRule = this.detailFindRule;
        r.sdetailFindRule = this.sdetailFindRule;
        r.detailColType = this.detailColType;
        r.sdetailColType = this.sdetailColType;
        r.preRule = this.preRule;
        r.pages = this.pages;
        r.lastChapterRule = this.lastChapterRule;
        r.icon = this.icon;
        r.chapterFind = this.chapterFind;
        r.movieFind = this.movieFind;
        r.firstHeader = this.firstHeader;
        r.params = this.params;
        r.pinYin = this.pinYin;
        r.proxy = this.proxy;
        r.titleColor = this.titleColor;
        r.storage = this.storage;
        r.displayName = this.displayName;
        r.weight = this.weight;
        return r;
    }

    /**
     * o52.b0()：校验，不合法时 Toast 提示。
     * 注意：原文里 url 为空时还看 k62.f(find_rule) 的结果，该判定未从反编译确认；
     * 本步保守做法：url 为空且 find_rule 不是 js: 时才报“规则缺少 url”。
     */
    public void validate() {
        if (TextUtils.isEmpty(title())) {
            Notify.show("规则缺少 title");
            return;
        }
        if (TextUtils.isEmpty(url()) && !findRule().trim().startsWith("js:")) {
            Notify.show("规则缺少 url");
        } else if (TextUtils.isEmpty(findRule())) {
            Notify.show("规则缺少 find_rule");
        }
    }
}
