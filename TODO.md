# TODO（交接清单，2026-10-24 · 0.22.4 会话整理）

> 接手前**必读**：`DEVELOPER.md` 全文（第 3 节代码地图 + §3.4/§3.5 深色与内边距）。
> 当前版本 `versionCode 72 = 0.22.4`；新会话从 `73` 起加。构建/发布/文档同步规矩全在 DEVELOPER.md §5。

## P1 工具输出可见（默认折叠）✅ 已结项（0.22.5，versionCode 73，等真机复验后从清单摘除）

- **现状**：`GET /api/session/:id/message` 的 assistant `content[{type:"tool"}]` 里，`state.content` 携带完整工具输出，**实测单条最大 12KB**（shell/read），但 `ChatActivity.addPartView()` 的 tool 分支只渲染一行状态（`toolLine()`：`✅ shell [completed] · df -h`），输出一个字都看不到。用户想核对 AI 到底跑了什么、结果是什么，只能切 Termux。历史渲染和既有的 synthetic 折叠逻辑都在，缺的就是把 `state.content` 摘出来。
- **目标**：
  - 工具完成（`state.status=="completed"`）后在工具行下方显示输出，**默认等宽折叠 3 行**，点「展开」全量；再次点击收起；
  - 长按复制全文；`content` 为空数组就不显示（免得空占位）；
  - 控制在 addPartView 的 tool 分支里，**不要碰 text/reasoning/流式增量路径**（0.21.13 节流、0.21.15 键名映射都刚稳定过）。
- **注意**：输出本来就是模型回读用的文本（含 ANSI 转义的可能），要按**等宽 + 代码底色**渲染（复用 `Palette.codeBg()`），别走富文本解析；`state.content` 可能是数组（V2 实测 `content[]`），逐项取 `.text` 或字符串拼接，先真机抓一条实测结构再写。
- **验收**：跑一条 `ls`、一条 `grep` 长输出，折叠态 3 行 + 展开不炸滚动。

## P2 通知前台服务兜底（可靠性，已知复杂度高）

- **现状**：0.15.0 的「跑完叫你」依赖列表页 15 秒轮询 + 杀进程前的通知。**App 被系统杀死或长时间后台**后没有兜底，会话跑完了也不知道。
- **改法方向**：前台 Service 常驻 + SSE 监听「跑完叫你」。坑在先查真机：
  1. Android 14+ 要求 `<uses-permission FOREGROUND_SERVICE>` + `foregroundServiceType="dataSync"`（清单里加在 service 上），且 `FOREGROUND_SERVICE_DATA_SYNC` 是限定于数据同步类型，启动要用户可见的前台时机；
  2. 后台不能 startForegroundService 的场景要评估（App 没跑时起服务收不到“跑完”通知，因此真正的兜底时机只在 App 存活期间）；
  3. 电池优化名单不申请，接受系统杀（用户自己开）。
- **验收**：聊天页发一条会跑很久的指令 → 从最近任务划掉 App → 跑完收到通知。

## P3 深色模式收尾（0.22.x 留下的未验项）

- 已验证：列表页、聊天页主链路、表格/行内代码、浏览器页底栏。
- **未真机验证**：① 权限审批条（`permBg/permText` 黄棕色系）；② 提问条（`questionBg/questionText` 蓝色系）；③ 浏览器页切档重进（`themeGen → recreate()`，cookie/登录态应保留）；④ 「跟随系统」随系统切换。
- 附带小取舍（§3.5「取舍」已记录）：现在内边距加在根布局，状态栏那条带显示的是 `pageBg` 而非顶栏色，视觉上是“两种深浅拼接”；要消除得把 `Edge.apply` 改成给**顶栏自身**加 top padding 且顶栏背景铺到 y=0，改动面大，除非用户在意图案的接缝，否则不动。

## P4 先不排期待确认

- 待办显示（V2 无端点 404，等上游）、分享会话（V2 无接口）、会话回滚（文档 §6 已写“暂缓，危险”）、列表多端自动同步（V2 事件流没测出 session.* 增删事件，暂靠手动刷新）。

## 环境速记（新会话先对账）

- 仓库 `/sdcard/opencode伪安卓版`（origin `git@github.com:526248198-prog/opencode-android-client.git`，`main`；SSH 已通，改完直接 push，例如 `git add -A && git commit -F - && git push origin main`）。
- 构建：`cd /sdcard/opencode伪安卓版/app && bash build-apk.sh`；真机装：`termux-open app/OpenCodeClient.apk`，列表页底部小字核对 `v<版本>`。
- 服务端 OpenCode 2.0.12 在 `127.0.0.1:4097`，Basic `opencode:<密码>`（密码读 `~/../sdcard/Download/opencode/server_password.txt` 首行）；AI 查数据用 `curl -s -u "opencode:$(head -1 /sdcard/Download/opencode/server_password.txt) …"`。
- 硬规矩：零第三方依赖；**禁匿名内部类/非静态内部类**（D8 8.2.2-dev 崩）；每版 `versionCode +1`；改代码必须同步 DEVELOPER.md。
