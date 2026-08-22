# AirFerry Lite 工程交接

给后续接手的人：当前怎么跑、发送/接收怎么实现、对照速度、哪些路不能再走、怎么发到 GitHub Pages。

对外说明只写 [README.md](README.md)。不要在 README 里放版本号、实测 KB/s、Worker / VideoFrame 细节或本文链接。

**交接时点：** 2026-08-23。网页接收端 **v86**。Android APK **0.8.89**（versionCode 103）。给蓝只发 GitHub Actions Artifacts 直链。

**0.8.89 冻结（蓝已确认）：** 四码速度正常；首次点「接收文件」后几秒内**不**弹「正在释放相机」倒计时。解码：未锁多码整幅 `maxSymbols=4`，四码头锁 `quadStream` 后只并行扫格 4。收完 `pauseScanner` 不 unbind；继续接收相机仍绑则热启动。自动恢复只留软解码，**不要**空扫 HAL 杀进程。**不要** `dualFastPath` / `dualStream`（0.8.73–0.8.85 回归：全图单码、空扫 90%+）。

过程：0.8.86 四码串行 8 路补扫 → 采集 33.2 FPS / 会话 121 KB/s；0.8.87 改回并行格 4；0.8.88 仅跳过唯一帧=0 仍会在瞄准时弹倒计时；0.8.89 去掉自动杀进程。

## 1. 项目一句话

电脑浏览器把文件打成连续 QR，手机摄像头扫回来，无服务器。当前发送走 **AFL2**（Decimen v0.3 MIT：二进制帧 + LT 喷泉码）。网页和 APK 都能收；旧 **AFL1** 文本流只作兼容。

仓库：https://github.com/shuipashui/airferry-lite  
网页接收：https://shuipashui.github.io/airferry-lite/  
发送端：https://shuipashui.github.io/airferry-lite/sender/dist/airferry-lite-sender.html  

工作副本：`C:\Users\UU\airferry-lite`。默认分支 `main`。不要 force-push。

## 2. 人、机、测法

- 用户：**蓝**，软件工程师。直接改代码、看诊断、迭代。不要主动 commit/push，除非明确要求。
- 主力测试机：Xiaomi / 红米（M098FE, songyuan）· Android 16 · Chrome 151。相机 **0–60 FPS**，预览常为 **1440×1920 竖屏**。
- 电脑屏：**60 Hz**。四码必须 **四个码都进取景框**；拿太近只看见 1–2 格会明显掉速。
- 网页**只在 GitHub Pages 上测**，本地 `index.html` 没有摄像头 HTTPS。改接收端必须升 `RECEIVER_BUILD` / `index.html?v=` / `sw.js` 的 `CACHE_NAME`，同步 `web-receiver/`，跑 `npm test`，再推 `main`。
- 发送端 UI/布局改完跑 `node sender/build.mjs`，再推。不必升接收端版本。
- **出 APK：** 推 `android-receiver/**` 触发 `Build Android receiver`。只发链接：`https://github.com/shuipashui/airferry-lite/actions/runs/<runId>/artifacts/<artifactId>`。不要写本地 `android-receiver/dist/` 或 Gradle 输出路径。

## 3. 当前冻结面

| 部件 | 版本 | 对照 |
|---|---|---|
| 网页接收端 | **v86** | 预览 30/60 FPS；四码 inflight 1 · 33 ms；识别 `layoutCodes=2` |
| Android APK | **0.8.89**（versionCode 103） | 四码并行格4（蓝确认正常）；首次接收无倒计时；继续热相机 |
| 发送端 | AFL2 单文件 HTML | 单码预填 **2953 B · 30 FPS**；四码 **1465 B · 30 FPS**；双码 **2068 B · 60 FPS**（V33） |

诊断第一行：`网页：v86` 或 `App 0.8.89`。

## 4. 实测对照（只认这些）

电脑 **60 Hz**。红米 M098FE。会话速度看「唯一载荷」对应 KB/s。

### 网页（2331 B · 30 FPS）

| 布局 | 会话 |
|---|---|
| 单码 | **53.8 KB/s** |
| 四码全屏 | **43.3 KB/s** |

### APK 双码 2068 B · 60 FPS（窗口 · 非全屏）

| 场景 | 采集 | 每帧 | ROI | 会话 |
|---|---|---|---|---|
| **冻结 · 0.8.43 首次** | 60.0 | ~1.99 | 格 2 | **234.6 KB/s** |
| **冻结 · 0.8.43 继续接收** | 59.9 | 2.00 | 格 2 | **238.4 KB/s** |
| **冻结 · 0.8.43 强杀重开** | 59.5 | 1.96 | 格 2 | **236.2 KB/s** |
| 0.8.83 继续（无卡顿） | 59.2 | ~1.8 | 格 2 | 实时 **240**；会话受爬坡影响 |
| 0.8.89 | 待测 | ≥1.9 | 格 2 | 目标 **≥220 KB/s**；首次接收不得弹倒计时 |

