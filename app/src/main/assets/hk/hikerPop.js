// __hk_hikerpop_native__ 本壳内置 hikerPop 实现。官方原版构建在 XPopup /
// com.example.hikerview 类上，本包不存在，require 到一调用就 TypeError。
// 这里按规则实际用到的 API 面用宿主桥重实现：selectCenter /
// selectBottomResIcon 落到原生 showSelectOptions 列表弹窗——回调对象
// （含其闭包）经 __hkStoreSet/Get 跨作用域保存，选中后在页面引擎里按
// (input, MY_INDEX) 回放 click，click 的返回值继续走 showSelectOptions
// 的结果分发（导航/toast/refreshPage 均生效）；筛选输入框、菜单、长按、
// 宫格列数等纯 UI 能力降级为无操作，核心选择/切源语义完整。
$.exports.setUseStartActivity = function (b) {};
$.exports.canBiometric = function () { return 0; };
$.exports.checkByBiometric = function (cb) {
    return { show: function () {}, dismiss: function () {} };
};
$.exports.scrollSmooth = function () {};
$.exports.updateRecordsBottom = function (data) {
    __hkShowUpdateLog(JSON.stringify(data));
};
$.exports.ResExtraInputBox = function (cfg) { this.cfg = cfg || {}; };
$.exports.ResExtraInputBox.prototype.setTitle = function (t) {
    if (this.cfg) this.cfg.title = t;
};
$.exports.ResExtraInputBox.prototype.setHint = function (h) {
    if (this.cfg) this.cfg.hint = h;
};
$.exports.ResExtraInputBox.prototype.setDefaultValue = function (v) {
    if (this.cfg) this.cfg.defaultValue = v;
};
var __hpSeq = 0;
function __hpStore(handler) {
    __hpSeq++;
    var id = 'hp' + __hpSeq + '_' + new Date().getTime();
    __hkStoreSet('hikerPop:' + id, handler);
    return id;
}
function __hpInvokeJs(id) {
    return "(function(){var h=__hkStoreGet('hikerPop:" + id + "');"
        + "if(!h)return 'hiker://empty';"
        + "var m={change:function(){},changeColumns:function(){},"
        + "scrollToPosition:function(){},setSelectedIndex:function(){},"
        + "setTitle:function(){},dismiss:function(){}};"
        + "if(h.kind==='center')return h.click?h.click(input,MY_INDEX)"
        + ":'hiker://empty';"
        + "var it=h.items&&h.items[MY_INDEX];"
        + "return h.click?h.click(it,MY_INDEX,m):'hiker://empty';})()";
}
$.exports.selectCenter = function (opt) {
    opt = opt || {};
    var id = __hpStore({ kind: 'center', click: opt.click });
    showSelectOptions({
        title: opt.title || '',
        options: opt.options || [],
        selectedIndex: (opt.position == null ? -1 : opt.position),
        col: opt.columns || 1,
        js: __hpInvokeJs(id)
    });
    return { show: function () {}, dismiss: function () {} };
};
$.exports.selectBottomResIcon = function (opt) {
    opt = opt || {};
    var items = opt.iconList || opt.options || [];
    var id = __hpStore({ kind: 'bottom', items: items, click: opt.click });
    showSelectOptions({
        title: opt.title || '',
        options: items,
        selectedIndex: (opt.position == null ? -1 : opt.position),
        col: opt.columns || 1,
        bottom: true,
        js: __hpInvokeJs(id)
    });
    return { show: function () {}, dismiss: function () {} };
};
$.exports.toastMeg = function (t) {
    try { toast(String(t == null ? '' : t)); } catch (e) {}
    return { show: function () {}, dismiss: function () {} };
};
$.exports.seekCenter = function (opt) {
    opt = opt || {};
    var min = 39, max = 100;
    var cur = (opt.pos == null ? 0 : opt.pos) + min;
    var arr = [];
    for (var p = min; p <= max; p++) arr.push(p + '%');
    var id = __hpStore({ kind: 'seek', opt: opt });
    showSelectOptions({
        title: opt.title || '',
        options: arr,
        selectedIndex: (cur < min ? -1 : cur - min),
        col: 4,
        js: "(function(){var h=__hkStoreGet('hikerPop:" + id + "');"
            + "if(!h)return 'hiker://empty';var p=MY_INDEX+39;"
            + "try{if(h.opt.rightClick)h.opt.rightClick(p-39,61);}catch(e){}"
            + "return 'hiker://empty';})()"
    });
    return { show: function () {}, dismiss: function () {} };
};
$.exports.inputAutoRow = function (opt) {
    try { toast('本壳暂不支持该输入框'); } catch (e) {}
    return { show: function () {}, dismiss: function () {} };
};
$.exports.inputTwoRow = function (opt) {
    try { toast('本壳暂不支持该输入框'); } catch (e) {}
    return { show: function () {}, dismiss: function () {} };
};
