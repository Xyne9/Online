# Day 8–11 交付说明：第 4 步 AI 助教闭环

对应《从 0 到 1 的 6 步标准开发顺序与操作手册》第 4 步，执行日期 2026-10-27。

## 交付范围

新增 `/api/ai` 的六个接口：`workspace-init`、`chat/stream`、`quota`、`channels`、`sessions`、`feedback`。工作台通过真实网关读取教学权限、阶段解锁、配额、历史会话和模型通道，聊天区消费全部六类 SSE 事件。

| 能力 | 实现 |
|---|---|
| 模型工厂 | LangChain4j 0.35.0；Redis 通道快照为空时从 MySQL 装载，后续热读快照；本地 Router、Guardrail 与端云 Tutor |
| 本地资源 | Ollama 原生客户端显式 `numCtx=8192`，单次 Tutor 最多 1024 个输出 Token；宿主 JVM 将种子 `host.docker.internal` 地址归一化为 `127.0.0.1` |
| 端云切换 | 云端最多等待 8 秒首个非空 Token，密钥缺失、首字超时或首字前调用失败均转本地，并发送真实 `fallback_notice` |
| 阶段①② | 读题拆解与编码引导；教学活动和题目阶段策略取交集 |
| 阶段③ | 按用户、题目、活动隔离查询非 AC 结果，经请求级 `@Tool` 读取失败点诊断；代码自动附真实行号 |
| 阶段④ | 当前活动下已有满分 AC 才解锁；查询真实下一题候选，排除当前题、未发布、软删及已尝试题 |
| 提示等级 | Redis 问题轮次递增，等级 `min(count,3)`；分别为方向反问、逻辑与反例、填空伪代码 |
| 多轮记忆 | Redis 保存最近六轮问答，TTL 两小时；模型上下文按长度裁剪，不把完整历史无限塞入 8192 上下文 |
| 输入护栏 | 拦截修改系统指令等输入，返回 JSON 403；审计 `guardrail_action=1 / JAILBREAK`，不消耗配额 |
| 输出护栏 | 普通文字增量放行，围栏代码块暂存；超过五行立即截断并调用本地 Guardrail 改写，审计 `2 / OVER_LENGTH_CODE` |
| 记录与反馈 | 回答、实际模型、降级标记、行号代码、首字耗时及可用 Token 计量落库；反馈按记录和用户幂等覆盖 |

云端种子密钥仍为空，因此本机验证的是“真实云通道配置缺失 → 真实本地 Ollama 回答”；没有宣称已成功请求付费云模型。8 秒首字超时和空 delta 的降级路径另由可控模型测试验证。

## 配额与权限

自由练习不限次数，API 用 `max=-1 / remaining=-1` 表示。作业模式使用活动表的 `ai_quota_per_prob`，仅开放①③与活动配置的交集。考试关闭 AI；当前班级进行中的同题考试也会阻止通过 `assignmentId=0` 绕过。

学生必须属于该活动班级，教师必须是活动发布者，题目必须存在于活动的 `problem_ids`。活动状态与北京时间窗口由 teaching 服务裁决。

Lua 原子预留同用户最多两条流与有限配额，回答成功写入 MySQL 后确认消费。错误、取消或落库失败会释放本次预留；结束与回调重复触发也只释放一次。前端只接受服务端配额快照，提交判题不会重置次数；429 和 403 后重新读取快照，区分并发繁忙、配额耗尽、输入拦截与考试禁用。

POST 流不会自动重放，避免重复生成和重复扣费。六种业务事件之外，异常使用 `error`，空闲流每 15 秒发送注释心跳。流开始前的错误显式使用 JSON 媒体类型，即使请求 `Accept: text/event-stream`，仍保留正确的 HTTP 状态码。

## 数据边界与页面兼容

新增题库 `/inner/problem/ai-rag/{id}`，独立返回题面、私有参考题解和公开样例，不复用含全部测试点的编辑响应。私有题解仅进入 Tutor 上下文，不出现在工作台初始化、通道列表或会话元数据里。

修正判题历史查询中 `assignmentId=0` 原先代表“不限活动”的问题。失败诊断的新摘要只保留状态、编号和输出类型；历史摘要里的隐藏 `expectedOut/actualOut` 也会在读取边界转换为类型，避免截断的短答案被完整交给模型。

经用户明确授权，工作台现有节点可增加 `x-text/x-show` 数据绑定；DOM 结构、class、样式保持不变。真实态不再展示固定的 AI 示例回复、假调用进度或固定推荐文案；`?mock=1` 保留原先首屏与离线演示。

## 运行与验收

先停止待打包服务，Windows 上运行中的 JAR 会阻止 Maven repackage 重命名：

```powershell
pwsh scripts/run-services.ps1 -Action stop -Only 'teaching,problem,judge,ai'
docker run --rm -v "${PWD}/backend:/w" -v "${env:USERPROFILE}/.m2:/root/.m2" -w /w maven:3.9-eclipse-temurin-17 mvn -B clean install
pwsh scripts/run-services.ps1 -Action start -Only 'gateway,teaching,problem,judge,ai'
```

本机 Python 不在当前会话 PATH 时，使用 `C:\Users\ZhuanZ.DESKTOP-PH97BKO\AppData\Local\Programs\Python\Python312\python.exe`。本地模型已有，无需重新下载。

