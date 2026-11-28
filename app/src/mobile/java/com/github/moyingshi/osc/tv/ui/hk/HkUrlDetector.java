package com.github.moyingshi.osc.tv.ui.hk;

import android.text.TextUtils;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 海阔 URL 类型判定（第 3 步第 1 件）。
 * <p>
 * 瘦身移植自被删实现 {@code b056322b} 的 HkUrlDetector（419 行，照抄 8.83 UrlDetector）：
 * 本步只取 {@link #isImage} 及其依赖（IMAGES/APPS/IMAGE_URLS 三张表、
 * getImageIdByUrl/removeDom），供 HkRouter.dealKind 判定图片地址。
 * 未移植：videoRules 持久化（SharedPreferences hk_big_text）、isVideo/isMusic、
 * clearTag（HkParserKit 已有同款）、getNeedCheckUrl——第 3 步不需要它们，
 * 需要时再按原文补。
 */
public final class HkUrlDetector {

    private HkUrlDetector() {
    }

    private static final List<String> APPS = Arrays.asList(
            ".css", ".html", ".js", ".apk", ".apks", ".apk.1", ".exe", ".zip", ".rar", ".7z", ".hap", ".mtz");
    private static final List<String> IMAGES = Arrays.asList(
            ".ico", ".png", ".PNG", ".jpg", ".JPG", ".jpeg", ".JPEG", ".gif", ".GIF", ".webp", ".svg", ".avif");

    private static final List<String> IMAGE_URLS = Arrays.asList(
            "hiker://images/\u4e66\u7b7e",
            "hiker://images/\u4e91\u5907\u4efd",
            "hiker://images/\u4fee\u6539",
            "hiker://images/\u5220\u9664",
            "hiker://images/\u5386\u53f2",
            "hiker://images/\u5f00\u5173",
            "hiker://images/\u6dfb\u52a0",
            "hiker://images/\u79fb\u52a8",
            "hiker://images/\u8bbe\u7f6e",
            "hiker://images/\u8c46\u74e3",
            "hiker://images/account",
            "hiker://images/backup",
            "hiker://images/bbs",
            "hiker://images/block",
            "hiker://images/bookmark",
            "hiker://images/card_bg",
            "hiker://images/code",
            "hiker://images/collection",
            "hiker://images/cover",
            "hiker://images/history",
            "hiker://images/home",
            "hiker://images/home_bg",
            "hiker://images/home_bookmark2",
            "hiker://images/home_download",
            "hiker://images/home_history2",
            "hiker://images/home_icon_add",
            "hiker://images/home_icon_bookmark",
            "hiker://images/home_icon_bookmark_group",
            "hiker://images/home_icon_code",
            "hiker://images/home_icon_edit",
            "hiker://images/home_icon_fold",
            "hiker://images/home_icon_item",
            "hiker://images/home_late",
            "hiker://images/home_pic1",
            "hiker://images/home_pic2",
            "hiker://images/home_pic3",
            "hiker://images/home_pic4",
            "hiker://images/home_setting",
            "hiker://images/home_tools",
            "hiker://images/ic_bookmark_folder",
            "hiker://images/ic_bookmark_url",
            "hiker://images/icon1",
            "hiker://images/icon2",
            "hiker://images/icon3",
            "hiker://images/icon4",
            "hiker://images/icon_360",
            "hiker://images/icon_360ai",
            "hiker://images/icon_account",
            "hiker://images/icon_add2",
            "hiker://images/icon_ai",
            "hiker://images/icon_ai_big",
            "hiker://images/icon_app3",
            "hiker://images/icon_baidu",
            "hiker://images/icon_baidu_ai",
            "hiker://images/icon_bing",
            "hiker://images/icon_bookmark_add",
            "hiker://images/icon_bottom",
            "hiker://images/icon_box",
            "hiker://images/icon_browser_history",
            "hiker://images/icon_class",
            "hiker://images/icon_clock",
            "hiker://images/icon_close1",
            "hiker://images/icon_close_2",
            "hiker://images/icon_close_2_fill",
            "hiker://images/icon_cloud6",
            "hiker://images/icon_code_view",
            "hiker://images/icon_del",
            "hiker://images/icon_del6",
            "hiker://images/icon_dev",
            "hiker://images/icon_domain_config",
            "hiker://images/icon_down5",
            "hiker://images/icon_download",
            "hiker://images/icon_download6",
            "hiker://images/icon_duck",
            "hiker://images/icon_edit6",
            "hiker://images/icon_felo",
            "hiker://images/icon_filter",
            "hiker://images/icon_find",
            "hiker://images/icon_fire",
            "hiker://images/icon_folder3",
            "hiker://images/icon_foot",
            "hiker://images/icon_fsou",
            "hiker://images/icon_good6",
            "hiker://images/icon_google",
            "hiker://images/icon_his",
            "hiker://images/icon_hot",
            "hiker://images/icon_iask",
            "hiker://images/icon_info",
            "hiker://images/icon_info_fill",
            "hiker://images/icon_isou",
            "hiker://images/icon_kfind",
            "hiker://images/icon_kuake",
            "hiker://images/icon_left5",
            "hiker://images/icon_left6",
            "hiker://images/icon_like",
            "hiker://images/icon_like_fill",
            "hiker://images/icon_menu2",
            "hiker://images/icon_menu6",
            "hiker://images/icon_meta",
            "hiker://images/icon_music3",
            "hiker://images/icon_my",
            "hiker://images/icon_my_fill",
            "hiker://images/icon_offline",
            "hiker://images/icon_phind",
            "hiker://images/icon_pic3",
            "hiker://images/icon_plugin",
            "hiker://images/icon_pure",
            "hiker://images/icon_rect",
            "hiker://images/icon_rect_fill",
            "hiker://images/icon_rect_selected",
            "hiker://images/icon_refresh",
            "hiker://images/icon_right5",
            "hiker://images/icon_right6",
            "hiker://images/icon_save",
            "hiker://images/icon_search5",
            "hiker://images/icon_search6",
            "hiker://images/icon_sel",
            "hiker://images/icon_sel5",
            "hiker://images/icon_sel_fill",
            "hiker://images/icon_select",
            "hiker://images/icon_select_fill",
            "hiker://images/icon_setting5",
            "hiker://images/icon_setting6",
            "hiker://images/icon_share5",
            "hiker://images/icon_share_green",
            "hiker://images/icon_shenma",
            "hiker://images/icon_sougou",
            "hiker://images/icon_star",
            "hiker://images/icon_star_fill",
            "hiker://images/icon_star_fill_green",
            "hiker://images/icon_star_fill_red",
            "hiker://images/icon_thinkany",
            "hiker://images/icon_time6",
            "hiker://images/icon_top",
            "hiker://images/icon_toutiao",
            "hiker://images/icon_trans",
            "hiker://images/icon_txt2",
            "hiker://images/icon_ua",
            "hiker://images/icon_unknown",
            "hiker://images/icon_video2",
            "hiker://images/icon_weibo",
            "hiker://images/icon_weixin",
            "hiker://images/icon_wuzhui",
            "hiker://images/icon_xiutan",
            "hiker://images/icon_yandex",
            "hiker://images/icon_yep",
            "hiker://images/icon_zhihu",
            "hiker://images/icon_zhihu_ai",
            "hiker://images/icon_zhiwang",
            "hiker://images/icon_zip2",
            "hiker://images/logo",
            "hiker://images/menu_back2",
            "hiker://images/menu_backup2",
            "hiker://images/menu_book2",
            "hiker://images/menu_clear2",
            "hiker://images/menu_new2",
            "hiker://images/menu_now2",
            "hiker://images/menu_rules2",
            "hiker://images/menu_search2",
            "hiker://images/placeholder",
            "hiker://images/rule_type_all",
            "hiker://images/rule_type_audio",
            "hiker://images/rule_type_comics",
            "hiker://images/rule_type_live",
            "hiker://images/rule_type_news",
            "hiker://images/rule_type_other",
            "hiker://images/rule_type_pic",
            "hiker://images/rule_type_read",
            "hiker://images/rule_type_tool",
            "hiker://images/rule_type_video",
            "hiker://images/search",
            "hiker://images/tree1",
            "hiker://images/tree2",
            "hiker://images/tree3",
            "hiker://images/tree4",
            "hiker://images/ua",
            "hiker://images/video");

    /** 对应 ImageUrlMapEnum.getIdByUrl：命中内置图 url 回正数、否则 -1。 */
    public static int getImageIdByUrl(String url) {
        if (url == null) return -1;
        return IMAGE_URLS.contains(url) ? 1 : -1;
    }

    /** 内置图 url → 名称（hiker://images/<name> 的 <name>），非内置图回 null。 */
    public static String getImageNameByUrl(String url) {
        if (url == null || !url.startsWith("hiker://images/")) return null;
        return getImageIdByUrl(url) > 0 ? url.substring("hiker://images/".length()) : null;
    }

    /** 照抄 StringUtil.removeDom：剥 scheme 后去掉域名段（异常回原串）。 */
    public static String removeDom(String url) {
        if (TextUtils.isEmpty(url)) return url;
        try {
            String s = url.replaceFirst("http://", "").replaceFirst("https://", "");
            String[] parts = s.split("/");
            if (parts.length <= 1) return s;
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < parts.length; i++) {
                if (i > 1) sb.append('/');
                sb.append(parts[i]);
            }
            return sb.toString();
        } catch (Throwable e) {
            return url;
        }
    }

    /** 照抄 UrlDetector.clearTag：先剥 x5Play:// 前缀，再逐个 replaceOnce 24 个标记，末尾正则剥 #threads=N#。 */
    public static String clearTag(String url) {
        if (TextUtils.isEmpty(url)) return url;
        String out = url;
        if (out.startsWith("x5Play://")) out = out.replaceFirst(Pattern.quote("x5Play://"), "");
        String[] tags = {
                "#ignoreVideo=true#", "#isVideo=true#", "#ignoreImg=true#", "#immersiveTheme#",
                "#noRecordHistory#", "#noHistory#", "#noLoading#", "#isMusic=true#", "#ignoreMusic=true#",
                "#autoPage#", "#autoReader#", "#pre#", "#noPre#", "#fullTheme#", "#readTheme#",
                "#gameTheme#", "#noRefresh#", "#background#", "#autoCache#", "#cacheOnly#",
                "#originalSize#", "#memoryPage#", "#fastPlayMode#", "#checkMetadata=false#"};
        for (String tag : tags) {
            int idx = out.indexOf(tag);
            if (idx >= 0) out = out.substring(0, idx) + out.substring(idx + tag.length());
        }
        return out.replaceAll("#threads=\\d{1,2}#", "");
    }

    /** 照抄 UrlDetector.isImage。 */
    public static boolean isImage(String url) {
        if (TextUtils.isEmpty(url)) return false;
        if (url.startsWith("x5Play://")) return false;
        if (url.contains("@rule=")) return false;
        if (url.contains("@lazyRule=")) return false;
        if (url.contains("isVideo=true")) return false;
        if (getImageIdByUrl(url) > 0) return true;
        for (String app : APPS) {
            if (url.endsWith(app)) return false;
        }
        String dom = removeDom(url);
        if (dom.contains("ignoreImg=true")) return false;
        for (String img : IMAGES) {
            if (dom.contains(img)) return true;
        }
        return false;
    }
}
