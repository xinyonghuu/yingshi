package com.github.moyingshi.osc.tv.ui.hk;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.github.catvod.crawler.SpiderDebug;
import com.github.moyingshi.osc.tv.api.hk.HkItem;
import com.github.moyingshi.osc.tv.api.hk.HkPlay;
import com.github.moyingshi.osc.tv.api.hk.HkRule;
import com.github.moyingshi.osc.tv.api.hk.HkSession;
import com.github.moyingshi.osc.tv.databinding.FragmentHkListBinding;
import com.github.moyingshi.osc.tv.databinding.ItemHkCardBinding;
import com.github.moyingshi.osc.tv.ui.base.BaseFragment;
import com.github.moyingshi.osc.tv.utils.ImgUtil;
import com.github.moyingshi.osc.tv.utils.Notify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 海阔小程序列表 Fragment，第 1 步最小等价（APK 的 fy1 Fragment 的列表部分）。
 * <p>
 * 只做：拉列表（HkListEvalTask）→ 网格渲染卡片 → 滑到底部翻页 → 卡片点击分流。
 * 分类 Tab、长按菜单、搜索入口、详情页是第 2/3 步。
 * <p>
 * 卡片点击三路分流（规格书 §8.3）：
 * 1. 子页面（hk:// 地址，或带 findRule 的卡片）→ 自我递归进 HkListActivity；
 * 2. 能解析出可播地址 → HkPlay.play（BLOCKER 窄缝，f6 缺失，见 HkPlay）；
 * 3. 标准详情（HkSiteAdapter.detail）是第 2 步，本步不做；
 * 空地址返回“小程序未解析出播放地址”。
 */
public class HkListFragment extends BaseFragment {

    private FragmentHkListBinding mBinding;
    private HkRule rule;
    private String title;
    private boolean listHost;

    private final List<HkItem> items = new ArrayList<>();
    private HkListAdapter adapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int page = 1;
    private boolean loading;
    private boolean hasMore = true;

    public static HkListFragment newInstance(String ruleJson, String title, boolean listHost) {
        HkListFragment fragment = new HkListFragment();
        Bundle args = new Bundle();
        args.putString("ruleJson", ruleJson);
        args.putString("title", title);
        args.putBoolean("listHost", listHost);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentHkListBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        Bundle args = getArguments();
        String ruleJson = args == null ? null : args.getString("ruleJson");
        title = args == null ? "" : args.getString("title", "");
        listHost = args != null && args.getBoolean("listHost", false);
        rule = HkRule.fromJson(ruleJson);
        if (rule == null) {
            if (isAdded()) requireActivity().finish();
            return;
        }
        int span = spanFor(rule.colType());
        GridLayoutManager manager = new GridLayoutManager(requireContext(), span);
        mBinding.list.setLayoutManager(manager);
        adapter = new HkListAdapter();
        mBinding.list.setAdapter(adapter);
        mBinding.list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0 || loading || !hasMore) return;
                int last = manager.findLastVisibleItemPosition();
                if (last >= items.size() - 6) loadPage(page + 1);
            }
        });
        loadPage(1);
    }

    @Override
    protected void initEvent() {
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        executor.shutdownNow();
    }

    /** col_type 里的列数（movie_3 → 3，text_1 → 1；默认 3）。 */
    private static int spanFor(String colType) {
        if (colType == null) return 3;
        if (colType.contains("_1")) return 1;
        if (colType.contains("_2")) return 2;
        if (colType.contains("_3")) return 3;
        if (colType.contains("_4")) return 4;
        return 3;
    }

    private void loadPage(int targetPage) {
        if (loading || rule == null) return;
        loading = true;
        if (targetPage == 1 && mBinding != null) mBinding.loading.setVisibility(View.VISIBLE);
        executor.submit(() -> {
            List<HkItem> batch;
            try {
                HkSession session = HkSession.forRule(rule);
                batch = session == null ? new ArrayList<>() : session.list(rule.url(), String.valueOf(targetPage));
            } catch (Throwable e) {
                SpiderDebug.log("hk", "loadPage %d failed: %s", targetPage, e.toString());
                batch = new ArrayList<>();
            }
            List<HkItem> result = batch;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                loading = false;
                if (mBinding == null) return;
                mBinding.loading.setVisibility(View.GONE);
                if (targetPage == 1) items.clear();
                if (!result.isEmpty()) {
                    page = targetPage;
                    items.addAll(result);
                }
                hasMore = !result.isEmpty();
                adapter.notifyDataSetChanged();
                mBinding.empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    /** 卡片点击：后台懒解析，主线程分流。 */
    private void onCardClick(HkItem item) {
        if (item == null || rule == null) return;
        executor.submit(() -> {
            HkSession session = HkSession.forRule(rule);
            if (session == null) return;
            session.lazyParse(0, new HkSession.HkLazyCallback() {
                @Override
                public void onResolved(String url, String linesJson, int lineIndex) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> onResolvedOnUi(item, url, linesJson, lineIndex));
                }

                @Override
                public void onEmpty() {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> Notify.show("小程序未解析出播放地址"));
                }
            }, item.getUrl(), item.getExtra());
        });
    }

    private void onResolvedOnUi(HkItem item, String url, String linesJson, int lineIndex) {
        if (url == null) url = "";
        if (url.startsWith("hk://")) {
            // 子页面：自我递归（listHost 区分宿主与子页，Activity 栈即返回栈）。
            HkRule sub = rule.derive(url, item.getFindRule(), item.getColType());
            HkListActivity.start(requireActivity(), sub, sub.title(), true);
            return;
        }
        if (HkSession.isPlayable(url)) {
            HkPlay.play(requireActivity(), rule, item, url, linesJson, lineIndex);
            return;
        }
        if (!item.getFindRule().isEmpty()) {
            HkRule sub = rule.derive(url, item.getFindRule(), item.getColType());
            HkListActivity.start(requireActivity(), sub, sub.title(), true);
            return;
        }
        Notify.show("小程序未解析出播放地址");
    }

    private class HkListAdapter extends RecyclerView.Adapter<HkListAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemHkCardBinding binding = ItemHkCardBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
            return new Holder(binding);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            HkItem item = items.get(position);
            holder.binding.title.setText(item.getTitle());
            holder.binding.desc.setText(item.getDesc());
            ImgUtil.load(item.getTitle(), item.getPic(), holder.binding.pic);
            holder.itemView.setOnClickListener(v -> onCardClick(item));
        }

        @Override
        public int getItemCount() {
            return items.size();
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
