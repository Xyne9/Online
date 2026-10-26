这是一份为你全面整合、查漏补缺后的**《系统详细设计与接口规格说明书（终极完整版）》**。

这份文档将我们前面确立的所有创新点（**OI 按点计分、三级渐进式提示阶梯、代码自动注入行号、SSE 流式滑动窗口防泄题护栏、端云大模型热插拔与超时降级、双阶段漏斗抗混淆查重**）全部落实到了具体的 **MySQL 表字段、Redis 键名、前后端 RESTful 接口、微服务 OpenFeign 内部接口、RabbitMQ 消息体以及 Go-Judge 沙箱协议**中。你可以直接将此文档作为编码开发手册及毕业论文第 4 章的核心内容。

---

# 📕 第一部分：存储层详细设计（MySQL 8.0+ Redis 规范）

## 1.1 MySQL 数据库物理表结构全集（共 9 张核心表，按微服务划分）

统一数据库名：`oj_cloud`（字符集 `utf8mb4`，排序规则 `utf8mb4_unicode_ci`）。通过表前缀严格隔离 4 个微服务的领域边界：

### 【服务 1：`oj-teaching-service`（用户、教务与防作弊服务 —— 4 张表）】

```sql
-- 1. 系统用户表 (学生/教师/管理员)
CREATE TABLE `sys_user` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户主键ID',
  `username` VARCHAR(64) NOT NULL UNIQUE COMMENT '学号/工号/登录账号',
  `password` VARCHAR(128) NOT NULL COMMENT 'BCrypt加密密码',
  `real_name` VARCHAR(64) NOT NULL COMMENT '真实姓名',
  `role` VARCHAR(16) NOT NULL DEFAULT 'STUDENT' COMMENT '角色: STUDENT / TEACHER / ADMIN',
  `class_id` BIGINT DEFAULT NULL COMMENT '所属班级ID(仅学生有效)',
  `weak_tags` JSON DEFAULT NULL COMMENT '【学情画像】历史高频错题标签统计 {"动态规划": 5, "二分查找": 3}',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-正常, 0-禁用',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `idx_class` (`class_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户与学情画像表';

-- 2. 班级信息表
CREATE TABLE `teach_class` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '班级ID',
  `class_name` VARCHAR(64) NOT NULL COMMENT '班级名称, 如: 计科2201班',
  `teacher_id` BIGINT NOT NULL COMMENT '负责教师ID',
  `student_count` INT NOT NULL DEFAULT 0 COMMENT '班级人数',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教学班级表';

-- 3. 教学活动与AI分级策略配置表 (平时练习/课后作业/期末考试)
CREATE TABLE `teach_assignment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '活动ID',
  `title` VARCHAR(128) NOT NULL COMMENT '活动标题',
  `class_id` BIGINT NOT NULL COMMENT '绑定班级ID',
  `teacher_id` BIGINT NOT NULL COMMENT '创建教师ID',
  `mode_type` TINYINT NOT NULL COMMENT '模式: 1-平时练习(AI全开), 2-课后作业(仅阶段①③且限次), 3-期末考试(AI全关+防作弊)',
  `allowed_ai_stages` JSON NOT NULL COMMENT '允许的AI阶段数组: 练习[1,2,3,4], 作业[1,3], 考试[]',
  `ai_quota_per_prob` INT NOT NULL DEFAULT -1 COMMENT '单题AI提示上限(-1不限, 作业默认3, 考试0)',
  `tab_switch_limit` INT NOT NULL DEFAULT 5 COMMENT '考试切屏标红预警阈值(离开>3s计1次, 默认5次)',
  `problem_ids` JSON NOT NULL COMMENT '关联题目ID有序数组, 如 [1001, 1024, 1030]',
  `start_time` DATETIME NOT NULL COMMENT '开始时间',
  `end_time` DATETIME NOT NULL COMMENT '截止时间',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `idx_class_time` (`class_id`, `start_time`, `end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教学活动与AI策略表';

