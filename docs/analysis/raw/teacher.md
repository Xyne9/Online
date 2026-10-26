# 教师端静态原型 · 字段级数据需求盘点

> 分析对象：`prototype/app/` 下 7 个教师端页面 + 共享数据层 `shell.js`
> 比对基准：《系统详细设计与接口契约规范（Detailed Design & API Specification）》
> （9 张 MySQL 表 L15-L190；6 类 Redis Key L194-L203；REST 接口 7 个 L215-L441；Feign/RabbitMQ/Go-Judge L445-L566）
> 判定口径：
> `A`＝现有 REST 接口直接返回；`B`＝现有表可派生但无接口；`C`＝现有表缺字段；`D`＝无任何表落点；`E`＝纯前端派生可算。

---

## 页面：t-dashboard.html（教师工作台 / 概览）

### 数据需求表

| # | 页面模块 / 交互点 | 所需数据（字段级） | 类型与取值域 | 数据来源判定 | 现有契约覆盖情况 | 缺口说明 |
|---|---|---|---|---|---|---|
| 1 | 页头副标题 | 班级名称、年级 | `class_name` string(64)；年级无字段（可从名称解析） | B-现有表可派生但无接口 | `teach_class.class_name` | 无班级列表接口，教师无法取到自己名下班级 |
| 2 | 页头副标题 | 当前周次、周起止日期（"第 8 周（10-18 至 10-24）"） | `week_no` int；`week_start`/`week_end` date | D-无任何表落点 | 无 | 9 张表没有任何学期/教学周历表，周次口径无处可取 |
| 3 | 待办①「待发布作业 等 2 项」 | 活动发布状态与待发布计数 | `status` enum: draft/published/closed；`count` int | C-现有表缺字段 | `teach_assignment`（仅 `start_time`/`end_time`，无 status） | `teach_assignment` 没有状态列，"草稿/待发布"无法表达，只能靠时间猜 |
| 4 | 待办②「查重待复核 1 组」 | 待裁定报告数 | `audit_status` tinyint 0-2；count | B-现有表可派生但无接口 | `teach_plagiarism_report.audit_status = 0` | 只有"一键查重"接口 3.2，没有按 `audit_status` 列表/计数的查询接口 |
| 5 | 待办③「模型通道 4 条全部在线」 | 通道启用状态与条数 | `is_enabled` tinyint 0/1；`channel_code` | B-现有表可派生但无接口 | `ai_model_config.is_enabled`、`channel_code` | 管理端配置表存在，但教师端无只读的通道状态接口 |
| 6 | 待办副标题「96.4% 需人工判定」 | 该报告的综合相似度 | `stage1_algo_sim`/`stage2_ai_sim` decimal(5,2)，0-100 | B-现有表可派生但无接口 | `teach_plagiarism_report.stage2_ai_sim` | 综合相似度需前端对两列取均值，契约未定义"综合"口径 |
| 7 | 「本周通过率」主数字 + 口径句 | 本周提交次数、通过次数、通过率 | `sub_count` int、`ac_count` int、`rate` 0-100 | B-现有表可派生但无接口 | `judge_submission.status`（3=AC）、`judge_submission.create_time` | 无按周聚合接口；`status` 的 4=PAC 是否计入"通过"契约未定义 |
| 8 | 7 天趋势柱（提交/通过双色） | 每日 `date`、`week`（周几）、`sub`、`ac` | date `MM-DD`；week enum 周一…周日；sub/ac int | B-现有表可派生但无接口 | `judge_submission.create_time` + `status` 按日 GROUP BY | 需要日粒度时序聚合接口，现无 |
| 9 | 周环比「+4.0 个百分点」 | 上周同口径通过率 | `prev_rate` 0-100、`delta` float | B-现有表可派生但无接口 | `judge_submission`（可回溯 create_time） | 契约没有任何"环比/同比"派生规则，且学期边界未定义 |
| 10 | 「本周最易错题目」列表 | 题目 `pid`、`title`、`lvl`、`tags`、`tried`（尝试人数）、`ac`（AC 率）、`pass`（通过人数）、`avg`（平均尝试次数） | pid bigint；lvl 1-5；tags string[]；tried/pass int；ac 0-100；avg float | B-现有表可派生但无接口 | `prob_question.title/difficulty/tags`；`judge_submission` 聚合出 tried/pass | 平均尝试次数需 `count(*)/count(distinct user_id)`，无接口；难度 1-5 与 `difficulty` 1-3 不一致 |
| 11 | 排行抽屉「首次错误点」 | 全班首次失败测试点众数 | `first_failed_case_id` int 1-N | B-现有表可派生但无接口 | `judge_submission.first_failed_case_id` | 需按题聚合成"第 N 个测试点"文案，无接口 |
| 12 | 排行抽屉「错误类型分布」 | WA/TLE/RE/CE 占比 | `status` enum 5/6/8/9；`pct` 0-100 ×4 | B-现有表可派生但无接口 | `judge_submission.status` | 可直方图聚合，但契约无统计接口；MLE/CE 是否归入"其他"未定义 |
| 13 | 排行抽屉「错误次数最多的学生」 | 学生 `name`、`sid`、`n`（错误次数）、`at`（最近一次） | name string、sid string(64)、n int、at datetime | B-现有表可派生但无接口 | `sys_user.real_name/username` + `judge_submission` | 需按学生聚合失败提交数与 MAX(create_time)，无接口 |
| 14 | 「进行中的教学活动」列表 | 活动 `id`、`title`、`typeName`、`pidCount`、`done`、`total`、`due`、`left` | id string('A-0412')；type enum practice/homework/exam；pidCount int；done/total int；due datetime；left 文案 | B-现有表可派生但无接口 | `teach_assignment.title/mode_type/problem_ids/end_time`；`teach_student_score` 聚合 done | `id` 是 'A-0412' 字符串，表主键是 BIGINT，编号格式无落点；done/total 需跨表聚合 |
| 15 | 「进行中的教学活动」状态 | 活动状态 | enum: doing/todo/done | C-现有表缺字段 | `teach_assignment`（无 status） | 同 #3：无状态列，"待发布"和"已结束"无法区分 |
| 16 | 次要指标「班级人数 48 / 本周活跃 44」 | `student_count`、本周活跃人数 | int；活跃＝周内有提交的 distinct user | B-现有表可派生但无接口 | `teach_class.student_count`；`judge_submission` distinct `user_id` | 活跃口径（有提交？有登录？）契约未定义 |
| 17 | AI 通栏「1840 次调用 · 平均 TTFT 0.8s」 | 调用次数、平均首字延迟 | `calls` int；`ttft_ms` int | C-现有表缺字段 | `ai_chat_record`（有记录数，无 TTFT 列） | `ai_chat_record` 没有首字耗时字段，平均值无法落库 |
| 18 | AI 通栏「批改省下 4.2 小时」 | 节省工时估算 | `hours` decimal(4,1) | D-无任何表落点 | 无 | 纯业务估算，9 张表无任何工时/批改量字段，也无可配置的折算系数 |
| 19 | AI 通栏「诊断覆盖 38 人 · 5 类错误模式」 | 被诊断人数、错误模式类别数 | `user_cnt` int；`pattern_cnt` int | D-无任何表落点 | 无（`ai_chat_record` 只记对话，不记诊断结论分类） | 无"诊断覆盖""错误模式"任何字段，属于产品叙事指标 |
| 20 | AI 通栏「四阶段使用分布」 | `stage_type` 1-4 各自次数 | int ×4，合计=总调用数 | B-现有表可派生但无接口 | `ai_chat_record.stage_type` | 可 GROUP BY，但无统计接口 |
| 21 | AI 通栏「云端 / 本地路由」 | 云端 Tutor、本地 Router、降级回落三段次数 | `selected_channel`、`actual_model`、`is_fallback` 0/1 | B-现有表可派生但无接口 | `ai_chat_record.selected_channel / actual_model / is_fallback` | 三段的归类规则（Router 与 Tutor 共用一张表）契约未给出 |
| 22 | 快捷操作 4 项 | 无数据依赖 | — | E-纯前端派生可算 | 无 | 纯导航，不构成数据需求 |
| 23 | 待办行内「发布」按钮（就地发布 2 项） | 需回写活动状态 | `status` → published | C-现有表缺字段 | `teach_assignment` | 无状态列即无发布语义，写操作无处落 |

### 交互与写操作清单

| 操作 | 触发位置 | 是否写库 | 需要的接口语义 |
|---|---|---|---|
| 就地发布作业（含 400ms 进行态 + 5 秒撤销） | 待办行「发布」按钮 | 是 | `PUT /api/teaching/assignment/{id}/publish`，幂等；撤销需 `PUT .../unpublish` |
| 跳转复核查重 | 待办行「去复核」 | 否 | 纯跳转 |
| 排行模式切换（按 AC 率 / 按尝试人数） | `.seg` 分段控件 | 否 | 建议下推为 `sort=ac_rate|tried_cnt`，否则前端只能对当前页排序 |
| 打开题目错误分析抽屉 | 排行表行点击 | 否 | `GET /api/teaching/analytics/problem/{pid}/errors` |
| 周趋势选中某天 | 柱状图点击 | 否 | 已在前端 `days[]` 内完成，无需接口 |
| 新建活动 / 班级学情 | 页头按钮 | 否 | 纯跳转 |

### 枚举/状态机

- 活动类型 `practice / homework / exam` —— 契约 `teach_assignment.mode_type` 为 `1/2/3`，语义可映射但**取值域不一致**。
- 活动状态 `doing / todo / done` —— 契约**无此字段**，不一致（缺）。
- 待办类型 `publish / plag / model` —— 前端私有分组键，契约无对应概念。
- AI 阶段 `1 读题拆解 / 2 编码引导 / 3 排错诊断 / 4 AC 复盘` —— 与 `ai_chat_record.stage_type`、`teach_assignment.allowed_ai_stages` **一致**。
- 判题状态 `WA / TLE / RE / CE` —— 与 `judge_submission.status` 的 `5/6/8/9` **一致**（数值需映射）。
- 通道状态 `on / off` —— 与 `ai_model_config.is_enabled` 的 `1/0` **一致**。

### 实时性 / 分页 / 排序 / 筛选

- **实时性**：待办计数（待发布/待复核/通道离线）建议 30s 轮询；本周指标与 7 天趋势按 5 分钟缓存即可；页面无 SSE 需求，契约也没有面向教师端的推送通道。
- **分页**：排行榜仅 5 行、活动仅 3 行，前端不必要分页；但排行榜若扩到全题库必须服务端分页。
- **排序**：`rankMode` 的两种排序目前是前端对 5 条常量排序；落到真实数据必须服务端排序（`ORDER BY ac_rate ASC` / `tried_cnt DESC`）。
- **筛选**：本页只有排序切换，无筛选控件；周次切换隐含在页头文案里，无交互。

