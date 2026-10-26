# 学生端原型 · 字段级数据需求盘点

> 分析对象：`prototype/app/` 下学生端 6 个页面 + 1 个共享数据层（`shell.js`）。
> 比对基准：《系统详细设计与接口契约规范（Detailed Design & API Specification）》——9 张 MySQL 表（L15-L190）、6 类 Redis Key（L194-L203）、7 个 REST 接口（L215-L441）、内部 Feign / RabbitMQ / Go-Judge（L445-L566）。
> 判定口径：`A` = 现有接口直接覆盖；`B` = 现有表可派生但无接口；`C` = 现有表缺字段；`D` = 无任何表落点；`E` = 纯前端派生可算。
> 说明：契约对外只有 7 个 REST（judge 3 个 / ai 2 个 / teaching 2 个），**不存在**题目列表、作业列表、提交记录列表、学情分析、作业提交（交作业/交卷）任何接口。这是本盘点里 B 类占比极高的根因。

---

## 页面：s-problems.html（题库列表）

### 数据需求表

| # | 页面模块 / 交互点 | 所需数据（字段级） | 类型与取值域 | 数据来源判定 | 现有契约覆盖情况 | 缺口说明 |
|---|---|---|---|---|---|---|
| 1 | 页头「数据结构 · 12 道题 · 第五章 动态规划专题」 | 当前筛选范围题目总数 `total`、专题/作业名 `assignmentTitle` | int ≥0；string | B-现有表可派生但无接口 | `prob_question`（可 `COUNT(*)`）+ `teach_assignment.title`；无列表接口 | 契约无题目列表/统计接口，12 只能写死 |
| 2 | 页头「10-26 23:59 截止」 | `teach_assignment.end_time`、`teach_assignment.class_id` → 班级名 | datetime；long | B-现有表可派生但无接口 | `teach_assignment.end_time` / `teach_class.class_name`；无作业详情接口 | 无「当前作业上下文」接口，页头文案硬编码 |
| 3 | 主角卡（绑定 `OJ.problems[0]`，即 #1024） | `problem.id / title` | long / string | B-现有表可派生但无接口 | `prob_question.id` / `prob_question.title` | 无单题/首题接口，直接取 mock 数组第 0 条 |
| 4 | 主角卡难度「中等 3/5」 | `problem.difficulty` → 5 档刻度 | tinyint；契约 1\|2\|3，前端需 1..5 | C-现有表缺字段 | `prob_question.difficulty`（仅 3 档） | 前端 `lvlName` 是 5 档（入门/简单/中等/较难/困难），契约缺 4/5 档定义 |
| 5 | 主角卡标签「动态规划 · 前缀和」 | `problem.tags[]` | JSON array\<string\> | B-现有表可派生但无接口 | `prob_question.tags` | 表有字段，无接口下发 |
| 6 | 主角卡「1841 人尝试 · AC 62%」 | `problem.submit_count` / `problem.pass_count`（`ac = pass/submit`） | int / int；percent float | B-现有表可派生但无接口 | `prob_question.submit_count` / `prob_question.pass_count` | mock 键名为 `ac`/`tried`，与契约 `pass_count`/`submit_count` 不同名；AC 率为派生值 |
| 7 | 主角卡「上次停在 测试点 #9 · 8/10 通过 · 80 分」 | 该题最近一次提交的 `first_failed_case_id / pass_case_count / total_case_count / oi_score` | int / int / int / int 0~100 | B-现有表可派生但无接口 | `judge_submission.first_failed_case_id / pass_case_count / total_case_count / oi_score` | 无「我在这题的最后一次提交」查询接口；原型用 `Math.round(score/10)` 反推测试点名，非真实 case 数据 |
| 8 | 主角卡提交时间「10-24 23:41」 | `judge_submission.create_time` | datetime | B-现有表可派生但无接口 | `judge_submission.create_time` | 同上，无接口；原型写死字符串 |
| 9 | 主角卡「剩 2 天」倒计时 | `end_time - now()` → 剩余天数 | int（天） | E-纯前端派生可算 | `teach_assignment.end_time`（值需由 #2 提供） | 差值可前端算，但 `end_time` 本身无接口来源 |
| 10 | 右窄带「已解决 2 / 12」「已尝试 7」 | 每题我的状态聚合：`solved = COUNT(myStatus='AC')`、`tried = COUNT(myStatus<>'NONE')` | int / int | B-现有表可派生但无接口 | `judge_submission`（user_id + problem_id 聚合）+ `prob_question` 总数 | 无聚合接口；原型直接遍历 `OJ.problems` 12 条 |
| 11 | 右窄带「解决率 29%」 | `solved / tried` | percent int | E-纯前端派生可算 | 派生自 #10 | 除零需保护（tried=0），原型已处理 |
| 12 | 右窄带「本周新增 3」 | 本周新增 AC 题数（`status=3` 且本周内，按 problem_id 去重） | int | B-现有表可派生但无接口 | `judge_submission.status` + `create_time` | 无接口；写死 3 |
| 13 | 状态筛选 4 档 + 徽标计数（12/7/2/3） | `myStatus ∈ {NONE, AC, 其它}`，各档 `count` | string 枚举；int | B-现有表可派生但无接口 | 由 `judge_submission.status` 派生 | 无接口，计数由前端对 12 条 mock 现算 |
| 14 | 难度多选 `lvls[]`（1..5） | 难度可选集合与计数 | int[] | C-现有表缺字段 | `prob_question.difficulty` 仅 1/2/3 | 契约 3 档 vs 前端 5 档，筛选项与数据源枚举不闭合 |
| 15 | 关键词搜索（匹配 `title` + `String(id)`） | `title` / `id` 模糊匹配 | string | B-现有表可派生但无接口 | `prob_question.title` / `prob_question.id` | 无搜索接口，只能前端全量过 |
| 16 | 列表首列状态圆点 `.pdot` | `myStatus` → 颜色 | 'none'\|'ac'\|'wa'\|'tle'\|'mle'\|'re'\|'ce'\|'pac' | B-现有表可派生但无接口 | `judge_submission.status`（TINYINT 0-9 映射） | 无接口；`none` 需靠「无提交记录」推 |
| 17 | 列表副标题「1841 人尝试 · AC 62%」 | 同 #6 | int / percent | B-现有表可派生但无接口 | `prob_question.submit_count` / `pass_count` | 同 #6 |
| 18 | 列表标签列 | `problem.tags[]` | JSON array | B-现有表可派生但无接口 | `prob_question.tags` | 同 #5 |
| 19 | 列表难度列 `OJ.lvl(p.lvl)` | `difficulty` → 5 格刻度 | tinyint | C-现有表缺字段 | `prob_question.difficulty` | 同 #4 |
| 20 | 列表判分链 10 格 `chain(p)` | 该题最近一次提交的 `case_details[].status`（10 条，按 `caseId` 升序） | array\<{caseId:int, status:string}\> | B-现有表可派生但无接口 | `judge_submission.case_details` JSON | 表有；无「每题最近一次提交」接口；源码注释已承认「题库没有逐测试点数据，按得分推一个一致的结果」 |
| 21 | 列表「我的最好」列 | `bestSubmission.status` + `max_score` | string / int 0~100 | B-现有表可派生但无接口 | `teach_student_score.max_score` / `best_submission_id` → `judge_submission.status` | 无接口；自由练习（`assignment_id=0`）是否维护 `teach_student_score` 未见定义 |
| 22 | 操作列「重做/开做」文案切换 | `myStatus` 派生 | string | E-纯前端派生可算 | 派生 | 纯前端三元 |
| 23 | 表头排序（id / lvl / rate / score，升降） | 服务端排序参数 `sortKey` + `sortDir` | enum('id','difficulty','pass_rate','my_score')；asc\|desc | B-现有表可派生但无接口 | `prob_question.id / difficulty / pass_count` + `teach_student_score.max_score` | 无接口与排序参数约定；原型只排当前 12 条 |
| 24 | 分页器（1 / 2 / 下一页） | `page` / `pageSize` / `total` | int / int / int | B-现有表可派生但无接口 | `prob_question` | 分页器是**纯静态装饰**，无 `x-model`、无点击处理，无任何后端绑定 |
| 25 | 抽屉题面正文 | `content` | text（Markdown 或 HTML） | B-现有表可派生但无接口 | `prob_question.content` | 无接口；抽屉正文硬编码一段「给定一个整数序列 a₁…aₙ」 |
| 26 | 抽屉两组样例 | `sample_cases[].input` / `sample_cases[].output` | JSON array\<{input,output}\> | B-现有表可派生但无接口 | `prob_question.sample_cases` | 无接口；样例硬编码（`3 / -1 -2 -3` → `-1`） |
| 27 | 抽屉时限 / 内存 | `time_limit` / `memory_limit` | int（ms）/ int（MB） | B-现有表可派生但无接口 | `prob_question.time_limit` / `memory_limit` | 硬编码 1000ms / 256MB；语言倍率是全局规则（E） |
| 28 | 抽屉「测试点 10 组 · 含 2 组隐藏」 | `test_case_count` + 隐藏组数 | int / int | C-现有表缺字段 | `prob_question.test_case_count`=10；**无 `hidden_case_count` / `visible_case_count`** | 「含 2 组隐藏」这一语义在契约里没有任何字段可落 |
| 29 | 抽屉「我的记录」 | 该题我的提交 `id / status / oi_score` 列表 | array | B-现有表可派生但无接口 | `judge_submission`（user_id + problem_id 过滤） | 无提交列表接口 |
| 30 | 侧栏导航「我的作业 3」badge | 待办作业数 | int | B-现有表可派生但无接口 | `teach_assignment` + `teach_student_score` | 写死 3，无接口 |
| 31 | 三态（骨架 600ms / HTTP 503 / 空态） | `loading`、HTTP 状态码 | boolean / int | E-纯前端派生可算 | 无 | 纯前端开关，`?err=1` / `?skeleton=1` 只是评审脚手架 |

### 交互与写操作清单

| 操作 | 触发位置 | 是否写库 | 需要的接口语义 |
|---|---|---|---|
| 切换状态筛选 | `.seg` 四个 tab（`setStatus`） | 否 | 无写操作；需 `GET` 列表带 `status` 参数（服务端筛选） |
| 勾选难度 | 难度多选（`lvls[]`） | 否 | 需 `GET` 列表带 `difficulty` 多值参数 |
| 关键词搜索 | 搜索框 `x-model="kw"` | 否 | 需 `GET` 列表带 `keyword`，服务端匹配 title/id |
| 表头排序 | `th` 上 `pickSort` | 否 | 需 `GET` 列表带 `sortKey` / `sortDir` |
| 翻页 | `.pager`（无绑定） | 否 | 需 `GET` 列表带 `page` / `pageSize`，返回 `total` |
| 打开题目抽屉 | 行点击 / `preview` | 否 | 需 `GET /api/problem/{id}` 般单题详情（含 content/sample_cases/limits/我的记录） |
| 清空筛选 | 「清空筛选」按钮 | 否 | 无写操作，纯前端复位 |
| 跳转工作台 | 「重做/开做」→ `s-workbench.html?pid=` | 否 | 无写操作（GET 深链） |

### 枚举/状态机

- **我的题目状态**（`p.status`）：`none`（未提交）/ `ac` / `wa` / `tle` / `mle` / `re` / `ce` / `pac`。契约对应 `judge_submission.status` 的 `3/4/5/6/7/8/9`（`0 排队/1 编译中/2 评测中` 无对应展示态）。**不一致点**：契约有 `PAC`(4) 而原型筛选器无 PAC 档；原型的 `none` 在契约中需靠「无记录」表达，不是枚举值。
- **筛选档位**：`all`(全部) / `todo`(未开始) / `done`(已通过) / `fail`(未通过)。`done`→`ac`，`fail`→`wa|tle|mle|re|ce`（聚合了 5 个状态）。
- **难度档**：前端 `1 入门 / 2 简单 / 3 中等 / 4 较难 / 5 困难` + 5 格刻度（`.lvl` 的 `data-l` 与 `i.on` 数量）。契约 `difficulty = 1 简单 / 2 中等 / 3 困难`。**明确不一致（3 档 vs 5 档）**。
- **标签 tag class（颜色语义）**：`.tag.ac`（绿，AC）、`.tag.wa`（红，WA）、`.tag.tle`（琥珀，TLE）、`.tag.mle`（紫，MLE）、`.tag.re`（红，RE）、`.tag.ce`（灰，CE）、`.tag.pac`（蓝，PAC）、`.tag.pending`（判题中）、`.tag.blue`、`.tag.violet`。均定义在 `styles/components.css:200-209`。与契约 `status` 枚举一致，仅缺 PAC 的筛选入口。
- **判分链格**：`.vchain > i` 取 `ac / wa / tle / mle / re / ce / skip`，空串表示「未运行」；`chain(p)` 只产出 `ac` + 单点 `p.status` + 空串，**没有真实 `skip`**。

