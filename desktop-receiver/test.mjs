import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const [app, html, serviceWorker, storage] = await Promise.all([
  readFile(new URL("./app.js", import.meta.url), "utf8"),
  readFile(new URL("./index.html", import.meta.url), "utf8"),
  readFile(new URL("./sw.js", import.meta.url), "utf8"),
  readFile(new URL("./receiver-storage.js", import.meta.url), "utf8")
]);

assert.match(app, /getDisplayMedia\s*\(/, "receiver must capture a screen source");
assert.doesNotMatch(app, /getUserMedia\s*\(/, "receiver must not request a camera source");
assert.match(html, /选择屏幕源/, "screen-source action must be visible");
assert.match(html, /\.\.\/sender\/dist\/beamferry-sender\.html/, "sender link must resolve from the isolated directory");
assert.match(serviceWorker, /beamferry-desktop-receiver-/, "cache namespace must be isolated");
assert.match(serviceWorker, /!key\.startsWith\(CACHE_PREFIX\)/, "activation must preserve unrelated caches");
assert.match(storage, /beamferry-desktop-receiver/, "IndexedDB namespace must be isolated");

console.log("desktop screen receiver isolation checks ok");
