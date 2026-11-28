# hk-replicate-1：复刻 APK 海阔小程序引擎 · 第 1 步（最小闭环）

> Recovery anchor（任务文档即执行状态；对话被压缩后按此恢复）。

## 目标（完成句）

在 yingshi 复刻网盘 APK 那套海阔小程序引擎的第 1 步——最小闭环：打开一条内置规则、列表出卡、点卡解析出播放地址；在测试分支 `test/hk-replicate-1` 上本地提交，不推送、不触发 CI。

- 分支：`test/hk-replicate-1`（自 main `b80c52c` 切出）
- task_guard 会话：`hk-replicate-1`（`--mode standard`）
- 基线：main `b80c52cc`（2026-11-27 核实），本地干净，0 条预先脏路径
- 规格书：`~/workspace/yingshi-haikuo-analysis/REPLICATION.md`（980 行）
- 复刻源：APK 反编译结果（`~/workspace/apk-analysis/jadx-full/`），不是被删的 b056322b 引擎（失败方案，不抄代码；只看 §12.4 列出的可参照部分）

## 状态

- [x] 侦察：精读规格书 §1/§3–§5/§6.2/§7/§8.3/§11/§13/§14/§15/附录 A；逐项核对反编译原文（`a02`/`f42`/`o52`/`h12`/`n52`/`l22`/`x12`/`w12`/`HkListActivity`）；核对 yingshi 接线点（`LocalSource`/`SiteDialog`/`VodConfig`/`VideoActivity`/`BaseActivity`/`BaseFragment`/`ImgUtil`/`Notify`/`SpiderDebug`/`MediaType` 用法）。
- [x] 分支 + guard 会话已开；旧残留会话 `hk-restore-1` 已 abort（状态归档 `aborted-20261127152019-hk-restore-1`，工作区未动）。
- [x] 资源：`Hikerurl.js` 已从 APK 原样复制（md5 `ee794a0d…`）；`crypto-java.js` 已从 APK 复制（yingshi 原本缺失，md5 `ec2122fa…`）；`plugin.js` 与 `aes.js` 与 APK md5 完全相同，无需复制。
- [x] 种子规则：`assets/hk/rules/ai-generator.json`（规则标题“AI生成器”，自 `hiker-rules` 的 `2024-12-13-38个小程序.json` 原样复制，`find_rule` 以 `setResult(d);` 结尾）；另有一条自写最小测试规则 `hk-test.json`（规则标题“海阔测试”，纯 `js:` 静态列表，含真实可播 MP4 地址）。assets 文件名用 ASCII 别名，`ensureSeeded()` 按规则标题落盘（task_guard 对中文文件名转义后 scope 匹配不上，故改 ASCII 名）。
- [x] 代码：见下表（全部写完；重读时修了 3 处：`doParseDom(args, false)` 调用的签名不匹配、`evalRuleJs` 未剥 `js:` 前缀、`refreshWebProxyRule` 漏注册）。
- [x] 验证：逐行重读每个改动文件 + `git diff --check` 通过 + import/父类冲突核对；本地无编译器，CI 编译由父 agent 接手。
- [x] 提交：`task_guard finish`（本地提交 + recovery 标签），不推送。

## ✅ 已解决：播放接线（2026-11-27 16:38 中国主人定 PUSH，16:39 改完）

规格书 §13.4 断言“播放器 | `VideoActivity.f6(...)` 已存在”，§8.3 给出三个 `VideoActivity.f6(...)` 调用点。但实测 yingshi 全仓无此方法：

- `grep -rn "\.f6(" app/src` → 0 命中（2026-11-27 亲自核实）。
- `VideoActivity` 现有播放入口只有 `start(...)` 系列重载（`start(Activity, String url)` 在 798 行：裸地址走 PUSH 站点播放）、`startDirectResolved(Activity, Vod)`、`startDirect(...)`、`startFromHistory(...)` 等；无 `f6`，无 10 参播放入口。
- 因此“多线路 JSONArray 字符串 + 下标”作为 `f6()` 第 8/9 参的接线方式在 yingshi 无处可接。

