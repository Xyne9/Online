# B · 教师端页面契约审计（原型 → 后端对接清单）

> 审计对象：`prototype/app/` 下 7 个教师端页面 + 共享层 `prototype/app/shell.js`、`prototype/app/_SPEC.md`。
> 方法：逐文件通读全部 `<script>` 中的 Alpine 组件（`x-data` 工厂函数）与其模板里所有 `x-text` / `x-model` / `:href` 绑定，按「读 → 写 → 派生 → 实时 → 难度」五面提取。
> 约定：
> - 字段名一律原样抄写（不翻译、不改大小写、不补全）。
> - 行号指该文件内的物理行；下文表格中的 `t-xxx.html:NNN` 均指 `prototype/app/t-xxx.html`。
> - 标 **〔硬编码〕** 的项在源码里是字面量，没有任何数据源，后端必须新增字段或接口来喂。
> - 标 **〔未在源码中找到〕** 的项在原型里确实不存在对应实现，不作为既有契约，只作为缺口提示。

---

## 页面：t-dashboard.html（教师概览）

### 1. 页面职责
一屏回答「我这周要处理什么、班级这周怎么样、AI 花了多少」——待办优先、指标其次、AI 价值通栏收尾（`t-dashboard.html:151-433`）。

### 2. 该页读取的数据（读操作）

| 数据项 | 字段（组件里实际用的属性名，逐个列全） | 语义/来源 |
|---|---|---|
| 页头班级与周次 | —（模板字面量 `数据结构 · 计算机 2022 级 1 班 · 第 8 周（10-18 至 10-24）`） | 〔硬编码〕`t-dashboard.html:156`；真实系统需 `class.name` / `term.weekNo` / `week.startDate` / `week.endDate` |
| 本周每日提交/通过 | `days[].date`、`days[].week`、`days[].sub`、`days[].ac` | `t-dashboard.html:507-515` 内联数组；真实系统 = 按自然日聚合的提交数与 AC 数（7 条/周） |
| 易错题排行 | `ranks[].pid`、`ranks[].title`、`ranks[].lvl`、`ranks[].tags`、`ranks[].tried`、`ranks[].ac`、`ranks[].pass`、`ranks[].avg`、`ranks[].first`、`ranks[].students[]` | `t-dashboard.html:517-528`；`tried`=尝试人数，`ac`=AC 百分比(0-100)，`pass`=通过人数，`avg`=平均尝试次数，`first`=首次错误点（如 `第 7 个测试点`） |
| 排行抽屉里的学生 | `ranks[].students[].name`、`.sid`、`.n`、`.at` | `t-dashboard.html:519-527`；`n`=该生错误次数，`at`=最近一次错误时间（`10-24 19:11` 形式） |
| 进行中活动 | `activities[].id`、`.title`、`.type`、`.typeName`、`.pidCount`、`.done`、`.total`、`.due`、`.left` | `t-dashboard.html:530-537`；`type`∈`homework`\|`practice`\|`exam`，`done/total`=已提交/应提交人数 |
| 待办条目 | `todos[].k`、`.label`、`.sub`、`.count`、`.icon`、`.href`、`.act`、`.actTitle`、`.busy`、`.struck`、`.gone`、`.warn` | `t-dashboard.html:548-557`；`count>0` 才是真待办，`warn` 只给「需人判断」的一条（查重复核） |
| AI 四阶段分布 | `aiStages[].k`、`.label`、`.n` | `t-dashboard.html:558-563`；① 读题拆解 588 / ② 编码引导 754 / ③ 排错诊断 368 / ④ AC 复盘 130 |
| 本周通过率汇总 | 由 `days` 派生 `weekSub` / `weekAc` / `weekPassRate` | `t-dashboard.html:570-572`，渲染在 `:321`、`:326`、`:357` |
| 班级人数 | —（`48`、`本周活跃 44`） | 〔硬编码〕`t-dashboard.html:355` |
| 待复核查重组数 | —（`1` 组、`96.4%`） | 〔硬编码〕`t-dashboard.html:360-361` |
| 进行中活动计数 | —（`3 项`） | 〔硬编码〕`t-dashboard.html:277` |
| AI 调用总量 / TTFT | —（`1840` 次、`平均 TTFT 0.8s`） | 〔硬编码〕`t-dashboard.html:391` |
| AI 省时与诊断覆盖 | —（`4.2` 小时、`38` 人、`5` 类错误模式） | 〔硬编码〕`t-dashboard.html:395`、`:400-401` |
| 云端/本地路由分布 | —（`云端 Tutor 1325` / `本地 Router 386` / `降级回落 129`） | 〔硬编码〕`t-dashboard.html:421-428`；三段须与 `a-models` 页同源（注释 `:419` 明确要求「同一批 1840 次调用只能有一套账」） |
| 错误类型分布（抽屉） | —（`WA 54%` / `TLE 26%` / `RE 12%` / `CE 8%`） | 〔硬编码〕`t-dashboard.html:462-471`，不随 `detail` 变化 |
| 全周失分归因文案 | —（`失分集中在动态规划与字符串匹配两章…`） | 〔硬编码〕`t-dashboard.html:351`；真实系统应为 AI 生成的归因摘要 |

### 3. 该页写入/触发的动作（写操作）

| 动作（用户点的是什么、方法名） | 期望副作用 | 当前是否只是本地模拟 |
|---|---|---|
| 点「发布」`finish(t)`（`:187`，方法 `:587-599`） | 把「待发布作业」2 项真正发布，学生端立即可见；`count--`，条目划掉并收起 | **是**：仅 `busy=true` 400ms → `collapse(t)`，无任何请求（`:591-598`） |
| 点「去复核」`finish(t)` + 跳 `t-plagiarism.html`（`:191-192`，`:589`） | 打开查重复核页 | 部分是：跳页真实，状态未提交 |
| 点「详情」（`:193`） | 跳 `t-assignments.html` | 纯导航 |
| toast「撤销」`undo`（`:594-597`） | 5 秒内回滚发布 | **是**：只把 `t.gone`/`t.struck` 置回 false |
| 点排行行 `detail=r`（`:255`） | 打开题目错误分析抽屉 | 纯前端状态 |
| 快捷操作 4 个入口（`:371-374`） | 新建题目/发布作业/查重报告/考试监考 | 纯导航 |

### 4. 该页的派生/计算逻辑

应下沉到后端的聚合指标：

| 指标 | 方法名（源码位置） | 说明 |
|---|---|---|
| 周内最大单日提交量 | `get maxSub()` `t-dashboard.html:565` | 仅用于柱高归一化，前端算可接受 |
| 当前选中日 | `get day()` `t-dashboard.html:566` | 纯 UI |
| 本周提交总数 | `get weekSub()` `t-dashboard.html:570` | 7 天求和；真实系统应由后端按周聚合 |
| 本周通过总数 | `get weekAc()` `t-dashboard.html:571` | 同上 |
| **本周提交通过率（按次数加权）** | `get weekPassRate()` `t-dashboard.html:572` | `Math.round(weekAc/weekSub*100)`；注释 `:567-569` 特别声明「与 t-analytics 的班级 AC 率（按学生算术平均）是两个指标，不能混用」——后端必须分别提供两种口径 |
| 待办筛选/计数 | `get todoRows()` `:575`、`get pendingRows()` `:576`、`get healthyTodos()` `:577` | `count>0` 且未 `gone`；真实系统应为待办查询接口 |
| 易错题排行排序 | `get rankRows()` `t-dashboard.html:578-583` | `rankMode===0` 按 `ac` 升序，`===1` 按 `tried` 降序；后端应支持 `sort=ac_asc\|tried_desc` |

> 注：`detail` 抽屉里的「通过人数 `pass`」「平均尝试 `avg`」「首次错误点 `first`」与排行行同源，但错误类型分布是死值（`:462-471`），未随 `detail` 变化。

### 5. 该页的实时性要求

- **无轮询、无 SSE/WebSocket**，源码中无 `setInterval`、无 `EventSource`、无 `WebSocket`。
- 只有交互级 `setTimeout`：发布按钮 400ms 假进行态（`t-dashboard.html:591`）、划掉后 220ms 收起（`:602`）、toast 5 秒撤销窗口（shell.js 层）。
- 真实系统可接受「打开页面拉一次 + 手动刷新」，周维度指标无需实时。

### 6. 对接难度初判

**中** —— 读侧全是聚合查询（周趋势、易错题排行、待办、AI 用量四类），每类都能独立成型；难点在于「按次数加权的通过率」与「按学生平均的班级 AC 率」两套口径必须同时存在且不混用。

---

## 页面：t-problems.html（题库管理）

### 1. 页面职责
题库的列表工作台：按状态/难度/标签/关键词筛选、排序、分页，批量发布与归档，抽屉里看题目测试点明细（`t-problems.html:120-357`）。

### 2. 该页读取的数据（读操作）

