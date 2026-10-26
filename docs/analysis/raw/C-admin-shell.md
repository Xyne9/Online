# C · 管理端 2 页 + 共享层（shell.js / _SPEC.md）契约审计

> 审计范围：`prototype/app/a-models.html`（823 行）、`prototype/app/a-audit.html`（1021 行）、`prototype/app/shell.js`（570 行）、`prototype/app/_SPEC.md`（339 行）。
> 全部结论取自源码，行号即 `文件名:行号`。字段名一律原样抄写，不改大小写、不改分隔符。
> 判定口径：**真实实现** = 有实际副作用/真实浏览器 API；**本地模拟** = `setTimeout` + toast，不落地数据、不发请求。

---

## 一、shell.js 导出的契约面

### 1.1 window.OJ 的全部成员

`window.OJ = OJ;` 在 `shell.js:516`。对象字面量定义在 `shell.js:457-514`。表内「行号」为该成员的定义区间。

| 成员 | 类型 | 结构或签名 | 行号 |
| --- | --- | --- | --- |
| `OJ.svg` | 函数 | `svg(name, cls) → string`；返回 `'<svg class="ic ' + (cls \|\| '') + '" viewBox="0 0 24 24" aria-hidden="true">' + path + '</svg>'`；`name` 未命中时回退 `ICONS.info` | shell.js:84-87（引用赋值 shell.js:458） |
| `OJ.icons` | 常量对象 | `ICONS`，**68 个键**，值为 SVG 子元素字符串（无外层 `<svg>`）。键序：`logo book task clock chart gauge edit users scan cpu server plug coin monitor home search filter plus check checkC x xC alert info play send chevD chevR chevL arrowR arrowL arrowU arrowD more refresh download upload copy external settings logout code spark flag tag file layers diff bolt lock unlock bell eye trash sort grid calendar award target trend stop pause expand user key folder kbd shield` | shell.js:13-82（引用 shell.js:458） |
| `OJ.roles` | 常量对象 | `ROLES`，3 键 `student`/`teacher`/`admin`，每值 `{name, user, avatar, sub, home}`；`home` 即该角色首页文件名 | shell.js:90-94（引用 shell.js:458） |
| `OJ.nav` | 常量对象 | `NAV`，3 键 `student`/`teacher`/`admin`，每值为 `[{title, items:[{id,label,icon,href,badge?}]}]` 数组 | shell.js:96-129（引用 shell.js:458） |
| `OJ.problems` | 数组 | `PROBLEMS`，**12 条**；字段 `{id,title,lvl,tags,ac,tried,status,score}` | shell.js:320-345（引用 shell.js:459） |
| `OJ.submissions` | 数组 | `SUBMISSIONS`，**10 条**；字段 `{id,pid,title,lang,status,score,pass,time,mem,at,first}` | shell.js:347-368（引用 shell.js:459） |
| `OJ.assignments` | 数组 | `ASSIGNMENTS`，**5 条**；字段 `{id,title,type,typeName,pidCount,done,score,full,due,left,ai,status}` | shell.js:370-386（引用 shell.js:459） |
| `OJ.students` | 数组 | `STUDENTS`，**10 条**；字段 `{name,sid,done,total,score,ai,last,risk}` | shell.js:388-399（引用 shell.js:459） |
| `OJ.models` | 数组 | `MODELS`，**4 条**；字段 `{id,name,vendor,role,base,state,ttft,calls,tokens,cost}` | shell.js:401-410（引用 shell.js:460） |
| `OJ.aiCalls` | 数组 | `AI_CALLS`，**10 条**；字段 `{at,who,stage,model,route,ttft,tok,guard,note}` | shell.js:412-433（引用 shell.js:460） |
| `OJ.sandbox` | 数组 | `SANDBOX`，**5 条**；字段 `{id,lang,user,state,cpu,mem,age}` | shell.js:435-441（引用 shell.js:461） |
| `OJ.plagiarism` | 数组 | `PLAGIARISM`，**5 条**；字段 `{id,a,b,pid,title,sim,stage,state,tokens,level,verdict}` | shell.js:443-454（引用 shell.js:461） |
| `OJ.statusName` | 函数 | `statusName(s) → string`；映射表 11 键：`ac→AC wa→WA tle→TLE mle→MLE re→RE ce→CE pac→PAC pending→判题中 none→未提交 run→运行中 idle→空闲`；未命中回退原值 `s` | shell.js:463-466 |
| `OJ.statusTag` | 函数 | `statusTag(s, extra) → string`；`'<span class="tag ' + s + (extra ? ' ' + extra : '') + '">' + OJ.statusName(s) + '</span>'`；**类名直接用 status 原值**，故 `extra` 才是 `.blue/.violet/.outline/.mono/.dot` 等修饰位 | shell.js:467-469 |
| `OJ.lvlName` | 函数 | `lvlName(n) → string`；查表 `['', '入门', '简单', '中等', '较难', '困难'][n]`，未命中回退 `'简单'`；**n=0 返回空串** | shell.js:470 |
| `OJ.lvl` | 函数 | `lvl(n) → string`；5 格实心刻度，`'<span class="lvl" data-l="' + n + '" title="难度 ' + n + ' / 5"><b>' + OJ.lvlName(n) + '</b><span class="lvl-bars" aria-hidden="true">' + 5×'<i>' + '</span></span>'`；`i<=n` 的 `<i>` 带 `class="on"` | shell.js:473-478 |
| `OJ.toast` | 函数 | `toast(msg, opt) → close()`；`opt = {undo?: fn, type?: 'ok'\|'err'\|'warn'}`；图标 `err→xC / warn→alert / 其它→checkC`；停留 **带 undo 5000ms / 无 undo 2600ms**，退场 160ms；带 `data-oj` 属性、`role="status"`；有 undo 时追加 `<button class="toast-undo">撤销</button>`；**单例**（先 `document.querySelector('.toast[data-oj]').remove()`） | shell.js:481-507 |
| `OJ.reduced` | 函数 | `reduced() → boolean`；`matchMedia('(prefers-reduced-motion: reduce)').matches` | shell.js:508 |
| `OJ.fmt` | 函数 | `fmt(n) → string`；`n >= 10000 ? (n/1000).toFixed(1) + 'K' : String(n)`；即 **1841 → `'1841'`，18410 → `'18.4K'`** | shell.js:509 |
| `OJ.qs` | 函数 | `qs(k, d) → string`；正则 `[?&]k=([^&#]*)` 扫 `location.search`，命中 `decodeURIComponent`，未命中返回 `d`（`d === undefined` 时返回 `''`） | shell.js:510-513 |

**OJ 之外的共享契约（同一文件导出，页面同样依赖）：**

| 导出面 | 说明 | 行号 |
| --- | --- | --- |
| Alpine 指令 `x-ic` | `x-ic="'search'"` → `el.innerHTML = svg(name, cls)`，`cls = binding.modifiers.join(' ')`；空值清空 `innerHTML` | shell.js:523-532 |
| Alpine 指令 `x-count` | `x-count="62"` 数字滚动，480ms cubic ease-out，小数位跟随目标值，`OJ.reduced()` 时直接落终值 | shell.js:537-558 |
| 全局快捷键 | `/` 聚焦 `.tb-search input`、`?` 打开快捷键面板、`Esc` 关面板 / 点最后一个可见 `.scrim` | shell.js:236-262 |
| 外壳注入 | 读 `body[data-role]`、`body[data-page]`、`body[data-crumb]`，把 `#page-content` 搬进 `.main` | shell.js:266-305 |
| 面板快捷键面板 | `KBD_GLOBAL` 3 条 + `KBD_WORKBENCH` 2 条（仅 `page === 's-workbench'` 前置） | shell.js:193-210 |

> **审计注记 1（文档与实现不符，2 处）**：`_SPEC.md:104` 写「`OJ.lvl(n)` → 难度点 HTML（含颜色）」，实际实现 `shell.js:471-472` 明确注释「统一中性色，颜色只留给判题结果」；`_SPEC.md:106` 写「`OJ.fmt(n)` 1841 → `'1.8K'`」，实际 `shell.js:509` 阈值为 10000，1841 返回 `'1841'`。两处契约文档滞后于实现。
> **审计注记 2**：`_SPEC.md:91-108` 的 `window.OJ` 清单**漏记 3 项**——`OJ.toast`（`shell.js:481`，t-dashboard 已在用）、`OJ.reduced`（`shell.js:508`）、`OJ.statusTag` 的第二参数 `extra`。另 `_SPEC.md:124` 图标清单 **67 个**，`shell.js:13-82` 实为 **68 个**，差集为 `kbd`（顶栏快捷键按钮用，`shell.js:184`）。
> **审计注记 3**：`OJ.nav` / `OJ.roles` / `OJ.icons` / `OJ.svg` **零外部消费者**——全仓 16 个页面没有任何一处引用（已 grep 全量 `OJ.nav|OJ.roles|OJ.icons|OJ.svg`，0 命中），只在 `shell.js` 内部 `buildSide/buildTop/svg` 使用。它们是「导出但非跨页契约」的死面。

---

### 1.2 各 mock 数组的字段字典

#### PROBLEMS（`shell.js:320-345`，12 条）

| 字段 | 类型 | 取值样例 | 取值范围 / 约束 |
| --- | --- | --- | --- |
| `id` | number | `1024` | 4 位整数；全集 `1001 1017 1024 1032 1045 1058 1063 1071 1088 1092 1105 1118`；**非连续、非有序**（首条是 1024） |
| `title` | string | `'最大子段和'` | 中文题名，最长 12 字（`'最短路径（Dijkstra）'`）；含全角括号 |
| `lvl` | number | `3` | `1..4`（数据）；`OJ.lvlName` 支持 `1..5`，故 5 为未使用档 |
| `tags` | string[] | `['动态规划', '前缀和']` | 1–3 项；闭集实测：`动态规划 前缀和 哈希表 数组 树 广度优先 图 最短路 堆 排序 贪心 并查集 单调队列 滑动窗口 字符串 KMP 拓扑排序 二分` |
| `ac` | number | `62` | 整数百分数 `27..88`，无单位、无 `%`（`%` 由模板拼，`s-problems.html:289`） |
| `tried` | number | `1841` | `412..3210` 人次 |
| `status` | string 枚举 | `'wa'` | 实测 `ac wa tle re none`；`OJ.statusName` 另备 `mle ce pac pending`，本数组未用 |
| `score` | number | `80` | `0..100`，**恒为 10 的倍数**；`status==='none'` 时恒 `0` |

#### SUBMISSIONS（`shell.js:347-368`，10 条）

