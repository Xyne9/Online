# A · 学生端页面契约审计

> 审计对象：`prototype/app/` 下 7 个学生端页面 + `shell.js`（共享数据层）+ `_SPEC.md`（共享契约）。
> 方法：逐文件通读源码，字段名一律原样抄写（不改大小写、不翻译）。所有结论后附 `文件名:行号`。
> 前提事实：**全站没有任何 `fetch` / `XMLHttpRequest` / `EventSource` / `WebSocket`**，所有数据来自 `shell.js` 的内存常量与本页 `<script>` 里的本地常量；所有"异步"都是 `setTimeout` 假装的。

---

## 页面：index.html

### 1. 页面职责
原型总览门户：一张静态地图，把学生端 6 页、教师端 7 页、管理端 2 页的入口列出来，并展示一段"判分链 + WA"的设计母题。

### 2. 该页读取的数据（读操作）

**本页不引用 `window.OJ`：全文没有 `<script>` 标签（无 `shell.js`、无 `alpine.min.js`），也没有 `x-data`。页面没有任何数据契约。**

| 数据项 | 字段（组件里实际用的属性名，逐个列全） | 语义/来源 |
|---|---|---|
| 顶栏站点元信息 | 字面量 `3 角色端`、`15 功能页`、`4 AI 阶段`、`2 查重阶段`、`4 模型通道` | 写死在 HTML（index.html:175-179），无变量 |
| 版本号 | 字面量 `v2.0` | 写死（index.html:181） |
| 判分链示例 | 2 个 `<i class="ac">` + 1 个 `<i class="wa">` + 7 个 `<i class="skip">` | 写死的 10 格 DOM 结构（index.html:206-211） |
| 判题结果示例 | 字面量 `WA`、`测试点 #3`、`用时 12ms` | 写死（index.html:213-216） |
| Hero 文案 | 字面量标题/副标题 | 写死（index.html:189-198） |
| 学生端页面清单 | 6 个 `<a href>`：`s-problems.html`、`s-workbench.html`、`s-assignments.html`、`s-submissions.html`、`s-analytics.html`、`s-exam.html` | 写死的目录卡（index.html:282-323） |
| 教师端 / 管理端页面清单 | 7 个 + 2 个 `<a href>`（`t-dashboard.html` … `a-models.html`） | 写死（index.html:330-378、385-398） |

### 3. 该页写入/触发的动作（写操作）

| 动作（用户点的是什么、方法名） | 期望副作用 | 当前是否只是本地模拟 |
|---|---|---|
| 点「进入题库」（index.html:195、225） | 跳 `s-problems.html` | 是（纯 `<a href>`，无请求） |
| 点「#map」锚点（index.html:196） | 页内滚动 | 是（纯锚点） |
| 点教师端入口（index.html:240） | 跳 `t-dashboard.html` | 是（纯 `<a href>`） |
| 点管理端入口（index.html:254） | 跳 `a-models.html` | 是（纯 `<a href>`） |
| 点学生端 6 张页卡（index.html:282-323） | 逐页跳转 | 是（纯 `<a href>`） |

**本页无任何写操作、无表单、无按钮事件。**

### 4. 该页的派生/计算逻辑
无。页面不含 Alpine 表达式，不含任何 `.filter` / `.map` / 运算。判分链的"2 绿 + 1 红 + 7 灰"是手写的 DOM 节点，不存在"由 score 反推"的逻辑。

### 5. 该页的实时性要求
无。无轮询、无 SSE、无 WebSocket、无 `setInterval`。本页**连 `<script>` 标签都没有**（全文 416 行，grep `script|alpine|shell` 零命中），所有跳转都是 `<a href>`，页面不存在任何运行时状态。

### 6. 对接难度初判
**易**：整页是纯静态导航 + 文案，0 个接口、0 个字段；唯一可对接的是"当前登录角色 → 高亮对应入口"，属于 `GET /api/me` 级别的弱需求，甚至可以不做。

---

## 页面：s-problems.html

### 1. 页面职责
题库列表：首屏一张"继续做题"主角卡，下面一张支持状态/难度/关键词筛选与四列排序的 6 列题目表，整行可点开题目预览抽屉。

### 2. 该页读取的数据（读操作）

| 数据项 | 字段（组件里实际用的属性名，逐个列全） | 语义/来源 |
|---|---|---|
| 题目集合 | `OJ.problems`（数组，`OJ.problems[0]` 取主角题，`OJ.problems.length` 取总数） | shell.js:320-345 的 12 条内存常量 |
| 题目主键 | `p.id`（列表 286、开做链接 315、抽屉 353/412、搜索 494） | shell.js PROBLEMS |
| 题目标题 | `p.title`（286、494） | 同上 |
| 难度等级 | `p.lvl`（`OJ.lvl(p.lvl)` 160/297/359、排序 490/497） | 1-5 整数，`OJ.lvlName` 映射中文 |
| 标签 | `p.tags`（`x-for` 162、292、360） | 字符串数组 |
| AC 率 | `p.ac`（164、289、361、497 作为 `rate` 排序键） | 百分比整数 |
| 尝试人数 | `p.tried`（164、288 经 `OJ.fmt`） | 整数 |
| 我的判题状态 | `p.status`（284 状态点、299/304-311 分支、402、465-467） | 取值 `ac` / `wa` / `tle` / `re` / `ce` / `none` |
| 我的最高分 | `p.score`（170、173、299、305、308、393、403、476） | OI 百分制整数 |
| 工具：难度刻度 | `OJ.lvl(lvl)`（160、297、359） | shell.js:473-478 返回 5 格 HTML |
| 工具：难度名 | `OJ.lvlName(l)`（221） | shell.js:470 |
| 工具：万位缩写 | `OJ.fmt(n)`（164、288） | shell.js:509 |
| 工具：状态中文名 | `OJ.statusName(status)`（402） | shell.js:463-466 |
| 工具：状态标签 HTML | `OJ.statusTag(status)`（本页未用；s-submissions.html:314、s-analytics.html:472 用） | shell.js:467-469 |
| 地址参数：状态 | `OJ.qs('status')`（441） | 学情页「去练习」带过来的 `?status=fail` |
| 地址参数：难度 | `OJ.qs('lvl')`（443） | 同上，`?lvl=3` |
| 地址参数：错误态开关 | `OJ.qs('err')`（445） | 评审用，非接口 |
| 地址参数：骨架屏开关 | `OJ.qs('skeleton')`（449-450） | 评审用，非接口 |
| 页面副标题 | 字面量 `数据结构 · 12 道题 · 第五章 动态规划专题（10-26 23:59 截止）` | 写死（136） |
| 主角卡截止文案 | 字面量 `10-26 23:59 截止 · 剩 2 天` | 写死（166） |
| 主角卡"上次停在"时间 | 字面量 `10-24 23:41` | 写死（174） |
| 主角卡"本周新增" | 字面量 `3` | 写死（193） |
| 抽屉题面正文 | 字面量两段题干 + 输入/输出示例 | 写死（364-378） |
| 抽屉题目元信息 | 字面量 `1000ms（Java / Py ×2）`、`256MB`、`10 组 · 含 2 组隐藏`、`OI 按点计分 · 取历史最高` | 写死（382-385） |

### 3. 该页写入/触发的动作（写操作）

| 动作（用户点的是什么、方法名） | 期望副作用 | 当前是否只是本地模拟 |
|---|---|---|
| 点状态分段控件 `@click="status = f.k"`（205） | 按 `all`/`todo`/`done`/`fail` 过滤 | **是**，纯前端 `get filtered`（483-500） |
| 勾难度复选框 `x-model="lvls"`（220） | 多选难度过滤 | **是**（488-491） |
| 输入搜索框 `x-model="kw"`（232） | 按标题/题号模糊匹配 | **是**（492-495） |
| 点列头 `pickSort(k)`（247、249、255、260；实现 502-505） | 按 `id`/`lvl`/`rate`/`score` 升降排序 | **是**（496-498） |
| 点「清空筛选」`clearFilters()`（238、326；实现 506） | 复位三组筛选 | **是** |
| 点整行 / Enter `openDetail(p)`（283；实现 512） | 打开题目预览抽屉 | **是**，`this.detail = p`，**不请求详情** |
| 点「进入做题」`<a :href="s-workbench.html?pid=${detail.id}">`（412、315、177） | 跳工作台 | **是**（纯跳转，无请求） |
| 点「重新加载」`retry()`（334；实现 507-511） | 复位错误态并再等 600ms | **是**，`setTimeout` 假加载 |
| 点分页按钮（338-344） | 翻页 | **未在源码中找到**：三个 `.pg` 按钮无 `@click`，页码 `1`/`2` 写死；`filtered` 无 `slice` 分页 |

