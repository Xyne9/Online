# 智判 OJ · 全站原型

毕业设计配套的高保真交互原型。三端（学生 / 教师 / 管理）共 **15 个功能页面 + 1 个门户总览**，全部为可直接打开的静态 HTML，无需构建、无需服务器、无外网依赖。

**入口：`index.html`**（双击即可，或 `python -m http.server` 后访问）。

---

## 一、怎么走一遍

`index.html` 是原型总目录，三张角色卡 + 按角色分组的全部页面清单，点任意一项直达。

| 角色 | 建议路线 |
| --- | --- |
| 学生 | `s-problems`（题库）→ 点任一题目「开做」→ `s-workbench`（做题工作台）→ `s-submissions`（提交记录）→ `s-analytics`（学情分析）→ `s-exam`（考试作答） |
| 教师 | `t-dashboard`（班级概览）→ `t-problems`（题目管理）→ `t-problem-edit`（出题编辑）→ `t-assignments`（教学活动）→ `t-analytics`（班级学情）→ `t-monitor`（考试监考）→ `t-plagiarism`（代码查重） |
| 管理 | `a-models`（模型通道）→ `a-audit`（调用审计与沙箱） |

左侧导航、面包屑、角色切换器由 `shell.js` 按 `<body>` 上的 `data-role` / `data-page` / `data-crumb` 自动注入，**每个页面只写自己的内容区**，所以页面之间可以任意跳转，导航高亮始终正确。

---

## 二、页面清单

### 学生端（6 页）

| 文件 | 页面 | 核心内容 |
| --- | --- | --- |
| `s-problems.html` | 题库列表 | 主角是「继续做题」卡（下一道该做的题 + 迷你判分链）；筛选栏（状态/难度/标签/关键词 + 已启用条件 chips）、6 列紧凑行、行点开题目预览抽屉 |
| `s-workbench.html` | 做题工作台 | **全站最复杂一页**：左题面（可拖拽调宽）+ 中编辑器（行内批注胶囊）+ 右侧 AI 抽屉（模型分段切换 / 思考过程折叠 / 四阶段面板）+ 底部 40px 判分摘要条（点开看 10 格全宽判分链）+ 最右 64px 阶段窄轨 |
| `s-assignments.html` | 我的作业 | 三格扁平统计带 + 状态 chip 筛选（带计数与空态）、搜索、截止时间排序、作业详情抽屉、分页 |
| `s-submissions.html` | 提交记录 | 主角是「每一次提交的判分链」（列表每条带迷你判分链）；状态/语言/时间三重筛选、搜索、代码回看抽屉、AI 诊断跳转 |
| `s-analytics.html` | 学情分析 | 主角是首屏 AI 学情解读 + 薄弱点大卡；时间范围切换（7/30/90 天全联动）、标签能力下钻、只看薄弱开关、薄弱点抽屉（症状/根因/建议/推荐练习） |
| `s-exam.html` | 考试作答 | 三栏锁满一屏、倒计时、题目导航、禁止 AI 提示、交卷确认、判分链回显 |

### 教师端（7 页）

| 文件 | 页面 | 核心内容 |
| --- | --- | --- |
| `t-dashboard.html` | 班级概览 | **全站版式样板**：「需要你处理」待办主卡（行内动作，处理完原地划掉收起）+ 本周通过率 30px 次级数字 + 易错排行 / 进行中活动 / AI 价值证明 |
| `t-problems.html` | 题目管理 | 题目列表（草稿/已发布/已归档）、筛选、批量操作、状态切换、行内迷你判分链 |
| `t-problem-edit.html` | 出题编辑 | 主角是 10 格测试点判分链（提到基本信息之后）；题面编辑器、样例用例、判题配置、标签、AI 策略（阶段开关 + 提示上限） |
| `t-assignments.html` | 教学活动 | 主角是「还要处理哪几项活动」（进行中/待发布置顶，带完成率进度）；发布/编辑抽屉、AI 权限配置（练习全开 / 作业仅①③ / 考试全关） |
| `t-analytics.html` | 班级学情 | 主角是班级薄弱点 + AI 解读卡；题目掌握度矩阵、学生排名、薄弱标签分布 |
| `t-monitor.html` | 考试监考 | 主角是「需要关注」的异常学生卡组（左 3px 状态条 + 红色状态字）；实时事件流、考场概况、强制交卷、结束考试 |
| `t-plagiarism.html` | 代码查重 | 主角是两份代码的并排比对；两阶段查重（AST 75% / 语义向量 85%）、可疑对列表、判定操作 |