| 字段 | 类型 | 取值样例 | 取值范围 / 约束 |
| --- | --- | --- | --- |
| `id` | string | `'S-20481'` | 格式 `S-\d{5}`；降序排列，跨度 `S-20318..S-20481` |
| `pid` | number | `1024` | 外键 → `PROBLEMS.id`；实测引用 `1024 1001 1017 1058 1105 1045` |
| `title` | string | `'最大子段和'` | **冗余字段**，与 `PROBLEMS.title` 同值（反范式，需后端保证一致） |
| `lang` | string 枚举 | `'C++ 17'` | `'C++ 17' \| 'Python 3' \| 'Java 17'`（**含空格**，见注记 5） |
| `status` | string 枚举 | `'wa'` | `ac wa tle re ce`（本数组不出现 `none/mle`） |
| `score` | number | `80` | `0..100`，10 的倍数 |
| `pass` | string | `'8/10'` | 格式 `'{通过}/{总数}'`，分母恒 `10`（与 `_SPEC.md:333` 隐藏测试点 10 组一致）；**是字符串不是两个数** |
| `time` | string | `'12ms'` | 带单位字符串；无耗时（RE/CE）时恒 `'—'`（全角破折号） |
| `mem` | string | `'4096KB'` | 带单位字符串，单位 `KB`；无值恒 `'—'` |
| `at` | string | `'10-24 23:41'` | 格式 `MM-DD HH:mm`，**无年份**；全集落在 `10-23`/`10-24` 两天 |
| `first` | number \| null | `3` | 首次失败测试点序号 `1..8`；`null` = 一次通过（ac 行实测全为 `null`） |

#### ASSIGNMENTS（`shell.js:370-386`，5 条）

| 字段 | 类型 | 取值样例 | 取值范围 / 约束 |
| --- | --- | --- | --- |
| `id` | string | `'A-0412'` | 格式 `A-\d{4}` |
| `title` | string | `'第五章 动态规划 · 课后作业'` | 含 `·` 与空格分节 |
| `type` | string 枚举 | `'homework'` | `homework \| practice \| exam` |
| `typeName` | string | `'作业'` | 与 `type` 一一对应的中文名：`作业 / 练习 / 考试`；**冗余字段** |
| `pidCount` | number | `6` | `4..6` 题 |
| `done` | number | `3` | `0..pidCount` |
| `score` | number | `82` | `0..100`；`done===0` 时为 `0` |
| `full` | number | `100` | 恒 `100`（满分） |
| `due` | string | `'10-26 23:59'` | 格式 `MM-DD HH:mm`，**无年份** |
| `left` | string | `'2 天'` | `'{n} 天'` 或 `'已结束'`；**派生字段**（由 `due` 与「今天」算），后端不该存 |
| `ai` | string | `'仅阶段 ①③ · 上限 3 次'` | 三值：`'仅阶段 ①③ · 上限 3 次'`（homework）/`'全阶段开放 · 不限次'`（practice）/`'全部关闭'`（exam）；**是展示文案不是结构化策略**，与 `_SPEC.md:337` 口径对应但不可解析 |
| `status` | string 枚举 | `'doing'` | `doing \| todo \| done` |

#### STUDENTS（`shell.js:388-399`，10 条）

| 字段 | 类型 | 取值样例 | 取值范围 / 约束 |
| --- | --- | --- | --- |
| `name` | string | `'林昭'` | 中文姓名 2–3 字；`[0]` 必须是 `林昭`（`_SPEC.md:330` 口径） |
| `sid` | string | `'20220101'` | **8 位数字字符串**，`20220101..20220110` 连续；不可当 number（丢前导零风险） |
| `done` | number | `8` | `3..10` |
| `total` | number | `10` | 恒 `10` |
| `score` | number | `82` | `31..98`，整数 |
| `ai` | number | `6` | `0..11`，AI 调用次数 |
| `last` | string | `'10-24 23:41'` | 格式 `MM-DD HH:mm`，**无年份** |
| `risk` | string 枚举 | `'ok'` | `ok \| ai \| low`；语义：`ok` 正常 / `ai` AI 依赖偏高 / `low` 进度偏低 |

#### MODELS（`shell.js:401-410`，4 条）

| 字段 | 类型 | 取值样例 | 取值范围 / 约束 |
| --- | --- | --- | --- |
| `id` | string slug | `'local-qwen'` | 只含小写字母与 `-`；全集 `local-qwen cloud-ds cloud-qwen cloud-zhipu`；**这是全站通道主键** |
| `name` | string | `'Qwen2.5-Coder 7B'` | 含空格型号名 |
| `vendor` | string | `'Ollama · 本地'` | 4 值：`'Ollama · 本地' 'DeepSeek · 云端' '阿里云 · 云端' '智谱 · 云端'`；**` · ` 分隔的「厂商 · 部署形态」复合串**，a-models 直接当筛选/展示用 |
| `role` | string | `'Router / Guardrail'` | **单数字符串，` / ` 分隔多角色**；实测三值 `'Router / Guardrail' 'Tutor 主通道' 'Tutor 备用通道'`；角色闭集 `Router / Tutor / Guardrail` |
| `base` | string URL | `'http://127.0.0.1:11434'` | 带协议；本地是 `http://127.0.0.1:xxxxx`，云端是 `https://` 域名 |
| `state` | string 枚举 | `'on'` | **仅 `on \| off`**，本数组**不出现 `degrade`**（`degrade` 由 a-models 页自己注入，`a-models.html:622`） |
| `ttft` | string | `'0.42s'` | 带 `s` 单位字符串，2 位小数；`0.42 / 1.12 / 1.48 / 1.35` |
| `calls` | number | `12840` | `96..12840` 累计调用数 |
| `tokens` | string | `'3.1M'` | 带 `M` 单位字符串 1 位小数；`'¥0'` 场景例外见 `cost` |
| `cost` | string | `'¥0'` | 带 `¥` 前缀；整数元写作 `'¥0'`（**无小数**），非零保留 2 位 `'¥214.80'`；**本地通道恒 `'¥0'`** |

#### AI_CALLS（`shell.js:412-433`，10 条）

| 字段 | 类型 | 取值样例 | 取值范围 / 约束 |
| --- | --- | --- | --- |
| `at` | string | `'23:41:08'` | 格式 `HH:mm:ss`，**只有时刻没有日期**（见注记 6）；降序排列，跨度 `23:41:08 → 23:19:27` |
| `who` | string | `'林昭'` | 学生姓名，外键 → `STUDENTS.name` |
| `stage` | string 枚举 | `'③ 排错诊断'` | **4 值定长**：`'① 读题拆解' '② 编码引导' '③ 排错诊断' '④ AC 复盘'`（含圈码序号 + 空格 + 中文名；`_SPEC.md:334`） |
| `model` | string | `'DeepSeek-V3'` | 2 值：`'DeepSeek-V3'`（云端）/`'Qwen2.5-Coder'`（本地）；**注意与 `MODELS.name` 不同**（MODELS 是 `'Qwen2.5-Coder 7B'` 带参数量），a-models 靠 `MATCH` 映射表桥接（`a-models.html:626-627`、`:715` 用 `includes`） |
| `route` | string 枚举 | `'cloud'` | `cloud \| local` |
| `ttft` | number | `1180` | **毫秒整数** `388..1310`；与 `MODELS.ttft` 的字符串秒不同源，需 `/1000` 再 `toFixed(2)` |
| `tok` | number | `1240` | 整数 `520..3120` |
| `guard` | string 枚举 | `'pass'` | `pass \| rewrite \| block`（三档，`a-audit.html:117-121` 对应三色） |
| `note` | string | `'取回 #3 测试点特征'` | 自由文本；无备注恒 `'—'`；**是本表唯一的自然语言泄漏点**（含 `'云端超时 8.1s，自动降级本地'`、`'泄漏率拦截：整段题解源码'`） |

#### SANDBOX（`shell.js:435-441`，5 条）

| 字段 | 类型 | 取值样例 | 取值范围 / 约束 |
| --- | --- | --- | --- |
| `id` | string | `'sbx-8841'` | 格式 `sbx-\d{4}`；降序 `sbx-8837..sbx-8841` |
| `lang` | string 枚举 | `'C++ 17'` | 同 `SUBMISSIONS.lang`：`C++ 17 / Python 3 / Java 17` |
| `user` | string | `'林昭'` | 学生姓名 |
| `state` | string 枚举 | `'run'` | **仅 `run \| idle`**；与 a-audit 的 `running/queued/exited` **不是同一套枚举**（见注记 4） |
| `cpu` | number | `12` | 整数百分数 `0..96`；`state==='idle'` 时恒 `0` |
| `mem` | number | `4096` | **KB 整数**，`0 / 4096 / 38200 / 51200`；展示需 `/1024` 转 MB |
| `age` | string | `'0.4s'` | 带 `s` 单位字符串 1 位小数 |

#### PLAGIARISM（`shell.js:443-454`，5 条）

| 字段 | 类型 | 取值样例 | 取值范围 / 约束 |
| --- | --- | --- | --- |
| `id` | string | `'P-0088'` | 格式 `P-\d{4}` |
| `a` / `b` | string | `'林昭'` / `'许清和'` | 两名学生姓名；`a` 为疑似抄袭方、`b` 为被抄袭方（顺序语义未显式声明） |
| `pid` | number | `1024` | 外键 → `PROBLEMS.id` |
| `title` | string | `'最大子段和'` | 冗余题名 |
| `sim` | number | `96.4` | **1 位小数**百分数 `33.8..96.4`；**是综合相似度，不是单阶段值** |
| `stage` | number | `2` | `1 \| 2`，表示该对比推进到第几阶段（1=AST 结构，2=语义向量） |
| `state` | string 枚举 | `'flagged'` | `flagged \| review \| pass`；语义 `flagged` 命中待处理 / `review` 人工复核 / `pass` 通过 |
| `tokens` | number | `214` | 命中相似 token 数 `5..214` |
| `level` | string 枚举 | `'high'` | **`high \| mid \| low`**（与 t-plagiarism 的 `high/suspect/normal` 不同，见注记 7） |
| `verdict` | string 枚举 | `'pending'` | `pending \| clean` |

---

### 1.3 导航结构 OJ.nav 与角色

**角色表 `ROLES`（`shell.js:90-94`）** —— `home` 即该角色落地页：

| key | name | user | avatar | sub | home |
| --- | --- | --- | --- | --- | --- |
| `student` | 学生端 | 林昭 | 林 | 计算机 2022 级 · 1 班 | `s-problems.html` |
| `teacher` | 教师端 | 周明远 | 周 | 数据结构 · 主讲教师 | `t-dashboard.html` |
| `admin` | 管理端 | 运维管理员 | 运 | 系统与模型运维 | **`a-models.html`** |

**学生端（`shell.js:97-107`）**