`startDirect + hk://` 也走不通（2026-11-27 16:25 核实）：`VideoActivity.checkId()` 无条件调 `getDetail()` → `SiteApi.detailContent(hk://key)`；hk:// 站点 type=3、api="hk://标题"，在 `BaseLoader.getSpider` 里既不是 CatSpider 也不是 .py/.js/.jar，落到 `SpiderNull`，详情为空 → 无线路无剧集 → `applyIntentPlaybackSelection` 没东西可匹配。`:771` 的 null 容错救不了（失败是详情为空，不是空指针）。

定下的接法（中国主人 2026-11-27 16:38 点选 PUSH）：

```java
VideoActivity.startDirect(activity, SiteApi.PUSH, PushParser.of(resolvedUrl, title).getId(),
        title, item.getPic(), "", ResUtil.getString(R.string.push), title, resolvedUrl);
```

核实过的点（2026-11-27 16:39，读代码）：

- `PushParser.of(url, title).getId()` = `url|title`（标题空时为裸 url）；`pushDetail` 里 `PushParser.fromId(id)` 按最后一个 `|` 反切回原地址，标题须含字母/数字且不是 URL/请求头才认作后缀——往返成立。
- `ResUtil.getString(R.string.push)` = "Push"，与 `pushDetail` 里 `vod.setPlayFrom(...)` 用的是同一个字符串，`startDirect` 的 playFlag 与之匹配。
- `VideoActivity.startDirect` 9 参重载 mobile（:1027）与 leanback（:1011）同签名，`HkPlay` 在 main 源集，两 flavor 都能编过；9 参版转调 10 参版时 `resumeFromHistory=false`。
- `pic` 取自卡片 `HkItem.getPic()`（种子规则“海阔测试”的 pic 为空，传 ""，不影响播地址）。
- `resolvedUrl` 不用从 linesJson 按下标取：`HkSession.lazyParse` 的全部 `onResolved` 路径里它已是选中线路的地址（多线路取 `lines.urls.get(0)`，单地址直接传 url）；`HkPlay.play` 里不解析 linesJson（JSONArray 元素键名未核对，见 REPLICATION.md §8.3，猜键会弄坏已能播的地址）。
- `"$"` / `"|"` 边界：`PushParser.fromId` 会把含 `$` 的 id 整个当结构化 id、把含 `|` 的地址误切。`HkPlay.play` 里用真解析器做往返自检——反解不回原地址就退回裸地址（只丢标题）并记日志；地址含 `$` 或 `|` 时另记一条边界风险日志。行为不改，待真机测。
- 没用裸地址的 `VideoActivity.start(activity, url)`——那版丢标题、线路名和封面。

`HkPlay.play` 只改这一处（新 task_guard 会话 `hk-replicate-1-push`，本地提交，见下）。

## 第 1 步文件清单（按依赖顺序）

