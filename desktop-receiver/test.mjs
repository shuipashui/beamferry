import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const [app, html, serviceWorker, storage, desktopStyles] = await Promise.all([
  readFile(new URL("./app.js", import.meta.url), "utf8"),
  readFile(new URL("./index.html", import.meta.url), "utf8"),
  readFile(new URL("./sw.js", import.meta.url), "utf8"),
  readFile(new URL("./receiver-storage.js", import.meta.url), "utf8"),
  readFile(new URL("./desktop-screen.css", import.meta.url), "utf8")
]);

assert.match(app, /getDisplayMedia\s*\(/, "receiver must capture a screen source");
assert.doesNotMatch(app, /getUserMedia\s*\(/, "receiver must not request a camera source");
assert.match(app, /stopBtn\.onclick\s*=\s*\(\)\s*=>\s*stop\(\)/, "stop clicks must not render the PointerEvent as status text");
assert.match(app, /pauseHighSpeedJobs\(\);\s*stopHighSpeedWorkers\(\);/, "a closed screen stream must discard old decoder workers");
assert.ok(app.indexOf("startHighSpeedWorkers();") < app.indexOf("scheduleScan();", app.indexOf("async function start()")), "decoder workers must restart before the new capture loop");
assert.match(app, /改选发送端窗口或整个屏幕/, "tab-capture stalls need actionable guidance");
assert.match(app, /highScanMisses\s*>=\s*12[\s\S]*decodeBlindScreenQuadrants\(\)/, "dense desktop QR layouts need a high-resolution fallback scan");
assert.match(app, /function desktopAcquisitionCrops\(\)/, "fallback scan must define overlapping desktop capture windows");
assert.match(app, /frame\.width \* 0\.52/, "desktop capture windows must preserve dense QR module resolution");
assert.match(app, /blindScreenScan\s*\?\s*" · 四区盲扫"/, "diagnostics must expose the high-resolution fallback");
assert.match(app, /const HIGH_QUAD_INFLIGHT = 3;/, "quad decoding must use a bounded three-frame pipeline");
assert.match(app, /const HIGH_QUAD_GRAB_MS = 12;/, "quad capture must not retain the old 30 FPS throttle");
assert.match(app, /highQuadJobsInFlight\s*>=\s*HIGH_QUAD_INFLIGHT/, "quad backpressure must use concurrent frame jobs");
assert.match(app, /highQuadJobsInFlight \+= 1;[\s\S]*highQuadJobsInFlight = Math\.max\(0, highQuadJobsInFlight - 1\)/, "quad frame jobs must release their pipeline slot");
assert.match(app, /const slot = slots\[0\];\s*highWorkerBusy\[slot\] = true;/, "quad frame capture must reserve a worker before awaiting a bitmap");
assert.match(app, /" · 帧任务 " \+ highQuadJobsInFlight/, "diagnostics must expose quad pipeline occupancy");
assert.match(app, /for \(let index = 0; index < HIGH_SPEED_WORKERS; index \+= 1\) startHighSpeedWorker\(index\)/, "desktop decoder workers must boot in parallel");
assert.match(app, /const rollingRates = \[0, 0, 0, 0, 0\]/, "speed display must smooth multiple recent samples");
assert.match(app, /speedBps = speedBps \? speedBps \* 0\.6 \+ sample \* 0\.4 : sample/, "realtime speed must use an EWMA instead of a raw one-second sample");
assert.match(app, /if \(highWorkers\.length\) \{[\s\S]*setTimeout\([\s\S]*HIGH_QUAD_GRAB_MS\)/, "high-speed scanning must use an independent bounded pump instead of throttled video-frame callbacks");
assert.match(html, /选择屏幕源/, "screen-source action must be visible");
assert.match(html, /id="openSender"[^>]*target="_blank"[^>]*rel="noopener"/, "sender must open without navigating away from the receiver");
assert.match(html, /id="download"[^>]*aria-disabled="true"/, "download must start disabled");
assert.match(app, /reset\(\);\s*startInFlight = true;/, "choosing a new screen source must clear the previous transfer first");
assert.match(app, /if \(!download\.hasAttribute\("href"\)\) event\.preventDefault\(\)/, "disabled downloads must not navigate");
assert.ok(html.indexOf('class="actions"') < html.indexOf('class="camera-card"'), "narrow layouts must show screen-source actions before the preview");
assert.match(html, /\.\.\/sender\/dist\/beamferry-sender\.html/, "sender link must resolve from the isolated directory");
assert.match(serviceWorker, /beamferry-desktop-receiver-/, "cache namespace must be isolated");
assert.match(serviceWorker, /!key\.startsWith\(CACHE_PREFIX\)/, "activation must preserve unrelated caches");
assert.match(storage, /beamferry-desktop-receiver/, "IndexedDB namespace must be isolated");
assert.match(html, /class="card missing-card"/, "desktop layout needs an explicit missing-data region");
assert.match(desktopStyles, /grid-template-areas:/, "wide screens must use the desktop grid");
assert.match(desktopStyles, /body\s*\{[^}]*overflow:\s*hidden/s, "desktop view should fit without page scrolling");
assert.match(desktopStyles, /\.stats strong\s*\{[^}]*white-space:\s*normal/s, "long status values must wrap inside the sidebar");

const server = await readFile(new URL("./serve.mjs", import.meta.url), "utf8");
assert.match(server, /relative\.startsWith\("sender\/"\)/, "local server must expose the sibling sender build");

console.log("desktop screen receiver isolation checks ok");