| 分组 | id | 名称 | icon | href | badge |
| --- | --- | --- | --- | --- | --- |
| 学习 | `s-problems` | 题库 | `book` | `s-problems.html` | — |
| 学习 | `s-assignments` | 我的作业 | `task` | `s-assignments.html` | `3` |
| 学习 | `s-submissions` | 提交记录 | `clock` | `s-submissions.html` | — |
| 学习 | `s-analytics` | 学情分析 | `chart` | `s-analytics.html` | — |
| 正在做 | `s-workbench` | 做题工作台 | `code` | `s-workbench.html` | — |

**教师端（`shell.js:108-119`）**

| 分组 | id | 名称 | icon | href | badge |
| --- | --- | --- | --- | --- | --- |
| 教学 | `t-dashboard` | 教师概览 | `gauge` | `t-dashboard.html` | — |
| 教学 | `t-problems` | 题目管理 | `book` | `t-problems.html` | — |
| 教学 | `t-assignments` | 教学活动 | `task` | `t-assignments.html` | `2` |
| 教学 | `t-analytics` | 班级学情 | `chart` | `t-analytics.html` | — |
| 监控与审查 | `t-monitor` | 考试监考 | `monitor` | `t-monitor.html` | — |
| 监控与审查 | `t-plagiarism` | 查重报告 | `scan` | `t-plagiarism.html` | `5` |

**管理端（`shell.js:120-129`）**

| 分组 | id | 名称 | icon | href | badge |
| --- | --- | --- | --- | --- | --- |
| AI 平台 | `a-models` | 模型通道 | `plug` | `a-models.html` | — |
| AI 平台 | `a-audit` | 调用审计 | `coin` | `a-audit.html` | — |
| 基础设施 | `a-sandbox` | 沙箱监控 | `cpu` | **`a-audit.html#sandbox`** | — |

**href ↔ 文件名对应关系**：`href` 恒为 `{id 同名}.html`，**唯一例外是 `a-sandbox`**——它没有独立文件，指向 `a-audit.html` 的 `#sandbox` 锚点（`shell.js:126`），由 `a-audit.html:850`（`if (location.hash === '#sandbox') this.tab = 'sandbox'`）消费。

**高亮规则 `buildSide`（`shell.js:131-168`）**：`it.id === page` 时才加 `class="nav-item on"` 与 `aria-current="page"`（`shell.js:143-144`）；`badge` 存在时渲染 `<span class="badge blue|gray">`（`shell.js:146`）。因此 **`a-sandbox` 这一项永远不会高亮**——它的 `id` 与任何页面的 `data-page` 都不相等。同理 `s-exam.html:143` 用 `data-page="s-assignments"`、`t-problem-edit.html:233` 用 `data-page="t-problems"` 借用上级菜单高亮（这两页不在 `_SPEC.md:83-85` 的 `data-page` 取值表内）。

> **审计注记 4（枚举冲突 / 高优先级）**：沙箱状态在两处是两套值。`shell.js:436-440` 用 `run|idle`，`OJ.statusName`（`shell.js:465`）也为 `run→运行中 / idle→空闲` 备了译名；但 `a-audit.html:716-717` 的 `BOX_STATE` 把同一批 id 映射成 `running|queued|exited`，模板直接 `x-text="b.state"` 输出英文原值（`a-audit.html:494-495`），`OJ.statusName` 在此完全没被调用。**后端只能选一套**，建议 `running|queued|exited`（信息量更大），并把 `OJ.statusName` 的 `run/idle` 一并迁移。
> **审计注记 5（语言枚举不一致）**：`shell.js` 全量为 `'C++ 17'`（带空格），`t-plagiarism.html:474-490` 全量为 `'C++17'`（无空格）。同一系统两种写法，后端落库前必须收敛（建议 `C++17` 或 `cpp17` 作为 code，展示名另存）。
> **审计注记 6（时间为无年份字符串）**：`AI_CALLS.at` 只有 `HH:mm:ss`，`SUBMISSIONS.at / ASSIGNMENTS.due / STUDENTS.last` 只有 `MM-DD HH:mm`。后果：①跨年不可判；②`a-audit.html:877` 的排序 `a.at.localeCompare(b.at)` **仅在同一天内成立**，跨日会把 `09:00` 排到 `23:00` 之后；③`a-models.html:661-665` 的降级事件、`:512` 的「最近调用」都靠 `at` 当 key（`x-for :key="c.at"`，`a-models.html:512`），同秒并发会撞 key。后端必须下发 `ISO8601` 或 `epoch ms`。
> **审计注记 7（查重域模型双轨）**：`OJ.plagiarism`（`shell.js:443-454`）是 `sim + stage + level(high/mid/low) + state(flagged/review/pass) + verdict(pending/clean)`；而唯一使用查重的页面 `t-plagiarism.html` **完全不读它**——该页 `var OJX = window.OJ;`（`t-plagiarism.html:392`）后**只调用了 `OJX.qs('filter')` 一处**（`:776`），`OJ.plagiarism` 零消费；`:471-492` 的 `PAIRS` 是页内自建。页内域模型是 `struct + sem + level(high/suspect/normal) + review(confirmed/independent)`（`t-plagiarism.html:520-552`）。两套模型互不兼容，是后端建模前必须裁决的第一冲突。

---

## 二、管理端页面

### 页面：a-models.html

`data-role="admin" data-page="a-models" data-crumb="系统|模型通道"`（`a-models.html:182`）；`x-data="modelsPage()"`（`:184`），工厂函数在 `:621-820`。

#### 1. 页面职责

管理端**模型通道热插拔运维台**：一屏回答「哪条通道出问题了、要不要现在处理」。四个信息层（自上而下）：
1. **通道健康主带**（`:202-281`）——4 条通道并排，异常的两条带极淡底色 + 3px 左侧状态条 + 行内动作（重试/启用），正常通道无按钮；脚底一行压掉 5 个统计数字。
2. **通道列表**（`:284-407`）——状态分段筛选 + 关键词搜索 + TTFT/今日调用两列可排序 + 行内「测试/编辑/启停」悬停现形。
3. **运行概览**（`:410-466`）——路由分布 stackbar、全站 TTFT 24 点趋势、近期降级事件。
4. **两个覆盖层**——通道详情抽屉（`:469-544`，连接配置 + 24 点 TTFT + 最近调用）、新增通道弹窗（`:547-606`）。

页面自带的元信息：`ph-sub` 声明核心策略「Router 与 Guardrail 固定走本地 Ollama，Tutor 按用户所选模型路由；云端超时 8 秒自动降级本地」（`:189`）。

#### 2. 读取数据

| 数据项 | 字段 | 语义 |
| --- | --- | --- |
| 通道主键 | `models[].id` | slug，映射 4 张页内常量表的 key（`:622-647`） |
| 通道名 | `models[].name` | 列表「通道」列主文本（`:347`）、抽屉标题（`:474`） |
| 厂商 | `models[].vendor` | 「厂商」列（`:350`）、抽屉头部 tag（`:475`） |
| 承担角色 | `models[].role` → 页内 `roles[]` | `role.split(' / ')` 拆数组（`:671`），列表渲染多 tag（`:353`），抽屉 `roles.join(' · ')`（`:496`） |
| 接口地址 | `models[].base` | 抽屉 `base_url`（`:490`）；`host` 由 `HOST[id]` 或 `base.replace(/^https?:\/\//,'')` 派生（`:670`） |
| 通道状态 | **页内 `STATE`，不是 `models[].state`** | `:622` `{'local-qwen':'on','cloud-ds':'on','cloud-qwen':'degrade','cloud-zhipu':'off'}`；`models[].state` 被完全忽略 |
| TTFT | `models[].ttft` | 「TTFT」列（`:357`）、抽屉摘要（`:484`）；排序时 `parseFloat`（`:699`） |
| 累计调用 / tokens | `models[].calls` / `models[].tokens` | **只搬进 channel 对象（`:672`），全页零渲染**——死字段 |
| 成本 | `models[].cost` | 「成本」列（`:359`）、抽屉摘要（`:485`） |
| 今日调用 | 页内 `TODAY` | `:623` `{1180,542,78,40}`；「今日调用」列（`:358`）、抽屉（`:483`）、`todayTotal` 求和（`:703`） |
| 连接参数 | 页内 `CFG` | `:636-641` `{model,timeout,max_tokens,temp,conc}`；抽屉 6 个 `dt/dd`（`:491-496`） |
| 24h TTFT 序列 | 页内 `TREND` | `:642-647`，每条 **24 个整数毫秒**；抽屉柱图（`:500-504`） |
| 模型匹配串 | 页内 `MATCH` | `:626-627` `{'local-qwen':'Qwen2.5-Coder',...}`；用于 `OJ.aiCalls.filter(c => c.model.includes(m.match))`（`:715`） |
| 健康说明 | 页内 `NOTE` | `:630-635`，解释「为什么是这个状态」；健康块第二行（`:238`），写操作会覆盖它（`:759/:779/:783`） |
| 最近调用 | `OJ.aiCalls` | `at/who/stage/ttft/guard` 五项（`:512-521`），按 `MATCH` 过滤取前 5 条（`:715`） |
| 降级事件 | 页内 `degradeEvents` | `:661-665` `{t,n,d}` 三条静态 |
| 地址参数 | `OJ.qs('state'/'err'/'skeleton')` | `:681` 状态深链、`:683` 强制错误态、`:688` 骨架钉住 |

#### 3. 写操作

| 动作 | 方法名 | 期望副作用 | 是否本地模拟 |
| --- | --- | --- | --- |
| 新增通道（开弹窗） | `openAdd()` `:801-805` | 重置 `form` 为默认值、清 `formErr`、`adding=true` | 纯前端状态 |
| 新增通道（提交） | `save()` `:806-818` | 三段校验（名称/地址/角色，`:809-811`）→ `saving=true` → 750ms 后 toast「通道「x」已创建，等待首次连通校验」 | **本地模拟**；且 **`save()` 不向 `this.channels` push**，创建后列表不增（`:813-817`） |
| 连通性测试 | `test(m)` `:766-775` | `m.testing=true` → 900ms；`state==='off'` 时 toast err「当前已停用，请先启用通道」，否则 toast「连通正常 · TTFT {m.ttft}」 | **本地模拟**；**不改变 `state`**，即"测通"与"上线"解耦 |
| 降级重试 | `retry(m)` `:751-765` | 900ms；`state==='degrade'` → 置 `'on'`、`note='主 Tutor 通道 · 重试后恢复'`、toast 成功；否则 toast warn「仍不可用」 | **本地模拟**；**这是唯一能把 `degrade` 洗回 `on` 的路径** |
| 启用 / 停用 | `toggleState(m)` `:776-786` | `off→on`（`note='主 Tutor 通道 · 已启用，等待首次校验'`）；`on→off`（`note='手动停用 · 流量切至备用通道'`） | **本地模拟**；无二次确认、无乐观回滚、无 loading 态 |
| 自动降级开关 | `onDegradeSwitch()` `:787-790` | 只 toast：「已开启自动降级：云端超时 8 秒回落本地」/「已关闭自动降级，超时请求将直接失败」（warn） | **本地模拟**；不改任何通道 `state`，也**不更新** `:415` 那个静态 `<span class="tag dot">自动降级已开启</span>` |
| 刷新状态 | `refreshAll()` `:791-795` | `refreshing=true` → 800ms → toast「已同步 4 条通道的健康状态」（**"4" 硬编码**，`:794`） | 本地模拟 |
| 错误态重试 | `retryAll()` `:796-800` | 清 `loadErr`、重跑 300ms 骨架 | 本地模拟 |
| 排序 | `pickSort(k)` `:741-744` | 同列再点翻转 `d`；换列时 `today` 默认降序、其它默认升序 | 纯前端 |
| 筛选 / 搜索 / 清空 | 模板内联（`:289`、`:300`、`:389`） | `st`、`kw` 被 `filtered` getter 消费 | 纯前端 |
| 轻提示 | `say(msg, tone)` `:745-749` | 单条 `toast`，2600ms 自动清 | 纯前端（**未用 `OJ.toast`**，与 `t-dashboard.html:594` 的做法分叉） |

