/* 学生学情控制器离线回归：使用实际适配器和控制器，数据来自明确的测试夹具。 */
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const calls = [];
let personal = { aiUsed: 7, aiStages: [{ stage: 1, count: 2 }, { stage: 3, count: 5 }] };
const original = { loading: true };
let mock = false;
const window = {
  api: {
    http: { useMock: () => mock, describe: e => e.message },
    analytics: {
      me: async () => ({ id: 1, classId: 1 }),
      student: async () => ({ weakKps: [], recentSubmissions: [], ...personal }),
      classKnowledge: async () => ({ list: [] }),
      weekTrend: async q => { calls.push(['trend', q]); return { days: [] }; },
      mySubmissions: async q => { calls.push(['submissions', q]); return []; },
      questions: async () => [], classes: async () => [],
      aiUsage: () => { throw new Error('学生不得调用教师 AI 用量接口'); }
    }
  },
  adapter: {}, analyticsPage: () => original
};
const context = vm.createContext({ window, document: { body: { dataset: { page: 's-analytics' } },
  dispatchEvent() {} }, CustomEvent: class {} });
for (const file of ['adapter/analytics.js', 'step6-analytics.js']) {
  vm.runInContext(fs.readFileSync('prototype/app/' + file, 'utf8'), context, { filename: file });
}
const d = window.analyticsPage();
const checks = [];
function check(name, fn) { fn(); checks.push({ name, pass: true }); }
await d.init();
check('个人 AI 用量来自 #62，四阶段补零', () => {
  assert.equal(d.aiTotal, 7);
  assert.equal(JSON.stringify(d.aiStages.map(s => s.n)), '[2,0,5,0]');
});
check('无调用记录不能推断阶段未开放', () => assert.ok(!d.aiNote.includes('未开放')));
personal = { aiUsed: 1, aiStages: [{ stage: 2, count: 1 }] };
await d.setRange(2);
check('切换周期后清除上一周期阶段计数', () => {
  assert.equal(d.aiTotal, 1);
  assert.equal(JSON.stringify(d.aiStages.map(s => s.n)), '[0,1,0,0]');
});
check('学期趋势和提交表使用同一126天窗口', () => {
  assert.equal(calls.findLast(c => c[0] === 'trend')[1].range, 'term');
  assert.equal(calls.findLast(c => c[0] === 'submissions')[1].range, 126);
  assert.equal(d.rangeLabel, '本学期（126 天）');
});
mock = true;
check('mock模式保留原控制器', () => assert.equal(window.analyticsPage(), original));
fs.mkdirSync('logs/step6-student-unit', { recursive: true });
fs.writeFileSync('logs/step6-student-unit/report.json', JSON.stringify({ passed: checks.length,
  total: checks.length, checks }, null, 2));
console.log(`${checks.length}/${checks.length} passed`);