| # | 文件 | 对应 APK 角色 | 状态 |
|---|---|---|---|
| ① | `app/src/main/java/.../api/hk/HkRule.java` | `o52`：Gson 模型 + version 三类型 TypeAdapter + `title()/url()/userAgent()/hasSearch()/derive()/validate()` | 已写，已重读 |
| ② | `app/src/main/java/.../api/hk/HkHttp.java` | `h12`：OkHttp 客户端 + `resolveUrl` 模板解析（`[firstPage]`/`fypage@算术@`/`fyclass` 等）+ `fetch` | 已写，已重读 |
| ③ | `app/src/main/java/.../api/hk/HkJsRuntime.java` | `f42` 最小核：Rhino scope + `registerApis()`（196 名，33 个核心真实接线，其余记日志 stub）+ `initRequire()` + `evalJs/evalRule/parseDom` 族 + `fetch/request/setResult` | 已写，已重读 |
| ③a | `app/src/main/java/.../api/hk/HkItem.java` | `w12`：卡片模型（title/url/pic/desc/sel/colType/extra/content/line/findRule/group/pages/preRule/rule） | 已写，已重读 |
| ③b | `app/src/main/java/.../api/hk/HkListEvalTask.java` | `x12`：列表求值（超驰检查 → 取数 → bootstrap → `t2(find_rule)` → 快照结果） | 已写，已重读 |
| ③c | `app/src/main/java/.../api/hk/HkEvalGuard.java` | `w32`：求值超驰监控（每规则自增序号） | 已写，已重读 |
| ③d | `app/src/main/java/.../api/hk/HkInitTask.java` | `l22`：引擎初始化 Callable（case 0 全量 / case 1 极简） | 已写，已重读 |
| ④ | `app/src/main/java/.../api/hk/HkEngine.java` | `a02`：运行时缓存 + mtime 失效 + `.js:` 求值 | 已写，已重读 |
| ⑤ | `app/src/main/java/.../api/hk/HkSession.java` | `n52` 最小核：URL 白名单（12 schemes）+ 懒解析 `lazyParse()` + `parse()` 相对地址补齐 | 已写，已重读 |
| ⑤a | `app/src/main/java/.../api/hk/HkRuleStore.java` | 第 1 步最小存储（非完整 `HkRuleManager`）：规则目录、文件名清洗、种子复制、内置站点 | 已写，已重读 |
| ⑤b | `app/src/main/java/.../api/hk/HkPlay.java` | 播放接线：PUSH 直播（`startDirect` + `push_agent`，2026-11-27 16:39 接完，见上） | 已写，已重读 |
| ⑥ | `app/src/main/assets/Hikerurl.js`（APK 原样） | 同名资源 | 已复制（md5 与 APK 一致） |
| ⑥ | `app/src/main/assets/crypto-java.js`（APK 原样，yingshi 原缺） | 同名资源 | 已复制（md5 与 APK 一致） |
| ⑥ | `app/src/main/assets/plugin.js`、`aes.js` | 与 APK md5 相同，无改动 | 已核实 |
| ⑥ | `app/src/main/assets/hk/rules/ai-generator.json`、`hk-test.json` | 内置种子规则 | 已写，JSON 已校验 |
| ⑦ | `app/src/mobile/java/.../ui/hk/HkListActivity.java` | 列表宿主：`start()`（>200KB 走 MD5 + `hk_temp_rule.txt`）+ 挂 Fragment | 已写，已重读 |
| ⑦ | `app/src/mobile/java/.../ui/hk/HkListFragment.java` | `fy1` 最小等价：拉列表、网格渲染、分页、卡片点击分流 | 已写，已重读 |
| ⑦ | `app/src/mobile/res/layout/activity_hk_list.xml`、`fragment_hk_list.xml`、`item_hk_card.xml` | 宿主/Fragment/卡片布局（仿 `activity_dice.xml` 风格） | 已写 |
| ⑦ | `app/src/mobile/AndroidManifest.xml` | 注册 `HkListActivity` | 已改 |
| ⑦ | `app/src/main/java/.../utils/LocalSource.java` | `hk://` 分支恢复为入口：保留跳过 settings 里陈旧 `hk://` 条目，末尾追加 `HkRuleStore.builtinSites()` | 已改 |
| ⑦ | `app/src/mobile/java/.../ui/dialog/SiteDialog.java` | `onTextClick` 单点拦截：`hk://` 站点改开 `HkListActivity`，不走 `setSite` | 已改 |
| Dep | `gradle/libs.versions.toml` + `app/build.gradle` | 加 `org.mozilla:rhino:1.7.15`（规格书 §13.3） | 已改 |

## T1() 核心 API（33 个真实接线；共注册 196 名，其余 163 个记日志 stub）

