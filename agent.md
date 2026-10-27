# agent.md —— 本项目 AI 协作开发的约束与操作手册

> 本文件是**所有 AI Agent（以及人类协作者）在本仓库内做任何改动前必须读完的唯一约束文件**。
> 它不重复 PRD 的业务需求，只回答三件事：**改什么、怎么改、怎么证明没改坏**。
>
> 维护规则：本文件随项目阶段演进更新，但**「红线」章节只能在明确评审后放宽**。
> 最后更新：Day 2（2026-10-27）

---

## 0. 三十秒速览

| 问题 | 答案 |
| --- | --- |
| 这个项目是什么 | 毕业设计：AI Agent + 微服务架构的启发式在线判题系统（1 网关 + 5 服务 + 87 端点 + 18 张表 + 17 页） |
| 当前进度 | Day 1 基础设施完成、Day 2 前后端地基完成（见 `docs/` 下的交付说明） |
| 前端技术栈 | **零构建**多页应用：原生 HTML + Alpine.js + 手写 CSS Token 体系。**没有 npm、没有 Vite、没有打包** |
| 后端技术栈 | JDK 17 + Spring Boot 3.2.5 + Spring Cloud Alibaba 2023.0.1.0 |
| 宿主机有什么 | 有 Docker、JDK 17（`D:\Java\jdk-17`）、Python 3.12、Node 24。**没有 Maven**（走容器编译） |
| 最重要的红线 | 17 页原型的 **DOM 结构与 class 名一行都不能动**（视觉零回归是硬门槛） |
| 验证怎么跑 | `pwsh scripts/verify-day2.ps1`（前端 + 后端一键全验，50 项） |

---

## 1. 目录地图与「谁能改什么」

```
D:\毕设\agent oj\
├── agent.md                      ← 本文件（约束）
├── README.md                     项目总览
├── docker-compose.yml            5 个基础容器（MySQL/Redis/RabbitMQ/Nacos/go-judge）
│
├── docker/
│   ├── mysql/init/init_oj_cloud.sql   18 张表 DDL + 种子数据（只首启执行）
│   └── judge/                    go-judge 派生镜像 + 三语言冒烟脚本
│
├── data/                         ⛔ 运行期数据卷（gitignore，可随时删重建）
│
├── backend/                      ⭐ Maven 多模块（详见 §4）
│   ├── pom.xml                   父 POM：统一锁版本
│   ├── oj-common-core/           Result / ErrorCode / BusinessException / JwtUtil / RedisKeys
│   ├── oj-common-api/            7 个 OpenFeign 客户端 + 17 个共享 DTO
│   ├── oj-gateway/               :8080 路由 + AuthGlobalFilter
│   ├── oj-teaching-service/      :8081 认证 + 教务 + 查重
│   ├── oj-problem-service/       :8082 题库 + 测试点
│   ├── oj-judge-service/         :8083 异步判题 + Go-Judge 适配
│   ├── oj-ai-agent-service/      :8084 端云模型工厂 + SSE
│   └── oj-admin-service/         :8085 通道探活 + 审计 + 沙箱监控
│
├── prototype/
│   ├── app/                      ⭐ 全站原型（17 页）
│   │   ├── index.html            门户（唯一无 Alpine 的静态页）
│   │   ├── login.html            第 17 页（新增，复用既有组件类）
│   │   ├── s-*.html t-*.html a-*.html   16 个业务页
│   │   ├── shell.js              🔒 外壳：图标/导航/侧栏/工具函数（只读）
│   │   ├── s-workbench.js        工作台交互（可改，但受 §3.4 约束）
│   │   ├── _SPEC.md              🔒 设计硬规则（只读）
│   │   ├── styles/               🔒 tokens / components / shell / pages（只读）
│   │   ├── vendor/alpine.min.js  🔒 Alpine 3.x（只读）
│   │   ├── api/                  ⭐ Stage 0 请求层（http/sse/poll/mock/auth）
│   │   ├── adapter/              ⭐ Stage 0 字段适配层（7 张映射表 + index.js）
│   │   └── boot.js               ⭐ 鉴权守卫 + 身份注入 + mock 跨页透传
│   ├── workbench/ explore/ research/   设计探索与竞品参考（一般不碰）
│
├── docs/
│   ├── day1-交付说明.md           Day 1 验收记录
│   ├── day2-3-交付说明.md         Day 2-3 验收记录
│   └── analysis/                 契约补全与对接指南（**改动前必读**）
│       ├── 00-基线决策.md        字典统一 / 红线 / 缺口判定口径
│       ├── 02-契约补全规范-v2.0.md   87 端点逐条契约（120 KB，查字段用它）
│       ├── 03-前端对接实施指南.md   前端逐页改造方案（**前端改动前必读**）
│       └── 04- 终极完整版设计文档.md  17 张表 + 类结构 + 算法
│
├── scripts/
│   ├── verify-day1.py            Day 1 验收（31 项）
│   ├── verify-day2.ps1           Day 2-3 一键验收（前后端全验）
│   ├── build-backend.ps1         容器内 mvn 编译/打包
│   ├── run-services.ps1          启停 6 个服务（含 Nacos 注册检查）
│   └── _probe_*.mjs              视觉/守卫/工作台探针（临时工具）
│
└── logs/                         ⛔ 服务运行日志（gitignore）
```

