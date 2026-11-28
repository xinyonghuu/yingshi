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
18. 推送直链强制直接播 + 播放诊断弹窗（2026-11-28 15:03，会话 `hk-fix-push-direct`，在独立 worktree `~/workspace/yingshi-wt-hkfix` 做，主仓库工作区当时在 `test/sync-ai-vlc` 未碰）：真机反馈换内核（ijk/mpv）也还是不行、普通片子能播，问题锁定在推送链路——海阔给出的地址本来就是可直接播的 MP4，却可能被播放器送进嗅探/解析路线。已修：mobile `VideoActivity.setPlayer` 里，key 是推送（`SiteApi.PUSH.equals(getKey())`）且 `result.getRealUrl()` 是可直接播的视频格式（`Sniffer.isVideoFormat`，判空防 NPE）时，`setUseParse(false)` 强制直接播；否则保持原来的 `result.shouldUseParse()`；非视频格式的推送地址仍走原有逻辑。`SiteApi`、`Sniffer` 本就已 import，无新增 import。起播分发已核实：`PlaybackActivity.startPlayer` 是 `result.needParse() || useParse` 双条件，`isUseParse` 不是唯一开关——推送直链在 `pushPlayer` 里 parse=0、jx=0，`needParse()` 为 false，所以本次 `setUseParse(false)` 能保证直接播；但如果推送走了别的分支（配了 push_agent 站点且 playUrl 非空）导致 `needParse()` 为 true，本修复拦不住，仍会进解析，待真机验证。另在 `HkPlay.play` 里 `startDirect` 之前加了临时诊断弹窗（`WebHtvAlertDialogBuilder`，App 里已有用法）：标题“海阔播放诊断”，四行——标题、线路（Push）、地址（完整 URL 不截断）、当前内核（`PlayerSetting.getActivePlayer()` + 就地名字映射 Exo/ijk/系统/mpv/VLC）；按钮“播放”走原 `startDirect`、“取消”不播；Activity 正在结束/已销毁时跳过弹窗直接播。弹窗代码标了 `// TODO DIAGNOSTIC: 定位后删除`，下一版要删。`HkPlay.play` 在 UI 线程被调（`HkListFragment.onCardClick` 经 `runOnUiThread` 到 `onResolvedOnUi`），弹窗不用另包 `runOnUiThread`。
19. 种子规则旧文件永不刷新（2026-11-28 15:50，会话 `hk-fix-seed-refresh`，同 worktree `~/workspace/yingshi-wt-hkfix`）：#8 诊断弹窗截图证明问题不在播放器——卡片用的还是旧地址 `https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4`（实测 403，已死），卡片标题还是旧的 Elephants Dream / For Bigger Blazes；而 #8 APK 里打包的 `hk-test.json` 明明已是新地址（test-videos.co.uk + mdn，标题 Jellyfish / Flower）。根因：`HkRuleStore.ensureSeeded()` 里 `if (target.exists()) continue;`——种子只在首次复制，已存在就永不覆盖；用户第一次跑 #3/#4 时旧规则被拷进应用私有目录 `filesDir/hk/rules`，之后 #5/#6/#8 换了新种子也永远不生效，播的始终是死地址，0 KB/s 就是这么来的。已修（只改 `ensureSeeded`，`setPlayer` 直播修复和 `HkPlay` 诊断弹窗原样保留）：内容比对——读出目标文件现有内容与 assets 种子 JSON 文本比对，相同则跳过（不动文件，mtime 不变）；不同（或文件不存在、读不出旧文件）则用种子内容覆盖写，记 SpiderDebug 日志 `seed rule refreshed: <文件名>`（首次复制仍记原来的 `seed rule copied`）；方法注释同步改掉，写明新行为与原因（第 1 步没有应用内改规则的入口，文件与种子不一致只可能来自应用更新换了新种子，必须刷新）。调用点已核实：`ensureSeeded` 只在 `HkRuleStore.builtinSites()` 里调，`builtinSites` 由 `LocalSource.getSites()` 末尾追加调用；`getSites()` 有静态内存缓存（`sites != null` 直接返回），所以 `ensureSeeded` 每进程只在首次 `getSites()` 跑一次——应用更新会重启进程，用户更新到 #9 后打开海阔列表必触发刷新；调用点未动。缓存失效已核实：`HkEngine` 按规则标题 key，`MTIMES` 存规则 JSON 文件 `lastModified`，mtime 一变就同时驱逐 `RUNTIMES`/`MTIMES` 重建；覆盖写更新 mtime，链路没问题。另注：`HkSession.SESSIONS` 是进程内按标题缓存的会话（持旧 rule 对象与旧 runtime），`getOrCreateRuntime` 的 mtime 驱逐只在会话创建时跑，进程内文件变化不会刷新已有会话；但第 1 步里文件只在 `ensureSeeded`（进程首次 `getSites()`、会话尚不存在时）变，且应用更新重启进程，#9 验证（全新进程）不受影响，未动这块。#9 验证方法：海阔测试卡片应变为 Jellyfish / Flower，弹窗地址应为 test-videos.co.uk（Big Buck Bunny）/ mdn（Flower）。
20. 诊断弹窗删除（2026-11-28 17:30，会话 `hk-step1-remove-diagnostic`，同 worktree `~/workspace/yingshi-wt-hkfix`）：中国主人 2026-11-28 17:29 下令“去掉弹窗先不编，直接进行下一步”“弄好了一起编”——弹窗删掉，但这次不编，等第 2 步做完再一起编一个包。已删：`HkPlay.play` 里整块 TODO DIAGNOSTIC 诊断 AlertDialog（标题/线路/完整地址/当前内核 + “播放”/“取消”按钮，含 `isFinishing()/isDestroyed()` 跳过分支），点卡片后直接走原来弹窗里“播放”按钮调的同一个 `startDirect` 调用，参数原样不动；弹窗专用的 `kernelName` 名字映射 helper 顺手删掉；因此变未使用的 `PlayerSetting`、`WebHtvAlertDialogBuilder` 两个 import 删掉（已确认两处只被弹窗用）。未动：#8 的 `VideoActivity.setPlayer` 直播强制直接播、#9 的 `HkRuleStore` 种子刷新、PUSH 接线、卡片列表逻辑。本地提交，未推送、未触发构建。

## 验证（本地）

- 逐行重读每个改动文件；`git diff --check` 通过；每个新增符号的 import 在同一文件、无用 import 已删、无父类方法重名/可见性冲突。
- 无本地编译器：CI 编译与真机验证由父 agent 接手（见“下一步”）。

## 下一步（需父 agent）

1. 推送测试分支 `test/hk-replicate-1`（浏览器网页上传路线）、触发 CI、等构建完成、核对 mobile arm64 APK 与 sizes 文件哈希。
2. 发 Wei 真机验收：用内置规则打开列表、列表出卡、点卡播出地址；详情/搜索/多线路播放/x5/云口令是第 2、3 步，不在本次验收内。

## 唯一下一步

`task_guard finish` 本地提交（会话 `hk-replicate-1-push`，不推送），随后把分支名、提交哈希报给父 agent 推分支、编 CI。

## 第 2 步（item 21）

### 1. HkRuleManager

做了什么（2026-11-28 17:35–17:41，会话 `hk-step2-rulemanager`，本地提交，未推送、未触发构建）：

- 新文件 `api/hk/HkRuleManager.java`（单例 `get()`，照规格书 §13.2 签名）：
  `rulesDir()/ruleFile()/fileNameFor()/load()/list()` 全部委托给 `HkRuleStore`，
  未另写一套读写。新增 `importRuleJson/importText/importHkZip/exportHkZip/
  resolveCloudPassword/deleteRule/clearRuleCache/rebirthRule/isEnabled/setEnabled`
  与公开的 `decodePayload`（载荷解码梯子）。
- 云口令（q52.K()+q()，逐行核对 q52.java）：正则 `^云([1-9]|10)oooole(/.*)$`，
  oooole 后缺 `/` 时补（J()）；云 1=pastebin raw、云 2=textdb（/apidb/ 路径，
  内容先 Base64 解）、云 5=cmd.im（取 test_box）、云 6=pasteme（/xxxxxx/ 路径，
  JSON 的 data 字段，口令后第一段文本当附加口令 `(@…)`）、云 7=note.ms（先带 UA
  取 textarea，再不带 UA 直取）、云 9=txtpad、云 10=hastebin（hassdtebin 与
  hastebin 两个域名依次试）；/p/ 的 netcut 通道按原文报错（已停接口）。
  错误文案照原文（“不是有效的云口令（需云1~云10开头）”“云端返回内容为空”等）。
- `.hkzip`：必须含 rule.json，否则按原文报错（“hkzip 文件不存在” /
  “hkzip 中没有找到 rule.json” / “不是有效的 hkzip 包或文件已损坏”）。
  require.json / libs.zip 如有则随规则落到 `HkRuleStore.ruleDataDir(title)`
 （新增，`<规则目录>/data/<P(title)>`，照 q52.w()）；导出时同样一并打包。
  嵌套 .hkzip（包中包）未做，见未验证。
- 启用开关：SharedPreferences `"hk_rule"`，key `enabled_<标题>`，默认启用。
  `HkRuleStore.builtinSites()` 跳过被禁用的规则；增删/改开关时调
  `LocalSource.invalidate()` 清掉源列表内存缓存，下次打开源列表即按新状态重算。
- 删除/重生/清缓存：`deleteRule` 删规则文件 + 数据目录 + 启用开关 + 清缓存；
  `clearRuleCache` 驱逐 `HkEngine`（运行时/mtime/初始化守卫，新增 `evictRule`）
  与 `HkSession`（会话缓存，新增 `evictRule`）；`rebirthRule` 清缓存 + 删数据目录
  再重读规则文件。
- 导入入口 UI（`ui/hk/HkListActivity` 工具栏菜单，不新增 Activity）：
  “导入规则”→ 一个对话框三路并列：① 粘贴规则文本（多行 EditText，调 importText，
  接受规则 JSON / rule:// 口令 / 云口令）；② 选择文件（ACTION_OPEN_DOCUMENT，
  照 ModuleActivity 的 launcher 写法，.json/.hk 当文本，.hkzip 按文件名或 PK 魔数认）；
  ③ 云口令（单行输入框，调 resolveCloudPassword）。导入成功弹“导入成功，是否打开？”
  （APK 的 q1 行为），“打开”进 `HkListActivity.start`，“关闭”只关弹窗。
  “管理规则”→ 列表对话框：点标题打开，行内 Switch 切启用/禁用，行内“导出”
  （ACTION_CREATE_DOCUMENT 写 .hkzip）、“删除”（删完就地刷新列表）。
  导入/导出全走 `Task.execute` 后台线程，UI 只做弹窗与 Toast；失败一律 Toast +
  SpiderDebug 记日志，不闪退；后台完成时若 Activity 已销毁则跳过弹窗（防
  BadTokenException）。

云口令解码做到哪一步：梯子 (a) 直接规则 JSON（含 `￥home_rule( _v2)￥{...}` 包裹形，
q52.U()）与 (b) `base64://…@…@<b64>` 形及整段 Base64（q52.V()/e()，含 URL-safe
变体）是按反编译原文实现的；(c) AES 解密是“试一把”——CryptoJS 兼容格式
（Base64 解出 "Salted__" + 盐，EVP_BytesToKey/MD5 派生 key/IV，AES-256-CBC，
key "hk6666666109"，即 getPrivateJS 用的那个 key），只在解出规则 JSON 时才认，
解不出返回 null 再报明确错误。

哪处未验证（尤其 .hiker 二进制解密）：

1. `.hiker` 云口令载荷解密算法未确认（§14.1）：梯子 (c) 的 AES 只是按 getPrivateJS
   的 key 猜的 CryptoJS 兼容格式，**没有一条真实云口令跑通过**，不知道真机的
   .hiker 载荷是不是这个格式；解不开时只会报“云端内容不是规则 JSON…….hiker
   二进制载荷解密未确认”，需真机拿一条真实云口令对照。
