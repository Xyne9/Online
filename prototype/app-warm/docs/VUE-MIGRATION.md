# 学生做题工作台 · Vue 3 + Element Plus 迁移映射

把 `prototype/workbench/` 这套静态小样（`index.html` 587 行 + `styles.css` 1172 行 + `app.js` 185 行，Alpine 驱动）落到 PRD 指定的技术栈上：**Vue 3 + Vite + Pinia + Element Plus + Monaco Editor + Fetch EventSource**。

这份文档只回答三件事：**哪些东西换成 Element Plus 组件、哪些东西必须自己写、换的时候会踩什么坑**。所有组件版本与 props 都对着 Element Plus 官方文档核过，标注了来源。

**目录**

- [0. 先说结论](#0-先说结论)
- [1. 工程脚手架](#1-工程脚手架)
- [2. 目录结构](#2-目录结构)
- [3. 组件树映射](#3-组件树映射indexhtml--sfc)
- [4. 状态迁移](#4-状态迁移appjs--pinia)
- [5. 编辑器：手写 17 行 → Monaco](#5-编辑器手写-17-行--monaco最重的一块)
- [6. Element Plus 组件对照表](#6-element-plus-组件对照表)
- [7. 主题与设计 token 映射](#7-主题与设计-token-映射)
- [8. 布局与动效](#8-布局与动效)
- [9. 接口对接映射](#9-接口对接映射)
- [10. 迁移顺序](#10-迁移顺序)
- [11. 风险与取舍](#11-风险与取舍)
- [附录：事实来源](#附录事实来源)

---

## 0. 先说结论

| 判断 | 内容 |
| --- | --- |
| 换得掉 | 顶栏、面包屑、按钮、头像、标签、输入框、进度条、结果页、描述列表、模型分段控件 |
| **换不掉，必须自己写** | 判分链带（10 格 + 失败格放大 + 上浮说明卡）、64px 阶段窄轨、Router→Tutor→Guardrail 流水线卡、行内批注胶囊 |
| **不要换** | 聊天气泡（`el-message` 是全局 toast，不是气泡）、失败格说明卡（不是 popup，是常驻视觉元素）、整页五列 grid（`el-splitter` 还是 beta） |
| 真正的重活 | 编辑器：从「手写 17 行 `<div class="code-row">`」换成 Monaco + decoration + viewZone |

**换不掉的四处恰好是这套设计的全部识别度**，所以迁移不是「换成组件库」，是「组件库托住外围，核心手写保留」。这句话要写进毕设文档里，否则看起来像没用上 Element Plus。

---

## 1. 工程脚手架

```bash
npm create vite@latest oj-web -- --template vue
cd oj-web
npm i vue-router pinia element-plus @element-plus/icons-vue
npm i -D sass unplugin-auto-import unplugin-vue-components unplugin-element-plus
npm i monaco-editor @guolao/vue-monaco-editor
npm i @microsoft/fetch-event-source
```

| 依赖 | 用途 | 对应 PRD |
| --- | --- | --- |
| `element-plus` | UI 组件 | PRD 技术栈 |
| `sass` | 主题定制必须走 SCSS（`@forward ... with`） | 见 §7 |
| `unplugin-*` | 按需引入 + 让 SCSS 变量注入生效 | 见 §7.1 |
| `monaco-editor` | 代码编辑、防粘贴、行内批注 | PRD 技术栈 |
| `@microsoft/fetch-event-source` | SSE 流式对话 | PRD「Fetch EventSource」 |
| `pinia` | 全局状态（判题 / AI / 权限） | PRD 技术栈 |

**没有装 `axios`。** PRD 的技术栈里只写了 `Fetch EventSource`，没提 HTTP 库；而 `@microsoft/fetch-event-source` 本身就基于 `fetch`。全项目统一用 `fetch` 包一层 `request.js` 即可，少一个依赖也少一层错误处理。如果团队习惯 axios，加 `npm i axios` 不影响其余部分，只是 `request.js` 里换掉拦截器写法。

`vite.config.js` 关键两段：

```js
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import ElementPlus from 'unplugin-element-plus/vite'

export default defineConfig({
  resolve: {
    // ← 必须有：下面 additionalData 里的 @/ 要靠它解析。
    //   注意别写成 path.resolve(__dirname, 'src')：Vite 5 起的模板
    //   package.json 里带 "type": "module"，vite.config.js 是 ESM，
    //   __dirname 不存在，会直接报 ReferenceError。
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver({ importStyle: 'sass' })] }),
    ElementPlus({ useSource: true }),   // ← 主题定制必需，否则 .scss 变量不生效
  ],
  css: {
    preprocessorOptions: {
      scss: {
        // ← 这一行是主题定制能否生效的分水岭
        additionalData: `@use "@/styles/element/index.scss" as *;`,
      },
    },
  },
})
```

三个容易漏的点：

1. **`resolve.alias` 必须先配，且要用 `fileURLToPath` 写法。** 官方 theming 文档的示例里用的是 `~/` 别名，同样靠 `resolve.alias` 解析。`create-vite` 的 `vue` 模板生成的 `vite.config.js` 全文只有 `plugins: [vue()]`，没有任何别名配置，直接抄 `additionalData` 会报 `Can't find stylesheet to import`。而 Vite 5 起模板的 `package.json` 带 `"type": "module"`，配置文件按 ESM 执行，`__dirname` 是 `undefined`。
2. **`additionalData` 的值末尾必须带分号。** 它是被**拼在每个 `.scss` 文件最前面**的，不带分号会把下一个文件的第一个 token 吃进去。
3. **`ElementPlus({ useSource: true })` 不能省。** 它让 `unplugin-element-plus` 引入 `.scss` 源文件而不是编译后的 `.css`；省了它，`additionalData` 注入的变量无处可去，主题定制静默失效（页面能跑，但颜色还是 EP 默认蓝）。

---

## 2. 目录结构

```
src/
├── main.js
├── App.vue
├── router/index.js
├── styles/
│   ├── tokens.scss              # 原型 :root 的 21 个变量 → SCSS 变量（单一真源）
│   ├── element/index.scss       # @forward element-plus var.scss with (...)
│   ├── element/overrides.scss   # EP 组件级覆盖（.el-button 等）
│   └── base.scss                # reset / 字体 / 滚动条 / :focus-visible
├── stores/
│   ├── judge.js                 # 判题状态机 + 链带
│   ├── ai.js                    # 抽屉、模型、流水线、SSE 流
│   ├── problem.js               # 题面、编辑器内容
│   └── permission.js            # AI 阶段锁、考试模式 403
├── api/
│   ├── request.js               # 统一请求封装，解包 {code,message,data}（fetch 或 axios，见下）
│   ├── judge.js                 # 接口 1 / 2
│   ├── ai.js                    # 接口 3（SSE）
│   └── teaching.js              # 接口 4 / 5
├── composables/
│   ├── useJudgePolling.js       # 1s 轮询
│   ├── useSSEChat.js            # fetchEventSource 封装
│   ├── useResizable.js          # 中缝拖拽（替代 app.js 的 startDrag 组）
│   └── useReducedMotion.js      # prefers-reduced-motion
├── components/
│   ├── layout/AppTopbar.vue
│   ├── problem/ProblemPanel.vue
│   ├── editor/CodeEditor.vue        # Monaco 封装
│   ├── editor/InlineAnnotation.vue  # 行内批注胶囊（Monaco content widget）
│   ├── ai/AiDrawer.vue
│   ├── ai/ModelSwitch.vue           # el-segmented 封装
│   ├── ai/AgentPipeline.vue         # 手写流水线
│   ├── ai/AgentNodeCard.vue
│   ├── verdict/VerdictBand.vue      # 底部 143px
│   ├── verdict/CaseChain.vue        # 10 格
│   ├── verdict/CaseCell.vue
│   ├── verdict/FailCallout.vue      # 上浮说明卡
│   └── rail/StageRail.vue           # 64px 窄轨
└── views/WorkbenchView.vue          # 五列 grid 容器
```

---

## 3. 组件树映射（`index.html` → SFC）

原型是一个文件 587 行，按「一个区块一件事」拆。括号里是原型行号，方便对照。

| 原型区块 | 行号 | 迁到 | 说明 |
| --- | --- | --- | --- |
| `header.topbar` | 14–41 | `AppTopbar.vue` | 面包屑 + 主导航 + 倒计时 + 语言 + 运行/提交 + 头像 |
| `main.work` | 44–45 | `WorkbenchView.vue` | 五列 grid 容器，`--problem-w` / `--drawer-w` 两个 CSS 变量 |
| `section.problem` | 48–119 | `ProblemPanel.vue` | 题面 + spec + 两个样例 + 底部事实表 |
| `div.seam` | 122–123 | `useResizable.js` + 一个 `<div class="seam">` | 拖拽逻辑抽 composable |
| `section.editor` | 126–201 | `CodeEditor.vue` + `InlineAnnotation.vue` | **最大的重写**，见 §5 |
| `section.ai-drawer` | 204–490 | `AiDrawer.vue` + 子组件 | 四阶段 panel |
| `aside.ai-rail` | 493–515 | `StageRail.vue` | 手写 |
| `section.verdict` | 520–584 | `VerdictBand.vue` | 手写 |

拆完之后 `WorkbenchView.vue` 只剩布局，大约 60 行：

```vue
<template>
  <div class="wb" :class="{ 'drawer-closed': !ai.drawer }" :style="{ '--problem-w': problemW + 'px' }">
    <AppTopbar />
    <main class="work">
      <ProblemPanel class="col-problem" />
      <div class="seam" role="separator" aria-orientation="vertical" tabindex="0"
           @mousedown="startDrag" @keydown="seamKey" />
      <CodeEditor class="col-editor" />
      <AiDrawer class="col-drawer" />
      <StageRail class="col-rail" />
    </main>
    <VerdictBand />
  </div>
</template>
```

**注意 `--drawer-w` 不要漏。** 上面 `:style` 只给了 `--problem-w`（因为它要跟着拖拽变），`--drawer-w` 是固定值（`322px`，≤1280px 时 `296px`、≤1080px 时 `272px`），写在 `WorkbenchView` 的 `<style scoped>` 或 `tokens.scss` 里即可。两个变量缺一个，`.work` 的 `grid-template-columns` 就会因为 `var()` 无值而整列塌掉。

**另外 `:style` 用的是 `--problem-w`（无单位），模板里拼了 `'px'`；如果改成绑数字给 `style` 对象，Vue 会自动补 `px` 但不会补到 CSS 变量上**——CSS 变量里存的永远是字符串，`--problem-w: 392` 是非法长度，必须自己拼 `'392px'`。

---

## 4. 状态迁移（`app.js` → Pinia）

原型把所有状态塞在一个 `workbench()` 里，正好切成三个 store。**字段名建议原样保留**，这样从原型对照着抄逻辑不会串。

### 4.1 `stores/judge.js`

| Alpine | Pinia | 类型 | 说明 |
| --- | --- | --- | --- |
| `result: 'wa'` | `result` | `'idle' \| 'judging' \| 'wa' \| 'ac'` | 原型没有 `idle`，接后端后必须有 |
| `lit: 0` | `litCount` | `number` | 已判完的测试点数 |
| `runState: 'idle'` | `runState` | `'idle' \| 'running' \| 'done'` | 样例自测，不写提交表 |
| — | `submission` | `object \| null` | 接口 2 的 `data` 原样存 |
| `get judging()` | `computed` | `boolean` | `result === 'judging'` |
| `cellClass(i)` | `computed cellStates` | `string[]` | 改成一次性算好的数组，模板里不再调函数 |
| `cellSub(i)` | 同上，返回 `{cls, label}` | — | 原型拆成两个函数调两遍，迁移时合并 |

**关键改动：`cellClass` / `cellSub` 必须从「模板里调函数」改成「computed 数组」。**
原型这么写没问题（Alpine 每次重算也就 10 个格子），但 Vue 里在 `v-for` 里调方法，每次重渲染都会重跑 10 次，而且不好做 `TransitionGroup`。改成：

```js
const cellStates = computed(() =>
  Array.from({ length: total }, (_, idx) => {
    const i = idx + 1
    if (result.value === 'judging')
      return i <= litCount.value ? { cls: 'lit', label: '通过' } : { cls: '', label: '待判' }
    if (result.value === 'wa' && i === firstFailedCaseId.value)
      return { cls: 'fail', label: CASE_LABELS[idx] }
    // AC 时 10 格给 lit，整条链带的绿色由外层 .v-ac 统一接管
    return { cls: result.value === 'ac' ? 'lit' : '', label: CASE_LABELS[idx] }
  })
)
```

注意原型写死 `i === 3`，迁移时改成 `firstFailedCaseId`（接口 2 直接给这个字段）。

**`result === 'ac'` 时给格子加的是 `lit`，不是自创的 `ok`。** 原型 CSS 里格子只有 `.cell` / `.cell.lit` / `.cell.fail` / `.v-judging .cell` 四种状态选择器（`styles.css:1019-1055`），`ok` 这个类不存在。

**AC 的绿色不是格子自己给的，是外层 `.v-ac` 统一染的**（`styles.css:1075-1077`）：

```css
.v-ac .cell { background: var(--ac-bg); border-color: var(--ac-line); color: var(--ac) }
.v-ac .cell > span, .v-ac .cell > b { color: var(--ac) }
```

所以 `verdict` 根节点上的 `v-wa` / `v-judging` / `v-ac` 类**必须一起迁过去**（原型是 `:class="'v-' + result"`），漏了的话 AC 态会变成 10 个蓝格子。

**AC 态还有一点和 WA 不同**：`.v-wa` 下第 3 格 `.fail` 是 `flex:2.9`，`.v-ac` 下没有这个突变，10 格等分。所以切到 AC 时 `cellStates` 里不能残留任何 `fail`。

### 4.2 `stores/ai.js`

| Alpine | Pinia | 说明 |
| --- | --- | --- |
| `drawer: true` | `drawerOpen` | |
| `think: false` | `thinkOpen` | 思考过程折叠 |
| `stage: 3` | `stage` | 1..4，由 `unlockedAiStages` + `highlightAiStage3` 驱动 |
| `model: 'local'` | `model` | `'local' \| 'cloud'`，对应 `selectedChannelCode` |
| `chat: []` | `messages` | `{role, text, streaming}` |
| `replying: false` | `replying` | |
| `askText: ''` | `askText` | 用 `v-model` |
| `quotaLeft: 2` | `quotaLeft` | 来自 SSE `done.remainingQuota` |
| — | `pipeline: []` | 新增，来自 SSE `agent_step` |

### 4.3 `stores/problem.js` / `permission.js`

| Alpine | Pinia | 说明 |
| --- | --- | --- |
| `fixed: false` | `problem.code` + `problem.dirty` | 原型用 `fixed` 表示「已改对」，接后端后应改判「本地代码是否被改过」，实际对错由判题结果决定 |
| `hintOpen: false` | `problem.annotationOpen` | |
| `problemW: 392` | `useResizable` 内部 ref | 不需要全局 |
| — | `permission.mode` | `'practice' \| 'homework' \| 'exam'`，PRD 模块三 |
| — | `permission.hintLimit` | 课后作业的提示上限 |
| — | `permission.isExam` | 考试模式，所有 AI 请求后端 403 |

### 4.4 方法映射

| `app.js` | 迁移后 | 落点 |
| --- | --- | --- |
| `submit()` | `judge.submit()` → `POST /api/judge/submit` → 启动 1s 轮询 | `stores/judge.js` action |
| （原型用 `setTimeout` 模拟逐格） | `useJudgePolling` 按 `caseDetails` 推进 `litCount` | `composables/useJudgePolling.js` |
| `runSample()` | `judge.runSample()` → 只跑样例，不改 `result` | 注意 PRD：自测不触发 AI 状态变更 |
| `applyFix()` | `problem.applyFix()` → 改 Monaco 内容 + `annotationOpen = false` | 用 Monaco `executeEdits`，不要直接改字符串 |
| `setStage(n)` | `ai.setStage(n)` → 检查锁 → `drawerOpen = true` | 锁判断走 `permission` |
| `stageState(n)` | `computed stageStates` | 同 `cellStates`，别在模板里调 |
| `ask(text)` | `ai.ask()` → `useSSEChat` | 见 §9.2 |
| `startDrag/onDragMove/stopDrag/seamKey` | `useResizable` composable | 300–560px 夹取逻辑原样搬 |
| `init()` 里的 `?state=` 分派 | **删掉** | 那是小样的状态切换开关，正式页面靠路由 + 真实数据 |

> `?state=` 那 10 个分支是给小样截图用的。迁移时不要搬进生产代码，但**建议保留一个 `?mock=wa` 之类的开发开关**，方便做 UI 回归截图。

---

## 5. 编辑器：手写 17 行 → Monaco（最重的一块）

原型 `index.html:157-199` 是 17 行代码（18 个 `<div class="code-row">`，因为第 8 行有「未修复 / 已修复」两个分支，用 `x-show="!fixed"` / `x-show="fixed"` 互斥显示）。每行 `<span class="ln">` + `<span class="src">`，语法高亮靠 `<span class="k">` / `<span class="n">` 手标。行内批注是一个 `<button class="annot">` 插在第 8 行的 `.src` 里（`index.html:167-174`），展开条 `.hintbar` 紧跟在第 8 行后面占一个网格行（`index.html:177-188`，同样有未修复 / 已修复两份）。

### 5.1 三样东西各自怎么落

| 原型元素 | Monaco 对应能力 | 关键 API |
| --- | --- | --- |
| 17 行代码 + 行号 + 语法色 | Monaco 原生 | `monaco.editor.create`，行号/着色全自动，`.src .k` 那套手标 span 直接删 |
| 行号高亮（`.code-row.cur`，第 8 行蓝底蓝号 + 左侧 2px 蓝条） | 行装饰 | `editor.createDecorationsCollection([...])` + `isWholeLine: true` + `className` |
| 行内批注胶囊 `.annot` | **内容部件**（content widget） | `editor.addContentWidget({ position, domNode })` |
| 展开条 `.hintbar` | **视图区**（view zone） | `editor.changeViewZones(accessor => accessor.addZone({ afterLineNumber: 8, heightInLines: 3, domNode }))` |
| 防粘贴（PRD 要求） | DOM 事件拦截 | `editor.onDidPaste` + `editor.getModel().pushStackElement()` 或直接阻止 |

> **用 `createDecorationsCollection` 而不是 `deltaDecorations`。** 后者在 Monaco 的 changelog 里已被 soft-deprecate（*"Soft deprecation of ICodeEditor.deltaDecorations, no adoption required. IEditor.createDecorationsCollection API should be used instead."*），而且返回的 id 数组在 `executeEdits` 之后容易错位（[microsoft/monaco-editor#442](https://github.com/microsoft/monaco-editor/issues/442)）。`createDecorationsCollection` 返回一个集合对象，`.set([...])` 更新、`.clear()` 清空，跟着编辑器生命周期走，更适合 Vue 的 `watch`。

> **`@guolao/vue-monaco-editor` 要先 `loader.config`。** 它默认从 CDN 加载 Monaco，必须在使用前调用 `loader.config({ paths: { vs: '...' } })`；要打进包里则 `import * as monaco from 'monaco-editor'` 再 `loader.config({ monaco })`。漏这一步会报 `loader is not defined` 或从 CDN 拉取失败（[npm](https://www.npmjs.com/package/@guolao/vue-monaco-editor)）。**内网/离线环境必须走第二种**，因为毕设演示环境不一定能连 jsdelivr。

### 5.2 行高对齐

原型 `.code-row{min-height:32px;line-height:32px}`，`.code-row .src{font-size:14px}`。Monaco 对应：

```js
monaco.editor.create(el, {
  value: code,
  language: 'cpp',
  fontSize: 14,
  lineHeight: 32,          // ← 和原型 32px 行高一致
  fontFamily: '"Cascadia Mono", Consolas, "Courier New", monospace',
  minimap: { enabled: false },
  scrollBeyondLastLine: false,
  renderLineHighlight: 'none',   // 我们自己用装饰画，避免和 Monaco 默认高亮打架
  padding: { top: 12, bottom: 12 },
  glyphMargin: false,
  folding: false,
})
```

`renderLineHighlight: 'none'` 这一条别漏——默认的 `'line'` 会画一条灰底，和原型的 `#F3F7FE` 蓝底冲突，两层叠上去很脏。

### 5.3 行内批注胶囊

原型是 `display:inline-flex` 插在行尾。Monaco 里做「行尾跟着代码的胶囊」只有 content widget 一条路：

```js
const dom = document.createElement('button')
dom.className = 'annot'
dom.innerHTML = '<i>!</i>全负时 ans 停在 0'
const widget = {
  getId: () => 'annot-line8',
  getDomNode: () => dom,
  getPosition: () => ({ position: { lineNumber: 8, column: 1 }, preference: [0] }),
}
editor.addContentWidget(widget)
```

`preference: [0]` 是 `EXACT`，让胶囊贴在第 8 行左侧；要贴行尾就把 `column` 设成该行长度 + 1。

**两个坑**：

1. **content widget 的 DOM 在 Monaco 的 overflow guard 层里**，原型 `.annot` 上的 `margin-left:18px` 和 `vertical-align:2px` 会失效（不是行内流），要改成 `position:absolute; top:...` 或让 widget 自己包一层 flex。建议迁移时把 `.annot` 的样式拆成 `.annot`（外观）+ `.annot-slot`（定位），外观部分原样复用。
2. **`getPosition()` 每次布局都会重新调用**，所以 `column` 不能写死成 `1` 之后就不管——如果胶囊要贴行尾，得在 `getPosition` 里现算 `model.getLineMaxColumn(8)`，否则用户在第 8 行敲字后胶囊不会跟着动。

### 5.4 展开条用 view zone

```js
let zoneId = null
watch(annotationOpen, (open) => {
  editor.changeViewZones((a) => {
    if (zoneId !== null) { a.removeZone(zoneId); zoneId = null }
    if (!open) return
    const dom = document.createElement('div')
    dom.className = 'hintbar-host'
    zoneId = a.addZone({ afterLineNumber: 8, heightInLines: 2, domNode: dom })
    // 把 Vue 组件挂到这个 dom 上，或者用 render() 手动挂
  })
})
```

原型 `.hintbar` 的 `animation:slide-down .2s` 可以保留，但 view zone 高度是整数行，动画过程中高度突变会闪。**建议迁移时去掉高度动画，只保留 opacity + translateY**，和原型里 `slide-down` 的关键帧一致（原型的 keyframes 本来也只动 opacity 和 translateY，不动高度——所以直接能复用）。

---

## 6. Element Plus 组件对照表

### 6.1 顶栏（`index.html:14-41`）

| 原型 | Element Plus | 版本/说明 |
| --- | --- | --- |
| `.crumbs` 面包屑 | `el-breadcrumb` + `el-breadcrumb-item` | 稳定。`separator` 可换成 `separator-icon` |
| `.mainnav` 5 项导航 | **手写** `<nav>` + `el-link` | 原型的 `.mainnav span` 是胶囊底色的 tab，`el-tabs` 会带下划线，`el-menu` 会带竖排语义，都不合适。用 `el-link` 加自定义 class 最省事 |
| `.deadline` 剩余 4 天 | `el-tag type="warning" effect="light"` | `el-tag` 的 `type` 支持 `primary/success/warning/info/danger`，`effect` 支持 `dark/light/plain` |
| `.lang` C++ 17 | `el-select` + `el-option` | 语言切换。原型是静态 chip，正式页要能选 |
| `.btn.ghost` 运行 | `el-button` | 原型 `.btn` 是 **30px**（`styles.css:105-111`），不是 `size="small"` 的 24px。按 §7.1 把 `$common-component-size.default` 改成 30px 之后，用默认档即可，不要写 `size="small"` |
| `.btn.primary` 提交 | `el-button type="primary" :loading="judging"` | 同样默认档（30px）。见下方「宽度抖动」坑 || `.user` + `.avatar` | `el-avatar :size="22"` | `size` 数字即可；文字头像用默认插槽 |

**坑 1：`:loading` 会让按钮变宽，而且 `loading` 插槽不能替换正文。**

看 `packages/components/button/src/button.vue` 的模板（已核实）：

```vue
<template v-if="loading">
  <slot v-if="$slots.loading" name="loading" />
  <el-icon v-else :class="ns.is('loading')"><component :is="loadingIcon" /></el-icon>
</template>
<el-icon v-else-if="icon || $slots.icon">…</el-icon>
<span v-if="$slots.default" :class="ns.e('text')"><slot /></span>
```

注意 `loading` 插槽和默认插槽是**并列渲染**的：写了 `#loading` 之后，「提交」两个字还在后面跟着，会变成「判题中提交」。所以不要用插槽塞文案。

原型 `.btn.primary` 提交时是「保持原宽 + 内嵌 `.spin` + 文字变『判题中』」。最稳的迁法是**固定宽度 + 文字用三元切换**：

```vue
<el-button type="primary" :loading="judging" class="btn-submit">
  {{ judging ? '判题中' : '提交' }}
</el-button>
```

```css
.btn-submit { width: 96px; padding: 0; }
```

宽度写死之后，转圈图标出现也不会挤动顶栏右侧其他元素。

**坑 2：`--el-component-size` 改 CSS 变量对 `el-button` 无效。**

EP 的尺寸是两套机制（已核实源码）：

| 组件 | 高度来源 | 能否用 CSS 变量改 |
| --- | --- | --- |
| `el-input` | `@include css-var-from-global('input-height','component-size')` → `--el-input-height: var(--el-component-size)` | **能**，运行时改 `--el-component-size` 立刻生效 |
| `el-button` | `height: map.get($input-height, 'default')` —— **编译期**把 32px 写死进 CSS | **不能**，改 CSS 变量无效 |

`$common-component-size` 的默认值是 `('large': 40px, 'default': 32px, 'small': 24px)`（`common/var.scss:233-241`），`$input-height: map.merge($common-component-size, $input-height)` 继承它（`common/var.scss:621-622`）。

原型用的是 30px（`.btn`，`styles.css:105-111`）/ 28px（`.btn.primary.sm`）/ 24px（`.ibtn`），中间那档 30px 没有对应 size。**必须走 SCSS 配置，不能走 CSS 变量**：

```scss
// src/styles/element/index.scss
@forward 'element-plus/theme-chalk/src/common/var.scss' with (
  $common-component-size: ('large': 40px, 'default': 30px, 'small': 24px),
  // 横向内边距也一起收：默认档 16px，原型 .btn 是 14px
  $button-padding-horizontal: ('large': 20px, 'default': 14px, 'small': 12px),
);
```

`$button-padding-horizontal` 的默认值是 `('large':20px, 'default':16px, 'small':12px)`（`common/var.scss:831-839`）。原型的 `.btn` 是 `padding:0 14px`，不调这一项的话按钮会比原型宽 4px。

**这个改动是全局连带的**，改一处会同时影响：

| 组件 | 受影响的属性 | 源码位置 |
| --- | --- | --- |
| `el-button` | `height`（默认档编译期取值） | `button.scss:29` |
| `el-button` | `size="large"/"small"` 档的 `height`（走 `--el-button-size`） | `button.scss:294-301` |
| `el-button` | `padding`（走 `$button-padding-horizontal`，与尺寸档无关，要单独配） | `common/var.scss:831-839` |
| `el-input` | `--el-input-height` → `line-height`、内部 `<input>` 高度 | `input.scss:174,180` |
| `el-segmented` | `min-height` | `segmented.scss:62` |

所以 30px 一改，模型分段控件的最小高度也跟着从 32 降到 30，双行内容（约 35px）照样撑开它，不用再单独处理。

### 6.2 题面（`index.html:48-119`）

| 原型 | Element Plus | 说明 |
| --- | --- | --- |
| `.pid` #1024 | `el-tag size="small"` 或纯文本 | 建议纯文本，编号不是「标签」语义 |
| `.lvl` 中等 | `el-tag` | 原型是蓝底蓝字 `#EFF6FF`/`#2563EB`，`el-tag type="primary" effect="light"` 接近，但要按 §7.2 对齐色值 |
| `.prob-body` 滚动区 | **保留 `overflow-y:auto`** | 不建议换 `el-scrollbar`：原型的底部 18px `mask-image` 渐隐是滚动容器上的，`el-scrollbar` 会多一层 wrap，渐隐要挂到 `.el-scrollbar__wrap` 上，反而麻烦 |
| `dl.prob-info` 5 组 dt/dd | `el-descriptions :column="2"` + `el-descriptions-item` | 原型是 `grid-template-columns:1fr 1fr; gap:5px 12px`，`el-descriptions` 的 `column` 和 `border` 都能对上。已核实：`column` 默认 **3**，`direction` 默认 `horizontal`，`label-width` 是 **2.8.8** 起才有的（`descriptions.md:57-62`）。原型是「标签在左、值在右」的横向两列，所以 `:column="2"` 即可；若标签宽度不齐，用 `label-width` 或每个 item 上的 `label-width`（2.8.8+）压一下 |
| `.eg` 两个样例 | 手写 | 输入/输出对照块，组件库没有对应物 |
| `.note` 两条说明 | 手写 | |

### 6.3 AI 抽屉（`index.html:204-490`）

| 原型 | Element Plus | 说明 |
| --- | --- | --- |
| `.ai-icon` | `el-icon` + 自定义 SVG | EP 图标没有「助教」语义的图标，别硬套 `MagicStick`（原型 README 里明确列了「星星/魔法棒/机器人代表 AI」是禁止项） |
| `.ai-stage` 阶段胶囊 | `el-tag size="small" effect="light"` | |
| **`.seg` 模型分段控件** | **`el-segmented`** | 见 §6.4，这块最值得换 |
| `.think` 思考过程折叠 | `el-collapse-transition` 或手写 | 原型用 `grid-template-rows:0fr→1fr` 做的折叠（`styles.css:578-583`），这个方案比 `el-collapse-transition` 更好——`el-collapse-transition` 是 JS 量高度，和原型的内联样式冲突。**建议保留手写**，见 §8.3 |
| `.pstep` 流水线节点 | 手写 | 见 §6.5 |
| `.tool-call` 工具调用卡 | 手写 + `el-tag` | |
| `.progress` 86% 进度条 | `el-progress :percentage="86" :stroke-width="3" :show-text="false"` | 已核实：`percentage` 必填 0–100，`stroke-width` 控制条粗，正好 3px |
| `.ai-body` 正文 | 手写 + `markdown-it` 渲染 | PRD 要求「支持 Markdown 与公式渲染」→ 再挂 `katex` |
| `.msg` 聊天气泡 | **手写** | `el-message` 是全局 toast，不是气泡，别混 |
| `.msg.typing` 三点打字 | 手写 | |
| `.ai-next .chip` 快捷胶囊 | `el-button size="small" text` 或手写 | 原型是「边框 + 悬停变蓝底」，`el-button` 的 `text` 模式没边框，`plain` 模式底色又不对，手写更省事 |
| `.ai-pin` 代码定位卡 | 手写 + `el-tag` | |
| `dl.ai-info` | `el-descriptions :column="1" size="small"` | |
| `.locked-panel` ④ 未解锁 | **`el-result`** | 见 §6.6，这块最值得换 |
| `.ai-quota` 剩余次数 | `el-tag` | |
| `.ai-switch` 切换按钮 | `el-button size="small" text` | |
| `.ai-ask` 追问输入框 | `el-input` + `el-button` | 见下方「无边框输入框」坑 |
| `.ai-ask:focus-within` 光圈 | 用 `el-input` 的 `:focus-within` 覆盖 | 原型是 `border-color:var(--blue-soft); box-shadow:0 0 0 3px rgba(37,99,235,.08)` |

**坑 3：`el-input` 去边框。**
原型的 `.ai-ask` 是一个**外层带边框的容器**，里面是裸 `<input>`。迁到 `el-input` 有两条路：
- **推荐**：不用 `el-input` 的边框，把 `el-input` 塞进现有的 `.ai-ask` 容器，给 `el-input` 加 `class="ask-input"`，覆盖 `--el-input-border-color: transparent`、`--el-input-bg-color: transparent`、`--el-input-hover-border-color: transparent`、`--el-input-focus-border-color: transparent`；
- 或者用 `el-input` 自带边框，删掉 `.ai-ask` 那层壳。但这样 `:focus-within` 的 3px 柔光圈就没了（EP 用的是 1px 边框变色），视觉上会比原型硬。

**注意 EP 的输入框边框不是 `border`，是 `box-shadow`。** 已核实源码：`input.scss:225-259` 的 `.el-input__wrapper` 用 `@include inset-input-border(...)`，而这个 mixin（`mixins/mixins.scss`）展开就是 `box-shadow: 0 0 0 1px $color inset`。所以：

- **没有 `--el-input-wrapper-shadow` 这个变量**（网上流传的写法是错的），要覆盖就直接写 `box-shadow`；
- 想去掉边框，最稳的是 `--el-input-border-color: transparent` 这一类变量，而不是 `border: none`（写 `border:none` 完全没效果）。

```scss
.ask-input {
  --el-input-border-color: transparent;
  --el-input-hover-border-color: transparent;
  --el-input-focus-border-color: transparent;
  --el-input-bg-color: transparent;
  .el-input__wrapper { padding: 0; box-shadow: none; }
}
```

**坑 4：`el-input` 的高度。**
`el-input` 的高度是 `@include css-var-from-global('input-height', 'component-size')`（`input.scss:174`），也就是运行时读 `--el-component-size`，所以**改 CSS 变量对它是有效的**（这点和 `el-button` 相反，见坑 2）。原型 `.ai-ask` 是 30px、`.ask-btn` 是 22px。改 `--el-component-size` 到 30px 之后，`.ask-btn` 还需要单独设 `height:22px`。

### 6.4 模型分段控件 → `el-segmented`（重点）

原型 `.seg` 是手写：外层灰底 `#EAEFF6` + 一个 `.seg-thumb` 白卡片绝对定位 + `transform:translateX(calc(100% + 3px))` 做滑块（`styles.css:474-504`）。这个交互 `el-segmented` 天生就有。

**已核实的官方事实**（来源：Element Plus 官方 `docs/en-US/component/segmented.md`，2.7.0 引入）：

| 项 | 值 |
| --- | --- |
| 引入版本 | **2.7.0** |
| `v-model` | `model-value / v-model`，类型 `string \| number \| boolean` |
| `options` | `Option[]`，`Option = Record<string, any> \| string \| number \| boolean` |
| `block` | `boolean`，`true` 时撑满父宽（原型正是撑满抽屉的 322px 内宽） |
| `size` | `'' \| 'large' \| 'default' \| 'small'` |
| `direction` | `'horizontal' \| 'vertical'`，**2.8.7** 起 |
| `props` | 别名配置（`value` / `label` / `disabled`），**2.9.8** 起 |
| `change` | `(val) => void` |
| **默认插槽** | `default`，作用域 `{ item: Option }` —— **双行内容（名称 + 模型名）可以放进默认插槽** |

用法：

```vue
<el-segmented v-model="ai.model" :options="modelOptions" block class="model-switch">
  <template #default="{ item }">
    <span class="seg-name">{{ item.name }}</span>
    <span class="seg-model">{{ item.model }}</span>
  </template>
</el-segmented>
```

```js
const modelOptions = [
  { value: 'local', name: '本地极速', model: 'Qwen2.5-Coder 7B' },
  { value: 'cloud', name: '云端',     model: 'DeepSeek' },
]
```

**坑 5：`el-segmented__item-label` 带 `utils-ellipsis`，双行内容必须是块级元素。**

看 `packages/theme-chalk/src/segmented.scss:147-154`（已核实）：

```scss
@include e(item-label) {
  flex: 1;
  text-align: center;
  line-height: normal;
  @include utils-ellipsis;     // ← overflow:hidden; text-overflow:ellipsis; white-space:nowrap
  transition: color 0.3s;
  z-index: 1;
}
```

`utils-ellipsis` 展开是 `overflow:hidden; text-overflow:ellipsis; white-space:nowrap`。槽里的两个 `<span>` 如果保持 `inline`，会被 `white-space:nowrap` 压成一行然后被省略号截断——「本地极速 Qwen2.5-Co…」。

所以插槽内容里两个 span **必须 `display:block`**（原型 `.seg-name` / `.seg-model` 本来就是 `display:block`，照抄即可，不会踩这个坑；但如果是新写的，很容易漏）：

```css
.model-switch .seg-name  { display: block; font-size: 12.5px; line-height: 1.5; color: #475569; }
.model-switch .seg-model { display: block; font-family: var(--oj-mono); font-size: 11px; line-height: 1.5; color: #475569; }
```

改成块级之后 `line-height:normal` 会被覆盖成 1.5，两行总高约 35px，`.el-segmented` 的 `min-height`（30px）会自动被撑高，滑块 `height:100%` 跟着拉伸，不用额外处理。

**坑 6：滑块是 JS 量的，没有 CSS-only 方案，也没法关。**

`packages/components/segmented/src/segmented.vue` 的实现（已核实）：

```js
const updateSelect = () => {
  const selectedItem = segmentedRef.value.querySelector('.is-selected')
  state.width = selectedItem.offsetWidth
  state.translateX = selectedItem.offsetLeft
  ...
}
const selectedStyle = computed(() => ({
  width: `${state.width}px`,
  transform: `translateX(${state.translateX}px)`,
  display: state.isInit ? 'block' : 'none',
}))
useResizeObserver(segmentedRef, updateSelect)
watch(activeElement, updateSelect)
```

三个后果：

1. **没有 prop 能关掉滑块**，也没有 CSS 变量能控它——要关只能 `display:none` 掉 `.el-segmented__item-selected`，但那等于放弃选中态视觉，没有意义。想保留原型的白卡片滑块，只能接受这套 JS 测量。
2. **首帧滑块是 `display:none`**（`state.isInit` 初始为 `false`，要等第一次 `updateSelect` 跑完）。原型 `.seg-thumb` 是常驻的。SSR 或慢挂载时会看到「先没有滑块、突然出现」的闪一下。介意的话给 `.el-segmented__item-selected { opacity: 0 }` 加个 `transition: opacity .2s` 之类的过渡遮掉，或者直接接受（实际首帧差不到一帧）。
3. **滑块位移是 `offsetLeft` 算的**，不是 `calc(100%)`。所以原型那句 `transform:translateX(calc(100% + 3px))` 在 EP 里没有对应物——好消息是也不用你算了。

**坑 7：`el-segmented` 的默认配色和原型相反。**
官方 CSS 变量（来源：`common/var.scss:909-923` 与 `segmented.scss`，已核实）：

```scss
$segmented: (
  'color': getCssVar('text-color', 'regular'),
  'bg-color': getCssVar('fill-color', 'light'),
  'padding': 2px,
  'item-selected-color': getCssVar('color-white'),        // ← 选中项文字是白色
  'item-selected-bg-color': getCssVar('color-primary'),   // ← 选中项底色是主题蓝
  'item-selected-disabled-bg-color': getCssVar('color-primary', 'light-5'),
  'item-hover-color': getCssVar('text-color', 'primary'),
  'item-hover-bg-color': getCssVar('fill-color', 'dark'),
  'item-active-bg-color': getCssVar('fill-color', 'darker'),
  'item-disabled-color': getCssVar('text-color', 'placeholder'),
);
```

也就是说默认是「蓝底白字」。原型是「灰底上浮一张白卡片、文字变深」。**必须覆盖 6 个变量 + 2 处结构样式**：

```scss
.model-switch {
  --el-segmented-bg-color: #EAEFF6;
  --el-segmented-padding: 3px;
  --el-segmented-item-selected-bg-color: #FFFFFF;
  --el-segmented-item-selected-color: #0F172A;
  --el-segmented-item-hover-bg-color: transparent;
  --el-segmented-item-hover-color: #475569;
  border-radius: 7px;

  // 原型 .seg-item 是 padding:3px 6px，EP 默认 0 11px
  .el-segmented__item { padding: 3px 6px; border-radius: 5px; }

  // 原型 .seg-thumb 是两层阴影（接触影 + 1px 描边影），EP 滑块只有背景色
  .el-segmented__item-selected {
    box-shadow: 0 1px 2px rgba(15,23,42,.09), 0 0 0 1px rgba(15,23,42,.04);
    border-radius: 5px;
  }
}
```

两处结构样式都不能省：不覆盖 `padding` 的话两个选项横向内边距 22px，文字被挤；不加 `box-shadow` 的话滑块「浮」不起来，看不出是张卡片。

**坑 8：滑块过渡写死 `all 0.3s`，而且颜色过渡也是 0.3s。**

`segmented.scss:85` 是 `transition: all 0.3s`，`segmented.scss:152`（`.el-segmented__item-label`）是 `transition: color 0.3s`。两条都没走 `--el-transition-duration` 变量，改全局过渡时长对它们无效。原型是 `.24s var(--ease)`（`cubic-bezier(.32,.72,.28,1)`）。要一致就覆盖：

```scss
.model-switch {
  .el-segmented__item-selected {
    transition: transform .24s cubic-bezier(.32,.72,.28,1),
                width     .24s cubic-bezier(.32,.72,.28,1);
  }
  .el-segmented__item-label { transition: color .15s; }
}
```

注意写成 `transform` + `width` 两条，别写 `all`——`all` 会把 `display`、`box-shadow` 也带上，首帧从 `display:none` 变 `block` 时会多一次无意义的过渡。

**结论：这块值得换，但省下的没有想象中多。** 手写版是 56 行 CSS + 一行内联位移；EP 版是一个组件 + 约 20 行覆盖（4 个坑里的 3 个都要覆盖）。真正白送的是**键盘可达性**：EP 内部是 `<input type="radio" name="...">` + `role="radiogroup"`（`segmented.vue:7-25`），方向键切换、`:focus-visible` 焦点环都是现成的，原型里没有。如果后面要接表单校验（`el-form` + `el-form-item`），也只有用 `el-segmented` 才能拿到 `validate-event` 和 `useFormSize` 的联动。

### 6.5 流水线 → 必须手写

原型 `.pstep` 的结构是「16px 节点列 + 1fr 卡片列，节点间用 `::after` 画竖线，三种状态（done 实线 / active 虚线 + 空心节点 / wait 灰虚线）」（`styles.css:592-645`）。

Element Plus 没有对应组件。`el-timeline` 看着像，但：
- `el-timeline-item` 的节点只有实心圆，没有「空心 + 2px 描边」的 active 态；
- 连线是 `el-timeline` 自己画的，`::after` 那套 `repeating-linear-gradient` 虚线插不进去；
- 每个节点右侧是 `el-card`，原型的卡片是 5px 圆角 + 虚线边框（wait 态），`el-card` 强制白底 + 阴影。

**结论：手写，原样搬 CSS。** 这是整套设计里「智能体协同」这个卖点的可视载体，不能为了用组件库而变形。

迁移时唯一要改的是 `pstep` 的状态从「CSS 类」变成「数据驱动」：

```js
// stores/ai.js
const pipeline = ref([
  { role: 'Router',    state: 'done',   line: '已识别为「阶段③ 排错诊断」', meta: '本地 qwen2.5-coder:7b · 0.4s' },
  { role: 'Tutor',     state: 'active', line: '取回第 3 号测试点特征，正在生成回复', meta: '本地 qwen2.5-coder:7b',
    tool: { name: 'getFailedTestCaseFeature(1024)', ok: true }, progress: 86 },
  { role: 'Guardrail', state: 'wait',   line: '规则：回复不得出现超过 5 行完整解题代码', meta: '命中即拦截并重写，不直接推送给学生' },
])
```

`agent_step` 事件进来就改 `state`，见 §9.2。

### 6.6 ④ 未解锁面板 → `el-result`

原型 `.locked-panel` 是「虚线框 + 圆形虚线节点 + 标题 + 说明 + 一个按钮」（`styles.css:800-820`）。

**已核实的官方事实**（来源：`docs/en-US/component/result.md`）：

| 项 | 值 |
| --- | --- |
| Attributes | `title`、`sub-title`、`icon`（`'primary'(2.9.11) \| 'success' \| 'warning' \| 'info' \| 'error'`，默认 `info`） |
| Slots | `icon`、`title`、`sub-title`、`extra` |

```vue
<el-result icon="info" class="locked-panel">
  <template #icon><span class="locked-node">④</span></template>
  <template #title>阶段④ AC 复盘未解锁</template>
  <template #sub-title>先在本题拿到满分（AC），AI 才会带你复盘复杂度</template>
  <template #extra>
    <el-button type="primary" size="small" @click="judge.submit()">再提交一次</el-button>
  </template>
</el-result>
```

**坑 9：`el-result` 的 6 个间距全是 CSS 变量，但默认值是按「整页结果页」设计的。**

`result.scss:16-37` 与 `common/var.scss:1599-1610`（已核实）：

| 变量 | 默认值 | 原型值 | 处理 |
| --- | --- | --- | --- |
| `--el-result-padding` | `40px 30px` | `20px 16px` | 覆盖 |
| `--el-result-icon-font-size` | `64px` | 15px（`.lock-node`） | 覆盖 |
| `--el-result-title-font-size` | `20px` | 14px（`.locked-panel h2`） | 覆盖 |
| `--el-result-title-margin-top` | `20px` | — | 覆盖为 `8px` |
| `--el-result-subtitle-margin-top` | `10px` | — | 覆盖为 `4px` |
| `--el-result-extra-margin-top` | `30px` | — | 覆盖为 `10px` |

好消息是**这 6 个都是 CSS 变量，不用走 SCSS**，写在组件 class 上即可（官方 theming 文档也推荐按 class 覆盖而非 `:root`，性能更好）：

```scss
.locked-panel {
  --el-result-padding: 20px 16px;
  --el-result-icon-font-size: 30px;      // 容器 30px，里面再放 15px 的 .lock-node
  --el-result-title-font-size: 14px;
  --el-result-title-margin-top: 8px;
  --el-result-subtitle-margin-top: 4px;
  --el-result-extra-margin-top: 10px;
  border: 1px dashed #CBD5E1;
  border-radius: 6px;
  background: #fff;
}
```

注意 `icon-font-size` 控制的是 `#icon` 插槽外层容器的宽高（`result.scss:18-23`），不是图标本身。原型 `.lock-node` 是 15px 的虚线圆，所以容器给 30px 留点余量，里面那个 `.lock-node` 保持 15px 居中即可。

### 6.7 64px 阶段窄轨 → 必须手写

原型 `.stage` 是「17px 圆形节点（内含数字/勾）+ 10px 标签」的竖排按钮，三态（done 绿底 / cur 蓝底 / lock 虚线）。EP 没有对应组件。

`el-steps direction="vertical"` 最接近，但它的 `space` 是固定高度，塞不进 64px 宽；而且它的节点是实心圆 + 右侧文字横排，和「节点在上、标签在下、居中」完全相反。

**结论：手写。** 但把焦点环、`aria-current`、键盘方向键这三样补上（原型已有 `:focus-visible`，迁移时别丢）。

### 6.8 判分链带 → 必须手写

这是整套设计的主视觉：10 个 `li.cell`，失败格 `flex:2.9`、高 70px、红底白字、顶部一个 12px 旋转 45° 的菱形指针、上方浮一张 `.callout` 说明卡（输入 / 期望 / 实际）。

EP 里没有任何组件能做「横向等分格 + 单格突变宽高 + 上浮卡片」。`el-steps` 是流程，`el-progress` 是单条，都不对。

**结论：手写，原样搬 CSS。** 唯一要改的是 `i === 3` → `firstFailedCaseId`。

---

## 7. 主题与设计 token 映射

### 7.1 SCSS 定制写法（已核实）

来源：Element Plus 官方 `docs/en-US/guide/theming.md`。

```scss
// src/styles/element/index.scss
@forward 'element-plus/theme-chalk/src/common/var.scss' with (
  $colors: (
    'primary': ('base': #2563EB),
    'success': ('base': #15803D),
    'danger':  ('base': #DC2626),
    'error':   ('base': #DC2626),
    'warning': ('base': #B45309),
    'info':    ('base': #64748B),
  ),
  $font-size: ('base': 13px),
  $border-radius: ('base': 6px),
  $common-component-size: ('large': 40px, 'default': 30px, 'small': 24px),
  $button-padding-horizontal: ('large': 20px, 'default': 14px, 'small': 12px),
);
```

注意 `$common-component-size` 和 `$button-padding-horizontal` 这两行：它们不在官方 theming 文档的示例里，但本项目必须要（§6.1 坑 2 解释了为什么光改 CSS 变量不行）。默认值分别在 `common/var.scss:233-241` 和 `:831-839`。

四条硬性要求（官方文档明确写了，漏一条就不生效）：

1. **必须用 `@use ... as *`，不能用 `@import`。** 官方原文：*"You must use `@use 'xxx.scss' as *;` instead of `@import`"*。
2. 按需引入 + Vite 时，**必须**配 `css.preprocessorOptions.scss.additionalData: '@use "~/styles/element/index.scss" as *;'`（见 §1）。
3. **必须**配 `unplugin-element-plus/vite` 的 `ElementPlus({ useSource: true })`，否则只引了编译后的 CSS，SCSS 变量根本没进编译。
4. `element/index.scss` 必须在 element-plus 的 scss **之前**引入——因为 `light-1…light-9` 和 `dark-2` 要用你的 `base` 去生成。

### 7.2 CSS 变量对照

原型 `:root` 有 21 个变量（`styles.css:5-27`）。**建议全部保留成 `--oj-*` 前缀的自有变量，只把该映射的映射到 EP**：

| 原型变量 | 值 | → EP 变量 | 处理 |
| --- | --- | --- | --- |
| `--blue` | `#2563EB` | `--el-color-primary` | 走 SCSS `$colors.primary.base` |
| `--blue-deep` | `#1D4ED8` | `--el-color-primary-dark-2` | **需显式覆盖**，EP 生成的是 `#1E4FBC`，见 §7.3 |
| `--blue-bg` | `#EFF6FF` | `--el-color-primary-light-9` | **需显式覆盖**，EP 生成的是 `#E9EFFD` |
| `--blue-line` | `#DBEAFE` | `--el-color-primary-light-8` | **需显式覆盖**，EP 生成的是 `#D3E0FB` |
| `--blue-soft` | `#93B4F5` | `--el-color-primary-light-5` | **需显式覆盖**，EP 生成的是 `#92B1F5`（差 1 位） |
| `--ac` / `--ac-bg` / `--ac-line` | `#15803D` / `#F0FDF4` / `#BBF7D0` | `--el-color-success*` | 同上，逐档覆盖 |
| `--wa` / `--wa-deep` | `#DC2626` / `#B91C1C` | `--el-color-danger` / `-dark-2` | 同上 |
| `--ink` / `--ink2` / `--ink4` | `#0F172A` / `#475569` / `#64748B` | `--el-text-color-primary` / `-regular` / `-secondary` | 走 SCSS `$text-color` |
| `--line` / `--line2` | `#E2E8F0` / `#EDF1F7` | `--el-border-color` / `-light` | 走 SCSS `$border-color` |
| `--bg` / `--panel` | `#F7F9FC` / `#FFFFFF` | `--el-bg-color-page` / `--el-bg-color` | 走 SCSS `$bg-color` |
| `--mono` / `--sans` | 字体栈 | `--el-font-family` | 走 SCSS `$font-family` |
| `--r` | `6px` | `--el-border-radius-base` | 走 SCSS `$border-radius.base`（EP 默认 4px） |
| `--ease` | `cubic-bezier(.32,.72,.28,1)` | 无对应 | 保留为 `--oj-ease` |

**关键：不要全盘依赖 EP 生成的色阶。** 原型的 `--blue-bg` / `--blue-line` / `--blue-soft` 是设计师手动挑的，和 EP 按比例混出来的值差 1–28 位（`--blue-deep` 的蓝通道差了 28）。差这一点，页面上几十个边框和底色就会和原型不一致。做法是：**SCSS 里设 `$colors.primary.base` 让 EP 生成一整套，再在 `:root` 里显式覆盖用到的 3 个 light-N 变量和 `dark-2` 回原型的值**。

### 7.3 EP 色阶生成规则（已核实并验算）

官方 `common/var.scss:55-96` 的生成逻辑（原文）：

```scss
@mixin set-color-mix-level($type, $number, $mode: 'light', $mix-color: $color-white) {
  $colors: map.deep-merge(
    ($type: ('#{$mode}-#{$number}': roundColor(
        color.mix($mix-color, map.get($colors, $type, 'base'), math.percentage(math.div($number, 10)))
      ))),
    $colors
  ) !global;
}

@each $type in $types {
  @for $i from 1 through 9 {
    @include set-color-mix-level($type, $i, 'light', $color-white);
  }
}
@each $type in $types {
  @include set-color-mix-level($type, 2, 'dark', $color-black);
}
```

注意两点容易看错的地方：

1. **`color.mix($mix-color, $base, percentage)` 的第二个参数是 `$base`**，所以 `light-N` 是「N×10% 白 + 其余基色」，不是反过来。
2. **`roundColor()` 会四舍五入**（`mixins/function.scss:92-99` 对 r/g/b 各做 `math.round`），所以手算和编译结果可能有 1 的误差。

即：

- **`light-N` = N×10% 白 + (1 − N×10%) 基色**（N = 1…9 全生成，不是只有 3/5/7/8/9）
- **`dark-2` = 80% 基色 + 20% 黑**

验算（用 EP 默认蓝 `#409EFF`）：`light-3` = 30% 白 + 70% `#409EFF` = `(0.3×255+0.7×64, 0.3×255+0.7×158, 0.3×255+0.7×255)` = `#79BBFF` ✅ 与官方注释（`var.scss:80` 逐字写着 `30% 79bbff`）一致；`dark-2` = 80% `#409EFF` = `#337ECC` ✅ 一致。公式确认。

代进本项目的 `#2563EB`：

| 档位 | EP 生成值 | 原型值 | 差 |
| --- | --- | --- | --- |
| `light-3` | `#6692F1` | —（未使用） | — |
| `light-5` | `#92B1F5` | `--blue-soft: #93B4F5` | 差 1/3/0 |
| `light-7` | `#BED0F9` | —（未使用） | — |
| `light-8` | `#D3E0FB` | `--blue-line: #DBEAFE` | 明显偏灰 |
| `light-9` | `#E9EFFD` | `--blue-bg: #EFF6FF` | 偏灰 |
| `dark-2` | `#1E4FBC` | `--blue-deep: #1D4ED8` | 蓝通道差 28 |

结论：**用到的 3 个 light 档（5/8/9）和 dark-2 全部显式覆盖**。`light-3` 和 `light-7` 原型里没用，可以不管。

### 7.4 其它要改的 EP 默认值

| EP 变量 | 默认值 | 改成 | 怎么改 | 理由 |
| --- | --- | --- | --- | --- |
| `--el-font-size-base` | `14px` | `13px` | SCSS `$font-size.base` 或 CSS 变量 | 原型 body 是 13px |
| 组件尺寸 | `large:40 / default:32 / small:24` | `40 / 30 / 24` | **必须走 SCSS `$common-component-size`** | 原型 `.btn` 是 30px；`el-button` 的高度是编译期写死的，改 CSS 变量无效，见 §6.1 坑 2 |
| 按钮横向内边距 | `large:20 / default:16 / small:12` | `20 / 14 / 12` | SCSS `$button-padding-horizontal` | 原型 `.btn` 是 `padding:0 14px` |
| `--el-border-radius-base` | `4px` | `6px` | SCSS `$border-radius.base` | 原型 `--r` 是 6px |
| `--el-transition-duration` | `0.3s` | `0.24s` | CSS 变量 | 原型过渡普遍 `.24s`–`.26s` |
| `--el-transition-duration-fast` | `0.2s` | `0.15s` | CSS 变量 | 原型 hover 是 `.15s` |

（默认值来源：`packages/theme-chalk/src/common/var.scss:198-209`（`$font-size`）、`:233-241`（`$common-component-size`）、`:153-162`（`$border-radius`）、`:1652-1659`（`$transition-duration`），已核实。）

**注意「改得了」和「改不了」的分界**：EP 里凡是 `@include css-var-from-global(...)` 出来的属性（`el-input` 高度见 `input.scss:174`、`--el-border-radius-base`、字号、颜色）都能在运行时改 CSS 变量；凡是 SCSS 里直接 `map.get()` 取值写进属性的（`el-button` 的 `height`、`padding`），只能在 SCSS 配置阶段改，运行时改变量没有任何效果。判断方法：打开 EP 源码看那一行是 `var(--el-xxx)` 还是具体的 `32px`。

**「全部走 CSS 变量」是行不通的。** 最容易踩的误会是以为在 `:root` 里写 `--el-component-size: 30px` 就能把按钮压到 30px。实际上 `--el-component-size` 只对走 `css-var-from-global` 的组件有效（`el-input` 已核实走这条），`el-button` 的 `height` 在编译期就固定成 `32px` 了。所以 §7.1 那段 SCSS 是**必须的**，不是可选优化。

---

## 8. 布局与动效

### 8.1 五列 grid 原样保留

```css
.work {
  height: calc(100vh - 44px - 143px);
  display: grid;
  grid-template-columns: var(--problem-w) 4px minmax(0,1fr) var(--drawer-w) 64px;
  transition: grid-template-columns .26s var(--oj-ease);
}
.work.drawer-closed { grid-template-columns: var(--problem-w) 4px minmax(0,1fr) 0 64px }
.work.drawer-closed .ai-drawer { display: none }
.work > .col-problem { grid-column: 1 }
.work > .seam        { grid-column: 2 }
.work > .col-editor  { grid-column: 3 }
.work > .col-drawer  { grid-column: 4 }
.work > .col-rail    { grid-column: 5 }
```

**两条不能删的细节**（都是原型调试时踩出来的）：

1. **显式 `grid-column` 必须有。** 抽屉 `display:none` 后，如果没有显式列定位，后面的 `.ai-rail` 会被自动排到第 4 列，窄轨跑到编辑器右边溢出。
2. **`.work` 的 `height` 用 `calc(100vh - 44px - 143px)`。** 顶栏 44px + 底部判分链 143px 都是固定的，中间自适应。

### 8.2 中缝拖拽：`useResizable`

原型 `app.js:165-183` 的四个方法搬成一个 composable，逻辑原样（300–560px 夹取，方向键 12px，Shift 40px）：

```js
export function useResizable(initial = 392, { min = 300, max = 560 } = {}) {
  const width = ref(initial)
  const dragging = ref(false)
  let startX = 0, startW = 0

  const onMove = (e) => {
    if (!dragging.value) return
    width.value = Math.min(max, Math.max(min, startW + e.clientX - startX))
  }
  const onUp = () => { dragging.value = false }

  const startDrag = (e) => { dragging.value = true; startX = e.clientX; startW = width.value; e.preventDefault() }
  const seamKey = (e) => {
    const step = e.shiftKey ? 40 : 12
    if (e.key === 'ArrowLeft')  { width.value = Math.max(min, width.value - step); e.preventDefault() }
    if (e.key === 'ArrowRight') { width.value = Math.min(max, width.value + step); e.preventDefault() }
  }

  onMounted(() => { window.addEventListener('mousemove', onMove); window.addEventListener('mouseup', onUp) })
  onUnmounted(() => { window.removeEventListener('mousemove', onMove); window.removeEventListener('mouseup', onUp) })

  return { width, dragging, startDrag, seamKey }
}
```

**迁移时必须补的一件事**：原型在 `window` 上挂监听但没在销毁时摘（小样只有一页，无所谓）。Vue 里必须 `onUnmounted` 摘掉，否则路由切换后监听器会累积。

### 8.3 动效映射

| 原型 | 实现 | 迁移方式 |
| --- | --- | --- |
| 首次进入链带逐格出场（`.intro .cell`，`animation-delay: calc(var(--i) * 45ms)`） | CSS animation | 保留 CSS，用 `:class="{ intro }"` 控制，`setTimeout(1400ms)` 后摘掉 |
| 判题中逐格点亮（`.cell.lit`，`@keyframes lit`） | CSS animation | 保留。`litCount` 变化时给新格子加类即可，CSS 的 `animation-delay: calc(var(--i) * 40ms)` 仍可用 |
| 失败格抖动（`.cell.fail`，`@keyframes shake`） | CSS animation | 保留。**注意**：从 `lit` 切到 `fail` 时元素没重建，动画不会重播。原型是靠「先 judging 再 wa」两个不同 class 阶段避开的；Vue 里如果同一节点直接从 `lit` 变 `fail`，要加 `:key` 强制重建或用 `el.classList.remove/void offsetWidth/add` 手动重播 |
| 说明卡上浮（`.callout`，`@keyframes pop` + `animation-delay:.34s`） | CSS animation | 保留 |
| 思考过程折叠（`grid-template-rows: 0fr → 1fr`，`.26s`） | CSS transition | **保留手写**。这是纯 CSS 折叠，不需要 JS 量高度，`el-collapse-transition` 反而要 JS 算 `scrollHeight` |
| 模型滑块位移 | CSS transition on `transform` | 换成 `el-segmented` 后由组件内部处理（见 §6.4 坑 6） |
| 抽屉开合 | `grid-template-columns` transition | 保留 |
| 行内批注展开 | `slide-down`（opacity + translateY） | 保留，但改成 view zone 后要确认高度已定（见 §5.4） |
| 减弱动效 | `@media (prefers-reduced-motion: reduce)` | **必须保留整段**（`styles.css:1163-1172`），这是无障碍硬要求，不是可选项 |

### 8.4 滚动条

原型自定义了 `::-webkit-scrollbar`（`.code` 8px、`.ai-scroll` 8px + `border:2px solid #FBFCFE`）。EP 的 `el-scrollbar` 用的是自己的 `el-scrollbar__bar` 元素，样式完全不同。

**结论：全部用原生 `overflow-y:auto` + `::-webkit-scrollbar`，不换 `el-scrollbar`。** 理由：原型的滚动条是细的、透明的、和背景融合的，`el-scrollbar` 是半透明浮条，视觉上更重。

---

## 9. 接口对接映射

接口契约来自 `《系统详细设计与接口契约规范》.md`（L161–270）。

### 9.1 判题（接口 1 + 2）

```
POST /api/judge/submit          → { submissionId, status, statusDesc }
GET  /api/judge/status/{id}     → 每 1s 轮询（PRD 明确要求）
```

> **`submissionId` 是字符串，不是数字。** 契约示例里是 `"1849023849201"`（带引号）。轮询 URL 直接拼接即可，但**不要** `parseInt` 后存进 `number` 字段——19 位超过 `Number.MAX_SAFE_INTEGER`，会丢精度导致查不到记录。

| 后端字段 | 前端落点 | 说明 |
| --- | --- | --- |
| `status` | `judge.result` | 需要一张枚举表，见下 |
| `statusDesc`（接口 1）/ `statusText`（接口 2） | `.vsum-top` 的说明文字 | **两个接口字段名不同**，别用同一个解构 |
| `oiScore` | `.score`（`29px/600` mono） | 80 → WA 红色 `#B91C1C`；100 → AC 绿色 `#15803D` |
| `passCaseCount` / `totalCaseCount` | `.vsum-bot` 的「通过 8/10」 | |
| `firstFailedCaseId` | 链带哪一格变 `.fail` | **替代原型写死的 `i === 3`** |
| `caseDetails[].caseId` | 格子序号 | |
| `caseDetails[].status` | 格子 `lit` / `fail` / 空 | `AC` → 亮，`WA/TLE/MLE/RE` → 红 |
| `caseDetails[].timeMs` | `.console-row` 的「最大耗时」 | 取 `max` |
| `timeUsed` / `memoryUsed` | `.console-row` | 12ms / 4096KB |
| `unlockedAiStages` | 窄轨 4 个 stage 的 `done/cur/lock` | `[1,3]` → ①③ 可用 |
| `highlightAiStage3` | ③ 加脉冲高亮 | 对应原型的 `.stage.cur` |

**判题状态枚举**（契约里明确给了 `0` 和 `5` 两个值，其余需与后端对齐后再补）：

```js
export const JUDGE_STATUS = {
  0: { key: 'pending',  text: '排队评测中…' },   // 契约：落库 Pending(0)
  5: { key: 'wa',       text: 'WA (答案错误)' }, // 契约：接口 2 的示例
  // AC / TLE / MLE / RE / PAC 等按后端实际枚举补齐
}
```

**别用魔数。** 原型里 `result` 是 `'wa'|'judging'|'ac'` 三个字符串，迁移后应该由 `status` 映射出 `key`，再驱动 UI。另外注意契约里两个字段名不同：接口 1 返回 `statusDesc`，接口 2 返回 `statusText`——**不是同一个名字**，别写成同一个解构。

**3 秒防抖**（PRD 要求 Redis 限同一用户每 3 秒最多 1 次）：
- 前端在 `submit()` 成功后置 `cooldown = 3`，用 `setInterval` 递减，按钮 `:disabled="cooldown > 0 || judging"`；
- 后端返回 `code: 429` 时也要兜底：`ElMessage.warning('提交太频繁，请 3 秒后再试')`。

### 9.2 SSE 流式对话（接口 3）

```
POST /api/ai/chat/stream
Content-Type: text/event-stream;charset=UTF-8
```

请求体（契约逐字）：

```json
{
  "problemId": 1024,
  "assignmentId": 15,
  "selectedChannelCode": "ollama_local",
  "clickedStageCapsule": 3,
  "userMessage": "为什么第3个测试点我没过？",
  "currentEditorCode": "#include <iostream>\n..."
}
```

三类事件（契约原文示例逐字）：

```text
event: agent_step
data: {"agent": "Router", "model": "qwen2.5-coder:7b(本地)", "stage": 3, "desc": "已识别为[阶段③：排错与反例诊断]，正在调用 @Tool 拉取第3号失败测试点..."}

event: token
data: {"content": "我通过内部诊断工具查看了你未通过的 **第 3 号测试点**。\n\n"}

event: done
data: {"guardrailTriggered": false, "remainingQuota": 2, "actualModelUsed": "qwen2.5-coder:7b"}
```

| 事件 | data 字段 | 前端动作 |
| --- | --- | --- |
| `agent_step` | `agent` / `model` / `stage` / `desc` | 往 `ai.pipeline` 里推进：上一个节点 `done`，当前节点 `active`，后面的 `wait`；`desc` 直接进 `.pline`，`model` 直接进 `.pmeta` |
| `token` | `content` | 追加到 `messages[last].text`，`streaming = true` |
| `done` | `guardrailTriggered` / `remainingQuota` / `actualModelUsed` | 结束流；`quotaLeft = remainingQuota`；若 `guardrailTriggered` 显示拦截提示；`actualModelUsed` 和用户选的模型不一致 → 显示「已自动降级到本地」 |

**注意 `agent_step` 的 `data` 是单行的 JSON，且 `model` 字段带中文括号**（`"qwen2.5-coder:7b(本地)"`）。前端不要拿它做等值判断（比如 `=== 'qwen2.5-coder:7b'`），只用于展示；要判断实际模型用 `done.actualModelUsed`。

```js
import { fetchEventSource } from '@microsoft/fetch-event-source'

export function useSSEChat() {
  let ctrl = null

  async function send(payload, { onStep, onToken, onDone, onError }) {
    ctrl = new AbortController()
    await fetchEventSource('/api/ai/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(payload),
      signal: ctrl.signal,
      openWhenHidden: true,          // 切标签页不中断
      onmessage(ev) {
        const data = JSON.parse(ev.data)
        if (ev.event === 'agent_step') onStep(data)
        else if (ev.event === 'token') onToken(data.content)
        else if (ev.event === 'done') onDone(data)
      },
      onerror(err) { onError(err); throw err },   // throw 才会停止重连
    })
  }

  const abort = () => ctrl?.abort()
  return { send, abort }
}
```

**三处必须处理的边界**：

1. **`done.guardrailTriggered === true`**：PRD 要求「命中即拦截并重写」。前端不能只是静默——要在气泡上方插一条 `.ai-lock` 样式的提示（原型 `styles.css:777`），说明「本次回复被护栏拦截并已改写」。
2. **`actualModelUsed` ≠ 用户选择**：PRD AC-2.3 要求云端超时 8 秒自动降级。前端要把分段控件的选中态临时切到本地，并提示「云端超时，已切到本地模型」，否则用户会以为云端的还在跑。
3. **`remainingQuota` 归零**：`el-input` 的 `:disabled` + 占位符改成「今日提示次数已用完」，不要只是按钮变灰。

### 9.3 权限与状态机

PRD 模块三的规则直接决定 UI：

| 场景 | AI 阶段可用 | 前端表现 |
| --- | --- | --- |
| 平时练习 | ①②③④ 全开 | 窄轨四段都可点 |
| 课后作业 | 仅 ① 和 ③，且每题有提示上限（如 3 次） | ②④ 显示 `lock`，`.ai-quota` 显示剩余次数 |
| 期末考试 | 全部关闭，后端 403 | 抽屉整体替换为 `el-result icon="warning"`「考试模式已关闭 AI 助教」；**前端要拦，但不能只靠前端** |

阶段锁（PRD 模块二业务规则 1）：

```js
// stores/permission.js
const canUseStage = (stage) => {
  if (isExam.value) return false
  if (mode.value === 'homework' && ![1, 3].includes(stage)) return false
  if (stage === 3 && !judge.hasNonAcSubmission.value) return false   // 必须有非 AC 提交
  if (stage === 4 && !judge.hasAcSubmission.value) return false      // 必须有 AC 提交
  return true
}
```

**注意**：原型里 `stage4Unlocked` 是 `result === 'ac'`，这是小样的简化。真实规则是「存在 AC 提交记录」，跟当前这一次判题结果无关——学生 AC 之后又交了一发 WA，阶段④仍然应该开着。

另外两个接口 4/5（切屏上报、双阶段查重）**不属于这个工作台页面**，它们是考试端与教师端的能力，列在这里只是说明 `api/teaching.js` 的位置：

| 接口 | 路径 | 谁用 |
| --- | --- | --- |
| 4 切屏异常上报 | `POST /api/teaching/exam/tab-switch`，body `{assignmentId, blurDurationSeconds}` | 考试端页面，返回 `currentSwitchCount / limit / isRedFlagged / warningMsg` |
| 5 双阶段查重 | `POST /api/teaching/plagiarism/check` | 教师端报表页 |

工作台这边**唯一相关的联动**是：考试模式下 `isExam` 为真时把整个 AI 抽屉换成 `el-result`，不需要调这两个接口。

---

## 10. 迁移顺序

按「先骨架后血肉、先静态后接口」分五步，每步都能独立跑起来看效果：

| 步骤 | 内容 | 验收 |
| --- | --- | --- |
| 1 | 脚手架 + 目录 + `tokens.scss` + `element/index.scss` | 页面白底、13px 字体、`--el-color-primary` 是 `#2563EB` |
| 2 | `WorkbenchView` 五列 grid + 四个占位区块 + `useResizable` | 拖中缝能改题面宽、300–560 夹取、抽屉能收能开 |
| 3 | 纯静态迁移：`AppTopbar` / `ProblemPanel` / `VerdictBand` / `CaseChain` / `StageRail` / `AiDrawer` | **和原型截图逐像素对比**，允许差 1px，不允许差色值 |
| 4 | `CodeEditor`（Monaco）+ `InlineAnnotation`（content widget + view zone） | 行号、语法色、第 8 行高亮、胶囊、展开条全部到位 |
| 5 | store 接 mock → 接真实接口 → SSE | 走通「提交 → 轮询 → 链带点亮 → WA → 展开批注 → 改代码 → 提交 → AC → ④ 解锁」 |

**第 3 步是分水岭。** 静态迁移做完之后，拿原型的 10 张状态图逐张比对，这一步过了再动 Monaco 和接口，否则一旦有偏差，分不清是迁移的问题还是接口的问题。

比对基准就是 `prototype/workbench/_shots/final10/` 里的 10 张（1440×900）：`default`（WA 80/100）、`hint`（行内批注展开）、`think`（思考过程展开）、`judging`（判题中逐格点亮）、`ac`（AC 100/100）、`locked`（④ 未解锁）、`closed`（抽屉收起）、`cloud`（云端模型）、`stage1`（读题拆解）、`stage2`（编码引导）。小样的状态入口是 URL 参数 `?state=xxx`，迁移后用 `?mock=xxx` 的开发开关复现同一批画面即可。

截图脚本可以直接复用 skill 里的 `shoot.mjs`（`oil-ui-pro/scripts/shoot.mjs`），命令形状：

```bash
node <skill>/scripts/shoot.mjs http://localhost:5173/workbench?mock=hint --size 1440x900 --out shots/hint
```

十个 `?mock=` 值跑一遍，和 `_shots/final10/` 同名图并排看，第 3 步的验收就完成了。

---

## 11. 风险与取舍

| 风险 | 影响 | 处理 |
| --- | --- | --- |
| `el-splitter` 还是 **beta**（2.10.0 引入） | 未来 API 可能变 | 不用它，五列 grid 手写。只有中缝一处需要拖拽，`useResizable` 30 行就够 |
| `el-segmented` 需要 **2.7.0+**，`direction` 要 2.8.7，`props` 别名要 2.9.8 | 版本不够就用不了 | 锁 `element-plus >= 2.9.8`；只用 `v-model` + `options` + 默认插槽，不依赖 `direction`/`props` |
| EP 默认色阶和原型差 1–28 位 | 全页边框/底色偏灰、深蓝偏紫 | 显式覆盖用到的 3 个 light-N + dark-2（§7.3） |
| `el-button` `:loading` 变宽 | 提交按钮抖动 | 固定宽 96px（§6.1 坑 1） |
| `el-button` / `el-segmented` 的高度改不动 | 整页组件比原型高 2px，顶栏 44px 装不下 | 走 SCSS `$common-component-size`，不是改 CSS 变量（§6.1 坑 2、§7.1） |
| `el-segmented` 双行内容被 `nowrap` 截断 | 模型名显示成「本地极速 Qwen2.5-Co…」 | 插槽里的 span 必须 `display:block`（§6.4 坑 5） |
| Monaco content widget 样式隔离 | 行内胶囊位置错 | `.annot` 外观/定位拆开（§5.3） |
| `prefers-reduced-motion` 被漏掉 | 无障碍不过 | 整段搬进 `base.scss`（§8.3） |
| 判题状态枚举不全 | 状态机缺分支 | 先只做 `pending/wa/ac`，其余按后端补齐，用默认分支兜底 |

**最后一条取舍值得单独说**：这套设计里最有价值的东西——判分链带、阶段窄轨、流水线卡、行内批注——Element Plus 一个都接不住。这不是选错了组件库，而是「教学型 OJ」的交互形态本来就超出通用后台组件库的表达范围。**迁移文档里要主动说明这一点**，比假装全都用上了组件库要站得住。

---

## 附录：事实来源

| 事实 | 来源 |
| --- | --- |
| `el-segmented` 2.7.0、props/插槽/事件 | 官方 `docs/en-US/component/segmented.md` |
| `el-segmented` 的 `$segmented` CSS 变量默认值 | 官方 `packages/theme-chalk/src/common/var.scss:909-923` 与 `segmented.scss` |
| `el-segmented` 的滑块由 JS 量 `offsetWidth/offsetLeft`、首帧 `display:none`、无 prop 可关 | 官方 `packages/components/segmented/src/segmented.vue:126-170` |
| `el-segmented` 的结构（`role="radiogroup"` + `<input type="radio">`） | 官方 `packages/components/segmented/src/segmented.vue:2-32` |
| `el-segmented__item-label` 带 `utils-ellipsis`（`white-space:nowrap`） | 官方 `packages/theme-chalk/src/segmented.scss:147-154` |
| `el-segmented` 滑块 `transition: all 0.3s` | 官方 `packages/theme-chalk/src/segmented.scss:85` |
| `el-segmented` 的 `min-height` 走 `$input-height` | 官方 `packages/theme-chalk/src/segmented.scss:62` |
| `el-button` 的 `height` 是编译期 `map.get($input-height,'default')`，改 CSS 变量无效 | 官方 `packages/theme-chalk/src/button.scss:29` + `common/var.scss:233-241` |
| `el-button` 的 `loading` 插槽与默认插槽并列渲染 | 官方 `packages/components/button/src/button.vue:10-25` |
| `el-input` 高度走 `css-var-from-global('input-height','component-size')` | 官方 `packages/theme-chalk/src/input.scss:174` |
| `el-result` 的 6 个间距变量默认值 | 官方 `packages/theme-chalk/src/common/var.scss:1599-1610` |
| `utils-ellipsis` 展开为 `overflow:hidden;text-overflow:ellipsis;white-space:nowrap` | 官方 `packages/theme-chalk/src/mixins/utils.scss` |
| `el-splitter` beta / 2.10.0 | 官方 `docs/en-US/component/splitter.md`（标题 `Splitter ^(beta)`） |
| `el-result` 的 props / slots | 官方 `docs/en-US/component/result.md` |
| `el-progress` 的 `percentage` 必填 0–100 | 官方 `docs/en-US/component/progress.md` |
| `el-tag` 的 `type` / `effect` / `size` | 官方 `docs/en-US/component/tag.md` |
| SCSS 主题定制四条硬要求 | 官方 `docs/en-US/guide/theming.md` |
| 色阶生成公式 `mix(white, base, N×10%)` | 官方 `var.scss`，并用 `#409EFF → #79BBFF` 验算 |
| `--el-font-size-base: 14px`、`--el-component-size: 32px`、`--el-transition-duration: 0.3s`、`--el-border-radius-base: 4px` | 官方 `var.scss` |
| `$common-component-size` 默认 `40/32/24`、`$button-padding-horizontal` 默认 `20/16/12` | 官方 `common/var.scss:233-241`、`:831-839` |
| `createDecorationsCollection` 取代 soft-deprecated `deltaDecorations` | Monaco changelog（"Soft deprecation of ICodeEditor.deltaDecorations…"）+ [issue #442](https://github.com/microsoft/monaco-editor/issues/442) |
| `@guolao/vue-monaco-editor` 必须先 `loader.config`，默认从 CDN 加载 | [npm 包说明](https://www.npmjs.com/package/@guolao/vue-monaco-editor) |
| 接口契约（4 个接口 + SSE 三类事件） | `《系统详细设计与接口契约规范》.md` L161–270 |
| PRD 的阶段锁 / 混合路由 / 双层护栏 / 权限分级 | `《…》产品需求文档（PRD v1.0 终版）.md` L61–86 |

> 附录里的官方源码行号对应 element-plus `dev` 分支（抓取时间见 `_notes/`）；组件 props 与版本号对应官方文档站。**两处都可能在后续版本变动，落地前跑一次 `npm i element-plus@latest` 后复核 §6 的坑是否仍然成立。**
