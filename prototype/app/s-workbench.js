/* 学生做题工作台 · 交互
   修错闭环一条线：提交 → 逐格点亮 → WA 失败格抖动 + callout → 代码第 8 行 /
   判分链第 3 格 / 抽屉「丢分定位」三处联动高亮 → 学生点「改成 −10⁹」→ 重新提交
   → AC 扫绿光 + 分数滚动 + 窄轨 ④ 解锁。

   状态集中在一个 workbench()，初始态读 ?state= */

const CASE_LABELS = ['样例', '样例', '全负', '边界', '全正', '混合', '大数据', '边界', '随机', '极限'];

/* 第 N 号测试点主要考的是哪一行代码——联动高亮靠它反查。
   真实判题系统不会给这种映射，原型里它是「让格子看起来在说同一件事」的叙事道具：
   全负 / 边界考的是 ans 的初值（第 8 行），大数据考的是循环上限（第 9 行），
   混合 / 随机考的是转移（第 12 行），全正考的是取最大（第 13 行），样例走输出（第 15 行）。 */
const CASE_LINE = { 1: 15, 2: 15, 3: 8, 4: 8, 5: 13, 6: 12, 7: 9, 8: 8, 9: 12, 10: 9 };
/* 反向：鼠标进代码行时点亮哪一格（同一行被多个测试点考到时取编号最小的） */
const LINE_CASE = { 8: 3, 9: 7, 12: 6, 13: 5, 15: 1 };

const STAGE_NAMES = ['', '① 读题拆解', '② 编码引导', '③ 排错诊断', '④ AC 复盘'];

const REPLIES = {
  1: '题面里「至少要包含一个元素」这句是硬约束。空段的和是 0，如果允许空段，全负数组的答案就会变成 0，和题意冲突。',
  2: 'n 到 10⁵，平方级是 10¹⁰ 次运算，一定超时。O(n log n) 的分治能过，但 O(n) 的 DP 更短，也更好写对。',
  3: '因为「以 i 结尾」这个限制让转移只依赖前一项：接上前面那段（dp + x），或者从 x 重新开始。取两者较大值就是转移方程。',
  4: '行。dp 只依赖上一轮的值，所以不需要数组，两个变量滚动更新就够，空间从 O(n) 降到 O(1)。',
  5: 'ans 是「所有 dp 里的最大值」。如果把它也初始化成 0，全负数组里所有 dp 都是负的，ans 就永远停在 0，取不到正确答案。',
  6: '先把边界列出来：全负、只有一个元素、n 取到 10⁵、答案刚好跨过整个数组。这四类各自手算一遍再对照输出。',
  7: '快不了了。至少要把 10⁵ 个数读进来一遍，下界就是 O(n)。',
  8: '乘积那道题要同时维护最大值和最小值，因为负数乘负数会翻正；求和没有这个问题，一个 dp 就够。',
  9: '因为 dp 的定义就是「以 i 结尾」，它把历史信息压缩成了一个数。答案不一定是最后一项结尾，所以要单独用 ans 记录过程中的最大。',
};

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
    quotaLeft: 2,
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
    // 当前聚焦的格子在代码里对应哪一行
    get focusLine() { return this.focusCase ? (CASE_LINE[this.focusCase] || 0) : 0; },

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
      this.quotaLeft = 2;
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

    /* ── 三处联动高亮 ──
       判分链第 N 格 / 代码中该测试点对应的那一行 / 抽屉「丢分定位」卡，
       共用 focusCase 一个状态：谁被悬停或聚焦，另外两处同时加 .is-focus。
       颜色由 body[data-focus] 统一切换——聚焦的是失败点就用红，其余用蓝。 */
    clearFocus() { this.focusCase = 0; },
    focusFromLine(line) { const c = LINE_CASE[line]; if (c) this.focusCase = c; },

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

    /* ── 追问 ── */
    ask(text) {
      const q = String(text == null ? this.askText : text).trim();
      if (!q || this.replying) return;
      this.chat.push({ role: 'me', text: q });
      this.askText = '';
      this.quotaLeft = Math.max(0, this.quotaLeft - 1);

      const key = this.fixed ? (this.stage === 4 ? 7 : 6) : 5;
      const pick = REPLIES[key] || REPLIES[1];

      this.replying = true;
      setTimeout(() => {
        this.chat.push({ role: 'ai', text: pick });
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
