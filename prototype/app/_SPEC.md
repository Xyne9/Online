# 智判 OJ 原型 · 页面开发规范

> 这份文件是并行开发的**唯一契约**。所有页面必须严格遵守，保证 15 个页面看起来是同一个产品。

---

## 一、文件与目录

```
prototype/app/
├── index.html              ✅ 门户（Lead 已做）
├── s-problems.html         ✅ 题库列表（Lead 已做，标杆页）
├── s-workbench.html        ✅ 做题工作台（Lead）
├── s-assignments.html      ✅ 我的作业
├── s-submissions.html      ✅ 提交记录
├── s-analytics.html        ✅ 学情分析
├── s-exam.html             ✅ 考试作答（Lead）
├── t-dashboard.html        ✅ 教师概览
├── t-problems.html         ✅ 题目管理
├── t-problem-edit.html     ✅ 出题编辑
├── t-assignments.html      ✅ 教学活动
├── t-analytics.html        ✅ 班级学情
├── t-monitor.html          ✅ 考试监考
├── t-plagiarism.html       ✅ 查重报告
├── a-models.html           ✅ 模型通道
├── a-audit.html            ✅ 调用审计与沙箱
├── index.css               ✅ 门户专用（Lead）
├── shell.js                🔒 共享：图标 / 导航 / 外壳 / 模拟数据 / 工具函数
├── vendor/alpine.min.js    🔒 Alpine 3.x
└── styles/
    ├── tokens.css          🔒 设计变量
    ├── components.css      🔒 基础组件
    ├── shell.css           🔒 外壳（侧栏 / 顶栏 / 页头 / 统计条 / 列表）
    └── pages.css           🔒 跨页模式（列表行 / 面板 / 步骤 / Diff / 趋势）
```

**🔒 = 只读。不要改这五个文件。** 页面专用的样式写在页面自己的 `<style>` 里，页面专用的数据写在页面自己的 `<script>` 里。

---

## 二、页面骨架（照抄，只换 data-* 和内容）

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>页面名 · 智判 OJ</title>
<link rel="stylesheet" href="styles/tokens.css">
<link rel="stylesheet" href="styles/components.css">
<link rel="stylesheet" href="styles/shell.css">
<link rel="stylesheet" href="styles/pages.css">
<style>/* 本页专用样式 */</style>
</head>
<body data-role="student" data-page="s-problems" data-crumb="题库|全部题目">

<div id="app"><main class="page" id="page-content" x-data="problemsPage()" x-cloak>
  <!-- 页面内容 -->
</main></div>

<script src="shell.js"></script>
<script src="vendor/alpine.min.js" defer></script>
<script>
function problemsPage(){ return { /* ... */ } }
</script>
</body>
</html>
```

**四个硬性约定**

| 项 | 说明 |
| --- | --- |
| `data-role` | `student` / `teacher` / `admin`，决定侧栏导航和用户卡 |
| `data-page` | 当前页 id，用于侧栏高亮。见下表 |
| `data-crumb` | 面包屑，用 `\|` 分隔，如 `题库\|全部题目` |
| `id="page-content"` | **必须有**，`shell.js` 靠它把内容搬进主区 |
| `x-cloak` | `<main>` 上必须有，避免 Alpine 初始化前闪一下 |
| `<script src="shell.js">` | **不能加 defer**，必须在 `</body>` 前同步执行 |
| `vendor/alpine.min.js` | **必须加 defer**，且放在 shell.js 之后 |

`data-page` 取值：`s-problems` `s-workbench` `s-assignments` `s-submissions` `s-analytics`
`t-dashboard` `t-problems` `t-assignments` `t-analytics` `t-monitor` `t-plagiarism`
`a-models` `a-audit`

---

## 三、`window.OJ` —— 共享数据与工具（shell.js 已提供）

```js
OJ.svg(name, cls)        // → SVG 字符串
OJ.problems              // 12 道题 {id,title,lvl,tags,ac,tried,status,score}
OJ.submissions           // 10 条提交 {id,pid,title,lang,status,score,pass,time,mem,at,first}
OJ.assignments           // 5 条任务 {id,title,type,typeName,pidCount,done,score,full,due,left,ai,status}
OJ.students              // 10 名学生 {name,sid,done,total,score,ai,last,risk}
OJ.models                // 4 条模型通道 {id,name,vendor,role,base,state,ttft,calls,tokens,cost}
OJ.aiCalls               // 10 条调用审计 {at,who,stage,model,route,ttft,tok,guard,note}
OJ.sandbox               // 5 个沙箱容器 {id,lang,user,state,cpu,mem,age}
OJ.plagiarism            // 5 条查重 {id,a,b,pid,title,sim,stage,state,tokens,level,verdict}