### 1.1 写入权限矩阵

| 路径 | 允许改 | 约束 |
| --- | --- | --- |
| `backend/**` | ✅ | 必须能 `mvn clean compile` 通过 |
| `prototype/app/api/**` `adapter/**` `boot.js` `login.html` | ✅ | 遵守 §3.2 / §3.3 |
| `prototype/app/s-workbench.js` | ✅ | 受 §3.4 约束（禁造假数据） |
| `prototype/app/*.html`（16 个业务页） | ⚠️ **仅限页尾 `<script src>` 与 PRD 5 处微创修正** | 禁改 DOM/class/内联样式 |
| `prototype/app/shell.js` | ⚠️ 仅限「删假数据、读登录态」 | 禁改 `buildSide` 的 DOM 模板结构 |
| `prototype/app/styles/**` `_SPEC.md` `vendor/**` | ⛔ 只读 | 需设计评审才可动 |
| `docker/**` `docker-compose.yml` | ⚠️ 谨慎 | 改 SQL 要删 `data/mysql` 重建 |
| `docs/**` | ✅ | 交付说明随代码同步更新 |
| `data/**` `logs/**` `*/target/**` `node_modules/**` | ⛔ | 已 gitignore，不要提交 |

---

## 2. 环境事实（不要猜，直接用）

### 2.1 本机路径与工具

| 项 | 值 | 备注 |
| --- | --- | --- |
| 工作目录 | `D:\毕设\agent oj` | **含中文与空格**，所有脚本必须给路径加引号 |
| JDK | `D:\Java\jdk-17`（`JAVA_HOME` 已设） | 宿主机**有** JDK，**没有** Maven |
| Maven | 容器 `maven:3.9-eclipse-temurin-17` | 见 §4.2 |
| Docker Desktop | `D:\Docker_Desktop` | 已装，5 容器 healthy |
| Ollama 模型目录 | `D:\ollma-models`（`OLLAMA_MODELS`） | 不污染 C 盘 |
| Python | 3.12.10 | 验收脚本用它 |
| Node | 24.18.0 | 探针脚本用它（Playwright 已装） |

### 2.2 本地模型：显存预算（已实测，不要重测）

机器 **RTX 4060 Laptop / 8 GB 显存 + 16 GB 内存**。

| 模型 | 上下文 | 实测显存 | 结论 |
| --- | ---: | ---: | --- |
| `qwen2.5-coder:7b` | 32768 | 7160 MiB | ❌ 顶满显存并溢出 CPU，与 5 容器同机有 OOM 风险 |
| `qwen2.5-coder:7b` | **8192** | **5960 MiB** | ✅ **主模型**：100% GPU，留 2.2 GB 余量 |
| `qwen2.5-coder:1.5b` | 32768 | 约 2.1 GB | ✅ 降级备份 / 快速冒烟 |

**铁律：本地模型一律 `num_ctx=8192`。** 不要用默认 32768 拉起 7b——会直接吃满 8 GB 显存。

### 2.3 端口分配（固定，不要改）

