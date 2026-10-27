# agent.md —— 本项目 AI 协作开发的约束与操作手册

> 本文件是**所有 AI Agent（以及人类协作者）在本仓库内做任何改动前必须读完的唯一约束文件**。
> 它不重复 PRD 的业务需求，只回答三件事：**改什么、怎么改、怎么证明没改坏**。
>
> 维护规则：本文件随项目阶段演进更新，但**「红线」章节只能在明确评审后放宽**。
> 最后更新：**第 6 步收官（2026-10-27 深夜）**。新增 §0.1 硬事实 #8~#11（`FLUSHDB` 会清展示号序列 / 网关白名单通配误放行 / `ttftMs` 为 null 的正确语义 / 智谱已非 429）；§4.4 补「admin 服务 401 vs 403」；§8 补第 6 步验收命令。

---

## 0. 三十秒速览

| 问题 | 答案 |
| --- | --- |
| 这个项目是什么 | 毕业设计：AI Agent + 微服务架构的启发式在线判题系统（1 网关 + 5 服务 + 87 端点 + 19 张表 + 17 页） |
| 当前进度 | Day 1 基础设施 ✅ ｜ Day 2-3 前后端地基 ✅ ｜ Day 3 题库导入 ✅ ｜ Day 4-7 第 3 步判题闭环 ✅ ｜ Day 8-11 第 4 步 AI 助教闭环 ✅ ｜ Day 12-16 第 5 步教学闭环与双阶段查重 ✅ ｜ **Day 17-20 第 6 步学情看板与运维治理 ✅（490/490 + 37/37 + 17/17）** ｜ **全站 87 接口 100% 覆盖，6 步全部收官** |
| 前端技术栈 | **零构建**多页应用：原生 HTML + Alpine.js + 手写 CSS Token 体系。**没有 npm、没有 Vite、没有打包** |
| 后端技术栈 | JDK 17 + Spring Boot 3.2.5 + Spring Cloud Alibaba 2023.0.1.0 + MyBatis-Plus 3.5.7 |
| 宿主机有什么 | 有 Docker、JDK 17（`D:\Java\jdk-17`）、Python 3.12、Node 24。**没有 Maven**（走容器编译） |
| 最重要的红线 | 17 页原型的 **DOM 结构与 class 名一行都不能动**（视觉零回归是硬门槛） |
| 第二重要的红线 | **`prob_test_case.input_text/output_text` 是 4KB 摘要，判题必须读磁盘 `.in/.out` 文件** |
| **命令怎么跑** | **只用 `pwsh`（PowerShell 7），禁止 `cmd` / `.bat` / `powershell` 5.1**（§2.1.1） |
| **服务怎么启** | **只用 `pwsh scripts/run-services.ps1`**，它用 `javaw.exe` 起进程——**绝不弹窗**（§4.5.4） |
| 验证怎么跑 | 第 6 步：`python scripts/verify-day17.py`（490 项）+ `node scripts/_probe_step6_acceptance.mjs`（37 项，手册验收标志逐字复现）｜ 第 5 步：`python scripts/verify-day12.py`（**94 项，已含 #63/#71**）+ `node scripts/_probe_step5_acceptance.mjs`（18 项）｜ 第 4 步：`python scripts/verify-day8.py`（29 项）+ `node scripts/_probe_day8_frontend.mjs`（43 项）+ `node scripts/_probe_step4_acceptance.mjs`（26 项）｜ 第 3 步：`python scripts/verify-day4.py`（77 项）+ `node scripts/_probe_step3_acceptance.mjs`（16 项）｜ DOM 红线：`node scripts/_probe_dom_structure.mjs`（17 项 / 15 页）｜ 前端底座：`pwsh scripts/verify-day2.ps1` ｜ **⚠️ 各套件必须串行跑**（同账号登录会互相顶掉 token，另有 1 分钟登录限流）｜ 全量见 §8 |

### 0.1 十一个「不知道就一定踩」的硬事实

| # | 事实 | 后果 |
| --- | --- | --- |
| 1 | 题库 **1008 道题 / 4867 个测试点 / 6.95 GB 测试数据在磁盘**，不在 MySQL | 按「测试数据在库里」写代码，判题 100% 误判 WA |
| 2 | go-judge v1.13 的 `/run` 里**多个 cmd 不共享文件系统**，且 `files` 是**位置语义** | 按官方文档写「编译+跑同一请求多 cmd」，全部 `no such file` |
| 3 | 本机 8 GB 显存 / 16 GB 内存，5 容器 + 4 服务常驻已占 ~4 GB | 本地模型必须 `num_ctx=8192`；服务必须 `-Xmx256m` |
| 4 | **`java.exe` 是控制台子系统程序，每进程必弹一个黑窗口**（`Start-Process -WindowStyle Hidden` 也挡不住） | 必须用 `javaw.exe` + `--logging.file.name` 写文件日志，见 §4.5.4 |
| 5 | **`@FeignClient` 按服务名调用时，光有 openfeign 不够，必须再加 `spring-cloud-starter-loadbalancer`** | 少了它服务**启动期**就抛 `No Feign Client for loadBalancing defined`，端口不通（第 5 步踩过） |
| 6 | **`Integer` 变量与 `int` 常量写在同一个三元表达式里，整个表达式会被定成 `int`**，对 null 直接拆箱 NPE | `teacherOrAbove ? q.getState() : STATE_PUBLISHED` 就是这样炸的（教师题库首屏 500），一律改 `if/else` |
| 7 | **验收套件之间不能并行跑**：同账号重复登录会顶掉旧 token，另有 `oj:rate:login:{ip}` 1 分钟窗口（约 12 次） | 并行/连跑会得到「未登录或 Token 失效」「登录尝试过于频繁」，看着像产品缺陷其实是环境限制 |
| 8 | **`redis-cli FLUSHDB` 会连带清掉 `oj:seq:{biz}` 展示号序列**，下一条记录拿到 `S-1`/`A-0001`/`PL-0001` 与历史行唯一键冲突 | `DuplicateKeyException` → 500。**只 `DEL` 单个 key，绝不 `FLUSHDB`**；误删后按库内 `display_no` 最大值重 SET（**查重的任务与报告是两个独立序列**，见 §4.5.5） |
| 9 | **网关白名单 `/api/*/health` 会匹配 `/api/admin/health`** | 网关跳过 JWT 且不注入 `X-User-Role` → admin 侧收到"无身份头" → **管理员拿着有效 token 也 401**。白名单必须逐条列，禁用通配（§4.5.6） |
| 10 | **`ai_chat_record.ttft_ms` 为 null 是正确语义**（`guardrail_action=1` 的拦截根本没调模型） | 验收断言写成「必须非负整数」会把正确语义判红。正确写法：`非负整数 或 null` + 交叉断言「`guardAction≠1` 时不允许 null」 |
| 11 | **智谱 GLM-5.3-Flash 当前返回 HTTP 200 但 `content` 为空**（token 全被 `reasoning_content` 吃掉），**已不是 429**（§2.6 旧记录过期） | 探活判活标准必须是「2xx + `choices` 非空」，**不能看正文是否为空**，否则推理模型被永久误判 DEGRADE |

---

## 1. 目录地图与「谁能改什么」

```
D:\毕设\agent oj\
├── agent.md                      ← 本文件（约束）
├── README.md                     项目总览
├── docker-compose.yml            5 个基础容器（MySQL/Redis/RabbitMQ/Nacos/go-judge）
│
├── docker/
│   ├── mysql/init/init_oj_cloud.sql       19 张表 DDL + 种子数据（只首启执行）
│   ├── mysql/init/patch_day3_design.sql   Day3 数据库设计修复补丁（幂等，可重跑）
│   └── judge/                    go-judge 派生镜像 + 三语言冒烟脚本
│
├── data/                         ⛔ 运行期数据卷（gitignore，可随时删重建）
│   ├── mysql/                    MySQL 数据文件（271 MB，只存元数据 + 4KB 摘要）
│   └── testcases/{problemId}/    ⭐ 6.95 GB 真实测试数据，9675 个 .in/.out 文件
│
├── backend/                      ⭐ Maven 多模块（详见 §4）
│   ├── pom.xml                   父 POM：统一锁版本
│   ├── oj-common-core/           Result / ErrorCode / BusinessException / JwtUtil / RedisKeys / PageResult
│   ├── oj-common-web/            ⭐ Day4 新增：全局异常处理 / UserContext / MyBatis-Plus / TraceId 过滤器
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
│   │   ├── shell.js              🔒 外壳：图标/导航/侧栏/工具函数
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
│   ├── day3-题库导入与数据库修复说明.md   ⭐ 判题 DAO 开工前必读（§7 硬约束）
│   └── analysis/                 契约补全与对接指南（**改动前必读**）
│       ├── 00-基线决策.md        字典统一 / 红线 / 缺口判定口径
│       ├── 02-契约补全规范-v2.0.md   87 端点逐条契约（118 KB，查字段用它）
│       ├── 03-前端对接实施指南.md   前端逐页改造方案（**前端改动前必读**）
│       └── 04- 终极完整版设计文档.md  19 张表 + 类结构 + 算法 + 状态机
│
├── scripts/
│   ├── verify-day1.py            Day 1 验收（31 项）
│   ├── verify-day2.ps1           Day 2-3 一键验收（前后端全验）
│   ├── verify-day4.py            第 3 步验收（77 项）
│   ├── verify-day8.py            第 4 步验收（29 项，真实网关 + 真实本地模型）
│   ├── verify-day12.py          ⭐ 第 5 步验收（94 项，含 #63 同步查重 + #71 rerun）
│   ├── verify-day17.py          ⭐ 第 6 步验收（490 项，28 端点 + 反向守卫 + 密钥扫描）
│   ├── build-backend.ps1         容器内 mvn 编译/打包
│   ├── run-services.ps1          启停 6 个服务（含 Nacos 注册检查）
│   ├── browser-runtime.mjs      ⭐ 浏览器三级回退解析（env → Playwright 自带 → 系统 Chrome）
│   │                             ⚠️ 所有 .mjs 探针**必须**从这里 import chromium，不许直连 'playwright'
│   ├── _probe_gojudge*.py        ⭐ go-judge 协议探针（判题开发前先跑）
│   ├── _audit_day17_fields.py    ⭐ 契约字段静态自查（290 个字段路径逐字比对 02 文档）
│   ├── _selftest_day17_paths.py    字段路径解析器离线单测（44 条，不联网不起服务）
│   ├── _selftest_day12_coverage.py #63~#71 覆盖核对
│   ├── import_problems.py        题库导入（物化测试数据 + 元数据入库）
│   └── _probe_*.mjs              视觉/守卫/工作台/教学闭环/学情运维探针
│└── logs/                         ⛔ 服务运行日志（gitignore）
    ├── <svc>.log                 javaw 启动后 logback 写的唯一日志来源
    └── pids/<svc>.pid            进程号，stop/status 靠它定位
```

### 1.1 写入权限矩阵

