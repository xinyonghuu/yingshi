package com.github.moyingshi.osc.tv.api.hk;

import java.util.Locale;

/**
 * 海阔详情选集头型判定（第 3 步第 2 件）。
 * <p>
 * 参照被删实现 {@code b056322b} 的 HkEpisodeQualifier（27 行）移植：除包名外与原文一致。
 * 旧五关资格判定已于 2026-11-13 摘除，只剩头型判定——头卡是封面元数据（已落 vod.pic），
 * 其 url 常是封面图链，不成集。
 */
public final class HkEpisodeQualifier {

    private HkEpisodeQualifier() {
    }

    /** 详情头型 col_type：detail_header 或 movie_1 系头卡。 */
    public static boolean isHeaderColType(String colType) {
        if (colType == null) return false;
        String c = colType.trim().toLowerCase(Locale.ROOT);
        return c.equals("detail_header") || c.contains("movie_1_vertical_pic")
                || c.equals("movie_1") || c.equals("movie_1_left_pic");
    }
}