### 管理端（2 页）

| 文件 | 页面 | 核心内容 |
| --- | --- | --- |
| `a-models.html` | 模型通道 | 主角是通道健康主带（哪条出问题、要不要现在处理）；4 条通道（本地 Ollama / DeepSeek / 通义千问 / 智谱）启停、超时降级配置（8 秒）、权重、连通性测试 |
| `a-audit.html` | 调用审计与沙箱 | 主角是护栏命中主带（拦了几次 + 命中的是什么）；AI 调用全链路留痕、Token 用量、判题沙箱容器健康 |

---

## 三、文件结构

```
prototype/app/
├── index.html                门户总览（不套外壳）
├── index.css                 门户专用样式
├── _SPEC.md                  并行开发契约（页面职责 / 共享 API / 数据口径）
│
├── s-*.html / t-*.html / a-*.html     15 个功能页
├── s-workbench.js            工作台专属 Alpine 组件（判题时序 / AI 对话 / 拖拽）
│
├── shell.js                  外壳脚本：图标 sprite + 导航注入 + 共享模拟数据（window.OJ）
├── styles/
│   ├── tokens.css            设计变量（颜色 / 字号 / 间距 / 圆角 / 动效时长）
│   ├── shell.css             左侧导航 + 顶栏 + 页面容器
│   ├── components.css        通用组件：按钮 / 标签 / 表格 / 抽屉 / 弹窗 / 空态
│   ├── pages.css             跨页复用的版式块：列表行 / 筛选条 / 时间线 / 迷你图
│   └── workbench.css         做题工作台专属样式
│
├── vendor/alpine.min.js      本地 Alpine（无 CDN）
└── _shots/                   截图产物（设计迭代 / 多分辨率复核留档）
    ├── final/                最终全站基准图：1440×900，16 张单页 + 并排图 sheet.png + report.json
    ├── final-1920/           同上一套，1920×1080 回归
    ├── final-1280/           同上一套，1280×800 回归
    ├── ship/ ship-1920/      上一轮的基准图（保留作三轮整改的前后对照）
    ├── p1/                   上一轮的过程证据（像素联动验证 / Playwright 脚本）
    └── iter1/ workbench/ lists/ admin/ ... 本轮各 worker 的过程截图（改前/改后/状态图）
                              —— 单轮就几百张、几十 MB，已在 .gitignore 排除，只留在本地
```

页面写法统一为：

```html
<body data-role="teacher" data-page="t-problems" data-crumb="教学|题库管理">
  <div id="app"><main class="page" id="page-content"> …只写这一页的内容… </main></div>
  <script src="shell.js" defer></script>
  <script src="vendor/alpine.min.js" defer></script>
</body>
```

---

## 四、设计口径

全站共用一套设计语言，取值只来自 `styles/tokens.css`，页面里不写裸色值。`index.css` / `components.css` / `shell.css` / `pages.css` / `workbench.css` 五个样式表的裸 hex、裸 `font-size:<n>px`、裸 `border-radius:<n>px` 与裸过渡时长已全部清零（`.html` 内联样式里的裸圆角也一并收口），新增样式一律走变量。

- **圆角** 按「同心」关系分档：`--r-hair` 1px（发丝：中缝把手、刻度小条）/ `--r-chip` 2px（微标：批注角标、进度条）/ `--r-xs` 3px（代码内联底）/ `--r-sm` 4px / `--r-ctl` 5px（控件：图标按钮、分段项）/ `--r` 6px / `--r-seg` 7px（分段控件外槽，比内层大 2px）/ `--r-lg` 12px / `--r-pill` 999px；门户页另有 `--r-logo` 8px、`--r-tile` 14px（只给 `index.css` 的角色卡与 logo 用，功能页不取）。圆形一律写 `50%`，不走档位。卡片这一档从 10px 提到 12px：1440 宽下卡片普遍 520–880px，10px 圆角配 1px 边框显得「方」，提到 12px 后与 5px 的控件档拉开层次