| 路径 | 允许改 | 约束 |
| --- | --- | --- |
| `backend/**` | ✅ | 必须能 `mvn clean compile` 通过 |
| `prototype/app/api/**` `adapter/**` `boot.js` `login.html` | ✅ | 遵守 §3.2 / §3.3 |
| `prototype/app/s-workbench.js` | ✅ | 受 §3.4 约束（禁造假数据） |
| `prototype/app/*.html`（16 个业务页） | ⚠️ **仅限页尾 `<script src>`、`x-data` 对象内部、PRD 5 处微创修正** | 禁改 DOM 结构/class/内联样式 |
| `prototype/app/shell.js` | ⚠️ 仅限「删假数据、读登录态、getter 数据源」 | 禁改 `buildSide` 的 DOM 模板结构 |
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
| Shell | **PowerShell 7.6.4**（`D:\PowerShell7\7\pwsh.exe`） | **一律用 `pwsh`，禁止 `cmd`**，见 §2.1.1 |
| JDK | `D:\Java\jdk-17`（`JAVA_HOME` 已设） | 宿主机**有** JDK，**没有** Maven。启动服务必须用 `javaw.exe`（见 §4.5.4） |
| Maven | 容器 `maven:3.9-eclipse-temurin-17` | 见 §4.2 |
| Docker Desktop | `D:\Docker_Desktop` | 已装，5 容器 healthy |
| Ollama 模型目录 | `D:\ollma-models`（`OLLAMA_MODELS`） | 不污染 C 盘 |
| Python | 3.12.10 | 验收脚本用它 |
| Node | 24.18.0 | 探针脚本用它（Playwright 已装） |

### 2.1.1 命令执行铁律：只用 `pwsh`（PowerShell 7），禁止 `cmd`

> **这是硬性约束，违反即返工。** 本项目所有命令、脚本、子进程一律走
> **PowerShell 7（`pwsh`）**，不得使用 `cmd.exe`、`.cmd`/`.bat` 文件、
> 也不得用 Windows PowerShell 5.1（`powershell.exe`）。

**为什么**：

| 问题 | `cmd` / 5.1 的坑 | `pwsh` 7 的优势 |
| --- | --- | --- |
| 中文路径 | `cmd` 默认 GBK 代码页，`D:\毕设\agent oj` 常被截断成乱码 | 全程 UTF-8，中文路径无问题 |
| 引号规则 | `cmd` 的引号转义规则与 PowerShell 完全不同，双层嵌套必翻车 | 统一的引号语义 |
| 进程管理 | `cmd /c` 会引入一层 shell，被 Job Object 收走（见 §4.5.1） | 直接 `Invoke-CimMethod` 起进程 |
| 弹窗 | `cmd /c java ...` 会弹控制台窗口 | `pwsh` + `javaw` 无窗口（见 §4.5.4） |
| 错误处理 | `%ERRORLEVEL%` 语义弱 | `$LASTEXITCODE` / `try-catch` / `-ErrorAction` |
| 对象管道 | 只有文本 | 结构化对象，`ConvertTo-Json` 直接可用 |

**具体规则**：

```
□ 所有命令用 `pwsh -Command` 或 `pwsh -File`，不用 `cmd /c`
□ 不写 .cmd / .bat 文件；脚本一律 .ps1，首行声明 [CmdletBinding()]
□ 不调用 powershell.exe（5.1）—— 它在 UTF-8、`??` 空合并、`-Parallel` 上都弱
□ 需要环境变量用 `$env:NAME`，不用 `set NAME=`
□ 需要退出码用 `$LASTEXITCODE`，不用 `%ERRORLEVEL%`
□ 启动后台进程用 `Invoke-CimMethod Win32_Process.Create`，不用 `start /b`
□ 路径一律加引号（工作目录含中文与空格）
```

**当前环境已验证**：

```powershell
$PSVersionTable.PSVersion   # 7.6.4
$PSVersionTable.PSEdition    # Core
(Get-Command pwsh).Source    # D:\PowerShell7\7\pwsh.exe
```

> ⚠️ 机器上**同时存在** Windows PowerShell 5.1（`C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe`）。
> 它是系统自带的，删不掉也不该删；但**本项目的任何脚本都不许调它**。
> 判断当前在哪个 shell：`$PSVersionTable.PSEdition` 为 `Core` 才是 pwsh 7。

### 2.2 本地模型：显存预算（已实测，不要重测）

机器 **RTX 4060 Laptop / 8 GB 显存（8188 MiB）+ 16 GB 内存**。
模型目录固定在 **`D:\ollma-models`**（`OLLAMA_MODELS`，不污染 C 盘）。

| 模型 | 上下文 | 实测显存 | 结论 |
| --- | ---: | ---: | --- |
| `qwen2.5-coder:7b` | 32768 | 7160 MiB | ❌ 顶满显存并溢出 CPU，与 5 容器同机有 OOM 风险 |
| `qwen2.5-coder:7b` | **8192** | **约 5.0 GB / 6307 MiB 已用** | ✅ **主模型**：100% GPU，留 ~1.6 GB 余量 |
| `qwen2.5-coder:1.5b` | 32768 | 约 2.1 GB | ✅ 降级备份 / 快速冒烟 |

**铁律：本地模型一律 `num_ctx=8192`。** 不要用默认 32768 拉起 7b——会直接吃满 8 GB 显存。

**验证方式**（每次怀疑显存有问题时跑一遍，不要凭感觉）：

```powershell
# 用 8192 上下文加载并问一句话
$body = @{ model='qwen2.5-coder:7b'; messages=@(@{role='user';content='ok'});
           options=@{ num_ctx=8192 }; stream=$false } | ConvertTo-Json -Depth 5
Invoke-RestMethod http://127.0.0.1:11434/api/chat -Method Post -Body $body -ContentType 'application/json'

ollama ps        # CONTEXT 列必须是 8192，PROCESSOR 必须是 100% GPU
nvidia-smi --query-gpu=memory.used,memory.free --format=csv,noheader
```

实测结果：`PROCESSOR=100% GPU`、`CONTEXT=8192`、`SIZE=5.0 GB`、显存 `used=6307 MiB / free=1651 MiB`。
**只要 PROCESSOR 出现 CPU 百分比，就说明上下文或并发超了，立刻降 `num_ctx` 或换 1.5b。**

### 2.3 内存预算（16 GB 机器，这是硬约束）

| 常驻项 | 内存 | 说明 |
| --- | ---: | --- |
| MySQL / Redis / RabbitMQ / Nacos / go-judge | ~2.6 GB | 5 容器，Nacos 已压到 `JVM_XMX=256m` |
| 每个 Spring Boot 服务 | 256 MB 堆 | `-Xmx256m`，6 个全开约 1.2 GB |
| Ollama 7b（8192 上下文） | ~5.0 GB 显存 + ~1 GB 宿主 | 只在第 4 步 AI 时启动 |
| **合计（第 3 步：4 服务 + 5 容器，无 Ollama）** | **~3.6 GB** | 余量充足 |
| **合计（第 4 步：6 服务 + 5 容器 + Ollama）** | **~6.4 GB** | 仍在 16 GB 内 |

**按需启动（省内存核心技巧）**：
- 写第 3 步判题链路时只开 `gateway + teaching + problem + judge`
- 写第 4 步 AI 时再开 `ai`
- `admin` 平时完全不用开，等做管理端再启

### 2.4 端口分配（固定，不要改）

| 端口 | 服务 | 端口 | 服务 |
| ---: | --- | ---: | --- |
| 8080 | oj-gateway | 8848 / 9848 | Nacos（**9848 必须映射**，gRPC） |
| 8081 | oj-teaching-service | 3306 | MySQL 8.0 |
| 8082 | oj-problem-service | 6379 | Redis 7 |
| 8083 | oj-judge-service | 5672 / 15672 | RabbitMQ |
| 8084 | oj-ai-agent-service | 5050 | go-judge 沙箱 |
| 8085 | oj-admin-service | 11434 | Ollama |

### 2.5 网络：代理 + 国内镜像源

**本机外网一律走本地代理 `http://127.0.0.1:7897`**（Clash/Mihomo 常用端口）。直连 `github.com` 会超时或被 reset——这不是网络故障，是没走代理。

```powershell
# git 已配好全局代理（换机器/换端口时重配）
git config --global http.proxy  http://127.0.0.1:7897
git config --global https.proxy http://127.0.0.1:7897

# 临时给单条命令加代理（不改配置）
curl.exe -x http://127.0.0.1:7897 https://github.com

# 环境变量方式（部分工具认这个）
$env:HTTPS_PROXY = 'http://127.0.0.1:7897'
$env:HTTP_PROXY  = 'http://127.0.0.1:7897'
```

> 注意 `NO_PROXY` 已含 `127.0.0.1,localhost,::1`，本地服务（Nacos/MySQL/网关）不会被代理劫持。

**下载优先用国内镜像源**（代理是兜底，不是首选）：

| 场景 | 镜像 | 现状 |
| --- | --- | --- |
| Docker 镜像 | `docker.m.daocloud.io/` 或 `docker.1ms.run/` 前缀 | `build-backend.ps1` 已内置回退 |
| Maven 依赖 | `https://maven.aliyun.com/repository/public` | ✅ **`~/.m2/settings.xml` 已配好阿里云镜像** |
| apt（容器内） | 阿里云源 | ⚠️ 本机实测该源有证书校验失败，失败时改回 `deb.debian.org` |
| npm | `https://registry.npmmirror.com` | 本项目前端零构建，一般用不到 |
| Ollama 模型 | 官方源走代理 | 本地已有 `qwen2.5-coder:7b` / `1.5b`，**不要重复下载** |
| GitHub | 走代理（已配） | `git push` 前先 `git ls-remote origin` 验证连通 |

**判断顺序**：① 本地/镜像有没有 → ② 国内镜像拉 → ③ 走代理拉官方源 → ④ 才考虑干等。
**不要**在没试镜像的情况下直接挂代理下几十 GB 的镜像。

### 2.6 密钥管理：**只走环境变量，绝不入库**

> **红线：任何 API Key / 密码 / Token 都不得出现在代码、SQL、文档、提交历史里。**
> 2026-10-27 曾有人把智谱 API Key 明文写进《6 步手册》正文，已在入库前拦下。

| 位置 | 允许放密钥 | 说明 |
| --- | --- | --- |
| `.env.local` | ✅ **唯一真实值存放处** | 已被 `.gitignore` 忽略；`run-services.ps1` 启动时自动载入 |
| `.env.example` | ✅ 只放**占位空值** | 入库，让别人知道要配哪些变量 |
| 系统环境变量 | ✅ | 优先级高于 `.env.local`（方便临时调试） |
| `ai_model_config.api_key` | ✅ 只放**占位符** | 形如 `${OJ_ZHIPU_API_KEY}`，由后端运行时解析 |
| 代码 / SQL / 文档 / 提交信息 | ⛔ **绝对禁止** | 一旦入库会永久留在 git 历史里，删除文件也没用 |

**后端解析规则**（`DynamicAiModelFactory.resolveSecret`，三种形态）：

| 写法 | 解析结果 |
| --- | --- |
| `${OJ_ZHIPU_API_KEY}` | 取同名环境变量；不存在则视为「未配置密钥」 |
| `${VAR:默认值}` | 环境变量缺失时用冒号后的默认值 |
| `env:OJ_ZHIPU_API_KEY` | 取同名环境变量 |
| 明文 `sk-xxx` | 原样返回（兼容老数据 / 管理后台直接填） |

