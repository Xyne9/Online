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
  const parse = (c) => { const m = c.match(/rgba?\\(([^)]+)\\)/); if (!m) return null; const p = m[1].split(',').map(Number); return { r: p[0], g: p[1], b: p[2], a: p[3] === undefined ? 1 : p[3] }; };
  const lum = ({ r, g, b }) => { const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }; return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b); };
  const over = (fg, bg) => ({ r: fg.r * fg.a + bg.r * (1 - fg.a), g: fg.g * fg.a + bg.g * (1 - fg.a), b: fg.b * fg.a + bg.b * (1 - fg.a), a: 1 });
  const bgOf = (e) => {
    let cur = e, stack = [];
    while (cur) { const c = parse(getComputedStyle(cur).backgroundColor); if (c && c.a > 0.001) stack.push(c); cur = cur.parentElement; }
    let base = { r: 255, g: 255, b: 255, a: 1 };
    for (let i = stack.length - 1; i >= 0; i--) base = over(stack[i], base);
    return base;
  };
  const out = [];
  const seen = new Set();
  for (const e of document.querySelectorAll('body *')) {
    const cs = getComputedStyle(e);
    if (cs.display === 'none' || cs.visibility === 'hidden' || +cs.opacity === 0) continue;
    // 只测直接含文本的元素
    const own = [...e.childNodes].some(n => n.nodeType === 3 && n.textContent.trim().length > 0);
    if (!own) continue;
    const fg = parse(cs.color); if (!fg) continue;
    const bg = bgOf(e);
    const c = over(fg, bg);
    const L1 = lum(c), L2 = lum(bg);
    const ratio = (Math.max(L1, L2) + 0.05) / (Math.min(L1, L2) + 0.05);
    const px = parseFloat(cs.fontSize), bold = +cs.fontWeight >= 600;
    const large = px >= 24 || (px >= 18.66 && bold);
    const need = large ? 3 : 4.5;
    if (ratio < need) {
      const tag = e.tagName.toLowerCase() + (typeof e.className === 'string' && e.className ? '.' + e.className.trim().split(/\\s+/).join('.') : '');
      const key = tag + '|' + cs.color + '|' + Math.round(ratio * 100);
      if (!seen.has(key)) { seen.add(key); out.push({ tag, color: cs.color, px, ratio: +ratio.toFixed(2), need, text: e.textContent.trim().slice(0, 24) }); }
    }
  }
  return out;
})()`;
const r = await cdp("Runtime.evaluate", { expression: expr, returnByValue: true });
console.log(JSON.stringify(r.result.value, null, 1));
ws.close();
browser.dispose();