### 实时性 / 分页 / 排序 / 筛选

- **实时性**：无。列表、主角卡、抽屉全部静态；判分链按 `score` 反推而非订阅判题进度。若真实实现，列表页只需一次 `GET`；抽屉里的判分链建议随列表接口一次返回（避免 N+1）。
- **分页**：**必须服务端分页**。当前 `.pager` 是静态装饰（写死 1/2/下一页，无绑定），mock 仅 12 条全量渲染；真实题库量级下需 `page` / `pageSize` + `total`，且分页要保留当前的 status/lvl/kw/sort 组合。
- **排序**：4 列可排（`id` 默认升序、`lvl`、`rate`(=`p.ac`)、`score`），同列再点翻转方向；必须服务端排序，否则分页后排序结果错乱。默认 `{k:'id', d:1}`。
- **筛选参数**：`status`（单值枚举）、`lvl`（多值 1..5）、`kw`（string）、`sortKey` + `sortDir`、`page` + `pageSize`；页面还接受 URL 深链 `?status=` / `?lvl=`（来自学情分析页的 `practiceHref()`），说明筛选状态需可寻址、可分享。**注意**：学情页想按「标签」下钻但题库页没有 tag 参数（源码注释已承认「不假装能按标签过滤」），这是筛选维度的真实缺口。

---

## 页面：s-workbench.html + s-workbench.js（在线做题工作台）

### 数据需求表

| # | 页面模块 / 交互点 | 所需数据（字段级） | 类型与取值域 | 数据来源判定 | 现有契约覆盖情况 | 缺口说明 |
|---|---|---|---|---|---|---|
| 1 | 顶部题号 + 标题「#1024 最大子段和」 | `problem.id` / `problem.title` | long / string | B-现有表可派生但无接口 | `prob_question.id` / `title` | 无单题接口；HTML 硬编码 |
| 2 | 难度徽标「中等 3/5」 | `problem.difficulty` | tinyint 1\|2\|3（前端 1..5） | C-现有表缺字段 | `prob_question.difficulty` | 同题库页：3 档 vs 5 档 |
| 3 | 标签行「动态规划 · 前缀和」 | `problem.tags[]` | JSON array | B-现有表可派生但无接口 | `prob_question.tags` | 硬编码 |
| 4 | 题面正文两段 | `problem.content` | text | B-现有表可派生但无接口 | `prob_question.content` | 硬编码在 HTML |
| 5 | 两组样例（`6 / -2 11 -4 13 -5 -2` → `20`） | `sample_cases[].input` / `output` | JSON array | B-现有表可派生但无接口 | `prob_question.sample_cases` | 硬编码 |
| 6 | 判分说明「10 个隐藏测试点按通过比例折算」 | `test_case_count` | int | B-现有表可派生但无接口 | `prob_question.test_case_count`（默认 10） | 硬编码 10 |
| 7 | 「隐藏测试点」语义 | 是否有可见测试点 / 隐藏组数 | boolean / int | C-现有表缺字段 | 无 `hidden_case_count`；`sample_cases` 只是样例而非测试点 | 「隐藏」这一属性没有字段承载 |
| 8 | 时限「1000ms（Java / Py ×2）」、内存「256MB」 | `time_limit` / `memory_limit` + 语言倍率规则 | int ms / int MB + 规则 | B-现有表可派生但无接口 | `prob_question.time_limit` / `memory_limit`（契约明确 Java/Python ×2） | 倍率是全局规则（E），但限额值仍需接口 |
| 9 | 顶栏「剩余 4 天」 | `teach_assignment.end_time` | datetime | B-现有表可派生但无接口 | `teach_assignment.end_time` | 无「当前作业上下文」接口；硬编码 |
| 10 | 语言选择「C++ 17」 | `language` 枚举 + 版本展示名 | 'cpp'\|'java'\|'python'；展示 'C++ 17'/'Java 17'/'Python 3' | C-现有表缺字段 | `judge_submission.language`（cpp\|java\|python） | 契约只有语言标识，**无语言版本号字段**（17 / 3.10 无法表达） |
| 11 | 编辑器初始代码 | `code_templates.{cpp,java,python}` | JSON object | B-现有表可派生但无接口 | `prob_question.code_templates` | 表有字段；原型把 17 行代码硬编码成 `.code-row` DOM，非数据渲染 |
| 12 | 「本题最高分 80 / 100」 | 我在本题的最高 `oi_score` | int 0~100 | B-现有表可派生但无接口 | `teach_student_score.max_score`（作业内）或 `MAX(judge_submission.oi_score)`（自由练习） | 无接口；原型用 `result==='ac' ? '100 / 100' : '80 / 100'` 三元 |
| 13 | 「最近提交 WA · 刚刚」 | 最近一次 `status` + `create_time` | string / datetime | B-现有表可派生但无接口 | `judge_submission.status` / `create_time` | 无接口；「刚刚」是写死文案 |
| 14 | 编辑器当前代码（提交/追问时要回传） | 学生本次编辑的代码文本 `currentCode` | text | D-无任何表落点 | 无草稿表、无自动保存接口 | 契约无 `code_draft` / `exam_answer` 类表，刷新即丢；`POST /api/judge/submit` 只收一次性 code |
| 15 | 第 8 行行内批注胶囊「全负时 ans 停在 0」+ 行号高亮 | 缺陷**行号** + 批注文案 | int / string | D-无任何表落点 | 无 | 判题产出（`case_details` / `first_failed_summary`）**不含代码行号**，行级定位无任何表落点 |
| 16 | 状态栏「语法检查通过 / 缩进 4 空格 / 行尾 LF / 上次保存 21:34」 | 编辑器本地状态 + 保存时间 | boolean/string/datetime | E-纯前端派生可算 | 无 | 纯前端；「上次保存」需本地状态或草稿接口（见 #14） |
| 17 | 运行样例结果「示例 1 通过 · 输出 20 · 11 ms」 | `selfTest.actualOutput` / `timeMs` / `memoryKb` / `compileError` | string / int / int / string\|null | A-现有接口直接覆盖 | `POST /api/judge/self-test` → data{status:'FINISHED', compileError, actualOutput, timeMs, memoryKb} | 覆盖完整，只在原型未调用（`runSample()` 760ms 后直接置 done） |
| 18 | 判分带 badge「WA / AC / 判题中」+ 得分 | `status` / `statusLabel` / `oiScore` | TINYINT 0-9 / string / int 0~100 | A-现有接口直接覆盖 | `GET /api/judge/status/{submissionId}` | 覆盖完整；原型 `setTimeout` 模拟 |
| 19 | 「通过 8 / 10 个测试点」 | `passCaseCount` / `totalCaseCount` | int / int | A-现有接口直接覆盖 | `GET /api/judge/status/{submissionId}` | 覆盖完整 |
| 20 | 判分带 10 格链（逐格点亮） | `caseDetails[].caseId / status / timeMs / memKb` | array（契约 10 条） | A-现有接口直接覆盖 | `GET /api/judge/status/{submissionId}` → `caseDetails[]` | 覆盖；但原型 `lit` 由 92ms 计时器递增，**失败点硬编码为 `i===3`**，非真实 `caseId` |
| 21 | 「首个失败点 #3」 | `firstFailedCaseId` | int | A-现有接口直接覆盖 | `GET /api/judge/status/{submissionId}` | 覆盖；原型写死 3 |
| 22 | callout「输入 3 / -1 -2 -3 · 期望 -1 · 实际 0」 | `first_failed_summary{status, inputFeature, expectedOut, actualOut}` | JSON | A-现有接口直接覆盖 | `judge_submission.first_failed_summary`；`GET /api/judge/status` 首败字段 | 覆盖；三值齐全 |
| 23 | callout「丢 20 分」 | 单点分值 = `100 / totalCaseCount` × 丢点数 | int | E-纯前端派生可算 | 派生 | 可算，但原型「通过 8/10 得 80 分」与「丢 20 分」口径不自洽（8 个点应丢 20，displayed 单点又是 10 分） |
| 24 | console-row「最大耗时 12 ms / 最大内存 4096 KB」 | `max(caseDetails[].timeMs)` / `max(caseDetails[].memKb)` | int | E-纯前端派生可算 | 派生自 `case_details` | 需 `caseDetails` 返回全部 10 条（契约已保证） |
| 25 | 「提交 #1849023849201」 | `submissionId` | long | A-现有接口直接覆盖 | `POST /api/judge/submit` → data{submissionId} | 覆盖；原型是写死常量 |
| 26 | 测试点语义标签 `CASE_LABELS`（样例/全负/边界/全正/混合/大数据/随机/极限） | 每个测试点的语义名 `label` | string[]（10 条） | C-现有表缺字段 | `judge_submission.case_details` 只有 `caseId/status/timeMs/memKb` | **无 label 字段**，前端只能写死一组中文名 |
| 27 | 测试点 → 代码行映射 `CASE_LINE {1:15,…,10:9}`、`LINE_CASE {8:3,9:7,12:6,13:5,15:1}` | `caseId ↔ lineNumber` 双向映射 | map\<int,int\> | D-无任何表落点 | 无 | 判题系统原理上不产出该映射（源码注释：「真实判题系统不会给这种映射，原型里它是叙事道具」）。真实实现只能由 AI 从 `first_failed_summary` 反推，属推理结果而非字段 |
| 28 | 判题进度推进（`lit` 逐格 +1） | 需可增量获取 `passCaseCount` 的轮询 | int | A-现有接口直接覆盖 | `GET /api/judge/status/{submissionId}` | 覆盖；建议 500~1000ms 轮询 + `passCaseCount` 驱动 `lit`，替代 92ms 假计时器 |
| 29 | 阶段解锁态（③/④ 是否可点、按钮文案） | `aiStateUpdate{canUnlockStage3, canUnlockStage4, guideBtnText}` | boolean / boolean / string | A-现有接口直接覆盖 | `GET /api/judge/status/{submissionId}` | 覆盖；原型 `stage4Unlocked = result==='ac'` 纯前端推，未消费该字段 |
| 30 | AI 面板开关与配额 | `aiEnabled` / `modeDesc` / `remainingQuota` / `maxQuota` | boolean / string / int / int | A-现有接口直接覆盖 | `GET /api/ai/workspace-init?problemId=&assignmentId=` | 覆盖；原型 `quotaLeft=2` 写死、`rail-quota` 分母写死 `' / 3'` |
| 31 | 模型下拉「本地极速 / 云端」 | `availableChannels[{channelCode, channelName, isDefault}]` | array；enum(ollama_local\|deepseek\|qwen\|zhipu) | A-现有接口直接覆盖 | `GET /api/ai/workspace-init` + `ai_model_config.channel_name` | 覆盖 channel；但下拉副标题「Qwen2.5-Coder 7B · 单次 2–4 秒」需 `ai_model_config.model_name` / `timeout_seconds`，workspace-init 未返回 |
| 32 | 右侧 4 个阶段胶囊（① 读题拆解 / ② 编码引导 / ③ 排错诊断 / ④ AC 复盘） | `stageCapsules[].stage / title / unlocked / nextHintLevel / lockReason` | int 1-4 / string / boolean / int 1-3 / string | A-现有接口直接覆盖 | `GET /api/ai/workspace-init` | 覆盖完整（`lockReason` 正好对应「AC 复盘还没解锁」文案） |
| 33 | 历史对话回填（切换会话后保留） | `chatHistory[].role / stage / hintLevel / model / content` | array | A-现有接口直接覆盖 | `GET /api/ai/workspace-init` + Redis `oj:ai:memory:{sessionId}`（最近 6 轮 / 2h） | 覆盖；原型 `chat: []`，`submit()` 里还清空 |
| 34 | 追问回复（流式、路由、工具、降级、护栏、结束） | SSE 6 事件：`router_step{stage,stageName,hintLevel,levelDesc,routerModel}`、`tool_calling{toolName,summary}`、`fallback_notice{fromChannel,toChannel,msg}`、`token{delta}`、`guardrail_rewrite{reason,replacementText}`、`done{recordId,remainingQuota,actualModelUsed}` | text/event-stream | A-现有接口直接覆盖 | `POST /api/ai/chat/stream`（入参 problemId/assignmentId/selectedChannelCode/capsuleStage/userMessage/currentCode） | 六类事件齐全；原型完全未调用，只 `setTimeout(820ms)` push 一条硬编码文本 |
| 35 | AI 回复正文（9 条预置 `REPLIES`） | `ai_response`（流式拼接） | string | A-现有接口直接覆盖 | `POST /api/ai/chat/stream` 的 `token` 累积 + `ai_chat_record.ai_response` | 接口能覆盖；但原型 `ask()` 按 `this.fixed ? (stage===4?7:6) : 5` 取键，**与用户输入文本完全无关** |
| 36 | think-sum「已协同 2 个智能体 · 未调用工具 · 护栏审查通过」 | 参与智能体数量、`tool_called`、护栏结果 | int / string / int | C-现有表缺字段 | `ai_chat_record.tool_called`（单值）+ `guardrail_action`；**无「智能体数量 / 协作链路」字段** | 多智能体协作数是契约没有的概念 |
| 37 | 流水线「Router 0.3s / Tutor 1.1s」各段耗时 | 每个阶段耗时 | int ms ×N | D-无任何表落点 | SSE 无耗时字段，`ai_chat_record` 只有 `create_time` | 无法还原分段耗时 |
| 38 | ai-pin 约束卡片「n ≤ 10⁵ · \|aᵢ\| ≤ 10⁴」/ 边界「全负时答案一定为负」 | 结构化要点（kind + title + text） | array\<{kind,title,text}\> | C-现有表缺字段 | `ai_chat_record.ai_response` 只存纯文本 | 结构化卡片（pin）无字段，只能从文本里正则提取 |
| 39 | ③ 进度条「86%」 | 语义不明（既非得分也非通过率） | percent | D-无任何表落点 | 无 | 找不到任何可映射的量化字段，真实实现必须删掉或重新定义语义 |
| 40 | ③ ai-pin「丢分定位：第 8 行代码」 | 代码行号 + 说明 | int / string | D-无任何表落点 | 无 | 同 #15；AI 可推测但无字段承载，`guardrail` 也禁止输出长代码 |
| 41 | ④ 工具卡「getAcceptedSubmission(1024) → O(n) 时间 · O(1) 空间 · 12 ms」 | 最优提交 `time_used` + 时间/空间复杂度评估 | int ms + string×2 | C-现有表缺字段 | `judge_submission.time_used` 有；**复杂度评估无字段** | 复杂度只能由 AI 生成，`ai_response` 纯文本 |
| 42 | ④ 复盘要点 pin「边界 全负·单元素·n=10⁵ / 同类 最大子数组乘积·环形最大子段和」 | 结构化复盘条目 + 同类题推荐 | array\<{kind,items[]}\> | C-现有表缺字段 | `ai_response` 纯文本；`/recommend-next` 是内部 Feign 且无 why 字段 | 同类题推荐无对外接口、无理由字段 |
| 43 | 模型下拉副标题「云端超时自动降级到这里」+ chip「断网可用」 | `fallback_notice{fromChannel,toChannel,msg}` | SSE 事件 | A-现有接口直接覆盖 | `POST /api/ai/chat/stream` 的 `fallback_notice` | 覆盖；但「断网可用」是 `role_binding`/`provider_type` 的静态属性，需 `ai_model_config` 一并下发 |
| 44 | 「本题还剩 2 次」`quotaLeft` / 轨道「2 / 3」 | `remainingQuota` / `maxQuota` | int / int | A-现有接口直接覆盖 | `workspace-init` + SSE `done.remainingQuota` + Redis `oj:ai:quota:{assignId}:{probId}:{userId}` | 覆盖；原型 `ask()` 里 `quotaLeft = Math.max(0, quotaLeft-1)` **前端自减**，且 `submit()` 会把 `quotaLeft` 重置为 2（真实实现绝不能重置） |
| 45 | 拖拽分栏（300~560px） | 无后端数据 | int px | E-纯前端派生可算 | 无 | 纯前端，建议本地持久化 |
| 46 | 自测/提交防抖 | Redis `oj:judge:lock:{userId}`（3s SETNX） | key / TTL 3s | A-现有接口直接覆盖 | Redis Key 清单第 2 项 | 覆盖；原型无防抖，连点会重复提交 |
| 47 | 考试态切屏上报（`?state=locked` 场景） | `tabSwitchCount` / `limit` / `redFlagTriggered` / `alertMessage` | int / int / boolean / string | A-现有接口直接覆盖 | `POST /api/teaching/exam/tab-switch`（入参 assignmentId/problemId/awaySeconds） | 覆盖；本页未实现调用（在 s-exam 才有） |

