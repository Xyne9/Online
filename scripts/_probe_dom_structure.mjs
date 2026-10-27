/**
 * DOM 结构 / class 集合红线校验（全站业务页）
 *
 * 背景：agent.md §3.1 红线 #1 规定「不改 DOM 结构与 class 名」，唯一允许的
 * HTML 增量是页尾 <script src> 与 x-data 对象内部新增属性。2026-10-27 用户
 * 明确批准的窄范围例外是：**给现有节点增加 x-text / x-show 及相应动态属性**
 * （§3.2 顶部），DOM 结构、class、样式保持不变。
 *
 * 本探针用真实浏览器把「净化基线」与「当前工作树」渲染成 DOM 树，
 * 归一化掉 Alpine 运行时属性后逐节点比对：
 *   - 元素标签序列（结构）
 *   - 每个元素的 class 集合
 *   - 内联 style
 * 只要多出一个元素 / 少一个元素 / class 增减，立即报错。
 *
 * 覆盖页面（按步分组的累积清单，只增不减——每步新接真实数据的页面都要守住）：
 *   第 3 步：s-workbench / s-problems / s-submissions / index
 *   第 4 步：以上四页（AI 抽屉在工作台内）
 *   第 5 步：t-problems / t-problem-edit / t-assignments / t-monitor / t-plagiarism / s-exam
 *   第 6 步：s-analytics / t-dashboard / t-analytics / a-models / a-audit
 *
 * 用法：node scripts/_probe_dom_structure.mjs [--baseline <dir>]
 *   基线目录默认 %TEMP%/oj_clean_day8/prototype/app（干净 HEAD 检出），
 *   可用 --baseline 指向别处；基线缺失时脚本报错退出，**不会静默跳过**。
 */
import { chromium } from './browser-runtime.mjs';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const argOf = (k, d) => { const i = args.indexOf(k); return i >= 0 && args[i + 1] ? args[i + 1] : d; };
const BASE_APP = path.resolve(argOf('--baseline', path.join(process.env.TEMP || '/tmp', 'oj_clean_day8', 'prototype', 'app')));
const CUR_APP = path.resolve(ROOT, 'prototype/app');

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2', '.woff': 'font/woff'
};

function serve(root) {
  return new Promise(resolve => {
    const s = http.createServer((req, res) => {
      const url = decodeURIComponent(req.url.split('?')[0]);
      const file = path.join(root, url === '/' ? '/index.html' : url);
      if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
        res.writeHead(404); res.end('nf'); return;
      }
      res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
      fs.createReadStream(file).pipe(res);
    });
    s.listen(0, '127.0.0.1', () => resolve({ server: s, port: s.address().port }));
  });
}

/* 归一化：去掉 Alpine 注入的运行时属性与状态 class，只留结构与 class/style */
const DUMP = `(() => {
  const skip = new Set(['_x_dataStack','_x_effects','_x_doBindings','_x_ignore','_x_teleport','_x_isInit','x-cloak']);
  const keepClassSkip = new Set(['x-cloak']);
  function walk(el, depth, out) {
    if (el.nodeType !== 1) return;
    const tag = el.tagName.toLowerCase();
    if (tag === 'script') return;              // 允许页尾新增 <script src>
    const cls = [...el.classList].filter(c => !keepClassSkip.has(c)).sort().join(' ');
    const style = (el.getAttribute('style') || '').trim();
    out.push(depth + ':' + tag + '|' + cls + '|' + style);
    for (const c of el.children) walk(c, depth + 1, out);
  }
  const out = [];
  walk(document.documentElement, 0, out);
  return out;
})()`;

async function dump(page, port, name) {
  await page.goto(`http://127.0.0.1:${port}/${name}.html?mock=1`, { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(1200);
  return page.evaluate(DUMP);
}

const results = [];
function check(pass, label, detail = '') {
  results.push({ pass, label, detail: String(detail || '') });
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${label}${detail ? '\n      ' + detail : ''}`);
}

/* 累积清单：每步接入真实数据的页面都要留在这里，防止「这一步改完 DOM 却没人守」 */
const PAGES = [
  // 第 3 步（判题闭环）
  's-workbench', 's-problems', 's-submissions', 'index',
  // 第 5 步（出题 / 作业 / 考试监考 / 查重）
  't-problems', 't-problem-edit', 't-assignments', 't-monitor', 't-plagiarism', 's-exam',
  // 第 6 步（学情看板 / 模型通道 / 调用审计）
  's-analytics', 't-dashboard', 't-analytics', 'a-models', 'a-audit'
];

/* 基线必须真的存在：缺失时立刻失败，绝不静默放过（否则红线形同不存在） */
if (!fs.existsSync(BASE_APP)) {
  check(false, '基线目录存在', `${BASE_APP} 不存在。请先按文件头说明用干净 HEAD 检出基线：\n` +
    `      git archive <clean-head> prototype/app | tar -x -C "${path.dirname(BASE_APP)}"`);
} else {
  const baselineMissing = PAGES.filter(n => !fs.existsSync(path.join(BASE_APP, `${n}.html`)));
  const currentMissing = PAGES.filter(n => !fs.existsSync(path.join(CUR_APP, `${n}.html`)));
  check(baselineMissing.length === 0, '基线里包含本轮要比对的全部页面',
    baselineMissing.length ? `基线缺: ${baselineMissing.join(', ')}` : `${PAGES.length} 页齐全`);
  check(currentMissing.length === 0, '当前工作树里包含本轮要比对的全部页面',
    currentMissing.length ? `当前缺: ${currentMissing.join(', ')}` : `${PAGES.length} 页齐全`);

  const b = await serve(BASE_APP);
  const c = await serve(CUR_APP);
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();

  for (const name of PAGES) {
    if (baselineMissing.includes(name) || currentMissing.includes(name)) continue;
    try {
      const base = await dump(page, b.port, name);
      const cur = await dump(page, c.port, name);
      if (base.length !== cur.length) {
        const setB = new Set(base), setC = new Set(cur);
        const extra = cur.filter(x => !setB.has(x)).slice(0, 6);
        const missing = base.filter(x => !setC.has(x)).slice(0, 6);
        check(false, `${name} DOM 节点数一致`,
          `基线 ${base.length} vs 当前 ${cur.length}\n      多出: ${JSON.stringify(extra)}\n      缺失: ${JSON.stringify(missing)}`);
      } else {
        const diff = [];
        for (let i = 0; i < base.length; i++) if (base[i] !== cur[i]) diff.push(`#${i} base=${base[i]} cur=${cur[i]}`);
        check(diff.length === 0, `${name} DOM 结构 + class + style 完全一致`,
          diff.length ? diff.slice(0, 5).join('\n      ') : `${base.length} 个元素节点`);
      }
    } catch (e) {
      // 单页异常不中断整轮（其余页面继续比对）
      check(false, `${name} DOM 比对可执行`, String(e && e.message ? e.message : e).slice(0, 400));
    }
  }

  await ctx.close();
  await browser.close();
  b.server.close(); c.server.close();
}

const pass = results.filter(r => r.pass).length;
console.log(`\n══════════════════════════════════════════`);
console.log(`  DOM 红线校验：${pass}/${results.length} 通过`);
console.log(`══════════════════════════════════════════`);
const outDir = path.join(ROOT, 'logs', 'dom-structure');
fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'report.json'), JSON.stringify({ pass, total: results.length, results }, null, 2));
process.exit(pass === results.length ? 0 : 1);
