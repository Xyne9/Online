# 管理端 2 页 + 门户 + 全站框架 —— 字段级数据需求盘点

> 盘点对象：`prototype/app/a-models.html`、`prototype/app/a-audit.html`、`prototype/app/index.html`、`prototype/app/shell.js`、`prototype/app/_SPEC.md`
> 比对基准：`《系统详细设计与接口契约规范（Detailed Design & API Specification）》.md`（9 张 MySQL 表 L15-L190 / 6 类 Redis Key L194-L203 / 7 个 REST 接口 L215-L441 / Feign·MQ·Go-Judge L445-L566）
> 判定口径：**A** = 现有接口直接覆盖；**B** = 现有表可派生但无接口；**C** = 现有表缺字段；**D** = 无任何表落点；**E** = 纯前端派生可算

---

# 第一部分：逐页盘点

## 页面：a-models.html（管理·模型通道管理）

`<body data-role="admin" data-page="a-models" data-crumb="系统|模型通道">`，`x-data="modelsPage()"`。
页内数据来自 `shell.js` 的 `OJ.models`（4 条）+ 页内常量 `STATE / TODAY / HOST / MATCH / NOTE / CFG / TREND / degradeEvents`（L622-L665）。

### 数据需求表

| # | 页面模块 / 交互点 | 所需数据（字段级） | 类型与取值域 | 数据来源判定 | 现有契约覆盖情况 | 缺口说明 |
|---|---|---|---|---|---|---|
| M01 | 通道健康主带·通道名（4 块） | `channel_name` | VARCHAR(64) | B | `ai_model_config.channel_name`（L158）有字段 | 全契约无任何「查询通道列表」接口，`ai_model_config` 只能被 Redis `oj:ai:config:channels` 内部缓存读取 |
| M02 | 通道健康主带·状态徽标 | 通道运行态 `on / degrade / off` | 枚举 3 值 | C | `ai_model_config.is_enabled`（L165）TINYINT 0/1 | 只有「启用/停用」，**没有 `degrade`（降级中）这一运行时健康态**；也无 `last_check_time`、无连续失败计数 |
| M03 | 通道健康主带·状态说明文案 | 状态原因短句，如「连续 3 次超时 8s · 已回落本地」「鉴权失败 401 · 08:12 起离线」 | 自由文本 | C | 无 | `ai_model_config` 无 `health_note` / `last_error` / `last_error_at` 字段 |
| M04 | 通道健康主带·「自动降级」开关 | 全局自动降级开关 `autoDegrade: bool` | Boolean | D | 仅表级 `timeout_seconds DEFAULT 8`（L164）；Redis `oj:ai:config:channels` 是 ai_model_config 的缓存 Hash | 无「全局降级策略」表/配置项；开关关闭后「超时请求直接失败」的语义无落点 |
| M05 | 通道健康主带·摘要行 | `healthSummary` = `4 条通道 · N 条需要处理` | 派生字符串 | E | — | 由 `state` 聚合，前端可算；依赖 M02 |
| M06 | 主带脚·云端/本地通道计数 | `provider_type` 分组计数（3 云端 / 1 本地） | INT | B | `provider_type`（L159）`OLLAMA / OPENAI_COMPATIBLE` 可分组 | 需聚合接口 |
| M07 | 主带脚·今日调用总量 | 按通道、按自然日 COUNT（`1180 + 542 + 78 + 40 = 1840`） | INT | B | `ai_chat_record.create_time`（L186）+ `selected_channel`（L180）可 GROUP BY | 无统计接口；且无「按日分区/汇总表」，直接 `COUNT(*)` 扫全表 |
| M08 | 主带脚·降级次数 | `is_fallback = 1` 的当日计数（12） | INT | B | `ai_chat_record.is_fallback`（L182）有 | 无统计接口 |
| M09 | 主带脚·平均 TTFT | 平均首 token 时延 `0.8s`（本地 0.42s / 云端 1.2s） | DECIMAL 毫秒 → `x.xxs` | C | **`ai_chat_record` 无任何耗时字段** | 需新增 `ttft_ms`（且要按 `is_fallback` 拆本地/云端） |
| M10 | 通道表·通道 host 小字 | `127.0.0.1:11434` / `api.deepseek.com`（去协议头） | VARCHAR | B | `base_url`（L160）可裁掉 `http(s)://` | 无接口返回 |
| M11 | 通道表·厂商列 | 「Ollama · 本地」「DeepSeek · 云端」「阿里云 · 云端」「智谱 · 云端」 | 枚举 4 值 | C | 无 `vendor` 字段；`provider_type` 只有 `OLLAMA / OPENAI_COMPATIBLE` 两值，**表达不出 3 家云厂商的区分** | 需新增 `vendor_name` 或把 `channel_code`（ollama_local/deepseek/qwen/zhipu）当厂商用——但 `channel_code` 是 UNIQUE 主键语义，新增通道即冲突 |
| M12 | 通道表·承担角色列 | `roles: string[]` ∈ {Router, Tutor, Guardrail}，可多选（如 `['Router','Guardrail']`） | 数组 1-3 项 | C | `role_binding`（L163）只有 `SYSTEM_FIXED / TUTOR_OPTIONAL` 两值 | 两值模型 = 「Router+Guardrail 固定本地」vs「Tutor 可选」，**无法表达三者的独立组合**（如「只有 Tutor」「Tutor+Guardrail」）；原型 4 条通道里 2 条是 `Tutor 备用通道`、1 条 `Router / Guardrail`，两值勉强够，但新增弹窗允许任意组合 → 契约接不住 |
| M13 | 通道表·TTFT 列 | 每通道滚动平均 TTFT（`0.42s` / `1.12s` / `1.48s` / `1.35s`） | DECIMAL | C | 无 | 同 M09 |
| M14 | 通道表·今日调用列 | 每通道当日调用数（1180 / 542 / 78 / 40） | INT | B | 同 M07 分组 | 同 M07 |
| M15 | 通道表·成本列 | 累计/当日费用 `¥214.80` / `¥0` | DECIMAL(10,2) | C | 无单价、无 `cost`、无 token 计量 | **成本 = 单价 × token，两个因子都没有**；本地 Ollama `¥0` 尚可用 `provider_type=OLLAMA` 硬编码，云端三家无解 |
| M16 | 通道表·「测试」按钮 | 单通道连通性探测结果 + 探测耗时 | `{ok:bool, ttftMs:int, errorCode?:int}` | D | 无健康探针接口；Go-Judge `/run` 与 AI 通道无关 | 需新增 `POST /api/admin/channels/{code}/probe` |
| M17 | 状态筛选条 + 计数 | `全部/在线/降级/离线` 四档 + `countBy(k)` | 派生 INT | E | — | 前端按 `state` 过滤计数（依赖 M02 的 C 类字段） |
| M18 | 关键词搜索 | `kw` 匹配 `name + vendor + id` | String | E | — | 前端过滤 |
| M19 | 表头排序 | 按 `ttft`（`parseFloat`）/ `today` 升降序，默认 `today desc` | — | E | — | 前端排序 |
| M20 | 分页条 | `共 N 条通道` + 禁用上/下一页 | — | E | — | 只有 4 条，**原型未做真实分页**，纯静态文案 |
| M21 | 运行概览·路由分布条 | 近 24h 按「云端 Tutor / 本地 Router / 降级回落」三桶计数：`1325 / 386 / 129`，占比 `72% / 21% / 7%` | INT + 百分比 | C | `ai_chat_record` 只记录「助手对话」，`is_fallback` 可切「直连/降级」两桶 | **Router 的调用不落 `ai_chat_record`**（它是路由决策，不是对话），因此「本地 Router 386」无任何表可数；三桶口径无字段 |
| M22 | 运行概览·全站 TTFT 趋势 | 24 个逐小时柱（高度百分比）+ 峰值 `1.26s` + 均值 `0.80s` | 24×INT | C | 无 ttft | 依赖 M09 |
| M23 | 运行概览·近期降级事件（时间/主体） | `t`（`23:34`）+ `n`（`周叙 · ③ 排错诊断`） | TIME + 用户名 + 阶段 | B | `create_time` + `user_id`→`sys_user.real_name` + `stage_type`（L176）可派生 | 无接口；`is_fallback=1` 过滤后按时间倒序取 3 条即得 |
| M24 | 运行概览·近期降级事件（耗时） | `d`（`8.1s` / `8.4s` / `8.2s`） | DECIMAL 秒 | C | 无 | 需 `fallback_cost_ms` 或复用新增的 `ttft_ms` |
| M25 | 运行概览·右上角 tag | 「自动降级已开启」 | 派生文案 | E | — | **原型里该 tag 是静态字符串，未与 `autoDegrade` 联动** |
| M26 | 详情抽屉·kvcard `base_url` | 完整 URL（含协议） | VARCHAR(255) | B | `base_url`（L160）有 | 无单通道详情接口 |
| M27 | 详情抽屉·kvcard `model` | 实际模型标识：`qwen2.5-coder:7b` / `deepseek-chat` / `qwen-max` / `glm-4-plus` | VARCHAR(64) | B | `model_name`（L162）有，注释明确「模型标识」 | **两套命名需分清但都能对上**：`channel_name`（L158）= 前端展示名（如 `DeepSeek-V3`），`model_name` = 模型标识（`deepseek-chat`）；原型的 `OJ.models.name` ↔ `channel_name`，`CFG[id].model` ↔ `model_name`。⚠️ 唯一偏差：契约 `channel_name` 示例带 emoji 前缀（`🟢本地极速(Qwen2.5-Coder)`），原型是纯文本 `Qwen2.5-Coder 7B`——展示层需统一 |
| M28 | 详情抽屉·kvcard `timeout` | `8000ms` | INT 毫秒 | B | `timeout_seconds`（L164）**单位是秒、DEFAULT 8** | **单位冲突**：原型全套用毫秒（`8000`、`form.timeout=8000`、`${timeout}ms`），契约是秒；直接对接会把 8 秒渲染成 8000 秒 |
| M29 | 详情抽屉·kvcard `max_tokens` | `2048 / 4096` | INT | C | 无 | 需新增 `max_tokens` |
| M30 | 详情抽屉·kvcard `temperature` | `0.2 / 0.3 / 0.3 / 0.4` | DECIMAL(3,2) | C | 无 | 需新增 `temperature` |
| M31 | 详情抽屉·kvcard「并发上限」 | `8 / 16 / 8 / 6 路` | INT | C | 无 | 需新增 `max_concurrency`；此值也是 a-audit 沙箱「并发上限 4」的同族概念 |
| M32 | 详情抽屉·kvcard「承担角色」 | `roles.join(' · ')` | 数组 | C | 同 M12 | 同 M12 |
| M33 | 详情抽屉·近 24h TTFT 柱图 | 每通道 24 个逐小时 TTFT 值 + `maxOf` / `avgOf` / `barH` | 24×INT | C | 无 | 依赖 M09，且需要「按小时降采样」的聚合接口 |
| M34 | 详情抽屉·最近调用（基础列） | `at` / `who` / `stage` / `guard` 各 5 条 | TIME + 用户名 + 枚举 + 枚举 | B | `create_time` / `user_id` / `stage_type` / `guardrail_action` 均可 | 过滤条件是 `c.model.includes(m.match)`（按模型名模糊匹配），契约无「按通道查最近调用」接口 |
| M35 | 详情抽屉·最近调用 TTFT 列 | `${(c.ttft/1000).toFixed(2)}s` | INT 毫秒 | C | 无 | 同 M09 |
| M36 | 详情抽屉·空态 | 「近 7 天没有调用」判定 | `COUNT(*) WHERE create_time > now-7d` | B | 可算 | 无接口 |
| M37 | 新增弹窗·通道名称 | `form.name`，非空校验 | VARCHAR(64) | B | `channel_name` 有 | 无 `POST /channels` 接口 |
| M38 | 新增弹窗·厂商下拉 | 4 选项：`Ollama · 本地` / `DeepSeek · 云端` / `阿里云 · 云端` / `智谱 · 云端` | 枚举 4 值 | C | 同 M11 | 同 M11；且 `channel_code` 是 UNIQUE，新增第 5 条云通道时该填什么无定义 |
| M39 | 新增弹窗·承担角色 checkbox | Router / Tutor / Guardrail 多选，至少 1 项 | 数组 | C | 同 M12 | 同 M12 |
| M40 | 新增弹窗·接口地址 | `form.base`，非空，placeholder `https://api.deepseek.com`（**无 `/v1` 后缀**） | VARCHAR(255) | B | `base_url` 有 | 无写接口；且契约未约定「是否含 `/v1`、是否含 `/chat/completions`」 |
| M41 | 新增弹窗·API Key | `form.key`，`type=password`，placeholder `sk-…` | VARCHAR(255) | B | `api_key`（L161）`DEFAULT ''` | 字段有，但**契约未定义加密方式**（原型提示「API Key 加密存储，仅服务端调用」，表注释只写 `DEFAULT ''`）；也未规定「读取时永不回显」 |
| M42 | 新增弹窗·超时（ms） | `form.timeout = 8000` | INT 毫秒 | B | `timeout_seconds` | 同 M28 单位冲突 |
| M43 | 新增成功后「等待首次连通校验」 | 校验任务状态 `pending / passed / failed` | 枚举 | D | 无 | 无「探针任务」表，也无校验结果字段 |
| M44 | 错误态文案 | 「网关返回 502，暂时读不到通道配置」「健康探针请求超时」 | 错误码语义 | D | 全局状态码只有 `200/400/401/403/429`（L211） | **无 502/503/504 与「探针超时」的错误契约**，前端错误态无法被后端触发条件对上 |