---

## 页面：t-problems.html（题库管理）

### 数据需求表

| # | 页面模块 / 交互点 | 所需数据（字段级） | 类型与取值域 | 数据来源判定 | 现有契约覆盖情况 | 缺口说明 |
|---|---|---|---|---|---|---|
| 1 | 页头副标题「题库共 12 道 · 已发布 x · 草稿 y · 已归档 z」 | 各状态题目计数 | int ×4 | C-现有表缺字段 | `prob_question`（无 state） | 依赖不存在的状态列，三个计数都无法算 |
| 2 | 统计卡「题目总数」+「覆盖 14 个知识点」 | 题目数、`tags` 去重后的知识点数 | int；tags string[] | B-现有表可派生但无接口 | `prob_question.tags`（JSON） | 可 `JSON_LENGTH`/去重，但无题目列表接口 |
| 3 | 统计卡「已发布 / 草稿 / 已归档」 | 状态计数 | enum published/draft/archived | C-现有表缺字段 | `prob_question`（无 state） | **关键缺口**：`prob_question` 无 `state` 字段，草稿 `DRAFT=[1092,1118]` 与归档 `ARCHIVED=[1001]` 只能写死在前端 |
| 4 | 表格列「题号」 | `id` bigint，从 1001 自增 | bigint | B-现有表可派生但无接口 | `prob_question.id` | 表结构完备，缺列表查询接口 |
| 5 | 表格列「标题」 | `title` | varchar(128) | B-现有表可派生但无接口 | `prob_question.title` | 同上 |
| 6 | 表格列「难度」 | 难度 1-5 档（入门/简单/中等/较难/困难） | tinyint 1-5 | C-现有表缺字段 | `prob_question.difficulty` 为 **tinyint 1-3**（简单/中等/困难） | **取值域冲突**：页面用 5 档、契约只有 3 档，无法一一映射 |
| 7 | 表格列「标签」 | `tags` string[] | JSON array，最多 5 个 | B-现有表可派生但无接口 | `prob_question.tags` | 无接口；另需按标签筛选的 `JSON_CONTAINS` 支持 |
| 8 | 表格列「提交数」 | `submits` | int，累计人次 | B-现有表可派生但无接口 | `prob_question.submit_count` | 表里有累计值，但页面口径是"本周"时无法表达 |
| 9 | 表格列「AC 率」 | `ac` 百分比 | int 0-100 | B-现有表可派生但无接口 | `prob_question.pass_count / submit_count` | 两列可算，但契约未定义除零与"有效提交"口径 |
| 10 | 表格列「状态」 | 状态标签与配色 | enum published/draft/archived | C-现有表缺字段 | `prob_question`（无 state） | 同 #3 |
| 11 | 表格列「更新时间」 | `updated` | datetime `MM-DD HH:mm` | C-现有表缺字段 | `prob_question.create_time`（无 update_time） | 表只有创建时间，没有 `update_time`，排序与展示都失真 |
| 12 | 表格列「测试点链」`okCells / chainOf` | 10 格通过态 | 10 × enum ac/skip/空 | E-纯前端派生可算 | 由 `prob_question.pass_count/submit_count` 推算 | 前端用 AC 率×10 造格，**不是真实逐点数据**，会与真实判分链不一致 |
| 13 | 测试点链数字「n/10」 | 测试点总数 | int，默认 10 | B-现有表可派生但无接口 | `prob_question.test_case_count` | 总数可读，但"多数人过到第几格"无逐点数据 |
| 14 | 详情抽屉「测试点」表：类型 | `kind` enum sample/hidden | enum | C-现有表缺字段 | `prob_question.sample_cases`（只有公开样例内容） | 只能表达"样例内容"，无法标注某个测试点属于 sample/hidden |
| 15 | 详情抽屉「测试点」表：输入规模、期望输出 | `size` string、`out` string | string | B-现有表可派生但无接口 | `test_case_dir`（文件目录，非结构化） | 逐点 I/O 落在文件系统，库里无字段也无读取接口 |
| 16 | 详情抽屉「测试点」表：启用/停用 | `on` boolean（`off: !(id===1024 && i===8)`） | boolean | C-现有表缺字段 | `prob_question.test_case_count`（只有数量） | 没有逐点启停标记，"停用点不参与判分"无法表达 |
| 17 | 详情抽屉 提示条「第 3 组全负数，班级首次 WA 命中 27 人」 | 按测试点聚合的首次失败人数 | `case_id` int、`user_cnt` int | D-无任何表落点 | `judge_submission.case_details`（JSON 明细存在但不可聚合查询） | 无逐测试点结果表，JSON 无法按 caseId 聚合统计 |
| 18 | 详情抽屉「时限 / 内存 / 计分」 | `time_limit`、`memory_limit`、计分方式 | int ms；int MB；enum OI 按点 | B-现有表可派生但无接口 | `prob_question.time_limit / memory_limit / test_case_count` | 计分方式无字段（隐含在 OI 逻辑里），无接口 |
| 19 | 详情抽屉「AI 策略：阶段 ①③ 开放 · 上限 3 次」 | 题目级 AI 阶段与配额 | `allowed_stages` int[]、`quota` int | C-现有表缺字段 | `teach_assignment.allowed_ai_stages`（活动级），题目表无 | 契约把 AI 策略放在活动级，题目级没有对应字段，题目被多个活动引用时无法各自不同 |
| 20 | 筛选：状态分段 + 计数 | 状态枚举与计数 | 同 #3 | C-现有表缺字段 | `prob_question`（无 state） | 分段控件的 4 个计数全部依赖缺失字段 |
| 21 | 筛选：难度多选（1-5） | 难度集合 | tinyint[] 1-5 | C-现有表缺字段 | `prob_question.difficulty` 1-3 | 同 #6，5 档筛选无法映射到 3 档列 |
| 22 | 筛选：标签下拉 `tagOptions` | 全部标签字典 | string[] | B-现有表可派生但无接口 | `prob_question.tags` | 需全库 tags 去重接口 |
| 23 | 搜索（题号/标题） | `kw` 模糊匹配 | string | B-现有表可派生但无接口 | `prob_question.title`、`id` | 需 `title LIKE` + `CAST(id AS CHAR) LIKE`，无接口 |
| 24 | 分页（pageSize=8） | total、page、pageSize | int | B-现有表可派生但无接口 | `prob_question` | 表可 count，缺分页参数契约 |
| 25 | 表头排序（题号/标题/提交数/更新时间） | 4 个排序键 + 方向 | enum id/title/submits/updated；asc/desc | B-现有表可派生但无接口 | `prob_question.id/title/submit_count/create_time` | `updated` 依赖不存在的 update_time（见 #11） |
| 26 | 批量发布 / 批量归档 | 批量改状态 | `ids` bigint[]、`state` | C-现有表缺字段 | `prob_question`（无 state） | 无状态列即无批量改状态的语义 |
| 27 | 「导入题目」 | 导入结果与新增草稿 id | `ids` bigint[]、`count` int | D-无任何表落点 | 无 | 9 张表无导入任务/批次概念，契约也没有导入接口 |
| 28 | 加载/错误/空态 | 无业务字段 | — | E-纯前端派生可算 | 无 | 三态是 UI 状态，不产生数据需求 |

### 交互与写操作清单

| 操作 | 触发位置 | 是否写库 | 需要的接口语义 |
|---|---|---|---|
| 状态筛选 / 难度多选 / 标签筛选 / 搜索 | 筛选栏 | 否 | `GET /api/problem/questions?state=&difficulty=&tag=&kw=&page=&size=&sort=` |
| 表头排序 | 4 个 `th-sort` | 否 | 同上 `sort=id|title|submits|updated&dir=` |
| 分页 | 底部 `.pager` | 否 | 同上 `page/size`，返回 `total` |
| 批量发布 | 批量操作条 | 是 | `PUT /api/problem/questions/batch-state`，body `{ids[], state:'published'}` |
| 批量归档 | 批量操作条 | 是 | 同上 `state:'archived'`；需定义"归档保留历史提交"的软删除语义 |
| 单题归档 / 恢复 | 详情抽屉底部 | 是 | `PUT /api/problem/questions/{id}/state` |
| 导入题目 | 页头 | 是 | `POST /api/problem/questions/import`（multipart + 模板校验 + 落草稿） |
| 打开详情抽屉 | 整行点击 | 否 | `GET /api/problem/questions/{id}`（含测试点摘要） |
| 重新加载（错误态） | 错误态按钮 | 否 | 重放上一次查询 |

### 枚举/状态机

- 发布状态 `published / draft / archived` —— 契约 `prob_question` **无 state 字段**，不一致（缺）。
- 难度 `1 入门 / 2 简单 / 3 中等 / 4 较难 / 5 困难` —— 契约 `difficulty` 只有 `1 简单 / 2 中等 / 3 困难`，**档位数不一致**。
- 测试点类型 `sample / hidden` —— 契约只有 `sample_cases`（内容），**无逐点类型**。
- 测试点启停 `on / off` —— 契约**无字段**。
- 判题状态（用于 AC 率语义）`ac/wa/tle/re/ce/pac` —— 与 `judge_submission.status` **一致**，但 `PAC(4)` 是否计入 AC 率未定义。

### 实时性 / 分页 / 排序 / 筛选

- **实时性**：无需实时通道；AC 率与提交数为小时级缓存即可。
- **分页**：**必须服务端分页**（pageSize=8，题库量级会到千级），当前 `filtered/paged` 是全量前端切片。
- **排序**：4 个键全部需服务端排序，尤其 `updated` 依赖缺失字段（见 #11）。
- **筛选**：状态（互斥）、难度（多选集合）、标签（单选）、关键词四组条件需组合下推到 SQL；难度筛选因档位不一致必须先解决字段问题。

---

## 页面：t-problem-edit.html（题目编辑 / 测试点管理）

### 数据需求表

