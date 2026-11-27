package com.github.moyingshi.osc.tv.ui.hk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.viewbinding.ViewBinding;

import com.github.catvod.crawler.SpiderDebug;
import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.api.hk.HkRule;
import com.github.moyingshi.osc.tv.databinding.ActivityHkListBinding;
import com.github.moyingshi.osc.tv.ui.base.BaseActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 海阔小程序列表宿主，第 1 步。
 * <p>
 * 等价于 APK 的 HkListActivity（未混淆，1,660+ 行）的宿主部分：
 * 静态 starter C1（本类写作 start）+ onCreate 读规则 JSON + 挂列表 Fragment。
 * 列表渲染/分类 Tab/分页/卡片点击/长按菜单的本体在 APK 里是 fy1 Fragment；
 * 本步只做其最小等价 HkListFragment（列表 + 分页 + 卡片点击分流）。
 * <p>
 * &gt;200KB 的规则走 MD5 + hk_temp_rule.txt（大规则不直接放 Intent extra），
 * 与 APK 的 C1/onCreate 逻辑一致；进程内另有静态 S/R 做同 MD5 的快速交接。
 */
public class HkListActivity extends BaseActivity {

    private static final String TEMP_RULE_FILE = "hk_temp_rule.txt";
    /** 200KB 上限（APK 的 C1 阈值）。 */
    private static final int MAX_INLINE_RULE_BYTES = 200 * 1024;

    /** 静态交接：最近一次大规则的 MD5 与 JSON（进程内快速通道）。 */
    private static String S;
    private static String R;

    private ActivityHkListBinding mBinding;
    private HkRule rule;
    private String ruleJson;
    private String title;
    private boolean listHost;

    /** C1 等价：规则 JSON + title / listHost / hkAutoPage extras。 */
    public static void start(Context context, HkRule rule, String title) {
        start(context, rule, title, false);
    }

    public static void start(Context context, HkRule rule, String title, boolean listHost) {
        if (context == null || rule == null) return;
        String json = rule.toJson();
        Intent intent = new Intent(context, HkListActivity.class);
        if (TextUtils.isEmpty(title)) title = rule.title();
        intent.putExtra("title", title);
        intent.putExtra("listHost", listHost);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_INLINE_RULE_BYTES) {
            String md5 = md5(json);
            S = md5;
            R = json;
            try {
                File temp = new File(App.get().getCacheDir(), TEMP_RULE_FILE);
                try (FileOutputStream out = new FileOutputStream(temp)) {
                    out.write((md5 + "\n" + json).getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable e) {
                SpiderDebug.log("hk", "write temp rule failed: %s", e.toString());
            }
            intent.putExtra("ruleKey", md5);
        } else {
            intent.putExtra("data", json);
        }
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHkListBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(mBinding.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        ruleJson = readRuleJson(getIntent());
        if (TextUtils.isEmpty(ruleJson)) {
            finish();
            return;
        }
        rule = HkRule.fromJson(ruleJson);
        if (rule == null) {
            finish();
            return;
        }
        title = getIntent().getStringExtra("title");
        if (TextUtils.isEmpty(title)) title = rule.title();
        listHost = getIntent().getBooleanExtra("listHost", false);
        setTitle(title);
        HkListFragment fragment = HkListFragment.newInstance(ruleJson, title, listHost);
        getSupportFragmentManager().beginTransaction().replace(R.id.container, fragment, "0").commit();
    }

    @Override
    protected void initEvent() {
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) onBackInvoked();
        return super.onOptionsItemSelected(item);
    }

    /** onCreate 读规则 JSON：先 "data"，再 "ruleKey"（静态 S/R → hk_temp_rule.txt）。 */
    private static String readRuleJson(Intent intent) {
        if (intent == null) return null;
        String data = intent.getStringExtra("data");
        if (!TextUtils.isEmpty(data)) return data;
        String ruleKey = intent.getStringExtra("ruleKey");
        if (ruleKey != null && ruleKey.equals(S)) return R;
        if (ruleKey == null) return null;
        try {
            File file = new File(App.get().getCacheDir(), TEMP_RULE_FILE);
            if (!file.exists()) return null;
            String content = readFile(file);
            int newline = content.indexOf('\n');
            if (newline >= 0 && ruleKey.equals(content.substring(0, newline).trim())) {
                return content.substring(newline + 1);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String readFile(File file) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String md5(String input) {
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
}