| 数据项 | 字段（组件里实际用的属性名，逐个列全） | 语义/来源 |
|---|---|---|
| 题目主表（来自共享层） | `p.id`、`p.title`、`p.lvl`、`p.tags`、`p.tried`、`p.ac` | `t-problems.html:470-473` 从 `OJ.problems` 逐条映射；`tried`→本地 `submits`，`ac`→本地 `ac` |
| 题目状态 | 派生字段 `state`：`'published'` \| `'draft'` \| `'archived'` | `t-problems.html:481`；由 `DRAFT=[1092,1118]`（`:455`）与 `ARCHIVED=[1001]`（`:456`）两个硬编码数组决定 |
| 更新时间 | `updated` | `t-problems.html:450-454` 的 `UPD` 映射（`1024:'10-24 20:31'` 等 12 条），缺失回退 `'10-01 00:00'`（`:482`） |
| 测试点（列表用摘要） | `tps[].id`、`.kind`、`.size`、`.out`、`.on` | `tpsFor(p)` `t-problems.html:459-468`；`kind`∈`sample`\|`hidden`，`size` 形如 `n = 10⁵`，`out` 只有 1024 的样例点给真值其余 `'—'`，`on` 除 `1024` 第 9 点外全 true |
| 测试点计数 | `onCount`、`offCount` | `t-problems.html:485-486`；草稿强制为 0 |
| 抽屉里的固定判题参数 | —（`1000ms（Java / Py ×2）`、`256MB`、`OI 按点计分 · 取历史最高`、`阶段 ①③ 开放 · 上限 3 次`） | 〔硬编码〕`t-problems.html:383-386`，不随 `detail` 变化 |
| 顶部统计 | 由 `list` 派生：`list.length`、`countOf('published')`、`countOf('draft')`、`countOf('archived')` | `t-problems.html:125`、`:137-153`、`:604-606` |
| 「覆盖 14 个知识点」 | — | 〔硬编码〕`t-problems.html:138` |
| 标签候选集 | `tagOptions` | `t-problems.html:534-538`，从当前 `list` 全量标签去重得到（不是后端字典） |
| 难度候选 | `[1,2,3,4,5]` + `OJ.lvlName(l)` | `t-problems.html:178-182` |
| 状态分段的计数 | `countOf(f.k)` | `t-problems.html:166` |
| 全选状态 | `allSel` | `t-problems.html:601-603`，仅当前页 |

### 3. 该页写入/触发的动作（写操作）

| 动作（用户点的是什么、方法名） | 期望副作用 | 当前是否只是本地模拟 |
|---|---|---|
| 「批量发布」`batchPublish()` `t-problems.html:634-640` | 选中题 `state→'published'`，学生端立即可见 | **是**：只改本地 `p.state`（`setState` `:628-633`） |
| 「批量归档」`batchArchive()` `t-problems.html:641-647` | 选中题 `state→'archived'`，历史提交保留 | **是**：同上 |
| 抽屉底部「归档题目 / 恢复题目」`archive(detail)` `t-problems.html:648-652` | 单题在 `archived`/`published` 间切换 | **是**：改本地字段 + toast |
| 「导入题目」`importProblems()` `t-problems.html:653-656` | 从题库模板导入 3 道题为草稿 | **是**：**只弹 toast，连本地 `list` 都没插入**（注释 `:654` 声称「撤销把刚加进来的 3 道草稿移出列表」，但代码没有 push 动作） |
| toast「撤销」`undoToast()` `t-problems.html:664-672` | 5 秒内回滚上一批状态变更 | **是**：`snap()`（`:658-663`）记录 `{id,state}` 快照后本地还原 |
| 行点击 / 回车 `openDetail(p)` `t-problems.html:576-578` | 打开题目详情抽屉 | 纯前端 |
| 「编辑题目」跳转 `:429` | 去 `t-problem-edit.html?pid=${detail.id}` | 纯导航（`pid` 参数实际未被编辑页读取，见下页） |
| 排序 `setSort(key)` `t-problems.html:614-618` | 切列/切换升降序，回第 1 页 | 前端排序 |
| 「重新加载」`retry()` `t-problems.html:528-532` | 重拉列表 | **是**：600ms `setTimeout` 后置 `loading=false` |

### 4. 该页的派生/计算逻辑

| 指标 | 方法名（源码位置） | 说明 |
|---|---|---|
| 判分链绿格数 | `okCells(p)` `t-problems.html:560-563` | `Math.max(1, Math.min(10, Math.round(p.ac/100*10)))` —— **用 AC 百分比倒推「多数人过到第几格」**，属前端伪造；真实系统必须给每道题「逐测试点的通过人数分布」 |
| 判分链着色 | `chainOf(p)` `t-problems.html:564-568` | 前 `okN` 格 `ac`，第 `okN` 格 `skip`，其余空 |
| 链的 tooltip | `tpTitle(p)` `t-problems.html:569-575` | 拼「多数人通过 N 个测试点，卡在第 N+1 个 · 全班 AC 率 x%」 |
| 筛选 + 排序 | `get filtered()` `t-problems.html:579-595` | 状态/难度集合/标签/关键词四条件，排序支持 `id`/`title`/`submits`/`updated` |
| 分页 | `get pages()` `:596`、`get paged()` `:597-600` | `pageSize = 8`（`:500`），客户端切片；后端需 `page`/`pageSize` 或游标 |
| 状态徽标映射 | `stateMeta(s)` `t-problems.html:607-613` | `published→已发布/ac`、`draft→草稿/pending`、`archived→已归档/''` |
| 难度分档计数 | `lvlCount(n)` `t-problems.html:548-550` | 下拉里每档的题数 |

### 5. 该页的实时性要求

- **无**。`setInterval` / `EventSource` / `WebSocket` 均未出现。
- 仅有加载态计时器：`init()` 里 600ms 后 `loading=false`（`t-problems.html:526`），`retry()` 同（`:531`）。注释 `:503` 明确「加载态：进入 600ms 后置 false，不做假接口」。
- 状态变更对其他教师/学生端的可见性要求是「秒级生效，但当前会话不需要推送」。

### 6. 对接难度初判

**易** —— 纯 CRUD + 列表筛选/排序/分页，无实时无事务；唯一需后端补的是「逐测试点通过分布」（现在是用 `ac` 倒推的）。

---

## 页面：t-problem-edit.html（题目编辑）

### 1. 页面职责
出题主界面：基本信息 + 10 格判分链（测试点）+ Markdown 题面 + 右侧学生端实时预览，底部存草稿/发布（`t-problem-edit.html:235-679`）。

### 2. 该页读取的数据（读操作）

| 数据项 | 字段（组件里实际用的属性名，逐个列全） | 语义/来源 |
|---|---|---|
| 题目 ID | `pid`（固定 `1024`） | 〔硬编码〕`t-problem-edit.html:739`；`init()`（`:789-796`）**没有读 `OJ.qs('pid')`** —— 〔未在源码中找到〕按 URL `?pid=` 载入题目的逻辑，页面永远是 1024 |
| 最近保存时间 | `savedAt`（`'10-24 21:40'`） | 〔硬编码〕`t-problem-edit.html:740`；发布时改写为 `'10-25 15:20'`（`:880`） |
| 标题 | `title`（`'最大子段和'`） | 〔硬编码〕`:753` |
| 难度 | `lvl`（`3`）、`lvlNames` | `:754`、`:686`（`lvlNames` 取自 `OJ.lvlName`，注释 `:685` 声明全站唯一来源） |
| 标签 | `tags`（`['动态规划','前缀和']`）、`tagDraft` | `:757`、`:758`；上限 5 个（`:831`） |
| 知识点 | `kp`、`kpOptions` | `:759`、`:760`（6 个硬编码选项：动态规划/图论/字符串/树结构/排序与查找/数学） |
| 时限/内存 | `tl`（`1000`）、`ml`（`256`） | `:761-762` |
| AI 开关与上限 | `aiOn`（`true`）、`aiLimit`（`3`） | `:764-765` |
| AI 四阶段 | `stages[].k`、`.name`、`.on` | `t-problem-edit.html:766-771`；①读题拆解 on、②编码引导 on、③排错诊断 off、④AC 复盘 off |
| 题面 | `statement`（`DEF_STATEMENT`） | `:773`，模板在 `:689-701` |
| 测试点 | `tps[].uid`、`.kind`、`.input`、`.output`、`.score`、`.open` | `:776-787` 共 10 条；`kind`∈`sample`\|`normal`\|`edge`\|`stress`；`score` 合计 100 |
| 预览页码签 | `pvTab`（`'stmt'`\|`'chain'`） | `:751`，可由 `?tab=chain` 覆盖（`:791`） |
| 三态标记 | `loading`、`loadErr`、`saving`、`dirty`、`confirmOpen` | `:741-749` |

### 3. 该页写入/触发的动作（写操作）

