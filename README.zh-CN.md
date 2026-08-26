# BeamFerry

[English](README.md) | 简体中文

[![Web CI](https://github.com/shuipashui/beamferry/actions/workflows/web-ci.yml/badge.svg)](https://github.com/shuipashui/beamferry/actions/workflows/web-ci.yml)
[![Android build](https://github.com/shuipashui/beamferry/actions/workflows/android-apk.yml/badge.svg)](https://github.com/shuipashui/beamferry/actions/workflows/android-apk.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

BeamFerry 通过连续二维码流，将文件从电脑屏幕传输到手机摄像头。文件的编码、显示、扫描、重建和校验均在本地完成，不需要上传服务器或注册账号。

> BeamFerry 面向近距离离线光学传输。实际吞吐取决于显示器、相机、环境光、对焦和设备解码性能；发送帧率不等于可保证的传输速度。

## 在线使用

- [打开发送端](https://shuipashui.github.io/beamferry/sender/dist/beamferry-sender.html)
- [打开网页接收端](https://shuipashui.github.io/beamferry/)
- Android 调试 APK 由 [Build Android receiver](https://github.com/shuipashui/beamferry/actions/workflows/android-apk.yml) 工作流生成，产物名称为 `beamferry-android-debug`。

网页接收端需要通过 HTTPS 或 `localhost` 访问才能调用摄像头。原生接收端要求 Android 10 或更高版本。

当前发布基线：Android `0.8.135-quad-stall-classifier`（`versionCode 148`），网页接收端 `v88`。

## 主要特性

- 文件编码和重建完全在本地完成
- 发送端支持单码、双码和 2x2 四码布局
- 使用系统 LT 喷泉码和二进制 AFL2 帧
- 可以从循环码流的任意位置开始接收
- gzip 确实能减小体积时自动压缩
- 文件恢复完成后进行 SHA-256 完整性校验
- 网页接收端使用 Web Worker 和 ZXing WASM 解码
- Android 接收端直接分析 CameraX Y 平面并使用 zxing-cpp
- 兼容接收旧 AFL1 码流
- 发送端可构建为独立的单文件 HTML

不同接收端的布局支持范围如下：

| 布局 | 发送端 | 网页接收端 | Android 接收端 | 状态 |
| --- | --- | --- | --- | --- |
| 单码 | 支持 | 支持 | 支持 | 稳定 |
| 双码 | 支持 | 不支持 | 支持 | 实验模式，仅保留横排双码 |
| 四码 | 支持 | 支持 | 支持 | 30 FPS 稳定；50/60 FPS 为实验模式 |

网页接收端会明确拒绝双码 AFL2 码流，并提示切换发送布局。双码仍用于 Android 测试和协议兼容。

## 快速开始

1. 在电脑上打开发送端并选择文件。
2. 首次使用建议保持默认的 `四码 / 2068 B / 30 FPS`。
3. 在手机上打开网页接收端或 Android 应用，并授予摄像头权限。
4. 确保屏幕上的所有二维码完整进入相机预览。全屏播放通常能获得更大的模块和更稳定的对焦。
5. 等待完整性校验完成，然后保存恢复的文件。

对于 60 Hz 显示器，建议从以下参数开始：

| 布局 | 推荐起始参数 | 说明 |
| --- | --- | --- |
| 四码 | `2068 B / 30 FPS` | 默认且最稳定的高吞吐配置 |
| 单码 | `2953 B / 30 FPS` | 更容易取景；对焦或摩尔纹较差时降低码密度 |
| 双码 | `2068 B / 50 FPS` | 仅 Android；实验模式，对相机/屏幕相位敏感 |
| 四码高帧率 | `1465 B / 50 或 60 FPS` | 实验模式；应以完整会话速度和 30 FPS 对比 |

如果识别断断续续，请先让手机离屏幕远一些，确保二维码静区完整可见；同时提高屏幕亮度、避开反光，并优先降低单码字节数，而不是继续提高帧率。

## 工作原理

```text
文件
  -> 可选 gzip 容器
  -> 系统 LT 喷泉码符号
  -> 二进制 AFL2 二维码帧
  -> 屏幕 / 相机光学信道
  -> 二维码解码器
  -> LT 数据重建
  -> SHA-256 完整性校验
  -> 恢复文件
```

AFL2 使用 20 字节紧凑二进制帧头和一个喷泉码数据块。发送端先发送系统符号，再发送修复符号；接收端无需请求重传，即可处理丢帧、重复帧和乱序帧。线协议与兼容规则见 [protocol/SPEC.md](protocol/SPEC.md)。

## 接收端说明

### 网页接收端

网页接收端只处理最新可用视频帧，并限制 worker 并发，不会建立无限增长的解码队列。单码模式跟踪一个紧凑 ROI；四码模式分别校准四个物理格位，并且只冻结经过真实二维码命中的格位。

当前浏览器版本的单文件上限为 64 MiB。刷新或关闭页面会丢失正在进行的 AFL2 重建状态。

### Android 接收端

Android 接收端直接分析 CameraX 亮度平面，并将四码裁剪区域交给 zxing-cpp 解码。稳定生产配置仍为四码 30 FPS。50/60 FPS 全刷属于实验路径：四个物理格位必须分别由真实命中确认，相机重新定相时保留已校准几何，并使用更宽的校准裁剪框；首轮系统符号结束后，以 `1:7` 比例穿插第二遍系统符号与新修复符号。该路径不会改变原有四码 30 FPS 行为。

双码采用同一横排的两个二维码，仅 Android 接收端支持。双码 50/60 FPS 路径会保持稳定的双格几何，并在持续缺半帧时尝试重新调整相机相位。滚动快门会造成较大性能波动，因此双码不是默认布局，也不构成稳定速度承诺。

报告性能问题时，请附上应用内诊断信息，包括发送字节数和帧率、相机分辨率和帧率、QR/frame、四格命中数、校准格数、唯一/光学载荷以及完整会话速度。

## 已知限制

- 当前网页版本的文件上限为 64 MiB。
- 光学链路为单向传输，接收端无法向发送端反馈并动态调整节拍。
- 滚动快门或屏幕切换破坏有效帧时，高密度和高帧率配置反而可能更慢。
- 压缩依赖浏览器的 `CompressionStream` / `DecompressionStream` 支持；对已经压缩的格式会跳过压缩。
- GitHub Actions 提供的是未签名开发调试 APK，不是应用商店发行版。

## 开发与构建

CI 使用 Node.js 22。安装依赖并运行完整的网页、协议和纠删码测试：

```bash
npm ci
npm test
npm run build
```

常用构建命令：

| 命令 | 输出 |
| --- | --- |
| `npm run build:sender` | 重新生成 `sender/dist/beamferry-sender.html` |
| `npm run build:receiver` | 重建协议/解码资源并同步 `web-receiver/` |
| `npm run build` | 同时构建接收端和发送端 |

Android 项目要求 Java 17 和 Android SDK Platform 35：

```powershell
cd android-receiver
.\build-local.ps1 assembleDebug
```

本地 APK 输出到 `android-receiver/app/build/outputs/apk/debug/app-debug.apk`。用于分发和测试的 APK 应来自 GitHub Actions，以保证构建过程可复现。

## 仓库结构

```text
app.js, index.html, sw.js       GitHub Pages 网页接收端
web-receiver/                   同步维护的接收端镜像
sender/                         发送端源码和单文件构建产物
android-receiver/               原生 Android 接收端
shared/                         AFL2 协议和喷泉码实现
protocol/SPEC.md                AFL1/AFL2 线协议文档
tests/                          协议、FEC、接收端和安全测试
```

## 安全与隐私

BeamFerry 不会主动通过网络传输文件内容。发送端只读取用户选择的本地文件；接收端仅在完整性校验通过后提供恢复文件。使用托管页面时，常规静态资源请求仍会访问 GitHub Pages；如需完全自主控制网络来源，可以自行部署本仓库。

二维码码流属于可见数据，任何能够看到屏幕的人都可能使用相机捕获传输内容。BeamFerry 提供完整性校验，但不提供加密或身份认证。传输敏感文件前，请先使用可信工具加密文件。

## 致谢

AFL2 的实现参考并使用了 [decimen-optical-transfer](https://github.com/bashalarmistalt/decimen-optical-transfer) v0.3.0 中采用 MIT 许可证的组件和设计。网页接收端使用 ZXing WASM，Android 接收端使用 zxing-cpp。完整许可证和署名见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

BeamFerry 是独立项目，与 AirFerry 没有关联。

## 许可证

[MIT](LICENSE)，版权所有者为 BeamFerry 贡献者。