| 端口 | 服务 | 端口 | 服务 |
| ---: | --- | ---: | --- |
| 8080 | oj-gateway | 8848 / 9848 | Nacos（**9848 必须映射**，gRPC） |
| 8081 | oj-teaching-service | 3306 | MySQL 8.0 |
| 8082 | oj-problem-service | 6379 | Redis 7 |
| 8083 | oj-judge-service | 5672 / 15672 | RabbitMQ |
| 8084 | oj-ai-agent-service | 5050 | go-judge 沙箱 |
| 8085 | oj-admin-service | 11434 | Ollama |

### 2.4 国内镜像源（下载慢时优先换，不要干等）

| 场景 | 镜像 |
| --- | --- |
| Docker 镜像 | `docker.m.daocloud.io/` 或 `docker.1ms.run/` 前缀 |
| Maven 依赖 | `https://maven.aliyun.com/repository/public` |
| apt（容器内） | 阿里云源；**注意本机实测该源有证书校验失败**，失败时改回 `deb.debian.org` |
| npm | `https://registry.npmmirror.com` |

---

## 3. 前端铁律（违反即返工）

### 3.1 五条绝对红线

1. **不改 DOM 结构与 class 名。** 17 页的视觉已在第五轮整改验收（commit `8ec9fda`）。
   唯一允许新增的 HTML 是**页尾的 `<script src>`**，以及 `x-data` 对象内部新增属性。
   *新增任何 `<div>`、任何新 class、任何 `<style>` 规则都算破红线。*
2. **不改 `styles/**` 与 `_SPEC.md`。** 颜色只取 token，6 级字号台阶，强调色 ≤10%，四态（loading/err/empty/有数据）齐全，**全站文案不含 Emoji**。
3. **不引第三方库、不引构建工具。** 没有 npm / Vite / webpack。新增能力要么手写 ES5/ES6，要么用已在本地的 `vendor/alpine.min.js`。
4. **不造假数据。** 见 §3.4——任何「看起来有、实际没有」的交互都必须删除或改为真实数据源。
5. **`?mock=1` 必须始终可用。** 断网演示是答辩底线，任何改动后 17 页都要能在 `?mock=1` 下完整交互。

### 3.2 前端分层：请求层 / 适配层 / 页面

```
页面 (x-data 组件)
   ↓ 只认 adapter 的输出（键名与改造前常量一字不差）
adapter/  ← 字段改名 + 单位换算，不做筛选、不做排序、不发请求
   ↓
api/      ← http（统一信封/错误码/超时/重试）/ sse（六事件）/ poll（1s 自适应）
   ↓
网关 :8080
```

- **`api/http.js` 是唯一出口。** 页面禁止直接 `fetch`，禁止自己拼 `Authorization`。
- **`adapter/` 三约定**（`adapter/index.js` 文件头）：① 只改名与换算；② 输出键名必须与改造前常量字段一字不差（`lang/pass/time/mem/at/first/left/due/ai/pidCount/done/score/full/lvl/tags/ac/tried` 全部保留）；③ 缺值一律给 `null` 或 `'—'`，**绝不给 `undefined`**（Alpine 的 `x-text` 渲染 undefined 会变成空串，看起来像加载失败）。
- **`shell.js` 的 8 个 `OJ.xxx` getter 已指向 `api/mock.js` 的 fixtures。** 页面里 `OJ.problems` 这类读法一行都不用改。

### 3.3 页面脚本引用顺序（固定，勿调）

```html
<script src="shell.js"></script>          <!-- 必须同步、无 defer -->
<script src="api/http.js"></script>
<script src="api/sse.js"></script>
<script src="api/poll.js"></script>
<script src="adapter/status.js"></script>
<script src="adapter/difficulty.js"></script>
<script src="adapter/language.js"></script>
<script src="adapter/time.js"></script>
<script src="adapter/unit.js"></script>
<script src="adapter/id.js"></script>
<script src="adapter/page.js"></script>
<script src="adapter/index.js"></script>   <!-- 必须最后：聚合上面 7 个 -->
<script src="api/mock.js"></script>        <!-- 依赖 adapter -->
<script src="boot.js"></script>            <!-- 要读 document.body，只能放页尾 -->
<script src="vendor/alpine.min.js" defer></script>
<script>/* 页面自己的 x-data 组件 */</script>
```

> `s-workbench.html` 是唯一例外：它的 `s-workbench.js` 与 `alpine.min.js` 都在 `<head>` 且带 `defer`，所以 Stage 0 的脚本放在 `</body>` 前**不带 defer**——普通脚本在解析期立即执行，一定早于 head 里那两个 defer 脚本，时序等价。