2. 各云服务取数（pastebin/textdb/cmd.im/note.ms/txtpad/hastebin/pasteme 的 URL 与
   内容提取）只核对了反编译，未联网实测；note.ms 的 textarea 结构、hastebin 的
   hassdtebin 域名是否还活着，都要真机跑一条口令才知道。
3. `.hkzip` 的嵌套包（包里再含 .hkzip）与 data.zip 未处理；`sniffRuleJson` 对直接
   JSON 的判定比原文放宽（原文要求同时含 col_type 与 find_rule，本步放宽为
   含 col_type/find_rule/url 之一且能解析出标题），极简规则能进、但也可能把个别
   非规则 JSON 误判——最终以 `HkRule.fromJson` + 标题非空为准。
4. 未在本地编译（本机无 Java/Android SDK），编译由后续 CI 验证；全部行为待真机验收。

真机验收方法：

1. 粘贴导入：进任一海阔列表 → 右上角菜单“导入规则”→ 粘贴一条规则 JSON → 点
   “导入粘贴的文本”→ 弹“导入成功，是否打开？”→ 点“打开”进新规则列表。
2. 选文件导入 .hkzip：同一对话框点“选择文件…”→ 选一个含 rule.json 的 .hkzip →
   同样弹成功框并能打开。
3. 云口令：同一对话框输一条真实云口令（如云 1 / 云 2）→ 点“导入云口令”→ 能导入并
   打开；若报“.hiker 二进制载荷解密未确认”，把口令与日志发回来对照（见未验证 1）。
4. 删规则：菜单“管理规则”→ 点某条的“删除”→ 该行消失；回实验室本地源列表，
   该规则也不见了。
5. 关掉一条规则：管理列表里把某条的开关拨掉 → 回实验室本地源列表，该规则消失；
   再拨回来 → 重新出现。不用重启应用（LocalSource.invalidate 已接好）。

### 2. HkSiteAdapter.detail()

做了什么（2026-11-28 17:41–17:48，会话 `hk-step2-detail`，本地提交，未推送、未触发构建）：

- 新文件 `app/src/mobile/java/com/github/moyingshi/osc/tv/ui/hk/HkSiteAdapter.java`
  （mobile 源集，与 `HkListActivity` 同源集；本类无 mobile 专属 import，理论上可放
  main，但任务默认 mobile，且 leanback 不需要它，故放 mobile）：
  `public static Vod detail(HkRule rule, HkItem item)`——对卡片地址取数并求值详情规则，
  把求值出的卡片按 `HkItem.line` 分组装成标准 `Vod/Flag/Episode`。
  求值照 `HkListEvalTask` 的范式（`HkEngine.ensureInit` → `clearResults` →
  `evalLazyUrl` → `HkHttp.resolveUrl(1, …)` → `fetchDocument` → `bootstrapListEval` /
  `restoreEvalContext` → `js:` 规则走 `evalRuleJs`，选择器规则走
  `parseDomForArrayJava` → `getResults` 快照），只把求值规则从 `find_rule` 换成详情规则；
  超驰守卫（`HkEvalGuard.begin/check`）与失败记日志返回空列表的做法与列表求值一致。
- 组装：无 `line` 的全进“默认”线路；每组一个 `Flag.create(线路名)`，逐个
  `Episode.create(集名, 地址)`；集名取卡片 title，空则按序号 `01/02……`（并
  `setNumberName(true)`，与 APK 一致）；同时按 `Vod.setFlags()` 的 `$$$` 约定拼
  `setPlayFrom`（线路名 `$$$` 连接）与 `setPlayUrl`（每线路内 `集名$地址` 以 `#`
  连接，线路间 `$$$` 连接）。分集地址在组装时用第 1 步的 `HkSession.lazyParse`
  同步解析一次（各分支都是同步回调）：相对地址补齐、`#` 标记剥离、`video://` 去壳、
  `@lazyRule=` 求值、`{"urls": […]}` 多线路 JSON 只取第一条；解析不出（webRule /
  pics / novel / 空地址 / 懒解析链过深）的分集直接丢弃并记日志。
  动作 URL（`javascript:/hiker://empty/copy://toast://download://video://`）不过滤进分集，
  谓词照 APK `detail()` 的分组过滤。
- 标题/图/简介：求值结果里没有可播地址、但带标题/图/简介的那张当详情信息卡，优先用它的
  字段；没有这种卡时回落到原卡片的标题/图/简介（APK `detail()` 直接取缓存的原卡片）。
  这样写是故意的：详情求值返回的分集卡标题是集名（“第 1 集”之类），不能拿第一张分集卡
  的标题当 Vod 标题。
- 子页面展开：卡片 URL 含 `?hk_u=`（`toVods` 的 subPageId 写法）时，先取卡片
  `extra["url"]`，空则从 `?hk_u=` 与 `&hk_t=` 之间解回真实 URL（APK `expandPage` 的
  顺序、`subPageRealUrl` 写法）；裸 `hiker://page/xxx` 只认 `extra["url"]`，APK 的
  `expandPage` 还会按路径查规则的页面规则表（`b52`），yingshi 没有这张表，查不到返回
  null。`hiker://empty##<真实地址>` 剥掉 `hiker://empty` 与 `#` 前缀用 remainder。
- 点击分流接线（`ui/hk/HkListFragment.onResolvedOnUi` 加第 4 路，在现有三路之后、
  最后那个 toast 之前）：解析出的 URL 不是可播地址、不是 `hk://` 子页面、卡片无
  findRule，且规则有 `detail_find_rule`（或 `sdetail_find_rule`）时，把
  `HkSiteAdapter.detail(rule, item)` 提交到本 Fragment 的单线程 executor 上跑
  （取数 + Rhino 求值是耗时操作，绝不在 UI 线程调）；拿到 Vod 则
  `VideoActivity.startDirectResolved(activity, vod)` 进详情页；detail() 返回 null
  或求值出空结果时，回落到原来的“小程序未解析出播放地址” toast。注意：`onEmpty`
  路（懒解析直接失败，如 webRule / pics / novel / 空地址）仍直接 toast，不尝试详情，
  见未验证 6。

detail_find_rule 与 sdetail_find_rule 的选用结论：

- APK `detail()` 只读 `detail_find_rule`（`o52.j()` 返回字段 `t`，即
  `detail_find_rule`；`detail()` 内两次取规则用的都是 `j()`）。
  `sdetail_find_rule`（字段 `u`）在反编译的 `HkSiteAdapter` / `HkListActivity` /
  `HkSearchActivity` 里没有任何读取点——`o52` 里甚至没有返回字段 `u` 的 getter，
  只有拷贝方法 `a()` 会复制它；它的消费者（如果有）在未提供的 `fy1` 列表引擎里，
  不可考。
- 本实现按任务要求：优先 `detail_find_rule`，它为空才试 `sdetail_find_rule`，两个
  都空返回 null；两者都有时只用前者（与 APK 一致）。`sdetail` 回落是任务要求的
  扩展，不是 APK 行为；APK 到底在什么条件下用 `sdetail` 未核对清。
- `detail_col_type` / `sdetail_col_type`：APK `detail()` 不用它们分集（分集挑选用的是
  写死的 `text_5` 与启发式，见 `pickEpisodes`），本实现也不用，只按 `HkItem.line` 分组。

Vod 站点 key / id 的定法与依据：

- 站点 key = `SiteApi.PUSH`（`"push_agent"`），id = 原卡片 URL（APK `detail()` 的
  `vod.setId(str)`，str 即传入的卡片 id）。依据是 `VideoActivity` 的详情模式代码：
  `startDirectResolved` 把 `vod.getSiteKey()` / `vod.getId()` 写进 intent 的
  `key` / `id`，并把 Vod 本体放进 `VodDetailCache`；`checkId()` 见到缓存就走
  `setCachedTmdbDetail()` → `setDetail(Result.vod(cached))`，不再调
  `getDetail()` / `SiteApi.detailContent`。选集点击时 `getPlayer(flag, episode)` 调
  `mViewModel.playerContent(getKey(), playFlag, episode.getUrl(), …)`，
  即 `SiteApi.playerContent(PUSH, 线路名, 分集地址）`。
  APK 的 `detail()` 最后 `vod.setSite(site)` 用的站点是 `hk://标题` 的 Spider 站，
  但 yingshi 里 `hk://` 站点在 `BaseLoader.getSpider` 落到 `SpiderNull`（见 `HkPlay`
  的说明），若照抄会导致详情页取空；所以站点 key 必须取 PUSH，不能取 `hk://标题`。
  `VodDetailCache` 存的是 Vod 对象本身（非序列化），`Result.getVod()` 不调
  `setFlags()`，故直接建好的 flags / episodes 原样进详情页。
  另：`VodEventGuard.alignCachedIdentity` 见到 Vod 已带 siteKey 就原样返回，不改写 id。

选集点击的播放链（已按代码逐段核对，未真机跑通）：

```
详情页点选集 → VideoActivity.getPlayer(flag, episode)
  → mViewModel.playerContent(PUSH, 线路名, 分集地址, 内核)
  → SiteApi.playerContent：requestId = PushParser.fromId(分集地址).getUrl()
      （裸 http(s) 地址原样通过；含 "|" 的地址按 PushParser 的标题切分规则处理，
       与第 1 步 HkPlay 的往返自检同款风险）
  → PUSH 且站点为空 → pushPlayer：Result.url = 分集地址，
      parse = 该地址是视频格式 ? 0 : 1（非视频格式仍走嗅探）
  → setPlayer(result)：#8 的判定 pushDirect = key==PUSH 且 Sniffer.isVideoFormat(realUrl)
      → setUseParse(false) 直接播，不走解析服务
```

- #8 的 `setPlayer` 修复覆盖这条链：只要分集地址是视频格式（`Sniffer.isVideoFormat`
  为真，如 `.mp4` 直链），`pushDirect` 为真，直接播；第 1 步验收过的 Big Buck Bunny
  这类 MP4 分集应能播。覆盖不到的情况：分集地址不是视频格式（如详情页地址、m3u8 之外
  的页面地址、磁力/电驴/迅雷地址）时 `pushDirect` 为假，走原有 `shouldUseParse` /
  `source.fetch` 嗅探逻辑，能否播出取决于嗅探，与第 1 步直链情形不同，未验证。
- 切线路：详情页的线路切换是现有 `FlagAdapter` / `checkFlag` 行为，本件未改；
  打开详情页时 `checkFlag(item)` 会按历史记录选线路并自动播第一集（与普通详情页一致）。
- 单地址回落：无可用分集、但详情地址本身是视频格式时，拼一个“默认”线路单集
  （集名取原卡片标题，空则“播放”）；判定用 `Sniffer.isVideoFormat`（与 #8 同款），
  不用 `HkSession.isPlayable`——后者只看协议头，会把普通 http 详情页误当成单集。
  本接线里这路基本走不到（可播地址早在点卡分流第 2 路直接播了），只作安全网。

哪处未验证（尤其 e52 字段语义与多线路 JSONArray 键名）：

1. 未在本地编译（本机无 Java/Android SDK），编译由后续 CI 验证；全部行为待真机验收。
2. `e52` 三个字段的精确语义（`a/b/c` 哪个是线路名、哪个是地址、哪个是头，§14.2）：
   本件不涉及 `e52`——详情组装只用 `HkItem.line`（`w12.h()`）分组，不读 `e52`；
   `n52.s()` 的 `{"urls","names"}` 多线路包在 `lazyParse` 里沿用第 1 步的解析，
   其字段语义仍未逐一核对。
3. 多线路 `JSONArray` 元素的精确键名（`{name, url}` 还是 `{title, url}` 等，§14.3）：
   本件不产生 `f6()` 式 `JSONArray`——多线路表达为 Vod 的多个 Flag，分集地址逐条走
   `playerContent(PUSH)`；`HkSession.MultiLine.toJson()` 沿用第 1 步的 `{name, url}`
   写法，精确键名仍未确认，待第 3 步 `HkPlay` 多线路时真机抓包对照。
4. `sdetail_find_rule` 的真实启用条件（见上“选用结论”）：APK 反编译里无读取点，
   回落分支只在 `detail_find_rule` 为空的规则上跑通过才算数。