| 动作（用户点的是什么、方法名） | 期望副作用 | 当前是否只是本地模拟 |
|---|---|---|
| 「存草稿」`saveDraft()` `t-problem-edit.html:863-872` | 落库为草稿，`dirty=false`，`savedAt` 刷新 | **是**：700ms `setTimeout` 后置标志位 + toast「草稿已保存 · 学生端仍不可见」 |
| 「保存并发布」→ `askPublish()` `:873-876` → `publish()` `:877-882` | 弹确认框；确认后落库并置 `published`，学生端题库立即可见，已有提交不受影响（文案 `:658-659`） | **是**：只改 `savedAt` 与 toast |
| 「还原」`resetForm()` `t-problem-edit.html:883-894` | 丢弃未保存修改，回上次保存态 | **是**：把 8 个字段硬写回初始字面量 |
| 添加测试点 `addTp()` `:841-845` | 末尾追加一个空点（`kind='normal'`, `score=0`） | 纯前端数组 |
| 删除测试点 `removeTp(i)` `:846-851` | 移除该点 | 纯前端 |
| 改分值 `x-model.number="tp.score"` `:441-443` | 触发 `dirty`，重算 `total` | 纯前端 |
| 展开输入输出 `tp.open = !tp.open` `:449-452` | 行内展开 textarea | 纯前端 |
| 加标签 `addTag()` `:828-836` / 删标签 `removeTag(i)` `:837-840` | 维护 `tags`，超 5 个报 warn | 纯前端 |
| 切 AI 阶段 `toggleStage(i)` `:823-827` | 切换 `stages[i].on` | 纯前端 |
| 「插入公式」`insertFormula()` `:852-856` / 「插入样例」`insertSample()` `:857-862` | 向 `statement` 追加 Markdown 片段 | 纯前端 |
| 「重试」`retryLoad()` `:895-899` | 重拉题目 | **是**：300ms `setTimeout` |

### 4. 该页的派生/计算逻辑

| 指标 | 方法名（源码位置） | 说明 |
|---|---|---|
| 分值合计 | `get total()` `t-problem-edit.html:799-801` | 10 个 `tp.score` 求和 |
| 可否发布 | `get canPublish()` `:802` | `total === 100 && !!title.trim()` —— **硬约束：满分必须恰好 100** |
| 校验失败原因 | `get publishHint()` `:803-808` | 三种文案；对应后端也应做同样校验 |
| 样例点数量 | `get sampleCount()` `:809`、`get hiddenCount()` `:810`、`get sampleTps()` `:811` | 样例点对学生可见（`:573-583`） |
| 已开 AI 阶段数 | `get onStageCount()` `:812` | 当前模板未渲染，属预留 |
| 题面段落数 | `get paragraphCount()` `:813-815` | 按空行切分 |
| Markdown → HTML | `md(src)` `t-problem-edit.html:715-736` + `get previewHtml()` `:816` | 只处理 `#`小标题 / 段落 / `**加粗**` / `` `行内代码` ``；**格式契约在此固化**，后端存原文即可，渲染必须一致 |
| 测试点类型中文名 | `kindName(k)` `:818-820` | `sample/normal/edge/stress` → 样例/普通/边界/构造 |

### 5. 该页的实时性要求

- **无**。无轮询、无 SSE、无 WebSocket。
- 仅加载骨架：`init()` 里 300ms 后 `loading=false`（`t-problem-edit.html:794-795`），`?skeleton=1` 时钉住 60 秒（`:794`）。
- 右侧预览是**本地即时计算**（`:571`、`:597`、`:607`），不需要服务端渲染接口；但「预览即学生所见」意味着后端渲染器必须与 `md()` 行为一致。

### 6. 对接难度初判

**难** —— 测试点输入输出是大文本/大文件（`n = 2×10^5` 的构造数据），需要独立的上传/校验与存储通道；发布是一次带强校验（分值=100、标题非空、至少 1 个测试点 `:488`）的原子操作；且当前页面**根本没有按 `pid` 载入**，从零到一的「读题目」接口完全缺失。

---

## 页面：t-assignments.html（作业管理）

### 1. 页面职责
教学活动的全集管理：13 项活动的状态/类型/完成率/均分总览，新建活动，抽屉里看 48 人逐生完成情况与风险（`t-assignments.html:119-472`）。

### 2. 该页读取的数据（读操作）

| 数据项 | 字段（组件里实际用的属性名，逐个列全） | 语义/来源 |
|---|---|---|
| 页头班级 | —（`计算机 2022 级 1 班 · 48 人 · 本学期 13 项`） | 〔硬编码〕`t-assignments.html:124` |
| 活动主表 | `a.id`、`a.title`、`a.type`、`a.typeName`、`a.pidCount`、`a.done`、`a.avg`、`a.ai`、`a.due`、`a.left`、`a.status`、`a.seed` | `SEED` 常量 `t-assignments.html:497-524`，13 条；`type`∈`practice`\|`homework`\|`exam`，`status`∈`doing`\|`todo`\|`done`，`ai` 是**中文字符串**如 `'仅①③ · 上限 3 次'`，`seed` 只用于伪随机派生的盐 |
| 应提交人数 | —（模板里写死 `/ 48`） | 〔硬编码〕`t-assignments.html:278`、`:286-287`、`:352`；`pct()` 里也是 `/48`（`:637`） |
| 学生名册 | `s.name`、`s.sid`（来自 `OJ.students`）+ 38 个补充姓名 | `ROSTER` 构造 `t-assignments.html:484-494`；补充学号 `'2022' + String(111+i).padStart(4,'0')` |
| 抽屉逐生完成情况 | `r.name`、`r.sid`、`r.prog`、`r.total`、`r.score`、`r.ai`、`r.risk`、`r.sub` | `roster(a)` `t-assignments.html:688-712`；`prog`=已完成题数，`total`=`a.pidCount`，`score` 可为 `null`，`risk`∈`ok`\|`ai`\|`low` |
| 抽屉头指标 | `detail.done`、`detail.avg`、`detail.pidCount`、`detail.due`、`detail.left`、`detail.ai`、`detail.status` | 模板 `:351-361`；`AI 调用` 由 `aiTotal(detail)` 现算（`:713-715`） |
| 筛选计数 | `statusCount(s.k)`、`typeCount(t.k)`、`acts.length` | `t-assignments.html:675-682` |
| 学生筛选计数 | `stuCount(f.k)` | `t-assignments.html:716-723`；四档 `all/sub/nosub/risk`（`:557-562`） |
| 时间提示「最近截止 10-26 23:59」 | — | 〔硬编码〕`t-assignments.html:137` |

### 3. 该页写入/触发的动作（写操作）

| 动作（用户点的是什么、方法名） | 期望副作用 | 当前是否只是本地模拟 |
|---|---|---|
| 「新建活动」→ 弹窗 →「存为草稿」`createAct()` `t-assignments.html:731-761` | 生成 `id='A-0'+(424+n)` 的草稿，`status='todo'`，插入列表首位 | **是**：只 `acts.unshift(made)`；5 秒撤销把它 `splice` 掉（`:757-760`） |
| 抽屉底部「发布活动」`toggle(a)` `t-assignments.html:762-781` | `todo→doing`，`left='剩 7 天'`，48 名学生可见 | **是**：只改本地 `status`/`left` |
| 抽屉底部「结束活动」`toggle(a)` `t-assignments.html:762-781` | `doing→done`，成绩归档 | **是**：同上 |
| 「导出成绩」`exportList()` `t-assignments.html:782` | 导出 CSV | **是**：只有 toast |
| toast「撤销」`undoToast()` `:797-805` | 5 秒回滚上一动作 | **是**：执行 `_undoFn` 闭包 |
| 筛选/排序/分页 `pickSort` `:663-667`、`toggleType` `:668-672`、`clearFilters` `:673` | 纯视图 | 纯前端 |
| 行点击 `openDetail(a)` `:726` | 打开完成情况抽屉 | 纯前端 |
| 「查看班级学情」`:418` | 跳 `t-analytics.html?aid=${detail.id}` | 纯导航（`aid` 参数实际未被学情页读取） |

### 4. 该页的派生/计算逻辑

| 指标 | 方法名（源码位置） | 说明 |
|---|---|---|
| 三态计数 | `get doingN()` / `get todoN()` / `get doneN()` `t-assignments.html:565-567` | 按 `status` 计数 |
| **平均完成率** | `get avgRate()` `t-assignments.html:568-573` | 13 项活动 `pct()` 的算术平均（含 2 项待发布，`:152` 文案已声明） |
| **单活动完成率** | `pct(a)` `t-assignments.html:637` | `Math.round(a.done/48*100)` —— **分母写死 48**，真实系统应为 `class.studentCount` |
| 筛选 + 排序 | `get filtered()` `t-assignments.html:583-614` | 类型多选 + 状态互斥 + 关键词；排序键 `title`/`rate`/`avg`/`status`（`status` 用 `rank={doing:0,todo:1,done:2}` 再按 `done` 降序） |
| 分页 | `get pages()` `:615`、`get pageRows()` `:621-625` | `PAGE = 9`（`:481`） |
| 学生表过滤 | `get stuRows()` `t-assignments.html:626-634` | `all/sub/nosub/risk` 四档 |
| **AI 四阶段格子** | `aiCells(a)` `t-assignments.html:647-654` | **靠字符串匹配 `a.ai.indexOf('全部关闭')` / `indexOf('全阶段')`** 反推开放阶段，`else → [1,3]`；这是最典型的「该由后端给数组却给了中文串」的地方 |
| AI 阶段文案 | `aiLabel(a)` `t-assignments.html:655-659` | `'全关'` / `'①②③④'` / `'①③'` |
| 新建活动时的 AI 策略 | `aiOf(t)` `t-assignments.html:683-685` | `exam→'全部关闭'`、`homework→'仅①③ · 上限 3 次'`、其余 `'全阶段 · 不限次'` |
| **逐生完成情况（伪随机）** | `roster(a)` `t-assignments.html:688-712` | 见下方专门说明 |
| 活动 AI 调用总量 | `aiTotal(a)` `t-assignments.html:713-715` | `roster(a)` 的 `ai` 求和 |

