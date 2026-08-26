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
assert.match(html, /选择屏幕源/, "screen-source action must be visible");
assert.ok(html.indexOf('class="actions"') < html.indexOf('class="camera-card"'), "narrow layouts must show screen-source actions before the preview");
assert.match(html, /\.\.\/sender\/dist\/beamferry-sender\.html/, "sender link must resolve from the isolated directory");
assert.match(serviceWorker, /beamferry-desktop-receiver-/, "cache namespace must be isolated");
assert.match(serviceWorker, /!key\.startsWith\(CACHE_PREFIX\)/, "activation must preserve unrelated caches");
assert.match(storage, /beamferry-desktop-receiver/, "IndexedDB namespace must be isolated");
assert.match(html, /class="card missing-card"/, "desktop layout needs an explicit missing-data region");
assert.match(desktopStyles, /grid-template-areas:/, "wide screens must use the desktop grid");
assert.match(desktopStyles, /body\s*\{[^}]*overflow:\s*hidden/s, "desktop view should fit without page scrolling");

console.log("desktop screen receiver isolation checks ok");
