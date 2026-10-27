# Day 4-7 交付说明 —— 第 3 步【第一纵深 P0-A】登录 → 题库 → 异步判题闭环

> 对应《从 0 到 1 的 6 步标准开发顺序与操作手册》第 3 步
> 执行日期：2026-10-27　｜　验收：`python scripts/verify-day4.py`（77 项）+ `node scripts/_probe_day4_frontend.mjs`（40 项）

---

## 1. 一句话结论

**第 3 步验收标志已达成**：在工作台提交一段故意写错初始值的 C++ 代码，判题服务真实编译、跑完全部测试点、
逐格点亮判分链，点测试点能看到真实的输入/期望/实际输出对比。

| 验收维度 | 结果 |
|---|---|
| 后端端到端（走真实网关 :8080） | **77 / 77 通过** |
| 前端真连（含真实判题） | **40 / 40 通过** |
| 手册「验收标志」逐字复现（`_probe_step3_acceptance`） | **16 / 16 通过** |
| 工作台「题面/代码/判题」同源 | **7 / 7 通过** |
| 15 页真实态健壮性（不白屏、不报错） | **15 / 15 通过** |
| 工作台既有行为（防回归） | **16 / 16 通过** |
| 视觉零回归 | **15 / 16 逐字节相同**，0 console error |
| 全量编译 | **10 模块 BUILD SUCCESS** |

> 视觉比对唯一的差异是 `index.html`（PRD 微创修正 #1 的文案改动，Day 2 已确认，
> 差异只落在 `bbox=(270,375,444,389)` 那一小块）。
>
> **复验补记**：上面 77 + 40 项跑的是「A+B 3 个测试点全 AC」这条省事路径，
> 并未覆盖手册验收标志要的「母题 + 10 测试点 + 隐藏点失败」。按手册原话逐字复现时
> 抓到 3 个真缺陷（母题 ID 串题 / 左栏题面写死 / 点测试点看不到输入输出），
> 已全部修复并补了 3 个专用探针 + 1 个单元测试。详见 **§8 复验补记**。

---

## 2. 这一步到底做了什么

手册第 3 步要求「只启动 gateway + teaching + problem + judge 4 个服务，打通学生刷题判题的核心闭环」。
本次把**后端 12 个端点 + 前端 5 个文件**全部打通，且**没有一行假数据**。

### 2.1 端点覆盖

| # | 端点 | 服务 | 状态 |
|---:|---|---|---|
| 1 | `POST /api/auth/login` | teaching | ✅ |
| 2 | `POST /api/auth/logout` | teaching | ✅ |
| 3 | `POST /api/auth/refresh` | teaching | ✅ |
| 4 | `GET /api/auth/me` | teaching | ✅ |
| 5 | `GET /api/auth/menus` | teaching | ✅ |
| 6 | `GET /api/problem/questions` | problem | ✅ |
| 7 | `GET /api/problem/questions/{id}` | problem | ✅ |
| 19 | `POST /api/judge/self-test` | judge | ✅ |
| 20 | `POST /api/judge/submit` | judge | ✅ |
| 21 | `GET /api/judge/status/{id}` | judge | ✅ |
| 22 | `GET /api/judge/submissions` | judge | ✅ |
| 23 | `GET /api/judge/submissions/{id}` | judge | ✅ |
| 24 | `GET /api/judge/submissions/trace` | judge | ✅ |
| 25 | `POST /api/judge/run-sample` | judge | ✅ |

### 2.2 新增模块

| 模块 | 作用 |
|---|---|
| `backend/oj-common-web/` | 5 个 Servlet 服务的公共基座：`GlobalExceptionHandler`（业务异常统一转 Result，code 与 HTTP 同值）、`UserContext`（读网关注入的 X-User-Id）、`MybatisPlusConfig`（分页拦截器）、`TraceIdFilter`（桥接网关 traceId） |