5. APK 详情的同源多址归并（`ar1.F` 的 `bh` 分组：同一集多个同源地址按 URL 结构归并、
   挑最优）未复刻——反编译失真严重且 yingshi 没有对应工具类；同一集在不同线路返回
   多条地址时，本实现会原样列出多集，不归并。
6. `onEmpty` 路不尝试详情：卡片 URL 经 `lazyParse` 直接失败（webRule / pics /
   novel / 空地址 / 懒解析链过深）时仍直接 toast；这类卡片即使规则有
   `detail_find_rule` 也进不了详情页，留待第 3 步 `HkRouter` 分流时再定。
7. 嵌套 `@rule=` 的分集地址：`isEpisodeUrl` 不认含 `@rule=` 但不以 http 开头的地址；
   以 http 开头又含 `@rule=` 的分集地址会原样存进 Episode，`playerContent(PUSH)`
   不会二次解析，大概率播不出，需第 3 步的 `player()` 懒解析补齐。
8. 分集名含 `$` 或 `#` 时，`playUrl` 的 `$$$` / `#` 段在重解析时会错切（`Flag.setEpisodes`
   按首个 `$` 切集名、按顶层 `#` 切集）；本件的详情页渲染用的是直接建好的 episodes
   列表，不走重解析，故不受影响，但若将来有代码对该 Vod 调 `Vod.setFlags()`，
   每个 Flag 会多出一个 `("01", "")` 空集——`Flag` 没有 `urls` 的 setter，这是
   上游已有的 quirk，本件未动它；本件的进详情路径（`VodDetailCache` 对象缓存 →
   `setDetail`）上没有任何 `setFlags()` 调用，已逐段核对。
9. 详情页打开时的 TMDB 富集行为与普通 PUSH 详情页一致（本件未改 `setDetail`）：
   若某条 PUSH 详情触发 TMDB 模式，线路/选集 UI 可能被 TMDB 头部覆盖，与第 1 步
   `pushDetail` 的情形相同，不在本件范围内。

真机验收方法：

1. 找一条带 `detail_find_rule` 的规则（列表页能出卡即可）→ 点一张卡 → 应进详情页，
   看到标题/图/简介（与原卡片一致）与多线路（每条线路一个 Flag）与选集行。
2. 切一次线路（点另一条线路名）→ 选集行换成该线路的集。
3. 点一集（MP4 直链的集）→ 应直接播出（#8 覆盖）；若转圈或报错，把该集地址与
   `SpiderDebug` 里 `hk-site` 标签的日志（`detail ok … flags=… episodes=…` /
   `detail drop episode …`）发回来。
4. 回归：不带 `detail_find_rule` 的规则点卡行为不变（可播地址直接播、无地址仍 toast）；
   带 findRule 的卡片仍进子页面，不进详情页。

### 3. HkSearchActivity + h62

做了什么（2026-11-28 17:50–17:57，会话 `hk-step2-search`，本地提交，未推送、未触发构建）：

- `HkListEvalTask.evalSearch(runtime, rule, keyword, page)`（填上第 1 步留的 stub）：
  照 `evalList` 的范式（`HkEvalGuard` 超驰守卫 → `clearResults` → `setPage` →
  取数 → bootstrap → 求值 → `getResults` 快照；空结果记日志返回空列表，不抛错）：
  取数 URL = `HkEngine.evalLazyUrl(rule, rule.searchUrl(), page)` 再
  `HkHttp.resolveUrlEncoded(page, searchUrl, keyword, false)`（`**` 换 URL 编码后的
  关键词，`fypage` 按 page 算）；求值前把关键词置进作用域 `MY_KEYWORD`
  （`evalJs("var MY_KEYWORD = '…';")`，转义后 best-effort，失败只记日志）；
  求值规则串是 `rule.searchFind()`：`js:` 走 `evalRuleJs`，选择器走
  `parseDomForArrayJava(searchUrl, searchFind)` 回填。`search_url` 为空或
  `!hasSearch()` 时直接返回空列表并记日志。
- 新文件 `api/hk/HkSearchEngine.java`（h62 等价）：`search(rules, keyword, page)`
  对规则列表逐条调 `evalSearch` 串行求值（不并行——规则运行时共用 Rhino scope 与
  `HkEvalGuard` 序列，并行会互相超驰），结果按规则分组返回（`Group{rule, items}`，
  无结果的规则也保留空组；单条失败不阻断其余规则）；`searchSingle(rule, keyword, page)`
  是单规则便捷入口。无搜索能力的规则直接跳过并记日志。
- 新文件 `ui/hk/HkSearchActivity.java`（mobile 源集）+ 布局
  `app/src/mobile/res/layout/activity_hk_search.xml`：静态 starter a1 等价
  `start(Activity, HkRule)`（单规则）与 b1 等价
  `start(Activity, List<HkRule>, String keyword)`（多规则，`wd` 关键词可选传入，
  有则自动搜一次）；界面是顶部搜索框（EditText + “搜索”按钮，支持 IME 搜索键，
  空关键词 Toast“搜索关键词不能为空”并收键盘，与 APK 的 c1() 同款文案）+ 结果
  RecyclerView（复用 `item_hk_card.xml` 卡片布局与 `HkListFragment` 的网格渲染写法，
  列数按 `colType` 取，与 `spanFor` 同款）+ 加载圈 + “暂无搜索结果”空提示；
  搜索执行放单线程后台 executor，结果回主线程刷新；翻页：单规则且 `search_url`
  含 `fypage` 时滑到底部加载下一页（与 `HkListFragment` 的分页写法一致），不含则不翻页。
  规则交接用 Intent 传规则 JSON 列表（`"ruleJsons"`，与 `HkListActivity` 的 `"data"`
  同款内联做法；单条规则通常只有几 KB，远低于 Binder 上限）。
- 接线：`HkListActivity` 工具栏菜单加“搜索”项（`MENU_SEARCH`，只有当前规则
  `hasSearch()` 为真时才显示，点开进 `HkSearchActivity` 单规则模式）；
  `app/src/mobile/AndroidManifest.xml` 注册 `HkSearchActivity`
  （与 `HkListActivity` 同一写法）。
- 未动：`HkListFragment`（零改动）、TV/leanback、品牌与包名、#8 的 `setPlayer` 直播
  强制直接播、#9 的种子刷新、PUSH 接线、第 1/2 件。

searchFind 回落规则的结论（已按 APK 核对，不回落——任务书的“回落 findRule”建议与 APK 冲突，以 APK 为准）：

- `o52.t()` 返回字段 `s` = `searchFind`，`o52.u()` 返回字段 `r` = `search_url`
  （`o52.java` 的 `@SerializedName` 对照）；`o52.E()` =
  `(F(r) || F(s)) ? false : true`，`F()` = 空串判定，即 `hasSearch()` 要求
  search_url 与 searchFind 同时非空（与本地 `HkRule.hasSearch()` 一致）。
- `HkSiteAdapter.search`（另一条搜索入口，反编译可读）：`E()` 门不过直接返回空列表；
  `strT = o52Var.t()`（即 searchFind）为空时直接返回空列表，**没有回落到 find_rule**；
  `js:` 路（`k62.f(strT)`）走 x12，`x12` 的搜索分支用该串做 `t2(str9)` 求值。
- 因此 `evalSearch` 里 searchFind 为空时不回落 `findRule()`：`!hasSearch()` 门已挡掉
  （searchFind 为空 → `hasSearch()` 为假 → 返回空列表并记日志）。
- 未完全核对的一处：h62 本体的 `c()` 方法反编译为
  `throw new UnsupportedOperationException("Method not decompiled")`，即 h62 内部
  到底传 searchFind 还是 find_rule 给 x12 无法直接读到；但 `HkSiteAdapter.search`
  是同一引擎的另一条已反编译搜索入口，其行为明确（searchFind 为空 → 空列表），
  且 `E()` 门与 `t()` 的字段归属无歧义，故按“不回落”实现。若真机上某条 searchFind
  为空但 find_rule 能搜出结果的规则出现，再按实测改。
- 编码细节：APK 里 `HkSiteAdapter.search` 的 `js:` 路给 x12 的 URL 用未编码
  `h12.i(i, strB, str, false)`，选择器路取数用编码 `h12.i(i, strB, str, true)`；
  本实现按任务书统一用编码后的 URL 取数（`resolveUrlEncoded`），与任务书一致。
- r65 映射：APK 的 x12 搜索分支把每张 w12 卡映射成 r65（与 w12 同构的子集，
  `type = "video"`）；本实现直接复用 `HkItem`，不另建 r65 类——点击分流不读 type，
  无对应消费点。

点击分流：照抄，未抽 helper——理由：

- `HkSearchActivity.onCardClick/onResolvedOnUi` 是 `HkListFragment` 同名方法的
  逐路照抄（子页面自递归 / `HkPlay.play` / 带 findRule 的卡片自递归 /
  `HkSiteAdapter.detail` 详情；其余 toast“小程序未解析出播放地址”），代码旁已注明
  与 `HkListFragment` 同步维护。
- 未抽成 `HkCardClick` helper 的理由：分流与 Fragment 生命周期
  （`isAdded()`/`requireActivity()`）及详情分支的 executor 二次提交耦合，抽取要把
  生命周期判定与 UI 回调当参数传进 helper，等于改动已验证通过的列表点击代码；
  本地无编译器可验重构，宁可两处同步维护，不动 `HkListFragment`（本件对其零改动）。
- 唯一差别：生命周期判定换成本 Activity 的 `isFinishing()/isDestroyed()`；
  每行卡片用其所属规则（多规则搜索时各组规则不同）——Activity 内用 `Row{item, rule, header}`
  携带，不动 `HkItem`（它没有 `setGroup/setRule`，`rule` 字段是 JS 侧字符串，不可靠）。

多规则 b1 的状态（引擎已实现，UI 入口留待第 3 步）：

- `HkSearchEngine.search`（h62 引擎）已实现并可调；`HkSearchActivity.start(Activity, List<HkRule>, String)`
  starter 已实现，`wd` 有则自动搜。
- 本件的 Activity 多规则渲染只是最小实现：结果按组排，每组前面一条规则名分隔行
  （复用卡片布局，图/简介隐藏、不可点；空组不显示），只搜第 1 页，不翻页。
  APK 的完整多规则 UI（ViewPager2 按规则分页签 + 横向规则条 + 搜索历史列表 +
  分组/精准模式切换 + 每规则各自页码与逐组回推 `i62/onStatusEvent`）留待第 3 步；
  目前没有现成的多规则入口 UI 调 b1（列表页菜单只接单规则）。

哪处未验证：

1. 未在本地编译（本机无 Java/Android SDK），编译由后续 CI 验证；全部行为待真机验收。
2. `MY_KEYWORD` 的置入方式：APK 用 `ScriptableObject.putProperty(scope, "MY_KEYWORD", …)`，
   本实现用 `evalJs("var MY_KEYWORD = '…';")`（转义后 best-effort）——同一 scope 内
   后续 `evalRuleJs` 可见，语义等价，但未真机验证某条引用 `MY_KEYWORD` 的搜索规则。
3. h62 内部的 find-rule 选择未直接反编译确认（见上“回落结论”末段）。
4. 规则 JSON 内联传 Intent：单条/少量规则无问题；一次传几十条大规则可能触 Binder
   上限——b1 目前无调用方，真机多规则搜一次时留意。
5. 多规则翻页未做（`search_url` 含 `fypage` 的多规则搜索目前只返回第 1 页）。

真机验收方法：

1. 打开一条带 `search_url` + `searchFind` 的规则（列表页能出卡即可）→ 点工具栏
   “搜索”（无搜索能力的规则不应出现该菜单项）→ 输关键词搜一次 → 应出卡片，
   无结果时应显示“暂无搜索结果”而不是闪退。
2. 点一张搜出的卡（MP4 直链的卡）→ 应直接播出（与列表页点卡同一分流；#8 覆盖）。
3. 点一张搜出的详情卡（规则带 `detail_find_rule`）→ 应进详情页（第 2 件的 detail 路）。
4. 多规则：调一次 b1（暂无 UI 入口，需临时调用或等第 3 步）→ 应按规则分组显示，
   每组前面一条规则名分隔行；搜一次看分组是否正确。