**接口能力定性（题面特别关注）**

- **「连通性测试」= 同步主动探活 / 诊断接口**，不是读接口。真实语义是「拿当前 base + key + timeout 打一次上游，返回 `{ok, ttft, error_code}`」。它与「列表读」是两回事：列表读的是缓存健康态，探活会**产生真实计费与真实上游负载**（`MODELS.cost` 非零，`:405`），因此后端必须限流 + 做幂等键，且**不允许在 GET 里顺手探活**。原型只给了 900ms 定时器与一个不落地的话术（`:770-774`）。
- **「启用 / 停用」= 状态写入接口**（`PATCH /admin/model-channels/{id}`，body `{state:'on'|'off'}`），幂等、需审计留痕、需与路由表热更新原子生效。原型 `toggleState` 只改内存。
- **「降级」不是可写状态，是系统观测态**。`state` 的业务闭集应为 `on | off`（`shell.js:407-409` 也只有这两个），`degrade` 是「云端连续失败后自动回落」的瞬时结果（`:633` note 写「连续 3 次超时 8s · 已回落本地」）。因此接口上 **`degrade` 必须只读**；原型的 `toggleState` 会把 `degrade` 直接 `off→on` 覆盖掉（`:781-783` 只判 `off`，`degrade` 落到 else 分支被停用），语义上是「停掉一条正在降级的通道」，容易误操作。
- **「自动降级」= 全局路由策略写**（`PUT /admin/ai/routing-policy`，`{auto_degrade:bool, timeout_ms:8000}`），影响所有在线请求，不是页面局部状态；需要权限校验 + 变更广播。

#### 4. 派生指标

| 派生量 | 实现 | 行号 | 备注 |
| --- | --- | --- | --- |
| `filtered` | 状态过滤 → `kw` 对 `name+vendor+id` 小写包含 → 按 `sort.k`（`ttft` 用 `parseFloat`、`today` 用数值）排序 | `:693-702` | 排序键只支持两列 |
| `todayTotal` | `channels.reduce((n,m) => n + m.today, 0)` | `:703` | 由 `TODAY` 求和，等于 `1180+542+78+40 = 1840`，与 a-audit `:821` 的 `todayTotal: 1840` **同值**（两页口径对齐，`a-models.html:413` 亦注明「统计口径与调用审计一致」） |
| `stIdx` | `stateFilters.findIndex(f => f.k === st)`，未命中回 0 | `:704` | 驱动分段滑块 `translateX(stIdx * 75px)`（`:286`） |
| `healthSummary` | `bad = 非 on 的通道数`；`bad ? '4 条通道 · N 条需要处理' : '4 条通道 · 全部在线'` | `:705-708` | 当前数据下 = 「4 条通道 · 2 条需要处理」 |
| `countBy(k)` | `all` 返回总数，否则按 `state` 计数 | `:709-712` | 供分段控件 `seg-n`（`:290`） |
| `recentCalls(m)` | `OJ.aiCalls.filter(c => c.model.includes(m.match)).slice(0, 5)` | `:713-716` | 抽屉「最近调用」；`match` 缺失时用 `m.name`（`:677`） |
| `maxOf` / `avgOf` | `Math.max/min` 与均值取整 | `:732-733` | 抽屉图例峰值/均值（`:507-508`） |
| `barH(m,v)` | `max===min → 80`，否则 `28 + 72*(v-min)/(max-min)` | `:734-737` | 柱高压缩到 28%–100%，避免矮柱不可见 |
| `fmtSec(ms)` | `(ms/1000).toFixed(2) + 's'` | `:738` | **未用 `OJ.fmt`** |
| `stateName(s)` | `on→在线 / degrade→降级 / 其余→离线` | `:719-723` | **未用 `OJ.statusName`** |
| `stKey(s)` | `degrade→'deg' / off→'off' / 其它→'on'` | `:724` | 映射到 CSS `.is-deg/.is-off/.is-on` |
| `guardName(g)` | `pass→通过 / rewrite→改写 / 其余→拦截` | `:725-729` | 与 a-audit `:917-921` **重复实现** |
| 硬编码统计 | 云端 3 条 / 本地 1 条 / 降级 12 次 / 平均 TTFT 0.8s（本地 0.42·云端 1.2） | `:271-279` | 4 个数字写死在模板，不走 `channels` |
| 硬编码路由分布 | `72% / 21% / 7%` + `云端 Tutor 1325 / 本地 Router 386 / 降级回落 129` | `:421-430` | 三者相加 `1325+386+129=1840`，与 `todayTotal` 自洽（仅当前数据下） |
| 硬编码 TTFT 趋势 | 24 个 `height:` 内联百分比 + 峰值 1.26s / 均值 0.80s | `:434-448` | 纯静态，无数据绑定 |

#### 5. 实时性

- **无任何轮询/长连接**。全仓 `setInterval` 只出现在 `s-exam.html:622`、`t-monitor.html:765`、`t-monitor.html:773`，a-models 没有。
- 唯一的「加载」是进入页面的 **300ms 一次性骨架**（`:688-689`），`?skeleton=1` 时钉住 60000ms 供设计评审；注释（`:685-687`）明说「原型不造假接口」。
- 「刷新状态」是**手动**触发（`:211`），800ms 后仅 toast；`refreshing` 只驱动按钮的 spinner 与文案（`:212-214`）。
- `degradeEvents`（`:661-665`）是 3 条静态事件，无时间推进；`autoDegrade` 开关不改变任何渲染。
- **结论**：页面呈现的是「快照 + 手动刷新」模型。真实产品需要 **通道健康态的推送**（SSE/WS `channel.health` 事件）或 ≥10s 轮询，否则「降级」这一核心信息会滞后到用户手动点刷新才可见。

#### 6. 难度初判

**中等偏上（★★★★☆ / 5）**。理由：
- 数据维度多且大量依赖页内派生（`STATE × TODAY × HOST × MATCH × NOTE × CFG × TREND` 共 7 张按 id 索引的旁表，`:622-647`），**一旦后端只提供 `OJ.models` 那 10 个字段，页面会立刻塌成半空**——这是最大的对接成本。7 张表中 `CFG`（6 字段）与 `TREND`（24 点序列）后端全无对应，必须新增接口。
- 交互量中等：4 类写操作（新增/测试/启停/重试）+ 1 个全局开关 + 2 列排序 + 3 向筛选。
- 无并发/无实时/无分页真实逻辑（分页按钮 `disabled` 写死，`:404-406`），故复杂度不来自状态机。
- 真正的坑在**语义**：`degrade` 的归属、探活的副作用、开关与静态文案不同步（`:415`）。

---

### 页面：a-audit.html

`data-role="admin" data-page="a-audit" data-crumb="系统|调用审计"`（`a-audit.html:198`）；`x-data="auditPage()"`（`:200`），工厂函数在 `:715-1018`。

#### 1. 页面职责

管理端**双页签可观测台**，把「AI 调用的合规留痕」与「判题容器的运行态」放在同一路由下（`a-sandbox` 菜单项即指向本页 `#sandbox`）：

- **页签一「AI 调用审计」**（`:235-420`）：主角是**护栏命中**——左侧一个大数字说清「拦了几次」（`:243`），右侧 3 条拦截原因分布（`:251-257` 遍历 `hitDist`，实测 `泄漏率 4 / 越狱特征 2 / 敏感词 1`；注意 CSS 注释 `:34` 写的是「4 条规则」，与数据不符），其余三个数字压进脚底一行（`:259-267`）；下接多条件筛选（阶段/路由互斥分段、模型多选下拉、仅看命中开关、关键词）+ 9 列调用表 + 分页；整行点开「调用详情」抽屉，回答三件事：护栏为什么这么判、走了哪条链路、卡在哪个测试点（`:538-539` 注释）。
- **页签二「沙箱容器」**（`:423-536`）：4 张统计卡 + 状态 chip 筛选 + 语言下拉 + 实时刷新开关 + 搜索 + 8 列容器表；点行或「日志」开「容器日志」抽屉（深色 codebox + 复制 + 下载 + 终止）。

#### 2. 读取数据