### 4. 该页的派生/计算逻辑

| 指标 | 前端算法 | 位置 |
|---|---|---|
| 已通过题数 `solved` | `OJ.problems.filter(p => p.status === 'ac').length` | `get solved` s-problems.html:455 |
| 尝试过题数 `tried` | `OJ.problems.filter(p => p.status !== 'none').length` | `get tried` s-problems.html:456 |
| 我的 AC 率 | `Math.round(solved / tried * 100)` | 模板 s-problems.html:193 |
| 学期进度条宽度 | `solved / OJ.problems.length * 100` | 模板 s-problems.html:190 |
| **判分链 10 格** | `ok = clamp(round(p.score/100*10), 0, 9)` 个绿 + 1 个真实状态格 + 余下灰 | `chain(p)` s-problems.html:473-481（476 行为核心） |
| 测试点通过数 | `Math.round(hero.score/10)` | 模板 s-problems.html:170、173 |
| 各状态计数徽标 | `countByStatus(k)` 按 `all`/`todo`/`done`/`fail` 分支 | s-problems.html:462-468 |
| 状态分段滑块位移 | `statusIdx * 79` | 模板 s-problems.html:202 + `get statusIdx` 457-460 |
| 筛选+排序结果集 | `filtered` getter，`rate` 排序键映射到 `p.ac` | s-problems.html:483-500 |
| 错误文案"HTTP 503" | 写死字符串 | s-problems.html:333 |

> ⚠️ 关键结论：**题库没有逐测试点数据，判分链是用 `score` 反推的**（源码注释 s-problems.html:470-472 明确承认）。同一个函数在 s-analytics.html:947-955 被复制了一遍。

### 5. 该页的实时性要求
**无**。没有轮询、没有 SSE/WebSocket。
唯一的时间行为是假加载：进入页面 `setTimeout(() => { this.loading = false; }, 600)`（449-450），`?skeleton=1` 时把 600ms 换成 60000ms（449）。`retry()` 同样只是再等 600ms（510）。

### 6. 对接难度初判
**中**。
缺的不是接口形状（`GET /api/problems` 即可），而是**字段语义**：`status` / `score` / `ac` / `tried` 四个字段全部是"当前用户 × 该题"的聚合结果，列表接口必须带用户上下文做 LEFT JOIN；同时判分链需要按测试点粒度的结果数组，现在只有 `score` 一个标量。

---

## 页面：s-workbench.html + s-workbench.js

### 1. 页面职责
做题工作台（全屏三栏 + 右侧 AI 抽屉 + 底部判分链带）：左题面、中编辑器、右 AI 四阶段对话、底部 10 格判分结果；提交判题、运行样例、AI 追问都在这一页。

### 2. 该页读取的数据（读操作）

> 这一页**不加载 `shell.js`**（s-workbench.html:8-10 只有 `styles/workbench.css`、`s-workbench.js`、`vendor/alpine.min.js`），也**没有任何 `OJ.*` 引用**。题面、代码、判分结果全部写死在 HTML 里，状态全部是 `workbench()` 的本地属性。

| 数据项 | 字段（组件里实际用的属性名，逐个列全） | 语义/来源 |
|---|---|---|
| 题号 / 标题 / 难度 / 标签 | 字面量 `#1024`、`最大子段和`、`中等`+5 格刻度、`动态规划 · 前缀和` | 写死（s-workbench.html:61-68） |
| 题面正文 + 输入输出格式 | 字面量段落 | 写死（70-82） |
| 示例 1 / 示例 2 | 字面量 `6 / -2 11 -4 13 -5 -2` → `20`；`3 / -1 -2 -3` → `-1` | 写死（84-114） |
| 判分说明 | 字面量 `10 个隐藏测试点按通过比例折算，满分 100 分` | 写死（120） |
| 时限 / 内存 / 隐藏测试点 | 字面量 `1000 ms`（`Java / Python ×2`）、`256 MB`、`10 组` | 写死（124-126） |
| 本题最高分 | `result`（`result==='ac' ? '100 / 100' : '80 / 100'`） | 模板表达式（127），基线值写死 |
| 最近提交摘要 | `result`（`result==='ac'?'AC':'WA'`、`'100 分':'80 分'`）+ 字面量 `刚刚` | 模板表达式（128） |
| 剩余天数 | 字面量 `4` | 写死（36） |
| 当前用户 | 字面量 `林` / `林昭` | 写死（52） |
| 文件名 / 语言 / 编码 | 字面量 `main.cpp`、`C++ 17`、`UTF-8` | 写死（140-142） |
| 光标位置 | `fixed`（`fixed ? '行 8，列 30' : '行 8，列 15'`） | 模板表达式（144） |
| 源码 17 行 | 字面量 DOM，含第 8 行缺陷批注 | 写死（155-205） |
| 编辑器状态栏 | 字面量 `语法检查通过`、`缩进 4 空格`、`行尾 LF`、`上次保存 21:34` | 写死（208-214） |
| 运行结果芯片 | `runState`（`'running'` / `'done'`）+ 字面量 `示例 1 通过 · 输出 20 · 11 ms` | 模板表达式（216-220） |
| 当前 AI 阶段 | `stage`、`stageLabel` | `STAGE_NAMES[stage]`（s-workbench.js:18、66） |
| 当前模型 | `model`（`'local'` / `'cloud'`） | 本地状态，默认 `'local'`（s-workbench.js:43） |
| 提示余额 | `quotaLeft`（模板 `quotaLeft+' / 3'`、`本题还剩 ${quotaLeft} 次`） | 本地状态，初值 `2`（s-workbench.js:48） |
| AI 对话消息 | `chat[]` 的 `m.role` / `m.text` | 本地数组（模板 298-300） |
| AI 是否正在输入 | `replying` | 本地状态（301） |
| 判题结果 | `result`（`'wa'` / `'judging'` / `'ac'`）、`judging` | 本地状态（40、62） |
| 判题中已跑完测试点数 | `lit` | 本地计数（47） |
| 当前分数 | `score`（getter，`Math.round(scoreNum)`） | `scoreNum` 初值 80（s-workbench.js:58、69） |
| 是否已修复缺陷 | `fixed`、`failLive`、`hintOpen`、`row-fixed` | 本地状态（41、42、70） |
| 聚焦的测试点 / 对应代码行 | `focusCase`、`focusLine`、`focusFromLine(n)`、`clearFocus()` | 本地状态 + `CASE_LINE` 反查（s-workbench.js:14、71） |
| 判分链展开态 | `bandOpen` | 本地状态（52） |
| AI 抽屉开合 / 思考过程展开 | `drawer`、`think` | 本地状态（42、44） |
| 题面栏宽度 / 拖拽态 | `problemW`、`dragging` | 本地状态（49、50） |
| 模型菜单开合 / 追问输入 | `modelMenu`、`askText` | 本地状态（44、47） |
| AI 流水线工具名 | 字面量 `getProblemConstraints(1024)`、`getFailedTestCaseFeature(1024)`、`getAcceptedSubmission(1024)` | 写死文案（334、394、469）——**是"假装调了工具"的叙述，非真实调用** |
| 测试点特征 | 字面量 `输入 3 / -1 -2 -3 · 期望 -1 · 实际 0` | 写死（397、411） |
| 护栏规则 | 字面量 `回复不得出现超过 5 行完整解题代码` | 写死（402） |
| 通过数 / 最大耗时 / 最大内存 / 提交号 | 字面量 `8`、`12`、`4096`、`#1849023849201` / `#1849023911266` | 写死（567、638、639、640） |

### 3. 该页写入/触发的动作（写操作）