### 交互与写操作清单

| 操作 | 触发位置 | 是否写库 | 需要的接口语义 |
|---|---|---|---|
| 切换「自动降级」开关 | 通道健康带右上 `x-model="autoDegrade"` + `@change="onDegradeSwitch()"` | 是（应写全局配置） | `PUT /api/admin/routing/auto-degrade`，body `{enabled:bool}`；返回 `{enabled, degradeThresholdSeconds}`。契约无 |
| 刷新通道健康 | 主带「刷新状态」按钮 `refreshAll()` | 否 | `GET /api/admin/channels/health`，返回 `[{channelCode, state:on\|degrade\|off, note, ttftMs, todayCalls, costYuan, lastCheckAt}]`。契约无 |
| 单通道连通性测试 | 表行内「测试」`test(m)` / 抽屉脚「测试连通」 | 否（应落探测日志） | `POST /api/admin/channels/{channelCode}/probe`，返回 `{ok, ttftMs, httpStatus, errorCode, message}`；超时应返回可区分于 502 的错误码。契约无 |
| 单通道「重试」（degrade→on） | 健康带降级块内「重试」`retry(m)` | **是**（应解除降级、写状态） | `POST /api/admin/channels/{code}/recover`，返回 `{state:'on'\|'degrade', note}`。契约无 |
| 启用 / 停用通道 | 表行内锁图标、抽屉脚「停用通道/启用通道」`toggleState(m)` | 是 | `PATCH /api/admin/channels/{code}` body `{isEnabled:bool}`。契约无（`is_enabled` 字段有，接口无） |
| 打开通道详情 | 整行点击 `open=m` / 编辑图标 | 否 | `GET /api/admin/channels/{code}`，返回全量配置（含 M26-M33）。契约无 |
| 新增通道 | 「新增通道」→ 弹窗 → `save()` | 是 | `POST /api/admin/channels`，body `{channelCode, channelName, vendorName, providerType, baseUrl, apiKey, modelName, roleBinding[], timeoutMs, maxTokens, temperature, maxConcurrency}`。契约无；且 `channel_code` UNIQUE 与「新增第 5 条通道」天然冲突 |
| 错误态重试 | 「重新检查」`retryAll()` / 「重试」 | 否 | 复用健康查询接口。契约无 |
| 筛选 / 搜索 / 排序 / 翻页 | 筛选条、表头、分页条 | 否 | 前端本地完成，无需接口 |
| 查看沙箱 | 无 | — | — |

> **写操作实现现状**：`save()` / `toggleState()` / `retry()` / `onDegradeSwitch()` / `refreshAll()` **全部是 `setTimeout` 改内存 + `OJ.toast` 提示，无一处 `fetch`/`XMLHttpRequest`/`EventSource`**（已全文件 grep 确认）。页面加载也只是 `OJ.models.map()` + 300ms 骨架屏。

### 枚举 / 状态机

| 枚举 | 取值 | 出处 | 契约对应 |
|---|---|---|---|
| 通道状态 `state` | `on` 在线 / `degrade` 降级 / `off` 离线 | `STATE`（L622）、`stateName()`（L719-723） | `is_enabled` 只有 0/1，**缺 degrade** |
| 状态筛选 `stateFilters` | `all` / `on` / `degrade` / `off`，带计数 | L657-660 | — |
| 承担角色 `roles` | `Router` / `Tutor` / `Guardrail`（可多选） | `CFG`+`form.roles`、`m.role.split(' / ')` | `role_binding` 只 2 值，**不可多选** |
| 厂商 `vendor` | `Ollama · 本地` / `DeepSeek · 云端` / `阿里云 · 云端` / `智谱 · 云端` | 新增弹窗 select | 无字段 |
| 通道 ID | `local-qwen` / `cloud-ds` / `cloud-qwen` / `cloud-zhipu` | `OJ.models.id` | `channel_code` 约定为 `ollama_local / deepseek / qwen / zhipu` → **命名不一致，需映射表** |
| 护栏结果（抽屉最近调用） | `pass` 通过 / `rewrite` 改写 / `block` 拦截 | `guardName()` | `guardrail_action` 0/1/2 |

**状态机（隐式）**：
```
off --启用--> on --探测超时/连续失败--> degrade --重试成功--> on
on  --手动停用--> off
degrade --手动停用--> off
```
依据：`toggleState()`（L776-786）只做 `off↔on`，`retry()`（L751-765）只做 `degrade→on`，`STATE` 初值里 `cloud-qwen` 停在 `degrade`、`cloud-zhipu` 停在 `off`。契约 `ai_model_config` 无状态列，**该状态机完全无落点**。

### 实时性 / 分页 / 排序 / 筛选

- **实时性**：无轮询、无 SSE、无 WebSocket。`refreshAll()` 是 800ms 假延迟；骨架屏 300ms（`?skeleton=1` 钉住 60s）。健康状态靠「进入页面时读一次」。
- **分页**：`pager` 只有 `共 ${channels.length} 条通道` + 两个 `disabled` 按钮 + 一个 `pg on` 的「1」按钮（L401-407），**无真实分页**。4 条通道不需要分页，但接口语义上仍应返回全量或约定 `pageSize`。
- **排序**：两个可排序列 `ttft`（`parseFloat`）与 `today`（数值），默认 `{k:'today', d:-1}`；`pickSort` 同列反转方向、换列时 `today` 默认降序、`ttft` 默认升序（L741-744）。前端排序，无接口参数。
- **筛选**：状态四档（`st`）+ 关键词（`kw`，匹配 `name+vendor+id`，`toLowerCase` 后 `includes`）；「清空筛选」按钮 `x-show="st!=='all' || kw"`。`?state=on|degrade|off` 可预置筛选（`init()` L681-682）。
- **图表**：全部为静态 SVG/CSS 柱条，`barH()`（L734-737）把 24 个 TTFT 值线性映射到 28%-100% 高度，`maxOf/avgOf` 纯前端计算。