| 数据项 | 字段 | 语义 |
| --- | --- | --- |
| 调用记录主体 | `OJ.aiCalls[].at` | 时间列（`:376`）、排序键（`:877`）、抽屉头部 tag（`:546`）、`CHAIN`/`x-for :key` 的 key（`:374`、`:946`） |
| 用户 | `OJ.aiCalls[].who` | 「用户」列（`:377`）、抽屉摘要（`:552`）、`openBoxFromCall` 的关联键（`:1014`） |
| 阶段 | `OJ.aiCalls[].stage` | 「阶段」列标签（`:378`）、阶段筛选值（`:869`）、抽屉「阶段」（`:560`）、`promptOf` 的映射 key（`:930-936`） |
| 模型 | `OJ.aiCalls[].model` | 「模型」列（`:379`）、模型多选筛选（`:870`）、路由链路节点（`:568`）、`promptOf` 之外的搜索字段（`:874`） |
| 路由 | `OJ.aiCalls[].route` | 「路由」列 `local→本地 / 其它→云端`（`:381-382`）、路由筛选（`:871`）、链路分支（`:570-578`） |
| TTFT | `OJ.aiCalls[].ttft` | 「TTFT」列 `/1000 toFixed(2)`（`:384`）、排序（`:876`）、抽屉（`:553`）、链路说明（`:577-578`） |
| Tokens | `OJ.aiCalls[].tok` | 「Tokens」列（`:385`）、排序（`:876`）、抽屉（`:554`） |
| 护栏 | `OJ.aiCalls[].guard` | 「护栏」列三色 `.gd.pass/.rewrite/.block`（`:386`）、命中行底色 `is-hit`（`:375`）、「仅看命中」筛选（`:872`）、抽屉护栏链三段状态（`:615-631`） |
| 备注 | `OJ.aiCalls[].note` | 「备注」列 + `title` 悬浮全文（`:387`）、抽屉「备注」（`:563`）、搜索字段（`:874`） |
| 补充调用 | 页内 `MORE_CALLS` | `:725-738` 6 条同日更早记录，`init` 时 `concat` 到 `calls`（`:836`），使「仅看命中」有料可看（`:722-724` 注释） |
| 容器主体 | `OJ.sandbox[]` 的 `id/lang/user/cpu/mem/age` | 容器 ID（`:491`）、语言（`:492`）、所属用户（`:493`）、CPU 使用率（`:498`）、内存 `/1024` 转 MB（`:502`）、存活时长（`:504`） |
| 容器状态 | **页内 `BOX_STATE`** | `:716-717` 6 个 id → `running/queued/exited`；覆盖 `OJ.sandbox[].state` |
| 第 6 个容器 | 页内 `BOX_EXTRA` | `:718-720` 只补 `'sbx-8836'`，但 **`OJ.sandbox` 里没有该 id**（`shell.js:435-441` 只有 5 条），故 `:837` 的 `map` 永远产不出这一行，`:778-783` 的 `LOGS['sbx-8836']` 与 `BOX_STATE` 的该键**均为不可达死数据** |
| 容器日志 | 页内 `LOGS` | `:740-784`，按容器 id 索引，每条 `{ts:'HH:mm:ss.SSS', msg:string, cls?:'hl'}`；`hl` 为高亮行（`:144`） |
| 判分链卡点 | 页内 `CHAIN` | `:788-794`，按 `at` 索引 `{i:0-7, g:'wa'|'tle', n:string}`；`i` = 命中的测试点下标 |
| 主带统计 | 页内常量 | `todayTotal: 1840`（`:821`）、`hitDist` 4/2/1（`:822-826`）、模板里另写死 7 / 21 / 0.4% / 12 / 0.8s / 48 人（`:243-266`） |
| 地址参数 | `location.hash`、`OJ.qs('err'/'skeleton')` | `:850` `#sandbox` 直达页签二；`:851` 强制错误态；`:854` 骨架钉住 |

#### 3. 写操作

| 动作 | 方法名 | 期望副作用 | 是否本地模拟 |
| --- | --- | --- | --- |
| 刷新 | `refresh()` `:979-986` | `refreshing=true` → 800ms → toast「已同步最新 N 条调用记录」（N=`calls.length`，`:984`） | 本地模拟 |
| 导出 CSV | `exportCsv()` `:987-994` | `exporting=true` → 900ms → toast「已导出 N 条记录为 CSV」（N=`filteredCalls.length`） | **本地模拟**；**无 Blob、无 `URL.createObjectURL`、无 `<a download>`**，浏览器不会产生任何文件 |
| 终止沙箱 | `kill(b)` `:1000-1011` | 守卫 `if (b.killing \|\| b.state === 'exited') return` → 700ms → `state='exited'`、`cpu=0`、`mem=0`、toast warn「已终止，资源已回收」 | 本地模拟；**只改一行内存**，容器表行仍留在 `pagedBoxes` 里（`:489`），统计卡不跟着减（`:427` 那 3 个是静态） |
| 复制日志 | `copyLog(b)` `:964-970` | `logLines(b).map(l => l.ts + ' ' + l.msg).join('\n')` → `navigator.clipboard.writeText(txt)`（`catch` 吞错）→ toast「已复制 N 行容器日志」 | **真实实现**（本页唯一真接口调用，浏览器 Clipboard API） |
| 下载日志 | `downloadLog(b)` `:971` | toast「{id} 日志已加入下载队列」 | **本地模拟**；无请求、无文件、无队列 |
| 切页签 | `setTab(t)` `:859-864` | `tab=t`；`history.replaceState` 写 `#sandbox` 或回到纯净 URL | 纯前端（真用了 History API） |
| 从调用跳到容器 | `openBoxFromCall(c)` `:1012-1016` | 关调用抽屉、`boxes.find(b => b.user === c.who) \|\| boxes[0]`、切到 sandbox 页签 | 纯前端；**弱关联**——按「用户名」而非 `submission_id / sandbox_id` 匹配，同名用户会跳错 |
| 排序 | `pickSort(k)` `:892-896` | 同列翻 `d`；换列时 `at` 默认降序、其它升序；并 `page=1` | 纯前端 |
| 清筛选 | `resetFilters()` `:888-891` | 5 个筛选位复位 + `page=1` | 纯前端 |
| 翻页 | 模板 `page-- / page++ / page=p`（`:414-418`） | 由 `pagedCalls` 消费 | 纯前端 |
| 错误态重试 | `retryAll()` `:995-999` | 清 `loadErr`、重跑 300ms 骨架 | 本地模拟 |
| 轻提示 | `say(msg, tone)` `:974-978` | 2600ms 单条 toast | 纯前端（同样未用 `OJ.toast`） |
| 复制（其它） | — | 全页仅 `copyLog` 一处调用剪贴板 | — |

**接口能力定性（题面特别关注，逐项给结论）**

| 题面提到的动作 | 本页是否存在 | 依据 | 能力类型 |
| --- | --- | --- | --- |
| **重判** | **不存在** | 全仓 grep `重判/重测/重新评测/rejudge` = **0 命中**；a-audit 无任何重判按钮，`s-submissions`、`t-monitor` 也没有 | 若后端要提供，属**异步任务提交**（`POST /submissions/{id}/rejudge` → 返回 `task_id` + 进度流），本原型零基础 |
| **终止沙箱** | 存在 | `kill()` `:1000-1011`，按钮 `:510-516`、抽屉 `:686-691` | **命令下发 / 非幂等写**。真实语义是向沙箱调度器发 `SIGKILL`（或 `DELETE /sandbox/{id}`），必须处理「已退出」「正在终止」两态（原型用 `b.killing` + `state==='exited'` 守卫，方向正确），需要二次确认与审计记录；返回值应是最终状态而非立即 `exited` |
| **导出日志** | 存在（叫「导出 CSV」） | `exportCsv()` `:987-994` | **文件流 / 下载**。当前是纯 toast。真实后端应为 `GET /admin/ai/calls/export?format=csv&<当前筛选>`，返回 `text/csv; charset=utf-8` + `Content-Disposition`，且必须**带上筛选条件**（否则导出与所见不一致），大数据量需异步任务 + 轮询产物 |
| **下载**（日志） | 存在 | `downloadLog()` `:971` | **文件流 / 下载**，同上；容器日志适合 `GET /admin/sandbox/{id}/log?download=1`，返回 `text/plain` 或 `application/gzip` |
| **复制** | 存在 | `copyLog()` `:964-970` | **纯前端 Clipboard API**，唯一真实现。注意 `:967` 的 `.catch(function(){})` 吞掉所有失败，权限被拒时仍会 toast「已复制」——是缺陷 |
| **打印** | **不存在** | 全仓 `window.print` = 0 命中；`打印` 仅在 `a-audit.html:941` 的 AI 回复文案里作为动词出现 | 若需要，属**纯前端 `window.print()` + `@media print` 样式**，无需接口 |

#### 4. 派生指标

| 派生量 | 实现 | 行号 | 备注 |
| --- | --- | --- | --- |
| `filteredCalls` | `stage` 精确 → `fModels` 包含 → `route` 精确 → `fGuard==='hit'` 时 `guard !== 'pass'` → `fKw` 对 `who+note+model` 小写包含 → 排序 | `:867-879` | `at` 排序用 `localeCompare`（见注记 6）；数值列用减法 |
| `pagedCalls` | `slice((page-1)*pageSize, +pageSize)`，`pageSize = 8`（`:807`） | `:880-883` | 客户端分页，全量在内存 |
| `pageCount` | `Math.max(1, Math.ceil(filteredCalls.length / pageSize))` | `:884` | 下限 1，空结果也显示「第 1 / 1 页」 |
| `isFiltered` | 5 个筛选位任一非默认 | `:885` | 控制「清空」按钮显隐（`:318`） |
| `stageIdx` / `routeIdx` | `findIndex` 未命中回 0 | `:886-887` | 驱动分段滑块位移（`:275`、`:286`），宽度常量 `segW/routeW = 56`（`:817`） |
| `filteredBoxes` | `state` 精确 → `lang` 精确 → `fBoxKw` 对 `id+user+lang` 小写包含 | `:899-906` | 无排序 |
| `pagedBoxes` | **复用 `this.page` / `this.pageSize`**（与调用表共享页码） | `:907-910` | 缺陷：切页签后页码不回 1，且容器表分页条只显示总量、无翻页按钮（`:533-535`），到第 2 页后无法回退 |
| `boxCount(k)` | 按 `state` 计数 | `:911-914` | chip 上的数字（`:452`） |
| `chainOf(c)` | 未命中 `CHAIN` → 10 个 `'skip'`；命中 → 前 `i` 个 `'ac'` + 1 个 `cfg.g` + 余下 `'skip'` | `:945-956` | 恒 10 格（对应隐藏测试点 10 组）；`:948-950` 注释明确「没碰到测试点的必须是灰不是绿」 |
| `chainNote(c)` | `CHAIN[c.at].n`，否则「这次调用没有触碰任何测试点」 | `:957-960` | — |
| `promptOf(c)` | 按 `stage` 取 4 条固定 prompt，缺省「（无 prompt 记录）」 | `:929-937` | **与具体调用无关**，同阶段同文案 |
| `replyOf(c)` | 优先级 `block → rewrite → ③ → ④ → 默认` 共 5 条 | `:938-944` | 同上，按 guard/stage 模板化 |
| `guardName(g)` | `pass→通过 / rewrite→改写 / 其余→拦截` | `:917-921` | 与 a-models `:725-729` 重复 |
| `guardText(g)` | 三档长文案（含「≤5 行」阈值） | `:922-926` | 与 `_SPEC.md:336` 口径一致 |
| 主带数字 | 大字 `7`、`改写 21 次`、`拦截率 0.4%` | `:243-245` | **全部写死**；分布条的百分比还除以硬编码 `7`（`:254` `h.v / 7 * 100`） |
| 命中分布 | `hitDist` = `泄漏率 4 / 越狱特征 2 / 敏感词 1` | `:822-826` | 三者和 = 7，与大字自洽，但**不随筛选变化** |
| 脚底统计 | `todayTotal 1840`、降级 12、平均 TTFT 0.8s（云端 1.2 / 本地 0.42）、覆盖 48 人 | `:260-266`、`:821` | 仅 `todayTotal` 是响应式绑定（仍绑常量） |
| 沙箱统计卡 | 运行中 3 / 排队中 1 / 今日执行 512 / 平均耗时 12ms（P95 38ms） | `:424-445` | 前两个与 `boxes` 实际分布恰好一致，但**未绑定**；后两个纯编造 |

