package com.github.moyingshi.osc.tv.api.hk;

import android.content.Context;
import android.content.SharedPreferences;

import com.github.moyingshi.osc.tv.App;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 海阔小程序 KV 持久化（第 2 步 extras 第 7 件）。
 * <p>
 * 规格书 REPLICATION.md §4.12：{@code putMyVar/getMyVar}、
 * {@code setItem/getItem}、{@code setPublicItem/getPublicItem} 是规则级/全局级 KV，
 * 持久化；{@code putVar/getVar} 是作用域内临时变量，不持久化（本类不碰它）。
 * 规格书 §14 第 8 项说落盘位置（SharedPreferences 还是文件）需真机核实——
 * 本实现选 SharedPreferences（最稳妥、无新文件格式、无需处理规则 JSON），
 * 落盘位置待真机核实，见任务文档 §7。
 * <p>
 * 三个命名空间分开存（三个 SharedPreferences 文件），键不混用：
 * <ul>
 * <li>items（{@code setItem/getItem}）：全局，不加标题前缀；</li>
 * <li>publicItems（{@code setPublicItem/getPublicItem}）：全局，不加标题前缀；</li>
 * <li>myVars（{@code putMyVar/getMyVar}）：按 plugin.js §2.2 的规则标题隔离，
 * 键为 {@code "<标题>@<key>"}，与 {@code initConfig} 同一机制。</li>
 * </ul>
 * 内存里三张共享 static map 是唯一工作副本（构造时/首次访问时从磁盘读进，
 * 写操作写穿：先改内存再 {@code apply()} 异步落盘）。共享而不是每运行时一份，
 * 是因为同一进程里多个规则的运行时共存（宿主列表 + 子页面递归），全局的
 * items/publicItems 与标题隔离的 myVars 本来就是跨运行时共享的语义；
 * 每运行时一份快照会在另一规则写入后读到陈旧值。{@code vars}（临时变量）
 * 不进本类，仍是每运行时一份。
 * <p>
 * myVars 的值可能是任意 JS 值：字符串原样存；数字/布尔存 {@code toString}；
 * 对象/数组存 JSON 字符串（经 {@link HkItem#jsToJava} 转 Java 结构再 Gson 序列化，
 * 与卡片 extra 的序列化同一路）。读回时一律是 String——规则里 myVars
 * 本来就是字符串级存取（plugin.js 的 storage0 取值时对 {@code {…}/[…]} 形
 * 字符串自动 {@code JSON.parse}，所以 JSON 形值经 storage0 读回仍是对象；
 * 裸 {@code getMyVar} 读回 JSON 字符串，与“存时就是字符串”的行为一致）。
 * 不碰 {@link HkRuleStore} 的规则文件读写，不把 KV 写进规则 JSON 文件。
 */
public final class HkKvStore {

    private static final String PREFS_ITEMS = "hk_kv_items";
    private static final String PREFS_MY_VARS = "hk_kv_myvars";
    private static final String PREFS_PUBLIC_ITEMS = "hk_kv_public_items";

    /** 内存工作副本（构造/首次访问时从磁盘读进；写操作写穿）。 */
    private static final Map<String, String> ITEMS = new ConcurrentHashMap<>();
    private static final Map<String, Object> MY_VARS = new ConcurrentHashMap<>();
    private static final Map<String, String> PUBLIC_ITEMS = new ConcurrentHashMap<>();

    /** 三个命名空间是否已从磁盘读进（只读一次；读失败时记空，不抛错）。 */
    private static volatile boolean itemsLoaded;
    private static volatile boolean myVarsLoaded;
    private static volatile boolean publicItemsLoaded;

    private static final Object LOAD_LOCK = new Object();

    private HkKvStore() {
    }

    // ---------- 内存工作副本（HkJsRuntime 的 dispatch 直接读写这三张表） ----------

    /** items 共享内存表（getItem/setItem 用）。 */
    public static Map<String, String> items() {
        ensureItemsLoaded();
        return ITEMS;
    }

    /** myVars 共享内存表（getMyVar/putMyVar 用；键已是 "<标题>@<key>" 形）。 */
    public static Map<String, Object> myVars() {
        ensureMyVarsLoaded();
        return MY_VARS;
    }

    /** publicItems 共享内存表（getPublicItem/setPublicItem 用）。 */
    public static Map<String, String> publicItems() {
        ensurePublicItemsLoaded();
        return PUBLIC_ITEMS;
    }

    // ---------- 写穿（只落盘；内存表由调用方先改——三张表就是 HkJsRuntime
    //  dispatch 直接读写的同一批对象，见类注释） ----------

    public static void putItem(String key, String value) {
        if (key == null) return;
        ensureItemsLoaded();
        prefs(PREFS_ITEMS).edit().putString(key, value == null ? "" : value).apply();
    }

    public static void removeItem(String key) {
        if (key == null) return;
        ensureItemsLoaded();
        prefs(PREFS_ITEMS).edit().remove(key).apply();
    }

    public static void clearItems() {
        ensureItemsLoaded();
        prefs(PREFS_ITEMS).edit().clear().apply();
    }

    public static void putMyVar(String prefixedKey, String value) {
        if (prefixedKey == null) return;
        ensureMyVarsLoaded();
        prefs(PREFS_MY_VARS).edit().putString(prefixedKey, value == null ? "" : value).apply();
    }

    public static void removeMyVar(String prefixedKey) {
        if (prefixedKey == null) return;
        ensureMyVarsLoaded();
        prefs(PREFS_MY_VARS).edit().remove(prefixedKey).apply();
    }

    /**
     * 清掉指定规则标题的全部 myVars（磁盘上键前缀 {@code "<标题>@"} 的那些）。
     * 无参 {@code clearMyVar()} 只清当前规则的，不动别的规则的变量。
     * 调用方同时从内存表里删掉同一批键（见 HkJsRuntime.dispatch）。
     */
    public static void clearMyVarsForTitle(String title) {
        ensureMyVarsLoaded();
        String prefix = (title == null ? "" : title) + "@";
        Set<String> diskKeys;
        try {
            Map<String, ?> all = prefs(PREFS_MY_VARS).getAll();
            diskKeys = all == null ? new HashSet<>() : new HashSet<>(all.keySet());
        } catch (Throwable ignored) {
            return;
        }
        SharedPreferences.Editor editor = prefs(PREFS_MY_VARS).edit();
        boolean any = false;
        for (String key : diskKeys) {
            if (key != null && key.startsWith(prefix)) {
                editor.remove(key);
                any = true;
            }
        }
        if (any) editor.apply();
    }

    /** 当前规则标题的 myVars 键（去掉 "<标题>@" 前缀后返回；只列本规则的）。 */
    public static List<String> listMyVarKeysForTitle(String title) {
        ensureMyVarsLoaded();
        String prefix = (title == null ? "" : title) + "@";
        List<String> keys = new ArrayList<>();
        for (String key : MY_VARS.keySet()) {
            if (key.startsWith(prefix)) keys.add(key.substring(prefix.length()));
        }
        return keys;
    }

    public static void putPublicItem(String key, String value) {
        if (key == null) return;
        ensurePublicItemsLoaded();
        prefs(PREFS_PUBLIC_ITEMS).edit().putString(key, value == null ? "" : value).apply();
    }

    public static void removePublicItem(String key) {
        if (key == null) return;
        ensurePublicItemsLoaded();
        prefs(PREFS_PUBLIC_ITEMS).edit().remove(key).apply();
    }

    public static void clearPublicItems() {
        ensurePublicItemsLoaded();
        prefs(PREFS_PUBLIC_ITEMS).edit().clear().apply();
    }

    // ---------- 首次从磁盘读进 ----------

    private static void ensureItemsLoaded() {
        if (itemsLoaded) return;
        synchronized (LOAD_LOCK) {
            if (itemsLoaded) return;
            loadInto(ITEMS, PREFS_ITEMS);
            itemsLoaded = true;
        }
    }

    private static void ensureMyVarsLoaded() {
        if (myVarsLoaded) return;
        synchronized (LOAD_LOCK) {
            if (myVarsLoaded) return;
            loadInto(MY_VARS, PREFS_MY_VARS);
            myVarsLoaded = true;
        }
    }

    private static void ensurePublicItemsLoaded() {
        if (publicItemsLoaded) return;
        synchronized (LOAD_LOCK) {
            if (publicItemsLoaded) return;
            loadInto(PUBLIC_ITEMS, PREFS_PUBLIC_ITEMS);
            publicItemsLoaded = true;
        }
    }

    /** 读一个 prefs 文件的全部字符串键值进内存表（只存 String；非 String 值跳过）。 */
    private static void loadInto(Map<String, ? super String> map, String prefsName) {
        try {
            Map<String, ?> all = prefs(prefsName).getAll();
            if (all == null) return;
            Map<String, String> copy = new LinkedHashMap<>();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                if (e.getKey() == null || !(e.getValue() instanceof String)) continue;
                copy.put(e.getKey(), (String) e.getValue());
            }
            map.putAll(copy);
        } catch (Throwable ignored) {
            // 磁盘读失败不阻断运行：内存表留空，本次写操作仍会写穿落盘。
        }
    }

    private static SharedPreferences prefs(String name) {
        return App.get().getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    /** 规则标题隔离：myVars 的存取键（plugin.js §2.2：MY_RULE.title + "@" + key）。 */
    public static String myVarKey(String title, String key) {
        return (title == null ? "" : title) + "@" + (key == null ? "" : key);
    }
}
