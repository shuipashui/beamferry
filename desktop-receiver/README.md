# BeamFerry desktop screen receiver

这是一个与项目其他接收端隔离的电脑接收端。它只使用浏览器屏幕捕获，不请求摄像头权限，并兼容 BeamFerry 现有 AFL1/AFL2 单码与四码文件流。

## 使用

1. 在本目录运行 `node serve.mjs`。
2. 用 Chrome 或 Edge 打开终端显示的 `http://127.0.0.1:8765/`。
3. 点击“选择屏幕源”，选择正在播放 BeamFerry 二维码的屏幕、窗口或浏览器标签页。
4. 文件恢复完成后点击“下载文件”。

也可以把整个目录部署到 HTTPS 静态站点。屏幕捕获 API 只在 HTTPS 或 localhost 安全上下文中可用，不能直接双击 `index.html` 使用。

## GitHub Pages

仓库内的 `desktop-pages.yml` 会在 `main` 分支相关文件变化时自动构建并部署：

- 站点根路径：原网页摄像头接收端。
- `/sender/dist/beamferry-sender.html`：BeamFerry 发送端。
- `/desktop-receiver/`：电脑屏幕接收端。

首次使用时，在 GitHub 仓库的 **Settings → Pages → Build and deployment** 中将 Source 设为 **GitHub Actions**，随后运行 **Desktop receiver Pages** workflow。

## 隔离边界

- 所有运行文件、解码 worker、WASM 和持久化代码都位于本目录。
- 使用独立的 Service Worker 缓存前缀、IndexedDB 名称和 FPS 设置键，不会清理或覆盖现有网页接收端的数据。
- 不修改根目录、`web-receiver/`、`sender/` 或 `android-receiver/` 的构建流程。

屏幕源选择与捕获生命周期的设计参考了 MIT 许可证的 [AirFerry Windows 接收端](https://github.com/UR-SillyB/AirFerry/tree/main/apps/windows)，解码与文件恢复使用 BeamFerry 自己的协议实现。
