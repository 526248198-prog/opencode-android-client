# 开发者说明书（DEVELOPER.md）

> **【强制条款，所有 AI 必读】任何 AI 在阅读本说明书后、修改本项目代码之前，必须先读完全文；修改代码后，必须同步更新本说明书中受影响的章节（API 表、代码地图、构建步骤、版本历史至少更新其中相关项），不得只改代码不改文档。**
>
> **[MANDATORY FOR ALL AI AGENTS] Before modifying any code in this project, read this manual in full. After modifying code, you MUST synchronously update the affected sections of this manual (API table, code map, build steps, version history). Code changes without doc updates are forbidden.**

## 1. 这是做什么用的

给不懂代码的用户（机主本人在用）在 Android 手机上操作 OpenCode 的极简客户端。

- **解决的痛点**：Termux 里敲命令、看刷屏输出，在手机上极易误触。App 只做“大按钮 + 会话列表 + 聊天”，防误触优先。
- **不做的事**：不复刻 OpenCode Web UI；不自己实现 Agent / 模型调用 / 工具系统；不加项目管理、世界书、角色卡等多余功能。
- **运行环境**：OpenCode Server（v2.0.12）跑在同一台手机的 Termux 里，App 通过 `http://127.0.0.1:4097` 直连。Server 有鉴权（`OPENCODE_SERVER_PASSWORD`，本机回环使用，App 用 HTTP Basic `opencode:<你的密码>`；V1 老服务仍在 `4096` 跑 `1.18.29` 进程，未动）。V1（`opencode1 1.18.32`）与 V2（`opencode 2.0.12`）双装共存，App 只连 V2。
- **密码不写进源码**（0.21.6 起，本仓库公开）：App 启动时读 `/sdcard/Download/opencode/server_password.txt` 的**第一行**当密码；文件不存在/为空/读不到就用占位符 `YOUR_SERVER_PASSWORD`（连不上会 401）。所以首次使用要自己建这个文件：

  ```bash
  mkdir -p /sdcard/Download/opencode
  echo '你的密码' > /sdcard/Download/opencode/server_password.txt
  ```

  改密码时两边一起改：文件 + `OPENCODE_SERVER_PASSWORD=… opencode serve …`（App 底部「复制指令」会带上当前读到的密码）。

### 1.1 复现所需环境（别人照着跑要什么）

| 项 | 值 | 说明 |
|---|---|---|
| Termux | **F-Droid 版**（版本号不重要） | Play 商店版已废弃、装不了新包；这里只用来跑 `opencode serve` |
| OpenCode | **2.0.12（V2 API）** | **最关键**。V1/V2 的接口、SSE、鉴权、消息结构完全不同，按 V1 抄必崩（本文档里 V1 记录均已标「存档/已退役」） |
| Android SDK | `build-tools 34.0.4` + `platforms android-35`（编译）/ `android-33`（链接） | `35.0.1` 的 `aapt2` 在本机报 `Exec format error`，只能用 `34.0.4` |
| JDK | 21（`javac --release 17`） | 见第 5 节 |
| 手机 | Android 8.0+（minSdk 26，targetSdk 35） | WebView 需为系统/Chrome 提供方 |
- **用户语言**：全部中文 UI。

## 2. 目录结构

```
/sdcard/opencode伪安卓版/           # 项目根（手机存储，文件管理器可见；2026-10-03 从 ~/opencode-android-client 搬来，无代码变更）
├── DEVELOPER.md                  # 本说明书
└── app/                          # Android 工程（纯 SDK 构建，无 Gradle）
    ├── AndroidManifest.xml       # 包名 com.opencode.client，INTERNET 权限，usesCleartextTraffic=true
    ├── build-apk.sh              # 一键构建脚本（见第 5 节）
    ├── debug.keystore            # 调试签名，勿删！删了则更新包无法覆盖安装
    ├── OpenCodeClient.apk        # 构建产物（重新构建会覆盖）
    ├── res/values/               # strings.xml / styles.xml（主题 AppTheme）
    └── src/java/com/opencode/client/
        ├── MainActivity.java     # 会话列表页
        ├── ChatActivity.java     # 聊天页
        ├── CrashLogger.java      # 进程级崩溃日志（0.21.0：闪退写 Download/opencode/crash-*.txt）
        ├── Palette.java          # 深色模式调色板（0.22.0：语义色 + 三态模式）
        ├── Edge.java             # 系统栏/键盘内边距（0.22.3：edge-to-edge 适配）
        └── BrowserActivity.java  # 浏览器页（0.17.0；0.18.0 起带读桥）
        └── BridgeServer.java     # 读桥（0.18.0：127.0.0.1:4098，随浏览器页生死）```

## 3. 关键代码信息

### 3.1 MainActivity.java（会话列表页）

- `BASE_URL = http://127.0.0.1:4097`，`SERVE_CMD = OPENCODE_SERVER_PASSWORD=<密码> opencode serve --hostname 127.0.0.1 --port 4097 --print-logs`。密码来自 `loadServerPassword()`：读 `/sdcard/Download/opencode/server_password.txt` 首行，读不到回落占位符 `YOUR_SERVER_PASSWORD`（0.21.6 起，公开仓库不带真实密码）。所有请求带 `MainActivity.auth()`（HTTP Basic `opencode:<密码>`，`android.util.Base64`，零第三方）。
- 启动即 `GET /api/session` 拉列表（`{data:[Session.Info]}` 解包，默认 50 条），行显示同前；`model` 字段 V2 为 `{providerID,modelID}`（无 `id` 时拼 `provider/model`）。
- `＋ 新建会话`：`POST /api/session` 空 body `{}`（回 `{data}`，只验状态码）。
- 条目**长按**：`PATCH /api/session/:id {"title"}` / `DELETE /api/session/:id`，V2 成功码为 `200/204`（V1 只有 200）。
- 底部常驻启动指令小字 + `复制指令`按钮（一键复制 `SERVE_CMD`，提示去 Termux 粘贴回车）+ `安装更新`按钮。
- 跑完叫你（0.15.0）：`SharedPreferences(opencode_done)` 记 `await_<sid>=标题`（在等结果）与 `done_<sid>`（刚完成待看）；聊天页 `send()`/`execution.started` 记、页内完成（`onServerIdle`/失败分支 `uiVisible` 时）清、离页完成直接系统通知（`NotificationManager`，渠道 `done`，点之回到该会话，`DEFAULT_ALL` 声音+震动）；进页按 `GET /api/session/active` 对账一次（在跑则保留、不在则清）；列表页 `onResume/onPause` 起停 15 秒轮询，在等但已不在 active 表即通知+绿勾，行前缀 `✅`（刚完成）/`⏳`（在等），点进会话清绿勾。Android 13+ 加 `POST_NOTIFICATIONS` 权限声明+运行时申请，无权限时通知被静默丢弃、绿勾不受影响。切走/锁屏管用；按返回键销毁聊天页后靠列表轮询兜底。
- `安装更新`：起因是 Termux 在后台时系统拦截 `termux-open` 弹窗。App 自己在前台调安装器则允许。实现用框架 `PackageInstaller` 会话安装（零第三方依赖）：读固定路径 `…/opencode伪安卓版/app/OpenCodeClient.apk` → 依次引导开通“所有文件访问权限”（`MANAGE_EXTERNAL_STORAGE`，读手机存储必需）与“安装未知应用”（`REQUEST_INSTALL_PACKAGES`）→ `commit` 后系统先回 `STATUS_PENDING_USER_ACTION`（必须用 `Intent.EXTRA_INTENT` 主动 `startActivity` 拉起确认框，0.11.5 之前缺这步，所以按钮一次都没成功过、永远“安装返回：null”且无弹窗；已排除签名问题：已装包与文件包 SHA-256 都是 `dc962e…`）→ 用户确认后系统再回 `SUCCESS`；安装结果经 `PendingIntent.getActivity` 回到 `MainActivity`（`singleTop` + `onNewIntent` + `handleInstallResult`，失败 toast 带状态码）。
- D8 坑：`build-tools/34.0.4` 的 D8（8.2.2-dev）遇到**匿名内部类**（`MainActivity$1`）和**非静态内部类**（如 `ChatActivity$ModelSelectListener`，带 `this$0` 合成字段）都会直接内部崩溃 `NullPointerException: Cannot invoke "String.length()"`。本项目一律用 lambda / **static 具名**写法：禁匿名内部类，内部类必须 `static`（`BrowserActivity$StayClient`、`BridgeServer$Listener` 即此例）。
- 崩溃日志（0.21.0，`CrashLogger.java`）：Termux 侧看不到本 App 的 logcat（无 root/adb），闪退无从定位。`CrashLogger.install(ctx)` 幂等装 `Thread.setDefaultUncaughtExceptionHandler`（三个 Activity 的 `onCreate` 都调，且在任何 UI 之前），崩溃时把 时间/线程/机型/Android 版本/应用版本 + 完整堆栈写到 `/sdcard/Download/opencode/crash-<ms>.txt`（Termux 可读，失败退回 App 私有目录），再交回系统原处理器。列表页底部第二行 `🐞 查看崩溃日志` 按钮**仅在检测到 crash 文件时出现**（`onResume` 刷新），点开弹窗看内容（可选中/`复制全文`）。
- 全部网络请求走后台线程 + `runOnUiThread` 回主线程；零第三方依赖，只用 `HttpURLConnection` + `org.json`。

### 3.2 ChatActivity.java（聊天页）