---

## 页面：a-audit.html（管理·AI 调用审计与沙箱监控）

`<body data-role="admin" data-page="a-audit" data-crumb="系统|调用审计">`，`x-data="auditPage()"`。
两页签：`tab==='audit'`（AI 调用审计）/ `tab==='sandbox'`（沙箱容器），`#sandbox` hash 可直达（`init()` L850）。数据来自 `OJ.aiCalls`（10 条）+ `MORE_CALLS`（6 条）= 16 条，`OJ.sandbox`（5 条）+ `BOX_STATE`（6 键）/`BOX_EXTRA`（L715-720）。

### 数据需求表

| # | 页面模块 / 交互点 | 所需数据（字段级） | 类型与取值域 | 数据来源判定 | 现有契约覆盖情况 | 缺口说明 |
|---|---|---|---|---|---|---|
| A01 | 护栏主带·今日拦截数 | `guardrail_action = 1` 当日 COUNT（硬编码 `7`） | INT | B | `ai_chat_record.guardrail_action`（L185）0/1/2 | 无统计接口；且 `1` 的语义是「输入防注入阻断」，原型「拦截」指**输出侧整段题解**（见 A16）→ 口径需重新对齐 |
| A02 | 护栏主带·今日改写数 | `guardrail_action = 2` 当日 COUNT（硬编码 `21`） | INT | B | 同上，`2` = 「输出流超 5 行代码块熔断折叠」 | 同上 |
| A03 | 护栏主带·拦截率 | `拦截 / 今日调用 = 7 / 1840 ≈ 0.4%` | DECIMAL 百分比 | E | — | 前端可算（A01 / A05） |
| A04 | 护栏主带·拦截原因分布 | 3 类原因计数：`泄漏率 4 / 越狱特征 2 / 敏感词 1`，横条宽度 `v/7*100%` | 3×INT | C | `guardrail_action` 是**动作**不是**原因**；无 `guardrail_reason` / 命中规则 ID / 敏感词表 | 需新增 `guardrail_reason TINYINT(1泄漏率/2越狱特征/3敏感词)` 或独立 `ai_guardrail_hit` 明细表；当前 3 类原因**一个都数不出来** |
| A05 | 主带脚·今日调用 | `todayTotal: 1840`（`data` 里静态常量，**非 computed**） | INT | B | `ai_chat_record` 当日 COUNT | 无接口 |
| A06 | 主带脚·降级次数 | `12` | INT | B | `is_fallback=1` COUNT | 无接口 |
| A07 | 主带脚·平均 TTFT | `0.8s`（云端 1.2s · 本地 0.42s） | DECIMAL | C | 无 | 同 M09 |
| A08 | 主带脚·覆盖人数 | `48 人` | INT | B | `COUNT(DISTINCT user_id)` | 需聚合接口 |
| A09 | 调用表·时间列 | `at`（`23:41:08`），可排序 | TIME | B | `create_time`（L186）需格式化 `HH:mm:ss` | 需接口做格式化或返回 ISO |
| A10 | 调用表·用户列 | `who`（姓名：林昭/苏晚/…） | VARCHAR | B | `user_id` → `sys_user.real_name`（L21）JOIN | **`ai_chat_record` 只存 `user_id`**，展示名要跨表 JOIN；契约无联表查询接口 |
| A11 | 调用表·阶段列 | `stage` ∈ `① 读题拆解 / ② 编码引导 / ③ 排错诊断 / ④ AC 复盘` | ENUM 4 值 | B | `stage_type`（L176）1/2/3/4 + 注释有中文映射 | 无接口 |
| A12 | 调用表·模型列 | `model`（`DeepSeek-V3` / `Qwen2.5-Coder` / `qwen-max` / `glm-4-plus`） | VARCHAR | B | `actual_model`（L181） | 无接口；同 M27 的「显示名 vs 配置名」问题 |
| A13 | 调用表·路由列 | `route` ∈ `cloud`（云端 tag） / `local`（本地 tag） | ENUM 2 值 | B | `is_fallback` 只能表达「是否回落」，直连云端的判定要靠 `selected_channel` / `actual_model` 是否命中本地模型名 | 无 `route` 字段；`is_fallback=0` 既可能是云端直连也可能是本地直连（如 Router 固定本地）→ **二值字段无法还原三态语义** |
| A14 | 调用表·TTFT 列 | `${(c.ttft/1000).toFixed(2)}s`，可排序 | DECIMAL 毫秒 | C | 无 | 同 M09 |
| A15 | 调用表·Tokens 列 | `tok`（1240 / 2460 / 610 …），可排序 | INT | C | **`ai_chat_record` 无 `prompt_tokens` / `completion_tokens` / `total_tokens`** | 需新增三列（或至少 `total_tokens`）；这也是 M15 成本、A07 单次成本的共同前置 |
| A16 | 调用表·护栏列 | `pass` 通过 / `rewrite` 改写 / `block` 拦截；`guard!=='pass'` 行加 `.is-hit` | ENUM 3 值 | B | `guardrail_action` 0/1/2 | **语义错位**：契约 `1` = 输入侧防注入阻断、`2` = 输出侧超 5 行熔断折叠；原型的 `block` 是输出侧整段题解丢弃、`rewrite` 是输出改写。映射应为 `0→pass`、`2→rewrite`、`1→block`，但这样「输入注入」和「输出拦截」被合并成一个 `block`，`hitDist`（A04）就永远分不出「越狱特征」 |
| A17 | 调用表·备注列 | `note`（如「检出 6 行完整源码，已改写为苏格拉底式提问」「取回 #3 测试点特征」） | TEXT，可悬浮 | C | 无 | 需新增 `audit_note` 或由 A31 的命中详情拼装 |
| A18 | 调用表·行高亮 | `c.guard !== 'pass'` → `.is-hit` | 派生 class | E | — | 前端派生 |
| A19 | 筛选条·5 组条件 | 阶段（4+全部）、路由（3）、模型（多选）、仅看护栏命中、关键词（`who+note+model`） | 查询参数 | B | 对应字段均存在（`stage_type` / `is_fallback` / `actual_model` / `guardrail_action`） | 无查询接口，无法下推；16 条数据靠前端过滤，真实数据量下必须服务端分页筛选 |
| A20 | 模型下拉选项 | `DeepSeek-V3` / `Qwen2.5-Coder`（硬编码 2 项） | `DISTINCT actual_model` | B | 可 `SELECT DISTINCT` | 硬编码 → 新增通道后选项不会变；需接口返回枚举 |
| A21 | 表头排序 | `at`（`localeCompare`）/ `ttft` / `tok`，默认 `{k:'at', d:-1}` | — | B | `create_time` 可排；`ttft`/`tok` 需先补字段 | 后端排序需接口参数 `sortBy` + `order` |
| A22 | 分页 | `pageSize: 8`、`page`、`pageCount`、`共 N 条记录 · 第 x / y 页` | INT | B | 可 `LIMIT/OFFSET` | 需接口返回 `total`；契约 7 个接口无任何一个带分页参数 |
| A23 | 「导出 CSV」 | 按当前筛选导出（原型仅 toast，**无真实 CSV 生成**） | 文件流 | D | 无 | 需 `GET /api/admin/audit/export?...` 返回 `text/csv` |
| A24 | 详情·Prompt 摘要 | `user_message` 片段（按阶段硬编码 4 段示例文案） | TEXT | B | `user_message`（L178） | 无详情接口；且需脱敏规则（当前原型直接展示全文） |
| A25 | 详情·Response 摘要 | `ai_response` 片段 | TEXT | B | `ai_response`（L184） | **语义冲突**：`block` 时原型写「（回复已丢弃）」却仍展示被丢弃正文；契约若真「丢弃」则审计无法复盘，需明确「对外丢弃、对内留痕」 |
| A26 | 详情·路由链路与降级原因 | 云端节点（`model`）→ 本地节点（`Qwen2.5-Coder`）；`why` =「云端超时 8.0s · 自动降级 · 本地 0.42s」/「直连 1.18s · 未触发降级」 | 文案 | C | `is_fallback` 只知道「降级了」，**不知道「为什么降级」**（超时？401？限流？）；「8.0s」是硬编码字符串 | 需新增 `fallback_reason` + 触发时的 `timeout_ms` 实际值 |
| A27 | 详情·命中的测试点（10 格判分链） | 10 格 `ac/wa/tle/skip` + 卡点序号 `i` | 10×ENUM | C | `judge_submission.case_details`（L138）`[{caseId,status,timeMs,memKb}]` 能画 10 格 | **但 `ai_chat_record` 与 `judge_submission` 之间无任何关联键**（无 `submission_id`、无 `trace_id`）→ 「这次 AI 调用对应哪次提交」无法回答 |
| A28 | 详情·卡点归因文案 | `chainNote`：「题解直出 · 卡在测试点 #3」「越狱尝试 · 未进入判分」「改写后仍引用测试点 #6 的边界值」 | TEXT | C | `teach_plagiarism_report.ai_diagnosis` 是查重域的 AI 诊断，不同域 | 需新增「AI 调用级归因」字段（如 `ai_chat_record.diagnosis_note`） |
| A29 | 详情·护栏判定链路 3 步 | ①输入过滤（关键词与越狱特征）②泄漏率检测（源码行数阈值 ≤5 行）③输出整形（苏格拉底式提问）；每步 `done/pending` | 3×{title,desc,status} | D | 无分步判定表 | 需 `ai_guardrail_step` 明细表（或 `ai_chat_record` 存 JSON）；`guardrail_action` 单值只能表示最终动作，还原不出中间步骤 |
| A30 | 详情·各步耗时 | 「未命中敏感词库，耗时 3ms」 | INT 毫秒 | D | 无 | 同 A29；三段耗时需分列 |
| A31 | 详情·命中的代码行数 | 「检出 7 行完整源码，触发改写或拦截」（`7` 硬编码） | INT | C | 无 | 需 `leaked_code_lines` 或护栏命中详情 JSON |
| A32 | 详情·护栏阈值可配 | 「单次回复中连续完整源码 ≤ 5 行，超出即改写；整段题解直接拦截」 | 阈值 INT | D | 仅 PRD/`_SPEC` 口径，无配置表 | 需 `ai_guardrail_config`（`max_code_lines`、`block_mode`）；当前是全局硬编码常量，管理端改不了 |
| A33 | 详情脚·「查看沙箱执行」 | 从 `openCall` 跳到对应容器：`boxes.find(b => b.user === c.who) \|\| boxes[0]` | 外键 | C | 无 `ai_chat_record.sandbox_id`，也无法从 `judge_submission` 反查容器 | **关联键完全缺失**，原型只能按「用户名相同」猜，找不到就退化成第一个容器 |
| A34 | 沙箱统计卡·运行中 | `3 个`，副标「CPU 配额 1 核 / 容器」 | INT + 常量 | D | Go-Judge 是同步 `POST /run`（L537-560），**无容器生命周期** | 无 `sandbox_instance` 表、无状态机、无容器 ID → 整卡无落点 |
| A35 | 沙箱统计卡·排队中 | `1 个`，副标「并发上限 4 · 预计 0.6s 内调度」 | INT + 预估毫秒 | D | 无队列、无排队位置、无 slot 等待时间 | 同 A34；日志里 `[queue] enqueued, position=1`、`[queue] slot acquired after 607ms` 也无处存放 |
| A36 | 沙箱统计卡·今日执行 | `512 次`，副标「覆盖 48 人 · 人均 10.7 次」 | INT | B | `judge_submission` 当日 COUNT（含自测则偏高、不含则偏低） | **自测（`/self-test`）不落库**（契约 1.1 明确），所以「沙箱执行次数」永远小于真实容器启动次数 |
| A37 | 沙箱统计卡·覆盖人数 | `48 人`（`COUNT(DISTINCT user_id)`） | INT | B | 可算 | 无接口；且 `48` 与 `teach_class.student_count` 是两套口径 |
| A38 | 沙箱统计卡·耗时分布 | 平均 `12 ms` / P95 `38ms` / 超时 `0 次` | INT + P95 | B | `judge_submission.time_used`（L139）+ `status=6 (TLE)` 可聚合 | 需 `AVG` / `PERCENTILE` 聚合接口；契约无 |
| A39 | 容器表·容器 ID | `sbx-8841` / `sbx-8840` / …6 条 | VARCHAR | D | 无 | Go-Judge `/run` 不返回容器 ID |
| A40 | 容器表·语言 | `C++ 17` / `Python 3` / `Java 17` | ENUM | D | `judge_submission.language` 是提交语言，非容器镜像 | 容器镜像是运行时概念，无表 |
| A41 | 容器表·所属用户 | 林昭 / 苏晚 / 陈砚 / 周叙 / 何知遥 / 温言 | VARCHAR | D | 无容器-用户关系 | 同 A39 |
| A42 | 容器表·状态 | `running` 运行中 / `queued` 排队 / `exited` 已退出 | ENUM 3 值 | D | 无 | 契约里最接近的是 `judge_submission.status`（0排队/1编译/2评测/3AC/…），**是提交状态不是容器状态**，且没有 `exited` |
| A43 | 容器表·CPU | 限制 `1 核`（字面量）+ 使用率 `12%` / `88%` / `96%` / 已释放 | INT% + 常量 | D | `cpuLimit: 1e9` 是 `/run` 请求参数（L553），**不落库**；无采样 | 需 `sandbox_instance.cpu_limit_ns` + `cpu_usage_pct` |
| A44 | 容器表·内存 | 限制 `256 MB`（字面量）+ 已用 `(mem/1024).toFixed(1) MB`（`4096` → `4.0 MB`） | INT KB + 常量 | D | `memoryLimit: 268435456`（L555）是请求参数，不落库 | 同 A43；另注意 `OJ.sandbox.mem` 单位是 **KB**（`51200` → `50.0 MB`），而契约用字节 → 三方单位不统一 |
| A45 | 容器表·存活时长 | `age`：`0.4s` / `1.1s` / `3.2s` / `2.4s` / `5.0s` / `—` | DECIMAL 秒 | D | 无 | 需 `started_at` + `now()` 或 `duration_ms` |
| A46 | 沙箱筛选 | 状态 chip + 计数 `boxCount`、语言 select、搜索 `id+user+lang` | 派生 | E | — | 前端过滤（依赖 A39-A42 的 D 类字段） |
| A47 | 「实时刷新」开关 | `autoRefresh`，仅文案「实时刷新中/已暂停刷新」 | Boolean | E | — | **无定时器**，开关不产生任何行为 → 纯视觉 |
| A48 | 容器日志抽屉·日志行 | `LOGS[id]`：`{[{ts:'23:41:07.902', msg, cls}]}`，含 `[limits] cpu=1.0 mem=256MB wall=1000ms`、`[compile] g++ -O2 …`、`[run] case #1 WA expected=7 got=6`、`[run] case #2..8 skipped (first-fail mode)`、`[verdict] WA pass=0/10 score=0 time=50ms mem=3.1MB`、`[sandbox] container destroyed` | 日志流 | D | Go-Judge `/run` 是同步请求-响应（L537-560），**无日志流接口** | 需容器日志表或 `GET /api/admin/sandbox/{id}/logs?since=`；`judge_submission.case_details` 只能给结果，给不出编译/运行日志 |
| A49 | 抽屉·复制 / 下载日志 | `navigator.clipboard.writeText` / 仅 toast | 文件 | D | 无 | 下载需 `GET .../logs/export` |
| A50 | 抽屉·终止容器 | `kill(b)` → 700ms 后 `state='exited'; cpu=0; mem=0` | 写操作 | D | **Go-Judge 无 kill 接口**（只有 `/run`） | 需 `POST /api/admin/sandbox/{id}/kill` |
| A51 | 抽屉·隔离策略脚注 | 「独立网络命名空间、只读根文件系统、seccomp 白名单；单容器 CPU 1 核、内存 256MB、墙钟时限 1000ms（Java / Python ×2）」 | 常量文案 | B | `prob_question.time_limit=1000`（L110，注释「Java/Python判题时自动 x2」）/ `memory_limit=256`（L111，同样注明 x2）+ Go-Judge `cpuLimit/clockLimit/memoryLimit` | ✅ **倍率规则有落点**（写在 L110/L111 字段注释里，不是独立字段）。但三处口径要小心：①表里 CPU/内存限制是**字面量 `1 核` / `256 MB`**，不随语言变；②`time_limit` 是**每道题**的属性，而脚注写的是**每容器**的墙钟上限，两者是同一件事还是两层限制未定义；③「独立网络命名空间 / seccomp 白名单」在 Go-Judge 协议里没有对应参数 |
| A52 | 沙箱并发上限 | 「并发上限 4」 | INT | D | 无 | 需全局配置项；与 M31 的通道并发是两回事 |
| A53 | 错误态 | 「审计日志读取失败 · 日志服务返回 503，最近 5 分钟的写入可能正在积压」 | 错误码语义 | D | 只有 200/400/401/403/429 | 503 与「写入积压」语义无契约 |
| A54 | 页签角标计数 | `calls.length`（16）/ `boxes.length`（6） | 派生 INT | E | — | 前端派生 |
| A55 | 分页（沙箱页签） | `pagedBoxes` 复用 `this.page` / `pageSize=8`，但页面只有 `共 N 个容器` 文案，**无翻页按钮** | INT | E | — | 与审计表共享 `page` 状态 → 切页签时页码串味（原型 bug 级细节，接口设计时需独立分页态） |