| # | 页面模块 / 交互点 | 所需数据（字段级） | 类型与取值域 | 数据来源判定 | 现有契约覆盖情况 | 缺口说明 |
|---|---|---|---|---|---|---|
| 1 | 页头「编辑题目 #1024」「最近保存 10-24 21:40」 | `id`、`update_time` | bigint；datetime | C-现有表缺字段 | `prob_question.id`；无 `update_time` | 只有 `create_time`，"最近保存"与并发编辑冲突检测都无依据 |
| 2 | 基本信息「题目名称」 | `title`，maxlength 40，非空 | varchar(128)，页面限 40 字 | B-现有表可派生但无接口 | `prob_question.title` | 字段够用，缺保存接口 |
| 3 | 基本信息「难度」5 格刻度 | `lvl` 1-5 | tinyint 1-5 | C-现有表缺字段 | `prob_question.difficulty` 1-3 | **取值域冲突**：5 档无法落到 3 档列 |
| 4 | 基本信息「所属知识点」单选 | `kp`，枚举 6 个（动态规划/图论/字符串/树结构/排序与查找/数学） | string，单选 | C-现有表缺字段 | `prob_question.tags`（JSON 数组，且是多标签） | 页面要求"单一主知识点"，契约只有多标签数组，无主知识点字段 |
| 5 | 基本信息「标签」多标签编辑 | `tags` string[]，≤5，单个 ≤8 字 | JSON array | B-现有表可派生但无接口 | `prob_question.tags` | 结构一致，但 5 个/8 字的约束库里没有，无校验接口 |
| 6 | 基本信息「时间限制」「内存限制」 | `tl` int ms（min 100, step 100）、`ml` int MB（min 16, step 16） | int | B-现有表可派生但无接口 | `prob_question.time_limit`（默认 1000）、`memory_limit`（默认 256） | 字段齐全，缺保存接口与"Java/Py ×2"规则的落点 |
| 7 | 基本信息「AI 调用上限 3 次」 | `aiLimit` int | int，-1=不限 | C-现有表缺字段 | `teach_assignment.ai_quota_per_prob`（活动级） | 题目级 AI 配额无字段，题目被多活动引用时无法各自配置 |
| 8 | 基本信息「AI 辅助策略」开关 + 四阶段 chip | `aiOn` boolean、`stages[4].on` | boolean ×5 | C-现有表缺字段 | `teach_assignment.allowed_ai_stages`（活动级） | 同 #7，题目级无 AI 阶段字段 |
| 9 | 测试点表「类型」下拉 | `kind` enum sample/normal/edge/stress（4 档） | enum ×4 | C-现有表缺字段 | `prob_question.sample_cases`（仅公开样例） | 契约只有"公开样例内容"，**无法表达 4 档逐点类型** |
| 10 | 测试点表「分值」输入 | `score` int 0-100，step 5，合计必须 100 | int | C-现有表缺字段 | `prob_question.test_case_count`（只有数量） | **关键缺口**：无逐测试点分值字段，OI 按点计分无法按自定义权重算分 |
| 11 | 测试点表「输入摘要 / 标准输入」 | `input` text | text（可多行） | B-现有表可派生但无接口 | `test_case_dir`（`/data/testcases/1001/` 下的 .in 文件） | 输入落在文件系统，库里无字段、无读写接口，编辑态无法回显 |
| 12 | 测试点表「期望输出」 | `output` text | text（可多行） | B-现有表可派生但无接口 | `test_case_dir` 下的 .out 文件 | 同 #11 |
| 13 | 测试点表「展开/收起」 | `open` boolean | boolean | E-纯前端派生可算 | 无 | 纯 UI 展开态，不落库 |
| 14 | 测试点表「启用/隐藏」可见性 | 该点是否对学生可见 | boolean | C-现有表缺字段 | `prob_question.sample_cases` | 只能靠"是否在 sample_cases 里"隐式推断，无法逐点开关 |
| 15 | 标题行判分链预览（格高=分值、色=类型） | 逐点 `score` + `kind` | int + enum | C-现有表缺字段 | 无 | 同 #9/#10，两个维度都没落点 |
| 16 | 「分值合计」条 + `total !== 100` 校验 | 合计分、与 100 的差额 | int；`canPublish` boolean | E-纯前端派生可算 | 无 | 前端可算，但**服务端没有同等校验**，绕过前端即可发不满 100 分的题 |
| 17 | 「添加测试点」「删除测试点」 | 测试点数量变化 | `test_case_count` int | B-现有表可派生但无接口 | `prob_question.test_case_count` | 只存总数，增删的逐点实体无处存 |
| 18 | 题面编辑器（Markdown） | `statement` text，含 `#/##`、`**`、反引号 | text | B-现有表可派生但无接口 | `prob_question.content`（TEXT，Markdown 正文） | 字段语义一致，缺保存接口 |
| 19 | 题面「字符数 / 段数」 | 派生统计 | int ×2 | E-纯前端派生可算 | 无 | 纯前端 |
| 20 | 学生端预览（题面/判分链两个 tab） | 全部表单字段的即时镜像 | — | E-纯前端派生可算 | 无 | 前端渲染，无额外数据依赖 |
| 21 | 底部操作条「上次保存 · 已同步 / 有未保存修改」 | `dirty`、`savedAt` | boolean、datetime | E-纯前端派生可算 | 无 | 前端状态；真实脏检查需服务端版本号 |
| 22 | 底部「存草稿」 | 落草稿态 | `state='draft'` | C-现有表缺字段 | `prob_question`（无 state） | 无草稿状态列，草稿保存无处落 |
| 23 | 底部「保存并发布」+ 确认弹窗 | 发布态 + 影响面（测试点数、已有提交不受影响） | `state='published'`；`test_case_count` | C-现有表缺字段 | `prob_question`（无 state） | 同 #22；另需"发布不动历史提交"的约束定义 |
| 24 | 加载态 / 错误态（504） | 无业务字段 | — | E-纯前端派生可算 | 无 | UI 三态 |

### 交互与写操作清单

| 操作 | 触发位置 | 是否写库 | 需要的接口语义 |
|---|---|---|---|
| 存草稿 | 底部操作条 | 是 | `POST/PUT /api/problem/questions`，`state='draft'`，返回 `id` + `update_time` |
| 保存并发布 | 底部操作条 + 确认弹窗 | 是 | 同上 `state='published'`；服务端必须重算分值和=100 |
| 添加 / 删除测试点 | 测试点卡标题行、行内删除 | 是（随题目整体保存） | 需支持 `testCases[]` 数组整体 PUT（含 score/kind/visible/input/output） |
| 修改测试点分值 | 表格内 number 输入 | 是（随保存） | 同上；需服务端校验 0-100 与合计 100 |
| 切换测试点类型 | 行内 select | 是（随保存） | 同上 |
| 编辑测试点输入/输出 | 展开行两个 textarea | 是（随保存） | 需把文件型测试点转成可读写接口，或落独立表 |
| 添加 / 移除标签 | 标签框 | 是（随保存） | 需 `tags` 数组校验（≤5、去重、单标签长度） |
| 切换 AI 阶段 / 开关 | AI 策略区 | 是（随保存） | 需题目级 AI 策略字段与校验 |
| 还原表单 | 页头「还原」 | 否 | 重新拉取 `GET /api/problem/questions/{id}` |
| 重试加载 | 错误态按钮 | 否 | 重放查询 |
| 插入公式 / 插入样例 | 题面工具条 | 否（仅改前端 buffer） | 纯前端文本插入 |

### 枚举/状态机

- 题目状态 `draft / published / archived` —— 契约**无 state 字段**，不一致（缺）。
- 难度 `1-5` —— 契约 `difficulty 1-3`，**不一致**。
- 测试点类型 `sample / normal / edge / stress` —— 契约**无对应字段**（`sample_cases` 只能表达公开样例），不一致。
- 测试点可见性 `visible / hidden` —— 契约**无字段**。
- AI 阶段 `① 读题拆解 / ② 编码引导 / ③ 排错诊断 / ④ AC 复盘` —— 与 `ai_chat_record.stage_type` / `allowed_ai_stages` **一致**（但作用层级从活动级变为题目级）。
- 发布校验态 `canPublish`（合计=100 且标题非空）—— 契约**无对应校验规则**。

### 实时性 / 分页 / 排序 / 筛选

- **实时性**：无轮询/SSE；"学生端预览"是本地即时渲染，不需要通道。但多人协同编辑需要版本号（`update_time` 或 `version`），当前契约没有。
- **分页**：测试点最多十余条、标签 ≤5，无需分页。
- **排序**：测试点按数组顺序（`#1..#N`）固定，不支持排序；但**顺序本身是语义**（判分链第 N 格），契约没有 `case_order` 字段。
- **筛选**：本页无筛选控件。

---

## 页面：t-assignments.html（作业与考试管理）

### 数据需求表

