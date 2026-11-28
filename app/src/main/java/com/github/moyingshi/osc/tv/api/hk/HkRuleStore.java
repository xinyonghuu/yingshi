package com.github.moyingshi.osc.tv.api.hk;

import android.content.res.AssetManager;
import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;
import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.bean.Site;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 海阔小程序规则存储，第 1 步最小实现。
 * <p>
 * 注意：这不是完整版 HkRuleManager（q52，第 2 步才写：导入/导出/云口令/.hkzip、
 * 启用开关 SharedPreferences "hk_rule"）。本步只提供第 1 步闭环必需的：
 * 规则目录、文件名清洗、内置种子复制、按标题读写、内置站点列表。
 * <p>
 * 规则目录 = 应用私有 filesDir/hk/rules（对应 q52.x()）；文件名 = 清洗后标题 + ".json"。
 */
public final class HkRuleStore {

    /** assets 里的内置种子规则目录。 */
    private static final String SEED_ASSETS_DIR = "hk/rules";

    private HkRuleStore() {
    }

    /** q52.x()：规则目录（应用私有目录）。 */
    public static File rulesDir() {
        File dir = new File(App.get().getFilesDir(), "hk/rules");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * q52.P(title)：标题 → 文件名（清洗规则未从反编译确认，本步保守做法：
     * 非字母/数字/中文/._- 的字符换成下划线；空标题用 "rule"）。
     */
    public static String fileNameFor(String title) {
        String clean = title == null ? "" : title.trim().replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5._-]+", "_");
        if (TextUtils.isEmpty(clean)) clean = "rule";
        return clean + ".json";
    }

    /** q52.w(title)：规则文件。 */
    public static File ruleFile(String title) {
        return new File(rulesDir(), fileNameFor(title));
    }

    /**
     * q52.w(title)：规则私有数据目录（require.json / libs/ 的落点，第 2 步）。
     * APK 是 {@code <规则目录>/data/<P(title)>}，P(title) 不带 ".json"。
     */
    public static File ruleDataDir(String title) {
        String name = fileNameFor(title);
        if (name.endsWith(".json")) name = name.substring(0, name.length() - ".json".length());
        return new File(rulesDir(), "data/" + name);
    }

    /** q52.A(title)：读规则 JSON → HkRule；不存在返回 null。 */
    public static HkRule load(String title) {
        if (TextUtils.isEmpty(title)) return null;
        File file = ruleFile(title);
        if (!file.exists()) return null;
        try {
            String json = readFile(file);
            return HkRule.fromJson(json);
        } catch (Throwable e) {
            SpiderDebug.log("hk", "load rule %s failed: %s", title, e.toString());
            return null;
        }
    }

    /** q52.B()：列出已安装规则。 */
    public static List<HkRule> list() {
        List<HkRule> rules = new ArrayList<>();
        File dir = rulesDir();
        File[] files = dir.listFiles();
        if (files == null) return rules;
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".json")) continue;
            try {
                HkRule rule = HkRule.fromJson(readFile(f));
                if (rule != null) rules.add(rule);
            } catch (Throwable ignored) {
            }
        }
        return rules;
    }

    /** 写规则 JSON 到规则目录。 */
    public static void save(HkRule rule) throws IOException {
        if (rule == null || TextUtils.isEmpty(rule.title())) return;
        File file = ruleFile(rule.title());
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(rule.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /**
     * 内置种子：把 assets/hk/rules/*.json 同步到规则目录。
     * <p>
     * 目标文件不存在 → 直接复制；已存在但内容与种子不一致 → 用种子覆盖刷新。
     * 第 1 步没有应用内改规则的入口，文件与种子不一致只可能来自应用更新换了新
     * 种子，必须刷新——否则旧规则（比如已死的视频地址）永远播不了。
     * 内容相同则跳过，不动文件（mtime 不变，HkEngine 的 mtime 缓存也不受影响）。
     * <p>
     * 目标文件名按规则标题生成（fileNameFor(title)），与 load(title) 的查找一致；
     * assets 文件名本身只是打包用的 ASCII 别名（避免中文文件名进仓库）。
     */
    public static void ensureSeeded() {
        // $.require 接通补丁（第 2 步第 4 件补件）：顺带把内置 hikerPop 种子写进
        // filesDir/libs/hikerPop.js（HkHikerPopBridge.ensureSeeded，自带 try/catch
        // 不抛错）。种子同步已在首次 getSites 时跑，规则求值一定在它之后，时序没问题。
        HkHikerPopBridge.ensureSeeded();
        try {
            AssetManager assets = App.get().getAssets();
            String[] seeds = assets.list(SEED_ASSETS_DIR);
            if (seeds == null) return;
            for (String name : seeds) {
                if (!name.endsWith(".json")) continue;
                try (InputStream in = assets.open(SEED_ASSETS_DIR + "/" + name)) {
                    String json = readStream(in);
                    HkRule seed = HkRule.fromJson(json);
                    File target = seed == null || TextUtils.isEmpty(seed.title())
                            ? new File(rulesDir(), name) : ruleFile(seed.title());
                    boolean existed = target.exists();
                    if (existed) {
                        String current = null;
                        try {
                            current = readFile(target);
                        } catch (Throwable ignored) {
                            // 读不出旧文件就按不一致处理，下面覆盖写；写失败再由外层 catch 记日志。
                        }
                        if (json.equals(current)) continue;
                    }
                    try (OutputStream out = new FileOutputStream(target)) {
                        out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    if (existed) {
                        SpiderDebug.log("hk", "seed rule refreshed: %s", target.getName());
                    } else {
                        SpiderDebug.log("hk", "seed rule copied: %s -> %s", name, target.getName());
                    }
                } catch (Throwable e) {
                    SpiderDebug.log("hk", "seed rule %s failed: %s", name, e.toString());
                }
            }
        } catch (Throwable e) {
            SpiderDebug.log("hk", "ensureSeeded failed: %s", e.toString());
        }
    }

    private static String readStream(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString(java.nio.charset.StandardCharsets.UTF_8.name());
    }

    /** 规则文件原文（给 HkListActivity 的 "data" extra 用）。 */
    public static String ruleJson(String title) {
        if (TextUtils.isEmpty(title)) return "";
        File file = ruleFile(title);
        if (!file.exists()) return "";
        try {
            return readFile(file);
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 内置站点：供 LocalSource.getSites() 追加。站点 key/api 均为 "hk://&lt;标题&gt;"，
     * 点开后由 SiteDialog 拦截进 HkListActivity（不走正常 setSite）。
     * <p>
     * 第 2 步：被禁用的规则（SharedPreferences "hk_rule" 的 enabled_&lt;标题&gt; 为 false）
     * 不进列表；HkRuleManager 改开关/增删规则时会调 LocalSource.invalidate()，
     * 下次打开源列表即按新状态重算。
     */
    public static List<Site> builtinSites() {
        ensureSeeded();
        List<Site> sites = new ArrayList<>();
        for (HkRule rule : list()) {
            if (!HkRuleManager.get().isEnabled(rule.title())) continue;
            Site site = new Site();
            site.setKey("hk://" + rule.title());
            site.setName(rule.title());
            site.setApi("hk://" + rule.title());
            site.setExt("");
            site.setType(3);
            site.setSearchable(0);
            sites.add(site);
        }
        return sites;
    }

    private static String readFile(File file) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        }
    }
}