| 动作（用户点的是什么、方法名） | 期望副作用 | 当前是否只是本地模拟 |
|---|---|---|
| 点「提交」（47）/ Ctrl+Enter（`init` 内 keydown 97-102）→ `submit()`（s-workbench.js:120-156） | 逐测试点判题 → 返回 score/status/首个失败点 | **是**：`const willPass = this.fixed;`（130），`tick()` 每 92/96ms `lit++`（134-138），跑完 `result = willPass ? 'ac' : 'wa'`（141）。**零请求、零轮询** |
| 点「运行」（43）/ Ctrl+' → `runSample()`（174-181） | 在沙箱跑样例，返回输出与耗时 | **是**：760ms `setTimeout` 置 `runState='done'`（176-179），输出 `示例 1 通过 · 输出 20 · 11 ms` 是写死文案（219） |
| 点批注「改成 −10⁹」→ `applyFix()`（182；实现 184-188） | 把第 8 行改成 `ans = -1000000000` | **是**：仅 `fixed = true`，切换两段预写好的 DOM（166-176） |
| 点发送 / Enter → `ask(text)`（310-311、365、433-434、495-496；表单 509） | 调 AI 助教，扣 1 次提示，流式返回回答 | **是**：`chat.push({role:'me',text})` → `quotaLeft = Math.max(0, quotaLeft-1)`（227）→ 820ms 后推入硬编码 `REPLIES`（232-240） |
| 切换模型 `model='local'` / `model='cloud'`（246、252） | 切换 AI 通道（本地 Qwen / 云端 DeepSeek） | **是**，纯 UI 状态 |
| 点阶段窄轨 → `setStage(n)`（525-537；实现 191-200） | 在 ①②③④ 间切换；④ 未 AC 时锁 | **是**，`stage4Unlocked = result==='ac'`（65） |
| 展开/收起 AI 抽屉 `drawer=!drawer`（260、520） | 布局变化 | **是** |
| 展开/收起判分链带 `bandOpen`（560、568、641） | 底部带 40px ↔ 全展开 | **是** |
| 拖拽中缝 `startDrag/onDragMove/stopDrag`（135；实现 244-266） | 调整题面宽度 300-560px | **是**，纯本地像素 |
| 鼠标悬停测试点/代码行 `focusCase`/`focusFromLine`（167、193、600-603） | 三处联动高亮 | **是**，`CASE_LINE` 是前端硬编码映射 |

### 4. 该页的派生/计算逻辑

| 指标 | 前端算法 | 位置 |
|---|---|---|
| 判分链 10 格每格状态 | `cellClass(i)`：`judging` 时 `i<=lit` 亮；`wa` 时 `i===3` 为 `fail` | s-workbench.js:210-219 |
| 每格副文本 | `cellSub(i)` 取 `CASE_LABELS[i-1]`（样例/全负/边界…） | s-workbench.js:8、217-219 |
| **测试点 → 代码行映射** | `CASE_LINE = {1:15,2:15,3:8,4:8,5:13,6:12,7:9,8:8,9:12,10:9}`，反向 `LINE_CASE = {8:3,9:7,12:6,13:5,15:1}` | s-workbench.js:14、16。**源码注释第 10-13 行明确写"真实判题系统不会给这种映射，原型里它是叙事道具"** |
| 分数滚动 | `rollScore(target)` 480ms ease-out，80 → 100 | s-workbench.js:159-171 |
| 判定"是否可以 AC" | `willPass = this.fixed`，即"改了第 8 行就一定过" | s-workbench.js:130、141 |
| 阶段 4 是否解锁 | `stage4Unlocked = result === 'ac'` | s-workbench.js:65 |
| 提示余额递减 | `this.quotaLeft = Math.max(0, this.quotaLeft - 1)`，下限 0；且每次提交判题会把 `quotaLeft` 重置回 2 | s-workbench.js:227、127 |
| "通过 8 / 10 个测试点" | 模板里写死 `8`（`result==='ac'?'10':'8'`） | s-workbench.html:567、591 |
| 最大耗时 | `result==='ac'?'12':'12'`——两个分支都是 12 | s-workbench.html:638 |
| 提交号 | `result==='ac' ? '提交 #1849023911266' : '提交 #1849023849201'` | s-workbench.html:640 |
| 护栏审查 / 智能体流水线 | 字面量 `已协同 3 个智能体 · 调用 getFailedTestCaseFeature · 护栏审查通过` | s-workbench.html:382（纯文案） |

### 5. 该页的实时性要求

**这是全站实时性压力最大的一页，但原型里全是假动作。**

| 需求 | 原型的假实现 | 依据 |
|---|---|---|
| 判题进度（逐测试点） | `submit()` 里 `tick()` 每 92ms / 96ms 跑一格，`lit++` 直到 10 | s-workbench.js:134-138 |
| 判题结果回填 | 跑完立刻 `result = 'wa'` / `'ac'`（141），再 420ms 后跳 `stage=4`（146），3000/3200ms 后收 band（151） | s-workbench.js:140-152 |
| 分数滚动 | 480ms `requestAnimationFrame` / ease-out | s-workbench.js:159-171 |
| 运行样例结果 | 760ms 后置 `done`，2800ms 后回 `idle` | s-workbench.js:176-180 |
| **AI 回答（流式/非流式）** | 820ms `setTimeout` 后 push 一条完整硬编码文本，无逐字流式 | s-workbench.js:232-240 |
| 思考过程（Router→Tutor→Guardrail 流水线） | 纯静态 DOM，进度条写死 `86%` | s-workbench.html:386-406 |
| 入口动画 | `setTimeout(()=>{this.intro=false}, 1400)` | s-workbench.js:90 |

→ 真实系统需要：判题进度推送（SSE 或 200-500ms 轮询）、AI 回答流式（SSE）、AI 流水线阶段事件。**原型一处都没有。**

### 6. 对接难度初判
**极难**。
卡在三点：① 缺**数据模型**——题目详情、代码快照、逐测试点结果、AI 会话四类实体在这页全靠写死，连 `OJ` 都没接；② 缺**流式能力**——判题进度与 AI 回答都必须服务端推送，现有接口形态（无 fetch 无 SSE）完全没准备；③ 缺**编排能力**——AI 四阶段 + 路由/护栏/工具调用是产品核心叙事，需要后端有 agent 编排与配额记账，不是单条 REST 能覆盖的。

---

## 页面：s-assignments.html

### 1. 页面职责
我的作业列表：4 张统计卡 + 5 项作业的可筛选/排序/分页卡片列表 + 右侧"本周提交 / AI 额度 / 即将截止"三张侧栏卡 + 作业详情抽屉（含题目清单与"交作业"）。

### 2. 该页读取的数据（读操作）

| 数据项 | 字段（组件里实际用的属性名，逐个列全） | 语义/来源 |
|---|---|---|
| 作业集合 | `OJ.assignments`（637-642、649、655、661、714） | shell.js:370-386 的 5 条 |
| 作业主键 | `a.id`（309、698、714；`PLAN[a.id]` 554-560、611、700） | 同上 |
| 作业标题 | `a.title`（314、444、460、666、709） | 同上 |
| 作业类型 | `a.type`（311、461、686 `typeCls`） | `homework` / `exam` / 其他 |
| 类型中文名 | `a.typeName`（311、461、666） | 如「课后作业」「课堂练习」 |
| 题目数 | `a.pidCount`（316、328、468、494、642、669、708） | 整数 |
| 已完成题数 | `a.done`（318、328、469、494、617-621、645、669、707、717） | 整数 |
| 作业得分 | `a.score`（340、470、668、698、718） | 整数 |
| 满分 | `a.full`（340、470） | 整数 |
| 截止时间 | `a.due`（344、445、471） | 格式化字符串 `10-26 23:59` |
| 剩余时间 | `a.left`（345-346、447、636、650-651、670） | `'2 天'` / `'已结束'` 字符串——**排序与判断靠 `parseInt(a.left)`（651、670），是字符串解析** |
| AI 策略文案 | `a.ai`（320、488、608-610） | 文本，含「全部关闭」「全阶段」等关键词，**`aiTrace` 靠字符串 `indexOf` 判断开放阶段** |
| 作业状态 | `a.status`（325、345、530、533、637-640、658、663、670、687-688、707） | `todo` / `doing` / `done` |
| 学生个人成绩 | `OJ.students[0].score`（`get avgScore` 647） | **借用了 shell.js 的 STUDENTS 排行榜首行**，不是本学期平均分 |
| 题目（详情抽屉用） | `OJ.problems.find(p => p.id === id)` → `p.id`、`p.title`、`p.lvl`、`p.status`、`p.score` | shell.js:320-345；查找 691、701、721 |
| 提交记录（算 AI 阶段用） | `OJ.submissions` 的 `s.pid`、`s.status` | 612、693 |
| 作业→题目清单映射 | `PLAN`（`'A-0412': [1024, 1045, 1058, 1001, 1017, 1032]` … 共 5 组） | **本页 `<script>` 硬编码（554-560），`OJ.assignments` 里没有这个字段** |
| 本周提交柱 | `weekBars: [1, 0, 3, 1, 4, 2, 2]` | 本地常量（571） |
| 近 6 次得分 | `scoreTrend: [46, 58, 52, 71, 76, 82]` | 本地常量（572） |
| AI 额度 | `quota[]` 的 `q.id`、`q.title`、`q.used`、`q.limit`、`q.note` | 本地常量（573-576），`note` 字段实际未渲染 |
| 筛选器定义 | `filters[]` 的 `f.k`、`f.label` | 本地常量（577-581） |
| 排序项 | `SORTS[]` 的 `s.k`、`s.label` | 本地常量（561-565） |
| 页面副标题 | 字面量 `林昭 · 数据结构 · 共 5 项，3 项待完成` | 写死（204） |
| 平均分周同比 | 字面量 `较上周 +4` | 写死（231） |
| AI 策略提示条 | 字面量「仅开放 ① 读题拆解与 ③ 排错诊断，单题上限 3 次」 | 写死（237） |
| 成绩走势卡 | 字面量 `较上一次 +6 分`、`班级均分 76`、`96 最高`、`71 平均`、`46 最低` | 写死（389-394） |
| 本周提交卡 | 字面量 `10-20 至 10-26`、`9 次提交 · 6 次 AC`、`6 AC / 3 未通过 / 2.4 平均尝试` | 写死（406-418） |
| AI 阶段图例 | 字面量 `①读题 ②思路 ③排错 ④复盘` | 写死（283-284） |

