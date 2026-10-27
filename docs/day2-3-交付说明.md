# Day 2-3 交付说明 —— 前端 Stage 0 适配底座 + 后端 Maven 父子工程骨架

> 对应《从 0 到 1 的 6 步标准开发顺序与操作手册》**第 2 步 / 第 2～3 天**。
> 执行日期：2026-10-27　｜　验收脚本：`pwsh scripts/verify-day2.ps1`（当前 **50/50 全绿**）

---

## 1. 交付物清单

| 文件 | 作用 |
|---|---|
| [`prototype/app/api/`](../prototype/app/api/) | 请求层四件套：`http.js` / `sse.js` / `poll.js` / `mock.js`（+ `auth.js`） |
| [`prototype/app/adapter/`](../prototype/app/adapter/) | 字段适配层：7 张映射表 + `index.js` 聚合 |
| [`prototype/app/boot.js`](../prototype/app/boot.js) | 鉴权守卫 + 身份注入 + mock 跨页透传 + 卸载清理 |
| [`prototype/app/login.html`](../prototype/app/login.html) | 第 17 页：登录页（复用全部既有组件类） |
| [`backend/`](../backend/) | Maven 父子工程：父 POM + 8 模块 |
| [`agent.md`](../agent.md) | **AI 协作开发约束手册**（红线 / 环境事实 / 验收 checklist） |
| [`scripts/build-backend.ps1`](../scripts/build-backend.ps1) | 容器内 `mvn clean compile/package` |
| [`scripts/run-services.ps1`](../scripts/run-services.ps1) | 6 服务启停 + 端口 + Nacos 健康检查 |
| [`scripts/verify-day2.ps1`](../scripts/verify-day2.ps1) | 本日一键验收（49 项） |
| [`scripts/_probe_shots.mjs`](../scripts/_probe_shots.mjs) | 16 页视觉零回归探针 |
| [`scripts/_probe_guard.mjs`](../scripts/_probe_guard.mjs) | 登录守卫 14 项探针 |
| [`scripts/_probe_workbench.mjs`](../scripts/_probe_workbench.mjs) | 工作台 8 项探针 |
| [`scripts/_probe_sse.mjs`](../scripts/_probe_sse.mjs) | SSE 六事件 9 项探针 |

---

## 2. 前端 Stage 0：三层地基

```
页面 (x-data 组件)
   ↓ 只认 adapter 的输出（键名与改造前常量一字不差）
adapter/   ← 字段改名 + 单位换算，不做筛选/排序/发请求
   ↓
api/       ← http（信封/错误码/超时/重试）/ sse（六事件）/ poll（1s 自适应）
   ↓
网关 :8080
```

### 2.1 `api/http.js` —— 统一请求层

- `BASE = ''` 同源；直连网关时改 `'http://localhost:8080'`
- 自动带 `Authorization: Bearer <token>` 与 `X-Trace-Id`
- **9 条中文错误文案**与 `00-基线决策.md §4` 的业务码同值
- 401 → 清 token → 跳 `login.html?redirect=...`（防死循环）
- 15s 超时；GET 指数退避重试 2 次（400ms → 800ms）；**POST 默认 0 次重试**（防判题提交被重放成两次）
- `useMock()` 三个入口都认：`?mock=1` / `sessionStorage` / `localStorage`

### 2.2 `api/poll.js` —— 自适应轮询器

四条硬要求全部落地：**1s 一次**、指数退避（上限 5s）、**页面隐藏立即停表**、**终态自停**。
不用 `setInterval`：网络一慢会堆请求，退避要动态改间隔，后台标签页节流不可控会白烧配额。

401/403/404 视为 fatal 立即停（继续轮询没有意义）。

### 2.3 `adapter/` —— 7 张映射表

