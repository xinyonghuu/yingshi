package com.github.moyingshi.osc.tv.ui.hk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.github.catvod.crawler.SpiderDebug;
import com.github.moyingshi.osc.tv.api.hk.HkItem;
import com.github.moyingshi.osc.tv.api.hk.HkJsRuntime;
import com.github.moyingshi.osc.tv.api.hk.HkPlay;
import com.github.moyingshi.osc.tv.api.hk.HkRule;
import com.github.moyingshi.osc.tv.api.hk.HkSearchEngine;
import com.github.moyingshi.osc.tv.api.hk.HkSession;
import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.databinding.ActivityHkSearchBinding;
import com.github.moyingshi.osc.tv.databinding.ItemHkCardBinding;
import com.github.moyingshi.osc.tv.ui.activity.VideoActivity;
import com.github.moyingshi.osc.tv.ui.base.BaseActivity;
import com.github.moyingshi.osc.tv.utils.ImgUtil;
import com.github.moyingshi.osc.tv.utils.Notify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 海阔小程序搜索页，第 2 步第 3 件。
 * <p>
 * 等价于 APK 的 HkSearchActivity（561 行）的最小实现：顶部搜索框（EditText + 搜索按钮，
 * 支持 IME 搜索键）+ 结果网格（复用 item_hk_card.xml 与 HkListFragment 的网格/分页写法）
 * + 空结果提示；搜索执行放后台线程，结果回主线程刷新。
 * <p>
 * 与 APK 的差别（有意简化，见任务文档 §3）：APK 用 ViewPager2 按规则分页签 + 横向规则条
 * + 搜索历史列表 + 分组/精准模式切换；本件只做单输入框 + 单结果网格。多规则搜索只做最小
 * 渲染（每组前面一条规则名分隔行，不翻页）；逐组回推与每规则页码留待第 3 步。
 * <p>
 * 规则交接：Intent 传规则 JSON 列表（"ruleJsons"，与 HkListActivity 的 "data" 同款
 * 内联做法；单条规则通常只有几 KB，远低于 Binder 上限）。
 */
public class HkSearchActivity extends BaseActivity {

    private ActivityHkSearchBinding mBinding;

    /** 单规则模式的规则（oneRule 为真时用）；多规则模式用 rules。 */
    private HkRule rule;
    private List<HkRule> rules = new ArrayList<>();
    private boolean oneRule = true;

    private final List<Row> rows = new ArrayList<>();
    private HkSearchAdapter adapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String keyword = "";
    private int page = 1;
    private boolean loading;
    private boolean hasMore;
    /** 单规则且 search_url 含 fypage 时才翻页（与 HkListFragment 的分页条件同款判定）。 */
    private boolean pageable;

    /** 一行渲染单元：卡片行带所属规则（点击分流用）；header 行是规则名分隔行，不可点。 */
    private static final class Row {
        final HkItem item;
        final HkRule rule;
        final boolean header;

        Row(HkItem item, HkRule rule, boolean header) {
            this.item = item;
            this.rule = rule;
            this.header = header;
        }
    }

    /** a1 等价：单规则搜索。 */
    public static void start(Activity activity, HkRule rule) {
        if (activity == null || rule == null) return;
        List<HkRule> single = new ArrayList<>();
        single.add(rule);
        startInternal(activity, single, null, true);
    }

    /** b1 等价：多规则搜索（h62 执行器）；keyword 为空时不自动搜，等用户输入。 */
    public static void start(Activity activity, List<HkRule> rules, String keyword) {
        if (activity == null || rules == null || rules.isEmpty()) return;
        startInternal(activity, rules, keyword, false);
    }