注册总数 196 = 附录 A 的 194 名 + `fetch` + `require`（分发器名，附录 A 的 grep 提取漏了这两个；另补上转录时漏掉的 `refreshWebProxyRule`，重读时发现并已补）。

真实接线的 33 个：`request`、`fetch`、`fetchCache`、`parseDom`、`parseDomForArray`、`parseDomForHtml`、`pdfh`、`pd`、`_pdfh`、`_pd`、`setResult`、`getJsPlugin`、`getJsLazyPlugin`、`getCryptoJS`、`log`、`xlog`、`toast`、`require`、`getRule`、`getVar`、`putVar`、`getItem`、`setItem`、`getMyVar`、`putMyVar`、`fileExist`、`md5`、`joinUrl`、`buildUrl`、`sleep`、`getPageTitle`、`setTitle`、`getUrl`。

说明：规格书 §15 称“约 20 个核心”；实测列表求值（尤其 AI 生成器种子：`getMyVar` 在 `defaultValue` 处求值）与 `pdfh` 族需要 33 个。`parseDom` 族第 1 步只做 jsoup CSS 选择器 + `&&` 链 + `:eq()` + `text/html/@attr` 提取；完整 Hiker 选择器语法（§11 的 `pdfh/pdfa/xpath` 全量语义）留待第 2/3 步。`setItem/getItem/getMyVar/putMyVar` 第 1 步为运行时内存态（不持久化），持久化留待第 2 步 `HkRuleManager`。

## 已知的未完成与未确认项（写进提交说明）