> **为什么单独一个模块而不是塞进 `oj-common-core`**：core 刻意不依赖 `spring-boot-starter-web`
> （否则 WebFlux 网关会因 classpath 出现 spring-webmvc 直接启动失败）。
> 而上面这些能力全都需要 servlet API，只能拆开。

---

## 3. 三个必须记住的技术事实

### 3.1 go-judge v1.13 的协议与官方文档不一致（实测 9 条）

这一条是本次最大的坑。**官方文档写的用法直接照抄会全部失败**，所有结论来自
`scripts/_probe_gojudge*.py` 的实测。

| # | 事实 | 照文档写会怎样 |
|---:|---|---|
| 1 | 一次 `/run` 里多个 `cmd` **各自独立环境，文件系统不共享** | 编译 cmd + 运行 cmd 放同一请求 → `no such file or directory` |
| 2 | workdir = `/w`；`/` 只读 | 往 `/` 写文件 → `Read-only file system` |
| 3 | **`files` 是位置语义**：第 0 项 = stdin，第 1 项 = stdout，第 2 项 = stderr | 只声明 `name=stdout` → 那个 fd 变成 stdin，输出全空 |
| 4 | 响应是**单对象**不是数组 | 按数组解析 → 越界 |
| 5 | `copyIn` 是 **map** `{"dst": {"content": ...}}` | 按文档写数组 → 400 unmarshal 错 |
| 6 | **`copyIn` by fileId 不可用**（404） | 想靠 `copyOutCached` 的 fileId 传产物 → 404 |
| 7 | `/w` 与 `/tmp` 都是 **128 MB tmpfs** | 大输入（本机 4 个 >100MB）→ `no space left on device` |
| 8 | `files[0]` 支持 `{"src": "/data/testcases/..."}` **直接挂载** | 不知道的话大输入永远跑不了（205MB 挂载耗时 0.0s） |
| 9 | **MLE 的 `exitStatus` 是 0** | 靠 exit code 判定 → MLE 被误判成 AC |

### 3.2 判题的两阶段调用链

```
阶段1 编译（1 cmd）
  copyIn : {"/w/Main.cpp": {"content": 源码}}
  args   : g++ ... && tar czf /w/art.tgz -C /w/bin . && base64 -w0 /w/art.tgz > /w/art.b64
  copyOut: ["art.b64"]
  → status != "Accepted" 或 exitStatus != 0 ⇒ CE
  → File/Internal Error ⇒ 502（沙箱故障，不记成 CE）

阶段2 跑测试点（N cmd 并行，分批 4 个防打爆内存）
  每 cmd: copyIn {"/w/art.b64": {...}}
          files  [{src:"/data/testcases/{pid}/{seq}.in"}, {name:stdout}, {name:stderr}]
          args   "cd /w && base64 -d art.b64 > art.tgz && tar xzf art.tgz && ./m"
          cpuLimit/clockLimit/memoryLimit/stackLimit/procLimit  ← 全在 cmd 内部

阶段3 逐点比对：读磁盘 {pid}/{seq}.out，normalizeOutput（去行尾空白 + CRLF + 末尾空行）
阶段4 汇总：oiScore = Σ 通过点 score；全通过 = AC，部分通过 = PAC
```

### 3.3 测试数据在磁盘，不在数据库

| 项 | 值 |
|---|---|
| 规模 | **6.95 GB / 9675 个文件 / 1002 个题目目录** |
| 位置 | `data/testcases/{problemId}/{seqNo}.in\|.out` |
| 数据库里 | `prob_test_case.input_text/output_text` **只存 ≤4096 字节摘要** |
| 判题怎么读 | **必须读磁盘文件**。用 `input_text` 比对 = 100% 误判 WA |

---

## 4. 前端真连的四个关键设计

### 4.1 数据槽位：`OJ._problems`

页面从接口拿到数据后**写 OJ 的槽位**，不写闭包变量：

```js
OJ._problems = (await api.http.get('/api/problem/questions')).list
```

