/**
 * Day2 视觉零回归探针
 *
 * 用途：把 16 个原型页在 ?mock=1 下逐页截图，与 _day2_baseline/ 的基线逐字节比对，
 * 同时收集 console error / pageerror / 请求失败，确认 api+adapter+boot 接入后
 * 「DOM 结构、class 集合、视觉呈现」零变化（PRD AC-4.1）。
 *
 * 用法：node scripts/_probe_shots.mjs [--out _shots/_day2] [--port 8811] [--baseline <dir>] [--app <dir>]
 *
 * ⚠️ 基线说明
 *   仓库里的 `_shots/final/` 是在另一台机器的字体栈下渲染的，本机无法逐字节复现。
 *   因此权威基线是 `_day2_baseline/`——由 **Day1 提交（822b5f1，即 Stage 0 改造前）**
 *   的 `git archive` 检出，在本机用本脚本渲染产生。
 *
 *   重建基线（改动了渲染口径后必须做，否则比对无意义）：
 *     $clean = "$env:TEMP\oj_clean"; New-Item -ItemType Directory -Force $clean | Out-Null
 *     git archive 822b5f1 prototype/app | tar -x -C $clean
 *     node scripts/_probe_shots.mjs --app "$clean\prototype\app" --baseline "$clean\prototype\app" `
 *          --out "$env:TEMP\oj_base" --port 8821
 *     # 再跑一次对比 $env:TEMP\oj_base，必须 16/16 相同（证明确定性）
 *     Copy-Item "$env:TEMP\oj_base\*.png" _day2_baseline\
 *
 * ⚠️ 为什么要注入 `animation:none`
 *   工作台有 4 处 `animation: ... infinite`。`reducedMotion:'reduce'` 压不掉 infinite，
 *   `animation-play-state:paused` 也只是「停在当前相位」，相位仍随截图时刻变——
 *   实测同一份代码连跑两次，s-workbench 会偶发 12×12 区域 119 像素差异。
 *   注入 `animation:none` 让元素回落到基础样式，任何时刻渲染都一样。
 *   该注入只存在于探针的页面上下文，**落盘的 HTML/CSS 一个字节都没动**；
 *   基线与被测两侧用同一套设置，口径一致。
 */
import { chromium } from './browser-runtime.mjs';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const args = process.argv.slice(2);
const argOf = (k, d) => {
  const i = args.indexOf(k);
  return i >= 0 && args[i + 1] ? args[i + 1] : d;
};
/** 权威基线：干净 HEAD 在本机的渲染结果 */
const BASELINE = path.resolve(ROOT, argOf('--baseline', '_day2_baseline'));
/** 被测站点根目录：默认当前工作树；--app 可指向干净 HEAD 的检出，用来重建基线 */
const APP = path.resolve(ROOT, argOf('--app', 'prototype/app'));
const OUT = path.resolve(APP, argOf('--out', '_shots/_day2'));
const PORT = Number(argOf('--port', '8811'));

/** 16 个页面（含 index，不含 login） */
const PAGES = [
  'a-audit', 'a-models', 'index', 's-analytics', 's-assignments', 's-exam',
  's-problems', 's-submissions', 's-workbench', 't-analytics', 't-assignments',
  't-dashboard', 't-monitor', 't-plagiarism', 't-problem-edit', 't-problems'
];

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ico': 'image/x-icon'
};

function serve() {
  const server = http.createServer((req, res) => {
    const url = decodeURIComponent((req.url || '/').split('?')[0]);
    const fp = path.join(APP, url === '/' ? 'index.html' : url);
    if (!fp.startsWith(APP)) { res.writeHead(403).end('forbidden'); return; }
    fs.readFile(fp, (err, buf) => {
      if (err) { res.writeHead(404).end('not found'); return; }
      res.writeHead(200, { 'Content-Type': MIME[path.extname(fp).toLowerCase()] || 'application/octet-stream' });
      res.end(buf);
    });
  });
  return new Promise(r => server.listen(PORT, '127.0.0.1', () => r(server)));
}

const sha = buf => crypto.createHash('sha256').update(buf).digest('hex').slice(0, 16);