| # | 页面模块 / 交互点 | 所需数据（字段级） | 类型与取值域 | 数据来源判定 | 现有契约覆盖情况 | 缺口说明 |
|---|---|---|---|---|---|---|
| 1 | 页头副标题 | 班级名、人数、本学期活动数 | string；int；int | B-现有表可派生但无接口 | `teach_class.class_name/student_count`；`teach_assignment` count | 无活动列表接口 |
| 2 | 统计条「进行中 / 待发布 / 已结束」 | 三态计数 | enum doing/todo/done | C-现有表缺字段 | `teach_assignment`（无 status） | 无状态列，三个计数无法算 |
| 3 | 统计条「平均完成率」 | 13 项活动的完成率均值 | float 0-100 | B-现有表可派生但无接口 | `teach_student_score` 按活动聚合 | 需先定义"完成率"分子分母（已提交人数/班级人数），契约未定义 |
| 4 | 表格列「活动」：名称、编号 | `title`、`id` | varchar(128)；页面为 `'A-0421'` 字符串 | C-现有表缺字段 | `teach_assignment.id` 为 **BIGINT 自增** | 编号格式不一致，页面的 'A-xxxx' 无落点 |
| 5 | 表格列「活动」副行 | `pidCount`、班级人数 48 | int ×2 | B-现有表可派生但无接口 | `JSON_LENGTH(teach_assignment.problem_ids)`、`teach_class.student_count` | 可算，无接口 |
| 6 | 表格列「类型」 | `type` enum practice/homework/exam | enum ×3 | B-现有表可派生但无接口 | `teach_assignment.mode_type` 1/2/3 | 取值域不一致（字符串 vs tinyint），需映射表 |
| 7 | 表格列「题目」 | 题目数 | int | B-现有表可派生但无接口 | `teach_assignment.problem_ids` | 同上 |
| 8 | 表格列「完成率」：`done`、进度条档位 | 已提交人数 | int 0-48 | B-现有表可派生但无接口 | `teach_student_score` distinct `user_id` by `assignment_id` | 需聚合；`done/48` 分母写死在前端 |
| 9 | 表格列「平均分」 | `avg` 0-100，待发布为 null | int / null | B-现有表可派生但无接口 | `teach_student_score.max_score` 按活动取均值 | 单题最高分表可算活动均分，但契约未定义是否按题加权 |
| 10 | 表格列「AI 阶段」四格 | `allowed_ai_stages`、`ai_quota_per_prob` | int[] ⊆ {1,2,3,4}；int | B-现有表可派生但无接口 | `teach_assignment.allowed_ai_stages / ai_quota_per_prob` | 字段齐全，但页面把策略写成 `'仅①③ · 上限 3 次'` 文案，需规则引擎或前端拼装 |
| 11 | 表格列「截止时间」 | `end_time` | datetime | B-现有表可派生但无接口 | `teach_assignment.end_time` | 缺接口；页面 `MM-DD HH:mm` 需格式化 |
| 12 | 表格列「状态」 | `status` enum doing/todo/done | enum ×3 | C-现有表缺字段 | `teach_assignment`（无 status） | **关键缺口**：无活动状态机，"待发布"在表里完全不可表达 |
| 13 | 抽屉「已提交 / 完成率 / 平均分 / AI 调用」 | `done`、`pct`、`avg`、`aiTotal` | int / int / int / int | B-现有表可派生但无接口 | `teach_student_score.ai_used_count` 求和 | AI 调用次数表里有，但"活动内 AI 总次数"需聚合接口 |
| 14 | 抽屉「题目数 / 截止 / 剩余」 | `pidCount`、`end_time`、`left` | int / datetime / 文案 | B-现有表可派生但无接口 | `teach_assignment.problem_ids / end_time` | `left`（剩 N 天）前端可算，但依赖服务端时间基准 |
| 15 | 抽屉学生名单：姓名、学号 | `real_name`、`username` | varchar(64) ×2 | B-现有表可派生但无接口 | `sys_user.real_name / username` | 无按班级取名单接口；页面用 48 人常量 + 确定性哈希伪造 |
| 16 | 抽屉学生名单：作答进度 | `prog`/`total` | int / int | B-现有表可派生但无接口 | `teach_student_score`（按 `assignment_id,user_id` 计已提交题数） | "进度"=已提交题数，未提交的本地编辑无法计入 |
| 17 | 抽屉学生名单：得分 | `score` 0-100 | int / null | B-现有表可派生但无接口 | `teach_student_score.max_score` 按活动求和或取均值 | 契约只到"单题最高分"，活动总分口径未定义 |
| 18 | 抽屉学生名单：AI 次数 | `ai` int | int | B-现有表可派生但无接口 | `teach_student_score.ai_used_count`、`ai_chat_record` | 同 #13 |
| 19 | 抽屉学生名单：风险标签 | `risk` enum ok/ai/low + 文案 | enum ×3 | D-无任何表落点 | `sys_user.weak_tags`（只到标签频次，无风险等级） | **风险等级无任何字段**，`risk='ai'`/'low' 的判定阈值也无处配置 |
| 20 | 抽屉筛选「全部/已提交/未提交/需关注」 | 计数 ×4 | int ×4 | E-纯前端派生可算 | 无 | 可对已取名单前端过滤，但名单本身需分页接口 |
| 21 | 新建活动弹窗：名称/类型/题目数/截止时间 | `title`、`mode_type`、`problem_ids`、`end_time` | varchar(128)、tinyint 1-3、JSON 数组、datetime | B-现有表可派生但无接口 | `teach_assignment` 同名字段 | 缺创建接口；且"题目数"输入框与 `problem_ids` 数组语义不匹配（只能填数量不能选题） |
| 22 | 新建活动弹窗：AI 策略（只读、随类型套用） | 由 `mode_type` 推导 `allowed_ai_stages` / `ai_quota_per_prob` | int[]、int | B-现有表可派生但无接口 | `teach_assignment.allowed_ai_stages / ai_quota_per_prob` | 推导规则（练习[1,2,3,4]/作业[1,3]/考试[]）写在注释里，契约无枚举约束表 |
| 23 | 发布 / 结束活动 | 状态流转 | doing ↔ done | C-现有表缺字段 | `teach_assignment`（无 status） | 无状态列即无流转；"结束后成绩归档"也无归档标记 |

### 交互与写操作清单

| 操作 | 触发位置 | 是否写库 | 需要的接口语义 |
|---|---|---|---|
| 新建活动（存草稿） | 页头「新建活动」→ 弹窗 | 是 | `POST /api/teaching/assignments`，`state='draft'`，返回活动 id/编号 |
| 发布活动 | 抽屉底部「发布活动」 | 是 | `PUT /api/teaching/assignments/{id}/publish`（校验题目齐备、绑定班级） |
| 结束活动 | 抽屉底部「结束活动」 | 是 | `PUT /api/teaching/assignments/{id}/close`，触发成绩归档 |
| 撤销上述状态变更（5 秒） | toast「撤销」 | 是（回滚） | 状态回滚接口，或前端延迟提交 |
| 导出成绩 CSV | 页头「导出成绩」 | 否（导出流） | `GET /api/teaching/assignments/{id}/export`（CSV，含学号/姓名/得分/AI 次数） |
| 状态分段 / 类型多选 / 搜索 | 筛选栏 | 否 | `GET /api/teaching/assignments?state=&types=&kw=&page=&size=&sort=` |
| 表头排序（活动/完成率/平均分/状态） | 4 个 `th-sort` | 否 | 同上 `sort=title|rate|avg|status` |
| 分页（9 条/页） | 底部 `.pager` | 否 | 同上 |
| 打开完成情况抽屉 | 整行点击 | 否 | `GET /api/teaching/assignments/{id}/progress?filter=&page=` |
| 抽屉内筛选学生 | chip 组 | 否 | 同上 `filter=all|submitted|nosub|risk` |
| 重试加载 | 错误态按钮 | 否 | 重放查询 |

### 枚举/状态机

- 活动类型 `practice / homework / exam` —— 契约 `mode_type` `1/2/3`，**取值域不一致**（需映射）。
- 活动状态 `doing / todo / done` —— 契约**无 status 字段**，不一致（缺）。
- AI 阶段组合 `全阶段[1,2,3,4] / 仅①③[1,3] / 全关[]` —— 与 `allowed_ai_stages` **一致**。
- 风险标签 `ok / ai / low` —— 契约**无字段**（`weak_tags` 是标签频次 JSON，不是风险等级）。
- 学生提交态 `sub / nosub` —— 可由 `teach_student_score.submit_count > 0` 派生，与契约**语义一致但无字段直出**。

### 实时性 / 分页 / 排序 / 筛选

- **实时性**：无实时需求；完成率可 1-5 分钟缓存。考试类活动若在监考中，应跳转 `t-monitor`。
- **分页**：13 条活动前端分页（9/页）尚可，但活动数会累积到百级，需服务端分页。
- **排序**：4 个排序键（title/rate/avg/status）必须下推；`avg` 存在 null（待发布），排序时空值必须固定排末位——这条规则当前只在前端 `filtered` 里实现。
- **筛选**：状态（互斥）、类型（多选集合）、关键词三组；抽屉内还有学生维度四态筛选。

---

## 页面：t-analytics.html（班级学情分析）

### 数据需求表