### 3. 该页写入/触发的动作（写操作）

| 动作（用户点的是什么、方法名） | 期望副作用 | 当前是否只是本地模拟 |
|---|---|---|
| 点状态分段 `setStatus(k)`（250；实现 679） | 按 `all`/`todo`/`doing`/`done`/`over` 过滤 | **是**（660-663） |
| 选排序 `sort=s.k`（264） | 按截止/得分/进度排序 | **是**（668-670） |
| 输入搜索 `x-model="kw"`（274） | 匹配 `title` / `typeName` | **是**（664-667） |
| 点「清空筛选」`reset()`（277；实现 685） | 复位 | **是** |
| 点作业卡 `open(a)`（310；实现 694） | 打开详情抽屉 | **是**，`this.detail = a` |
| 点「交作业」`handIn(detail)`（530；实现 697-710） | 提交作业 → 状态变 `done`、得分待批改，5 秒内可撤销 | **是**：直接改 `a.status='done'`、`a.done=a.pidCount`（707-708），并把 `OJ.problems` 里 `none` 改成 `pending`（704）。**无任何提交请求** |
| 点「撤销」`undoToast()`（544；实现 711-729） | 还原作业状态与题目状态 | **是**，读 `undoSnap` 快照回滚（698） |
| 点「演示出错态」`loadErr = true`（286） | 展示错误态 | **是**，评审开关 |
| 点「重新加载」`retry()`（287、361；实现 590-594） | 复位并再等 600ms | **是** |
| 翻页 `page--` / `page=n` / `page++`（375-382） | 分页 | **是**，`per: 4`（568），`paged` 本地 `slice`（674-677） |
| 点「继续做题」（208、528） | 跳工作台 | **是**（纯跳转，`?pid=1024` 或 `detailPids[0]`） |
| 点「提交记录」（207） | 跳 s-submissions | **是**（纯跳转） |

### 4. 该页的派生/计算逻辑

| 指标 | 前端算法 | 位置 |
|---|---|---|
| 待完成 / 未开始 | `filter(a => a.status !== 'done' && !isOver(a)).length` / `status==='todo'` | 637、638 |
| 进行中 / 已完成 | `status==='doing'` / `status==='done'` | 639、640 |
| 进行中题数 / 已完成题数 | `reduce((n,a) => n + a.pidCount)` / `n + a.done` | 641-646 |
| 平均分 | `OJ.students[0].score` | 647（**取错了实体**：学生排行榜首行而非本人作业均分） |
| 是否已截止 | `a.left === '已结束' && a.status !== 'done'` | 636 |
| 即将截止排序 | `parseInt(a.left)` 升序 | 648-653（**字符串当数字解析**） |
| **AI 四阶段链** | 由 `a.ai` 文本 `indexOf('全部关闭')` / `indexOf('全阶段')` 决定开放 `[1,3]` 或 `[1,2,3,4]`；再结合 `a.done > 0`、`OJ.submissions` 里是否有非 AC / 有 AC 推出 `used` | `aiTrace(a)` 605-635（608-623 为核心） |
| 「开放 ①③，已用 ①」文案 | 字符串拼接 | 631-634 |
| 筛选结果集 | `filtered` getter，`sort==='progress'` 用 `b.done/b.pidCount` | 660-672 |
| 分页 | `pages = ceil(filtered.length / per)`，`per=4` | 673-677 |
| 状态分组计数 | `countBy(k)`，`over` 单独判 | 654-659 |
| 抽屉里题目清单 | `PLAN[detail.id].map(id => OJ.problems.find(...))` | 689-692 |
| 每题提交次数 | `OJ.submissions.filter(s => s.pid === pid).length` | 693 |
| 进度条宽度 | `Math.round(a.done / a.pidCount * 100)` | 模板 326 |

> ⚠️ 关键结论：**「作业包含哪些题」这个最基础的关联关系不在任何数据实体里**，是前端 `PLAN` 常量（554-560）硬编码的。`aiTrace` 也不是读真实的 AI 使用记录，而是从「交没交过题 + 提交状态 + ai 文案」**猜**出来的。

### 5. 该页的实时性要求
**无**。
- 假加载：`setTimeout(() => { this.loading = false; }, 600)`（588）。
- 交作业成功提示：`setTimeout(..., undo ? 5000 : 2400)`（736）——5 秒撤销窗口是纯前端计时。
- 无轮询、无 SSE、无 WebSocket。

### 6. 对接难度初判
**难**。
主要卡在**缺数据模型**而非缺接口：① 作业—题目关联（`PLAN`）必须以 `GET /api/assignments/{id}/problems` 形式落到后端；② AI 阶段使用记录需要独立实体（谁、哪份作业、哪个阶段、用了几次、上限多少），现在只能从提交行为反推；③ `left`/`due` 是预格式化字符串，排序靠 `parseInt('2 天')`，需要改成 `dueAt` 时间戳由后端算剩余；④ 平均分误用了 `OJ.students[0].score`。

---

## 页面：s-submissions.html

### 1. 页面职责
提交记录：4 张统计卡 + 可按状态/语言/时间筛选、可按表头排序的提交明细表 + 语言分布/判题结果分布两张堆叠条 + 提交详情抽屉（10 格判分链、测试点输入输出、同题提交轨迹、代码回看）。

### 2. 该页读取的数据（读操作）

| 数据项 | 字段（组件里实际用的属性名，逐个列全） | 语义/来源 |
|---|---|---|
| 提交集合 | `OJ.submissions`（692）+ 本页 `EXTRA`（512-523）合并为 `ALL` | shell.js:347-368 的 10 条 + 本地 5 条 = 15 条 |
| 提交号 | `s.id`（311、464、759、765、848-850） | 形如 `S-20481` |
| 题目主键 | `s.pid`（312、412、498、848、860） | 整数 |
| 题目名 | `s.title`（312、412、759、763） | 字符串 |
| 语言 | `s.lang`（313、413、710、730、755、794-796） | `C++ 17` / `Python 3` / `Java 17` |
| 判题状态 | `s.status`（314、316、405、708、745、751、756、833-836、840、881-882） | `ac` / `wa` / `tle` / `mle` / `re` / `ce` |
| 得分 | `s.score`（414） | 0-100 |
| 通过测试点 | `s.pass`（316、319、415、836、855） | **字符串 `'8/10'`**，代码里 `parseInt(s.pass)` 取前半段 |
| 耗时 | `s.time`（416、443、740） | **字符串带单位 `'14ms'`**（`parseInt` 剥单位） |
| 内存 | `s.mem`（416、443） | 字符串 `'4210KB'` |
| 提交时间 | `s.at`（323、417、693、764、861） | 字符串 `'10-24 23:41'` |
| 首次失败测试点 | `s.first`（shell.js 定义里有，本页**未使用**） | shell.js:347-368 |
| 几天前（本地派生） | `s.d`（693、743、748、764） | 本页给 `OJ.submissions` 补的字段，由 `AGE[s.at.slice(0,5)]` 映射（525） |
| 历史提交（本地常量） | `EXTRA[]` 的 `id`/`pid`/`title`/`lang`/`status`/`score`/`pass`/`time`/`mem`/`at`/`d` | 512-523 |
| 代码回看内容 | `CODES[status]` 的 `title`、`hl`、`hint`、`note`、`src[]` | 528-664（按 6 种状态各写死一份） |
| 测试点输入/输出 | `CASE_IO[10]` 的 `in`、`out`、`got` | 669-680 |
| 总提交次数 / AC 次数 | 字面量 `TOTAL: 47`、`TOTAL_AC: 32` | 698（**硬编码，不由 ALL 推导**） |
| 页面副标题 | 字面量 `林昭 · 数据结构 · 本学期 47 次提交，AC 率 68%` | 写死（175） |
| 统计卡补充文案 | 字面量 `涉及 8 道题`、`首次 AC 6 道`、`较上周 2ms` | 写死（187、192、197） |
| AI 复盘提示条 | 字面量「单题上限 3 次；AC 后可解锁 ④」 | 写死（208） |
| 筛选器定义 | `filters[]` 的 `f.k`、`f.label`（`all`/`ac`/`wa`/`tle`/`mle`/`re`/`ce`） | 705-709 |
| 语言清单 | `langs: ['all','C++ 17','Python 3','Java 17']` | 710 |
| 时间范围 | `ranges[]` 的 `r.d`、`r.label`（3/7/30 天） | 711 |

