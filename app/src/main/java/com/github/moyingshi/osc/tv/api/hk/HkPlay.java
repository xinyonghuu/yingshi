package com.github.moyingshi.osc.tv.api.hk;

import android.app.Activity;

import com.github.catvod.crawler.SpiderDebug;
import com.github.moyingshi.osc.tv.R;
import com.github.moyingshi.osc.tv.api.SiteApi;
import com.github.moyingshi.osc.tv.ui.activity.VideoActivity;
import com.github.moyingshi.osc.tv.utils.PushParser;
import com.github.moyingshi.osc.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * 第 3 步第 3 件：{@code play()} 已接进 {@link HkPlayData}——多线路 JSON（对象形或
 * JSONArray 形）按 lineIndex 选线播；单地址老路（第 1 步的直链）不受影响。
 * 分线请求头只进模型与日志：PUSH 直播没有请求头通道（见 HkPlayData 类注）。
 * <p>
 * 第 3 步第 1 件：并入被删实现 {@code b056322b} HkPlay（258 行）的字段模型——
 * 多线路三列（urls/names/lineHeaders 按下标对齐）、音频表、弹幕/歌词/字幕、
 * pics 漫画、playType、refreshOnly、lazyStagePage 等。静态 {@link #play} 的
 * PUSH 接线与 #8 的 setPlayer 直播强制直接播保持不动。
 */
public final class HkPlay {

    // ================= 播放解析结果模型（第 3 步，参照 b056322b HkPlay） =================

    private String url = "";
    private final Map<String, String> headers = new HashMap<>();
    /**
     * P2 多线路 JSON 的三列保全（8.83 PlayData 语义：urls/names/headers 按下标对齐，
     * 逐线路取、禁止跨线路合并）。单地址路径沿用 url/headers 旧字段。
     */
    private final List<String> urls = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final List<Map<String, String>> lineHeaders = new ArrayList<>();

    public HkPlay() {
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url == null ? "" : url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void putHeader(String key, String value) {
        if (key != null && value != null) headers.put(key, value);
    }

    public List<String> getUrls() {
        return urls;
    }

    public void addUrl(String u) {
        if (u != null && !u.isEmpty()) urls.add(u);
    }

    public List<String> getNames() {
        return names;
    }

    public void addName(String n) {
        names.add(n == null ? "" : n);
    }

    public List<Map<String, String>> getLineHeaders() {
        return lineHeaders;
    }

    public void addLineHeaders(Map<String, String> h) {
        lineHeaders.add(h == null ? new HashMap<>() : h);
    }

    /** 按下标取某条线路的地址（越界回首条；无多线时回 url 旧字段）。 */
    public String getUrl(int line) {
        if (urls.isEmpty()) return getUrl();
        return urls.get(line >= 0 && line < urls.size() ? line : 0);
    }

    /** 按下标取某条线路的请求头（对齐 8.83 getHeaders(url,playData,i)；无多线时回旧字段）。 */
    public Map<String, String> getHeaders(int line) {
        if (!lineHeaders.isEmpty()) {
            Map<String, String> m = lineHeaders.get(line >= 0 && line < lineHeaders.size() ? line : 0);
            if (m != null && !m.isEmpty()) return m;
        }
        return getHeaders();
    }

    /** 按下标取某条线路的名称（越界回首条；无多线时回空）。 */
    public String getName(int line) {
        if (names.isEmpty()) return "";
        return names.get(line >= 0 && line < names.size() ? line : 0);
    }

    private String subtitle = "";
    /** P1：#isM3u8# 强制按 m3u8 处理。 */
    private boolean forceM3u8;
    /** P1：#concat# 多段地址拼接播放（url 里以 && 或换行分隔的多段）。 */
    private boolean concat;
    /** P1：#fastPlayMode# / #threads=N# 极速模式标记（透传给播放器）。 */
    private boolean fastPlayMode;
    private int threads;
    /**
     * P1/G49：{urls:[], audioUrls:[]} 音视频分离时的音频地址全表（8.83 PlayData
     * audioUrls 是 List 全量）。
     */
    private final List<String> audioUrls = new ArrayList<>();
    /** G49/G59：PlayData 附带字段 danmu（弹幕地址）。 */
    private String danmu = "";
    /** G49/G59：PlayData 附带字段 lyric（歌词）。 */
    private String lyric = "";
    /**
     * 本结果是否经过 pics:// 解析（parsePicsInto 跑过）：消费方据此区分
     * 「不是漫画」与「是漫画但拆完无图」——后者不许静默落进视频装配。
     */
    private boolean picsAttempted;
    /** P1：pics:// 漫画多图模式的图片列表（非视频，UI 提示不支持）。 */
    private final List<String> pics = new ArrayList<>();
    /**
     * 产出本结果的真实页面地址（懒解析/规则求值实际跑在哪个页面上）：
     * pics 分流进阅读器时作会话 pageUrl（图片组装的页面上下文）。
     * 集地址本身（尤其 pics:// 串全文）不是页面地址，绝不许拿它冒充。
     */
    private String pageUrl = "";
    /** P1：ed2k/magnet/thunder/ftp 等特殊协议类型（透传给播放器前识别）。 */
    private String playType = "";
    /**
     * 官 dealLazyRule 收口标记：本次 {@code @lazyRule=} 求值只起副作用
     * （putMyVar + refreshPage），回址是陪衬值（多为 {@code hiker://empty}），
     * 不是播放目标。置位时 url 已清空，调用方应重刷当前列表让新闭包生效，
     * 不进播放链、也不许按「解析失败」报错。与 {@link HkDetail#isTabSwitch()} 同口径。
     */
    private boolean refreshOnly;
    /**
     * 懒产出来源标记：本结果由哪一页喂出的懒段产出（净形页址）。
     * 出口 checkPlayExit 据此判「产出就是页址本身」的降级产物——那是
     * 懒函数在规则体残缺时把剧集网页原样吐回的形态，绝不许当播放地址出门。
     * 非懒产出恒空。
     */
    private String lazyStagePage = "";

    public String getLazyStagePage() {
        return lazyStagePage == null ? "" : lazyStagePage;
    }

    public void setLazyStagePage(String lazyStagePage) {
        this.lazyStagePage = lazyStagePage == null ? "" : lazyStagePage;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle == null ? "" : subtitle;
    }

    public boolean isForceM3u8() {
        return forceM3u8;
    }

    public void setForceM3u8(boolean forceM3u8) {
        this.forceM3u8 = forceM3u8;
    }

    public boolean isConcat() {
        return concat;
    }

    public void setConcat(boolean concat) {
        this.concat = concat;
    }

    public boolean isFastPlayMode() {
        return fastPlayMode;
    }

    public void setFastPlayMode(boolean fastPlayMode) {
        this.fastPlayMode = fastPlayMode;
    }

    public int getThreads() {
        return threads;
    }

    public void setThreads(int threads) {
        this.threads = threads;
    }

    public List<String> getAudioUrls() {
        return audioUrls;
    }

    public void addAudioUrl(String u) {
        if (u != null && !u.isEmpty()) audioUrls.add(u);
    }

    /** 音频地址首条（无则 ""）——旧单值口的等价便捷口。 */
    public String getAudioUrl() {
        return audioUrls.isEmpty() ? "" : audioUrls.get(0);
    }

    public String getDanmu() {
        return danmu == null ? "" : danmu;
    }

    public void setDanmu(String danmu) {
        this.danmu = danmu == null ? "" : danmu;
    }

    public String getLyric() {
        return lyric == null ? "" : lyric;
    }

    public void setLyric(String lyric) {
        this.lyric = lyric == null ? "" : lyric;
    }

    public String getPageUrl() {
        return pageUrl == null ? "" : pageUrl;
    }

    public void setPageUrl(String pageUrl) {
        this.pageUrl = pageUrl == null ? "" : pageUrl;
    }

    public List<String> getPics() {
        return pics;
    }

    public void addPic(String pic) {
        if (pic != null && !pic.isEmpty()) pics.add(pic);
    }

    public boolean isPicsAttempted() {
        return picsAttempted;
    }

    public void setPicsAttempted(boolean picsAttempted) {
        this.picsAttempted = picsAttempted;
    }

    public String getPlayType() {
        return playType == null ? "" : playType;
    }

    public void setPlayType(String playType) {
        this.playType = playType == null ? "" : playType;
    }

    public boolean isRefreshOnly() {
        return refreshOnly;
    }

    public void setRefreshOnly(boolean refreshOnly) {
        this.refreshOnly = refreshOnly;
    }

    public boolean isEmpty() {
        return (url == null || url.isEmpty()) && pics.isEmpty();
    }

    // ================= 静态 PUSH 播放接线（第 1 步，保持不动） =================

    /**
     * @param activity    当前 Activity
     * @param rule        所属规则（第 1 步未用，留给第 2/3 步多线路处理）
     * @param item        被点卡片（取标题与封面）
     * @param resolvedUrl 解析出的可播地址；当前各调用方传的已是选中线路的地址
     *                    （{@code HkSession.lazyParse} 与 {@code playRouted} 都取首线），
     *                    play() 再按 lineIndex 从 linesJson 里取一次作核对——
     *                    取出的地址为空时仍以 resolvedUrl 为准。
     * @param linesJson   多线路 JSON（PlayData 对象形或 JSONArray 形；无多线路时 ""）。
     *                    第 3 步第 3 件：经 {@link HkPlayData} 解析，按 lineIndex 选线；
     *                    选出的地址为空或与 resolvedUrl 不一致时以 resolvedUrl 为准。
     * @param lineIndex   选中线路下标（第 3 步第 3 件起真生效；之前只记日志）
     */
    public static void play(Activity activity, HkRule rule, HkItem item, String resolvedUrl, String linesJson, int lineIndex) {
        if (activity == null || resolvedUrl == null || resolvedUrl.isEmpty()) {
            SpiderDebug.log("hk", "play skipped: activity=%s url=%s", String.valueOf(activity), String.valueOf(resolvedUrl));
            return;
        }
        String title = item == null ? "" : item.getTitle();
        String pic = item == null ? "" : item.getPic();
        // 多线路：经 HkPlayData 按下标选线（两种 JSON 形态都认；解析失败按单地址走）。
        String playUrl = resolvedUrl;
        HkPlayData data = HkPlayData.fromJson(linesJson);
        if (data != null && data.isMultiLine()) {
            String indexed = data.urlAt(lineIndex);
            if (indexed != null && !indexed.trim().isEmpty()) {
                playUrl = indexed.trim();
            }
            SpiderDebug.log("hk", "play multiline: lines=%d index=%d lineName=%s headers=%s subtitle=%s danmu=%s lyric=%s",
                    data.lineCount(), lineIndex, data.nameAt(lineIndex),
                    data.headersAt(lineIndex).isEmpty() ? "none" : "yes",
                    data.getSubtitle().isEmpty() ? "no" : "yes",
                    data.getDanmu().isEmpty() ? "no" : "yes",
                    data.getLyric().isEmpty() ? "no" : "yes");
        } else if (data != null) {
            SpiderDebug.log("hk", "play single playdata: subtitle=%s danmu=%s lyric=%s",
                    data.getSubtitle().isEmpty() ? "no" : "yes",
                    data.getDanmu().isEmpty() ? "no" : "yes",
                    data.getLyric().isEmpty() ? "no" : "yes");
        }
        String id = PushParser.of(playUrl, title).getId();
        // 往返自检：pushDetail 里 PushParser.fromId(id) 必须反解回原地址。
        // 含 "$" 的地址会被当成结构化 id、含 "|" 的地址会被误切、纯符号标题会被拒作后缀，
        // 这三种情况 fromId 都会把 "url|title" 整个当成 url；此时退回裸地址，只丢标题。
        if (!playUrl.equals(PushParser.fromId(id).getUrl())) {
            SpiderDebug.log("hk", "play push id round-trip failed, fallback to bare url: url=%s title=%s id=%s",
                    playUrl, title, id);
            id = playUrl;
        }
        if (playUrl.contains("$") || playUrl.contains("|")) {
            SpiderDebug.log("hk", "play url contains $ or | (push id boundary risk): url=%s lines=%s index=%d",
                    playUrl, linesJson, lineIndex);
        }
        SpiderDebug.log("hk", "play push: key=%s id=%s title=%s url=%s lines=%s index=%d",
                SiteApi.PUSH, id, title, playUrl, linesJson, lineIndex);
        // TODO DIAGNOSTIC 已删（中国主人 2026-11-28 17:29 下令）：点卡片直接播放，不再弹窗确认。
        VideoActivity.startDirect(activity, SiteApi.PUSH, id, title, pic, "",
                ResUtil.getString(R.string.push), title, playUrl);
    }
}