**改了 `ai_model_config.api_key` 必须清 Redis 缓存**，否则读到的是旧快照：

```powershell
docker exec oj-cloud-redis redis-cli DEL "oj:ai:config:channels"
```

> 这是本项目真实踩过的坑：数据库已经改成 `${OJ_ZHIPU_API_KEY}`，但 Redis 里
> 还缓存着 `apiKey:""`，云端通道因此一直静默降级到本地，日志里没有任何「密钥无效」
> 提示——因为它在发出请求**之前**就以「未配置密钥」失败了。

**加载 `.env.local` 的方式**（`run-services.ps1 -Action start|restart` 已自动做）：

```powershell
Get-Content .env.local | Where-Object { $_ -match '^\s*[^#].+=' } | ForEach-Object {
  $k, $v = $_ -split '=', 2
  [Environment]::SetEnvironmentVariable($k.Trim(), $v.Trim())
}
```

> ⚠️ **`Win32_Process.Create` 不继承调用方进程内新设的环境变量（实测）。**
> 在 pwsh 里 `SetEnvironmentVariable` 之后起的子进程，读不到该变量——
> WMI 服务端用的是自己的环境块。所以**不要指望「脚本里 export 一下」就能传到 javaw**。
> 本项目实际生效的路径是 `spring.config.import: optional:file:./.env.local[.properties]`
> （见 `oj-ai-agent-service/application.yml`），即 **Spring 直接读文件**。
> 又因为 `spring.config.import` 只写进 Spring `Environment`、**不进 `System.getenv()`**，
> 所以 `resolveSecret()` 必须从 `Environment` 取值（已修正）。

**新增云端通道的完整步骤**：① `.env.local` 加变量 → ② `ai_model_config.api_key`
写 `${变量名}` → ③ 清 Redis 通道缓存 → ④ 重启 ai 服务 → ⑤ 确认 SSE 没有 `fallback_notice`。

**万一密钥已经提交过**：仅删文件无效，必须重写历史（`git filter-repo`）并**立刻到厂商控制台吊销重建**。
本项目当前状态：智谱 Key **从未进入任何提交**（`git log --all -S` 已验证为空）。

> ⚠️ 智谱 GLM 当前实测返回 **429 `{"code":"1113","message":"余额不足或无可用资源包"}`**——
> 密钥有效、通道配置正确，是账户余额问题。答辩演示若要用云端，需先充值；
> 否则走「云端密钥缺失/失败 → 真实降级本地 Ollama」这条路径，该路径本身已有验收覆盖。

### 2.7 云端「推理型模型」的三条硬约束（GLM-5.3 实测）

> 智谱赠送的资源包**按模型绑定**：`glm-5.3-flash` / `glm-5.3-flashx` 有额度，
> **`glm-4-plus` 没有**（调它必 429）。通道当前配 `glm-5.3-flash`。

GLM-5.3-Flash 是**推理模型**（先出 `reasoning_content` 再出正文），由此踩出三个坑，
都已修：

| 坑 | 现象 | 修法 |
| --- | --- | --- |
| 首字超时焊死 8s | `Math.min(8, timeoutSeconds)` → 推理要 9~30s 才出首字，**必然判超时并降级**，日志只有一句「云端不可用」 | 改为尊重通道 `timeout_seconds`（现配 40s） |
| `max_tokens` 焊死 1024 | 1024 全被 reasoning 吃掉，**正文直接为空**（HTTP 200 但 `content=""`） | 云端按通道配置（现配 8192），本地仍 1024 |
| 单轮 deadline 54s | 40s 首字 + 正文流式 > 54s 被掐断 | deadline 放宽到 150s，`SseEmitter` 同步 170s |

**实测基线**（供以后判断「是不是又变慢了」）：

| 指标 | 值 |
| --- | --- |
| 单次问答总耗时 | 9.5s（短问）/ 30~45s（长问） |
| `reasoning_tokens` | 500~2000+（占 completion 的绝大多数） |
| 典型 `finish_reason` | `stop`（token 给够时） |

> 结论：**给推理模型配 `max_tokens` 必须按「reasoning + 正文」两段预估**，
> 只按正文长度配会得到空回答。前端若要显示「思考中」，也应据此放宽等待预期。


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

> **2026-10-27 用户明确批准的窄范围例外（仅 `s-workbench.html`）：**
> 可以给现有节点增加 `x-text` / `x-show` 及相应动态属性，将固定 AI 示例、模型名、
> 解锁原因和配额显示绑定为真实数据。DOM 结构、class、样式及 `?mock=1` 首屏保持不变。
> 此授权不扩大到新增节点或修改其他业务页的布局。

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
- **`shell.js` 的 8 个 `OJ.xxx` getter 是页面读数据的唯一入口。** 页面里 `OJ.problems` 这类读法一行都不用改。

### 3.2.1 真实接口接入方式（Day 4 起，重要）

页面要接真实数据，**必须走「写回 OJ 的 data slot」而不是改 DOM**：

```js
// shell.js 里每个 getter 背后有一个可写槽位：
//   OJ._problems = null  → 未加载，getter 返回 []（页面走空态）
//   OJ._problems = [...] → 已加载，getter 返回真实数据
// 页面 init() 里：
this.OJ._problems = (await api.http.get('/api/problem/questions', {query: {...}})).list
```

**为什么这样设计**：`OJ` 被 Alpine 的 `reactive()` 代理，页面里写 `this.OJ._problems = x` 会触发依赖追踪，模板里的 `OJ.problems` 自动重渲染。**不要**用闭包变量存数据（Alpine 追踪不到，页面永远停在空态）。

**硬性要求**：
- 真实态下 `OJ._problems` 为 `null`，getter 返回 `[]`，页面**必须能渲染空态**（每页都已有空态，不要新写）。
- 禁止 `OJ.problems[0].xxx` 这种不经判空的取值。
- `?mock=1` 时 getter 仍返回 fixtures，**不读槽位**——保证演示与真实完全隔离。

### 3.2.2 BASE 地址三档解析（Day 4 踩过的坑）

`api/http.js` 的 `BASE` **不能恒为 `''`**。前端跑 `python -m http.server 3000` 时，
同源请求会打到静态服务器自己并 404——页面看着像「接口没接上」，实际是请求发错了地方。

```js
// 优先级：显式覆盖 > 同源（网关托管前端）> 本地开发默认 :8080
if (typeof window.OJ_API_BASE === 'string') return window.OJ_API_BASE;
if (isLocalhost && port && port !== '8080') return 'http://' + hostname + ':8080';
return '';
```

用 `location.hostname` 拼而不是写死 `localhost`——答辩现场可能用局域网 IP 访问，
写死 localhost 会让手机/另一台电脑打不开。

### 3.2.3 适配器必须同时认契约名与 mock 短名

| 实体 | 契约名（02 文档） | mock 短名（api/mock.js） |
| --- | --- | --- |
| 题目 | `acRate` `triedCount` `myStatus` `myScore` | `ac` `tried` `status` `score` |
| 提交 | `problemTitle` `oiScore` `passCaseCount` `timeUsedMs` `memoryUsedKb` `submittedAt` | `title` `score` `pass` `time` `mem` `at` |

**mock 与真实走同一个 adapter 是 Stage 0 的核心承诺。** 分叉成两个函数就会出现
「演示好看、真实炸」——而且这种 bug 只有在真实态才会暴露，评审当场看到白屏。

### 3.2.4 数字主键 vs 字符串展示号（真连必踩）

真实接口的 `id` 是**数字**（`1006`），`displayNo` 是**字符串**（`"S-20"`）。
而原型页面的排序/筛选写的是 `a.id.localeCompare(b.id)`、`s.id.indexOf(...)`——全按字符串。

真实态下会抛 `a.id.localeCompare is not a function`，**整页白屏**。

**规则**：
- `adapter` 输出同时给 `id`（数字，详情接口用）与 `idStr`/`no`（字符串，排序筛选用）
- 页面里所有 `localeCompare` / `indexOf` / `toLowerCase` 一律走 `idStr`
- 展示提交号用 `no`，**绝不**自己拼 `'S-' + id`（违反 §5.2 ID 策略）

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
| **编辑器代码恒为母题解答**（Day 4 发现） | 工作台打开 `?pid=1006`（A+B）时，左侧题面是 A+B 的真实数据，编辑器里却是 `#1024 最大子段和` 的代码，提交必然 WA。评审一追问「为什么这题永远不过」就穿帮 | **代码跟题走**：`pid===1024` 用写死的那两份（视觉零回归），其它题用后端 `/api/problem/questions/{id}` 下发的 `codeTemplates.cpp`。这样「看到的题面 / 编辑器代码 / 提交去判的代码」三者同源 |

### 3.4.0 「代码跟题走」的判定规则（Day 4 新增，第 3 步复验修正）

工作台的编辑器内容必须与 `?pid=` 对应的题目一致：

```js
if (!this.isMother(this.problemId)) {         // 母题 ID 运行时解析，不写死
  const tpl = (d.codeTemplates && d.codeTemplates.cpp) || '';
  if (tpl) { this.code = tpl; this.codeFixed = tpl; }
}
```

提交时用 `this.code`（未修正）或 `this.codeFixed`（已应用行内批注），
**绝不再直接引用全局 `CODE_BUGGY` / `CODE_FIXED`**——那两个常量只服务母题。

> **⚠️ 母题 ID 不能写死数字（第 3 步复验抓到的真缺陷）**
> 曾经写死 `MOTHER_PID = 1024`，理由是「#1024 是最大子段和」。但 Day 3 导入 1000 道
> 真实题库后，**本库 #1024 是「最大异或和」，最大子段和是 #1001**。后果：
> 侧栏点「做题工作台」（无 `?pid=`）→ 题面写着「最大异或和」，编辑器里却是 Kadane 解法 → 必全 WA。
> 现在 `MOTHER_PID = 1001` 只是兜底，真实 ID 由 `resolveMotherPid()` 在 `init()` 里
> 按题名 `kw=最大子段和` 反查一次并缓存；判定统一走 `isMother(pid)`。
> **凡是「题目 ID / 表 ID」都不能写死**——题库是 Day 3 才灌进来的，原型里的假 ID 全部对不上。

> 同理，任何「页面里写死的示例数据」都要问一句：换了 `?pid=` / `?id=` 之后它还成立吗？
> 不成立就必须改成跟参数走。左栏题面（题号/题名/难度/标签/正文/格式/样例/限值）
> 就是同一个坑的第二处：原先整块是静态 HTML，已改为真实态读 `this.problem`。

### 3.4.1 MOCK 的 SSE 必须吐全六事件

`api/mock.js` 的 `/api/ai/chat/stream` **不能只走「正常路径」**。只吐 4 个事件的话，断网演示里**降级黄条与护栏折叠永远看不到**——而这两块正是论文的核心创新点，评审没法验证就等于没有。

三条分支必须都能触发（已由 `scripts/_probe_sse.mjs` 9 项守住）：