| 文件 | 映射 |
|---|---|
| `status.js` | 判题状态 int ↔ 前端短码（`3→ac`、`5→wa`…） |
| `difficulty.js` | 5 档难度 ↔ `lvl` 刻度 + 名称 |
| `language.js` | `cpp/java/python/c` ↔ 展示名 + 时空补偿倍率 |
| `time.js` | 毫秒时间戳 ↔ `MM-DD HH:mm`、剩余时间文案 |
| `unit.js` | 字节 → KB、KB → 展示、分值 → 百分比、TTFT 格式化 |
| `id.js` | `display_no` 优先、主键兜底（**主键不出现在 UI**） |
| `page.js` | 分页切片 + 分页器数组 |

**三约定**：① 只改名与换算；② 输出键名与改造前常量字段一字不差（`lang/pass/time/mem/at/first/left/due/ai/pidCount/done/score/full/lvl/tags/ac/tried` 全部保留）；③ 缺值一律 `null` 或 `'—'`，**绝不给 `undefined`**（Alpine 的 `x-text` 渲染 undefined 会变空串，看起来像加载失败）。

### 2.4 `shell.js` 的 8 组常量已迁出

`PROBLEMS` / `SUBMISSIONS` / `ASSIGNMENTS` / `STUDENTS` / `MODELS` / `AI_CALLS` / `SANDBOX` / `PLAGIARISM` 整块搬进 `api/mock.js`，**字面量一个不改**。
`shell.js` 的 8 个 `OJ.xxx` getter 改为指向 `OJMock.fixtures`，所以 16 个页面里 `OJ.problems` 这类读法**一行都不用改**。

`mock.js` 同时是：fixtures 唯一数据源 + 判题进度模拟器 + 87 端点路由表。

### 2.5 `boot.js` —— 守卫是「跳转」而不是「隐藏页面」

关键设计：**守卫只做跳转，不动 DOM**。这样 16 页的 DOM 结构、class、初始渲染时序全部不受影响——这是视觉零回归能成立的前提。

- `WHITE = ['login.html', 'index.html']` 免登录
- `?mock=1` 时自动发假 token，角色优先取**本页 `body[data-role]`**（不是全局默认值，否则从 `t-dashboard.html?mock=1` 打开会被踢回学生首页）
- `?mock=1` 写进 `sessionStorage`，跨页透传；站内 `<a>` 点击时捕获阶段补 `mock=1`
- 程序化跳转前掐掉 `@view-transition`，避免 Chrome 抛 `Transition was aborted because of invalid state`（一次跳转刷一条 pageerror，验收时看着像写崩了）

### 2.6 登录页 `login.html`（第 17 页）

完全复用既有 `.card / .field / .input / .btn / .note` 组件类，**不新增任何一条 CSS 规则**。
支持 `?redirect=` 回跳、三角色演示账号一键填充、错误态提示。

---

## 3. PRD v2.0 的 5 处微创修正：全部落地

| # | 文件 | 内容 | 状态 |
|---|---|---|---|
| 1 | `index.html:198` | 文案改「所有页面均可直接打开浏览；进入工作区需要登录。」 | ✅ |
| 2 | `s-exam.html` / `t-problem-edit.html` | `data-page` 修正为 `s-exam` / `t-problem-edit` | ✅ |
| 3 | `a-models.html:512` / `a-audit.html:374` | `:key` 从 `c.at` 改为 `c.recordId` | ✅ |
| 4 | `s-workbench.html` / `s-workbench.js` | `focusLine` → `focusCase`；删 `CASE_LINE`/`LINE_CASE`/`REPLIES`/`quotaLeft` 自减 | ✅ |
| 5 | 15 个业务页页尾 + `login.html` | 插入 `api/`/`adapter/`/`boot.js` 引用 | ✅ |

### 3.1 修正 #2 的副作用与解法

`data-page` 从 `s-assignments` 改成 `s-exam` 后，侧栏高亮会失配（导航项 id 还是 `s-assignments`）。
`shell.js` 里补了一张 `NAV_ALIAS` 别名表：

```js
var NAV_ALIAS = { 's-exam': 's-assignments', 't-problem-edit': 't-problems' };
```

**只影响高亮，不改 `data-page` 的值**——页面身份与导航归属本来就是两件事。

### 3.2 修正 #3：为什么必须换 key