### 3.4 禁止的四类假数据（已在 Day 2 清理，不要写回来）

| 曾经的写法 | 为什么必须删 | 正确做法 |
| --- | --- | --- |
| `CASE_LINE` / `LINE_CASE`：测试点编号 ↔ 代码行号映射 | 真实判题只回状态/耗时/内存，**拿不到「第 8 行导致第 3 号测试点失败」**。要做这个需要逐行插桩或符号执行，是另一个课题 | 测试点维度联动用 `focusCase`；点测试点看输入/期望/实际三栏 diff |
| `REPLIES{1..9}`：9 条固定 AI 回复 | 与用户输入**完全无关**，输入「为什么 TLE」和「你好」得到同一段话。评审打两句话就穿帮 | `api.sse('/api/ai/chat/stream', ...)` 六事件链；Stage 0 用 `api.mockAiReply()` 按关键字兜底 |
| `quotaLeft` 前端自减 + `submit()` 重置 | 配额是计费与公平性约束，**只能服务端裁决**。前端自减 = 改一行 JS 就无限用；提交重置 = 白送次数 | 进页面读 `workspace-init`；**只由 SSE `done` 事件的 `remainingQuota` 写入**；429 置 0 并禁用输入框；`submit()` 不碰配额 |
| `setTimeout` 假判题 / 假运行 | 掩盖了轮询、异步队列、终态判断三个真实工程问题 | `api/poll.js` 1s 自适应轮询；`api/http.js` POST 默认不重试（防判题重放） |

### 3.4.1 MOCK 的 SSE 必须吐全六事件

`api/mock.js` 的 `/api/ai/chat/stream` **不能只走「正常路径」**。只吐 4 个事件的话，断网演示里**降级黄条与护栏折叠永远看不到**——而这两块正是论文的核心创新点，评审没法验证就等于没有。

三条分支必须都能触发（已由 `scripts/_probe_sse.mjs` 9 项守住）：

| 路径 | 触发条件 | 事件序列 |
| --- | --- | --- |
| 正常 | 普通提问 | `router_step → tool_calling → token×3 → done` |
| 降级 | 提问含「云端 / 降级 / 超时」 | 正常路径 + `fallback_notice`（`toChannel=local-qwen`, `elapsedMs=8100`） |
| 护栏 | 提问含「代码 / 完整 / 给我 / 题解」 | 正常路径 + 超长 `token` + `guardrail_rewrite`（`guardAction=2`, `OVER_LENGTH_CODE`） |

三条分支都必须以 `done` 收尾——**`done` 是配额的唯一写入口**。

### 3.5 PRD v2.0 的 5 处微创修正（必须保持，回归时逐条查）

| # | 文件 | 内容 | 状态 |
| --- | --- | --- | --- |
| 1 | `index.html` | 文案改「所有页面均可直接打开浏览；进入工作区需要登录。」 | ✅ Day 2 |
| 2 | `s-exam.html` / `t-problem-edit.html` | `data-page` 从 `s-assignments`/`t-problems` 改为 `s-exam`/`t-problem-edit` | ✅ Day 2 |
| 3 | `a-models.html` / `a-audit.html` | `:key="c.at"` → `:key="c.recordId"`（防 Alpine 撞 Key） | ✅ Day 2 |
| 4 | `s-workbench.html` / `s-workbench.js` | `is-focus:focusLine===N` → `focusCase===N`；删 `CASE_LINE`/`REPLIES`/`quotaLeft` 自减 | ✅ Day 2 |
| 5 | 16 个业务页页尾 + `login.html` | 插入 `api/`、`adapter/`、`boot.js` 引用；新增登录页 | ✅ Day 2 |

> 注意 #2 带来的副作用：`data-page` 改成独立值后，侧栏高亮会失配。`shell.js` 里已加 `NAV_ALIAS` 别名表（`s-exam → s-assignments`、`t-problem-edit → t-problems`）只影响高亮，不改 `data-page` 的值。

### 3.6 零回归怎么证明

```powershell
# 16 页在 ?mock=1 下逐页截图，与基线逐字节比对 + 收集 console error
node scripts/_probe_shots.mjs --out _shots/_day2_verify
```

