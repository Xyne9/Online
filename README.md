# 基于 AI Agent 与微服务架构的启发式智能在线判题系统

> 一个「判题 + AI 启发式辅导」的在线判题系统（OJ）：异步判题按 OI 规则逐点计分，AI 助教以 Router → Tutor → Guardrail 多智能体工作流做四阶段启发，而不是直接给答案。

毕业设计项目。仓库内目前包含**产品需求文档**、**系统详细设计与接口契约规范**、一套**高保真静态交互原型**（三端共 16 个功能页面 + 1 个门户总览），以及**已完成的前端 Stage 0 适配底座与后端 Maven 多模块骨架**（6 个服务可编译、可启动、可注册 Nacos）。

> 开发进度与操作规范见 [`agent.md`](./agent.md)；每日验收记录见 [`docs/day1-交付说明.md`](./docs/day1-交付说明.md) 与 [`docs/day2-3-交付说明.md`](./docs/day2-3-交付说明.md)。

## 项目简介

系统面向高校算法课程与 OI 赛制训练场景，覆盖学生、教师、管理员三端：

- **判题**：学生提交后任务进入 RabbitMQ 异步队列，由 `oj-judge-service` 调用 Docker 容器化的 **Go-Judge** 沙箱评测；按 OI 规则跑完全部 N 组隐藏测试点，通过 M 组即得 `⌊M/N × 满分⌋`，并持久化**首个失败测试点编号及其脱敏特征**（供 AI 排错诊断调用）。Java / Python 相对 C/C++ 基准时限与内存自动 ×2 补偿。
- **AI 启发式辅导**：分 ①读题拆解 ②编码伪代码引导 ③排错诊断 ④AC 后复盘四个阶段，随做题状态加锁（③需非 AC 提交、④需 AC 提交）。`Router` 与 `Guardrail` 固定走本地轻量模型，`Tutor` 可端云切换，云端超时 **8 秒**自动降级本地 Ollama；护栏拦截「>5 行完整解题代码」，改写为苏格拉底式引导。
- **教务与防作弊**：练习 / 作业 / 考试三种活动分级管控 AI 权限（全开 / 仅①③且限次 / 全关返回 403），考中切屏预警，考后「SimHash + AST 粗筛（≥75%）→ AI 语义精筛」双阶段查重。

架构为 **1 网关 + 5 微服务**（共 87 个 HTTP 端点）：`oj-gateway`（路由 / JWT 鉴权 / 限流）、`oj-teaching-service`（认证 + 教务 + 双阶段查重）、`oj-problem-service`（题库与隐藏题解 RAG 知识源）、`oj-judge-service`（异步判题调度）、`oj-ai-agent-service`（多智能体编排与 SSE 流式输出）、`oj-admin-service`（通道探活 / 审计 / 沙箱监控）。

## 技术栈基线

| 层 | 选型 |
| --- | --- |
| 前端 | **零构建多页应用**：原生 HTML + Alpine.js 响应式内核 + 手写 CSS Design Token 体系（`styles/tokens.css`）。内置自定义在线代码编辑器（行号联动 + 考中剪贴板沙箱）、原生 SVG/CSS 学情可视化、基于 `fetch + ReadableStream` 的自定义 SSE 客户端、自适应轮询器，以及支持断网全功能演示的 `?mock=1` 离线双轨适配层。**无 npm / 无 Vite / 无打包** |
| 微服务底座 | JDK 17 + Spring Boot 3.2.5 + Spring Cloud 2023.0.1 + Spring Cloud Alibaba 2023.0.1.0（Nacos 注册/配置中心、OpenFeign、Spring Cloud Gateway） |
| 数据与中间件 | MySQL 8.0（18 张表：17 核心业务 + 1 知识点字典）+ Redis（12 类缓存与分布式锁）+ RabbitMQ（判题任务异步削峰） |
| 判题与 AI 引擎 | Docker 容器化 Go-Judge 安全评测内核（C/C++、Java、Python）+ LangChain4j 智能体编排 + Ollama 本地模型 Qwen2.5-Coder + 云端 DeepSeek / 通义千问 / 智谱 GLM |

## 仓库结构