### 3. 该页写入/触发的动作（写操作）

| 动作（用户点的是什么、方法名） | 期望副作用 | 当前是否只是本地模拟 |
|---|---|---|
| 点状态分段 `setStatus(k)`（218；实现 784） | 按判题结果过滤 | **是**（753-756） |
| 选语言 `lang = l`（228） | 按语言过滤 | **是**（755） |
| 选时间范围 `range = r.d`（240） | 按 3/7/30 天过滤 | **是**（748 `scoped`） |
| 输入搜索 `x-model="kw"`（249） | 匹配 `title` / `id` | **是**（757-760） |
| 点列头 `setSort(k)`（275、278、287；实现 733-737） | 按 `id` / `title` / `time` 升降 | **是**（761-766） |
| 点「清空筛选」`reset()`（253；实现 785） | 复位 | **是** |
| 点整行 / Enter `open(s)`（310；实现 817-825） | 打开提交详情抽屉，默认选中"卡住的那个测试点" | **是**，`this.detail = s` |
| 点判分链某一格 `selTest = i`（427） | 切换测试点详情 | **是** |
| 点「演示出错态」`loadErr = true`（265） | 展示错误态 | **是**，评审开关 |
| 点「重新加载」`retry()`（266、343；实现 720-724） | 复位并再等 600ms | **是** |
| 翻页（350-357） | 分页 | **是**，`per: 8`（699） |
| 点「AI 诊断」`<a :href="s-workbench.html?pid=${detail.pid}&stage=3">`（498） | 跳工作台 ③ 排错诊断 | **是**（纯跳转，`stage=3` 由 `s-workbench.js:79` 接住） |
| `copyCode()`（901-905，`navigator.clipboard.writeText`） | 复制代码 | **未在源码中找到调用点**：模板里没有绑定 `copyCode` 的按钮，`copied` 字段也未被渲染 |

### 4. 该页的派生/计算逻辑

| 指标 | 前端算法 | 位置 |
|---|---|---|
| **判分链 10 格** | `ac` → 全绿；`ce` → 全灰；其余按 `parseInt(s.pass)` 前 p 格绿、第 p 格放真实状态、余下灰 | `chainFor(s)` 831-844（836 行 `parseInt`，840 行卡点） |
| 「比上次多过几个点」 | `parseInt(s.pass) - parseInt(prev.pass)` | `deltaOf(s)` 852-856 |
| 同题上一次提交 | 按 `pid` 过滤后按 `id` 字典序排，取前一条 | `prevOf(s)` 847-851 |
| 同题提交轨迹 | 按 `pid` 过滤 + 按 `at` 字符串排序 + 每条附 `ch` 链 | `get trace` 858-863 |
| 抽屉通过数 | `chain.filter(c => c === 'ac').length` | `get passCount` 866 |
| 测试点"实际输出" | `ac` → `CASE_IO[i].out`；未运行 → `'—'`；卡点 → `CASE_IO[i].got` | `outOf(i)` 868-873 |
| 卡点标签 | 从链上 `findIndex(c => c !== 'ac' && c !== '')` 反推编号 | `get failLabel` 890-897 |
| 平均用时 | `parseInt(s.time)` 求和 / 条数 | 739-742 |
| 本周提交 / 本周 AC / 覆盖题数 | `d <= 7` 过滤、`status==='ac'` 过滤、`new Set(pid).size` | 743-746 |
| 语言分布 | 按 `lang` 计数 → `pct = round(c/n*100)` → 降序 | 790-798 |
| 判题结果分布 | 按 6 种状态计数 → `pct` → 降序 | 799-814 |
| 筛选后结果集 | 时间窗 → 语言 → 状态 → 关键词 → 排序 | 753-767 |
| 状态计数徽标 | `countBy(k)`，受 `lang` 影响但**不受 `status` 影响** | 749-752 |
| 代码高亮 | `highlight(src)` 手写正则替换关键字/数字/字符串 | 682-689 |
| 空态文案 | `emptyTitle` / `emptyDesc` 按 `kw` / `status==='mle'` 分支 | 773-782 |

> ⚠️ 关键结论：**这页最核心的 10 格判分链，是从一个字符串 `'8/10'` 反解出来的**（836 行），不是真实的逐测试点结果数组；测试点的输入/输出/实际输出三格全部来自前端 `CASE_IO`（669-680），与所查看的提交毫无数据关系。

### 5. 该页的实时性要求
**无**。
- 假加载 600ms（718）。
- 无轮询、无 SSE、无 WebSocket；提交记录是静态历史，天然不需要实时，但**若要做"刚提交完跳过来看判题进度"，当前也无任何机制**。

### 6. 对接难度初判
**难**。
卡在**缺字段**：① 判分链要真实的 `testcases: [{index, status, time, mem}]` 数组，现在只有 `pass: '8/10'` 字符串；② 测试点输入/期望输出/实际输出是判题系统的敏感数据，需要后端决定"哪些能给、哪些隐藏"，现在前端全写死；③ 代码快照要有真实 `source` 字段（现在按 status 从 `CODES` 里取预置代码）；④ `TOTAL: 47` / `TOTAL_AC: 32` 硬编码，需要 `GET /api/submissions/stats`。

---

## 页面：s-analytics.html

### 1. 页面职责
学情分析：首屏「AI 学情解读 + 薄弱点清单」主角卡 + 4 张统计卡 + 提交趋势柱状图 + 标签能力列表 + 题目掌握表 + 右栏「测试点热力图 / AI 使用统计 / 最近 AC」+ 两个抽屉（薄弱知识点、题目详情）。

### 2. 该页读取的数据（读操作）

| 数据项 | 字段（组件里实际用的属性名，逐个列全） | 语义/来源 |
|---|---|---|
| 题目集合 | `OJ.problems`（814 `PROBS` 由它派生） | shell.js:320-345 |
| 题目主键 / 标题 / 难度 | `p.id`、`p.title`、`p.lvl`（467-469、516、590、619、661） | 同上 |
| 题目标签 | `p.tags`（816 兜底 `p.tags[0]`） | 同上 |
| 班级 AC 率（题目维度） | `p.ac`（815 兜底 `rate`） | 同上 |
| 判题状态 / 得分 | `p.status`、`p.score`（818-819、824-826、828-832、471） | 同上 |
| 题目补全字段 | `PROBLEM_EXTRA[p.id]` 的 `tries`、`score`、`at`、`rate` | **本页硬编码 736-749**，12 道题逐条写 |
| 标签定义 | `TAGS[]` 的 `k`、`name`、`pct`、`cls`、`pids`、`symptom`、`cause`、`advice`、`note`、`recs[]` | 本页硬编码 686-733；`recs` 元素含 `id`、`title`、`lvl`、`tag`、`why` |
| 每日提交趋势 | `DAYS[]` 的 `[日期, ac数, wa数]` | 本页硬编码 676-683（30 天）+ `DAYS90` 785-798（补 60 天） |
| 标签→题号映射 | `TAG_PIDS`（`all` / `dp` / `greedy` / `graph` / `string` / `math`） | 本页硬编码 752-759 |
| 题目提交历史 | `HISTORY[pid][]` 的 `id`、`status`、`score`、`pass`、`at` | 本页硬编码 762-776 |
| AI 阶段调用次数 | `aiStages[]` 的 `k`、`no`、`n`（①4 ②0 ③6 ④2） | 本页硬编码 861-866 |
| AI 统计说明 | `aiNote` | 本页硬编码 867 |
| 最近 AC 列表 | `recentAc[]` 的 `pid`、`title`、`lang`、`at`、`time` | 本页硬编码 868-872 |
| 时间范围 | `RANGES[]` 的 `d`、`label`（7/30/90 天） | 779-783 |
| 工具：难度刻度 / 状态标签 | `OJ.lvl(p.lvl)`（469）、`OJ.statusTag(p.status)`（472） | shell.js:473-478、467-469 |
| AI 解读标题/正文 | 字面量「下一步：先把图论补到班级线（41% → 58%）」「动态规划 82% vs 66%」「图论 41%」「3 次提交卡在 Dijkstra」 | 写死（284-289） |
| AI 解读元信息 | 字面量 `基于 47 次提交 · 12 道题`、`更新于 10-24 23:41`、`口径：标签掌握度 = 该标签下已 AC 题数占比` | 写死（282、291-293） |
| 页面副标题 | 字面量 `林昭 · 数据结构 · 本学期 47 次提交，班级平均 AC 率 62%` | 写死（267） |
| 统计卡补充文案 | 字面量 `班级均值 62%`、`全站均值 3.1 次`、`最难的 1 道试了 8 次` | 写死（341-353） |
| AI 使用统计总数 | 字面量 `12` 次调用 · `云端 9 / 本地 3` | 写死（531） |