**通过标准**：16 页中除 `index.html`（#1 文案修正）外全部逐字节相同，且 0 个 console/pageerror/请求失败。
`index.html` 的差异必须只落在文案那一小块矩形内（Day 2 实测 `bbox=(270,375,444,389)`，1595 像素）。

**基线口径（容易踩坑）：**
- 权威基线是 `_day2_baseline/`，由 **Day 1 提交 `822b5f1`（Stage 0 改造前）** 的 `git archive` 检出在本机渲染产生。**不要用 `_shots/final/`**——那是另一台机器的字体栈，本机无法逐字节复现。
- 探针会往页面注入 `animation:none`。工作台有 4 处 `animation: ... infinite`，`reducedMotion:'reduce'` 压不掉，`animation-play-state:paused` 也只是停在随机相位。实测同一份代码连跑两次 `s-workbench` 会偶发 12×12 区域 119 像素差异。注入只存在于探针上下文，**落盘文件不动**；基线与被测两侧同口径。
- **改了渲染口径就必须重建基线**，命令在 `scripts/_probe_shots.mjs` 文件头。重建后连跑两次必须 16/16 相同，否则说明还有不定态。

---

## 4. 后端铁律

### 4.1 版本矩阵（已锁，不要升）

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **17** | `maven.compiler.release=17` |
| Spring Boot | **3.2.5** | |
| Spring Cloud | **2023.0.1** | |
| Spring Cloud Alibaba | **2023.0.1.0** | 不要用 2022.0.0.0——与 Boot 3.2 代际不匹配 |
| jjwt | 0.12.6 | |
| MyBatis-Plus | 3.5.7 | 占位，`mybatis-plus-spring-boot3-starter`（Boot 3 专用变体） |

### 4.2 编译与打包（宿主机没有 Maven，一律走容器）

```powershell
# 编译
docker run --rm -v "D:\毕设\agent oj\backend:/w" -v "$env:USERPROFILE\.m2:/root/.m2" `
  -w /w maven:3.9-eclipse-temurin-17 mvn -B clean compile

# 打包
docker run --rm -v "D:\毕设\agent oj\backend:/w" -v "$env:USERPROFILE\.m2:/root/.m2" `
  -w /w maven:3.9-eclipse-temurin-17 mvn -B -DskipTests package
```

或直接 `pwsh scripts/build-backend.ps1`。首次约 3 分钟（下 BOM），之后有 `.m2` 缓存约 30 秒。

### 4.3 启动（宿主机 JVM，省内存）

```powershell
pwsh scripts/run-services.ps1 -Action start     # 启动 6 个服务并等 Nacos 注册
pwsh scripts/run-services.ps1 -Action status    # 看端口 + Nacos 健康
pwsh scripts/run-services.ps1 -Action stop      # 全停
pwsh scripts/run-services.ps1 -Action start -Only "gateway,teaching"   # 按需启
```

**内存预算**：每个服务统一 `-Xms128m -Xmx256m -XX:MetaspaceSize=64m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC`。
6 个全开常驻约 **1.2 GB**（不配默认会吃 6~8 GB，16 GB 机器直接卡死）。

**按需启动（省内存核心技巧）**：
- 写判题链路时只开 `gateway + teaching + problem + judge`
- 写 AI 时再开 `ai`
- `admin` 平时完全不用开，等做管理端再启

### 4.4 后端约定

- **统一响应 `Result<T>`**：`{code, message, data, traceId, timestamp, success}`，`code` 与 HTTP 状态码严格同值，`success` 是 `code==200` 的派生字段。
- **错误码**：`ErrorCode` 实现 10 个常量（含 `200 SUCCESS`）。文档称「9 大」，但表格实际列 10 行，按表格逐字实现。
- **网关不做 `StripPrefix`**：5 个服务的 Controller 自带完整前缀（如 `@RequestMapping("/api/teaching")`），网关原样透传。
- **`discovery.locator.enabled=false`**：全站只认手写的 6 个前缀，不出现 `/oj-teaching-service/**` 第二套路径。
- **Redis Key 禁止手写字符串**：一律走 `RedisKeys` 的静态方法。
- **Feign 必须给 `contextId`**：同名服务（`oj-problem-service` / `oj-teaching-service` 各被两个接口引用）不给 `contextId` 会 Bean 名冲突启动失败。
- **`username`/`password` 默认留空**：本机 Nacos 2.2.3 standalone 关闭鉴权，带凭据会反复请求 `/v1/auth/users/login` 拿 500，日志每 5s 刷 ERROR。