#### 关于 `roster(a)` 的伪随机生成（真实系统这里需要什么）

`t-assignments.html:688-712` 用 `(i*37 + a.seed*13) % 97` 给 48 人排个「提交顺序」，再取前 `a.done` 人视为已提交；每人再用 `(i*29 + a.seed*7) % 56` 造完成比例、`(i*19 + a.seed*11) % 19 - 9` 造分数抖动、`(i*5 + a.seed*3) % 9` 造 AI 次数。产物字段：

- `prog`（已完成题数，`0..total`）、`total`（=`a.pidCount`）
- `score`（`30..100` 的整数；未提交为 `null`）
- `ai`（该生在本活动中的 AI 调用次数，未提交为 `0`）
- `risk`（`ai`≥7 → `'ai'`；`base<0.5` → `'low'`；否则 `'ok'`）
- `sub`（布尔，是否已提交）

**真实系统对应的是「活动 × 学生」的完成明细表**，每行至少要有：`activityId`、`studentId`、`submitted`（bool）、`submittedAt`、`solvedCount`（已 AC 题数）、`totalCount`（活动题数）、`score`（活动得分，未提交为 null）、`aiCalls`（该活动内 AI 调用次数）、`lastActiveAt`、以及后端算好的 `risk`（`ok`/`ai`/`low`，附 `reason` 文案）。`risk` 的阈值（AI≥7 次、完成率<50%）现在硬编码在 `:705-707`，应由后端可配置。

### 5. 该页的实时性要求

- **无**。无 `setInterval`/SSE/WebSocket。
- 仅加载态：`init()` 600ms 后 `loading=false`（`t-assignments.html:816`），`retry()` 同（`:822`）。
- 完成率、均分这类数据是「教师查看时算一次」的批处理指标，无实时需求。

### 6. 对接难度初判

**中** —— 活动 CRUD + 逐生完成明细两个实体能覆盖；难点在 `roster` 把「活动×学生」明细压成了一个伪随机函数，后端要补一张真正的明细表，并把 `ai` 从中文串改成结构化阶段数组。

---

## 页面：t-analytics.html（班级学情）

### 1. 页面职责
班级维度的教学诊断：AI 薄弱点结论领衔，下接知识点掌握度、成绩分布、逐测试点热力图、48 人成绩表与风险预警（`t-analytics.html:209-676`）。

### 2. 该页读取的数据（读操作）

| 数据项 | 字段（组件里实际用的属性名，逐个列全） | 语义/来源 |
|---|---|---|
| 班级选择器 | `classes[].k`、`.label` | `t-analytics.html:777-781`；`cs2201`(48 人)/`cs2202`(46)/`se2201`(52) |
| 时间范围 | `ranges[].k`、`.label`；选中索引 `range` | `:782-786`；`week`/`month`/`term`，默认 `range=1`（本月，`:763`） |
| 页头副标题 | `rangeTip` | `:792-794`，三个范围都返回 `'10-24 23:59'` —— 〔硬编码〕，真实系统应为数据截止时间戳 |
| 学生主表 | `s.name`、`s.sid`、`s.solved`、`s.total`、`s.score`、`s.ai`、`s.rate`、`s.risk`、`s.spark` | `buildStudents(range)` `t-analytics.html:720-758`；`spark` 是长度 7 的数组（近 7 天提交次数，10–98 的百分位数值） |
| 学生风险与理由 | `s.risk`（`ok`\|`ai`\|`low`）、`s.reason` | `:735-743`；`reason` 由 `REASON`（`t-analytics.html:705-708`）拼出，如 `AI 调用 12 次，班级均值 4.3 次` |
| 学生按标签掌握 | `s.tags[].name`、`.pct`、`.ac`、`.tried` | `:744-747`；5 个知识点 `KP_NAMES`（`:703`） |
| 学生最近提交 | `s.recent[].id`、`.pid`、`.title`、`.status`、`.pass`、`.at` | `:750-755`；**4 条对所有学生完全相同**（`S-20481`/`S-20440`/`S-20412`/`S-20398`）—— 明显未按人区分 |
| 知识点掌握度清单 | `kps[].name`、`.pct`、`.ac`、`.tried`、`.delta` | `get kps()` `t-analytics.html:830-842`；由 `KP_BASE`（`:704`）加 `(range-1)*4` 偏移得出 |
| AI 薄弱点结论 | `weakest.name`、`.pct`、`.delta`、`.tried`、`.ac`；`avgKp`、`weakCount`；`classSize`、`rangeLabel`、`rangeTip` | 模板 `:255-278`；派生见下节 |
| 统计带 | `classSize`、`activeCount`、`avgScore`、`dist.median`、`dist.max`、`acRate`、`riskTotal`、`aiRiskCount` | 模板 `:311-332` |
| 成绩分布 | `dist.ac`、`dist.part`、`dist.fail`、`dist.max`、`dist.min`、`dist.median`、`dist.sd` | `get dist()` `t-analytics.html:804-823`；三段阈值 ≥85 / 60–84 / <60 |
| 测试点热力图 | `heatRows[].pid`、`.title`、`.pass`、`.cells`、`.okN` | `get heatRows()` `:879-901`；`cells` 是 10 个 `'ac'`\|`''`\|`'skip'`\|`'wa'` 的字符串数组 |
| 热力图页脚结论 | `heatFoot` | `:902-909`，依赖 `heatPick` |
| 风险学生卡 | `risks[].name`、`.sid`、`.reason`、`.risk` | `get risks()` `:843-845`，只取前 5 |
| AI 使用 TOP5 | `aiTop[].name`、`.sid`、`.ai`、`.stage` | `get aiTop()` `:849-855`；`stage` 是按风险类型硬凑的 `'② 编码引导为主'` / `'① 读题拆解为主'` |
| 学生抽屉趋势峰值 | `peakDay`、`peakCount`、`peakIdx` | `:910-921`；`peakDay` 用的是固定的 7 天数组 `['10-18'...'10-24']` |
| 「本周期待办」4 条 | —（`发布第五章课后作业` / `批改期中考试主观题` / `复核查重疑似对` / `生成学期学情报告`） | 〔硬编码〕`t-analytics.html:577-592`，与 `t-dashboard` 的待办不同源 |
| AC 率环比 | —（`4%` 较上周期） | 〔硬编码〕`t-analytics.html:325` |

### 3. 该页写入/触发的动作（写操作）

| 动作（用户点的是什么、方法名） | 期望副作用 | 当前是否只是本地模拟 |
|---|---|---|
| 「发送提醒」`remind()` `t-analytics.html:974-978` | 向该生推送学习提醒 | **是**：toast「已向 X 发送学习提醒」 |
| 「导出报告」`exportReport()` `:979` | 导出班级学情 CSV/PDF | **是**：只有 toast |
| 「布置『薄弱点』专项练习」`:267` | 跳 `t-assignments.html?kp=${weakest.name}` 并预填知识点 | 纯导航（`kp` 参数在活动页**未被读取**，`init()` 只认 `state`/`type`，见 `t-assignments.html:807-817`） |
| 「仅风险学生」`filterRisk()` `:957` | 过滤学生表 | 纯前端 |
| 排序 `sortBy(k)` `:951-955` | 5 列排序 | 纯前端 |
| 切班级 `x-model="cls"` `:227` | 重算全班 | **是**：只改 `classSize`（`:795`），学生数据完全不换（`all` 恒为 `buildStudents(1)`） |
| 切时间范围 `@click="range=i"` `:237` | 重算所有指标 | **是**：`$watch('range')` 里 `all = buildStudents(self.range)` + 600ms 假加载（`:1003-1008`） |
| 「重新加载」`retry()` `:1010-1015` | 重拉 | **是**：600ms `setTimeout` |

### 4. 该页的派生/计算逻辑