### 交互与写操作清单

| 操作 | 触发位置 | 是否写库 | 需要的接口语义 |
|---|---|---|---|
| 刷新审计流 | 页头「刷新」`refresh()` | 否 | `GET /api/admin/audit/calls?from&to&stage&route&model&guard&kw&page&pageSize&sortBy&order`，返回 `{total, list:[{recordId,at,userId,userName,stageType,actualModel,route,ttftMs,tokens,guardrailAction,note}]}`。契约无 |
| 导出 CSV | 页头「导出 CSV」`exportCsv()` | 否 | `GET /api/admin/audit/calls/export?...` → `text/csv`（带 BOM）。契约无 |
| 打开调用详情 | 整行点击 `openCall=c` | 否 | `GET /api/admin/audit/calls/{recordId}`，返回 prompt/response 摘要、路由链路、护栏 3 步、命中测试点。契约无 |
| 从调用跳沙箱 | 抽屉脚「查看沙箱执行」`openBoxFromCall(c)` | 否 | `GET /api/admin/audit/calls/{recordId}/sandbox` → `{sandboxId}`。契约无；需先补 `sandbox_id` 外键 |
| 切换页签 | `setTab('audit'\|'sandbox')` | 否 | 前端 `history.replaceState` 改 hash，无需接口（但若要懒加载列表则需两条查询接口） |
| 沙箱列表查询 | 沙箱页签（无刷新按钮） | 否 | `GET /api/admin/sandbox/instances?state&lang&kw&page`，返回 `{total, runningCount, queuedCount, list:[{sandboxId,lang,userId,userName,state,cpuLimitCores,cpuUsagePct,memLimitMb,memUsedKb,startedAt,durationMs}]}`。契约无 |
| 沙箱统计 | 4 张统计卡 | 否 | `GET /api/admin/sandbox/stats` → `{running, queued, todayRuns, distinctUsers, avgMs, p95Ms, timeoutCount, concurrencyLimit}`。契约无 |
| 查看容器日志 | 行内「日志」/ 行点击 `openBox=b` | 否 | `GET /api/admin/sandbox/{id}/logs?since=&limit=`。契约无 |
| 复制日志 | 抽屉「复制」`copyLog(b)` | 否 | 前端 `clipboard` 即可（数据来自日志接口） |
| 下载日志 | 抽屉「下载日志」`downloadLog(b)` | 否 | `GET /api/admin/sandbox/{id}/logs/export`。契约无 |
| 终止容器 | 行内停止图标、抽屉脚「终止容器」`kill(b)` | **是** | `POST /api/admin/sandbox/{id}/kill`，返回 `{state:'exited', releasedCpu, releasedMem}`。契约无；Go-Judge 不提供该能力，需 Go-Judge 侧先做容器化改造 |
| 筛选 / 排序 / 翻页（审计） | 筛选条、表头、分页按钮 | 否 | 应作为查询参数下推（A19/A21/A22） |
| 筛选（沙箱） | chip / 语言 select / 搜索框 | 否 | 同上 |
| 「实时刷新」开关 | `x-model="autoRefresh"` | 否 | 若实现，需 SSE `GET /api/admin/sandbox/stream` 或 3-5s 轮询；契约无任何流式/轮询接口（只有判题状态轮询 1.3） |

