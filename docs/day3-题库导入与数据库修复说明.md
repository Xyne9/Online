# Day 3 题库导入与数据库设计修复说明

> 任务：重置数据库 → 审计数据库设计 → 导入 `D:\todo\oj_problems_repo` 完整题库与测试用例 → 分类
> 执行日期：2026-10-27　｜　验收：`python scripts/verify_import.py`

---

## 1. 最终结果

| 指标 | 数值 |
|---|---:|
| 题目总数 | **1008**（3 道 Day1 种子 + 1000 道题库 + 5 道验证题） |
| 已发布（学生可见） | 964 |
| 草稿（不可判题 / 无数据） | 44 |
| 测试点总数 | **4867** |
| 知识点 | 10（原 5 + 补充 5） |
| 题目-知识点关联 | 652 条（覆盖 515 道题） |
| 测试数据落盘 | **6.95 GB / 9675 文件** |
| MySQL 数据目录 | **271 MB**（只存元数据 + 4KB 摘要） |
| 表数量 | 19（18 + 新增 `prob_question_kp`） |
| 外键 / CHECK | 7 / 3（原本 0 / 0） |

### 难度分布（重标定后）

| 档位 | 题数 | 占比 |
|---|---:|---:|
| 1 入门 | 10 | 1.0% |
| 2 简单 | 191 | 19.0% |
| 3 中等 | 418 | 41.6% |
| 4 较难 | 348 | 34.6% |
| 5 困难 | 38 | 3.8% |

呈金字塔，符合「入门少数、中等为主、困难极少数」的直觉。

---

## 2. 核心决策：测试数据不入库，物化到磁盘

这是本次最重要的一个设计判断。

### 2.1 为什么不能入库

题库测试数据 **6.95 GB**，其中：

| 阈值 | 文件数 | 合计 | 占比 |
|---|---:|---:|---:|
| > 64 KB | 2080 | 6.93 GB | 99.7% |
| > 1 MB | 1022 | 6.50 GB | 93.6% |
| > 4 MB | 396 | 5.24 GB | 75.4% |
| **> 16 MB** | **94** | **2.89 GB** | **41.6%** |

单文件最大 **205.6 MB**。而：

- `prob_test_case.input_text/output_text` 是 `MEDIUMTEXT`，上限 **16,777,215 字节** → 94 个文件直接超限
- MySQL `max_allowed_packet` 只有 **64 MB** → 205 MB 的单条 INSERT 必然失败
- InnoDB buffer pool 只有 **256 MB** → 6.95 GB 的表无法有效缓存，判题查询会全部走磁盘
- 改 `LONGTEXT` 也不解决：行溢出会把数据放大到十几 GB，备份/恢复不可用

### 2.2 采用的方案

```
prob_test_case 只存元数据：
  seq_no / kind / score / is_public / input_size / output_size
  + input_text / output_text（≤4096 字节摘要，仅供前端展示失败点对比）

完整 .in/.out 物化到：
  data/testcases/{problem_id}/{seq_no}.in|.out
  容器内对应 /data/testcases/（docker-compose 已挂载 :ro）

判题时 go-judge 通过 copyIn 直接读文件，不经过数据库。
```

**这正是设计文档 §3 的原意**（`test_case_dir` 字段 + docker-compose 的 `/data/testcases` 挂载早就准备好了，只是一直没用上）。

### 2.3 效果验证

| 项 | 结果 |
|---|---|
| MySQL 占用 | 271 MB（含 13.7 MB 摘要文本） |
| 磁盘测试数据 | 6.95 GB |
| 4837 / 4867 行的 `input_text` 是截断摘要 | 与 `input_size` 不符属预期 |
| 端到端判题 | A+B 题 3 个测试点全部 AC，OI 100/100 |

> ⚠️ **给 Day 4 DAO 实现的硬约束**：`input_text/output_text` 是**摘要不是全文**，判题比对必须读 `data/testcases/{id}/{seq}.in|.out`。为防误用，已把列注释改成明确警告（见 §4 P1-7）。

---

## 3. 数据分类

### 3.1 按知识点（`knowledge_point_id` + `prob_question_kp`）

| 知识点 | 题数 |
|---|---:|
| 未分类 | 280 |
| 图论 | 203 |
| 动态规划 | 152 |
| 数学 | 138 |
| 数据结构 | 120 |
| 字符串 | 41 |
| 贪心 | 22 |
| 模拟 | 20 |
| 搜索 | 17 |
| 二分 | 12 |

**280 道「未分类」是源数据的真实缺陷**：这些题在题库里只有赛事/年份标签（如 `['2016','FJOI']`），一个算法标签都没有。没有硬塞进某个知识点——那会让学情聚合失真。

### 3.2 按算法标签（清洗后 TOP 20）