| # | 页面模块 / 交互点 | 所需数据（字段级） | 类型与取值域 | 数据来源判定 | 现有契约覆盖情况 | 缺口说明 |
|---|---|---|---|---|---|---|
| 1 | 班级下拉（3 个班，48/46/52 人） | `class_id`、`class_name`、`student_count` | bigint、varchar(64)、int | B-现有表可派生但无接口 | `teach_class.id/class_name/student_count` | 无"教师名下班级列表"接口 |
| 2 | 时间范围「本周/本月/学期」 | 时间窗参数（非字段） | enum week/month/term | E-纯前端派生可算 | 无（作为查询参数） | 需接口接受 `range` 参数并统一口径 |
| 3 | 数据截至时间 `rangeTip` | 统计快照时间 | datetime | D-无任何表落点 | 无 | 无统计快照/物化视图表，无法给出"数据截至" |
| 4 | 统计卡「班级人数 / 本周活跃」 | `classSize`、`activeCount` | int ×2 | B-现有表可派生但无接口 | `teach_class.student_count`；`judge_submission` distinct user | 活跃口径未定义（提交？登录？） |
| 5 | `buildStudents(range)`：总分 `score`（55-96） | 学生活动总分/加权总分 | int 0-100 | D-无任何表落点 | `teach_student_score.max_score` 只有**单题**最高分 | **关键缺口**：9 张表没有"学生总分/加权成绩"落点，`teach_student_score` 是 `(assignment,user,problem)` 粒度，聚合口径与权重都无处定义 |
| 6 | `buildStudents`：AC 率 `rate`（39-85） | 学生 AC 率 | int 0-100 | B-现有表可派生但无接口 | `judge_submission.status` 按 `user_id` 聚合 | 可 `count(AC)/count(*)`，但"AC 率"的分母（提交数 or 题数）契约未定义 |
| 7 | `buildStudents`：AI 调用 `ai`（0-13） | 学生 AI 使用次数 | int | B-现有表可派生但无接口 | `teach_student_score.ai_used_count`、`ai_chat_record` | 两处口径不同（单题累计 vs 会话记录），需统一 |
| 8 | `buildStudents`：已解决 `solved` / `total` | 已 AC 题数 / 总题数 | int / int | B-现有表可派生但无接口 | `teach_student_score` 中 `max_score=100` 的题数 | `total` 取"活动题目数"还是"全库题数"未定义 |
| 9 | `buildStudents`：近 7 天 `spark[7]` | 每日提交次数 | int ×7 | B-现有表可派生但无接口 | `judge_submission.create_time` 按日聚合 | 需按学生×日的时序接口 |
| 10 | 统计卡「平均分 / 中位数 / 最高 / 标准差」 | 派生统计量 | float ×4 | E-纯前端派生可算 | 由 #5 派生 | 前端可算，但依赖缺失的总分落点（#5） |
| 11 | 统计卡「AC 率 +4% 较上周期」 | 上周期 AC 率与差值 | int、int | B-现有表可派生但无接口 | `judge_submission`（可回溯） | 无上周期快照，只能实时重算，成本高且口径易漂移 |
| 12 | 统计卡「风险学生 13 人 / 含 x 人 AI 依赖」 | 风险计数 | int ×2 | D-无任何表落点 | `sys_user.weak_tags`（无风险等级） | 风险规则（`ai>=12` → AI 依赖、`score<63` → 落后）只写在前端，无字段、无阈值配置 |
| 13 | 主角卡「AI 薄弱点解读」标题与正文 | 最弱知识点名、掌握度、班级线、差值与下降幅度 | string；pct 0-100；delta int | D-无任何表落点 | 无（结论由前端模板拼装） | 不是数据问题而是"AI 生成结论"的问题：契约无任何学情解读生成/存储接口 |
| 14 | 主角卡「分析依据」三条 | 知识点 AC 人数/尝试人数、热力图结论、环比 delta | int / int / int | B-现有表可派生但无接口 | `teach_student_score` + `prob_question.tags` | 前两条可算，第三条 delta 需历史快照（缺） |
| 15 | 知识点掌握度清单 `KP_BASE` | 知识点名、`pct`、`ac`、`tried`、`delta` | string；int；int；int；int | C-现有表缺字段 | `prob_question.tags`（JSON 数组）+ `teach_student_score` | **关键缺口**：`tags` 是 JSON 无法索引 join，"该标签下 AC 人数占比"只能全表扫描；且 `delta` 必须历史快照，无表 |
| 16 | 「掌握度 ≥70% 达标」判定 | 阈值与达标计数 | int；count | E-纯前端派生可算 | 无 | 阈值写死在前端，服务端无配置 |
| 17 | 成绩分布（≥85 / 60-84 / <60 + max/min/median/sd） | 分数分桶与统计量 | int%；int ×3；float | E-纯前端派生可算 | 由 #5 派生 | 分桶阈值写死前端，契约无定义 |
| 18 | 测试点通过热力图 `heatRows` | 每题 10 格「多数通过/没跑到/卡住」+ 全班通过率 `pass` | pid；cells enum ×10；pass 0-100 | C-现有表缺字段 | `judge_submission.case_details`（JSON，含 caseId/status） | **关键缺口**：无逐测试点结果表，JSON 无法按 caseId 聚合出"每格通过率"；页面当前用 `p.ac` 反推格数 |
| 19 | 热力图脚注文案 | 选中题通过率、多数人跑到第几格 | int；int | E-纯前端派生可算 | 无 | 由 #18 派生 |
| 20 | 学生成绩表（7 列） | `name`、`sid`、`solved/total`、`score`、`ai`、`rate`、`risk`、`spark` | 见 #5-#9、#12 | C-现有表缺字段 | 混合：`sys_user` + `teach_student_score` + `judge_submission` | 单表取不齐，需宽表/物化视图；`score`、`risk` 无落点 |
| 21 | 学生表排序（姓名/已解决/总分/AI/AC 率） | 5 个排序键 | enum name/solved/score/ai/rate | B-现有表可派生但无接口 | 派生列 | 派生列排序必须服务端做（前端只有当前页 10 条） |
| 22 | 学生表分页（10 条/页） | total/page/size | int ×3 | B-现有表可派生但无接口 | `sys_user` by `class_id` | 需服务端分页 |
| 23 | 「仅风险学生」筛选 | 风险过滤 | boolean | C-现有表缺字段 | 无 | 依赖 #12 的风险落点 |
| 24 | 风险学生预警卡（`reason` 文案） | 风险原因（AI 次数/总分差） | string | E-纯前端派生可算 | 无 | 文案可拼，但阈值来自 #12 |
| 25 | 「AI 提示使用 TOP 5」 | 学生名、`ai` 次数、主用阶段 | string；int；string | B-现有表可派生但无接口 | `ai_chat_record.stage_type` 按 user 聚合 | 需"按学生×阶段"聚合接口；"为主"的判定规则未定义 |
| 26 | 「本周期待办」steps（4 条） | 待办标题、日期、完成态 | string；date；enum done/doing/wait | D-无任何表落点 | 无 | 纯静态文案，无待办表 |
| 27 | 学生抽屉：按标签掌握情况 | 5 个标签的 `pct`、`ac`、`tried` | string；int ×3 | B-现有表可派生但无接口 | `prob_question.tags` + `teach_student_score` | 同 #15 的 JSON join 问题 |
| 28 | 学生抽屉：最近提交（4 条） | `id`、`pid`、`title`、`status`、`pass`、`at` | string；bigint；string；enum；int；datetime | B-现有表可派生但无接口 | `judge_submission` 同名字段 | 需按学生取最近 N 条 |
| 29 | 「发送提醒」 | 提醒动作 | — | D-无任何表落点 | 无 | 无消息/通知表，也无推送通道 |
| 30 | 「导出报告」 | 导出动作 | — | D-无任何表落点 | 无 | 无导出接口 |
| 31 | 能力雷达（若后续新增） | 多维度能力值 | — | D-无任何表落点 | 无 | **当前原型没有雷达图**（全页只有知识点条形 + 热力图 + 分布条），契约也没有能力维度模型 |

### 交互与写操作清单

| 操作 | 触发位置 | 是否写库 | 需要的接口语义 |
|---|---|---|---|
| 切换班级 | 班级下拉 | 否 | `GET /api/teaching/analytics/class/{classId}?range=` |
| 切换时间范围（本周/本月/学期） | 分段控件 | 否 | 同上 `range=week|month|term`，须返回同一份口径 |
| 查看/收起分析依据 | 主角卡按钮 | 否 | 纯前端展开 |
| 布置薄弱点专项练习 | 主角卡主按钮 | 否（跳转带参） | 跳转 `t-assignments.html?kp=`，由活动创建接口消费 |
| 点选热力图某行 | 热力图行按钮 | 否 | 数据已在前端，无接口 |
| 表头排序（5 键） | 学生表 | 否 | `sort=name|solved|score|ai|rate&dir=` |
| 分页 | 底部 `.pager` | 否 | `page/size` |
| 仅风险学生 | chip | 否 | `riskOnly=true` |
| 打开学生抽屉 | 行点击 | 否 | `GET /api/teaching/analytics/student/{sid}?range=` |
| 发送提醒 | 抽屉底部 | **是** | 需 `POST /api/teaching/notify`（当前契约**无**任何通知接口） |
| 导出报告 | 页头 | 否（导出流） | `GET /api/teaching/analytics/export?classId=&range=` |
| 重试加载 | 错误态按钮 | 否 | 重放查询 |

### 枚举/状态机

- 时间范围 `week / month / term` —— 契约**无对应枚举**（仅作为隐含查询参数），不一致。
- 风险等级 `ok / ai（AI 依赖）/ low（进度落后）` —— 契约**无字段**，不一致。
- 分数分桶 `≥85 通过 / 60-84 部分通过 / <60 未通过` —— 契约**无定义**，与 `judge_submission.oi_score` 的 0-100 可对齐但阈值需明确。
- 知识点集合 `动态规划/贪心/图论/字符串/数学` —— 与 `prob_question.tags` **弱一致**（tag 是自由文本，不是受控词表）。
- 判题状态（抽屉最近提交）`ac/wa/tle/...` —— 与 `judge_submission.status` **一致**。
- 步骤态 `done / 进行 / wait` —— 契约**无待办表**，不一致（缺）。

### 实时性 / 分页 / 排序 / 筛选

- **实时性**：无需实时；统计结果应允许 5-15 分钟缓存，且必须返回"数据截至"时间——当前契约没有快照表，这是硬伤。
- **分页**：**必须服务端分页**（48 人 × 多班，10 条/页），前端 `sorted.slice` 只对已取数据切片。
- **排序**：5 个键全部需服务端排序；`score/solved/ai/rate` 都是派生列，要求服务端先物化。
- **筛选**：班级、时间范围、仅风险学生三组；筛选变化时页面会重进骨架态，说明交互上已按"重新请求"设计，但契约无对应接口。

---

## 页面：t-monitor.html（考试实时监考）

### 数据需求表

| # | 页面模块 / 交互点 | 所需数据（字段级） | 类型与取值域 | 数据来源判定 | 现有契约覆盖情况 | 缺口说明 |
|---|---|---|---|---|---|---|
| 1 | 页头副标题 | 班级名、考试名、题目数、时长 | string；string；int；int 分钟 | B-现有表可派生但无接口 | `teach_class.class_name`、`teach_assignment.title/problem_ids/start_time/end_time` | "120 分钟"可由 `end_time-start_time` 算，但无考试详情接口 |
| 2 | 考试状态条：进行中/已结束 | 考试运行状态 | enum live/ended | C-现有表缺字段 | `teach_assignment`（无 status，只能靠时间推断） | 提前结束、暂停都无法表达 |
| 3 | 剩余时间倒计时 `leftSec` / `clock` | `end_time`、服务端当前时间 | datetime；int 秒 | E-纯前端派生可算 | `teach_assignment.end_time` | 前端可算，但需服务端时间基准防篡改 |
| 4 | 状态条「开考 10-24 19:00 · 每 5 秒刷新」 | `start_time`、刷新策略 | datetime；int 秒 | B-现有表可派生但无接口 | `teach_assignment.start_time` | 刷新策略无配置项 |
| 5 | 学生宫格：姓名、学号 | `real_name`、`username` | varchar(64) ×2 | B-现有表可派生但无接口 | `sys_user.real_name/username`（`class_id` 过滤） | 无考场名单接口，页面用 48 人常量伪造 |
| 6 | 学生宫格：已完成题数 `done` / 总题数 5 | 已作答题数 | int / int | C-现有表缺字段 | `teach_student_score.submit_count`（提交次数，非题数） | **关键缺口**：无"作答进度"字段；`submit_count` 是同一题的重复提交次数，语义完全不同 |
| 7 | 学生宫格：切屏次数 `switch` | `tab_switch_count` | int ≥0 | B-现有表可派生但无接口 | `teach_student_score.tab_switch_count` | **写入有接口 3.1，读取无接口**；且写入按 `(assignment,user,problem)` 分题记，页面要按人汇总 |
| 8 | 学生宫格：状态 `doing/submitted/offline/abnormal` | 在线心跳、交卷态、异常标记 | enum ×4 | D-无任何表落点 | `teach_student_score.red_flag_type` 只能表达 0/1/2 三档 | 无"在线/断线"心跳落点；`red_flag_type` 语义是"切屏超阈值/查重抄袭"，与 offline 无关 |
| 9 | 学生宫格：进入考场时间 `enter` | 入场时间 | datetime | D-无任何表落点 | 无 | 无入场记录表 |
| 10 | 学生宫格：最后活跃 `last` | 最后活跃时间 | datetime | D-无任何表落点 | 无 | 无心跳/活跃时间字段 |
| 11 | 学生宫格：AI 调用 `ai`（考试应为 0） | `ai_used_count` | int | B-现有表可派生但无接口 | `teach_student_score.ai_used_count`、`ai_chat_record` | 考试模式应恒为 0，但契约只靠 `allowed_ai_stages=[]` 拦截，无审计读接口 |
| 12 | 右栏「实时事件」流 | 事件 `id`、`sid`、`who`、`what`、`note`、`at`、`tone`、`icon` | id string；sid；string；string；datetime；enum ok/warn/danger | D-无任何表落点 | 无（无行为事件表，也无推送通道） | **关键缺口**：实时事件流没有任何落点，契约里唯一的 SSE 是学生端 AI 对话，教师监考端无推送通道 |
| 13 | 「考场概况：断线未回」 | 断线人数 | int | D-无任何表落点 | 无 | 同 #8，无心跳即无断线判定 |
| 14 | 「平均进度 x/5 题」 | 全场平均已答题数 | float | C-现有表缺字段 | 无（`submit_count` 不等于题数） | 同 #6 |
| 15 | 「切屏累计 92 次」 | 全场切屏次数求和 | int | B-现有表可派生但无接口 | `SUM(teach_student_score.tab_switch_count)` | 可按活动求和，无接口 |
| 16 | 「最长静默」 | 最久未活跃的学生 + 时间 | string；datetime | D-无任何表落点 | 无 | 无 `last_active_time` 字段 |
| 17 | 「考场状态分布」四档人数 | 四档计数（合计=总人数） | int ×4 | D-无任何表落点 | 无 | 依赖 #8 的状态落点 |
| 18 | 学生抽屉：进度/切屏/AI | `done`、`switch`、`ai` | int ×3 | C-现有表缺字段 | 混合（`tab_switch_count` 有，`done` 无） | 同 #6 |
| 19 | 学生抽屉：行为时间线 | 入场/完成第 N 题/切屏/断线/交卷事件序列 | `event_type` enum；`at` datetime；`detail` json | D-无任何表落点 | 无 | 无行为事件表，时间线无法重建 |
| 20 | 学生抽屉：作答记录（题/状态/用时） | `pid`、`title`、`status`、`cost` | bigint；string；enum；int 秒 | B-现有表可派生但无接口 | `judge_submission`（按 `assignment_id,user_id`） | 可查但无接口；`cost` 需 `time_used` |
| 21 | 「结束考试」确认（自动收卷 38 份） | 进行中人数、收卷份数 | int ×2 | C-现有表缺字段 | 无 | 无考试运行状态与收卷结果字段 |
| 22 | 「延长 5 分钟」 | 个人延时 | `extra_minutes` int | D-无任何表落点 | `teach_assignment.end_time`（活动级，无法个人级） | 无个人级延时字段，只能改全场 |
| 23 | 「发送警告」 | 警告动作与记录 | — | D-无任何表落点 | 无 | 无通知/警告记录表 |
| 24 | 沙箱 CPU / 内存 | （本页**不存在**该模块） | — | D-无任何表落点 | 无 | 原型 `t-monitor.html` 全文无沙箱字样；沙箱 CPU/内存只出现在 `a-audit.html`，且契约也无沙箱资源表 |