5. 回归：不带搜索的规则列表页无“搜索”菜单；空关键词搜一次应 Toast
   “搜索关键词不能为空”；`search_url` 不含 `fypage` 的规则搜到底不翻页。

### 5. showSelectOptions 原生弹窗

做了什么（2026-11-28 17:57–17:59，会话 `hk-step2-selectoptions`，本地提交，未推送、未触发构建；先做这一件，因为第 4 件 hikerPop 依赖它）：

- `HkJsRuntime`：`"showSelectOptions"`、`"__hkStoreGet"`、`"__hkStoreSet"` 加进
  `CORE_APIS`（之前只在 `ALL_API_NAMES` 里记日志 stub），`dispatch` 加三个 case。
  `confirm` 未碰（另一 stub，不在本件范围）；`HkPlay`/`HkSession` 播放链未动。
- `__hkStoreGet`/`__hkStoreSet`：`HkJsRuntime` 内一个
  `ConcurrentHashMap<String, Object> hkStore`（REPLICATION.md §4.12 的 i0 map
  等价）；key 用 `String.valueOf`（经 `argString` 的 `Context.toString`）归一化——
  规格书的 `f42.h(obj, false)` 归一化细节未核对，用最简单的字符串化；
  set 存原始 JS 值（可能是 `NativeFunction` 闭包，原样存放），get 原样返回，
  查无回 null；null/undefined 视为删键（`ConcurrentHashMap` 不接受 null 值，
  与 `putVar` 同款）；不持久化（进程内仓库，APK 的 i0 map 也是内存态）。
- `showSelectOptions` 分发（k22 case 0，§4.5）：arg0 是 JS 对象（`NativeObject`/
  `Scriptable`），字段 `js`（函数源码字符串，来自 `$.toString`）、`options`
  （字符串数组，`NativeArray` 或类数组对象按 `length` 读下标）、`title`（字符串）、
  `selectedIndex`（整数，缺省默认 **0** 即首项；越界夹取到合法范围）。
  解析容错：arg0 不是对象、js 为空、options 为空时直接返回 null 并记日志，不抛错。
- 弹窗：`WebHtvAlertDialogBuilder`（应用唯一的 Material 弹窗构造点，主题已绑定）
  + `setItems` 列表，点一项即选中并关闭（与 APK 的 `ez3.Q` 点选即确认一致）；
  `selectedIndex` 只作初始高亮参考，`setItems` 不支持高亮故忽略（已解析并夹取，
  仅留档）。弹窗必须在 UI 线程弹：`dispatch` 所在的是规则求值线程，
  故经 `Activity.runOnUiThread` 弹；弹前再判一次 `isFinishing()/isDestroyed()`。
- 当前 Activity 持有器：`HkJsRuntime` 静态 `WeakReference<Activity>` +
  `setCurrentActivity/clearCurrentActivity`，由 `HkListActivity` 与
  `HkSearchActivity` 的 `onResume/onPause` 设置/清除（两个 Activity 原来都没有
  `onResume/onPause`，新加；`BaseActivity` 已有这两个方法，本件只是 override +
  调 `super`）。`clear` 只有持有者正是该 Activity 时才清，避免切换时序误清；
  弱引用，不漏 Activity。持有器是静态的、各运行时实例共用一个——当前只有一个
  前台 Activity，够用；见未验证 4。
- 回放机制（input/MY_INDEX 双通道）：选中下标 i、值 `options[i]` 后，
  `HkJsRuntime.replayJs(js, options[i], i)` 走 `Task` 后台线程回放——绝不在 UI
  线程求值 JS，并在 `evalLock` 下进 Context（与 `evalJs/evalRule` 同一把串行锁，
  Rhino scope 不支持并发求值）。回放先在作用域里置 `input`（选中值字符串）与
  `MY_INDEX`（选中下标）两个全局变量（照顾像 AI 生成器 onChange 那种引用自由变量
  `input` 的箭头函数），再求值：js 是函数源码（`function` 开头或箭头函数）时以
  `(<js>)(<input 的 JSON 字符串>, <i>)` 形式调用，否则直接当代码 eval（如 hikerPop
  的 `__hpInvokeJs(id)` 调用表达式）。两种都 best-effort，失败只记日志。
  `dispatch` 本身立即返回 null（异步结果走回放，不阻塞规则求值线程）。
  死锁说明：`dispatch` 在原求值持有 `evalLock` 期间只把弹窗 post 到 UI 线程、
  不取锁；回放在用户点选后才跑，此时原求值已返回、锁已释放。

无 Activity 时的行为：`getCurrentActivity()` 无登记、已被回收、或正在结束/已销毁
时一律回 null，`doShowSelectOptions` 只记日志返回 null，不弹不闪退（例如规则在
后台线程求值、用户已退回桌面的情况）。

哪处未验证：

1. 未在本地编译（本机无 Java/Android SDK），编译由后续 CI 验证；全部行为待真机验收。
2. **ez3.Q 的精确交互未确认**：本实现是 `setItems` 点选即确认、无确定按钮；
   规格书只写了 `ez3.Q(title, options, selectedIndex, 回调)` 弹原生选择列表，未核对
   APK 里点选后是立即回调还是要点“确定”才回调；`selectedIndex` 的初始高亮语义也未
   核对（`setItems` 不支持高亮，本件忽略它）。若真机上某条规则表现为“选了没反应、
   要再点确定”，就改成 `setSingleChoiceItems` + 确定按钮。
3. `f42.h(obj, false)` 的 key 归一化细节未核对：本件用最简单的字符串化；若 hikerPop
   回调存取时 key 两端不一致（例如对象型 key 的 `toString` 在存取两侧不同），
   `__hkStoreGet` 会查无——第 4 件接 hikerPop 时以实测为准。
4. 当前 Activity 持有器是静态单例：列表页与搜索页同时在栈里时以后 resumed 的为准；
   规则求值线程若在搜索页后台跑、而用户已回到列表页，弹窗会落在列表页而非搜索页——
   目前求值入口都在当前页触发，可接受；若以后出现跨页求值，再改成按运行时实例持有。
5. 回放的函数源码判定是启发式（`function` 开头或箭头函数正则）：形如
   `(function(){...})()` 的立即执行表达式会走直接 eval 分支（语义相同，能跑）；
   极少数非标准写法若误判，`input`/`MY_INDEX` 全局变量仍在，可兜底。

真机验收方法：

1. 找一条调 `showSelectOptions` 的规则（例如带选源/筛选弹窗的规则，或 AI 生成器
   里 `onChange` 调它选线路的规则）打开列表 → 点一次触发弹窗的操作 → 应弹出原生
   选择列表（标题与选项与规则一致），点其中一项 → 弹窗关闭，规则按选中值继续跑
   （例如切到选中的源/线路并刷新出卡，不过滤错）。
2. 点弹窗的“取消” → 应只关弹窗，规则不继续、不闪退。
3. 弹窗弹出后立刻按返回/退到桌面再回来 → 不应闪退、不应残留弹窗。
4. 回归：第 1/2/3 件（列表、detail、搜索）与 #8 直播直通、#9 种子刷新均不受影响。

### 4. 内置 hikerPop（HkHikerPopBridge）

做了什么：

- 新文件 `app/src/main/java/com/github/moyingshi/osc/tv/api/hk/HkHikerPopBridge.java`：
  `builtinSource(HkJsRuntime)` 读 `assets/hk/hikerPop.js` 的内置 JS 文本（进程内缓存，
  必须含 `__hk_hikerpop_native__` 标记才认，读不到或标记缺失时记日志回 `""`，不抛错）；
  `isOfficialOriginal(String)` 做官方原版判定（见下）。
- 新文件 `app/src/main/assets/hk/hikerPop.js`：内置 hikerPop 实现全文（4020 字符）。
- 接线 `HkJsRuntime.doRequire()`（b22 case 12）：(a) URL 去 `?`/`#` 后以 `hikerPop.js`
  结尾或等于 `hikerPop.js` → 记日志 `hikerPop -> builtin src=%s`，返回内置实现
  （第 1 步 stub 只记日志回 `""`，现改成真返回）；(b) `hiker://files/libs/` 先读文本，
  命中官方原版判定时丢弃原版、记日志 `hikerPop -> builtin (lib cache) len=%d`，返回
  内置实现，否则返回原文；(c) 其余分支（`hiker://assets/`、`hiker://page/`、其他
  `hiker://`/`file://`、http(s)、本地文件）原样不动；`?`/`#` 预处理沿用原有代码，未重复剥。
- 没碰 confirm、没改 `HkPlay`/`HkSession` 播放链、没改 `showSelectOptions` 本身
  （第 5 件原样复用）；TV/leanback、品牌、包名不动；#8 直播直通、#9 种子刷新、PUSH 接线、
  第 1/2/3/5 件保持原样。

内置 JS 的来源：字面量抽取，不是 Java 重写。`b22.java` case 12 里两段
`__hk_hikerpop_native__` 字面量（188 行与 194 行）经脚本解码 Java 转义后逐字节相同
（原始各 4154 字符，解码后各 4020 字符），直接落盘为 `assets/hk/hikerPop.js`，未手改一字。
选 assets 而不用 Java 字符串常量，是为避免转义弄坏字面量；`node --check` 已过。

`__hpInvokeJs` 的归属结论：定义在内置 JS 字面量内部（与 `__hpStore`/`__hpSeq`
同在字面量里），不在 Hikerurl.js / plugin.js / b22 注入里——桥里不另提供，字面量被
eval 后它自然存在。行为按字面量：`__hpInvokeJs(id)` 返回一段回放表达式字符串，运行时
经 `__hkStoreGet('hikerPop:' + id)` 取回 handler 再调它的 `click`；`selectCenter`
回放 `h.click(input, MY_INDEX)`，`selectBottomResIcon` 回放
`h.click(h.items[MY_INDEX], MY_INDEX, m)`（`m` 是 change/changeColumns/
scrollToPosition/setSelectedIndex/setTitle/dismiss 全空实现的占位对象）。

require 返回值消费方式的结论：`require` 返回的是 JS 源码文本，调用方（规则）eval 它——
APK 的 case 12 同样只返回字面量文本，不在 Java 侧 eval；所以 doRequire 返回内置 JS
文本就足够，不由 doRequire 自己 eval 进作用域（与 `getCryptoJS`/`getJsPlugin` 返回文本
由规则 eval 是同一契约）。字面量用到的宿主名在作用域里都有：`showSelectOptions` /
`__hkStoreGet` / `__hkStoreSet` / `toast` 是第 1/2 步已接的核心 API；
`__hkShowUpdateLog` 已注册为记日志 stub（调 `updateRecordsBottom` 时不抛错、只记一次日志）。

官方原版三标记判定的实现：`HkHikerPopBridge.isOfficialOriginal(text)`——文本长度
≥ 20000 且同时含 `CustomCenterRecyclerViewPopup`、`ToastMgr`、`builderXPopup`
三个标记时判定为官方原版，丢弃原版返回内置实现（官方原版构建在 XPopup /
`com.example.hikerview` 类上，本包不存在，require 到一调用就 TypeError）。null 或短文本
回 false，按原文返回。注意：判定只做在 `hiker://files/libs/` 分支，与 APK 一致；
`hiker://assets/` 分支不做判定。

哪处未验证：

1. 未在本地编译（本机无 Java/Android SDK），编译由后续 CI 验证；全部行为待真机验收。
2. hikerPop 各方法的真机行为全未验证：选源弹窗（selectCenter/selectBottomResIcon 点选后
   是否切源/生效）、百分比 seek（seekCenter 选 39%–100% 后 `rightClick` 是否生效）、
   toast（toastMeg 是否弹出提示）。
3. `selectedIndex: -1` 的语义未核对：字面量在 `opt.position == null` 时传 `-1`；
   第 5 件的 `doShowSelectOptions` 会把越界下标夹取到合法范围（`-1` 夹成 0），即无
   position 时默认高亮首项——APK 的 `ez3.Q` 在 `-1` 时是否“无高亮”未核对，若真机上
   表现为“一点弹窗就默认选中首项、还没点就切源了”，再按第 5 件未验证 2 的办法改。
