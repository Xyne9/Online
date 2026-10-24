# 基于 AI Agent 与微服务架构的启发式智能在线判题系统

> 一个「判题 + AI 启发式辅导」的在线判题系统（OJ）：异步判题按 OI 规则逐点计分，AI 助教以 Router → Tutor → Guardrail 多智能体工作流做四阶段启发，而不是直接给答案。

毕业设计项目。仓库内目前包含**产品需求文档**、**系统详细设计与接口契约规范**、以及一套**高保真静态交互原型**（三端共 15 个功能页面 + 1 个门户总览）；后端服务代码尚在实现阶段。

## 项目简介

系统面向高校算法课程与 OI 赛制训练场景，覆盖学生、教师、管理员三端：

- **判题**：学生提交后任务进入 RabbitMQ 异步队列，由 `oj-judge-service` 调用 Docker 容器化的 **Go-Judge** 沙箱评测；按 OI 规则跑完全部 N 组隐藏测试点，通过 M 组即得 `⌊M/N × 满分⌋`，并持久化**首个失败测试点编号及其脱敏特征**（供 AI 排错诊断调用）。Java / Python 相对 C/C++ 基准时限与内存自动 ×2 补偿。
- **AI 启发式辅导**：分 ①读题拆解 ②编码伪代码引导 ③排错诊断 ④AC 后复盘四个阶段，随做题状态加锁（③需非 AC 提交、④需 AC 提交）。`Router` 与 `Guardrail` 固定走本地轻量模型，`Tutor` 可端云切换，云端超时 **8 秒**自动降级本地 Ollama；护栏拦截「>5 行完整解题代码」，改写为苏格拉底式引导。
- **教务与防作弊**：练习 / 作业 / 考试三种活动分级管控 AI 权限（全开 / 仅①③且限次 / 全关返回 403），考中切屏预警，考后「SimHash + AST 粗筛（≥75%）→ AI 语义精筛」双阶段查重。

架构为 **1 网关 + 4 微服务**：`oj-gateway`（路由 / JWT 鉴权 / 限流）、`oj-problem-service`（题库与隐藏题解 RAG 知识源）、`oj-judge-service`（异步判题调度）、`oj-ai-agent-service`（多智能体编排与 SSE 流式输出）、`oj-teaching-service`（教务、监控与查重粗筛）。

## 技术栈基线

| 层 | 选型 |
| --- | --- |
| 前端 | Vue 3 + Vite + Pinia + Element Plus + Monaco Editor（编辑与防粘贴）+ ECharts（学情可视分析）+ Fetch EventSource（SSE 流式对话） |
| 微服务底座 | JDK 17/21 + Spring Boot 3.2.x + Spring Cloud Alibaba（Nacos 注册/配置中心、OpenFeign、Spring Cloud Gateway） |
| 数据与中间件 | MySQL 8.0（业务持久化）+ Redis（缓存、AI 上下文记忆、接口防抖、提示配额计数）+ RabbitMQ（判题任务异步削峰） |
| 判题与 AI 引擎 | Docker 容器化 Go-Judge 安全评测内核（C/C++、Java、Python）+ LangChain4j 智能体编排 + Ollama 本地模型 Qwen2.5-Coder + 云端 DeepSeek / 通义千问 / 智谱 GLM |

## 仓库结构

```
.
├── README.md                                    本文件
├── 《基于AI Agent与微服务架构的启发式智能在线判题系统》产品需求文档（PRD v1.0 终版）.md
├── 《系统详细设计与接口契约规范（Detailed Design & API Specification）》.md
├── prototype/
│   ├── app/                 三端全站高保真原型（15 功能页 + 门户，静态 HTML）
│   ├── workbench/           学生做题工作台单页原型（A 判分链 + C 协同面板融合稿）
│   ├── explore/             设计方向探索（三个首屏方案 a/ b/ c/ 与对比页）
│   ├── research/            竞品参考截图（LeetCode 中国站、洛谷）与采集报告
│   └── design-notes.md      原型设计说明：标杆拆解、调性、色彩与方向取舍
├── _ep_tmp/                 临时抓取的 Element Plus 文档/源码片段（过程性产物）
└── prototype/**/_shots/     各阶段截图留档（论文与答辩配图素材）
```

## 设计文档

### [产品需求文档（PRD v1.0 终版）](<./《基于AI Agent与微服务架构的启发式智能在线判题系统》产品需求文档（PRD v1.0 终版）.md>)

- 技术栈基线与 **1 网关 + 4 核心服务**的边界划分。
- 异步判题状态机（Pending → Compiling → Running → AC / PAC·WA·TLE·MLE·RE / CE）与 AI 四阶段状态机的 Mermaid 图。
- 四个模块的功能详情、业务规则、交互规则与可验收标准（AC-1.x ~ AC-3.x），如「SSE 首字 TTFT ≤ 2.5s」「20 组套话指令代码泄漏率 0%」「50 人班级粗筛 ≤ 2s」。

### [系统详细设计与接口契约规范](<./《系统详细设计与接口契约规范（Detailed Design & API Specification）》.md>)

- **第一部分**：MySQL 8.0 物理表设计，4 个服务共 8 张主表的核心建表 SQL（`prob_question`、`judge_submission`、`ai_model_config`、`ai_chat_record`、`teach_assignment`、`teach_student_score`、`teach_plagiarism_report` 等）。
- **第二部分**：经网关的前后端 RESTful 接口与微服务间 OpenFeign RPC 契约，含请求/响应 JSON 报文。
- **第三部分**：端云动态模型工厂与 `@Tool` 挂载、Go-Judge 沙箱调度、双阶段漏斗查重的类结构与算法设计。

