# BeamFerry

电脑浏览器把文件编成连续二维码，手机摄像头扫回来，不经过服务器。

- 发送端：单文件 HTML，可直接打开，并显示手机接收页地址和二维码
- 网页接收端：https://shuipashui.github.io/beamferry/
- Android 接收端：原生应用，Android 10+
- 当前传输为 AFL2（二进制帧 + 喷泉码）；旧 AFL1 发送端仍可接收
- 文件只在发送电脑和接收手机本地处理

## 使用

- 发送端：https://shuipashui.github.io/beamferry/sender/dist/airferry-lite-sender.html
- 网页接收端也可从发送页右侧的地址 / 二维码进入；开始播放文件码流后该入口会隐藏

1. 在电脑打开发送端，选择文件。
2. 首次打开默认四码 `2068 B · 30 FPS`；按需调整后点击「生成二维码流」。四码可窗口播放；全屏模块更大、更稳。
3. 手机打开网页接收端或安装 APK，允许摄像头并开始扫描。APK 可在标题行切换相机 30/60/120（默认 60；达不到会回落）。
4. 对准二维码保持稳定。网页收完后下载文件；APK 点「接收文件」才开相机，收完后关相机预览，再点保存或「继续接收」。诊断区可直接滚动查看。

网页接收端需要 HTTPS 才能打开摄像头。Chrome 若一直停在旧版，可清掉该站数据后再打开。

## 推荐参数

面向常见 60 Hz 电脑屏。发送端会按实测刷新率对齐播放。

| 场景 | 参数 |
|---|---|
| 单码 | **2953 B · 30 FPS**（切换到单码时预填；也可改 2331 / 1465 B） |
| 四码 | **2068 B · 30 FPS**（首次打开默认；V33，四码 2×2） |
| 双码 | **2068 B · 50 FPS**（60 Hz 屏使用 5/6 vsync 节拍；只有上排两枚） |
| 较远或摩尔纹明显 | 单码 1465 B，或四码 1273 / 1003 B |
| 不要用 | 单码 60/120 FPS；四码贴着屏幕拍；45 FPS（60 Hz 上等于 30） |

## 实测记录（2026-08-24）

测试环境：Android 16、电脑屏幕 60 Hz、相机分析流约 60 FPS。

| 布局与参数 | 结果 | 结论 |
|---|---|---|
| 上排双码 · 2068 B · 50 FPS | 最佳 `1.92` 码/帧、约 **199 KB/s**；另一次受光学相位影响降至约 40–59 KB/s | 当前双码峰值最优，但启动稳定性仍受屏幕刷新、曝光和摩尔纹影响 |
| 纵列双码 · 2068 B · 50 FPS | 约 `1.15` 码/帧、约 **118 KB/s** | 无稳定收益，方案已移除 |
| 对角双码 | 多数只有单码命中，约 **119 KB/s** 或零速 | 无稳定收益，方案已移除 |
| 四码 · 1465 B · 50 FPS | `2.32` 码/帧、约 **168 KB/s** | 与 30 FPS 接近；50 FPS 保留为实验档，不替代四码 30 FPS 推荐值 |
| 四码 · 1732 / 1952 / 2068 B · 30 FPS | 3 MB 文件每档重复 5 次，1952 B 与 2068 B 多数接近各自理论上限，峰值约 **224 / 239 KB/s** | 作为当前高密度四码基准；2068 B 设为默认 |

双码 50 FPS 在 60 Hz 屏上按每 6 个 vsync 更新 5 次，理论光学速度约 202 KB/s。接收端在上述测试中维持约 60 FPS、零丢帧、平均解码低于 9 ms；主要波动来自光学时序，而不是分析线程吞吐。

## 限制

- 单次文件上限 64 MiB。
- 刷新网页或关掉应用会丢失当前 AFL2 进度，需要重新扫描。
- 速度受屏幕亮度、摩尔纹、对焦、手机性能和反光影响。
- 四码让四个码都进手机画面，不要贴太近。全屏模块更大；APK 窗口模式也可以收。
- 测试四码高密度档时记录发送端显示的 QR Version、模块数、每模块设备像素和画布尺寸，并复制 APK 诊断用于对比命中率与吞吐。
- gzip 传输需要接收端支持 `DecompressionStream`；当前 Android Chrome 和 Android 应用均支持。

## 目录

```text
index.html / app.js / sw.js                 网页接收端（GitHub Pages 根目录）
sender/dist/airferry-lite-sender.html       单文件发送端
sender/                                     发送端源码
android-receiver/                           Android 应用
shared/                                     AFL1 / AFL2 协议
protocol/SPEC.md                            线协议说明
tests/                                      测试
```

## 本地构建

需要 Node.js 18+。

```powershell
npm test
npm run build:sender
npm run build
```

`npm run build` 会生成协议 bundle、同步网页接收端镜像，再生成单文件发送端。

Android 调试包需要 Java 17 和 Android SDK Platform 35：

```powershell
cd android-receiver
.\build-local.ps1 assembleDebug
```

APK 输出为 `android-receiver/app/build/outputs/apk/debug/app-debug.apk`（仅本地自测）。**交付请只用 GitHub Actions** 工作流 `Build Android receiver` 的 artifact `beamferry-android-debug`；不要保留或分发仓库里的旧 APK。

## 和 AirFerry / Decimen 的关系

本项目品牌为 BeamFerry，与上游 AirFerry 项目区分；协议兼容 AFL2/旧 AFL1。

当前发送 AFL2，参考 [decimen-optical-transfer](https://github.com/bashalarmistalt/decimen-optical-transfer) v0.3.0（MIT）：二进制 LT 喷泉码、固定掩码、发送端 lookahead。网页用 ZXing WASM 解码，Android 用 zxing-cpp。Decimen 后续 AGPL 版本的四码 / RaptorQ 没有纳入本项目。旧 AFL1 接收代码仍保留，用于读取旧发送端。

## 许可证

MIT License。第三方说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