### 4.5 已知坑（都已踩过，别再踩）

| 坑 | 现象 | 正确做法 |
| --- | --- | --- |
| Nacos 只映射 8848 | 服务能注册但客户端永远连不上 | **必须同时映射 9848**（gRPC = 8848+1000） |
| go-judge 没开 privileged | `create environment builder failed: cgroup path is empty` 直接退出 | `privileged: true` + `cgroup: host` |
| go-judge 限值写在请求顶层 | `clone3: resource temporarily unavailable`（看起来像宿主机资源不足） | `cpuLimit/clockLimit/memoryLimit/procLimit/stackLimit` **必须写在每个 `cmd` 对象内部** |
| `Start-Process -ArgumentList` 传含空格路径 | `Unable to access jarfile D:\毕设\agent` | 数组里的路径要**自己裹双引号**：`"`"$jar`""` |
| 改 init SQL 后重启容器 | 表结构没变 | init SQL **只在首次初始化数据卷时执行**，必须删 `data/mysql` 重建 |
| `prob_test_case` 重跑 | 30 行重建、自增 id 变化 | 唯一键含可空列 `deleted_at`（MySQL 里 NULL 不参与判重），脚本用「先 DELETE 旧行再 INSERT」 |

---

## 5. 数据与契约

### 5.1 18 张表归属

| 服务 | 表数 | 表名 |
| --- | ---: | --- |
| `oj-teaching-service` | 9 | `sys_user` `teach_class` `teach_assignment` `assignment_submission` `teach_student_score` `exam_monitor_event` `teach_plagiarism_task` `teach_plagiarism_report` `teach_plagiarism_segment` |
| `oj-problem-service` | 3 | `prob_knowledge_point`（字典） `prob_question` `prob_test_case` |
| `oj-judge-service` | 2 | `judge_submission` `judge_sandbox_instance` |
| `oj-ai-agent-service` | 4 | `ai_model_config` `ai_model_role` `ai_chat_record` `ai_call_feedback` |

### 5.2 七类字典（核心，改字段前必看 `00-基线决策.md §5`）

- **判题状态**：`0 QUEUED / 1 COMPILING / 2 JUDGING / 3 AC / 4 PAC / 5 WA / 6 TLE / 7 MLE / 8 RE / 9 CE`（**int 为真值**）
- **难度**：`1 入门 / 2 简单 / 3 中等 / 4 较难 / 5 困难`（v1.0 是 3 档，v2.0 扩为 5 档）
- **语言**：`c` / `cpp` / `java` / `python`
- **时间**：一律 `Long` 毫秒时间戳；时长一律 `xxxMs`
- **内存**：`memoryUsedKb`（KB）；Go-Judge 返回字节 → `Math.round(rawBytes / 1024.0)`
- **ID 策略**：主键**不出现在 UI**，UI 只显示 `display_no`（如 `SUB-20481`），前端永不自拼

### 5.3 判题计分（OI 按测试点权重）

```
oiScore = Σ(通过测试点的 score)      // 全部通过 = AC(3)
firstFailedCaseId / first_failed_summary   // 首个失败点，供 AI 阶段③ 用
effectiveTimeMs = timeLimit × (java|python ? 2 : 1)   // 异构语言时空补偿
effectiveMemMb  = memoryLimit × (java|python ? 2 : 1)
cpuLimit   = effectiveTimeMs × 1_000_000L      // 纳秒
clockLimit = cpuLimit × 2
memoryLimit = effectiveMemMb × 1024L × 1024L   // 字节
```

**输出比对**：`stdout` 与 `output_text` 统一按行 `stripTrailing()`（去行尾空格与 `\r`）后逐行比对，忽略文件末尾多余空行。

---

## 6. 每次改动的标准动作（Checklist）

### 6.1 改前端

```
□ 读 docs/analysis/03-前端对接实施指南.md 对应页面章节
□ 确认只动「页尾 <script>」或「x-data 对象内部」，没碰 DOM/class/style
□ node --check <改动的 js>（语法）
□ node scripts/_probe_shots.mjs --out _shots/_verify   （视觉零回归）
□ node scripts/_probe_guard.mjs                        （守卫 14 项）
□ 手工过一遍：?mock=1 能交互；去掉 mock 跳 login
```

