# AirFerry Lite 交接文档

更新时间：2026-08-25

本文记录当前 `main` 的真实状态、已验证结果、已排除方案，以及对上游
[UR-SillyB/AirFerry](https://github.com/UR-SillyB/AirFerry) 四码 60 FPS 实现的分析。
历史数据仅用于解释决策，不应继续作为当前版本的验收门槛。

## 1. 当前基线

| 项目 | 当前值 |
|---|---|
| Android 源码版本 | `0.8.124-stable-30-profile` |
| versionCode | `137` |
| 最近已构建 APK | `0.8.118-beamferry`（新版本待 Actions 构建） |
| Web receiver build/cache | `v87` / `airferry-lite-v87` |
| Android 解码器 | `zxing-cpp 2.3.0` |
| 默认工作分支 | `main` |
| 推荐单码参数 | `2953 B / 30 FPS` |
| 推荐双码参数 | `2068 B / 50 FPS`，同一横排左右排列 |
| 推荐四码参数 | `2068 B / 30 FPS`，2×2 排列；发送端首次打开默认 |

- GitHub Pages：
  <https://shuipashui.github.io/beamferry/sender/dist/beamferry-sender.html>
- 当前 APK Action：
  <https://github.com/shuipashui/beamferry/actions/runs/32796379916>
- 当前 APK artifact：
  <https://github.com/shuipashui/beamferry/actions/runs/32796379916/artifacts/9544984872>

版本号或链接变化时，应同时更新本节，不能保留旧 APK 作为“当前版本”。

## 2. 当前发送端和协议行为

### 单码

- 单码使用一个 QR 区域。
- 当前推荐 `2953 B / 30 FPS`，这是已验证的稳定配置。

### 双码

- 生产模式只保留同一横排、左右两个 QR。
- `50 FPS` 是真实的 5/6 屏幕刷新节拍，不是界面标签伪装成 50 FPS。
- 双码发送链路允许两个独立数据流/缓存协作；旧文档中“禁止 dualStream”的说法已经失效。
- 已移除“双码纵列”和“双码对角线”方案。它们没有解决相位与滚动快门问题，还增加了分支和误锁风险。
- `0.8.115-dual-phase-recovery` 清空每轮接收的双码 ROI 缓存，避免跨会话沿用旧坐标。
- 双码已锁定后，单个 QR 被滚动快门切坏时保持已确认的双格几何；只有同一帧重新识别到两个码才重建格位。
- 60 FPS 双码连续多个窗口完整帧比例过低时，APK 自动重新绑定相机以改变屏幕/相机刷新相位；每轮接收最多尝试 3 次。
- 以上恢复逻辑只在 `dualLayout=true` 且请求 60 FPS 时触发，四码 `quadStream` 路径未修改。

### 0.8.115 修复验证

- GitHub Actions：`32796379916` 构建成功。
- APK artifact：`airferry-lite-android-debug`，下载页见本节顶部链接。
- JS 全量测试：`npm test` 通过。
- Android 新增双码布局保持和相位恢复状态机测试；本地未安装 Gradle，Android 单测由 Actions 构建环境执行。

### 四码

- 生产模式为 2×2 四格。
- `60 FPS` 现为实验性四格全刷：每个发送更新周期生成并绘制四个新符号，理论源符号率为
  `4 × 60 = 240 symbols/s`。原有 `30 FPS` 四码整屏更新分支、默认选择和预缓冲路径保持不变。
- 实验四码 60 使用独立 AFL2 magic `0x1e/0x1f`；接收端据此启用稳定四格缓存、连续 12 个完整空帧后的低频补扫、
  0/1/2/3/4 码帧统计和坏相位相机重绑。普通四码 30 仍用 `0x0d/0x0f`，不会触发这些实验恢复逻辑。
- 四码 60 仅在解块进度低于 15%、唯一载荷速度低于 120 KiB/s 且统计窗口低于 `0.75 QR/frame` 时重绑 CameraX；每轮最多一次，协议解块进度保留。诊断记录重绑前/后的 QR/frame。普通四码 30 不进入该策略。
- `0.8.121` 发送端四码光学实验将静区从 4 modules 收紧到 2 modules，并对四码画布应用轻度亮度/对比度增强；APK 增加 `1920×1080` / `1920×1440` 分析流切换，默认保持 1440。协议、线程和恢复策略本轮不变。
- `0.8.122` 将 APK 顶部改为独立品牌行和双分组控制行，修复窄屏标题逐字换行；帧率及分析流按钮使用高对比选中/未选中状态，分辨率显示为 `1080p/1440p`。扫描行为不变。
- `0.8.123` 默认分析流改为 1080p；实验四码 60 固定格位连续 4 次失格时，仅对该格运行 `GLOBAL_HISTOGRAM + tryHarder/tryInvert`，诊断显示单格加强次数。四码 50 FPS 在检测到真实屏幕刷新率变化时重新对齐 5/6 分数节拍，避免启动测频阶段遗留节拍信用。四码 30 路径不变。
- `0.8.124` 所有模式的新安装默认分析流恢复 1440p。四码 30 和双码恢复 4-module 稳定静区及原始画面；2-module 静区和亮度/对比度增强仅用于四码 50/60 FPS。相机诊断明确区分实际与请求分辨率。
- 当前稳定推荐仍为 `2068 B / 30 FPS`；发送端首次打开即为四码布局并预填该参数。60 FPS 全刷尚需从 `1465 B` 开始实测，不能把理论值当作稳定速度承诺。
- 四码保留 `1732 B (V30)`、`1952 B (V32)`、`2068 B (V33)` 档位，上限为 `2068 B`。
- 发送端速率诊断显示 QR Version、码体模块数、静区、每模块设备像素和最终画布设备像素；APK 从实际 AFL2 帧显示帧字节、QR Version 和模块矩阵。

### 编码和页面部署

- 发送端 QR 当前使用固定 mask：`qr.make(4)`。
- 四码 quiet zone 当前为每码 4 modules。
- 仅修改发送端静态页面时，不需要无条件提升接收端 cache 版本；只有接收端缓存资源发生变化且旧缓存会影响行为时才提升。

## 3. 当前设备实测结论

测试环境：

- 手机：Xiaomi M098FE，Android 16
- 屏幕：60 Hz
- CameraX 分析流：1920×1440，通常约 59–60 FPS
- 发送数据：双码常用 `2068 B`，四码默认 `2068 B`

### 双码横排 2068 B / 50 FPS

同一版本、同一设备会随启动/刷新相位出现明显分化：

- 最好一次：`1.92 QR/frame`，完整双码帧 `870/908`，约 `199 KB/s`
- 差相位一次：`0.39 QR/frame`，完整双码帧 `187/1633`，约 `40–59 KB/s`
- 常见半速：约 `0.9–1.2 QR/frame`，约 `119 KB/s`
- 理论净载荷约 `202 KB/s`，最好结果已经接近理论值，但不能稳定复现

这说明瓶颈不是相机提交 FPS，也不是平均解码耗时。相机通常保持约 60 FPS、丢帧为 0，
解码平均多在 6–10 ms；真正变化的是每帧能得到几个完整 QR。

### 已移除的双码布局

- 纵列：一次约 `1.15 QR/frame`、约 `118 KB/s`，没有稳定满速
- 对角线：多数只能得到一个码、约 `119 KB/s`，也出现完全无速度

所以不能把“布局像四码”理解成“会获得四码的稳定性”。QR 的屏幕刷新扫描方向、相机滚动快门方向、
取景旋转和刷新相位共同决定一帧中哪些码是完整的。

### 四码 1465 B

- 30 FPS：约 `168.9 KB/s`，当前稳定推荐值
- 50 FPS：一次 `2.32 QR/frame`、约 `168 KB/s`，吞吐未高于 30 FPS
- 旧版 60 FPS 对角交替设计的符号上限只有约 120 symbols/s；当前实验性四格全刷的理论上限为 240 symbols/s，尚待设备实测

因此“四码满速”必须区分两件事：发送端现在可以在每个刷新周期生成四个新符号，但接收端分析流达到
60 FPS 不代表每帧都能完整识别四码。日志中的 `QR/frame` 与有效唯一符号率比相机 FPS 更能说明实际吞吐。

### 四码高密度基准（3 MB 文件）

- `1732 / 1952 / 2068 B` 均以 `30 FPS` 实测，每档重复 5 次。
- `1952 B` 与 `2068 B` 多数运行接近理论满速；已观察峰值约 `224 KB/s` 与 `239 KB/s`。
- 当前以这组 3 MB 多轮结果作为基准，并将 `2068 B / 30 FPS` 设为默认。
- 接近完成时恢复块增长会因 LT 喷泉码尾部效应放缓；不能只用进度条最后一段判断光学吞吐。
- APK 的“高速会话总耗时”从首个有效 AFL2 帧开始，完成时冻结；首帧前显示“等待首个 AFL2 帧”。

## 4. Android 接收端现状

- CameraX 分析分辨率：1920×1440
- 分析目标：60 FPS
- 4 个格位工作线程
- zxing-cpp 参数：
  - `tryHarder=false`
  - `tryInvert=false`
  - `tryRotate=false`
  - `tryDownscale=false`
  - 主二值化：`LOCAL_AVERAGE`
  - 备用二值化：`GLOBAL_HISTOGRAM`
- 双码路径包含稳定格位、双格缓存、miss 后失效和备用二值化诊断

详细诊断必须保留以下指标：

- 相机采集/提交/完成 FPS、丢帧
- 平均解码耗时、空结果、异常
- 多码命中数与去重后 `QR/frame`
- 双码完整帧、缺半帧、格位 A/B、轴向和几何锁定
- ROI 格数、缓存状态、备用二值化次数
- 协议总数、唯一/重复/无效、解块进度
- 实时/平均/会话速度

不能仅依据“轴向 horizontal/vertical”判断屏幕物理布局。相机缓冲区旋转、坐标变换或两个检测框的中心差
都可能使诊断轴向与肉眼看到的屏幕左右关系不同。

## 5. 双码问题的当前判断

核心问题是 60 Hz 显示刷新与相机约 60 FPS 采样之间的相位锁定，加上 CMOS rolling shutter：

1. 显示器不是瞬时整屏更新；不同扫描位置在相机曝光期间可能属于相邻发送帧。
2. 两个 QR 即使在同一屏幕刷新中生成，也可能只有一个在该相机帧中完整。
3. 相机和显示器频率接近时，相位可能长时间停留在“两个都完整”“只有一个完整”或“都被刷新边界切开”的状态。
4. 清空、继续接收、强杀 APK 会改变启动时刻与相位，因此结果可在零速、半速、满速之间跳变。
5. 解码器线程、缓存和 ROI 会放大或缓解结果，但现有日志不支持“CPU 解码不够快”是主要根因。

50 FPS 的 5/6 节拍可让相位缓慢漂移，理论上比严格 60/60 锁相更有机会离开坏相位；但它不能保证每个相机帧
都包含两个完整 QR，所以目前仍是实验性优化，不是稳定满速保证。

## 6. 上游 UR-SillyB/AirFerry 四码 60 FPS 分析

分析基于上游 `main` 提交
`8a72ab86dee9d8f19b74bfec56c270101c5980ba`（2026-08-18）。

### 6.1 上游实际上如何发送四码

- 参数页公开提供单码和四码，没有正式双码选项。
- FPS 选项包括 15/20/30/45/60/90/120，以及跟随显示器。
- 默认速度预设为 `1400 B / 60 FPS`，默认四码。
- 每个 `requestAnimationFrame` 调用一次 WASM `next_qr_scratch(4)`。
- 同一刷新帧生成并绘制四个不同的 RaptorQ 符号，2×2 四格同时更新。
- 因而 60 FPS 时理论源符号率为 `4 × 60 = 240 symbols/s`。

Lite 现已增加同类的实验性四格全更新 60 FPS 路径；协议、QR 生成器、静区和接收流水线仍与上游不同，
因此上游结果不能直接作为本项目的验收值。

上游性能文档曾记录 Android 720p 约 `210–240 symbols/s`。这是上游在其版本、设备和测试条件下的历史报告，
不能直接当作本项目 Xiaomi 设备的保证值。

### 6.2 上游 QR 更紧凑

- 上游默认数据符号 1400 B，加 60 B 头和 4 B CRC，线长约 1464 B，与 Lite 四码 1465 B 密度接近。
- 使用 Rust/WASM `fast_qr`，固定 mask 0，跳过遍历 8 个 mask 的成本。
- 自动选择可容纳数据的最小 QR version。
- 单码 margin 为 2 modules，相邻两码之间合计约 4 modules。
- Lite 当前每码 quiet zone 为 4 modules，相邻间隙相当于 8 modules；同样画布下，上游有效 QR 模块可更大。
- 上游自动优化可提高亮度至至少 1.15，并应用约 1.1 对比度；还可选 ±1 px dither。

margin 2 是值得单独验证的高优先级因素，但不能直接改成生产默认。屏幕摩尔纹、对焦和取景裁切可能使更小
quiet zone 在某些手机上反而降低成功率。

### 6.3 上游 Android 解码流水线

- 请求 1920×1080，优先固定 `[60,60]`，后备 `[30,60]`。
- analyzer 只复制 Y 平面到池化缓冲区，立即关闭 CameraX image。
- 使用有界跨帧队列，工作线程数为 `CPU 核数 - 3`，限制在 2–6。
- 队列满时丢弃新帧，避免延迟无限堆积。
- 初次用全图 `ReadBarcodes` 锁定多个 bounding boxes。
- 热路径按固定框做原生零拷贝 crop，每框扩大约 35%，使用单码 `ReadBarcode`。
- 某一框短时 miss 会保留旧框；全部区域连续 miss 时每 3 帧回退全图扫描。
- 原生 zxing-cpp 3.0.2，开启 `tryHarder` 和 `tryInvert`。
- 每个 worker 将一帧的 4 个符号批量提交，减少协议层锁竞争。

上游架构的关键不是简单“开更多线程”，而是先锁定四个稳定格位，再把后续工作变成四个较小、可并行的单码 ROI。
Lite 已有格位线程和缓存思路，但固定 N 格位、miss 保框和全失后低频重锁还可以进一步向上游对齐。

### 6.4 协议差异

- 上游使用 RaptorQ，默认冗余 5%。
- Lite 当前 LT 冗余约 15%。
- 更低冗余和批量 ingest 能改善完成时间与 CPU/锁开销。
- RaptorQ 不会提高模糊或被刷新边界切开的 QR 的光学可解码率，也不能解决 rolling shutter。

### 6.5 建议的独立实验顺序

高优先级（每项必须独立构建、独立对照）：

1. 实测“实验性四格每帧全更新”模式：`1465 B / 60 FPS`，以生产四码 `2068 B / 30 FPS` 作为对照。
2. 四码 quiet zone 从 4 降至 2 的独立实验，记录 QR 实际像素边长、`QR/frame` 和唯一符号率。
3. 增加可关闭的亮度 1.15 / 对比度 1.1 发送端优化。
4. 对照 `1400 B` 与 `1465 B`，判断略低 QR version/模块密度是否更稳定。

中优先级：

1. `tryHarder/tryInvert` 只用于 miss recovery，避免每帧全图开启造成耗时抖动。
2. 独立比较 1920×1080 与 1920×1440，确认分辨率、画幅裁切和滚动快门表现。
3. 双码/四码改为固定 N 个格位；单格短时 miss 保持旧框，仅全格连续 miss 时低频全图重锁。
4. 实验分支升级 zxing-cpp 或采用上游 C++ 原生 crop 路径，必须重新测耗时与稳定性。

低优先级/长期：

1. 若性能分析显示存在跨帧空闲，再尝试 2–6 个工作线程的有界跨帧池；当前 Xiaomi 解码多在 6–10 ms 且无丢帧，
   它不是双码半速的首要修复。
2. 若协议提交锁竞争可测量，再加入一帧 4 符号批量 ingest。
3. RaptorQ 可作为协议升级评估，但不能作为光学识别问题的修复。

实验纪律：

- 不要同时修改发送 FPS、数据大小、quiet zone、亮度、解码参数和 ROI 策略。
- 每个版本至少记录首次接收、继续接收、清空重收、强杀后接收。
- 以唯一符号率、完整多码帧比例、空结果率和会话完成时间为主要指标，不只看瞬时 KB/s。
- 不要把上游历史 `210–240 symbols/s` 当作本设备验收阈值。
- 不要在全图热路径盲目开启 `tryHarder/tryInvert`。
- 跨帧队列必须有界；禁止用无限队列换取表面“不丢帧”。

## 7. 已知失败模式与不要重复的方向

- 不要恢复双码纵列或对角线生产选项；实测没有稳定收益。
- 不要仅凭相机 60 FPS 宣称二维码达到 60 FPS 有效吞吐。
- 不要把“识别出两个框”与“每帧获得两个新的、有效的协议符号”混为一谈。
- 不要为了双码问题重写整个单码/四码稳定路径。
- 不要用 RaptorQ、更多冗余或协议缓存掩盖光学解码率问题。
- 不要删除详细诊断；不同启动相位的差异必须依靠完整日志比较。

## 8. 构建、验证与发布

- Android APK 必须通过 GitHub Actions 的 `Build Android receiver` workflow 构建，不使用本地 APK 作为交付产物。
- 每次 Android 改动完成后，等待 Actions 构建成功，并向用户提供该次 run 的 APK artifact 下载链接。
- APK 下载链接必须使用 GitHub 网页形式：`https://github.com/<owner>/<repo>/actions/runs/<run-id>/artifacts/<artifact-id>`；不要提供 `api.github.com` 的 artifact API 地址。

### GitHub 网络与 Git 代理

本机使用 v2rayN/Xray，当前 mixed 代理监听 `127.0.0.1:10808`。GitHub 443 直连可能超时；在该 VPN
运行时为 Git 持久配置 GitHub 代理：

```powershell
git config --global http.proxy http://127.0.0.1:10808
git config --global https.proxy http://127.0.0.1:10808
git ls-remote https://github.com/shuipashui/beamferry.git HEAD
```

验证命令成功输出远端 HEAD SHA 后，再执行 `git push` 或查询 Actions。代理配置写入用户级
`C:\Users\<用户名>\.gitconfig`，只影响 Git，不改变系统代理。若 v2rayN 更换端口，应同步更新两项配置；
若需要取消 Git 代理，执行：

```powershell
git config --global --unset http.proxy
git config --global --unset https.proxy
```

修改后至少执行：

```powershell
npm test
git diff --check
```

Android 代码变化时还应执行可用的本地 Gradle 测试/构建，并通过 GitHub Actions 生成 APK。发布检查：

1. 确认工作分支与目标提交。
2. 检查版本名、versionCode、接收端 build/cache 是否按需要更新。
3. 推送后确认 GitHub Actions 和 Pages 状态。
4. 下载 artifact 后校验文件确实存在，再提供链接。
5. 将新版本、Action、artifact 和测试结果回填本文件。

## 9. 上游参考

- [AirFerry 参数和默认值](https://github.com/UR-SillyB/AirFerry/blob/main/apps/sender/src/pages/ParamsPage.tsx)
- [AirFerry 类型与配置](https://github.com/UR-SillyB/AirFerry/blob/main/apps/sender/src/types.ts)
- [四码发送渲染](https://github.com/UR-SillyB/AirFerry/blob/main/apps/sender/src/components/QrStream.tsx)
- [Android 解码池](https://github.com/UR-SillyB/AirFerry/blob/main/apps/scanner/app/src/main/java/com/airferry/app/scan/QrDecodePool.kt)
- [Android 相机与格位跟踪](https://github.com/UR-SillyB/AirFerry/blob/main/apps/scanner/app/src/main/java/com/airferry/app/ui/ScanActivity.kt)
- [原生 zxing-cpp 解码](https://github.com/UR-SillyB/AirFerry/blob/main/apps/scanner/app/src/main/cpp/scan_jni.cpp)
- [QR 生成实现](https://github.com/UR-SillyB/AirFerry/blob/main/core/qr-protocol/src/qr_render.rs)
- [上游性能记录](https://github.com/UR-SillyB/AirFerry/blob/main/docs/perf-web-receiver.md)

引用上游代码时遵守其 MIT 许可证，并保留必要版权和许可证文本。设计思路可以借鉴，但不要未经验证就将上游参数
作为 Lite 的默认值。