> **写操作实现现状**：`kill()` 是 700ms `setTimeout` 改内存；`refresh()` / `exportCsv()` 只 toast；**无 `setInterval`、无 `EventSource`、无 `fetch`**（已 grep 确认）。`autoRefresh` 开关不绑定任何定时器。

### 枚举 / 状态机

| 枚举 | 取值 | 出处 | 契约对应 |
|---|---|---|---|
| 教学阶段 `stage` | `① 读题拆解` / `② 编码引导` / `③ 排错诊断` / `④ AC 复盘` | `stageOpts`（L809-815）+ `OJ.aiCalls.stage` | `ai_chat_record.stage_type` 1/2/3/4 ✅ 可对齐 |
| 路由 `route` | `cloud` 云端 / `local` 本地 | `routeOpts`（L816） | `is_fallback` 0/1 —— **二值 ≠ 二路由**，缺 `route_type` |
| 护栏结果 `guard` | `pass` 通过 / `rewrite` 改写 / `block` 拦截 | `guardName()`（L917-921） | `guardrail_action` 0/1/2，语义顺序不同（见 A16） |
| 拦截原因（仅统计用） | `泄漏率` / `越狱特征` / `敏感词` | `hitDist`（L822-826） | 无字段 |
| 容器状态 `state` | `running` / `queued` / `exited` | `BOX_STATE`（L716-717）+ `boxStates`（L831-833） | 无；`OJ.sandbox` 原数据却是 `run` / `idle`（**两套词表**，页面用 `BOX_STATE` 覆盖） |
| 容器语言 | `C++ 17` / `Python 3` / `Java 17` | 语言 select（L459-461） | `judge_submission.language` 同词表但语义不同 |
| 护栏判定链路 3 步状态 | `done` / 未完成 | `:class="openCall.guard==='pass' ? 'done' : ''"` | 无 |

**沙箱状态机（隐式）**：
```
queued --slot acquired--> running --正常结束/超时--> exited
running/queued --kill()--> exited（cpu=0, mem=0）
```
依据：`kill()`（L1000-1011）把 `state` 置 `exited` 并清零资源；日志里有 `[queue] enqueued, position=1` → `[queue] slot acquired after 607ms` → `[sandbox] container destroyed`。契约 `judge_submission.status` 的 0排队/1编译/2评测 是**任务状态**，不能替代容器状态（无 `exited`、无资源回收）。

### 实时性 / 分页 / 排序 / 筛选

- **实时性**：沙箱页签写着「实时刷新中」，但**无任何定时器/推送**；`autoRefresh` 只是布尔值。日志抽屉也不流式。真实实现至少需要 SSE 或 3s 轮询，契约里完全没有对应接口（唯一轮询是 `GET /api/judge/status/{id}`，粒度是单次提交）。
- **分页**：审计表 `pageSize: 8`、`page`、`pageCount`，真实派生 `pagedCalls`（L880-884），文案 `共 N 条记录 · 第 x / y 页` + 数字页码按钮；**沙箱表复用同一个 `page`**，但页面上没有翻页按钮，只有 `共 N 个容器 · 并发上限 4 · 单容器内存 256MB`。
- **排序**：`at` 用 `localeCompare`（字符串比较，`HH:mm:ss` 定长可正确排序）、`ttft`/`tok` 数值；`pickSort` 换列时 `at` 默认降序、其余默认升序，并 `page=1`。
- **筛选**：阶段/路由为单选分段（`segW=56`、`routeW=56` 控制滑块宽度）、模型为多选 `details` 下拉（硬编码 2 项）、护栏命中为开关、`fKw` 搜索 `who+note+model`；`isFiltered` 控制「清空」按钮显隐；`resetFilters()` 一并 `page=1`。
- **空/错态**：审计「没有符合条件的调用记录」、错误态「日志服务返回 503…」；沙箱「没有符合条件的容器」；`?err=1` 强制错误态、`?skeleton=1` 钉住 60000ms 骨架。

---

## 页面：index.html（门户 / 首页）

`<body>` **无 `data-role` / `data-page` / `data-crumb`**，且**不引入 `shell.js` 与 `styles/shell.css`**（只引 `tokens.css` / `components.css` / `index.css`），自带一套 `.hd` 顶栏与 `.ft` 页脚 —— 是全站唯一一个脱离共享外壳的页面之一（另一个是 `s-workbench.html`）。

### 数据需求表

| # | 页面模块 / 交互点 | 所需数据（字段级） | 类型与取值域 | 数据来源判定 | 现有契约覆盖情况 | 缺口说明 |
|---|---|---|---|---|---|---|
| I01 | 顶栏 meta·「3 角色端」 | 角色种类数 | INT 常量 | E | `sys_user.role` 三值可印证 | 静态文案，无需接口 |
| I02 | 顶栏 meta·「15 功能页」 | 页面总数 | INT 常量 | E | — | 静态文案；实际 `prototype/app/*.html` 为 15 个业务页 + index，与文案一致 |
| I03 | 顶栏 meta·「4 AI 阶段」 | 阶段枚举基数 | INT | B | `ai_chat_record.stage_type` 1-4 | 静态文案，可由枚举字典接口返回 |
| I04 | 顶栏 meta·「2 查重阶段」 | 查重阶段数 | INT | B | `teach_plagiarism_report.stage1_algo_sim` / `stage2_ai_sim`（L87-L88）两列 | 同 I03；两阶段阈值 75% / 85% 无配置字段 |
| I05 | 顶栏 meta·「4 模型通道」 | 通道数 | INT | B | `ai_model_config` 行数 | 需 `GET /channels` 返回 `total` |
| I06 | 首屏判分链（10 格） | `10 个测试点` + 10 格 `ac/ac/wa/skip×7` | `test_case_count` + 10×ENUM | B | `prob_question.test_case_count DEFAULT 10`（L113）+ `judge_submission.case_details` + `first_failed_case_id` | 需判题结果接口（1.3 已有 `caseDetails`）→ **本页唯一能被现有接口部分支撑的数据** |
| I07 | 判分链说明行 | `WA / 测试点 #3 / 用时 12ms` | ENUM + INT + INT | B | `first_failed_case_id` + `case_details[].timeMs` | 1.3 接口有此结构，但需 `problemId` 上下文；门户页无用户态 → **需要「示例提交」或匿名样例接口**才拿得到 |
| I08 | 首屏 hero-note | 「所有页面均可直接打开、交互可用，**不需要登录**。」 | 声明 | D | 与契约 `401 未登录` / `403 权限拦截`（L211）、`oj:auth:token`（L198）**正面冲突** | 这不是字段缺口而是**架构立场冲突**，必须在文档层明确取舍 |
| I09 | 三端入口卡（3 张） | 标题 / 描述 / 3 条 role-list / 跳转 href | 文案 + 路由 | E | — | 静态，路由硬编码 |
| I10 | 「全部页面」三列清单 | 15 条 `{序号, 中文名, data-page, 一句话描述, href}` | 元数据 | E | — | **静态 HTML 手写**，与 `shell.js` 的 `NAV` 是两份独立清单（`index` 有 `s-exam`/`t-problem-edit`，`NAV` 没有） |
| I11 | 页脚 | 版本 `v2.0` / 「1440 × 900 桌面基准」 | 常量 | E | — | 静态 |

### 交互与写操作清单

| 操作 | 触发位置 | 是否写库 | 需要的接口语义 |
|---|---|---|---|
| 「从学生端开始」 | hero CTA → `s-problems.html` | 否 | 纯静态跳转 |
| 「浏览全部页面」 | hero CTA → `#map` | 否 | 页内锚点 |
| 三端入口卡 | 整卡 `<a href>` → 各端 home | 否 | 纯静态跳转（**跳转即切角色，无鉴权、无 token 换取**） |
| 「全部页面」15 行 | `<a href>` | 否 | 纯静态跳转 |
| 迁移文档链接 | 页脚 → `../workbench/VUE-MIGRATION.md` | 否 | 静态文件链接 |

> **本页零写操作、零网络请求**（无 `<script>` 引入，纯静态 HTML + CSS）。

### 枚举 / 状态机

