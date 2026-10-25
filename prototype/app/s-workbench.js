/* 学生做题工作台 · 交互
   判题链带逐格点亮 → 失败格抖动落定；抽屉开合；思考过程折叠；
   模型分段切换；行内批注胶囊；阶段窄轨 4 段；
   丢分点三处联动（判分链 #3 ↔ 代码第 8 行 ↔ 抽屉「丢分定位」）。
   状态集中在一个 workbench()，初始态读 ?state= */

const CASE_LABELS = ['样例', '样例', '全负', '边界', '全正', '混合', '大数据', '边界', '随机', '极限'];
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
    runState: 'idle',        // idle | running | done
    lit: 0,                  // 判题中已跑完的测试点数
    chat: [],
    replying: false,
    askText: '',
    quotaLeft: 2,
    problemW: 0,             // 0 = 跟随 CSS 的响应式栏宽；拖动/按键后才变成定值
    dragging: false,
    intro: true,
    lossHover: '',           // 悬停中的丢分点位置：chain | code | pin
    lossPin: '',             // 点击锁定的丢分点位置

    /* ── 派生 ── */
    get judging() { return this.result === 'judging'; },
    get stage4Unlocked() { return this.result === 'ac'; },
    get stageLabel() { return STAGE_NAMES[this.stage]; },
    get askPlaceholder() { return this.judging ? '判题中，先等链带跑完…' : '追问 AI 助教…'; },
    // 丢分点是否仍然成立：WA 且尚未应用行内修正
    get lossLive() { return this.result === 'wa' && !this.fixed; },
    // 当前主高亮位置：悬停优先，其次点击锁定
    get lossMain() { return this.lossLive ? (this.lossHover || this.lossPin) : ''; },

    /* ── 初始化 ── */
    init() {
      const p = new URLSearchParams(location.search);
      const s = p.get('state') || 'default';
      if (s === 'hint') { this.hintOpen = true; }
      if (s === 'think') { this.think = true; }
      if (s === 'judging') { this.result = 'judging'; this.lit = 5; this.stage = 3; }
      if (s === 'ac') { this.result = 'ac'; this.fixed = true; this.stage = 4; }
      if (s === 'locked') { this.stage = 4; }
      if (s === 'closed') { this.drawer = false; }
      if (s === 'cloud') { this.model = 'cloud'; this.think = true; }
      if (s === 'stage1') { this.stage = 1; }
      if (s === 'stage2') { this.stage = 2; }
      if (s === 'loss') { this.lossPin = p.get('loss') || 'chain'; }   // 丢分点联动锁定态（截图用，可加 &loss=code|pin）

      setTimeout(() => { this.intro = false; }, 1400);

      window.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
          if (this.hintOpen) this.hintOpen = false;
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
      this.lit = 0;
      this.hintOpen = false;
      this.chat = [];
      this.quotaLeft = 2;
      this.lossHover = '';
      this.lossPin = '';

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
              setTimeout(() => { this.stage = 4; }, 420);
            }
          }, 300);
        }
      };
      setTimeout(tick, 420);
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
      this.lossHover = '';
      this.lossPin = '';
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

    /* ── 丢分点联动 ──
       判分链第 3 格 / 代码第 8 行 / 抽屉「丢分定位」是同一个丢分点：
       悬停任一处 → 三处一起亮；点击任一处 → 锁定，再点取消。 */
    lossIs(pos) { return this.lossLive && this.lossMain === pos; },
    lossPeer(pos) { return this.lossLive && !!this.lossMain && this.lossMain !== pos; },
    lossAttr(i) { return i === 3 && this.lossLive ? 'p3' : null; },
    lossTag() { return this.lossLive ? 'p3' : null; },
    lossEnter(pos) { if (this.lossLive) this.lossHover = pos; },
    lossLeave() { this.lossHover = ''; },
    lossToggle(pos) { if (this.lossLive) this.lossPin = this.lossPin === pos ? '' : pos; },

    /* ── 链带格子 ── */
    cellClass(i) {
      if (this.judging) return i <= this.lit ? 'lit' : '';
      if (this.result === 'wa' && i === 3) {
        return 'fail' + (this.lossIs('chain') ? ' loss-main' : this.lossPeer('chain') ? ' loss-peer' : '');
      }
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