OJ.statusName(s)         // 'ac' → 'AC'
OJ.statusTag(s)          // → '<span class="tag ac">AC</span>'
OJ.lvl(n)                // → 难度点 HTML（含颜色）
OJ.lvlName(n)            // 1→'入门' 2→'简单' 3→'中等' 4→'较难' 5→'困难'
OJ.fmt(n)                // 1841 → '1.8K'
OJ.qs(key, default)      // 读地址参数
```

**页面要额外数据，自己在本页 `<script>` 里定义常量**，不要改 `shell.js`。

---

## 四、图标：`x-ic` 指令

```html
<span x-ic="'search'"></span>          <!-- <svg class="ic"> -->
<span x-ic="'x'" class="sm"></span>    <!-- 尺寸用 class: sm / lg / xl -->
<span x-ic="'play'" class="play"></span> <!-- 实心播放三角 -->
```

可用图标名（全部 24×24 / 1.5 描边）：

`logo book task clock chart gauge edit users scan cpu server plug coin monitor home search filter plus check checkC x xC alert info play send chevD chevR chevL arrowR arrowL arrowU arrowD more refresh download upload copy external settings logout code spark flag tag file layers diff bolt lock unlock bell eye trash sort grid calendar award target trend stop pause expand user key folder shield`

**不要用 emoji 当图标。**

---

## 五、可直接用的组件类（已写在共享 CSS 里）

### 按钮
```html
<button class="btn primary">主操作</button>
<button class="btn ghost">次操作</button>
<button class="btn soft">淡蓝</button>
<button class="btn danger">危险</button>
<button class="btn danger-ghost">危险描边</button>
<button class="btn text">文字按钮</button>
<button class="btn icon ghost"><span x-ic="'x'"></span></button>
```
尺寸加 `.sm`（26px）/ `.lg`（36px）；宽度加 `.block`。
禁用：`disabled`。处理中：`<span class="spin"></span>处理中`（**保持原宽**，不要改 padding）。

### 标签 / 徽标 / 头像
```html
<span class="tag">默认</span>
<span class="tag ac">AC</span>  <!-- 还有 wa tle mle re ce pac pending blue violet outline mono dot -->
<span class="badge">3</span>   <!-- 还有 gray blue -->
<span class="avatar">林</span> <!-- 还有 lg gray green red -->
```

### 表单
```html
<div class="field"><label>字段名</label><input class="input" placeholder="…"><span class="hint">说明</span></div>
<select class="select">…</select>
<textarea class="textarea"></textarea>
<label class="check"><input type="checkbox" checked>复选项</label>
<label class="switch"><input type="checkbox" checked>开关</label>
<div class="search"><span x-ic="'search'"></span><input class="input" type="search"></div>
```

### 卡片 / 面板
```html
<div class="card"><div class="card-head"><h3>标题</h3><span class="sub">说明</span></div>
  <div class="card-body">…</div><div class="card-foot">…</div></div>

<div class="panel"><div class="panel-h"><h3>标题</h3><span class="ph-act">…</span></div>
  <div class="panel-b flush">…</div></div>
```

### 表格
```html
<div class="table-wrap"><table class="tbl">
  <thead><tr><th>列</th><th class="num">数字列</th><th></th></tr></thead>
  <tbody><tr><td class="c-t">主文本</td><td class="num">42</td><td class="act">…</td></tr></tbody>
</table></div>
```
行状态：`tr.is-sel`（选中）/ `tr.is-danger`（告警）。

### 列表行
```html
<div class="listcard">
  <div class="li"><div class="li-main"><div class="li-t">标题</div><div class="li-s">副信息</div></div>
    <div class="li-act">…</div></div>