### 3. 该页写入/触发的动作（写操作）

| 动作（用户点的是什么、方法名） | 期望副作用 | 当前是否只是本地模拟 |
|---|---|---|
| 切换时间范围 `setRange(i)`（378；实现 892-895） | 7/30/90 天，重算趋势与统计 | **是**（876-891） |
| 点「只看薄弱」`onlyWeak = !onlyWeak`（414） | 题目表只留薄弱标签下的题 | **是**（915-919） |
| 点标签行 `pickTag(k)`（421；实现 905-908） | 下钻该标签的题目 | **是**（912-921 + `TAG_PIDS`） |
| 点「重置」`resetTable()`（446；实现 909） | 复位标签与薄弱筛选 | **是** |
| 点题目行 `openProb(p)`（466、547；实现 959） | 打开题目详情抽屉 | **是**，`this.prob = p` |
| 点薄弱点 `openWeak(t)`（311；实现 958） | 打开薄弱知识点抽屉 | **是**，`this.weak = t` |
| 点「开始针对性练习」`href="s-problems.html?status=fail"`（296） | 跳题库并按"未通过"筛选 | **是**（纯跳转） |
| 点「去练习」`practiceHref(t)`（325；实现 966-971） | 跳题库 `?status=fail&lvl=N`，无题时跳工作台 | **是**（前端拼 URL，用平均难度近似标签） |
| 点抽屉「去练习」`s-workbench.html?pid=${weak.recs[0].id}&tag=...`（605、661） | 跳工作台 | **是**（纯跳转） |
| 翻页（491-497） | 分页 | **是**，`per: 5`（857） |

**本页无任何写接口：没有提交、没有保存、没有 AI 调用。**

### 4. 该页的派生/计算逻辑

| 指标 | 前端算法 | 位置 |
|---|---|---|
| **时间范围汇总** | `ac` / `wa` 求和 → `total = ac + wa` → `acRate = round(ac/total*100)` → `pids = 有提交的天数`（注意：变量名叫 `pids` 实际是**天数**） | `get range` 876-884 |
| 趋势柱高度 | `max = max(ac+wa)`；`hAc = round(ac/max*96)`、`hWa = round(wa/max*96)`，非零保底 3px / 2px | `build(list)` 800-812 |
| 标签题数 / 已 AC 数 / 提交数 | `n = pids.length`、`done = score===100 的题数`、`subs = tries 求和` | `TAGS_D` 837-848 |
| 标签文案 `sub` | `${done}/${n} 题` 或 `暂无题目` | 846 |
| **标签掌握度 `pct`** | **不在前端计算**——`TAGS` 里写死（如 `dp: 82`、`graph: 41`）；`aihero` 正文里的 82%/41% 也是字面量 | 687-732、286-287 |
| 薄弱标签 | `tags.filter(t => t.pct < 70)` | 898-899 |
| 平均尝试次数 | `probs.reduce(tries) / probs.length`，`toFixed(1)` | 900-903 |
| 题目表行集 | `TAG_PIDS[selTag]` → `probs.filter` → `onlyWeak` 再过滤 | 912-921 |
| 「有过提交就不能显示未提交」 | `st === 'none' && ex.tries > 0` → 按 `score` 推 `ac` / `wa` | 818-819 |
| 每题的 `tagName` / `tagKey` | 从 `TAGS.find(t => t.pids.includes(p.id))` 反查，兜底 `p.tags[0]` / `'all'` | 816、822-823 |
| 每题的 `history` | 有 `HISTORY[pid]` 用它，否则伪造一条 `S-2xxx` | 824-827 |
| 每题的 AI `note` | 按 `score===100` / `score>0` / 其他三档返回写死文案 | 828-832 |
| **测试点热力图 10 格** | 与 s-problems 同一套：`round(score/100*10)` 个绿 + 1 个状态格 + 灰 | `chain(p)` 947-955（950 行核心） |
| 热力图取哪 6 道 | `probs.filter(p => p.tries > 0).slice(0, 6)` | 944-946 |
| 「去练习」URL 的难度 | 该标签下题目 `lvl` 的平均值四舍五入 | `practiceHref` 966-971（969） |
| 90 天趋势补数 | 30 项图案循环补 60 天（注释 790-791 说明不循环会 `undefined` 导致 NaN） | 785-798 |

> ⚠️ 关键结论：**学情页的核心指标「标签掌握度」没有任何计算过程，是写死的常量**（`pct: 82/64/41/73/55`），页面却标注了口径「标签掌握度 = 该标签下已 AC 题数占比」（291-293）。若真按该口径算，`dp` 应为 2/4 = 50% 而非 82%。同理 `cls`（班级均值）也无来源。

### 5. 该页的实时性要求
**无**。
- 无轮询、无 SSE、无 WebSocket、无 `setInterval`。
- 唯一的"时间感"是写死的「更新于 10-24 23:41」（291）。
- 趋势柱有 CSS 入场动画（`@keyframes trend-in`，98-105），与数据无关。

### 6. 对接难度初判
**极难**。
主要卡在**缺数据模型 + 缺聚合能力**：① 标签掌握度、班级均值、薄弱点症状/根因/建议是典型的**离线聚合 + AI 生成结果**，必须有 `tag_mastery` 快照表与生成任务，现有实体（problems/submissions/students）里完全没有；② 趋势需要按天分桶的 `ac/wa` 计数，`submissions` 只有 10 条样本且时间是字符串；③ 题目掌握表的 `tries`/`rate`/`at`、AI 阶段调用次数、最近 AC 列表全部是硬编码，无对应查询；④ `pids` 字段名与语义不符（实为天数），口径需要与后端重新对齐。

---

## 页面：s-exam.html

### 1. 页面职责
考试作答：左题目导航（含考试须知）、中题面、右代码作答区三栏锁满一屏 + 90 分钟倒计时 + 单题提交 + 交卷确认/已交卷两个弹窗，考试期间 AI 全关。

### 2. 该页读取的数据（读操作）