### 6.2 改后端

```
□ 读 docs/analysis/02-契约补全规范-v2.0.md 对应端点
□ 不改端口、不改 URL 前缀、不手写 Redis Key 字符串
□ pwsh scripts/build-backend.ps1        （mvn clean compile 必须 BUILD SUCCESS）
□ pwsh scripts/run-services.ps1 -Action restart
□ curl http://127.0.0.1:8080/api/<svc>/health    （经网关）
□ Nacos 服务列表 count 必须 = 6
```

### 6.3 改数据库

```
□ 改 docker/mysql/init/init_oj_cloud.sql
□ docker compose down && Remove-Item -Recurse -Force .\data\mysql
□ docker compose up -d mysql && python scripts/verify-day1.py
□ 幂等性：脚本连跑 3 次必须全部 exit 0
```

### 6.4 提交前

```
□ git status 里没有 data/、logs/、target/、node_modules/
□ 交付说明已更新（docs/ 下）
□ 提交信息用 <type>(<scope>): <中文摘要>，type ∈ feat/fix/docs/chore/refactor/test
```

---

## 7. 验收命令速查

```powershell
# ── 基础设施（Day 1）──
docker compose ps                                  # 5 容器 healthy
python scripts/verify-day1.py                      # 31 项

# ── 后端（Day 2-3）──
pwsh scripts/build-backend.ps1                     # 编译 + 打包
pwsh scripts/run-services.ps1 -Action status       # 端口 + Nacos 健康
curl.exe http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1`&pageSize=20
curl.exe http://127.0.0.1:8080/api/teaching/health # 经网关
curl.exe -i http://127.0.0.1:8080/api/teaching/students   # 应 401

# ── 前端（Day 2-3）──
python -m http.server 3000                         # 在 prototype/app/ 下
node scripts/_probe_shots.mjs                      # 视觉零回归
node scripts/_probe_guard.mjs                      # 登录守卫 14 项
node scripts/_probe_workbench.mjs                  # 工作台 8 项

# ── 一键全验 ──
pwsh scripts/verify-day2.ps1
```

---

## 8. 常见任务：从零把环境跑起来

```powershell
cd "D:\毕设\agent oj"

# 1. 中间件（5 容器）
docker compose up -d
docker compose ps                                  # 等 5 个 healthy

# 2. 本地模型（已在 D:\ollma-models）
$env:OLLAMA_MODELS = "D:\ollma-models"
ollama list                                        # 应看到 qwen2.5-coder:7b / 1.5b
ollama run qwen2.5-coder:7b                        # 首次会拉，之后秒开

# 3. 后端
pwsh scripts/build-backend.ps1
pwsh scripts/run-services.ps1 -Action start

# 4. 前端
cd prototype\app; python -m http.server 3000
# 浏览器开 http://127.0.0.1:3000/index.html
# 演示模式：任意页加 ?mock=1
```

---

## 9. 任务进度与下一步

| 步骤 | 内容 | 状态 |
| --- | --- | --- |
| 第 1 步（Day 1） | 容器化 + 18 表建表 + 种子数据 + Ollama | ✅ |
| 第 2 步（Day 2-3） | 前端 Stage 0 底座 + 后端 Maven 骨架 + 6 服务注册 Nacos | ✅ |
| 第 3 步（Day 4-7） | 【第一纵深 P0-A】登录 → 题库 → 异步判题闭环 | ⬜ 下一步 |
| 第 4 步（Day 8-11） | 【第二纵深 P0-B】端云 AI Agent + SSE 六事件流 | ⬜ |
| 第 5 步（Day 12-16） | 【第三纵深 P1】出题/作业/考试防作弊/双阶段查重 | ⬜ |
| 第 6 步（Day 17-20） | 【第四纵深 P2】学情看板 + 运维治理 | ⬜ |

**Day 4 开工第一件事**：在 `oj-teaching-service` 实现 `#1 login` ~ `#5 menus` 五个端点，同时把 `oj-problem-service` 的 `#6`/`#7` 只读接口做出来，然后把 `login.html` + `shell.js` + `s-problems.html` 去掉 `?mock=1` 真连。

---

## 10. 与 AI 协作的具体约定