</div>
```

### 统计卡
```html
<div class="stats c4">
  <div class="stat"><div class="stat-k"><span x-ic="'users'"></span>标签</div>
    <div class="stat-v">128<span class="u">人</span></div>
    <div class="stat-d"><span class="up"><span x-ic="'arrowU'" class="sm"></span>12%</span>较上周</div></div>
</div>
```
`c3 / c4 / c5` 三种列数；`.stat-v.sm` 用于长数字。

### 进度 / 趋势 / 占比条
```html
<div class="bar"><i style="width:64%"></i></div>          <!-- 加 .thin 更细；.ac/.wa/.tle 换色 -->
<div class="trend"><i style="height:40%"></i><i class="hi" style="height:80%"></i></div>
<div class="stackbar"><i style="width:60%;background:var(--ac)"></i>…</div>
<div class="legend"><span style="color:var(--ac)">通过 60%</span>…</div>
```

### 键值 / 字段行
```html
<div class="kvcard"><div><dt>标签</dt><dd>值</dd></div>…</div>
<dl class="frow"><dt>字段</dt><dd>值</dd></dl>
```

### 抽屉 / 弹窗 / 提示
```html
<template x-if="open"><div>
  <div class="scrim" @click="open=false"></div>
  <aside class="drawer"><div class="drawer-head"><h3>标题</h3><span class="grow"></span>
    <button class="btn icon ghost" @click="open=false"><span x-ic="'x'"></span></button></div>
    <div class="drawer-body">…</div>
    <div class="drawer-foot"><span class="grow"></span><button class="btn ghost">取消</button>
      <button class="btn primary">确定</button></div></aside>
</div></template>
```
弹窗用 `.modal > .modal-head / .modal-body / .modal-foot`，外层同样要 `.scrim`。
提示条用 `.note`（加 `.warn/.err/.ok`）。

### 空态 / 加载 / 错误
```html
<div class="state"><span class="st-ico"><span x-ic="'search'" class="lg"></span></span>
  <h4>标题</h4><p>说明文字</p><button class="btn ghost sm">动作</button></div>
```
加 `.error` 换红色。

### 分页
```html
<div class="pager"><span>共 48 条</span><span class="grow"></span>
  <button class="pg" disabled><span x-ic="'chevL'" class="sm"></span></button>
  <button class="pg on">1</button><button class="pg">2</button>
  <button class="pg"><span x-ic="'chevR'" class="sm"></span></button></div>
```

### 页头 / 工具条 / 筛选
```html
<div class="page-head"><div class="ph-main"><h2>页面标题</h2><div class="ph-sub">一句话说明</div></div>
  <div class="ph-act"><button class="btn ghost">次</button><button class="btn primary">主</button></div></div>

<div class="toolbar"><div class="filters">
  <button class="chip on">全部<span class="n">12</span></button>
  <button class="chip">待办<span class="n">3</span></button>
</div><span class="spacer"></span>…</div>
```

### 两栏 / 三栏
`.split`（左内容 + 右 340px）、`.split.wide-r`（右 400px）、`.split.wide-l`（左 400px）、`.split2`、`.split3`
卡片网格：`.grid.c2 / .c3 / .c4`

### 代码 / Diff / 步骤 / 阶段锁
```html
<div class="codebox"><div class="cl"><b>1</b><span>…</span></div>
  <div class="cl add"><b>2</b><span>新增行</span></div>
  <div class="cl del"><b>3</b><span>删除行</span></div></div>

<div class="steps"><div class="step done"><span class="sdot"></span>
  <div><div class="st-t">步骤标题</div><div class="st-d">说明</div></div></div>
  <div class="step"><span class="sdot"></span>…</div>
  <div class="step wait"><span class="sdot"></span>…</div></div>

<span class="stagechip on">① 读题拆解</span>
<span class="stagechip lock"><span x-ic="'lock'" class="sm"></span>③ 排错诊断</span>
```

### 分段控件
```html
<div class="seg" x-data="{i:0}" :style="`--n:3`">
  <span class="seg-thumb" :style="`width:calc((100% - 6px)/3);transform:translateX(calc(${i} * (100% + 3px)))`"></span>
  <button class="seg-item" :class="{'is-on':i===0}" @click="i=0">选项 A</button>
  <button class="seg-item" :class="{'is-on':i===1}" @click="i=1">选项 B</button>
  <button class="seg-item" :class="{'is-on':i===2}" @click="i=2">选项 C</button>