4. `col` / `bottom` 两个字段第 5 件解析后忽略（`setItems` 单列、无底部样式）：多列
   宫格与底部图标弹窗在真机上都退化成单列列表，选项与选中回放语义不变，只是样式降级。
5. `$.require` 路径已接通（见下“$.require 接通补丁”，第 4 件补件）：`$.require`
   （Hikerurl.js CommonJS）经 `request()` 取模块、不经过 doRequire 的两层堵点都已打通——
   种子保证 `fileExist` 为真，`request()` 经 `readInternalUrl` 返回种子文件内容。
   仍是已知限制：`$.require("http://...hikerPop.js")` 这类写法会经 `request()` 拉远端原文
   （官方原版的话 eval 后一调用就 TypeError），本件不处理；该写法也不走 doRequire 的
   原版判定分支。
6. 官方原版三标记判定的真机触发未验证：手头没有一份 ≥ 20000 字符的官方原版
   `hikerPop.js` 可供实测（本机无真机文件系统可写），判定逻辑只按规格书字面实现。

真机验收方法：

1. 找一条调 hikerPop 的规则（例如用 `require("hiker://files/libs/hikerPop.js")` 或
   `require("hikerPop.js")` 后调 `selectCenter` 选源的规则）打开列表 → 触发一次选择
   弹窗 → 应弹出原生选择列表（标题与选项与规则一致），点其中一项 → 弹窗关闭，规则按
   选中值继续跑（切到选中的源/线路并刷新出卡）。
2. 找一条调 `seekCenter` 的规则（或同一规则的百分比 seek 入口）→ 应弹出 39%–100%
   的百分比列表（单列显示即可），选一项 → 应调 `rightClick` 生效、不闪退。
3. 找一条调 `toastMeg` 的规则 → 应弹出 toast 提示。
4. 回归：第 1/2/3/5 件（列表、detail、搜索、showSelectOptions 弹窗）与 #8 直播直通、
   #9 种子刷新均不受影响；confirm 与播放链未动。

$.require 接通补丁（第 4 件补件，本地提交，未推未编）：让
`$.require("hiker://files/libs/hikerPop.js")` 也能拿到内置 hikerPop 实现。背景：
`$.require` 是 Hikerurl.js 的 CommonJS 实现，不走 doRequire（b22 case 12 只接裸
`require(...)`）；它先调 `fileExist(id)`，再调 `request(id)` 取模块文本，两层在补丁前
都堵住了——`fileExist` 经 `HkJsRuntime.resolveFile`（约 1021 行）把 `hiker://files/`
映射到 filesDir，新装上 `libs/hikerPop.js` 不存在就抛 `Module "…" cannot be found`；
即使文件存在，`request()` 经 `doRequest`（约 621 行）直接拿该 URL 建 OkHttp 请求，
`hiker://` 协议抛错被 catch 回 `""`，模块是空的。

两处改动（都很小）：

1. 种子：`HkHikerPopBridge.ensureSeeded()`（新增静态方法）读
   `assets/hk/hikerPop.js`（经 `App.get().getAssets()`，不经 HkJsRuntime），写到
   filesDir 的 `libs/hikerPop.js`；内容比对策略与 `HkRuleStore.ensureSeeded` 的规则
   种子一致——不存在则复制，已存在但内容不同则覆盖写并记日志，相同则跳过不动文件；
   资产文本须含 `__hk_hikerpop_native__` 标记才写（与 `builtinSource` 的校验一致），
   失败只记日志不抛错。`HkRuleStore.ensureSeeded()` 开头调它一次；种子同步已在首次
   getSites 时跑，规则求值一定在它之后，时序没问题，不另起触发点。
2. `doRequest` 接内置协议：`HkJsRuntime.doRequest()` 开头（取 url、判空之后，建
   OkHttp 请求之前）加与 `doFetch()` 同款的一行：
   `String internal = readInternalUrl(url); if (internal != null) return internal;`。
   `readInternalUrl` 对 `hiker://files/` 读 filesDir 文件、`hiker://assets/` 读
   assets、`hiker://empty` 与 `hiker://page/` 等回 `""`、不能处理的回 null 走原有
   OkHttp 分支。改动后 `hiker://files/` 与 `hiker://assets/` 在 `request()` 里真正
   可用，其余行为不变（原来 OkHttp 抛错回 `""` 的那些，现在 `readInternalUrl` 回 `""`
   或 null，结局相同）。

fileExist 与 request 的核实结论：`fileExist` 这一层补丁前已核实——`resolveFile` 把
`hiker://files/` 映射到 filesDir（`HkJsRuntime.java` 约 1021–1035 行），种子写好后
检查即为真；`request` 这一层补丁前也已核实——`doRequest` 原先无内置协议分流、
直接建 OkHttp 请求（约 621 行），`doFetch`（约 666 行）已有 `readInternalUrl` 一行，
本补丁只是把同一行搬进 `doRequest`。没改 doFetch（它已经是这个写法），没改
Hikerurl.js（APK 原样复制的资产），没碰裸 require 的 doRequire 接线、showSelectOptions、
`__hkStore`、播放链、`HkPlay`/`HkSession`。

已知限制：`$.require("http://…hikerPop.js")` 那条路（下载官方原版再缓存）本件不处理——
它走 `request()` 拉远端原文，官方原版 eval 后一调用就 TypeError，且不走 doRequire 的
原版判定分支。

真机验收方法：找一条用 `$.require("hiker://files/libs/hikerPop.js")` 的规则（或在规则
里临时调一次），再调 hikerPop 的 `selectCenter` → 应弹出原生选择框（与裸 `require`
路径的行为一致）；点其中一项 → 弹窗关闭，规则按选中值继续跑。回归同上（第 1/2/3/5 件、
#8、#9 不受影响）。本地未编译（本机无 Java/Android SDK），编译由后续 CI 验证。

### 6. AI 生成器真输入框

做了什么（2026-11-28 18:09–18:25，会话 `hk-step2-extras`，本地提交，未推送、未触发构建）：

- `ui/hk/HkListFragment.java`：适配器加一种输入框视图（`getItemViewType`：`col_type == "input"` → `TYPE_INPUT`，其余 `TYPE_CARD`）。输入框行程序化建视图（不新增 layout 资源）：标题行 + `EditText`，`desc` 作 hint；`extra.titleVisible == false` 时不显示标题行（缺失时默认显示）；`type == "textarea"` 且 `height >= 2` 时多行输入框（`minLines = height`，AI 生成器第二张是 `minLines 2`），其余单行。输入框卡在网格里占满整行（`GridLayoutManager.SpanSizeLookup`：input 卡 span 全宽，否则在 `movie_3` 的 3 列里只有 1/3 宽，没法输入）。
- 初始文本取 `extra.defaultValue`（`HkItem.extra(key)` 取字段；`defaultValue` 是规则求值时 `getMyVar("r1"/"rule", "")` 的求值结果，已是字符串）。文本变化时调 `onChange`：`extra.onChange` 是 `$.toString` 包的函数源码，复用第 5 件的 `replayJs`（置全局 `input` 为新文本、`MY_INDEX` 为 0 后求值；onChange 箭头函数引用自由变量 `input`，靠的正是这条双通道）。防抖约 300ms（主线程 `Handler`，按输入卡分开记待执行回放：A 框刚输完立刻去 B 框输，不会把 A 的 `onChange` 顶掉）；回放走 `replayJs` 的 `Task` 后台线程，绝不在 UI 线程跑 JS。程序化 `setText` 回填时用 `binding` 标志挡住 `TextWatcher`，不触发保存/onChange。
- 输入值保存键：优先用 `desc`（提示文案），`desc` 为空时退化为位置键 `"pos:N"`。理由：`desc` 是规则给输入框的稳定身份，`refreshPage` 重载、上下插入卡片后位置可能漂移而 `desc` 不变；风险是同一列表里两张 `desc` 相同的输入卡会共用一份文本（AI 生成器两张 `desc` 不同，不受影响）。文本存在 Fragment 内的 `Map` 里，`RecyclerView` 复用与 `refreshPage` 重载都只认这张表；已输入的文本优先于 `defaultValue`，重载后用户刚输的内容不被默认值的重求值覆盖。
- `refreshPage` 接真实现：`HkJsRuntime` 上加按运行时实例挂的刷新监听（`RefreshListener` + `setRefreshListener/getRefreshListener`，`dispatch` 里 `"refreshPage"` 与 `"refresh"` 进 `CORE_APIS` 并调监听）；Fragment 在 `initView` 里经 `session.getRuntime()` 设置、`onDestroyView` 里清除（只清自己的那一个：同一运行时可能正被另一个同规则 Fragment 用）。监听在规则求值线程上被调，切回主线程重跑 `loadPage(1)`。注意 `loadPage` 的 `loading` 守卫可能吞掉一次并发中的刷新，可接受（正在跑的那次加载本来就会取到最新值）。
- “生成规则”走通：`onResolvedOnUi` 里 lazyParse 结果以 `"海阔视界￥home_rule￥"`（或 `"￥home_rule_v2￥"`）开头时，后台线程调 `HkRuleManager.importRuleJson` 导入该 JSON，成功弹“导入成功，是否打开？”（APK 的 q1 行为，与 `HkListActivity` 的导入成功弹窗一致；“打开”进 `HkListActivity.start`，“关闭”只关弹窗），失败 Toast“导入失败：…”。
- “来个示例”不报错：lazyParse 结果为 `"hiker://empty"`（或空串）时静默返回（规则 JS 里已自行 `refreshPage()`/toast，不再弹“小程序未解析出播放地址”）；真正的 `onEmpty`（什么都没解析出）仍走 `HkLazyCallback.onEmpty` 弹原 toast。顺手补了同一分流里的 `"toast://…"`：显示规则自己的文案（如“生成规则”空输入时的 `"toast://请先在上面输入网站地址和名称"`），而不是最后的通用“未解析出播放地址”。

未验证：

1. 输入框真机行为未验证（无真机、无本地编译）：`EditText` 聚焦/键盘弹出、多行框高度、滚动复用后文本是否还在、300ms 防抖后 `putMyVar` 是否生效，均待真机。
2. `refreshPage` 在列表求值中途被调时的时序未验证（如 `find_rule` 里直接调 `refreshPage()` 会不会与正在跑的 `loadPage` 死锁——按代码不会：监听只发主线程任务，不阻塞求值线程；但未真机跑过）。
3. “生成规则”生成的规则 JSON 未在真机上导入过：`importRuleJson` 要求标题非空且含 `col_type`/`find_rule`/`url` 之一（`HkRuleManager.sniffRuleJson` 的放宽口径不适用于 `importRuleJson` 本身——`importRuleJson` 只要求 `HkRule.fromJson` 成功且标题非空）；生成规则来自 `fetch("hiker://home@…")` 的完整规则，理论上满足，但未实测。

真机验收方法：

1. 打开 AI 生成器：两张输入框应为真 `EditText`（可点可输，第一张单行，第二张多行 2 行高），标题行不显示，hint 分别是两张卡的 `desc`。
2. 两框各输一次（如名称“测试”、地址 `https://cn.bing.com/`），输完停 1 秒 → 点“生成规则” → 应弹“导入成功，是否打开？”，点“打开” → 新规则列表打开。
3. 点“来个示例” → 两框应回填“必应AI”与 `https://cn.bing.com/`（`refreshPage` 重载后 `defaultValue` 重求值），不弹错、不闪退；再点“生成规则”应同样能导入。
4. 空输入点“生成规则” → 应只弹规则自己的“请先在上面输入网站地址和名称”，不再出现“小程序未解析出播放地址”。
5. 回归：第 1/2/3/4/5 件、#8 直播直通、#9 种子刷新不受影响；`refreshPage` 未挂监听的运行时（如搜索页）行为与原来 stub 一致（空操作）。

### 7. KV 持久化

做了什么（同会话 `hk-step2-extras`，本地提交，未推送、未触发构建）：