| 数据项 | 字段（组件里实际用的属性名，逐个列全） | 语义/来源 |
|---|---|---|
| 试卷题目集 | `QS[]`（`qs`，402-598），每题字段见下 | **本页 `<script>` 硬编码 5 道题**，与 `OJ.problems` 无关 |
| 题号 | `q.id`（221、244） | 1024 / 1045 / 1017 / 1092 / 1058 |
| 题目标题 | `q.title`（226、245） | 字符串 |
| 难度 | `q.lvl`（227 经 `OJ.lvlName`、248 经 `OJ.lvl`） | 1-5 |
| 分值 | `q.score`（227） | 每题 20 分 |
| 时限 | `q.time`（258） | `'1000ms'` / `'2000ms'` |
| 标签 | `q.tags`（249） | 字符串数组 |
| 作答状态 | `q.state`（224 用作 class、336-338 禁用按钮、642 计数） | `'doing'` / `'todo'` / `'done'` |
| 已提交次数 | `q.tries`（325、333、649-653、678-681） | 整数 |
| 题面段落 | `q.desc[]`（253-255，`x-html` 渲染） | 含 `<b>` / `<code>` 的内联 HTML |
| 示例 | `q.examples[]` 的 `e.in`、`e.out`（264-277） | 字符串 |
| 初始代码 | `q.code[]`（298-299） | 行数组 |
| 剩余秒数 | `secs`（604 初值 `38 * 60 + 24`） | 本地状态 |
| 工具：难度名 / 难度刻度 | `OJ.lvlName(q.lvl)`（227）、`OJ.lvl(q.lvl)`（248） | shell.js:470、473-478 |
| 地址参数 | `OJ.qs('q')`（618）、`OJ.qs('state')`（620）、`OJ.qs('err')`（621）、`OJ.qs('skeleton')`（627） | `?q=3` 定位题号、`?state=submitted` 直出交卷态 |
| 页面头部 | 字面量 `数据结构期中测验`、`计算机 2022 级 1 班 · 共 5 题 · 限时 90 分钟 · 提交次数不限，取最后一次` | 写死（149-150） |
| AI 关闭提示 | 字面量「阶段 ①②③④ 全部禁用，接口直接返回 403」 | 写死（166） |
| 考试须知 | 字面量 `AI 全关`、`到时自动强制交卷`、`切屏超过 3 次记为异常`、`交卷后代码参与查重` | 写死（233-236） |
| 题目元信息 | 字面量 `内存 256MB`、`测试点 10 组`、`计分 OI 按点` | 写死（259-261） |
| 运行结果 | 字面量 `样例 1 通过 · 用时 8ms · 内存 3420KB` / `期望 -1，实际输出 0` | 写死（313、319） |
| 切屏次数 | 字面量 `0` | 写死（368） |
| 已交卷文案 | 字面量 `试卷已提交给周明远老师` | 写死（387） |

### 3. 该页写入/触发的动作（写操作）

| 动作（用户点的是什么、方法名） | 期望副作用 | 当前是否只是本地模拟 |
|---|---|---|
| 点题目导航 `cur = i`（222） | 切换当前题 | **是**（纯前端索引） |
| 点「运行样例」`run()`（289；实现 661-670） | 在沙箱跑样例 | **是**：780ms 后置 `runState = t.cur === 0 ? 'bad' : 'ok'`（668）——**第 1 题永远失败、其余永远成功，写死** |
| 点「提交本题」`submitOne()`（336；实现 673-683） | 提交到判题，取最后一次成绩 | **是**：`q.state = 'done'; q.tries++`（677-678），5 秒内可撤销（679-682） |
| 点「撤销」`undoToast()`（349；实现 697-704） | 还原 `state` / `tries` | **是**，函数式快照回滚 |
| 点「交卷」`askSubmit = true`（158） | 弹确认框 | **是** |
| 点「确认交卷」`handIn()`（373；实现 706-709） | 提交整卷、停止计时、进入已交卷态 | **是**：只置 `submitted = true`，无请求 |
| 点「重新加载」`retry()`（209；实现 630-635） | 复位错误态并再等 600ms | **是** |
| 点「返回我的作业」/「查看提交记录」（390-391） | 跳转 | **是**（纯 `<a href>`） |
| 倒计时到 0 自动交卷 | `t.submitted = true`（625） | **是**，本地 `setInterval` |

### 4. 该页的派生/计算逻辑

| 指标 | 前端算法 | 位置 |
|---|---|---|
| 倒计时显示 | `secs` → `mm:ss` 补零 | `get clock` 638-641 |
| 已作答数 | `qs.filter(x => x.state === 'done').length` | `get doneCount` 642 |
| **上一次提交的通过数** | `q.tries >= 2 ? 10 : 7`——**写死的两档** | `get lastPass` 647-651 |
| 上一次提交的判分链 | 前 `lastPass` 格绿 + 第 `lastPass` 格 `wa` + 其余灰 | `get lastChain` 652-659 |
| 「已作答 N/5」 | 模板拼 `doneCount / 5`，`5` 是字面量（不是 `qs.length`） | 218、364、366 |
| 到时强制交卷 | `if (secs > 0) secs--; else { clearInterval; submitted = true }` | 622-626 |
| 计时暂停条件 | `if (submitted 或 loading 或 loadErr) return;` | 623 |
| 运行结果分支 | `runState` 三态驱动四段固定文案 | 303-321 |
| 「切屏次数」 | 无实现，模板写死 `0` | 368 |

> ⚠️ 关键结论：**这页是"取最后一次成绩"的考试，但 `lastPass` 只有 7 或 10 两个值**（650 行），完全由"交过几次"决定，与真实判题无关。题目分值 `20` 分、随机代码、示例全部是 `QS` 常量。

### 5. 该页的实时性要求

**这是全站唯一有真实周期任务的一页。**

| 需求 | 原型实现 | 依据 |
|---|---|---|
| **倒计时（1 秒粒度）** | `setInterval(function(){...}, 1000)`，每秒 `secs--`，归零自动交卷 | s-exam.html:622-626 |
| 计时暂停/恢复 | 通过 `submitted` / `loading` / `loadErr` 三个标志跳过递减 | 623 |
| 倒计时告警 | `:class="{warn: secs < 600}"`，最后 10 分钟变红 | 154 |
| 交卷后停止 | `clearInterval` | 625 |
| 切屏检测 | **未在源码中找到**：只有文案「切屏超过 3 次记为异常」（235）与写死的 `0`（368），无 `visibilitychange` / `blur` 监听 |
| 服务端校时 | **未在源码中找到**：`secs` 是进入页面时的本地常量 `38*60+24`（604），刷新即重置 | 604 |

> 真实系统风险：倒计时必须在服务端权威时间上算，否则刷新页面就能恢复时间（当前 `secs` 初值写死，刷新即回到 38:24）。

### 6. 对接难度初判
**难**（略低于工作台）。
卡在：① **缺试卷实体**——试卷下发要一次返回"5 道题的题面 + 初始代码 + 分值 + 时限"，当前 `QS` 是本页私有的 5 条常量，与 `OJ.problems` 无关联；② **缺作答态持久化**——切题、改代码、提交本题都只改内存，刷新即丢；③ **缺服务端计时**——`secs` 写死、刷新重置；④ **缺考试态门禁**——「AI 全关返回 403」「交卷后查重」「切屏次数」都只有文案没有实现。另外 `data-page` 用的是 `s-assignments`（s-exam.html:143），与 `_SPEC.md:83-85` 的 `data-page` 取值清单不一致（清单里没有 `s-exam`），属于原型自身的契约偏差。

---

## 学生端横向汇总

### A. 业务实体（合并去重后，学生端共需 14 类）

1. **用户/会话 User** — `name`、`sid`、`class`、`major`、`grade`、`avatar`。
   依据：s-problems.html（`OJ` 由 shell.js 注入）、s-workbench.html:52、s-assignments.html:204、s-submissions.html:175、s-analytics.html:267。
2. **题目摘要 ProblemSummary** — `id`、`title`、`lvl`、`tags[]`、`ac`、`tried`、`status`、`score`。
   依据：shell.js:320-345，被 s-problems（全部字段）、s-analytics（`OJ.problems` map 814）共用。
3. **题目详情 ProblemDetail** — `id`、`title`、`lvl`、`tags[]`、`description`（含输入/输出格式）、`examples[{in,out}]`、`timeLimit`、`memoryLimit`（含 Java/Py ×2 倍率）、`testcaseCount`、`hiddenCount`、`scoring`。
   依据：s-problems.html:364-385（写死）、s-workbench.html:70-126（写死）、s-exam.html:407-433（`desc`/`examples`/`time`）。
4. **提交记录 Submission** — `id`、`pid`、`title`、`lang`、`status`、`score`、`pass`、`time`、`mem`、`at`、`first`。
   依据：shell.js:347-368；主用 s-submissions.html:311-323、414-417。
5. **逐测试点结果 TestcaseResult** — `index`(1-10)、`status`、`time`、`mem`、`input`、`expected`、`actual`、`hidden`。
   依据：**全站最缺的实体**。s-submissions.html:831-844 用 `pass` 字符串伪造、s-problems.html:473-481 与 s-analytics.html:947-955 用 `score` 伪造、s-workbench.html:598-621 用 `cellClass` 伪造。
6. **提交代码快照 SubmissionCode** — `submissionId`、`lang`、`source[]`、`errorLine`。
   依据：s-submissions.html:528-664（按 status 取 6 份预置代码）、s-workbench.html:155-205（写死 17 行）。`first` 在 shell.js:347-368 有定义，但学生端 7 页无任何渲染点（仅教师端 t-dashboard.html:457 使用）。