```
.
├── README.md                                    本文件
├── agent.md                                     AI 协作开发约束手册（红线 / 环境事实 / checklist）
├── docker-compose.yml                           5 个基础容器一键启动
├── 《…》产品需求文档（PRD v2.0 终版）.md          v2.0 为准（v1.0 留档）
├── 《系统详细设计与接口契约规范》.md
├── docs/
│   ├── day1-交付说明.md                          Day 1 验收记录
│   ├── day2-3-交付说明.md                        Day 2-3 验收记录
│   └── analysis/                                契约补全规范与前端对接指南（改动前必读）
├── backend/                                     Maven 多模块：父 POM + 8 模块
├── docker/mysql/init/init_oj_cloud.sql          18 张表 DDL + 种子数据
├── docker/judge/                                go-judge 派生镜像 + 三语言冒烟
├── scripts/                                     构建 / 启停 / 验收脚本
├── prototype/
│   ├── app/                 三端全站高保真原型（16 功能页 + 门户 + 登录页，静态 HTML）
│   │   ├── api/             请求层：http / sse / poll / mock / auth
│   │   ├── adapter/         字段适配层：7 张映射表 + index.js
│   │   ├── boot.js          鉴权守卫 + 身份注入 + mock 跨页透传
│   │   └── login.html       第 17 页
│   ├── workbench/           学生做题工作台单页原型（A 判分链 + C 协同面板融合稿）
│   ├── explore/             设计方向探索（三个首屏方案 a/ b/ c/ 与对比页）
│   ├── research/            竞品参考截图（LeetCode 中国站、洛谷）与采集报告
│   └── design-notes.md      原型设计说明：标杆拆解、调性、色彩与方向取舍
├── _day2_baseline/          视觉零回归权威基线（16 页在本机渲染的「改造前」快照）
└── prototype/app/_shots/    各阶段截图留档（论文与答辩配图素材）
```

## 设计文档

### [产品需求文档（PRD v2.0 终版）](<./《基于AI Agent与微服务架构的启发式智能在线判题系统》产品需求文档（PRD v2.0 终版）.md>)

- 技术栈基线与 **1 网关 + 5 核心服务 / 87 个端点 / 18 张表 / 17 页**的边界划分。
- 异步判题状态机（`0 QUEUED → 1 COMPILING → 2 JUDGING → 3 AC / 4 PAC / 5 WA / 6 TLE / 7 MLE / 8 RE / 9 CE`）与 AI 四阶段状态机的 Mermaid 图。
- 四个模块的功能详情、业务规则、交互规则与可验收标准（AC-1.x ~ AC-4.x），如「SSE 首字 TTFT ≤ 2.5s」「20 组套话指令代码泄漏率 0%」「50 人班级粗筛 ≤ 2s」。
- 末节列出**5 处微创绑定调整**——把静态原型接上真实接口时唯一允许的 HTML/JS 改动（已全部落地，见 `agent.md §3.5`）。

### [系统详细设计与接口契约规范](<./《系统详细设计与接口契约规范（Detailed Design & API Specification）》.md>)

- **第一部分**：MySQL 8.0 物理表设计，4 个服务共 8 张主表的核心建表 SQL（`prob_question`、`judge_submission`、`ai_model_config`、`ai_chat_record`、`teach_assignment`、`teach_student_score`、`teach_plagiarism_report` 等）。
- **第二部分**：经网关的前后端 RESTful 接口与微服务间 OpenFeign RPC 契约，含请求/响应 JSON 报文。
- **第三部分**：端云动态模型工厂与 `@Tool` 挂载、Go-Judge 沙箱调度、双阶段漏斗查重的类结构与算法设计。

## 交互原型

原型位于 [`prototype/app/`](./prototype/app/)，纯静态 HTML/CSS/JS：**无需构建、无需服务器、无外网依赖**，样式与 Alpine.js 均已本地化。

**打开方式**：直接双击 `prototype/app/index.html`；或在 `prototype/app/` 下执行 `python -m http.server 3000`，然后访问 `http://127.0.0.1:3000/index.html`。

`index.html` 是门户总览，三张角色卡 + 按角色分组的全部页面清单，点任意一项直达。

### 两种运行模式

| 模式 | 入口 | 行为 |
|---|---|---|
| **断网演示** | 任意页加 `?mock=1` | 自动发假 token、走 `api/mock.js` 的假数据与假判题，17 页全部可交互 |
| **真实联调** | 直接开页面 | 无 token 跳 `login.html?redirect=<原页>`；登录后走网关 `:8080` 的真实接口 |

> `?mock=1` 会写进 `sessionStorage`，跨页跳转不丢。站内链接点击时自动补参数。

**外壳机制**：每个页面只在 `<body>` 上声明 `data-role` / `data-page` / `data-crumb`，`shell.js` 据此注入左侧导航、面包屑、角色切换器；页面自身只写内容区，因此页面之间可任意跳转且导航高亮始终正确。设计变量集中在 `styles/tokens.css`，页面内不写裸色值。

**请求与适配分层**（Day 2 新增）：

```
页面 (x-data)
   ↓ 只认 adapter 的输出（键名与改造前常量一字不差）
adapter/   字段改名 + 单位换算
   ↓
api/       http（信封/9 错误码/超时/退避重试）/ sse（六事件）/ poll（1s 自适应）
   ↓
网关 :8080
```