动手前：**首次 / 继续 / 强杀** 不能低于 **234.6 / 238.4 / 236.2**。点「接收文件」后应一直停在扫描预览；继续接收相机仍绑时热启动。两者都不应出现「正在释放相机」倒计时。

### APK 四码 1465 B · 30 FPS（整屏同换）

| 采集 | 每帧 | ROI | 会话 |
|---|---|---|---|
| 59.1 | **2.94** | 格 4 | **168.9 KB/s**（冻结对照） |
| 0.8.86 回归 | 33.2 | 1.67 | 格 4 | **121.1 KB/s**（串行补扫，勿回归） |
| **0.8.89** | ~60 | ≥2.5 | 格 4 | 蓝确认四码正常；对照 **168.9** |

### 理论上限

双码 2068·60 光学约 **240 KB/s**；四码 1465·30 约 **169 KB/s**。LT 约 1.15× 后文件通量再打折。

## 5. 发送端实现

源码：`sender/app.js`、`sender/styles.css`、`sender/template.html`。产物：`sender/dist/airferry-lite-sender.html`。改源码后 `node sender/build.mjs`。

- 双码 **`layoutCodes=2`（magic 0x1c / 0x1d）**，2×2 **上排**两枚，下排白底。60 FPS **两格同刷**。
- 四码 30 FPS **整屏同换**；60 FPS 四码仍交错，不要四格同刷。
- 不要 45 FPS。不要下排复制 QR / 对角 / 并排 2×1。

## 6. 网页接收端

根目录 `index.html` + `app.js` + `sw.js`；`web-receiver/` 必须 byte-identical。高速路径：`requestVideoFrameCallback` → Worker WASM。四码 `HIGH_QUAD_INFLIGHT = 1`，33 ms。

## 7. Android APK（0.8.89）

源码：`android-receiver/app/src/main/java/com/airferrylite/receiver/`。构建：GitHub Actions `Build Android receiver` 或 `android-receiver/build-local.ps1`（输出 `app/build/outputs/apk/debug/app-debug.apk`，**不要**发给蓝）。

- 分析流 **1920×1440** · `KEEP_ONLY_LATEST` · 标题行 30/60/120。
- **解码：** `main` 模型——`multiLayout` 时格位 + quadrant。未锁时 `maxSymbols=4` 整幅读。四码头锁 `quadStream`：格 4 后**只并行扫格**，命中 <2 才并行 overlay；**不要**串行 8 路（0.8.86 采集 33 FPS）。`isMultiLayout` 或 ≥2 命中锁多码。
- **不要** `dualStream` 早退、不要 `noteStreamLayout` 预 bootstrap、不要 `dualFastPath`（0.8.82–0.8.85 回归）。
- **生命周期：** 点「接收文件」立即开相机并扫描，几秒内不要弹倒计时。打开过程用面板盖住 PreviewView。收完 `pauseScanner`（不 unbind）；「继续接收」若相机仍绑则热启动，不杀进程。不要 `onStop` unbind。
- **HAL：** **不要**因空扫杀进程弹倒计时。软解码恢复可留。点「接收文件」立即绑相机，不等 HAL 冷却。
- **诊断：** 含 `总耗时`；ROI `格 N`；复制全文给开发。

## 8. 架构

```text
sender/dist/airferry-lite-sender.html   单文件发送端
index.html + app.js + sw.js             GitHub Pages 网页接收
web-receiver/                           根目录镜像
android-receiver/                       Kotlin + CameraX + zxing-cpp（0.8.89）
shared/ + highspeed-protocol.js         AFL1 / AFL2
tests/                                  npm test
```

## 9. 不要做（摘要）

- 网页：不要单码整幅压 720、不要四码 16 ms inflight 2、不要 SW `reload`。
- APK：不要 `ImageProxy.read()`、不要收完 `unbindAll` 后在同进程立刻 bind、不要 `onStop` unbind、不要看门狗 `unbindAll`、不要双码专用早退路径（已回退 main）、不要四码格 4 后再走串行 8 路补扫、不要空扫 HAL 杀进程倒计时。
- 发布：不要把本地 apk 路径发给蓝；不要 force-push `main`。

完整否定清单见 git 历史 `0.8.63` 版 HANDOVER §9；新增：**不要** 0.8.73+ 的 `dualFastPath` / 低 FPS `unbind` 重绑 / 每秒清格 / 空扫杀进程倒计时。

## 10. 怎么改网页、怎么上线

1. 改根目录或 `sender/`，接收端升 `RECEIVER_BUILD` 与 `sw.js` `CACHE_NAME`。
2. `node sync-receiver.mjs`，`npm test`，`npm run build`。
3. `git push origin main`。Pages 从 `main` 根目录自动发布。
4. 核对 https://shuipashui.github.io/airferry-lite/app.js 里 `RECEIVER_BUILD`。

## 11. 许可证

MIT。第三方见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