### 交互与写操作清单

| 操作 | 触发位置 | 是否写库 | 需要的接口语义 |
|---|---|---|---|
| 搜索姓名/学号 | 考场学生面板 | 否 | 前端过滤即可（名单已全量下发），或 `GET .../roster?kw=` |
| 切屏上报（学生端触发） | 学生端离开页面 | **是** | `POST /api/teaching/exam/tab-switch`（**契约已有，接口 3.1**） |
| 按状态筛选（全部/进行中/已交卷/断线/异常） | chip 组 | 否 | 建议 `filter=` 下推，返回四档计数 |
| 暂停/继续事件流 | 实时事件面板 | 否 | 纯前端暂停渲染，不涉及接口 |
| 点事件定位学生 | 事件行 | 否 | 纯前端滚动 |
| 打开学生详情 | 学生卡点击 | 否 | `GET /api/teaching/exam/{assignmentId}/student/{sid}` |
| 发送警告 | 抽屉底部 | **是** | 需 `POST /api/teaching/exam/warn`（契约**无**） |
| 延长 5 分钟 | 抽屉底部 | **是** | 需 `PUT /api/teaching/exam/{id}/extend`（契约**无**） |
| 结束考试 | 页头 + 确认弹窗 | **是** | 需 `PUT /api/teaching/exam/{id}/finish`，触发批量收卷（契约**无**） |
| 导出监考记录 | 页头 | 否（导出流） | 需 `GET /api/teaching/exam/{id}/monitor-log/export`（契约**无**） |

### 枚举/状态机

- 考生状态 `doing / submitted / offline / abnormal` —— 契约**无对应字段**；`red_flag_type`（0 正常 / 1 切屏超阈值 / 2 查重抄袭）只有 3 档且语义不同，**不一致**。
- 考试状态 `进行中 / 已结束` —— 契约**无 status 字段**（`teach_assignment` 只能靠 `start_time/end_time` 推断），不一致。
- 切屏阈值 `limit=5`，页面文案「3 次以上标记异常」 —— 契约 `tab_switch_limit` 默认 5，**页面与契约阈值不一致**（3 vs 5）。
- 事件严重度 `ok / warn / danger` —— 前端私有，契约无。
- 判题状态（作答记录）`ac/wa/none` —— 与 `judge_submission.status` **一致**。

### 实时性 / 分页 / 排序 / 筛选

- **实时性**：**本页是教师端唯一真正需要推送的页面**。原型用 `setInterval` 每 8 秒造一条事件、每秒跑倒计时，文案写「每 5 秒刷新」。契约**没有任何教师端实时通道**：唯一 SSE 是 `/api/ai/chat/stream`（学生端 AI 对话），`/api/teaching/exam/tab-switch` 只负责"写"，不负责"推"。→ 建议补 `SSE /api/teaching/exam/{id}/monitor/stream`（事件类型：`student_join`、`student_progress`、`tab_switch`、`offline`、`submit`、`exam_end`），否则只能退化为 3-5 秒轮询快照。
- **分页**：**明确不分页**（页面注释写了"分页等于让老师只盯 1/3 个考场"），48 人一次性铺开；但 200 人以上考场需要虚拟滚动或分组，接口应支持一次返回全量快照。
- **排序**：异常组按 `offline → abnormal → 切屏次数降序`，其余按名单顺序；排序规则在前端，服务端只需保证"异常优先"或提供 `sort` 参数。
- **筛选**：状态五档（互斥）+ 关键词；需要返回各档计数用于 chip 上的数字。

---

## 页面：t-plagiarism.html（双阶段查重）

### 数据需求表

| # | 页面模块 / 交互点 | 所需数据（字段级） | 类型与取值域 | 数据来源判定 | 现有契约覆盖情况 | 缺口说明 |
|---|---|---|---|---|---|---|
| 1 | 页头副标题 | 作业名、班级名、提交份数、比对对数、两阶段耗时 | string；string；int；int；int 秒 | A-现有接口直接覆盖 | 接口 3.2 返回 `totalValidSamples`、`totalPairsCompared`、`stage1CostMs`；作业名需 `teach_assignment.title` | 作业名/班级名不在 3.2 响应里，需额外查活动 |
| 2 | 疑似对列表：`reportId` | 报告主键 | bigint | A-现有接口直接覆盖 | 接口 3.2 `records[].reportId`；表 `teach_plagiarism_report.id` | 覆盖 |
| 3 | 疑似对：学生 A/B 姓名、学号 | `id`、`name`、`stuNo` | bigint；varchar(64)；varchar(64) | A-现有接口直接覆盖 | 接口 3.2 `records[].userA/userB`；`sys_user.real_name/username` | 覆盖 |
| 4 | 疑似对：题目 `pid`、`title` | `problem_id`、`title` | bigint；varchar(128) | B-现有表可派生但无接口 | `teach_plagiarism_report.problem_id`、`prob_question.title` | 3.2 响应只回 `problemId`（请求参数带入），不带标题 |
| 5 | 相似度「结构」`struct` | `stage1_algo_sim` 0-100 | decimal(5,2) | A-现有接口直接覆盖 | 接口 3.2 `stage1AlgoSim`；表 `stage1_algo_sim` | 覆盖 |
| 6 | 相似度「语义」`sem` | `stage2_ai_sim` 0-100（可 null） | decimal(5,2) / null | A-现有接口直接覆盖 | 接口 3.2 `stage2AiSim`；表 `stage2_ai_sim`（默认 NULL，阶段一被筛掉的样本为空） | 覆盖，但页面 `overall()` 未对 null 做容错，会产出 NaN |
| 7 | 综合相似度 `overall = (struct+sem)/2` | 派生值 | 0-100 | E-纯前端派生可算 | 无（契约未定义"综合"） | 契约只有两个阶段分，**没有综合相似度字段或计算规则**，前端自行取均值 |
| 8 | 判定结论 `high / suspect / normal` | 阈值判定结果 | enum ×3（结构≥75 且语义≥85 → high） | E-纯前端派生可算 | 无 | 阈值 75/85 只写在页面文案里，`teach_plagiarism_report` 无 `verdict` 字段，服务端不落判定结论 |
| 9 | 判定说明 `verdictText` | 两阶段分数与阈值比较文案 | string | E-纯前端派生可算 | `ai_diagnosis`（AI 鉴定评语，语义不同） | `ai_diagnosis` 是 AI 生成的抄袭特征描述，不是"哪一维超阈值"的规则结论 |
| 10 | 双侧代码 A：`codeA`（逐行 + 行号） | `code` text，按行切分 | text | A-现有接口直接覆盖 | 接口 3.3 `GET /api/teaching/plagiarism/diff/{reportId}` 返回 `codeA` | 覆盖代码正文，但**不带分段标注** |
| 11 | 双侧代码 B：`codeB` | 同上 | text | A-现有接口直接覆盖 | 接口 3.3 `codeB` | 同上 |
| 12 | 提交时间 A/B（`at`） | `create_time` | datetime | A-现有接口直接覆盖 | 接口 3.3 明确返回"双方提交时间"；`judge_submission.create_time` | 覆盖 |
| 13 | 提交语言 `lang` | `language` | varchar(16)，取值 cpp/java/python | A-现有接口直接覆盖 | `teach_plagiarism_report.language` | 覆盖；页面 `PAIRS[].lang` 写的是 `'C++17'/'Java'/'Python'`，与契约枚举 `cpp/java/python` **格式不一致**，需前端映射 |
| 14 | 双方 AI 提示次数 | `ai_used_count` | int | A-现有接口直接覆盖 | 接口 3.3 明确返回"各自 AI 提示使用次数"；`teach_student_score.ai_used_count` | 覆盖（页面当前未展示，但接口有） |
| 15 | **分段对齐 `segs{1..4}`（片段名/行号范围）** | 相似片段编号、片段语义名、A/B 各自起止行 | `seg_id` int；`name` varchar；`a_from/a_to` int；`b_from/b_to` int | D-无任何表落点 | `teach_plagiarism_report` 全表只有 `stage1_algo_sim`/`stage2_ai_sim`/`ai_diagnosis` 三个标量，**无任何分段/对齐结构字段** | **最严重缺口之一**：契约只有两个相似度标量，没有任何"结构相似度明细/分段对齐"字段；页面 4 段（头文件/函数签名/循环体/返回语句）与逐行 seg 编号全部写死在前端 |
| 16 | 每行 `s`（片段归属编号，0=不属于任何片段） | 行级分段归属 | int 0-N | D-无任何表落点 | 无 | 无行级标注字段；高亮所需的 `cl.sg/.act` 完全由前端 `CODE` 常量伪造 |
| 17 | 最长连续相似行数 `maxRun` | 连续相似行游程 | int | D-无任何表落点 | 无 | 无字段，前端用 seg 连算 |
| 18 | 片段明细行（A/B 行号范围 + 行数） | `aRange`、`bRange`、`count` | string；string；int | D-无任何表落点 | 无 | 同 #15 |
| 19 | 裁定状态 `review` | `audit_status` 0/1/2 | tinyint | A-现有接口直接覆盖 | 接口 3.3 `PUT /api/teaching/plagiarism/audit`；表 `audit_status` | 读取侧 3.2 也返回 `auditStatus`，覆盖 |
| 20 | 筛选 chip 计数（全部/高度疑似/疑似/正常/待复核） | 四类计数 | int ×5 | E-纯前端派生可算 | 无 | 依赖 #8 的判定结论（服务端未落），只能前端算 |
| 21 | 页头「提交 46 份 · 比对 1035 对」 | `totalValidSamples`、`totalPairsCompared` | int ×2 | A-现有接口直接覆盖 | 接口 3.2 同名字段 | 覆盖 |
| 22 | 「导出报告 PDF（含 4 组疑似对比）」 | 导出动作 | — | D-无任何表落点 | 无 | 无导出接口 |
| 23 | 「重新查重」 | 触发 stage1+stage2 | `stage1SimThreshold`、`stage2ChannelCode` | A-现有接口直接覆盖 | 接口 3.2 `POST /api/teaching/plagiarism/check` | 覆盖触发，但"预计 40 秒"的异步进度无接口 |

