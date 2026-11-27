package com.github.moyingshi.osc.tv.api.hk;

import android.app.Activity;

import com.github.catvod.crawler.SpiderDebug;
import com.github.moyingshi.osc.tv.api.SiteApi;
import com.github.moyingshi.osc.tv.ui.activity.VideoActivity;
import com.github.moyingshi.osc.tv.utils.PushParser;
import com.github.moyingshi.osc.tv.utils.ResUtil;

/**
 * 海阔小程序播放接线（第 1 步）：PUSH 直播。
 * <p>
 * 规格书 §8.3 要求卡片点击解析出地址后直调 {@code VideoActivity.f6(...)}，
 * 但实测 yingshi 全仓没有 f6（{@code grep -rn "\.f6(" app/src} 0 命中）；
 * {@code startDirect + hk://} 也走不通——{@code VideoActivity.checkId()} 无条件调
 * {@code getDetail()}，而 hk:// 站点 type=3、api="hk://标题"，在
 * {@code BaseLoader.getSpider} 里落到 {@code SpiderNull}，详情为空、无线路无剧集，
 * {@code applyIntentPlaybackSelection} 没东西可匹配。
 * <p>
 * 中国主人 2026-11-27 16:38 定下用 PUSH 接线：{@code VideoActivity.startDirect}
 * 的 key 用 {@code SiteApi.PUSH}（"push_agent"）；{@code SiteApi.detailContent} 遇
 * PUSH key 时调 {@code pushDetail}，按 id 反解出原地址并合成 "Push" 线路 + "01" 集，
 * {@code playerContent} 走 {@code pushPlayer} 直接播地址。
 * <p>
 * 多线路 JSON 仍传不进去（第 2/3 步 {@code HkPlay} 再做，届时按另一条地址重调）。
 */
public final class HkPlay {

    private HkPlay() {
    }

    /**
     * @param activity    当前 Activity
     * @param rule        所属规则（第 1 步未用，留给第 2/3 步多线路处理）
     * @param item        被点卡片（取标题与封面）
     * @param resolvedUrl 解析出的可播地址；所有 {@code onResolved} 路径里它已是选中线路的地址
     *                    （{@code HkSession.lazyParse}：多线路取 {@code lines.urls.get(0)}，
     *                    单地址直接传 url），故不在此从 linesJson 按下标取
     * @param linesJson   多线路 JSONArray 字符串（无多线路时 ""；第 1 步只记日志）
     * @param lineIndex   选中线路下标（第 1 步只记日志）
     */
    public static void play(Activity activity, HkRule rule, HkItem item, String resolvedUrl, String linesJson, int lineIndex) {
        if (activity == null || resolvedUrl == null || resolvedUrl.isEmpty()) {
            SpiderDebug.log("hk", "play skipped: activity=%s url=%s", String.valueOf(activity), String.valueOf(resolvedUrl));
            return;
        }
        String title = item == null ? "" : item.getTitle();
        String pic = item == null ? "" : item.getPic();
        String id = PushParser.of(resolvedUrl, title).getId();
        // 往返自检：pushDetail 里 PushParser.fromId(id) 必须反解回原地址。
        // 含 "$" 的地址会被当成结构化 id、含 "|" 的地址会被误切、纯符号标题会被拒作后缀，
        // 这三种情况 fromId 都会把 "url|title" 整个当成 url；此时退回裸地址，只丢标题。
        if (!resolvedUrl.equals(PushParser.fromId(id).getUrl())) {
            SpiderDebug.log("hk", "play push id round-trip failed, fallback to bare url: url=%s title=%s id=%s",
                    resolvedUrl, title, id);
            id = resolvedUrl;
        }
        if (resolvedUrl.contains("$") || resolvedUrl.contains("|")) {
            SpiderDebug.log("hk", "play url contains $ or | (push id boundary risk): url=%s lines=%s index=%d",
                    resolvedUrl, linesJson, lineIndex);
        }
        SpiderDebug.log("hk", "play push: key=%s id=%s title=%s url=%s lines=%s index=%d",
                SiteApi.PUSH, id, title, resolvedUrl, linesJson, lineIndex);
        VideoActivity.startDirect(activity, SiteApi.PUSH, id, title, pic, "",
                ResUtil.getString(R.string.push), title, resolvedUrl);
    }
}