-- 4. 学生单题最高分汇总与考中监控表
CREATE TABLE `teach_student_score` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `assignment_id` BIGINT NOT NULL COMMENT '教学活动ID',
  `user_id` BIGINT NOT NULL COMMENT '学生ID',
  `problem_id` BIGINT NOT NULL COMMENT '题目ID',
  `best_submission_id` BIGINT DEFAULT NULL COMMENT '【取历史最高分】最高分对应的提交ID',
  `max_score` INT NOT NULL DEFAULT 0 COMMENT '有效期内单题历史最高分(0~100)',
  `min_time_used` INT DEFAULT 0 COMMENT '最高分对应的最短耗时(ms, 同分比耗时)',
  `submit_count` INT NOT NULL DEFAULT 0 COMMENT '累计正式提交次数',
  `ai_used_count` INT NOT NULL DEFAULT 0 COMMENT '【学情统计】该题已使用AI提示次数(仅记录不扣分)',
  `tab_switch_count` INT NOT NULL DEFAULT 0 COMMENT '【考中监控】切屏(>3秒)累计次数',
  `red_flag_type` TINYINT NOT NULL DEFAULT 0 COMMENT '异常预警状态: 0-正常, 1-切屏超阈值标红, 2-查重确认抄袭(成绩清零)',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_assign_user_prob` (`assignment_id`, `user_id`, `problem_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生单题最高分与防作弊监控表';