### 交互与写操作清单

| 操作 | 触发位置 | 是否写库 | 需要的接口语义 |
|---|---|---|---|
| 选择疑似对 | 右栏疑似对列表 | 否 | 数据已在前端，`reportId` 切换即可；首屏需 `GET .../plagiarism/reports?assignmentId=&problemId=` |
| 片段导航 #1..#4 / 上一处 / 下一处 | 片段条 | 否 | 纯前端滚动，但 seg 数据需接口提供（见 #15） |
| 「只看相似片段」开关 | 片段条 | 否 | 纯前端折叠 |
| 双栏滚动同步 | 代码窗 | 否 | 纯前端 |
| 确认抄袭 | 处理动作条 | **是** | `PUT /api/teaching/plagiarism/audit` `{reportId, auditStatus:1}`（**契约已有**，同时清零 `max_score`、`red_flag_type=2`） |
| 标记独立完成 | 处理动作条 | **是** | 同上 `auditStatus:2` |
| 撤销（5 秒） | toast / 动作条 | **是（回滚）** | 需支持 `auditStatus` 回退到 0，契约未定义"撤销裁定"语义 |
| 重新查重 | 页头 | **是** | `POST /api/teaching/plagiarism/check`（**契约已有**） |
| 导出报告 | 页头 | 否（导出流） | 需 `GET .../plagiarism/export`（契约**无**） |

### 枚举/状态机

- 判定等级 `high（高度疑似）/ suspect（疑似）/ normal（正常）` —— 契约**无字段**，由阈值前端推导，不一致（缺）。
- 裁定状态 `audit_status` `0 待裁定 / 1 确认抄袭 / 2 排除嫌疑` —— 与契约 **完全一致**；页面 `review` 用 `confirmed / independent / undefined` 表达，**命名不一致但语义可映射**。
- 阶段标识 `stage1（AST 粗筛 75%）/ stage2（语义 85%）` —— 与契约两阶段设计**一致**；阈值 75/85 与 `_SPEC.md` 及 3.2 请求参数 `stage1SimThreshold: 75.0` **一致**。
- 语言 `C++17 / Java / Python` —— 契约 `language` 为 `cpp/java/python`，**格式不一致**（页面带版本号）。

### 实时性 / 分页 / 排序 / 筛选

- **实时性**：查重是异步长任务（页面写"两阶段耗时 42 秒"、"重新查重预计 40 秒"），但契约只有同步 POST，**没有任务状态查询/进度推送**；建议补 `GET .../plagiarism/task/{taskId}` 或 SSE。
- **分页**：疑似对 4-5 条，前端不分页；但一对真实作业可能有上百条报告，`GET .../reports` 必须分页。
- **排序**：疑似对默认按综合相似度降序（页面顺序即如此），无排序控件；服务端应按 `stage2_ai_sim DESC` 排。
- **筛选**：五档 chip（全部/高度疑似/疑似/正常/待复核）需服务端过滤或返回全量后前端过滤；chip 上的计数需与列表同源。

---

## 教师端汇总

- **页面数**：7 个（t-dashboard / t-problems / t-problem-edit / t-assignments / t-analytics / t-monitor / t-plagiarism）
- **数据需求条目总数**：**176 条**（7 页逐条计数，脚本核验）
- **判定分布**：

| 判定 | 含义 | 条目数 | 占比 |
|---|---|---|---|
| A | 现有接口直接覆盖 | 13 | 7.4% |
| B | 现有表可派生但无接口 | 73 | 41.5% |
| C | 现有表缺字段 | 39 | 22.2% |
| D | 无任何表落点 | 30 | 17.0% |
| E | 纯前端派生可算 | 21 | 11.9% |
| — | **合计** | **176** | 100% |

**分页明细**：

| 页面 | A | B | C | D | E | 合计 |
|---|---|---|---|---|---|---|
| t-dashboard.html | 0 | 15 | 4 | 3 | 1 | 23 |
| t-problems.html | 0 | 13 | 11 | 2 | 2 | 28 |
| t-problem-edit.html | 0 | 7 | 11 | 0 | 6 | 24 |
| t-assignments.html | 0 | 17 | 4 | 1 | 1 | 23 |
| t-analytics.html | 0 | 13 | 4 | 8 | 6 | 31 |
| t-monitor.html | 0 | 7 | 5 | 11 | 1 | 24 |
| t-plagiarism.html | 13 | 1 | 0 | 5 | 4 | 23 |
| **合计** | **13** | **73** | **39** | **30** | **21** | **176** |

> 说明 1：B 占比最高（41.5%）说明"表基本够、接口几乎全缺"——契约 REST 段（L215-L441，8 个编号 / 9 条路径）里教师端只占 4 条（`POST /api/teaching/exam/tab-switch`、`POST /api/teaching/plagiarism/check`、`GET /api/teaching/plagiarism/diff/{reportId}`、`PUT /api/teaching/plagiarism/audit`），且全部集中在"切屏上报 + 查重"两个点；其余 **6 个页面 A = 0**（含 t-monitor：`tab-switch` 只有"学生写"，没有"教师读"）。
> 说明 2：A 类 13 条**全部落在 t-plagiarism.html**，因为只有查重同时具备"写接口 + 读接口 + 表字段"三件套。
> 说明 3：C+D 合计 **69 条（39.2%）**，说明即便补齐 REST 接口，仍有近四成需求无处落库；再叠加 30 条 D 类（t-monitor 11 条、t-analytics 8 条最集中），缺口并非"接口没写"而是"模型没设计"。

### 最严重的 5 个缺口（按影响排序）

1. **`teach_plagiarism_report` 没有分段结构（struct/seg/activeSeg/行级归属），双阶段查重的"逐行高亮 + 4 段对齐"整体无落点** —— 页面 96% 相似度的核心体验（左右分栏对齐、片段导航、最长连续相似行）全部由前端 `CODE`/`PAIRS` 常量伪造；契约只有两个标量相似度，缺 `struct`、缺 `seg`、缺行级标注，需新增 `teach_plagiarism_segment` 表（reportId/segId/segName/side/lineFrom/lineTo/lines）或 `segments` JSON 字段。
2. **`prob_question` 没有 `state` 字段，题库的草稿/发布/归档三态与全部写操作悬空** —— `DRAFT=[1092,1118]`、`ARCHIVED=[1001]` 写死在前端；连带统计卡、状态筛选、批量发布、批量归档、存草稿、保存并发布共 10+ 条需求无落点。
3. **测试点只有"总数 + 目录"，没有逐点实体（分值/类型/可见性/启停/顺序）** —— `prob_question` 仅有 `sample_cases`（公开样例内容）、`test_case_dir`、`test_case_count`，无法表达 `mkTp(kind,input,output,score,open)` 的 4 档 kind、逐点分值、隐藏/公开开关与 `case_order`；OI 按点计分因此无法按自定义权重算分，"合计 100 分"校验也无服务端依据。
4. **教师端零实时通道，考试监考的在人数/作答进度/断线/事件流全部无落点** —— 契约里唯一 SSE 是学生端 `/api/ai/chat/stream`；`teach_student_score` 只有 `tab_switch_count`/`red_flag_type`，没有心跳、入场时间、最后活跃、作答进度、行为事件；页面的 48 人状态、实时事件流、四档分布只能靠 `setInterval` 伪造。
5. **学生总分/知识点掌握度/风险等级三个核心聚合指标无表可依** —— `teach_student_score` 只有"单题最高分"，没有学生级总分（加权口径无处定义）、没有知识点维度（`tags` 是 JSON 无法索引 join）、没有风险等级与阈值配置；`buildStudents()` 的 score/rate/ai、`KP_BASE` 掌握度、`delta` 环比、风险卡全部只能前端生成，学情页的"AI 薄弱点解读"因此无法被数据验证。

### 六处「聚合指标无落点」怀疑的逐条核实结论

> 前置校正：任务里列举的卡片名与原型实际文案并不完全一致——`t-dashboard.html` **没有**「班级数」「今日提交」「待批改」「近期活动流」这四个卡（全文 `grep` 命中 0 次），实际对应的是「班级人数」「本周提交」「待发布作业」「进行中的教学活动」。下表按**源码真实模块**逐条给结论，不按任务里的措辞硬凑。