| 路径 | 触发条件 | 事件序列 |
| --- | --- | --- |
| 正常 | 普通提问 | `router_step → tool_calling → token×3 → done` |
| 降级 | 提问含「云端 / 降级 / 超时」 | 正常路径 + `fallback_notice`（`toChannel=local-qwen`, `elapsedMs=8100`） |
| 护栏 | 提问含「代码 / 完整 / 给我 / 题解」 | 正常路径 + 超长 `token` + `guardrail_rewrite`（`guardAction=2`, `OVER_LENGTH_CODE`） |

三条分支都必须以 `done` 收尾——**`done` 是配额的唯一写入口**。

### 3.4.2 adapter 层不得依赖 `window.OJ`

`s-workbench.html` **不引 `shell.js`**（它是独立的全屏工作台），所以那一页没有 `window.OJ`。
`adapter/**` 是「任何页面都能加载」的通用层，一旦内部写 `OJ.lvlName(...)` / `OJ.fmt(...)`，工作台就会抛 `OJ is not defined`——而工作台是 P0 主链路。

**规则**：`adapter/` 与 `api/` 只允许依赖 `window.adapter` / `window.api` 自己，不得反向依赖 `shell.js`。
需要的字典与工具函数在 `adapter/` 内自带一份，与 `shell.js` 同源同值（目前：`difficulty.LVL_NAMES`、`index.fmtNum`）。

### 3.4.3 `OJ` 的 fixtures getter 必须受 `isMock()` 守卫

`shell.js` 的 8 个 `OJ.xxx` getter **只在 `?mock=1` 时返回假数据**，非 mock 态返回空数组（或真实数据槽位）。

原实现无条件返回 fixtures，导致「不带 mock、已登录」时页面照常渲染同一份假数据——**真实态根本不存在**，演示时把参数删掉也看不出区别。这既违反 03 指南 §8.2「mock 与真实不混用」，也让「删参数切真实接口」这个答辩动作失去意义。

页面必须能处理空数组（走各自已有的空态）。**禁止**写 `OJ.problems[0].xxx` 这种不经判空的取值——`s-problems` 的 `hero` 与 `s-assignments` 的 `avgScore` 都因此抛过 pageerror。真实态由 `_probe_realmode.mjs`（15 页）守住。

### 3.4.4 会话残留：无 `?mock=` 时必须清干净

`?mock=1` 会写进 `sessionStorage`（跨页透传）。但**手打无参 URL 时要清掉**，否则先看过演示再切真实接口永远切不回来。

`boot.js` 的规则：
- 有 `?mock=` → 写 `sessionStorage.OJ_USE_MOCK = 1`
- 无 `?mock=` → 清 `sessionStorage.OJ_USE_MOCK`，并**只清 `mock-` 前缀的假 token**（真实 JWT 绝不动）

`localStorage.OJ_USE_MOCK` 是「用户手动开的长期开关」，不自动清（`api.http.setMock(false)` 是显式出口）。

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
| MyBatis-Plus | 3.5.7 | **必须用 `mybatis-plus-spring-boot3-starter`**（Boot 3 专用变体） |
| MySQL Connector | 8.3.0 | `com.mysql:mysql-connector-j` |
| spring-security-crypto | 由 Boot BOM 管理 | 只引这一个 jar 做 BCrypt，**不要引 spring-security 全家桶** |

### 4.2 编译与打包（宿主机没有 Maven，一律走容器）

```powershell
# 全量编译
pwsh scripts/build-backend.ps1

# 单模块编译（改一个服务时用这个，快得多）
docker run --rm -v "D:\毕设\agent oj\backend:/w" -v "$env:USERPROFILE\.m2:/root/.m2" `
  -w /w maven:3.9-eclipse-temurin-17 mvn -B -q -pl oj-problem-service -am compile

# 打包（产出 6 个 fat jar）
pwsh scripts/build-backend.ps1 -Package
```

首次约 3 分钟（下 BOM），之后有 `.m2` 缓存约 30 秒。
**改了 `oj-common-*` 必须 `install` 而不是 `compile`**，否则下游模块读到的是 .m2 里的旧 jar：

```powershell
docker run --rm -v "D:\毕设\agent oj\backend:/w" -v "$env:USERPROFILE\.m2:/root/.m2" `
  -w /w maven:3.9-eclipse-temurin-17 mvn -B -q -pl oj-common-core,oj-common-web,oj-common-api -am install -DskipTests
```

### 4.3 启动（宿主机 JVM，省内存）

```powershell
pwsh scripts/run-services.ps1 -Action start     # 启动 6 个服务并等 Nacos 注册（javaw，无弹窗）
pwsh scripts/run-services.ps1 -Action status    # 端口 + Nacos 健康 + 窗口状态
pwsh scripts/run-services.ps1 -Action stop      # 全停
pwsh scripts/run-services.ps1 -Action start -Only "gateway,teaching,problem,judge"   # 第 3 步按需启
pwsh scripts/run-services.ps1 -Action logs -Only judge -Tail 100 -Follow            # 跟日志
```

**内存预算**：每个服务统一 `-Xms128m -Xmx256m -XX:MetaspaceSize=64m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC`。
6 个全开常驻约 **1.2 GB**（不配默认会吃 6~8 GB，16 GB 机器直接卡死）。

**窗口**：`-Action status` 有一列「窗口」，正常应为 `none`。出现 `POPUP!` 说明
有服务用了 `java.exe` 而非 `javaw.exe`，按 §4.5.4 修。

**日志**：javaw 没有控制台，日志**只**在 `logs/<svc>.log`（logback 文件 appender）。
不再有 `<svc>.out.log` / `<svc>.err.log`——那两个是 shell 重定向产物，
`Win32_Process.Create` 本来就做不了重定向，Day 4 起统一改走文件。

### 4.4 后端约定

- **统一响应 `Result<T>`**：`{code, message, data, traceId, timestamp, success}`，`code` 与 HTTP 状态码严格同值，`success` 是 `code==200` 的派生字段。
- **错误码**：`ErrorCode` 实现 10 个常量（含 `200 SUCCESS`）。文档称「9 大」，但表格实际列 10 行，按表格逐字实现。**绝不新增状态码**；框架层的 405/413 等一律收敛到 400。
- **异常处理统一走 `oj-common-web` 的 `GlobalExceptionHandler`**：业务代码只 `throw BusinessException.xxx(...)`，不要 try-catch 后手工拼 Result。
- **网关不做 `StripPrefix`**：5 个服务的 Controller 自带完整前缀（如 `@RequestMapping("/api/teaching")`），网关原样透传。
- **`discovery.locator.enabled=false`**：全站只认手写的 6 个前缀，不出现 `/oj-teaching-service/**` 第二套路径。
- **Redis Key 禁止手写字符串**：一律走 `RedisKeys` 的静态方法。
- **Feign 必须给 `contextId`**：同名服务（`oj-problem-service` / `oj-teaching-service` 各被两个接口引用）不给 `contextId` 会 Bean 名冲突启动失败。
- **`username`/`password` 默认留空**：本机 Nacos 2.2.3 standalone 关闭鉴权，带凭据会反复请求 `/v1/auth/users/login` 拿 500，日志每 5s 刷 ERROR。
- **用户身份只从网关头取**：`UserContext.userId()/role()` 读 `X-User-Id` / `X-User-Role` / `X-Class-Id`。**服务层不重复解析 JWT**——令牌校验只发生在网关一处。
- **越权校验必须在服务层**：网关只能证明「你是谁」，不能证明「这条数据是不是你的」。所有涉及归属的 SQL 必须带 `WHERE user_id = currentUserId`。
- **时间字段一律 `Long` 毫秒时间戳**，字段名以 `At` 结尾；**时长以 `Ms` 结尾**；**内存 `memoryUsedKb`（KB）**。
- **MyBatis-Plus 分页**：`PaginationInnerInterceptor` 已在 `oj-common-web` 配好（`maxLimit=100`，与 `PageResult.normalizePageSize` 同口径）。**不要**再各服务配一遍。
- **判题逐点明细只有一份组装代码**：`JudgeServiceImpl.buildCaseDetails(submission, limit, maskHiddenOutputs)`。
  两个消费方脱敏要求相反，**必须靠参数区分，不许各写一份**：

  | 端点 | 消费方 | `maskHiddenOutputs` | 隐藏点 `expectedPreview/actualPreview` |
  | --- | --- | --- | --- |
  | `#21 GET /status/{id}` | 工作台判分链 | `false` | **下发**（本人刚提交的判题结果，要看得到输入输出对比） |
  | `#23 GET /submissions/{id}` | 历史回看 | `true` | `null`（契约 §4.3 防泄题） |

  历史上这两处各写一份，结果状态接口干脆一个预览字段都没回，工作台点开测试点只有「（隐藏）」。
  四种组合由 `JudgeCaseVisibilityTest` 锁住。**隐藏点的 `inputPreview` 仍只给前 100 字符。**

### 4.5 已知坑（都已踩过，别再踩）

| 坑 | 现象 | 正确做法 |
| --- | --- | --- |
| Nacos 只映射 8848 | 服务能注册但客户端永远连不上 | **必须同时映射 9848**（gRPC = 8848+1000） |
| go-judge 没开 privileged | `create environment builder failed: cgroup path is empty` 直接退出 | `privileged: true` + `cgroup: host` |
| go-judge 限值写在请求顶层 | `clone3: resource temporarily unavailable`（看起来像宿主机资源不足） | `cpuLimit/clockLimit/memoryLimit/procLimit/stackLimit` **必须写在每个 `cmd` 对象内部** |
| `Start-Process -ArgumentList` 传含空格路径 | `Unable to access jarfile D:\毕设\agent` | 数组里的路径要**自己裹双引号**：`"`"$jar`""` |
| **用 `Start-Process` 起常驻服务** | 日志里明明有 `Started XxxApplication`，但端口瞬间消失、进程全没 | **见 §4.5.1，这是最坑的一个** |
| 用 `cmd.exe /c java ... > log` 做重定向起服务 | 同上，进程一样会被收走，且日志文件是空的 | 同上，不要夹 shell |
| 改 init SQL 后重启容器 | 表结构没变 | init SQL **只在首次初始化数据卷时执行**，必须删 `data/mysql` 重建 |
| `prob_test_case` 重跑 | 30 行重建、自增 id 变化 | 唯一键含可空列 `deleted_at`（MySQL 里 NULL 不参与判重），脚本用「先 DELETE 旧行再 INSERT」 |
| 改 `oj-common-*` 后只 `compile` | 下游模块编译通过但运行时报 `NoSuchMethodError` | 改公共模块必须 `mvn install` |
| 服务启动时 `spring.datasource.url` 没配 | HikariCP 启动即报错 | Day 4 起 5 个业务服务已配好，网关/无库服务不要配 |
| **网关 `redis-check` 写成嵌套 yml** | 配置明明写了 `enabled: true`，注销后旧 token 却还能用，且**不报任何错** | 见 §4.5.2 |
| 服务加了 `oj-common-web` 但没加 `CommonWebConfig` | 业务异常（该 400/429 的）全部退化成 500 白页；`traceId` 与网关对不上 | 见 §4.5.3 |
| **用 `java.exe` 起常驻服务** | 每启一个服务弹一个黑色控制台窗口（实测 4 个服务 = 4 个 Windows Terminal 窗口 + 4 个 conhost 进程），批量启停时满屏弹窗、抢焦点 | **必须用 `javaw.exe`**，见 §4.5.4 |
| **`-D` 参数不加引号** | `java` 报 `Could not find or load main class .encoding=UTF-8`（`-Dfile.encoding=UTF-8` 被拆成 `-D` + `file.encoding=UTF-8`） | 每个 JVM 参数**单独裹双引号**，见 §4.5.4 |
| **`--logging.file.name` 放在 `-jar` 前面** | `Unrecognized option: --logging.file.name=...` + `Could not create the Java Virtual Machine` | 它是 **Spring Boot 应用参数**，必须放 `-jar <jar>` **之后**，见 §4.5.4 |