### 交互与写操作清单

| 操作 | 触发位置 | 是否写库 | 需要的接口语义 |
|---|---|---|---|
| 运行样例（`runSample` / Ctrl+'） | 右栏「运行样例」按钮 | 否（不落题解，但会占 Go-Judge 配额） | `POST /api/judge/self-test`，入参 `{problemId, language, code, customInput?}`，返回 `{status:'FINISHED', compileError, actualOutput, timeMs, memoryKb}`；前置 `oj:judge:lock:{userId}` 3s 防抖 |
| 提交判题（`submit` / Ctrl+Enter） | 「提交」按钮 | **是** | `POST /api/judge/submit`，入参 `{problemId, assignmentId, language, code}`，返回 `{submissionId, status:0, isOverdue}`；随后经 RabbitMQ `oj.judge.submit.key` → `oj.judge.submit.queue` 投递 `JudgeTaskMessage{submissionId,userId,problemId,assignmentId,language,code,isOverdue,sendTimestamp}` |
| 判题进度轮询 | `submit()` 后的「逐格点亮」 | 否（读） | `GET /api/judge/status/{submissionId}`，按 500~1000ms 轮询；`passCaseCount` 驱动 `lit`、`caseDetails` 填 10 格、终态取 `status/oiScore`；同时消费 `aiStateUpdate` |
| 进入页面初始化（题目 + AI 面板） | `init()` | 否（读） | `GET /api/ai/workspace-init?problemId=&assignmentId=`，取 `aiEnabled/modeDesc/remainingQuota/maxQuota/availableChannels/stageCapsules/chatHistory`；题目本体还需一个单题接口（契约缺） |
| AI 追问（`ask(text)`） | AI 抽屉输入框 + 发送 | **是**（落 `ai_chat_record` + INCR 配额 + 写会话记忆） | `POST /api/ai/chat/stream`（text/event-stream），入参 `{problemId, assignmentId, selectedChannelCode, capsuleStage, userMessage, currentCode}`；客户端处理 6 类 SSE 事件：`router_step`(渲染阶段/提示层级) / `tool_calling`(渲染工具卡) / `fallback_notice`(降级提示) / `token`(流式正文) / `guardrail_rewrite`(替换被拦文本) / `done`(recordId + remainingQuota + actualModelUsed) |
| 配额扣减 | 发送 AI 追问时 | **是** | 服务端 `oj:ai:quota:{assignId}:{probId}:{userId}` INCR，仅 `done` 事件回传 `remainingQuota`；前端**不得**自减（原型 `quotaLeft-1` 必须删除）；超额应返回 429 |
| 阶段切换（①②③④ 胶囊） | 右侧 `.ai-rail` 4 个按钮 | 否（读） | 阶段可点性来自 `stageCapsules[].unlocked`；④ 需 `aiStateUpdate.canUnlockStage4`；切换本身不改库，只影响后续 `capsuleStage` 入参 |
| 阶段解锁判定 | 判题终态后 | **是**（服务端按 AC/失败点推进 `oj:ai:level`） | `aiStateUpdate{canUnlockStage3, canUnlockStage4, guideBtnText}`；Redis `oj:ai:level:{userId}:{probId}:{stage}`（24h，Level=min(count,3)） |
| 应用修改建议（`applyFix`） | 批注胶囊「改成 −10⁹」 | 否（改编辑器文本） | 无独立接口：需把修改写回编辑器并更新 `currentCode`（原型只置 `fixed=true`，代码文本没变） |
| 切换模型通道 | 模型下拉 `model` | 否 | 仅切换后续 `selectedChannelCode`；通道清单来自 `workspace-init` |
| 切屏上报（考试态） | `document.visibilitychange`（本页未实现） | **是** | `POST /api/teaching/exam/tab-switch`，入参 `{assignmentId, problemId, awaySeconds}`（>3s 计 1 次），返回 `{tabSwitchCount, limit, redFlagTriggered, alertMessage}` |
| 折叠/展开判分带、拖拽分栏 | `.verdict`、`.seam` | 否 | 纯前端 |

### 枚举/状态机

- **判题结果（`result`）**：`wa` / `judging` / `ac`（原型只有 3 态，缺少 `ce`/`re`/`tle`/`mle`/`pac` 的表现）；对应契约 `judge_submission.status` 0-9。**不一致**：原型把 0/1/2 压成 `judging`，把 4(PAC) 忽略。
- **阶段（`stage`）**：`1 ① 读题拆解` / `2 ② 编码引导` / `3 ③ 排错诊断` / `4 ④ AC 复盘`，与契约 `ai_chat_record.stage_type` 1-4 一致；显示名 `STAGE_NAMES` 与 `stageCapsules[].title` 一致。
- **阶段视觉态（`stageState(n)`）**：`done`（已完成）/ `cur`（当前）/ `lock`（未解锁）。④ 仅在 `!stage4Unlocked` 时 `lock`（原型由 `result==='ac'` 推）。
- **提示层级（`hintLevel`）**：契约 1 方向反问 / 2 逻辑反例 / 3 填空伪代码，`ai_chat_record.hint_level` 与 `stageCapsules.nextHintLevel` 一致；原型**未展示**该枚举，SSE `router_step.levelDesc` 才带文案。
- **运行态（`runState`）**：`idle` / `running` / `done`；`statusName` 里 `run:'运行中'`、`idle:'空闲'` 是**契约没有的枚举值**（`judge_submission.status` 无此二值）。
- **模型通道（`model`）**：`local`（本地极速 / Qwen2.5-Coder 7B）/ `cloud`（云端 / DeepSeek）→ 契约 `ai_model_config.channel_code` ∈ `ollama_local|deepseek|qwen|zhipu`。**不一致**：原型是二元 UI，契约是四通道；且 `qwen`/`zhipu` 在 UI 中无入口。
- **护栏动作**：`guardrail_action` 0 放行 / 1 输入防注入阻断 / 2 输出超 5 行代码熔断；原型文案「回复未给出完整代码」「规则：回复不得出现超过 5 行完整解题代码」对应 2，但**原型是静态文本**，未消费 `guardrail_rewrite` 事件。
- **判分链格颜色语义**：`.chain-c.ac`（绿）/ `.wa`（红）/ `.tle`（琥珀）/ `.re`（红）/ `.mle`（红同 RE）/ 空（灰）。`cellClass(i)` 在 judging 时用 `lit`，WA 时 `i===3` 标 `fail`。

### 实时性 / 分页 / 排序 / 筛选

- **实时性（本页最重）**：
  1. **判题必须轮询**。契约没有 WebSocket/SSE 判题通道，只能 `GET /api/judge/status/{submissionId}` 轮询；建议 500~800ms 一次、终态（3-9）即停。10 格链、通过点数、首个失败点、最大耗时/内存、`aiStateUpdate` 全部由同一个响应驱动，避免多次请求。
  2. **AI 必须 SSE**。`POST /api/ai/chat/stream` 的 6 类事件缺一不可：`router_step` 决定阶段标签与提示层级、`tool_calling` 渲染工具卡、`fallback_notice` 渲染降级提示、`token` 渲染正文、`guardrail_rewrite` 替换被熔断的文本、`done` 回填 `remainingQuota` 与 `recordId`。原型用 `setTimeout(820ms)` + 9 条 `REPLIES` 完全替代了这套机制。
  3. **不需实时**：题面、样例、限额、模板、阶段胶囊、通道清单——一次性 `workspace-init` + 单题接口即可。
- **分页**：无（单题工作台，一页一题）。
- **排序**：无。
- **筛选**：无显式筛选。隐性参数是 `?pid=`（题目深链，实际被硬编码为 1024 忽略）、`?state=`（13 种演示态 default/hint/think/judging/ac/locked/closed/cloud/stage1/stage2/band/menu/focus）、`&case=`（聚焦测试点）。真实实现应改为 `?pid=` + `?assignmentId=`（作业上下文，决定 AI 策略与配额）。

---

## 页面：s-assignments.html（我的作业）

### 数据需求表

