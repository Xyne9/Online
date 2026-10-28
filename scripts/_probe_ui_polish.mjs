/** 2026-10-28 UI 精修：只在隔离浏览器中验证呈现与交互，不调用真实模型或写业务数据。 */
import { chromium } from './browser-runtime.mjs';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';

const app = path.resolve('prototype/app');
const out = path.resolve(process.argv[2] || 'logs/ui-polish');
fs.mkdirSync(out, { recursive: true });
const mime = { '.html':'text/html; charset=utf-8', '.js':'text/javascript; charset=utf-8', '.css':'text/css; charset=utf-8', '.svg':'image/svg+xml' };
const server = http.createServer((req, res) => {
  const file = path.join(app, decodeURIComponent(req.url.split('?')[0]));
  fs.readFile(file, (err, data) => {
    if (err) return res.writeHead(404).end();
    res.writeHead(200, { 'Content-Type':mime[path.extname(file)] || 'application/octet-stream' }).end(data);
  });
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const origin = 'http://127.0.0.1:' + server.address().port;
const browser = await chromium.launch();
const results = [];
const errors = [];
async function check(name, run) {
  try { await run(); results.push({ name, pass:true }); console.log('PASS ' + name); }
  catch(e) { results.push({ name, pass:false, error:e.message }); console.log('FAIL ' + name + ': ' + e.message); }
}
async function context(size = { width:1440, height:900 }) {
  const ctx = await browser.newContext({ viewport:size });
  await ctx.route('**/favicon.ico', r => r.fulfill({ status:204, body:'' }));
  ctx.on('page', p => p.on('pageerror', e => errors.push(e.message)));
  return ctx;
}
async function ready(page, target) {
  await page.goto(origin + '/' + target, { waitUntil:'load' });
  await page.waitForFunction(() => {
    const el = document.querySelector('[x-data]');
    return !el || (el._x_dataStack && !el.hasAttribute('x-cloak'));
  });
}
try {
  const ctx = await context();
  const p = await ctx.newPage();
  await check('登录入口没有业务身份、侧栏或通知', async () => {
    await ready(p, 'login.html');
    assert.equal(await p.locator('.side,.topbar,.user-card').count(), 0);
    assert.ok(await p.getByRole('heading', { name:'登录智判 OJ' }).isVisible());
    assert.equal(await p.locator('.login-header .brand').getAttribute('href'), 'index.html');
  });
  await check('空表单显示错误并可继续输入', async () => {
    await p.getByRole('button', { name:'登录', exact:true }).click();
    await p.getByRole('alert').waitFor({ state:'visible' });
    assert.match(await p.getByRole('alert').innerText(), /请填写/);
    assert.ok(await p.getByLabel('账号', { exact:true }).isEditable());
  });
  await check('登录失败保留输入、位置并恢复按钮', async () => {
    await p.route('**/api/auth/login', r => {
      const headers = { 'Access-Control-Allow-Origin':'*', 'Access-Control-Allow-Methods':'POST,OPTIONS',
        'Access-Control-Allow-Headers':'content-type,authorization,x-trace-id' };
      return r.request().method() === 'OPTIONS'
        ? r.fulfill({ status:204, headers, body:'' })
        : r.fulfill({ status:200, headers, contentType:'application/json', json:{ code:400, message:'账号或密码错误', data:null } });
    });
    await p.getByLabel('账号', { exact:true }).fill('ui-review');
    await p.getByLabel('密码', { exact:true }).fill('wrong-password');
    await p.getByLabel('密码', { exact:true }).press('Enter');
    await p.waitForFunction(() => !Alpine.$data(document.getElementById('page-content')).loading);
    assert.equal(await p.getByLabel('账号', { exact:true }).inputValue(), 'ui-review');
    assert.equal(await p.getByLabel('密码', { exact:true }).inputValue(), 'wrong-password');
    assert.ok(await p.getByRole('alert').isVisible());
    assert.ok(p.url().includes('login.html'));
    assert.ok(await p.getByRole('button', { name:'登录', exact:true }).isEnabled());
  });
  await check('演示登录 Enter 提交、回跳学情并在刷新后保持登录', async () => {
    await ready(p, 'login.html?mock=1&redirect=s-analytics.html');
    await p.getByLabel('账号', { exact:true }).fill('20220101');
    await p.getByLabel('密码', { exact:true }).fill('123456');
    await p.getByLabel('密码', { exact:true }).press('Enter');
    await p.waitForURL('**/s-analytics.html?mock=1');
    await p.reload({ waitUntil:'load' });
    await p.locator('.aihero').waitFor();
    assert.match(await p.evaluate(() => localStorage.getItem('OJ_TOKEN')), /^mock-/);
  });
  await check('热力图图例与通过格一致，趋势总计不再重复', async () => {
    const colors = await p.evaluate(() => ({
      legend:getComputedStyle(document.querySelector('.heat-foot .lg-ac'), '::before').backgroundColor,
      cell:getComputedStyle(document.querySelector('.heat-row .vchain i.ac')).backgroundColor
    }));
    assert.equal(colors.legend, colors.cell);
    assert.equal(await p.locator('.trend-foot .tf-i').count(), 1);
    assert.equal(await p.locator('.aihero-meta > span').count(), 2);
    await p.getByRole('tab', { name:'近 7 天' }).click();
    assert.equal(await p.getByRole('tab', { name:'近 7 天' }).getAttribute('aria-selected'), 'true');
  });
  await check('判分链 8 通过/2 失败，与摘要一致且支持键盘定位', async () => {
    await ready(p, 's-workbench.html?mock=1');
    await p.getByRole('button', { name:'展开测试点', exact:true }).click();
    await p.waitForFunction(() => document.activeElement.matches('.chain .cell'));
    assert.equal(await p.locator('.chain .cell.ac').count(), 8);
    assert.equal(await p.locator('.chain .cell.wa').count(), 2);
    const ninth = p.locator('.chain .cell').nth(8);
    await ninth.waitFor({ state:'visible' });
    await ninth.focus(); await ninth.press('Enter');
    await p.waitForFunction(() => document.querySelectorAll('.chain .cell')[8].getAttribute('aria-pressed') === 'true');
    assert.equal(await ninth.getAttribute('aria-pressed'), 'true');
    assert.match(await ninth.getAttribute('class'), /is-focus/);
    assert.match(await ninth.locator('.callout').innerText(), /未通过/);
    assert.doesNotMatch(await ninth.locator('.callout').innerText(), /-1 -2 -3/);
    await ninth.press('Space');
    await p.waitForFunction(() => document.querySelectorAll('.chain .cell')[8].getAttribute('aria-pressed') === 'false');
    assert.equal(await ninth.getAttribute('aria-pressed'), 'false');
    await p.locator('.band-toggle').click();
    await p.waitForFunction(() => document.activeElement.matches('.brief-toggle'));
  });
  await check('教学回复与执行过程分开，草稿和降级提示保留', async () => {
    await p.locator('.ai-next:visible .chip').first().click();
    await p.waitForFunction(() => {
      const d = Alpine.$data(document.body);
      return !d.replying && d.chat.some(m => m.role === 'ai' && m.answer);
    });
    const reply = p.locator('.panel:visible .msg.ai:not(.typing)').last();
    assert.doesNotMatch(await reply.locator(':scope > p').first().innerText(), /Router[：:]|Tool[：:]|完成[：:]/);
    await p.screenshot({ path:path.join(out,'reply.png') });
    await reply.locator('summary').click();
    assert.match(await reply.locator('details p').innerText(), /Router[：:]|Tool[：:]/);
    await p.screenshot({ path:path.join(out,'reply-process.png') });
    await p.getByLabel('追问', { exact:true }).fill('尚未发送的草稿');
    await p.locator('.stage').nth(0).click();
    await p.locator('.stage').nth(2).click();
    assert.equal(await p.getByLabel('追问', { exact:true }).inputValue(), '尚未发送的草稿');
    await p.evaluate(() => Alpine.$data(document.body).chat.push({ role:'ai', text:'',
      status:['降级：已切换备用通道'], answer:'先检查边界条件。' }));
    await p.waitForFunction(() => [...document.querySelectorAll('.msg-notice')].some(e => e.textContent.includes('已切换备用通道')));
    assert.ok(await p.locator('.panel:visible .msg-notice').last().isVisible());
  });
  await check('受控真实测试点状态保持各自结果色，首个失败可定位', async () => {
    await p.evaluate(() => {
      const d = Alpine.$data(document.body);
      d.live = true;
      d.cases = ['ac','wa','tle','mle','re','ce'].map((status,i) => ({ seqNo:i+1, status,
        statusLabel:status.toUpperCase(), score:10, gotScore:i===0?10:0 }));
    });
    await p.waitForFunction(() => document.querySelectorAll('.chain .cell').length === 6);
    assert.equal(await p.locator('.cell.fail').count(), 1);
    await p.waitForFunction(() => new Set([...document.querySelectorAll('.chain .cell')].map(e => getComputedStyle(e).color)).size >= 5);
    const colors = await p.locator('.chain .cell').evaluateAll(els => els.map(e => getComputedStyle(e).color));
    assert.ok(new Set(colors).size >= 5);
  });
  await check('1280 桌面：AI 正文不少于 320px，阶段单行且无横向溢出', async () => {
    await p.setViewportSize({ width:1280, height:800 });
    await ready(p, 's-workbench.html?mock=1');
    const g = await p.evaluate(() => ({
      ai:document.querySelector('.ai-drawer').getBoundingClientRect().width,
      overflow:document.documentElement.scrollWidth - innerWidth,
      labels:[...document.querySelectorAll('.stage .lab')].map(e => ({ h:e.getBoundingClientRect().height, line:parseFloat(getComputedStyle(e).lineHeight) }))
    }));
    assert.ok(g.ai >= 320); assert.ok(g.overflow <= 1);
    assert.ok(g.labels.every(l => l.h <= l.line + 1));
  });
  await p.setViewportSize({ width:1440, height:900 });
  await check('监考断线与切屏异常色分级，筛选和详情保持可用', async () => {
    await ready(p, 't-monitor.html?mock=1');
    const g = await p.evaluate(() => ['.acard.off','.acard:not(.off)'].map(s => getComputedStyle(document.querySelector(s),'::before').backgroundColor));
    assert.notEqual(g[0],g[1]);
    await p.locator('.acard.off').first().click();
    await p.getByRole('dialog', { name:'学生监考详情' }).waitFor({ state:'visible' });
    await p.keyboard.press('Escape');
  });
  await check('17 页演示入口可挂载，无页面异常和横向溢出', async () => {
    for(const name of fs.readdirSync(app).filter(n => n.endsWith('.html'))) {
      const c = await context(); const page = await c.newPage();
      await ready(page, name + '?mock=1');
      assert.ok(await page.locator('body').innerText());
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), name);
      await c.close();
    }
    assert.deepEqual(errors, []);
  });
  await ctx.close();
} finally { await browser.close(); await new Promise(r => server.close(r)); }
const report = { passed:results.filter(r => r.pass).length, total:results.length, results, errors };
fs.writeFileSync(path.join(out,'report.json'), JSON.stringify(report,null,2));
console.log(JSON.stringify({ passed:report.passed, total:report.total, report:path.join(out,'report.json') }));
process.exitCode = report.passed === report.total ? 0 : 1;
