package com.github.moyingshi.osc.tv.api.hk;

import com.github.catvod.crawler.SpiderDebug;
import com.github.moyingshi.osc.tv.App;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 内置 hikerPop 宿主桥（第 2 步第 4 件；REPLICATION.md §4.8 / §13.1）。
 *
 * <p>来源：内置 JS 文本原样抽自 APK 反编译 {@code b22.java} case 12 里的两段
 * {@code __hk_hikerpop_native__} 字面量（逐字节相同，4020 字符），未手改一字，存放在
 * {@code assets/hk/hikerPop.js}。放 assets 而不用 Java 字符串常量，是为避免转义弄坏
 * 字面量——抽取是脚本解码 Java 转义后逐字节比对的，不是手抄。</p>
 *
 * <p>核实结论（写代码前已确认，见文档 §4）：</p>
 * <ul>
 * <li>(a) {@code __hpInvokeJs} / {@code __hpStore} / {@code __hpSeq} 都定义在该
 * 字面量内部，不在 Hikerurl.js / plugin.js / b22 注入里，桥里不另提供。</li>
 * <li>(b) {@code require} 的返回值是 JS 源码文本，调用方（规则）eval 它——APK 的
 * case 12 同样只返回字面量文本，不在 Java 侧 eval；所以 doRequire 返回内置 JS 文本
 * 就足够，不由 doRequire 自己 eval 进作用域。注意：这只覆盖裸 {@code require(...)}
 * 调用；{@code $.require}（Hikerurl.js CommonJS）走 {@code request()}，不经过
 * doRequire，见文档 §4 未验证 5。</li>
 * </ul>
 *
 * <p>行为（规格书 §4.8，字面量原样）：{@code selectCenter} /
 * {@code selectBottomResIcon} 经 {@code __hkStoreSet('hikerPop:' + id, handler)}
 * 把回调对象（含闭包）跨作用域存起来，再调第 5 件已接好的 {@code showSelectOptions}
 * 弹原生列表；选中后在页面引擎里按 {@code (input, MY_INDEX)} 回放 {@code click}，
 * 返回值继续走 {@code showSelectOptions} 的结果分发（导航/toast/refreshPage 均生效）。
 * {@code seekCenter} 是 39%–100% 的百分比列表（col: 4），选中后调
 * {@code opt.rightClick(p-39, 61)}。{@code toastMeg} 转 {@code toast(String(t))}；
 * 其余（setUseStartActivity / canBiometric 返回 0 / checkByBiometric / scrollSmooth /
 * updateRecordsBottom 转 {@code __hkShowUpdateLog} / ResExtraInputBox 纯数据类 /
 * 输入框降级 toast）按字面量原样走。桥本身不弹对话框、不碰 confirm、不碰播放链。</p>
 *
 * <p>官方原版判定：{@link #isOfficialOriginal(String)}——文本长度 ≥ 20000 且同时含
 * {@code CustomCenterRecyclerViewPopup}、{@code ToastMgr}、{@code builderXPopup}
 * 三个标记时判定为官方原版（构建在 XPopup / {@code com.example.hikerview} 类上，
 * 本包不存在，require 到一调用就 TypeError），丢弃原版、返回内置实现。</p>
 */
public final class HkHikerPopBridge {

    private HkHikerPopBridge() {
    }

    /** 内置实现在 assets 里的路径。 */
    public static final String ASSET_PATH = "hk/hikerPop.js";

    /** $.require("hiker://files/libs/hikerPop.js") 在 filesDir 里的落点。 */
    public static final String SEEDED_REL_PATH = "libs/hikerPop.js";

    /**
     * $.require 接通补丁（第 2 步第 4 件补件）：把 {@code assets/hk/hikerPop.js}
     * 种子写进 filesDir 的 {@code libs/hikerPop.js}。Hikerurl.js 的 getScript 先调
     * {@code fileExist(id)}——{@code HkJsRuntime.fileExists} 经 {@code resolveFile}
     * 把 {@code hiker://files/} 映射到 filesDir，文件不存在就抛
     * {@code 'Module "…" cannot be found'}；种子写好后该检查通过。
     * <p>
     * 内容比对策略与 {@code HkRuleStore.ensureSeeded} 的规则种子一致：目标不存在
     * 则复制，已存在但内容不同则覆盖写并记日志，内容相同则跳过、不动文件。
     * 资产文本必须含 {@code __hk_hikerpop_native__} 标记才写（与
     * {@link #builtinSource(HkJsRuntime)} 的校验一致），资产损坏时不写坏文件、
     * 只记日志；失败只记日志、不抛错。由 {@code HkRuleStore.ensureSeeded()} 调一次，
     * 规则求值一定在首次 getSites 之后，时序没问题，不另起触发点。
     */
    public static void ensureSeeded() {
        try {
            String text;
            try (InputStream in = App.get().getAssets().open(ASSET_PATH)) {
                text = readStream(in);
            }
            if (text == null || !text.contains("__hk_hikerpop_native__")) {
                SpiderDebug.log("HkHikerPopBridge", "seed skipped: asset missing marker, len=%d", text == null ? 0 : text.length());
                return;
            }
            File target = new File(App.get().getFilesDir(), SEEDED_REL_PATH);
            boolean existed = target.exists();
            if (existed) {
                String current = null;
                try {
                    current = readFile(target);
                } catch (Throwable ignored) {
                    // 读不出旧文件就按不一致处理，下面覆盖写；写失败再由外层 catch 记日志。
                }
                if (text.equals(current)) return;
            }
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            try (OutputStream out = new FileOutputStream(target)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            SpiderDebug.log("HkHikerPopBridge", existed ? "seed hikerPop refreshed: %s" : "seed hikerPop copied: %s", target.getName());
        } catch (Throwable e) {
            SpiderDebug.log("HkHikerPopBridge", "seed hikerPop failed: %s", e.toString());
        }
    }

    private static String readStream(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static String readFile(File file) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            return readStream(in);
        }
    }

    /** 官方原版判定的最小长度（§4.8）。 */
    public static final int OFFICIAL_MIN_LENGTH = 20000;

    /** 官方原版三标记。 */
    public static final String MARKER_CENTER_POPUP = "CustomCenterRecyclerViewPopup";
    public static final String MARKER_TOAST_MGR = "ToastMgr";
    public static final String MARKER_XPOPUP_BUILDER = "builderXPopup";

    /** 内置 JS 文本的进程内缓存（assets 内容随包固定，不会变）。 */
    private static String cachedSource;

    /**
     * 内置 hikerPop JS 文本。读不到 assets 时记日志回 ""（不抛错，与 doRequire 其余
     * 分支的失败语义一致）；调用方拿到 "" 时 require 结果为空，规则按原有空值路径走。
     * 文本必须含 {@code __hk_hikerpop_native__} 标记，否则视为资产损坏、同样回 ""。
     */
    public static String builtinSource(HkJsRuntime runtime) {
        if (cachedSource != null) return cachedSource;
        String text = "";
        try {
            text = runtime == null ? "" : runtime.readAssetText(ASSET_PATH);
        } catch (Throwable e) {
            SpiderDebug.log("HkHikerPopBridge", "builtin load failed: %s", e.toString());
            text = "";
        }
        if (text == null) text = "";
        if (!text.contains("__hk_hikerpop_native__")) {
            SpiderDebug.log("HkHikerPopBridge", "builtin asset missing marker, len=%d", text.length());
            return "";
        }
        cachedSource = text;
        return text;
    }

    /**
     * 官方原版判定（§4.8）：长度 ≥ 20000 且同时含三个标记。null / 短文本回 false，
     * 即按原文返回，不丢弃。
     */
    public static boolean isOfficialOriginal(String text) {
        if (text == null || text.length() < OFFICIAL_MIN_LENGTH) return false;
        return text.contains(MARKER_CENTER_POPUP)
                && text.contains(MARKER_TOAST_MGR)
                && text.contains(MARKER_XPOPUP_BUILDER);
    }
}
