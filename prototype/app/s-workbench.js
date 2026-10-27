/* 学生做题工作台 · 交互
   修错闭环一条线：提交 → 逐格点亮 → WA 失败格抖动 + callout → 代码第 8 行 /
   判分链第 3 格 / 抽屉「丢分定位」三处联动高亮 → 学生点「改成 −10⁹」→ 重新提交
   → AC 扫绿光 + 分数滚动 + 窄轨 ④ 解锁。

   状态集中在一个 workbench()，初始态读 ?state=

   ── Day 4 真连（本版新增） ──────────────────────────────────────────
   判题不再是 setTimeout 假动画：
     submit()    → POST /api/judge/submit 拿 submissionId
                 → api.poll 每 1s 轮询 GET /api/judge/status/{id}
                 → 终态（status >= 3）时把 caseDetails 写进 this.cases
     runSample() → POST /api/judge/run-sample（不落库、不占配额）
   演示态（?mock=1）仍走原来的纯前端动画，保证断网答辩不断裂。
   两条分支共用同一份渲染逻辑（cellClass/cellSub 读 this.cases），
   不存在「演示一套、真实一套」——那是 Stage 0 明令禁止的。 */

/* PRD v2.0 微创修正 #4：CASE_LINE / LINE_CASE 已删除。
   原因（03 指南 §5.1）：真实判题系统只拿到 cmd/copyIn 与限值，跑完只回
   状态、耗时、内存，没有任何一环能知道「第 8 行导致第 3 号测试点失败」。
   要做这个能力需要逐行插桩或符号执行，那是另一个课题。原型里它只是让
   格子「看起来在说同一件事」的叙事道具，评审追问一句就穿帮。
   替代：联动高亮统一走 focusCase（测试点维度，真实），点测试点看
   输入/期望/实际三栏 diff，点代码行只显示行号与断点态。 */

const STAGE_NAMES = ['', '① 读题拆解', '② 编码引导', '③ 排错诊断', '④ AC 复盘'];

/* PRD v2.0 微创修正 #4：REPLIES{1..9} 已删除。
   原因（03 指南 §5.2）：九条固定文本与用户输入完全无关——输入「这段代码为什么
   TLE」和输入「你好」拿到同一段话。AI 对话是论文核心创新点，评审在两个输入框
   打两句不同的话立刻能看出回复一模一样，而且它掩盖了 SSE 六事件分发、降级提示、
   护栏改写标注、配额服务端控制这四个真实工程问题。
   替代：真实态和演示态共用 api.sse('/api/ai/chat/stream', ...) 六事件链。 */

/* ── 提交给判题服务的真实源码 ──────────────────────────────────────
   页面上展示的代码是 HTML 写死的（17 行，行号 + 语法高亮 span）。
   为了让「提交的代码」与「看到的代码」严格一致，这里把两份源码
   按行原样存成字符串——`fixed=false` 对应缺陷版（ans = 0），
   `fixed=true` 对应修正版（ans = -1e9）。
   ⚠️ 改 HTML 里任何一行代码，必须同步改这里，否则用户看到的和判的
      就不是同一份代码了。这条约束写在 §3.4「不造假数据」里。

   ── Day 4 修正：代码必须跟题走 ───────────────────────────────────
   这两份源码是**母题「最大子段和」**的解答。工作台以前不管
   ?pid= 是多少都提交它，于是打开任何其它题（比如 #1006 A+B）都会
   判 WA —— 而页面左侧题面、样例、时限全都是那道题的真实数据。
   这不是「判题不准」，是「拿错代码去判」。
   处理：
     · pid === MOTHER_PID（母题）→ 用下面两份写死的代码，视觉零回归不变
     · 其它题 → 用后端 /api/problem/questions/{id} 下发的 codeTemplates.cpp
               作为编辑器内容与提交代码
   这样「看到的题面 / 编辑器里的代码 / 提交去判的代码」三者始终同源。

   ── 第 3 步验收修正：MOTHER_PID 不能写死 1024 ──────────────────
   原先写死 `MOTHER_PID = 1024`，理由是「#1024 是最大子段和」。但本库
   真实数据里 **#1024 是「最大异或和」，最大子段和是 #1001**（Day 3 导入
   1000 道真实题库后，ID 与原型里的假题号不再对应）。后果是：
     · 侧栏点「做题工作台」（无 ?pid=）→ 默认打开 #1024，标题写「最大异或和」，
       编辑器却塞最大子段和的 Kadane 解法 → 提交必然全 WA；
     · ?pid=1024 → 同样串题（题面最大异或和 + 代码最大子段和）。
   现在不再写死数字：`MOTHER_PID` 只作为「开机默认题」的兜底，真实 ID 由
   `resolveMotherPid()` 在运行时按题名反查一次（命中缓存），查不到才用兜底。
   兜底值本身也改成 #1001（本库真实的最大子段和），并保留代码层的
   `isMother(pid)` 判断，让「写死的那两份代码」只属于母题。 */
const CODE_BUGGY = [
  '#include <iostream>',
  '#include <algorithm>',
  'using namespace std;',
  '',
  'int main() {',
  '    int n;',
  '    cin >> n;',
  '    int dp = 0, ans = 0;',
  '    for (int i = 0; i < n; i++) {',
  '        int x;',
  '        cin >> x;',
  '        dp = max(dp + x, x);',
  '        ans = max(ans, dp);',
  '    }',
  '    cout << ans << endl;',
  '    return 0;',
  '}'
].join('\n');

const CODE_FIXED = CODE_BUGGY.replace('int dp = 0, ans = 0;', 'int dp = 0, ans = -1000000000;');

/* 母题 ID：只有它用上面两份写死的代码。
   1024 只是**兜底值**——本库真实的最大子段和是 #1001，运行时由
   resolveMotherPid() 按题名反查覆盖（见文件头「MOTHER_PID 不能写死」）。 */
const MOTHER_PID = 1001;
const MOTHER_TITLE = '最大子段和';

/* 判题结果 → 页面三态（wa/ac/judging）。PAC 也算未完全通过，归到 wa。 */
const FINAL_RESULT = { 3: 'ac', 4: 'wa', 5: 'wa', 6: 'wa', 7: 'wa', 8: 'wa', 9: 'wa' };
const JUDGE_LABELS = ['排队中', '编译中', '评测中', 'AC', 'PAC', 'WA', 'TLE', 'MLE', 'RE', 'CE'];