#### 5. 实时性

- **页面上有「实时刷新」开关，但没有任何定时器**。`autoRefresh`（`:830`）只被用来切换文案：`x-text="autoRefresh ? '实时刷新中' : '已暂停刷新'"`（`:465`），**没有任何 `setInterval` / `EventSource` / `WebSocket` 消费它**。这是本页最严重的「视觉承诺 > 实现」项——沙箱监控的核心价值（容器会不会 OOM、CPU 会不会打满）完全落空。
- 进入页面的 300ms 一次性骨架（`:854-855`），同样支持 `?skeleton=1` 钉住。
- 「刷新」按钮（`:208`）是手动 800ms 假同步。
- **结论**：需要补 **容器态推送**（WS/SSE，建议 1–2s 粒度，只推 `id/state/cpu/mem/age`）+ **审计流增量拉取**（游标分页，避免全量重拉 1840 条）。`autoRefresh` 这个开关正好可以复用为「暂停/恢复订阅」的真实控制位。

#### 6. 难度初判

**高（★★★★★ / 5）**，是本批 4 个文件中对接成本最高的一个。理由：
1. **双域合一**：审计（AI 调用）与沙箱（容器）是两套完全不同的后端服务与数据形态，却共用一个路由、一个 `page`、一个 `pageSize`、一套 toast——拆分或共享状态都要重新设计。
2. **列表 9 列 + 5 维筛选 + 3 列排序 + 服务端分页**，且 `pagedCalls` 是纯客户端切片（`:880-883`），接真接口后必须改为服务端分页，`pageCount` 语义同步变化。
3. **两个抽屉各 6–8 个区块**，其中「护栏判定链路」是 3 步定长（`:607-633`，第一步与第三步文案写死）、「判分链」是 10 格（`:598-604`），都需要后端提供结构化字段而非拼装字符串。
4. **`CHAIN` / `LOGS` / `promptOf` / `replyOf` 四组页内假数据**（`:740-794`、`:929-944`）后端全无对应，必须设计：`GET /calls/{id}` 返回 `prompt/response` 摘要 + `hit_testcase` + `guardrail_steps[]`；`GET /sandbox/{id}/log` 返回行数组。
5. 实时性缺口（见上）是额外的架构成本。

---

## 三、_SPEC.md 中的数据口径规定

> 说明：`_SPEC.md` 是「并行开发的唯一契约」（`:3`）。下表只摘**硬性规定**（枚举/格式/命名/时间/ID/阈值），并标注该规定是「显式写明」还是「只能从 shell.js 反推」。**§九「数据口径」是全文唯一集中规定业务数值的地方（`:328-339`）**，其余散落在 §二/§三/§五。

### 3.1 枚举取值

| 行号 | 原文摘录 | 含义 |
| --- | --- | --- |
| `:75` | `` `data-role` \| `student` / `teacher` / `admin`，决定侧栏导航和用户卡 `` | 角色枚举三值，**唯一合法集**；决定 `ROLES`/`NAV` 查表键 |
| `:76` | `` `data-page` \| 当前页 id，用于侧栏高亮。见下表 `` | 页面 id 必须与 `NAV[].items[].id` 逐字相等，否则不高亮 |
| `:77` | `` `data-crumb` \| 面包屑，用 `\|` 分隔，如 `题库\|全部题目` `` | 面包屑以**半角竖线**分隔，`shell.js:171` 用 `.split('\|').filter(Boolean)` 消费 |
| `:83-85` | `data-page 取值：s-problems s-workbench s-assignments s-submissions s-analytics t-dashboard t-problems t-assignments t-analytics t-monitor t-plagiarism a-models a-audit` | **13 个合法 page id**；注意**不含** `s-exam`、`t-problem-edit`（这两页实际借用上级 id，见 §1.3 注） |
| `:148` | `<span class="tag ac">AC</span>  <!-- 还有 wa tle mle re ce pac pending blue violet outline mono dot -->` | 判题标签类名枚举 **9 个判题态**（`ac wa tle mle re ce pac pending`）+ **5 个修饰**（`blue violet outline mono dot`）；`OJ.statusTag` 的 `extra` 参数即修饰位 |
| `:149` | `<span class="badge">3</span>   <!-- 还有 gray blue -->` | badge 变体 `gray \| blue`；`shell.js:146` 按选中态自动二选一 |
| `:150` | `<span class="avatar">林</span> <!-- 还有 lg gray green red -->` | avatar 变体 `lg \| gray \| green \| red` |
| `:105` | `OJ.lvlName(n)   // 1→'入门' 2→'简单' 3→'中等' 4→'较难' 5→'困难'` | **难度枚举 5 档**，1 起、无 0 档；与 `PROBLEMS.lvl` 实测 `1..4` 相容 |
| `:334` | `- AI 阶段：① 读题拆解 ② 编码引导 ③ 排错诊断 ④ AC 复盘` | **AI 阶段编号 4 档**，含圈码 `①②③④`；`AI_CALLS.stage` 必须逐字使用（含圈码后的空格），否则 `a-audit.html:869` 的精确匹配会失效 |
| `:338` | `- 查重两阶段：阶段一 AST 结构相似度（阈值 75%）、阶段二 语义向量（阈值 85%）` | **查重分级**：两阶段各自阈值，`≥75%`/`≥85%`；`t-plagiarism.html:521-525` 据此派生 `high/suspect/normal` 三级（两超=high，一超=suspect，全低=normal） |
| `:336` | `- 降级阈值 8 秒；护栏允许 ≤5 行示例代码` | **护栏动作分级**的数值依据：`≤5 行`通过 → 超出改写 → 整段题解拦截（`a-audit.html:917-926` 的三档文案即此展开） |
| `:337` | `- 作业 AI 策略：课后作业仅开 ①③、上限 3 次；考试全关（403）` | 作业类型 → 阶段开关的映射枚举；`403` 是接口层语义（考试中调 AI 直接拒绝）。`ASSIGNMENTS.ai` 的三种文案正好对应 homework/practice/exam（`shell.js:373/376/382`） |
| `:93-100` | `OJ.problems // 12 道题 {id,title,lvl,tags,ac,tried,status,score}` … `OJ.plagiarism // 5 条查重 {id,a,b,pid,title,sim,stage,state,tokens,level,verdict}` | 8 个数组的**条数**（12/10/5/10/4/10/5/5）与**字段名全集**；这是「字段命名」的硬规定——多一个字段不报错，改名/缺字段会直接渲染空 |

**题面点名要关注的 6 类，逐项落点**：

| 关注点 | 是否有硬性规定 | 依据行号 |
| --- | --- | --- |
| 状态枚举 | **部分**：判题态 9 值（`:148`）明确；提交态/沙箱态/查重态**未在 `_SPEC.md` 中单独列枚举**，只能从 `shell.js:348-453` 反推 | `:148` + `shell.js` 数据 |
| 难度枚举 | **有**：1–5 五档 + 中文名 | `:105` |
| 语言枚举 | **无**（`_SPEC.md` 全文 0 处出现 `C++ 17`/`Python 3`/`Java 17`）。**这是规格空白**，实测值与冲突见注记 5 | —— |
| 时间格式 | **无**（`_SPEC.md` 全文 0 处规定时间格式）。只能从 `shell.js` 反推 `MM-DD HH:mm`（`SUBMISSIONS.at` 等）与 `HH:mm:ss`（`AI_CALLS.at`）。**这是规格空白**，后果见注记 6 | —— |
| AI 阶段编号 | **有**：`①②③④` 四档 | `:334` |
| 护栏动作 | **有（数值口径）**：`≤5 行`+`8 秒`；三档动作名（pass/rewrite/block）本身未列枚举，由 `AI_CALLS.guard` 实测 | `:336` |
| 查重分级 | **有（阈值口径）**：75% / 85%；三级命名（high/suspect/normal vs high/mid/low）**未统一**，见注记 7 | `:338` |

### 3.2 数值格式

| 行号 | 原文摘录 | 含义 |
| --- | --- | --- |
| `:287` | `字号只用 6 级：--fs-xs 11.5 / --fs-sm 12.5 / --fs-md 13 / --fs-lg 15 / --fs-xl 19 / --fs-2xl 26。正文不小于 13px（辅助信息可用 12.5 / 11.5）` | 字号**闭环 6 档**，不允许出现第 7 档；正文下限 13px |
| `:289` | `强调色占比 ≤10%。大面积用 --panel 白 + --bg 灰底，蓝色只用于主操作、当前项、关键数字` | 颜色用量硬上限 |
| `:291` | `状态切换不改变尺寸，不挤动旁边内容。处理中按钮保持原宽` | 「处理中」必须保持宽度，禁止 `disabled` 引发换行 |
| `:297` | `表格数字列右对齐并加 font-variant-numeric:tabular-nums（body 已有）` | 数字列格式：`th/td.num` + 等宽数字 |
| `:106` | `OJ.fmt(n)   // 1841 → '1.8K'` | 千分位缩写规则；**与实现不符**（见注记 1） |
| `:333` | `- 时限 1000ms（Java/Py ×2），内存 256MB，隐藏测试点 10 组` | 判题资源口径：C++ 1000ms / Java&Py 2000ms；内存统一 256MB；测试点固定 10 组。`a-audit.html:682` 与 `:443` 复用此口径 |
| `:339` | `- 班级 48 人；本周新增提交 312 次；平均 AC 率 62%` | 三个全站口径常量；`a-audit.html:266` 的「覆盖 48 人」与 `:438` 的「覆盖 48 人」均取自此处。**注意与 `:96` 的「10 名学生」冲突**：10 是样例集规模，48 是业务口径，页面里两者并存（`OJ.students` 只有 10 条） |
| `:332` | `- 当前题目：#1024 最大子段和，难度中等，WA 80/100，首次失败在第 3 个测试点` | 演示主数据的**逐字段锁定值**：id=1024、lvl=3（中等）、score=80、`first=3`；与 `shell.js:321-322` 完全一致（`{id:1024, lvl:3, status:'wa', score:80}` / `{pid:1024, first:3}`），这是**跨文件一致性最好的口径** |

### 3.3 字段命名