- 新文件 `api/hk/HkKvStore.java`：默认 SharedPreferences 落盘（最稳妥的方案：无新文件格式、无需处理规则 JSON；规格书 §14 第 8 项说落盘位置需真机核实，见未验证 1）。三个命名空间分开存（三个 SharedPreferences 文件，键不混用）：`hk_kv_items`（`setItem/getItem`）、`hk_kv_myvars`（`putMyVar/getMyVar`）、`hk_kv_public_items`（`setPublicItem/getPublicItem`）。
- 键规则：items/publicItems 是全局的，不加标题前缀；myVars 的键按 plugin.js §2.2 的规则标题隔离（`"<标题>@<key>"` 前缀，与 `initConfig` 同一机制，由 `HkKvStore.myVarKey(title, key)` 统一拼）。`vars`（`putVar/getVar`）按 §4.12 是作用域内临时变量，不持久化，没动它（仍是每运行时一份内存 `Map`）。
- 内存表是三张共享 static map（`HkKvStore.items()/myVars()/publicItems()`），`HkJsRuntime` 构造时取引用；首次访问时从磁盘读进内存，写操作写穿（先改内存，再 `apply()` 异步落盘）。用共享表而不是每运行时一份快照，是因为同一进程里多个规则的运行时共存（宿主列表 + 子页面递归），全局的 items/publicItems 与标题隔离的 myVars 本来就是跨运行时共享的语义；每运行时一份快照会在另一规则写入后读到陈旧值。
- `HkJsRuntime` 接线：`setItem/getItem`、`setPublicItem/getPublicItem`、`clearPublicItem` 改为内存 + 磁盘写穿；`getMyVar/putMyVar` 按 `"<标题>@<key>"` 存取（第 1 步的内存键是裸 key，本步改了键格式——旧进程内存里没有持久化数据，不存在迁移问题）；新增 `clearItem/deleteItem/clearMyVar/listMyVarKeys` 进 `CORE_APIS` + `dispatch`（规则里用得到，如 AI 生成器长按菜单的 `clearItem("list")`）：`deleteItem(key)` 删指定键；`clearItem([key])` 有参清指定键、无参清全部；`clearMyVar([key])` 有参清当前规则的指定键、无参只清当前规则的全部键（不动别的规则的变量）；`listMyVarKeys()` 只列当前规则的键，去掉 `"<标题>@"` 前缀后以 `NativeArray` 返回。`__hkStoreGet/Set`（跨作用域对象仓库，§4.12）是进程内内存态，不持久化，没动。
- myVars 的值：内存里留活对象（JS 值原样，`getMyVar` 当次读回不变）；落盘取字符串形——字符串原样，数字/布尔 `toString`，对象/数组经 `HkItem.jsToJava` 转 Java 结构再 Gson 序列化为 JSON 字符串（与卡片 extra 的序列化同一路）。`null/undefined` 视为删键（内存 + 磁盘一起删，与 `putVar` 的原有语义一致）。
- 没动 `HkRuleStore` 的规则文件读写，没把 KV 写进规则 JSON 文件。

未验证：

1. **KV 落盘位置待真机核实（规格书 §14 第 8 项）**：本实现选 SharedPreferences 是工程上的最稳妥选择，不是已确认的 APK 行为。APK 真机上 `putMyVar/setItem` 后文件变化点未对照过——如果 APK 实际落的是文件而非 SharedPreferences，跨版本/跨实现互通时会有差异；但对本应用自身（读写都走同一套 `HkKvStore`）持久化是自洽的。
2. 非字符串 myVars 值的往返是近似：对象/数组落盘成 JSON 字符串，读回时是 String 而不是活 JS 对象（经 `storage0.getMyVar` 读回时对 `{…}/[…]` 形自动 `JSON.parse`，仍是对象；裸 `getMyVar` 读回 JSON 字符串）。函数值落盘成源码字符串，读回后不再是可调用的函数。规则里 myVars 常用值（字符串、`initConfig` 的 JSON、`$.extend` 的 `"$:extend"`）不受影响。
3. `apply()` 是异步落盘：写完立刻杀进程，极小概率丢最后一次写（Android SharedPreferences 的常规语义；`initConfig` 一类关键写在规则执行流里，不在杀进程竞态内）。
4. 本地未编译（本机无 Java/Android SDK），编译由后续 CI 验证；真机持久化未验证。

真机验收方法：

1. 打开 AI 生成器，两框输值后点“生成规则”（或任意触发 `putMyVar` 的操作）→ 杀进程重进 → 再打开 AI 生成器，两框应回填上次输的值（`getMyVar` 读到磁盘值）。
2. 点“来个示例” → 应回填“必应AI”与 `https://cn.bing.com/`，杀进程重进后仍在（`putMyVar` 已落盘）。
3. 用一条调 `storage0.setItem("list", …)` 的规则（如 AI 生成器点“生成规则”后写 `list`）→ 杀进程重进 → 生成的规则仍列在下方历史卡里（`setItem` 已落盘）。
4. 回归：`putVar/getVar` 仍是作用域内临时变量（切规则/重进后不保留）；第 1/2/3/4/5/6 件不受影响。

### 8. 播放器线路/选集显示

结论：**本件未改，保持现状**。查了一路，定位结果如下，改动点不在海阔这一路能小范围够到的地方，硬改风险大于收益，留到下一步（需真机截图 + 日志先定性）。

查到的链路（mobile，`VideoActivity.setPlayer` 的 #8 修复、`PlaybackActivity.startPlayer` 的双条件分发、`PlaySpec.playbackRoute`、`SiteApi.pushDetail/pushPlayer`、`getEpisodePlayFlag`、详情页的 `FlagAdapter/EpisodeAdapter` 与线路/选集 `TextView` 设值处，一路读下来）：

- 播放入口：`HkPlay.play` 调 `VideoActivity.startDirect(activity, SiteApi.PUSH, id, title, pic, "", ResUtil.getString(R.string.push), title, resolvedUrl)`——`playFlag` 是 `R.string.push`，即 `"Push"`；`playEpisodeName` 是卡片标题、`playEpisodeUrl` 是直链地址。
- 详情：`VideoActivity.checkId()` → `getDetail()` → `SiteApi.detailContent(PUSH, …)`；`VodConfig` 里没有注册 `push_agent` 站点（`getSite("push_agent")` 为空），所以走 `pushDetail`：`vod.setPlayFrom("Push")`、`vod.setPlayUrl(直链)`，再 `Source.get().parse(vod.setFlags())`。`Vod.setFlags()` 按 `$$$` 切线路、`Flag.create("Push", 直链)`；`Flag.setEpisodes` 里裸 URL（无 `$` 分隔名）按序号命名，单地址就是 `"01"`。所以按代码模型，线路应是 `"Push"`、选集应是 `"01"`。
- 渲染：线路行 `FlagAdapter` 显示 `flag.getShow()`（无 `show` 时即 `flag` 本身）→ `"Push"`；选集行 `EpisodeAdapter.getTitle` → `getNativeTitle` → `displayName`（未设时即 `desc + name`）→ `"01"`。`getEpisodePlayFlag(flag, episode)` 只在有音频队列覆盖时才取别的值，正常返回 `flag.getFlag()`。`startPlayer` 的双条件分发（`result.needParse() || useParse` → 解析路，否则 `player().start(PlaySpec.from(…))` 直接播）只决定走解析还是直通，不写线路/选集文本；`PlaySpec.playbackRoute`（`PlaybackRoute.resolve` → 直链得 `DIRECT_REMOTE_HTTP`）只用于预加载线程数/能力判定与日志，从未渲染成 UI 文本。
- **“direct”字面量在播放器代码里不存在**：全仓 `grep '"direct"'`（排除 `redirect/direction/directly`）只命中无关处——`TmdbConfig` 的路由模式、`GithubProxy.DIRECT`、`MpvVulkanBackendPolicy.DIRECT`、mpv 的 HLS 代理内部键、歌词回落源名、测试代码。没有任何一条把 `"direct"` 写进线路/选集 `TextView`。同理，PUSH 详情链路上没有任何一步按 URL 文件名派生选集名（`Flag.setEpisodes` 命名就是 `"01"`）。
- 因此真机上看到的线路 `"direct"`、选集文件名（`Big_Buck_Bunny_720_10s_1MB.mp4`）**不能从静态代码定位到某一层**：最可能的候选只剩两处未排除的——(a) TMDB 刮削改写 `displayName`（`TmdbUIAdapter.applyEpisodeMetadata` 调 `EpisodeTitleFormatter.withSourceFileSize(源集名, 刮削标题, …)`，`extractFileSize` 会从源集名里抠 `1MB` 这类文件大小 token，但这只能解释“带文件大小的标题”，解释不了裸文件名与 `"direct"`）；(b) 观测到的那行 UI 根本不是 `FlagAdapter/EpisodeAdapter` 的行，而是详情页头/播放控制标题/历史记录里的另一路文本（如 `getPlaybackControlTitle` 拼的 `name + episode`、`buildMetadata` 的 `title/artist`、或 `mHistory.getVodRemarks()`），需要真机截图指认到底是哪一行。
- 没改的原因：按代码模型应显示的已经是 `Push/01`，且没有任何一处写 `"direct"` 或文件名可供“只改海阔直链这一路”下手；凭猜测去改 `Flag`/`Episode` 的命名或 `pushDetail` 的 `playFrom`，改不到真机现象的根上，还可能动到正常站源的显示（`pushDetail` 之外的 `Flag`/`Episode` 逻辑全是共用的）。第 2 件的详情 `Vod` 选集点击进路同样走 `pushDetail` → 同一模型，同样不在改动范围内。

未验证 / 下一步需要的证据：

1. 真机截图：指认显示 `"direct"` 的是哪一行 UI（线路行还是详情页头还是控制栏标题），以及显示文件名的选集行是哪一行。
2. 真机日志：播一次直链，取 `VideoActivity.getPlayer` 打的 `"video-flow" player start key=… flag=… episode=… url=…` 那一行（`VideoActivity.java` 约 3063 行）——它直接印出起播时用的 flag 与 episode 名，是定性最便宜的证据：若日志里 `flag=Push episode=01`，则问题在 UI 渲染层（刮削/历史/标题拼装）；若日志里就是 `direct`/文件名，则问题在 `applyIntentPlaybackSelection` 或详情缓存那一层（`detailContent` 的 `VodDetailCache` 命中分支返回的可能是旧 `Result`，`applyPushTitle` 只改名不改线路/选集）。
3. 在拿到这两项之前，不改 `HkPlay`/`pushDetail`/`Flag`/`Episode`/`VideoActivity` 的任何显示逻辑。

真机验收方法（下一步）：播一次 AI 生成器/测试视频的直链，先截图（线路行、选集行各一张），再抓一条 `video-flow player start` 日志，把截图与日志一起对照本节的定位结论；若日志 `flag=Push episode=01` 但 UI 仍是 `direct`/文件名，则按 (a)(b) 两条候选继续查刮削与标题拼装。

## 第 3 步（item 22）

2026-11-28 17:42 中国主人下令“直接第三步在编”——第 3 步做完再一起编一个包，中间不单独编。第 3 步 6 件全部本地提交（分支 `test/hk-replicate-1`，worktree `~/workspace/yingshi-wt-hkfix`），未推送、未触发构建。参照实现：被删引擎最后状态 `b056322b`（只读参照，不照搬、不整套恢复）；第 1/2 步已有的类是底座，新件适配它们，同名模型并进现有类、不覆盖现有类。

### 1. HkRouter（dealKind 分流 + playRoute + detail 预分流 + evalTab + 类型谓词）

提交 `154ca6a6`（会话 `hk-step3-router`）。落地内容按提交信息如实概括：HkRouter 的 dealKind 分流、playRoute、detail 预分流、evalTab、类型谓词。`fetchWebRule` 取址缝本件返回空串（第 5 件才接真实现）；点击位置槽（click-position slot）放在 HkDetailBridge，卡片点击时置位、playRouted 时消费。

### 2. HkDetailBridge.toVod + 选集四件套

