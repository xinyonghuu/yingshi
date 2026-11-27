/**
 * @name 海阔url点击事件生成工具
 * @Author LoyDgIk
 * @version 6
 */
!(function(windows) {

    (function fixMethodToString() {
        const originalToString = Function.prototype.toString;
        //es标准
        Function.prototype.toString = function() {
            const str = originalToString.call(this);

            if (str.trim().startsWith('(') && this.name && this.prototype == void 0 && isShorthand(this)) {
                return this.name + str;
            }
            return str;
        };
        //$处理
        Function.prototype.__toString__ = function() {
            const str = originalToString.call(this);

            if (str.trim().startsWith('(') && this.name && this.prototype == void 0 && isShorthand(this)) {
                return "function " + str;
            }
            return str;
        };
    })();

    var head = {
        empty: "hiker://empty",
        lazyRule: "@lazyRule=",
        rule: "@rule=",
        x5: "x5WebView://",
        input: "input://",
        confirm: "confirm://",
        x5Lazy: "x5Rule://",
        select: "select://",
        webLazy: "webRule://",
    };

    function HikerUrl(param1, param2, param3, param4) {
        this.param = param1 || "";
        this.param1 = param1 === "" ? "" : (param1 || head.empty);
        this.param2 = param2 || "";
        this.param3 = param3;
        this.param4 = param4;
        this.isbase64 = false;
        this.url = this.param1;
        this.ruleOrdinary = this.param2;
    }

    function $(param1, param2, param3, param4) {
        return new HikerUrl(param1, param2, param3, param4);
    }
    var $require = (function() {
        const RequireUtils = com.example.hikerview.ui.rules.service.require.RequireUtils;

        function Module({
            id,
            modulePath,
            importParam,
            headers,
            time,
            code
        }) {
            this.id = id; // 存放路径
            this.exports = {}; //模块数据,
            this.importParam = importParam;
            this.modulePath = modulePath;
            this.headers = headers;
            this.time = time;
            this.code = code;
        }
        Module._cache = new Map();

        Module._extensions = {
            'json': function(module) {
                let script = getScript(module)
                    .trim();
                try {
                    module.exports = JSON.parse(script);
                } catch (e) {
                    Module._extensions.js(module);
                }
            },
            'js': function(module) {
                let script = getScript(module);
                let templateFn = new Function("module", "exports", "__filename", script);
                let exports = module.exports;
                let thisValue = exports;
                let filename = module.id;
                templateFn.call(thisValue, module, exports, filename);
            }
        }

        Module.prototype.load = function() {
            // 原版此处存的是 $.inputParam（全树无此属性 ⇒ 恒 undefined），实际只恢复 $.exports，
            // $.importParam 会「留在最后一个模块的参数上」。这不是笔误可改的地方：
            // 合集壳的 hijackLazyRule / setLastChapterRule 里写的是**无参**
            // $.require('hiker://page/funcTools?rule=…')，funcTools 第 1 行就是
            // let { QING_TITLE, MY_RULE, isIndex } = $.importParam —— 它靠的正是这份「遗留参数」。
            // 若改成保存/恢复 $.importParam，无参调用时会被写回 undefined，
            // funcTools 第 1 行直接 TypeError（2026-11-09 真机：hk#1(eval)#89(Function)#1）。
            let $temp = [$.exports, $.inputParam];
            let referee = this.exports;
            $.exports = this.exports;
            // 只在确实传了参数时覆盖；无参调用沿用上一发的参数（与官方一致）。
            if (this.importParam !== void 0) {
                $.importParam = this.importParam;
            }
            let type = this.id.split("?")[0];
            if (type.endsWith(".json")) {
                Module._extensions.json(this);
            } else {
                Module._extensions.js(this);
            }
            if ($.exports !== this.exports && referee === this.exports) {
                this.exports = $.exports;
            }
            $.exports = $temp[0];
            $.inputParam = $temp[1];
        }

        function getScript(module) { //处理子页面和js:
            let code = "";
            if (module.code !== void 0) return module.code;
            if (module.id.startsWith("hiker://page/")) {
                let codeObject = request(module.id);
                if (!codeObject) throw new Error('Module "' + module.modulePath + '" cannot be found.');
                code = JSON.parse(codeObject)
                    .rule;
            } else {
                if (fileExist(module.id) || module.id.startsWith("hiker://assets/")) {
                    if (module.time) {
                        code = fetchCache(module.modulePath, module.time, module.headers);
                    } else {
                        code = request(module.id);
                    }
                } else if (module.modulePath.startsWith("http")) {
                    code = request(module.modulePath, module.headers);
                    if (!isJsCode(code)) {
                        throw new Error('failed to get module "' + module.modulePath + '" from the network!');
                    }
                    writeFile(module.id, code);
                } else {
                    throw new Error('Module "' + module.modulePath + '" cannot be found.');
                }
                if (module.modulePath.startsWith("http")) {
                    try {
                        let title = "";
                        if (typeof MY_RULE !== "undefined" && MY_RULE != null) {
                            title = MY_RULE.title;
                        } else if (typeof MY_TITLE !== "undefined") {
                            title = MY_TITLE;
                        }
                        RequireUtils.generateRequireMap(title, module.modulePath, "", getPath(module.id)
                            .slice(7));
                    } catch (e) {
                        //log(e.toString());
                    }
                }
            }
            if (code.startsWith("js:")) {
                code = code.slice(3);
            }
            return code;
        }

        function isJsCode(code) {
            if (!code) {
                return false;
            }
            code = code.trim();
            let notJsCode1 = ["<!DOCTYPE", "<html", "<?xml"];
            for (let s of notJsCode1) {
                if (code.startsWith(s)) {
                    return false;
                }
            }
            let notJsCode2 = ["</html>", "</rss>"];
            for (let s of notJsCode2) {
                if (code.endsWith(s)) {
                    return false;
                }
            }
            let jsKey = ["var ", "let ", "const ", "this.", "function", "eval(", "call(", "eval (", "call (", " => ", ")=>"];
            for (let s of jsKey) {
                if (code.includes(s)) {
                    return true;
                }
            }
            return false;
        }

        // 判断 importParam 是否「有实际内容」（undefined/null/空串/空对象都算没有）。
        // 页面模块（hiker://page/…）的模块级变量在加载那一刻就把 importParam 固化了，
        // 典型：合集类规则 funcTools 的 MY_RULE / QING_TITLE。
        function hasImportParam(importParam) {
            if (importParam === void 0 || importParam === null) return false;
            if (typeof importParam === "string") return importParam.length > 0;
            if (typeof importParam === "object") {
                try {
                    return Object.keys(importParam).length > 0;
                } catch (e) {
                    return true;
                }
            }
            return true;
        }

        function require(modulePath, importParam, headers, time) {
            if (typeof headers === "number") {
                time = headers;
                headers = undefined;
            }
            modulePath = modulePath || "";
            let absPathname = require.resolve(modulePath);
            // 页面模块（hiker://page/…）＝带参数的函数库。官方 JSEngine.runScript/evalJS 每次都
            // initStandardObjects 起全新 scope，模块缓存只活一次执行，参数永远不会串；本壳用常驻
            // scope 保留缓存（站表/会话态在 exports 里，⛔ 不许清 Module._cache），就必须自己管住
            // 「这个实例对应哪一套参数」：
            //   · 带参调用：无条件现场重载，并用本次实例覆盖缓存（参数变了就得换实例）；
            //   · 无参调用：优先吃缓存（拿到的就是最近一次实例），缓存空才加载。
            // 关键点：带参调用**不许**读缓存。只要这一条成立，缓存里放的是谁都不会
            // 毒化后续带参调用，于是无参实例照旧可以缓存（dataLoad 这类纯函数库不必反复重载）。
            // 两个反例都是真机踩出来的：
            //   ① 带参调用也读缓存 ⇒ 命中早先的无参实例 ⇒ funcTools 第12行
            //      JSON.parse(base64Decode({})) ⇒ Empty JSON string ⇒ 二级页地址回退域名根；
            //   ② 带参实例不进缓存 ⇒ 无参调用每次新建、$.importParam 落空 ⇒ funcTools 第1行
            //      解构 TypeError（hk#1(eval)#89(Function)#1）。
            let pageModule = absPathname.startsWith("hiker://page/");
            let paramsFresh = pageModule && hasImportParam(importParam);
            if (!paramsFresh && Module._cache.has(absPathname)) {
                return Module._cache.get(absPathname)
                    .exports;
            }
            const module = new Module({
                id: absPathname,
                modulePath,
                importParam,
                headers,
                time
            });
            module.load();
            Module._cache.set(module.id, module);
            return module.exports;
        }
        require.resolve = function(modulePath) {
            if (modulePath.startsWith("../") || modulePath.startsWith("./")) {
                return joinUrl("file:///files/data/" + MY_RULE.title + "/", modulePath).replace("file:///", "hiker://");
            } else if (modulePath.startsWith("https://") || modulePath.startsWith("http://")) {
                return "hiker://files/libs/" + md5(modulePath) + ".js";
            } else if (!modulePath.startsWith("hiker://") && !modulePath.startsWith("file://")) {
                return "hiker://page/" + modulePath;
            } else {
                return modulePath;
            }
        }
        require.cache = Module._cache;
        require.eval = function(code, id, importParam) {
            let id = id || md5(code);
            if (Module._cache.has(id)) {
                return Module._cache.get(id)
                    .exports;
            }
            const module = new Module({
                id,
                importParam,
                code
            });
            module.load();
            Module._cache.set(module.id, module);
            return module.exports;
        }
        return require;
    })()
    //静态方法
    let $staticFunc = {
        toString() {
            if (arguments.length === 0) {
                return "$";
            } else {
                return toStringFun(arguments);
            }
        },
        require: $require,
        importRequire(path, importParam, scope) {
            let code;
            if ($.type(path) === "object") {
                code = path;
                scope = importParam || $.hiker;
            } else {
                code = $.require(path, importParam);
                if ($.type(code) !== "object") {
                    return code;
                }
                scope = scope || $.hiker;

            }
            Object.entries(code)
                .forEach((item) => {
                    scope[item[0]] = item[1];
                });
        },
        type(obj) {
            if (obj == null) {
                return String(obj);
            }
            return typeof obj === "object" || typeof obj === "function" ? class2type[core_toString.call(obj)] || "object" : typeof obj;
        },
        dateFormat(date, text) {
            if ($.type(date) !== "date" && $.type(date) !== "number") {
                throw new Error("Cannot format given Object as a Date");
            }
            if ($.type(text) !== "string") {
                throw new Error("Text should be String");
            }

            let simpleDateFormat;
            if (dateFormatCache.text === text) {
                simpleDateFormat = dateFormatCache.value;
            } else {
                simpleDateFormat = new java.text.SimpleDateFormat(text);
                dateFormatCache.text = text;
                dateFormatCache.value = simpleDateFormat;
            }
            return String(simpleDateFormat.format(date));
        },
        stringify(Data, Pattern) {
            switch (Object.prototype.toString.call(Data)) {
                case "[object Undefined]":
                    return "undefined";
                    break;
                case "[object Null]":
                    return "null";
                    break;
                case "[object Function]":
                    return Data.__toString__();
                    break;
                case "[object Array]":
                    return "[" + Data.map(item => {
                            return $.stringify(item);
                        })
                        .toString() + "]";
                    break;
                case "[object Object]":
                    return "{" + Object.keys(Data)
                        .map(item => {
                            return '"' + item + '":' + $.stringify(Data[item]);
                        })
                        .join(",") + "}";
                    break;
                default:
                    return JSON.stringify(Data);
            }
        },
        log(logX) {
            if ($.type(logX) === "string" && arguments.length > 1) {
                let c = Array.from(arguments, (p) => {
                    if ($.type(p) === "date") {
                        return new java.util.Date(p);
                    } else {
                        return p;
                    }
                });
                let logY = java.lang.String.format.apply(logX, c);
                log(logY);
            } else if ($.type(logX) === "string") {
                log(logX);
            } else {
                log($.stringify(logX));
            }
            return logX;
        },
        extend(o) {
            if (o === void 0) {
                windows.$ = $oneself;
                $extend = {};
                clearMyVar("$:extend");
                return;
            }
            Object.keys(o)
                .forEach(key => {
                    if ($statickey.includes(key)) {
                        o[key] = void 0;
                    }
                });
            Object.assign($extend, o);
            Object.assign($, o);
            //putMyVar("$:extend", uneval($extend));
            putMyVar("$:extend", "(" + $.stringify($extend) + ")");
            return $;
        }
    };
    let $staticAttrs = {
        hiker: windows,
        exports: {},
        importParam: null,
    };
    let $extend = {};
    try {
        $extend = eval.call(null, getMyVar("$:extend", "({})"));
        if (typeof $extend !== "object") {
            $extend = {};
        }
        Object.assign($, $extend, $staticFunc, $staticAttrs);

    } catch (e) {
        clearMyVar("$:extend");
        Object.assign($, $staticFunc, $staticAttrs);
    }
    let $staticFuncK = Object.keys($staticFunc);
    for (let key of $staticFuncK) {
        Object.defineProperty($, key, {
            writable: false,
            configurable: false
        });
    }
    let $staticAttrsK = Object.keys($staticAttrs);
    for (let key of $staticAttrsK) {
        Object.defineProperty($, key, {
            writable: true
        });
    }
    let $statickey = $staticFuncK.concat($staticAttrsK);
    $staticFunc = $staticAttrs = $staticFuncK = $staticAttrsK = void 0;
    let dateFormatCache = {};
    let moduleMap = new Map;
    let class2type = {},
        classtype = ["Boolean", "Number", "String,", "Function", "Array", "Date", "RegExp", "Object", "Error", "Symbol", "Promise"],
        core_toString = class2type.toString;

    classtype.forEach((name) => {
        class2type["[object " + name + "]"] = name.toLowerCase();
    });

    function base64Func(tg, funcStr) {
        if (tg.isbase64) {
            let q = tg.base64quote;
            return 'eval(base64Decode(' + q + base64Encode(funcStr) + q + '));';
        } else {
            return funcStr;
        }
    }

    function goPreRule() {
        let includeMark = ["@include start", "@include end"]
        if (!(typeof MY_RULE !== "undefined" && MY_RULE && MY_RULE.preRule)) {
            return;
        }
        let preRule = String(MY_RULE.preRule)
            .trim();
        let endIndex = 0;
        if (!(preRule.startsWith("/*<$>") && (endIndex = preRule.indexOf("<$>*/")) > -1)) return;
        preRule = preRule.slice(5, endIndex)
            .trim();
        let ruleStartIndex = 0;
        let ruleEndIndex = 0;
        try {
            if ((ruleStartIndex = preRule.indexOf(includeMark[0])) > -1 && (ruleEndIndex = preRule.indexOf(includeMark[1])) > -1) {
                let rules = preRule.slice(ruleStartIndex + includeMark[0].length, ruleEndIndex)
                    .trim()
                    .split("\n");
                for (let it of rules) {
                    it = it.trim();
                    if (!it) continue;
                    it = it.split("=>");
                    if (it.length > 1) {
                        $.hiker[it[1].trim()] = $.require(it[0].trim());
                    } else {
                        $.importRequire(it[0].trim());
                    }
                }
            }
        } catch (e) {
            throw Error("引入预处理插件失败\nat " + String(e));
        }
    }

    function toStringFun(arr) {
        var args = [];
        for (let i = 1, j = 0; i < arr.length; i++, j++) {
            args[j] = $.stringify(arr[i]);
        }
        if (typeof arr[0] === "function") {
            return "(" + arr[0].__toString__() + ")(" + args.toString() + ")";
        } else {
            return "";
        }
    }
    //方法
    $.fn = {
        constructor: HikerUrl,
        b64(quote) {
            this.base64quote = quote || "\"";
            this.isbase64 = !this.isbase64;
            return this;
        },
        rule() {
            return this.param1 + head.rule + (this.param2 || "js:" + base64Func(this, toStringFun(arguments)));
        },
        lazyRule() {
            return this.param1 + head.lazyRule + this.param2 + ".js:" + base64Func(this, toStringFun(arguments));
        },
        x5Rule() {
            return (this.param == "" ? "" : head.x5) + "javascript:var input=" + JSON.stringify(this.param) + ";" + toStringFun(arguments);
        },
        input() {
            return head.input + JSON.stringify({
                value: this.param,
                hint: this.param2,
                js: toStringFun(arguments)
            });
        },
        confirm() {
            return head.confirm + this.param + ".js:" + base64Func(this, toStringFun(arguments));
        },
        x5Lazy() {
            return head.x5Lazy + this.param + "@" + toStringFun(arguments);
        },
        webLazy() {
            return head.webLazy + this.param + "@" + toStringFun(arguments);
        },
        select() {
            return head.select + JSON.stringify({
                title: this.param3,
                selectedIndex: this.param4,
                options: Array.isArray(this.param) ? this.param : [],
                col: this.param2 || 1,
                js: toStringFun(arguments)
            });
        },
        image(f) {
            let result = "";
            if ($.type(this.param) === "object") {
                result += "@headers=" + JSON.stringify(this.param);
            } else {
                result = this.param || "";
                if ($.type(this.param2) === "object") {
                    result += "@headers=" + JSON.stringify(this.param2);
                }
            }
            if ($.type(f) === "function") {
                result += "@js=$.hiker.MY_TITLE = " + $.stringify($.hiker.MY_RULE ? $.hiker.MY_RULE.title : undefined) + ";" + toStringFun(arguments);
            }
            return result;
        }
    }
    HikerUrl.prototype = $.prototype = $.fn;
    Object.defineProperty(windows, "$", {
        value: $,
        writable: true,
        enumerable: true,
        configurable: false
    });
    goPreRule();
    if (typeof $.initHiker === "function") {
        $.initHiker();
    }
})(this);