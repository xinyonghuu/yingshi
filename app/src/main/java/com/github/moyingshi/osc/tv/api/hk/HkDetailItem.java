package com.github.moyingshi.osc.tv.api.hk;

/**
 * 详情解析出的原始条目（第 3 步第 1 件）。
 * <p>
 * 参照被删实现 {@code b056322b} 的 HkDetailItem（80 行）移植：除包名外与原文一致。
 * 选择器：detail_find_rule 6 段 = 列表;标题;图片;描述;链接;样式。
 * JS：setResult 项的 title/url/pic_url/desc + 可选 line（线路名）/col_type。
 */
public class HkDetailItem {

    private String title = "";
    private String url = "";
    private String pic = "";
    private String desc = "";
    private String line = "";
    private String colType = "";

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? "" : title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url == null ? "" : url;
    }

    public String getPic() {
        return pic;
    }

    public void setPic(String pic) {
        this.pic = pic == null ? "" : pic;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc == null ? "" : desc;
    }

    public String getLine() {
        return line;
    }

    public void setLine(String line) {
        this.line = line == null ? "" : line;
    }

    public String getColType() {
        return colType;
    }

    public void setColType(String colType) {
        this.colType = colType == null ? "" : colType;
    }

    /**
     * 干跑分类标（第 3 步第 2 件）：{@link HkDetailBridge#toVod} 的分类环写标，
     * {@link HkChapters#admitEpisodeRuns} 读标（ACTION 剔除、PLAYABLE 作段级认据）。
     * 未分类为 null，与 UNKNOWN 同口径保留（结构认据仍可放行）。
     */
    private HkEpisodeDryClassifier.Verdict dryVerdict;

    public HkEpisodeDryClassifier.Verdict getDryVerdict() {
        return dryVerdict;
    }

    public void setDryVerdict(HkEpisodeDryClassifier.Verdict dryVerdict) {
        this.dryVerdict = dryVerdict;
    }

    /** 海报卡/纯展示项：无可播链接。 */
    public boolean isDisplayOnly() {
        String u = url.trim();
        return u.isEmpty() || "javascript:;".equalsIgnoreCase(u) || "javascript:void(0)".equalsIgnoreCase(u);
    }

    /** 从第 2 步的列表卡片转详情条目（HkSiteAdapter.detail 改走 toVod 时用）。 */
    public static HkDetailItem fromHkItem(HkItem item) {
        HkDetailItem di = new HkDetailItem();
        if (item == null) return di;
        di.setTitle(item.getTitle());
        di.setUrl(item.getUrl());
        di.setPic(item.getPic());
        di.setDesc(item.getDesc());
        di.setLine(item.getLine());
        di.setColType(item.getColType());
        return di;
    }
}