提交 `dff28555`（会话 `hk-step3-detail`）。落地内容按提交信息如实概括：HkDetailBridge.toVod（标准 Vod 组装，站点 key 取 PUSH——hk:// 站点落 SpiderNull；集址经 `HkSession.resolveSync` 同步解析一次，解不出/不可播的集丢弃）+ 选集四件套（HkEpisodeDryClassifier / HkEpisodeMerger / HkEpisodeQualifier / HkEpisodeSorter + HkChapters）。PlayData 多线 JSON 在集址处展开（`expandPlayDataItem` / `expandLineUrl` 两处 org.json 最小解析，注“第 3 件落 HkPlayData 后可改走它”）。`HkChapters.collect()` + `Collected` 本件未移植，注“第 6 件需要时再补”。详情页选集点击与播放器内切集的置位点（原文在 HkDetailFragment 与 VideoActivity）本件未接——yingshi 详情页是标准 DetailActivity、播放器核心是禁区，留第 6 件定。

### 3. HkPlayData 多线路模型 + play() 选线接线

提交 `047047ab`（会话 `hk-step3-playmodel`，6 个文件，+521/−244，恢复标签 `recovery/hk-step3-playmodel/20261128190351-047047abb6fa`）。

- 新建 `app/src/main/java/com/github/moyingshi/osc/tv/api/hk/HkPlayData.java`：9 字段 PlayData 模型（url/urls/names/header/headers/audioUrls/danmu/lyric/subtitle），按参照 `b056322b` 的 HkPlayData（96 行，本身是海阔 8.83 PlayData 的直接移植）写。两种 JSON 形态都认：PlayData 对象形 `{urls, names, headers, subtitle, danmu, lyric}` 与多线路 JSONArray 形 `[{name, url}]`（元素键名以 REPLICATION.md §11/§14.3 为准——只认 `name`/`url`，未确认的键不猜后备，见“未验证”）。方法：`fromJson`（形态探测、永不抛）、`fromObject`、`fromArray`（地址全空回 null）、`fromPlay(HkPlay)`、`lineCount/isMultiLine/urlAt/nameAt`（缺名 → “线路N”）、`headersAt`（全局头与分线头合并、分线优先）、`audioAt`、`toPlay(HkPlay)`（与旧 HkRouter.parsePlayJson 逐项同义：urls 全进、url=urls[0] 或单 url、names 为空 → “线路1..N”、names 超长按 urls 截断、headers 累积、全局头、audioUrls/subtitle/danmu/lyric）、`toSingleLineJson(line)`（与旧 HkDetailBridge.expandLineUrl 同形打包：本线地址 + 本线请求头 k@v 串 + 0 线全局头 + subtitle/audioUrls[line]/danmu/lyric；无附加项时回干净地址）、`toJson()`（对象形，只写非空字段）、`toMultiLineJson()`（JSONArray 形，与 HkSession.MultiLine.toJson 同形）。请求头值过官方 `decodeConflictStr`（？？→? ＆＆→& ；；→; ，，→,）；`headers` 元素 JSONObject（一线头表）与 "K@V" 字符串（累进当前线头表）两种都接（8.83 getPlayData 语义）。
- `HkPlay.play()` 改走它：按 `linesJson` 选线（多线时取 `urlAt(lineIndex)`，取空回落 `resolvedUrl`），日志记线名 + 请求头/subtitle/danmu/lyric 有无；PushParser id 往返校验与 startDirect 改用选出的 `playUrl`；单地址老路不变。
- `HkDetailBridge` 两处 org.json 最小解析改走 HkPlayData（`expandPlayDataItem` 用 `fromJson` + `nameAt`/`toSingleLineJson`，线名归一同前；`expandLineUrl` 与 `jsonStringList`/`jsonKeys`/`jsonStringValue` 三 helper 删掉，org.json import 清掉）。行为差（往路由器 parsePlayJson 语义对齐）：字符串形请求头旧代码丢弃（`optJSONObject` 对字符串回 null），现收集；请求头值旧代码不过 decode，现过。
- `HkRouter.parsePlayJson` 体换成 `fromJson` + `toPlay()` 委托；私有的 `decodeConflict` 实例方法保留（另 5 处调用）；org.json import 清掉。
- `HkSession.lazyParse` 透传：`{...}` 与 `x5Play://` 的 PlayData 对象形分支改传原始对象 JSON 作 `linesJson`（不再经 `MultiLine.toJson()` 重发——那会丢请求头/subtitle/danmu/lyric）；webRule 分支多线时传 `HkPlayData.fromPlay(out).toJson()`（单线仍传 ""）。`linesJson` 的消费方事先全查过：只进 `HkPlay.play`（两种形态都认）或只记日志（`HkSiteAdapter.resolveEpisodeUrl` 记“detail multiline collapsed to first url”）。`MultiLine`/`parseMultiLine` 保留（lazyParse 各分支仍用它校验）。
- `HkListFragment.playRouted`：多线时经 `HkPlayData.fromPlay(resolved).toJson()` 建 `linesJson`（原 `HkSession.MultiLine` 路换掉；下标仍恒 0，选线在播放器里——第 6 件改）。
- 记名局限（写进 HkPlayData 类注）：**PUSH 直播没有请求头通道**（PushParser 只带 url|title；SiteApi.pushPlayer 只播裸地址）——解析出的分线请求头只进模型与日志、到不了播放器；需 Referer/Cookie 的地址仍可能播不了。这是接线层缺口，不是 HkPlayData 能修的。
- 核实：`git diff --check` 通过；改动逐项重读；HkDetailBridge/HkRouter 的 org.json 引用清干净；5 改 + 1 新文件。无本地 Android SDK，编译由后续 CI 验证。

### 4. HkAuthManager / HkCloudClip 鉴权与云剪贴分发表

提交 `e290f23f`（会话 `hk-step3-authcloud`，6 个文件，恢复标签 `recovery/hk-step3-authcloud/20261128190904-e290f23f8563`）。

- 新建 `HkAuthManager.java`（252 行参照移植，除包名与两处适配外与原文一致）：一般敏感面（publishRule 等）沿既有形态——SharedPreferences 按“规则标题+API名”持久授权，首次弹窗、授权后不再问；openAppIntent 面照官改姿态——**无持久授权**，每回先过 `canTouchUI(规则标题, "openAppIntent", 1000ms)` 频控门（通过即占槽、1 秒内再调直回 false），过门后每回弹确认（标题“温馨提示”，正文“小程序{标题}想要打开第三方软件 ({显示名})，确定允许吗？”——显示名＝包名本体，唯 `com.alicloud.databox` 显示“阿里云盘”；钮“取消”/“确定”），JS 线程在 CountDownLatch 上 await 1 分钟读结果（超时回 false）。历史上已存的 openAppIntent 持久条目在新路首调时清除，其余 API 存量条目不动。适配：星辰的 AiActivityTracker.top() 我方无，以 `App.activity()` 承接；星辰的 XPopup 我方无（硬约束不引入），确认弹窗以原生 AlertDialog 同文案承接（borderRadius 无原生等价物、丢弃）。`checkAuth` 只服务 publishRule；findJavaClass/loadJavaClass/registerDNS 不走本类（yingshi 的 JS 入口根本不存在 dex 动态加载）。
- 新建 `HkAuthDialog.java`（53 行参照移植）：标题“规则请求授权”，内容 API 名 + 说明，钮“允许”/“拒绝”，setCancelable(false)；findJavaClass/loadJavaClass 有高风险追加警告（文案照官；yingshi 无此入口，只作弹窗文案）。
- 新建 `HkAccountPwd.java`（参照小 bean，account/password 两字段）：云仓库（publish）账号载体。现状：publishRule 落点是云剪贴上传（见下），不走云仓库账号体系——本类先作账号载体备着，账号设置页落树后再接存取。
- 新建 `HkCloudClip.java`（323 行参照移植，除包名与两处适配外与原文一致）：云剪贴分发表——云1 pastebin、云2 netcut/textdb、云5 cmd.im、云6 pasteme、云7 note.ms、云9 txtpad、云10 hastebin；**云3/4/8 不支持**（8.83 getImporter 走 default 回 null）；云1上传恒 Bad API（`PASTEBIN_DEV_KEY` 空占位——星辰公开源码即空，真 key 由 CI 保密区注入，我方不在 api/ 新建密钥文件，公开构建同态不可用）。适配：HTTP 客户端复用 `HkHttp.client()`（15s/30s，与原文自建 client 同超时）。云2/云5 的上传形不在本类：旧有的 netcut 形与 cmd.im /api/save 形是无调用方的潜伏死实现（官无 /api/save 形、实测 404；netcut 接口已 301 跳文档页），已摘除防误接——云2导出活通道是 HkRuleManager.exportAsCloudCode（textdb 专用形），云5是 exportAsCmdImCode（cmd.im Location 形）；upload(2)/upload(5) 抛错指去这两个方法。pasteme 取数/取件号按参照正过：成功判据是信封 data.path 非空（不查 return_code）、内容取信封 data 字段（旧法找顶层 content/id/key 恒失败）。
- `HkRuleManager.resolveCloudPassword` 接线：云1（pastebin raw/{id}）、云6（pasteme api/getContent/{id} 取信封 data）、云9（txtpad.cn/{id}）三处改走 `HkCloudClip.download`——端点形经参照确认，与第 2 步直取的猜测相同，行为不变；云6失败包一层 null-on-failure（`fetchCloudClip`），保住 "(@口令)" 重试逻辑；第 2 步的 `fetchPasteme` 已删。**保留未动的口令专属形**：云2 textdb apidb（Base64 解）、云5 cmd.im test_box 提取、云7 note.ms textarea 两次尝试、云10 hastebin 双域名依次试——这些是口令形（云N＋路径）的取数，参照的分享 URL 形与之不是同一入口。`.hiker` 二进制载荷解密仍未确认（`decodePayload` 原样）。
- 新增 `HkRuleManager.exportAsCloudCode`（云2：rule.json → base64 → POST textdb.online/update，key=`xc<epoch>_<|title.hashCode|>`，status==1 回 `云2oooole/apidb/{key}`）与 `exportAsCmdImCode`（云5：POST txt=json 到 cmd.im，followRedirects(false)，读 Location 头 `/8xwm` 回 `云5oooole/{id}`）——与参照原文逐项对照，文件改走 HkRuleStore 的 rulesDir/fileNameFor、客户端复用 HkHttp.client()。**UI 入口尚未接**（管理页“导出为云口令”按钮留后续）；调用方目前是 HkCloudClip.upload(2/5) 的活通道。
- `HkJsRuntime` 接两个分支：`openAppIntent(p, a, param, ruleTitleKey)`——门序照官：标题空直回 Boolean.FALSE（不验 p/a、不走门）；p/a 定型（非字符串参照抛 JS 异常「参数类型只能是字符串」——我方 dispatch 统一吞异常回 null，抛不到规则里，故此处回 FALSE 并记日志，与参照差一档，记名）；action 恒 VIEW、flags 恒 0x10008000、component＝包名＋类名（setClassName 在 try 内）；装机门在 param 消费之前，未装 toast“找不到对应的软件，请确认已安装”回 FALSE；param 仅 JSONObject 形有效，逐键拼 extra（intentData → setData；Integer/Double/Boolean 按类型，其余转字符串）；启动异常 toast“出错：”＋msg；回真 Boolean（不是字符串 "false"）。`publishRule()`——参照的落点是云仓库提交（HkPublishHelper 整套账号/提交/JS 评测子系统，yingshi 没有，不照搬），且**参照自己的 publishRule 也只弹确认、上传动作记名未落**（官生态后端，星辰无此服务）；我方落点是云剪贴上传：先过 checkAuth 持久授权门，通过后当前规则 JSON 经 HkCloudClip 上传（默认云6 pasteme，其 `云6oooole/xxxxxx/{path}` 码可直接被我方 resolveCloudPassword 导入），成功回分享码、失败回 ""。另加 `scriptableToMap` helper（Rhino JS 对象 → 原始值 Map，跳过函数与未定义；第 5 件 options 复用）。
- 核实：`git diff --check` 通过；参照逐项对照（openAppIntent 门序/装机 toast/param 类型化 extra/真 Boolean 回值、publishRule 落点、云分发表、导出方法）；两个未用 import 已清（HkRuleManager 的 HashMap、HkJsRuntime 的 PackageManager）。无本地 Android SDK，编译由后续 CI 验证。

### 5. HkWebkitFetcher 系统 WebView 后台抓取