1. **已解决（2026-11-27 16:39）**：播放接线 = `startDirect` + `push_agent`（PUSH 直播），见上。f6 不存在、startDirect+hk:// 走不通的原因已记。多线路 JSON 仍传不进去——第 2/3 步 `HkPlay` 再做（届时按另一条地址重调）；`"$"` / `"|"` 边界风险已在 `HkPlay.play` 里做往返自检 + 记日志，待真机测。
2. Rhino 精确版本未核实（§14.13）：规格书 §13.3 给 `1.7.15`，按此加；APK 内实际版本待真机/反编译对照。
3. `parseDom` 族为第 1 步近似实现；`xpath/xpa/xpathArray` 为 stub（d22 case 16 待接）。
4. `showSelectOptions` / 内置 hikerPop / `HkHikerPopBridge` 是第 2 步（b22 case 12 只做 `hiker://files/libs/`、`hiker://assets/`、http(s) 三路 + hikerPop 记日志返回空实现）。
5. `.hiker` 云口令解密算法未确认（§14.1）；`HkRuleManager`（导入/导出/云口令/`.hkzip`）、`HkSiteAdapter.detail()`、`HkSearchActivity` 均是第 2 步，不在本步。
6. `HkJsRuntime.init()` 里 `f1()/e1()` 读配置在 APK 内未逐行核对，本步跳过（记日志）。
7. `preRule` 在初始化时跑（`M()`），失败只记日志不阻断（与 `l22` 的 `O0(th)` 语义一致的保守做法）。
8. TV/leanback 不动；品牌与包名不动（未碰 `applicationId`/`namespace`/包路径）。
9. `hiker://files/` 在本步映射到应用 `filesDir`（`Y1` 原文未逐行核对）；`require` 的 CommonJS 根是 `file://<规则目录>/libs/`（按 §4.9）。
10. `h12.k()` 465 行之后的分支未全读，通用分支（POST body 按表单编码）是大体等价实现。
11. 重读时发现并已修：`evalRuleJs` 必须剥 `js:` 前缀——`js:` 在 ES6 里是标签，`js: let ...` 是 SyntaxError（标签后不能跟词法声明），不剥则 `let` 开头的规则求值必失败；`parseDom` 调用签名不匹配已修。
12. 种子规则“海阔测试”的卡片 `pic` 为空（未猜测示例视频的封面地址），列表里图片位显示占位图；不影响点卡解析地址的闭环验证。
13. 构建修（2026-11-27 17:37）：构建运行 45969479112 报 `HkJsRuntime.java:21: cannot find symbol`——`StrongCachingModuleScriptProvider` 的 import 写错了包（`commonjs.module`，实测在 `commonjs.module.provider`），已改成 provider 包；第 22 行 `UrlModuleSourceProvider` 本就在 provider 包，未动。构建里另一个失败原因（签名 secrets 未配）与本次无关，未碰工作流。
14. 构建修（2026-11-27 19:15）：构建运行 #2（45983119653）编译失败，两个原因——`HkPlay.java:66` 用了 `R.string.push` 但没 import R；`HkListActivity.java:43` 声明了 `private static String R;`，遮蔽了生成的 R 类，导致第 107 行 `R.id.container` 报错。已修：HkPlay 补 `import com.github.moyingshi.osc.tv.R;`；HkListActivity 静态字段 R 改名 RULE_JSON（三处：声明、赋值、返回，注释同步），并补 R import。
15. 入口修（2026-11-27 22:16）：真机验收发现，从“实验室 → 本地源”列表点“海阔测试”只 `setHome`，首页标题变成“海阔测试”、内容空白——`LocalSourceDialog.onItemClick` 点任何源都是直接 `setHome`，没有 hk:// 拦截；第 1 步的拦截只写在了 SiteDialog（换源对话框）里，实验室这条路没接。已修：LocalSourceDialog.onItemClick 开头加 hk:// 拦截，与 SiteDialog 一致（`HkRuleStore.load` 取规则，成功则 `HkListActivity.start` 并 dismiss；规则为 null 时落回原有 setHome 行为）。
16. 真机再验修（2026-11-28 07:40，会话 `hk-fix-step1-retest`）：构建 #4 重跑成功后真机验收出两个新问题，根因都已定位并修了。
    - **测试视频 0 KB/s**：播放接线本身是通的（播放器打开，线路“推送”、选集 01 都对），卡住是因为 `hk-test.json` 里的 3 个谷歌 MP4 地址（`commondatastorage.googleapis.com/gtv-videos-bucket/sample/`）现在返回 403，根本下不到数据。已换成普通 curl 就返回 `200 video/mp4` 的地址：Big Buck Bunny → `test-videos.co.uk/.../Big_Buck_Bunny_720_10s_1MB.mp4`；Elephants Dream → Jellyfish（`test-videos.co.uk/.../Jellyfish_720_10s_1MB.mp4`）；For Bigger Blazes → Flower（`interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4`），卡片标题按实际内容改。
    - **偏离规格**：规格原定的 w3schools `mov_bbb.mp4` 没用——普通 curl 请求它返回 403，只有浏览器 UA 才返回 200；而手机播放器发的是 Exo 的 UA，不是浏览器 UA，用它有风险。所以 3 个地址全改用普通 curl 就 200 的上面三个。
    - **AI 生成器列表空白**：规则 `find_rule` 里 `storage0.getItem("list", [])` 回 null，`arr.reverse()` / `.filter()` 直接抛错，整条求值中断。根因是 `HkJsRuntime.java` 的 `getItem` 分发（旧 340–341 行）根本不看第二个参数（默认值），键不存在就回 null；而 `plugin.js:308` 的 `storage0.getItem` 正是把默认值经 `toJsonStr` 传给全局 `getItem` 再 `toJsonObj`。已修三处（都在 `HkJsRuntime.java`，全部内存态、不加持久化）：(a) `getItem` 分发：键不存在时回第二个参数，与 `getMyVar` 一致（新 371–375 行）；(b) `getVar` 分发同类缺陷顺带修了（新 362–366 行，`storage0.getVar` 靠它；`setItem` 不动）；(c) `BOOTSTRAP_SHIM` 加 `typeof storage0 === 'undefined'` 守卫的 storage0 定义（新 122–144 行：getItem/setItem/getMyVar/putMyVar/getVar/putVar + getPublicItem/setPublicItem，配 `__hkToJsonStr`/`__hkToJsonObj`，ES5 写法，照抄 `plugin.js` 281–347 行语义；`__hkToJsonObj` 对非字符串值直接原样回，比原版多一层类型守卫，防 `startsWith` 抛错）。另补 `getPublicItem`/`setPublicItem`/`clearPublicItem` 分发 case（新 379–390 行），背后是与 `items` 并列的内存 map `publicItems`（新 172–173 行）；`clearPublicItem` 有参清指定键、无参清全部。注意：这三个名字还必须进 `CORE_APIS` 名单（新 101 行）——`registerApis`（272 行）对非核心 API 直接记日志 stub，根本不进 `dispatch`，只加 case 会是死代码。未按 `docs/miniapp-align-5-storage.md`（2026-11-15 旧引擎、旧路径）改，没动 kv.json 落盘等完整对齐方案。
    - 验收范围不变：实验室 → 点“海阔测试”，出 3 张卡，每张点开能播；“AI生成器”列表出输入框卡片，不再空白。详情页、搜索、播放器内切线路仍是第 2、3 步，这次不验。
