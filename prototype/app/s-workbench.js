/* 学生做题工作台 · 交互
   修错闭环一条线：提交 → 逐格点亮 → WA 失败格抖动 + callout → 代码第 8 行 /
   判分链第 3 格 / 抽屉「丢分定位」三处联动高亮 → 学生点「改成 −10⁹」→ 重新提交
   → AC 扫绿光 + 分数滚动 + 窄轨 ④ 解锁。

   状态集中在一个 workbench()，初始态读 ?state= */

const CASE_LABELS = ['样例', '样例', '全负', '边界', '全正', '混合', '大数据', '边界', '随机', '极限'];

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
   替代：api.sse('/api/ai/chat/stream', ...) 的六事件链（阶段 2 接入）。
   Stage 0 阶段先用 api/mock.js 的 MOCK_AI_REPLIES 兜底，保证断网演示不断裂。 */


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
    lit: 0,                  // 判题中已跑完的测试点数
    chat: [],
    replying: false,
    askText: '',
    /* PRD v2.0 微创修正 #4 / 03 指南 §5.3：配额只能由服务端裁决。
       前端自减 = 用户改一行 JS 就无限用；submit() 重置配额 = 提交一次白送一次
       AI 机会，且这个重置没有任何业务含义。故：
         · 进工作台读 GET /api/ai/workspace-init 的 remainingQuota / maxQuota
         · 每次对话结束只由 SSE done 事件写 remainingQuota（唯一写入口）
         · 配额耗尽由 error(429) 置 0 并禁用输入框
         · submit() 不碰 quotaLeft
       Stage 0 阶段 workspace-init 尚未实现，先取 mock 契约值 2/3 占位。 */
    quotaLeft: 2,
    quotaMax: 3,
    problemW: 0,             // 0 = 跟随 CSS 的响应式栏宽；拖动/按键后才变成定值
    dragging: false,
    intro: true,

    /* 联动高亮：三处（判分链第 N 格 / 代码对应行 / 抽屉丢分定位）共享这一个数。
       0 = 没有聚焦，鼠标移出即归零。 */
    focusCase: 0,

    /* 底部判分带两档：收起 40px 摘要 / 展开 143px 完整链条 */
    bandOpen: false,
    scoreNum: 80,            // 分数滚动用的中间值，渲染时取整（见 get score）

    /* ── 派生 ── */
    get judging() { return this.result === 'judging'; },
    get stage4Unlocked() { return this.result === 'ac'; },
    get stageLabel() { return STAGE_NAMES[this.stage]; },
    get askPlaceholder() { return this.judging ? '判题中，先等链带跑完…' : '追问 AI 助教…'; },
    // 分数：整数渲染 + body 上的 tabular-nums，滚动时数字不会左右抖
    get score() { return Math.round(this.scoreNum); },
    // 丢分点是否仍然成立：WA 且尚未应用行内修正
    get failLive() { return this.result === 'wa' && !this.fixed; },
    /* PRD v2.0 微创修正 #4：focusLine getter 已删除（见文件头注释）。
       联动高亮只剩 focusCase 一个维度，颜色由 body[data-focus] 统一切换。 */
    // 配额是否耗尽（驱动输入框 disabled 与发送按钮禁用态）
    get quotaEmpty() { return this.quotaLeft <= 0; },

    /* ── 初始化 ── */
    init() {
      const p = new URLSearchParams(location.search);
      const s = p.get('state') || 'default';
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
    },

    /* ── 提交判题 ── */
    submit() {
      if (this.judging) return;
      this.result = 'judging';
      this.bandOpen = true;          // 判题时把链条展开，让逐格点亮可见
      this.lit = 0;
      this.hintOpen = false;
      this.chat = [];
      /* PRD v2.0 微创修正 #4：原 this.quotaLeft = 2 已删除。
         提交判题不该重置 AI 配额——配额由服务端按作业模式与逾期状态裁决，
         前端任何重置都是凭空造次数。 */
      this.focusCase = 0;

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
        this.scoreNum = from + (to - from) * ease(p);
        if (p < 1) requestAnimationFrame(step);
        else this.scoreNum = to;
      };
      requestAnimationFrame(step);
    },

    /* ── 运行样例 ── */
    runSample() {
      if (this.runState === 'running') return;
      this.runState = 'running';
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
      this.stage = n;
      this.drawer = true;
    },
    stageState(n) {
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

    /* ── 链带格子 ── */
    cellClass(i) {
      if (this.judging) return i <= this.lit ? 'lit' : '';
      // WA 时只有失败的那一格变红；其余是「通过」的中性蓝
      if (this.result === 'wa' && i === 3) return 'fail';
      return '';
    },
    cellSub(i) {
      if (this.judging) return i <= this.lit ? '通过' : '待判';
      return CASE_LABELS[i - 1] || '';
    },

    /* ── 追问 ──
       PRD v2.0 微创修正 #4 / 03 指南 §5.2+§5.3：
         · 删掉本地自减 this.quotaLeft（配额只能由服务端裁决）
         · 删掉 REPLIES{1..9} 硬编码回复，改走 api.sse() 的六事件链
       阶段 2 接入真实 SSE 后，这里换成：
         this.sseHandle = api.sse('/api/ai/chat/stream', {...}, {
           router_step: ..., tool_calling: ..., fallback_notice: ...,
           token: ..., guardrail_rewrite: ..., done: d => this.quotaLeft = d.remainingQuota
         });
       Stage 0 期间用 api/mock.js 的 MOCK_AI_REPLIES 兜底，保证断网演示不断裂——
       但 mock 回复同样是「按问题关键字挑选」而非固定一条，不会出现两问一答。 */
    ask(text) {
      const q = String(text == null ? this.askText : text).trim();
      if (!q || this.replying || this.quotaEmpty) return;
      this.chat.push({ role: 'me', text: q });
      this.askText = '';

      this.replying = true;
      const mock = (window.api && api.mockAiReply) ? api.mockAiReply(q, this) : null;
      setTimeout(() => {
        this.chat.push({ role: 'ai', text: mock || '（AI 助教服务未就绪，请确认 oj-ai-agent-service 已启动）' });
        this.replying = false;
        this.$nextTick && this.$nextTick(() => {
          const box = document.querySelector('.ai-scroll');
          if (box) box.scrollTop = box.scrollHeight;
        });
      }, 820);
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