- **主色** `#2563EB`（蓝），判题语义色：AC 绿 `#15803D` / WA 红 `#DC2626` / TLE 橙 / MLE 紫 / RE 粉 / CE 灰
- **字号** 按用途分档，不按大小硬排，相邻两档至少差 2px：
  - **界面正文 14px**（`--fs-md`）——按钮、表格、导航、表单
  - **阅读型正文 14px**（`--fs-read`）——题面、AI 回复、说明段落，行高 1.75–1.8。题面用 13px 会让人读着累，这一档是专门为「连续读几百字」设的。落地位置：`.prob-body`/`.spec-*`/`.ai-body`/`.hb-txt`/`.scoring p`/`.note`/`.ai-note`/`.hero-note`/`.hero-cal`/`.aival-note`/`.eb-s`/`.sb-s`/`.pv-stmt`/`.ph-sub`（页头副标题，全站 13 页）
  - **次要正文 13px**（`--fs-sm`）——表格单元格、密集列表
  - **元信息 12px**（`--fs-xs`）——表头、副标题、辅助说明
  - **层级** `--fs-lg` 16（卡片/区块标题）/ `--fs-xl` 22（页面标题）/ `--fs-2xl` 30（关键数字）
  - **`--fs-display` 44px** 是特例：**一屏只允许一个**，给页面主角数字用（`t-dashboard` 的「本周通过率」、`s-problems` 的「继续做题」、`t-monitor` 的实考人数等，一页仅一次）。没有主角的页面不要用
  - **11px 那档（`--fs-2xs`）只给等宽小字**：代码行号、判分链里的等宽元信息、`.tag` 微标。不给中文正文，因为 11px 中文在 100% 缩放下已经难认
  - 上一版的 `--ink3 #55637A` / `--ink4 #64748B` 差 4 个亮度单位、肉眼分不出，已合并为一级（`--ink4` 保留为旧名别名）；另补 `--ink-dim #94A3B8` 给占位符、禁用态、省略号——文字色因此是**三级实色 + 一级虚线色**
- **页面分量三级**（样板：`t-dashboard.html`）——一屏内只允许出现三级，同级元素不许互相抢：
  1. **主角**：一个 44px 数字 + 一句人话结论 + 最要紧的操作入口
  2. **统计带**：一条扁平分隔带（30px 数字），**不套卡片**，放次级数字
  3. **明细面板**：卡片 + 表格 + 图表，放「点进去才看」的内容
  改版前 `t-dashboard` 是五格统计条 + 六个同构面板，全部同分量，页面没有主角；新页面照这个三级来排。**16 页已全部按这一版式重排**：每页只有一个 44px 主角，主角之后才轮到统计带和面板。上一版只有 `t-dashboard` 有主角。
  **照着做（骨架都已在共享层，不需要写新 CSS）**：
  1. `<div class="page-head"><h2>页名</h2><p class="ph-sub">一句话说清这页在看什么</p><div class="ph-act">主操作</div></div>`
  2. `<div class="hero"><div class="hero-main"><div class="hero-l">…`.hero-v` 主角数字 + `.hero-note` 结论…</div><div class="hero-r">…趋势/图例…</div></div><div class="hero-side">…最要紧的待办…</div></div>`
  3. `<div class="mini-stats"><div class="mini-stat">…</div>×3</div>`（**三格**，不要五格；这一层不套卡片）
  4. `<div class="split"><div class="lcol">明细面板</div><div class="rcol">辅助信息</div></div>`，面板用 `.panel` + `.panel-h h3`（16px）+ `.panel-b`
  内联 `<style>` 只写本页独有的内容块（当前 15 页合计约 2.4k 行，最大一页 `s-analytics` 247 行，`t-dashboard` 136 行）；`--fs-display` 一页最多一次；写完后把「同一屏里同一个数字出现两次」逐条排掉——**不同含义的数字撞同一个值也算重复**（Hero 的 62% 与云端占比 62% 就犯过这个错）