`未分类 277` / `动态规划 83` / `数学 73` / `数据结构 73` / `图论 66` / `贪心 41` / `网络流 35` / `数论 35` / `字符串 25` / `搜索 23` / `线段树 21` / `二分 19` / `平衡树 17` / `组合计数 17` / `模拟 16` / `计算几何 15` / `矩阵 14` / `最短路 12` / `树形DP 12` / `并查集 12`

### 3.3 标签清洗做了什么

题库源 `tags` 混了三类东西，直接入库会让「分类」维度失效（按 `tags[0]` 分组时前 20 名一半是年份和赛事名）：

| 类型 | 例子 | 处理 |
|---|---|---|
| 算法标签 | `DP` / `线段树` / `网络流` | ✅ 保留并归一化（`DP`→`动态规划`、`Hash`→`哈希`、`AC 自动机`→`AC自动机`） |
| 赛事 / 年份 | `NOI` / `SDOI` / `2017` / `POI` | ❌ 从 `tags` 剔除（但仍用于难度判定） |
| 数据质量标记 | `标签待整理` / `数据待检查` / `子任务` / `模板` | ❌ 剔除 |

归一化词典覆盖 **210 个实测出现过的标签**，映射到 40 个标准算法名。

### 3.4 测试点类型

| 类型 | 数量 | 规则 |
|---|---:|---|
| `sample` 样例 | 1973 | 每题前 2 个，`is_public=1`（学生可见） |
| `normal` 普通 | 1898 | 中间点，隐藏 |
| `stress` 构造 | 966 | 每题最后一个，隐藏 |

---

## 4. 数据库设计审计：修掉的 8 个问题

### P0-1 · 全库 0 个外键

**实测**：`information_schema.TABLE_CONSTRAINTS` 里 `FOREIGN KEY` = 0。`init_oj_cloud.sql:29` 写了 `SET FOREIGN_KEY_CHECKS = 0`，但**一条 FK 都没有**，是死代码。

**后果**：`prob_test_case` 表注释写的「所属题目 prob_question.id」只是注释。删题、回滚、并发导入都会留孤儿测试点；判题服务按 `problem_id` 拉配置时拿到空结果而不是报错。

**修复**：加 7 个外键（加前已确认 5 组关联全部 0 孤儿）

```sql
prob_test_case.problem_id      → prob_question.id        ON DELETE CASCADE
prob_question.knowledge_point_id → prob_knowledge_point.id ON DELETE SET NULL
teach_student_score.assignment_id → teach_assignment.id  ON DELETE CASCADE
judge_submission.problem_id    → prob_question.id        ON DELETE RESTRICT
judge_submission.user_id       → sys_user.id             ON DELETE RESTRICT
prob_question_kp.problem_id    → prob_question.id        ON DELETE CASCADE
prob_question_kp.kp_id         → prob_knowledge_point.id ON DELETE CASCADE
```

### P0-2 · `uk_prob_seq` 唯一键实际失效（最隐蔽的一个）

**实测**：原键是 `UNIQUE KEY uk_prob_seq (problem_id, seq_no, deleted_at)`。MySQL 里 **NULL 不参与唯一性判定**，所以 `deleted_at IS NULL` 时这个三列键退化成两列——而且**不生效**。

**验证**：手动插一条 `(1001, 1, ...)` 的重复行 → **插入成功**，`COUNT(*)` 变成 2。

**后果**：同一题同一 `seq_no` 可以无限插入有效行。判题按 `seq_no` 取用例时会随机命中一条，且没有任何机制阻止。

**修复**：改成两列唯一，软删时把 `seq_no` 取负腾出位置

```sql
ALTER TABLE prob_test_case DROP INDEX uk_prob_seq;
ALTER TABLE prob_test_case ADD UNIQUE KEY uk_prob_seq (problem_id, seq_no);
-- 软删语句改为：UPDATE prob_test_case SET seq_no = -seq_no, deleted_at = NOW() WHERE id = ?
```

**验证**：再插重复 → `ERROR 1062 Duplicate entry '1001-1'`。约束真正生效。

### P0-3 · 已发布题正在违反自家发布规则

**实测**：27 条测试点 `score % 5 <> 0`，分布在 9 道题（`33/33/34`），其中 **7 道是已发布态**。

**根因**：题库源有 8 道题的 `test_case_score` 之和是 99（3 点各 33），导入脚本兜底成 `33/33/34`，绕过了「5 的倍数」规则。

**后果**：PRD 第 65 行「发布前强制校验 Σscore=100」+ 表注释「5 的倍数」——这 7 道题过不了自己的发布校验却已是发布态。教师端一保存就 400。

**修复**：`33→30`、`34→40`（Σ 仍 = 100，且全为 5 的倍数），并加 CHECK 兜住未来