| 指标 | 方法名（源码位置） | 说明 |
|---|---|---|
| 班级人数 | `get classSize()` `t-analytics.html:795` | **按 `cls` 三元硬编码** 46/52/48，与 `classes[].label` 里的「48 人」重复 |
| 平均分 | `get avgScore()` `:796-799` | 48 人 `score` 算术平均 |
| **班级 AC 率** | `get acRate()` `:800-803` | 48 人 `rate` 的算术平均 —— 注释 `t-dashboard.html:567-569` 明确它是「按学生平均」，与概览页「按次数加权」不同 |
| **成绩分布** | `get dist()` `t-analytics.html:804-823` | 计算 `ac/part/fail` 百分比、`max`、`min`、`median`、`sd`（总体标准差，`:811`） |
| 活跃人数 | `get activeCount()` `:824-826` | `rate >= 55` 的人数（阈值硬编码） |
| AI 依赖人数 | `get aiRiskCount()` `:827-829` | `risk === 'ai'` 计数 |
| **知识点掌握度** | `get kps()` `:830-842` | `pct = clamp(KP_BASE[n] + (range-1)*4, 20, 98)`；`ac = round(pct/10)`、`tried = 10`（凑成一个假的 10 次样本）；`delta` 用 `(j%2?−1:1)*(2+j*2)` 造正负 |
| 风险学生列表 | `get risks()` `:843-845` | 前 5 条 |
| 风险总数 | `get riskTotal()` `:846-848` | 全部 `risk !== 'ok'` |
| AI 使用 TOP5 | `get aiTop()` `:849-855` + `get maxAi()` `:856` | 按 `ai` 降序取 5，含伪造的 `stage` |
| **最弱知识点** | `get weakest()` `t-analytics.html:862-864` | `kps` 按 `pct` 升序取第 1 个 |
| 知识点班级线 | `get avgKp()` `:865-868` | 5 个 `pct` 的算术平均 |
| 未达标知识点数 | `get weakCount()` `:869-871` | `pct < 70` 的个数（70% 为达标线，`:305`） |
| **测试点热力图** | `get heatRows()` `:879-901` | 取 `OJ.problems.slice(0,6)`，`pass = clamp(p.ac + (range-1)*4, 8, 96)`，前 `round(pass/100*10)` 格 `'ac'`，第 `okN` 格 `'skip'`，通过率最低那一行的 `okN` 格改 `'wa'`（全表仅一格） |
| 热力图脚注 | `get heatFoot()` `:902-909` | 拼接选中行的结论 |
| 抽屉峰值日 | `get peakIdx()` `:910-915`、`get peakCount()` `:916`、`get peakDay()` `:917-921` | 从 `detail.spark` 取最大值下标；日期用固定 7 天数组 |
| 单条提交判分链 | `chainOf(s)` `t-analytics.html:961-973` | `ac→10 格全绿`、`ce→全空`；否则前 `pass` 格绿、第 `pass` 格取 `s.status` |
| 学生表排序 | `get sorted()` `:923-933` | 5 个键，`typeof x === 'string'` 走 `localeCompare('zh')` |
| 分页 | `get pages()` `:934`、`get pageRows()` `:940-944` | `PAGE = 10`（`:689`） |

#### 关于 `buildStudents()` 的伪随机生成（真实系统这里需要什么）

`t-analytics.html:720-758` 用 `mix()`/`rnd()`（`:711-718`）按 `(i, range, salt)` 确定性地生成 48 行。每生需要的真实数据是：

- `score`（本期总分，原型 `55–96`，`:722`）
- `rate`（**个人 AC 率**，`39–85`，`:724`）
- `ai`（本期 AI 调用次数，`0–13`，`:725`）
- `solved` / `total`（已解决题数 / 总题数，原型 `solved = clamp(round(score/10),1,10)`、`total=10` 写死，`:726`、`:729`）
- `spark`（**近 7 天逐日提交次数**，长度 7 的数组，`:727-728`）
- `risk` + `reason`（后端判定与文案，`:736-743`）
- `tags[]`：每个知识点的 `{name, pct, ac, tried}` —— 即「该生在该标签下 AC 的次数 / 尝试次数」，`:744-747`
- `recent[]`：最近 4 条提交 `{id, pid, title, status, pass, at}`，`pass` 是「过了几个测试点」`:750-755`

其中 `recent` 是**全班级同一份**（4 条写死），`tags` 的 `pct` 也是用一个线性公式凑的 —— 这两处是后端最需要替换成真实数据的地方。

### 5. 该页的实时性要求

- **无轮询/SSE/WebSocket**（无 `setInterval`、无 `EventSource`）。
- 有一个响应式副作用：`this.$watch('range', ...)`（`t-analytics.html:1003-1008`）在切「本周/本月/学期」时重建数据并强制 600ms 假加载；注释 `:1001-1002` 的理由是「旧数据会在新条件下假装已经算完了」。这说明**真实系统需要按 `range` 重新请求**，属于请求级而非推送级要求。
- 学情是批处理指标，可接受「打开页面算一次」。

### 6. 对接难度初判

**难** —— 单页要 8 类聚合（班级概览、成绩分布与分位、知识点掌握度、AI 生成结论、逐测试点热力图、逐生明细、风险判定、AI 用量排行），且「热力图」和「知识点掌握度」目前都是**用单一通过率数字倒推 10 格/5 项的伪造**，后端必须提供真正的「题目 × 测试点」和「学生 × 标签」交叉统计。

---

## 页面：t-monitor.html（考试监考）

### 1. 页面职责
一场进行中考试的实时监考台：48 名考生按「需要关注 / 其余」分组铺开，右栏滚动事件流 + 考场概况，可对单个学生发警告/延时、可结束整场考试（`t-monitor.html:200-499`）。

### 2. 该页读取的数据（读操作）

| 数据项 | 字段（组件里实际用的属性名，逐个列全） | 语义/来源 |
|---|---|---|
| 页头考试信息 | —（`计算机 2022 级 1 班 · 数据结构 期中考试 · 5 题 · 120 分钟`） | 〔硬编码〕`t-monitor.html:205` |
| 开考时间 / 刷新周期 | —（`开考 10-24 19:00 · 监考模式已开启 · 每 5 秒刷新`） | 〔硬编码〕`t-monitor.html:220`；注意**实际定时器是 1 秒倒计时 + 8 秒造事件**（`:765-773`），文案写的「每 5 秒」与代码不符 |
| 倒计时 | `leftSec`（初值 `38*60+24` = 2304 秒）、`clock` | `t-monitor.html:603`、`:619-622`；到 0 自动 `endExam()`（`:768`） |
| 考生快照 | `s.name`、`s.sid`、`s.done`、`s.switch`、`s.status`、`s.enter`、`s.last`、`s.ai`、`s.problems`、`s.timeline` | `buildRoster()` `t-monitor.html:521-567` |
| 考生状态枚举 | `s.status` ∈ `'doing'` \| `'submitted'` \| `'offline'` \| `'abnormal'` | `t-monitor.html:542-545`，判定顺序：`done>=5→submitted`；`r3<0.055→offline`；`sw>=3→abnormal` |
| 考生作答记录 | `s.problems[].pid`、`.title`、`.cost`、`.status` | `t-monitor.html:526-532`、`:560-563`；5 道题固定，`status`∈`ac`\|`wa`\|`none`，`cost` 来自 `COST` 常量 `['12:41','06:08','18:22','21:05','15:47']` |
| 考生行为时间线 | `s.timeline[].t`、`.s`、`.tone` | `t-monitor.html:547-553`；`tone`∈`ok`\|`warn`\|`danger` |
| 事件流 | `e.id`、`e.sid`、`e.who`、`e.what`、`e.note`、`e.at`、`e.tone`、`e.icon`、`e.fresh` | `evtOf(s)` `t-monitor.html:570-581`；`e.fresh` 是纯前端动画标记 |
| 筛选计数 | `countOf(f.k)` | `t-monitor.html:735-738`，五档 `all/doing/submitted/offline/abnormal`（`:605-611`） |
| 考场概况 | `offlineCount`、`avgDone`、`switchWarn`、`silentTip`、`dist[].k/.label/.c/.color` | 模板 `:367-390`；派生见下节 |
| 分组标题文案 | `calmLabel`、`attention.length`、`abnormalCount`、`offlineCount` | 模板 `:255-289` |
| 学生详情抽屉 | `detail.name`、`.sid`、`.status`、`.done`、`.switch`、`.enter`、`.last`、`.ai`、`.timeline`、`.problems` | 模板 `:398-463` |
| 结束考试时间 | `endAt` | `t-monitor.html:601`，结束时写 `'21:14'`（`:748`） |
| 「5 题」分母 | —（`s.done/5`、`${detail.done}/5`） | 〔硬编码〕`t-monitor.html:275`、`:277`、`:294`、`:298`、`:414`、`:421`；应为 `exam.problemCount` |

### 3. 该页写入/触发的动作（写操作）

| 动作（用户点的是什么、方法名） | 期望副作用 | 当前是否只是本地模拟 |
|---|---|---|
| 「发送警告」`warn(s)` `t-monitor.html:740-743` | 向该考生推送考试警告（学生端弹窗），并留监考记录 | **是**：关抽屉 + toast |
| 「延长 5 分钟」`:470` | 该考生 `leftSec`/交卷时刻顺延 5 分钟 | **是**：只 toast「已为该学生延长 5 分钟」，`leftSec` 未变 |
| 「结束考试」→ 确认 → `endExam()` `t-monitor.html:744-753` | 学生端立即禁止提交，进行中的 38 人自动收卷，不可撤销（文案 `:485-487`） | **是**：把非 `submitted` 全置 `submitted`，插一条「考试已结束」事件 |
| 「导出记录」`:208` | 导出本次实时监考记录 | **是**：只有 toast |
| 「暂停/继续」事件流 `paused=!paused` `:335` | 暂停接收新事件 | **是**：只让 `tickEvent` 提前 return（`:696`） |
| 点事件定位 `locate(e)` `t-monitor.html:717-729` | 左侧滚到该生并闪蓝环 600ms | 纯前端，`data-sid` 属性选择器 |
| 筛选/搜索 `filter`、`kw` | 纯视图 | 纯前端 |
| 行点击 `openStu(s)` `:739` | 开抽屉 | 纯前端 |

### 4. 该页的派生/计算逻辑