- **布局** 左侧导航 216px + 顶栏 48px + 内容区；列表页 `max-width` 约束，工作台与考试页锁满一屏
- **动效** 统一缓动 `--ease`，时长按四级刻度取：`--dur-1` 120ms（悬停/按下/颜色）、`--dur-2` 200ms（下拉/切换）、`--dur-3` 280ms（抽屉/展开）、`--dur-4` 480ms（页面级/图表生长）；`prefers-reduced-motion` 下全部关闭
- **跨页过渡**（`tokens.css` 末尾）——原型是多份 HTML，同源跳转时用 View Transition：侧栏 `.side` 与顶栏 `.topbar` 按 `view-transition-name` 保持不动，只有内容区 180ms 上浮淡入。不支持该特性的浏览器（Firefox）直接跳转，不影响功能。写在 `tokens.css` 而不是 `shell.css`，是因为做题工作台不加载 `shell.css` 但也要有过渡
- **页面进场与跨页过渡不能叠加**——`components.css` 的 `.page > *` 有一条 `rise` 错峰淡入（0/40/80/120ms，每块 200ms），只在**首次打开**时跑。跨文档过渡进来的页面由 `shell.js` 在 `pagereveal` 里给 `<html>` 加 `.vt-in`，`components.css` 据此关掉 `rise`——两层透明度相乘会让内容先白一下。**别用 `:active-view-transition` 做这个判断**：过渡一结束选择器就不再匹配，`rise` 会从头再播一遍，变成「正常 → 空白 → 再淡入」的闪回。同理 `rise` 的选择器必须写成 `.page > :where(:not(template)):where(:not(:has(…)))`，`/not()` 里的内容会算特异性，直接写会把下面 `:nth-child` 的分档延迟全压掉
- **图标** 全部为 24×24 网格 1.5 描边线性 SVG（`shell.js` 里的 `ICONS` sprite，用 `x-ic="'名字'"` 引用），不用 emoji
- **彩色标签不带描边**——`.tag` 系列（`.tag.blue/.ac/.wa/…`）只有浅底，`border-color` 设为 `transparent`。理由：满屏「浅底 + 一圈同色细边」是原始评审里「看着碎」的来源之一，去掉描边后彩色标签靠底色区分，页面纵向只剩卡片一条实线
- **难度不用判题色**——`.lvl` 是五格中性刻度（`shell.js` 的 `OJ.lvl()` 生成），不用蓝/红/橙去标「简单/较难」。判题语义色（AC 绿、WA 红、TLE 橙…）在全站只表示判题结果，难度借色会让人误读。代价是列表里难度不再一眼可辨，全灰
- **`animation:` 里的裸时长是允许的**——「裸过渡时长已清零」只针对 `transition:`；`animation:` 的时长按动画语义各自取值（`.7s` 旋转、`2s` 脉冲、`.48s` 图表生长、逐格错峰的 `40–60ms`），不进 `--dur-*` 四级刻度。`--dur-1..4` 管的是「状态切换」这类可预期动效

---

## 五、数据口径（全站一致，避免同页出现两个数）