无枚举、无状态机。仅有的「判分链」10 格是展示用静态标记：`ac`（绿，通过）/ `wa`（红，失败）/ `skip`（灰，未测到），与 `judge_submission.status` 的 0-9 九值体系**不是同一层**（`case_details[].status` 才是逐测试点状态）。

### 实时性 / 分页 / 排序 / 筛选

- **实时性**：无。纯静态首屏。
- **分页 / 排序 / 筛选**：均无。页面是「原型地图」，不承载业务数据。
- **导航结构**：`#map` 锚点 + 三列 `pagecols`，序号 01-15 与 `_SPEC` 的 `data-page` 清单存在**双向不一致**（见第二部分 Q1）。

---

# 第二部分：全站框架与鉴权盘点

## 全站框架（shell.js / 权限 / 路由）

### 1. `OJ.roles` / `OJ.nav` / `MOUNTS` 的结构与字段；页面如何声明角色与导航归属

**先纠正一个前提：`shell.js` 中不存在 `MOUNTS`。**
全文 grep `MOUNT` 只命中两处：`function mount()` 与 `host.dataset.mounted`。`_SPEC.md` 也没有 `MOUNTS` 章节。所谓「`MOUNTS` 页面元数据表」在原型里**没有任何对应物**；最接近的东西是 `OJ.nav`（导航树）与被手写进 `index.html` 的「全部页面」清单。这是需求侧必须先对齐的第一个偏差。

#### `OJ.roles`（`shell.js` L90-L94）

```js
ROLES = {
  student: { name:'学生端', user:'林昭',   avatar:'林', sub:'计算机 2022 级 · 1 班',   home:'s-problems.html'  },
  teacher: { name:'教师端', user:'周明远', avatar:'周', sub:'数据结构 · 主讲教师',     home:'t-dashboard.html' },
  admin:   { name:'管理端', user:'运维管理员', avatar:'运', sub:'系统与模型运维',      home:'a-models.html'    }
}
```

| 字段 | 类型 | 语义 | 契约落点 |
|---|---|---|---|
| `name` | string | 端名（角色展示名） | 无（`sys_user.role` 只有 `STUDENT/TEACHER/ADMIN` 英文码） |
| `user` | string | 当前登录人姓名（**硬编码**） | `sys_user.real_name` 有，但无「当前用户」接口 |
| `avatar` | string | 头像字（取姓名首字） | 无 `avatar` 字段，前端可算 |
| `sub` | string | 身份副标（班级 / 职称 / 运维岗位） | 学生可从 `teach_class.class_name` 拼；教师无「职称」；管理员无岗位 |
| `home` | string | 该角色首页路由 | 无路由表/菜单表 |

#### `OJ.nav`（L96-L129）

结构：`NAV[role] = Array<{ title:string, items:Array<{ id, label, icon, href, badge? }> }>`

| 字段 | 类型 | 语义 |
|---|---|---|
| `title` | string | 导航分组标题（学生「学习 / 正在做」、教师「教学 / 监控与审查」、管理「AI 平台 / 基础设施」） |
| `items[].id` | string | 页面 id，**与 `body[data-page]` 做全等比较决定高亮** |
| `items[].label` | string | 菜单文案 |
| `items[].icon` | string | `ICONS` 图标名（约 90 个 SVG path 常量） |
| `items[].href` | string | 目标路由，含 `a-audit.html#sandbox` 这种带 hash 的 |
| `items[].badge` | string? | 角标数字（学生「我的作业 3」、教师「教学活动 2」「查重报告 5」） |

**三组导航共 14 个条目**：学生 5（`s-problems / s-assignments / s-submissions / s-analytics / s-workbench`）、教师 6（`t-dashboard / t-problems / t-assignments / t-analytics / t-monitor / t-plagiarism`）、管理 3（`a-models / a-audit / a-sandbox`）。其中 **`a-sandbox` 与 `a-audit` 指向同一个 `a-audit.html`**（一个带 `#sandbox`），`id` 不同但 `data-page` 只能取 `a-audit` → 从 `#sandbox` 页签进入时，「调用审计」与「沙箱监控」两个菜单项永远只有一个高亮，**导航项与页面不是一一映射**。

同时，`NAV` 里**没有** `s-exam` 与 `t-problem-edit` 两项，而 `index.html` 的 15 页清单里有 → 这两个页面在侧栏**无处高亮**，实际靠复用别人的 `data-page` 蒙混（见下）。

#### 页面如何声明角色与导航归属

三件事全部由 `<body>` 上的 data 属性声明，由 `mount()`（L266-L305）读取：

| 属性 | 读取处 | 作用 | 取值校验 |
|---|---|---|---|
| `data-role` | `body.dataset.role \|\| 'student'`（L272） | 选 `ROLES[role]` 与 `NAV[role]`，决定侧栏与用户卡 | 无校验，非法值静默回落 `student` |
| `data-page` | `body.dataset.page \|\| ''`（L273） | 与 `it.id === page` 全等比较，决定 `.on` 与 `aria-current="page"` | 无校验，拼错就是不高亮 |
| `data-crumb` | `body.dataset.crumb \|\| ''`（L274） | 按 `\|` 切分生成面包屑，最后一段加 `.cur` | 无校验 |

`mount()` 的实际动作：取 `#page-content` → `buildSide(role,page)` 生成 `<aside class="side">` → `buildTop(crumb)` 生成 `<header class="topbar">` → `host.innerHTML=''` 后重新 append → `host.dataset.mounted='1'` 幂等。**页面 id 只影响高亮，不参与任何数据取用或权限判断。**

**实测 16 个 HTML 的声明情况（逐文件核对 `<body>`）：**

| 页面 | `data-role` | `data-page` | 引 shell.js | 备注 |
|---|---|---|---|---|
| `a-models.html` | admin | `a-models` | ✅ | 正常 |
| `a-audit.html` | admin | `a-audit` | ✅ | 正常，`#sandbox` 靠 hash 切页签 |
| `s-problems.html` | student | `s-problems` | ✅ | 正常 |
| `s-assignments.html` | student | `s-assignments` | ✅ | 正常 |
| `s-submissions.html` | student | `s-submissions` | ✅ | 正常 |
| `s-analytics.html` | student | `s-analytics` | ✅ | 正常 |
| `s-exam.html` | student | **`s-assignments`** | ✅ | ❗ `_SPEC` 的 `data-page` 清单**漏列** `s-exam`；页面复用「我的作业」的 id → 侧栏高亮错位 |
| `t-dashboard.html` | teacher | `t-dashboard` | ✅ | 正常 |
| `t-problems.html` | teacher | `t-problems` | ✅ | 正常 |
| `t-problem-edit.html` | teacher | **`t-problems`** | ✅ | ❗ 清单**漏列**；复用父页 id（面包屑另写 `教学\|题库管理\|编辑题目`） |
| `t-assignments.html` | teacher | `t-assignments` | ✅ | 正常 |
| `t-analytics.html` | teacher | `t-analytics` | ✅ | 正常 |
| `t-monitor.html` | teacher | `t-monitor` | ✅ | 正常 |
| `t-plagiarism.html` | teacher | `t-plagiarism` | ✅ | 正常 |
| `s-workbench.html` | **无** | **无** | ❌ | ❗ 自建顶栏 `.wb-brand` + `.mainnav`，完全脱离 shell（也不引 `shell.css`），`#page-content` 不存在 |
| `index.html` | **无** | **无** | ❌ | 门户页，自建 `.hd` / `.ft` |

→ **15 个业务页里，14 个走共享外壳、1 个（`s-workbench.html`）自成一套；`data-page` 取值域实际有 15 个页面但只用到 13 个不同取值（`s-exam` 借 `s-assignments`、`t-problem-edit` 借 `t-problems`），而 `_SPEC` 只登记 13 个。** 这套「页面元数据」如果要变成后端驱动（菜单权限接口），现在连一份可信的页面清单都没有。

### 2. 原型如何切换角色与页面（纯静态跳转？有无登录态？token？）—— **零鉴权**

**结论：纯静态跳转，无登录态、无 token、无会话、无路由守卫，是彻底的零鉴权原型。**

证据链（逐条可复核）：

1. **切换角色 = 点链接跳首页**。`buildSide()` 的 `.role-switch` 是硬编码 `['student','teacher','admin']` 三个 `<a href="{ROLES[k].home}">`（L154-L160）；点「教师端」就是 `location.href = 't-dashboard.html'`。目标页的 `<body data-role="teacher">` 是**源码里写死的**，不是从任何状态读出来的。当前角色的 `<a class="on">` 被 `preventDefault()`（L302-304），只是防止点了刷新。
2. **角色是「页面属性」而不是「用户属性」**。`mount()` 从 `document.body.dataset.role` 取角色（L272）—— 换句话说，谁能改 HTML 谁就能改角色；把 URL 从 `a-models.html` 改成 `t-dashboard.html` 就直接以教师身份进入，没有任何中间校验。
3. **无任何认证代码**。`shell.js` 全文（570 行）grep `fetch|XMLHttpRequest|localStorage|sessionStorage|document.cookie|token|login|logout`：**只有 `ICONS.logout` 一条 SVG path 常量命中**（L54），没有任何登录/登出函数。`window.OJ` 导出的 20 个成员（12 份模拟数据 + 8 个工具函数：`svg / icons / roles / nav / problems / submissions / assignments / students / models / aiCalls / sandbox / plagiarism` + `statusName / statusTag / lvlName / lvl / toast / reduced / fmt / qs`）**没有一个与身份、token 或权限相关**。
4. **无登录页**。全仓 16 个 HTML 无 `login.html` / `auth.html`；顶栏 `buildTop()` 只有搜索框、通知铃（badge 2，无面板、无 JS）、快捷键按钮，**用户卡 `<button type="button" class="user-card">`（L161-L165）带 `chevD` 下箭头却没有绑定任何点击事件、没有下拉面板、没有「退出登录」入口** —— 视觉上暗示有菜单，实际上是个死按钮。
5. **原型的自我声明**。`index.html` L198：`所有页面均可直接打开、交互可用，不需要登录。` 这是设计者主动写在首屏的，不是遗漏。
6. **契约侧同样没有登录能力**（详见下一小节）—— 所以不是「前端偷懒跳过」，而是**两端都缺**。