| 指标 | 方法名（源码位置） | 说明 |
|---|---|---|
| 已交卷人数 | `get submittedCount()` `t-monitor.html:614` | `status === 'submitted'` |
| 进行中人数 | `get doingCount()` `:615` | 同上 |
| 断线人数 | `get offlineCount()` `:616` | 同上 |
| 异常人数 | `get abnormalCount()` `:617` | 同上 |
| 切屏累计 | `get switchWarn()` `:618` | 48 人 `switch` 求和 |
| 倒计时格式化 | `get clock()` `:619-622` | `mm:ss` |
| 搜索过滤 | `get base()` `:627-632` | `name` 或 `sid` 包含关键词 |
| **关注分组** | `get attention()` `t-monitor.html:633-646` | 只收 `abnormal`/`offline`；排序 `rank={offline:0,abnormal:1}` 再按 `switch` 降序 |
| **其余分组** | `get others()` `:647-654` | 排除异常/断线后按筛选 |
| 结果数 | `get resultCount()` `:655` | 两组之和；为 0 时显示空态 |
| 安静组标题 | `get calmLabel()` `:656-659` | `其余 N 人` / `筛选结果 · N 人` |
| **平均进度** | `get avgDone()` `:660-663` | 48 人 `done` 的算术平均，保留 1 位小数 |
| **最长静默** | `get silentTip()` `:664-668` | 非 `submitted` 里 `last` 字典序最小者 → `'19:31 · 姓名'` |
| **四档状态分布** | `get dist()` `t-monitor.html:673-681` | 按 `status` 精确计数，过滤掉 `c===0` 的档；注释 `:669-672` 强调「不可用减法推算，否则会出现『顶部进行中 26、右栏作答中 35』这类同屏矛盾」 |
| 完成百分比 | 模板内联 `Math.round(submittedCount/48*100)` `:379` | 分母硬编码 48 |

### 5. 该页的实时性要求（重点）

原型用两套 `setInterval` 假装实时，**没有** `EventSource` / `WebSocket`：

| 定时器 | 周期 | 源码 | 真实系统对应 |
|---|---|---|---|
| 倒计时 | `1000ms` | `t-monitor.html:765-769`：`leftSec--`，归零自动 `endExam()` | 服务端权威的考试结束时间；前端只做本地插值，需与服务端时钟对齐 |
| 伪造新事件 | `8000ms` | `t-monitor.html:773` + `tickEvent()` `:695-713`：轮流让一名 `doing` 学生 `done+1`，若满 5 则 `status='submitted'`，插到 `evts` 头部并 `splice(8)` 只留 8 条 | **这里必须换成 SSE 或 WebSocket**，见下 |

**「实时事件流」的数据结构**（`t-monitor.html:569-581` 注释：「一条事件 = 一个学生的一次行为。事件里带 sid，点它才知道该滚到谁」）：

```js
{
  id:   String,   // 唯一 ID，原型用 `${sid}-sw` / `${sid}-off` / `${sid}-sub` / `t${clock}-${sid}` / `'end'`
  sid:  String,   // 学号，前端用它做 `[data-sid="..."]` 定位；系统级事件为空串
  who:  String,   // 展示名（学生姓名，或 '系统'）
  what: String,   // 行为短句：`切屏 ${switch} 次` / '断线' / '交卷' / `完成第 ${done} 题` / '考试已结束'
  note: String,   // 补充说明：'连续切出考试页面，已记录' / '连接中断，等待重连' / '5 题全部作答，已自动判题' / 'AC · 用时 14:22' / '已自动收卷 38 份'
  at:   String,   // 'HH:MM' 文本（注意是字符串，不是时间戳）
  tone: String,   // 'ok' | 'warn' | 'danger' —— 决定图标底色
  icon: String,   // 图标名：'checkC' | 'plug' | 'alert' | 'stop'
  fresh:Boolean   // 纯前端：新事件插入后的 1400ms 入场动画标记（`:712`），不应来自后端
}
```

排序契约（`buildEvents()` `t-monitor.html:583-591`）：先按 `rank = {offline:0, abnormal:1, submitted:2}`，再按 `at` 倒序，`slice(0, 6)`。运行期 `tickEvent` 用 `unshift` 插入头部并截断到 8 条（`:710-711`）。

**建议的推送通道**：WebSocket（双向，因为同一通道还要承载「发送警告/延长/结束考试」三个写操作）或「SSE 下行 + REST 上行」。事件需要服务端生成 `seq` 或 `occurredAt`（ISO 8601）而非 `'HH:MM'`，以便断线重连后按 `Last-Event-ID` 补拉。

### 6. 对接难度初判

**极难** —— 唯一一个真正需要长连接的页面：既有秒级倒计时与心跳（`offline` 判定标准是「心跳超时 60 秒」，`:272`、`:552`），又有双向控制（警告/延时/结束考试），还要保证「事件流、学生卡进度、筛选计数、状态分布」四处数字同源一致（`:669-672` 的注释就是这个教训）。

---

## 页面：t-plagiarism.html（代码查重）

### 1. 页面职责
两阶段查重结果的复核台：左列并排双栏代码 + 相似片段导航，右栏相似度报告与疑似对列表，底部做「确认抄袭 / 独立完成 / 撤销」的判定（`t-plagiarism.html:154-384`）。

### 2. 该页读取的数据（读操作）

| 数据项 | 字段（组件里实际用的属性名，逐个列全） | 语义/来源 |
|---|---|---|
| 页头作业信息 | —（`数据结构 · 作业 3「最大子段和」· … · 提交 46 份 · 比对 1 035 对 · 两阶段耗时 42 秒`） | 〔硬编码〕`t-plagiarism.html:161` |
| 疑似对主表 | `pairs[].id`、`.pid`、`.title`、`.struct`、`.sem`、`.a`、`.b`、`.segs`、`.review` | `PAIRS` 常量 `t-plagiarism.html:471-492`，4 组；`struct`=结构相似度(0-100)，`sem`=语义相似度(0-100)，`review` 初始 undefined |
| 当事人 A/B | `.name`、`.sid`、`.lang`、`.at`、`.code` | `t-plagiarism.html:474-475` 等；`lang` 全为 `'C++17'`，`at` 是提交时间 `'10-21 21:04'` |
| 代码行 | `code[].n`、`.t`、`.s` | `lines()` `:469` 把 `[文本, 片段号]` 转成 `{n: 行号, t: 文本, s: 片段号}`；`s===0` 表示不属于任何相似片段 |
| 片段名称表 | `p.segs[k]`（k ∈ `1..4`） | `t-plagiarism.html:476`、`:481`、`:486`、`:491`；如 `'头文件与命名空间'`、`'循环体（状态转移）'` |
| 判定阈值 | 结构 `75`、语义 `85` | 〔硬编码〕`t-plagiarism.html:521`、文案 `:327`、`:331`、`:546` |
| 筛选计数 | `countOf(f.k)` | `t-plagiarism.html:557-562`；五档 `all/high/suspect/normal/todo`（`:504-510`） |
| 报告脚注 | —（`阶段一 AST → 阶段二 语义向量 · 10-24 20:16`） | 〔硬编码〕`t-plagiarism.html:338` |

### 3. 该页写入/触发的动作（写操作）

| 动作（用户点的是什么、方法名） | 期望副作用 | 当前是否只是本地模拟 |
|---|---|---|
| 「确认抄袭」`review(cur,'confirmed')` `t-plagiarism.html:747-753` | 写入学术诚信档案，同步成绩单，保留比对快照（文案 `:743`、`:745`） | **是**：只置 `p.review`，压一条 `hist` |
| 「标记独立完成」`review(cur,'independent')` `:747-753` | 该对不再进入待复核队列 | **是**：同上 |
| 「撤销」`undo()` `t-plagiarism.html:754-761` | 回退上一次处理 | **是**：`hist.pop()` 还原 `p.review` |
| 「重新查重」`rescan()` `t-plagiarism.html:762-767` | 重新跑两阶段比对，约 40 秒 | **是**：把 4 对 `review` 清空 + toast「已提交重新查重，预计 40 秒后完成」，**没有任何任务提交与进度查询** |
| 「导出报告」`:164` | 导出含 4 组疑似对比的 PDF | **是**：只有 toast |
| 片段导航 `gotoSeg(k)` `:727-731` / `prevSeg` `:732` / `nextSeg` `:733` | 双栏同步滚到第 k 段 | 纯前端 |
| 「只看相似片段」`onlySeg` `:240`、`:617-637` | 折叠非相似行 | 纯前端 |
| 双栏滚动同步 `syncScroll(e)` `t-plagiarism.html:708-714` | 两栏 `scrollTop` 对齐 | 纯前端 |
| 选中疑似对 `select(p)` `:721-726` | 切换对比对象，回第 1 段 | 纯前端 |

### 4. 该页的派生/计算逻辑

