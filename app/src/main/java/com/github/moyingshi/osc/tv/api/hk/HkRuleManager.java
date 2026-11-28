package com.github.moyingshi.osc.tv.api.hk;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.SpiderDebug;
import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.utils.LocalSource;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 海阔小程序规则管理器，第 2 步第 1 件。
 * <p>
 * 等价于 APK 的混淆类 q52（单例 {@code u()}，本类写作 {@code get()}）。
 * 规则目录/文件名/读写全部委托给 {@link HkRuleStore}（第 1 步已有的实现），
 * 本类只加第 1 步没有的管理能力：导入导出、云口令分流、.hkzip 校验、
 * 启用开关 SharedPreferences "hk_rule"。
 * <p>
 * 行为依据：REPLICATION.md §6.1 / §13.2，q52.java 逐行核对：
 * 云口令正则 {@code ^云([1-9]|10)oooole(/.*)$}、云 1=pastebin、云 2=textdb
 * （/apidb/ 路径）、云 5=cmd.im、云 6=pasteme（/xxxxxx/ 路径）、云 7=note.ms、
 * 云 9=txtpad、云 10=hastebin；.hkzip 必须含 rule.json，错误文案照原文；
 * 启用开关 key {@code enabled_<标题>}，默认启用。
 * <p>
 * 云口令载荷解码（最大努力，未全部验证）：取回的文本按梯子试——(a) 直接是规则
 * JSON（含 {@code ￥home_rule￥{...}} 包裹形，q52.U()）；(b) {@code base64://}
 * 形与整段 Base64（q52.V()/e()，含 URL-safe 变体）；(c) AES 解密（CryptoJS
 * 兼容：Base64 解出 "Salted__" + 盐，EVP_BytesToKey/MD5 派生 key/IV，
 * AES-256-CBC，key "hk6666666109"，即 getPrivateJS 用的那个 key，见 §4.13）。
 * (c) 只是“试一把”：.hiker 二进制载荷的真实解密算法未从反编译确认（§14.1），
 * 解不开时明确报错并记日志，不假装已验证。
 */
public final class HkRuleManager {

    /** q52.c：云口令全串。 */
    private static final Pattern CLOUD_FULL = Pattern.compile("^云([1-9]|10)oooole(/.*)$");
    /** q52.d：在一段文字里找云口令。 */
    private static final Pattern CLOUD_FIND = Pattern.compile("云([1-9]|10)oooole/?\\S+");

    /** q52.n()：cmd.im 的 test_box 提取。 */
    private static final Pattern TEST_BOX = Pattern.compile("class=\"test_box\"[^>]*>(.*?)</div>", Pattern.DOTALL);
    /** q52.p()：note.ms 的 textarea 提取。 */
    private static final Pattern TEXTAREA = Pattern.compile("<textarea[^>]*>(.*?)</textarea>", Pattern.DOTALL);

    /** 启用开关所在的 SharedPreferences（q52.G()）。 */
    private static final String PREFS_NAME = "hk_rule";
    private static final String ENABLED_PREFIX = "enabled_";

    /** getPrivateJS 用的硬编码 AES key（REPLICATION.md §4.13），仅作解码梯子 (c) 的尝试。 */
    private static final String AES_TRY_KEY = "hk6666666109";

    /** 云端取数统一 UA（q52.q() 各分支原文）。 */
    private static final String CLOUD_UA = "Mozilla/5.0";

    /** 云 2 导出（textdb）上传端点（参照原文常量）。 */
    private static final String TEXTDB_API_UPDATE = "https://api.textdb.online/update/";
    /** 云 5 导出（cmd.im）根地址（参照原文常量）。 */
    private static final String CMDIM_BASE = "https://cmd.im";

    private static volatile HkRuleManager sInstance;

    private HkRuleManager() {
    }

    /** q52.u()：单例。 */
    public static HkRuleManager get() {
        HkRuleManager instance = sInstance;
        if (instance == null) {
            synchronized (HkRuleManager.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new HkRuleManager();
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    // ---------- 委托给 HkRuleStore 的读写（q52.x/w/P/A/B） ----------

    /** q52.x()：规则目录。 */
    public File rulesDir() {
        return HkRuleStore.rulesDir();
    }

    /** q52.w(title)：规则文件。 */
    public File ruleFile(String title) {
        return HkRuleStore.ruleFile(title);
    }

    /** q52.P(title)：标题 → 文件名。 */
    public String fileNameFor(String title) {
        return HkRuleStore.fileNameFor(title);
    }

    /** q52.A(title)：读规则。 */
    public HkRule load(String title) {
        return HkRuleStore.load(title);
    }

    /** q52.B()：列出已安装规则。 */
    public List<HkRule> list() {
        return HkRuleStore.list();
    }

    // ---------- 启用开关（q52.G()） ----------

    private SharedPreferences prefs() {
        return App.get().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** q52.G(title)：是否启用，默认启用。 */
    public boolean isEnabled(String title) {
        if (TextUtils.isEmpty(title)) return true;
        try {
            return prefs().getBoolean(ENABLED_PREFIX + title, true);
        } catch (Throwable e) {
            return true;
        }
    }

    /** 写启用开关；改完清掉本地源站点缓存，下次打开源列表即按新开关重算。 */
    public void setEnabled(String title, boolean enabled) {
        if (TextUtils.isEmpty(title)) return;
        try {
            prefs().edit().putBoolean(ENABLED_PREFIX + title, enabled).apply();
        } catch (Throwable e) {
            SpiderDebug.log("hk", "setEnabled %s failed: %s", title, e.toString());
        }
        LocalSource.invalidate();
    }

    // ---------- 导入：总入口 ----------

    /**
     * 导入总入口（q52.M() 的单规则等价）。
     * <ol>
     * <li>含云口令 → {@link #resolveCloudPassword(String)}；</li>
     * <li>{@code rule://} 前缀 → Base64 解出 JSON；</li>
     * <li>否则按解码梯子试文本本身（JSON / Base64 / AES）；</li>
     * <li>都不是 → 报错。</li>
     * </ol>
     * 耗时操作（云口令要走网络），调用方必须放后台线程。
     */
    public HkRule importText(String text) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("内容为空");
        }
        String t = text.trim();
        Matcher cloud = CLOUD_FIND.matcher(t);
        if (cloud.find()) {
            // 传整段文本（不是只传匹配到的口令）：resolveCloudPassword 还要取口令后的
            // 第一段文本当 pasteme 附加口令（q52.K() 的 str3），只传 group() 会丢掉它。
            return resolveCloudPassword(t);
        }
        if (t.startsWith("rule://")) {
            String payload = decodePayload(t.substring("rule://".length()));
            if (payload == null) {
                throw new IllegalArgumentException("rule:// 口令不是有效的规则（Base64/AES 解码失败；.hiker 二进制载荷解密未确认）");
            }
            return importRuleJson(payload);
        }
        String payload = decodePayload(t);
        if (payload == null) {
            if (t.startsWith("[")) {
                throw new IllegalArgumentException("内容是规则数组，请用批量导入");
            }
            throw new IllegalArgumentException("内容不是规则 JSON（可能是口令已失效或云端内容已更换；.hiker 二进制载荷解密未确认）");
        }
        return importRuleJson(payload);
    }

    /** 解析规则 JSON 并保存（q52.L() + Q() 的保存部分），返回规则；标题为空或解析失败时抛错。 */
    public HkRule importRuleJson(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("规则内容为空");
        }
        HkRule rule = HkRule.fromJson(json.trim());
        if (rule == null) {
            throw new IllegalArgumentException("规则 JSON 解析失败");
        }
        if (TextUtils.isEmpty(rule.title())) {
            throw new IllegalArgumentException("规则缺少 title");
        }
        HkRuleStore.save(rule);
        clearRuleCache(rule.title());
        LocalSource.invalidate();
        SpiderDebug.log("hk", "imported rule: %s", rule.title());
        return rule;
    }

    // ---------- .hkzip 导入/导出 ----------

    /**
     * 导入 .hkzip（q52 的 hkzip 导入分支）。
     * 包内必须含 rule.json，否则按原文报错。require.json / libs.zip 如有则
     * 随规则落到规则数据目录（失败只记日志，不阻断导入）。
     */
    public HkRule importHkZip(File file) throws Exception {
        if (file == null || !file.exists()) {
            throw new IllegalArgumentException("hkzip 文件不存在");
        }
        ZipFile zip;
        try {
            zip = new ZipFile(file);
        } catch (ZipException e) {
            throw new IllegalArgumentException("不是有效的 hkzip 包或文件已损坏");
        } catch (IOException e) {
            throw new IllegalArgumentException("不是有效的 hkzip 包或文件已损坏");
        }
        try {
            ZipEntry ruleEntry = null;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                if ("rule.json".equalsIgnoreCase(baseName(entry.getName()))) {
                    ruleEntry = entry;
                    break;
                }
            }
            if (ruleEntry == null) {
                throw new IllegalArgumentException("hkzip 中没有找到 rule.json");
            }
            String json = readZipEntry(zip, ruleEntry);
            HkRule rule = importRuleJson(json);
            extractExtras(zip, rule.title());
            return rule;
        } finally {
            try {
                zip.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 导出 .hkzip（q52.k()）：rule.json 必含；数据目录里的 require.json / libs/ 如有则一并打包。 */
    public void exportHkZip(HkRule rule, File out) throws Exception {
        if (rule == null || TextUtils.isEmpty(rule.title())) {
            throw new IllegalArgumentException("规则不存在");
        }
        if (out == null) {
            throw new IllegalArgumentException("导出目标为空");
        }
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            putEntry(zos, "rule.json", rule.toJson().getBytes(StandardCharsets.UTF_8));
            File dataDir = HkRuleStore.ruleDataDir(rule.title());
            File require = new File(dataDir, "require.json");
            if (require.isFile()) {
                putEntry(zos, "require.json", readFile(require));
            }
            File libs = new File(dataDir, "libs");
            File[] libFiles = libs.isDirectory() ? libs.listFiles() : null;
            if (libFiles != null && libFiles.length > 0) {
                ByteArrayOutputStream libsZip = new ByteArrayOutputStream();
                try (ZipOutputStream lz = new ZipOutputStream(libsZip)) {
                    for (File f : libFiles) {
                        if (f.isFile()) putEntry(lz, f.getName(), readFile(f));
                    }
                }
                putEntry(zos, "libs.zip", libsZip.toByteArray());
            }
        }
    }

    /**
     * 导出规则为云口令（textdb 通道）：rule.json → base64 → 上传 textdb.online
     * → 返回 云2oooole/apidb/{key}（与官方 App 的云2分享格式一致，官方 App 可导入）。
     * 移植自参照 exportAsCloudCode：文件改走 HkRuleStore 的 rulesDir/fileNameFor，
     * 客户端复用 {@link HkHttp#client()}（15s/30s，与原文自建 client 同超时），
     * 其余逐字。调用方：HkCloudClip.upload(2) 的活通道（见该类注）；UI 入口尚未接，
     * 后续可在管理页加「导出为云口令」。
     *
     * @return 生成的云口令
     */
    public String exportAsCloudCode(String title) throws Exception {
        File f = new File(HkRuleStore.rulesDir(), HkRuleStore.fileNameFor(title));
        if (!f.exists()) throw new IllegalArgumentException("规则不存在：" + title);
        byte[] bytes = readFile(f);
        String json = new String(bytes, StandardCharsets.UTF_8);
        String b64 = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String key = "xc" + System.currentTimeMillis() / 1000 + "_" + Math.abs(title.hashCode());

        FormBody body = new FormBody.Builder()
                .add("key", key)
                .add("value", b64)
                .build();
        Request req = new Request.Builder().url(TEXTDB_API_UPDATE).post(body)
                .header("User-Agent", "Mozilla/5.0").build();
        try (Response resp = HkHttp.client().newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null)
                throw new IllegalArgumentException("上传失败（HTTP " + resp.code() + "）");
            String respText = resp.body().string();
            try {
                JsonObject obj = JsonParser.parseString(respText).getAsJsonObject();
                if (obj.has("status") && obj.get("status").getAsInt() == 1) {
                    return "云2oooole/apidb/" + key;
                }
            } catch (Exception ignored) {
            }
            throw new IllegalArgumentException("上传失败：服务器返回异常");
        }
    }

    /**
     * 导出规则为云5短口令（cmd.im 通道）：POST rule.json → 302 跳转取短 ID
     * → 返回 云5oooole/{id}（短格式，与官方 App 的云5分享格式一致，官方 App 可导入）。
     * 移植自参照 exportAsCmdImCode：文件改走 HkRuleStore 的 rulesDir/fileNameFor，
     * 客户端复用 {@link HkHttp#client()} 加 followRedirects(false)，其余逐字。
     * 调用方：HkCloudClip.upload(5) 的活通道（见该类注）；UI 入口尚未接。
     *
     * @return 生成的云口令
     */
    public String exportAsCmdImCode(String title) throws Exception {
        File f = new File(HkRuleStore.rulesDir(), HkRuleStore.fileNameFor(title));
        if (!f.exists()) throw new IllegalArgumentException("规则不存在：" + title);
        byte[] bytes = readFile(f);
        String json = new String(bytes, StandardCharsets.UTF_8);

        OkHttpClient client = HkHttp.client().newBuilder()
                .followRedirects(false)
                .build();
        FormBody body = new FormBody.Builder().add("txt", json).build();
        Request req = new Request.Builder().url(CMDIM_BASE + "/").post(body)
                .header("Origin", CMDIM_BASE)
                .header("Referer", CMDIM_BASE + "/")
                .header("User-Agent", "Mozilla/5.0").build();
        try (Response resp = client.newCall(req).execute()) {
            String loc = resp.header("Location");
            if (loc == null || loc.isEmpty())
                throw new IllegalArgumentException("上传失败：未返回短链接（HTTP " + resp.code() + "）");
            // Location 形如 /8xwm
            String id = loc.substring(loc.lastIndexOf('/') + 1).split("[\\s?]")[0];
            if (id.isEmpty()) throw new IllegalArgumentException("上传失败：短链接格式异常");
            return "云5oooole/" + id;
        }
    }

    /** .hkzip 里的 require.json / libs.zip 解到规则数据目录（尽力而为，失败只记日志）。 */
    private void extractExtras(ZipFile zip, String title) {
        try {
            File dataDir = HkRuleStore.ruleDataDir(title);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String base = baseName(entry.getName());
                try {
                    if ("require.json".equalsIgnoreCase(base)) {
                        File target = new File(dataDir, "require.json");
                        writeFile(target, readZipEntryBytes(zip, entry));
                    } else if ("libs.zip".equalsIgnoreCase(base)) {
                        unzipTo(readZipEntryBytes(zip, entry), new File(dataDir, "libs"));
                    }
                } catch (Throwable e) {
                    SpiderDebug.log("hk", "hkzip extra %s failed: %s", base, e.toString());
                }
            }
        } catch (Throwable e) {
            SpiderDebug.log("hk", "hkzip extras failed: %s", e.toString());
        }
    }

    // ---------- 云口令 ----------

    /**
     * 云口令解析 + 取数 + 导入（q52.K() + q()）。
     * 返回已保存的规则；口令无效、云端为空、载荷解不开时抛 IllegalArgumentException。
     * 耗时操作（走网络），调用方必须放后台线程。
     */
    public HkRule resolveCloudPassword(String password) throws Exception {
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalArgumentException("口令为空");
        }
        Matcher find = CLOUD_FIND.matcher(password);
        if (!find.find()) {
            throw new IllegalArgumentException("不是有效的云口令（需云1~云10开头）");
        }
        // q52.J()：oooole 后缺 "/" 时补一个。
        String matched = normalizeCloudPassword(find.group());
        Matcher full = CLOUD_FULL.matcher(matched);
        if (!full.matches()) {
            throw new IllegalArgumentException("不是有效的云口令（需云1~云10开头）");
        }
        String num = full.group(1);
        String path = full.group(2);
        // q52.K()：口令后的第一段非空文本（不以 "小程序"/http 开头）是 pasteme 的附加口令。
        String extra = null;
        if (find.end() < password.length()) {
            String rest = password.substring(find.end()).trim();
            if (!rest.isEmpty() && !rest.startsWith("小程序")) {
                String token = rest.split("\\s+")[0];
                if (!token.isEmpty() && !token.startsWith("http")) extra = token;
            }
        }
        String fetched = fetchCloud(num, path, extra);
        if (fetched == null || fetched.trim().isEmpty()) {
            throw new IllegalArgumentException("云端返回内容为空");
        }
        String payload = decodePayload(fetched.trim());
        if (payload == null) {
            String head = fetched.trim();
            if (head.length() > 30) head = head.substring(0, 30) + "…";
            SpiderDebug.log("hk", "cloud payload undecodable (head=%s): .hiker binary decryption unconfirmed", head);
            throw new IllegalArgumentException("云端内容不是规则 JSON（开头为：" + head + "），可能是口令已失效或云端内容已更换；.hiker 二进制载荷解密未确认");
        }
        return importRuleJson(payload);
    }

    /** q52.J()：oooole 后不是 "/" 时补一个 "/"。 */
    private static String normalizeCloudPassword(String password) {
        int i = password.indexOf("oooole");
        if (i < 0) return password;
        int j = i + 6;
        if (j >= password.length() || password.charAt(j) == '/') return password;
        return password.substring(0, j) + "/" + password.substring(j);
    }

    /**
     * q52.q(path, num, extra)：按云编号/路径分流取数。
     * 返回云端原始文本（各服务的内容提取照原文：textdb 先 Base64 解、cmd.im 取
     * test_box、note.ms 取 textarea、pasteme 取 JSON 的 data 字段）；最终的
     * JSON/Base64/AES 解码由 {@link #decodePayload(String)} 统一做。
     */
    private String fetchCloud(String num, String path, String extra) throws Exception {
        if (path.startsWith("/apidb/")) {
            // 云 2：textdb。内容是 Base64 编码的规则（q52.e() 解）。
            String key = path.substring("/apidb/".length()).split("[\\s/]")[0];
            if (key.isEmpty()) throw new IllegalArgumentException("口令中 apidb key 为空");
            String body = httpGet("https://textdb.online/" + key, true, null);
            String decoded = base64DecodeToString(body.trim());
            if (decoded == null || decoded.isEmpty()) {
                throw new IllegalArgumentException("云端内容不是有效的 base64 规则");
            }
            return decoded;
        }
        if (path.startsWith("/xxxxxx/")) {
            // 云 6：pasteme。id 后可跟 "(@口令)" 做附加口令。取数端点形经
            // HkCloudClip 确认（api/getContent/{id} 取信封 data），第 2 步直取
            // 的猜测与之相同，改走分发表；失败仍返回 null 让调用方按原逻辑重试
            // "(@口令)" 形（分发表抛异常，包一层转 null）。
            String id = path.substring("/xxxxxx/".length()).split("\\s")[0];
            if (id.isEmpty()) throw new IllegalArgumentException("口令中缺少便签ID");
            String result = fetchCloudClip(id, 6);
            if (result == null && extra != null && !extra.isEmpty() && !id.contains("(@")) {
                result = fetchCloudClip(id + "(@" + extra + ")", 6);
            }
            if (result == null) {
                throw new IllegalArgumentException("云端未返回有效规则（便签不存在或密码错误）");
            }
            return result;
        }
        if ("5".equals(num)) {
            // 云 5：cmd.im。
            String id = path.substring(1).split("[\\s/]")[0];
            if (id.isEmpty()) throw new IllegalArgumentException("口令中缺少便签ID");
            String body = httpGet("https://cmd.im/" + id, true, null);
            String extracted = extractTestBox(body);
            if (extracted == null) {
                throw new IllegalArgumentException("云端未返回有效规则（便签不存在或内容格式不对）");
            }
            return extracted;
        }
        if ("1".equals(num)) {
            // 云 1：pastebin。端点形经 HkCloudClip 确认（get raw/{id}），
            // 第 2 步直取的猜测与之相同，改走分发表，行为不变。
            String id = (path.startsWith("/raw/") ? path.substring("/raw/".length()) : path.substring(1)).split("[\\s/]")[0];
            if (id.isEmpty()) throw new IllegalArgumentException("口令中缺少 pasteId");
            String body = HkCloudClip.download("https://pastebin.com/raw/" + id, 1);
            if (body == null || body.trim().isEmpty()) {
                throw new IllegalArgumentException("云端未返回有效规则");
            }
            return body;
        }
        if ("7".equals(num)) {
            // 云 7：note.ms。先带 UA 取 textarea，失败再不带 UA 直取原文（q52.r() 两次尝试）。
            // 两次都因网络失败才抛最后一次的 IOException（原文：e2 != null 时 throw e2），
            // 否则按“云端未返回有效规则”报。
            String id = path.substring(1).split("[\\s/]")[0];
            if (id.isEmpty()) throw new IllegalArgumentException("口令中缺少便签ID");
            String url = "https://note.ms/" + id;
            IOException noteError = null;
            String result = null;
            try {
                result = fetchNoteMs(url, true);
            } catch (IOException e) {
                noteError = e;
            }
            if (result == null || result.isEmpty()) {
                try {
                    result = fetchNoteMs(url, false);
                } catch (IOException e) {
                    noteError = e;
                }
            }
            if (result != null && !result.isEmpty()) return result;
            if (noteError != null) throw noteError;
            throw new IllegalArgumentException("云端未返回有效规则");
        }
        if ("9".equals(num)) {
            // 云 9：txtpad。端点形经 HkCloudClip 确认（get txtpad.cn/{id}），
            // 第 2 步直取的猜测与之相同，改走分发表，行为不变。
            String id = path.substring(1).split("[\\s/]")[0];
            if (id.isEmpty()) throw new IllegalArgumentException("口令中缺少便签ID");
            String body = HkCloudClip.download(id, 9);
            if (body == null || body.trim().isEmpty()) {
                throw new IllegalArgumentException("云端未返回有效规则");
            }
            return body;
        }
        if ("10".equals(num)) {
            // 云 10：hastebin。两个域名依次试（原文顺序：hassdtebin 先，hastebin 后）。
            String[] segments = path.split("/");
            String key = segments[segments.length - 1].split("\\s")[0];
            if (key.isEmpty()) throw new IllegalArgumentException("口令中缺少 key");
            String[] hosts = {"https://hassdtebin.com/raw/", "https://hastebin.com/raw/"};
            IOException lastError = null;
            int ioFailures = 0;
            for (String host : hosts) {
                try {
                    String body = httpGet(host + key, true, null);
                    if (body != null && !body.trim().isEmpty()) return body;
                } catch (IOException e) {
                    lastError = e;
                    ioFailures++;
                }
            }
            // 原文：两个域名都因网络失败（i == 2）才抛最后一次的 IOException，否则报“未返回有效规则”。
            if (lastError != null && ioFailures == 2) throw lastError;
            throw new IllegalArgumentException("云端未返回有效规则");
        }
        if (path.startsWith("/p/")) {
            throw new IllegalArgumentException("该口令走 netcut 通道，netcut 已停止开放接口，无法拉取。请让分享者用「导出为云口令」重新分享，或索取规则文件导入。");
        }
        throw new IllegalArgumentException("暂不支持该云口令通道（当前支持：云1、云2/apidb、云5、云6、云7、云9、云10）");
    }

    /**
     * 经 HkCloudClip 分发表取数：失败（网络/HTTP/无有效内容）返回 null，不抛异常，
     * 供云 6 的 "(@口令)" 重试逻辑用。参照确认的端点形见 HkCloudClip。
     */
    private String fetchCloudClip(String code, int clipNum) {
        try {
            String body = HkCloudClip.download(code, clipNum);
            if (body == null) return null;
            String data = body.trim();
            return data.isEmpty() ? null : data;
        } catch (Throwable e) {
            return null;
        }
    }

    /** q52.r()：note.ms 取数；withUa=true 时带 UA 并取 textarea，否则不带 UA 直取原文。网络失败时抛 IOException，由调用方决定是重试还是报错。 */
    private String fetchNoteMs(String url, boolean withUa) throws Exception {
        String body = httpGet(url, withUa, null);
        if (body == null) return null;
        if (withUa) body = extractTextarea(body);
        return body;
    }

    /** q52.n()：cmd.im 的 test_box 提取 + 去标签 + HTML 转义还原。 */
    private static String extractTestBox(String html) {
        if (html == null) return null;
        try {
            Matcher m = TEST_BOX.matcher(html);
            if (!m.find()) return null;
            String text = htmlUnescape(m.group(1).replaceAll("<[^>]+>", "").trim());
            return text.isEmpty() ? null : text;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** q52.p()：note.ms 的 textarea 提取 + 去标签 + HTML 转义还原。 */
    private static String extractTextarea(String html) {
        if (html == null) return null;
        try {
            Matcher m = TEXTAREA.matcher(html);
            if (!m.find()) return null;
            String text = htmlUnescape(m.group(1).replaceAll("<[^>]+>", "").trim());
            return text.isEmpty() ? null : text;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String htmlUnescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
    }

    /** 云端 GET：非 2xx 抛 IOException("HTTP <code>")；withUa=false 时不带 UA（note.ms 第二次尝试原文如此）。 */
    private String httpGet(String url, boolean withUa, Map<String, String> extraHeaders) throws IOException {
        OkHttpClient client = HkHttp.client();
        Request.Builder builder = new Request.Builder().url(url);
        if (withUa) builder.header("User-Agent", CLOUD_UA);
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                builder.header(e.getKey(), e.getValue());
            }
        }
        Response response = client.newCall(builder.build()).execute();
        try {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) throw new IOException("空响应 " + url);
            return body.string();
        } finally {
            response.close();
        }
    }

    // ---------- 载荷解码梯子 ----------

    /**
     * 云口令/粘贴文本的载荷解码梯子（q52.V() + 本步的 AES 尝试）。
     * 成功返回规则 JSON 字符串；解不开返回 null（调用方再按原文口径报错）。
     * <ol>
     * <li>(a) 直接是规则 JSON（含 {@code ￥home_rule￥{...}} 包裹形）；</li>
     * <li>(b) {@code base64://…@…@<b64>} 形；</li>
     * <li>(c) 整段 Base64 解出 JSON；</li>
     * <li>(d) AES 解密（CryptoJS 兼容，key "hk6666666109"，未验证，见类注释）。</li>
     * </ol>
     */
    public String decodePayload(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        String direct = sniffRuleJson(t);
        if (direct != null) return direct;
        int b64 = t.indexOf("base64://");
        if (b64 >= 0) {
            String[] parts = t.substring(b64 + 9).split("@");
            if (parts.length >= 3) {
                String decoded = base64DecodeToString(parts[2]);
                if (decoded != null && decoded.contains("{") && decoded.contains("}")) {
                    String sniffed = sniffRuleJson(decoded.trim());
                    if (sniffed != null) return sniffed;
                }
            }
        }
        String decoded = base64DecodeToString(t);
        if (decoded != null) {
            String sniffed = sniffRuleJson(decoded.trim());
            if (sniffed != null) return sniffed;
        }
        String aes = tryAesDecrypt(t);
        if (aes != null) {
            String sniffed = sniffRuleJson(aes.trim());
            if (sniffed != null) return sniffed;
        }
        return null;
    }

    /**
     * q52.U()：判断一段文本是不是规则 JSON。
     * 直接形：以 "{" 开头且能解析出带标题的规则；{@code ￥home_rule( _v2)￥{...}} 包裹形：
     * 取 ￥ 后各段拼回 JSON。原文还要求含 col_type/find_rule，本步放宽为
     * “能解析且标题非空、含 col_type/find_rule/url 之一”，以免拒掉极简规则。
     */
    private static String sniffRuleJson(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.startsWith("{")) {
            HkRule rule = HkRule.fromJson(t);
            if (rule != null && !TextUtils.isEmpty(rule.title())
                    && (t.contains("col_type") || t.contains("find_rule") || t.contains("\"url\""))) {
                return t;
            }
            return null;
        }
        if (t.contains("￥")) {
            String[] parts = t.split("￥", -1);
            if (parts.length >= 3) {
                String kind = parts[1].trim();
                if ("home_rule".equals(kind) || "home_rule_v2".equals(kind)) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 2; i < parts.length; i++) {
                        if (i > 2) sb.append("￥");
                        sb.append(parts[i]);
                    }
                    String inner = sb.toString().trim();
                    if (inner.startsWith("{") && inner.contains("col_type")) {
                        HkRule rule = HkRule.fromJson(inner);
                        if (rule != null && !TextUtils.isEmpty(rule.title())) return inner;
                    }
                }
            }
        }
        return null;
    }

    /** q52.e()：容错 Base64 解码（去空白、URL-safe 字符还原、补 "="，依次试 DEFAULT/URL_SAFE/NO_WRAP）。 */
    private static byte[] base64Decode(String text) {
        if (text == null) return null;
        String s = text.trim().replaceAll("\\s+", "");
        if (s.indexOf('-') >= 0 || s.indexOf('_') >= 0) {
            s = s.replace('-', '+').replace('_', '/');
        }
        int mod = s.length() % 4;
        if (mod == 1) return null;
        s = s.concat("====".substring(0, (4 - mod) % 4));
        int[] flags = {Base64.DEFAULT, Base64.URL_SAFE, Base64.NO_WRAP};
        for (int flag : flags) {
            try {
                return Base64.decode(s, flag);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String base64DecodeToString(String text) {
        byte[] bytes = base64Decode(text);
        if (bytes == null) return null;
        try {
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * AES 解密尝试（未验证，见类注释）：CryptoJS.AES.decrypt(密文, 口令) 的
     * OpenSSL 兼容格式——Base64 解出 "Salted__" + 8 字节盐 + 密文，口令经
     * EVP_BytesToKey（MD5）派生 32 字节 key + 16 字节 IV，AES-256-CBC 解密。
     * 解不出或解出后不是规则 JSON 一律返回 null，不抛错。
     */
    private static String tryAesDecrypt(String text) {
        try {
            byte[] raw = base64Decode(text);
            if (raw == null || raw.length < 16) return null;
            if (!"Salted__".equals(new String(raw, 0, 8, StandardCharsets.US_ASCII))) return null;
            byte[] salt = Arrays.copyOfRange(raw, 8, 16);
            byte[] cipherText = Arrays.copyOfRange(raw, 16, raw.length);
            byte[] keyIv = evpBytesToKey(AES_TRY_KEY.getBytes(StandardCharsets.UTF_8), salt, 48);
            SecretKeySpec key = new SecretKeySpec(keyIv, 0, 32, "AES");
            IvParameterSpec iv = new IvParameterSpec(keyIv, 32, 16);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, key, iv);
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            SpiderDebug.log("hk", "AES payload try failed: %s", e.toString());
            return null;
        }
    }

    /** OpenSSL EVP_BytesToKey（MD5 迭代）：D_i = MD5(D_{i-1} + 口令 + 盐），拼够指定长度。 */
    private static byte[] evpBytesToKey(byte[] password, byte[] salt, int length) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] prev = new byte[0];
        while (out.size() < length) {
            md5.reset();
            md5.update(prev);
            md5.update(password);
            md5.update(salt);
            prev = md5.digest();
            out.write(prev);
        }
        return Arrays.copyOf(out.toByteArray(), length);
    }

    // ---------- 删除 / 清缓存 / 重生 ----------

    /**
     * 删除规则（HkSiteAdapter.deleteRule → q52.f()）：删规则文件与数据目录、
     * 清启用开关、清运行时缓存。文件本就不存在也算成功。
     */
    public boolean deleteRule(String title) {
        if (TextUtils.isEmpty(title)) return false;
        File file = HkRuleStore.ruleFile(title);
        boolean ok = !file.exists() || file.delete();
        deleteRecursive(HkRuleStore.ruleDataDir(title));
        try {
            prefs().edit().remove(ENABLED_PREFIX + title).apply();
        } catch (Throwable ignored) {
        }
        clearRuleCache(title);
        LocalSource.invalidate();
        SpiderDebug.log("hk", "delete rule=%s ok=%s", title, ok);
        return ok;
    }

    /**
     * 清规则缓存（HkSiteAdapter.clearRuleCache 的第 1 步等价）：
     * 驱逐 HkEngine 的运行时/mtime/初始化守卫与 HkSession 的会话缓存。
     * 下次打开该规则时按磁盘文件重建。
     */
    public void clearRuleCache(String title) {
        if (TextUtils.isEmpty(title)) return;
        HkEngine.evictRule(title);
        HkSession.evictRule(title);
        SpiderDebug.log("hk", "clear rule cache=%s", title);
    }

    /**
     * 重生规则（HkSiteAdapter.rebirthRule 的第 1 步等价）：清缓存 + 删规则数据
     * 目录（require.json/libs），再从磁盘重读规则；规则文件还在返回 true。
     */
    public boolean rebirthRule(String title) {
        if (TextUtils.isEmpty(title)) return false;
        clearRuleCache(title);
        deleteRecursive(HkRuleStore.ruleDataDir(title));
        boolean ok = HkRuleStore.load(title) != null;
        SpiderDebug.log("hk", "rebirth rule=%s ok=%s", title, ok);
        return ok;
    }

    // ---------- 小工具 ----------

    private static String baseName(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String readZipEntry(ZipFile zip, ZipEntry entry) throws IOException {
        return new String(readZipEntryBytes(zip, entry), StandardCharsets.UTF_8);
    }

    private static byte[] readZipEntryBytes(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void putEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    /** 解 libs.zip 到目录（防 zip-slip：跳过含 ".." 与绝对路径的条目）。 */
    private static void unzipTo(byte[] zipBytes, File targetDir) throws IOException {
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || name.contains("..") || name.startsWith("/")) continue;
                File target = new File(targetDir, name);
                writeStreamToFile(zin, target);
                zin.closeEntry();
            }
        }
    }

    private static void writeFile(File file, byte[] data) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

    private static void writeStreamToFile(InputStream in, File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private static byte[] readFile(File file) throws IOException {
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }
}