function workbench() {
  return {
    /* ── 状态 ── */
    result: 'wa',            // wa | judging | ac
    fixed: false,            // 是否已应用行内批注的修正
    hintOpen: false,         // 行内批注展开条
    drawer: true,            // AI 抽屉展开
    think: false,            // 思考过程折叠
    stage: 3,                // 1..4
    model: 'local',          // local | cloud
    modelMenu: false,        // 抽屉头部的模型下拉
    runState: 'idle',        // idle | running | done
    lit: 0,                  // 判题中已跑完的测试点数（真实态由轮询推进）
    chat: [],
    replying: false,
    askText: '',
    /* PRD v2.0 微创修正 #4 / 03 指南 §5.3：配额只能由服务端裁决。
       前端自减 = 用户改一行 JS 就无限用；submit() 重置配额 = 提交一次白送一次
       AI 机会，且这个重置没有任何业务含义。故：
         · 进工作台读 GET /api/ai/workspace-init 的 remainingQuota / maxQuota
         · 每次对话结束只由 SSE done 事件写 remainingQuota（唯一写入口）
         · 拒绝请求后刷新 GET quota，只有权威配额为 0 时禁用输入框
         · submit() 不碰 quotaLeft
       mock 首屏保留 2/3；真实态初始化时置空，随后只接服务端状态。 */
    quotaLeft: 2,
    quotaMax: 3,
    problemW: 0,             // 0 = 跟随 CSS 的响应式栏宽；拖动/按键后才变成定值
    dragging: false,
    intro: true,

    /* 联动高亮：三处（判分链第 N 格 / 代码对应行 / 抽屉丢分定位）共享这一个数。
       0 = 没有聚焦，鼠标移出即归零。 */
    focusCase: 0,

    /* 点开的测试点（0 = 未点开）。与 focusCase 分开：
       focusCase 是「鼠标悬停时的高亮」，移出就归零；
       pinnedCase 是「点一下钉住」，用来让说明卡停在学生想看的那一格上。 */
    pinnedCase: 0,

    /* 底部判分带两档：收起 40px 摘要 / 展开 143px 完整链条 */
    bandOpen: false,
    scoreNum: 80,            // 分数滚动用的中间值，渲染时取整（见 get score）

    /* ── Day 4 真连状态 ── */
    live: false,             // 是否真实态（?mock=1 时为 false）
    problemId: MOTHER_PID,   // 从 ?pid= 读；无参时由 resolveMotherPid() 反查真实母题 ID
    motherPid: MOTHER_PID,   // 「最大子段和」在本库的真实 ID（运行时解析）
    assignmentId: 0,         // 0 为自由练习；作业入口通过 URL 传入
    submissionId: null,      // 本次提交号
    displayNo: '',           // 展示号 S-20481
    cases: [],               // 真实 caseDetails（判分链唯一数据源）
    maxTimeMs: 0,            // 本次最大耗时（底部带）
    maxMemKb: 0,             // 本次最大内存
    errMsg: '',
    poller: null,
    runOut: '',              // 运行样例的真实输出
    /* 当前题的代码：母题用写死的两份，其它题用后端 codeTemplates.cpp */
    code: CODE_BUGGY,
    codeFixed: CODE_FIXED,
    /* 当前题的真实元信息（题面/样例/限值），供左侧题面区显示 */
    problem: null,
    aiReady: false,
    aiEnabled: true,
    aiErr: '',
    modeDesc: '',
    quotaUnlimited: false,
    stageCapsules: [],
    channels: [],
    sseHandle: null,
    activeReply: null,
    _disposed: false,
    _chatVersion: 0,
    _quotaVersion: 0,
    _workspaceVersion: 0,
    _sessionsVersion: 0,
    _judgeVersion: 0,
    _stageSelectionVersion: 0,
    _initialStageApplied: false,
    judgeStatus: null,
    recordScoreLabel: '本题最高分',

    /* ── 左栏题面（真实态读 this.problem，演示态走各自兜底值）──
       x-text 的兜底文案与改造前写死的 HTML 完全一致，所以 ?mock=1 首屏
       逐字节不变；真实态则由接口数据覆盖。 */
    get problemPid() {
      return this.live && this.problem ? '#' + this.problemId : '#1024';
    },
    /* 面包屑「第 4 周作业 / #1024 最大子段和」——真实态跟题走，演示态保持原文案 */
    get problemCrumb() {
      return this.live && this.problem ? ('#' + this.problemId + ' ' + this.problemTitle) : ('#1024 ' + this.problemTitle);
    },
    get problemTitle() {
      return (this.live && this.problem && this.problem.title) || '最大子段和';
    },
    get problemLvl() {
      const lv = this.live && this.problem ? Number(this.problem.difficulty) : 3;
      return Number.isSafeInteger(lv) && lv >= 1 && lv <= 5 ? lv : 3;
    },
    get problemLvlLabel() {
      return ['', '入门', '简单', '中等', '较难', '困难'][this.problemLvl] || '中等';
    },
    get problemMeta() {
      if (!this.live || !this.problem) return '动态规划 · 前缀和';
      const tags = Array.isArray(this.problem.tags) ? this.problem.tags : [];
      return tags.length ? tags.join(' · ') : '未分类';
    },
    /* 题面正文：后端给的是 Markdown。原型左栏只有一个 <p>，取「题目描述」第一段；
       LaTeX 公式降级为纯文本（$$ → 去掉，→ 保留可读符号），不引第三方渲染库。 */
    get problemIntro() {
      if (!this.live || !this.problem || !this.problem.statement) {
        return '给定一个长度为 n 的整数序列 a₁…aₙ，请找出其中一段连续子序列，使得它的元素之和最大。子序列至少要包含一个元素。';
      }
      const text = String(this.problem.statement)
        .replace(/^#+\s*/gm, '')
        .split(/\n{2,}/)
        .map(s => s.trim())
        .filter(s => s && !/^(样例|输入格式|输出格式|数据范围)/.test(s));
      const first = (text[0] || '').replace(/\*\*/g, '').replace(/\$/g, '').trim();
      return first || '题面加载中…';
    },
    get problemInputSpec() {
      if (!this.live || !this.problem) {
        return '第一行一个整数 n（1 ≤ n ≤ 10⁵）。第二行 n 个整数，|aᵢ| ≤ 10⁴。';
      }
      return String(this.problem.inputSpec || '—').replace(/\$/g, '').replace(/`/g, '').trim() || '—';
    },
    get problemOutputSpec() {
      if (!this.live || !this.problem) {
        return '一个整数，表示最大子段和。';
      }
      return String(this.problem.outputSpec || '—').replace(/\$/g, '').replace(/`/g, '').trim() || '—';
    },
    /* 样例：真实态用接口的 samples；演示态用原型里那两组写死的示例。
       注意 x-show 的「可见性」在真实态才生效——mock 首屏必须与基线逐字节一致，
       所以这两个 getter 在非 live 时恒为 true，且 sample 文案与基线一字不差。 */
    get sample1In() {
      if (this.live && this.problem) {
        const s = this._sampleAt(0);
        return s ? String(s.input || '').replace(/\s+$/, '') : '—';
      }
      return '6\n-2 11 -4 13 -5 -2';
    },
    get sample1Out() {
      if (this.live && this.problem) {
        const s = this._sampleAt(0);
        return s ? String(s.output || '').replace(/\s+$/, '') : '—';
      }
      return '20';
    },
    get sample2In() {
      if (this.live && this.problem) {
        const s = this._sampleAt(1);
        return s ? String(s.input || '').replace(/\s+$/, '') : '';
      }
      return '3\n-1 -2 -3';
    },
    get sample2Out() {
      if (this.live && this.problem) {
        const s = this._sampleAt(1);
        return s ? String(s.output || '').replace(/\s+$/, '') : '';
      }
      return '-1';
    },
    get hasSample1() {
      if (!this.live) return true;
      return !!this._sampleAt(0);
    },
    get hasSample2() {
      if (!this.live) return true;
      return !!this._sampleAt(1);
    },
    _sampleAt(i) {
      const list = this.problem && Array.isArray(this.problem.samples) ? this.problem.samples : [];
      return list[i] || null;
    },
    get limitText() {
      if (!this.live || !this.problem) return '1000 ms';
      const ms = Number(this.problem.timeLimitMs);
      return Number.isFinite(ms) && ms > 0 ? ms + ' ms' : '—';
    },
    /* 时限 / 内存 / 隐藏测试点 三格：
       `.prob-info dd span` 会把格子里的 <span> 打到 --fs-xs/--ink4、字体也换成 sans，
       而基线里这三格是「dd 直接文本 + 等宽正文字号」。所以**不能用 x-text 加 span**，
       也不给 dd 加绑定（加了就得新增子节点）。改为 init 后按索引写既有节点的首个文本节点，
       DOM 结构与 class 一个不动，?mock=1 首屏逐字节不变。 */
    syncProblemFoot() {
      const dds = document.querySelectorAll('.prob-info > div > dd');
      if (!dds.length) return;
      const writeText = (dd, text) => { if (dd && dd.firstChild && dd.firstChild.nodeType === 3) dd.firstChild.textContent = text; };
      writeText(dds[0], this.limitText);
      writeText(dds[1], this.memoryText);
      writeText(dds[2], this.caseCountText);
      /* 难度文字是 `.lvl` 的首个文本节点（后面紧跟 .lvl-bars）。
         基线里它是裸文本「中等」，没有包 <span>——所以同样不能加 x-text+span，
         否则就多一个元素节点（破 §3.1 红线）。按既有写法只改文本节点。 */
      const lvl = document.querySelector('.prob-title .lvl');
      if (lvl && lvl.firstChild && lvl.firstChild.nodeType === 3) {
        lvl.firstChild.textContent = this.problemLvlLabel;
      }
    },
    get memoryText() {
      if (!this.live || !this.problem) return '256 MB';
      const mb = Number(this.problem.memoryLimitMb);
      return Number.isFinite(mb) && mb > 0 ? mb + ' MB' : '—';
    },
    get caseCountText() {
      if (!this.live || !this.problem) return '10 组';
      const n = Number(this.problem.testCaseCount);
      return Number.isFinite(n) && n > 0 ? n + ' 组' : '—';
    },
    get scoringNote() {
      if (!this.live || !this.problem) return '10 个隐藏测试点按通过比例折算，满分 100 分。';
      const n = Number(this.problem.testCaseCount) || 0;
      const pub = Number(this.problem.publicCaseCount) || 0;
      if (!n) return '按测试点通过比例折算，满分 100 分。';
      return `${n} 个测试点（公开 ${pub} 个）按分值累加折算，满分 100 分。`;
    },
    get bestScoreText() {
      return this.result === 'ac' ? '100 / 100' : '80 / 100';
    },

    /* ── 派生 ── */
    get judging() { return this.result === 'judging'; },
    get stage4Unlocked() { return this.live ? this.isStageUnlocked(4) : this.result === 'ac'; },
    get stageLabel() {
      const capsule = this.stageCapsules.find(c => Number(c.stage) === this.stage);
      return this.live && capsule ? ('①②③④'[this.stage - 1] + ' ' + (capsule.stageName || capsule.title || '')) : STAGE_NAMES[this.stage];
    },
    get askPlaceholder() {
      if (this.askUnavailableReason) return this.askUnavailableReason;
      return this.judging ? '判题中，先等链带跑完…' : '追问 AI 助教…';
    },
    get askUnavailableReason() {
      if (this.live && !this.aiReady) return this.aiErr || '正在加载 AI 工作台…';
      if (this.live && !this.aiEnabled) return this.aiErr || this.modeDesc || '当前模式未开放 AI';
      if (this.live && !this.isStageUnlocked(this.stage)) return this.stageLockReason(this.stage);
      if (!this.quotaUnlimited && this.quotaLeft <= 0) return '本题提示次数已用完';
      return '';
    },
    get askInputTitle() {
      if (this.live && this.askUnavailableReason) return this.askUnavailableReason;
      return this.quotaUnlimited ? 'Enter 发送 · 当前模式不限提示次数' : 'Enter 发送 · 每次提问消耗 1 次提示';
    },
    get sendTitle() {
      if (this.quotaEmpty) return this.askUnavailableReason || '本题提示次数已用完';
      return this.quotaUnlimited ? '发送 · 当前模式不限提示次数' : `发送 · 消耗 1 次提示，本题还剩 ${this.quotaLeft} 次`;
    },
    get quotaLabel() { return this.quotaUnlimited ? '不限' : this.quotaLeft + ' / ' + this.quotaMax; },
    get modelLabel() { return this.live ? this.channelName(this.model) : (this.model === 'local' ? '本地极速' : '云端'); },
    get judgeStatusLabel() { return this.judgeStatus === null ? '' : (JUDGE_LABELS[this.judgeStatus] || ''); },
    get judgeSummary() {
      if (this.judging) return '判题中…';
      return this.judgeStatus === null ? '暂无判题结果' : this.recordScoreLabel + ' ' + this.score + ' / 100 · ' + this.judgeStatusLabel;
    },
    get diagnosisContext() {
      if (!this.live) return this.judging ? '判题中…' : '第 3 号测试点';
      if (!this.aiReady || !this.aiEnabled || !this.isStageUnlocked(3)) return this.stageContext(3);
      if (this.judging) return '判题中…';
      if (this.firstFailSeq) return '第 ' + this.firstFailSeq + ' 号测试点';
      if (this.judgeStatus === null) return '暂无判题结果';
      return this.judgeStatus === 3 ? '暂无失败测试点' : (this.judgeStatus === 9 ? '编译未通过' : '暂无可用失败点详情');
    },
    get acceptedContext() {
      return this.cases.length ? this.passCount + ' / ' + this.totalCount + ' 个测试点' : this.judgeSummary;
    },
    // 分数：整数渲染 + body 上的 tabular-nums，滚动时数字不会左右抖
    get score() { return Math.round(this.scoreNum); },
    // 丢分点是否仍然成立：WA 且尚未应用行内修正
    get failLive() { return this.result === 'wa' && !this.fixed; },
    /* PRD v2.0 微创修正 #4：focusLine getter 已删除（见文件头注释）。
       联动高亮只剩 focusCase 一个维度，颜色由 body[data-focus] 统一切换。 */
    // 配额是否耗尽（驱动输入框 disabled 与发送按钮禁用态）
    get quotaEmpty() {
      return (this.live && (!this.aiReady || !this.aiEnabled || !this.isStageUnlocked(this.stage))) ||
             (!this.quotaUnlimited && this.quotaLeft <= 0);
    },
    // 通过测试点数：真实态数 cases，演示态按 8/10 兜底
    get passCount() {
      if (this.cases.length) return this.cases.filter(c => c.gotScore >= c.score).length;
      return this.result === 'ac' ? 10 : 8;
    },
    get totalCount() { return this.cases.length || 10; },
    // 首个失败点序号（真实态从 cases 里找，演示态固定 #3）
    get firstFailSeq() {
      if (this.cases.length) {
        const f = this.cases.find(c => c.gotScore < c.score);
        return f ? f.seqNo : 0;
      }
      return this.live || this.result === 'ac' ? 0 : 3;
    },
    // 首个失败点的输入/期望/实际（callout 用）
    get failCase() {
      if (this.cases.length) return this.cases.find(c => c.gotScore < c.score) || null;
      return null;
    },
    /* ── 说明卡定位（点测试点看输入输出对比）──
       原实现只认「首个失败点」那一格，且 callout 随 mouseleave 消失：学生
       **点**第 8 格想看它为什么错，鼠标一移开就没了。《6 步手册》第 3 步
       的验收标志要求「点击第 3 格能看到真实的输入输出对比」，所以：
         · pinCase 把某一格钉住，钉住期间说明卡跟着它走；
         · 没钉住时退回「首个失败点」语义，视觉零回归。
       pinnedCase 为 0 表示未钉住。 */
    get calloutSeq() {
      if (this.pinnedCase) return this.pinnedCase;
      return this.result === 'wa' ? this.firstFailSeq : 0;
    },
    get calloutCase() {
      if (!this.cases.length) return null;
      const seq = this.calloutSeq;
      if (seq) return this.cases.find(c => Number(c.seqNo) === Number(seq)) || null;
      return null;
    },

    /* ── 初始化 ── */
    async init() {
      const p = new URLSearchParams(location.search);
      this.live = !(window.api && api.http.useMock && api.http.useMock());
      const urlPid = Number(p.get('pid') || 0), aid = Number(p.get('assignmentId') || 0);
      this.problemId = Number.isSafeInteger(urlPid) && urlPid > 0 ? urlPid : MOTHER_PID;
      this.assignmentId = Number.isSafeInteger(aid) && aid >= 0 ? aid : 0;

      /* 无 ?pid= 时（侧栏直接点「做题工作台」），开机默认题必须是本库真实的
         「最大子段和」，不能写死数字——Day 3 导入真实题库后 ID 已变。
         真实态先反查一次题名，查不到就退回兜底值，绝不因此白屏。 */
      if (this.live && !(Number.isSafeInteger(urlPid) && urlPid > 0)) {
        const resolved = await this.resolveMotherPid();
        if (resolved) this.problemId = resolved;
      }

      const s = this.live ? 'default' : (p.get('state') || 'default');
      if (s === 'hint') { this.hintOpen = true; }
      if (s === 'think') { this.think = true; }
      if (s === 'judging') { this.result = 'judging'; this.lit = 5; this.stage = 3; this.bandOpen = true; }
      if (s === 'ac') { this.result = 'ac'; this.fixed = true; this.stage = 4; this.bandOpen = true; this.scoreNum = 100; }
      if (s === 'locked') { this.stage = 4; }
      if (s === 'closed') { this.drawer = false; }
      if (s === 'cloud') { this.model = 'cloud'; this.think = true; }
      if (s === 'stage1') { this.stage = 1; }
      if (s === 'stage2') { this.stage = 2; }
      if (s === 'band') { this.bandOpen = true; }          // 截图 / 巡检用：直接看展开态
      if (s === 'menu') { this.modelMenu = true; }         // 模型下拉展开态
      if (s === 'focus') { this.focusCase = Number(p.get('case') || 3); }

      /* 真实态：从 ?pid= 读题目，进来先拉一次「我的最好记录」，
         让「本题最高分 / 最近提交」两块显示真实值，而不是写死的 80/100。 */
      if (this.live) {
        this.quotaLeft = 0; this.quotaMax = 0;
        this.loadMine();
        this.loadWorkspace();
      } else this.aiReady = true;

      /* HTML 的关闭按钮直接写 drawer，监听既有状态即可取消流，无需修改模板。 */
      if (this.$watch) {
        this.$watch('drawer', (open) => {
          if (!open) this.cancelAi('抽屉已收起，回复已中断');
          else if (this.live) { this.loadSessions(); this.refreshQuota(); }
        });
        this.$watch('stage', () => {
          this._stageSelectionVersion++;
          if (this.replying) this.cancelAi('阶段已切换，回复已中断');
        });
      }

      setTimeout(() => { this.intro = false; }, 1400);

      window.addEventListener('keydown', (e) => {
        // 快捷键：Ctrl+Enter 提交、Ctrl+' 运行。写进顶栏按钮的 title 里（见 HTML）。
        // 除了 e.key 再兜一层 e.code：某些自动化/远程输入法只给 code 不给 key，
        // 只认 key 会静默失效，而快捷键失效是最难被用户发现、也最容易怪到浏览器头上的一类 bug。
        const mod = e.ctrlKey || e.metaKey;
        if (mod && (e.key === 'Enter' || e.code === 'Enter' || e.code === 'NumpadEnter')) {
          if (e.repeat) return;
          e.preventDefault();
          this.submit();
          return;
        }
        if (mod && (e.key === "'" || e.code === 'Quote')) {
          if (e.repeat) return;
          e.preventDefault();
          this.runSample();
          return;
        }
        if (e.key === 'Escape') {
          if (this.modelMenu) this.modelMenu = false;
          else if (this.hintOpen) this.hintOpen = false;
          else if (this.drawer) this.drawer = false;
        }
      });
      window.addEventListener('mousemove', (e) => this.onDragMove(e));
      window.addEventListener('mouseup', () => this.stopDrag());
      /* 卸载时停掉轮询，否则切题后旧轮询还在打接口 */
      window.addEventListener('beforeunload', () => this.destroy());
    },

    destroy() {
      this._disposed = true;
      this.cancelAi();
      if (this.poller) { this.poller.stop('unload'); this.poller = null; }
    },

    /* ── 反查母题真实 ID（不写死数字）──
       本库「最大子段和」是 #1001，不是原型里的 #1024。这里按题名走一次
       关键词搜索，命中即缓存到 this.motherPid；查不到就返回 0，调用方退回
       MOTHER_PID 兜底。失败不抛，进页面不能因为这一发请求白屏。 */
    async resolveMotherPid() {
      if (this._motherResolved) return this.motherPid;
      this._motherResolved = true;
      try {
        const d = await api.http.get('/api/problem/questions', {
          query: { page: 1, pageSize: 20, kw: MOTHER_TITLE }
        });
        const hit = (d.list || []).find(x => String(x.title || '') === MOTHER_TITLE);
        if (hit) { this.motherPid = Number(hit.id); return this.motherPid; }
      } catch (e) {
        console.warn('[workbench] 母题 ID 反查失败，退回兜底值', e && e.message);
      }
      return 0;
    },
    /* 当前打开的是不是母题（只有母题用写死的那两份代码） */
    isMother(pid) { return Number(pid) === Number(this.motherPid); },

    /* ── 进页面拉题目详情（真实态） ──
       一次拿到三样东西：
         ① codeTemplates.cpp —— 非母题时作为编辑器内容与提交代码
         ② 题面/样例/限值     —— 左侧题面区显示真实数据
         ③ myRecord           —— 本题最高分与最近状态
       失败不阻塞页面：拿不到就保持原型默认值，只 console.warn。
       这里不弹 toast——进页面就报错会让演示看起来像系统坏了。 */
    async loadMine() {
      const revision = this._judgeVersion;
      try {
        const d = await api.http.get('/api/problem/questions/' + this.problemId);
        if (this._disposed || revision !== this._judgeVersion) return;
        this.problem = d;
        this.syncProblemFoot();
        /* 代码跟题走：母题用写死的那两份（视觉零回归），其它题用后端模板。
           母题判定必须走 isMother()——写死 1024 会导致「最大异或和」也拿到
           最大子段和的代码，题面与提交代码再次不同源。 */
        if (!this.isMother(this.problemId)) {
          const tpl = (d.codeTemplates && d.codeTemplates.cpp) || '';
          if (tpl) {
            this.code = tpl;
            this.codeFixed = tpl;   // 非母题没有「缺陷版/修正版」之分
          }
        }
        if (d && d.myRecord && d.myRecord.submitted) {
          this.scoreNum = d.myRecord.bestScore || 0;
          this.judgeStatus = typeof d.myRecord.lastStatus === 'number' ? d.myRecord.lastStatus : null;
          this.recordScoreLabel = '本题最高分';
          this.result = FINAL_RESULT[d.myRecord.lastStatus] || 'wa';
        }
      } catch (e) {
        console.warn('[workbench] 题目详情加载失败', e && e.message);
      }
    },

    /* ── 提交判题 ── */
    submit() {
      if (this.judging) return;
      this._judgeVersion++;
      this.cancelAi();
      this.result = 'judging';
      this.bandOpen = true;          // 判题时把链条展开，让逐格点亮可见
      this.lit = 0;
      this.hintOpen = false;
      this.chat = [];
      /* PRD v2.0 微创修正 #4：原 this.quotaLeft = 2 已删除。
         提交判题不该重置 AI 配额——配额由服务端按作业模式与逾期状态裁决，
         前端任何重置都是凭空造次数。 */
      this.focusCase = 0;
      this.pinnedCase = 0;       // 新一次提交：取消上一轮点开的测试点
      this.errMsg = '';
      this.cases = [];
      this.maxTimeMs = 0;
      this.maxMemKb = 0;

      /* 真实态：走接口 + 轮询。演示态：保持原来的纯前端动画。 */
      if (this.live) { this.submitLive(); return; }
      this.submitDemo();
    },

    /* 真实提交：POST /api/judge/submit → 1s 轮询 #21 直到终态 */
    async submitLive() {
      const code = this.fixed ? this.codeFixed : this.code;
      try {
        const r = await api.http.post('/api/judge/submit', {
          problemId: this.problemId,
          assignmentId: this.assignmentId,
          language: 'cpp',
          code: code
        });
        this.submissionId = r.submissionId;
        this.judgeStatus = 0;
        this.displayNo = r.displayNo || ('S-' + r.submissionId);
        /* 判题中让格子有节奏地点亮，纯视觉；真实结果以轮询返回为准。
           这不是造假——只是把「已排队」这件事画出来，最终状态由 cases 覆盖。 */
        this.startLitAnimation();
        this.startPoll();
      } catch (e) {
        this.result = 'wa';
        this.errMsg = api.http.describe(e);
        this.bandOpen = false;
        if (window.OJ && OJ.toast) OJ.toast(this.errMsg, { type: 'err' });
      }
    },

    startLitAnimation() {
      let i = 0;
      const tick = () => {
        if (!this.judging) return;              // 已出结果就停
        i++;
        this.lit = Math.min(i, 9);
        if (i < 10) setTimeout(tick, 180);
      };
      setTimeout(tick, 220);
    },

    startPoll() {
      const self = this;
      if (this.poller) this.poller.stop('restart');
      this.poller = api.poll(
        () => api.http.get('/api/judge/status/' + self.submissionId),
        {
          interval: 1000,
          limit: 120,
          /* status 0/1/2 是过程态，>= 3 才是终态 */
          isDone: (d) => d && typeof d.status === 'number' && d.status >= 3,
          onData: (d) => {
            if (d && d.caseDetails && d.caseDetails.length) {
              self.cases = d.caseDetails.map(adapter.adaptCase);
            }
            self.maxTimeMs = d.timeUsedMs || 0;
            self.maxMemKb = d.memoryUsedKb || 0;
          },
          onStop: (why) => {
            if (why !== 'done') {
              /* 超时/致命错误：不能假装判完了。回落到 WA 并给出真实原因。 */
              self.result = 'wa';
              self.errMsg = why === 'limit' ? '判题超时，请到提交记录查看结果'
                                            : '判题状态查询中断';
              return;
            }
            /* 终态：以最后一次轮询的数据为准，再拉一次详情保证 cases 完整 */
            self.finishLive();
          }
        }
      ).start();
    },

    async finishLive() {
      try {
        const d = await api.http.get('/api/judge/status/' + this.submissionId);
        this.applyResult(d);
      } catch (e) {
        this.result = 'wa';
        this.errMsg = api.http.describe(e);
      }
    },

    /* 把 #21 的响应映射到页面三态。这里是**唯一**的结果写入口。 */
    applyResult(d) {
      if (!d) return;
      this.judgeStatus = typeof d.status === 'number' ? d.status : null;
      this.recordScoreLabel = '本次得分';
      this.cases = (d.caseDetails || []).map(adapter.adaptCase);
      this.maxTimeMs = d.timeUsedMs || 0;
      this.maxMemKb = d.memoryUsedKb || 0;
      this.displayNo = d.displayNo || this.displayNo;
      const next = FINAL_RESULT[d.status] || 'wa';
      const prev = this.result;
      this.result = next;
      this.lit = 10;
      this.errMsg = d.errorMsg || '';
      if (next === 'ac') {
        this.scoreNum = 80;
        this.rollScore(100);
        /* 真实态阶段只读服务端胶囊；判题完成不覆盖用户正在看的阶段。 */
        setTimeout(() => { this.bandOpen = false; }, 3000);
      } else {
        this.scoreNum = d.oiScore !== undefined ? d.oiScore : this.scoreNum;
        setTimeout(() => { this.bandOpen = false; }, 3200);
        if (prev === 'judging' && window.OJ && OJ.toast) {
          OJ.toast('未通过：' + (d.statusLabel || 'WA'), { type: 'warn' });
        }
      }
      if (this.live) this.refreshQuota();
    },

    /* 演示态：原来的纯前端动画，一行未改（保证 ?mock=1 视觉零回归） */
    submitDemo() {
      const willPass = this.fixed;
      const total = 10;
      let i = 0;

      const tick = () => {
        i++;
        this.lit = i;
        if (i < total) {
          setTimeout(tick, willPass ? 92 : 96);
        } else {
          setTimeout(() => {
            this.result = willPass ? 'ac' : 'wa';
            if (willPass) {
              // 分数 80 → 100 滚动（480ms ease-out），窄轨 ④ 同时从锁变勾
              this.scoreNum = 80;
              this.rollScore(100);
              setTimeout(() => { this.stage = 4; }, 420);
            } else {
              this.scoreNum = 80;
            }
            // 结果落定后把高度还给编辑器：留在摘要条上，需要时再展开
            setTimeout(() => { this.bandOpen = false; }, willPass ? 3000 : 3200);
          }, 300);
        }
      };
      setTimeout(tick, 420);
    },

    /* 数字滚动：480ms、ease-out，逐帧取整渲染（body 已开 tabular-nums） */
    rollScore(to) {
      const from = this.scoreNum;
      const t0 = performance.now();
      const dur = 480;
      const ease = (t) => 1 - Math.pow(1 - t, 3);
      const step = (now) => {
        const p = Math.min(1, (now - t0) / dur);
        this.scoreNum = from + (to - from) * p;
        if (p < 1) requestAnimationFrame(step);
        else this.scoreNum = to;
      };
      requestAnimationFrame(step);
    },

    /* ── 运行样例 ── */
    async runSample() {
      if (this.runState === 'running') return;
      this.runState = 'running';
      this.runOut = '';

      if (this.live) {
        try {
          const r = await api.http.post('/api/judge/run-sample', {
            problemId: this.problemId,
            language: 'cpp',
            code: this.fixed ? this.codeFixed : this.code,
            mode: 'sample'
          });
          const first = (r.cases || [])[0];
          this.runOut = first ? (first.actual || '') : '';
          this.runState = 'done';
          if (window.OJ && OJ.toast) {
            OJ.toast('样例运行完成：' + (first ? first.status : 'FINISHED'),
                     { type: first && first.statusCode === 'ac' ? 'ok' : 'warn' });
          }
        } catch (e) {
          this.runState = 'idle';
          if (window.OJ && OJ.toast) OJ.toast(api.http.describe(e), { type: 'err' });
          return;
        }
        setTimeout(() => { this.runState = 'idle'; }, 2800);
        return;
      }

      /* 演示态：保持原样 */
      setTimeout(() => {
        this.runState = 'done';
        setTimeout(() => { this.runState = 'idle'; }, 2800);
      }, 760);
    },

    /* ── 行内批注 ── */
    applyFix() {
      this.fixed = true;
      this.hintOpen = true;
      this.focusCase = 0;
    },

    /* ── 阶段窄轨 ── */
    setStage(n) {
      this._stageSelectionVersion++;
      if (this.stage !== n) this.cancelAi('阶段已切换，回复已中断');
      this.stage = n;
      this.drawer = true;
    },
    stageState(n) {
      if (this.live && !this.isStageUnlocked(n)) return n === this.stage ? 'cur' : '';
      if (n === 4 && !this.stage4Unlocked) return n === this.stage ? 'cur' : '';
      if (n < this.stage) return 'done';
      if (n === this.stage) return 'cur';
      return '';
    },

    /* ── 联动高亮 ──
       判分链第 N 格 / 抽屉「丢分定位」卡，共用 focusCase 一个状态：
       谁被悬停或聚焦，另一处同时加 .is-focus。
       颜色由 body[data-focus] 统一切换——聚焦的是失败点就用红，其余用蓝。

       PRD v2.0 微创修正 #4：focusFromLine() 已删除。代码行不再假装与某个
       测试点相关（真实判题拿不到这个映射），鼠标进代码行只显示行号。 */
    clearFocus() { this.focusCase = 0; },

    /* 点击测试点：钉住它（再点同一格取消）。
       说明卡因此不会在鼠标移开时消失——「点第 3 格看真实输入输出对比」
       是《6 步手册》第 3 步的验收标志，必须能稳定看到。 */
    pinCase(i) {
      const n = Number(i);
      this.pinnedCase = this.pinnedCase === n ? 0 : n;
      this.focusCase = this.pinnedCase || 0;
    },

    /* ── 链带格子 ──
       真实态：完全由 this.cases 驱动（第 i 格 = cases[i-1]）
       演示态：沿用原来的 judging / wa#3 逻辑，保证视觉零回归 */
    cellClass(i) {
      if (this.judging) return i <= this.lit ? 'lit' : '';
      if (this.cases.length) {
        const c = this.cases[i - 1];
        if (!c) return '';
        return c.gotScore >= c.score ? 'ac' : (c.status || 'wa');
      }
      if (this.result === 'wa' && i === 3) return 'fail';
      return '';
    },
    cellSub(i) {
      if (this.judging) return i <= this.lit ? '通过' : '待判';
      if (this.cases.length) {
        const c = this.cases[i - 1];
        if (!c) return '';
        return (c.statusLabel || '').toUpperCase() || (c.gotScore >= c.score ? 'AC' : 'WA');
      }
      return ['样例', '样例', '全负', '边界', '全正', '混合', '大数据', '边界', '随机', '极限'][i - 1] || '';
    },

    /* ── AI 服务端状态 ── */
    aiQuery() { return { problemId: this.problemId, assignmentId: this.assignmentId }; },
    isStageUnlocked(n) {
      const c = this.stageCapsules.find(c => Number(c.stage) === Number(n));
      return !!(this.aiReady && this.aiEnabled && c && c.unlocked);
    },
    stageLockReason(n) {
      const c = this.stageCapsules.find(c => Number(c.stage) === Number(n));
      return c && c.lockReason ? c.lockReason : '当前阶段尚未解锁';
    },
    stageContext(n) {
      if (!this.aiReady) return this.aiErr || '正在加载 AI 工作台…';
      if (!this.aiEnabled) return this.aiErr || this.modeDesc || '当前模式未开放 AI';
      return this.isStageUnlocked(n) ? (this.modeDesc || STAGE_NAMES[n]) : this.stageLockReason(n);
    },
    channelFor(kind) {
      const code = kind === 'cloud' ? 'cloud-ds' : 'local-qwen';
      return this.channels.find(c => c.channelCode === code) || null;
    },
    channelName(kind) {
      const c = this.channelFor(kind);
      return c ? (c.channelName || c.modelName || c.channelCode) : (kind === 'cloud' ? '云端通道未开放' : '本地通道未开放');
    },
    channelState(kind) {
      const c = this.channelFor(kind);
      return c ? ({ ON: '可用', DEGRADE: '降级', OFF: '不可用', PROBING: '探测中' }[c.healthStatus] || '已配置') : '未开放';
    },
    channelSubtitle(kind) {
      const c = this.channelFor(kind);
      return c ? (c.modelName || c.channelName || c.channelCode) + ' · ' + this.channelState(kind) : '当前没有可用的模型通道';
    },
    applyQuota(d) {
      if (!d) return;
      const max = d.maxQuota !== undefined ? d.maxQuota : d.max;
      const left = d.remainingQuota !== undefined ? d.remainingQuota : d.remaining;
      this.quotaUnlimited = d.unlimited === true || Number(max) === -1;
      if (this.quotaUnlimited) {
        this.quotaLeft = '不限'; this.quotaMax = '不限';
      } else {
        if (typeof left === 'number') this.quotaLeft = Math.max(0, left);
        if (typeof max === 'number') this.quotaMax = Math.max(0, max);
      }
      if (typeof d.aiEnabled === 'boolean') this.aiEnabled = d.aiEnabled;
      if (Array.isArray(d.stageCapsules || d.stages)) this.stageCapsules = d.stageCapsules || d.stages;
    },
    historyMessages(rows) {
      const messages = [];
      rows.forEach(r => {
        if (r.role && (r.text !== undefined || r.content !== undefined)) {
          messages.push({ role: r.role === 'user' || r.role === 'me' ? 'me' : 'ai',
                          text: String(r.text !== undefined ? r.text : r.content), recordId: r.recordId || null });
          return;
        }
        if (r.userMessage) messages.push({ role: 'me', text: String(r.userMessage) });
        if (r.aiResponse !== undefined && r.aiResponse !== null) {
          const status = [];
          if (r.isFallback) status.push('降级：已使用备用通道');
          if (r.guardrailCode === 'rewrite' || Number(r.guardrailAction) === 2) status.push('护栏：回复已改写');
          messages.push({ role: 'ai', text: status.concat(String(r.aiResponse)).join('\n'), recordId: r.recordId || null });
        }
      });
      return messages;
    },
    async loadWorkspace() {
      const request = ++this._workspaceVersion, chatVersion = this._chatVersion, quotaVersion = this._quotaVersion;
      const initialStage = this.stage, selectionVersion = this._stageSelectionVersion;
      try {
        const d = await api.http.get('/api/ai/workspace-init', { query: this.aiQuery() });
        if (this._disposed || request !== this._workspaceVersion) return;
        this.aiReady = true; this.aiEnabled = d.aiEnabled === true;
        this.modeDesc = d.modeDesc || ''; this.aiErr = '';
        if (quotaVersion === this._quotaVersion) this.applyQuota(d);
        if (!this._initialStageApplied) {
          if (selectionVersion === 0 && this._stageSelectionVersion === 0 && this.stage === initialStage && !this.isStageUnlocked(this.stage)) {
            const first = this.stageCapsules.find(c => c.unlocked);
            if (first) this.stage = Number(first.stage);
          }
          this._initialStageApplied = true;
        }
        this.channels = Array.isArray(d.availableChannels) ? d.availableChannels : [];
        if (chatVersion === this._chatVersion && !this.replying) this.chat = this.historyMessages(d.chatHistory || []);
        if (!this.aiEnabled) this.aiNotice(this.modeDesc || '当前模式未开放 AI');
        this.loadChannels();
      } catch (e) {
        if (this._disposed || request !== this._workspaceVersion) return;
        this.aiErr = api.http.describe(e);
        this.aiEnabled = false;
        this.aiNotice(this.aiErr);
      }
    },
    async loadChannels() {
      try {
        const d = await api.http.get('/api/ai/channels', { query: this.aiQuery() });
        if (!this._disposed) this.channels = Array.isArray(d.list) ? d.list : [];
      } catch (e) {
        if (!this._disposed) this.aiNotice('通道列表加载失败：' + api.http.describe(e));
      }
    },
    async refreshQuota() {
      if (!this.live || !this.aiReady || this._disposed) return;
      const revision = ++this._quotaVersion;
      try {
        const d = await api.http.get('/api/ai/quota', { query: this.aiQuery() });
        if (!this._disposed && revision === this._quotaVersion) this.applyQuota(d);
      } catch (e) {
        if (this._disposed || revision !== this._quotaVersion) return;
        this.aiNotice('配额刷新失败：' + api.http.describe(e));
      }
    },
    async loadSessions() {
      if (!this.live || !this.aiReady || this.replying || this._disposed) return;
      const request = ++this._sessionsVersion, revision = this._chatVersion;
      try {
        const d = await api.http.get('/api/ai/sessions', { query: Object.assign(this.aiQuery(), { page: 1, pageSize: 20 }) });
        if (this._disposed || request !== this._sessionsVersion || revision !== this._chatVersion || this.replying) return;
        /* sessions 按时间倒序，聊天区按时间正序；不重排服务端其它字段。 */
        this.chat = this.historyMessages((d.list || []).slice().reverse());
        this.scrollChat();
      } catch (e) {
        if (!this._disposed && revision === this._chatVersion) this.aiNotice('历史加载失败：' + api.http.describe(e));
      }
    },
    selectedChannel() {
      const c = this.channelFor(this.model);
      return c && c.healthStatus !== 'OFF' ? c : null;
    },
    aiNotice(text) {
      this.chat.push({ role: 'ai', text: String(text) });
      this.scrollChat();
    },
    scrollChat() {
      this.$nextTick && this.$nextTick(() => {
        const box = document.querySelector('.ai-scroll');
        if (box) box.scrollTop = box.scrollHeight;
      });
    },
    renderReply(reply) {
      /* 所有服务器内容只写 text，模板用 x-text，不生成 HTML。 */
      reply.text = reply.status.concat(reply.answer || '').filter(Boolean).join('\n');
      this.scrollChat();
    },
    cancelAi(message) {
      this._chatVersion++;
      if (this.sseHandle) this.sseHandle.close();
      this.sseHandle = null;
      if (this.replying && this.activeReply && message) {
        this.activeReply.status.push(message);
        this.renderReply(this.activeReply);
      }
      this.activeReply = null;
      this.replying = false;
    },
    async feedback(recordId, helpful, reasonCode, comment) {
      return api.http.post('/api/ai/feedback', { recordId, helpful, reasonCode: reasonCode || null, comment: comment || null });
    },

    /* ── 追问：六事件都落到既有 chat.role/text，不改变 DOM ── */
    ask(text) {
      const q = String(text == null ? this.askText : text).trim();
      if (!q || this.replying || this.quotaEmpty || this._disposed) return;
      const channel = this.selectedChannel();
      if (this.live && !channel) { this.aiNotice('所选模型通道当前不可用，请切换模型后重试'); return; }
      this.cancelAi();
      const revision = this._chatVersion;
      this._quotaVersion++;       // 先前发出的配额 GET 不能覆盖本轮对话的配额
      this.chat.push({ role: 'me', text: q });
      this.chat.push({ role: 'ai', text: '正在连接 AI 助教…', status: [], answer: '', recordId: null });
      /* 从响应式数组取回代理，异步 token 才能触发 Alpine 更新。 */
      const reply = this.chat[this.chat.length - 1];
      this.activeReply = reply;
      this.askText = '';
      this.replying = true;
      let completed = false;
      const current = () => !this._disposed && revision === this._chatVersion;
      const status = line => { reply.status.push(line); this.renderReply(reply); };
      const stop = () => {
        this.replying = false;
        this.activeReply = null;
        if (this.sseHandle) this.sseHandle.close();
        this.sseHandle = null;
      };
      this.sseHandle = api.sse('/api/ai/chat/stream', {
        reconnect: false,
        body: {
          problemId: this.problemId, assignmentId: this.assignmentId,
          selectedChannelCode: channel ? channel.channelCode : (this.model === 'cloud' ? 'cloud-ds' : 'local-qwen'),
          capsuleStage: this.stage, userMessage: q, currentCode: this.fixed ? this.codeFixed : this.code
        }
      }).on('router_step', p => {
        if (!current()) return;
        status('Router：' + (p.stageName || STAGE_NAMES[p.stage] || '阶段分析') +
          (p.levelDesc ? ' · ' + p.levelDesc : '') + (p.routerModel ? ' · ' + p.routerModel : ''));
        /* Router 描述本次请求，不能覆盖学生新选择的阶段/模型。 */
      }).on('tool_calling', p => {
        if (current()) status('Tool：' + (p.toolName || '工具调用') + (p.summary ? ' · ' + p.summary : ''));
      }).on('fallback_notice', p => {
        if (current()) status('降级：' + (p.msg || p.reason || ((p.fromChannel || '') + ' → ' + (p.toChannel || '备用通道'))));
      }).on('token', p => {
        if (!current()) return;
        reply.answer += String(p.delta || ''); this.renderReply(reply);
      }).on('guardrail_rewrite', p => {
        if (!current()) return;
        reply.answer = String(p.replacementText !== undefined ? p.replacementText : (p.replacedWith || ''));
        status('护栏：' + (p.reason || p.guardReason || '回复已改写'));
      }).on('done', p => {
        if (!current()) return;
        completed = true;
        this._quotaVersion++;
        if (typeof p.remainingQuota === 'number' && !this.quotaUnlimited) this.quotaLeft = Math.max(0, p.remainingQuota);
        reply.recordId = p.recordId || null;
        if (p.actualModelUsed) status('完成：' + p.actualModelUsed);
        stop();
      }).on('error', p => {
        if (!current() || completed) return;
        completed = true;
        status('回复中断：' + (p.message || '连接失败，请手动重试'));
        stop();
        /* 429 也可能是并发繁忙，403 也可能只拒绝本次输入/阶段。
           配额与全局开放状态只由 GET quota 的权威快照更新。 */
        if (this.live) this.refreshQuota();
      }).on('end', () => {
        if (!current() || completed) return;
        completed = true;
        status('连接已结束，回复未完成；请手动重试');
        stop();
        if (this.live) this.refreshQuota();
      });
      this.scrollChat();
    },

    /* ── 题面宽度拖动 ── */
    startDrag(e) {
      this.dragging = true;
      this.dragStartX = e.clientX;
      // problemW 为 0 时栏宽由 CSS 媒体查询决定，先从实际渲染宽度接管
      const el = document.querySelector('.problem');
      this.dragStartW = this.problemW || (el ? Math.round(el.getBoundingClientRect().width) : 392);
      e.preventDefault();
    },
    onDragMove(e) {
      if (!this.dragging) return;
      const w = this.dragStartW + (e.clientX - this.dragStartX);
      this.problemW = Math.max(300, Math.min(560, w));
    },
    stopDrag() {
      this.dragging = false;
    },
    seamKey(e) {
      const step = e.shiftKey ? 40 : 12;
      const el = document.querySelector('.problem');
      const cur = this.problemW || (el ? Math.round(el.getBoundingClientRect().width) : 392);
      if (e.key === 'ArrowLeft') { this.problemW = Math.max(300, cur - step); e.preventDefault(); }
      if (e.key === 'ArrowRight') { this.problemW = Math.min(560, cur + step); e.preventDefault(); }
    },
  };
}