同一分钟内可能有多条调用记录，`:key="c.at"` 会让 Alpine 撞 Key，复用 DOM 时把上一行的 `model`/`guard` 渲染到新行上。
`a-audit` 原本用 `c.at + c.who`，但「同一人同一分钟内多次调用」仍会重复。契约里调用记录有唯一主键，故 `mock.js` 给 `AI_CALLS` 补了 `recordId`（90001 起），两页 `:key` 都改用它。

### 3.3 修正 #4：删掉的四类假数据

这是本日**最重要的一次清理**，依据 `03-前端对接实施指南.md §5.1~§5.3`。

| 删掉的 | 为什么必须删 | 替代 |
|---|---|---|
| `CASE_LINE` / `LINE_CASE` | 真实判题只回状态/耗时/内存，**拿不到「第 8 行导致第 3 号测试点失败」**。要做这个需要逐行插桩或符号执行，是另一个课题。评审追问一句就穿帮 | 联动高亮统一走 `focusCase`（测试点维度，真实）；点测试点看输入/期望/实际三栏 diff |
| `REPLIES{1..9}` | 九条固定文本与用户输入**完全无关**——输入「为什么 TLE」和「你好」拿到同一段话。AI 对话是论文核心创新点，评审打两句话就穿帮 | `api.sse('/api/ai/chat/stream')` 六事件链（阶段 2 接）；Stage 0 用 `api.mockAiReply()` **按问题关键字**兜底 |
| `quotaLeft` 前端自减 | 配额是计费与公平性约束，**只能服务端裁决**。前端自减 = 改一行 JS 就无限用 | 进页面读 `workspace-init`；**只由 SSE `done` 的 `remainingQuota` 写入** |
| `submit()` 重置配额 | 「提交一次就白送一次 AI 机会」没有任何业务含义，纯粹为了让演示能反复跑 | `submit()` 完全不碰 `quotaLeft` |

顺带把 `x-text="quotaLeft+' / 3'"` 的硬编码分母改成 `quotaLeft + ' / ' + quotaMax`（仍是同一个 `<b>`），并给输入框/发送按钮加了 `quotaEmpty` 禁用态。

**验证**：`_probe_workbench.mjs` 实测「问 TLE」与「问边界」得到两段不同回复，且问两次后 `quotaLeft` 不变（前端不再自减）。

---

## 4. 后端 Maven 父子工程

### 4.1 版本矩阵（已锁）

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **17** | `maven.compiler.release=17` |
| Spring Boot | **3.2.5** | |
| Spring Cloud | **2023.0.1** | |
| Spring Cloud Alibaba | **2023.0.1.0** | 不用 2022.0.0.0——官方对应 Spring Cloud 2022，与 Boot 3.2 代际不匹配 |
| jjwt | 0.12.6 | |

**实测 `mvn clean compile` 与 `mvn -DskipTests package` 均 BUILD SUCCESS，无版本冲突。**

### 4.2 模块与职责

| 模块 | 端口 | 关键类 | 说明 |
|---|---:|---|---|
| `oj-common-core` | — | `Result<T>` / `PageResult<T>` / `ErrorCode` / `BusinessException` / `JwtUtil` / `RedisKeys` / `RoleConstants` / `TraceIdHolder` | 普通 jar，不 repackage |
| `oj-common-api` | — | 7 个 Feign 客户端（19 方法）+ 17 个 DTO + `InnerConstants` | 普通 jar，不 repackage |
| `oj-gateway` | 8080 | `GatewayApplication` / `AuthGlobalFilter` / `CorsConfig` / `GatewayAuthProperties` / `JwtConfig` | WebFlux |
| `oj-teaching-service` | 8081 | `TeachingApplication` + `HealthController` | Servlet |
| `oj-problem-service` | 8082 | `ProblemApplication` + `HealthController` | Servlet |
| `oj-judge-service` | 8083 | `JudgeApplication` + `HealthController` | Servlet |
| `oj-ai-agent-service` | 8084 | `AiAgentApplication` + `HealthController` | Servlet |
| `oj-admin-service` | 8085 | `AdminApplication` + `HealthController` | Servlet |