| # | 页面模块 / 交互点 | 所需数据（字段级） | 类型与取值域 | 数据来源判定 | 现有契约覆盖情况 | 缺口说明 |
|---|---|---|---|---|---|---|
| 1 | 页头「共 5 项，3 项待完成」 | 作业总数 + 未完成数 | int / int | B-现有表可派生但无接口 | `teach_assignment` + `teach_student_score` | 无作业列表接口，5 项来自 mock |
| 2 | 页头「林昭 · 数据结构」 | `sys_user.real_name` / `teach_class.class_name` | string / string | B-现有表可派生但无接口 | `sys_user.real_name` / `teach_class.class_name` | 契约无「当前用户信息」接口 |
| 3 | 统计卡「待完成」+ 副文案「含 N 项未开始」 | `nTodo`（`status<>'done'` 且未过 `end_time`）、`nNotStarted` | int / int | B-现有表可派生但无接口 | `teach_assignment.end_time` + `teach_student_score` | 无接口；由 mock 现算 |
| 4 | 统计卡「进行中」+「共 N 题 · 已完成 M 题」 | 该作业 `problem_ids.length` 与已完成数 | int / int | B-现有表可派生但无接口 | `teach_assignment.problem_ids`（JSON）+ `teach_student_score` | 无接口 |
| 5 | 统计卡「已完成」+「均已批改」 | 批改状态 `graded` | boolean/enum | D-无任何表落点 | 无 `grading_status` 字段、无批改表 | 「已批改」这一状态在契约里不存在 |
| 6 | 统计卡「平均分」`avgScore` | 我的作业平均 `max_score` | float 0~100 | B-现有表可派生但无接口 | `teach_student_score.max_score` 按 assignment 平均 | 无接口；原型取 `OJ.students[0].score`（学生列表第一条），不是作业加权 |
| 7 | 平均分副文案「较上周 ↑4」 | 周环比 | int | D-无任何表落点 | 无历史快照表 | 无「上周均值」落点，只能写死 |
| 8 | 策略提示条「课后作业仅开放 ①③，单题上限 3 次；考试期间全部关闭」 | `mode_type`(1/2/3) + `allowed_ai_stages` JSON + `ai_quota_per_prob` | tinyint / JSON array\<int\> / int(-1 不限,0 关,3 限) | B-现有表可派生但无接口 | `teach_assignment.mode_type` / `allowed_ai_stages` / `ai_quota_per_prob` | 表有全部字段，但无「作业配置」接口 |
| 9 | 筛选 5 档 + 计数徽标（全部/待开始/进行中/已完成/已截止） | `status ∈ {todo,doing,done,over}` + `count` | string / int | B-现有表可派生但无接口 | `teach_assignment.end_time` + `teach_student_score` 派生 | 无接口；`over` 由 `end_time < now` 推 |
| 10 | 排序 3 种（截止时间 / 得分 / 进度） | `end_time` / `score` / `done÷pidCount` | enum('due','score','progress') | B-现有表可派生但无接口 | `teach_assignment.end_time` + `teach_student_score` | 无接口；`progress` 需除零保护 |
| 11 | 搜索 `kw`（匹配 title / typeName） | `title` / `mode_type` 展示名 | string | B-现有表可派生但无接口 | `teach_assignment.title` + `mode_type` | 无接口 |
| 12 | 卡片类型 tag（作业/练习/考试） | `mode_type` 1\|2\|3 → `practice`/`homework`/`exam` | tinyint → string | B-现有表可派生但无接口 | `teach_assignment.mode_type` | 表有枚举；前端另有 `typeName` 中文字段，需服务端映射 |
| 13 | 卡片标题 | `teach_assignment.title` | string | B-现有表可派生但无接口 | `teach_assignment.title` | 硬编码 5 条 |
| 14 | 「6 题 \| 已完成 4 题 \| 仅阶段 ①③ · 上限 3 次」 | `problem_ids.length` + `done` + AI 策略文案 | int/int/string | B-现有表可派生但无接口 | `teach_assignment.problem_ids` / `allowed_ai_stages` / `ai_quota_per_prob` | 文案由结构化字段拼装（E），但三字段无接口 |
| 15 | 进度条 `done / pidCount` | int / int | int / int | B-现有表可派生但无接口 | `teach_student_score` + `problem_ids` | 无接口 |
| 16 | **AI 四格链 `aiTrace(a)`**（该作业 ①②③④ 是否用过） | 该作业下 `ai_chat_record.stage_type` 去重集合 | set\<1\|2\|3\|4\> | B-现有表可派生但无接口 | `ai_chat_record.stage_type`（+ Redis `oj:ai:level`） | 无「按作业聚合阶段使用」接口；原型用 `a.ai` 字符串包含匹配 + 提交状态硬推（`a.done>0` 才点亮①、`hasFail` 才点亮③、`hasAc` 才点亮④） |
| 17 | AI 链 label / why（未开放 / 未使用 / 追问浮层） | `allowed_ai_stages` + 使用记录 | string | B-现有表可派生但无接口 | `teach_assignment.allowed_ai_stages` | 原型用 `a.ai.indexOf('全部关闭')` / `indexOf('全阶段')` 解析自然语言字符串，契约里是 JSON 数组 + 数值配额 |
| 18 | 卡片得分 `score / full` | 作业得分与**满分** | int / int | C-现有表缺字段 | `teach_student_score.max_score` 是单题 0~100；`teach_assignment` **无 `full_score`**，`prob_question` **无 `score` 分值字段** | 作业满分无处可取——这是最硬的字段缺失之一（见汇总缺口 #4） |
| 19 | 截止 `a.due` + `a.left`（"剩 2 天" / "已截止"） | `end_time` + 剩余天数 | datetime / int | B-现有表可派生但无接口 | `teach_assignment.end_time` | 无接口；`left` 是预先格式化好的中文字符串 |
| 20 | 状态 tag（已完成 ac / 进行中 blue / 待开始 none） | 由 done 与 done 数派生 | string | E-纯前端派生可算 | 派生 | 纯前端；颜色 tag class 见下 |
| 21 | 分页 `per: 4` + 页码 + 「共 X 项 / 第 x / y 页」 | `page` / `pageSize` / `total` | int | B-现有表可派生但无接口 | `teach_assignment` | 前端切片，无服务端分页 |
| 22 | 底部「近 6 次作业得分」柱图 `scoreTrend=[46,58,52,71,76,82]` | 最近 6 次作业得分时序 | array\<int\> | B-现有表可派生但无接口 | `teach_student_score` 按 assignment 聚合 `max_score` | 无接口；「近 6 次」窗口口径未定义 |
| 23 | 「较上一次 ↑6 分 / 班级均分 76 / 最高 96 / 平均 71 / 最低 46」 | 班级同作业均分、最高/最低 | float / int | B-现有表可派生但无接口 | `teach_student_score` 跨用户聚合（需 class_id 过滤） | 需班级维度聚合，无接口；数值全部硬编码 |
| 24 | 右栏「本周提交」+「9 次提交 · 6 次 AC」+ `weekBars=[1,0,3,1,4,2,2]` | 本周每日提交数与 AC 数 | array\<{date,count,ac}\> | B-现有表可派生但无接口 | `judge_submission.create_time` 按日分组 + `status=3` | 无接口；含"10-20 至 10-26"周起始口径 |
| 25 | 「6 AC / 3 未通过 / 2.4 平均尝试」 | 周内 AC 数、未通过数、平均尝试次数 | int / int / float | B-现有表可派生但无接口 | `judge_submission.status` / `teach_student_score.submit_count` | 无接口 |
| 26 | 右栏「AI 提问额度」卡 `quota[{id,title,used,limit,note}]` | `ai_used_count` / `ai_quota_per_prob` / 分阶段用量 note | int / int / string | B-现有表可派生但无接口 | `teach_student_score.ai_used_count` + `teach_assignment.ai_quota_per_prob`；Redis `oj:ai:quota:{assignId}:{probId}:{userId}` | Redis 只按「作业+题+人」计，**作业级 used 需再聚合**；note「①1 次 · ③1 次」需按 `ai_chat_record.stage_type` 分组，无接口 |
| 27 | 右栏「即将截止」`upcoming`（按 `parseInt(a.left)` 排序取前 2） | `end_time` 升序 | datetime | B-现有表可派生但无接口 | `teach_assignment.end_time` | 无接口；按中文字符串 `left` 排序在真实数据下不可靠（"已结束"→NaN） |
| 28 | 抽屉 kvcard（题目数 / 已完成 / 得分 / 截止） | 同 #4/#18/#19 | int/int/int/datetime | B-现有表可派生但无接口 | `teach_assignment` + `teach_student_score` | `full` 缺失（#18） |
| 29 | 抽屉题目清单表（题号 / 标题 / 难度 / 状态 / 得分 / 提交数） | `problem.id / title / difficulty` + 我的状态 + `max_score` + `submit_count` | long/string/tinyint/string/int/int | B-现有表可派生但无接口 | `prob_question` + `teach_student_score.submit_count` | 无「作业题目清单（含我的进度）」接口 |
| 30 | 「交作业」动作与提交后的作业状态 | 作业级提交状态 + 提交时间 | enum / datetime | D-无任何表落点 | 无 `assignment_submission` 表、无交作业接口、`teach_assignment` 无提交状态字段 | 契约里「交作业」这个语义完全不存在；原型 `handIn()` 只改本地 `a.status='done'` |
| 31 | 「已提交 · 已批改」tag | 同上 + 批改状态 | enum | D-无任何表落点 | 无 | 同 #5 / #30 |
| 32 | 侧栏导航「我的作业 3」badge | 待办作业数 | int | B-现有表可派生但无接口 | `teach_assignment` | 写死 |

### 交互与写操作清单

| 操作 | 触发位置 | 是否写库 | 需要的接口语义 |
|---|---|---|---|
| 切换筛选（全部/待开始/进行中/已完成/已截止） | `.filterbar` chip | 否 | `GET` 作业列表带 `status`，服务端筛选 + 返回各档 count |
| 切换排序（截止/得分/进度） | 排序下拉 | 否 | `GET` 作业列表带 `sortKey` / `sortDir` |
| 搜索作业 | 搜索框 | 否 | `GET` 作业列表带 `keyword` |
| 翻页 | `.pager`（`per: 4`） | 否 | `GET` 作业列表带 `page` / `pageSize` + `total` |
| 打开作业抽屉（题目清单） | 卡片点击 | 否 | 需 `GET /api/teaching/assignment/{id}` 般详情（含 problem_ids 展开每题我的进度/AI 阶段用量） |
| **交作业** | 抽屉「交作业」按钮 → `handIn(a)` | **是** | 契约**无此接口**。需要 `POST /api/teaching/assignment/{id}/hand-in` 语义：校验未到期、冻结作答、写作业提交状态与时间、触发批改与查重入队；当前只在前端把 `status='none'` 的题改成 `'pending'` |
| 撤销交作业 | toast「撤销」（5s 内） | **是**（回滚） | 需服务端支持撤销窗口，或改为「交作业二次确认」而不是可撤销写操作；当前 `undoToast()` 全量还原 mock |
| 继续做题 | 抽屉脚「继续做题」→ `s-workbench.html?pid=` | 否 | GET 深链；缺 `assignmentId` 参数，导致工作台拿不到作业上下文（配额/阶段策略） |
| 去题库练习 | 「开始针对性练习」→ `s-problems.html?status=fail` | 否 | GET 深链 |
| 重现/重试错误态 | 「演示出错态」「重新加载」 | 否 | 无后端；错误态由 `?err=1` 触发 |

### 枚举/状态机

- **作业状态 `status`**：`todo`（待开始）/ `doing`（进行中）/ `done`（已完成）——契约**没有作业级状态字段**，只能由 `teach_assignment.end_time` + `teach_student_score` 是否齐全派生；`over`（已截止）也是派生。**不一致**：原型是 3 值持久态，契约是「派生」。
- **作业类型 `type` / `typeName`**：`homework`(作业) / `practice`(练习) / `exam`(考试)；对应契约 `mode_type` 1 平时练习 / 2 课后作业 / 3 期末考试。**语义可映射，但枚举名完全不同**（字符串 vs 数字）。
- **AI 策略 `a.ai`（自然语言字符串）**：`'仅阶段 ①③ · 上限 3 次'` / `'全阶段开放 · 不限次'` / `'全部关闭'`。契约侧是 `allowed_ai_stages`（JSON 数组）+ `ai_quota_per_prob`（-1/0/3）。**严重不一致**：用中文串承载策略，只能字符串包含匹配。
- **AI 四格链单元态**：`on`（绿色=用过）/ 空（灰色=未用）/ `off`（未开放）；文案 `已用` / `未使用` / `未开放`。
- **状态 tag 颜色**：`.tag.ac`（已完成，绿）/ `.tag.blue`（进行中）/ `.tag.pac`（考试类型，蓝）/ 空 class（练习）/ `.soon`（临近截止的 `left` 变色）。
- **截止文案**：`已结束` → 显示「已截止」；否则「剩 X 天」，且 `parseInt(left) <= 2` 加 `soon` 高亮。

### 实时性 / 分页 / 排序 / 筛选

- **实时性**：无强制实时需求。倒计时/「剩 X 天」按 `end_time` 前端算即可（E）；「即将截止」排序同理。**唯一近实时点**：交作业后若需等服务端批改/查重，应轮询作业级状态（契约无此接口）。
- **分页**：前端切片（`per: 4`，`pages` / `paged`）。真实数据下作业量小（一学期几十项），可继续前端分页，但**筛选+排序必须服务端做**，否则跨页统计（各档 count）会错。
- **排序**：3 种，均需服务端：`due`（`end_time` 升序）、`score`（得分降序）、`progress`（`done/pidCount` 降序）。原型 `upcoming` 用 `parseInt(a.left)` 排序，真实数据里 `left` 是格式化文案，不可排序——必须改用 `end_time`。
- **筛选参数**：`status`（todo/doing/done/over）、`keyword`、`sortKey` + `sortDir`、`page` + `pageSize`；另有隐式 `classId`（当前学生班级）用于隔离作业范围。**缺 tag / type 筛选**（原型也未做）。