| 指标 | 方法名（源码位置） | 说明 |
|---|---|---|
| **综合相似度** | `overall(p)` `t-plagiarism.html:517` | `(p.struct + p.sem) / 2` —— 简单算术平均，真实系统应有可配置权重 |
| 数字格式化 | `pct(v)` `:513-516` | 整数不带小数、非整数 1 位（`96 / 96.8 / 96.4`） |
| **判定等级** | `level(p)` `t-plagiarism.html:520-525` | `struct>=75 && sem>=85 → 'high'`；`struct>=75 \|\| sem>=85 → 'suspect'`；否则 `'normal'` |
| 判定中文名 | `verdictName(p)` `:526-528` | 高度疑似 / 疑似 / 正常 |
| 判定配色 | `verdictCls(p)` `:529-531` | `high→wa`、`suspect→tle`、`normal→ac` |
| 判定解释文案 | `verdictText(p)` `:532-547` | 按等级拼「阶段一 AST…阶段二 语义向量…」三段解释 |
| 复核状态文案 | `statusOf(p)` `:548-552` | `已确认抄袭` / `独立完成` / `待复核` / `无需复核` |
| 超阈值集合 | `get flagged()` `:555` | `struct>=75 \|\| sem>=85` |
| 待复核集合 | `get pending()` `:556` | `flagged` 且无 `review` |
| 筛选结果 | `get shownPairs()` `:563-568` | 五档 |
| **逐行对齐** | `runsOf(code)` `:577-593` + `alignedRows()` `:594-616` | 按片段号切连续块，同名块取较长侧、短侧补 `pad` 空行；行高 CSS 钉死 22px（`:45`），因此只同步 `scrollTop` 即可对齐 |
| 渲染行装配 | `rowsFor(side)` `t-plagiarism.html:617-637`、`get rowsA()` `:638`、`get rowsB()` `:639` | 每行 `{n, t, cls, ann}`；`ann` 只在块末行给「相同片段 #N」 |
| **片段信息** | `get segInfo()` `:642-657` | 从 A 侧代码扫出每个片段号的 `from`/`to` 行号，挂上 `segs[k]` 名称 |
| 最长连续相似 | `get maxRun()` `t-plagiarism.html:658-664` | 遍历对齐行，统计 `seg` 非 0 的最长连跑 |
| 片段明细表 | `get segRows()` `:667-687` | 每段两侧各自的 `aRange`/`bRange`/`count` |
| 当前片段标签 | `get curSegLabel()` `:688-692` | `第 from–to 行 · 名称` |
| 片段行索引 | `rowIndexOf(seg)` `:693-703` | 按「筛选后真正渲染的行」计数（注释 `:694-695`） |

### 5. 该页的实时性要求

- **无轮询/SSE/WebSocket**，源码无 `setInterval`、无 `EventSource`。
- 但有**异步任务语义**：`rescan()`（`t-plagiarism.html:762-767`）承诺「预计 40 秒后完成」，页头也写「两阶段耗时 42 秒」（`:161`），**却没有任何任务状态查询**。真实系统需要：`POST /rescan` → 返回 `taskId` → 轮询或 SSE 推进度 → 完成后刷新 `pairs`。这是本页唯一的时间维度需求。
- 代码块渲染是纯前端（`:394-467` 的 `CODE` 常量），不需要服务端 diff 接口 —— 但代价是相似片段必须由后端预先算好 `code[].s` 这个「片段编号」标注。

### 6. 对接难度初判

**极难** —— 需要真正的代码相似度计算服务（AST 结构 + 语义向量两阶段），并把「逐行片段归属」作为一等数据返回（`code[].s`）；同时判定动作要写学术诚信档案并留 5 秒可撤销窗口，属跨模块写事务。

---

## 教师端横向汇总

### A. 合并去重后的业务实体

| # | 业务实体 | 被哪些页面使用 | 关键字段（去重合并后） | 备注 |
|---|---|---|---|---|
| E1 | **班级 Class** | dashboard, analytics, assignments, monitor | `id`、`name`、`grade`（如 `2022`）、`major`、`studentCount`、`weekNo`、`term` | `studentCount` 现在散落为三处硬编码 `48`（assignments `:637`、monitor `:379`、analytics `:795`） |
| E2 | **学生 Student** | 全部 7 页 | `sid`、`name`、`classId`、`avatar` | `sid` 为 8 位学号（`20220101`），前端多处用 `name.slice(0,1)` 做头像 |
| E3 | **题目 Problem** | problems, problem-edit, analytics, dashboard, monitor, plagiarism | `id`、`title`、`lvl`(1-5)、`tags[]`、`state`(`published`/`draft`/`archived`)、`updatedAt`、`timeLimitMs`、`memoryLimitMb`、`scoreMode`、`statement`(Markdown)、`knowledgePoint` | `lvl` 的中文名由 `OJ.lvlName()` 统一（problem-edit `:686`） |
| E4 | **测试点 TestCase** | problems, problem-edit, analytics | `id`、`problemId`、`seq`、`kind`(`sample`/`normal`/`edge`/`stress`)、`input`、`output`、`score`、`enabled`、`inputSize`、`expectedOut` | 分值合计必须 = 100（problem-edit `:802`）；样例点对学生可见（`:488`） |
| E5 | **提交 Submission** | analytics, monitor, plagiarism, dashboard | `id`、`sid`、`pid`、`status`(`ac`/`wa`/`tle`/`re`/`ce`)、`passedCases`(0-10)、`submittedAt`、`lang`、`code`、`costMs` | `pass` 是「过了几个测试点」，chains 全靠它画（analytics `:961`、monitor `:562`） |
| E6 | **活动/作业 Assignment** | assignments, dashboard, analytics | `id`(`A-04xx`)、`title`、`type`(`practice`/`homework`/`exam`)、`status`(`doing`/`todo`/`done`)、`problemCount`、`dueAt`、`aiPolicy`、`createdAt` | `aiPolicy` 现在是中文串 `'仅①③ · 上限 3 次'`，须改成结构化 `{stages:[1,3], limit:3}` |
| E7 | **活动参与记录 AssignmentProgress** | assignments | `activityId`、`sid`、`submitted`、`solvedCount`、`totalCount`、`score`、`aiCalls`、`risk`、`riskReason`、`lastActiveAt` | 现由 `roster(a)` 伪随机生成（assignments `:688-712`） |
| E8 | **考试场次 ExamSession** | monitor | `id`、`assignmentId`、`startAt`、`endAt`、`leftSec`、`status`、`problemCount`、`proctorMode` | `leftSec` 需服务端权威（monitor `:603`） |
| E9 | **考场考生状态 ExamSeat** | monitor | `sid`、`name`、`status`(`doing`/`submitted`/`offline`/`abnormal`)、`done`、`switchCount`、`enterAt`、`lastActiveAt`、`aiCalls`、`timeline[]`、`problems[]` | `offline` 判定标准是「心跳超时 60 秒」（monitor `:272`） |
| E10 | **监考事件 ExamEvent** | monitor | `id`、`sid`、`who`、`what`、`note`、`at`、`tone`、`icon`（+ 服务端应有 `seq`/`occurredAt`） | 唯一需要推送的实体，见 §C |
| E11 | **AI 调用记录 AiCall** | dashboard, analytics, assignments, problem-edit | `id`、`sid`、`pid`、`stage`(1-4)、`route`(`cloud-tutor`/`local-router`/`fallback`)、`ttftMs`、`createdAt`、`success` | 概览页的 1840/1325/386/129 三段账必须与此表同源 |
| E12 | **知识点掌握度 KnowledgeMastery** | analytics, problems | `sid`(或 `classId`)、`kpName`、`tried`、`ac`、`pct`、`delta` | analytics 现在用 `KP_BASE` 常量 + 线性公式伪造（`:704`、`:744-747`） |
| E13 | **测试点通过分布 CasePassStat** | problems, analytics | `pid`、`caseSeq`、`passCount`、`failCount`、`stuckCount` | **原型中不存在**，但两个页面都在伪造它（problems 用 `okCells` 倒推、analytics 用 `pass/100*10` 倒推） |
| E14 | **查重任务 PlagiarismScan** | plagiarism | `id`、`assignmentId`、`submittedCount`、`pairCount`、`durationMs`、`status`、`startedAt` | `rescan()` 承诺 40 秒但无任务对象（`:766`） |
| E15 | **查重对 PlagiarismPair** | plagiarism | `id`、`pid`、`a{sid,name,lang,at,code[]}`、`b{...}`、`struct`、`sem`、`segs{}`、`review`(`confirmed`/`independent`/null)、`reviewedAt`、`reviewerId` | `code[].s` 是「片段编号」，后端必须给 |
| E16 | **待办 Todo** | dashboard, analytics | `key`、`label`、`sub`、`count`、`icon`、`href`、`actionType`、`severity` | dashboard 的 `todos` 与 analytics 的「本周期待办」**两处不同源**（`t-dashboard.html:548-557` vs `t-analytics.html:577-592`） |
| E17 | **周趋势 DailyStat** | dashboard | `date`、`weekday`、`submitCount`、`acCount` | 概览页 7 条（`:507-515`） |
| E18 | **易错题排行 ProblemDifficultyStat** | dashboard | `pid`、`title`、`lvl`、`tags`、`tried`、`acPct`、`passCount`、`avgAttempts`、`firstFailCase`、`topFailStudents[]` | 概览抽屉（`:517-528`） |

### B. 合并去重后的接口能力

**读侧（GET / 查询）**