async function main() {
  fs.mkdirSync(OUT, { recursive: true });
  const server = await serve();
  const browser = await chromium.launch();
  const ctx = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 1,
    reducedMotion: 'reduce'
  });

  const report = [];
  // 浏览器总会自动请求 /favicon.ico；静态服务器没这个文件就打 404，
  // 被 console 监听当成「页面报错」，于是 a-audit 永远红。直接 204 掉。
  await ctx.route('**/favicon.ico', route => route.fulfill({ status: 204, body: '' }));
  for (const name of PAGES) {
    const page = await ctx.newPage();
    const errors = [];
    page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource|favicon/i.test(m.text())) errors.push('console: ' + m.text()); });
    page.on('pageerror', e => errors.push('pageerror: ' + (e && e.message)));
    page.on('requestfailed', r => {
      errors.push('requestfailed: ' + r.url() + ' :: ' + (r.failure()?.errorText || ''));
    });

    const target = name + '.html?mock=1';
    await page.goto(`http://127.0.0.1:${PORT}/${target}`, { waitUntil: 'networkidle', timeout: 30000 });
    // 进场动画 180ms + 数字滚动 480ms，统一等 900ms 后再拍，保证与基线同一稳定态
    await page.waitForTimeout(900);

    /* ── 让无限循环动画不再影响截图 ──────────────────────────────────
       工作台有 4 处 `animation: ... infinite`（.think-dot 的 pulse 2s、
       .msg.typing span 的 blink 1.2s、判分格 cell-idle 1.6s 等）。
       reducedMotion:'reduce' 只能压掉「一次性」过渡，压不掉 infinite；
       而 `animation-play-state:paused` 也只是「停在当前相位」，相位本身
       仍取决于截图时刻——实测同一份代码连跑两次，s-workbench 会偶发
       12×12 区域 119 像素差异。
       唯一确定的做法是 `animation:none`：元素回落到它的基础样式，
       任何时刻渲染都一样。这不是「改页面」，只在探针的页面上下文里生效，
       落盘的 HTML/CSS 一个字节都没动；基线也用同一套设置重新生成，
       两侧口径一致，比对仍然有效。 */
    await page.addStyleTag({
      content: `*,*::before,*::after{
        animation:none !important;
        transition:none !important;
      }`
    });
    await page.waitForTimeout(120);

    const file = path.join(OUT, name + '.png');
    await page.screenshot({ path: file, fullPage: false });
    const nowBuf = fs.readFileSync(file);
    const baseFile = path.join(BASELINE, name + '.png');
    const baseBuf = fs.existsSync(baseFile) ? fs.readFileSync(baseFile) : null;

    // DOM 结构指纹：元素数 + class 集合，用于区分「像素近似但结构变了」
    const dom = await page.evaluate(() => {
      const classes = new Set();
      let count = 0;
      document.querySelectorAll('*').forEach(el => {
        count++;
        el.classList.forEach(c => classes.add(c));
      });
      const b = document.body;
      return {
        elements: count,
        classCount: classes.size,
        classHash: Array.from(classes).sort().join('|'),
        dataRole: b.getAttribute('data-role'),
        dataPage: b.getAttribute('data-page'),
        dataCrumb: b.getAttribute('data-crumb'),
        title: document.title,
        navItem: document.querySelectorAll('.side .nav-item').length,
        sideLinks: document.querySelectorAll('.side a').length
      };
    });

    report.push({
      name,
      target,
      bytes: nowBuf.length,
      sha: sha(nowBuf),
      baseBytes: baseBuf ? baseBuf.length : null,
      baseSha: baseBuf ? sha(baseBuf) : null,
      identical: baseBuf ? sha(nowBuf) === sha(baseBuf) : null,
      elements: dom.elements,
      classCount: dom.classCount,
      classHash: crypto.createHash('sha256').update(dom.classHash).digest('hex').slice(0, 16),
      dataRole: dom.dataRole,
      dataPage: dom.dataPage,
      dataCrumb: dom.dataCrumb,
      title: dom.title,
      navItem: dom.navItem,
      sideLinks: dom.sideLinks,
      errors
    });
    await page.close();
    process.stdout.write(`. ${name} (${nowBuf.length}B, err=${errors.length})\n`);
  }

  await browser.close();
  server.close();

  fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify(report, null, 2), 'utf8');
  const bad = report.filter(r => r.errors.length);
  const diff = report.filter(r => r.identical === false);
  console.log('\n===== 汇总 =====');
  console.log(`页面 ${report.length} 个，截图逐字节相同 ${report.filter(r => r.identical).length} 个，不同 ${diff.length} 个`);
  console.log(`有 console/pageerror/请求失败的页面：${bad.length} 个`);
  for (const r of diff) console.log(`  [像素不同] ${r.name}: now=${r.bytes}B/${r.sha} base=${r.baseBytes}B/${r.baseSha}`);
  for (const r of bad) { console.log(`  [报错] ${r.name}:`); r.errors.forEach(e => console.log('     ' + e)); }
}

main().catch(e => { console.error(e); process.exit(1); });