7. **作业 Assignment** — `id`、`title`、`type`、`typeName`、`pidCount`、`done`、`score`、`full`、`due`（应为 `dueAt` 时间戳）、`left`（应由后端算）、`ai`（应为结构化策略）、`status`。
   依据：shell.js:370-386；s-assignments.html:309-352、468-471。
8. **作业题目清单 AssignmentProblem** — `assignmentId`、`pid`、`title`、`lvl`、`status`、`score`、`submitCount`。
   依据：**s-assignments.html:554-560 的 `PLAN` 常量**（后端完全没有这个关联）、抽屉表格 508-516、`subsOf` 693。
9. **AI 阶段策略与使用记录 AiStageUsage** — `scope`(assignment/pid)、`stage`(1-4)、`open`、`used`、`limit`、`lastUsedAt`。
   依据：s-assignments.html:605-635 `aiTrace`（从提交行为**猜**）、237（文案）、573-576（`quota` 的 `used`/`limit`）、s-workbench.js:48（`quotaLeft`）、s-analytics.html:861-866（`aiStages` 的 `n`）。
10. **AI 对话/提示 AiHint** — `pid`、`stage`、`role`、`text`、`model`、`createdAt`。
    依据：s-workbench.js:20-30（`REPLIES`）、47（`chat[]`）、222-241（`ask`）。
11. **AI 模型通道 AiModel** — `key`(`local`/`cloud`)、`name`、`model`、`vendor`、`latencyHint`、`fallback`。
    依据：s-workbench.html:240-256（本地极速 Qwen2.5-Coder 7B / 云端 DeepSeek）。
12. **考试 Exam + 考试题目 ExamQuestion** — `examId`、`title`、`class`、`duration`、`questionCount`、`policy`(AI 全关/查重/切屏阈值)；题目 `id`、`title`、`lvl`、`score`、`time`、`tags[]`、`desc[]`、`examples[]`、`code[]`、`state`、`tries`。
    依据：s-exam.html:403-598（`QS`）、150、166、233-236。
13. **学情统计（多张聚合视图）** —
    - `TagMastery`：`k`、`name`、`pct`、`cls`、`pids[]`、`n`、`done`、`subs`、`symptom`、`cause`、`advice`、`note`、`recs[]`。依据 s-analytics.html:686-733、837-848。
    - `TrendBucket`：`date`、`ac`、`wa`、`total`。依据 676-683、800-812。
    - `AiUsageStat`：`stage`、`count`、`cloud`、`local`。依据 861-866、531。
    - `RecentAc`：`pid`、`title`、`lang`、`at`、`time`。依据 868-872。
    - `ProblemMastery`：`tries`、`score`、`at`、`rate`。依据 736-749。
14. **判分结果链 GradingChain（10 格）** — `status[]`(长度 10，取值 `ac`/`wa`/`tle`/`mle`/`re`/`ce`/空)。
    依据：这是**全站的设计母题**，4 个页面各自用不同方式伪造它（s-problems.html:473-481、s-submissions.html:831-844、s-analytics.html:947-955、s-workbench.js:210-219）。后端必须提供统一的 `status[]`，否则 4 处推导口径会漂移。

### B. 接口能力（按页面归并去重，共 28 项）

**题库**
1. `GET /api/problems` — 列表；支持 `status`（`all`/`todo`/`done`/`fail`）、`lvl`（多选）、`kw`、`sort`（`id`/`lvl`/`rate`/`score`）、`page`。每行需返回 `status`/`score`/`tried`/`ac`（用户维度）。
2. `GET /api/problems/{id}` — 题面详情（含 `examples`、时限内存、测试点数量与隐藏数、计分方式）。
3. `GET /api/problems/{id}/my-status` — 我的最好分、判分链、是否已 AC（"继续做题"主角卡用）。

**判题**
4. `POST /api/submissions` — 提交代码，返回 `submissionId`。
5. `GET /api/submissions/{id}/progress`（或 SSE `/judge-stream`）— 逐测试点判题进度（`lit` 0→10）。
6. `POST /api/code/run` — 运行样例，返回 stdout、耗时、内存。
7. `GET /api/submissions/{id}/testcases` — 逐测试点 `status`/`time`/`mem`/`input`/`expected`/`actual`（含隐藏点策略）。

**提交记录**
8. `GET /api/submissions` — 列表；支持 `status`（7 种）、`lang`、`range`（3/7/30 天）、`kw`、`sort`（`id`/`title`/`time`）、`page`。
9. `GET /api/submissions/{id}` — 提交详情（含代码快照）。
10. `GET /api/submissions/stats` — 总提交、AC 提交、AC 率、平均用时、本周提交/AC/覆盖题数。
11. `GET /api/problems/{pid}/submissions` — 同题提交轨迹（含"比上次多过几个点"的增量）。
12. `GET /api/submissions/distribution` — 语言分布、判题结果分布（受当前筛选影响）。

**作业**
13. `GET /api/assignments` — 列表；支持 `status`（`all`/`todo`/`doing`/`done`/`over`）、`sort`（`due`/`score`/`progress`）、`kw`、`page`。
14. `GET /api/assignments/{id}` — 作业详情。
15. `GET /api/assignments/{id}/problems` — **作业题目清单（当前完全没有，靠前端 `PLAN`）**。
16. `POST /api/assignments/{id}/hand-in` — 交作业（含 5 秒撤销 → 需要 `DELETE /hand-in` 或幂等回滚）。
17. `GET /api/assignments/summary` — 待完成/进行中/已完成/平均分、本周提交、AI 额度、即将截止（当前 8 个字段全靠前端 reduce + 写死）。

**AI 助教**
18. `POST /api/ai/ask`（或 SSE `/ai/stream`）— 入参 `pid`、`stage`(1-4)、`model`(`local`/`cloud`)、`text`；返回流式回答。**需配额校验与考试期 403**。
19. `GET /api/ai/quota?pid=` — 本题剩余提示次数（模板用 `quotaLeft`）。
20. `GET /api/ai/models` — 可用模型通道与降级策略（本地 Qwen2.5-Coder 7B / 云端 DeepSeek）。
21. `GET /api/ai/stages/{pid}` — 四阶段开放状态与解锁条件（`stage4Unlocked` 依赖 AC）。

**学情**
22. `GET /api/analytics/*` — 至少五个子视图：`overview`（`range` 参数 + `acRate`/`ac`/`wa`/`total`）、`trend`（按天分桶）、`tags`（掌握度/班级均值/症状/根因/建议/推荐题）、`problems`（`tries`/`score`/`at`/`rate`）、`heatmap`（最近 N 题的判分链）。

**考试**
23. `GET /api/exams/{id}` — 试卷下发（5 题题面 + 初始代码 + 分值 + 时限 + 策略）。
24. `POST /api/exams/{id}/run` — 考试内运行样例。
25. `POST /api/exams/{id}/questions/{qid}/submit` — 提交本题（取最后一次）。
26. `POST /api/exams/{id}/hand-in` — 交卷。
27. `GET /api/exams/{id}/remaining-time` — **服务端权威剩余时间**（当前 `secs` 写死、刷新重置）。
28. `POST /api/exams/{id}/events` — 切屏/失焦上报（当前只有文案，`未在源码中找到`实现）。

### C. 横向共性结论（对接前必须先定的 5 件事）

1. **判分链是最大公约数**：4 个页面（题库/提交记录/学情/工作台）都要"10 格测试点状态"，但 4 处各自伪造。后端应提供统一的 `status[]`（长度 = 测试点数），否则口径必然漂移。
2. **`pass` 是字符串、`time`/`mem` 带单位**：`'8/10'`、`'14ms'`、`'4210KB'` 全部靠 `parseInt` 剥（s-submissions.html:836、740）。应改为 `passCount`/`totalCount` 整数 + `timeMs`/`memKb` 数值。
3. **`left` 是预格式化中文**（`'2 天'`、`'已结束'`），排序靠 `parseInt`（s-assignments.html:651、670），跨月/跨年即错。应改为 `dueAt` 时间戳由后端算剩余。
4. **AI 使用记录只能"猜"**：s-assignments.html:605-635 用"是否交过题 + 提交里有没有非 AC"反推四阶段是否用过；s-analytics.html:861-866 的调用次数写死。需要独立的 `ai_stage_usage` 记账表。
5. **分类与关联关系全部硬编码在前端**：作业→题目（`PLAN`，s-assignments.html:554-560）、标签→题目（`TAG_PIDS`，s-analytics.html:752-759）、测试点→代码行（`CASE_LINE`，s-workbench.js:14）。这三张映射在真实系统里分别属于"作业编排""标签体系""判题器诊断"，都必须落到后端。