---

## 页面：s-submissions.html（提交记录）

### 数据需求表

| # | 页面模块 / 交互点 | 所需数据（字段级） | 类型与取值域 | 数据来源判定 | 现有契约覆盖情况 | 缺口说明 |
|---|---|---|---|---|---|---|
| 1 | 页头「本学期 47 次提交，AC 率 68%」 | 总提交数 + AC 数 → 比率 | int / int / percent | B-现有表可派生但无接口 | `judge_submission`（user_id 过滤）`COUNT(*)` / `COUNT(status=3)` | 无提交列表/统计接口，47 与 68% 均写死 |
| 2 | 统计卡「总提交」+「涉及 8 道题」 | `COUNT(*)` + `COUNT(DISTINCT problem_id)` | int / int | B-现有表可派生但无接口 | `judge_submission.problem_id` | 无接口 |
| 3 | 统计卡「AC 提交」+「AC 率 68% · 首次 AC 6 道」 | AC 数 + 首次 AC 的题目数（每题最早 `status=3`） | int / int | B-现有表可派生但无接口 | `judge_submission`（按 problem_id 取 `MIN(create_time)` where status=3） | 「首次 AC」需窗口函数，无接口 |
| 4 | 统计卡「平均用时」+「较上周 ↓2ms」 | `AVG(time_used)` + 周环比 | int ms / int | B-现有表可派生但无接口 | `judge_submission.time_used` / `create_time` | 无接口；周环比需两次聚合 |
| 5 | 统计卡「本周提交」+「其中 AC N 次 · 覆盖 N 道题」 | 本周 `COUNT(*)` / `COUNT(status=3)` / `COUNT(DISTINCT problem_id)` | int ×3 | B-现有表可派生但无接口 | `judge_submission.create_time` / `status` / `problem_id` | 无接口 |
| 6 | 状态筛选 7 档（全部/AC/WA/TLE/MLE/RE/CE）+ 计数 | `status` 枚举 + 各档 `count` | TINYINT 3/5/6/7/8/9 | B-现有表可派生但无接口 | `judge_submission.status` | 无接口；**PAC(4) 无筛选入口** |
| 7 | 语言筛选（全部/C++ 17/Python 3/Java 17）+ 计数 | `language` + `count` | 'cpp'\|'java'\|'python' | B-现有表可派生但无接口 | `judge_submission.language` | 契约枚举是小写标识，UI 是带版本号展示名（`C++ 17`），缺版本字段 |
| 8 | 时间范围（近 3 / 7 / 30 天） | `create_time` 区间 | enum(3,7,30) | B-现有表可派生但无接口 | `judge_submission.create_time` | 无接口；原型用 `AGE` 映射「几天前」 |
| 9 | 关键词搜索（题目名 / 提交号） | `problem_title` / `submission id` | string | B-现有表可派生但无接口 | `judge_submission.id` + `prob_question.title` | 无接口；需 join |
| 10 | 列表「提交号」`S-20481` | 面向用户的**提交展示号** | string（`S-` + 5 位） | C-现有表缺字段 | `judge_submission.id` 是自增 BIGINT | 无业务单号字段，`S-20481` 这种可读单号无法生成（也无从「按单号搜索」） |
| 11 | 列表「题目」`#1024 最大子段和` | `problem_id` / `title` | long / string | B-现有表可派生但无接口 | `judge_submission.problem_id` + `prob_question.title` | 无接口（需 join） |
| 12 | 列表「语言」 | `language` | 'cpp'\|'java'\|'python' | B-现有表可派生但无接口 | `judge_submission.language` | 同 #7 |
| 13 | 列表「状态」tag | `status` | TINYINT 0-9 | B-现有表可派生但无接口 | `judge_submission.status` | 无接口 |
| 14 | 列表「判分链」10 格 `chainFor(s)` | `case_details[].status`（10 条） | array | B-现有表可派生但无接口 | `judge_submission.case_details` | 表有；无列表接口一次带出（否则 N+1：47 次提交 × 10 条） |
| 15 | 列表「通过点数」`8/10` | `pass_case_count` / `total_case_count` | int / int | B-现有表可派生但无接口 | `judge_submission.pass_case_count` / `total_case_count` | mock 里是字符串 `'8/10'`，需两个数值字段 |
| 16 | 列表增量「+1」`deltaOf(s)` | 与同题上一条提交的 `pass_case_count` 差值 | int | E-纯前端派生可算 | 派生自 #15 | 需同题前后两条都在当前页/窗口内，跨页会失真 |
| 17 | 列表「耗时 / 内存」 | `time_used` / `memory_used` | int ms / int KB | B-现有表可派生但无接口 | `judge_submission.time_used` / `memory_used` | mock 是 `'12ms'` / `'4096KB'` 字符串；契约是数值，需前端格式化 |
| 18 | 列表「提交时间」 | `create_time` | datetime | B-现有表可派生但无接口 | `judge_submission.create_time` | mock 是 `'10-24 23:41'` 无年份字符串，跨年排序会错 |
| 19 | 表头排序（提交号 / 题目 / 提交时间，升/降） | `sortKey` + `sortDir` | enum('id','title','time') | B-现有表可派生但无接口 | `judge_submission.id` / `create_time` + `prob_question.title` | 无接口；原型 `title` 排序用 `localeCompare('zh')` |
| 20 | 分页 `per: 8` + 页码 + 「共 X 条 / 第 x / y 页」 | `page` / `pageSize` / `total` | int | B-现有表可派生但无接口 | `judge_submission` | 前端切片；47 条数据全在内存 |
| 21 | 「语言分布」`langStat`（人数 + 百分比 + 配色） | `GROUP BY language` | array\<{lang,count,pct}\> | B-现有表可派生但无接口 | `judge_submission.language` | 无接口；配色是前端映射（E） |
| 22 | 「判题结果分布」`statusStat`（各状态次数 + 百分比） | `GROUP BY status` | array\<{status,count,pct}\> | B-现有表可派生但无接口 | `judge_submission.status` | 无接口 |
| 23 | 抽屉 kvcard「得分」 | `oi_score` | int 0~100 | B-现有表可派生但无接口 | `judge_submission.oi_score` | 无单条提交详情接口 |
| 24 | 抽屉测试点详情「输入 / 期望输出 / 实际输出」 | 单测试点的 `input` / `expectedOutput` / `actualOutput` | text ×3 | D-无任何表落点 | `case_details` 只有 `{caseId,status,timeMs,memKb}`；`first_failed_summary` 只覆盖**首个**失败点 | 除首个失败点外，其余 9 个测试点的输入/期望/实际**无任何表落点**；原型用 `CASE_IO` 常量写死 10 组 |
| 25 | 抽屉测试点状态标签 `stName(c)` | `case_details[].status` | 'ac'\|'wa'\|'tle'\|'mle'\|'re'\|'ce'\|'none' | B-现有表可派生但无接口 | `judge_submission.case_details` | 无接口 |
| 26 | 抽屉「这道题的提交轨迹」（同题历史提交竖排） | 同 `problem_id` 的历史提交 `id/pass/at` | array | B-现有表可派生但无接口 | `judge_submission`（problem_id 过滤） | 无接口；需「同题最近 N 条」 |
| 27 | 抽屉「代码回看」`codeLines` + 高亮行 + 备注 | `code`（源码）+ 错误行号 + 诊断文案 | text / int / string | C-现有表缺字段 | `judge_submission.code` 有；`error_msg` 有；**无 `error_line` 字段** | 代码有，「第 9 行初始化错误」的行号与备注无落点（`CODES` 常量按 status 取整段代码） |
| 28 | 三态（骨架 600ms / HTTP 503 / 空态） | `loading` / 错误码 | boolean / int | E-纯前端派生可算 | 无 | 纯前端 |

### 交互与写操作清单

| 操作 | 触发位置 | 是否写库 | 需要的接口语义 |
|---|---|---|---|
| 切换状态筛选 | `.seg` 7 个 tab | 否 | `GET` 提交列表带 `status` |
| 切换语言筛选 | 「语言」下拉 | 否 | `GET` 提交列表带 `language` |
| 切换时间范围 | 「时间」下拉（近 3/7/30 天） | 否 | `GET` 提交列表带 `from` / `to` 或 `days` |
| 搜索 | 搜索框（题目/提交号） | 否 | `GET` 提交列表带 `keyword`（需 join `prob_question.title`） |
| 表头排序 | `th` 内 `.th-sort` | 否 | `GET` 提交列表带 `sortKey` / `sortDir` |
| 翻页 | `.pager` | 否 | `GET` 提交列表带 `page` / `pageSize` + `total` |
| 打开提交详情抽屉 | 行点击 / Enter | 否 | 需 `GET /api/judge/submission/{id}`：返回 `oi_score`/`case_details`/`code`/`time_used`/`memory_used` + **每测试点 IO**（当前契约缺） |
| 选中某个测试点 | `.chain-c` 按钮 `selTest = i` | 否 | 纯前端；但数据来自上一条接口 |
| 清空筛选 / 重置 | 「清空筛选」「清空筛选条件」 | 否 | 无写操作 |
| 跳转 AI 诊断 | 抽屉脚「AI 诊断」→ `s-workbench.html?pid=&stage=3` | 否 | GET 深链；`stage=3` 参数工作台实际未解析（`init()` 只认 `state=`） |
| 跳转学情分析 / 继续做题 | 页头按钮 | 否 | GET 深链 |
| 重试错误态 | 「重新加载」 | 否 | 无后端；`retry()` 只是重开 600ms 骨架 |

### 枚举/状态机

- **判题结果 `status`**：`ac` / `wa` / `tle` / `mle` / `re` / `ce`（筛选 6 档 + 全部）。契约 `judge_submission.status` 还有 `0 排队 / 1 编译中 / 2 评测中 / 4 PAC`。**不一致**：原型无 `pending`/`pac` 档（`statusName` 里有 `pending:'判题中'`、`pac:'PAC'`，但本页未使用）。
- **判分链单元**：`ac` / `wa` / `tle` / `mle` / `re` / `ce` / `''`（未运行）；`ce` 整链留空（源码明确「CE 根本没进测试点执行，不要谎报」）。契约 `case_details[].status` 的取值域未在文档中枚举，存在对齐风险。
- **语言**：`C++ 17` / `Python 3` / `Java 17`（展示名）；契约 `language ∈ cpp|java|python`。**不一致**：无版本维度。
- **时间范围**：3 / 7 / 30 天（`ranges`），默认 30。
- **颜色语义**：`.tag.{status}`（见 components.css:200-209）；`.chain-c.ac` 绿 / `.wa` 红 / `.tle` 琥珀 / `.re`、`.mle` 同红 / 空 灰；`.chain-c.on` 蓝描边＝当前选中测试点；分布条 `--ac` 绿 / `--ink3` 灰阶（**未通过一律中性色，不用高饱和红**，见 CSS 注释 §2.3）。
- **三态文案态**：`loading` 骨架 7 行 / `loadErr`「提交记录没加载出来（HTTP 503）」/ `empty` 三种（关键词无匹配 / 该结果无提交 / 该条件无记录）。

### 实时性 / 分页 / 排序 / 筛选

- **实时性**：无轮询需求（历史记录页）。若有 `status ∈ 0/1/2`（判题中）的提交，列表需轮询刷新或在打开时单条查询 `GET /api/judge/status/{submissionId}`；原型 mock 里没有判题中数据，故未实现。
- **分页**：**必须服务端分页**。`.pager` 有真实绑定（`page` / `pages` / `paged`，`per: 8`），但数据是本地 `ALL`（10 条 shell mock + 5 条 `EXTRA` = 15 条）。真实 47 次/学期量级尚可前端分页，但**「+1 增量」和「同题轨迹」依赖跨行比较**，前端分页会算错，需服务端一次给出同题前一条。
- **排序**：3 列（提交号默认倒序、题目、提交时间），同列再点翻转；`title` 走 `localeCompare('zh')`，`time` 用 `AGE` 的「几天前」近似——**真实实现必须按 `create_time` 排序**，不能按格式化字符串。
- **筛选参数**：`status`（单值）、`language`（单值）、`days`（3/7/30）、`keyword`；另需隐式 `userId`（当前学生）。**缺题目维度的筛选**（点某题看它的提交，需从学情/作业页带 `pid` 进来，原型未支持）。

---

## 页面：s-analytics.html（学情分析）

### 数据需求表