`api/mock.js` 是断网演示的唯一数据源；`shell.js` 的 8 个 `OJ.xxx` getter 已指向它，所以页面里 `OJ.problems` 这类读法一行都不用改。

### 学生端（6 页）

| 文件 | 页面 | 核心内容 |
| --- | --- | --- |
| `s-problems.html` | 题库列表 | 主角是「继续做题」卡（下一道该做的题 + 迷你判分链），筛选栏（状态/难度/标签/关键词 + 已启用条件 chips）、6 列紧凑行、行点开题目预览抽屉 |
| `s-workbench.html` | 做题工作台 | 全站最复杂一页：题面（可拖拽调宽）+ 自定义代码编辑器（行内批注胶囊）+ AI 抽屉（模型分段切换 / 思考过程折叠 / 四阶段面板）+ 底部 40px 判分摘要条（点开看 10 格全宽判分链）+ 64px 阶段窄轨 |
| `s-assignments.html` | 我的作业 | 三格扁平统计带 + 状态 chip 筛选（带计数与空态）、搜索、截止时间排序、详情抽屉、分页 |
| `s-submissions.html` | 提交记录 | 主角是「每一次提交的判分链」（列表每条带迷你判分链）、状态/语言/时间三重筛选、代码回看抽屉、AI 诊断跳转 |
| `s-analytics.html` | 学情分析 | 主角是首屏 AI 学情解读 + 薄弱点大卡，7/30/90 天范围联动、标签能力下钻、只看薄弱开关、薄弱点抽屉（症状/根因/建议/推荐练习） |
| `s-exam.html` | 考试作答 | 三栏锁满一屏、倒计时、题目导航、禁止 AI 提示、交卷确认、判分链回显 |

### 教师端（7 页）

| 文件 | 页面 | 核心内容 |
| --- | --- | --- |
| `t-dashboard.html` | 班级概览 | **全站版式样板**：「需要你处理」待办主卡（行内动作，处理完原地划掉收起）+ 本周通过率 30px 次级数字 + 易错排行 / 进行中活动 / AI 价值证明 |
| `t-problems.html` | 题目管理 | 草稿/已发布/已归档列表、筛选、批量操作、状态切换 |
| `t-problem-edit.html` | 出题编辑 | 主角是 10 格测试点判分链（提到基本信息之后），题面/样例/判题配置/标签/AI 策略（阶段开关 + 提示上限） |
| `t-assignments.html` | 教学活动 | 主角是「还要处理哪几项活动」（进行中/待发布置顶，带完成率进度），发布/编辑抽屉、AI 权限配置（练习全开 / 作业仅①③ / 考试全关） |
| `t-analytics.html` | 班级学情 | 主角是班级薄弱点 + AI 解读卡，题目掌握度矩阵、学生排名、薄弱标签分布 |
| `t-monitor.html` | 考试监考 | 主角是「需要关注」的异常学生卡组（左 3px 状态条 + 红色状态字），实时事件流、考场概况、强制交卷、结束考试 |
| `t-plagiarism.html` | 代码查重 | 主角是两份代码的并排比对；两阶段查重（AST 75% / 语义向量 85%）、可疑对列表、判定操作 |

### 管理端（2 页）

| 文件 | 页面 | 核心内容 |
| --- | --- | --- |
| `a-models.html` | 模型通道 | 主角是通道健康主带（哪条出问题、要不要现在处理），4 条通道（本地 Ollama / DeepSeek / 通义千问 / 智谱）启停、8 秒超时降级、权重、连通性测试 |
| `a-audit.html` | 调用审计与沙箱 | 主角是护栏命中主带（拦了几次 + 命中的是什么），AI 调用全链路留痕、Token 用量、判题沙箱容器健康 |

页面级开发契约（页面职责、`window.OJ` 共享 API、数据口径）见 [`prototype/app/_SPEC.md`](./prototype/app/_SPEC.md)；更细的页面说明与已知取舍见 [`prototype/app/README.md`](./prototype/app/README.md)。

### 视觉整改（三轮评审后）

原型第一版「能用但平」：每块内容分量一样、白底细边框同字号，页面没有主角；字号挤在 11.5–13px；页面之间是硬跳转；AI 能力只以技术指标呈现、看不出价值。三轮整改后：