### 4.3 网关鉴权链路

`AuthGlobalFilter`，`Ordered.HIGHEST_PRECEDENCE + 10`（早于 `RouteToRequestUrlFilter(10000)`，未鉴权请求打不到下游）：

1. 生成/透传 `X-Trace-Id`，写入响应头
2. 命中白名单直放：`/api/auth/login`、`/api/auth/refresh`、`/actuator/**`、`/api/*/health`
3. 取 `Authorization: Bearer <token>`，缺失或格式错 → 401
4. 校验 JWT 签名与 `exp` → 失败 401
5. 校验 Redis 在线态 `RedisKeys.authToken(uid)`（**Day 2 默认关闭**，第 4 步认证接口上线后置 `true`）
6. 注入 `X-User-Id` / `X-User-Role` / `X-Class-Id` 给下游做资源归属校验

### 4.4 编译与启动

```powershell
pwsh scripts/build-backend.ps1              # 容器内 mvn clean compile
pwsh scripts/build-backend.ps1 -Package     # 产出 6 个 fat jar
pwsh scripts/run-services.ps1 -Action start # 启动 6 服务并等 Nacos 注册
pwsh scripts/run-services.ps1 -Action status
pwsh scripts/run-services.ps1 -Action stop
```

**内存预算**：统一 `-Xms128m -Xmx256m -XX:MetaspaceSize=64m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC`。
6 个全开常驻约 **1.2 GB**（实测单进程 190~240 MB）。不配默认会吃 6~8 GB，16 GB 机器直接卡死。

---

## 5. 验收结果

```
$ pwsh scripts/verify-day2.ps1
结果：50/50 通过
```

| 分组 | 项数 | 结果 |
|---|---:|---|
| 前端 · 文件齐备性（api 4 + adapter 8 + boot + login） | 14 | ✅ |
| 前端 · 16 页脚本挂载与顺序 | 2 | ✅ |
| 前端 · PRD 5 处微创修正 | 9 | ✅ |
| 前端 · shell.js 常量迁出 | 2 | ✅ |
| 前端 · JS 语法（16 文件） | 1 | ✅ |
| 前端 · 视觉零回归 / 守卫 14 项 / 工作台 8 项 / SSE 六事件 9 项 | 4 | ✅ |
| 后端 · Maven 结构与 6 fat jar | 3 | ✅ |
| 后端 · 5 容器 running | 1 | ✅ |
| 后端 · 6 端口监听 + Nacos 6 服务 healthy | 3 | ✅ |
| 后端 · 网关 5 条路由 + 鉴权 4 项 | 9 | ✅ |

### 5.1 视觉零回归实测

```
$ node scripts/_probe_shots.mjs --out _shots/_day2_verify
页面 16 个，截图逐字节相同 15 个，不同 1 个
有 console/pageerror/请求失败的页面：0 个
  [像素不同] index: now=162918B/eb5748f264e8a098 base=162179B/642c0973a28b29d1
```

`index.html` 的唯一差异经像素级定位：`bbox=(270,375,444,389)`，1595 / 1,296,000 像素，**正好是微创修正 #1 那句文案**（已用裁剪图人工核对「所有页面均可直接打开、交互可用，不需要登录。」→「所有页面均可直接打开浏览；进入工作区需要登录。」）。**除此之外 16 页逐字节相同。**

#### 基线是怎么来的（重要，别用错基线）

`_day2_baseline/` 是**由 Day 1 提交 `822b5f1`（Stage 0 改造前）的 `git archive` 检出，在本机用同一份探针脚本渲染产生**的权威快照。
仓库里 `_shots/final/` 那套是另一台机器的字体栈渲染的，本机无法逐字节复现——**比对一律用 `_day2_baseline/`**。

重建基线的完整命令写在 `scripts/_probe_shots.mjs` 文件头。

#### 为什么探针要注入 `animation:none`