-- 5. 双阶段漏斗代码查重报告表
CREATE TABLE `teach_plagiarism_report` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '查重报告ID',
  `assignment_id` BIGINT NOT NULL COMMENT '所属作业/考试ID',
  `problem_id` BIGINT NOT NULL COMMENT '题目ID',
  `language` VARCHAR(16) NOT NULL COMMENT '语言: cpp / java / python',
  `user_a_id` BIGINT NOT NULL COMMENT '学生A ID',
  `sub_a_id` BIGINT NOT NULL COMMENT '学生A 最高分提交ID(得分>=60)',
  `user_b_id` BIGINT NOT NULL COMMENT '学生B ID',
  `sub_b_id` BIGINT NOT NULL COMMENT '学生B 最高分提交ID(得分>=60)',
  `stage1_algo_sim` DECIMAL(5,2) NOT NULL COMMENT '【阶段1粗筛】SimHash+AST归一化相似度(0~100.00)',
  `stage2_ai_sim` DECIMAL(5,2) DEFAULT NULL COMMENT '【阶段2精筛】AI抗混淆语义相似度(0~100.00)',
  `ai_model_used` VARCHAR(64) DEFAULT NULL COMMENT '阶段2鉴定使用的模型名称',
  `ai_diagnosis` VARCHAR(512) DEFAULT NULL COMMENT '阶段2 AI生成的抗混淆抄袭特征鉴定评语',
  `audit_status` TINYINT NOT NULL DEFAULT 0 COMMENT '教师裁定: 0-待裁定, 1-确认抄袭(双方该题记0分), 2-排除嫌疑',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `idx_assign_prob` (`assignment_id`, `problem_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='双阶段漏斗代码查重报告表';
```

### 【服务 2：`oj-problem-service`（题库与 RAG 知识库服务 —— 1 张主表）】

```sql
-- 6. 题库与隐藏官方题解RAG表
CREATE TABLE `prob_question` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '题目ID(从1001自增)',
  `title` VARCHAR(128) NOT NULL COMMENT '题目标题',
  `difficulty` TINYINT NOT NULL DEFAULT 1 COMMENT '难度: 1-简单, 2-中等, 3-困难',
  `tags` JSON NOT NULL COMMENT '算法标签数组, 如 ["动态规划", "线性DP"]',
  `content` TEXT NOT NULL COMMENT '题目Markdown正文(含题目描述、输入输出格式)',
  `sample_cases` JSON NOT NULL COMMENT '公开样例数组 [{"input":"5\\n-2 1...", "output":"6"}]',
  `code_templates` JSON NOT NULL COMMENT '标准I/O默认代码骨架 {"cpp":"#include...", "java":"import...", "python":"import sys..."}',
  `time_limit` INT NOT NULL DEFAULT 1000 COMMENT 'C/C++基准时间限制(ms), Java/Python判题时自动x2',
  `memory_limit` INT NOT NULL DEFAULT 256 COMMENT 'C/C++基准内存限制(MB), Java/Python判题时自动x2',
  `test_case_dir` VARCHAR(255) NOT NULL COMMENT '隐藏测试点文件(.in/.out)存储目录, 如 /data/testcases/1001/',
  `test_case_count` INT NOT NULL DEFAULT 10 COMMENT '隐藏测试点总数(用于OI按比例算分)',
  `official_solution` TEXT COMMENT '【保密RAG知识源】教师录入的标准题解思路与时间复杂度分析(对学生端API隐藏, 仅供Tutor Agent内部检索)',
  `pass_count` INT NOT NULL DEFAULT 0 COMMENT '累计AC人次',
  `submit_count` INT NOT NULL DEFAULT 0 COMMENT '累计提交人次',
  `creator_id` BIGINT NOT NULL COMMENT '创建教师ID',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1001 DEFAULT CHARSET=utf8mb4 COMMENT='题库与隐藏题解RAG表';
```

### 【服务 3：`oj-judge-service`（异步判题与沙箱调度服务 —— 1 张主表）】

```sql
-- 7. 代码评测提交记录表
CREATE TABLE `judge_submission` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '提交记录ID',
  `user_id` BIGINT NOT NULL COMMENT '学生ID',
  `problem_id` BIGINT NOT NULL COMMENT '题目ID',
  `assignment_id` BIGINT NOT NULL DEFAULT 0 COMMENT '所属教学活动ID(0表示平时题库自由练习)',
  `language` VARCHAR(16) NOT NULL COMMENT '语言: cpp / java / python',
  `code` TEXT NOT NULL COMMENT '学生提交的完整源码',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态机: 0-排队中(Pending), 1-编译中(Compiling), 2-评测中(Running), 3-AC(满分通过), 4-PAC(部分通过), 5-WA, 6-TLE, 7-MLE, 8-RE, 9-CE',
  `oi_score` INT NOT NULL DEFAULT 0 COMMENT 'OI按点计分最终得分(0~100)',
  `pass_case_count` INT NOT NULL DEFAULT 0 COMMENT '通过测试点个数',
  `total_case_count` INT NOT NULL DEFAULT 10 COMMENT '总测试点个数',
  `case_details` JSON DEFAULT NULL COMMENT '各测试点明细 [{"caseId":1,"status":"AC","timeMs":4,"memKb":1024}, ...]',
  `time_used` INT DEFAULT 0 COMMENT '最大测试点耗时(ms)',
  `memory_used` INT DEFAULT 0 COMMENT '最大测试点内存(KB)',
  `error_msg` TEXT COMMENT '编译错误(CE)或运行时异常(RE)堆栈日志',
  `first_failed_case_id` INT DEFAULT NULL COMMENT '【AI阶段③核心】首个未通过的测试点编号(1~N)',
  `first_failed_summary` JSON DEFAULT NULL COMMENT '【AI阶段③核心】首个失败点特征 {"status":"WA", "inputFeature":"n=5, 全负数数组[-2,-1,-3,-4,-5]", "expectedOut":"-1", "actualOut":"0"}',
  `is_overdue` TINYINT NOT NULL DEFAULT 0 COMMENT '是否作业逾期补交: 0-正常, 1-逾期练习(不更新正式作业最高分)',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `idx_user_prob_assign` (`user_id`, `problem_id`, `assignment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异步判题提交记录表';
```

### 【服务 4：`oj-ai-agent-service`（启发式多智能体服务 —— 2 张主表）】

```sql
-- 8. 端云大模型通道热插拔配置表
CREATE TABLE `ai_model_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `channel_code` VARCHAR(32) NOT NULL UNIQUE COMMENT '通道唯一标识: ollama_local / deepseek / qwen / zhipu',
  `channel_name` VARCHAR(64) NOT NULL COMMENT '前端展示名: 🟢本地极速(Qwen2.5-Coder) / 🔵云端深度(DeepSeek-V3)',
  `provider_type` VARCHAR(24) NOT NULL COMMENT '协议类型: OLLAMA / OPENAI_COMPATIBLE',
  `base_url` VARCHAR(255) NOT NULL COMMENT 'API端点, 如 http://localhost:11434/v1 或 https://api.deepseek.com/v1',
  `api_key` VARCHAR(255) DEFAULT '' COMMENT 'API密钥(本地Ollama为空)',
  `model_name` VARCHAR(64) NOT NULL COMMENT '模型标识: qwen2.5-coder:7b / deepseek-chat / glm-4-flash',
  `role_binding` VARCHAR(32) NOT NULL DEFAULT 'TUTOR_OPTIONAL' COMMENT '用途: SYSTEM_FIXED(Router与Guardrail固定绑定本地) / TUTOR_OPTIONAL(助教与查重可选)',
  `timeout_seconds` INT NOT NULL DEFAULT 8 COMMENT '超时熔断阈值(默认8秒超时自动降级本地Ollama)',
  `is_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用: 1-启用, 0-禁用',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='端云大模型热插拔路由配置表';

-- 9. 启发式AI对话与护栏审计记录表
CREATE TABLE `ai_chat_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `session_id` VARCHAR(64) NOT NULL COMMENT '会话标识: {userId}_{assignmentId}_{problemId}',
  `user_id` BIGINT NOT NULL,
  `problem_id` BIGINT NOT NULL,
  `assignment_id` BIGINT NOT NULL DEFAULT 0,
  `stage_type` TINYINT NOT NULL COMMENT '所处阶段: 1-读题思路, 2-编码伪代码, 3-排错反例, 4-AC复盘推荐',
  `hint_level` TINYINT NOT NULL DEFAULT 1 COMMENT '【渐进式脚手架】提示深度等级: 1-方向反问, 2-逻辑与反例定位, 3-填空伪代码',
  `user_message` TEXT NOT NULL COMMENT '学生提问内容或点击的快捷胶囊指令',
  `numbered_code` TEXT COMMENT '系统自动注入行号后的代码快照(如 1: #include...)',
  `selected_channel` VARCHAR(32) NOT NULL COMMENT '学生选择的模型通道编码',
  `actual_model` VARCHAR(64) NOT NULL COMMENT '实际执行模型(若触发超时降级则记录为本地模型)',
  `is_fallback` TINYINT NOT NULL DEFAULT 0 COMMENT '是否触发端云故障降级: 0-否, 1-云端超时自动降级本地Ollama',
  `tool_called` VARCHAR(64) DEFAULT NULL COMMENT '本次触发的 @Tool 工具名称(如 fetchFailedTestCaseTool)',
  `ai_response` TEXT NOT NULL COMMENT '经流式护栏审查后的最终回复内容',
  `guardrail_action` TINYINT NOT NULL DEFAULT 0 COMMENT '护栏动作: 0-正常放行, 1-输入防注入阻断, 2-输出流超5行代码块熔断折叠',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `idx_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='启发式AI四阶段对话与护栏审计表';
```

---

## 1.2 Redis 缓存与状态键名规范（共 6 类核心 Key）

| 序号 | Key 格式规范                               | 数据类型       | TTL 过期时间      | 业务用途说明                                                 |
| :--- | :----------------------------------------- | :------------- | :---------------- | :----------------------------------------------------------- |
| 1    | `oj:auth:token:{userId}`                   | String (JSON)  | 24 小时           | 存储登录用户信息与角色权限，供 `oj-gateway` 网关统一鉴权。   |
| 2    | `oj:judge:lock:{userId}`                   | String (`"1"`) | **3 秒**          | **提交/自测防抖锁**：`SETNX` 实现同一用户每 3 秒最多发起 1 次评测。 |
| 3    | `oj:ai:quota:{assignId}:{probId}:{userId}` | String (Int)   | 随作业截止        | **作业模式 AI 提示已用次数计数器**：每次 `Tutor Agent` 成功回复后 `INCR`。 |
| 4    | `oj:ai:level:{userId}:{probId}:{stage}`    | String (Int)   | 24 小时           | **三级渐进式提示阶梯计数器**：记录某生在某题某阶段的提问轮次，计算 `Level = min(count, 3)`。 |
| 5    | `oj:ai:memory:{sessionId}`                 | List (JSON)    | 2 小时            | **多轮对话滑动窗口记忆**：保留最近 6 轮对话上下文，供 LangChain4j `ChatMemory` 读取。 |
| 6    | `oj:ai:config:channels`                    | Hash           | 永久 (修改时刷新) | 缓存 `ai_model_config` 表的端云大模型配置，实现后台热插拔毫秒级生效。 |

---

# 📕 第二部分：前后端 RESTful 接口设计全集（经 `oj-gateway` 路由）

*   **网关统一前缀：** `http://localhost:8080/api/{service_module}/...`
*   **全局请求头：** `Authorization: Bearer {JWT_TOKEN}`
*   **全局状态码：** `200` 成功 | `400` 业务前置条件不满足（如未交代码点阶段③） | `401` 未登录 | `403` 权限拦截（如考试模式调 AI 接口） | `429` 请求过快（触发 3 秒防抖锁）。

---

## 2.1 在线编码与异步判题模块接口 (`oj-judge-service`)

### 接口 1.1：在线运行自测（不落库、不计分、同步/快速返回）
*   **接口路径：** `POST /api/judge/self-test`
*   **请求参数：**
```json
{
  "problemId": 1024,
  "language": "cpp",
  "code": "#include <iostream>\nusing namespace std;\nint main() { int a,b; cin>>a>>b; cout<<a+b; return 0; }",
  "customInput": "3 5"
}
```
*   **响应报文：**
```json
{
  "code": 200,
  "message": "自测完成",
  "data": {
    "status": "FINISHED",
    "compileError": null,
    "actualOutput": "8",
    "timeMs": 4,
    "memoryKb": 1520
  }
}
```

### 接口 1.2：正式提交代码（RabbitMQ 异步削峰）
*   **接口路径：** `POST /api/judge/submit`
*   **请求参数：**
```json
{
  "problemId": 1024,
  "assignmentId": 15,
  "language": "cpp",
  "code": "#include <iostream>\n..."
}
```
*   **响应报文：**
```json
{
  "code": 200,
  "message": "提交成功，已进入判题队列",
  "data": {
    "submissionId": 1849023849201,
    "status": 0,
    "isOverdue": 0
  }
}
```

### 接口 1.3：轮询判题状态与测试点明细（驱动前端控制台与 AI 阶段解锁）
*   **接口路径：** `GET /api/judge/status/{submissionId}`
*   **响应报文（OI 按点计分终态示例）：**
```json
{
  "code": 200,
  "data": {
    "submissionId": 1849023849201,
    "status": 5,
    "statusLabel": "WA (答案错误)",
    "oiScore": 80,
    "passCaseCount": 8,
    "totalCaseCount": 10,
    "timeUsed": 12,
    "memoryUsed": 3240,
    "errorMsg": null,
    "firstFailedCaseId": 3,
    "caseDetails": [
      {"caseId": 1, "status": "AC", "timeMs": 4, "memKb": 3100},
      {"caseId": 2, "status": "AC", "timeMs": 5, "memKb": 3200},
      {"caseId": 3, "status": "WA", "timeMs": 4, "memKb": 3240}
    ],
    "aiStateUpdate": {
      "canUnlockStage3": true,
      "canUnlockStage4": false,
      "guideBtnText": "💡 第 3 号测试点未通过（得分 80/100），点击让 AI 助教启发诊断反例"
    }
  }
}
```

---

## 2.2 启发式 AI 智能体模块接口 (`oj-ai-agent-service`)

### 接口 2.1：做题工作台 AI 面板初始化接口
*   **接口路径：** `GET /api/ai/workspace-init?problemId=1024&assignmentId=15`
*   **业务作用：** 学生进入做题页时调用，返回可选的本地/云端模型列表、当前教学活动下的 AI 权限、四个快捷胶囊的解锁状态、剩余配额以及历史对话记录。
*   **响应报文：**
```json
{
  "code": 200,
  "data": {
    "aiEnabled": true,
    "modeDesc": "课后作业模式（仅开放阶段①与阶段③）",
    "remainingQuota": 2,
    "maxQuota": 3,
    "availableChannels": [
      {"channelCode": "ollama_local", "channelName": "🟢 本地极速 (Qwen2.5-Coder:7B)", "isDefault": true},
      {"channelCode": "deepseek", "channelName": "🔵 云端深度 (DeepSeek-V3)", "isDefault": false},
      {"channelCode": "qwen", "channelName": "🔵 阿里百炼 (Qwen-Coder-Plus)", "isDefault": false},
      {"channelCode": "zhipu", "channelName": "🔵 智谱清言 (GLM-4-Flash)", "isDefault": false}
    ],
    "stageCapsules": [
      {"stage": 1, "title": "① 思路拆解", "unlocked": true, "nextHintLevel": 2},
      {"stage": 2, "title": "② 伪代码框架", "unlocked": false, "lockReason": "当前作业模式已禁用阶段②"},
      {"stage": 3, "title": "③ 报错与反例诊断", "unlocked": true, "nextHintLevel": 1},
      {"stage": 4, "title": "④ 复杂度复盘与推荐", "unlocked": false, "lockReason": "需本题获得满分(AC)后解锁"}
    ],
    "chatHistory": [
      {"role": "user", "stage": 1, "content": "帮我拆解一下这道题的思路"},
      {"role": "assistant", "stage": 1, "hintLevel": 1, "model": "qwen2.5-coder:7b", "content": "注意题目要求连续子数组的最大和..."}
    ]
  }
}
```

### 接口 2.2：多智能体协同 SSE 流式启发对话接口（全系统最核心接口）
*   **接口路径：** `POST /api/ai/chat/stream`
*   **响应类型：** `Content-Type: text/event-stream;charset=UTF-8`
*   **请求参数：**
```json
{
  "problemId": 1024,
  "assignmentId": 15,
  "selectedChannelCode": "deepseek",
  "capsuleStage": 3,
  "userMessage": "请帮我看看为什么第 3 个测试点 WA 了？",
  "currentCode": "#include <iostream>\n#include <vector>\nusing namespace std;\nint main() {\n    int n; cin >> n;\n    int ans = 0, sum = 0;\n    for(int i=0; i<n; i++) {\n        int x; cin >> x;\n        sum = max(x, sum + x);\n        ans = max(ans, sum);\n    }\n    cout << ans;\n    return 0;\n}"
}
```
*   **SSE 事件流协议规范（共定义 5 种标准事件，前端按 `event` 类型渲染不同 UI 动效）：**

```text
// 1. 路由与状态感知事件 (展示本地 Router Agent 判定结果及渐进式等级)
event: router_step
data: {"stage": 3, "stageName": "阶段③：排错与反例诊断", "hintLevel": 2, "levelDesc": "Level 2：逻辑拆解与边界反例定位", "routerModel": "ollama:qwen2.5-coder:7b"}

// 2. 内部工具调用事件 (展示 Agent 自动调用 @Tool 拉取判题数据)
event: tool_calling
data: {"toolName": "fetchFailedTestCaseTool", "summary": "已拉取最新提交(80分)的首个失败点 #3 特征：全负数输入场景"}

// 3. 端云超时自动降级告警事件 (仅当云端 API 超时 >8s 自动切回本地 Ollama 时触发)
event: fallback_notice
data: {"fromChannel": "deepseek", "toChannel": "ollama_local", "msg": "⚡ 云端接口响应超时，已为您无缝切换至本地 Ollama 模型继续回答"}

// 4. 正常流式打字机 Token 事件 (Tutor Agent 实时吐字)
event: token
data: {"delta": "请观察系统为你标记行号后的代码 **第 6 行**：`int ans = 0, sum = 0;`。\n\n"}

event: token
data: {"delta": "我通过内部工具查看了你未通过的 **第 3 号测试点**，该测试点的输入特征是【数组元素全部为负数】。不妨用极小反例 `n = 2, 数组为 [-2, -1]` 手算推演一下：你第 10 行的 `ans = max(ans, sum)` 最终会输出什么？应该如何修改第 6 行的初始值？"}

// 5. 流式滑动窗口护栏熔断重写事件 (仅当检测到输出超过 5 行完整代码时触发，通知前端折叠替换代码块)
event: guardrail_rewrite
data: {"reason": "检测到超过5行的可执行代码段，已触发启发式护栏折叠", "replacementText": "\n> 🛡️ **[启发式护栏提示]**：为了保障你的独立编程训练，完整代码段已被转化为以下伪代码填空步骤：\n> 1. 将 `ans` 初始化为数组第一个元素或极小值 `INT_MIN`；\n> 2. `[TODO: 在循环中更新当前连续和与全局最大值]`。\n"}

// 6. 流结束与配额同步事件
event: done
data: {"recordId": 5021, "remainingQuota": 1, "actualModelUsed": "deepseek-chat"}
```

---

## 2.3 教务管理、考中监控与双阶段查重接口 (`oj-teaching-service`)

### 接口 3.1：考试前端切屏异常实时上报接口
*   **接口路径：** `POST /api/teaching/exam/tab-switch`
*   **请求参数：** `{"assignmentId": 18, "problemId": 1024, "awaySeconds": 5}`
*   **响应报文：**
```json
{
  "code": 200,
  "data": {
    "tabSwitchCount": 5,
    "limit": 5,
    "redFlagTriggered": true,
    "alertMessage": "⚠️ 警告：您离开考试界面已达 5 次，系统已在教师监考台将您的试卷标红置顶！"
  }
}
```

### 接口 3.2：一键启动“双阶段漏斗代码查重”接口
*   **接口路径：** `POST /api/teaching/plagiarism/check`
*   **请求参数：**
```json
{
  "assignmentId": 15,
  "problemId": 1024,
  "language": "cpp",
  "stage1SimThreshold": 75.0,
  "stage2ChannelCode": "ollama_local"
}
```
*   **响应报文：**
```json
{
  "code": 200,
  "data": {
    "totalValidSamples": 45,
    "totalPairsCompared": 990,
    "stage1CostMs": 320,
    "stage1SuspiciousCount": 4,
    "stage2HighRiskCount": 2,
    "records": [
      {
        "reportId": 101,
        "userA": {"id": 2001, "name": "张三", "stuNo": "20220101"},
        "userB": {"id": 2008, "name": "李四", "stuNo": "20220108"},
        "stage1AlgoSim": 81.50,
        "stage2AiSim": 95.00,
        "aiModelUsed": "qwen2.5-coder:7b",
        "aiDiagnosis": "两份代码虽然将全部变量名进行了重命名，且把 for 循环改写为 while 结构，但核心状态转移逻辑、边界判断分支及冗余计算步骤 100% 同构，判定为抗混淆抄袭。",
        "auditStatus": 0
      }
    ]
  }
}
```

### 接口 3.3：查看查重左右分栏代码 Diff 详情与裁定接口
*   **获取 Diff 详情：** `GET /api/teaching/plagiarism/diff/{reportId}`
    *   返回 `codeA`、`codeB`、双方提交时间、各自 AI 提示使用次数及 `aiDiagnosis`。
*   **教师提交裁定：** `PUT /api/teaching/plagiarism/audit`
    *   **请求参数：** `{"reportId": 101, "auditStatus": 1}` （`1`-确认抄袭并将双方该题最高分 `max_score` 清零、标记 `red_flag_type = 2`；`2`-排除嫌疑）。

---

# 📕 第三部分：微服务内部通信与外部引擎契约设计

## 3.1 OpenFeign 服务间内部调用契约（定义于 `oj-common-api` 模块）

```java
package com.oj.common.api.feign;

// ==================== 1. 题库服务内部接口 ====================
@FeignClient(name = "oj-problem-service", path = "/inner/problem")
public interface ProblemInnerFeignClient {

    /** 供判题服务获取测试点目录与时空限制 */
    @GetMapping("/judge-config/{problemId}")
    Result<ProblemJudgeConfigDTO> getJudgeConfig(@PathVariable("problemId") Long problemId);

    /** 供 AI 服务获取题目内容与【保密官方题解 RAG】 */
    @GetMapping("/ai-rag/{problemId}")
    Result<ProblemAiRagDTO> getProblemRagForAi(@PathVariable("problemId") Long problemId);

    /** 供 AI 阶段④ @Tool 工具调用：根据薄弱标签推荐1道未做过的进阶题 */
    @GetMapping("/recommend-next")
    Result<ProblemSimpleDTO> recommendProblemByWeakTag(@RequestParam("userId") Long userId,
                                                       @RequestParam("currentProblemId") Long currentProblemId,
                                                       @RequestParam("tag") String tag);
}

// ==================== 2. 判题服务内部接口 ====================
@FeignClient(name = "oj-judge-service", path = "/inner/judge")
public interface JudgeInnerFeignClient {

    /** 供 AI 阶段③ @Tool 工具调用：获取学生最新提交状态及首个失败测试点特征 */
    @GetMapping("/failed-diagnosis")
    Result<FailedCaseDiagnosisDTO> getLatestFailedDiagnosis(@RequestParam("userId") Long userId,
                                                            @RequestParam("problemId") Long problemId,
                                                            @RequestParam("assignmentId") Long assignmentId);

    /** 供教务服务双阶段查重调用：批量获取某作业某题得分 >= 60 的历史最高分源码列表 */
    @PostMapping("/batch-best-codes")
    Result<List<SubmissionCodeDTO>> getBatchBestCodes(@RequestBody List<Long> bestSubmissionIds);
}

// ==================== 3. 教务服务内部接口 ====================
@FeignClient(name = "oj-teaching-service", path = "/inner/teaching")
public interface TeachingInnerFeignClient {

    /** 供判题服务评测完成后回调：更新学生单题历史最高分及学生画像薄弱标签 */
    @PostMapping("/score/sync")
    Result<Void> syncSubmissionScore(@RequestBody ScoreSyncRequestDTO dto);

    /** 供 AI 服务前置校验：检查当前活动是否允许该 AI 阶段、是否还有剩余配额 */
    @GetMapping("/ai-permission/verify")
    Result<AiPermissionVerifyDTO> verifyAiPermission(@RequestParam("userId") Long userId,
                                                     @RequestParam("assignmentId") Long assignmentId,
                                                     @RequestParam("problemId") Long problemId,
                                                     @RequestParam("targetStage") Integer targetStage);
}

// ==================== 4. AI 智能体服务内部接口 ====================
@FeignClient(name = "oj-ai-agent-service", path = "/inner/ai")
public interface AiInnerFeignClient {

    /** 供教务服务【双阶段查重-阶段2】调用：对算法粗筛出的可疑代码对进行 AI 抗混淆语义鉴定 */
    @PostMapping("/plagiarism/semantic-check")
    Result<AiPlagiarismResultDTO> checkCodeSemanticSimilarity(@RequestBody AiPlagiarismCheckDTO dto);
}
```

---

## 3.2 RabbitMQ 异步判题消息队列契约

*   **交换机 (Exchange)：** `oj.judge.direct.exchange` (Direct 类型，持久化)
*   **队列 (Queue)：** `oj.judge.submit.queue` (持久化，配置死信队列防判题崩溃丢单)
*   **路由键 (RoutingKey)：** `oj.judge.submit.key`
*   **消息体 JSON 结构 (`JudgeTaskMessage`)：**
```json
{
  "submissionId": 1849023849201,
  "userId": 2001,
  "problemId": 1024,
  "assignmentId": 15,
  "language": "cpp",
  "code": "#include <iostream>...",
  "isOverdue": 0,
  "sendTimestamp": 1761392000123
}
```

---

## 3.3 `oj-judge-service` 对接 `Go-Judge` 安全沙箱容器协议规范

`Go-Judge` 通过 Docker 启动在 `http://localhost:5050`。`oj-judge-service` 通过 `RestTemplate` / `WebClient` 调用其 `/run` 接口：

*   **以 C++ 评测单个测试点为例的请求报文 (`POST http://localhost:5050/run`)：**
    *   *注：C/C++ 基准限制为 `1000ms / 256MB`；若 `language` 为 `java` 或 `python`，代码中自动将 `cpuLimit` 和 `memoryLimit` 乘以 2。*
```json
{
  "cmd": [
    {
      "args": ["./main"],
      "env": ["PATH=/usr/bin:/bin"],
      "files": [
        {"content": "5\n-2 1 -3 4 -1"},
        {"name": "stdout", "max": 10485760},
        {"name": "stderr", "max": 10485760}
      ],
      "cpuLimit": 1000000000,
      "clockLimit": 2000000000,
      "memoryLimit": 268435456,
      "procLimit": 50,
      "copyIn": {
        "main": {"fileId": "go_judge_compiled_binary_id_123"}
      }
    }
  ]
}
```
*   **沙箱响应解析与 `first_failed_summary` 生成规则：**
    *   当 `status == "Accepted"` 时，提取 `files.stdout`，逐行执行 `rtrim()` 与标准 `.out` 文件比对。
    *   若遇到第一个不相等的测试点 `i`，记录 `first_failed_case_id = i`，并截取该测试点 `.in` 的前 100 个字符、标准 `.out` 前 50 个字符及学生 `stdout` 前 50 个字符，组装成 `first_failed_summary` JSON 存入 `judge_submission` 表，供 AI 助教的 `@Tool` 随时调取。

---

### 🎯 全局详细设计总结完毕！

现在你手上已经拥有了：
1. **完整的 PRD 需求规格说明书**
2. **4 大核心页面的高保真原型线框图**
3. **9 张 MySQL 物理表建表 SQL + 6 类 Redis 键规范**
4. **全套前后端 RESTful 接口 + 4 个微服务内部 OpenFeign 接口 + RabbitMQ 与 Go-Judge 协议**