    private static void startInternal(Activity activity, List<HkRule> rules, String keyword, boolean oneRule) {
        ArrayList<String> jsons = new ArrayList<>();
        for (HkRule r : rules) {
            if (r != null) jsons.add(r.toJson());
        }
        if (jsons.isEmpty()) return;
        Intent intent = new Intent(activity, HkSearchActivity.class);
        intent.putStringArrayListExtra("ruleJsons", jsons);
        intent.putExtra("oneRule", oneRule);
        if (keyword != null) intent.putExtra("wd", keyword);
        activity.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHkSearchBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        Intent intent = getIntent();
        ArrayList<String> jsons = intent == null ? null : intent.getStringArrayListExtra("ruleJsons");
        if (jsons != null) {
            for (String json : jsons) {
                HkRule r = HkRule.fromJson(json);
                if (r != null) rules.add(r);
            }
        }
        if (rules.isEmpty()) {
            finish();
            return;
        }
        oneRule = intent != null && intent.getBooleanExtra("oneRule", rules.size() == 1);
        rule = rules.get(0);
        pageable = oneRule && rule.searchUrl().contains("fypage");
        setTitle(oneRule ? rule.title() : "搜索");

        int spanCount = oneRule ? spanFor(rule.colType()) : 3;
        GridLayoutManager manager = new GridLayoutManager(this, spanCount);
        manager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (position < 0 || position >= rows.size()) return 1;
                return rows.get(position).header ? spanCount : 1;
            }
        });
        mBinding.list.setLayoutManager(manager);
        adapter = new HkSearchAdapter();
        mBinding.list.setAdapter(adapter);
        mBinding.list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0 || loading || !hasMore) return;
                int last = manager.findLastVisibleItemPosition();
                if (last >= rows.size() - 6) loadPage(page + 1);
            }
        });

        String wd = intent == null ? null : intent.getStringExtra("wd");
        if (wd != null && !wd.isEmpty()) {
            mBinding.searchInput.setText(wd);
            mBinding.searchInput.setSelection(wd.length());
            doSearch(wd);
        }
    }

    @Override
    protected void initEvent() {
        mBinding.searchButton.setOnClickListener(v -> doSearch(mBinding.searchInput.getText().toString()));
        mBinding.searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(mBinding.searchInput.getText().toString());
                return true;
            }
            return false;
        });
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
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    /** col_type 里的列数（与 HkListFragment.spanFor 同款）。 */
    private static int spanFor(String colType) {
        if (colType == null) return 3;
        if (colType.contains("_1")) return 1;
        if (colType.contains("_2")) return 2;
        if (colType.contains("_3")) return 3;
        if (colType.contains("_4")) return 4;
        return 3;
    }

    private void doSearch(String raw) {
        String kw = raw == null ? "" : raw.trim();
        if (kw.isEmpty()) {
            Notify.show("搜索关键词不能为空");
            hideKeyboard();
            return;
        }
        keyword = kw;
        hideKeyboard();
        loadPage(1);
    }

    private void loadPage(int targetPage) {
        if (loading) return;
        loading = true;
        if (targetPage == 1 && mBinding != null) mBinding.loading.setVisibility(View.VISIBLE);
        final String kw = keyword;
        executor.submit(() -> {
            List<Row> batch = new ArrayList<>();
            boolean more = false;
            try {
                if (oneRule) {
                    List<HkItem> items = HkSearchEngine.searchSingle(rule, kw, targetPage);
                    for (HkItem item : items) batch.add(new Row(item, rule, false));
                    more = pageable && !items.isEmpty();
                } else if (targetPage == 1) {
                    // 多规则：只搜第 1 页，按规则分组，每组前面一条规则名分隔行；空组不显示。
                    // 每规则各自翻页（APK 的 R 页码表）留待第 3 步。
                    List<HkSearchEngine.Group> groups = HkSearchEngine.search(rules, kw, 1);
                    for (HkSearchEngine.Group group : groups) {
                        if (group.items.isEmpty()) continue;
                        HkItem head = new HkItem(group.rule.title(), "", "", "");
                        head.setColType("header");
                        batch.add(new Row(head, group.rule, true));
                        for (HkItem item : group.items) batch.add(new Row(item, group.rule, false));
                    }
                    more = false;
                }
            } catch (Throwable e) {
                SpiderDebug.log("hk", "search page %d failed: %s", targetPage, e.toString());
            }
            List<Row> result = batch;
            boolean resultHasMore = more;
            runOnUiThread(() -> {
                loading = false;
                if (isFinishing() || isDestroyed() || mBinding == null) return;
                mBinding.loading.setVisibility(View.GONE);
                if (targetPage == 1) rows.clear();
                if (!result.isEmpty()) {
                    page = targetPage;
                    rows.addAll(result);
                }
                hasMore = resultHasMore;
                adapter.notifyDataSetChanged();
                mBinding.empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && mBinding != null) {
            imm.hideSoftInputFromWindow(mBinding.searchInput.getWindowToken(), 0);
        }
    }

    /**
     * 卡片点击：与 HkListFragment.onCardClick/onResolvedOnUi 完全相同的四路分流，
     * 此处照抄一份并同步维护，未抽成 helper——理由见任务文档 §3：
     * 分流与 Fragment 生命周期（isAdded/requireActivity）及详情分支的 executor
     * 二次提交耦合，抽取要把生命周期判定当回调参数传进 helper，改动已验证通过的
     * 列表点击代码且本地无编译器可验；宁可两处同步维护，不动 HkListFragment。
     * 唯一差别是生命周期判定换成本 Activity 的 isFinishing()/isDestroyed()，
     * 且每行卡片用其所属规则（多规则搜索时各组规则不同）。
     */
    private void onCardClick(Row row) {
        if (row == null || row.header || row.item == null || row.rule == null) return;
        HkItem item = row.item;
        HkRule itemRule = row.rule;
        executor.submit(() -> {
            HkSession session = HkSession.forRule(itemRule);
            if (session == null) return;
            session.lazyParse(0, new HkSession.HkLazyCallback() {
                @Override
                public void onResolved(String url, String linesJson, int lineIndex) {
                    if (isFinishing() || isDestroyed()) return;
                    runOnUiThread(() -> onResolvedOnUi(item, itemRule, url, linesJson, lineIndex));
                }

                @Override
                public void onEmpty() {
                    if (isFinishing() || isDestroyed()) return;
                    runOnUiThread(() -> Notify.show("小程序未解析出播放地址"));
                }
            }, item.getUrl(), item.getExtra());
        });
    }

    private void onResolvedOnUi(HkItem item, HkRule itemRule, String url, String linesJson, int lineIndex) {
        if (isFinishing() || isDestroyed()) return;
        if (url == null) url = "";
        if (url.startsWith("hk://")) {
            // 子页面：自我递归（listHost 区分宿主与子页，Activity 栈即返回栈）。
            HkRule sub = itemRule.derive(url, item.getFindRule(), item.getColType());
            HkListActivity.start(this, sub, sub.title(), true);
            return;
        }
        if (HkSession.isPlayable(url)) {
            HkPlay.play(this, itemRule, item, url, linesJson, lineIndex);
            return;
        }
        if (!item.getFindRule().isEmpty()) {
            HkRule sub = itemRule.derive(url, item.getFindRule(), item.getColType());
            HkListActivity.start(this, sub, sub.title(), true);
            return;
        }
        if (!itemRule.detailFindRule().isEmpty() || !itemRule.sdetailFindRule().isEmpty()) {
            // 第 4 路：标准详情。detail() 要取数 + 跑规则 JS，必须在后台线程做，
            // 不能在 UI 线程调；沿用本 Activity 的单线程 executor（与点卡懒解析同一条）。
            executor.submit(() -> {
                Vod vod;
                try {
                    vod = HkSiteAdapter.detail(itemRule, item);
                } catch (Throwable e) {
                    SpiderDebug.log("hk", "detail failed rule=%s: %s", itemRule.title(), e.toString());
                    vod = null;
                }
                Vod result = vod;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (result != null) VideoActivity.startDirectResolved(this, result);
                    else Notify.show("小程序未解析出播放地址");
                });
            });
            return;
        }
        Notify.show("小程序未解析出播放地址");
    }

    private class HkSearchAdapter extends RecyclerView.Adapter<HkSearchAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemHkCardBinding binding = ItemHkCardBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
            return new Holder(binding);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Row row = rows.get(position);
            if (row.header) {
                holder.binding.pic.setVisibility(View.GONE);
                holder.binding.desc.setVisibility(View.GONE);
                holder.binding.title.setText(row.item.getTitle());
                holder.itemView.setOnClickListener(null);
                return;
            }
            holder.binding.pic.setVisibility(View.VISIBLE);
            holder.binding.desc.setVisibility(View.VISIBLE);
            holder.binding.title.setText(row.item.getTitle());
            holder.binding.desc.setText(row.item.getDesc());
            ImgUtil.load(row.item.getTitle(), row.item.getPic(), holder.binding.pic);
            holder.itemView.setOnClickListener(v -> onCardClick(row));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ItemHkCardBinding binding;

            Holder(ItemHkCardBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