| # | 页面模块 / 交互点 | 所需数据（字段级） | 类型与取值域 | 数据来源判定 | 现有契约覆盖情况 | 缺口说明 |
|---|---|---|---|---|---|---|
| 1 | 页头「本学期 47 次提交，班级平均 AC 率 62%」 | 我的提交数 + 班级 AC 率 | int / percent | B-现有表可派生但无接口 | `judge_submission` 按 class 聚合；`teach_class` | 无班级维度聚合接口；62% 写死 |
| 2 | **AI 学情解读**（标题「下一步：先把图论补到班级线（41% → 58%）」+ 正文一段） | AI 生成的自然语言总结（含标签、掌握度、班级值、次数） | string（长文本） | D-无任何表落点 | `ai_chat_record` 只有「对话」语义（session_id 含 assignmentId+problemId），**无「学情总结」表/字段** | 这是本页的第一主角，但契约里完全没有落点（既非 chat 也非 diagnosis） |
| 3 | 解读条「基于 47 次提交 · 12 道题」 | 样本量（提交数 + 题目数） | int / int | B-现有表可派生但无接口 | `judge_submission` / `prob_question` | 无接口；两数写死 |
| 4 | 解读条「更新于 10-24 23:41」 | 分析结果生成时间 | datetime | D-无任何表落点 | 无分析快照表（无 `analysis_snapshot` / `stats_cache`） | 无「何时算的」概念 |
| 5 | 解读条「口径：标签掌握度 = 该标签下已 AC 题数占比」 | 计算口径说明 | string | E-纯前端派生可算 | 无 | 静态文案（但口径本身需服务端一致实现） |
| 6 | 薄弱点列表 `weakTags`（name / pct / cls / sub） | 标签名、我的掌握度、班级同标签掌握度、「done/n 题」 | string / percent / percent / string | B-现有表可派生但无接口 | `prob_question.tags`（JSON 数组）+ `judge_submission` 聚合 | 需把 JSON 数组展开成标签维度并跨用户聚合；契约无标签维表、无索引设计 |
| 7 | 「去练习」链接 `practiceHref(t)`（带 `status=fail` + `lvl`） | 标签下题目平均难度 | int | E-纯前端派生可算 | 派生自 `prob_question.difficulty` | 平均难度取整后当筛选参数——**题库页没有 tag 参数**，只能降级为难度近似（源码已注释承认） |
| 8 | 统计卡「AC 率」+「高于/低于班级均值 62%，差 N 个百分点」 | 我的 AC 率 + 班级 AC 率 | percent / percent | B-现有表可派生但无接口 | `judge_submission.status` 聚合（分 user / 分 class） | 无接口；62 写死 |
| 9 | 统计卡「已 AC 提交」+「近 30 天 N 次提交 · 覆盖 N 道题」 | 区间内 AC 数 / 总提交数 / distinct 题目数 | int ×3 | B-现有表可派生但无接口 | `judge_submission.create_time` / `status` / `problem_id` | 无接口 |
| 10 | 统计卡「平均尝试」+「全站均值 3.1 次 · 最难的 1 道试了 8 次」 | `AVG(submit_count)` + 全站均值 + `MAX(submit_count)` | float / float / int | B-现有表可派生但无接口 | `teach_student_score.submit_count`（+ 跨用户/全站聚合） | 全站均值需全表扫描聚合；无接口 |
| 11 | 统计卡「薄弱标签」`weakCount` | `COUNT(tag where pct < 70)` | int | E-纯前端派生可算 | 派生自 #6 | 阈值 70% 是前端常量 |
| 12 | 提交趋势 `bars`（每日 AC 段 + 未通过段高度 + 日期 label + total） | 按日聚合：`date` / `acCount` / `failCount` | array\<{date,ac,wa,total}\> | B-现有表可派生但无接口 | `judge_submission.create_time`（按日）+ `status` | 无接口；原型 `DAYS` 硬编码 30 天，90 天再编 60 天 |
| 13 | 趋势时间范围（近 7 / 30 / 90 天） | 区间参数 | enum(7,30,90) | B-现有表可派生但无接口 | `create_time` 区间 | 无接口 |
| 14 | 标签能力列表 `tags`（name / pct / cls / 排序） | 同 #6，按掌握度排序 | array | B-现有表可派生但无接口 | 同 #6 | 同 #6；排序是前端 `filter` 后按数组原序（实为 `TAGS` 常量顺序，**不是按掌握度排**，与副标题「按掌握度排序」不符） |
| 15 | 「只看薄弱」开关 `onlyWeak` | `pct < 70` 过滤 | boolean | E-纯前端派生可算 | 派生 | 纯前端 |
| 16 | 题目掌握表 7 列（题号/题目/难度/尝试/最佳得分/状态/最近提交） | `problem.id/title/difficulty` + `tryCount` + `maxScore` + `status` + `lastSubmitTime` | long/string/tinyint/int/int/enum/datetime | B-现有表可派生但无接口 | `teach_student_score.submit_count/max_score/update_time` + `judge_submission` | 无接口；「尝试次数」在 `teach_student_score.submit_count` 有，但自由练习（`assignment_id=0`）的口径未定义 |
| 17 | 题目表分页 `per: 5` | `page` / `pageSize` / `total` | int | B-现有表可派生但无接口 | `prob_question` | 前端切片 |
| 18 | 测试点热力图（最近 6 道 + 每题 10 格链） | 每题**最近一次**提交的 `case_details[]` + 排序依据「最近」 | array | B-现有表可派生但无接口 | `judge_submission.case_details` + `create_time` | 无接口；原型按 `p.tries > 0` 取前 6 条，并用 `score/10` 反推格数（源码注释承认「本页没有逐测试点数据」） |
| 19 | AI 使用统计「12 次调用 · 云端 9 / 本地 3」 | `COUNT(ai_chat_record)` + 按 `actual_model` / `is_fallback` 分组 | int / int / int | B-现有表可派生但无接口 | `ai_chat_record.actual_model` / `is_fallback` | 可派生；但「云端/本地」需把 `actual_model` 映射到通道类型，契约无该维度字段 |
| 20 | AI 阶段分布 `aiStages`（①4 ②0 ③6 ④2） | `GROUP BY stage_type` | int[4] | B-现有表可派生但无接口 | `ai_chat_record.stage_type` | 无接口；四个数字写死 |
| 21 | AI 统计说明 `aiNote`（「② 编码引导本周作业策略中关闭，所以为 0 次」） | 作业策略 + 阶段用量 | string | B-现有表可派生但无接口 | `teach_assignment.allowed_ai_stages` + `ai_chat_record` | 文案需两处数据拼装；无接口 |
| 22 | 「最近 AC」列表（pid / title / lang / at / time） | 最近 N 条 `status=3` 提交 | array | B-现有表可派生但无接口 | `judge_submission`（status=3, order by create_time desc） | 无接口；3 条写死 |
| 23 | 薄弱点抽屉 kvcard（掌握度 / 班级均值 / 已 AC · 总题 / 近 30 天提交） | 同 #6 + 区间提交数 | percent / percent / int+int / int | B-现有表可派生但无接口 | 同 #6 + `judge_submission` | 同 #6 |
| 24 | 薄弱点抽屉「主要失分点」症状 / 根因 / 建议 | 按标签的 AI 归因三段文本 | string ×3 | D-无任何表落点 | `teach_plagiarism_report.ai_diagnosis` 是**查重**诊断，语义不符；`ai_chat_record` 是对话 | 三段时间均无落点 |
| 25 | 薄弱点抽屉「推荐练习」`recs`（id / title / tag / why / lvl） | 推荐题目 + **推荐理由 why** | array\<{id,title,tag,why,lvl}\> | D-无任何表落点 | 内部 Feign `ProblemInnerFeignClient(/recommend-next)` 无对外 REST，且**无 why 字段** | 对外无推荐接口；理由无法生成 |
| 26 | 题目抽屉「班级通过率」 | 班级内该题 AC 率 | percent | B-现有表可派生但无接口 | `prob_question.pass_count` + `teach_class.student_count` 可粗算 | 需班级维度，`pass_count` 是**全站**累计而非班级 |
| 27 | 题目抽屉「我的提交」`history`（id/status/score/pass/at） | 该题我的历史提交 | array | B-现有表可派生但无接口 | `judge_submission`（user_id + problem_id） | 无接口；多数题只有 1 条伪造记录 |
| 28 | 题目抽屉「AI 建议」`note` | 按分数段生成的建议文本 | string | D-无任何表落点 | `ai_chat_record` 是对话，非「题目级建议」 | 无落点；原型按 `score===100 / >0 / 0` 三档硬编码 |
| 29 | 空态文案（数学标签下无题 / 只看薄弱但该标签不弱） | 标签下题目数 + 掌握度 | int / percent | E-纯前端派生可算 | 派生 | 纯前端 |

### 交互与写操作清单

| 操作 | 触发位置 | 是否写库 | 需要的接口语义 |
|---|---|---|---|
| 切换时间范围（7/30/90 天） | 趋势卡 `.seg` | 否 | `GET` 学情聚合接口带 `days` 参数（趋势、统计卡、AC 率需同一口径一起变） |
| 点标签选中/取消 | `.cap` 行 `pickTag` | 否 | 纯前端；但题目表需按 `tag` 重查（`GET` 带 `tag`） |
| 「只看薄弱」开关 | 面板头按钮 | 否 | 纯前端过滤；可下推为 `onlyWeak=true` |
| 题目表分页 | `.pager` | 否 | `GET` 带 `page` / `pageSize` |
| 重置表格筛选 | 「重置」按钮 | 否 | 无写操作 |
| 打开薄弱点抽屉 | `.wk-main` 点击 | 否 | 需「标签维度诊断」接口（掌握度/班级均值/失分归因/推荐题），契约无 |
| 打开题目抽屉 | 行点击 | 否 | 需单题学情接口（我的提交 + 班级通过率 + AI 建议），契约无 |
| 去练习（三处入口） | 「开始针对性练习」/ 薄弱点「去练习」/ 抽屉脚「去练习」 | 否 | GET 深链到 `s-problems.html?status=fail&lvl=` 或 `s-workbench.html?pid=`；**标签参数无法传递**，练习意图丢失 |
| 刷新 AI 解读 | 无此交互 | — | 需「重新生成学情解读」写操作（落 AI 调用记录 + 缓存），原型未提供入口 |

### 枚举/状态机

- **时间范围**：`7` / `30` / `90` 天（`RANGES`），默认 30。
- **掌握度分档（颜色语义）**：`pct >= 70` → 主蓝 `--blue`（正常）；`50 <= pct < 70` → 浅蓝 `--blue-soft`（`mid`）；`pct < 50` → 警示红 `--wa`（`low`），且 `.weak-dot.hot` 变红。阈值 **70 / 50** 是前端常量，契约无「掌握度」概念。
- **薄弱判定**：`pct < 70` 即薄弱（`weakTags`）；与 `cap-v` 的 50 分档是两套阈值，容易误读。
- **题目状态（表格列）**：沿用 `judge_submission.status` → `ac`/`wa`/`tle`/`mle`/`re`/`ce`；原型把 `p.status==='none' && tries>0` 强行改写成 `wa`（注释：「有过提交就不能显示未提交」）——**这是用假数据掩盖空值，真实实现必须有 `myStatus` 字段**。
- **AI 阶段**：① / ② / ③ / ④，对应 `ai_chat_record.stage_type` 1-4，一致。
- **趋势图色**：AC = `--blue`（蓝），未通过 = `--line`（浅灰）；**刻意不用红色**（§2.3：红不参与「提交多不多」的对比）。但热力图图例里「卡住」用真实 `--wa` 红——**同一页两种红色语义**，需在文档里说清。

### 实时性 / 分页 / 排序 / 筛选

- **实时性**：无。整页是离线聚合视图，`更新于 10-24 23:41` 说明它接受「最后一次计算时间」。若加「重新生成解读」，则为异步任务 + 轮询/SSE（契约无）。
- **分页**：题目掌握表前端分页（`per: 5`，共 12 条）。真实实现建议服务端分页（学生题目量可到几百）。趋势/标签/热力图不分页，需限制返回条数（趋势 90 天点位、热力图 6 道）。
- **排序**：标签能力声称「按掌握度排序」，但实际按 `TAGS` 常量顺序（`dp/greedy/graph/string/math`）——**名实不符，真实实现必须按 `pct` 升序**。题目表无排序控件。趋势按日期升序（固定）。
- **筛选参数**：`days`（7/30/90）、`tag`（标签 key）、`onlyWeak`（boolean）、`page` + `pageSize`；隐式 `userId` + `classId`（班级均值必须有）。**缺失**：按难度/状态筛选题目表（题库页有，这里没有）。

---

## 页面：s-exam.html（考试作答）

### 数据需求表

