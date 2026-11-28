package com.github.moyingshi.osc.tv.ui.hk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.viewbinding.ViewBinding;

import com.github.catvod.crawler.SpiderDebug;
import com.github.moyingshi.osc.tv.App;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.api.hk.HkJsRuntime;
import com.github.moyingshi.osc.tv.api.hk.HkRule;
import com.github.moyingshi.osc.tv.api.hk.HkRuleManager;
import com.github.moyingshi.osc.tv.api.hk.HkRuleStore;
import com.github.moyingshi.osc.tv.databinding.ActivityHkListBinding;
import com.github.moyingshi.osc.tv.theme.WebHtvAlertDialogBuilder;
import com.github.moyingshi.osc.tv.ui.base.BaseActivity;
import com.github.moyingshi.osc.tv.utils.Notify;
import com.github.moyingshi.osc.tv.utils.Task;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 海阔小程序列表宿主，第 1 步。
 * <p>
 * 等价于 APK 的 HkListActivity（未混淆，1,660+ 行）的宿主部分：
 * 静态 starter C1（本类写作 start）+ onCreate 读规则 JSON + 挂列表 Fragment。
 * 列表渲染/分类 Tab/分页/卡片点击/长按菜单的本体在 APK 里是 fy1 Fragment；
 * 本步只做其最小等价 HkListFragment（列表 + 分页 + 卡片点击分流）。
 * <p>
 * &gt;200KB 的规则走 MD5 + hk_temp_rule.txt（大规则不直接放 Intent extra），
 * 与 APK 的 C1/onCreate 逻辑一致；进程内另有静态 S/RULE_JSON 做同 MD5 的快速交接。
 */
public class HkListActivity extends BaseActivity {

    private static final String TEMP_RULE_FILE = "hk_temp_rule.txt";
    /** 200KB 上限（APK 的 C1 阈值）。 */
    private static final int MAX_INLINE_RULE_BYTES = 200 * 1024;

    /** 静态交接：最近一次大规则的 MD5 与 JSON（进程内快速通道）。 */
    private static String S;
    private static String RULE_JSON;

    private ActivityHkListBinding mBinding;
    private HkRule rule;
    private String ruleJson;
    private String title;
    private boolean listHost;

    /** 工具栏菜单项（第 2 步：导入/管理规则入口，程序化加项，不新增 menu 资源）。 */
    private static final int MENU_IMPORT_RULE = 1001;
    private static final int MENU_MANAGE_RULES = 1002;
    /** 第 2 步第 3 件：规则内搜索入口（仅当前规则 hasSearch() 为真时显示）。 */
    private static final int MENU_SEARCH = 1003;

    /** 待导出的规则（导出文件选择器回调用）。 */
    private HkRule pendingExportRule;

