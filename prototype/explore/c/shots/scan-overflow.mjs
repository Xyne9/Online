import { createChrome } from "file:///C:/Users/ZhuanZ.DESKTOP-PH97BKO/.agents/skills/oil-ui-pro/scripts/chrome.mjs";

const browser = createChrome({ prefix: "oil-measure-", label: "measure" });
const wsUrl = await browser.launch();
const ws = new WebSocket(wsUrl);
let id = 0;
const pending = new Map();
ws.addEventListener("message", (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); }
});
await new Promise((r) => ws.addEventListener("open", r, { once: true }));
const send = (method, params = {}, sessionId) => new Promise((res) => {
  const mid = ++id;
  pending.set(mid, (m) => res(m.result ?? m.error));
  ws.send(JSON.stringify({ id: mid, method, params, ...(sessionId ? { sessionId } : {}) }));
});
const { targetId } = await send("Target.createTarget", { url: "about:blank" });
const { sessionId } = await send("Target.attachToTarget", { targetId, flatten: true });
const cdp = (m, p) => send(m, p, sessionId);
await cdp("Page.enable");
await cdp("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
await cdp("Page.navigate", { url: "file:///D:/毕设/agent oj/prototype/explore/c/index.html" });
await new Promise((r) => setTimeout(r, 1400));

const expr = `(() => {
  const out = { overflow: [], outOfViewport: [], fonts: [], clipped: [] };
  const all = [...document.querySelectorAll('body *')];
  for (const e of all) {
    const cs = getComputedStyle(e);
    if (cs.display === 'none' || cs.visibility === 'hidden') continue;
    const r = e.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) continue;
    const tag = e.tagName.toLowerCase() + (e.className && typeof e.className === 'string' ? '.' + e.className.trim().split(/\\s+/).join('.') : '');
    // 内容溢出自身盒
    if (e.scrollWidth > e.clientWidth + 1 && e.clientWidth > 0) {
      out.overflow.push({ tag, sw: e.scrollWidth, cw: e.clientWidth, text: (e.textContent || '').trim().slice(0, 40) });
    }
    if (e.scrollHeight > e.clientHeight + 1 && e.clientHeight > 0 && cs.overflowY !== 'auto' && cs.overflowY !== 'scroll') {
      out.clipped.push({ tag, sh: e.scrollHeight, ch: e.clientHeight, text: (e.textContent || '').trim().slice(0, 40) });
    }
    // 超出视口
    if (r.right > 1440.5 || r.bottom > 900.5 || r.left < -0.5 || r.top < -0.5) {
      out.outOfViewport.push({ tag, l: +r.left.toFixed(1), t: +r.top.toFixed(1), r: +r.right.toFixed(1), b: +r.bottom.toFixed(1) });
    }
  }
  const fams = ['fontFamily'];
  const pick = ['.role','.agent-mono','.tool-name','.flow-seq','.state'];
  out.fonts = pick.map(s => { const e = document.querySelector(s); return e ? { s, fam: getComputedStyle(e).fontFamily } : { s, missing: true }; });
  out.doc = { sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth, sh: document.documentElement.scrollHeight, ch: document.documentElement.clientHeight };
  return out;
})()`;
const r = await cdp("Runtime.evaluate", { expression: expr, returnByValue: true });
console.log(JSON.stringify(r.result.value, null, 1));
ws.close();
browser.dispose();