工作台有 4 处 `animation: ... infinite`（`.think-dot` 的 pulse 2s、`.msg.typing span` 的 blink 1.2s、判分格 `cell-idle` 1.6s 等）。
`reducedMotion:'reduce'` 只能压掉一次性过渡，压不掉 infinite；`animation-play-state:paused` 也只是「停在当前相位」，相位仍随截图时刻变。

**实测**：同一份代码连跑两次，`s-workbench` 偶发 `12×12` 区域 119 像素差异（对应 `.think-dot` 那个脉冲圆点）。

解法是注入 `animation:none`，让元素回落到基础样式。该注入**只存在于探针的页面上下文，落盘的 HTML/CSS 一个字节都没动**；基线与被测两侧用同一套设置，口径一致。

**确定性验证**：连续跑两次，16/16 页 PNG 的 SHA256 完全相同。

### 5.2 登录守卫实测（14/14）

| 场景 | 结果 |
|---|---|
| 无 token 开 `s-problems` / `t-dashboard` / `s-workbench` | → `login.html?redirect=<原页>` ✅ |
| `?mock=1` 开 6 个代表页 | 正常挂载、自动发假 token、角色取本页 `data-role` ✅ |
| 有 token 开业务页 | 正常渲染 ✅ |
| `index.html`（白名单） | 不被踢走 ✅ |
| 站内链接点击瞬间补 `mock=1` | `s-assignments.html` → `s-assignments.html?mock=1` ✅ |
| `sessionStorage` 跨页透传 | 去掉 `?mock=1` 直接开另一页仍是 mock 模式 ✅ |

### 5.3 SSE 六事件实测（9/9）

PRD 模块二 AC-2.1 要求「6 种事件在工作台均有对应 UI 表现」。Stage 0 阶段工作台还没接真实 SSE，但 `api/sse.js` 的 MOCK 分支必须先把六事件跑通——否则断网演示里**降级黄条与护栏折叠这两块永远看不到**，而它们正是论文的核心创新点。

| 路径 | 触发条件 | 事件序列 |
|---|---|---|
| 正常 | 普通提问 | `router_step → tool_calling → token×3 → done` |
| 降级 | 提问含「云端 / 降级 / 超时」 | `router_step → tool_calling → fallback_notice → token×3 → done` |
| 护栏 | 提问含「代码 / 完整 / 给我 / 题解」 | `router_step → tool_calling → token(超长源码) → guardrail_rewrite → done` |

关键校验：
- `done` 携带 `remainingQuota`（**唯一配额写入口**，与 §3.3 的配额规则一致）
- `fallback_notice` 指明 `toChannel=local-qwen`、`elapsedMs=8100`（对应 PRD 的 8s 降级）
- `guardrail_rewrite` 标注 `guardAction=2`、`guardReason=OVER_LENGTH_CODE`

### 5.4 后端实测

```
Nacos count=6  [oj-gateway, oj-teaching-service, oj-problem-service,
                oj-judge-service, oj-ai-agent-service, oj-admin-service]
6 个实例 healthy=True

curl /api/teaching/health  → {"code":200,"data":"oj-teaching-service UP","traceId":"..."}
curl /api/teaching/students（无 token）→ HTTP 401
    {"code":401,"message":"未登录或 Token 失效","data":null,"traceId":"...","success":false}
curl /api/problem/health（白名单）→ HTTP 200
响应头 X-Trace-Id: 9192c68dd7eb
```

---

## 6. 常用命令

```powershell
# ── 基础设施 ──
docker compose up -d
docker compose ps

# ── 后端 ──
pwsh scripts/build-backend.ps1 -Package
pwsh scripts/run-services.ps1 -Action start
pwsh scripts/run-services.ps1 -Action status

# ── 前端（零构建，直接起静态服务器）──
cd prototype\app
python -m http.server 3000
# http://127.0.0.1:3000/index.html          ← 门户
# http://127.0.0.1:3000/s-problems.html?mock=1   ← 断网演示
# http://127.0.0.1:3000/login.html          ← 登录页

# ── 一键验收 ──
pwsh scripts/verify-day2.ps1
pwsh scripts/verify-day2.ps1 -SkipVisual    # 跳过截图比对（快 60s）
```