#### 4.5.1 常驻服务必须用 `Win32_Process.Create` 直接起，不能夹任何 shell

这是本项目踩得最久的一个坑，症状极具误导性：

**症状**：`pwsh scripts/run-services.ps1 -Action start` 跑完，`status` 显示 6 个服务全部 `healthy`；
但过一会儿（尤其是启动脚本所在的后台任务结束之后）再查，**6 个 java 进程全没了**，
而 `logs/*.out.log` 里清清楚楚写着 `Started GatewayApplication in 21.8 seconds`。

**根因**：Windows 的 **Job Object** 机制。
- `Start-Process` 起的子进程会挂在**当前 PowerShell 进程的 Job Object** 上。
  启动它的 shell（或被 harness 托管的后台任务）一退出 / 被 kill，
  Windows 就把整棵进程树一起收掉。
- 改用 `Invoke-CimMethod Win32_Process Create` 但外面包一层
  `cmd.exe /c "java ..." > log 2> err` —— **cmd.exe 这一层仍会被收走**，
  连带 java 一起死，而且日志文件是空的（cmd 没来得及 flush）。

**正确做法**：`Win32_Process.Create` **直接**起 `java.exe`，中间不夹任何 shell。

```powershell
$cmdLine = '"' + $javaExe + '" -Xms128m -Xmx256m -jar "' + $jar + '"'
$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmdLine }
# $r.ProcessId 就是 java 的 PID，直接写 pid 文件
```

**代价**：拿不到 stdout 重定向（WMI 不支持）。但 Spring Boot 日志本来就走
logback 控制台，需要看时用 `-Action logs` 或看进程控制台。
**比起「进程莫名其妙消失」，牺牲重定向完全划算。**

**验证方法**：启动后**等 30 秒再查一次**端口。只查一次会以为成功了——
job object 回收发生在父 shell 退出之后，有延迟。

**当前 `scripts/run-services.ps1` 已按此实现**，`Start-One` 里有完整注释，不要再改回 `Start-Process`。

---

#### 4.5.2 网关 `redis-check-enabled` 必须是扁平键

`GatewayAuthProperties` 里的字段是**布尔基本类型** `redisCheckEnabled`，
Spring Boot 的 relaxed binding 会把它解析成属性路径 `redis-check-enabled`。

```yaml
# ❌ 错：解析出 redis-check.enabled，需要属性类里有名为 redisCheck 的嵌套对象才能接住
oj:
  gateway:
    redis-check:
      enabled: true

# ✅ 对：解析出 redis-check-enabled，直接绑到 redisCheckEnabled 字段
oj:
  gateway:
    redis-check-enabled: true
```

**为什么这个坑特别危险**：写错时 Spring **静默忽略**，值永远停在字段默认值 `false`。
症状是「注销后旧 token 还能用」「换设备登录后旧 token 依然有效」——
看起来像 JWT 设计问题，实际是配置没生效。

**验证方法**（必须真验，不能看配置就以为生效）：

```powershell
# 1. 登录
$r = Invoke-RestMethod http://127.0.0.1:8080/api/auth/login -Method Post `
       -ContentType 'application/json' -Body '{"username":"20220101","password":"123456"}'
$tok = $r.data.accessToken
# 2. 注销
Invoke-RestMethod http://127.0.0.1:8080/api/auth/logout -Method Post `
  -Headers @{Authorization="Bearer $tok"} -ContentType 'application/json' -Body '{}'