`OJ` 被 Alpine 的 `reactive()` 代理，赋值会触发依赖追踪，模板里的 `OJ.problems` 自动重渲染。
用闭包变量存的话 Alpine 追踪不到，页面会**永远停在空态**——这是真连时最难查的坑。

`?mock=1` 时 getter 完全不看槽位，永远返回 fixtures，保证演示与真实彻底隔离。

### 4.2 BASE 三档解析

```
window.OJ_API_BASE 显式覆盖  >  同源（网关托管前端）  >  本地开发默认 :8080
```

用 `location.hostname` 拼而不是写死 `localhost`，局域网访问也能用。
前端跑 `python -m http.server 3000` 时开箱即用，不用改代码。

### 4.3 adapter 同时认契约名与 mock 短名

`acRate` / `ac`、`triedCount` / `tried`、`myStatus` / `status`、`oiScore` / `score`、
`passCaseCount` / `pass`、`submittedAt` / `at` —— 两组都认。

**mock 与真实走同一个 adapter 是 Stage 0 的核心承诺**，分叉成两个函数就会出现
「演示好看、真实炸」。

### 4.4 代码跟题走（修掉一个设计缺陷）

工作台的编辑器代码原本恒为母题 `#1024 最大子段和` 的解答，打开 `?pid=1006`（A+B）
也提交它 → 必然 WA，而左侧题面却是 A+B 的真实数据。

现在：
- `pid === 1024` → 用写死的两份（缺陷版 / 修正版），视觉零回归不变
- 其它题 → 用后端 `/api/problem/questions/{id}` 下发的 `codeTemplates.cpp`

这样「看到的题面 / 编辑器里的代码 / 提交去判的代码」三者始终同源。

---

## 5. 本次修掉的真实 Bug（都是实测发现的，不是推演）

| # | 现象 | 根因 | 修法 |
|---:|---|---|---|
| 1 | 网关配置写了 `redis-check.enabled: true`，注销后旧 token 却还能用 | `GatewayAuthProperties` 字段是扁平 boolean `redisCheckEnabled`，嵌套写法解析出 `redis-check.enabled` 接不住，**静默忽略** | yml 改成扁平 `redis-check-enabled: true` |
| 2 | `s-submissions` 整页白屏 | 真实接口 `displayNo` 是字符串 `"S-20"`、`id` 是数字，排序写的是 `a.id.localeCompare(b.id)` | 适配器加 `idStr`，排序/筛选统一走它 |
| 3 | 前端请求打到静态服务器 404 | `api/http.js` 的 `BASE` 恒为 `''`（同源） | 三档解析（见 §4.2） |
| 4 | 工作台打开非母题必然 WA | 编辑器代码恒为母题解答 | 代码跟题走（见 §4.4） |
| 5 | Python 判题全部 RE | `py_compile` 产物落在 `/w/__pycache__`，打包 `/w/bin` 得到空 tar | 显式 `cp main.py /w/bin/` |
| 6 | `case_details` 全为空 | 重构执行器时漏了 `result.setCases(outcomes)` | 集成测试直接抓到并修复 |
| 7 | 服务启动后过一会儿全没了 | `Start-Process` 的子进程挂在当前 PowerShell 的 Job Object 上，shell 退出即被静默回收 | 见 `074f8a0` 提交 |

---

## 6. 验收命令

```powershell
# 0. 前置：5 容器 healthy + 4 服务已启动
docker compose ps
pwsh scripts/run-services.ps1 -Action start -Only "gateway,teaching,problem,judge"

# 1. 后端端到端（77 项，走真实网关）
python scripts/verify-day4.py

# 2. 前端真连（40 项，含真实判题）
node scripts/_probe_day4_frontend.mjs

# 3. 真实态健壮性（15 页不白屏不报错）
node scripts/_probe_realmode.mjs

# 4. 工作台既有行为（8 项，防回归）
node scripts/_probe_workbench.mjs

# 5. 视觉零回归（15/16 逐字节相同，index 为预期文案修正）
node scripts/_probe_shots.mjs

# 6. 判题沙箱协议（怀疑判题行为异常时先跑这个）
python scripts/_probe_gojudge5.py
python docker/judge/smoke_test.py
```