| 项 | 取值 | 用在哪 |
| --- | --- | --- |
| 学生 | 林昭 · 计算机 2022 级 1 班 | 学生端全部页面 |
| 班级规模 | 48 人 | 教师端 / 管理端 |
| 本学期提交 | 47 次（AC 32 / 未通过 15），AC 率 **68%** | `s-submissions` / `s-analytics` |
| 平均尝试 | 2.3 次（全站均值 3.1 次） | `s-analytics` |
| 本周提交通过率 | **62%**（194 / 312，按提交次数加权） | `t-dashboard` Hero 主角 |
| 班级平均 AC 率 | **62%**（按学生算术平均，`t-analytics` 实测同为 62） | 学生端副标题 / 班级学情 |
| AI 阶段 | ①读题拆解 ②编码引导 ③排错诊断 ④AC 复盘 | 全站 |
| 阶段锁 | ①②随时可用；③需非 AC 提交；④需 AC 提交 | 工作台 |
| 模型通道 | 本地 Qwen2.5-Coder 7B / 云端 DeepSeek / 通义千问 / 智谱 | 工作台 / 管理端 |
| 降级阈值 | 云端超时 **8 秒** → 自动降级本地 Ollama | 工作台 / `a-models` |
| 护栏 | 单行 API 或中文伪代码 ≤5 行；>5 行完整解题代码即拦截重写 | 工作台 / `a-audit` |
| 查重 | AST 结构相似度 75% / 语义向量 85% | `t-plagiarism` |
| AI 权限 | 练习全开不限次；作业仅①③且每题上限 3 次；考试全关（403） | `t-assignments` |
| 判题时限 | C/C++ 1000ms / 256MB；Java、Python ×2 | 工作台题面 |

---

## 六、校验结果

全站 16 个页面均通过截图自检（工具：`oil-ui-pro` skill 的 `shoot.mjs`）。基准截图在 `_shots/final/`（16 张单页 + 全站并排图 `sheet.png` + `report.json`）；另有两档回归截图 `_shots/final-1920/`、`_shots/final-1280/`。

| 档位 | 覆盖 | 结果 |
| --- | --- | --- |
| 1440×900 | 全 16 页 | 无控制台错误、无横向溢出、无加载失败图片；`issues` 全 0，lint 只剩 `sourceSplit` 与 `sideStripe` 两类（见下） |
| 1920×1080 | 全 16 页 | 同上；lint 与 1440 档逐条一致 |
| 1280×800 | 全 16 页 | 同上；lint 与 1440 档逐条一致（本轮已无 `smallText`） |
| 1240 / 1080 | 全 16 页（Playwright 逐页量 `scrollWidth - clientWidth` 与元素裁切） | 横向溢出 0；Hero 与统计带正常降级；`.prob-info` 在 1080 档降为单列，无文字裁切 |
| 390×844 | 学生端三页 | `s-problems` / `s-workbench` 无横向溢出；`s-exam` 溢出 300px（题面与输出区双栏定宽，`git archive HEAD` 旧版同样溢出，非本轮引入）——**移动端未做适配，原型按桌面评审** |

另外用 Playwright 脚本 `_shots/p1/final-audit.py` 复核过：16/16 页 `scrollWidth - clientWidth = 0`、`pageerror` 与 `console.error` 均为 0、本地 `href`/`src` 引用**无断链**（静态引用 142 处，含 `:href` 动态拼接的 19 处按运行时变量判定，不参与静态断链检查）。

**进场动效的实测结论**（真实 Chrome 151，`channel="chrome"`）：首次加载时 `.page` 前四块 `animation-delay` 依次为 0 / 0.04s / 0.08s / 0.12s、`animation-duration` 0.2s；跨页跳转时 `pagereveal.viewTransition === true`，`<html>` 带上 `.vt-in`，四块 `animationName` 全部为 `none`、`opacity` 恒为 1（修复前此处是「过渡结束后 `rise` 从 0 重播」，最小净不透明度 0.33）。

### 剩下两类 lint 提示及其理由

**`sourceSplit`（6 页 + 1 个样式表，本地文件较大）**：`a-audit` 52 KiB / `t-analytics` 53 KiB / `s-analytics` 49 KiB / `t-problem-edit` 44 KiB / `s-submissions` 42 KiB / `t-plagiarism` 41 KiB / `workbench.css` 56 KiB。原型刻意维持「一页一文件、双击即开」的形态（见已知取舍 2），拆成多个 `.js`/`.css` 会引入 `file://` 下的相对路径依赖；这批页面又不进构建流程，拆分收益低于断链风险，故暂不拆。

**`sideStripe`（3 处语义色条，非装饰）**：