- **页面分量三级，16 页全部铺开**——以 [`t-dashboard.html`](./prototype/app/t-dashboard.html) 为样板：一个 44px 主角数字 + 一句人话结论 + 最要紧的操作 → 一条不套卡片的扁平统计带 → 明细面板。骨架（`.hero*` / `.mini-stats` / `.lcol` / `.rcol`）已下沉到共享层；每页的主角按「用户来这页要做的那个决定」重定，不一定是数字（题库是「下一道该做的题」、查重是「两份代码的比对」、监考是「需要关注的学生」）。
- **设计令牌收口**——五个样式表的裸 hex、裸字号、裸圆角、裸过渡时长全部清零；字号改成 11/12/13/14/16/22/30/44 的连续台阶（相邻至少差 2px），阅读型正文（题面、AI 回复、说明段落）统一 14px；文字色从「三级但有两级看着一样」压成三级实色 + 一级虚线色（`--ink-dim` 给占位与禁用）；卡片圆角 10 → 12px。
- **跨页 View Transition**——侧栏与顶栏保持不动，内容区 180ms 上浮；页面进场动画与之去叠加，避免导航后先白一下。
- **智能的可视化证据**——概览页把 AI 讲成价值（批改省下 4.2 小时、诊断覆盖 38 人、四阶段调用分布），而不是只列 Token 数。
- **工作台联动**——判分链失败格与 AI 抽屉「丢分定位」共用 `focusCase` 语义标记，悬停任一处另一处同步高亮（PRD 微创修正 #4 后已移除「代码行 ↔ 测试点」的伪造映射，见 `agent.md §3.4`）；AI 抽屉的层级从 10 层压到 5 层，判分链带从 145px 收到 40px 摘要条（点开还原全宽）。
- **键盘可运营**——`/` 聚焦全局搜索、`?` 打开快捷键表、`Esc` 关抽屉弹窗（`shell.js` 捕获阶段处理），工作台另有 `Ctrl+'` 运行、`Ctrl+Enter` 提交。

基准截图见 [`prototype/app/_shots/final/`](./prototype/app/_shots/final/)（1440×900 全 16 页 + 并排总览），另有 1920×1080 与 1280×800 两档回归截图。

## 设计探索与参考

[`prototype/explore/`](./prototype/explore/) 是定稿前的方向探索：三个共用同一份产品事实（`SHARED-FACTS.md`）的首屏方案——A「判分链」让每一分怎么丢的当场可见、B「批注课堂」把 AI 回复变成长在代码行上的批注、C「协同面板」把 Multi-Agent 的 Router/Tutor/Guardrail 协同过程做成界面；[`style-explorer.html`](./prototype/explore/style-explorer.html) 是三者并排对比页。最终定稿取 A 作主框架、把 C 的模型切换与智能体流水线收进右侧 AI 抽屉（见 [`prototype/workbench/`](./prototype/workbench/)，含 Vue 3 + Element Plus 迁移对照 `VUE-MIGRATION.md`）。

[`prototype/research/`](./prototype/research/) 是竞品参考：LeetCode 中国站做题页与洛谷题目页的实拍截图，配合 `report.json` 记录采集结果；设计说明 [`prototype/design-notes.md`](./prototype/design-notes.md) 里有逐要素的「看见什么 / 为什么这样 / 留什么换什么」拆解。

## 说明

- `_ep_tmp/` 是编写原型与迁移文档时临时抓取的 Element Plus 文档与源码片段，属**过程性工作产物**，不是交付内容。
- 各 `_shots/` 目录是设计迭代与多分辨率复核的**截图留档**（1440×900 / 1920×1080 / 1280×800 等档位），用于论文与答辩配图，非可运行代码。
- 原型为**纯静态 + 前端模拟数据**：判题、AI 回复、查重结果均为时序模拟，真实接口契约以《系统详细设计与接口契约规范》为准。
- `data/` 与 `logs/` 是容器运行期数据与服务日志，已 gitignore，可由 `init_oj_cloud.sql` 重建。
- **Day 2 起，原型已不再是「纯模拟」**：`api/` + `adapter/` + `boot.js` 三层地基就位，去掉 `?mock=1` 即走真实网关；`?mock=1` 保留为断网演示双轨。业务接口在 Day 4 起逐个接通。

## 快速开始

```powershell
# 1. 中间件（5 容器）
docker compose up -d && docker compose ps

# 2. 后端（宿主机无 Maven，编译走容器）
pwsh scripts/build-backend.ps1 -Package
pwsh scripts/run-services.ps1 -Action start

# 3. 前端（零构建，起静态服务器即可）
cd prototype\app; python -m http.server 3000
# 断网演示：http://127.0.0.1:3000/s-problems.html?mock=1
# 真实联调：http://127.0.0.1:3000/login.html

# 4. 一键验收
pwsh scripts/verify-day2.ps1
```

详细的开发约束、环境事实与验收 checklist 见 [`agent.md`](./agent.md)。

---

毕业设计项目 / 仅供学习交流