**推论（对接时必须在文档里写死）**：当前原型 = 「角色由页面静态决定」；契约 = 「角色由 JWT 决定」。二者之间缺的不是一个字段，而是**整条认证链路**：登录 → 签发 → 存储 → 网关校验 → 注入 `userId/role` → 前端读当前用户 → 渲染菜单。原型把这一整条压缩成了「body 上写一个字符串」。

### 3. 契约文档的鉴权能力盘点（逐条回答有/无）

**有的部分（3 项）：**

| 能力 | 位置 | 具体内容 | 是否够用 |
|---|---|---|---|
| Redis token 存储 | L198 | `oj:auth:token:{userId}` / String(JSON) / TTL 24 小时 / 注释「存储登录用户信息与角色权限，供 `oj-gateway` 网关统一鉴权」 | ⚠️ **只有 key 的存在性描述，没有 JSON schema**：不知道里面有没有 `role`、`username`、`classId`、过期时间、签发时间；「角色权限」是字符串还是权限点数组也未定义。24h 固定 TTL 意味着**没有滑动续期** |
| Bearer 头约定 | L210 | 全局请求头 `Authorization: Bearer {JWT_TOKEN}` | ⚠️ 只说了「怎么带」，没说「怎么签发」「怎么校验」「签名算法/密钥」「载荷字段」 |
| 401 / 403 状态码 | L211 | `401 未登录`、`403 权限拦截（如考试模式调 AI 接口）` | ⚠️ 语义只举了「考试禁用 AI」一例，**没有「角色不足」的 403 语义**（教师调管理端接口该返回什么？） |

**没有的部分（5 项，逐条确认）：**

| 能力 | 有没有 | 证据 |
|---|---|---|
| 登录接口 `POST /auth/login` | ❌ **无** | 7 个 REST 接口全文（L215-L441）无一涉及认证；`sys_user.password`（BCrypt，L20）有字段但**没有任何接口用它** |
| 刷新接口 `POST /auth/refresh` | ❌ **无** | 无 refresh token 概念，Redis 只有 `oj:auth:token:{userId}` 一个 key、固定 24h |
| 当前用户信息接口 `GET /auth/me` | ❌ **无** | 这是最致命的一条：前端连「我是谁、我叫什么、我是哪个班」都拿不到 → `ROLES.user / avatar / sub` 三个字段无来源 |
| 登出接口 `POST /auth/logout` | ❌ **无** | 无删除 `oj:auth:token:{userId}` 的接口；前端顶栏也没有登出入口 |
| 菜单权限接口 `GET /auth/menus` | ❌ **无** | `NAV` 是前端硬编码二维数组，无菜单表、无 `sys_role_menu`、无权限点；`sys_user.role` 之外没有任何授权数据 |

**补充缺口（同样要从「鉴权能力」角度记账）：**
- 无 `sys_permission` / `sys_role` / `sys_role_permission` 表 —— 权限模型只有 `sys_user.role` 一个字段。
- 无用户注册/改密/找回接口。
- 无「网关如何把 `userId` 透传给下游」的约定（Feign 内部调用是否带用户上下文？`verifyAiPermission`（L500 附近）需要 `userId + assignmentId`，但没说从哪来）。
- 无「JWT 过期后前端怎么办」的约定（无 refresh，24h 后只能重新登录，但登录接口也不存在）。

### 4. `sys_user.role` 三值能否表达原型导航所需的更细粒度？—— **不能，只能支撑「端级」粗分**

`sys_user.role VARCHAR(16) DEFAULT 'STUDENT'`，注释 `STUDENT / TEACHER / ADMIN`（L22），**无 CHECK 约束、无枚举表**。

| 原型需要的粒度 | 现状能否表达 | 结论 |
|---|---|---|
| 三端菜单差异（学生 5 项 / 教师 6 项 / 管理 3 项） | ✅ 能 | 三值 ↔ 三组 `NAV`，一对一。这是唯一完全对得上的部分 |
| 教师与管理员可见模块的进一步细分 | ❌ 不能 | 原型里管理端只有「运维管理员」一种人（`ROLES.admin.sub = 系统与模型运维`），但语义上「模型通道配置」和「调用审计/沙箱监控」是两个不同职责（一个改配置、一个只读观测）。三值模型下**任何 ADMIN 都能改通道**，无法表达「只读审计员」 |
| 教师端的「主讲 / 助教 / 只读」 | ❌ 不能 | `ROLES.teacher.sub = 数据结构 · 主讲教师` 暗示存在「非主讲」角色，但无字段 |
| 班级维度约束（`teach_class.teacher_id`） | ⚠️ **只能表达归属，不能表达约束** | `teach_class` 有 `teacher_id`（L35），可以反查「这个老师带哪些班」；但 **`role=TEACHER` 本身不带班级**，任何教师端接口都得先 `SELECT class_id FROM teach_class WHERE teacher_id = :currentUserId`。问题在于：①契约里**没有任何教师端业务接口**（7 个接口全是学生侧判题/AI 与查重），所以「数据可见范围」根本没有被执行的地方；②一个教师带多个班时，是「全部可见」还是「按请求头选班」无定义；③无「班主任 / 任课教师 / 助教」区分，`teach_class.teacher_id` 只有一列 |
| 行级数据隔离（教师 A 看不到教师 B 的班） | ❌ 不能 | 无租户/组织字段、无数据权限注解约定；Feign 内部接口（`getBatchBestCodes` 等）也不带调用者身份 |
| 管理端 vs 教师端在「审计数据」上的可见范围 | ❌ 不能 | 原型里 `a-audit` 能看到全站 48 人的调用记录；教师端 `t-analytics` 只能看自己班。契约无任何字段区分「全站」与「本班」 |

**结论**：`sys_user.role` 三值**刚好够画侧栏**，但一个字段撑不起：
1. 功能级权限点（哪个角色能调哪个接口）——无表；
2. 数据级范围（教师只看自己班）——靠 `teach_class.teacher_id` 反查能勉强实现，但无接口承载，且多班场景无定义；
3. 岗位细分（只读审计员、助教、主讲）——无字段。

如果只做毕设演示，三值 + 前端硬编码 `NAV` 可以跑通；如果要做「菜单权限接口」（`GET /auth/menus`），必须先补 `sys_role` / `sys_permission` / `sys_role_permission` 三张表，否则该接口只能返回写死的常量。

### 5. `sys_user.weak_tags` JSON 能否直接支撑 `s-analytics` 的 TAG 分布与 `t-analytics` 的知识点掌握度？—— **不能直接使用，语义相反**

**契约侧**：`weak_tags` JSON，注释示例 `{"动态规划":5,"二分查找":3}`（L24）。这是**「历史高频错题标签 → 次数」的计数表**，数值越大越弱。

**原型侧（两侧统计口径已逐行核实）：**

| 页面 | 数据来源 | 计算式 | 口径文案 |
|---|---|---|---|
| `s-analytics.html` | `TAGS` 5 条硬编码（L686-L733）：动态规划 `pct:82`、贪心 `64`、图论 `41`、字符串 `73`、数学 `55`，另带 `cls`（班级线 66/71/58/69/60）与 `pids[]` | `pct` 是**常量**，不是从提交算的；`TAGS_D`（L837-L848）只派生 `n/done/subs/hint/sub` | L293「标签掌握度 = 该标签下已 AC 题数占比」 |
| `t-analytics.html` | `KP_BASE = {动态规划:82, 贪心:64, 图论:41, 字符串:73, 数学:55}`（L704）—— **与 `s-analytics` 的 `pct` 完全同源同值**；每个学生 `pct = clamp(KP_BASE[n] + ((i*7 + j*17 + range*9) % 31) - 15, 22, 98)`（L744-747） | 学生级数据是**同一个基准 + 伪随机偏移**生成的 | L264「知识点掌握度 = 该标签下 AC 人数占比」 |

**四道硬坎（任意一条都足以否决「直接用」）：**

| 坎 | 说明 |
|---|---|
| **① 语义相反** | `weak_tags` 数的是「错了几次」（越高越弱）；原型 `pct` 是「AC 占比」（越高越强）。方向相反，不能直接映射；只能反着排序（`weak_tags` 降序 ≈ 最弱标签），拿不到百分比 |
| **② 无分母** | 原型的 `pct` 是 `已AC题数 / 该标签题目总数`（个人）/ `AC人数 / 班级人数`（班级）。`weak_tags` 只有分子侧的「错误次数」，**没有标签题目总数、没有班级人数** → 算不出比率 |
| **③ 无班级维度** | 班级线 `cls:66/71/58/69/60` 与 `t-analytics` 的 `classSize`（46/52/48）都需要「同一标签下全班 AC 人数 / 班级总人数」。`weak_tags` 是 `sys_user` 单行 JSON，**跨用户聚合必须全表扫 JSON 并解析**，且没有 `class_id` 参与的索引（`idx_class` 只索引 `class_id`） |
| **④ 无时间区间** | 两个页面都有 `RANGES`（本周/本月/学期）切换，`t-analytics` 的伪随机式里还带 `range * 9`。`weak_tags` 是全历史累计，**不分区间**，无法回答「本周比上周」 |