```sql
UPDATE prob_test_case SET score = 30 WHERE score = 33;
UPDATE prob_test_case SET score = 40 WHERE score = 34;
ALTER TABLE prob_test_case ADD CONSTRAINT ck_tc_score
  CHECK (score BETWEEN 0 AND 100 AND score % 5 = 0);
```

> Σ=100 是**跨行约束**，CHECK 做不到，必须靠发布事务里 `SELECT SUM(score) ... FOR UPDATE` 校验 —— 这条要写进 Day 4 的 DAO 规范。

### P1-1 / P1-2 / P1-3 / P2-1 · 四条高频查询全表扫

**实测 EXPLAIN**：

| 查询 | 原计划 | 原因 |
|---|---|---|
| `judge_submission WHERE assignment_id=? AND problem_id=?` | `type=ALL` | 已有 `idx_user_prob_assign` 前导列是 `user_id`，对不上 |
| `ai_chat_record WHERE create_time>=? AND stage_type=? AND selected_channel=?` | 只用 `create_time` 范围 | 缺 `(stage_type, selected_channel)` 组合 |
| `teach_student_score WHERE assignment_id=? AND problem_id=?` | 只用第一列 | 缺 `(assignment_id, problem_id)` |
| `prob_test_case WHERE problem_id=? AND is_public=1` | 回表过滤 | 契约要求的 `idx_prob_public` 不存在 |

**修复**：补 5 个索引

```sql
judge_submission      (assignment_id, problem_id, user_id)
judge_submission      (problem_id, status)
teach_student_score   (assignment_id, problem_id)
ai_chat_record        (stage_type, selected_channel, create_time)
prob_test_case        (problem_id, is_public)
```

### P1-4 · 多对多被压扁成单值

**问题**：`prob_question.knowledge_point_id` 是**单值**列，而题目有多个标签。学情分析要按「这道题涉及的所有知识点」聚合（契约 §6.6.x 的 `masteryPercent` / `weakTags`），单值会让「DP + 单调队列 + 线段树」的题只算进 DP，其余标签的能力评估永远偏低。

**修复**：保留 `knowledge_point_id` 作「主知识点展示」，另建关联表承载分析

```sql
CREATE TABLE prob_question_kp (
  problem_id BIGINT NOT NULL,
  kp_id      BIGINT NOT NULL,
  weight     DECIMAL(3,2) NOT NULL DEFAULT 1.00,
  is_primary TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (problem_id, kp_id),
  ...
);
-- 已从现有 tags 回填 652 条关联，覆盖 515 道题
```

### P1-7 · 列注释会直接误导 DAO 实现（最危险的一处文档缺陷）

**问题**：`input_text/output_text` 的注释原文写「标准输入全文 / 期望输出全文」。但实际存的是 4KB 摘要。**照注释实现 DAO 会让判题 100% 误判 WA**。

**修复**：注释改成明确警告

```sql
MODIFY input_text  MEDIUMTEXT NOT NULL
  COMMENT '输入摘要(<=4096字节; 全文见 test_case_dir/{seq_no}.in, 判题比对必须读磁盘文件)'
```

### P1-6 · collation 三套并存

**实测**：库 `utf8mb4_unicode_ci`、18 张表全是 `utf8mb4_0900_ai_ci`（建表没写 COLLATE，走了 8.0 默认）、契约文档要求 `utf8mb4_general_ci`。

`SELECT 'a' COLLATE utf8mb4_unicode_ci = 'a' COLLATE utf8mb4_0900_ai_ci` → `ERROR 1267 Illegal mix of collations`。

**修复**：库默认统一到 `utf8mb4_0900_ai_ci`（与表一致、性能更好）。

### 未改的项（明确取舍）

| 项 | 不改的理由 |
|---|---|
| `prob_question.title VARCHAR(128)` 注释说「<=40字符」 | 实测最长 50 字符 / 67 字节，列宽 128 富余，只改注释 |
| `judge_submission.time_used INT` / `memory_used INT` | 上界 24.8 天 / 2 TB，远超 30s×2 与 512MB，够用 |
| `prob_question.content TEXT` | 实测最大 13,049 字节，64KB 够 |
| 各表 `deleted_at` 不统一 | 只对需要复活的实体软删，符合取舍 |
| `SET FOREIGN_KEY_CHECKS = 0` | 加了 FK 后这行就有意义（导入期临时关闭），保留 |

---

## 5. 源数据缺陷（不是我们的问题，但必须记录）