| 行号 | 原文摘录 | 含义 |
| --- | --- | --- |
| `:93-100` | 8 行 `OJ.xxx // N 条 {a,b,c}` | 字段名以**逗号分隔、无引号、无类型**的简写给出；是**唯一命名来源**。命名风格实测为 `camelCase`（`pidCount`/`typeName`/`aiCalls`），**无 snake_case**——但 `a-models.html:490-494` 的抽屉又按 `base_url / max_tokens` 这类 **snake_case** 展示配置项，两套命名在同一产品并存 |
| `:110` | `页面要额外数据，自己在本页 <script> 里定义常量，不要改 shell.js` | 字段扩展的**唯一合法路径**：页内常量，禁止改共享层。这正是 a-models/a-audit 各带 7 张 / 5 张旁表的制度原因，也是后端对接时「字段缺口」的来源 |
| `:102-107` | `OJ.statusName(s) / OJ.statusTag(s) / OJ.lvl(n) / OJ.lvlName(n) / OJ.fmt(n) / OJ.qs(key, default)` | 6 个工具函数签名；`OJ.qs` 的形参名是 `key, default`，实现是 `(k, d)`——命名不统一但语义清晰 |

### 3.4 ID 格式

**`_SPEC.md` 对 ID 格式没有任何硬性规定**（全文无「ID 格式」「编号规则」章节，`id` 仅作为字段名出现在 `:93-100`）。实测的**事实标准**（只能从 `shell.js` 反推，非契约）：

| 实体 | 格式 | 样例 | 出处 |
| --- | --- | --- | --- |
| 题目 | 4 位数字（无前缀） | `1024` | `shell.js:321` |
| 提交 | `S-` + 5 位 | `S-20481` | `shell.js:348` |
| 作业 | `A-` + 4 位 | `A-0412` | `shell.js:371` |
| 查重 | `P-` + 4 位 | `P-0088` | `shell.js:444` |
| 沙箱 | `sbx-` + 4 位 | `sbx-8841` | `shell.js:436` |
| 模型通道 | 小写 slug | `local-qwen` | `shell.js:402` |
| 学号 | 8 位数字字符串 | `20220101` | `shell.js:389` |

> 前端无任何 ID 校验/解析代码（无正则、无 `parseInt(id)`），全部当**不透明字符串**处理——这对后端是好事（可自由改格式），但 `s-analytics.html:825` 有一处 `'S-2' + String(p.id).slice(-3)` 的**凭格式造 ID**，属于隐式依赖，改格式会静默产出错误 ID。

### 3.5 其余硬规则（与数据口径相邻、后端亦需知）

| 行号 | 原文摘录 | 含义 |
| --- | --- | --- |
| `:78` | `` `id="page-content"` \| **必须有**，shell.js 靠它把内容搬进主区 `` | 页面骨架硬约束 |
| `:80` | `` `<script src="shell.js">` \| **不能加 defer**，必须在 `</body>` 前同步执行 `` | 加载顺序硬约束（`shell.js:563-569` 的立即挂载依赖此） |
| `:81` | `` `vendor/alpine.min.js` \| **必须加 defer**，且放在 shell.js 之后 `` | 同上 |
| `:126` | `不要用 emoji 当图标。` | 图标硬约束（`shell.js:12` 同义重申） |
| `:298` | `12. **必须写空态**：列表无数据时给 .state，不要留白` | 空态硬约束 → 后端返回空数组时前端必须有兜底 UI（a-models `:385-390`、a-audit `:395-400`/`:525-530` 均已实现） |
| `:308` | `**每个页面至少实现 3 个真实可交互的动作**（筛选、切换、打开抽屉、提交、分页、排序等），不能是纯静态图` | 验收硬指标；也解释了为何两个管理页都带 `setTimeout` 假提交 |
| `:330-331` | `学生：**林昭**，学号 20220101，计算机 2022 级 1 班` / `教师：**周明远**，数据结构主讲` | 演示身份锁定值；与 `ROLES`（`shell.js:91-92`）逐字一致 |
| `:335` | `云端模型 **DeepSeek-V3**，本地 **Qwen2.5-Coder 7B**（Ollama）` | 模型名口径；`MODELS.name` 与 `AI_CALLS.model` 的差异正源于此（前者带 `7B`，后者不带） |

---

## 四、跨页共享的隐含契约

### 4.1 数组被谁引用（真实 grep 结果，`OJ.x` / `OJX.x` 两条路径都算）

| mock 数组 | 引用页面（文件:行号） | 页数 | 优先级 |
| --- | --- | --- | --- |
| `OJ.problems` | `s-problems.html:188,190,454,455,456,463,484` · `s-analytics.html:814` · `s-assignments.html:691,701,721` · `t-problems.html:470` · `t-analytics.html:880` | **5** | **P0** |
| `OJ.students` | `s-assignments.html:647`（仅 `[0].score`）· `t-assignments.html:485` · `t-analytics.html:692` · `t-monitor.html:522` | **4** | **P0** |
| `OJ.submissions` | `s-submissions.html:692` · `s-assignments.html:612,693` | 2 | **P0** |
| `OJ.assignments` | `s-assignments.html:637-661,691-714`（10 处） | 1（重度） | P1 |
| `OJ.aiCalls` | `a-models.html:715` · `a-audit.html:836` | **2（两个管理页都吃）** | **P0（管理端）** |
| `OJ.sandbox` | `a-audit.html:837` | 1 | P1 |
| `OJ.models` | `a-models.html:668` | 1 | P1 |
| `OJ.plagiarism` | **无任何引用** | **0** | **P3（死 mock）** |

### 4.2 工具函数被谁引用

| 函数 | 引用点 | 说明 |
| --- | --- | --- |
| `OJ.lvl` | `s-problems:160,297,359` · `s-assignments:512` · `s-analytics:469,593` · `s-exam:248` · `t-problems:308,372` · `t-problem-edit:566` · `t-dashboard:448` | **10 页**，最广 |
| `OJ.lvlName` | `s-problems:221` · `s-analytics:625` · `s-exam:227` · `t-problems:181` · `t-problem-edit:686` | 5 页 |
| `OJ.statusTag` | `s-assignments:513` · `s-analytics:472,643` · `s-submissions:314,405,442` · `t-analytics:662` · `t-monitor:457` | 6 页 / 8 处 |
| `OJ.statusName` | `s-problems:402` | **仅 1 处**（其余页面都走 `statusTag`） |
| `OJ.fmt` | `s-problems:164,288` | 2 处 |
| `OJ.qs` | `s-problems:441,443,445,449` · `s-assignments:583,585,587` · `s-exam:618,620,621,627` · `s-submissions:716,717` · `t-problems:519,521,523,525` · `t-problem-edit:790,791,794` · `t-analytics:989,992,998,1000` · `t-assignments:808,810,813,814` · `t-monitor:764` · `t-plagiarism:776` · `a-models:681,683,688` · `a-audit:851,854` | **12 页**，最广的「接口」——所有深链/错误态/骨架态的入口 |
| `OJ.toast` | `t-dashboard:594` | **仅 1 处**；两个管理页都自建了 `say()`，未复用 |
| `OJ.nav` / `OJ.roles` / `OJ.icons` / `OJ.svg` | **0 处外部引用** | 仅 shell.js 内部使用 |

### 4.3 由此决定的实现顺序

1. **`OJ.problems` 必须第一个真接口化**——它是 5 页的共同上游，且被 `s-problems`（标杆页）当作 Hero + 列表 + 详情抽屉 + 判分链的唯一数据源（`s-problems:454-478` 甚至用 `score` 反算测试点数：`Math.round(p.score/100*10)`）。**后端返回的 `score` 与 `status` 必须自洽**，否则 5 页同时出错。
2. **`OJ.students` 第二**——4 页引用，其中 `s-assignments:647` 只用 `[0].score`（把「第一个学生」当「当前登录用户」用），这是隐性硬编码：**后端必须以「当前登录者」返回，而不能保证 `[0]` 是林昭**。
3. **`OJ.submissions` + `OJ.assignments` 第三**——两者在 `s-assignments` 里交叉（`:612` 用 `pids.indexOf(s.pid)` 关联，`:691-693` 用 `subsOf(pid)` 计数），必须同批发布，否则关联断。
4. **`OJ.aiCalls` 第四（管理端 P0）**——两个管理页都读它，且 a-audit 要在其上 `concat` 本地 6 条（`:836`）。**mock 规模与页面宣称口径不自洽**：`AI_CALLS` 10 条里只有 2 条命中（`shell.js:418` 苏晚 `rewrite`、`shell.js:424` 许清和 `block`），补上 `MORE_CALLS` 6 条里的 3 条命中（`a-audit.html:727` `rewrite`、`:733` `block`、`:737` `rewrite`）后合计 **5 次（3 rewrite + 2 block）**，而主带宣称「今日护栏命中 7 次」且 `hitDist` 给出 `泄漏率 4 / 越狱特征 2 / 敏感词 1 = 7`（`:822-826`）。**5 ≠ 7，两处对不上**——这正是「聚合数与明细数必须同源」的证据：接口必须是「按日聚合 + 分页明细」两条腿，且聚合不能靠前端 `filter` 明细求和。
5. **`OJ.models` / `OJ.sandbox` 最后**——各 1 页引用，且都被页内旁表覆盖（`STATE`/`BOX_STATE`），可以按页内旁表的结构直接设计接口，不必迁就现有 10/7 字段。
6. **`OJ.plagiarism` 可直接删除或重做**——0 引用，且与 `t-plagiarism` 的域模型冲突（注记 7）。

### 4.4 跨页硬编码常量（后端若不接管会各页漂移）

| 常量 | 出现处 | 值 |
| --- | --- | --- |
| 今日调用 1840 | `a-models:275`（`todayTotal` 求和）、`a-audit:260,821` | 1840 |
| 降级 12 次 | `a-models:277`、`a-audit:262` | 12 |
| 平均 TTFT 0.8s / 云端 1.2s / 本地 0.42s | `a-models:279`、`a-audit:264` | 0.8 / 1.2 / 0.42 |
| 护栏拦截 7 / 改写 21 / 拦截率 0.4% | `a-audit:243-245` | 7 / 21 / 0.4% |
| 覆盖 48 人 | `a-audit:266`、`:438`；`_SPEC.md:339` | 48 |
| 测试点 10 组 | `s-problems:475-478`、`a-audit:951-955`、`_SPEC.md:333` | 10 |
| 并发上限 4 | `a-audit:433,534` | 4 |
| 单容器 CPU 1 核 / 内存 256MB / 时限 1000ms | `a-audit:428,497,501,534,682` | 1 / 256 / 1000（Java·Py ×2） |
| 降级阈值 8s | `a-models:189,633,788`、`a-audit:577,634`、`_SPEC.md:336` | 8000ms |
| 护栏 ≤5 行 | `a-audit:618,634`、`_SPEC.md:336` | 5 |
| 查重阈值 75% / 85% | `t-plagiarism:521,535-546`、`_SPEC.md:338` | 75 / 85 |

---

## 管理端+共享层横向汇总

### A. 管理端需要哪些接口能力