    /** 导入文件选择器（ModuleActivity 的 launcher 写法）：.json/.hk 当文本，.hkzip 当压缩包。 */
    private final ActivityResultLauncher<Intent> importFileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getData() == null || result.getData().getData() == null) return;
                importPickedFile(result.getData().getData());
            });

    /** 导出文件选择器：ACTION_CREATE_DOCUMENT，写 .hkzip。 */
    private final ActivityResultLauncher<Intent> exportFileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getData() == null || result.getData().getData() == null) return;
                if (pendingExportRule == null) return;
                exportPickedFile(pendingExportRule, result.getData().getData());
                pendingExportRule = null;
            });

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
            RULE_JSON = json;
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

    /** 第 2 步第 5 件：前台时登记为 showSelectOptions 弹窗的宿主 Activity。 */
    @Override
    protected void onResume() {
        super.onResume();
        HkJsRuntime.setCurrentActivity(this);
    }

    @Override
    protected void onPause() {
        HkJsRuntime.clearCurrentActivity(this);
        super.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_IMPORT_RULE, Menu.NONE, "导入规则");
        menu.add(Menu.NONE, MENU_MANAGE_RULES, Menu.NONE, "管理规则");
        if (rule != null && rule.hasSearch()) menu.add(Menu.NONE, MENU_SEARCH, Menu.NONE, "搜索");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) onBackInvoked();
        else if (item.getItemId() == MENU_IMPORT_RULE) showImportDialog();
        else if (item.getItemId() == MENU_MANAGE_RULES) showManageDialog();
        else if (item.getItemId() == MENU_SEARCH) HkSearchActivity.start(this, rule);
        return super.onOptionsItemSelected(item);
    }

    // ---------- 第 2 步：导入规则（粘贴 / 选文件 / 云口令，三路并列） ----------

    /** 导入对话框：三路并列，不新增 Activity。耗时操作一律走后台线程，UI 只做弹窗与 Toast。 */
    private void showImportDialog() {
        int pad = dp(16);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad, pad, pad, pad);

        TextView pasteLabel = new TextView(this);
        pasteLabel.setText("粘贴规则文本（规则 JSON / rule:// 口令 / 云口令）");
        container.addView(pasteLabel);
        EditText pasteEdit = new EditText(this);
        pasteEdit.setMinLines(4);
        pasteEdit.setMaxLines(8);
        pasteEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        pasteEdit.setHint("{\"title\": \"...\", \"url\": \"...\", ...}");
        container.addView(pasteEdit);
        Button pasteButton = new Button(this);
        pasteButton.setText("导入粘贴的文本");
        pasteButton.setOnClickListener(v -> {
            String text = pasteEdit.getText().toString();
            if (TextUtils.isEmpty(text.trim())) {
                Notify.show("内容为空");
                return;
            }
            runImportInBackground(() -> HkRuleManager.get().importText(text));
        });
        container.addView(pasteButton);

        TextView fileLabel = new TextView(this);
        fileLabel.setText("或从文件导入（.json / .hk / .hkzip）");
        fileLabel.setPadding(0, dp(12), 0, 0);
        container.addView(fileLabel);
        Button fileButton = new Button(this);
        fileButton.setText("选择文件…");
        fileButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            importFileLauncher.launch(intent);
        });
        container.addView(fileButton);

        TextView cloudLabel = new TextView(this);
        cloudLabel.setText("或输入云口令（云1~云10oooole/…）");
        cloudLabel.setPadding(0, dp(12), 0, 0);
        container.addView(cloudLabel);
        EditText cloudEdit = new EditText(this);
        cloudEdit.setSingleLine(true);
        cloudEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        cloudEdit.setHint("云1oooole/…");
        container.addView(cloudEdit);
        Button cloudButton = new Button(this);
        cloudButton.setText("导入云口令");
        cloudButton.setOnClickListener(v -> {
            String password = cloudEdit.getText().toString();
            if (TextUtils.isEmpty(password.trim())) {
                Notify.show("口令为空");
                return;
            }
            runImportInBackground(() -> HkRuleManager.get().resolveCloudPassword(password));
        });
        container.addView(cloudButton);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(container);
        new WebHtvAlertDialogBuilder(this)
                .setTitle("导入规则")
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 后台跑导入，成功弹“导入成功，是否打开？”（APK 的 q1 行为），失败 Toast + 记日志，不闪退。 */
    private void runImportInBackground(ImportTask task) {
        Task.execute(() -> {
            HkRule imported;
            try {
                imported = task.run();
            } catch (Throwable e) {
                SpiderDebug.log("hk", "import rule failed: %s", e.toString());
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                App.post(() -> Notify.show("导入失败：" + message));
                return;
            }
            App.post(() -> showImportSuccessDialog(imported));
        });
    }

    private void showImportSuccessDialog(HkRule imported) {
        if (isFinishing() || isDestroyed()) return;
        new WebHtvAlertDialogBuilder(this)
                .setTitle("导入成功")
                .setMessage("已导入规则“" + imported.title() + "”，是否打开？")
                .setPositiveButton("打开", (dialog, which) -> start(this, imported, imported.title()))
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 文件选择器回调：.hkzip（按文件名或 PK 魔数认）走 importHkZip，其余当规则文本走 importText。 */
    private void importPickedFile(Uri uri) {
        Task.execute(() -> {
            try {
                String name = displayNameOf(uri);
                byte[] bytes = readUriBytes(uri);
                HkRule imported;
                if ((name != null && name.toLowerCase().endsWith(".hkzip")) || isZipBytes(bytes)) {
                    File cache = new File(getCacheDir(), "hk_import_" + System.currentTimeMillis() + ".hkzip");
                    try (FileOutputStream out = new FileOutputStream(cache)) {
                        out.write(bytes);
                    }
                    try {
                        imported = HkRuleManager.get().importHkZip(cache);
                    } finally {
                        cache.delete();
                    }
                } else {
                    imported = HkRuleManager.get().importText(new String(bytes, StandardCharsets.UTF_8));
                }
                App.post(() -> showImportSuccessDialog(imported));
            } catch (Throwable e) {
                SpiderDebug.log("hk", "import file failed: %s", e.toString());
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                App.post(() -> Notify.show("导入失败：" + message));
            }
        });
    }

    /** 导出选择器回调：先在缓存里写好 .hkzip，再拷到用户选的位置。 */
    private void exportPickedFile(HkRule exportRule, Uri uri) {
        Task.execute(() -> {
            try {
                File cache = new File(getCacheDir(), "hk_export_" + System.currentTimeMillis() + ".hkzip");
                HkRuleManager.get().exportHkZip(exportRule, cache);
                try (InputStream in = new java.io.FileInputStream(cache);
                     OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new java.io.IOException("无法写入目标文件");
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                } finally {
                    cache.delete();
                }
                App.post(() -> Notify.show("已导出“" + exportRule.title() + "”"));
            } catch (Throwable e) {
                SpiderDebug.log("hk", "export rule failed: %s", e.toString());
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                App.post(() -> Notify.show("导出失败：" + message));
            }
        });
    }

    private String displayNameOf(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private byte[] readUriBytes(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new java.io.IOException("无法读取文件");
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static boolean isZipBytes(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
    }

    /** 后台导入任务（粘贴文本 / 云口令共用 runImportInBackground）。 */
    private interface ImportTask {
        HkRule run() throws Exception;
    }

    // ---------- 第 2 步：管理规则（打开 / 启用开关 / 导出 / 删除） ----------

    /** 管理规则对话框：点标题打开，开关切启用/禁用，行内按钮导出或删除。 */
    private void showManageDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        container.setPadding(pad, dp(8), pad, dp(8));
        AlertDialog dialog = new WebHtvAlertDialogBuilder(this)
                .setTitle("管理规则")
                .setView(new ScrollView(this) {{
                    addView(container);
                }})
                .setPositiveButton("关闭", null)
                .create();
        refreshManageList(dialog, container);
        dialog.show();
    }

    /** 重建管理列表行（删规则后调这里刷新，不重建整个弹窗）。 */
    private void refreshManageList(AlertDialog dialog, LinearLayout container) {
        container.removeAllViews();
        List<HkRule> rules = HkRuleManager.get().list();
        if (rules.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无已安装规则");
            empty.setPadding(0, dp(8), 0, dp(8));
            container.addView(empty);
            return;
        }
        for (HkRule ruleItem : rules) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(6), 0, dp(6));

            TextView titleView = new TextView(this);
            titleView.setText(ruleItem.title());
            titleView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            titleView.setOnClickListener(v -> {
                dialog.dismiss();
                start(this, ruleItem, ruleItem.title());
            });
            row.addView(titleView);

            Switch enabledSwitch = new Switch(this);
            enabledSwitch.setChecked(HkRuleManager.get().isEnabled(ruleItem.title()));
            enabledSwitch.setOnCheckedChangeListener((button, checked) -> {
                HkRuleManager.get().setEnabled(ruleItem.title(), checked);
                Notify.show(checked ? "已启用“" + ruleItem.title() + "”" : "已禁用“" + ruleItem.title() + "”，下次打开源列表生效");
            });
            row.addView(enabledSwitch);

            Button exportButton = new Button(this);
            exportButton.setText("导出");
            exportButton.setOnClickListener(v -> {
                pendingExportRule = ruleItem;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/zip");
                String name = HkRuleStore.fileNameFor(ruleItem.title());
                if (name.endsWith(".json")) name = name.substring(0, name.length() - ".json".length());
                intent.putExtra(Intent.EXTRA_TITLE, name + ".hkzip");
                exportFileLauncher.launch(intent);
            });
            row.addView(exportButton);

            Button deleteButton = new Button(this);
            deleteButton.setText("删除");
            deleteButton.setOnClickListener(v -> {
                boolean ok = HkRuleManager.get().deleteRule(ruleItem.title());
                Notify.show(ok ? "已删除“" + ruleItem.title() + "”" : "删除失败");
                refreshManageList(dialog, container);
            });
            row.addView(deleteButton);

            container.addView(row);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    /** onCreate 读规则 JSON：先 "data"，再 "ruleKey"（静态 S/RULE_JSON → hk_temp_rule.txt）。 */
    private static String readRuleJson(Intent intent) {
        if (intent == null) return null;
        String data = intent.getStringExtra("data");
        if (!TextUtils.isEmpty(data)) return data;
        String ruleKey = intent.getStringExtra("ruleKey");
        if (ruleKey != null && ruleKey.equals(S)) return RULE_JSON;
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
