# BeamFerry Maintainer Handover

本文档只记录当前可维护基线、发布流程和仍需验证的问题。历史实验、逐版本流水账及外部项目实现分析不在此保留；提交历史是这些内容的唯一归档。

## 当前基线

| 项目 | 当前值 |
| --- | --- |
| Android 接收端 | `0.8.135-quad-stall-classifier`，`versionCode 148` |
| 网页接收端 | `v89`，四码 3 帧有界流水线 |
| 电脑屏幕接收端 | `desktop-v1`，四码 60 FPS 高速路径 |
| 稳定推荐 | 四码 `2068 B / 30 FPS` |
| 实验配置 | 双码 `2068 B / 50 FPS`；四码 `50/60 FPS` |
| 相机分析流 | `1920x1440`；不要恢复已移除的 1080p 选项 |
| 网页接收端 | 仅接收单码和四码；明确拒绝双码 AFL2 |

由 `desktop-pages.yml` 发布的线上入口：

- 原网页摄像头接收端：https://shuipashui.github.io/beamferry/
- 发送端：https://shuipashui.github.io/beamferry/sender/dist/beamferry-sender.html
- 电脑屏幕接收端：https://shuipashui.github.io/beamferry/desktop-receiver/

## 产品边界

| 布局 | 发送端 | 网页接收端 | Android 接收端 |
| --- | --- | --- | --- |
| 单码 | 支持 | 支持 | 支持 |
| 双码 | 支持 | 不支持 | 支持，实验模式 |
| 四码 | 支持 | 支持 | 支持；30 FPS 稳定，50/60 FPS 实验 |

四码 30 FPS 是稳定路径。高速四码的校准、补扫和重定相逻辑必须受高帧率 AFL2 标志约束，修改时不得改变四码 30 FPS 行为。

## 架构摘要

BeamFerry 使用 AFL2 二进制帧和系统 LT 喷泉码。发送端先发源符号，再发修复符号；四码 50/60 FPS 在首轮源符号结束后，以 `1:63` 比例稀疏穿插第二遍源符号和新修复符号，重播以与 K 互质的步长覆盖全部源块。四码 30 FPS、双码和单码仍使用原调度。

电脑屏幕接收端使用独立的 4 Worker WASM 解码池、有界 3 帧抓取流水线和固定四格 ROI。高速四码发送端在首轮之后每 64 个尾段符号稀疏重播一个系统块，其余发送新 LT 修复方程；接收端剩余不超过 160 块且剥离停滞时，可对已收方程执行稀疏 GF(2) 消元。该消元不改变 AFL2 线格式，旧发送端仍兼容。

发送端将连续序号轮转到四个物理格位，避免固定盲格永久吞掉同一 modulo-4 源符号通道。网页接收端使用受限并发的 Web Worker 和 ZXing WASM，只保留最新待分析帧。`v89` 并行启动 Worker，四码允许最多 3 个相机帧流水处理，并在异步抓图前预占 Worker；仍使用单张 720 图集在 Worker 内切四格，Canvas 兼容回退保持串行。Android 使用 CameraX Y 平面和 zxing-cpp，四格裁剪通过 JNI 解码后以单批次提交协议层。

## Android 高速四码路径

高速路径分别校准左上、右上、左下、右下四个真实物理格位：

- 校准不足 `4/4` 时，每 4 个分析帧从完整 1440p 中心方形探测尚未确认的象限，并交替使用普通和备用二值化。
- 只有真实 QR 命中才能确认并冻结格位；不能用推算框冒充已校准格位。
- 已确认格框使用 35% 扩边，以容纳滚动快门与轻微构图误差。
- 校准完成前不做小框单格加强，也不允许相机重定相打断校准。
- 相机重定相后保留已验证的 `4/4` 格位缓存；单会话重定相总上限为 3 次。
- 固定 ROI 短时全空时不能被稀疏残片改写或清空。
- 校准完成后观察 1.5 秒且连续两个低命中窗口才允许早期重定相；相邻重定相至少间隔 5 秒。
- 中后期重定相依据“最近唯一符号”而不是任意重复帧，并要求全空或至少两个格位同时失活。
- 持续弱格每 12 次 miss 可单独扩大到约 51% 总扩边重试，健康格仍保持 35% 热路径。

诊断中的 `QR/frame`、四格命中分布、`格位校准 N/4`、唯一载荷与光学载荷比相机 FPS 更能解释吞吐。相机采集约 60 FPS 且丢帧为 0，并不表示每帧能解出四码。