**能勉强支撑的部分**：`weak_tags` 的**键**（`动态规划 / 二分查找 / …`）可以作为标签字典；值的大小可以给「薄弱点排序」——`s-analytics` 里「薄弱点优先练」这一条叙事（`onlyWeak` 开关、`weak` 对象）在逻辑上是「按错误次数降序」，这个方向 `weak_tags` 是对的。但**百分比、班级对比线、趋势、每学生每知识点的蜂窝热力图，全部无法支撑**。

**建议落点（供接口契约补章时参考）**：把 `weak_tags` 降级成「缓存/快照」，真实统计走聚合查询 `judge_submission`（`status`、`problem_id` → `prob_question.tags`、`time_used`）+ `teach_student_score`，按 `(class_id, tag, time_range)` 分组算 `AC人数/总人数`；`weak_tags` 只用于「上次算完的快照」以便秒开。

---

# 第三部分：汇总

## 管理端与全局汇总

### 1. 条目总数与 A/B/C/D/E 分布

| 范围 | 条目数 | A 现有接口直接覆盖 | B 现有表可派生但无接口 | C 现有表缺字段 | D 无任何表落点 | E 纯前端派生 |
|---|---:|---:|---:|---:|---:|---:|
| `a-models.html`（管理·模型通道） | 44 | 0 | 16 | 18 | 4 | 6 |
| `a-audit.html`（管理·调用审计与沙箱） | 55 | 0 | 21 | 10 | 18 | 6 |
| `index.html`（门户/首页） | 11 | 0 | 5 | 0 | 1 | 5 |
| **合计** | **110** | **0** | **42** | **28** | **23** | **17** |
| 占比 | 100% | **0.0%** | 38.2% | 25.5% | 20.9% | 15.5% |

**读法（这三行数字就是结论）：**
- **A = 0**：契约里 7 个 REST 接口，**没有任何一个能直接支撑这三个页面的任何一条数据需求**。7 个接口全部面向「学生做题 + AI 对话 + 查重」，管理端与门户是接口层的**完全空白区**。
- **B 占 38.2%（42 条）**：这些字段在 9 张表里**有落点**，只是没有查询接口。这是最容易补的一类（写 SELECT 即可），也是接口契约扩写时的主要工作量。
- **C 占 25.5%（28 条）**：表建了但**字段不够**，必须 `ALTER TABLE`。集中在两处：`ai_model_config`（缺 vendor / max_tokens / temperature / 并发 / 健康态 / note）与 `ai_chat_record`（缺 ttft / tokens / cost / 降级原因 / 审计备注）。
- **D 占 20.9%（23 条）**：**连表都没有**。23 条里 18 条来自沙箱页签（容器 ID、状态机、CPU/内存、日志、kill、并发上限）—— 整个「沙箱可观测」领域在契约里**零落点**（Go-Judge 是同步 `/run`，没有容器概念）。
- **E 占 15.5%（17 条）**：前端能自己算（合计、占比、计数、排序筛选）。但只有 `I01/I02/I09/I10/I11`（门户 5 条）与 `M25/A47/A54/A55` 是真正「免费」的静态派生；其余 `M05/M17/M18/M19/A03/A18/A46` 共 7 条都**依赖 C/D 类字段先存在**（依赖 `state` / `vendor` / `ttft` / `guard` / 容器字段），字段不补则无数据可算。

### 2. 最严重的 5 个缺口

**① 管理端接口 0 覆盖：7 个 REST 接口没有一个面向管理端，两个管理页 100% 悬空**
`a-models` 需要「通道列表 / 详情 / 新增 / 启停 / 探测 / 恢复 / 健康聚合」7 类接口，`a-audit` 需要「调用查询 / 详情 / 导出 / 沙箱列表 / 沙箱统计 / 日志 / 终止」7 类接口 —— **契约里一个都没有**。同时 Feign 的 4 个内部客户端（`ProblemInnerFeignClient` / `JudgeInnerFeignClient` / `TeachingInnerFeignClient` / `AiInnerFeignClient`）也全是学生侧调用，没有一个管理端方法。这不是「少几个字段」，而是**整个 `oj-admin` / `oj-gateway` 管理面缺失**。毕设答辩时若被问「管理端怎么实现」，目前只能答「前端原型」。

**② 可观测性字段整体缺失：TTFT / Token / 成本 / 通道配置项，两个管理页的主指标全部无落点**
`ai_chat_record` 只有 16 个字段，**没有 `ttft_ms`、没有 `prompt_tokens`/`completion_tokens`/`total_tokens`、没有 `cost`**；`ai_model_config` 只有 10 个字段，**没有 `max_tokens`、`temperature`、`max_concurrency`、`vendor`、健康态、`note`**。而这两个页面最显眼的数字恰恰是：平均 TTFT `0.8s`（M09/A07）、24h TTFT 趋势（M22/M33）、Tokens `1240`（A15）、成本 `¥214.80`（M15）、`max_tokens 4096 / temperature 0.3 / 并发 16 路`（M29-M31）。**管理端的两块主视觉（TTFT 趋势图 + 成本列）在当前契约下完全画不出来**，因为成本 = 单价 × token，两个因子都不存在。

**③ 沙箱域零落点：Go-Judge 是同步 `/run`，没有容器、没有状态机、没有日志、没有 kill**
`a-audit` 页签二整页（4 张统计卡 + 8 列表格 + 日志抽屉 + 终止按钮）对应 18 条 D 类需求，全部无表。Go-Judge 协议（L537-L560）只有一个 `POST http://localhost:5050/run`，`cmd[]` 里带 `cpuLimit / clockLimit / memoryLimit / procLimit` —— 这些是**每次请求的参数**，不是**可查询的实例**。因此：容器 ID（A39）、`running/queued/exited` 状态机（A42）、CPU/内存实时占用（A43/A44）、存活时长（A45）、排队位置（A35）、日志流（A48）、终止容器（A50）、并发上限 4（A52）**全部没有数据源**。这不是「补字段」能解决的，需要 Go-Judge 从「同步执行器」升级为「容器调度器 + 实例注册 + 日志采集」，属于架构级改造。

**④ 鉴权链路断裂：有 Redis key、有 Bearer 约定、有 401/403，但没有登录/刷新/me/登出/菜单 5 个接口，也没有 token 载荷 schema**
这是一条**自相矛盾**的契约：L198 说 `oj:auth:token:{userId}` 存「登录用户信息与角色权限」，L210 要求每个请求带 `Authorization: Bearer {JWT_TOKEN}`，L211 定义了 401/403 —— 但**没有任何接口能签发这个 token**（无 `POST /auth/login`），也没有接口能查当前用户（无 `GET /auth/me`）、续期（无 `/auth/refresh`）、注销（无 `/auth/logout`）、取菜单（无 `GET /auth/menus`）。`sys_user.password` 有 BCrypt 字段但无接口使用。原型侧则直接写在首屏「不需要登录」（I08）。**两端都缺，且缺的是同一条链路的两半** —— 对接时无法「前端调后端」，只能整条重建。这是全表里唯一一条「不是字段问题、是系统性缺失」的 P0。

**⑤ 审计溯源链断裂：`ai_chat_record` 与 `judge_submission` 无关联键，护栏判定链路无法还原**
`a-audit` 详情抽屉的核心叙事是「这次 AI 调用 → 卡在哪个测试点 → 护栏为什么这么判」。但：
- `ai_chat_record` **无 `submission_id`、无 `trace_id`、无 `sandbox_id`** → 画不出 10 格判分链（A27），也无法从调用跳到沙箱（A33，原型只能按「用户名相同」猜容器，猜不到就用 `boxes[0]`）；
- `guardrail_action` 是**单值动作**（0/1/2），**没有原因分类**（泄漏率/越狱特征/敏感词，A04 三个数一个都数不出来）、**没有分步耗时**（A29/A30 的「耗时 3ms」）、**没有命中代码行数**（A31 的「7 行」）、**没有阈值配置**（A32 的「≤5 行」）；
- 更微妙的是语义错位：契约 `1` = 输入侧防注入阻断、`2` = 输出侧超 5 行熔断，而原型 `block` = 输出侧整段题解丢弃、`rewrite` = 输出改写 —— 若按 `0→pass / 2→rewrite / 1→block` 映射，则「越狱尝试被输入过滤拦下」和「整段题解被输出拦截」会被合并成同一个 `block` 计数，`hitDist` 永远分不开。

**其余值得单列的中等问题（不占前五，但对接时必须处理）：**
- **单位三方不一致**：`timeout_seconds`（契约，秒）vs `form.timeout=8000` + `${timeout}ms`（a-models，毫秒）；`mem` 在 `OJ.sandbox` 是 KB（`51200` → `50.0 MB`）、在契约是字节（`268435456`）→ 直接互传会出现 1024 倍误差。
- **`channel_code` UNIQUE 与「新增通道」天然冲突**：契约把 `channel_code` 限定为 `ollama_local/deepseek/qwen/zhipu` 四个（L156 注释），而原型新增弹窗允许任意厂商 + 任意名称（M38）→ 第 5 条通道该填什么无定义。
- **`role_binding` 两值 vs 三角色多选**（M12/M32/M39）：`SYSTEM_FIXED / TUTOR_OPTIONAL` 表达不了 `{Router, Tutor, Guardrail}` 的任意组合。
- **错误码语义不覆盖**：原型需要 `502`（网关）、`503`（日志服务积压）、「健康探针超时」三种错误态，契约只有 `400/401/403/429`。
- **`data-page` 清单不可信**：`_SPEC` 只登记 13 个 id，实际 15 个业务页；`s-exam` 复用 `s-assignments`、`t-problem-edit` 复用 `t-problems`；`a-sandbox` 与 `a-audit` 是同一页两个菜单项。若要做「菜单权限接口」，**连一份权威的页面元数据表都不存在**（也就不存在所谓的 `MOUNTS`）。