### 手工演示路径（答辩用）

```
1. 浏览器开 http://127.0.0.1:3000/login.html
2. 账号 20220101 / 密码 123456 → 登录
3. 自动跳到 s-problems.html，看到 964 道真实题目（不是 12 道假数据）
4. 点任意题目 → 抽屉显示真实题面、样例、时限、测试点数
5. 点「进入做题」→ s-workbench.html?pid=xxx
6. 写代码 → 点【提交评测】（或 Ctrl+Enter）
7. 判分链逐格点亮，WA 时点红格看真实的输入/期望/实际对比
8. 顶部「提交记录」→ 看到刚才那次提交，点开看代码与逐测试点明细
```

---

## 7. 已知边界（不是缺陷，是本期取舍）

| 项 | 说明 |
|---|---|
| `admin` / `ai` 服务未启动 | 第 3 步不需要。按需启动是省内存的核心技巧（见 agent.md §2.3） |
| 教师端 / 考试 / 查重 / 学情页 | 第 3 步范围外，仍是原型态（真实态下显示空态，不报错） |
| `/inner/**` 未校验 `X-Inner-Token` | 靠「不经网关暴露」兜底，方案定稿后加 Filter 即可，端点签名不变 |
| 跨服务直连 | 判题服务直连 `prob_question.pass_count/submit_count`，题库服务直连读 `judge_submission`。都标了 `【跨服务直连·本期妥协】`，分库后改 Feign |
| 判题 3 秒防抖锁 | `run-sample` 与 `self-test` 共用同一把锁。连续调用会 429，这是正确行为 |
| 首次编译冷启动 | go-judge 首次编译有开销，偶发首点 TLE（重跑即 AC）。后续可加预热 |

---

## 8. 复验补记（2026-10-27 晚间）：把「验收标志」逐字跑了一遍

`verify-day4.py` 77/77 与 `_probe_day4_frontend.mjs` 40/40 全绿，但它们跑的是
「**A+B 题 3 个测试点全 AC**」这条最省事的路径。手册第 3 步的验收标志写的是另一件事：

> 输入一段故意把初始值写成 `ans = 0` 的 C++ 代码 → 10 个测试点格子点亮 8 个绿格、
> 第 3 个红格（WA，得分 80），**点击第 3 格能看到真实的输入输出对比**。

于是补了探针 `scripts/_probe_step3_acceptance.mjs` 逐字复现这段，**当场抓到 3 个真缺陷**
（原测试没覆盖到，因为都没走「母题 + 多于 3 个测试点 + 隐藏点失败」这条路径）：

| # | 缺陷 | 根因 | 修法 |
|---:|---|---|---|
| 1 | 侧栏点「做题工作台」，打开的是 **#1024 最大异或和**，编辑器里却是最大子段和的 Kadane 解法 | `s-workbench.js` 写死 `MOTHER_PID = 1024`。Day 3 导入 1000 道真实题库后，**#1024 早已不是最大子段和，真实的「最大子段和」是 #1001** | `MOTHER_PID` 降级为兜底值并改成 1001；真实 ID 由 `resolveMotherPid()` 运行时按题名 `kw=最大子段和` 反查一次并缓存；母题判定统一走 `isMother(pid)` |
| 2 | 左栏题面永远写着「#1024 最大子段和」，打开 `?pid=1006`（A+B）也不变 | 左栏题号/题名/难度/标签/正文/格式/样例/限值**全部是写死的静态 HTML**，`loadMine()` 拉回来的 `this.problem` 只用于取 `codeTemplates`，从没渲染 | 给既有节点加 `x-text` / `x-show`（只加绑定、不加节点，兜底文案等于原文案），真实态由 `this.problem` 驱动；`?mock=1` 首屏逐字节不变 |
| 3 | 提交缺陷代码后，点测试点只能看到「输入 — / 期望（隐藏） / 实际 —」 | ① `#21 GET /status/{id}` 的 `caseDetails` **只回状态/耗时/内存，一个预览字段都没有**，而工作台的 `this.cases` 唯一数据源就是它；② callout 只认首个失败点那一格，且 `mouseleave` 就消失，**「点」根本无法把某一格钉住** | ① 状态接口与详情接口共用 `buildCaseDetails(submission, limit, maskHiddenOutputs)`：状态接口 `false`（本人刚提交的判题结果，必须看得到对比），详情接口 `true`（保持契约 §4.3 隐藏点屏蔽）；② 新增 `pinnedCase` + `pinCase(i)`，点一下钉住、再点取消，`calloutSeq/calloutCase` 优先取钉住的那格 |