- 进入即 `GET /api/session/:id/message` 渲染历史（`{data:[Message],cursor}`，V2 消息形态；**V2 服务端返回倒序（新在前），`loadHistory()` 先反转再按 `time.created` 稳定升序（同毫秒保持服务端正序）后渲染**，0.13.2 修、0.13.12 补同毫秒并列错位；**服务端默认只回最新 50 条，`loadHistory()` 存 `cursor.next`（0.21.11 起仅在真取满一页时才认，见下「翻页按钮误显示」），顶部“⬆ 加载更早记录”按钮按游标向前翻、先收后插 prepend 到顶部（按钮之后）并按高度差回滚保持位置**，0.13.10 修“早先对话看不见”，0.13.12 修“翻页后顺序错乱+最新记录丢失”（根因有二：① `added++` 把 `idle(succeeded)`/`agent-switched` 等不渲染的消息也计数，多搬尾部视图把底部最新消息偷到顶部；② `remove(start)+insert(base+i)` 搬运循环在 `added>1` 时本就错位，尾部新气泡经一次搬运后已不在 `start`；现按 `childCount` 差值收齐实际新增视图、一次性插到顶部）；旧版形态：`user` 为扁平 `{text,files[]}`（`renderV2Message` 转成旧 `info/parts` 复用气泡；`files[]` 回显形态为 `Prompt.FileAttachment{data,mime,source{uri},name}`，转占位行，发送侧才用 `PromptInput{uri,name}` 无 mime）；`assistant` 为 `{agent,model,content[{type:text|reasoning|tool|file}],finish,cost,tokens}`（`text` 正文 / `reasoning` 灰字 💭 / `tool` 状态行，`content` 项缺 `id` 时补 `mid#i`）；`idle` 为完成标记（`outcome==succeeded` 跳过，否则红字 `❌ 运行未成功`）；`system/skill/shell/compaction` 不渲染；`synthetic` 文本附件转储不直接铺全文：`Called the Read tool` 噪音隐藏，`>300` 字折叠为一行占位（长按复制看全文，沿用 0.11.2 逻辑）。旧版 `info.error` 红字逻辑保留（V2 未见 `error` 字段，无害）。
- 标题行费用：`GET /api/session/:id` 取 `data.cost`/`data.tokens(input+output)`，余同前。V2 `Session.Info` 仍带 `cost/tokens`（实测确认）。
- 标题行待办：**已隐藏**。V2 无 todo 端点（`GET /api/session/:id/todo` 实测 404，openapi 117 接口里无 todo），`refreshTodos()` 现直接清空后缀；待办标题、SSE `todo.updated` 一并删除。
- 本轮改动查看（0.14.0）：Agent/模型行下方常驻“📝 改动”按钮；0.15.2 起主力改为从聊天记录认 newest user turn 之后的 `edit/write/apply_patch`（completed/error）工具（`session.diff` 在本机 2.0.12 上所有会话回空、无快照，靠它永远显示无改动；`diff` 只当 patch 明细增强，有 patch 才进详情）；进页与 `onServerIdle` 各查一次刷新按钮数字，点看文件列表（无 patch 时只列文件并明示），有 patch 才看前 8000 字、长按复制全文。
- 发送：`POST /api/session/:id/prompt {"text","files?[{uri,name}]"}`（V2 `text` 必填，纯附件发占位文案 `（附件说明见文件）`；附件形态为 `FileAttachment{uri,name}`，**无 mime 字段**，服务端自行识别 `text/plain` 并内联 base64——V1 的 `text/markdown` 拒收坑在 V2 不存在；`file://` 外部目录审批也不再触发，实测直发即读）。V2 prompt 无 per-message `model/agent` 字段：发送前按下拉真实选中值先调 `POST /api/session/:id/model {"model":{"providerID","id","variant?"}}`（0.20.0：`Model.Ref` 要 `id` 不是 `modelID`，错键 400；`id` 取 `/api/model` 的 `id` 字段，免费模型里与 `modelID` 同值；`variant`=思考档位，未选则不带该键，见下「思考深度」） / `POST .../agent {"agent": id}`（注意 agent 发 **id** 如 `build`，V1 发的是 name），再发 prompt；下拉为「未设置」（会话没存 model/agent）时跳过对应切换、用服务端默认。新建会话 App 自动切第一个 opencode 免费模型（0.20.0 起动态选，旧写死 `opencode/fledge-alpha-free`，因该模型已从 server 免费列表消失会静默失败；服务端默认是 openrouter 免费，不切则与“只用 opencode 免费”不一致）。回包 `200 {data:{inbox}}`（V1 为 204）。用户气泡先行展示，AI 回复全靠 SSE。
- `停止`：`POST /api/session/:id/interrupt`（回 `{interrupted:bool}`，闲时调返回 false，无害）。
- 标题行费用/待办写法（V1 存档，端点已变，见上）：旧 `GET /session/:id` 直取、`GET /session/:id/todo`、`updateTitle()` 单写者规则不变。
- 工具状态行格式：`[⏳|🔧|✅|❌] 工具名 [pending|running|completed|error] 标题`，error 截 200 字附原文。
- 工具输出可见（0.22.5）：工具行原来只有一行状态，AI 跑了什么、结果是什么根本看不到（`state.content` 实测单条最大 12KB，shell/read 尤其大）。现 `addPartView()` 的 **tool 分支**里，`state.status=="completed"` 且 `state.content` 非空时，在工具行下渲染 `addToolOutput()`：**只针对历史渲染路径**（工具输出常来自 `loadHistory()` 重拉，流式/事件路径零改动——running 期间 `state.content` 为 null 本来也不会渲染）。`state.content` 实测为 `{type:"text",text:"…"}` 数组，逐项取 `.text` 拼接（兼容非对象项与字符串形态），等宽 13sp + `Palette.codeBg()`，**默认折叠 3 行**（`maxLines=3` + END 省略），下方 11sp 灰字「▸ 工具输出（点展开）/ ▾ 工具输出（点收起）」切换；长按输出区复制全文（`makeCopyable` 拿的是 TextView 全文，`maxLines` 只影响显示）。空数组/空拼接不渲染，免空占位。验收：跑 `ls` 与一条长输出 `grep`，折叠 3 行、展开不炸滚动、长按复制全文。
- 发送（V1 存档，见上 V2 版）：旧 `POST /session/:id/prompt_async`。
- 停止反馈（0.13.3）：`abort()` 立即隐藏停止按钮、横幅/标题行显示「⏹️ 已发送停止，等待服务器确认中断…」，`execution.failed` 分支若系手动停止则只 Toast「已停止」；历史里的 `idle{outcome:interrupted}` 渲染为「⏹️ 已停止」（V2 实测 outcome 值为 `interrupted`）。
- 运行中进展显示（0.13.4，**0.21.9 已改事件驱动，见下条**）：最初 V2 无 part 级 tool SSE 事件，忙时每 3 秒轮询 `GET /api/session/:id/message`，把最新 assistant 消息里 `state.status` 为 `pending/running/streaming` 的 tool part 同步进 `runningTools`（复用 `friendlyToolText` 白名单），因此标题行/横幅实时显示当前工具；预建气泡占位文案为"… AI 正在处理，进行中的工具见上方状态栏 …"，首个 text delta 移除。→ 0.13.5 轮询改取数组头最新 assistant（V2 消息倒序），工具名 `shell` 已映射"⚙️ 正在执行命令"。
- 发送后忙时仍可继续发送（0.13.7）：`send()` 不再因 `busy` 直接 return，`setBusy` 不再禁用发送按钮；busy 时发送 Toast「已排队，当前回复结束后处理」，服务端按 `delivery:"steer"` 排队下一条。
- 思考阶段可视化（0.13.8）：`session.reasoning.started/delta/ended` → `thinkingShown`，状态栏「💭 正在思考…」；text delta →「✍️ 正在写回复…」；tool running 优先；`onServerIdle`/发送/历史重拉都重置。
- 富文本渲染（0.21.0）：正文以前一律纯 `TextView` 比例字体，AI 用字符画的框（`┌─┬─┐`）与 markdown 表格（`| a | b |`）列全对不齐。现 `applyRichText(tv,text)` 按行扫描，把 ``` 代码块（含流式中未闭合的）、含制表/框线字符（U+2500–U+257F）的连续行、markdown 表格行、ASCII 框（`+---+`）套上 `TypefaceSpan("monospace")` + `BackgroundColorSpan(#F2F2F2)`；单 `TextView` + `Spannable` 方案，流式每增量重建一次（`appendDelta`/历史 `addPartView` 都走它），长按仍复制纯文本。正文含代码块时气泡里加 `📋 复制代码`（`extractCodeBlocks` 拼全部代码块，历史渲染时加，流式中不加）。
- markdown 表格渲染（0.21.1）：0.21.0 的纯等宽对**含中文**的表格对不齐——中文双宽、拉丁单宽，字体比例非整数倍，空格补不齐（真机实测表头就歪）。现 `hasMarkdownTable()` 预判后走 `renderTextSegments()`：`splitSegments()` 把正文按「连续 `|` 行且含分隔行（`|---|`）」切成 普通段/表格段；表格段交 `addMarkdownTable()` 渲成真 `TableLayout`（首行=表头，`#EEEEEE` 底 + 加粗；单元格 `GradientDrawable` 描 1dp 边框；列宽由 TableLayout 跨行取最宽；外套 `HorizontalScrollView` 可横滑；单元格长按复制），普通段仍走 `applyRichText()`。同时把 `📋 复制代码` 按钮从**气泡顶部挪到正文之后**（0.21.0 bug：按钮先于正文 addView，显示在文字上方）。
- 渲染收尾（0.21.2）：① **表格改为压缩到一屏宽**——去掉 `HorizontalScrollView`，`TableLayout` 直接 MATCH_PARENT、单元格文字换行（真机反馈：横滑方案右列被藏在屏幕外，用户看不到）；② **代码块去掉 ` ``` ` 引号与语言行**——`Seg` 增 `CODE` 类型，`splitSegments()` 识别围栏并剥引号，`addCodeBlock()` 渲成等宽 + `#F2F2F2` 底、超宽包 `HorizontalScrollView` 横滑保留缩进、长按复制；正文段/表格段/代码段统一由 `renderTextSegments()` 分发（不再需要 `hasMarkdownTable()` 预判）；③ **历史思考折叠**——`reasoning` part 以前整段铺开（`"💭 "+rt`），现走 `addCollapsedReasoning()` 默认折叠 6 行、点标题展开（与 0.21.0 的流式思考块一致）。
- 表格收缩修正（0.21.3）：0.21.2 去掉 `HorizontalScrollView` 却没开列收缩——`TableLayout` **默认列不可收缩**（`mShrinkAllColumns=false`），宽度超屏时只会被父容器裁掉，既看不全又不能拖（比 0.21.1 更糟，真机复现）。修法：`table.setShrinkAllColumns(true)`（+ `setStretchAllColumns(false)`，单元格 padding 收到 `6/4`），列才会真正压到一屏宽、单元格文字换行。教训：`TableLayout` 想「压缩到一屏」必须显式开 shrink，光设 MATCH_PARENT 不够。
- 表格横滑（0.21.4）：0.21.3 的「压缩到一屏宽」对多列表格把每列压得又窄又高、文字疯狂换行；AI 又常输出超屏宽的表，用户既看不清也不能拖（真机反馈，用户明确要求「可滑动模式」）。现恢复横滑——`TableLayout` 不再收缩（`setShrinkAllColumns(false)` + `setStretchAllColumns(false)`），列保持内容自然宽度，外套 `HorizontalScrollView`（开滚动条）左右拖；仅当 `hsv.post` 布局后判定 `table.getMeasuredWidth() > hsv.getWidth()`（真溢出）才在表下显示「← 表格较宽，可左右滑动查看 →」提示，兼顾 0.21.1「右列藏在屏幕外看不见」的旧问题。未溢出则不显示提示。
- 一键复制全文（0.21.5）：分段渲染（表格/代码块拆成多视图）后，`makeCopyable` 的长按只能复制**单个视图**的文字，用户反馈「复制 AI 回复每次只有一部分」（0.21.0 前整条回复是单 TextView，长按可整段复制）。现 `renderMessage()` 在 AI 气泡末尾按消息加「📋 复制全文」按钮：`collectReplyText(parts)` 把该消息全部 `text` part 原样拼接（保留 markdown 表格原文、跳过 `synthetic` 附件转储），`addCopyAllButton` 挂 `copyText(full)`；用户消息不加（自己的话无需复制），空回复不加。与既有「📋 复制代码」（只拼代码块）并存；历史渲染时加、流式中不加（与复制代码一致，完成后 `loadHistory` 重渲染即有）。
- 推理过程实时显示（0.21.0）：以前 run 中 `reasoning.delta` 的正文被直接丢弃（只有历史重拉才显示 💭）。现 `appendReasoning()` 把增量写进本消息的思考块（`ReasonBlock`：header 可点开/收起 + 内容 `maxLines 6` 折叠），`reasoning.ended` 调 `finishReasoning()` 改 header 文案；`loadHistory()` 清空 `reasonBlocks`（完成后由历史 💭 行接管）。事件字段实测：`data{assistantMessageID,ordinal,delta}`（同 text.delta）。
- 审批兜底轮询（0.13.9）：忙时 3 秒轮询同步查权限/提问；两处列表按 `sessionID` 过滤（端点是 location 维度）；同 id 已展示则跳过（提问重建会清空已选选项，必须跳）。→ 35=0.13.9 修权限/提问条偶发不弹（根因有二：①审批条只靠 SSE 事件触发，SSE 易失，丢一个事件就永远不弹，忙时无任何轮询兜底；② `GET /api/permission/request` 与 `GET /api/form` 是 location 维度列表（openapi 实证参数只有 location），App 取第 0 条不按 sessionID 过滤；现忙时每 3 秒轮询兜底查权限/提问，按 sessionID 过滤，已展示的同 id 跳过不重建）。
- 工具细节显示（0.13.6）：`toolDetail()` 从 `state.input` 取 shell 命令 / edit·read 路径 / grep·glob pattern / webfetch url / websearch query，状态栏「⚙️ 正在执行命令 · df -h…」、历史工具行 `⏳ name [status] · 目标` 同样显示，均截 40 字；拿不到则退回泛文案。→ 32=0.13.6 状态栏与历史工具行显示具体目标（根因：V2 `state.title` 恒空，之前只显示泛文案；现新增 `toolDetail()` 从 `state.input` 提取 shell 命令/edit-read 文件路径/grep-glob pattern/webfetch url/websearch query，状态栏后缀 `· 目标` 截 40 字，历史工具行 `toolLine` 同步兜底显示）。→ 33=0.13.7 改 busy 时仍可发送（根因：App 之前忙时禁用发送按钮并在 `send()` 里 `return`；V2 实测 busy 时 `POST /api/session/:id/prompt` 返回 200 且 `delivery:"steer"`，服务端会排队/转向；现发送按钮常使能，busy 时发会弹"已排队，当前回复结束后处理"）。→ 34=0.13.8 增加思考阶段可视化（`session.reasoning.started/delta/ended` 以前被忽略；现 reasoning 期间状态栏显示「💭 正在思考…」，首个 text delta 切换「✍️ 正在写回复…」，工具运行仍优先显示具体目标；占位气泡文案改为「… 正在思考或准备中，详情见上方状态栏 …」）。
- 发附件（0.10.0 图片，0.11.0 加文件，V2 发送形态见上 `files[{uri,name}]`）：顶栏 `＋`（无附件时）/`清`（有待发附件时，点之移除）→ 弹窗选“发送图片”/“发送文件” → 系统选择器（`ACTION_GET_CONTENT`，免存储权限）；图片下采样到最长边 1280 + JPEG 80，非图片原样拷（5MB 上限）到 `/sdcard/Download/opencode/`；可纯附件发送；成功发出后自动清除。V2 下 `guessMime` 的 `text/plain` 强制已无用（`FileAttachment` 无 mime 字段，服务端自行识别，`.txt` 实测内联 `text/plain` 正常读出）但保留无害；`external_directory` 审批在 V2 文件直发链路未触发（旧 V1 经验：审批条点“总是允许”即可）。历史里用户消息的 `files[]` 按旧占位行渲染 `📷 图片：名` / `📎 文件：名`（`name` 来自服务端回显，40 字截断、总标签 80 字上限规则不变）。**注意（0.11.1，V1 教训，V2 仍保留防御）：占位行永不写 url/base64 内容，只显示类型+大小/文件名。**
- `停止`按钮位置与显隐规则不变（V1 存档：`POST /session/:id/abort`；V2 见上 `/interrupt`）。
- 再试一次（0.16.0，第 3 步前半）：`send()` 记 `lastSentText/lastHadAttach`（带附件那次只记文字）；顶栏 `↻ 重试`平时隐藏，仅上次失败（`execution.failed` 非手动、网络发送失败）且有可重发文字时出现，点之原样重发（`retryLast()` 复用 `send()`；忙时提示等跑完；带附件只回填文字不自动发）；新一发/跑成即隐藏。服务端无 retry 接口，客户端重发等价。
- SSE：`GET /api/event`（带 Basic 鉴权）单例长连接，V2 包络为 `{id,type,data:{sessionID,…}}`（V1 为 `{type,properties}`），断线 3 秒重连：
  - `session.execution.started` / `session.step.started` → 置忙（`step.started` 附 `assistantMessageID/agent/model`，预建 AI 空气泡；`ensureBubble()`）。
  - `session.text.delta{assistantMessageID,ordinal,delta}` → 以 `assistantMessageID` 为键追加（V2 流按会话+消息组织，无 part 路由，比 V1 简单）；`reasoning.*` 忽略（历史重拉时展示 💭）。
  - `session.execution.succeeded{sessionID}` → 唯一完成路径 `onServerIdle()`（V1 是 `session.status idle`/`session.idle`，V2 已无此二事件，旧分支已删）。
  - `session.execution.failed{sessionID,error{type,message}}` → 红字 Toast（截 80 字）+ `suppressDone` + 重拉历史（替代 V1 `session.error`）。
  - 权限/提问靠兜底：V2 询问事件名不在冻结 schema 里，凡 `type` 含 `permission` → `checkPendingPerm()`，含 `form/question` → `checkPendingQuestions(true)`；无 `sessionID` 的全局配置事件（`*.updated`）同样走此兜底。另有 `permission.asked/replied` 显式兼容分支（asked 即重查，replied 即藏条，均无害）。
  - V1 已删：`message.part.delta/updated`、`session.status/retry/idle`、`session.error`、`todo.updated`、`question.*` 精确 case。
  - 工具行现状（0.21.9 已定）：运行中改由 `session.tool.*` 事件实时驱动 `runningTools`（started 带 name、called 带 input、progress 带 metadata，success/failed/error 摘状态；`input.started` 不是完成标志），不再每 3 秒拉 `/message` 整包；完成后仍由 `loadHistory()` 全量重拉补齐历史工具行（`renderV2Message` + `toolLine`，按 V2 实测读 `name`）。
- 断线重连补漏（0.21.15）：老逻辑有两个硬伤。① `setReadTimeout(0)` 无限等——切网/休眠后 TCP 静默死掉时 `readLine()` 永远不返回，既不弹「事件断开」也不重连（实测服务端**每 15 秒**发一条 `: heartbeat` 注释行，故改为 `setReadTimeout(60000)`，60 秒一行都没有即抛 `SocketTimeoutException` 走原有 3 秒重连）；② 重连成功只 `pollSessionStatus()` 把 `busy` 补回来，**断线期间生成的正文没人补**，要等整轮跑完才对齐。现重连时 `pollSessionStatus(true)`：会话仍活动 → `loadHistory()` 按服务端重建（滚动位置照旧保留）；已空闲 → 原有 `onServerIdle()`。另 `lastSseLineAt` 记录任意一行（**心跳注释也算**），`onResume()` 时若超过 25 秒没吃到一个字节（后台被杀事件流但 socket 假活），同样补一次 `loadHistory()` + 对账（首次进页 `lastSseLineAt==0` 跳过，避免和 `onCreate` 的 `loadHistory()` 重复拉）。
- 重拉历史后的增量去重（0.21.15）：`session.text.delta` 只带 `assistantMessageID`（无 partID），流式一律以 `messageID` 为键存 `partViews/partBuf`；而 `GET /api/session/:id/message` 的 `content[]` **实测没有 `id`**，历史渲染只能用 `mid#index` 兜底。两边键对不上 → 重拉历史后迟到的增量找不到视图，会在气泡末尾**另起一个 TextView 只写尾巴**（表现为同一段话重复一截）。现 `addPartView()` 额外把本条消息**最后一个 text 视图**登记一条 `mid` 别名（`partViews`/`partBuf` 同键，buffer 用已渲染全文播种），重拉后增量接着往下写；`addCollapsedReasoning()` 同样按 `mid` 登记 `ReasonBlock`（`buf` 播种历史思考、`done=true`），迟到的 `reasoning.delta` 接在后面而不是再开一个「思考中…」块。
- 防丢 idle：`lastEventAt` + `busy` 30s 无事件 → 轮询 `GET /api/session/active`（`{data:{sid:…}}`，sid **不在表里即 idle**，替代已消失的 V1 `GET /session/status` 全量表）；SSE 重连成功同样对账。`applyPolledStatus`（busy/retry 三态机）已删，`retryText` 字段保留待 V2 retry 语义明确后再接。
- 模型/Agent/思考 下拉初始值 = 会话真实状态（0.20.0）：进页 `loadSessionMeta()` 读 `GET /api/session/:id` 的 `agent` 与 `model{providerID,id,variant}`，等 `loadModels`/`loadAgents` 都回来后由 `applySessionMeta()` 只应用一次并**选中真实那一项**（不再用「默认（跟随会话）」占位；会话没存则插「（未设置）」占位，空 id = 发送时不切、用服务端默认）。会话当前模型若不在免费列表（如 openrouter），插到下拉最前显示真实身份，做到“是什么就显示什么”。
- 模型下拉：`GET /api/model`（`{data:[{id,modelID,providerID,name,cost[],variants[]}]}`，只留 `providerID==opencode` 且 `cost` 全零的免费模型，按 `modelID` 排序；2026-10 实测 84 个 opencode 里 10 个免费如 `deepseek-v4.1-flash-free`（旧 `fledge-alpha-free` 已消失）；收费与 `openrouter` 等一律隐藏，不迁 openrouter key）。
- Agent 下拉：`GET /api/agent`（`{data:[{id,name,mode}]}`，`mode=="primary"` 且排除 `compaction/summary/title`；V2 `id`（如 `build`）与 `name`（如 `Build`）分离，发送用 **id**）。与「思考」同行各占一半（0.20.0 起，「Agent:」在左、「思考:」在右），省一整行。
- 思考深度（0.20.0）：V2 里思考档位就是 `Model.Ref.variant`。档位表 `modelVariants` 在 `loadModels()` 时从 `/api/model` 的 `variants[]` 按 `providerID+id` 建键（**含收费/openrouter**，保证非免费模型也能显示档位）；实测如 `deepseek-v4.1-flash-free` = `low/high/max`、`nemotron-3.5-super-vl-free` = `none/minimal/low/medium/high/xhigh`、`beam-free`/`space-bunny-free` = `low/medium/high/xhigh/max`，部分模型（`longcat`/`mimo`/`nemotron-3-ultra`/`nemotron-3.5-lightning`/`big-pickle`）无档位（下拉只显「默认（无档位）」并禁用）。下拉首项恒为「默认」（空 variant = 不发，用模型默认）；**换模型即重建档位并回落「默认」**（监听器 `ModelSelectListener` 必须 static，D8 坑见 3.1；带 `lastVariantModelKey` 护栏防 Spinner 异步回调把进页预选档位重置）；进页首次 `rebuildVariants(true)` 会尝试选中会话当前 variant。发送时把选中档位并进 `model` 切换请求，实测 `POST .../model {"model":{"providerID":"opencode","id":"deepseek-v4.1-flash-free","variant":"high"}}` → 204，会话详情回 `model.variant=high`。
- 权限审批条：进页 `GET /api/permission/request?sessionID=`（`{data:[{id^per,action,resources[],message?}]}`，展示 `action + resources` 换行）+ SSE 兜底；三个按钮 → `POST /api/session/:sid/permission/:rid/reply {"decision":"once"|"always"|"reject"}`（V1 是 `POST /permission/:rid/reply {"reply"}`，枚举值相同）。
- 提问条：进页 `GET /api/form?sessionID=`（`{data:[{id^frm_,title,fields[]}]}`）+ SSE 兜底；`adaptForm()` 把 V2 field（`{key,title,description,type,options[{value,label,description}],custom,required,hidden}`）转成旧横幅结构（`multiple ⟺ type==multiselect`；`boolean` 无选项时合成 是/否；`custom` 缺省=无选项或多选时 true；`hidden` 跳过）。提交 → `POST /api/session/:sid/form/:fid/reply {"answer":{key: value|[values]|bool}}`（单选/必填沿用旧校验，未填且 `required==false` 则省略该 key）；`跳过` → `DELETE /api/session/:sid/form/:fid`（V1 是 `POST /question/:rid/reject`）。旧 `answers:[[labels]]` 形态已删，`optionValue()` 做 label→value 回查。
- 防丢 idle（V1 存档，0.11.6 修永久等待：`GET /session/status` 三路对账；V2 见上 `/api/session/active`）。
- 模型下拉（V1 存档：`GET /config/providers` 分组形态；V2 见上 `/api/model`，0.13.0 起只留 opencode 免费）。
- Agent 下拉（V1 存档：`GET /agent` + `prompt_async.agent=name`；V2 见上 `/api/agent` + 发 id）。
- 权限审批条（V1 存档：`GET /permission` + `{"reply"}`；V2 见上；`always` 仅本权限+范围+本项目规则不变）。
- 提问条（V1 存档：`GET /question` + `{"answers":[[labels]]}` + `POST .../reject`；V2 见上 form 形态）。横幅 UI 行为不变：标题行（标题+收起/展开）+ 中间 `ScrollView` 240dp + 底部提交/跳过常驻；单选点已选项可取消；陈旧待答进页默认折叠、新到默认展开。状态行优先级：权限 > 提问 > 重试 > 工具 > 写回复。
- 长按复制：所有正文/工具行 TextView 长按 → 拷**该视图**文字 → Toast `已复制`（`ClipboardManager`，无须权限）。注意：分段渲染后长按只得单段（表格单元格/代码块各是一段），整条 AI 回复的复制请用气泡底部的「📋 复制全文」按钮（0.21.5）。
- 流式滚动不再锁死（0.21.7）：以前 `scrollDown()` 无条件 `fullScroll(FOCUS_DOWN)`，`text.delta`/`reasoning.delta`/`setPartText` 每个增量都把视图拽回底部，AI 输出时用户根本划不上去看历史（真机反馈）。现加「贴底才跟随」：`stickToBottom` 由 `ScrollView.setOnScrollChangeListener` → `updateStick()` 维护（`isAtBottom()` = 距底 ≤24dp），`scrollDown()` 在 `stickToBottom==false` 时直接 return；用户滑回底部自动恢复跟随。新增 `scrollDownForce()` 只管「自己发消息 / 首次进页 / 点回到底部」三种情形。消息区外套 `FrameLayout`，右下角悬浮「⬇ 回到底部」按钮仅在上滑时出现，点它=强制回底+恢复跟随。`loadHistory()` 跑完重拉时按 `firstRender` 区分：首次进页强制到底，用户在上滑则 `removeAllViews` 后 `scrollTo(0, y0)` 复原位置（否则会归零跳到最顶）。
- 工具状态事件驱动（0.21.9）：实测 `2.0.12` 的 `session.tool.input.started{sessionID,assistantMessageID,id,name}` / `session.tool.called{…,input}` / `session.tool.progress{…,metadata{shellID}}` / `session.tool.success{…,content[]}` 已完整（另有 `session.tool.input.ended{…,text}`，**不是完成标志**，不能用来摘状态）。现 `handleEvent` 直接维护 `runningTools`：started/called/progress → `runningTools[id]=friendlyToolText(name,"",detailFromInput(name,input))`，success/failed/error → 移除；`input.started` 是唯一带 `name` 的事件，故用 `liveToolNames`（id→name）记住供后续事件复用。**已删除 `pollToolStateOnce()`**（原每 3 秒拉一次 `/api/session/:id/message` 整包 50 条、含工具全量输出，长会话可达数 MB，只为读工具状态）；`startToolPoll` 改名 `startBusyPoll`，只保留权限/提问两个小接口的 3 秒兜底轮询（SSE 易失，丢了审批事件会永远不弹，这点不能省）。副作用：若 tool 事件丢失，状态栏可能短暂残留旧工具名，跑完 `loadHistory()` 会清（`runningTools` + `liveToolNames` 一并清）。
- 正文链接可点（0.21.14）：以前正文里 AI 给的 URL 只能手抄。现 `applyRichText()` 的两条出口都走 `setLinkifiedText()`：`Linkify.addLinks(WEB_URLS)` 后把默认 `URLSpan` 换成自定义 `UrlClickSpan`（默认 span 会甩去系统浏览器），点击 `startActivity(BrowserActivity, extra "url")` 走 App 内浏览器（登录态/桥都在那边，`BrowserActivity.onNewIntent` 补了常驻页的二次导航），失败退回系统 `ACTION_VIEW`。`LinkMovementMethod` + 蓝色下划线（`#1565C0`）。**长按复制不受影响**：`View.onTouchEvent` 先处理 long-click、MovementMethod 后处理 UP，两者会同时触发，故 `makeCopyable` 记 `lastLongPressAt`，`UrlClickSpan.onClick` 在 900ms 内直接忽略；`lastLongPressAt` 是 static，长按后 0.9 秒内点别的链接也会被吞（可接受）。代码块/表格单元格不链接化（历史走 `addCodeBlock`），流式整段文本里的代码块内 URL 会被链接化（无伤）。
- 流式节流（0.21.13）：以前每个 `text.delta`/`reasoning.delta` 都立刻 `applyRichText()` 全文重建 Span + `setText`，一条长回复是 **O(n²)**（几千个增量 × 上万字），也是发热掉帧的主因（0.21.0 起富文本渲染放大）。现增量先进 `partBuf` / `ReasonBlock.buf`，标记 `dirtyParts` / `dirtyReasons`，`scheduleFlush()` 用 `uiHandler.postDelayed(100ms)` 合并刷一次（`flushStreamingNow()`），滚动也随之从每增量一次降到每 100ms 一次。**兜底 flush** 位置：`session.text.ended`、`session.reasoning.ended`、`session.execution.succeeded/failed`、`abort()`、新建 part 的首个增量（`created` 分支立即刷，避免移除占位符后空白一瞬）；`loadHistory()` / `onDestroy()` 调 `dropPendingFlush()` 丢弃在途任务，防旧 buffer 写到已移除的视图上。注意：流式中长按复制拿的是 `TextView` 当前文本，可能比 buffer 落后 ≤100ms（历史渲染后不受影响）。
- 翻页探针（0.21.12）：
0.21.11 解决了「短会话也显示按钮」，但真机又暴露残留——最老那一页若正好取满 50 条，按钮仍会顶着，点一下回「已加载 0 条」才消失（服务端「取满就给 next」的通病）。实测消息端点支持 `?limit=`，故加 `probeHasEarlier(cursor)`：整页取回后用 `?limit=1&cursor=…` 轻量探一下，回空即收起按钮（网络异常按「有」处理，别把入口弄丢）；`earlierProbeSeq` 序号保证重拉历史后在途探针结果作废。代价：每次翻页/进页多一个 1 条的小请求（约几十字节级）。
- 翻页游标回归（0.22.1 真机反馈后查，0.22.2 修）：0.21.12 加探针时**把 `earlierCursor = nxt` 的赋值删掉了**，只留下「探针回空则清空」——游标恒为 `null`，于是全新加载的历史永远不显示「⬆ 加载更早记录」，长会话（>50 条）看起来丢了前半段。现 `loadHistory()` 先按 `fetched >= MSG_PAGE_SIZE && !nxt.isEmpty()` 设游标、再发探针；探针仍负责「其实没有更早」时收起。教训：这类「加了个收敛条件」的改动要同时确认旧的正向赋值还在，否则功能整体失效且不报错。
- 翻页按钮误显示（0.21.11）：真机截图发现**刚建的 5 条消息会话也顶着「⬆ 加载更早记录」**。实测服务端 `cursor.next` 第一页永远非空（跟进该 cursor 回 `{data:[], cursor:{previous:null,next:null}}`），所以「next 非空 ⇒ 有更早」的判断在本机 2.0.12 上恒真，旧实现等于每个会话都常驻一个空转按钮。现 `normCursor()`（把 `null`/`undefined`/空串归一为空）加两道收敛：① `loadHistory()` 只在 `arr.length() >= MSG_PAGE_SIZE(50)`（真取满一页）时才设 `earlierCursor`；② `loadEarlier()` 遇到空页 / `addedViews==0`（全是已渲染条目）一律置空并提示「已到最早」。代价：恰好整 50 条且真没更早时，会多显示一次按钮，点一次即消失。
- 思考标题措辞（0.21.11）：折叠态以前一律显示「💭 思考过程（点开看全部）」，可短思考（≤6 行）其实全露着，看着像按钮坏了。现 `setReasonHeader()` 在 `post` 后按 `Layout.getLineCount() > maxLines` 判断是否真被截断：没截断就只显示「💭 思考过程」。
- 工具行可见性（0.21.10）：事件驱动后暴露一个反直觉问题——`date` 这类工具从 started 到 success 只有 **约 160ms**（实测抓包），横幅刚 setText 就被下一次 `refreshStatus()` 摘掉，肉眼完全看不见（真机反馈「没看到齿轮」；旧 3 秒轮询反而因为采样+滞留看起来"更明显"）。现加 `lingerToolText` + `lingerHideTask`：工具完成时横幅改写 `✅ 已完成 · 目标`（失败 `❌ 工具失败`）并保持 **1.5 秒**再清；新工具开始 / 新一发 / 完成对账都会 `clearToolLinger()`。`refreshStatus()` 取值顺序变为 `currentToolText()` → `lingerToolText`。
- 版本号可见（0.21.10）：列表页底部第二行右侧常驻灰色小字 `v<versionName>`（`PackageManager.getPackageInfo`），用来一眼确认「安装更新」是否真装上——之前装没装、装的是哪版全凭感觉。
- 草稿保存（0.21.9）：`input` 内容在 `onPause` 写 `SharedPreferences(opencode_drafts)` 键 `d_<sessionID>`，进页 `restoreDraft()` 回填并置光标到末尾；`send()` 发出后立即清该草稿（避免下次进来回填已发内容）。以前打一半切出去查资料，回来字全没了。
- 键盘修复（Android 15 edge-to-edge 下 `adjustResize` 可能失效）：`getWindow().setSoftInputMode(ADJUST_RESIZE)` + API 30+ 用 `WindowInsets.Type.ime()` 高度给 root 加 bottom padding（resize 已生效时 inset 为 0，不会双重顶起）；输入框获焦延迟 300ms 滚到底。**0.22.3 起统一并入 `Edge.apply(root, onImeOpen)`，同一处还补了状态栏 top 与导航栏 bottom，见 §3.5。**
- 输入区尺寸约定：输入框 `minLines=3 maxLines=6`，按钮高 44dp、宽 ≥56dp、14sp（防误触下限，不再缩小；挤不出空间时砍按钮数量而不是缩小按钮——0.10.1 把停止移出底栏即此例）。
- 可信运行状态（标题行 + 输入框上方固定横幅，同源显示，解决“长时间 AI 正在回复 不知道在干什么”）：
  - 状态机字段（全在 UI 线程读写，0.11.4 起 `session.status/error` 分支也在 `runOnUiThread` 内改状态：之前分支跑在 SSE 后台线程直接写状态调 `showDone()`，与 `setBusy` post 到主线程的 `refreshStatus()` 穿插，出现过横幅 `✅ 已完成`、标题行还停在“正在写回复”的打架）：`runningTools`（LinkedHashMap partID→人话，并行工具取最后一个）、`writingShown`、`doneShowing`（✅ 2 秒窗口，`Handler.postDelayed`，主线程定时器非新线程）、`suppressDone`（abort/error/发送失败后 idle 不许显示 ✅）。
  - `refreshStatus()` 是唯一写标题行/横幅的入口：审批条可见 → 横幅隐藏、标题“等你确认权限”；有 running tool → 工具人话（标题行绝不许出现“AI 正在回复”）；无 tool 且收到过文字增量 → “✍️ 正在写回复…”；busy 无事件 → “⏳ 已发送，等待 AI 响应…”；否则隐藏横幅、标题“空闲”。
  - 工具映射白名单（V2 实测 `Assistant.Tool{name,state{status}}`，`toolLine` 读 `name` 兼容旧 `tool`，`streaming` 视同 running，`error` 兼容字符串/`{message}` 对象；`state.title` V2 已无，恒空）：`read/glob/grep/lsp`→🔧 正在读取项目文件；`edit/write/apply_patch`→✏️ 正在修改代码；`bash`→⚙️ 正在执行命令；`webfetch/websearch`→🌐 正在联网查找资料；`task/skill`→📦 正在处理子任务；其他→🔧 AI 正在处理…，`state.title` 非空后缀 ` · `+截断 30 字。`pending/running` 登记、`completed/error` 移除。
  - 只有非 suppress 的完成（V2：`execution.succeeded`）才 `showDone()`（✅ 2 秒后隐藏）；`loadHistory` 对账与 SSE 重连提示不得覆盖 ✅ 窗口；审批 `replied`/手动回复后 `refreshStatus()` 恢复。
  - 网络抖动显示（VPN 场景，V1 语义，V2 待复测）：App↔Server 走本机回环不受 VPN 影响；VPN 抖动影响的是 Server↔模型，表现为 `session.status=retry{attempt,message}` → 横幅/标题显示“🔁 网络波动，正在重试（第 N 次）：原因（截 40 字）”，优先级仅次于审批、高于工具行；`retry` 不清工具表（恢复后继续），`busy/idle/error/send` 清掉。事件流本身断开（`sseOk=false`）时横幅追加“⚠️ 事件连接中断，重连中，状态可能不是最新”，重连成功自动消除；空闲时保持旧提示。
  - 实测（V1，2026-10-03 临时会话，事后已删）：bash pending→running→completed + title `pwd` 映射正确；abort 后 tool 输出 `User aborted`、无 error、不显示 ✅；`external_directory /sdcard/Download/*` 真触发 `permission.asked`，API reject 成功。retry 分支按 schema 实现，未能安全复现，仅编译保证。V2 下 tool 流与 retry 语义待带工具的真 run 复测。
  - 旧逻辑已删除：`setBusy` 不再直接写“AI 正在回复”，`busy` 不等同于回复中。
  - 实测（V1，2026-10-03 临时会话，事后已删）：同上（0.11.6 三路对账验证用）。V2 对账见上 `/api/session/active`。

### 3.3 BrowserActivity.java（浏览器页，0.17.0 起）

- AI 代操作网页第1步：只装浏览器框，无 AI 桥。布局=顶栏（‹ 返回 + 地址栏 + 前往）+ WebView（weight 1）+ 底栏（◀ 后退 / 前进 ▶ / ⟳ 刷新，48dp 大按钮）。
- `WebView.getSettings()` 开 `JavaScriptEnabled + DomStorageEnabled`；链接留页内靠具名子类 `StayClient extends WebViewClient`（禁止匿名内部类，D8 坑见 MainActivity）。
- 入口：`MainActivity` 底部行第三钮“🌐 浏览器”；`Intent extra "url"` 可指定起始页，缺省 `https://github.com`；无协议头自动补 `https://`。
- 常驻页（0.19.1）：`singleTask` + 返回/系统返回键只退到列表（`CLEAR_TOP`，stop 不 destroy），重进不重载、桥一直绑着；真关页走底栏第四钮“✕ 关页”（`finish` → `detachWeb`，socket 照常驻）。根因：之前返回=`finish`=页销毁，用户体感“浏览器不常驻”。
- 系统返回键=网页后退（无历史才退页）；`onDestroy` 先 detach 再 `web.destroy()` 防泄漏。
- 已验证：本机 WebView 提供者为 `com.google.android.webview` + Chrome，可用。
- 下一步（0.18.0）：本机 HTTP 桥（读 DOM 文本）；0.19.0：`/open` + `/eval{js}` 写桥；0.20.0：截图回传 `/sdcard/Download/opencode/`。
- 读桥（0.18.0，`BridgeServer.java`）：手写 `ServerSocket`，零第三方；只绑 `127.0.0.1:4098`（4096/4097 已被 server 占）；`BrowserActivity.onCreate` 起、`onDestroy` 停，不常驻；开页 toast 提示。AI 侧 curl：
  - `GET /browser/status` → `{ok,bridge,auth,tokenFile,url,title}`（0.21.8 起必带 token）；
  - `GET /browser/text?max=N` → `{ok,url,title,total,text}`（`document.title+\n+body.innerText`，默认 2 万字、上限 6 万，超长注总字数）。
  - `evaluateJavascript` 必须 UI 线程发起、回调异步：`runOnUiThread + CountDownLatch` 同步等（12秒超时）；回的是 JSON 字符串字面量，`{"v":…}` 套一层解一次；`getUrl/getTitle` 同样走 UI 线程（5秒超时，拿不到给空串）。
  - 安全（0.21.8 起）：回环绑定 + token + Host/Origin 三道闸，见下条。
  - 桥鉴权（0.21.8）：此前 `127.0.0.1:4098` **零鉴权**——本机任意 App、乃至任意网页一个简单 POST 就能驱动 App 内浏览器（带用户登录态），是真洞。现三道闸：① `Host` 必须是 `127.0.0.1`/`localhost`（可带 `:4098`），否则 403，防 DNS rebinding；② **请求带 `Origin` 头一律 403**（浏览器页面发起的 fetch/XHR 必带，Termux curl 不带；同时堵住 App 自己 WebView 内网页的 CSRF）；③ `X-Bridge-Token` 必须等于本次起桥生成的随机 token（也接受 `?token=`），否则 401（回包带 `tokenFile` 路径）。token 每次 `start()` 用 `SecureRandom` 重生成（16 字节 hex），写 `/sdcard/Download/opencode/bridge_token.txt` 首行（写失败退 App 私有目录），`stop()`/「停桥」时删文件并清空。比较用逐字符 XOR 累计防时序试探。**不碰 WebView cookie**：网页登录态与桥 token 完全无关，用户仍只需登录一次。AI 侧用法见 `~/AGENTS.md`（`TOK=$(head -1 …)`，每个 curl 带 `-H "X-Bridge-Token: $TOK"`）。
  - 常驻（0.18.2）：桥改进程级（`BrowserActivity.live` 静态持有），关页只 `detachWeb` 不断 socket，重进页 `attach` 复用；关页后 `/text` 回空（无 WebView），`/status` 照常；停桥只走列表页“停桥”钮（桥活着才出现）或等进程死。根因：返回=关页=`onDestroy`，随页生死的桥没法边聊天边用。
  - 常驻页二次导航（0.21.14）：`singleTask` 下从聊天点链接只会复用实例、`onCreate` 不重跑，故补 `onNewIntent` → 读 `url` extra → `go(url)`（`urlBox`/`web` 为空则忽略）；链接点击走 `UrlClickSpan`，见 §3.2「正文链接可点」。
  - 写桥（0.19.0，截图暂缓）：`POST /browser/open {"url"}`（只收 http/https，`loadUrl` 走 UI 线程，关页时回 409）；`POST /browser/eval {"js"}`（任意 JS：点/填/读都走这一个口，2万字上限，结果截 2 万；关页回 409）。body 按字符读+8秒超时，中文请 `\u` 转义。AI 侧范例（0.21.8 起都要带 token；`TOK=$(head -1 /sdcard/Download/opencode/bridge_token.txt)`）：
    `curl -s -X POST -H "X-Bridge-Token: $TOK" 127.0.0.1:4098/browser/open -d '{"url":"https://github.com"}'`；
    `curl -s -X POST -H "X-Bridge-Token: $TOK" 127.0.0.1:4098/browser/eval -d '{"js":"document.title"}'`；
    点按钮：`'{"js":"document.querySelector(\"form button\").click()"}'`，填表：`'{"js":"document.querySelector(\"input\").value=\"x\""}'`。

### 3.4 Palette.java（深色模式，0.22.0）

- **背景**：以前颜色全写死在三个 Activity 里（`ChatActivity` 59 处、`MainActivity` 5 处、`BrowserActivity` 4 处，共 30 个色值），深色无从下手。
- **做法**：`Palette` 提供语义色，内部 `c(dark, 浅色值, 深色值)` 两套；取色一律 `Palette.xxx()`，**禁止再写 `Color.parseColor("#…")`**（`Palette` 内部除外）。语义色清单：`pageBg / surface / barBg / userBubble / textPrimary / textSecondary / textMuted / rowBg / codeBg / border / permBg / permText / questionBg / questionText / error / okBg / okText / interrupted / accent / onAccent / link / scrim / highlight`。
- **三态**：`MODE_SYSTEM / MODE_LIGHT / MODE_DARK` 存 `SharedPreferences(opencode_theme).mode`。`Palette.init(ctx)` 解析（跟随系统时读 `Configuration.uiMode` 的 `UI_MODE_NIGHT_MASK`）；`Palette.cycle(ctx)` 循环换档并落盘，同时 `gen++`。
- **主题应用**：`Palette.apply(activity)` 在 `super.onCreate` 之后、建任何视图之前调用——`setTheme(Theme_Material_NoActionBar / Theme_Material_Light_NoActionBar)`，这样 `AlertDialog` / `Spinner` 弹层 / `EditText` / `Button` 的默认配色一起变（只改背景会得到「黑底浅控件」的拼接感）；同时设 `windowBackground`、状态栏/导航栏颜色与图标明暗（API 30+ 用 `WindowInsetsController.setSystemBarsAppearance`，以下用 `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|LIGHT_NAVIGATION_BAR`）。
- **切换入口**：列表页标题行右侧按钮，文案即当前档（`🌓 跟随系统` / `☀️ 浅色` / `🌙 深色`），点一下换档再 `recreate()`——已建视图里的 Span 颜色改不动，重建最省事。对话框/下拉层因此也一起换。
- **列表项**：`simple_list_item_1` 的正文色来自主题，深色底上必须显式给色，故新增 `RowAdapter`（**static**，D8 坑见 3.1）在 `getView` 里设 `Palette.textPrimary()`。
- **常驻页跟随**：`BrowserActivity` 记 `themeGen = Palette.gen()`，`onResume` 发现代号变了就 `recreate()`（singleTask 常驻页不重建会「黑底浅控件」；WebView 重建但 cookie 在磁盘，登录态不掉）。`ChatActivity` 不处理：切档入口在列表页，聊天页返回即销毁。
- **网页深色（尽力而为）**：API 33+ `setAlgorithmicDarkeningAllowed(isDark)`，29–32 `setForceDark(FORCE_DARK_ON/OFF)`，26–28 无 API；站点自带深色时以站点为准，算法暗化的效果不保证。
- **配色值（浅 → 深）**：`pageBg #F5F5F5→#0F1216`、`surface #FFFFFF→#1B2026`、`textPrimary #222222→#E6EAF0`、`textSecondary #666666→#B2BAC4`、`textMuted #999999→#8B939D`、`rowBg/codeBg #EEEEEE/#F0F0F0→#242A31`、`border #CCCCCC→#3B444E`、`userBubble #DCF8C6→#24402C`、`permBg #FFF3E0→#3A2E1D`、`questionBg #E3F2FD→#16293C`、`error #C62828→#FF7B72`、`okText #2E7D32→#7FD88F`、`interrupted #8D6E63→#C3A69B`、`accent #1976D2→#2F6FD0`、`link #1565C0→#6CB0FF`。
- **已知限制**：① 主题在 Activity 创建时定死，深浅切换必须重建页面；② 网页暗化依赖 WebView 版本，效果不可控；③ 系统通知/系统弹窗由系统主题决定，App 改不了。
- **易漏点（0.22.1 补）**：`Color.parseColor("#…")` 之外还有直接写 `0xFF…` 的 **int 字面量**——表格单元格底色 `0xFFEEEEEE / 0xFFFFFFFF`、行内代码 `BackgroundColorSpan(0xFFF2F2F2)`。0.22.0 只换了 `parseColor`，漏掉这两处，深色下变成「白底浅字」，表格整片像空的（真机截图）。查漏命令：`grep -n '0x[0-9A-Fa-f]\{8\}' *.java`（`Palette.onAccent()` 恒白除外）。

- **易漏点（0.22.1 补）**：`Color.parseColor("#…")` 之外还有直接写 `0xFF…` 的 **int 字面量**——表格单元格底色 `0xFFEEEEEE / 0xFFFFFFFF`、行内代码 `BackgroundColorSpan(0xFFF2F2F2)`。0.22.0 只换了 `parseColor`，漏掉这两处，深色下变成「白底浅字」，表格整片像空的（真机截图）。查漏命令：`grep -n '0x[0-9A-Fa-f]\{8\}' *.java`（`Palette.onAccent()` 恒白除外）。

### 3.5 Edge.java（系统栏内边距 / edge-to-edge，0.22.3）

- **背景**：targetSdk 35 在 Android 15 上**强制 edge-to-edge**，窗口内容会画到状态栏、导航栏底下。本项目是纯代码布局，从未处理 insets——真机表现：聊天页标题「GitHub官方地址获取」被状态栏的时间和信号图标压住（截图实证），列表页靠 `dp(24)` 顶部 padding 碰巧躲过，底部输入栏也可能贴到导航条上。
- **做法**：`Edge.apply(root[, onImeOpen])` 给根布局挂 `setOnApplyWindowInsetsListener`，把 `statusBars | displayCutout` 的 top 与 `navigationBars / ime` 取最大后的 bottom **加到原有 padding** 上（记住原值，避免每帧累加）。API < 30 直接 return：老系统默认 `fitsSystemWindows`，内容本来就在栏下方。
- **不会双重留白**：Android 10–14 未强制 edge-to-edge 时，这些 inset 已被 decor 消费（读到 0），加 0 无副作用；键盘态若 `adjustResize` 已缩窗口，`ime` 也是 0（沿用 0.21.x 的判断）。
- **接入点**：三个 Activity 的根布局各一行 `Edge.apply(root)`；`ChatActivity` 传 `onImeOpen = () -> scrollDown()`，键盘弹出时滚到底部（替代原来只处理 IME 的监听器）。`MainActivity` 顶部 padding 从 `dp(24)` 降到 `dp(8)`，其余交给 insets。
- **取舍**：现在 padding 加在**根布局**上，所以状态栏那条带子显示的是页面底色（`pageBg`），不是顶栏颜色；要「顶栏颜色延伸到状态栏后面」得把 inset 加到顶栏自身并让顶栏背景铺到 y=0，改动更大，暂不做。
- **验证**：Android 15 真机，聊天页标题不被时间/图标遮挡、输入框随手势条留白；键盘弹出时输入栏在键盘上方且不被二次顶起。

## 4. Server API 表（OpenCode 2.0.12 已核实，App 只连 V2）
核实方式：`https://opencode.ai/v2/openapi.json`（117 接口，已存 `$PREFIX/tmp/v2-openapi.json` 即 `/data/data/com.termux/files/usr/tmp/v2-openapi.json`）+ 本机 `2.0.12` 实测（4097 端口测试会话，事后已删）。**全部要 Basic 鉴权**（`opencode:<密码>`，密码来源见第 1 节），**全部包 `{data}` 信封**（列表另有 `cursor` 分页）。V1 表（`1.18.29`）已退役，见 git 历史。

| 方法与路径 | 用途 | 备注 |
|---|---|---|
| `GET /api/info` | 连通检查 → `{version,pid,urls}` | Basic 通过（裸访 401）；V1 的 `/global/health` 已无 |
| `GET /api/session?limit` | 会话列表 `{data:[Session.Info],cursor}` | 默认 50 条；V1 返回裸数组 |
| `POST /api/session` | 建会话，body `{}`/`{"title"}` → `200 {data}` | 实测通过 |
| `GET /api/session/:id` | 会话详情 `{data}`（含 `cost/tokens`） | 费用显示靠它 |
| `PATCH /api/session/:id` | 改名 `{"title"}` → 204 | V1 要 200，V2 204 亦可 |
| `DELETE /api/session/:id` | 删除 → 204 | 实测通过 |
| `GET /api/session/:id/message` | 聊天记录 `{data:[Message],cursor}`，**服务端返回倒序（新在前）**，客户端先反转再按 `time.created` 稳定升序 | user 扁平 `{text,files[]}`；assistant `{agent,model,content[],finish,cost,tokens}`；`idle{outcome}` 为完成标记 |
| `GET /api/session/:id/diff` | 本轮改动 `data:[{file,patch,additions,deletions,status}]`，默认 newest user turn（`?from=` 可指定轮） | 空数组=本轮无文件改动；聊天页“改动”按钮靠它 |
| `POST /api/session/:id/prompt` | 发消息 `{"text","files?[{uri,name}]"}` → `200 {data:{inbox}}` | `text` 必填；无 per-message model/agent（先调 switch）；`FileAttachment{uri,name}` 无 mime，服务端自识别内联 |
| `POST /api/session/:id/model` | 切模型 `{"model":{"providerID","id","variant?"}}` | `Model.Ref` 要 `id`（错用 `modelID` 则 400）；`variant`=思考档位（0.20.0，可选，不带键即模型默认）；发送前按真实下拉值先调；新建会话动态切第一个 opencode 免费模型 |
| `POST /api/session/:id/agent` | 切 Agent `{"agent": id}`（发 id 如 `build`） | 下拉非默认时先调 |
| `POST /api/session/:id/interrupt` | 停止 → `{interrupted:bool}` | 闲时 false，无害 |
| `GET /api/event` | SSE（包络 `{id,type,data}`） | 完成事件 `session.execution.succeeded/failed`；流增量 `session.text.delta` |
| `GET /api/session/active` | 在跑会话表 `{data:{sid:…}}`，缺席即 idle | 替代已消失的 V1 `/session/status`，看门狗+重连对账用 |
| `GET /api/model` | 模型下拉 `{data:[{id,modelID,providerID,name,cost,variants[]}]}` | 只留 `opencode` 且 `cost` 全零免费（实测 10 个），收费/他 provider 隐藏；`variants[].id` = 思考档位（0.20.0 全量建表含收费模型） |
| `GET /api/agent` | Agent 列表 `{data:[{id,name,mode}]}` | `primary` 过滤，排除系统 agent |
| `GET /api/permission/request?sessionID=` | 待审批 `{data:[{id^per,action,resources[],message?}]}` | 实测空为 `{data:[]}` |
| `POST /api/session/:sid/permission/:rid/reply` | 审批 `{"decision":"once"\|"always"\|"reject"}` | 枚举同 V1，键名 `decision`，域限本会话 |
| `GET /api/form?sessionID=` | 待答提问 `{data:[{id^frm_,title,fields[]}]}` | field `{key,title,description,type,options[{value,label,description}],custom,required,hidden}` |
| `POST /api/session/:sid/form/:fid/reply` | 提交 `{"answer":{key: value\|[values]\|bool}}` | `optionValue()` 做 label→value 回查 |
| `DELETE /api/session/:sid/form/:fid` | 跳过提问（取消表单） | 替代 V1 `POST /question/:rid/reject` |

明确无接口（实测 404 / openapi 无）：`GET /session/:id/todo`（待办显示已隐藏）、`GET /session/status`（V1 全量表）、V1 全套裸路径（`/session`、`/event`、`/permission`、`/question`、`/config/providers`、`/agent`、`/prompt_async`、`/abort`——打 V2 会返回 HTML 或 4xx）。

SSE 事件 `type`（`data` 内含 `sessionID`）：`server.connected` / `session.inbox.enqueued|delivered` / `session.execution.started|succeeded|failed{error?}` / `session.step.started{assistantMessageID,agent,model}|streamed|ended|failed` / `session.reasoning.started{assistantMessageID,ordinal,state}|delta{assistantMessageID,ordinal,delta}|ended` / `session.text.started|delta{assistantMessageID,ordinal,delta}|ended` / `session.usage.updated` / 全套 `*.updated` 配置事件。询问类事件名不在冻结 schema，以子串兜底（见 3.2）。

**0.21.0 实测补充（重要）**：V2 `2.0.12` 现在**确实有工具级 SSE 事件**（旧文档说"未观测到"已过时，见 3.2「工具行现状」）：`session.tool.input.started{assistantMessageID,id,name}` → `session.tool.called{assistantMessageID,id,input}` → `session.tool.progress{assistantMessageID,id,metadata}`（可多次）→ `session.tool.success{assistantMessageID,id,content[]}`，另有 `shell.created/exited/deleted`。0.21.9 已接入这套事件替换掉原先每 3 秒拉 `/message` 的轮询（实测 `input.ended` 早于 `called` 到达，**不能**当完成标志；完成只认 `success`/`failed`/`error`）。抓包样本：`{"type":"session.reasoning.delta","data":{"sessionID":"…","assistantMessageID":"msg_…","ordinal":0,"delta":"…"}}`。

## 5. 构建与安装（必读，有坑）

- **构建方式**：纯 Android SDK，不用 Gradle、不用联网下载依赖。在 `app/` 下跑（手机存储分区可能不支持执行权限，统一用 `bash` 启动）：
  ```
  cd "/sdcard/opencode伪安卓版/app"
  bash build-apk.sh
  ```
  依次做：`aapt2 compile → aapt2 link → javac → d8 → zipalign → apksigner`，产物 `app/OpenCodeClient.apk`。
- **工具链（勿升级）**：`build-tools/34.0.4` + `platforms/android-35` 编译 + `platforms/android-33` 链接 + `javac --release 17`。教训：`35.0.1` 的 `aapt2` 在本机报 `Exec format error`，只能用 `34.0.4`（Gloomfall 同款已验证）。
- **安装**：项目已在手机存储，安装器可直接读取，无须再复制到 Download：
  ```
  termux-open "/sdcard/opencode伪安卓版/app/OpenCodeClient.apk"
  ```
  历史坑：以前项目在 Termux 私有目录（`~/...`）时安装器读不到，会报“解析软件包时出问题”，才需要中转 Download；搬家后已无此问题。
- **版本**：每次发版 `AndroidManifest.xml` 的 `versionCode +1`（否则系统拒更新）。历史：1=0.1.0 空壳连通 → 2=0.2.0 列表新建 → 3=0.3.0 聊天 → 4=0.3.1 键盘+模型 → 5=0.4.0 删除改名复制审批 → 6=0.4.1 一键复制指令 → 7=0.5.0 可信运行状态栏 → 8=0.6.0 网络重试与中断显示 → 9=0.7.0 App 内安装更新按钮 → 10=0.8.0 提问条（question.asked/reply/reject，修“3个问题只显示AI正在处理”）→ 11=0.8.1 提问横幅防盖屏（标题+240dp滚动选项区+常驻提交/跳过，收起/展开）+ 历史提问进页默认折叠（修“提问盖住界面看不到确定键”“旧提问进页就弹”）→ 12=0.8.2 单选点已选项可取消（修“点错无法取消，只能换选，纯手输也被迫带选项”；提交校验不变，每题仍至少要选项或手输其一）→ 13=0.8.3 费用/token 显示（列表行 + 聊天标题，`GET /session`/`GET /session/:id` 的 `cost`/`tokens`，缺口表第 5 条结项）→ 14=0.8.4 Todo 进度显示（聊天标题 `（待办x/y）`，进页/idle/SSE `todo.updated` 刷新，缺口表第 2 条结项）→ 15=0.9.0 Agent 下拉（模型行下方，`GET /agent` 过滤 + `prompt_async.agent`，缺口表第 3 条结项）→ 16=0.10.0 发图片（`＋图` 选图压缩 + `file` part，历史占位行，缺口表第 1 条结项）→ 17=0.10.1 停止移顶栏右侧仅忙时显示、底栏只留图/输入/发送（修“输入框被挤压”）→ 18=0.11.0 附件入口收成顶栏 `＋` 并支持发文件（图片压缩/文件原样 5MB 上限，按 mime 占位行）；另 2026-10-03 项目从 `~/opencode-android-client` 搬到 `/sdcard/opencode伪安卓版`（无代码变更，版本号不动）→ 19=0.11.1 修发 `.md` 后整页被 base64 糊住（历史 file part 的 `data:;base64` 不再进气泡，只显示类型+大小，文件名/`label` 截断）→ 20=0.11.2 修发 `.md` 后 AI 回复看似空白（Server 把文本附件展开成 synthetic 全文转储：隐藏 `Called the Read tool` 噪音、折叠 `>300` 字转储为一行占位；空 reasoning 跳过；无内容 assistant 泡显示 `⋯ AI 正在准备回复 ⋯`）→ 21=0.11.3 修发 `.md` 报错空白（根因：Server 拒收 `text/markdown` mime，`UnknownError` + assistant `parts=[]`，旧占位把错误盖住；现 `.md` 强制发 `text/plain` + 保留原后缀，`info.error` 直接红字显示）→ 22=0.11.4 修标题行与横幅打架（根因：`session.status/error` 分支在 SSE 后台线程直接改 UI 状态调 `showDone()`，与主线程的 `refreshStatus()` 穿插；现分支统一在 `runOnUiThread` 内执行）→ 23=0.11.5 修安装更新一次都没成功过（根因：缺 `STATUS_PENDING_USER_ACTION` 处理，从没拉起过系统确认框；现用 `EXTRA_INTENT` 拉起，失败 toast 带状态码）→ 24=0.11.6 修服务端已完成客户端永久等待（根因：完成只认 `session.status idle`，新规范 `session.idle` 未处理 + SSE 丢事件无兜底；现 `session.idle` 同路径完成 + `busy` 30s 无事件轮询 `GET /session/status` + 重连即对账，三路共用 `onServerIdle()`）→ 25=0.12.0 完整迁 V2（`opencode 2.0.12`，`4097` + Basic 鉴权 + 全 `/api` + `{data}` 解包：A 基建与列表页 → B 历史/发送/SSE/停止 → C 模型/Agent 下拉 → D 审批 permission/form 提问 → E 附件 `FileAttachment` 实测 + 待办隐藏 + 本次文档同步；V2 实测：问答流 `fledge-alpha-free` 免 key 可用，`session.execution.succeeded/failed` 为完成事件，`text.delta` 按 `assistantMessageID` 追加，`todo` 与 `/session/status` 确认消失；`fledge` 免费模型偶发 `provider.invalid-output` 致 run failed，属模型侧抖动非客户端 bug）→ 26=0.13.0 只显示 opencode 免费模型（`GET /api/model` 过滤 `providerID==opencode` 且 `cost` 全零，81 取 10如 `fledge-alpha-free`，收费/openrouter 隐藏，不迁 openrouter key；`toolLine` 按 V2 实测改读 `name`+`streaming`+对象型 `error.message`；文档补 `files[]` 回显/`synthetic` 折叠/`permission.asked` 分支/openapi 路径）→ 27=0.13.1 修选模型发不出+默认不用 opencode（根因：切模型误发 `modelID` 键，服务端要 `Model.Ref{id,providerID}`，一选就 400 中断发送；新建会话跟随服务端默认 `openrouter` 免费；现切模型改发 `id`，新建自动切 `opencode/fledge-alpha-free`，默认会话无模型空转（assistant `content=[]` 常 running）即消失）。→ 28=0.13.2 修聊天记录倒序（根因：V2 服务端 `GET /api/session/:id/message` 返回倒序数组（新在前），App 原样顺序 `addView` 导致最新消息在最上；现 `loadHistory()` 先按 `time.created` 升序排序再渲染）。→ 29=0.13.3 修停止反馈延迟且被标红（根因：`abort()` 只发 Toast 不改 UI 状态，得等十几秒服务端 `session.execution.failed` 才知道已停；且 V2 中断 run 的 `idle.outcome` 为 `interrupted`，被旧逻辑渲成红字「❌ 运行未成功：interrupted」；现 `abort()` 立即 `stoppedAwait` 置位、标题行/横幅即显「⏹️ 已发送停止，等待服务器确认中断…」、`setBusy(false)` 并补一次 `/api/session/active` 对账，`execution.failed` 命中 `stoppedManually` 时只显示中性「已停止」不红字不报错，`interrupted/aborted/cancelled` 的 idle 历史渲染为灰棕「⏹️ 已停止」）。→ 30=0.13.4 修运行中只见空白气泡看不到进展（根因：V2 无 part 级 tool SSE 事件，运行时气泡只收 text delta，用工具期间只剩一条白条；现忙时每 3 秒轮询 `/api/session/:id/message` 快照把 running/pending/streaming 的 tool part 同步进 `runningTools`，上方状态栏即实时显示"正在执行命令/正在修改代码"等；预建 AI 气泡改为"… AI 正在处理，进行中的工具见上方状态栏 …"占位，首个 text delta 到达即移除）。→ 31=0.13.5 修 0.13.4 轮询取错消息（V2 消息倒序，0.13.4 从数组尾部找 assistant 取到的是最老一条，导致工具状态永远不更新；改为从数组头找最新 assistant；另 V2 工具名为 `shell`，白名单补上等同 bash 的映射）。→ 32=0.13.10 修早先对话看不见（根因：服务端 `GET /api/session/:id/message` 默认只回最新 50 条，App 从不跟 `cursor`，长会话早期消息永远不渲染；现存 `cursor.previous`，顶部“⬆ 加载更早记录”按游标向前翻、prepend 后按高度差回滚保持位置；`msgBubbles` 查重防重叠页重复）。→ 33=0.13.11 修 0.13.10 翻页 400（根因：第一页是倒序、“更早”在 `cursor.next` 那边，0.13.10 误跟了 `previous`（往更新方向，空页/400）；现改存 `next`，实测 `next` 链直达首条；另翻页失败 toast 附服务端原文前 120 字，不再盲猜）。→ 34=0.13.12 修翻页后顺序错乱+最新记录丢失（根因有二：① `loadEarlier()` 用 `added++` 计数，把 `idle(succeeded)`/`agent-switched`/`system` 等不渲染的消息也算进去，多搬了底部最新视图到顶部，50 条里约 15 条不渲染，必错位；②搬运循环 `remove(start)+insert(base+i)` 在条数>1 时本就错位；现按 `childCount` 差值只搬实际新增、先收后插，同毫秒并列改先反转再稳定排序；真机 `ses_eeafe354` 两页实测：页内 `assistant/idle/user/agent-switched` 混排，不渲染占比约 30%）。→ 35=0.14.0 加本轮改动查看（`GET /api/session/:id/diff`，`FileDiff.Info{file,patch,additions,deletions,status}`，空即无改动；Agent 行下常驻按钮，进页/idle 刷新数字，点看文件列表+patch，长按复制全文）。→ 36=0.15.0 加跑完叫你（`await_/done_` 记 SharedPreferences；聊天页记/清+离页完成直接通知；进页按 `/api/session/active` 对账；列表页 15 秒轮询+`✅`/`⏳`前缀+点进清绿勾；`POST_NOTIFICATIONS` 权限；返回键销毁后靠列表兜底）。→ 37=0.15.1 修审批串单（根因：`checkPendingPerm()` 在无本会话单子时回退取列表第 0 条，把别的会话的审批弹到本页，回复时 `sid/rid` 对不上必 404；现只收本会话的单、无单时顺手收掉旧条子；回复 404 改为中性提示“这条审批已经没了”）。→ 38=0.15.2 修改动永远显示无改动（根因：`session.diff` 在本机 2.0.12 上所有会话所有轮都回空、无快照；现主力从聊天记录认 newest user turn 后的 edit/write/apply_patch 工具，`diff` 只做 patch 增强，无 patch 时只列文件并明示）。→ 39=0.16.0 加再试一次（顶栏 `↻ 重试`，仅失败后出现；`lastSentText` 复用 `send()` 重发，带附件只回填；无服务端 retry 接口，客户端重发等价）。
→ 40=0.17.0 加浏览器页（AI 代操作网页第1步：`BrowserActivity` 纯手动浏览，地址栏+后退/前进/刷新，`StayClient` 具名类留页内；`MainActivity` 底部加“🌐 浏览器”钮；`versionCode 44`；HTTP 桥下版再加）。
→ 41=0.18.0 加读桥（AI 代操作第2步：`BridgeServer` 手写 ServerSocket 只绑 `127.0.0.1:4098`，随浏览器页生死；`GET /browser/status|/browser/text`；evaluateJavascript 走 UI 线程+latch 同步；`versionCode 45`）。
→ 42=0.18.1 桥状态可见（`Listener` 具名回调：监听中/启动失败异常原文/收到的请求都显示到浏览器页状态行；Termux 侧看不见别家 logcat 与 socket 表，排障全靠这行；`versionCode 46`）。
→ 43=0.18.2 桥常驻进程（关页不断：静态 `live` + `attach/detachWeb/isBound`；重进页复用不断线；列表页“停桥”钮只在桥活时出现；`versionCode 47`）。
→ 44=0.19.0 加写桥（`POST /browser/open|/browser/eval{js}`，点/填/读走一个 JS 口；body 字符读+超时；截图按用户要求暂缓；`versionCode 48`）。
→ 45=0.19.1 浏览器页常驻（`singleTask` + 返回只退列表不销毁 + 底栏“✕ 关页”真关；重进不重载；`versionCode 49`）。
→ 46=0.19.2 修返回真销毁（返回按钮/系统返回误用 `CLEAR_TOP`，把浏览器页一起清掉；改 `REORDER_TO_FRONT` 只搬列表到前面；`versionCode 50`）。
→ 47=0.20.0 加思考深度开关 + 下拉初始值改为会话真实状态（V2 思考档位 = `Model.Ref.variant`：模型/Agent/思考三下拉进页读 `/api/session/:id` 的 `agent`+`model{providerID,id,variant}` 并选中真实项，不再用「默认（跟随会话）」；新增「思考:」下拉与 Agent 同行各半，档位在 `loadModels` 时从 `/api/model` 的 `variants[]` 全量建表（含收费），换模型重建并回落「默认」（守护 static `ModelSelectListener` + `lastVariantModelKey`），发送时把档位并进 `/api/session/:id/model` 请求；实测 `deepseek-v4.1-flash-free` 的 `low/high/max` 切 `high` 回 204、会话 `model.variant=high`。同时修新建会话模型写死 `fledge-alpha-free` 的坑——该模型已从 server 免费列表消失致静默失败，现改为动态取第一个 opencode 免费模型（`MainActivity.switchSessionModel` 先拉 `/api/model` 再切）；`versionCode 51`）。
→ 48=0.21.0 加表格/代码框等宽渲染 + 崩溃日志 + 推理实时显示（① `applyRichText` 把 ``` 代码块、字符框（U+2500–U+257F）、markdown 表格、ASCII 框套 `TypefaceSpan(monospace)`+浅底色，单 TextView 走 Spannable、流式每增量重建，含代码块的历史气泡加 `📋 复制代码`；② `CrashLogger.install()` 全局未捕获异常处理器写 `/sdcard/Download/opencode/crash-*.txt`，列表页按需显示 `🐞 查看崩溃日志`；③ `reasoning.delta` 以前被丢弃，现 `appendReasoning` 实时显示、6 行折叠可展开；另抓包证实 V2 已有 `session.tool.*` 事件，待后续替掉 3 秒轮询；`versionCode 52`）。
→ 49=0.21.1 修表格渲染（真机 0.21.0 实测：markdown 表格仍是原始 `| a | b |` 文本、中文表头对不齐；`📋 复制代码` 按钮跑到了气泡顶部）。① 纯等宽方案对中文表格无解（中文双宽/拉丁单宽非整数倍），改为 `hasMarkdownTable`→`splitSegments`→`addMarkdownTable` 渲成真 `TableLayout` 网格（表头灰底加粗、单元格描边、横滑、长按复制），普通段落仍等宽富文本；② `📋 复制代码` 挪到正文之后；`versionCode 53`）。
→ 50=0.21.2 渲染收尾（按用户反馈三项：表格压缩一屏宽 / 代码块去 ``` 引号 / 历史思考折叠）。① 表格去掉横滑改 `TableLayout` MATCH_PARENT + 单元格换行；② `Seg` 加 `CODE`，`splitSegments` 剥代码围栏与语言行，`addCodeBlock` 等宽浅底（超宽横滑保缩进），正文统一走 `renderTextSegments`；③ `reasoning` part 改 `addCollapsedReasoning` 默认折叠 6 行；`versionCode 54`）。
→ 51=0.21.3 修表格收缩（0.21.2 真机复现：表格被裁、既看不全又不能拖）。根因 `TableLayout` 默认列不可收缩（`mShrinkAllColumns=false`），去横滑后设 MATCH_PARENT 也不会压，只是被裁。加 `setShrinkAllColumns(true)` + `setStretchAllColumns(false)`、单元格 padding 收 6/4；`versionCode 55`）。
→ 52=0.21.4 表格改回可横滑（0.21.3「压缩到一屏宽」对多列表格太糟：列被压窄、文字乱换行；AI 常出超屏宽表、用户又无法拖看。现 `setShrinkAllColumns(false)` 保持列自然宽度 + 外套 `HorizontalScrollView`（开滚动条）可左右拖，`hsv.post` 判定真溢出时才在表下显示「← 表格较宽，可左右滑动查看 →」提示；`versionCode 56`）。
→ 53=0.21.5 加「复制全文」按钮（分段渲染后长按只得一段，用户反馈「复制 AI 回复每次只有一部分」；`renderMessage` 按 AI 消息拼全部 `text` part 原文——含 markdown 表格——挂底部「📋 复制全文」，跳过 `synthetic` 附件转储、用户消息不加；`versionCode 57`）。
→ 54=0.21.6 密码移出源码、仓库公开（原先真实密码硬编码在 `MainActivity` 与文档里，公开即等于公开本机服务密码；现 `loadServerPassword()` 读 `/sdcard/Download/opencode/server_password.txt` 首行、读不到回落占位符 `YOUR_SERVER_PASSWORD`，`SERVE_CMD` 同步用读到的值；§1 新增「复现所需环境」表，写明 Termux 需 F-Droid 版、OpenCode 必须 2.0.12 V2；`versionCode 58`）。
→ 55=0.21.7 修「AI 输出时锁死滚动」（用户真机反馈：回复流式刷新时无法上滑看别的内容）。根因 `scrollDown()` 在 `appendDelta`/`appendReasoning`/`setPartText`/`ensureBubble` 每次都被无条件调用 `fullScroll(FOCUS_DOWN)`，把视图抢回底部。现改为「贴底才跟随」：`stickToBottom` 由 `ScrollView.setOnScrollChangeListener → updateStick()` 维护（`isAtBottom()` 距底 ≤24dp），非贴底时 `scrollDown()` 不动作；滑回底部或点新加的右下角悬浮「⬇ 回到底部」按钮才恢复。新增 `scrollDownForce()` 用于自己发消息/首次进页；消息区改为 `FrameLayout` 承载 ScrollView + 悬浮按钮；`loadHistory()` 重拉时按 `firstRender` 区分（首次进页强制到底；用户上滑则复原原 `scrollY`）；`versionCode 59`）。
→ 56=0.21.8 桥加 token 鉴权（此前 4098 零鉴权：任意 App／任意网页一个简单 POST 就能驱动带登录态的 App 内浏览器）。三道闸：Host 限回环（防 DNS rebinding）、带 `Origin` 一律 403（堵网页 CSRF，含 App 自己 WebView 里的网页）、`X-Bridge-Token` 必须匹配本次起桥生成的随机 token（也收 `?token=`），否则 401。token 每次 `start()` 用 `SecureRandom` 重生成写 `/sdcard/Download/opencode/bridge_token.txt` 首行，停桥删文件；比较用 XOR 累计防时序。**不动 WebView cookie，用户登录态零影响**。AI 侧 `~/AGENTS.md` 已同步 token 读法；`versionCode 60`）。
→ 57=0.21.9 去掉忙时整包轮询 + 草稿保存（① 工具状态改事件驱动：实测 `session.tool.input.started/called/progress/success` 完整，`handleEvent` 直接维护 `runningTools`，`input.started` 的 `name` 用 `liveToolNames` 记住；**删掉 `pollToolStateOnce()`**——原每 3 秒拉 `/api/session/:id/message` 整包 50 条（含工具全量输出，长会话数 MB）只为读工具名；`startToolPoll`→`startBusyPoll` 只保留权限/提问小接口兜底。注意 `session.tool.input.ended` 早于 `called`，不是完成标志，完成只认 success/failed/error；② 草稿保存：`onPause` 写 `SharedPreferences(opencode_drafts)` 键 `d_<sessionID>`，进页 `restoreDraft()` 回填，`send()` 后清掉；`versionCode 61`）。
→ 58=0.21.10 修「看不到正在执行的工具」（0.21.9 事件驱动后真机反馈没看到齿轮）。根因：`date` 这类工具 started→success 仅约 160ms（实测抓包），横幅刚写上就被摘掉，一帧都没画出来；旧 3 秒轮询反而因采样+滞留显得更明显。现工具完成时把结果挂 `✅ 已完成 · <目标>`（失败 `❌ 工具失败`）**1.5 秒**再收（`lingerToolText` + `lingerHideTask`，新工具/新一发/完成对账都清），`refreshStatus` 取值 `runningTools` → `linger` 兜底；另列表页底部加灰色小字 `v<versionName>`，方便确认是否真装上；`versionCode 62`）。
→ 59=0.21.11 修「新会话也显示加载更早记录」+ 思考标题措辞（真机截图发现 5 条消息的新会话顶着翻页按钮：实测服务端第一页 `cursor.next` 永远非空，跟进回 0 条+next=null，所以旧判据恒真。现 `normCursor()` 归一 null/undefined，且只在取满 50 条时才显示按钮；空页/全重复页一律判定为已到最早。另折叠思考标题改按 `Layout.getLineCount()>maxLines` 判断是否真截断，短思考不再显示「点开看全部」；`versionCode 63`）。
→ 60=0.21.12 翻页按钮彻底收敛（0.21.11 之后真机：最老一页刚好 50 条时按钮仍顶着，点一下回 0 条才消失）。实测 `/message` 支持 `?limit=`，故整页取回后用 `?limit=1&cursor=…` 探针确认是否真有更早，回空即收起；`earlierProbeSeq` 防重拉后旧的探针结果乱改状态；网络异常按「有」保留入口；`versionCode 64`）。
→ 61=0.21.13 流式渲染节流（每个 delta 全文重建 Span+setText 是 O(n²)，长回复发热掉帧；现增量先入 buffer，UI 每 100ms 合并刷一次，滚动同步降频；在 text.ended/reasoning.ended/execution.succeeded|failed/abort 强制 flush，新建气泡首字立即刷，loadHistory/onDestroy 丢弃在途任务；`versionCode 65`）。
→ 63=0.21.15 修断线重连丢字/重复（根因：① SSE `setReadTimeout(0)` 无限等，死连接永不报错、横幅不出也不重连；② 重连只补 `busy` 不补正文；③ `text.delta` 按 messageID 定位，历史 `content[].id` 实测为空只能用 `mid#index`，两边键对不上 → 重拉后迟到增量另起 TextView 重复尾巴。现：SSE 读超时 60s（服务端 15s 一条 `: heartbeat`）→ 死连接必现异常走重连；重连 `pollSessionStatus(true)` 对活动会话 `loadHistory()` 补漏；`lastSseLineAt` 心跳时间戳 + `onResume` 超 25s 无字节则对账；`addPartView` 给最后 text 视图加 `mid` 别名、`addCollapsedReasoning(mid)` 登记 `ReasonBlock`，重拉后增量续写同一视图不再重复；`versionCode 67`）。
→ 64=0.22.0 深色模式（三态：跟随系统 / 浅色 / 深色）。新增 `Palette.java`：23 个语义色 + 浅/深两套值，三个 Activity 的 68 处写死颜色全部换掉（`ChatActivity` 59、`MainActivity` 5、`BrowserActivity` 4）；`Palette.apply()` 切框架主题（`Theme_Material[_Light]_NoActionBar`），让 AlertDialog / Spinner 弹层 / EditText / Button 跟着变，并处理窗口底色与状态栏图标明暗；列表页标题行右侧加切换钮（文案即当前档）+ `recreate()`；新增 static `RowAdapter` 给列表项显式文字色（`simple_list_item_1` 的颜色来自主题，深色下会看不清）；`BrowserActivity` 记 `themeGen`，`onResume` 发现变档就 `recreate()`（常驻页不重建会黑底浅控件，cookie 在磁盘不掉登录），并尽力把网页暗化（33+ `setAlgorithmicDarkeningAllowed` / 29–32 `setForceDark`）；`versionCode 68`）。
→ 65=0.22.1 修深色模式表格不可见（0.22.0 只替换了 `Color.parseColor`，漏掉直接写 int 的两处色值：表格单元格 `0xFFEEEEEE/0xFFFFFFFF`、行内代码 `BackgroundColorSpan(0xFFF2F2F2)`；深色下白底配浅字，整张表看着是空的。现改走 `Palette.rowBg()/surface()/codeBg()`；`versionCode 69`）。
→ 66=0.22.2 修「⬆ 加载更早记录」按钮永不出现（0.21.12 加 `?limit=1` 探针时误删了 `earlierCursor = nxt` 赋值，只剩「回空则清空」分支，游标恒 null；长会话超过 50 条就只剩后半段，看着像丢了历史。现 `loadHistory()` 先按「取满一页 && next 非空」设游标，再发探针做最后收敛；`versionCode 70`）。
→ 67=0.22.3 修状态栏压住顶栏（targetSdk 35 在 Android 15 强制 edge-to-edge，本项目纯代码布局从未处理 insets，聊天页标题被状态栏时间/图标遮挡——真机截图实证）。新增 `Edge.java`：`Edge.apply(root[, onImeOpen])` 把 `statusBars|displayCutout` 的 top、`navigationBars/ime` 取最大后的 bottom 加到原 padding 上，三个 Activity 各接一行；`ChatActivity` 的键盘滚动改用同一监听器（删掉旧的纯 IME 监听）；`MainActivity` 顶部 padding 24dp→8dp 交给 insets；API<30 直接跳过，未强制 edge-to-edge 的机型 inset 为 0 不会双重留白；`versionCode 71`）。
→ 68=0.22.4 修「失败原因被吞看起来像断线」（真机：发消息 4 秒就空响应，App 只显示「❌ 运行未成功：failed」）。根因：V2 的失败原因挂在 **assistant 消息对象**上（`error{type,message,status}`，实测 `provider.quota` = 免费额度 429），而 `renderV2Message` 构造的 v1 形态 `info` 只有 role/id，`extractErrorMsg(info)` 永远拿不到 → 历史只剩 outcome，没有原因。现：① assistant 分支把 `m.error` 复制进 `info`；② `errorHint()` 给 quota/invalid-output/network 补中文提示（换模型 / 重试）；③ idle 在已显示原因时不再重复「运行未成功」壳（`lastRunErrorShown`，用户新发言时复位）；④ `execution.failed` 的 toast 从 80 字提到 160 并附提示；`versionCode 72`）。
→ 73=0.22.5 工具输出可见（真机：聊天里工具只有一行状态行 `✅ shell [completed] · df -h`，AI 到底跑了什么、结果一个字都看不到，要核对得切 Termux。实测 `GET /api/session/:id/message` 的 `content[{type:"tool"}].state.content` 携带完整输出（`[{type:"text",text:"…"}` 数组，单条最大 12KB）。现 `addPartView()` tool 分支完成后渲染 `addToolOutput()`：工具行下等宽 + codeBg 折叠 3 行 + 「▸ 展开/点收起」切换 + 长按复制全文；只动历史渲染路径，流式/text/reasoning 零改动；`versionCode 73`）。
→ 62=0.21.14 正文链接可点（`applyRichText` 出口统一走 `setLinkifiedText`：Linkify WEB_URLS + 自定义 `UrlClickSpan` 打开 App 内浏览器，失败退回系统；`LinkMovementMethod` + 蓝字下划线；长按复制与点击同时触发的老问题用 `lastLongPressAt` 900ms 抑制；`BrowserActivity.onNewIntent` 支持常驻页二次导航；`versionCode 66`）。
- **环境**：`~/android-sdk`（platforms `android-33/35`、build-tools `34.0.4`）、`gradle` 仅备用（本项目不用）、JDK 21（`javac --release 17`）。

## 6. 已知缺口（V2 已核实，未排期）

> 2026-10-05 本机 `2.0.12` 实测（V1 缺口表已归档见 git 历史）。V2 下 `todo` 无端点、`session.created/updated/deleted` 未在 V2 事件流实测出现，`share` 未在 openapi 117 接口里出现。

| # | 缺口 | Server 证据 | App 现状 | 修法方向 |
|---|---|---|---|---|
| 1 | 发图片/文件附件 | `POST /api/session/:id/prompt` 的 `files[{uri,name}]`；服务端内联 base64 | 0.12.0 已做：`＋` 选图→压缩→`file://` 发送，历史占位显示 | ✅ 结项（V2 无 mime 字段，旧 `text/markdown` 坑消失） |
| 2 | Todo 进度显示 | V2 无 todo 端点（404，openapi 无） | 0.12.0 已隐藏：`refreshTodos()` 清空后缀 | ⛔ V2 侧无解，等上游加端点 |
| 3 | Agent 切换 | `GET /api/agent` → `data[]`（`build` 等，`mode=primary`） | 0.12.0 已做：Agent 下拉（发 id），发送前 switch | ✅ 结项 |
| 4 | 会话回滚 | V2 有三段式 revert（`stage/commit/clear`）+ `session.diff`/`context`，V1 单步语义已变；叠加上游 V1 revert 旧坑（#20955/#10287/#9811） | ⛔ 暂缓：手机上一键回滚可能静默毁代码，比没有按钮更危险 | 在 Termux 里勤 `git commit` 再让 AI 大改；先用 plan 模式审方案 |
| 5 | 费用/token 显示 | `Session.Info.cost/tokens`（列表+详情实测可见） | 0.12.0 已做：列表行 + 聊天标题显示 | ✅ 结项 |
| 6 | 分享会话 | openapi 117 接口里无 share（V1 有 `/session/:id/share`） | 无入口 | 低优，最后做 |
| 7 | 列表多端同步 | V2 事件流实测未见 `session.created/updated/deleted` | 列表页靠手动刷新 | 进页/返回时重拉即可 |

已确认非缺口（不用记）：权限审批、提问、重试/中断显示、停止、中止后对账均已覆盖（见 3.2）。

## 7. 约定（稳定性 > 简单 > 可维护 > 花哨）

1. 零第三方依赖（只用 Android SDK + `org.json`）；Java 不用 Kotlin；UI 用代码写不用 XML（减少 aapt 故障点）。
2. 一次只改一小步，每步在真机编译+安装实测通过再做下一步；修 bug 先在“测试会话”上验证，不动用户重要会话。
3. 新接口先查 V2 `openapi.json` + 本机实测，禁止按 V1 脑补；改名/删除等危险操作必须二次确认。
4. **改完代码必须同步更新本说明书**（见顶部强制条款），并 `versionCode +1`。
