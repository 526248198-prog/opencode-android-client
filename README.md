# OpenCode Android 极简客户端

在 **同一台 Android 手机** 上操作 [OpenCode](https://opencode.ai) 的极简客户端：Termux 里跑 `opencode serve`，App 通过 `http://127.0.0.1:4097` 直连，只做「大按钮 + 会话列表 + 聊天」，防误触优先。

不是 OpenCode Web UI 的复刻，也不实现 Agent / 模型 / 工具系统——全部交给本机的 OpenCode Server。

> 当前版本 **0.22.6**（版本历史全表见 [`DEVELOPER.md`](DEVELOPER.md) 第 5 节）。

## 它做什么

- **会话列表**：新建 / 改名 / 删除 / 费用与 token 显示；「跑完叫你」——15 秒轮询 + **前台 Service 兜底**（0.22.6 起，App 被系统杀掉后仍会通知），`✅`/`⏳` 标记
- **聊天页**：
  - 流式正文（100ms 节流防发热、贴底才跟随滚动、不锁屏）
  - **工具输出可见**（0.22.5）：每个工具的真实输出在状态行下等宽折叠 3 行，展开/长按复制全文
  - 推理过程折叠显示（流式实时 + 历史）
  - markdown 表格渲成真网格（可横滑）、代码块等宽底色、正文链接可在 App 内浏览器打开
  - 权限审批条、提问表单、附件（图片压缩 / 文件）、停止、重试、本轮改动查看、草稿自动保存
- **深色模式**（0.22.0）：跟随系统 / 浅色 / 深色三态，23 个语义色全员覆盖（列表/聊天/浏览器/系统栏）
- **模型 / Agent / 思考深度** 三个下拉，进页即显示会话真实状态（V2 `variant` 档位）
- **浏览器页 + 本机 HTTP 桥**：`127.0.0.1:4098`，让 AI 代读/代点网页（`/browser/status|text|open|eval`）。桥三道闸：回环绑定 + 随机 token（`X-Bridge-Token`）+ 带 `Origin` 一律拒绝
- **可靠性**：崩溃日志一键查看（闪退写 `/sdcard/Download/opencode/crash-*.txt`）、SSE 断线重连补漏、App 内一键安装更新
- 纯 Android SDK 构建，**零第三方依赖**（只用 `HttpURLConnection` + `org.json`），Java + 代码写 UI

## 跑起来需要什么

| 项 | 要求 |
|---|---|
| Termux | **F-Droid 版**（Play 商店版已废弃） |
| OpenCode | **2.0.x（V2 API）**，本机 2.0.12 实测——V1 与 V2 接口完全不同，照 V1 抄必崩 |
| Android | 8.0+（minSdk 26，targetSdk 35，Android 15 强制 edge-to-edge 已适配） |
| 构建 | `build-tools 34.0.4` + `platforms android-35`（编译）/ `android-33`（链接）+ JDK 21 |

```bash
# 1) 本机起服务（密码自己定）
OPENCODE_SERVER_PASSWORD='你的密码' opencode serve --hostname 127.0.0.1 --port 4097 --print-logs

# 2) App 读密码：文件第一行即密码，读不到则用占位符（会 401）
mkdir -p /sdcard/Download/opencode
echo '你的密码' > /sdcard/Download/opencode/server_password.txt

# 3) 构建（不用 Gradle）
cd app && bash build-apk.sh     # 产物 app/OpenCodeClient.apk
```

安装：`termux-open app/OpenCodeClient.apk`（或 App 列表页「安装更新」按钮）。

## 文档

**改代码前必读 [`DEVELOPER.md`](DEVELOPER.md)** —— 完整代码地图、V2 API 表、SSE 事件、构建坑（D8 / aapt2 / TableLayout / edge-to-edge）、版本历史，以及「改代码必须同步改文档」的强制条款。剩余待修项见 [`TODO.md`](TODO.md)。

## 状态

个人自用工具，按「能跑 > 稳定 > 简单」推进；接口随 OpenCode 2.x 演进，遇到上游改动请以 `openapi.json` + 本机实测为准。

## 许可

未附许可文件，默认保留所有权利。仅作个人自用与参考；其中 OpenCode 归其作者所有。