按「能力类型」归并（共 9 类、19 个端点建议）。`现状` 列标明原型是真实实现还是本地模拟。

| # | 能力类型 | 端点建议 | 消费方（行号） | 现状 | 关键要求 |
| --- | --- | --- | --- | --- | --- |
| 1 | **列表读（带筛选/排序/分页）** | `GET /admin/model-channels` | `a-models:668-702` | 本地（`OJ.models` + 7 张旁表） | 需一次返回 `state/today/ttft/cost/host/roles[]/cfg{}/trend[24]`，否则页内 7 张旁表无法删 |
| 2 | **列表读（审计明细 + 聚合）** | `GET /admin/ai/calls?stage&route&model&guard&kw&sort&page`；`GET /admin/ai/calls/summary?date=today` | `a-audit:836-885` | 本地（`OJ.aiCalls`+`MORE_CALLS`） | 聚合（7/21/0.4%/1840）与明细必须**同源同口径**，且都要带 `tz`；`at` 必须 ISO8601 |
| 3 | **列表读（容器实时态）** | `GET /admin/sandbox/containers`；推送 `WS /admin/sandbox/stream` | `a-audit:837-914` | 本地（`OJ.sandbox`+`BOX_STATE`） | 枚举统一 `running\|queued\|exited`；`age` 服务端算；**必须有推送**，否则「实时刷新」是假承诺 |
| 4 | **详情读（多段聚合）** | `GET /admin/model-channels/{id}`；`GET /admin/ai/calls/{id}`；`GET /admin/sandbox/{id}` | `a-models:469-544`；`a-audit:540-645,648-700` | 本地（`CFG/TREND/MATCH/promptOf/replyOf/CHAIN`） | 抽屉里 6–8 个区块后端全无对应，必须结构化返回：`prompt/response` 摘要、`guardrail_steps[3]`、`hit_testcase{index,verdict}`、`route_chain[]` |
| 5 | **同步探活 / 诊断** | `POST /admin/model-channels/{id}/probe`（超时 ≤8s，限流） | `a-models:766-775`（`test`） | **本地模拟 900ms** | 有真实上游副作用与计费；**只读字段不能顺手探活**；返回 `{ok, ttft_ms, error_code}`；需幂等键防连点 |
| 6 | **状态写入（幂等）** | `PATCH /admin/model-channels/{id}` body `{state:'on'\|'off'}`；`POST /admin/model-channels/{id}/reset-fallback` | `a-models:776-786`（`toggleState`）、`:751-765`（`retry`） | **本地模拟** | `degrade` **只读**，不可由客户端写入；`retry` 语义是「清降级标记」，需与探活分离；写后要热更新路由表并留审计 |
| 7 | **全局策略写** | `PUT /admin/ai/routing-policy` body `{auto_degrade, timeout_ms}` | `a-models:787-790`（`onDegradeSwitch`） | **本地模拟**（且不更新 `:415` 静态文案） | 影响全站在线请求，需权限 + 广播；响应要回带生效值供前端回显 |
| 8 | **创建资源** | `POST /admin/model-channels` body `{name,vendor,roles[],base,api_key,timeout_ms}` | `a-models:801-818`（`openAdd`/`save`） | **本地模拟**（且不 push 进列表） | key 服务端加密存储（`:595` 已承诺）；返回需含 `id` 与首次校验状态；前端必须在成功后重新拉列表 |
| 9 | **文件流 / 下载** | `GET /admin/ai/calls/export?...`；`GET /admin/sandbox/{id}/log?download=1` | `a-audit:987-994`（CSV）、`:971`（日志） | **本地模拟（纯 toast）** | 必须携带当前筛选；`Content-Disposition`；大结果走异步任务 + 产物轮询 |
| 10 | **命令下发 / 非幂等写** | `DELETE /admin/sandbox/{id}` 或 `POST .../kill` | `a-audit:1000-1011`（`kill`） | **本地模拟 700ms** | 需二次确认、终态回传、`409` 处理「已退出」；原型 `killing` + `exited` 双守卫方向正确可保留 |
| 11 | **异步任务（缺口）** | `POST /submissions/{id}/rejudge` → `{task_id}`；`GET /tasks/{id}` | **原型不存在**（全仓 0 处「重判」） | — | 若产品要「重判」，管理端需新增入口；属异步任务 + 进度流，非本页现有能力 |
| 12 | **纯前端能力** | Clipboard API（`a-audit:964-970`）；`window.print()`（**不存在**）；`history.replaceState`（`a-audit:859-864`） | — | 复制=**真实**；打印=**无**；URL=**真实** | 复制需处理权限拒绝（现 `:967` 吞错仍提示成功）；打印无任何实现 |

**优先级**：`3（容器推送）> 2（审计分页+聚合）> 1（通道列表全字段）> 5/6/7（通道运维三件套）> 4（三个详情聚合）> 9（导出下载）> 10（终止）= 12`；`11` 需产品决策。

**两个管理页共同的三个结构性缺口**：
- **实时性**：两页都只有 300ms 一次性骨架（`a-models:688`、`a-audit:854`），**零轮询、零长连接**；a-audit 的「实时刷新中」开关（`:465`）没有任何订阅逻辑支撑。管理端的核心价值（异常要现在被看到）在原型里是**手动刷新**。
- **写操作全部是 `setTimeout` + toast**：`a-models` 7 个写动作（`save`/`test`/`retry`/`toggleState`/`onDegradeSwitch`/`refreshAll`/`retryAll`）、`a-audit` 6 个写动作（`refresh`/`exportCsv`/`kill`/`downloadLog`/`copyLog`/`retryAll`）合计 13 个里，**只有 `copyLog`（`a-audit:964-970`）真调了浏览器 API**，其余 12 个都无副作用落地（甚至 `save()` 不往 `this.channels` push、`kill()` 不减少统计卡、`toggleState()` 不触发路由表变更）。
- **大量数字写死在模板**：a-models 4 个（`:271-279`）+ 路由分布 3 个（`:427-429`）+ 趋势 24 点（`:435-442`）；a-audit 6 个（`:243-266`）+ 沙箱 4 个（`:427-443`）。这些是「看起来是仪表盘、实际是静态图」的部分，后端接管前必须逐个改成绑定。

### B. 共享层哪些 mock 必须整体替换为后端数据

| mock | 替换方式 | 理由 |
| --- | --- | --- |
| `OJ.problems` | **整体替换**，且**第一条不能依赖 `[0]`** | 5 页引用；`s-problems:454` 用 `OJ.problems[0]` 当 Hero，隐藏假设「第一条是 #1024」。后端必须支持 `featured` 或按 id 取 |
| `OJ.students` | **整体替换**，并改为「当前登录者 + 班级名单」两个接口 | 4 页引用；`s-assignments:647` 把 `[0]` 当自己，逻辑错误；10 条 mock 与「48 人」口径冲突（`_SPEC.md:339`） |
| `OJ.submissions` | **整体替换** | 2 页引用，且 `s-submissions:692` 在其上做时间映射与补数；`time/mem` 是带单位字符串，后端应给数值 + 单位枚举 |
| `OJ.assignments` | **整体替换**（`left` 字段服务端算，`ai` 字段结构化） | `left` 是派生量（`'2 天'`/`'已结束'`），不应落库；`ai` 目前是展示文案（`'仅阶段 ①③ · 上限 3 次'`），无法被前端解析成策略，需拆成 `{stages:[1,3], limit:3}` |
| `OJ.models` | **整体替换，且必须扩字段** | 现 10 字段缺 `cfg{}/trend[24]/today/note/roles[]`，而 a-models 的 `:622-647` 7 张旁表全靠这些。建议直接按页内 `channel` 对象（`:668-680`）的形状下发，`role` 由 `' / '` 串改为 `roles[]` 数组 |
| `OJ.aiCalls` | **整体替换，且改为「聚合 + 分页明细」双接口** | 2 页引用；10 条 mock 与「今日 1840 / 拦截 7」口径不自洽（需 `MORE_CALLS` 兜底，`a-audit:722-724` 注释已自认）；`at` 无日期导致排序与 key 双重缺陷（注记 6） |
| `OJ.sandbox` | **整体替换，并统一状态枚举** | 1 页引用但枚举冲突（注记 4）；`mem` 是 KB 整数、`age` 是带 `s` 字符串，需规范化；第 6 个容器（`sbx-8836`）在 mock 中缺失导致 `a-audit:718-720/778-783` 成死代码 |
| `OJ.plagiarism` | **删除或重做** | 0 引用；域模型与 `t-plagiarism` 冲突（注记 7）。若保留，必须与 `PAIRS`（`t-plagiarism:471-492`）对齐为 `struct/sem/review` 模型 |
| `OJ.icons` / `OJ.roles` / `OJ.nav` | **保持为前端常量，不接口化** | 0 外部引用，纯 UI 骨架；但 `OJ.nav` 的 `badge`（学生 3 / 教师 2 / 查重 5）是**真实待办数**，若要动态化需单独接口，且必须与各列表页的计数同源 |
| `OJ.statusName` / `OJ.statusTag` / `OJ.lvl` / `OJ.lvlName` / `OJ.fmt` | **保持为前端纯函数，但需修 3 处** | ①`statusName` 补 `degrade`（a-models 另写了一套 `stateName`，`:719-723`）；②`fmt` 与 `_SPEC.md:106` 对齐（阈值 10000 vs 1000）；③`lvl` 的颜色口径与 `_SPEC.md:104` 对齐（中性 vs 含颜色） |

### C. 必须先裁决的 5 个契约冲突（否则后端建模会推倒重来）

1. **沙箱状态枚举**：`run\|idle`（`shell.js:436`）vs `running\|queued\|exited`（`a-audit:716`）。建议后者。
2. **查重域模型**：`sim/stage/level(high\|mid\|low)/state/verdict`（`shell.js:443`）vs `struct/sem/level(high\|suspect\|normal)/review`（`t-plagiarism:471-552`）。建议后者（有阈值推导、有页面消费）；`OJ.plagiarism` 应删。
3. **语言枚举**：`'C++ 17'`（`shell.js` 全量）vs `'C++17'`（`t-plagiarism:474-490`）。建议后端用 `code`（如 `cpp17`）+ `display`（`C++ 17`）。
4. **时间格式**：`MM-DD HH:mm` / `HH:mm:ss` 无年份无时区，`_SPEC.md` 未规定。建议接口统一 ISO8601 带偏移，展示层再降级。
5. **通道 `degrade` 的可写性**：`OJ.models.state` 只有 `on|off`（`shell.js:407-409`），a-models 却注入 `degrade`（`:622`）并允许 `toggleState` 把它洗成 `off`（`:781-783`）。建议 `degrade` 为只读派生态，另设 `reset-fallback` 动作。

---

*本文档共审计 4 个文件、2753 行源码；所有行号基于审计时的文件快照。*