## 交互原型

原型位于 [`prototype/app/`](./prototype/app/)，纯静态 HTML/CSS/JS：**无需构建、无需服务器、无外网依赖**，样式与 Alpine.js 均已本地化。

**打开方式**：直接双击 `prototype/app/index.html`；或在仓库根目录执行 `python -m http.server`，然后访问 `http://localhost:8000/prototype/app/index.html`。

`index.html` 是门户总览，三张角色卡 + 按角色分组的全部页面清单，点任意一项直达。

**外壳机制**：每个页面只在 `<body>` 上声明 `data-role` / `data-page` / `data-crumb`，`shell.js` 据此注入左侧导航、面包屑、角色切换器与共享模拟数据 `window.OJ`；页面自身只写内容区，因此页面之间可任意跳转且导航高亮始终正确。设计变量集中在 `styles/tokens.css`，页面内不写裸色值。

### 学生端（6 页）

| 文件 | 页面 | 核心内容 |
| --- | --- | --- |
| `s-problems.html` | 题库列表 | 状态/难度/标签筛选、关键词搜索、题目详情抽屉、开做跳转 |
| `s-workbench.html` | 做题工作台 | 全站最复杂一页：题面 + Monaco 编辑器（行内批注胶囊）+ AI 抽屉（模型分段切换 / 思考过程折叠 / 四阶段面板）+ 底部 10 格判分链带 + 64px 阶段窄轨 |
| `s-assignments.html` | 我的作业 | 状态 chip 筛选（带计数与空态）、搜索、截止时间排序、详情抽屉、分页 |
| `s-submissions.html` | 提交记录 | 状态/语言/时间三重筛选、代码回看抽屉（行号 + 错误行高亮 + 判题链带）、AI 诊断跳转 |
| `s-analytics.html` | 学情分析 | 7/30/90 天范围联动、标签能力下钻、只看薄弱开关、薄弱点抽屉（症状/根因/建议/推荐练习） |
| `s-exam.html` | 考试作答 | 三栏锁满一屏、倒计时、题目导航、禁止 AI 提示、交卷确认 |

### 教师端（7 页）

| 文件 | 页面 | 核心内容 |
| --- | --- | --- |
| `t-dashboard.html` | 班级概览 | 班级指标卡、AC 率趋势、待批作业、异常告警、快捷入口 |
| `t-problems.html` | 题目管理 | 草稿/已发布/已归档列表、筛选、批量操作、状态切换 |
| `t-problem-edit.html` | 出题编辑 | 题面编辑、样例用例、判题配置、标签、AI 策略（阶段开关 + 提示上限） |
| `t-assignments.html` | 教学活动 | 作业列表、发布/编辑抽屉、AI 权限配置（练习全开 / 作业仅①③ / 考试全关） |
| `t-analytics.html` | 班级学情 | 班级维度统计、题目掌握度矩阵、学生排名、薄弱标签分布 |
| `t-monitor.html` | 考试监考 | 实时在线列表、切屏告警、强制交卷、考试状态控制 |
| `t-plagiarism.html` | 代码查重 | 两阶段查重（AST 75% / 语义向量 85%）、可疑对列表、双栏代码对比、判定操作 |

### 管理端（2 页）

| 文件 | 页面 | 核心内容 |
| --- | --- | --- |
| `a-models.html` | 模型通道 | 4 条通道（本地 Ollama / DeepSeek / 通义千问 / 智谱）启停、8 秒超时降级、权重、连通性测试 |
| `a-audit.html` | 调用审计与沙箱 | AI 调用全链路留痕、护栏拦截记录、Token 用量、判题沙箱容器健康 |

页面级开发契约（页面职责、`window.OJ` 共享 API、数据口径）见 [`prototype/app/_SPEC.md`](./prototype/app/_SPEC.md)；更细的页面说明与已知取舍见 [`prototype/app/README.md`](./prototype/app/README.md)。

## 设计探索与参考

[`prototype/explore/`](./prototype/explore/) 是定稿前的方向探索：三个共用同一份产品事实（`SHARED-FACTS.md`）的首屏方案——A「判分链」让每一分怎么丢的当场可见、B「批注课堂」把 AI 回复变成长在代码行上的批注、C「协同面板」把 Multi-Agent 的 Router/Tutor/Guardrail 协同过程做成界面；[`style-explorer.html`](./prototype/explore/style-explorer.html) 是三者并排对比页。最终定稿取 A 作主框架、把 C 的模型切换与智能体流水线收进右侧 AI 抽屉（见 [`prototype/workbench/`](./prototype/workbench/)，含 Vue 3 + Element Plus 迁移对照 `VUE-MIGRATION.md`）。

[`prototype/research/`](./prototype/research/) 是竞品参考：LeetCode 中国站做题页与洛谷题目页的实拍截图，配合 `report.json` 记录采集结果；设计说明 [`prototype/design-notes.md`](./prototype/design-notes.md) 里有逐要素的「看见什么 / 为什么这样 / 留什么换什么」拆解。

## 说明

- `_ep_tmp/` 是编写原型与迁移文档时临时抓取的 Element Plus 文档与源码片段，属**过程性工作产物**，不是交付内容。
- 各 `_shots/` 目录是设计迭代与多分辨率复核的**截图留档**（1440×900 / 1920×1080 / 1280×800 等档位），用于论文与答辩配图，非可运行代码。
- 原型为**纯静态 + 前端模拟数据**：判题、AI 回复、查重结果均为时序模拟，真实接口契约以《系统详细设计与接口契约规范》为准。

---

毕业设计项目 / 仅供学习交流