| # | 能力 | 覆盖页面 | 关键参数 |
|---|---|---|---|
| R1 | 班级列表与基本信息 | analytics, assignments, dashboard | `teacherId` → `classes[]` |
| R2 | 教师待办聚合 | dashboard, analytics | `classId`、`week` → `todos[]`（含 `count`、`severity`、`actionType`） |
| R3 | 周日历趋势 | dashboard | `classId`、`weekStart` → 7 条 `{date,submitCount,acCount}` |
| R4 | 周指标汇总（两种口径） | dashboard, analytics | 需同时返回 `acBySubmissionRate`（按次数加权）与 `acByStudentAvg`（按学生平均），注释明确不可混用（`t-dashboard.html:567-569`） |
| R5 | 易错题排行 | dashboard | `classId`、`week`、`sort=ac_asc\|tried_desc`、`limit` |
| R6 | 单题错误分析 | dashboard | `pid` → `{tried, pass, avg, first, errorTypeDist{wa,tle,re,ce}, students[]}` |
| R7 | 题目列表（筛选/排序/分页） | problems | `state`、`lvl[]`、`tag`、`kw`、`sortKey`、`sortDir`、`page`、`pageSize=8` |
| R8 | 题目详情（含测试点） | problems, problem-edit | `pid` → Problem + `tps[]`（problem-edit 目前**完全没有这个读接口**） |
| R9 | 逐测试点通过分布 | problems, analytics | `pid` → `cases[{seq, passCount, stuckCount}]` —— **新增，替代两处伪造** |
| R10 | 标签/难度字典 | problems, problem-edit | `classId` → `tags[]`、`lvlOptions[]`、`kpOptions[]` |
| R11 | 活动列表（筛选/排序/分页） | assignments | `status`、`type[]`、`kw`、`sortKey`、`sortDir`、`page`、`pageSize=9` |
| R12 | 活动完成明细（逐生） | assignments | `assignmentId` → `[{sid,name,submitted,solvedCount,totalCount,score,aiCalls,risk,riskReason}]` |
| R13 | 班级学情总览 | analytics | `classId`、`range=week\|month\|term` → `{avgScore, acRate, dist{ac,part,fail,max,min,median,sd}, activeCount, riskTotal, aiRiskCount}` |
| R14 | 知识点掌握度（班级） | analytics | `classId`、`range` → `[{name,pct,ac,tried,delta}]` |
| R15 | 逐生学情明细 | analytics | `classId`、`range` → `[{sid,name,solved,total,score,ai,rate,risk,riskReason,spark[7],tags[],recent[]}]`（`spark` 是近 7 天提交次数） |
| R16 | 风险学生列表 | analytics | `classId`、`range` → 全量 + `reason` |
| R17 | AI 用量 TOP N | analytics, dashboard | `classId`、`range`、`limit=5` → `[{sid,name,calls,topStage}]` |
| R18 | 考试场次信息 | monitor | `examId` → `{name,classId,startAt,endAt,leftSec,problemCount,status}` |
| R19 | 考场花名册（含实时状态） | monitor | `examId` → `ExamSeat[]`（含 `timeline[]`、`problems[]`） |
| R20 | 监考事件拉取（增量） | monitor | `examId`、`sinceSeq` / `Last-Event-ID` → `ExamEvent[]` |
| R21 | 查重任务与疑似对列表 | plagiarism | `assignmentId`、`filter=all\|high\|suspect\|normal\|todo` |
| R22 | 单对查重详情（含代码与片段） | plagiarism | `pairId` → `{a{...code[]}, b{...code[]}, struct, sem, segs{}}` |

**写侧（POST / PUT / PATCH）**

| # | 能力 | 覆盖页面 | 说明 |
|---|---|---|---|
| W1 | 批量改题目状态 | problems | `PATCH /problems/status {ids[], state}`（批量发布/批量归档/单题归档恢复） |
| W2 | 导入题目 | problems | `POST /problems/import` —— 原型只 toast 不落库（`t-problems.html:653-656`），是纯缺口 |
| W3 | 保存题目草稿 | problem-edit | `PUT /problems/{pid}`，需带 `tps[]` 与 `statement` |
| W4 | 发布题目 | problem-edit | `POST /problems/{pid}/publish`，前置校验：`sum(score)===100 && title` 非空（`:802-808`）、≥1 个测试点（`:488`） |
| W5 | 创建活动 | assignments | `POST /assignments`，返回 `id`；AI 策略随 `type` 自动套用（`:683-685`） |
| W6 | 发布/结束活动 | assignments | `POST /assignments/{id}/publish`、`/close` |
| W7 | 导出成绩 | assignments, analytics | `GET /export/...`（CSV），原型两处都只 toast |
| W8 | 发送学习提醒 | analytics | `POST /students/{sid}/remind` |
| W9 | 发送考试警告 | monitor | `POST /exams/{id}/seats/{sid}/warn` |
| W10 | 延长考试时间 | monitor | `POST /exams/{id}/seats/{sid}/extend {minutes:5}` |
| W11 | 结束考试（不可撤销） | monitor | `POST /exams/{id}/end` → 服务端强制收卷（`:485-487` 文案声明不可撤销） |
| W12 | 提交复核结论 | plagiarism | `POST /plagiarism/pairs/{id}/review {verdict:'confirmed'\|'independent'}`，要写学术诚信档案 + 留快照（`:745`） |
| W13 | 撤销复核结论 | plagiarism | `POST /plagiarism/pairs/{id}/review/undo`，5 秒窗口（`:772`） |
| W14 | 重新查重（异步任务） | plagiarism | `POST /plagiarism/scans` → `{taskId}`；再 `GET /plagiarism/scans/{taskId}` 查进度（**原型完全缺失**） |

**推侧（Push）**

| # | 能力 | 覆盖页面 | 说明 |
|---|---|---|---|
| P1 | 考试事件流 | monitor | `WS /exams/{id}/stream` 或 `SSE /exams/{id}/events`；载荷见 §C 的 `ExamEvent`；周期要求：倒计时 1s 本地插值 + 事件秒级推送 |
| P2 | 查重任务进度 | plagiarism | `SSE /plagiarism/scans/{taskId}`，或 5s 轮询（原型承诺 40 秒完成） |

### C. 跨页一致性约束（后端最容易踩的坑）

1. **两套 AC 率不可混用**：`t-dashboard.html:567-569` 明确「本周提交通过率按提交次数加权」与 `t-analytics.html:800-803` 的「班级 AC 率按学生算术平均」是两个指标，同屏不能共用同一个数。
2. **人数分母统一**：`48` 在 4 个文件里硬编码（`t-dashboard.html:355`、`t-assignments.html:637`、`t-analytics.html:795`、`t-monitor.html:379`、`t-plagiarism.html:161` 的 46 份除外），后端必须给 `class.studentCount`。
3. **AI 调用总量一套账**：`t-dashboard.html:419` 注释要求与 `a-models` 页「路由分布」对齐，`1840 = 1325 + 386 + 129` 必须能对上 `AiCall` 表。
4. **待办两处不同源**：`t-dashboard.html:548-557` 的 3 条与 `t-analytics.html:577-592` 的 4 条内容完全不一致，需要统一到一个待办服务。
5. **同一数字只出现一次**：多个页面的注释（如 `t-analytics.html:60-63`、`:527-528`、`t-monitor.html:14-21`）反复强调「同一个数字在一屏里出现两次会被当成两个口径」，这是设计约束，也是后端返回值要能自洽的压力点。
6. **直播数字必须同源**：`t-monitor.html:669-672` 注释指出「顶部进行中 26、右栏作答中 35」这类矛盾来自用减法推算，四档分布必须由同一份 `ExamSeat[]` 精确计数得到。

### D. 缺失接口总清单（原型画了但没接，或压根没画）

| 缺口 | 证据 | 影响 |
|---|---|---|
| 题目按 `pid` 载入 | `t-problem-edit.html:739` 的 `pid` 固定 1024，`init()`（`:789-796`）从不读 `OJ.qs('pid')` | 编辑页无法作为真实路由使用 |
| 题目测试点上传/校验 | `t-problem-edit.html` 只在本地编辑 `tps[]`，无文件上传控件更无分片 | 大数据测试点（`n=2×10^5`）无法落地 |
| 导入题目 | `t-problems.html:653-656` 只 toast，无数组变更 | 功能空壳 |
| 逐测试点通过分布 | `t-problems.html:560-563`、`t-analytics.html:886-893` 都用 AC 率倒推 | 两个页面的「判分链」都是编的 |
| 逐生知识点掌握 | `t-analytics.html:744-747` 用 `KP_BASE + 线性公式` | 学情结论不可信 |
| 逐生最近提交 | `t-analytics.html:750-755` 对所有学生返回同 4 条 | 抽屉内容错 |
| 查重任务对象与进度 | `t-plagiarism.html:762-767` 承诺 40 秒但无 task 查询 | 「重新查重」无法反馈 |
| 教师端登录/权限 | 7 个页面均无 `teacherId`、无鉴权头、无 401 处理；`data-role="teacher"` 只是 HTML 属性（如 `t-dashboard.html:149`） | 所有接口缺租户上下文 |
| 错误码契约 | 各页错误文案写死 `HTTP 503`（`t-problems.html:336`、`t-assignments.html:311`、`t-analytics.html:503`）与 `504`（`t-problem-edit.html:279`） | 需统一错误码枚举 |