| 缺陷 | 数量 | 处理 |
|---|---:|---|
| **4 道 CEOI2011 的 testdata 是 macOS `.DS_Store`** 伪装成 `.in/.out`（219 字节含 NUL、`Mac OS X`、`com.apple.quarantine`） | 4 | 按草稿导入，无测试点。题目本身保留（题面有教学价值） |
| Special Judge / 提交答案 / 交互题（判题内核不支持） | 38 | `state=0` + `ai_enabled=0`，不进学生题库 |
| 只有赛事/年份标签、无算法标签 | 280 | `tags=["未分类"]`，`knowledge_point_id=NULL` |
| `difficulty` 1000 道全是 `"Mid"` | 1000 | 按分类+标签+测试点规模重标定（见 §1） |
| `test_case_score` 之和 = 99 | 8 | 兜底成 `33/33/34`，再修为 `30/30/40` |

**关于「系统测试」标签**：初版把 `系统测试` 也算作不可判题，误伤了 `A + B 问题`、`Input Test`、`复读机` 三类标准 I/O 题（共 5 道）。已修正——`系统测试` 只表示这是系统测试数据，不是「不可判题」的标记。

---

## 6. 交付物

| 文件 | 作用 |
|---|---|
| [`scripts/import_problems.py`](../scripts/import_problems.py) | 题库导入主脚本（物化测试数据 + 元数据入库） |
| [`scripts/normalize_problem_tags.py`](../scripts/normalize_problem_tags.py) | 标签清洗与知识点归并（210 个标签 → 40 个标准名） |
| [`scripts/recalibrate_difficulty.py`](../scripts/recalibrate_difficulty.py) | 难度重标定（金字塔分布） |
| [`scripts/verify_import.py`](../scripts/verify_import.py) | 导入后端到端判题验证（真实跑 go-judge） |
| [`docker/mysql/init/patch_day3_design.sql`](../docker/mysql/init/patch_day3_design.sql) | 数据库设计修复补丁（**幂等**，可重复执行） |
| `_db_backup/schema_*.sql` | 各阶段 schema 快照（改动前/加索引前/最终） |

### 常用命令

```powershell
# 导入（先 dry-run 看统计，再全量）
python scripts/import_problems.py --dry-run
python scripts/import_problems.py --all

# 标签清洗 / 难度重标定（都是先 dry-run 再 --apply）
python scripts/normalize_problem_tags.py
python scripts/normalize_problem_tags.py --apply
python scripts/recalibrate_difficulty.py
python scripts/recalibrate_difficulty.py --apply

# 端到端判题验证
python scripts/verify_import.py --sample 5

# 数据库设计修复（幂等，可重跑）
Get-Content docker\mysql\init\patch_day3_design.sql -Raw |
  docker exec -i oj-cloud-mysql mysql --default-character-set=utf8mb4 -uroot -proot oj_cloud
```

---

## 7. 给 Day 4 的硬约束（写 DAO 前必读）

1. **`input_text/output_text` 是摘要，不是全文。** 判题比对只能读 `data/testcases/{problem_id}/{seq_no}.in|.out`。`ProblemJudgeConfigDTO` 已经是正确形态（只带 `seqNo/kind/score`，不含正文），基于它 + 文件系统实现，**不要**用 `TestCaseDTO.input/output` 去比对。
2. **不要用 `LENGTH(input_text) == input_size` 做完整性断言** —— 它永远不等。要校验就用 `input_size` 与磁盘 `stat().size` 比。
3. **Σscore=100 必须在发布事务里校验**（CHECK 只能管单行）。用 `SELECT SUM(score) FROM prob_test_case WHERE problem_id=? FOR UPDATE`。
4. **软删测试点要 `seq_no = -seq_no`**，否则唯一键会挡住「删了再建同号点」。
5. **乐观锁**：题目编辑保存必须带 `version` 条件，返回 0 行即 409。
6. **计数对账**：`prob_question.pass_count/submit_count` 是手工计数，无触发器。判题落库事务内增量更新，另配每日对账 SQL。
7. **索引已就位**：判题落库、学情聚合、AI 审计、取公开测试点四条查询都有覆盖索引，不要重复加。

---

## 8. 已知边界

1. **`data/` 已 gitignore**，6.95 GB 测试数据不入库。换机器需重新跑 `import_problems.py --all`（约 3.5 分钟 + 拷 6.95 GB）。
2. **`prob_question_kp` 只回填了 tags 能对上的 652 条**，280 道「未分类」题没有关联。后续可用 AI 或人工补标注。
3. **44 道草稿题**（38 道不可判 + 4 道无数据 + 2 道验证残留）学生看不到，教师端可编辑。
4. **go-judge 首次编译有冷启动开销**，偶发首点 TLE（实测重跑 3 次全 AC）。判题服务应给首次请求更宽松的超时或预热。
5. **`data/testcases` 挂载是 `:ro`**，物化新题时写的是宿主机路径，不受影响；但容器内改不了，符合预期。