```powershell
python scripts/verify-day8.py
node scripts/_probe_day8_frontend.mjs
python scripts/verify-day4.py
node scripts/_probe_workbench.mjs
node scripts/_probe_sse.mjs
node scripts/_probe_stage0.mjs
node scripts/_probe_realmode.mjs
node scripts/_probe_shots.mjs --out _shots/_day8_verify
```

`verify-day8.py` 使用真实网关、真实判题和真实 Ollama，覆盖失败后诊断、云通道降级、历史/反馈、输入拦截、满分复盘推荐和作业配额。有限配额测试创建独立临时活动，在 `finally` 里清理自己的活动、聊天与 Redis 数据。自由练习问答与判题结果保留，供工作台人工复查。报告写入忽略目录 `logs/day8-backend/report.json`；前端报告和截图位于 `logs/day8-frontend/`。

模型 SDK 0.35 的流接口不提供取消传输句柄；页面关闭会停止消费、释放预留并忽略迟到回调，已发出的模型 HTTP 请求由模型客户端超时或服务端完成后结束。云端已经输出正文再中断时返回错误，不把两家模型的半段回答拼接成一次成功回答。

本轮不包含第 5 步的活动 CRUD、考试监考或查重业务；仅补齐 AI 必需的权威活动权限读取。

---

## 复验修正（2026-10-27 晚）

第 4 步收尾复验抓出两个真缺陷，均已修复并纳入回归。

### 1. 验收脚本写死母题 ID（`#1024` 串题）

`scripts/verify-day8.py` 把 `problemId` 写死成 `1024`，并提交「最大子段和」的
Kadane 解法（含 `ans = 0` 缺陷）。但 Day 3 导入真实题库后，**本库 `#1024` 是
「最大异或和」**，最大子段和是 `#1001`——于是「真实修复提交满分 AC」必然拿不到
100 分，连带「阶段④解锁」「阶段④推荐工具」「阶段④复盘完成」一共 4 项失败。

这正是 `agent.md` §3.4.0 已经写明的坑（母题 ID 不许写死）。修法与前端
`resolveMotherPid()` 同口径：按题名 `kw=最大子段和` 反查一次真实 ID，
查不到直接报错退出，不静默退回假 ID。修后 **29/29 全通过**。

> 遗留教训：**凡是脚本里出现题目 ID，都要问一句「换了库还成立吗」**。
> 前端早已改成运行时反查，验收脚本当时漏了。

### 2. 难度标签多包了一层 `<span>`（破 DOM 红线）

工作台左栏的难度文字 `中等` 在基线里是 `.lvl` 的**裸文本节点**，后被改成
`<span x-text="problemLvlLabel">中等</span>`——**新增了一个元素节点**，违反
`agent.md` §3.1 红线 #1。§3.2 的窄范围例外只允许「给**现有**节点增加
`x-text`/`x-show`」，不允许新增节点。

视觉上只差 5 个像素（抗锯齿），肉眼几乎看不出，**但结构红线是硬门槛**。
用与 `syncProblemFoot()` 完全一致的既有写法修复：不加元素，由
`syncProblemFoot()` 直接写 `.lvl` 的首个文本节点。

修后新增 `scripts/_probe_dom_structure.mjs` 把红线变成可执行断言：
以 `git archive HEAD` 的净化为基线，渲染真实浏览器 DOM 树，逐节点比对
**标签序列 + class 集合 + 内联 style**，`s-workbench` 从 `+1` 节点回到
**615/615 完全一致**。

### 复验后的验收结果

| 验收 | 结果 |
| --- | --- |
| `python scripts/verify-day8.py` | **29/29**（原 25/29） |
| `node scripts/_probe_step4_acceptance.mjs` | **26/26**（新增，手册验收标志逐字复现） |
| `node scripts/_probe_dom_structure.mjs` | **4/4**（新增，DOM 红线） |
| `node scripts/_probe_day8_frontend.mjs` | 43/43 |
| `node scripts/_probe_shots.mjs` | 15/16 逐字节相同；唯一差异 `index` = 已登记的 PRD 文案修正（1595 px，bbox `270,375,444,389`） |
| 第 3 步回归 `verify-day4.py` | 77/77 |
| 第 3 步回归 `_probe_step3_acceptance.mjs` | 16/16 |
| `_probe_guard.mjs` / `_probe_realmode.mjs` | 14/14 / 15/15 |

**手册第 4 步验收标志逐字复现**（`_probe_step4_acceptance.mjs`）：
紧接真实 WA 状态 → 点阶段③ → 聊天框出现
`Router：排错诊断 · 填空伪代码 · qwen2.5-coder:7b` 与
`Tool：fetchFailedTestCaseTool · 已拉取首个失败测试点 #7 脱敏特征` →
真实本地模型流式吐字回「初始值 `dp` 和 `ans` 都是 0 … 全负数输入会怎样」→
受限配额场景服务端 `done.remainingQuota` 从 **3 扣到 2**（`3/3 → 2/3`）。

> 自由练习模式的配额是 `-1`（不限），前端显示「不限」且**不伪造扣减**；
> 手册写的 `3/3 → 2/3` 只在受限配额（作业模式）下成立，探针用真实临时作业复现，
> 并在 `finally` 清理自己的活动、聊天记录与 Redis 键。

### 并发跑探针的注意事项

`_probe_step4_acceptance.mjs`、`_probe_workbench_sameproblem.mjs` 等会**真调用本地模型
并真提交判题**。可以并行跑，但同一时刻并发数过高时本地 Ollama 可能排队超时；
若出现偶发失败，**先串行重跑一次**再判定是否真失败（本次复验就遇到过一次）。