---

## 7. 已知边界

1. **`s-workbench.html` 的脚本位置是特例**。它的 `s-workbench.js` 与 `alpine.min.js` 都在 `<head>` 且带 `defer`，所以 Stage 0 脚本放在 `</body>` 前**不带 defer**——普通脚本解析期立即执行，一定早于 head 里的 defer 脚本，时序等价于其它 15 页。
2. **`s-workbench.html` 的 `body` 没有 `data-role`**。这是原型的既有设计（它不引 `shell.js`，是独立的全屏工作台），不是缺陷。`boot.js` 对它只做 token 守卫，不做角色纠偏。
3. **`index.html` 是唯一无 Alpine 的静态门户**（`03 指南 §4`：全文 `<script` 命中 0）。视觉基线比对时它的差异来自微创修正 #1，属预期。
4. **网关 `redis-check.enabled` 默认 `false`**。第 2 步服务尚未实现登录，Redis 里没有 `oj:auth:token:*`，开启会让所有验收请求 401。**第 4 步认证接口上线后必须置 `true`**。
5. **5 个业务服务目前只有 `HealthController`**，没有 DAO 层。`application.yml` 里**不配 `spring.datasource.url`**，否则 HikariCP 会立即尝试建连并报错。
6. **`oj-judge-service` 只装 RabbitMQ 连接，不声明队列**。队列/交换机留到第 3 步判题实现（避免消费者未就绪时消息堆积）。
7. **`LangChain4j` 未引入实际依赖**。父 POM 的 `dependencyManagement` 里已注释占位——它各版本对 jackson/okhttp/reactor 的传递依赖较杂，过早引入会与 Boot 3.2 BOM 冲突并显著拖慢首次构建。第 4 步开工时在此统一锁版本。
8. **`api/mockAiReply()` 是 Stage 0 的临时兜底**。阶段 2 接真实 SSE 后应整体删除。它按问题关键字匹配（tle/边界/思路/复杂度/题解…），不会出现「两问一答」，但**它不是 AI**，答辩时不要拿它演示。
9. **`_probe_*.mjs` 是临时探针**，依赖 Playwright。它们不进生产，只用于验收。
10. **视觉基线 `_day2_baseline/` 入库**，是「改造前」的权威快照（由干净的 `git archive HEAD` 检出在本机渲染产生）。仓库里的 `_shots/final/` 是另一台机器渲染的，本机无法逐字节复现——**比对一律用 `_day2_baseline/`**。

---

## 8. 下一步（Day 4，第 3 步起点）

按手册进入 **第 3 步【第一纵深 P0-A】**：打通「登录 → 题库 → 异步判题闭环」（Day 4～7）。

**Day 4 的两件事：**

1. **`oj-teaching-service` 实现 `#1`~`#5`**：`login` / `logout` / `refresh` / `me` / `menus`
   - 双 Token（access + refresh），BCrypt 校验 `sys_user.password`
   - 登录成功写 Redis `oj:auth:token:{uid}`，网关 `redis-check.enabled` 同时置 `true`
   - `menus` 按角色返回动态菜单（学生/教师/管理员三套）

2. **`oj-problem-service` 实现 `#6`/`#7`**：`GET /api/problem/questions`（分页 + 5 档难度筛选）、`GET /api/problem/questions/{id}`（详情 + 三语言代码骨架）
   - 接 MyBatis-Plus，读 `prob_question` / `prob_test_case` / `prob_knowledge_point`
   - 内部 Feign 接口按 `ProblemInnerFeignClient` 已定契约实现

**前端同步**：`login.html`、`shell.js`、`s-problems.html` 去掉 `?mock=1`，实现真登录、真题库列表展示。

> 环境提示：本机 JDK 17 在 `D:\Java\jdk-17`（`JAVA_HOME` 已设），**没有 Maven**——编译一律走 `scripts/build-backend.ps1`（容器内 `maven:3.9-eclipse-temurin-17`）。6 服务启动用 `scripts/run-services.ps1`，已内置 Nacos 注册检查。