> 脱敏边界没有放宽：`showCaseOutputs(maskHiddenOutputs, isPublic)` 收敛成一个函数，
> 四种组合由 `JudgeCaseVisibilityTest` 锁住。隐藏点的**输入**仍只给前 100 字符，
> 历史详情里的隐藏点期望/实际输出仍为 `null`。

### 新增/变更的验收命令

```powershell
node scripts/_probe_step3_acceptance.mjs      # ⭐ 手册第 3 步验收标志逐字复现（16 项）
node scripts/_probe_workbench_sameproblem.mjs # 题面/代码/判题三者同源（7 项）
node scripts/_probe_workbench_visible.mjs     # 用户视角：左栏跟着题走 + 说明卡有真值（5 项）
```

### 本轮回归结果

| 套件 | 结果 |
|---|---|
| `verify-day4.py` 后端 77 项 | **77 / 77** |
| `_probe_day4_frontend.mjs` 前端 40 项 | **40 / 40** |
| `_probe_step3_acceptance.mjs` 验收标志 16 项 | **16 / 16**（修复前 6/16） |
| `_probe_workbench_sameproblem.mjs` | **7 / 7**（修复前 3/7） |
| `_probe_workbench_visible.mjs` | **5 / 5**（修复前 2/3） |
| `_probe_workbench.mjs` 工作台防回归 | **16 / 16** |
| `_probe_realmode.mjs` 真实态 15 页 | **15 / 15** |
| `_probe_guard.mjs` / `_probe_sse.mjs` | **14 / 14** / **9 / 9** |
| `_probe_shots.mjs` 视觉零回归 | **15 / 16 逐字节相同**，唯一差异仍是 `index.html` 的既有文案修正；s-workbench 已恢复到基线 |
| `mvn -pl oj-judge-service test` | **4 / 4**（含新增 `JudgeCaseVisibilityTest`） |
| 全量 `mvn compile`（10 模块） | **BUILD SUCCESS** |

> `_probe_stage0.mjs` 的 4a 项（"带真实 token 不被踢去登录页"）在本机 **4 个服务全部启动**时
> 会失败——它用的是假 token `real.jwt.token`，真后端会正确回 401 并让 `boot.js` 跳登录页。
> 这条用例成立的前提是「后端不可达」。已实测：把本轮改动 stash 掉后同样失败，**不是回归**。

---

## 9. 下一步（第 4 步预览）


第 4 步【第二纵深 P0-B】攻克论文核心创新点：端云 AI Agent + SSE 六事件流。

| Day | 内容 | 端点 |
|---|---|---|
| 8 | `DynamicAiModelFactory` 端云多模型动态工厂 + 前置校验 | `#26` `#28` `#29` |
| 9-10 | 多智能体编排、行号注入、`@Tool`、流式护栏 | `#27` |
| 11 | 前端工作台 AI 抽屉联调（六事件渲染） | — |

**开工前必须先做**：`ollama run qwen2.5-coder:7b` 且 **`num_ctx=8192`**
（默认 32768 会吃满 8GB 显存，见 agent.md §2.2）。
