/**
 * Day2 视觉零回归探针（临时工具，验收后删除）
 *
 * 用途：把 16 个原型页在 ?mock=1 下逐页截图，与 _day2_baseline/ 的基线逐字节比对，
 * 同时收集 console error / pageerror / 请求失败，确认 api+adapter+boot 接入后
 * 「DOM 结构、class 集合、视觉呈现」零变化（PRD AC-4.1）。
 *
 * 用法：node scripts/_probe_shots.mjs [--out _shots/_day2] [--port 8811] [--baseline <dir>]
 *
 * ⚠️ 基线说明：仓库里的 `_shots/final/` 是在另一台机器的字体栈下渲染的，
 * 本机无法逐字节复现（已验证：干净的 HEAD 检出渲染 16 页，与 final/ 全部不同，
 * 但同一份代码连续跑两次 16/16 逐字节一致，说明渲染本身确定性）。
 * 因此本脚本的权威基线是 `_day2_baseline/`——它由干净的 `git archive HEAD` 检出
 * 在本机渲染产生，是"改造前"的真实视觉快照。
 */
import { chromium } from 'playwright';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const APP = path.join(ROOT, 'prototype', 'app');

const args = process.argv.slice(2);
const argOf = (k, d) => {
  const i = args.indexOf(k);
  return i >= 0 && args[i + 1] ? args[i + 1] : d;
};
/** 权威基线：干净 HEAD 在本机的渲染结果 */
const BASELINE = path.resolve(ROOT, argOf('--baseline', '_day2_baseline'));
const OUT = path.join(APP, argOf('--out', '_shots/_day2'));
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
  for (const name of PAGES) {
    const page = await ctx.newPage();
    const errors = [];
    page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
    page.on('pageerror', e => errors.push('pageerror: ' + (e && e.message)));
    page.on('requestfailed', r => {
      errors.push('requestfailed: ' + r.url() + ' :: ' + (r.failure()?.errorText || ''));
    });

    const target = name + '.html?mock=1';
    await page.goto(`http://127.0.0.1:${PORT}/${target}`, { waitUntil: 'networkidle', timeout: 30000 });
    // 进场动画 180ms + 数字滚动 480ms，统一等 900ms 后再拍，保证与基线同一稳定态
    await page.waitForTimeout(900);

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