| # | 页面模块 / 交互点 | 所需数据（字段级） | 类型与取值域 | 数据来源判定 | 现有契约覆盖情况 | 缺口说明 |
|---|---|---|---|---|---|---|
| 1 | 页头「数据结构期中测验 · 计算机 2022 级 1 班 · 共 5 题 · 限时 90 分钟」 | `assignment.title` / 班级名 / `problem_ids.length` / 考试时长 | string / string / int / int（分钟） | C-现有表缺字段 | `teach_assignment.title` / `class_id` / `problem_ids` / `start_time` / `end_time`；**无 `duration_minutes`** | 时长只能由 `end_time - start_time` 派生（E），但「限时 90 分钟」是考试属性，缺显式字段 |
| 2 | 页头「提交次数不限，取最后一次」 | 计分策略 / 提交次数上限 | enum（LAST / BEST / FIRST）+ int | D-无任何表落点 | `teach_assignment` **无 `scoring_policy` / `max_submit_count`** | 考试取「最后一次」与练习取「最高分」是两套规则，契约无策略字段 |
| 3 | 右上倒计时 `secs`（38:24） | 剩余秒数 = `end_time - now()` | int（s） | E-纯前端派生可算 | 派生自 `teach_assignment.end_time` | 可算；但必须用**服务端时间**校时，否则本地时钟可篡改 |
| 4 | 左栏题号列表（序号 / 标题 / 难度 / 分值） | `problem.id` / `title` / `difficulty` | long / string / tinyint | B-现有表可派生但无接口 | `prob_question.id/title/difficulty` | 无试卷接口；5 道题的题面全部硬编码在 JS |
| 5 | 左栏每题分值「20 分」 | 试卷内每题分值 | int | C-现有表缺字段 | `prob_question` **无 `score`**、`teach_assignment` 无 `problem_scores` | 20 分 × 5 题 = 100 分的分值分配无任何字段；`problem_ids` 只是 ID 数组 |
| 6 | 左栏作答状态 `q.state`（`todo` / `doing` / `done`） | 每题本次考试的作答状态 | enum | D-无任何表落点 | 无 `exam_answer` / `exam_progress` 表 | 刷新会丢；契约只有 `judge_submission`（每次提交一条），无法区分「是否已作答/正在编辑」 |
| 7 | 「N / 5 已作答」 | `count(state='done')` | int | E-纯前端派生可算 | 派生自 #6 | 纯前端 |
| 8 | 考试须知「切屏超过 3 次记为异常」 | `tab_switch_limit` | int（契约默认 5） | A-现有接口直接覆盖 | `teach_assignment.tab_switch_limit` + `POST /api/teaching/exam/tab-switch` → `data.limit` | 覆盖；但**原型写死 3，契约默认 5**，需以服务端返回为准 |
| 9 | 考试须知「到时自动强制交卷」 | `end_time` + 自动交卷策略 | datetime + boolean | C-现有表缺字段 | 无 `auto_submit` 字段、无交卷接口 | 「强制交卷」既无字段也无接口 |
| 10 | 中栏题面（`#id` / title / 难度 / tags / 正文） | `problem.id/title/difficulty/tags/content` | long/string/tinyint/array/text | B-现有表可派生但无接口 | `prob_question.*` | 5 道题正文全部硬编码（含 `desc` 数组两段） |
| 11 | 中栏 spec（时限 / 内存 / 测试点 / 计分） | `time_limit` / `memory_limit` / `test_case_count` + 计分规则 | int/int/int/enum | B-现有表可派生但无接口 | `prob_question.time_limit` / `memory_limit` / `test_case_count` | 表有前三项；「OI 按点」是全局规则（E）；内存 256MB / 测试点 10 组写死 |
| 12 | 中栏示例 1/2（input / output） | `sample_cases[].input` / `output` | JSON array | B-现有表可派生但无接口 | `prob_question.sample_cases` | 硬编码 |
| 13 | 右栏代码区 `q.code`（17~40 行） | 学生当前作答代码 | text | D-无任何表落点 | 无草稿/作答表 | 同上 #6；考试中断网刷新即全丢 |
| 14 | 右栏「main.cpp · C++ 17」 | `language` 枚举 + 版本名 | 'cpp'\|'java'\|'python' | C-现有表缺字段 | `judge_submission.language` | 语言版本无字段；且考试页**没有语言切换控件**（固定 C++） |
| 15 | 「运行样例」结果「样例 1 通过 · 用时 8ms · 内存 3420KB」 | `selfTest.actualOutput` / `timeMs` / `memoryKb` / `compileError` | string/int/int/string | A-现有接口直接覆盖 | `POST /api/judge/self-test` | 覆盖；原型 780ms 后按 `cur===0 ? 'bad' : 'ok'` 假判定 |
| 16 | 失败态「样例 1 未通过 · 期望 -1，实际输出 0」 | `actualOutput` + `sample_cases[].output` | string / string | A-现有接口直接覆盖 | `POST /api/judge/self-test`（+ `prob_question.sample_cases`） | 覆盖；但**期望值写死**，未随题变化 |
| 17 | 「上次 7/10」判分链 `lastChain` | 本题在本场考试中**上一次**提交的 `pass_case_count` + `case_details[]` | int + array | B-现有表可派生但无接口 | `judge_submission`（assignment_id + user_id + problem_id） | 无接口；原型 `lastPass = tries>=2 ? 10 : 7` 纯推算，注释亦承认「只表达走到第几格」 |
| 18 | 「已提交 N 次」（`q.tries`） | 本场考试该题提交次数 | int | B-现有表可派生但无接口 | `judge_submission` 计数（assignment_id + problem_id + user_id） | 无接口；`tries` 是硬编码初值 |
| 19 | **提交本题** | 新建一条 `judge_submission` | — | A-现有接口直接覆盖 | `POST /api/judge/submit`（入参 problemId/assignmentId/language/code） | 覆盖；原型 `submitOne()` 只改本地 `state='done'`、`tries++`，**不产生 submissionId** |
| 20 | 提交后判题结果 | `status` / `oiScore` / `passCaseCount` / `firstFailedCaseId` | TINYINT/int/int/int | A-现有接口直接覆盖 | `GET /api/judge/status/{submissionId}` | 覆盖；原型完全没有轮询，交了看不到结果 |
| 21 | 交卷弹窗「切屏次数 0」 | `tabSwitchCount` | int | A-现有接口直接覆盖 | `POST /api/teaching/exam/tab-switch` → `data.tabSwitchCount` | 覆盖；原型写死 `0`，且**全页没有任何 `visibilitychange` 监听与上报调用** |
| 22 | 交卷弹窗「已作答 N/5 · 未作答按 0 分」 | 已作答数 + 缺考计分规则 | int + enum | E-纯前端派生可算 | 派生自 #6 | 计分规则需服务端保证（见 #2） |
| 23 | **交卷动作** `handIn()` | 交卷状态 + 交卷时间 + 冻结作答 | enum + datetime | D-无任何表落点 | 契约**无交卷接口**、无 `exam_paper` / `exam_handin` 表 | 交卷是考试页最核心的写操作，契约里不存在；原型只把 `submitted=true` |
| 24 | 已交卷卡「试卷已提交给周明远老师 · 成绩考试结束后公布 · 代码进入查重队列」 | 教师姓名 + 成绩公布时间 + 查重任务状态 | string / datetime / enum | D-无任何表落点 | `teach_assignment.teacher_id` → `sys_user.real_name` 可派生教师名（B）；**成绩公布时间无字段**；查重状态在 `teach_plagiarism_report.audit_status` 但那是审核态不是任务态 | 三句中两句无落点 |
| 25 | 切屏事件监听与上报 | `awaySeconds` / `redFlagTriggered` / `alertMessage` | int / boolean / string | A-现有接口直接覆盖 | `POST /api/teaching/exam/tab-switch`（>3s 计 1 次） | 覆盖接口；**页面完全未实现监听**，是必须补的一环 |
| 26 | 三态（试卷下发骨架 / HTTP 503 / 重试） | `loading` + 错误码 | boolean / int | E-纯前端派生可算 | 无 | 纯前端；文案「计时已暂停」需真实暂停逻辑（当前 `setInterval` 在 `loadErr` 时确实 `return`，等价实现） |
| 27 | 顶部「AI 全关」提示 | `aiEnabled=false` + HTTP 403 | boolean / int | A-现有接口直接覆盖 | `GET /api/ai/workspace-init` → `aiEnabled`；`mode_type=3` 时 AI 权限校验返回 403 | 覆盖；文案「接口直接返回 403」正好对应契约的 403 语义 |

### 交互与写操作清单

| 操作 | 触发位置 | 是否写库 | 需要的接口语义 |
|---|---|---|---|
| 切换题目 | 左栏 `.qitem` / `?q=` 深链 | 否（但应保存当前题草稿） | 纯前端切换；真实实现需「保存上一题代码」的草稿接口（契约无） |
| 运行样例 | 「运行样例」按钮 | 否 | `POST /api/judge/self-test`（入参 problemId/language/code/customInput），返回 `{status:'FINISHED', compileError, actualOutput, timeMs, memoryKb}` |
| **提交本题** | 「提交本题」按钮 | **是** | `POST /api/judge/submit` → `{submissionId, status:0, isOverdue}`；考试场景 `assignmentId` 必须非 0；`is_overdue=1` 时应拒绝或标记 |
| 查看本次判题结果 | 提交后（原型无） | 否（读） | `GET /api/judge/status/{submissionId}` 轮询至终态；考试期间**只回 `passCaseCount` 不含 `caseDetails` 明细**（防止泄题），需接口侧支持脱敏 |
| 撤销提交 | toast「撤销」（5s 内） | **是**（回滚/作废） | 需「作废刚提交的记录」语义；契约无。更合理的做法是取消撤销、改为提交二次确认 |
| **切屏上报** | `document.visibilitychange` / `blur`（原型未实现） | **是** | `POST /api/teaching/exam/tab-switch`，入参 `{assignmentId, problemId, awaySeconds}`（>3s 计 1 次），返回 `{tabSwitchCount, limit, redFlagTriggered, alertMessage}`；达阈值 `redFlagTriggered=true` 并写 `teach_student_score.red_flag_type=1` |
| 计时归零自动交卷 | `setInterval` 到 0（当前只置 `submitted=true`） | **是** | 需与「交卷」同一接口，且服务端也必须定时兜底（前端关页面即失效） |
| **交卷** | 弹窗「确认交卷」→ `handIn()` | **是** | 契约**无交卷接口**。需 `POST /api/teaching/exam/hand-in`：校验截止、汇总已作答题目、冻结作答、写交卷时间、触发批改与查重入队（`POST /api/teaching/plagiarism/check`） |
| 交卷后跳转 | 「返回我的作业」「查看提交记录」 | 否 | GET 深链 |
| 重现错误态 | 「演示出错态」「重新加载」 | 否 | 无后端 |

### 枚举/状态机

- **作答状态 `q.state`**：`todo`（未作答，`.qn` 浅灰底）/ `doing`（正在作答，`.qn` 蓝底 `--blue-bg`）/ `done`（已提交，`.qn` 深中性 `--ink`，**刻意不用判题绿**，见源码注释「已作答不是答对了」）。契约**无对应枚举**（无作答状态表）。
- **运行态 `runState`**：`idle`（「样例未运行」）/ `running`（「正在沙箱里跑样例…」）/ `ok`（绿，通过）/ `bad`（红，未通过）。契约 `self-test` 只返回 `status:'FINISHED'` + `compileError`，**无 ok/bad 判定**——需前端拿 `actualOutput` 与样例期望比对得出（E）。
- **倒计时告警**：`secs < 600`（10 分钟）时 `.timer.warn` 变红（`--wa-bg` / `--wa-deep`）。阈值 600 是前端常量。
- **考试类型视觉**：`data-page="s-assignments"`（复用 breadcrumb「学习\|我的作业\|数据结构期中测验」）——**页面归属不一致**，面包屑指向作业列表，页面本身不在导航里。
- **AI 权限**：`aiEnabled=false` + 「阶段 ①②③④ 全部禁用，接口直接返回 403」→ 契约 `mode_type=3 期末考试` + `TeachingInnerFeignClient(/ai-permission/verify)` + HTTP 403，**一致**。
- **防作弊颜色语义**：`.timer.warn` 红、`.runbar.ok` 绿 / `.bad` 红；`.qn` 三态用中性/蓝/深灰，**不复用判题色**——与契约无关，属前端设计约束。

### 实时性 / 分页 / 排序 / 筛选

- **实时性（本页关键）**：
  1. **倒计时**：1s 心跳，必须基于**服务端下发的 `end_time` + 服务端时间偏移**，不能信任本地时钟；到 0 必须调交卷接口（前端兜底 + 服务端定时兜底双保险）。
  2. **切屏上报**：`visibilitychange` / `blur` 即时上报，`awaySeconds` 由 `performance.now()` 差算；返回的 `tabSwitchCount` / `redFlagTriggered` 要立即渲染告警。**这是考试页唯一的强制实时上报**，原型完全未实现。
  3. **判题轮询**：提交本题后需 `GET /api/judge/status/{submissionId}` 轮询（与工作台同款），但考试期间应限制返回明细（只给 `passCaseCount`，不给 `caseDetails` 的 IO）。
  4. **不需实时**：题面、示例、spec、须知——试卷下发一次即可（对应 `loading` 骨架态）。