</div>
```
（滑块宽度与位移按项数算；两项时宽度 `calc(50% - 4.5px)`，位移 `calc(100% + 3px)`。）

---

## 六、设计硬规则（来自 oil-ui-pro 视觉规范，违反要重做）

1. **字号只用 6 级**：`--fs-xs`11.5 / `--fs-sm`12.5 / `--fs-md`13 / `--fs-lg`15 / `--fs-xl`19 / `--fs-2xl`26。正文**不小于 13px**（辅助信息可用 12.5 / 11.5）。
2. **颜色只从 token 取**：`var(--blue)`、`var(--ink2)`、`var(--ac)`… 不写裸十六进制（`#fff` 除外）。
3. **强调色占比 ≤10%**。大面积用 `--panel` 白 + `--bg` 灰底，蓝色只用于主操作、当前项、关键数字。
4. **每个可点元素都要有 hover / active / focus / disabled 四态**。`focus-visible` 已有全局样式，不要覆盖。
5. **状态切换不改变尺寸**，不挤动旁边内容。处理中按钮保持原宽。
6. **不默认加眉标**（标题上方的小标签），不加卡片套卡片，不加每区块通栏 1px 横线，不加彩色单侧粗边线当装饰。
7. **不用 emoji 当图标**；不用星星 / 魔法棒 / 机器人代表 AI。
8. **中文正文字距 0**；全角标点；中英文之间留一个空格，全页一致。
9. **嵌套圆角同心**：内层圆角 ≈ 外层圆角 − 内边距。
10. **每个区块只讲一件事**。删掉重复解释和套话。
11. **表格数字列右对齐**并加 `font-variant-numeric:tabular-nums`（body 已有）。
12. **必须写空态**：列表无数据时给 `.state`，不要留白。

---

## 七、Alpine 写法约定

- 整页一个 `x-data="页面名Page()"`，函数定义在页面底部 `<script>`，返回对象。
- 用 `x-cloak` 防止闪烁；`x-show` 做显隐，`x-if` 做增删。
- 列表用 `<template x-for>` + `:key`。
- 页面初始状态读地址参数：`init(){ const s = OJ.qs('state'); … }`
- **每个页面至少实现 3 个真实可交互的动作**（筛选、切换、打开抽屉、提交、分页、排序等），不能是纯静态图。

---

## 八、验收（每个页面必须自己跑过）

```powershell
cd "D:\毕设\agent oj\prototype\app"
node "C:\Users\ZhuanZ.DESKTOP-PH97BKO\.agents\skills\oil-ui-pro\scripts\shoot.mjs" <页面>.html --size desktop --out _shots\<页面>
```

**通过标准**：
- 输出末行「检查通过：没有控制台错误、横向溢出或加载失败的图片」
- 打开 `_shots\<页面>\page.png` 亲眼看一遍：版式对齐、无遮挡、无空白大洞、无文字溢出
- 需要看交互后的状态：`--steps "click .chip; wait 300"` 再截一张

**不通过就改到通过为止。** 截图目录不要提交给别人，只是自查。

---

## 九、数据口径（全站一致，不要各写一套）

- 学生：**林昭**，学号 20220101，计算机 2022 级 1 班
- 教师：**周明远**，数据结构主讲
- 当前题目：**#1024 最大子段和**，难度中等，WA 80/100，首次失败在第 3 个测试点
- 时限 **1000ms**（Java/Py ×2），内存 **256MB**，隐藏测试点 **10 组**
- AI 阶段：① 读题拆解 ② 编码引导 ③ 排错诊断 ④ AC 复盘
- 云端模型 **DeepSeek-V3**，本地 **Qwen2.5-Coder 7B**（Ollama）
- 降级阈值 **8 秒**；护栏允许 **≤5 行**示例代码
- 作业 AI 策略：课后作业仅开 ①③、上限 **3 次**；考试全关（403）
- 查重两阶段：阶段一 AST 结构相似度（阈值 **75%**）、阶段二 语义向量（阈值 **85%**）
- 班级 48 人；本周新增提交 312 次；平均 AC 率 62%
