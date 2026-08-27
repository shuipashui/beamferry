# BeamFerry

[简体中文](README.md) | English

[![Web CI](https://github.com/shuipashui/beamferry/actions/workflows/web-ci.yml/badge.svg)](https://github.com/shuipashui/beamferry/actions/workflows/web-ci.yml)
[![Desktop Pages](https://github.com/shuipashui/beamferry/actions/workflows/desktop-pages.yml/badge.svg)](https://github.com/shuipashui/beamferry/actions/workflows/desktop-pages.yml)
[![Android build](https://github.com/shuipashui/beamferry/actions/workflows/android-apk.yml/badge.svg)](https://github.com/shuipashui/beamferry/actions/workflows/android-apk.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

BeamFerry transfers a file from a computer screen to a phone camera as a live QR-code stream. The file is encoded, displayed, scanned, reconstructed, and verified locally. No upload server or account is required.

> BeamFerry is designed for short-range, offline optical transfer. Throughput depends on the display, camera, lighting, focus, and device decoder performance; advertised frame rates are not guaranteed transfer rates.

## Try It

- [Open the web camera receiver](https://shuipashui.github.io/beamferry/)
- [Open the sender](https://shuipashui.github.io/beamferry/sender/dist/beamferry-sender.html)
- [Open the desktop screen receiver](https://shuipashui.github.io/beamferry/desktop-receiver/)
- Android debug APKs are produced by the [Build Android receiver](https://github.com/shuipashui/beamferry/actions/workflows/android-apk.yml) workflow as the `beamferry-android-debug` artifact.

The web receiver requires HTTPS or `localhost` for camera access. Android 10 or later is required for the native receiver.

Current release baseline: Android `0.8.135-quad-stall-classifier` (`versionCode 148`) and web receiver `v93`.

## Features

- Fully local file encoding and reconstruction
- Single-code, dual-code, and 2x2 four-code sender layouts
- Binary AFL2 frames with systematic LT fountain coding
- Reception from any point in the repeating stream
- Optional gzip compression when it reduces the transfer size
- SHA-256 verification before exposing the recovered file
- Browser receiver with Web Worker and ZXing WASM decoding
- Desktop browser receiver using screen capture only, with single-code and four-code AFL2 decoding
- Native Android receiver using CameraX Y-plane analysis and zxing-cpp
- Legacy AFL1 receive compatibility
- Installable, self-contained HTML sender

Layout support differs by receiver:

| Layout | Sender | Web receiver | Android receiver | Status |
| --- | --- | --- | --- | --- |
| Single-code | Yes | Yes | Yes | Stable |
| Dual-code | Yes | No | Yes | Experimental; horizontal two-code layout only |
| Four-code | Yes | Yes | Yes | 30 FPS stable; 50/60 FPS experimental |

The web receiver intentionally rejects dual-code AFL2 streams and asks the user to switch layouts. Dual-code remains available for Android testing and protocol compatibility.

## Quick Start

1. Open the sender on the computer and choose a file.
2. Start with the default `four-code / 2068 B / 30 FPS` profile.
3. Open either receiver on the phone and grant camera access.
4. Keep all displayed codes inside the camera preview. Full-screen playback usually gives the best module size and focus.
5. Wait for verification, then save the recovered file.

For a 60 Hz display, use these starting points:

| Layout | Recommended starting profile | Notes |
| --- | --- | --- |
| Four-code | `2068 B / 30 FPS` | Default and most stable high-throughput profile |
| Single-code | `2953 B / 30 FPS` | Easier framing; reduce density if focus or moire is poor |
| Dual-code | `2068 B / 50 FPS` | Android only; experimental and sensitive to camera/display phase |
| Four-code high FPS | `1465 B / 50 or 60 FPS` | Experimental; compare completed-session speed against 30 FPS |

If decoding is intermittent, move farther from the display so the complete quiet zones remain visible, increase display brightness, avoid reflections, and reduce the bytes per code before increasing frame rate.

## How It Works

```text
file
  -> optional gzip container
  -> systematic LT fountain symbols
  -> binary AFL2 QR frames
  -> display / camera optical channel
  -> QR decoder
  -> LT reconstruction
  -> SHA-256 verification
  -> recovered file
```

AFL2 uses a compact 20-byte binary header followed by a fountain-code block. Systematic symbols are sent first, followed by repair symbols, so the receiver can recover from missed, duplicated, and out-of-order frames without requesting retransmission. See [protocol/SPEC.md](protocol/SPEC.md) for the wire format and compatibility rules.

## Receiver Notes

### Web

The browser receiver uses the latest available video frames and bounded worker concurrency; it does not build an unbounded decode queue. Single-code mode tracks one tight ROI. Four-code mode calibrates four physical slots independently and freezes only slots confirmed by real QR hits. `v93` keeps the stable desktop receiver pipeline as its baseline: workers warm in parallel, at most three camera-frame jobs remain in flight, and a worker is reserved before bitmap capture. Fully calibrated quad geometry is cached separately. Recovery no longer replaces a normal four-slot job. Every 18 incomplete frames it enlarges at most the two weakest crops while retaining all four slots, and persistent loss of two slots triggers fresh calibration. Diagnostics now expose remaining blocks, unique-frame yield, time since the last new sequence and solved block, and relock count. Capable Android devices use three workers and a 720-pixel atlas; lower-tier Android devices retain two workers and a 680-pixel atlas.

The browser build limits a transfer to 64 MiB. Refreshing or closing the page discards an active AFL2 reconstruction.

### Desktop screen receiver

`desktop-receiver/` captures a shared screen, window, or browser tab instead of requesting camera access. Its four-code path uses four parallel WASM workers, bounded pipelined bitmap capture, fixed-slot ROI decoding, and a sparse GF(2) elimination fallback for stalled LT tails. The hosted receiver requires HTTPS; local development can use `node desktop-receiver/serve.mjs` from the repository root.

The verified Windows/Edge profile is `2068 B / four-code / 60 FPS` at a captured `2560x1600`. A completed 5.8 MiB transfer reached approximately `435 KB/s` near the tail and `425 KB/s` for the full session, with about 66 analyzed screen frames and 266 valid codes per second. These figures are a measured reference, not a guaranteed rate.

### Android

The Android receiver analyzes the CameraX luminance plane directly and dispatches four-code crops to zxing-cpp decoders. The stable production profile remains four-code at 30 FPS. The 50/60 FPS full-refresh path is experimental: it confirms all four physical slots from real hits, retains calibrated geometry across camera rephasing, and uses wider calibrated crops. After the first systematic pass, it sparsely interleaves a second systematic pass with fresh repair symbols at a 1:63 ratio. This path does not alter four-code 30 FPS behavior.

Dual-code uses two QR codes in the same horizontal row and is supported only by the Android receiver. Its 50/60 FPS path keeps a stable two-slot geometry and can rephase the camera after sustained partial frames. Results vary substantially with rolling shutter, so dual-code is not the default or a stable throughput claim.

Use the in-app diagnostics when reporting performance. Include the payload size, sender FPS, camera resolution and FPS, QR/frame rate, four-slot hit counts, calibration count, unique/optical bytes, and completed-session speed.

## Limitations

- Maximum file size is 64 MiB in the current browser build.
- This is a one-way optical channel; there is no receiver feedback to adapt sender timing.
- High-density and high-FPS profiles can be slower when rolling shutter or display transitions reduce valid QR frames.
- Compression depends on browser `CompressionStream` / `DecompressionStream` support and is skipped for formats that are already compressed.
- Debug APK artifacts are unsigned development builds, not store releases.

## Development

Node.js 22 is used in CI. Install dependencies and run the complete web/protocol test suite:

```bash
npm ci
npm test
npm run build
```

Useful build targets:

| Command | Output |
| --- | --- |
| `npm run build:sender` | Rebuilds `sender/dist/beamferry-sender.html` |
| `npm run build:receiver` | Rebuilds protocol/decoder assets and synchronizes `web-receiver/` |
| `npm run build` | Builds both receiver and sender artifacts |

The Android project requires Java 17 and Android SDK Platform 35:

```powershell
cd android-receiver
.\build-local.ps1 assembleDebug
```

Local APK output is `android-receiver/app/build/outputs/apk/debug/app-debug.apk`. Distributable test artifacts should come from GitHub Actions so the build is reproducible.

## Repository Layout

```text
app.js, index.html, sw.js       GitHub Pages web receiver
web-receiver/                   synchronized receiver mirror
desktop-receiver/               isolated screen-capture desktop receiver
sender/                         sender source and single-file build
android-receiver/               native Android receiver
shared/                         AFL2 protocol and fountain-code implementation
protocol/SPEC.md                AFL1/AFL2 wire-format documentation
tests/                          protocol, FEC, receiver, and safety tests
```

## Security and Privacy

BeamFerry does not intentionally transmit file contents over the network. The sender reads the selected local file, and the receiver writes the reconstructed result only after integrity verification. When using the hosted pages, normal static site requests still reach GitHub Pages; deploy the repository yourself for an entirely self-controlled origin.

Treat QR streams as visible data. Anyone with a camera and line of sight may capture the transfer. BeamFerry provides integrity checking, not encryption or authentication; encrypt sensitive files before sending them.

## Acknowledgements

The AFL2 implementation is derived from ideas and MIT-licensed components in [decimen-optical-transfer](https://github.com/bashalarmistalt/decimen-optical-transfer) v0.3.0. The web receiver uses ZXing WASM, and the Android receiver uses zxing-cpp. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for licenses and attribution.

BeamFerry is an independent project and is not affiliated with AirFerry.

## License

[MIT](LICENSE) (c) the BeamFerry contributors.