| # | 怀疑点 | 原型真实字段（源码位置） | 落点结论 | 判定 |
|---|---|---|---|---|
| 1a | 概览·班级数 | 页面**无**该卡；仅页头单班级文案 `数据结构 · 计算机 2022 级 1 班`（t-dashboard.html:156） | 若要"教师名下班级数"，`teach_class` 按 `teacher_id` 可 count，但无接口 | B |
| 1b | 概览·学生数 | 「班级人数 48 / 本周活跃 44」（t-dashboard.html:355-356） | `teach_class.student_count` 有值；「本周活跃」需 `judge_submission` 的 distinct `user_id`，活跃口径未定义 | B |
| 1c | 概览·今日提交 | 页面**无**"今日"口径，实际是 `weekSub=312`「本周提交」 | `judge_submission.create_time` 按周聚合可得，无接口 | B |
| 1d | 概览·待批改 | 页面**无**"待批改"（判题全自动、无人工批改环节）；最近似项是待办①「待发布作业 等 2 项」 | `teach_assignment` 无 status 列，"待发布"无法表达 | C |
| 1e | 概览·通过率 | 「本周通过率」`194/312 次提交加权`（t-dashboard.html:312-326） | `judge_submission.status`（3=AC、4=PAC）+ `create_time` 可算，无接口；PAC 是否计入"通过"契约未定义 | B |
| 1f | 概览·AI 节省时长 | 「批改省下 4.2 小时」（t-dashboard.html:394-395） | **9 张表无任何工时/批改量字段**，也无可配置的折算系数 | D |
| 1g | 概览·7 天趋势 | `const days=[{date,week,sub,ac}×7]`（t-dashboard.html:507-515） | `judge_submission` 按日 GROUP BY 可派生，无接口 | B |
| 1h | 概览·排行榜 | `const ranks=[{pid,title,lvl,tags,tried,ac,pass,avg,first,students[]}]`（:517-528） | `prob_question`（title/difficulty/tags）+ `judge_submission`（tried/pass/avg/first_failed_case_id）可派生；`students[]` 需按学生聚合 | B |
| 1i | 概览·近期活动流 | `const activities=[{id:'A-0412',type,pidCount,done,total,due,left}]`（:530-537） | `teach_assignment`（title/mode_type/problem_ids/end_time）+ `teach_student_score` 聚合；`id` 的 `A-xxxx` 编号格式无落点、`status` 缺 | B（编号/状态部分 C） |

**结论**：概览页 **A = 0**，9 张表里没有任何一张"聚合/统计/快照"表，所有指标都要现算；能靠 `judge_submission` 现场聚合的是 B，`AI 节省时长`与`诊断覆盖`两处是 D，`待发布作业`卡在 `teach_assignment` 缺 status（C）。所谓"聚合指标无落点"的怀疑**成立**，但准确说法是「**有明细表、无聚合层，且缺一条聚合接口**」，而非完全无表。

| # | 怀疑点 | 原型真实字段（源码位置） | 落点结论 | 判定 |
|---|---|---|---|---|
| 2a | 学情·`score`（55-96） | `buildStudents(range)` 内 `score`（t-analytics.html:720-730） | **9 张表无"学生级总分"落点**：`teach_student_score` 是 `(assignment_id,user_id,problem_id)` 明细粒度，聚合口径与权重无处定义 | D |
| 2b | 学情·`rate`（39-85） | `buildStudents` 的 `rate` | `judge_submission.status` 按 `user_id` 聚合可算；分母（提交数 or 题数）契约未定义 | B |
| 2c | 学情·`ai`（0-13） | `buildStudents` 的 `ai` | `teach_student_score.ai_used_count` 与 `ai_chat_record` 两处口径冲突，需统一 | B |
| 2d | 学情·`KP_BASE` 掌握度 | `var KP_BASE = { '动态规划':82, '贪心':64, '图论':41, '字符串':73, '数学':55 }`（t-analytics.html:704） | `prob_question.tags` 是 JSON 数组，**无法建索引 join** 到 `teach_student_score`；`delta` 环比还需历史快照表（无） | C |
| 2e | 学情·能力雷达 | 全页**没有雷达图**：`grep radar/polygon/能力` 只命中 `t-problems.html:304` 的一句 CSS 注释；本页实有图表是知识点条形、测试点热力图、成绩分布堆叠条 | 不构成本期数据需求；若后续新增，契约也没有任何能力维度模型 | D（假设项） |

**结论**：`buildStudents()` 三个指标里 `rate`/`ai` 可算（B），**`score` 是 D**；`KP_BASE` 是 C（JSON 标签无法索引 + 环比无快照）。**`teach_student_score` 只有单题最高分，不够**——它缺三样：① "学生×班级"宽表（做总分/均分/中位数/标准差）② 知识点维度（tag 的受控词表 + 关联表）③ 历史快照（做 `delta`/上周期对比）。另外「能力雷达」是任务假设中才有的模块，**原型里并不存在**，不应计入需求条目。

| # | 怀疑点 | 原型真实字段（源码位置） | 落点结论 | 判定 |
|---|---|---|---|---|
| 3a | 监考·在线人数 | `doingCount` + 四档 `{doing/submitted/offline/abnormal}` 计数（t-monitor.html:677） | 无心跳表、无 `last_active_time`；`red_flag_type` 只有 0/1/2 且语义是"切屏超阈值/查重抄袭"，与"在线/断线"无关 | D |
| 3b | 监考·每人已完成题数 | `buildRoster()` 的 `done = Math.min(5, Math.round(r1*5))`、`problems[].status`（:540-562） | `teach_student_score.submit_count` 是"同一题重复提交次数"，**不是"已答题数"**，语义完全不同 | C |
| 3c | 监考·切屏次数 | `switch`（0-4）、`switchWarn`、`tab_switch_count` | 表字段有、**写入有接口 3.1，教师读接口没有**；且按 `(assignment,user,problem)` 分题记，页面要按人汇总 | B |
| 3d | 监考·实时事件流 | `buildEvents()` 产 `{id,sid,who,what,note,at,tone,icon}`，`setInterval(...,8000)`（:585、:773） | 无行为事件表，也无任何推送通道 | D |
| 3e | 监考·沙箱 CPU/内存 | **本页不存在该模块**：`t-monitor.html` 全文无 CPU/内存/沙箱字样；仅 `a-audit.html:230` 有「沙箱容器」监控 | 契约亦无沙箱资源表 | D（且非本页需求） |
| 3f | 契约实时推送通道 | — | **只有 `POST /api/ai/chat/stream`（学生端 AI 对话 SSE，5 种事件）**；`/api/teaching/exam/tab-switch` 只写不推；6 类 Redis Key 全是学生端配额/记忆，无考场广播键 | D |

**结论**：监考页 5 项里 2 项 D（在线人数、事件流）、1 项 C（已完成题数）、1 项 B（切屏次数）、1 项根本不属于本页（沙箱 CPU/内存）。**契约没有任何教师端实时推送通道**，整页只能退化为 3–5 秒轮询快照，且轮询所需的心跳/进度/事件三张表全部缺失。

| # | 怀疑点 | 原型真实字段（源码位置） | 落点结论 | 判定 |
|---|---|---|---|---|
| 4a | 查重·`struct` | `PAIRS[].struct`（96/84/76/68，t-plagiarism.html:473-491） | 有落点：`teach_plagiarism_report.stage1_algo_sim`，接口 3.2 回 `stage1AlgoSim` | A |
| 4b | 查重·`sem` | `PAIRS[].sem`（96.8/79/71/59） | 有落点：`stage2_ai_sim`（DECIMAL(5,2)，可 NULL），接口 3.2 回 `stage2AiSim` | A |
| 4c | 查重·`segs{1..4}` 分段对齐 | `segs:{ 1:'头文件与命名空间', 2:'函数签名与初始化', 3:'循环体（状态转移）', 4:'返回语句' }`（:476） | `teach_plagiarism_report` 全表只有两个相似度标量 + `ai_diagnosis`，**无任何分段/对齐字段** | D |
| 4d | 查重·行级 `s`（片段归属）+ `activeSeg` 高亮 | `lines()` → `{n,t,s}`（`s`=0 表示不属于任何片段）；CSS `.cl.sg` / `.cl.sg.act`；`activeSeg` 片段导航（:62-63、:234） | 无行级标注字段，接口 3.3 只回 `codeA`/`codeB` 纯文本，无行级元数据 | D |
| 4e | 查重·`maxRun` 最长连续相似行 | `get maxRun()` 由 `alignedRows()` 的 `seg` 标记算游程（:658-664） | 无字段、无接口 | D |

**结论**：**`struct` / `sem` 两个标量有落点（A），但页面 90% 的查重体验无落点（D）**——`struct` 只是"结构相似度数值"，并**不包含** `segs` 分段、`s` 行级归属、`activeSeg` 导航、`maxRun` 游程。契约缺的是一整张 `teach_plagiarism_segment`（reportId / segId / segName / side / lineFrom / lineTo / lineCount），不是一个字段。

| # | 怀疑点 | 原型真实字段（源码位置） | 落点结论 | 判定 |
|---|---|---|---|---|
| 5 | 测试点 `mkTp(kind,input,output,score,open)` | `function mkTp(kind, input, output, score, open)`（t-problem-edit.html:704），调用如 `mkTp('sample','5\n1 -2 3 4 -1','7',10,false)`、`mkTp('edge','1\n0','0',5,false)` | `prob_question` 只有 `sample_cases`（JSON，仅 input/output）、`test_case_dir`（目录字符串）、`test_case_count`（数量）；**无 kind/score/visible/case_order 任一字段** | C |
| 6 | 题库·`DRAFT=[1092,1118]` / `ARCHIVED=[1001]` | `const DRAFT = [1092, 1118]; const ARCHIVED = [1001];`（t-problems.html:455-456），`state: archived ? 'archived' : (draft ? 'draft' : 'published')`（:481） | `prob_question` **无 `state` 字段**（且无 `update_time`） | C |

**结论（5）**：**不够**。`sample_cases` 只能表达"公开样例的 I/O 内容"，表达不了 `mkTp` 五参数里的任何一个维度：① 逐点 `score`（页面强制"合计=100"并据此算分）② `kind` 四档（sample / normal / edge / stress）③ `open` 可见性开关 ④ 顺序（判分链第 N 格本身是语义）。隐藏测试点连 I/O 都只在文件系统里，编辑态无法回显，服务端也无法校验分值和。

**结论（6）**：**`prob_question` 没有 state 字段**，草稿/发布/归档三态完全写死在前端常量里；连带页头计数、三张统计卡、状态筛选、批量发布、批量归档、存草稿、保存并发布共 10+ 条需求一起悬空，且归档的"软删除/保留历史提交"语义无定义。