# 3. 再用旧 token 访问 —— 必须 401。返回 200 就说明 redis-check 没生效
try { Invoke-RestMethod http://127.0.0.1:8080/api/auth/me -Headers @{Authorization="Bearer $tok"} }
catch { "PASS: $($_.Exception.Response.StatusCode.value__)" }
```

#### 4.5.3 依赖 `oj-common-web` 的服务必须加 `CommonWebConfig`

Spring Boot 默认只扫启动类所在包（`com.oj.xxx`），而 `oj-common-web` 的包是
`com.oj.common.web`，**不在扫描范围内**。

不显式加这一行：
- `GlobalExceptionHandler` 不注册 → 所有 `BusinessException` 退化成 500 白页，
  「code 与 HTTP 状态码严格同值」当场失效
- `TraceIdFilter` 不生效 → 响应里的 `traceId` 与网关响应头、日志三者对不上
- `MybatisPlusConfig` 不注册 → 分页参数被忽略，列表返回全表

每个依赖它的服务各放一个同款类（不改进启动类的 `scanBasePackages`，避免 5 个服务各写一套后漂移）：

```java
@Configuration(proxyBeanMethods = false)
@ComponentScan("com.oj.common.web")
public class CommonWebConfig { }
```

**当前已配置**：`oj-teaching-service` / `oj-problem-service` / `oj-judge-service`。
新增服务时必须照抄一份。

#### 4.5.4 启动 Java / Spring Boot 一律用 `javaw.exe`，禁止弹窗（Day 4 修，硬性）

> **这是本节最重要的一条。** 任何人（人或 Agent）在本项目里启动 JVM 进程，
> **都必须走 `scripts/run-services.ps1`**，不要自己手搓 `java -jar`。
> 手搓时若用 `java.exe`，每启一个服务就弹一个控制台窗口。

**根因**：`java.exe` 是**控制台子系统**（console subsystem）程序。Windows 在创建它时
会强制分配一个控制台——这个分配是内核行为，`Start-Process -WindowStyle Hidden`、
`Win32_Process.Create`、重定向到文件**都挡不住**。所以：
- 弹窗不是「脚本写得不好」，是 `java.exe` 的固有属性
- 每个 java 进程会带一个 `conhost.exe` 子进程，那就是那个黑窗口

**正确做法**：`javaw.exe` —— 同一个 JVM 的**GUI 子系统**版本。

| 维度 | `java.exe` | `javaw.exe` |
| --- | --- | --- |
| 控制台窗口 | ❌ 每进程一个 | ✅ 无 |
| `conhost.exe` 子进程 | ❌ 有 | ✅ 无 |
| JVM 行为 / 参数 / 类路径 | 相同 | 相同 |
| stdout / stderr | 有 | **无**（所以日志必须写文件） |
| 退出码 | 相同 | 相同 |

**配套要求：日志必须写文件。** javaw 没有控制台，`System.out` 直接丢弃。
本项目用 Spring Boot 的 `--logging.file.name` 落到 `logs/<svc>.log`：

```powershell
# 正确形态（scripts/run-services.ps1 已封装）
$argStr = ($JVM_OPTS | ForEach-Object { '"' + $_ + '"' }) -join ' '   # ① 每个 -D 单独加引号
$argStr += ' -jar "' + $jar + '"'                                    # ② jar 路径加引号
$argStr += ' "--logging.file.name=' + $appLog + '"'                  # ③ 应用参数放 -jar 之后
$cmdLine = '"' + $javaw + '" ' + $argStr

# ④ 用 Win32_Process.Create 直接起，中间不夹 cmd / PowerShell
Invoke-CimMethod -ClassName Win32_Process -MethodName Create `
  -Arguments @{ CommandLine = $cmdLine; CurrentDirectory = $root }
```

**三条容易踩的细节**（都实测复现过）：

1. **`-D` 参数必须单独加引号。** 否则 Windows 命令行解析把
   `-Dfile.encoding=UTF-8` 拆成 `-D` 和 `file.encoding=UTF-8`，
   java 报 `Could not find or load main class .encoding=UTF-8`。
2. **`--logging.file.name` 必须放在 `-jar <jar>` 之后。** 它是 Spring Boot
   应用参数，不是 JVM 参数。放前面 java 直接
   `Unrecognized option` + `Could not create the Java Virtual Machine`。
3. **不要夹 `cmd.exe /c`。** 除了会弹窗，`cmd` 这层还会被 Job Object 收走，
   连带 java 一起死（见 §4.5.1）。

**验证方法**（改完必须跑，不能只看脚本输出）：

```powershell
# 1. 看窗口列：必须全是 none
pwsh scripts/run-services.ps1 -Action status

# 2. 看有没有 conhost 子进程（应为空）
$pids = (Get-ChildItem logs\pids\*.pid | ForEach-Object { [int](Get-Content $_ -Raw).Trim() })
Get-CimInstance Win32_Process -Filter "Name='conhost.exe'" |
  Where-Object { $_.ParentProcessId -in $pids } | Select-Object ProcessId, ParentProcessId

# 3. 看窗口句柄（必须全是 0）
Get-Process -Id $pids | Select-Object Id, MainWindowHandle, MainWindowTitle

# 4. 看日志是否真的在写（javaw 无控制台，日志是唯一观测手段）
pwsh scripts/run-services.ps1 -Action logs -Only judge -Tail 20
```

**判定标准**：`status` 的窗口列全 `none`、conhost 查询为空、`MainWindowHandle` 全 0、
`logs/<svc>.log` 有内容且在增长。四条全满足才算「不弹窗」。

**如果 `javaw.exe` 找不到**（极少数精简 JDK 会缺）：脚本会打印黄色告警并退回
`java.exe`，此时**必然弹窗**。请补装完整 JDK 17，不要接受这个降级。

#### 4.5.5 Redis 排障只用 `DEL` 单个 key，**绝不 `FLUSHDB`**（第 6 步踩，硬性）

> **一句话**：`FLUSHDB` 会把 `oj:seq:{biz}` 展示号序列一起清掉，
> 下一条记录拿到 `S-1` / `A-0001` / `PL-0001`，与历史行**唯一键冲突** → `DuplicateKeyException` → 500。

**症状极具误导性**：接口报 500，日志里是 `Duplicate entry 'S-1' for key 'judge_submission.display_no'`，
看起来像判题服务的 insert 写错了，实际是**Redis 被清空了序列**。

**正确做法**：

```powershell
# ✅ 只删要删的那一个
docker exec oj-cloud-redis redis-cli DEL oj:rate:login:127.0.0.1
docker exec oj-cloud-redis redis-cli DEL oj:admin:audit:export

# ❌ 绝对不要
docker exec oj-cloud-redis redis-cli FLUSHDB
docker exec oj-cloud-redis redis-cli FLUSHALL
```

**误删后的恢复**（按库内各表最大值重 SET）：

```sql
-- ⚠️ 前缀长度不同：S- 是 2 位、A- 是 2 位、P- 是 2 位，PL- 是 3 位（SUBSTRING 起始位各不相同）
SELECT MAX(CAST(SUBSTRING(display_no,3) AS UNSIGNED)) FROM judge_submission         WHERE display_no REGEXP '^S-[0-9]+$';
SELECT MAX(CAST(SUBSTRING(display_no,3) AS UNSIGNED)) FROM teach_assignment        WHERE display_no REGEXP '^A-[0-9]+$';
SELECT MAX(CAST(SUBSTRING(display_no,3) AS UNSIGNED)) FROM teach_plagiarism_report WHERE display_no REGEXP '^P-[0-9]+$';
SELECT MAX(CAST(SUBSTRING(display_no,4) AS UNSIGNED)) FROM teach_plagiarism_task   WHERE display_no REGEXP '^PL-[0-9]+$';
```

```powershell
docker exec oj-cloud-redis redis-cli SET oj:seq:submission <max>
docker exec oj-cloud-redis redis-cli SET oj:seq:assignment <max>
docker exec oj-cloud-redis redis-cli SET oj:seq:plagiarism <max>        # 查重**报告** P-xxxx
docker exec oj-cloud-redis redis-cli SET oj:seq:plagiarism_task <max>   # 查重**任务** PL-xxxx
```

> **查重是两个独立序列**：`oj:seq:plagiarism`（报告）与 `oj:seq:plagiarism_task`（任务）。
> 只补一个还会撞——第 6 步实测先补了 `plagiarism` 又撞了 `plagiarism_task`，白跑两轮。

#### 4.5.6 网关白名单**禁用通配**，`/api/*/health` 会吞掉 `/api/admin/health`

`AntPathMatcher` 的 `/api/*/health` 会匹配 `/api/admin/health`。而这两者**同名不同义**：

| 路径 | 语义 | 权限 |
| --- | --- | --- |
| `/api/{teaching,problem,judge,ai}/health` | 「这个服务活着吗」的探活端点 | 匿名可访问（网关不注入身份头也无所谓，它们不校验） |
| `/api/admin/health` | 契约 §7.3.1「全站健康大盘」 | **仅 ADMIN** |

用通配的后果：网关对 `/api/admin/health` **跳过 JWT 校验、不注入 `X-User-Role`**，
admin 服务收到"无任何身份头" → 回 401。表现是**管理员拿着有效 token 也进不了管理端**，
且日志只有一行 401，完全看不出是网关放行的。

反过来的风险更大：一旦哪天 `/api/admin/health` 被写成"无头即放行"，通配就等于**把全站健康大盘裸奔到公网**。

**规则**：白名单只列真正该匿名可达的路径，**一条通配都不写**。

```yaml
# ❌ 错：连带放行了 /api/admin/health
whitelist:
  - /api/*/health

# ✅ 对：逐条列
whitelist:
  - /api/teaching/health
  - /api/problem/health
  - /api/judge/health
  - /api/ai/health
```

**验证方法**（必须四个角色都测，只看 ADMIN 200 会漏掉权限漏洞）：

```powershell
# ADMIN → 200 / TEACHER → 403 / STUDENT → 403 / 无 token → 401
# 另需确认 /api/problem/health 匿名仍 200（不要误伤探活端点）
```

#### 4.5.7 `/api/admin/**` 的 401 与 403 **必须分开**（`AdminGuard`）

| 情形 | 码 | 为什么不能合并 |
| --- | --- | --- |
| 无任何身份头 | **401** | 前端 `api/http.js` 收 401 会**清 token 并跳登录页** |
| 有身份但非 ADMIN | **403** | 收 403 只弹「没有权限」，不清 token |

合并成一律 403 的后果：**token 真过期时用户卡在页面上反复点，永远等不到自动跳登录**。

另有一步不能省：`/api/admin/health` 曾被网关通配放行（§4.5.6），此时服务侧收到的是**无身份头**，
必须判 401 而不是"无头即放行"。`AdminGuardTest` 用 6 个用例钉死这条（无头=401 / STUDENT=403 / TEACHER=403 / ADMIN=放行 / 大小写不敏感 / 调试开关不给越权后门）。

## 5. 数据与契约

### 5.1 19 张表归属

| 服务 | 表数 | 表名 |
| --- | ---: | --- |
| `oj-teaching-service` | 9 | `sys_user` `teach_class` `teach_assignment` `assignment_submission` `teach_student_score` `exam_monitor_event` `teach_plagiarism_task` `teach_plagiarism_report` `teach_plagiarism_segment` |
| `oj-problem-service` | 4 | `prob_knowledge_point`（字典） `prob_question` `prob_test_case` `prob_question_kp`（Day3 新增，多对多） |
| `oj-judge-service` | 2 | `judge_submission` `judge_sandbox_instance` |
| `oj-ai-agent-service` | 4 | `ai_model_config` `ai_model_role` `ai_chat_record` `ai_call_feedback` |

> **跨域只读查询先例**：`oj-teaching-service` 的 `AnalyticsRepository`（学情，第 6 步）与
> `PlagiarismRepository`（查重，第 5 步）都会 `SELECT` 别的域的库表（`judge_submission` /
> `ai_chat_record` / `prob_question` / `prob_test_case`），因为它们与教学表**同库**。
> **规矩：只读、不建 Entity/Mapper**（建了就是在教学域复制别人的表模型，列一改两边漂移）。
> `oj-admin-service` 同理：通道 CRUD / 审计 / 沙箱直连 MySQL，只有「这个服务现在活不活」走真实 HTTP 探活。

### 5.2 七类字典（核心，改字段前必看 `00-基线决策.md §5`）

- **判题状态**：`0 QUEUED / 1 COMPILING / 2 JUDGING / 3 AC / 4 PAC / 5 WA / 6 TLE / 7 MLE / 8 RE / 9 CE`（**int 为真值**）
- **难度**：`1 入门 / 2 简单 / 3 中等 / 4 较难 / 5 困难`（v1.0 是 3 档，v2.0 扩为 5 档）
- **语言**：`c` / `cpp` / `java` / `python`（`languageLabel`：`C++ 17` / `Java 17` / `Python 3`）
- **时间**：一律 `Long` 毫秒时间戳；时长一律 `xxxMs`
- **内存**：题目限制 `memoryLimitMb`（MB）；判题实测 `memoryUsedKb`（KB）；Go-Judge 返回字节 → `Math.round(rawBytes / 1024.0)`
- **ID 策略**：主键**不出现在 UI**，UI 只显示 `display_no`（如 `SUB-20481` / `S-20481`），前端永不自拼
- **题目状态**：`0 草稿 / 1 已发布 / 2 已归档`；合法迁移只有 `0→1`、`1→2`、`2→1`，**没有 `1→0` 撤回**

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

### 5.4 测试数据在磁盘，不在数据库（Day 3 最重要的设计决策）

| 项 | 事实 |
| --- | --- |
| 规模 | **6.95 GB / 9675 个文件 / 1002 个题目目录** |
| 位置 | 宿主机 `D:\毕设\agent oj\data\testcases\{problemId}\{seqNo}.in\|.out` |
| 容器内 | `/data/testcases`（docker-compose 已 `:ro` 挂载给 go-judge） |
| 数据库里 | `prob_test_case.input_text/output_text` **只存 ≤4096 字节的摘要**，供前端展示失败点对比 |
| 判题怎么读 | **必须读磁盘文件**。用 `prob_test_case.input_text` 比对 = 100% 误判 WA |

`prob_question.test_case_dir` 字段值是 `/data/testcases/{id}/`（容器内路径）。
**不要用 `LENGTH(input_text) == input_size` 做完整性断言**——它永远不等。

---

## 6. 判题沙箱协议（go-judge v1.13 · 本机实测，与官方文档不一致）

> **这一节是 Day 4 判题服务开工前必须读完的。** 所有结论来自 `scripts/_probe_gojudge*.py` 的实测，
> 不是从文档抄的。怀疑时先跑探针，不要凭想象改协议。

### 6.1 十条实测事实

| # | 事实 | 影响 |
| --- | --- | --- |
| 1 | **一次 `/run` 请求里多个 `cmd` 各自独立环境，文件系统不共享** | 不能靠「编译 cmd + 运行 cmd 放同一请求」共享产物 |
| 2 | `workdir = /w`；`/` 只读，`/tmp` 可写 | 所有产物写 `/w` 或 `/tmp` |
| 3 | **`files` 是位置语义**：第 0 项 = stdin，第 1 项 = stdout，第 2 项 = stderr。`name` 只是响应里的键名 | 写成 `[{name:"stdout"},{name:"stderr"}]` 会把 stdout 当 stdin，输出全空 |
| 4 | 响应是**单对象**（不是数组），字段 `status / exitStatus / time(ns) / memory(byte) / files{stdout,stderr} / error` | 老版本返回数组，解析要兼容 |
| 5 | `copyOut` 可用（相对 `/w` 或绝对路径），返回 `{文件名: 内容}` | 取编译产物用这个 |
| 6 | `copyIn` 是 **map**：`{"目标路径": {"content": "..."}}`。**copyIn by fileId 不可用**（404） | 别照文档写 `[{src, dst}]` 数组 |
| 7 | `status` 精确值：`Accepted` / `Nonzero Exit Status` / `Signalled` / `Time Limit Exceeded` / `Memory Limit Exceeded` / `Output Limit Exceeded` / `File Error` / `Internal Error` | 判定要用 `contains`，不要 `equals` |
| 8 | **`/w` 与 `/tmp` 都是 128 MB tmpfs**（`size=128m,nr_inodes=4k`），不是无限 | 本机 4 个 `.in` >100MB、156 个 >10MB，走 `copyIn` 直接 `no space left on device` |
| 9 | **`files[0]` 支持 `{"src": "/data/testcases/..."}` 直接挂载**，零传输开销 | 不知道的话大输入永远跑不了（205 MB 挂载耗时 0.0s） |
| 10 | **MLE 的 `exitStatus` 是 0** | 靠 exit code 判定 → MLE 被误判成 AC。必须以 `status` 字符串为准 |

### 6.2 编译 + 跑测试点的正确姿势（两阶段）

```
阶段 1（编译，1 个 cmd）
  copyIn:  {"/w/Main.cpp": {"content": 源码}}
  args:    g++ -O2 -std=c++17 -o /w/bin/m /w/Main.cpp
           && tar czf /w/art.tgz -C /w/bin . && base64 -w0 /w/art.tgz > /w/art.b64
  copyOut: ["art.b64"]
  → exitStatus != 0 或 status != "Accepted" ⇒ CE，errorMsg 取 files.stderr
  → File Error / Internal Error ⇒ **502**（沙箱故障，不要记成 CE）

阶段 2（跑测试点，N 个 cmd 并行，**分批 4 个/请求**防打爆 16G 内存）
  每个 cmd:
    copyIn:  {"/w/art.b64": {"content": <阶段1 取回的 base64>}}
    args:    cd /w && base64 -d art.b64 > art.tgz && tar xzf art.tgz && ./m
    files:   [{"src": "/data/testcases/{pid}/{seq}.in"},   ← 大输入必须用 src 挂载
              {"name":"stdout","max":...}, {"name":"stderr","max":...}]
    cpuLimit / clockLimit / memoryLimit / stackLimit / procLimit  ← 必须写在 cmd 内部
  → 一次请求跑完 N 个测试点，天然对应「逐测试点明细」
```

**语言模板（产物必须解包到 `/w`，否则运行路径对不上）**：
- `cpp`：`g++ -O2 -std=c++17 -o /w/bin/m /w/Main.cpp` → `./m`
- `java`：`javac -encoding UTF-8 -d /w/bin /w/Main.java` → `java -Xss256m -cp /w Main`
- `python`：`python3 -m py_compile /w/main.py` **且必须 `cp main.py /w/bin/`**
  （`py_compile` 产物落在 `/w/__pycache__`，不显式拷贝会让 tar 是空的 → 每点 RE。本机实测踩到过）

### 6.3 探针命令

```powershell
python scripts/_probe_gojudge5.py     # 协议最终确认（6 项）
python docker/judge/smoke_test.py     # 三语言冒烟（C++/Java/Python 各跑一次 A+B）
```

> ⚠️ `docker/judge/smoke_test.py` 的 `files` 写法是**正确的**（`[{content:stdin},{name:stdout},{name:stderr}]`），
> 但它把 `stdout` 和 `stderr` 两个 name 都声明了——按位置语义，第 1 项是 stdout、第 2 项是 stderr，恰好对。
> 若只声明一个 `name=stdout`，那个 fd 会变成 stdin，输出全空。这是本机踩过的坑。

---

## 7. 每次改动的标准动作（Checklist）

### 7.1 改前端

```
□ 读 docs/analysis/03-前端对接实施指南.md 对应页面章节
□ 确认只动「页尾 <script>」或「x-data 对象内部」，没碰 DOM/class/style
□ node --check <改动的 js>（语法）
□ node scripts/_probe_shots.mjs --out _shots/_verify   （视觉零回归）
□ node scripts/_probe_guard.mjs                        （守卫 14 项）
□ node scripts/_probe_realmode.mjs                     （真实态 15 页健壮性）
□ 手工过一遍：?mock=1 能交互；去掉 mock 跳 login
```

### 7.2 改后端

```
□ 读 docs/analysis/02-契约补全规范-v2.0.md 对应端点
□ 不改端口、不改 URL 前缀、不手写 Redis Key 字符串
□ pwsh scripts/build-backend.ps1        （mvn clean compile 必须 BUILD SUCCESS）
□ pwsh scripts/run-services.ps1 -Action restart
□ pwsh scripts/run-services.ps1 -Action status   （窗口列必须全 none，见 §4.5.4）
□ curl http://127.0.0.1:8080/api/<svc>/health    （经网关）
□ Nacos 服务列表 count 必须 = 启动的服务数
```

### 7.2.1 启动 / 停止服务的唯一入口

```
□ 一律 `pwsh scripts/run-services.ps1`，不手搓 `java -jar`
□ 不写 `cmd /c java ...`、不写 .bat
□ 启动后跑 -Action status，确认「窗口」列全 none
□ 确认 0 个 conhost 子进程（§4.5.4 验证方法）
□ 日志看 logs/<svc>.log（不是 .out.log / .err.log）
```

### 7.3 改数据库

```
□ 改 docker/mysql/init/init_oj_cloud.sql（或 patch_day3_design.sql，后者幂等可重跑）
□ docker compose down && Remove-Item -Recurse -Force .\data\mysql
□ docker compose up -d mysql && python scripts/verify-day1.py
□ 幂等性：脚本连跑 3 次必须全部 exit 0
```

### 7.4 提交前

```
□ git status 里没有 data/、logs/、target/、node_modules/
□ 交付说明已更新（docs/ 下）
□ 提交信息用 <type>(<scope>): <中文摘要>，type ∈ feat/fix/docs/chore/refactor/test
```

---

## 8. 验收命令速查

```powershell
# ── 基础设施（Day 1）──
docker compose ps                                  # 5 容器 healthy
python scripts/verify-day1.py                      # 31 项
python docker/judge/smoke_test.py                  # 三语言判题冒烟
python scripts/_probe_gojudge5.py                  # go-judge 协议 6 项

# ── 后端（Day 2-3）──
pwsh scripts/build-backend.ps1                     # 编译 + 打包
pwsh scripts/run-services.ps1 -Action status       # 端口 + Nacos 健康
curl.exe http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1`&pageSize=20
curl.exe http://127.0.0.1:8080/api/teaching/health # 经网关
curl.exe -i http://127.0.0.1:8080/api/teaching/students   # 应 401

# ── 第 3 步：认证 → 题库 → 判题（Day 4-7，已完成）──
python scripts/verify-day4.py                      # ⭐ 后端 77 项（走真实网关）
node scripts/_probe_day4_frontend.mjs              # ⭐ 前端 40 项（含真实判题）
node scripts/_probe_step3_acceptance.mjs           # ⭐ 手册「验收标志」逐字复现 16 项
node scripts/_probe_workbench_sameproblem.mjs      # 题面/代码/判题三者同源 7 项
node scripts/_probe_workbench_visible.mjs      # 用户视角：左栏跟题走 + 说明卡有真值 5 项
node scripts/_probe_realmode.mjs                   # 真实态 15 页不崩

# ── 第 4 步：AI 助教闭环（Day 8-11，已完成）──
# 前置：Ollama 已起且 num_ctx=8192（§2.2），gateway/teaching/problem/judge/ai 五个服务在跑
python scripts/verify-day8.py                      # ⭐ 后端 29 项（真实网关 + 真实本地模型）
node scripts/_probe_day8_frontend.mjs              # ⭐ 前端契约与竞态 43 项
node scripts/_probe_step4_acceptance.mjs           # ⭐ 手册「验收标志」逐字复现 26 项
node scripts/_probe_dom_structure.mjs              # ⭐ DOM/class/style 红线 4 项（对比 git HEAD）

# ── 第 6 步：学情看板 + 运维治理（Day 17-20，已完成）──
# 前置：5 容器 healthy + Ollama 已起（探活 #78 与查重 #63 要用），6 个服务在跑
docker exec oj-cloud-redis redis-cli DEL oj:rate:login:127.0.0.1
docker exec oj-cloud-redis redis-cli DEL oj:admin:audit:export
$env:PATH = "C:\Users\ZhuanZ.DESKTOP-PH97BKO\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python;" + $env:PATH
python scripts/verify-day17.py                     # ⭐ 后端 490 项（28 端点 + 反向守卫 + 密钥扫描）
node scripts/_probe_step6_acceptance.mjs           # ⭐ 手册「验收标志」逐字复现 37 项（5 页真实浏览器）
node scripts/_probe_dom_structure.mjs              # ⭐ DOM 红线 17 项 / 15 页

# ── 第 5 步：教学闭环 + 双阶段查重（Day 12-16，已完成）──
# 前置：5 容器 healthy + Ollama 已起（查重阶段二真调本地模型），6 个服务在跑
python scripts/verify-day12.py                     # ⭐ 后端 94 项（含新增的 #63 同步查重 + #71 rerun）
node scripts/_probe_step5_acceptance.mjs           # ⭐ 手册「验收标志」逐字复现 18 项（7 页真实浏览器）

# ⚠️ 第 5 步两套必须**串行**跑，且与其它套件也别并行：
#    同一账号重复登录会顶掉旧 token；另有 oj:rate:login:{ip} 1 分钟窗口（约 12 次）。
#    撞 429 就等 60 秒，或 docker exec oj-cloud-redis redis-cli DEL oj:rate:login:127.0.0.1

# ⚠️ 并发提示：第 4 步探针会真调用本地模型并真提交判题。
#    并发数过高时 Ollama 会排队超时，偶发失败请先串行重跑一次再判定。
# 下面是手工验证的最小闭环（答辩演示前自查用）
# 1. 登录取 token
$r = Invoke-RestMethod -Uri http://127.0.0.1:8080/api/auth/login -Method Post `
       -ContentType 'application/json' -Body '{"username":"20220101","password":"123456"}'
$tok = $r.data.accessToken
# 2. 题库列表（带 token）
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/problem/questions?page=1&pageSize=5" `
  -Headers @{ Authorization = "Bearer $tok" }
# 3. 提交判题
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/judge/submit -Method Post `
  -Headers @{ Authorization = "Bearer $tok" } -ContentType 'application/json' `
  -Body '{"problemId":1006,"assignmentId":0,"language":"cpp","code":"..."}'
# 4. 轮询状态直到 status >= 3
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/judge/status/<submissionId> `
  -Headers @{ Authorization = "Bearer $tok" }

# ── 前端（Day 2-3）──
python -m http.server 3000                         # 在 prototype/app/ 下
node scripts/_probe_shots.mjs                      # 视觉零回归
node scripts/_probe_guard.mjs                      # 登录守卫 14 项
node scripts/_probe_workbench.mjs                  # 工作台 16 项
node scripts/_probe_sse.mjs                        # SSE 六事件 9 项
node scripts/_probe_stage0.mjs                     # 底座缺陷回归 18 项
node scripts/_probe_realmode.mjs                   # 真实态 15 页健壮性

# ── 一键全验 ──
pwsh scripts/verify-day2.ps1
```

---

## 9. 常见任务：从零把环境跑起来

```powershell
cd "D:\毕设\agent oj"

# 1. 中间件（5 容器）
docker compose up -d
docker compose ps                                  # 等 5 个 healthy

# 2. 本地模型（已在 D:\ollma-models，只在第 4 步需要）
$env:OLLAMA_MODELS = "D:\ollma-models"
ollama list                                        # 应看到 qwen2.5-coder:7b / 1.5b
ollama run qwen2.5-coder:7b                        # 首次会拉，之后秒开
# ⚠️ 必须 num_ctx=8192，否则吃满 8G 显存（见 §2.2）

# 3. 后端
pwsh scripts/build-backend.ps1 -Package
pwsh scripts/run-services.ps1 -Action start -Only "gateway,teaching,problem,judge"
pwsh scripts/run-services.ps1 -Action status        # 窗口列必须全 none

# 4. 前端
cd prototype\app; python -m http.server 3000
# 浏览器开 http://127.0.0.1:3000/index.html
# 演示模式：任意页加 ?mock=1
```

> **全程只用 `pwsh`。** 不要 `cmd`、不要 `.bat`、不要 `powershell` 5.1。
> 不要手搓 `java -jar`（会弹窗），一律走 `run-services.ps1`。见 §2.1.1 / §4.5.4。

---

## 10. 任务进度与下一步

| 步骤 | 内容 | 状态 |
| --- | --- | --- |
| 第 1 步（Day 1） | 容器化 + 19 表建表 + 种子数据 + Ollama | ✅ |
| 第 2 步（Day 2-3） | 前端 Stage 0 底座 + 后端 Maven 骨架 + 6 服务注册 Nacos | ✅ |
| 第 3 步（Day 4-7） | 【第一纵深 P0-A】登录 → 题库 → 异步判题闭环 | ✅ 已完成 |
| 第 4 步（Day 8-11） | 【第二纵深 P0-B】端云 AI Agent + SSE 六事件流 | ✅ 已完成 |
| 第 5 步（Day 12-16） | 【第三纵深 P1】出题/作业/考试防作弊/双阶段查重 | ✅ 已完成 |
| 第 6 步（Day 17-20） | 【第四纵深 P2】学情看板 + 运维治理 | ✅ 已完成。**全站 87 接口 100% 覆盖** |

### 第 3 步交付物（已完成）

| 天 | 内容 | 端点 |
| --- | --- | --- |
| Day 4 | 认证 + 题库只读 | `#1 login` `#2 logout` `#3 refresh` `#4 me` `#5 menus` `#6 questions` `#7 questions/{id}` |
| Day 5-6 | Go-Judge 适配 + RabbitMQ 异步判题 | `#19 self-test` `#20 submit` `#21 status` `#22 submissions` `#23 submissions/{id}` `#24 trace` `#25 run-sample` |
| Day 7 | 前端工作台判题联调 | `login.html` `shell.js` `s-problems.html` `s-workbench.js` `s-submissions.html` |

**第 3 步验收结果**：后端 77/77、前端 40/40、真实态 15/15、工作台 8/8、视觉零回归 15/16。
详见 `docs/day4-7-判题闭环交付说明.md`。

**验收标志（已达成）**：在工作台提交一段故意写错初始值的 C++ 代码，判题服务真实编译、
跑完全部测试点、逐格点亮判分链；点失败格能看到真实的输入/期望/实际输出对比。

**第 4 步开工前必须先做**：`ollama run qwen2.5-coder:7b` 且 **`num_ctx=8192`**
（默认 32768 会吃满 8GB 显存，见 §2.2）。

### 第 4 步交付物（已完成，2026-10-27 复验修正后全绿）

| 天 | 内容 | 端点 |
| --- | --- | --- |
| Day 8 | 端云多模型工厂 + 前置校验 | `#26 workspace-init` `#28 quota` `#29 channels` |
| Day 9-10 | 多智能体编排 + 行号注入 + `@Tool` + 流式护栏 | `#27 chat/stream`（六事件） |
| Day 11 | 前端工作台 AI 抽屉联调 | `s-workbench.html` 右栏 + `api/sse.js` |
| 补充 | 会话历史 / 反馈 | `#30 sessions` `#31 feedback` |

**第 4 步验收结果**：后端 29/29、前端契约 43/43、验收标志逐字复现 26/26、
DOM 红线 4/4、真实态 15/15、视觉零回归 15/16（唯一差异 `index` 为已登记 PRD 文案修正）。
详见 `docs/day8-11-AI助教闭环交付说明.md`。

**验收标志（已达成）**：紧接真实 WA 提交，点阶段③胶囊 → 聊天框先浮现
`Router：排错诊断 · 填空伪代码 · qwen2.5-coder:7b` → `Tool：fetchFailedTestCaseTool ·
已拉取首个失败测试点 #7 脱敏特征`，随后真实本地模型流式吐字引导「初始值 `dp` 和
`ans` 都是 0 … 全负数输入会怎样」；受限配额下 `done.remainingQuota` 由 **3 扣到 2**。

**第 4 步代码与验收入口**：`docs/day8-11-AI助教闭环交付说明.md`，
`scripts/verify-day8.py`（真实网关与模型，29 项）、
`scripts/_probe_day8_frontend.mjs`（前端状态与流契约，43 项）、
`scripts/_probe_step4_acceptance.mjs`（手册验收标志逐字复现，26 项）、
`scripts/_probe_dom_structure.mjs`（DOM 红线，4 项）。
Windows 中正在运行的 JAR 会锁住 repackage 的重命名操作；重新打包前先通过唯一服务脚本停止待打包服务。

> **本轮复验抓到的两条新事实（写进红线旁注）**
> 1. **脚本里的题目 ID 同样不许写死。** §3.4.0 只讲了前端，验收脚本当时漏了：
>    `verify-day8.py` 写死 `1024` 并提交 Kadane 代码，而本库 `#1024` 是「最大异或和」，
>    导致「真实满分 AC」永不成立。现与前端同口径按题名反查，查不到直接失败退出。
> 2. **`x-text` 不能加在裸文本节点上。** 难度文字在基线里是 `.lvl` 的裸文本，
>    包一层 `<span x-text>` 就新增了元素节点 = 破 §3.1 红线（视觉只差 5 px，极难发现）。
>    这类「要改既有文本」的场景一律走 `syncProblemFoot()` 的写法：
>    只写 `firstChild.textContent`，不加节点。结构红线现在由
>    `_probe_dom_structure.mjs` 可执行断言守住。

### 第 6 步交付物（已完成，2026-10-27 深夜）

| 天 | 内容 | 端点 |
| --- | --- | --- |
| Day 17-18 | 学生/班级多维学情：周趋势、易错题排行、知识点掌握度、热力图、风险学生、AI 用量 | `#51~#62`（12 个，`AnalyticsController` + `TeachingInsightController`） |
| Day 19-20 | 模型通道 CRUD + 真实探活 + 降级恢复、AI 审计与 CSV 导出、沙箱治理、全站健康与大盘 | `#72~#87`（16 个，`oj-admin-service` 4 个 Controller + `HealthController`） |

**第 6 步验收结果**：后端 **490/490**、验收标志逐字复现 **37/37**、DOM 红线 **17/17**、
视觉零回归 **16/16 逐字节相同**、前五步全量回归 **0 回归**
（day1 31/31、day4 77/77、day8 29/29、day12 **94/94 含新增 #63/#71**、
step3 16/16、step4 26/26、step5 18/18、realmode 15/15）。
详见 `docs/day17-20-学情看板与运维治理交付说明.md`。

**验收标志**：学生在学情页看到的知识点掌握度**逐项等于** `#57 masteryPercent`（不再是硬编码的
`82/64/41/73/55`）；教师看板周趋势柱数等于 `#53 days` 长度、风险学生姓名逐个对上 `sys_user`；
管理端「测试」按钮真实发出 `POST /channels/{id}/probe` 并回显真实 `latencyMs`；
审计页点导出拿到 **5450 字节真实 CSV**（12 列表头逐字对上）。

**第 6 步代码与验收入口**：`scripts/verify-day17.py`（490 项）、
`scripts/_probe_step6_acceptance.mjs`（37 项）、`scripts/_probe_dom_structure.mjs`（17 项 / 15 页）。

> **第 6 步抓到的四个真缺陷（都写进 §0.1 / §4.5）**
> 1. **`#54` 下发 `题目 N` + `difficulty:null`**：`problemMeta` 只从 `teach_assignment.problem_ids` 灌，
>    而 103/119 条提交是 `assignment_id=0` 的自由练习——这些题从未被加载过元数据。
> 2. **`#75` 部分更新全 400**：`ChannelCommandDTO.roleBindings` 默认 `new ArrayList<>()` 而非 `null`，
>    "没传"与"传空数组"不可区分 → 任何不含 roleBindings 的局部更新都被拒（打中契约 §7.2.4）。
> 3. **网关白名单 `/api/*/health` 吞掉 `/api/admin/health`** → 管理员有 token 也 401（§4.5.6）。
> 4. **`AdminGuard` 把 401/403 合并** → token 过期时用户卡住不跳登录（§4.5.7）。
>
> 另有 **`FLUSHDB` 清掉展示号序列**导致 `Duplicate entry 'S-1'`（§4.5.5），
> 以及前端联调抓到的 **`ttftPeak=NaN`（稀疏数组 map 跳过空洞）**、
> **`null < 50 === true` 导致"没数据"的知识点被涂成薄弱色** 两个 JS 语义坑。

---

## 11. 与 AI 协作的具体约定

### 11.1 执行纪律：禁止阻塞等待，用 wait 并并行推进

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

### 11.2 多 Agent 并行开发契约（Day 4 起启用）

当多个 Agent 同时改同一个仓库时，**必须遵守以下规则，否则必然互相覆盖**：

1. **写入范围必须先声明、且互不重叠。**
   - 一个 Agent 一个模块目录（`backend/oj-judge-service/` 等），不要两个 Agent 同时改同一个文件。
   - 共享模块（`oj-common-*`）**由 Lead 独占**，其他 Agent 只读。
   - 前端 `shell.js` / `boot.js` / `api/**` 由一个人统一改，页面文件可以分。
2. **共享模块改完必须 `mvn install`**，否则下游模块编译的是 .m2 里的旧 jar。
3. **契约先定后写。** 跨服务字段（DTO）改动前先在 `docs/analysis/02` 里确认，不要各自发明字段名。
4. **不启动服务。** 服务启停由 Lead 统一做，避免端口抢占与日志交错。Agent 只负责「编译通过」。
5. **交付时报告三件事**：改了哪些文件、编译结果、以及**你无法验证的部分**（诚实说明比假装通过有价值）。
6. **发现别人的代码有问题，报告给 Lead，不要直接改**——直接改会破坏对方的上下文。

### 11.3 通用协作约定

1. **先读再改。** 改任何文件前先 `read`；改契约相关字段前先读 `docs/analysis/02`。
2. **不要猜版本号、端口、路径。** 全部在 §2 和 §4.1 里。
3. **不要引入新依赖。** 前端零构建，后端版本已锁。确实需要时先说明理由并等确认。
4. **不要为了让演示好看而造假数据。** 见 §3.4。这是本项目最容易犯、也最伤答辩的错。
5. **改完必须自己跑验收。** 不要交付「应该可以」的代码——§7 的 checklist 是硬要求。
6. **不确定就问。** 尤其是涉及「改不改原型 DOM」「要不要新增表」「要不要升版本」这三类决定。
7. **提交信息说人话。** 写清楚「改了什么、为什么」，不要 `update`、`fix bug` 这种。
8. **过程性产物不入库。** 截图基线（`_day2_baseline/`、`_shots/_day2/`）入库存档；单轮迭代的几百张中间截图不入库（见 `.gitignore`）。
9. **长输出不要用 `cat`。** 用 `read` 的 `offset`/`limit` 分段读；大范围搜索用 `grep`/`glob`，不要 `Get-ChildItem -Recurse | Select-String`。
10. **脚本里的中文输出不要被下游解析。** 探针脚本一律落 `report.json` 等结构化文件，验收脚本读文件而不是正则匹配 stdout（本项目真实踩过：终端编码不同导致中文正则匹配失败）。

---

## 12. 交付前自检（每次给用户答复前）

```
□ 我改了哪些文件？每个都在我的写入范围内吗？
□ 编译通过了吗？（不是「应该能过」，是真的跑了 mvn）
□ 服务起来了吗？接口真的 curl 通了吗？
□ 启动服务用的是 javaw 吗？status 的窗口列是不是全 none？（§4.5.4）
□ 所有命令都是 pwsh 7 吗？有没有误用 cmd / powershell 5.1？（§2.1.1）
□ 前端 ?mock=1 还能跑吗？视觉零回归跑了吗？
□ 我有没有为了让结果好看而造假数据 / 伪造通过？
□ 有没有遗留的后台 job / 未关闭的终端会话？
□ 交付说明更新了吗？git 提交了吗？
```