- **分页**：无（5 题一屏，左栏滚动）。
- **排序**：无（题目顺序 = 《试卷》的 `problem_ids` 数组顺序，契约 `teach_assignment.problem_ids` 是有序 JSON 数组，正好可用）。
- **筛选**：无。隐式参数 `?q=`（跳转到第 N 题，1-based）、`?state=submitted`（已交卷态）、`?err=1`、`?skeleton=1`。真实实现需要 `?assignmentId=`（当前完全硬编码为「数据结构期中测验」）。

---

## 页面：shell.js（共享数据层 / window.OJ）

### 数据需求表

| # | 页面模块 / 交互点 | 所需数据（字段级） | 类型与取值域 | 数据来源判定 | 现有契约覆盖情况 | 缺口说明 |
|---|---|---|---|---|---|---|
| 1 | `OJ.problems`（12 条，题库/工作台/学情/作业共用） | `id / title / lvl / tags[] / ac / tried / status / score` | long/string/int/array/int/int/enum/int | B-现有表可派生但无接口 | `prob_question.id/title/difficulty/tags/pass_count/submit_count` + `judge_submission` | 键名与契约不同（`lvl`↔`difficulty`、`ac`↔`pass_count`、`tried`↔`submit_count`）；`status`/`score` 是**用户维度**，却混在「题目常量」里——真实实现必须拆成「题目静态字段 + 我的状态」两段 |
| 2 | `OJ.submissions`（10 条，提交记录/学情/作业共用） | `id('S-20481') / pid / title / lang('C++ 17') / status / score / pass('8/10') / time('12ms') / mem('4096KB') / at('10-24 23:41') / first(3\|null)` | string/long/string/string/enum/int/string/string/string/string/int\|null | C-现有表缺字段 | `judge_submission.*` 多为数值型（`time_used` ms / `memory_used` KB / `create_time` datetime / `pass_case_count`+`total_case_count`） | mock 把 5 个数值字段全格式化成字符串（`pass`/`time`/`mem`/`at`/`id`），排序、求均值、比较全部退化为字符串操作；契约需补「展示单号」与「语言版本」，其余靠前端格式化 |
| 3 | `OJ.assignments`（5 条，作业列表/学情共用） | `id('A-0412') / title / type / typeName / pidCount / done / score / full / due('10-26 23:59') / left('2 天') / ai('仅阶段 ①③ · 上限 3 次') / status` | string/string/enum/string/int/int/int/int/string/string/string/enum | C-现有表缺字段 | `teach_assignment.title/mode_type/problem_ids/end_time` + `teach_student_score` | `full`（满分）与 `ai`（自然语言策略串）**无字段**；`left`/`due` 是预格式化文案而非可比较时间；`pidCount`/`done`/`score` 是聚合值 |
| 4 | `OJ.statusName` / `statusTag`（11 值映射） | `s → 中文名 + tag class` | {ac,wa,tle,mle,re,ce,pac,pending,none,run,idle} | C-现有表缺字段 | `judge_submission.status` 0-9 映射到 8 个判题态 | `run`（运行中）/ `idle`（空闲）**不是判题结果**，契约无此二态；`pending` 需覆盖 0/1/2 三态；缺 `PAC`(4) 的展示入口 |
| 5 | `OJ.lvlName(n)` / `OJ.lvl(n)`（5 档 + 5 格刻度） | `n → 入门/简单/中等/较难/困难` | int 1..5 | C-现有表缺字段 | `prob_question.difficulty` 1\|2\|3 | **3 档 vs 5 档**，全站 4 个页面（题库/工作台/作业抽屉/学情/考试）共用该函数，一处不一致波及全站 |
| 6 | `OJ.fmt(n)`（万位转 K） | 数字 → `1.8K` | int → string | E-纯前端派生可算 | 无 | 纯展示 |
| 7 | `OJ.qs(k, d)`（读 `location.search`） | URL 查询参数 | string | E-纯前端派生可算 | 无 | 纯前端路由；但它是全站深链协议的唯一实现，参数清单缺统一约定 |
| 8 | `OJ.nav`（学生 4 项 + 工作台）+ badge「我的作业 3」 | 导航项 + 待办数 | array + int | B-现有表可派生但无接口 | `teach_assignment` / `teach_student_score` | badge 写死 3，无计数接口 |
| 9 | `OJ.roles.student = {user:'林昭', sub:'计算机 2022 级 · 1 班'}` | `sys_user.real_name` / `teach_class.class_name` | string / string | B-现有表可派生但无接口 | `sys_user.real_name` / `teach_class.class_name` | 契约无当前用户接口；姓名班级在 4 个页面重复出现 |
| 10 | 顶栏通知 badge「2」 | 未读通知数 | int | D-无任何表落点 | 无 `notification` 表 | 无落点；纯装饰 |
| 11 | 顶栏全局搜索框 | 跨实体搜索（题目/作业/提交） | string | D-无任何表落点 | 无搜索接口 | 完全未挂接任何数据，纯装饰 |
| 12 | 顶栏快捷键面板 / 侧栏折叠 / 角色切换 | 无后端数据 | — | E-纯前端派生可算 | 无 | 纯 UI 状态 |

### 交互与写操作清单

| 操作 | 触发位置 | 是否写库 | 需要的接口语义 |
|---|---|---|---|
| 导航跳转 | 侧栏 4 项 + 工作台入口 | 否 | GET 深链；需统一 `?pid=&assignmentId=&status=` 参数协议（当前各页各写各的） |
| 全局搜索 | 顶栏搜索框 | 否（未实现） | 需 `GET /api/search?q=` 跨题目/作业/提交（契约无） |
| 通知查看 | 顶栏铃铛 | 否（未实现） | 需通知列表 + 已读标记（契约无） |
| 角色切换（评审用） | 侧栏角色选择 | 否 | 真实实现应走登录态 `sys_user.role`（STUDENT/TEACHER/ADMIN），无切换接口 |
| 轻提示 `OJ.toast` | 各页写操作反馈 | 否 | 纯前端；带 `undo` 的 toast（5s）暗示存在可撤销写操作，但契约无撤销语义 |
| 数字滚动 `x-count` | 统计卡 | 否 | 纯前端 |

### 枚举/状态机

- **判题态（11 值）**：`ac/wa/tle/mle/re/ce/pac/pending/none/run/idle`。契约 `judge_submission.status` 只有 0-9（排队/编译中/评测中/AC/PAC/WA/TLE/MLE/RE/CE）。**多出 3 个非判题态**：`none`（无记录）、`run`（运行中，应为 self-test 的临时态）、`idle`（空闲，非状态）；**缺** `pending` 与 0/1/2 的细分映射。
- **难度（5 档）**：`1 入门 / 2 简单 / 3 中等 / 4 较难 / 5 困难`，5 格刻度 `.lvl-bars > i.on`。契约 **3 档**。全站唯一口径来源。
- **角色**：`roles` 含 student / teacher / admin → 契约 `sys_user.role` STUDENT/TEACHER/ADMIN，**一致**（仅大小写/语言差异）。
- **颜色语义**：`.tag.*` 见 `components.css:200-209`；`.lvl` 全中性色（源码注释：避免「简单」蓝撞主色、「较难」红撞 WA、「困难」紫撞 MLE）。

### 实时性 / 分页 / 排序 / 筛选

- 本层无请求、无分页、无排序、无筛选——它只是**常量 + 工具函数**。但它定义了全站的数据「形状」，因此：
  - **实时性**：无。所有数据一次性加载（`<script src="shell.js">` 同步阻塞）。
  - **分页/排序/筛选**：全部由各页自行实现，导致 4 个页面各写一套（题库前端排序、作业前端切片、提交记录前端分页、学情前端分页）。真实实现应统一为服务端分页协议。
  - **深链参数**：`qs()` 是全站唯一入口，已出现的参数有 `pid` / `status` / `lvl` / `err` / `skeleton` / `state` / `case` / `q` / `tag` / `stage`，**无 `assignmentId`**——这是工作台拿不到作业上下文、考试页无法参数化的根因。

---

## 学生端汇总

### 规模

- **分析页面数**：6 个页面（s-problems / s-workbench[html+js] / s-assignments / s-submissions / s-analytics / s-exam）+ 1 个共享数据层（shell.js）。
- **数据需求条目总数**：**206 条**。

| 页面 | 条目数 | A | B | C | D | E |
|---|---|---|---|---|---|---|
| s-problems.html | 31 | 0 | 23 | 4 | 0 | 4 |
| s-workbench.html + .js | 47 | 19 | 10 | 8 | 6 | 4 |
| s-assignments.html | 32 | 0 | 26 | 1 | 4 | 1 |
| s-submissions.html | 28 | 0 | 23 | 2 | 1 | 2 |
| s-analytics.html | 29 | 0 | 19 | 0 | 5 | 5 |
| s-exam.html | 27 | 8 | 6 | 4 | 5 | 4 |
| shell.js（共享层） | 12 | 0 | 3 | 4 | 2 | 3 |
| **合计** | **206** | **27** | **110** | **23** | **23** | **23** |

### 判定分布

| 判定 | 条数 | 占比 | 含义 |
|---|---|---|---|
| A-现有接口直接覆盖 | 27 | 13.1% | 全部集中在 s-workbench（19）与 s-exam（8）；即 judge 3 个 + ai 2 个 + teaching tab-switch 这 6 个接口的字段，其余 4 页为 0 |
| B-现有表可派生但无接口 | 110 | 53.4% | 表结构基本够用，**缺的是查询接口**（题目列表、作业列表、提交列表、学情聚合） |
| C-现有表缺字段 | 23 | 11.2% | 难度档位、测试点 label/隐藏数、每题分值/作业满分、语言版本、业务提交号、复杂度评估等 |
| D-无任何表落点 | 23 | 11.2% | 学情 AI 解读、标签诊断/推荐、交作业/交卷、作答草稿、测试点代码行映射等 |
| E-纯前端派生可算 | 23 | 11.2% | 倒计时、进度百分比、分布百分比、派生文案；无需后端 |
| **合计** | **206** | 100% | A+B = 137 条（66.5%）说明「数据基本都有，接口严重不足」 |

> 口径提示：A 类 27 条全部落在「有接口」的两页（工作台 19 / 考试 8），也就是说 7 个 REST 里只有 6 个被学生端真正用到（`/api/teaching/plagiarism/*` 三个是教师端），而 4 个数据密集型页面（题库、作业、提交记录、学情）**没有一条 A 类**——这是本盘点最重要的结论。

### 最严重的 5 个缺口（按影响排序）

1. **作业/考试的「提交」闭环完全缺失**——契约只有 `POST /api/judge/submit`（单题提交），没有「交作业」「交卷」接口，也没有 `assignment_submission` / `exam_handin` / 作答草稿表；s-assignments 的 `handIn()` 与 s-exam 的 `handIn()` 都只是把本地布尔值置 true，学生刷新即回到未交状态，且考试页的代码草稿（`q.code`）在契约里无任何存储落点。
2. **一条「列表查询」接口都没有**——题目列表（23）、作业列表（26）、提交记录列表（23）、学情聚合（19）四类页面共 **91 条 B 类需求**全部卡在「表有字段、无接口」；最典型的是 s-submissions 的 47 次提交与 s-analytics 的标签掌握度，前端只能把 10~15 条 mock 硬编码进页面。
3. **难度是 3 档 vs 前端 5 档的结构性冲突**——`prob_question.difficulty` 只有 `1 简单/2 中等/3 困难`，而 `shell.js` 的 `lvlName` 是全站唯一口径（`入门/简单/中等/较难/困难`，5 格刻度），题库筛选、题库列表、工作台徽标、作业抽屉、学情表、考试题号列表共 6 处引用；不统一则「较难/困难」两档的题目无处安放，且 `?lvl=4/5` 深链永远筛不出题。
4. **作业/试卷的「每题分值」与「满分」无字段**——`prob_question` 无 `score`、`teach_assignment` 无 `full_score` / `problem_scores` / `duration_minutes` / `scoring_policy`；导致 s-assignments 的 `score/full` 列、s-exam 的「20 分 × 5 题 = 100 分」与「限时 90 分钟」「取最后一次」全部无法计算，也是 `teach_student_score.max_score(0~100)` 与作业总分口径打架的根源。
5. **判题结果的可视化细节大面积超出契约**——测试点语义名（`CASE_LABELS`）、测试点→代码行映射（`CASE_LINE`/`LINE_CASE`）、除首个失败点外的测试点输入/期望/实际输出（s-submissions 抽屉）、行级缺陷定位、多智能体协作数与分段耗时、学情 AI 解读与标签级归因/推荐理由，合计 **23 条 D 类 + 23 条 C 类**；其中「测试点代码行映射」源码注释已自认是叙事道具，真实实现必须由 AI 从 `first_failed_summary` 推理得到，不能当作字段需求提给后端。
