package com.github.moyingshi.osc.tv.api.hk;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

/**
 * 海阔小程序列表卡片模型，第 1 步。
 * <p>
 * 等价于 APK 的混淆类 w12（字段表见 REPLICATION.md §6.2，1:1 建模；
 * 字段 JSON 名就是规则 JS 里 d.push({...}) 的键名）。
 */
public class HkItem {

    private static final Gson GSON = new Gson();

    @SerializedName("title")
    private String title;

    @SerializedName("url")
    private String url;

    @SerializedName("pic")
    private String pic;

    @SerializedName("desc")
    private String desc;

    @SerializedName("sel")
    private boolean sel;

    @SerializedName("colType")
    private String colType = "";

    @SerializedName("extra")
    private String extra = "";

    @SerializedName("content")
    private String content = "";

    @SerializedName("line")
    private String line = "";

    @SerializedName("findRule")
    private String findRule = "";

    @SerializedName("group")
    private String group = "";

    @SerializedName("pages")
    private String pages = "";

    @SerializedName("preRule")
    private String preRule = "";

    @SerializedName("rule")
    private String rule = "";

    public HkItem() {
    }

    public HkItem(String title, String url, String pic, String desc) {
        this.title = title;
        this.url = url;
        this.pic = pic;
        this.desc = desc;
    }

    /**
     * 从 Rhino 作用域里的 JS 卡片对象转 HkItem（setResult 收到 NativeObject 时用）。
     * JS 侧的键名与本类字段名一致；extra 可能是对象，先转 JSON 字符串。
     */
    public static HkItem fromJs(Scriptable obj) {
        if (obj == null) return null;
        HkItem item = new HkItem();
        item.title = jsString(obj, "title");
        item.url = jsString(obj, "url");
        // 规则里图片键可能是 pic 或 pic_url，两者都认。
        item.pic = jsString(obj, "pic");
        if (item.pic.isEmpty()) item.pic = jsString(obj, "pic_url");
        item.desc = jsString(obj, "desc");
        item.colType = jsString(obj, "colType");
        if (item.colType.isEmpty()) item.colType = jsString(obj, "col_type");
        item.content = jsString(obj, "content");
        item.line = jsString(obj, "line");
        item.findRule = jsString(obj, "findRule");
        if (item.findRule.isEmpty()) item.findRule = jsString(obj, "find_rule");
        item.group = jsString(obj, "group");
        item.pages = jsString(obj, "pages");
        item.preRule = jsString(obj, "preRule");
        item.rule = jsString(obj, "rule");
        Object extra = ScriptableObject.getProperty(obj, "extra");
        if (extra != null && extra != Scriptable.NOT_FOUND && !(extra instanceof Undefined)) {
            if (extra instanceof CharSequence) {
                item.extra = extra.toString();
            } else if (extra instanceof Scriptable) {
                // JS 对象/数组递归转 Java 结构再由 Gson 输出 JSON（Context.toString
                // 在对象上只会得到 "[object Object]"，不能用）。
                try {
                    item.extra = GSON.toJson(jsToJava(extra));
                } catch (Exception ignored) {
                    item.extra = "";
                }
            }
        }
        Object sel = ScriptableObject.getProperty(obj, "sel");
        if (sel instanceof Boolean) item.sel = (Boolean) sel;
        return item;
    }

    private static String jsString(Scriptable obj, String name) {
        Object v = ScriptableObject.getProperty(obj, name);
        if (v == null || v == Scriptable.NOT_FOUND || v instanceof Undefined) return "";
        return Context.toString(v);
    }

    /** JS 值递归转 Java 结构（NativeArray → List，普通对象 → Map，函数跳过）。 */
    static Object jsToJava(Object value) {
        if (value == null || value == Scriptable.NOT_FOUND || value instanceof Undefined) return null;
        if (value instanceof Scriptable) {
            Scriptable s = (Scriptable) value;
            if (s instanceof org.mozilla.javascript.NativeArray) {
                org.mozilla.javascript.NativeArray arr = (org.mozilla.javascript.NativeArray) s;
                java.util.List<Object> list = new java.util.ArrayList<>();
                for (int i = 0; i < arr.getLength(); i++) list.add(jsToJava(arr.get(i, arr)));
                return list;
            }
            if (s instanceof org.mozilla.javascript.NativeFunction) return null;
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            for (Object id : s.getIds()) {
                if (!(id instanceof String)) continue;
                Object v = jsToJava(ScriptableObject.getProperty(s, (String) id));
                if (v != null) map.put((String) id, v);
            }
            return map;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof CharSequence) return value;
        return Context.toString(value);
    }

    /** w12.e(key)：解析 extra JSON 取字段（MARK / cls 都存在这里）。 */
    public String extra(String key) {
        if (extra == null || extra.isEmpty() || key == null) return "";
        try {
            JsonObject o = JsonParser.parseString(extra).getAsJsonObject();
            if (!o.has(key)) return "";
            return o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : o.get(key).toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** w12.x()：置 sel = true。 */
    public void markSelected() {
        sel = true;
    }

    public String getTitle() {
        return title == null ? "" : title;
    }

    public String getUrl() {
        return url == null ? "" : url;
    }

    public String getPic() {
        return pic == null ? "" : pic;
    }

    public String getDesc() {
        return desc == null ? "" : desc;
    }

    public boolean isSel() {
        return sel;
    }

    public String getColType() {
        return colType == null ? "" : colType;
    }

    public String getExtra() {
        return extra == null ? "" : extra;
    }

    public String getContent() {
        return content == null ? "" : content;
    }

    public String getLine() {
        return line == null ? "" : line;
    }

    public String getFindRule() {
        return findRule == null ? "" : findRule;
    }

    public String getGroup() {
        return group == null ? "" : group;
    }

    public String getPages() {
        return pages == null ? "" : pages;
    }

    public String getPreRule() {
        return preRule == null ? "" : preRule;
    }

    public String getRule() {
        return rule == null ? "" : rule;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setPic(String pic) {
        this.pic = pic;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    public void setColType(String colType) {
        this.colType = colType;
    }

    public void setExtra(String extra) {
        this.extra = extra;
    }

    public void setFindRule(String findRule) {
        this.findRule = findRule;
    }

    @Override
    public String toString() {
        return "HkItem{title='" + getTitle() + "', url='" + getUrl() + "', pic='" + getPic() + "', desc='" + getDesc() + "'}";
    }
}