提交 `26f2932e`（会话 `hk-step3-webkit`，4 个文件，+432/−11，恢复标签 `recovery/hk-step3-webkit/20261128191012-26f2932efd8d`）。

- 新建 `app/src/main/java/com/github/moyingshi/osc/tv/api/hk/HkWebkitFetcher.java`（参照 354 行移植，除包名与三处适配外与原文一致）：`fetchCodeByWebView(url, options)`（WebView 后台加载，取 JS 渲染后的页面源码；options.timeout 1000–30000ms 默认 30000ms；checkJs 非 null 非 String 时抛 ClassCastException——上层 dispatch 吞异常回 null，与官失败形等价收口；checkJs 轮询 120 次 × 250ms，条件未成不取码；无 checkJs 时 finish 后延迟 1 秒取 outerHTML；CountDownLatch 同步等待；WebView 主线程创建）与 `executeWebRule(url, ruleJs, options)`（加载后 1 秒执行规则 JS，返回执行结果）。共用：`escapeJsString`（照官 adblockplus 逐字：\n→\\n、\r→\\r、'"'/'\''/'\\' 加反斜杠、U+2028/U+2029 转义）、`blockRules`（正则 find，编译失败退化为包含匹配）、`headers`/`ua`、`LIVE_VIEWS` 弱引用登记（错误/完成路自毁，纯超时路不毁、残件续载）。**简化版：无全桥、无 JS 注入、type 写死 webkit（无 X5）。**
- 三处 yingshi 适配：(1) 切后台销毁 `destroyLiveOnBackground()` 的分发点——参照的 BackgroundSilence 我方无，改由 `App.onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` 调用（App.java 只加这一处，3 行 + 注释；须主线程，onTrimMemory 即主线程）；(2) `onReceivedError` 除参照的旧签名外另覆写新签名（API 23+），只认主帧错误收口，子资源错误不中断抓取；(3) `headersOf` 除 Map 外也认 Rhino Scriptable——JS 传来的 options 对象是 Scriptable 不是 Map，`instanceof Map` 恒 false 会把请求头整段丢掉（参照坑；HkJsRuntime 的 scriptableToMap 是另一层兜底）。(4) 参照注里「Router 播放路改走 HkIWebViewParser.parseSync」一段不适用（yingshi 无此类），我方 webRule 路是 HkRouter.resolveWebRule → fetchWebRule → executeWebRule。
- 接线：`HkRouter.fetchWebRule` 体换成 `HkWebkitFetcher.executeWebRule(loadUrl, ruleJs, null)`（异常记日志回 ""；两处过期“返回空串”注释已更新）；`HkJsRuntime` 增 `fetchCodeByWebView` / `executeWebRule` 分支（options 经 scriptableToMap；两名在 ALL_API_NAMES 里早已注册，本件前是 log-only 桩）。
- 核实：`git diff --check` 通过；与参照逐段对照；无本地 Android SDK，编译由后续 CI 验证。

### 6. 多线路播放与播放器内切换

提交 `ed25d8b4`（会话 `hk-step3-multiline`，3 个文件，+148/−2，恢复标签 `recovery/hk-step3-multiline/20261128191317-ed25d8b41bc8`）。改动只动海阔这一路，未碰播放器核心与正常站源。

- 核实的链（只读核对，未改播放器核心）：详情路——`HkListFragment.dispatchDetail`（`detail.getVod()` 与 rawItems→toVod 两路）→ `VideoActivity.startDirectResolved(activity, vod)`（intent key＝vod 站点 key＝PUSH，id＝vod id，`VodDetailCache.put(vod)` 挂缓存键；`put` 对 Vod 无额外要求，任意非空 Vod 都收）→ `checkId()` 先走 `setCachedTmdbDetail()`（取缓存 Vod → `VodEventGuard.alignCachedIdentity` → `setDetail(Result.vod(cached))`，**不重新取详情**）→ 播放器线路/选集 UI 即缓存 Vod 的 Flags；切线/切集后按集址经 `SiteApi.playerContent(PUSH, …)` → `pushPlayer` 直播（视频格式 parse=0；#8 的 setPlayer 强制直接播仍在）。结论：**详情 Vod 的 Flags 本来就是线路，播放器内切线直接可用，无需改播放器核心。**
- 补的缺口（本件唯一代码改动）：直链卡片（magnet/video 形）走 `playRouted` → `HkPlay.play` 的单地址 push 直播，那条路只播第 0 线——多线路 JSON 里其余线在播放器里不可达（PUSH 详情只有一个 Push 旗）。补法（任务定好的做法）：多线路时改走详情 Vod，不走单地址 push。
  - 新增 `HkDetailBridge.toVodFromPlayData(rule, item, data)`（紧贴 toVod）：一线一 Flag，每线单集 "01"；口径与 toVod 同形——站点 key 取 PUSH、集名 "01" 是回退名故打 numberName 标（选集显示换片名全名）、playFrom/playUrl 与 flags 同形拼出；无 I/O（地址已由 playRoute 解析过），UI 线程现组现推；data 为空/各线地址全空时 flags 为空，调用方按无分集处理。
  - `HkListFragment.playRouted`：`data.isMultiLine()` 时组 Vod 经 `startDirectResolved` 推播放器（flags 空则 toast“小程序未解析出播放地址”）；单线仍走 `HkPlay.play` 老路（`linesJson` 此时恒 ""）。方法注释里过期的“多线路经 HkPlayData 透传给 HkPlay.play”一句已更新。
- 点击位置槽（第 2 件留的两处）：**无需新增置位点**。核对：置位点只在随后要调 lazy 的 playRoute 之前——`dispatchDetail` 的 directPlayUrl 分支与 magnet/video 分支在调 playRouted 前各置一次（`setClickPosition(position, rule.title(), 地址)`），playRouted 开头 `consumeClickPosition` 领走；详情 Vod 两路（`detail.getVod()` / rawItems→toVod）不置位——选集在 toVod 里经 resolveSync 预解析，播放器内切集用已解析地址、无懒重算，故无消费点。原文 HkDetailFragment（详情页选集点击）与 VideoActivity（播放器内切集）的置位点在 yingshi 无对应物：海阔流程里没有中间详情页（卡片直进播放器），播放器内切集即标准 Flag 切换。
- `HkChapters.collect()` + `Collected`：**不补**。核对：参照 `b056322b` 里 `HkChapters.collect(` 全树零调用（只有 `collectEpisodeRuns` 被 HkDetailBridge:185 用）；它的消费方是原文的 HkPageActivity 章节链（routeEpisode/startPlayerChapters），yingshi 没有这条链；我方 HkChapters 类注早已写明此决定（“播放器内换线路需要章节收集时再视情况补”）——第 6 件核完：播放器内换线路走标准 Flag 切换，不需要章节收集，故仍不移植（补了也是死代码）。
- “direct”/文件名显示（第 2 步 item 21 §8）：**本件未改**。仍需真机截图（指认哪一行 UI）+ `video-flow player start` 日志先定性；在拿到这两项之前，不改 HkPlay/pushDetail/Flag/Episode/VideoActivity 的任何显示逻辑。
- 核实：`git diff --check` 通过；链路逐段只读核对（dispatchDetail / startDirectResolved / putDetailVodCache / setCachedTmdbDetail / VodDetailCache.put）；会话中途因 scope 需加 HkDetailBridge abort 重开一次（无改动丢失）。无本地 Android SDK，编译由后续 CI 验证。

### 哪些逻辑参照了 b056322b、哪些是适配改写、哪些未验证

- 参照（除包名外与原文一致或逐项对照）：HkPlayData 的 9 字段模型与 toPlay 语义；HkAuthManager 的 openAppIntent 官姿态（频控门 + 每回确认 + 1 分钟锁存）与一般面持久授权；HkAuthDialog 文案与按钮；HkAccountPwd bean；HkCloudClip 的云分发表与各云取数/上传端点形（pasteme 信封取法按参照正过）；exportAsCloudCode / exportAsCmdImCode；HkWebkitFetcher 的两件本职（取渲染源码 / 执行 web 规则）与 checkJs 轮询、escapeJsString、超时夹取、blockRules、LIVE_VIEWS 登记。
- 适配改写（yingshi 无对应物或形态不同）：HkRouter/HkDetailBridge/HkPlay/HkSession/HkListFragment 的接线（PUSH 直播、startDirect、startDirectResolved、VodDetailCache、click-position 槽）；HkAuthManager 的 App.activity() 承接与原生 AlertDialog 确认（无 XPopup）；HkCloudClip 的 HkHttp.client() 复用与 PASTEBIN_DEV_KEY 空常量、云2/云5 死实现摘除；publishRule 落点改云剪贴云6上传（参照自己只确认未落上传）；HkWebkitFetcher 的 App.onTrimMemory 切后台、新签名 onReceivedError 主帧限定、headersOf 认 Scriptable；toVodFromPlayData（一线一 Flag）是 yingshi 新增（参照无此 helper，APK 侧是 f6 直传多线路 JSON——我方播放器无此入口，故改走详情 Vod）。
- 未验证（需真机或 APK 侧核对）：PlayData JSONArray 元素键名（只认 `name`/`url`，其余键未确认）；`.hiker` 二进制载荷解密（仍抛错）；云口令分发表里各云端点是否仍活（第三方公开服务，接口可能变更；云1上传恒 Bad API 是公开构建同态）；x5/WebView：我方 type 写死 webkit、无 X5，依赖全桥 finishParse 的嗅探式规则仍回空；APK 与被删实现的差异点（§12.4 的鉴权与云口令分发表以参照为准、APK 侧未逐项核对）；“direct”/文件名显示（item 21 §8，仍需截图 + 日志）；KV 持久化落盘位置（第 2 步 extras，§14 item 8 仍需真机核对）。

### 真机验收方法（第 3 步做完一起编的那个包）

1. 多线路规则点卡进详情切线路：找一条带多线路的规则（或用 PlayData JSON 测），点卡进播放器，播一次，再切一次线路（换一线播），两线都出画面且线路名与规则一致；直链多线路卡（magnet/video 形）同样点一次、切一次——应进详情 Vod 路，不再只剩第 0 线。
2. 云口令导一条规则：用 `云6oooole/xxxxxx/{path}`（publishRule 产出的码）与一条云2码各导一次，进管理页确认规则在列、能打开；再用管理页既有导出确认 .hkzip 路未动。
3. 需要 JS 渲染的页面抓一次：找一条 webRule:// 或 fetchCodeByWebView 的规则，抓一次渲染后源码/执行结果，确认有内容回（超时 30 秒封顶、切后台不残留 WebView——切一次后台再回前台，抓取应正常结束或超时收口）。
4. 敏感 API 弹窗：调 openAppIntent 的规则点一次（应每回确认、1 秒内连点第二次直接拒绝），调 publishRule 的规则点一次（首次弹窗授权、授权后不再问、回分享码）。
5. 回归：单地址直链卡（海阔测试三张）点一次即播；AI 生成器列表与输入框、KV 持久化、种子刷新、#8 setPlayer 强制直接播均不受影响；TV/leanback、品牌、包名未动。

## 构建 #10 编译失败修复（item 23）

- 构建 #10（Android Release run 46139684065）编译失败，唯一错误：`HkJsRuntime.java:113: error: ')' or ',' expected`。
- 根因：第 2 步第 5 件（showSelectOptions 弹窗 + `__hkStoreGet`/`__hkStoreSet`）往 `CORE_APIS` 末尾加新行时漏了一个 `)`——第 101 行 `new HashSet<>(Arrays.asList(` 开了两个括号，第 113 行结尾 `"showSelectOptions", "__hkStoreGet", "__hkStoreSet");` 只有一个 `)`，只关了 asList，没关 HashSet。
- 修复：第 113 行末尾补一个 `)`，改成 `"showSelectOptions", "__hkStoreGet", "__hkStoreSet"));`。重读 101–113 行核实括号配平（2 开 2 合）；顺手扫过同一文件——全文件只有这一处 `Arrays.asList(`，无同款漏括号处。`git diff --check` 通过。无本地 Android SDK，编译由后续 CI（重跑 #10）验证。