17. 模块下载 404 修（2026-11-28 09:28，会话 `hk-fix-module-repo`，在独立 worktree `~/workspace/yingshi-wt-modulefix` 做，主仓库工作区当时被同步任务占用未碰）：真机验收发现播放器换不了内核——ijk/mpv 等内核是按需下载的模块包，下载地址由 `ModuleManager.java` 里写死的 `REPO = "xinyonghuu/moyingshi"` 拼出来，tag 取 `BuildConfig.BUILD_TAG`（CI 写入的本版 release tag）。yingshi 构建的 tag 是 yingshi 自己的（如 `v5.6.0-beta-202611280743`），拼到旧仓库上就成了 `https://github.com/xinyonghuu/moyingshi/releases/download/v5.6.0-beta-202611280743/module-ijk-arm64-v8a-560.zip`，实测 404，所以模块下载不了、播放器换不了内核；yingshi 自己那份地址实测是 302（文件在 #5 的 release 里，6 个模块分包都在）。已修：只改 `ModuleManager.java` 第 40 行，`REPO` 改成 `"xinyonghuu/yingshi"`；tag 逻辑、GithubProxy、下载器未动。核实：`RELEASES_API`（42 行）、`zipUrl`（244 行）、`totalBundleUrl`（250 行）都走 REPO；`module/` 目录里再无别的 `xinyonghuu/moyingshi` 硬编码；另有两处同名硬编码不在模块下载链路上，未动——`GithubProxy.java:123`（更新通道 update.json）和 `SettingEnhanceFragment.java:64`（设置页里的仓库主页链接）。
    - 注意：这一行是 yingshi 的本地分叉，以后每日同步如果上游改了这行，要保留 yingshi 的值，不要跟回 moyingshi。

## 验证（本地）

- 逐行重读每个改动文件；`git diff --check` 通过；每个新增符号的 import 在同一文件、无用 import 已删、无父类方法重名/可见性冲突。
- 无本地编译器：CI 编译与真机验证由父 agent 接手（见“下一步”）。

## 下一步（需父 agent）

1. 推送测试分支 `test/hk-replicate-1`（浏览器网页上传路线）、触发 CI、等构建完成、核对 mobile arm64 APK 与 sizes 文件哈希。
2. 发 Wei 真机验收：用内置规则打开列表、列表出卡、点卡播出地址；详情/搜索/多线路播放/x5/云口令是第 2、3 步，不在本次验收内。

## 唯一下一步

`task_guard finish` 本地提交（会话 `hk-replicate-1-push`，不推送），随后把分支名、提交哈希报给父 agent 推分支、编 CI。