## 当前性能结论

- 电脑屏幕接收端最新实测：Windows 10、Edge 151、`2560x1600` 屏幕源、`2068 B / 四码 / 60 FPS`，采集/分析约 `66.6 FPS`、有效码约 `266.5 FPS`、尾段实时约 `435.2 KB/s`、滚动平均约 `430.3 KB/s`、完整会话约 `425.4 KB/s`。
- 同一测试中识别 4862、唯一 3907、重复 955、无效 0；`K=2975` 正确完成。尾部消元为 `0/0`，表示本次普通 LT 剥离已直接完成。
- 先前 1:1 系统重播实验会把重复数推高并将会话速度降至约 `336 KB/s`，已经撤销。1/64 稀疏重播在合成丢帧基准中比 1/8 少约 8%–9% 的尾段帧。

- 健康会话中光学载荷应接近唯一载荷；差距明显增大通常表示重复采样或协议重复。
- 主要瓶颈仍是有效 QR/frame、物理格位偏科和屏幕/相机滚动快门相位，不是协议队列。
- 四码高速已有明显改善，但相同设备与参数仍可能出现启动等待或中途光学空窗，因此不能承诺理论吞吐。
- `2068 B / 30 FPS` 仍是默认稳定配置。高帧率优化必须用完整会话速度与此基线对照。
- 后续优先提高弱格命中率并研究 50 FPS 在 60 Hz 屏幕/相机上的重复采样；原生 C++ 单 JNI 四 ROI 只能作为隔离实验引入。

## 验证与发布

网页和共享协议改动至少运行：

```powershell
npm ci
npm test
npm run build
git diff --check
```

构建会更新根目录接收端、`web-receiver/` 镜像、电脑屏幕接收端协议以及发送端单文件产物，提交前检查生成差异。`desktop-pages.yml` 从 `main` 部署站点根原网页摄像头接收端、`/sender/dist/beamferry-sender.html` 发送端和 `/desktop-receiver/` 电脑屏幕接收端；推送后应确认 Web CI 与 Desktop receiver Pages 成功，并实际访问三个入口。

Android APK 必须由 GitHub Actions 的 `Build Android receiver` workflow 构建，不把本地 APK 作为交付物。发布时提供 Actions 网页 artifact 链接：

```text
https://github.com/<owner>/<repo>/actions/runs/<run-id>/artifacts/<artifact-id>
```

Android 改动必须同步更新 `versionCode`、`versionName` 和界面诊断版本。若本次只有文档或网页变更，无需额外生成 APK。

## Git 与 GitHub 网络

本机 v2rayN/Xray 的 mixed 代理通常监听 `127.0.0.1:10808`。GitHub 443 直连超时时，可持久配置 Git 专用代理：

```powershell
git config --global http.proxy http://127.0.0.1:10808
git config --global https.proxy http://127.0.0.1:10808
git ls-remote https://github.com/shuipashui/beamferry.git HEAD
```

配置写入用户级 `.gitconfig`，不改变系统代理。v2rayN 端口变化时同步更新；需要取消时执行：

```powershell
git config --global --unset http.proxy
git config --global --unset https.proxy
```

推送前先 `fetch origin` 并检查分支图，避免覆盖远端提交。工作区可能包含用户未提交文件，合并和发布时只暂存本任务明确修改的文件。

## 关键文件

| 路径 | 职责 |
| --- | --- |
| `sender/` | 发送端源码与单文件构建 |
| `desktop-receiver/` | 独立电脑屏幕捕获接收端 |
| `app.js`, `index.html`, `sw.js` | Pages 网页接收端 |
| `web-receiver/` | 网页接收端同步镜像 |
| `android-receiver/` | Android CameraX/zxing-cpp 接收端 |
| `shared/` | AFL2 与喷泉码共享实现 |
| `protocol/SPEC.md` | 协议规范 |
| `.github/workflows/` | Web、Pages 与 Android CI |

## 待办原则

1. 优化必须以诊断数据和多次完整会话为依据，不用单次实时峰值判断。
2. 高速四码实验与稳定 30 FPS 路径保持隔离。
3. 不重新加入 1080p 分析流，除非新设备矩阵证明收益并完成回归测试。
4. 不堆叠多个不可归因的光学实验；每轮保留可比较的版本、参数和日志。
