package com.github.moyingshi.osc.tv.ui.hk;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.github.catvod.crawler.SpiderDebug;
import com.github.moyingshi.osc.tv.api.hk.HkDetail;
import com.github.moyingshi.osc.tv.api.hk.HkDetailBridge;
import com.github.moyingshi.osc.tv.api.hk.HkItem;
import com.github.moyingshi.osc.tv.api.hk.HkJsRuntime;
import com.github.moyingshi.osc.tv.api.hk.HkPlay;
import com.github.moyingshi.osc.tv.api.hk.HkPlayData;
import com.github.moyingshi.osc.tv.api.hk.HkRouter;
import com.github.moyingshi.osc.tv.api.hk.HkRule;
import com.github.moyingshi.osc.tv.api.hk.HkRuleManager;
import com.github.moyingshi.osc.tv.api.hk.HkSession;
import com.github.moyingshi.osc.tv.bean.Vod;
import com.github.moyingshi.osc.tv.databinding.FragmentHkListBinding;
import com.github.moyingshi.osc.tv.databinding.ItemHkCardBinding;
import com.github.moyingshi.osc.tv.theme.WebHtvAlertDialogBuilder;
import com.github.moyingshi.osc.tv.ui.activity.VideoActivity;
import com.github.moyingshi.osc.tv.ui.base.BaseFragment;
import com.github.moyingshi.osc.tv.utils.ImgUtil;
import com.github.moyingshi.osc.tv.utils.Notify;
import com.github.moyingshi.osc.tv.utils.Util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 海阔小程序列表 Fragment，第 1 步最小等价（APK 的 fy1 Fragment 的列表部分）。
 * <p>
 * 只做：拉列表（HkListEvalTask）→ 网格渲染卡片 → 滑到底部翻页 → 卡片点击分流。
 * 分类 Tab、长按菜单、搜索入口、详情页是第 2/3 步。
 * <p>
 * 卡片点击四路分流（规格书 §8.3）：
 * 1. 子页面（hk:// 地址，或带 findRule 的卡片）→ 自我递归进 HkListActivity；
 * 2. 能解析出可播地址 → HkPlay.play（BLOCKER 窄缝，f6 缺失，见 HkPlay）；
 * 3. 标准详情（HkRouter.detail，第 3 步第 1 件）：先 dealKind 预分流（pics/x5/
 * image/magnet），再 lazyParse 解析，解析后仍按 dealKind 分流，最后第 4 路走
 * HkRouter.detail 预分流（@lazyRule=/js:/@rule=js: 分流 + V4 委托
 * HkSiteAdapter.detail），按 HkDetail 出口分发（直接播/种类分流/分类切换/
 * 文本弹窗/动作处理/V4 详情）。
 * <p>
 * 第 2 步 extras 第 6 件：col_type "input" 的卡渲染成真输入框（extra.defaultValue
 * 作初始文本，extra.onChange 防抖 300ms 后经 replayJs 回放）；规则调 refreshPage()
 * 时重跑 loadPage(1)；lazyRule 返回 "海阔视界￥home_rule￥…" 时导入并弹
 * “导入成功，是否打开？”（APK 的 q1 行为），返回 "hiker://empty" 时静默返回。
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
    /**
     * 每张输入卡的当前文本（第 6 件）：RecyclerView 复用 + refreshPage 重载都会重绑，
     * 文本只认这张表，不认 ViewHolder 里的 EditText。键见 {@link #inputKey}。
     */
    private final Map<String, String> inputTexts = new HashMap<>();
    /** onChange 防抖（主线程 Handler，约 300ms；回放本身走 Task 后台线程）。 */
    private final Handler inputHandler = new Handler(Looper.getMainLooper());
    /** 每张输入卡待执行的防抖回放（按 inputKey 分开：A 框刚输完立刻去 B 框输，不能把 A 的 onChange 顶掉）。 */
    private final Map<String, Runnable> pendingInputReplays = new HashMap<>();
    /** 本 Fragment 挂在运行时上的 refreshPage 监听（销毁时只清自己的那一个）。 */
    private HkJsRuntime.RefreshListener refreshListener;

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
        // 输入框卡占满整行（否则在 movie_3 的 3 列网格里只有 1/3 宽，没法输入）。
        manager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (position < 0 || position >= items.size()) return 1;
                return "input".equals(items.get(position).getColType()) ? span : 1;
            }
        });
        mBinding.list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0 || loading || !hasMore) return;
                int last = manager.findLastVisibleItemPosition();
                if (last >= items.size() - 6) loadPage(page + 1);
            }
        });
        // 第 6 件：refreshPage 真实现。规则求值线程上收到刷新调用时切回主线程重跑
        // loadPage(1)（loadPage 的 loading 守卫可能吞掉一次并发中的刷新，可接受：
        // 正在跑的那次加载本来就会取到最新值）。
        refreshListener = () -> {
            if (!isAdded()) return;
            android.app.Activity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded() || mBinding == null) return;
                loadPage(1);
            });
        };
        HkSession session = HkSession.forRule(rule);
        if (session != null) session.getRuntime().setRefreshListener(refreshListener);
        loadPage(1);
    }

    @Override
    protected void initEvent() {
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        inputHandler.removeCallbacksAndMessages(null);
        pendingInputReplays.clear();
        if (rule != null && refreshListener != null) {
            try {
                HkSession session = HkSession.forRule(rule);
                // 只清自己的监听：同一运行时可能正被另一个同规则 Fragment 用。
                if (session != null && session.getRuntime().getRefreshListener() == refreshListener) {
                    session.getRuntime().setRefreshListener(null);
                }
            } catch (Throwable ignored) {
            }
            refreshListener = null;
        }
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
    private void onCardClick(HkItem item, int position) {
        if (item == null || rule == null) return;
        // 第 3 步第 1 件：dealKind 预分流（HkRouter.dealKind，官 dealWithUrl 顺序）。
        // pics/x5/image/magnet 在懒解析之前分流：pics 与 image 在我们这里无查看器，
        // x5 是 webview 规则页（无内置 webview），magnet 直推播放器 Thunder 线。
        // video/web/"" 落回原有 lazyParse 流程（video 由 isPlayable 播，web 由详情路接）。
        // 注意：图片类卡片地址按官语义即图片本身（不是视频缩略图），故不再按视频播。
        String kind = HkRouter.dealKind(item.getUrl());
        if ("pics".equals(kind)) {
            Notify.show("漫画图片暂不支持查看");
            return;
        }
        if ("x5".equals(kind)) {
            Notify.show("网页规则暂不支持内置打开");
            return;
        }
        if ("image".equals(kind)) {
            Notify.show("图片查看暂不支持");
            return;
        }
        if ("magnet".equals(kind)) {
            // 磁力/种子直推播放器：先过路由器 playRoute（设 playType、拆增强、
            // 拦动作地址），再 push（pushPlayer 经 Source.fetch → Thunder.fetch 转换）。
            // 第 3 步第 2 件：点击下标预放（身份＝将要传给 play 的地址），
            // playRouted 入口按身份领走，供懒求值的 MY_POSITION 用。
            HkDetailBridge.setClickPosition(position, rule.title(), item.getUrl());
            executor.submit(() -> playRouted(item, item.getUrl()));
            return;
        }
        executor.submit(() -> {
            HkSession session = HkSession.forRule(rule);
            if (session == null) return;
            session.lazyParse(0, new HkSession.HkLazyCallback() {
                @Override
                public void onResolved(String url, String linesJson, int lineIndex) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> onResolvedOnUi(item, url, linesJson, lineIndex, position));
                }

                @Override
                public void onEmpty() {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> Notify.show("小程序未解析出播放地址"));
                }
            }, item.getUrl(), item.getExtra());
        });
    }

    private void onResolvedOnUi(HkItem item, String url, String linesJson, int lineIndex, int position) {
        if (url == null) url = "";
        // 第 6 件：“生成规则”卡的 lazyRule 返回 "海阔视界￥home_rule￥<规则 JSON>"，
        // 导入并弹“导入成功，是否打开？”（APK 的 q1 行为，与 HkListActivity 的导入成功弹窗一致）。
        if (url.startsWith("海阔视界￥home_rule￥") || url.startsWith("海阔视界￥home_rule_v2￥")) {
            importGeneratedRule(url);
            return;
        }
        // 第 6 件：“来个示例”卡的 lazyRule 返回 "hiker://empty"——规则 JS 里已自行
        // refreshPage()/toast，这里静默返回，不再弹“小程序未解析出播放地址”。
        // 真正的 onEmpty（什么都没解析出）仍走 HkSession.HkLazyCallback.onEmpty 弹原 toast。
        if (url.isEmpty() || "hiker://empty".equals(url)) return;
        // 规则主动返回的 toast（如“生成规则”空输入时的 "toast://请先在上面输入…"）：
        // 显示规则自己的文案，而不是最后的通用“未解析出播放地址”。
        if (url.startsWith("toast://")) {
            Notify.show(url.substring("toast://".length()));
            return;
        }
        if (url.startsWith("hk://")) {
            // 子页面：自我递归（listHost 区分宿主与子页，Activity 栈即返回栈）。
            HkRule sub = rule.derive(url, item.getFindRule(), item.getColType());
            HkListActivity.start(requireActivity(), sub, sub.title(), true);
            return;
        }
        // 第 3 步第 1 件：懒解析结果的 dealKind 分流（与 onCardClick 预分流同口径，
        // 覆盖 @lazyRule= 求值后才现形的 magnet/pics/x5/image）。image 类地址不再
        // 按视频播（isPlayable 只看 scheme，会把 http 图片误当视频推播放器）。
        String resolvedKind = HkRouter.dealKind(url);
        if ("magnet".equals(resolvedKind)) {
            // 第 3 步第 2 件：点击下标预放（身份＝将要传给 play 的地址）。
            HkDetailBridge.setClickPosition(position, rule.title(), url);
            executor.submit(() -> playRouted(item, url));
            return;
        }
        if ("pics".equals(resolvedKind)) {
            Notify.show("漫画图片暂不支持查看");
            return;
        }
        if ("x5".equals(resolvedKind)) {
            Notify.show("网页规则暂不支持内置打开");
            return;
        }
        if ("image".equals(resolvedKind)) {
            Notify.show("图片查看暂不支持");
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
        if (!rule.detailFindRule().isEmpty() || !rule.sdetailFindRule().isEmpty()) {
            // 第 4 路：标准详情。第 3 步第 1 件：走 HkRouter.detail 预分流
            // （@lazyRule=/js:/@rule=js: 分流 + V4 委托 HkSiteAdapter.detail），
            // 按 HkDetail 出口分发；detail() 要取数 + 跑规则 JS，必须在后台线程做，
            // 沿用本 Fragment 的单线程 executor（与点卡懒解析同一条）。
            // 注意：传解析后的 url（不是 item 原地址）——@lazyRule= 已由 lazyParse
            // 求过一次，路由器不再重复求值；路由器内部按需把改写过的地址换进 item 副本。
            String detailUrl = url;
            executor.submit(() -> routeDetail(item, detailUrl, position));
            return;
        }
        Notify.show("小程序未解析出播放地址");
    }

    /** 第 4 路：路由器详情分流（后台线程）→ 按 HkDetail 出口在 UI 线程分发。 */
    private void routeDetail(HkItem item, String detailUrl, int position) {
        HkDetail detail;
        try {
            detail = new HkRouter(rule).detail(detailUrl, item, false);
        } catch (Throwable e) {
            SpiderDebug.log("hk", "router detail failed rule=%s: %s", rule.title(), e.toString());
            if (!isAdded()) return;
            String message = e.getMessage() == null ? "海阔详情加载失败" : e.getMessage();
            requireActivity().runOnUiThread(() -> Notify.show(message));
            return;
        }
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> dispatchDetail(item, detail, position));
    }

    /** HkDetail 出口分发（UI 线程）：按 directPlayUrl/dealKind/tabSwitch/textResult/actionUrl/vod/rawItems 的优先级处理。 */
    private void dispatchDetail(HkItem item, HkDetail detail, int position) {
        if (!isAdded() || detail == null) {
            Notify.show("小程序未解析出播放地址");
            return;
        }
        // 分类切换：重刷当前列表。
        if (detail.isTabSwitch()) {
            loadPage(1);
            return;
        }
        // 解析失败：不推空详情。
        if (detail.isLazyParseFailed()) {
            Notify.show("小程序未解析出播放地址");
            return;
        }
        // 文本结果：弹窗显示。
        if (!detail.getTextResult().isEmpty()) {
            showDetailText(detail.getTextResult());
            return;
        }
        // 动作 URL：toast:// 显示文案，copy:// 复制，其余 toast 提示暂不支持。
        if (!detail.getActionUrl().isEmpty()) {
            handleActionUrl(detail.getActionUrl());
            return;
        }
        // 直接播放：经路由器 playRoute 再 push（与 magnet 预分流同口）。
        if (!detail.getDirectPlayUrl().isEmpty()) {
            String direct = detail.getDirectPlayUrl();
            if (direct.startsWith("海阔视界￥home_rule￥") || direct.startsWith("海阔视界￥home_rule_v2￥")) {
                importGeneratedRule(direct);
                return;
            }
            // 第 3 步第 2 件：点击下标预放（身份＝将要传给 play 的地址）。
            HkDetailBridge.setClickPosition(position, rule.title(), direct);
            executor.submit(() -> playRouted(item, direct));
            return;
        }
        // 按种类分流。
        if (!detail.getDealKind().isEmpty()) {
            String kind = detail.getDealKind();
            String dealUrl = detail.getDealUrl();
            if ("magnet".equals(kind) || "video".equals(kind)) {
                // 第 3 步第 2 件：点击下标预放（身份＝将要传给 play 的地址）。
                HkDetailBridge.setClickPosition(position, rule.title(), dealUrl);
                executor.submit(() -> playRouted(item, dealUrl));
                return;
            }
            if ("pics".equals(kind)) {
                Notify.show("漫画图片暂不支持查看");
                return;
            }
            if ("x5".equals(kind)) {
                Notify.show("网页规则暂不支持内置打开");
                return;
            }
            if ("image".equals(kind)) {
                Notify.show("图片查看暂不支持");
                return;
            }
            if ("web".equals(kind)) {
                Notify.show("网页浏览暂不支持内置打开");
                return;
            }
            if ("novel".equals(kind)) {
                Notify.show("小说阅读暂不支持");
                return;
            }
            Notify.show("小程序未解析出播放地址");
            return;
        }
        // V4 标准详情。
        if (detail.getVod() != null) {
            VideoActivity.startDirectResolved(requireActivity(), detail.getVod());
            return;
        }
        // @rule=js: 原始条目（rawItems）：第 3 步第 2 件 toVod 接入——在后台线程
        // 装成 Vod 再推详情；装不出可用选集（空 flags）或抛错时 toast，不推空详情。
        if (detail.getRawItems() != null && !detail.getRawItems().isEmpty()) {
            HkDetail rawDetail = detail;
            executor.submit(() -> {
                Vod vod;
                try {
                    vod = HkDetailBridge.toVod(rule, item.getUrl(), rawDetail);
                } catch (Throwable e) {
                    SpiderDebug.log("hk", "toVod failed rule=%s: %s", rule.title(), e.toString());
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> Notify.show("小程序未解析出播放地址"));
                    return;
                }
                if (!isAdded()) return;
                if (vod == null || vod.getFlags() == null || vod.getFlags().isEmpty()) {
                    SpiderDebug.log("hk", "detail rawItems toVod empty flags: %d", rawDetail.getRawItems().size());
                    requireActivity().runOnUiThread(() -> Notify.show("小程序未解析出播放地址"));
                    return;
                }
                Vod result = vod;
                requireActivity().runOnUiThread(() -> VideoActivity.startDirectResolved(requireActivity(), result));
            });
            return;
        }
        Notify.show("小程序未解析出播放地址");
    }

    /**
     * 经路由器 playRoute 解析后再 push 播放（magnet 预分流与详情 directPlayUrl 共用）。
     * 本方法在后台线程调：playRoute 在后台跑，HkPlay.play（起 Activity）切回 UI 线程。
     * refreshOnly（懒规则只起副作用）重刷列表；pics（漫画多图）toast；其余按首线路 push。
     * 多线路（第 3 步第 6 件）：不走单地址 push（只播第 0 线），改走详情 Vod
     * （HkDetailBridge.toVodFromPlayData，一线一 Flag）进播放器，切线即标准
     * Flag 切换；单线仍走 HkPlay.play 老路。
     */
    private void playRouted(HkItem item, String episodeUrl) {
        HkPlay resolved;
        try {
            // 第 3 步第 2 件：点击下标按（规则名＋被点集址）身份领走（无预放恒 -1，
            // 与接线前一致）；领走即清槽，身份不合的残值也在此清掉。
            int clickPos = HkDetailBridge.consumeClickPosition(rule.title(), episodeUrl);
            resolved = new HkRouter(rule).play(episodeUrl, null, clickPos);
        } catch (Throwable e) {
            SpiderDebug.log("hk", "router play failed rule=%s: %s", rule.title(), e.toString());
            if (!isAdded()) return;
            String message = e.getMessage() == null ? "海阔懒解析未返回播放地址，请换一条试试" : e.getMessage();
            requireActivity().runOnUiThread(() -> Notify.show(message));
            return;
        }
        if (resolved == null) {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> Notify.show("小程序未解析出播放地址"));
            return;
        }
        // refreshOnly：回址已清空，语义是「重刷当前列表」，不进播放链。
        if (resolved.isRefreshOnly()) {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> loadPage(1));
            return;
        }
        // pics：漫画多图，非视频——本件无阅读器，toast。
        if (!resolved.getPics().isEmpty()) {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> Notify.show("漫画图片暂不支持查看"));
            return;
        }
        if (resolved.isEmpty()) {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> Notify.show("小程序未解析出播放地址"));
            return;
        }
        HkPlayData data = HkPlayData.fromPlay(resolved);
        String linesJson = data.isMultiLine() ? data.toJson() : "";
        if (!isAdded()) return;
        if (data.isMultiLine()) {
            // 第 3 步第 6 件：多线路不走单地址 push 直播（只播第 0 线、其余线不可达），
            // 改走详情 Vod（带全部 Flag 线路）进播放器，切线即标准 Flag 切换。
            // 组装无 I/O，地址已由 playRoute 解析过；在 UI 线程现组现推。
            Vod vod = HkDetailBridge.toVodFromPlayData(rule, item, data);
            if (vod.getFlags() == null || vod.getFlags().isEmpty()) {
                requireActivity().runOnUiThread(() -> Notify.show("小程序未解析出播放地址"));
                return;
            }
            requireActivity().runOnUiThread(() -> VideoActivity.startDirectResolved(requireActivity(), vod));
            return;
        }
        requireActivity().runOnUiThread(() -> HkPlay.play(requireActivity(), rule, item, resolved.getUrl(0), linesJson, 0));
    }

    /** 详情文本结果弹窗（文本型条目求值出的非 URL 文本，如更新日志）。 */
    private void showDetailText(String text) {
        android.app.Activity host = getActivity();
        if (host == null || host.isFinishing() || host.isDestroyed()) return;
        new WebHtvAlertDialogBuilder(host)
                .setTitle("海阔")
                .setMessage(text)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 动作 URL 处理：toast:// 显示文案，copy:// 复制到剪贴板，其余暂不支持。 */
    private void handleActionUrl(String actionUrl) {
        if (actionUrl.startsWith("toast://")) {
            Notify.show(actionUrl.substring("toast://".length()));
            return;
        }
        if (actionUrl.startsWith("copy://")) {
            Util.copy(actionUrl.substring("copy://".length()));
            return;
        }
        Notify.show("暂不支持的操作");
    }

    /** “生成规则”卡：导入 lazyRule 返回的规则 JSON，成功弹“导入成功，是否打开？”。 */
    private void importGeneratedRule(String payload) {
        String marker = payload.startsWith("海阔视界￥home_rule_v2￥") ? "海阔视界￥home_rule_v2￥" : "海阔视界￥home_rule￥";
        String json = payload.substring(marker.length());
        executor.submit(() -> {
            HkRule imported;
            try {
                // 文件 IO + LocalSource.invalidate，放后台线程（importRuleJson 的要求）。
                imported = HkRuleManager.get().importRuleJson(json);
            } catch (Throwable e) {
                SpiderDebug.log("hk", "import generated rule failed: %s", e.toString());
                if (!isAdded()) return;
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                android.app.Activity activity = getActivity();
                if (activity == null) return;
                activity.runOnUiThread(() -> Notify.show("导入失败：" + message));
                return;
            }
            if (!isAdded()) return;
            HkRule result = imported;
            android.app.Activity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                android.app.Activity host = getActivity();
                if (host == null || host.isFinishing() || host.isDestroyed()) return;
                new WebHtvAlertDialogBuilder(host)
                        .setTitle("导入成功")
                        .setMessage("已导入规则“" + result.title() + "”，是否打开？")
                        .setPositiveButton("打开", (dialog, which) -> {
                            if (!host.isFinishing() && !host.isDestroyed()) {
                                HkListActivity.start(host, result, result.title());
                            }
                        })
                        .setNegativeButton("关闭", null)
                        .show();
            });
        });
    }

    /**
     * 输入框值的保存键：优先用 desc（提示文案）——它是规则给输入框的稳定身份，
     * refreshPage 重载、上下插入卡片后位置可能漂移而 desc 不变；desc 为空时
     * 退化为位置键。风险：同一列表里两张 desc 相同的输入卡会共用一份文本
     * （AI 生成器两张 desc 不同，不受影响）。
     */
    private String inputKey(HkItem item, int position) {
        String desc = item.getDesc();
        return TextUtils.isEmpty(desc) ? "pos:" + position : "desc:" + desc;
    }

    /** 文本变化防抖约 300ms 后跑 onChange；回放走 replayJs 的 Task 后台线程，绝不在 UI 线程跑 JS。 */
    private void scheduleInputChange(HkItem item, String key, String text) {
        String js = item.extra("onChange");
        if (TextUtils.isEmpty(js)) return;
        Runnable old = pendingInputReplays.remove(key);
        if (old != null) inputHandler.removeCallbacks(old);
        final String code = js;
        final String value = text;
        Runnable replay = () -> {
            pendingInputReplays.remove(key);
            if (!isAdded() || rule == null) return;
            HkSession session = HkSession.forRule(rule);
            if (session == null) return;
            // replayJs 先置全局 input 为新文本、MY_INDEX 为 0，再求值 onChange
            // （onChange 是 $.toString 包的箭头函数，引用自由变量 input，见第 5 件）。
            session.getRuntime().replayJs(code, value, 0);
        };
        pendingInputReplays.put(key, replay);
        inputHandler.postDelayed(replay, 300);
    }

    /** extra.height 的整数值（"2" → 2；缺失或非法时 1）。 */
    private static int extraHeight(HkItem item) {
        try {
            return Integer.parseInt(item.extra("height").trim());
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private class HkListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_CARD = 0;
        private static final int TYPE_INPUT = 1;

        @Override
        public int getItemViewType(int position) {
            return "input".equals(items.get(position).getColType()) ? TYPE_INPUT : TYPE_CARD;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_INPUT) return createInputHolder(parent);
            ItemHkCardBinding binding = ItemHkCardBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
            return new Holder(binding);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            HkItem item = items.get(position);
            if (holder instanceof InputHolder) {
                ((InputHolder) holder).bind(item, position);
                return;
            }
            Holder card = (Holder) holder;
            card.binding.title.setText(item.getTitle());
            card.binding.desc.setText(item.getDesc());
            ImgUtil.load(item.getTitle(), item.getPic(), card.binding.pic);
            card.itemView.setOnClickListener(v -> onCardClick(item, position));
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

        /**
         * 输入框行（第 6 件，程序化建视图，不新增 layout 资源）：
         * 标题行（extra.titleVisible=false 时隐藏）+ EditText（desc 作 hint）。
         * 输入框行不设点击监听，点它就是聚焦输入。
         */
        private InputHolder createInputHolder(ViewGroup parent) {
            android.content.Context context = parent.getContext();
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (4 * context.getResources().getDisplayMetrics().density);
            row.setPadding(pad, pad, pad, pad);
            TextView titleView = new TextView(context);
            titleView.setTextSize(13);
            row.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            EditText editText = new EditText(context);
            editText.setTextSize(14);
            row.addView(editText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new InputHolder(row, titleView, editText);
        }

        class InputHolder extends RecyclerView.ViewHolder {
            final TextView titleView;
            final EditText editText;
            private HkItem boundItem;
            private String boundKey;
            /** 正在用程序化 setText 回填时为 true，TextWatcher 不触发保存/onChange。 */
            private boolean binding;

            InputHolder(View row, TextView titleView, EditText editText) {
                super(row);
                this.titleView = titleView;
                this.editText = editText;
                this.editText.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        if (binding || boundItem == null || boundKey == null) return;
                        String text = s == null ? "" : s.toString();
                        inputTexts.put(boundKey, text);
                        scheduleInputChange(boundItem, boundKey, text);
                    }
                });
            }

            void bind(HkItem item, int position) {
                boundItem = item;
                boundKey = inputKey(item, position);
                // titleVisible=false → 不显示标题行；缺失时默认显示。
                boolean showTitle = !"false".equalsIgnoreCase(item.extra("titleVisible"));
                titleView.setVisibility(showTitle ? View.VISIBLE : View.GONE);
                if (showTitle) titleView.setText(item.getTitle());
                editText.setHint(item.getDesc());
                // type "textarea" + height 2 → 多行输入框（minLines 2）；其余单行。
                if ("textarea".equalsIgnoreCase(item.extra("type")) && extraHeight(item) >= 2) {
                    editText.setSingleLine(false);
                    editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                    editText.setMinLines(extraHeight(item));
                } else {
                    editText.setSingleLine(true);
                    editText.setInputType(InputType.TYPE_CLASS_TEXT);
                }
                // 已输入的文本优先于 extra.defaultValue：refreshPage 重载后用户刚输的
                // 内容不被默认值的重求值覆盖；没输过时用 defaultValue（规则求值时已取过 getMyVar）。
                String text = inputTexts.get(boundKey);
                if (text == null) text = item.extra("defaultValue");
                binding = true;
                editText.setText(text);
                if (editText.getText() != null) editText.setSelection(editText.getText().length());
                binding = false;
            }
        }
    }
}