1. **`t-monitor` 12 处**（`button.acard` 的 `::before`）——这是 design-review 明确要求的改法：改造前 11/48 张卡整张填粉，画面躁；现在改为**左侧 3px 色条 + 红色状态字**，整卡填色只留给「断线」。色条是「异常/断线」的状态编码，且同屏 12 张有 3 张是断线（红）、9 张是切屏异常（橙），色条承担了分组职责。列表卡片本身无边框、无阴影，色条即卡片的存在感来源。
2. **`a-models` 2 处**（`div.hblk.is-deg` / `div.hblk.is-off` 的 `::before`）——通道健康主带里的「降级 / 离线」两条，色条对应通道状态，与正常通道的绿点同一套编码。
3. **`s-workbench` 1 处**（`div.ai-pin` 的 `::before`）——「丢分点联动」的左端淡红竖条，与代码第 8 行、判分链第 3 格共用 `data-loss="p3"` 语义标记，是丢分点的**语义锚点**。

### 交互与可访问性

- 15 个功能页的真实交互数（`@click` + `x-show` + `x-model` 计数）：最少 19 个（`t-dashboard` / `s-exam`），最多 65 个（`s-workbench`），中位数约 28（门户页 `index.html` 是纯跳转入口，不计）
- 列表页已补齐三态：`s-problems` / `s-submissions` / `s-assignments` / `t-problems` / `t-assignments` / `t-analytics` / `a-models` / `a-audit` 各有骨架屏（`.sk-row`）+ 空态（`.state`）+ 出错重试（`loadErr` + 「重新加载」）；`t-plagiarism` / `t-monitor` / `s-exam` 是实时或锁屏页，走空态与行内提示，不做骨架屏
- 键盘：`/` 聚焦全局搜索、`?` 打开快捷键表、`Esc` 关闭抽屉/弹窗（`shell.js` 统一处理，捕获阶段拦截，输入框内有焦点时也不吞 Esc）；工作台另有 `Ctrl+'` 运行样例、`Ctrl+Enter` 提交判题
- 「丢分点联动」经 Playwright 实测：悬停三处任一处，主高亮处变化像素占比 54–73%、另两处 1–3%，与「主强调 + 虚线描边」的设计意图吻合
- `prefers-reduced-motion` 下按组件关闭：抽屉/弹窗只留 120ms 淡入、旋转图标从 0.7s 放宽到 2s、页面进场与图表生长全部取消，不再用 `*{animation-duration:.001ms!important}` 一刀切

---
## 七、已知取舍

1. **纯静态 + 模拟数据**。所有数据来自 `shell.js` 的 `window.OJ`，不接后端；判题、AI 回复、查重结果都是前端时序模拟。真实接口契约见仓库根目录的《系统详细设计与接口契约规范》。
2. **不引构建工具**。用 Alpine.js 写在 HTML 属性上，就是为了让每页能单独双击打开、单独评审。迁移到 Vue 3 + Element Plus 的完整对照见 `../workbench/VUE-MIGRATION.md`。
3. **`.page` 不是内部滚动容器**，长页面整页滚动（与题库页、概览页行为一致）。
4. **抽屉（drawer）骨架已在 `components.css`**（`.drawer` / `.drawer-head` / `.drawer-body` / `.drawer-foot` / `.scrim`，见 `components.css:312` 起），各页只写自己的内容排版，不再重复定义抽屉定位与遮罩。
5. **三级分量的骨架已下沉到共享层**：`.hero*` / `.mini-stats` / `.lcol` / `.rcol` 在 `shell.css`，`.aival*` / `.aigrid*` / `.panel-h .sub` / `.vchain` 在 `pages.css` 与 `components.css`，各页直接套用、只写自己独有的内容块。**16 页已全部按三级重排**，不再有「等分量面板」页面。
6. **`s-workbench` 的判题逻辑是简化的**：`submit()` 里 `willPass = this.fixed`，即「改对了就 AC」，真实场景应由后端返回。
7. **`s-workbench` 是唯一不套 `shell.js` 外壳的页面**（自带 44px 顶栏 + 五列 grid，锁满一屏），因为它要独占整屏做判分链带；`s-exam` 则复用外壳，`data-page` 指向 `s-assignments`，因为考试从「我的作业」进入、左侧导航应停在该项。