### 10.1 执行纪律：禁止阻塞等待，用 wait 并并行推进

> **这是硬性效率规则，违反即视为协作方式错误。**

任何「需要等结果」的环节，**都不允许空转阻塞**。必须改成「挂起 + 并行做别的 + 回来收」。

| 场景 | ❌ 不要这样做 | ✅ 应该这样做 |
| --- | --- | --- |
| 后台任务（编译、打包、截图、验收） | 反复 `job_output` 轮询、`Start-Sleep` 干等 | 用 `job_output -wait`（带 `timeout_ms`）挂起；等待期间去做**不依赖该结果**的下一件事 |
| 队友 / 子 Agent 干活 | 原地 `wait_agent` 死等 | `wait_agent` 一次拿到进展或超时后，**先检查还能并行做什么**，再回来收 |
| 服务启动 / 容器健康检查 | `Start-Sleep 60` 然后祈祷 | 脚本内用「轮询端口 + 超时」封装（`run-services.ps1` 已实现），调用方只管等脚本返回 |
| 长任务（首次 `mvn` 下依赖、镜像拉取） | 前台卡住整个回合 | `run_in_background: true` 起后台 job，拿到 `job_id` 立刻转去做别的 |
| 需要外部输入（等用户确认） | 停在这里什么都不做 | 把能提前做的部分做完，只把真正需要决策的点抛出去 |

**具体操作要求：**

1. **起后台任务后立刻返回做别的事。** 拿到 `job_id` 就继续推进独立工作，**不要**紧接着 `job_output` 空轮询。等到确实被它阻塞时，再用 `job_output -wait -timeout_ms`。
2. **等待必须带超时。** `job_output` 的 `wait: true` 要配 `timeout_ms`；`wait_agent` 要配 `timeout_ms`。**没有超时的等待等于挂死。**
3. **一次等待期间至少推进一件并行的事。** 例如：编译在后台跑时，去写文档 / 跑前端探针 / 核对契约字段 / 更新 `.gitignore`。
4. **同一回合内能并行发起的调用要合并。** 多个互不依赖的读文件、grep、端口探测、`curl`，放在**同一个消息里**一起发，不要串行一个个等。
5. **等待超时不是失败。** 超时后先 `job_output` 看已完成部分、`job_list` 看全局，再决定继续等还是改方案——**不要因为超时就重跑一遍**。
6. **收尾前必须回收所有后台 job。** 给最终答复前，用 `job_list` 检查：还相关的用 `job_output -wait` 收干净，不再需要的 `job_kill`。**不允许留下无人看管的后台任务。**

**反例（本项目真实踩过）：** 启动 6 个服务时用了串行 `Wait-Port`，6 × 90s 最坏要 9 分钟。改成 `Wait-Ports` 并行等待后，同样的启动只要 ~40s。**等待逻辑本身也要并行化。**

### 10.2 通用协作约定

1. **先读再改。** 改任何文件前先 `read`；改契约相关字段前先读 `docs/analysis/02`。
2. **不要猜版本号、端口、路径。** 全部在 §2 和 §4.1 里。
3. **不要引入新依赖。** 前端零构建，后端版本已锁。确实需要时先说明理由并等确认。
4. **不要为了让演示好看而造假数据。** 见 §3.4。这是本项目最容易犯、也最伤答辩的错。
5. **改完必须自己跑验收。** 不要交付「应该可以」的代码——§6 的 checklist 是硬要求。
6. **不确定就问。** 尤其是涉及「改不改原型 DOM」「要不要新增表」「要不要升版本」这三类决定。
7. **提交信息说人话。** 写清楚「改了什么、为什么」，不要 `update`、`fix bug` 这种。
8. **过程性产物不入库。** 截图基线（`_day2_baseline/`、`_shots/_day2/`）入库存档；单轮迭代的几百张中间截图不入库（见 `.gitignore`）。
9. **长输出不要用 `cat`。** 用 `read` 的 `offset`/`limit` 分段读；大范围搜索用 `grep`/`glob`，不要 `Get-ChildItem -Recurse | Select-String`。
10. **脚本里的中文输出不要被下游解析。** 探针脚本一律落 `report.json` 等结构化文件，验收脚本读文件而不是正则匹配 stdout（本项目真实踩过：终端编码不同导致中文正则匹配失败）。
