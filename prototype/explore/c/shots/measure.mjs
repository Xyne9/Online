// 临时量测脚本：用 shoot.mjs 同一套 chrome 通道取元素盒模型
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
await new Promise((r) => setTimeout(r, 1200));
const expr = `(() => {
  const sel = [".col-center",".editor",".code",".judge",".judge-head",".points",".fail",".judge-foot",".col-problem",".statement",".sample",".problem-foot",".col-agent",".panel-head",".models",".model-note",".flow",".flow-body",".step",".agent-card",".reply",".composer"];
  const out = sel.map(s => {
    const e = document.querySelector(s); if (!e) return { s, missing: true };
    const b = e.getBoundingClientRect();
    return { s, x: +b.x.toFixed(1), y: +b.y.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1), sw: e.scrollWidth, cw: e.clientWidth };
  });
  out.push({ s: 'LASTPROB', lastInk: (()=>{const r=document.querySelector('.col-problem').getBoundingClientRect(); return 0})() });
out.push({ s: 'DOC', sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth, sh: document.documentElement.scrollHeight, ch: document.documentElement.clientHeight });
  return out;
})()`;
const r = await cdp("Runtime.evaluate", { expression: expr, returnByValue: true });
console.log(JSON.stringify(r.result.value, null, 1));
ws.close();
browser.dispose();
