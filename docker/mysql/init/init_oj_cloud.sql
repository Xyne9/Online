-- =====================================================================
-- 文件：docker/mysql/init/init_oj_cloud.sql
-- 项目：《基于 AI Agent 与微服务架构的启发式智能在线判题系统》
-- 用途：Day1 数据库初始化脚本（MySQL 8.0），随 docker-compose 挂载到
--       /docker-entrypoint-initdb.d/ 首次启动自动执行。
--
-- 权威来源：docs/analysis/04- 终极完整版设计文档.md 第 3 章
--           「存储层物理设计全集」3.1 ~ 3.4 节，DDL 逐字对齐。
--           共 17 张核心业务表 + 1 张知识点字典表 = 18 条 CREATE TABLE。
--
-- 可重复导入性（幂等）：
--   * CREATE DATABASE IF NOT EXISTS / CREATE TABLE IF NOT EXISTS；
--   * 固定主键的种子数据使用 INSERT IGNORE，重复执行不报错、不产生重复行；
--   * prob_test_case 因 uk_prob_seq 含可空列 deleted_at（NULL 不参与唯一判重），
--     改为「先 DELETE 该题旧数据再 INSERT」，同样可重复执行。
--   * 全程不 DROP 任何对象，不覆盖业务数据。
--
-- 执行环境：MySQL 8.0 / utf8mb4 / utf8mb4_unicode_ci
-- 编码：UTF-8（无 BOM）
-- =====================================================================

-- ---------------------------------------------------------------------
-- 0. 库与连接会话
-- ---------------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS oj_cloud DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE oj_cloud;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------------------------------------------------------------------
-- 1. 表结构（18 张，顺序与设计文档 3.1 / 3.2 / 3.3 / 3.4 一致）
-- ---------------------------------------------------------------------
-- 表 1：系统用户表 (sys_user) [融合 v2.0 last_login_at]
CREATE TABLE IF NOT EXISTS `sys_user` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户主键ID',
  `username` VARCHAR(64) NOT NULL UNIQUE COMMENT '学号/工号/登录账号',
  `password` VARCHAR(128) NOT NULL COMMENT 'BCrypt加密密码',
  `real_name` VARCHAR(64) NOT NULL COMMENT '真实姓名',
  `role` VARCHAR(16) NOT NULL DEFAULT 'STUDENT' COMMENT '角色: STUDENT / TEACHER / ADMIN',
  `class_id` BIGINT DEFAULT NULL COMMENT '所属班级ID(仅学生有效)',
  `weak_tags` JSON DEFAULT NULL COMMENT '薄弱知识点名称数组快照, 如 ["图论", "数学"]',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-正常, 0-禁用',
  `last_login_at` DATETIME DEFAULT NULL COMMENT '[v2.0新增] 最近登录时刻',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `idx_class` (`class_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- 表 2：教学班级表 (teach_class)
CREATE TABLE IF NOT EXISTS `teach_class` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '班级ID',
  `class_name` VARCHAR(64) NOT NULL COMMENT '班级名称, 如: 计算机2022级1班',
  `teacher_id` BIGINT NOT NULL COMMENT '负责教师ID',
  `student_count` INT NOT NULL DEFAULT 0 COMMENT '班级人数',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教学班级表';

-- 表 3：教学活动与AI策略配置表 (teach_assignment) [融合 v2.0 display_no, status]
CREATE TABLE IF NOT EXISTS `teach_assignment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '活动ID',
  `display_no` VARCHAR(16) DEFAULT NULL UNIQUE COMMENT '[v2.0新增] 展示号 A-0412',
  `title` VARCHAR(128) NOT NULL COMMENT '活动标题',
  `class_id` BIGINT NOT NULL COMMENT '绑定班级ID',
  `teacher_id` BIGINT NOT NULL COMMENT '创建教师ID',
  `mode_type` TINYINT NOT NULL COMMENT '模式: 1-平时练习, 2-课后作业, 3-期末考试',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '[v2.0新增] 状态: 0-草稿, 1-未开始, 2-进行中, 3-已结束',
  `allowed_ai_stages` JSON NOT NULL COMMENT '允许的AI阶段数组: 练习[1,2,3,4], 作业[1,3], 考试[]',
  `ai_quota_per_prob` INT NOT NULL DEFAULT -1 COMMENT '单题AI提示上限(-1不限, 作业默认3, 考试0)',
  `tab_switch_limit` INT NOT NULL DEFAULT 5 COMMENT '考试切屏标红预警阈值(离开>3s计1次, 默认5次)',
  `problem_ids` JSON NOT NULL COMMENT '关联题目ID有序数组 [1001, 1024]',
  `start_time` DATETIME NOT NULL COMMENT '开始时间',
  `end_time` DATETIME NOT NULL COMMENT '截止时间(含考试延时)',
  `extended_minutes` INT NOT NULL DEFAULT 0 COMMENT '[v2.0新增] 考试累计延时分钟数',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `idx_class_status` (`class_id`, `status`, `end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教学活动与AI策略表';

-- 表 4：整份作业提交与5秒撤销实体表 (assignment_submission) [v2.0 全新表]
CREATE TABLE IF NOT EXISTS `assignment_submission` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `display_no` VARCHAR(16) NOT NULL UNIQUE COMMENT '展示号 SA-0412-01',
  `assignment_id` BIGINT NOT NULL COMMENT '作业 teach_assignment.id',
  `user_id` BIGINT NOT NULL COMMENT '学生 sys_user.id',
  `class_id` BIGINT NOT NULL COMMENT '冗余班级ID',
  `status` VARCHAR(16) NOT NULL DEFAULT 'UNSUBMITTED' COMMENT 'UNSUBMITTED 未交 / SUBMITTED 已交',
  `submitted_at` DATETIME(3) DEFAULT NULL COMMENT '提交时间',
  `undo_deadline_at` DATETIME(3) DEFAULT NULL COMMENT '5秒撤销截止时刻(submitted_at + 5s)',
  `revoked_at` DATETIME(3) DEFAULT NULL COMMENT '最近一次撤销时间',
  `problem_count` INT NOT NULL DEFAULT 0 COMMENT '应交题数快照',
  `answered_count` INT NOT NULL DEFAULT 0 COMMENT '已作答题数快照',
  `remark` VARCHAR(255) DEFAULT NULL COMMENT '学生备注',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_assign_user` (`assignment_id`, `user_id`),
  KEY `idx_assign_status` (`assignment_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='整份作业提交实体表';

-- 表 5：学生单题最高分汇总与考中监控表 (teach_student_score)
CREATE TABLE IF NOT EXISTS `teach_student_score` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `assignment_id` BIGINT NOT NULL COMMENT '教学活动ID',
  `user_id` BIGINT NOT NULL COMMENT '学生ID',
  `problem_id` BIGINT NOT NULL COMMENT '题目ID',
  `best_submission_id` BIGINT DEFAULT NULL COMMENT '最高分对应的提交ID',
  `max_score` INT NOT NULL DEFAULT 0 COMMENT '有效期内单题历史最高分(0~100)',
  `min_time_used` INT DEFAULT 0 COMMENT '最高分对应的最短耗时(ms)',
  `draft_language` VARCHAR(16) DEFAULT 'cpp' COMMENT '[v2.0新增] 考试作答草稿语言',
  `draft_code` MEDIUMTEXT DEFAULT NULL COMMENT '[v2.0新增] 考试实时保存的代码草稿',
  `submit_count` INT NOT NULL DEFAULT 0 COMMENT '累计正式提交次数',
  `ai_used_count` INT NOT NULL DEFAULT 0 COMMENT '该题已使用AI提示次数(仅记录不扣分)',
  `tab_switch_count` INT NOT NULL DEFAULT 0 COMMENT '切屏(>3秒)累计次数',
  `red_flag_type` TINYINT NOT NULL DEFAULT 0 COMMENT '异常预警状态: 0-正常, 1-切屏超阈值标红, 2-查重确认抄袭(清零)',
  `prev_max_score` INT DEFAULT NULL COMMENT '[v2.0新增] 查重清零前的原分数(供撤销复核恢复)',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_assign_user_prob` (`assignment_id`, `user_id`, `problem_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生单题最高分与考中监控表';

-- 表 6：考试实时监考事件流表 (exam_monitor_event) [v2.0 全新表]
CREATE TABLE IF NOT EXISTS `exam_monitor_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `exam_id` BIGINT NOT NULL COMMENT '考试ID(teach_assignment.id)',
  `seq` BIGINT NOT NULL COMMENT '考试内自增序号(SSE Last-Event-ID 断点续传源)',
  `user_id` BIGINT DEFAULT NULL COMMENT '相关考生ID(NULL为考场级事件)',
  `event_type` VARCHAR(32) NOT NULL COMMENT 'EXAM_START/STUDENT_ENTER/TAB_SWITCH/AI_CALL/SUBMIT/OFFLINE/WARN/EXAM_END',
  `tone` VARCHAR(16) NOT NULL DEFAULT 'info' COMMENT 'info / warn / danger',
  `message` VARCHAR(255) NOT NULL COMMENT '可直接展示的事件文案',
  `payload` JSON DEFAULT NULL COMMENT '附加数据, 如 {"awaySeconds": 12}',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_exam_seq` (`exam_id`, `seq`),
  KEY `idx_exam_time` (`exam_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='考试实时监考事件表';

-- 表 7：异步查重任务表 (teach_plagiarism_task) [v2.0 全新表]
CREATE TABLE IF NOT EXISTS `teach_plagiarism_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `display_no` VARCHAR(16) NOT NULL UNIQUE COMMENT '展示号 PL-0031',
  `assignment_id` BIGINT NOT NULL COMMENT '作业/考试ID',
  `problem_ids` JSON NOT NULL COMMENT '参与查重的题目ID数组',
  `language` VARCHAR(16) DEFAULT NULL COMMENT '限定语言(NULL为不限)',
  `stage1_threshold` DECIMAL(5,2) NOT NULL DEFAULT 75.00 COMMENT 'Stage1 结构相似度阈值',
  `stage2_channel` VARCHAR(32) DEFAULT NULL COMMENT 'Stage2 语义比对所用模型通道',
  `state` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / STAGE1 / STAGE2 / DONE / FAILED',
  `progress_percent` TINYINT NOT NULL DEFAULT 0 COMMENT '进度 0-100',
  `total_pairs` INT NOT NULL DEFAULT 0 COMMENT '待比对总对数',
  `compared_pairs` INT NOT NULL DEFAULT 0 COMMENT '已完成比对对数',
  `suspicious_count` INT NOT NULL DEFAULT 0 COMMENT 'Stage1 粗筛疑似数',
  `high_risk_count` INT NOT NULL DEFAULT 0 COMMENT 'Stage2 精筛高危数',
  `estimated_ms` INT NOT NULL DEFAULT 40000 COMMENT '预估耗时(ms)',
  `error_msg` VARCHAR(512) DEFAULT NULL COMMENT '失败原因',
  `created_by` BIGINT NOT NULL COMMENT '发起教师ID',
  `started_at` DATETIME DEFAULT NULL,
  `finished_at` DATETIME DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_assign_state` (`assignment_id`, `state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异步查重任务表';

-- 表 8：双阶段漏斗代码查重报告表 (teach_plagiarism_report) [融合 v2.0 display_no, task_id]
CREATE TABLE IF NOT EXISTS `teach_plagiarism_report` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '查重报告ID',
  `display_no` VARCHAR(16) DEFAULT NULL UNIQUE COMMENT '[v2.0新增] 展示号 P-0088',
  `task_id` BIGINT DEFAULT NULL COMMENT '[v2.0新增] 所属查重任务ID',
  `assignment_id` BIGINT NOT NULL COMMENT '所属作业/考试ID',
  `problem_id` BIGINT NOT NULL COMMENT '题目ID',
  `language` VARCHAR(16) NOT NULL COMMENT '语言: cpp / java / python',
  `user_a_id` BIGINT NOT NULL COMMENT '学生A ID',
  `sub_a_id` BIGINT NOT NULL COMMENT '学生A 最高分提交ID(得分>=60)',
  `user_b_id` BIGINT NOT NULL COMMENT '学生B ID',
  `sub_b_id` BIGINT NOT NULL COMMENT '学生B 最高分提交ID(得分>=60)',
  `stage1_algo_sim` DECIMAL(5,2) NOT NULL COMMENT 'Stage1 结构相似度(structSim, 0~100.00)',
  `stage2_ai_sim` DECIMAL(5,2) DEFAULT NULL COMMENT 'Stage2 AI语义相似度(semSim, 0~100.00)',
  `ai_model_used` VARCHAR(64) DEFAULT NULL COMMENT 'Stage2 鉴定模型名称',
  `ai_diagnosis` VARCHAR(512) DEFAULT NULL COMMENT 'Stage2 AI生成的抗混淆抄袭特征评语',
  `audit_status` TINYINT NOT NULL DEFAULT 0 COMMENT '教师裁定: 0-待复核, 1-确认抄袭, 2-排除嫌疑',
  `audited_by` BIGINT DEFAULT NULL COMMENT '[v2.0新增] 复核教师ID',
  `audited_at` DATETIME DEFAULT NULL COMMENT '[v2.0新增] 复核时间',
  `audit_note` VARCHAR(255) DEFAULT NULL COMMENT '[v2.0新增] 复核备注',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `idx_assign_prob` (`assignment_id`, `problem_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='双阶段代码查重报告表';

-- 表 9：查重相同代码片段对齐明细表 (teach_plagiarism_segment) [v2.0 全新表]
CREATE TABLE IF NOT EXISTS `teach_plagiarism_segment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `report_id` BIGINT NOT NULL COMMENT '关联 teach_plagiarism_report.id',
  `seg_id` INT NOT NULL COMMENT '片段序号(从1起, 对应原型 segs 1..4)',
  `seg_name` VARCHAR(64) NOT NULL COMMENT '片段名称, 如: 循环体（状态转移）',
  `a_from_line` INT NOT NULL COMMENT 'A侧起始行(1基)',
  `a_to_line` INT NOT NULL COMMENT 'A侧结束行',
  `b_from_line` INT NOT NULL COMMENT 'B侧起始行(1基)',
  `b_to_line` INT NOT NULL COMMENT 'B侧结束行',
  `row_count` INT NOT NULL DEFAULT 0 COMMENT '该片段对齐行数(前端跳转滚动计算用)',
  `similarity` DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '片段级相似度 0-100',
  `annotation` VARCHAR(255) DEFAULT NULL COMMENT '行级对比批注说明',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_report_seg` (`report_id`, `seg_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='查重相同片段对齐明细表';
-- 辅助字典表：知识点字典表 (prob_knowledge_point)
CREATE TABLE IF NOT EXISTS `prob_knowledge_point` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(64) NOT NULL UNIQUE COMMENT '知识点名称: 动态规划 / 贪心 / 图论 / 字符串 / 数学',
  `sort_order` INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识点字典表';

-- 表 10：题库与隐藏官方题解RAG表 (prob_question) [融合 v2.0 5档难度与7个新列]
CREATE TABLE IF NOT EXISTS `prob_question` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '题目ID(从1001自增, 展示为 #1024)',
  `title` VARCHAR(128) NOT NULL COMMENT '题目标题(<=40字符)',
  `difficulty` TINYINT NOT NULL DEFAULT 2 COMMENT '[v2.0五档] 1-入门, 2-简单, 3-中等, 4-较难, 5-困难',
  `knowledge_point_id` BIGINT DEFAULT NULL COMMENT '[v2.0新增] 主知识点ID',
  `tags` JSON NOT NULL COMMENT '算法标签数组(<=5个)',
  `content` TEXT NOT NULL COMMENT '题目Markdown正文(含输入输出格式)',
  `sample_cases` JSON NOT NULL COMMENT '公开样例数组 [{"input":"...", "output":"..."}]',
  `code_templates` JSON NOT NULL COMMENT '默认代码骨架 {"cpp":"...", "java":"...", "python":"..."}',
  `time_limit` INT NOT NULL DEFAULT 1000 COMMENT 'C/C++基准时限(ms), Java/Py自动x2',
  `memory_limit` INT NOT NULL DEFAULT 256 COMMENT 'C/C++基准内存限制(MB), Java/Py自动x2',
  `test_case_dir` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '物化测试点目录(/data/testcases/{id}/)',
  `test_case_count` INT NOT NULL DEFAULT 10 COMMENT '有效测试点总数',
  `official_solution` TEXT COMMENT '【保密RAG知识源】标准题解思路(仅Tutor Agent可见)',
  `ai_enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '[v2.0新增] 是否允许AI辅助',
  `ai_quota_limit` INT NOT NULL DEFAULT 3 COMMENT '[v2.0新增] 默认每题AI调用上限(-1不限)',
  `ai_stages` JSON DEFAULT NULL COMMENT '[v2.0新增] 默认允许的AI阶段数组 [1,2,3,4]',
  `state` TINYINT NOT NULL DEFAULT 0 COMMENT '[v2.0新增] 状态: 0-草稿, 1-已发布, 2-已归档',
  `version` INT NOT NULL DEFAULT 1 COMMENT '[v2.0新增] 乐观锁版本号',
  `pass_count` INT NOT NULL DEFAULT 0 COMMENT '累计AC人次',
  `submit_count` INT NOT NULL DEFAULT 0 COMMENT '累计提交人次',
  `creator_id` BIGINT NOT NULL COMMENT '创建教师ID',
  `deleted_at` DATETIME DEFAULT NULL COMMENT '[v2.0新增] 软删时间(NULL为有效)',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_state_diff` (`state`, `difficulty`, `deleted_at`)
) ENGINE=InnoDB AUTO_INCREMENT=1001 DEFAULT CHARSET=utf8mb4 COMMENT='题库与隐藏题解RAG表';

-- 表 11：题目逐测试点实体表 (prob_test_case) [v2.0 全新表]
CREATE TABLE IF NOT EXISTS `prob_test_case` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `problem_id` BIGINT NOT NULL COMMENT '所属题目 prob_question.id',
  `seq_no` INT NOT NULL COMMENT '测试点序号(从1起, 同题内连续)',
  `kind` VARCHAR(16) NOT NULL DEFAULT 'normal' COMMENT '类型四档: sample样例 / normal普通 / edge边界 / stress构造',
  `input_text` MEDIUMTEXT NOT NULL COMMENT '标准输入全文',
  `output_text` MEDIUMTEXT NOT NULL COMMENT '期望输出全文',
  `score` INT NOT NULL DEFAULT 10 COMMENT '该点分值(0-100, 5的倍数, 同题合计须=100方可发布)',
  `is_public` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否对学生公开: 1-公开(样例) / 0-隐藏',
  `input_size` INT NOT NULL DEFAULT 0 COMMENT '输入字节数',
  `output_size` INT NOT NULL DEFAULT 0 COMMENT '输出字节数',
  `deleted_at` DATETIME DEFAULT NULL COMMENT '软删时间',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_prob_seq` (`problem_id`, `seq_no`, `deleted_at`),
  KEY `idx_prob_kind` (`problem_id`, `kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题目测试点实体表';
-- 表 12：代码评测提交记录表 (judge_submission) [融合 v2.0 4个新列]
CREATE TABLE IF NOT EXISTS `judge_submission` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '提交记录ID',
  `display_no` VARCHAR(16) DEFAULT NULL UNIQUE COMMENT '[v2.0新增] 展示号 S-20481',
  `user_id` BIGINT NOT NULL COMMENT '学生ID',
  `problem_id` BIGINT NOT NULL COMMENT '题目ID',
  `assignment_id` BIGINT NOT NULL DEFAULT 0 COMMENT '所属教学活动ID(0为自由题库练习)',
  `language` VARCHAR(16) NOT NULL COMMENT '语言: cpp / java / python',
  `code` TEXT NOT NULL COMMENT '学生提交的完整源码',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态机: 0-排队, 1-编译, 2-评测, 3-AC, 4-PAC, 5-WA, 6-TLE, 7-MLE, 8-RE, 9-CE',
  `oi_score` INT NOT NULL DEFAULT 0 COMMENT 'OI按点计分最终得分(0~100)',
  `pass_case_count` INT NOT NULL DEFAULT 0 COMMENT '通过测试点个数',
  `total_case_count` INT NOT NULL DEFAULT 10 COMMENT '总测试点个数',
  `case_details` JSON DEFAULT NULL COMMENT '各测试点明细 [{"caseId":1,"seqNo":1,"kind":"sample","status":"AC","score":10,"gotScore":10,"timeMs":4,"memKb":1024}]',
  `time_used` INT DEFAULT 0 COMMENT '最大测试点耗时(ms)',
  `memory_used` INT DEFAULT 0 COMMENT '最大测试点内存(KB)',
  `error_msg` TEXT COMMENT '编译错误或运行时异常日志',
  `first_failed_case_id` INT DEFAULT NULL COMMENT '首个未通过的测试点序号(1~N)',
  `first_failed_summary` JSON DEFAULT NULL COMMENT '首个失败点脱敏特征 {"status":"WA","inputFeature":"...","expectedOut":"-1","actualOut":"0"}',
  `sandbox_instance_id` VARCHAR(32) DEFAULT NULL COMMENT '[v2.0新增] 关联沙箱实例展示号 sbx-8841',
  `rejudge_count` INT NOT NULL DEFAULT 0 COMMENT '[v2.0新增] 重判次数',
  `is_overdue` TINYINT NOT NULL DEFAULT 0 COMMENT '是否逾期补交: 0-正常, 1-逾期练习',
  `finished_at` DATETIME DEFAULT NULL COMMENT '[v2.0新增] 判题完成时刻',
  `teaching_synced_at` DATETIME(3) DEFAULT NULL COMMENT '教务已接收成绩时刻，NULL由后台重试',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `idx_user_prob_assign` (`user_id`, `problem_id`, `assignment_id`),
  INDEX `idx_create_status` (`create_time`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异步判题提交记录表';

-- 表 13：沙箱容器实例监控表 (judge_sandbox_instance) [v2.0 全新表]
CREATE TABLE IF NOT EXISTS `judge_sandbox_instance` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `sandbox_instance_id` VARCHAR(32) NOT NULL UNIQUE COMMENT '对外展示句柄 sbx-8841',
  `submission_id` BIGINT DEFAULT NULL COMMENT '关联提交ID(自测/运行样例为NULL)',
  `user_id` BIGINT NOT NULL COMMENT '发起用户ID',
  `problem_id` BIGINT DEFAULT NULL COMMENT '关联题目ID',
  `language` VARCHAR(16) NOT NULL COMMENT 'cpp / java / python',
  `state` VARCHAR(16) NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED / RUNNING / EXITED / KILLED',
  `judge_node` VARCHAR(64) DEFAULT 'local' COMMENT '执行节点标识',
  `container_id` VARCHAR(64) DEFAULT NULL COMMENT '容器/进程组标识',
  `cpu_limit` INT NOT NULL DEFAULT 1 COMMENT 'CPU核数上限',
  `mem_limit_kb` INT NOT NULL DEFAULT 262144 COMMENT '内存上限(KB)',
  `cpu_percent` INT NOT NULL DEFAULT 0 COMMENT '实时CPU占用百分比',
  `mem_used_kb` INT NOT NULL DEFAULT 0 COMMENT '实时内存占用(KB)',
  `exit_code` INT DEFAULT NULL COMMENT '退出码(0正常, 124超时, 137被kill)',
  `started_at` DATETIME DEFAULT NULL,
  `finished_at` DATETIME DEFAULT NULL,
  `last_heartbeat_at` DATETIME DEFAULT NULL COMMENT '最后心跳时间(超15s判失联回收)',
  `log_ref` TEXT DEFAULT NULL COMMENT '容器stdout/stderr采样日志缓存',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_state_hb` (`state`, `last_heartbeat_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='沙箱容器实例监控表';
-- 表 14：端云大模型通道配置表 (ai_model_config) [融合 v2.0 6个新列]
CREATE TABLE IF NOT EXISTS `ai_model_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `channel_code` VARCHAR(32) NOT NULL UNIQUE COMMENT '稳定通道标识: local-qwen / cloud-ds / cloud-qwen / cloud-zhipu',
  `channel_name` VARCHAR(64) NOT NULL COMMENT '前端展示名: 本地 Qwen / 云端 DeepSeek',
  `vendor` VARCHAR(32) NOT NULL DEFAULT 'Ollama' COMMENT '[v2.0新增] 厂商: Ollama / DeepSeek / Qwen / Zhipu / OpenAI-Compatible',
  `provider_type` VARCHAR(24) NOT NULL DEFAULT 'OPENAI_COMPATIBLE' COMMENT '协议类型: OLLAMA / OPENAI_COMPATIBLE',
  `base_url` VARCHAR(255) NOT NULL COMMENT 'API端点URL',
  `api_key` VARCHAR(255) DEFAULT '' COMMENT '加密存储的API密钥',
  `model_name` VARCHAR(64) NOT NULL COMMENT '模型标识: Qwen2.5-Coder-7B / DeepSeek-V3',
  `role_binding` VARCHAR(32) NOT NULL DEFAULT 'TUTOR_OPTIONAL' COMMENT 'v1.0兼容列: SYSTEM_FIXED / TUTOR_OPTIONAL',
  `timeout_seconds` INT NOT NULL DEFAULT 8 COMMENT '超时秒数(接口层统一转换为 timeoutMs = timeout_seconds * 1000 下发)',
  `max_tokens` INT NOT NULL DEFAULT 2048 COMMENT '[v2.0新增] 单次回复Token上限',
  `temperature` DECIMAL(3,2) NOT NULL DEFAULT 0.30 COMMENT '[v2.0新增] 采样温度 0.00-1.00',
  `max_concurrency` INT NOT NULL DEFAULT 8 COMMENT '[v2.0新增] 最大并发数',
  `health_status` VARCHAR(16) NOT NULL DEFAULT 'ON' COMMENT '[v2.0新增] 健康态: ON / DEGRADE / OFF / PROBING',
  `health_note` VARCHAR(255) DEFAULT NULL COMMENT '[v2.0新增] 健康异常或降级原因说明',
  `last_probe_at` DATETIME DEFAULT NULL COMMENT '[v2.0新增] 最近一次探活时间',
  `is_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用: 1-启用, 0-禁用',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='端云大模型通道配置表';

-- 表 15：通道与多智能体三角色绑定关联表 (ai_model_role) [v2.0 全新表]
CREATE TABLE IF NOT EXISTS `ai_model_role` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `channel_code` VARCHAR(32) NOT NULL COMMENT '关联 ai_model_config.channel_code',
  `role` VARCHAR(16) NOT NULL COMMENT '智能体角色: ROUTER / TUTOR / GUARDRAIL',
  `priority` INT NOT NULL DEFAULT 100 COMMENT '同角色优先级(小者优先)',
  `is_enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_role` (`channel_code`, `role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型通道与三角色多对多绑定表';

-- 表 16：启发式AI对话与护栏审计记录表 (ai_chat_record) [融合 v2.0 溯源、计量与护栏原因列]
CREATE TABLE IF NOT EXISTS `ai_chat_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `session_id` VARCHAR(64) NOT NULL COMMENT '会话标识: {userId}_{assignmentId}_{problemId}',
  `user_id` BIGINT NOT NULL,
  `problem_id` BIGINT NOT NULL,
  `assignment_id` BIGINT NOT NULL DEFAULT 0,
  `submission_id` BIGINT DEFAULT NULL COMMENT '[v2.0新增] 关联判题提交ID(阶段③溯源用)',
  `trace_id` VARCHAR(64) DEFAULT NULL COMMENT '[v2.0新增] 全链路追踪ID',
  `stage_type` TINYINT NOT NULL COMMENT '所处阶段: 1-题意澄清, 2-思路启发, 3-错因诊断, 4-复杂度优化',
  `hint_level` TINYINT NOT NULL DEFAULT 1 COMMENT '渐进式提示等级: 1-方向反问, 2-逻辑与反例, 3-填空伪代码',
  `user_message` TEXT NOT NULL COMMENT '学生提问内容',
  `numbered_code` TEXT COMMENT '自动注入行号后的代码快照',
  `selected_channel` VARCHAR(32) NOT NULL COMMENT '请求的模型通道编码',
  `actual_model` VARCHAR(64) NOT NULL COMMENT '实际执行的模型名称',
  `is_fallback` TINYINT NOT NULL DEFAULT 0 COMMENT '是否触发端云降级: 0-否, 1-超时降级本地',
  `tool_called` VARCHAR(64) DEFAULT NULL COMMENT '调用的 @Tool 名称',
  `ai_response` TEXT NOT NULL COMMENT '最终启发式回复内容',
  `guardrail_action` TINYINT NOT NULL DEFAULT 0 COMMENT '护栏动作: 0-pass通过, 1-block输入阻断, 2-rewrite输出改写',
  `guard_reason` VARCHAR(32) NOT NULL DEFAULT 'NONE' COMMENT '[v2.0新增] 命中原因: NONE / LEAK_RATE / JAILBREAK / SENSITIVE_WORD / OVER_LENGTH_CODE',
  `guard_replacement` TEXT DEFAULT NULL COMMENT '[v2.0新增] 护栏改写后的替代文本',
  `ttft_ms` INT DEFAULT NULL COMMENT '[v2.0新增] 首Token响应耗时(ms)',
  `tokens_in` INT NOT NULL DEFAULT 0 COMMENT '[v2.0新增] 输入Prompt Token数',
  `tokens_out` INT NOT NULL DEFAULT 0 COMMENT '[v2.0新增] 输出Completion Token数',
  `cost_cents` INT NOT NULL DEFAULT 0 COMMENT '[v2.0新增] 本次调用成本(分)',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `idx_session` (`session_id`),
  INDEX `idx_submission` (`submission_id`),
  INDEX `idx_create_guard` (`create_time`, `guardrail_action`, `guard_reason`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='启发式AI对话与护栏审计表';

-- 表 17：AI回复质量反馈表 (ai_call_feedback) [v2.0 全新表]
CREATE TABLE IF NOT EXISTS `ai_call_feedback` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `record_id` BIGINT NOT NULL COMMENT '关联 ai_chat_record.id',
  `user_id` BIGINT NOT NULL COMMENT '反馈学生ID',
  `helpful` TINYINT(1) NOT NULL COMMENT '1-有帮助 / 0-无帮助',
  `reason_code` VARCHAR(16) DEFAULT NULL COMMENT 'WRONG / TOO_VAGUE / LEAKED / TOO_LONG / OTHER',
  `comment` VARCHAR(200) DEFAULT NULL COMMENT '补充评价',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_record_user` (`record_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI回复质量反馈表';

-- =====================================================================
-- 种子数据 1：用户（学生 1 / 教师 2 / 管理员 3）
-- 密码明文均为 123456，password 列统一存放同一 BCrypt 哈希（cost=10）
-- =====================================================================
INSERT IGNORE INTO `sys_user`
(`id`, `username`, `password`, `real_name`, `role`, `class_id`, `weak_tags`, `status`, `last_login_at`)
VALUES
(1, '20220101', '$2b$10$0ST491RXGIQAEEH6Xo3is.JkwfZQgx1agt4SFCa9.BTrm3TXZgRPK', '林昭', 'STUDENT', 1, '["图论", "数学"]', 1, '2026-10-20 08:30:00'),
(2, 'T001',     '$2b$10$0ST491RXGIQAEEH6Xo3is.JkwfZQgx1agt4SFCa9.BTrm3TXZgRPK', '王砚', 'TEACHER', NULL, NULL, 1, '2026-10-20 08:00:00'),
(3, 'admin',    '$2b$10$0ST491RXGIQAEEH6Xo3is.JkwfZQgx1agt4SFCa9.BTrm3TXZgRPK', '运维管理员', 'ADMIN', NULL, NULL, 1, '2026-10-20 07:50:00');

-- =====================================================================
-- 种子数据 2：教学班级（id=1，负责教师为 T001，人数按实际学生数=1）
-- =====================================================================
INSERT IGNORE INTO `teach_class` (`id`, `class_name`, `teacher_id`, `student_count`)
VALUES (1, '计算机2022级1班', (SELECT `id` FROM `sys_user` WHERE `username` = 'T001'), 1);

-- =====================================================================
-- 种子数据 3：知识点字典（5 条）
-- =====================================================================
INSERT IGNORE INTO `prob_knowledge_point` (`id`, `name`, `sort_order`) VALUES
(1, '动态规划', 1),
(2, '贪心', 2),
(3, '图论', 3),
(4, '字符串', 4),
(5, '数学', 5);

-- =====================================================================
-- 种子数据 4：题库（3 道，显式 id 1001~1003，creator_id = 教师 T001）
-- =====================================================================
INSERT IGNORE INTO `prob_question`
(`id`, `title`, `difficulty`, `knowledge_point_id`, `tags`, `content`, `sample_cases`,
 `code_templates`, `time_limit`, `memory_limit`, `test_case_dir`, `test_case_count`,
 `official_solution`, `ai_enabled`, `ai_quota_limit`, `ai_stages`, `state`, `version`,
 `pass_count`, `submit_count`, `creator_id`, `deleted_at`)
VALUES
(1001, '最大子段和', 3, 1, '["动态规划", "数组", "最大子段和"]', '## 题目描述

给定一个长度为 `n` 的整数序列 `a[1..n]`，请找出一个**非空连续子段**，使得该子段内所有元素之和最大，输出这个最大和。

注意：子段必须非空，即使所有元素都是负数，也要选出其中最大的单个元素。

## 输入格式

- 第一行一个整数 `n`（`1 <= n <= 100000`），表示序列长度。
- 第二行包含 `n` 个整数 `a[i]`（`-10000 <= a[i] <= 10000`），相邻两个整数之间用一个空格分隔。

## 输出格式

输出一行一个整数，表示最大子段和。

## 样例说明

**样例 1**

输入：

```
8
-2 1 -3 4 -1 2 1 -5
```

输出：

```
6
```

解释：最大子段为 `4 -1 2 1`，其和为 `6`。

**样例 2**

输入：

```
5
1 2 3 4 5
```

输出：

```
15
```

解释：全部取用时和最大，`1+2+3+4+5 = 15`。

## 数据范围与提示

- `40%` 的数据满足 `n <= 100`，可用 `O(n^2)` 枚举。
- `100%` 的数据满足 `n <= 100000`，请使用 `O(n)` 的动态规划。
- 最大和可达 `10^9` 量级，仍建议使用 64 位整数（`long long` / `long`），避免中间过程溢出。
', '[{"input": "8\\n-2 1 -3 4 -1 2 1 -5\\n", "output": "6\\n"}, {"input": "5\\n1 2 3 4 5\\n", "output": "15\\n"}]', '{"cpp": "#include <bits/stdc++.h>\\nusing namespace std;\\n\\nint main() {\\n    ios::sync_with_stdio(false);\\n    cin.tie(nullptr);\\n\\n    int n;\\n    if (!(cin >> n)) return 0;\\n    vector<long long> a(n);\\n    for (int i = 0; i < n; ++i) cin >> a[i];\\n\\n    // TODO: 求非空连续子段的最大和（提示：Kadane，O(n)），把占位值换成你的答案\\n    long long ans = 0;  // 占位，请替换\\n    cout << ans << ''\\n'';\\n    return 0;\\n}\\n", "java": "import java.io.*;\\nimport java.util.*;\\n\\npublic class Main {\\n    public static void main(String[] args) throws Exception {\\n        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));\\n        String line = br.readLine();\\n        if (line == null) return;\\n        int n = Integer.parseInt(line.trim());\\n        long[] a = new long[n];\\n        StringTokenizer st = new StringTokenizer(br.readLine());\\n        for (int i = 0; i < n && st.hasMoreTokens(); ++i) {\\n            a[i] = Long.parseLong(st.nextToken());\\n        }\\n\\n        // TODO: 求非空连续子段的最大和（提示：Kadane，O(n)），把占位值换成你的答案\\n        long ans = 0L;  // 占位，请替换\\n        System.out.println(ans);\\n    }\\n}\\n", "python": "import sys\\n\\n\\ndef main() -> None:\\n    data = sys.stdin.read().split()\\n    if not data:\\n        return\\n    n = int(data[0])\\n    a = list(map(int, data[1:1 + n]))\\n\\n    # TODO: 求非空连续子段的最大和（提示：Kadane，O(n)），把占位值换成你的答案\\n    ans = 0  # 占位，请替换\\n    print(ans)\\n\\n\\nif __name__ == \\"__main__\\":\\n    main()\\n"}', 1000, 256, '/data/testcases/1001/', 10, '标准做法：Kadane 动态规划，时间复杂度 O(n)，空间复杂度 O(1)。

设 f[i] 表示「以第 i 个元素结尾」的最大子段和，则状态转移：
f[i] = max(a[i], f[i-1] + a[i])，答案为 max(f[1..n])。

实现细节：用变量 cur 维护 f[i]，cur = (cur > 0 ? cur : 0) + a[i]，再用 best 维护全局最大值。
因为子段必须非空，初值应取 a[1] 或 LLONG_MIN，不能把 best 初始化为 0，否则「全负数」数据会错误输出 0。
答案使用 long long / int64（Python 天然大整数，Java 用 long）。', 1, 3, '[1, 2, 3, 4]', 1, 1, 128, 356, 2, NULL),
(1002, '两数之和', 1, 5, '["哈希表", "数组", "数学"]', '## 题目描述

给定一个长度为 `n` 的整数序列 `a[1..n]` 和一个目标值 `target`，请在其中找出**两个不同位置** `i < j`，使得 `a[i] + a[j] = target`。

若存在多组解，请输出 `i` 最小的一组；若 `i` 相同则 `j` 最小。若不存在这样的两个位置，输出 `-1`。

## 输入格式

- 第一行两个整数 `n` 和 `target`（`2 <= n <= 100000`，`-10^9 <= target <= 10^9`），用一个空格分隔。
- 第二行包含 `n` 个整数 `a[i]`（`-10^6 <= a[i] <= 10^6`），相邻两个整数之间用一个空格分隔。

## 输出格式

- 若存在解，输出一行两个整数 `i j`（下标从 **1** 开始，`i < j`），用一个空格分隔。
- 若不存在解，输出一行 `-1`。

## 样例说明

**样例 1**

输入：

```
4 9
2 7 11 15
```

输出：

```
1 2
```

解释：`a[1] + a[2] = 2 + 7 = 9`，且这是下标字典序最小的一组。

**样例 2**

输入：

```
3 6
3 2 4
```

输出：

```
2 3
```

解释：`a[2] + a[3] = 2 + 4 = 6`；`a[1] + a[2] = 5 != 6`，故答案为 `2 3`。

## 数据范围与提示

- 暴力枚举 `O(n^2)` 只能通过 `40%` 的数据。
- 正解使用哈希表（值 -> 位置列表）或排序 + 双指针，注意输出要求「i 最小、再 j 最小」。
', '[{"input": "4 9\\n2 7 11 15\\n", "output": "1 2\\n"}, {"input": "3 6\\n3 2 4\\n", "output": "2 3\\n"}]', '{"cpp": "#include <bits/stdc++.h>\\nusing namespace std;\\n\\nint main() {\\n    ios::sync_with_stdio(false);\\n    cin.tie(nullptr);\\n\\n    int n;\\n    long long target;\\n    if (!(cin >> n >> target)) return 0;\\n    vector<long long> a(n);\\n    for (int i = 0; i < n; ++i) cin >> a[i];\\n\\n    // TODO: 输出 i j（1 基，i < j）；无解输出 -1。\\n    // 提示：哈希表存「值 -> 下标」，单次遍历即可，O(n)。\\n    cout << -1 << ''\\n'';  // 占位，请替换\\n    return 0;\\n}\\n", "java": "import java.io.*;\\nimport java.util.*;\\n\\npublic class Main {\\n    public static void main(String[] args) throws Exception {\\n        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));\\n        StringTokenizer st = new StringTokenizer(br.readLine());\\n        if (!st.hasMoreTokens()) return;\\n        int n = Integer.parseInt(st.nextToken());\\n        long target = Long.parseLong(st.nextToken());\\n        long[] a = new long[n];\\n        st = new StringTokenizer(br.readLine());\\n        for (int i = 0; i < n && st.hasMoreTokens(); ++i) {\\n            a[i] = Long.parseLong(st.nextToken());\\n        }\\n\\n        // TODO: 输出 i j（1 基，i < j）；无解输出 -1。\\n        // 提示：HashMap 存「值 -> 下标」，单次遍历即可，O(n)。\\n        System.out.println(-1);  // 占位，请替换\\n    }\\n}\\n", "python": "import sys\\n\\n\\ndef main() -> None:\\n    data = sys.stdin.read().split()\\n    if not data:\\n        return\\n    n = int(data[0])\\n    target = int(data[1])\\n    a = list(map(int, data[2:2 + n]))\\n\\n    # TODO: 输出 i j（1 基，i < j）；无解输出 -1。\\n    # 提示：dict 存「值 -> 下标」，单次遍历即可，O(n)。\\n    print(-1)  # 占位，请替换\\n\\n\\nif __name__ == \\"__main__\\":\\n    main()\\n"}', 1000, 256, '/data/testcases/1002/', 10, '标准做法：哈希表，时间复杂度 O(n log n)（或平均 O(n)）。

1. 建立映射：值 -> 该值出现的全部下标（升序列表）；
2. 按 i 从小到大枚举，对每个 i 查询 `target - a[i]` 对应的下标列表中，**严格大于 i 的最小下标 j**（有序列表二分查找 `bisect_right`）；
3. 第一次命中的 i 与对应 j 即为「i 最小、再 j 最小」的答案，直接输出；
4. 全部枚举完仍无命中则输出 -1。

若只需「存在性」，也可以边遍历边用哈希表记录已见过的值，一次扫描即可（O(n)），但那种写法拿到的是「j 最小」而非题目要求的「i 最小」，需要额外注意。', 1, 3, '[1, 2, 3, 4]', 1, 1, 241, 402, 2, NULL),
(1003, '回文串判断', 2, 4, '["字符串", "双指针"]', '## 题目描述

给定一个由**小写字母和数字**组成的字符串 `s`，判断它是否为回文串。

回文串的定义：字符串从左向右读与从右向左读完全相同。

## 输入格式

- 输入一行，一个非空字符串 `s`（`1 <= |s| <= 100000`），只包含小写字母 `a-z` 和数字 `0-9`，不含空格。

## 输出格式

- 若 `s` 是回文串，输出 `YES`；
- 否则输出 `NO`。

## 样例说明

**样例 1**

输入：

```
level
```

输出：

```
YES
```

解释：`level` 正着读和反着读都是 `level`。

**样例 2**

输入：

```
algorithm
```

输出：

```
NO
```

解释：倒序为 `mhtirogla`，与原串不同。

## 数据范围与提示

- `60%` 的数据满足 `|s| <= 1000`。
- `100%` 的数据满足 `|s| <= 100000`，建议使用双指针从两端向中间比较，`O(n)` 时间、`O(1)` 额外空间。
', '[{"input": "level\\n", "output": "YES\\n"}, {"input": "algorithm\\n", "output": "NO\\n"}]', '{"cpp": "#include <bits/stdc++.h>\\nusing namespace std;\\n\\nint main() {\\n    ios::sync_with_stdio(false);\\n    cin.tie(nullptr);\\n\\n    string s;\\n    if (!(cin >> s)) return 0;\\n\\n    // TODO: 判断 s 是否为回文串，是输出 YES，否则输出 NO。\\n    // 提示：双指针从两端向中间比较，O(n)。\\n    cout << \\"NO\\n\\";  // 占位，请替换\\n    return 0;\\n}\\n", "java": "import java.io.*;\\nimport java.util.*;\\n\\npublic class Main {\\n    public static void main(String[] args) throws Exception {\\n        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));\\n        String s = br.readLine();\\n        if (s == null) return;\\n        s = s.trim();\\n\\n        // TODO: 判断 s 是否为回文串，是输出 YES，否则输出 NO。\\n        // 提示：双指针从两端向中间比较，O(n)。\\n        System.out.println(\\"NO\\");  // 占位，请替换\\n    }\\n}\\n", "python": "import sys\\n\\n\\ndef main() -> None:\\n    s = sys.stdin.readline().strip()\\n\\n    # TODO: 判断 s 是否为回文串，是输出 YES，否则输出 NO。\\n    # 提示：双指针从两端向中间比较，O(n)。\\n    print(\\"NO\\")  # 占位，请替换\\n\\n\\nif __name__ == \\"__main__\\":\\n    main()\\n"}', 1000, 256, '/data/testcases/1003/', 10, '标准做法：双指针，时间复杂度 O(n)，额外空间 O(1)。

令 i = 0、j = |s| - 1，当 i < j 时比较 s[i] 与 s[j]：
- 若不相同，立即输出 NO；
- 若相同，则 i++、j--，继续比较。

循环正常结束说明全部对称，输出 YES。
另一等价写法是直接判断 `s == s[::-1]`（Python），但会额外占用 O(n) 空间；Java/C++ 中更推荐双指针写法。', 1, 3, '[1, 2, 3, 4]', 1, 1, 187, 331, 2, NULL);

-- =====================================================================
-- 种子数据 5：测试点（每题 10 条，seq_no 1..10，score 各 10 分，合计 100）
-- 前 2 条 kind=sample 且 is_public=1，其余 normal / edge / stress 隐藏
-- 期望输出由 Python 参考解法现算，input_size / output_size 为 utf8mb4 字节长度
-- =====================================================================
-- prob_test_case 幂等清理（uk_prob_seq 含 deleted_at，NULL 不参与唯一性判重，故先按题删除）
DELETE FROM `prob_test_case` WHERE `problem_id` IN (1001, 1002, 1003);

INSERT INTO `prob_test_case`
(`problem_id`, `seq_no`, `kind`, `input_text`, `output_text`, `score`, `is_public`,
 `input_size`, `output_size`)
VALUES
(1001, 1, 'sample', '8
-2 1 -3 4 -1 2 1 -5
', '6
', 10, 1, 22, 2),
(1001, 2, 'sample', '5
1 2 3 4 5
', '15
', 10, 1, 12, 3),
(1001, 3, 'normal', '6
5 -10 4 7 -3 2
', '11
', 10, 0, 17, 3),
(1001, 4, 'normal', '9
-1 2 3 -2 4 5 -1 1 1
', '13
', 10, 0, 23, 3),
(1001, 5, 'normal', '7
3 -1 4 -1 5 -9 2
', '10
', 10, 0, 19, 3),
(1001, 6, 'normal', '10
0 0 0 0 0 0 0 0 0 0
', '0
', 10, 0, 23, 2),
(1001, 7, 'edge', '1
-5
', '-5
', 10, 0, 5, 3),
(1001, 8, 'edge', '5
-3 -1 -7 -2 -9
', '-1
', 10, 0, 17, 3),
(1001, 9, 'stress', '20000
-909 -978 -391 960 -987 -787 807 952 929 -898 -215 469 832 -956 -637 833 49 -749 -926 -458
-430 52 -718 664 -458 -244 685 733 680 -315 -546 -450 -277 276 103 -322 523 -970 322 771
380 -683 -876 -608 420 -621 318 472 -235 601 994 -333 760 120 -303 374 -320 -112 911 182
487 -887 -68 554 -888 232 929 453 524 560 -189 654 383 -118 -965 684 544 186 -988 -162
482 -324 11 235 636 795 802 -660 925 -909 407 -302 -735 -934 -588 371 -727 -321 -823 -567
436 -485 -201 768 525 -642 861 282 54 691 -832 214 -174 108 -851 778 866 -207 -801 -308
-423 40 -778 -32 -930 -679 407 38 216 723 763 497 -125 -9 -43 507 -320 -598 -35 -789
162 -1 28 -369 881 -780 810 -375 895 -889 -596 541 -296 171 -695 741 -307 744 -106 425
-134 -558 -591 331 625 -226 52 -830 703 167 -360 465 -848 679 -35 558 -530 -385 -555 53
782 646 -547 514 -410 -954 663 637 585 -308 -138 -167 564 -194 -23 998 -465 558 -129 -932
990 -166 880 -221 456 697 173 791 643 -972 982 -408 389 -913 -662 802 -952 226 -367 -278
173 845 -223 -41 493 179 -735 682 -890 -463 -447 -548 654 930 -948 -120 -863 -501 89 -452
-269 681 -364 473 517 273 -989 702 958 180 -593 -857 910 -604 494 -811 375 -822 649 919
-919 -927 327 89 -214 -914 -645 75 -518 -308 -302 -421 624 195 672 -608 746 -817 -799 -131
-384 -582 139 526 1 -989 -753 434 392 -3 587 -25 111 412 -295 838 973 218 893 -586
729 -779 204 -464 562 -228 -783 -1 391 -523 -86 302 793 988 785 -323 -974 640 700 29
-147 -586 767 559 675 -844 747 -306 -817 -284 784 449 7 -948 -36 830 -717 -269 845 301
661 911 268 -704 -826 -800 227 494 -403 -99 706 -566 -757 -318 -79 99 -51 266 -955 908
875 -251 -258 314 926 245 -915 -482 461 -238 191 662 -950 -461 521 -92 -951 -14 -201 986
289 -124 -581 213 878 460 694 -276 -889 13 260 945 -605 -203 993 186 234 170 481 -5
60 -496 595 -932 -999 525 -777 -245 -712 -676 -604 -380 806 -231 -24 695 449 -174 -909 135
-642 -412 -795 670 11 590 871 -617 -767 -458 811 -569 391 379 -175 -879 -359 855 854 -641
-855 -812 344 -158 -340 530 605 232 118 -318 895 418 10 909 -753 592 -415 974 446 341
805 -223 482 22 -88 -909 289 811 269 761 992 836 -623 -669 468 273 745 280 364 66
-94 -930 153 -346 -255 -335 817 102 -412 -160 -27 -500 -1000 136 -768 709 123 -636 -640 -764
-774 -851 453 884 -372 837 -277 -772 -345 781 113 -799 994 703 444 -73 -42 -741 419 -433
-142 -851 -741 -760 711 -144 881 29 -346 106 -183 -512 252 940 335 522 -998 -725 388 -576
853 675 -737 731 -708 607 -700 -95 987 490 -864 -1 708 -82 689 -209 394 -799 438 202
-331 -710 275 -333 -771 363 -976 441 479 -625 -162 823 -57 171 223 388 470 381 353 -939
335 628 -585 -36 -146 -951 182 232 -774 21 -979 -124 -148 575 677 366 -922 -642 241 475
778 916 -890 324 74 -310 -106 175 -278 -380 -10 131 416 273 -142 229 -144 -863 -848 -37
-958 -591 724 939 724 318 -532 -871 -507 -145 -563 575 -49 341 636 793 901 447 487 633
-653 -750 -253 -450 639 -302 781 -941 -600 504 -763 -736 -236 -110 277 -379 926 -49 966 185
-469 -47 144 51 -54 2 753 -293 -871 -938 -658 616 417 832 82 906 227 911 -721 533
697 -559 410 496 -629 514 702 -996 -678 873 -293 243 -769 -338 595 -334 -340 269 413 -570
-762 64 -503 198 678 -996 833 553 446 -121 304 238 298 443 -514 -795 -725 737 -233 127
-688 859 266 -80 -195 21 66 218 145 -696 -4 -879 -447 -785 -563 77 772 -10 -806 102
444 -464 507 -731 685 156 -513 53 902 226 -549 882 216 807 870 -923 633 -245 878 124
651 -516 627 -45 360 786 918 377 97 170 423 7 922 -55 302 -539 -959 -577 63 721
-205 -55 957 986 -546 -462 924 873 390 -46 305 -235 -898 119 687 -238 427 -973 -363 -205
-258 -940 -69 934 -560 704 559 -484 807 -60 156 508 974 -133 -499 -812 -168 950 -13 -89
977 -950 -183 405 -80 766 0 308 817 -782 831 436 644 -758 -565 937 640 759 76 -913
164 946 709 -777 -709 35 882 901 220 -450 -804 -330 339 106 138 92 -988 -153 -775 -245
681 90 136 -731 424 164 5 774 -131 77 61 -948 -783 978 870 187 677 -209 913 -760
359 486 565 423 214 -783 -920 -921 198 -599 -604 -458 -601 -86 -923 932 -221 -942 -838 749
-912 -972 -758 131 192 89 751 -473 266 498 -319 -283 -818 -499 -851 -465 709 327 -11 218
-483 121 -934 -171 -510 592 -541 419 652 -835 243 -465 763 -418 -966 -807 -411 -13 -936 -910
647 -322 -217 388 -465 680 179 574 49 335 477 -179 647 895 -541 373 333 -334 654 -654
-596 612 -595 940 -315 -382 -360 34 33 584 572 -322 -795 742 412 398 596 120 494 -96
-660 167 100 473 -70 -449 -36 662 676 676 -127 -45 438 802 -656 -514 -52 813 309 -968
644 466 338 756 37 117 -774 -876 55 760 -631 -765 167 -698 232 377 -893 386 -279 -10
-910 475 -196 -105 41 -323 -332 -266 240 100 533 987 670 850 603 -378 465 318 1000 -942
-430 -158 -192 675 55 281 945 -332 -38 -201 -999 -917 870 986 373 -631 -309 665 -365 281
-60 815 -836 85 195 420 896 -588 586 961 931 -56 -912 -680 603 197 758 827 251 384
-327 -964 -45 740 835 18 852 -452 -178 840 -187 391 -790 346 -675 729 -839 -505 -19 -736
784 926 663 -750 -897 772 -7 -62 831 -253 803 -533 214 -812 598 415 -700 124 -241 469
846 0 903 838 -303 -969 889 419 -588 -974 -206 636 -129 -673 -799 426 -588 -816 65 553
-403 -166 -633 -566 -827 938 118 352 -730 -585 -359 485 281 -608 -50 -921 683 -579 -988 -53
-320 -532 488 -131 547 484 635 990 -910 -339 -602 -422 350 -971 535 741 -644 -73 676 723
-180 -774 111 -905 201 640 -671 707 888 582 -684 -297 -775 -75 688 -199 531 579 340 463
-755 723 13 -371 -476 563 666 775 -108 692 443 -37 -59 -206 395 -688 415 332 -745 132
-356 439 182 353 322 -632 784 -1000 150 -593 1000 176 -25 -293 -733 -764 -788 345 -683 -99
109 -820 -814 -416 -812 -102 679 -108 -508 -651 -468 -707 80 -829 -641 -827 -855 362 148 998
-100 -743 19 823 454 -960 703 735 -612 693 -313 719 134 671 393 902 -281 399 706 -548
631 627 433 -407 -7 523 -42 -128 -701 185 290 -981 -527 366 -534 -965 763 950 -512 -19
-248 78 15 429 -540 -322 242 -219 492 -405 -131 339 -622 -433 76 -348 -216 436 464 -427
-831 -979 752 138 -865 426 183 -738 -569 -683 -244 61 -203 914 320 793 -495 -305 265 392
87 591 -796 217 754 -768 -628 -676 -641 783 70 -27 518 -603 -934 -577 -279 -464 939 -589
-208 250 -280 296 -817 469 -825 819 -47 222 -671 750 -198 476 715 -801 242 -724 978 456
278 569 549 -674 277 -989 655 127 -720 611 612 -376 184 -387 334 -639 248 976 -152 635
455 378 1000 -308 -360 807 796 -238 -172 -202 35 479 -48 499 678 352 -988 -833 382 -368
-953 -457 868 -343 640 616 -139 703 -993 999 -363 960 614 705 72 919 -214 -523 -640 -448
609 -458 -935 773 -450 721 439 -321 175 575 -418 878 323 544 839 -543 -708 -575 366 -590
182 -459 378 731 417 -169 -976 340 -470 -243 -937 -634 637 66 -376 37 799 -330 810 844
524 -4 593 54 985 459 441 451 925 440 -87 234 421 -7 -594 928 211 699 543 -143
154 211 -306 -673 -52 -826 -138 -373 -473 419 850 -816 863 -956 -418 -278 57 150 193 -911
-932 -957 462 472 -431 391 580 -837 -208 381 -976 420 -55 884 272 756 -448 381 -193 -948
-522 -120 -895 125 -389 725 892 95 348 77 -331 136 -234 432 -423 777 -69 -84 -804 960
-751 -101 246 -567 888 750 855 969 -746 -482 244 -934 661 106 -452 546 608 948 -525 76
695 334 -852 853 670 -698 -193 -194 216 838 454 -331 -610 130 -863 669 -520 -659 792 -33
318 234 -377 -633 -212 -409 -798 -215 883 472 -531 -834 -970 33 830 -598 -580 -959 -818 -204
940 -854 946 587 -887 -92 -739 108 165 835 819 889 749 -140 173 -588 496 -576 -802 -587
968 -667 -242 -61 -46 927 -83 669 130 826 648 150 580 473 -93 529 334 886 -432 -227
538 -350 -729 -702 -520 720 592 339 70 226 -599 794 -307 886 435 667 492 754 -585 223
-786 -830 -472 132 -74 -564 -599 162 243 -362 514 901 -660 289 -859 -939 760 -279 608 606
-52 962 -476 443 -150 -285 -648 552 397 -167 -517 -339 969 -93 -914 -492 -439 -124 -167 196
-343 732 -636 -740 639 -970 -364 752 -922 -84 450 522 554 350 -565 -996 162 -44 -991 -874
-165 -22 9 255 205 646 -524 -118 829 134 -362 402 265 -886 -147 487 233 897 800 -430
-690 31 616 240 475 -966 -75 367 237 904 739 -695 213 364 681 -897 600 -779 498 239
802 874 -605 -104 274 -32 15 -349 -541 -880 574 -616 -174 -241 -865 792 -878 -928 -598 691
-458 837 -61 425 200 -542 523 -783 713 271 935 942 -518 927 -52 -962 -662 -324 845 -236
-469 584 403 -500 -806 66 -277 -914 448 -913 714 -833 -771 285 905 -344 -388 439 380 -933
148 309 235 331 -520 -768 620 690 -646 -125 -677 -357 -806 -788 -232 571 -3 -554 65 -392
494 -169 474 880 -141 780 -127 165 47 -447 386 -670 783 -202 -963 292 630 -176 431 -505
-43 76 221 723 38 -767 -601 -758 775 514 -512 -812 955 891 -426 184 -231 -551 -260 20
690 -988 737 648 615 -186 770 -566 187 -243 64 -440 -365 939 522 644 656 -963 474 18
330 -317 914 344 -954 558 -362 -327 -785 180 768 729 -281 -839 -610 -1 -871 561 -148 689
580 -418 -196 545 -923 611 -441 -482 33 844 902 511 -281 397 557 -667 210 -379 782 524
-793 436 -936 -728 -75 -707 -736 -335 -152 818 -887 374 -39 -635 -282 -656 -792 -437 699 -566
25 -297 184 536 -111 684 782 -997 161 487 281 -633 383 657 368 362 832 659 407 -441
-314 749 547 -392 -567 -941 247 -280 910 -176 -415 860 -801 -367 -784 815 270 875 -966 -97
932 376 -638 591 853 -600 349 -717 -119 987 452 408 82 111 -696 174 841 125 -369 -7
749 -661 741 176 382 662 818 397 -214 784 998 39 211 -139 -487 -503 -554 706 -860 -555
-216 -267 -244 290 -697 -575 945 -704 -660 925 -686 -124 150 -606 -377 -146 503 311 -584 -635
798 -836 -105 -700 250 -489 -730 779 465 -182 662 761 368 -162 859 -529 -560 249 857 475
772 -482 185 -971 -993 -821 602 -839 881 -312 742 672 -52 -920 817 -142 -113 -63 -14 -195
842 39 -775 163 -581 -81 723 754 -8 -127 264 396 67 -481 -271 -371 -94 308 -847 -510
820 652 681 -542 -398 -271 -260 -511 -404 -585 -412 -972 988 191 459 465 766 858 767 625
995 15 277 740 -973 26 -491 -760 -130 -475 428 -420 76 745 -587 -750 127 -491 -271 -997
381 -880 -446 -652 843 495 -298 -984 815 935 -224 363 557 892 -946 75 201 -100 -302 233
884 823 -230 -693 829 -779 864 344 -813 544 131 226 -367 -174 47 -142 478 895 258 390
-392 -994 -613 298 -857 9 -19 510 -891 -203 -918 687 987 -280 -324 -676 611 664 254 -143
776 135 -721 -862 591 654 510 -248 -987 -428 -261 -69 615 -351 -423 927 389 791 -656 -171
-498 88 275 382 479 -118 812 -739 600 -728 987 224 496 -576 178 258 720 64 -417 -392
-706 55 -83 487 -675 -819 -151 596 703 615 17 885 -174 632 535 -587 -884 362 -538 687
-532 524 -267 -377 -577 -843 -816 254 -774 589 -304 868 -569 -780 256 -329 659 782 -647 243
-536 361 682 547 992 351 163 486 -614 567 -482 -282 -947 888 -901 -128 370 844 -801 -93
-188 -386 -608 436 -790 806 -301 971 -873 -798 -463 239 321 -114 28 194 685 427 -85 946
-403 606 666 295 649 -273 56 -82 -526 -8 -488 245 149 -402 -831 287 -124 -773 -719 -519
-410 -684 106 -378 -793 246 304 -552 -145 -865 113 -267 -927 -749 -411 439 700 807 169 269
-936 451 408 -170 651 -579 980 724 569 546 451 -641 -354 916 184 -916 17 -285 -532 -446
338 -609 801 79 -423 -654 -416 -466 -498 936 229 994 170 734 680 937 165 -265 -170 304
-408 -389 158 369 665 -703 837 -613 585 -308 -417 -795 -587 -719 -564 -762 531 -544 288 646
970 771 -674 578 -689 -802 -207 445 416 -817 20 161 563 678 -868 633 -592 421 615 735
962 -9 536 127 -998 832 -243 954 -311 417 -293 399 677 -325 -734 -855 -149 377 792 553
-45 -661 797 72 -251 -571 13 -968 876 -357 601 -577 982 -507 -769 769 259 800 573 699
-753 -722 -557 -956 -529 182 868 -527 991 -12 -814 445 -88 378 -75 74 -844 899 195 690
459 318 784 305 -818 -222 548 826 -824 210 -431 -893 346 -922 325 955 -972 620 -488 -203
787 613 314 305 -855 937 -542 979 524 -677 716 -416 -568 936 -452 -659 -481 476 350 319
-226 654 -270 825 235 543 -531 -98 -149 -910 -636 900 994 -453 -898 -839 895 -107 483 -481
132 389 -329 -176 797 -182 -276 -207 327 -746 -623 837 -141 -93 687 -314 391 314 -760 483
-678 271 539 555 120 -361 -436 989 208 -668 -143 -814 408 -142 141 -534 744 -168 -441 -217
-943 -50 227 119 336 -417 674 -945 -673 999 -402 -644 -896 -160 885 788 832 338 861 294
357 255 663 -275 304 -535 272 -552 425 -203 676 137 -121 518 -475 899 -651 257 -722 -419
-863 -827 5 783 -720 683 -894 -207 -413 -290 -805 594 532 173 114 186 -717 -878 -102 -852
123 -204 -86 281 274 -562 797 -976 950 181 -486 -32 -558 -352 -96 -681 683 910 326 553
-514 -256 360 705 719 -50 -444 565 -902 347 -139 -825 105 -622 -717 -173 47 -822 -263 308
474 -989 900 -388 -819 -903 605 902 -775 748 387 643 847 -69 890 -125 -797 -521 -19 -437
-36 132 -974 -439 328 -376 191 104 -718 735 -16 -21 804 -591 106 532 755 -815 -741 -187
-85 658 515 -621 -751 -91 -513 989 -131 -660 -211 -965 -453 369 -205 285 -295 -624 394 -350
-434 227 585 222 -418 -602 877 558 758 345 -754 22 56 -170 226 822 526 -326 -5 74
88 250 389 -489 433 850 195 380 798 -879 -420 -926 -301 35 -667 802 222 -865 543 -82
294 168 156 -458 604 -583 -320 151 437 -443 -128 -595 -996 -925 -114 180 677 498 -717 272
736 238 -283 502 693 -109 480 789 -441 86 -1 -588 -80 -381 -184 -179 -53 -384 -753 -334
-968 759 610 265 808 480 101 -841 687 983 550 -4 850 13 287 645 218 -799 -334 271
989 -890 30 357 439 836 126 626 111 -302 500 322 431 105 475 218 936 -448 673 963
571 -456 103 -995 -502 720 -992 -214 -372 23 935 -939 -686 -811 -404 756 -655 -611 -255 928
-393 -467 -21 875 -992 840 938 580 -866 700 502 -399 89 929 922 74 524 648 -457 390
-293 -258 -609 -170 650 -376 431 -1 -683 967 -549 -374 -402 645 -917 -987 432 556 -916 66
-811 61 507 726 -718 412 -673 706 -724 -853 -306 646 769 127 858 -358 -366 -119 366 -370
282 -466 -646 -733 -47 989 -828 -452 30 598 -326 846 917 -196 -966 919 -527 -678 978 891
968 953 667 927 431 195 138 739 286 -394 -10 947 929 697 896 -295 -959 -960 332 -283
-396 974 -362 94 -736 596 -561 779 13 -363 -407 -506 101 297 -455 -807 -340 996 591 2
917 -846 934 -754 127 -352 369 -547 263 927 877 554 531 432 461 -161 642 154 -375 -620
-60 109 -644 433 267 129 -354 -36 203 -731 -685 -613 -647 -209 539 -896 -206 474 -393 13
673 -317 -326 754 14 -548 973 -617 -104 -267 928 661 -316 304 461 869 -668 828 74 998
332 755 -566 243 -700 -759 526 266 505 129 -317 235 662 -643 984 -38 299 582 335 894
-627 -938 958 887 54 -33 889 -683 49 165 -644 840 -740 944 403 -778 -687 407 725 -207
-974 374 114 -323 -121 75 -975 -902 566 -772 723 77 -149 -70 -554 -27 115 -279 213 -737
731 -686 -321 923 -64 446 -607 -36 438 -276 -470 270 55 704 344 439 -145 -663 149 -417
-926 805 901 9 -667 389 667 0 -723 25 264 746 -764 286 72 730 -448 -107 -882 941
-135 -977 279 -320 854 -24 699 655 -945 -268 -566 -617 99 26 -978 -19 -253 -939 -149 -154
872 592 -427 -318 -769 765 52 768 910 -19 -536 -59 -915 -816 317 988 -685 -101 708 -704
316 -51 -467 965 -728 265 -964 -749 -283 793 -150 88 -336 -913 -729 885 -910 -597 957 322
-373 -61 -274 781 126 -294 134 -612 849 571 716 -864 -899 242 -978 899 -96 -803 -1 933
251 -781 -568 150 658 -167 -574 802 -497 191 39 -163 928 -297 -580 924 -873 -609 -659 562
-25 879 104 -626 591 550 -542 -11 -766 204 -82 68 372 -540 -420 997 637 981 59 -103
684 556 701 -558 -984 -16 -795 577 -246 851 -624 -771 -374 976 542 717 387 -581 312 883
-259 399 -920 -609 821 -915 64 562 -343 -733 -331 174 -3 34 -978 -39 -837 -329 384 337
686 -915 638 171 716 -823 435 587 454 17 413 63 582 -417 -74 -290 -192 -448 894 -466
769 -679 376 358 99 -409 161 106 -383 -118 1 242 -284 -833 506 -595 -214 706 -305 -821
-298 -42 37 -405 -538 -213 674 -884 562 -910 -619 -309 -931 -126 -129 748 17 -477 -69 582
-354 -719 255 821 256 304 -233 918 416 -494 103 203 165 -598 -126 653 -183 -607 145 608
990 112 -199 212 0 684 446 953 -79 -863 -448 650 737 977 -365 -228 -37 -237 -346 807
918 134 -904 -154 145 938 -4 252 -56 19 -785 -568 -689 52 -877 351 616 221 -870 952
888 759 339 164 752 501 649 909 920 78 287 -54 39 465 -55 449 -556 -348 180 739
243 540 -865 -590 -183 -170 -989 703 671 311 575 -460 -993 -588 234 144 253 -68 629 633
-165 881 780 -356 -714 121 629 897 451 -667 444 -945 181 -376 -448 788 -915 -438 930 414
-6 424 603 -807 -663 203 437 61 -38 320 -535 -606 514 -64 -327 461 513 184 -393 -111
-107 -148 -984 -814 821 308 -51 36 620 -382 175 829 -772 -383 292 -952 -958 540 -675 145
940 143 399 648 238 683 -652 971 -878 259 -531 -677 955 80 269 -651 165 -482 862 -524
461 341 990 -931 194 471 804 660 -621 -785 -304 409 117 -751 644 856 -820 776 839 626
-639 -592 -887 -634 -810 -775 692 299 -282 816 392 260 470 845 910 512 67 -794 -64 187
416 483 -747 -412 -494 118 -346 -116 -773 -282 -2 264 723 187 125 -301 -508 -232 155 563
-308 383 150 692 -371 -795 86 930 -856 756 -410 -868 727 -405 -448 360 -455 509 -639 -592
378 -416 554 -525 -239 -245 533 170 362 826 -146 -163 -38 959 886 -302 -458 -177 -386 151
-397 -747 -757 681 -899 -521 312 -953 604 -930 849 -129 -937 784 -261 709 557 712 158 842
-538 45 -421 434 458 585 -735 171 -956 42 -995 570 521 -281 475 -53 -654 -793 54 246
-143 -84 540 -982 401 43 -990 167 -626 -416 -2 -304 -284 -110 118 -411 668 781 -310 -585
-984 241 312 399 808 -730 501 -927 582 -970 270 -131 279 282 750 -937 765 927 548 -91
-698 749 864 -735 540 289 -546 716 -281 -917 -762 656 195 441 199 -783 -110 721 -695 -308
-1000 -960 800 958 160 530 732 82 -132 401 42 841 377 751 947 483 518 -534 126 -751
-373 665 -182 -837 -635 844 -143 -139 -419 -475 -584 -184 92 -109 194 -810 412 -200 -842 -345
865 27 -470 703 412 -83 -276 31 -464 -843 -943 -154 381 -867 -994 -444 -733 -594 835 -587
570 13 -654 441 368 289 -742 222 -708 -608 -530 221 212 408 -177 581 484 824 897 50
282 473 -63 611 993 -50 -47 355 532 89 572 -921 428 823 -370 57 384 -298 554 153
-356 -724 698 675 -788 -93 -641 -222 327 667 -672 902 -689 -141 948 -76 739 -139 127 463
-937 -880 837 682 695 -530 -518 -167 -323 -302 -866 -172 762 -580 -955 357 530 -518 -372 387
-618 797 -728 551 162 660 719 -850 -419 670 744 -981 -797 -459 632 -893 650 -158 -23 497
-24 355 222 795 -410 181 615 -649 652 -791 -74 442 -29 587 890 801 682 -348 255 78
-220 -932 650 889 -936 387 572 928 -488 -220 121 744 795 -845 137 -237 550 -558 980 -681
88 -42 968 -830 687 -664 822 8 81 -425 -415 -720 225 -856 -747 727 -89 -585 292 -151
593 -359 643 -516 -174 303 168 103 939 -387 -701 515 404 228 422 348 -141 430 -897 443
982 276 118 -939 -72 -183 965 -749 -117 -188 -297 -752 209 46 -812 -790 106 826 335 -49
-278 827 -403 -50 -524 626 -324 339 -472 -125 -941 651 -724 870 833 -777 -586 911 -509 613
-761 71 742 293 370 793 51 754 447 -170 -8 285 272 739 -794 -766 -879 828 -189 -425
106 -730 -838 -312 404 667 863 519 297 -552 961 -662 -953 897 447 -9 -605 -734 2 -125
485 555 -195 -416 161 272 144 -231 323 -979 782 359 570 -146 -869 940 -981 -638 -752 -173
875 -697 -422 -295 492 -284 907 650 -61 -644 -789 -56 644 236 708 387 -535 -712 -318 349
713 545 -208 317 581 -389 -691 -441 82 -604 616 116 -248 40 -384 -709 -638 788 -651 -749
731 -775 375 -431 499 -466 -611 458 -815 -780 -50 180 -180 988 -464 -932 -533 399 -564 660
900 400 -907 -894 436 917 493 -652 -551 -170 -635 430 235 -555 114 -184 786 -207 -802 976
-260 -975 102 -644 -763 837 398 204 588 -941 -267 -893 -117 530 523 -921 889 267 -402 371
-377 284 818 285 -49 675 -577 -283 467 -152 945 -533 934 356 80 814 56 166 683 -11
37 794 28 959 -469 990 -278 470 -957 616 602 986 656 -603 393 -479 -695 -97 175 57
325 467 503 450 -400 809 -833 -569 -794 614 804 904 -33 -65 448 -977 421 -942 -290 223
-821 656 35 -157 -279 817 -281 -166 127 -893 246 241 -239 -333 480 -248 -415 -499 -367 631
-558 -753 -664 -180 534 -852 637 -934 -321 756 572 230 -767 617 611 347 -96 317 844 46
-373 -697 347 -186 879 -37 714 887 305 407 32 -978 880 -118 268 -419 -858 -387 -867 538
-552 108 158 831 990 111 420 425 -917 -357 -944 -74 -890 404 131 -324 91 -937 -572 526
501 933 384 793 -532 352 -73 -544 -268 -828 -307 278 -108 565 246 193 -563 -35 -166 261
-963 443 500 -645 585 318 149 355 881 747 -347 917 695 176 -841 -40 -727 -54 -700 -309
84 725 620 665 329 -997 -73 83 -310 -102 806 81 817 -456 -543 -890 907 -465 850 -239
-320 -352 -932 673 492 -882 209 -420 509 -768 -973 608 594 -606 977 -416 630 -670 323 -111
543 206 -723 -915 87 737 366 40 357 826 -602 -859 -141 830 -656 -817 -186 190 -43 269
-382 -978 208 62 931 176 -16 -283 -975 -936 293 882 975 163 -828 91 -767 -153 -961 174
85 269 976 -270 404 -772 -616 -315 -605 -566 451 -927 -413 696 -223 775 89 164 655 441
-582 651 -464 -212 326 -537 134 -909 -512 88 -292 -132 -58 119 -259 746 983 983 297 259
617 -975 785 -72 -421 -257 -333 -154 -510 -270 -161 65 -955 770 677 513 516 862 925 -145
-975 309 969 -384 -50 -276 123 104 -427 959 -953 -651 -8 530 350 440 235 698 836 203
-903 984 -594 -787 731 489 -150 -612 -313 -274 -285 8 -530 -186 -654 -343 -863 -556 716 299
798 972 985 -610 -879 984 162 526 -380 405 595 985 896 -861 -464 814 -659 -466 -308 -489
86 165 -540 -728 -703 -423 56 -898 -76 -356 699 415 119 464 -303 -618 -419 -243 256 -982
722 43 -311 -204 -483 -402 -185 394 975 771 -614 -834 935 -678 -84 -185 209 969 233 -179
-389 -588 522 -12 712 -603 363 -628 -95 -177 -8 300 -348 -990 302 -590 634 -601 981 -419
-634 -791 -454 -92 -971 -184 0 242 -443 -782 683 133 -203 676 558 772 63 -307 -470 3
57 -24 722 894 524 915 -465 -530 -588 201 777 77 457 -742 -713 653 -885 716 117 -226
-329 366 -684 -882 -963 709 -492 336 109 484 -735 -923 -882 -304 165 -158 627 775 -946 124
-175 -235 984 157 316 395 339 650 -196 558 -534 -871 502 478 -662 -251 933 6 981 297
-184 304 305 470 7 -262 525 -303 778 -209 27 977 935 -400 798 399 -17 416 461 494
-526 -844 -48 -945 687 -780 -455 -279 882 95 345 -702 176 -933 7 683 2 376 -959 -781
-255 343 -756 -950 889 -508 146 -336 615 995 225 -937 -168 141 956 -387 655 -358 34 218
-297 967 847 605 657 -181 -854 -817 -285 147 -513 952 -540 724 133 952 445 650 -246 -496
-355 -281 659 564 -707 199 392 724 -937 247 266 -409 450 700 -367 -896 313 -666 -601 867
-301 -682 356 507 412 -337 104 126 204 -652 337 194 135 492 824 110 620 -693 -513 231
-798 -903 233 883 -333 -138 -602 -675 806 -275 -779 -456 964 -978 -677 197 -801 661 578 -849
68 -871 -642 -334 -307 -563 538 -428 -136 547 -366 -916 95 -106 -375 -73 306 14 -287 -356
-187 729 736 -786 -412 669 -569 -817 331 -402 -432 262 -791 -681 103 -280 -428 -647 -396 497
478 -482 -297 -177 472 90 662 614 -989 769 543 952 -163 -776 -726 -110 -122 -298 935 -693
-993 -711 176 42 546 -660 65 -56 704 -918 -255 -308 -707 337 888 -545 -953 492 -95 -608
-461 982 798 -284 -859 704 -630 53 947 301 330 -892 730 520 -365 452 25 -935 -742 201
654 -279 -43 -575 362 -934 -618 639 492 -396 -322 218 615 -692 -554 -799 -790 701 958 69
-257 -971 -723 -635 561 456 800 448 3 895 -591 -729 -679 -116 568 -166 806 500 450 660
693 -162 -535 -344 460 670 267 720 30 -352 716 -468 824 893 -924 366 358 -888 852 449
-996 -786 288 -498 990 -387 -447 -347 -911 -840 797 646 -727 -568 -553 854 908 -91 -10 -787
-141 -742 -108 270 890 551 480 -591 -366 -600 -113 -254 707 -563 -632 951 -593 629 268 687
401 865 791 650 81 220 -634 824 221 953 -123 246 166 93 -886 -754 -361 417 474 37
-660 -234 -536 179 -158 676 260 -930 -970 -519 243 -383 149 -133 -70 333 -584 120 -331 -192
-837 585 829 39 -328 -683 790 -516 939 -607 447 -357 778 25 -512 696 748 101 268 560
-175 477 -510 611 -442 495 -888 698 -503 693 556 -68 -689 -736 -636 961 -854 -572 546 -866
192 451 -263 807 96 862 440 10 13 869 -416 -204 -561 -776 -962 737 -566 -861 925 -601
-353 400 431 -610 910 658 846 226 449 36 720 741 -176 732 56 -294 -189 -708 492 -414
334 342 762 385 122 -169 -298 588 51 -768 965 -985 -652 345 162 835 309 499 -482 33
-277 -621 475 71 821 607 -576 172 -668 587 857 523 -869 308 169 844 -209 -766 -498 -295
542 567 -689 -951 648 -687 -507 -168 -916 -235 329 -42 463 -555 113 865 -643 5 3 649
-40 528 279 -943 -465 -154 805 865 647 381 719 857 796 -100 977 425 225 -436 -260 48
-43 -711 -289 509 701 -995 -120 86 29 66 -685 -244 -257 -914 -836 786 -51 -645 -831 915
309 -960 -625 870 -555 -948 -872 565 748 -644 -536 724 -136 -483 -396 257 -425 -189 -737 -60
-842 -492 293 -89 58 -65 -758 -819 411 179 876 650 563 -674 -169 -953 -151 -619 -375 989
826 -230 855 396 875 237 979 374 -12 -25 369 266 -669 -123 954 839 -728 224 793 -213
-430 404 -226 -716 619 744 -486 -739 335 -372 231 -68 4 -730 -938 742 -941 660 -901 343
567 -848 -950 -58 352 -147 840 615 438 167 400 106 -216 -903 -314 -581 -555 -812 790 -533
843 -331 756 -693 960 -348 -315 -895 -935 -146 -815 -724 640 -837 -414 711 290 -675 611 191
774 664 100 125 -365 389 -693 -946 -91 -884 355 -816 913 275 -415 112 259 494 -970 -478
227 911 -601 -84 171 -513 200 -267 -379 95 -504 117 -972 15 515 227 -900 74 -486 -372
269 -913 -580 330 434 292 -683 340 937 843 -210 -587 872 763 297 502 -109 -881 -260 -271
295 -938 120 392 -37 -310 -381 -646 -915 -367 -588 -220 -380 919 -91 168 -218 800 -724 385
742 920 128 -318 891 5 255 321 198 604 -639 386 269 33 -404 39 577 -920 -741 -337
-373 -374 -276 683 901 -851 230 -709 113 707 -52 588 -756 421 497 -813 972 -957 -762 480
-242 -930 972 588 746 361 679 -22 484 1000 762 -674 -349 98 -612 -73 837 -225 -526 -314
-748 -709 576 -199 362 822 -289 543 -888 -244 -316 880 -254 77 -670 -198 -283 371 59 -109
532 93 -269 187 -873 -208 -117 571 -546 -450 -905 -623 -432 442 -321 -415 -431 -159 708 571
-530 -169 845 439 -384 -235 -48 -394 -821 594 175 -931 -737 996 293 986 -111 -808 -540 -201
-932 849 145 16 106 -541 606 495 822 10 223 118 632 434 930 -496 -900 524 916 -103
476 419 5 -817 -825 -980 -919 797 -925 912 -367 -586 -89 -909 -51 -16 -853 -401 -608 69
-526 -893 768 -339 -897 1 431 -332 -799 -859 -582 -55 -423 615 -799 -498 -149 -638 384 -536
-129 414 -932 -148 -636 88 553 -217 802 -877 873 559 817 -816 655 662 -672 127 687 -505
-625 -769 -160 637 -416 87 324 -385 -237 223 -773 -817 -197 -208 203 -441 -963 -139 531 -334
561 -135 -126 -668 640 712 -728 -82 23 -929 294 171 -279 303 -242 -60 -327 -408 975 163
163 -951 -41 -162 -98 -54 -250 186 450 886 371 -846 528 519 753 924 -534 -614 -113 932
-637 856 504 965 285 -136 417 -225 -539 -790 165 355 39 978 -147 -864 -402 -301 -113 440
304 -983 -400 843 -914 -193 -793 -893 855 -844 597 -136 380 -603 -730 759 -597 902 330 871
-205 537 -121 -834 -485 634 -35 84 10 184 -913 -749 760 7 373 -699 -732 -353 -743 589
-177 795 127 932 913 582 424 -510 -975 792 -901 530 -843 -942 174 -867 -483 236 -170 -867
-546 -19 -110 486 -23 -538 -732 -566 146 470 -454 -278 -45 -872 -828 -922 842 -435 -838 649
-69 -213 96 -869 894 711 -753 -962 970 629 563 -295 652 -356 567 89 -754 -853 -106 169
177 -566 -161 -287 14 878 755 76 44 -852 -976 -899 -981 169 197 -324 973 -838 629 -742
-781 -885 793 664 -224 303 35 -93 737 0 353 213 -470 -456 678 -585 -157 943 267 -49
428 -415 52 967 -852 -630 -169 -390 613 -793 801 510 122 -680 -997 389 469 4 -736 -402
-614 931 840 586 621 703 -656 -93 -308 -70 -252 -291 -840 -376 -749 806 -9 -722 293 750
-941 927 -865 384 -657 -632 -975 -750 -914 830 161 -291 -347 933 -164 861 -236 438 632 -459
-34 -352 259 -762 -470 706 -299 -718 -315 134 257 -672 587 777 -668 151 1 -891 -730 116
276 -536 -950 313 565 -132 993 74 -207 -676 539 86 375 699 182 -337 434 554 720 216
-171 -452 -753 676 -869 738 -824 454 187 -884 921 -479 299 444 -750 206 964 482 -743 -486
-499 573 -266 -63 551 -569 -552 26 499 653 127 451 -786 -375 -829 210 -641 -824 185 -791
-708 189 -686 453 -512 835 483 353 610 -903 31 -935 -6 627 -9 -416 -15 -840 173 982
-140 -914 409 -485 31 -494 -461 -667 687 935 -498 322 -822 -290 -879 495 -469 -722 547 -473
210 -11 -189 -415 921 966 129 -958 100 -686 413 -67 844 -260 -687 -840 714 -303 287 555
-751 46 -919 -231 80 -255 -884 -918 544 29 -807 458 -577 855 5 584 -58 -851 484 -999
809 53 585 -168 -260 -120 -511 -291 -593 -189 262 -490 -692 263 215 432 614 944 -84 -349
-948 163 -351 -995 -287 -354 542 999 184 -198 -733 998 -842 35 -695 540 -355 684 362 -131
424 -244 110 553 -107 -989 -467 448 497 -578 -427 -227 533 -444 -545 294 284 -631 -33 596
-678 -967 -515 304 -308 -230 -514 -263 -644 347 -361 271 178 423 147 -431 -80 277 533 626
945 -2 -954 500 339 193 -144 270 850 -90 828 786 234 213 -725 -806 -376 869 -716 550
-54 -260 -406 64 437 174 162 737 679 -237 664 -907 -465 -794 611 92 -983 -848 -413 934
707 -612 -50 355 -977 -27 -380 -95 729 283 154 689 828 -117 -372 540 -956 -122 469 498
98 407 -587 -503 -992 -352 960 131 741 -111 -29 -813 -743 -505 405 292 717 917 -742 354
876 613 794 666 -983 -372 118 -342 761 823 103 450 -1 -122 147 931 210 172 -928 -542
-501 -591 -540 -764 131 863 164 -686 -751 850 -915 957 204 -122 -657 986 -342 -662 -234 796
101 167 276 -423 262 532 -211 -840 -924 352 233 -866 681 981 636 772 304 287 143 -68
726 -260 501 -836 -890 -970 -228 -473 -544 302 -595 -860 570 -701 -897 284 -863 460 -307 272
171 -247 852 464 -206 345 916 872 -348 726 955 -349 768 403 595 -843 -192 -293 -297 -151
-961 408 594 341 -324 -421 -629 -659 908 -857 -739 -889 662 -355 -264 -641 -456 -643 -454 -839
-103 -837 -972 -547 851 713 -884 -681 -607 -716 -572 437 409 -423 506 -379 112 -892 -92 -485
-155 885 326 -628 844 -595 290 -635 817 -470 -959 95 -76 -663 173 765 568 -311 -305 -967
-15 -765 -154 583 686 -361 -629 490 -163 434 898 256 730 -874 93 -879 -114 32 -469 299
804 662 -264 930 -34 825 -963 -284 -508 -735 -405 437 -80 119 -17 42 568 539 -330 753
39 869 -373 230 463 -408 -819 791 -327 -93 509 -18 -86 75 452 -706 520 326 -296 993
329 -777 -570 -227 989 -704 -964 -425 -549 692 -875 -118 -752 646 -174 872 231 785 815 -371
315 534 -76 -40 954 -131 975 -503 455 -977 -685 -921 98 825 245 -447 308 -659 -655 243
-473 -560 -146 944 53 423 753 -885 516 -264 -180 -716 -158 632 567 739 -427 -733 327 -181
390 -804 -978 974 -324 547 242 -461 845 985 528 -907 -521 -194 974 -780 -685 -613 992 -322
774 -181 -496 969 -895 -540 701 -215 321 -918 382 819 -427 -558 512 245 -43 -129 94 645
-880 -956 18 385 -764 -44 -539 450 -552 739 187 431 803 -830 867 204 -114 828 -879 608
-398 -751 -916 -78 346 -968 -340 -799 720 -53 -318 705 437 692 117 -861 -819 -789 -674 -837
-493 286 665 -622 -349 40 -150 687 -634 483 -22 -519 -528 -704 -643 -47 -121 -226 891 760
674 -592 679 -741 -400 -221 2 -424 960 732 26 -454 -189 298 -851 937 -225 657 -259 -923
337 -486 229 -339 971 -67 -260 28 748 -19 572 812 -507 -677 -909 271 -192 210 322 -674
74 -384 2 351 -410 75 667 617 276 -425 265 63 -878 28 -725 524 -955 621 -877 159
-553 695 600 -331 -861 -896 -549 280 -891 797 -964 21 622 -5 -563 -857 -735 927 297 786
-851 44 349 -30 833 895 -899 -465 259 876 583 -650 276 94 -766 -346 968 -88 -381 698
2 -181 -171 -358 811 381 -993 354 438 -609 -114 -335 -370 -120 741 -443 226 502 -566 -649
-697 -469 978 -311 345 -15 262 -315 114 177 -457 -325 -38 443 -449 -504 -830 483 -731 -435
-321 -404 201 -87 -333 -771 966 793 -138 204 -641 -510 -239 929 420 -896 491 744 606 518
-460 -660 -902 287 -3 -22 -447 63 -296 156 -916 -681 -855 696 -274 154 298 -472 231 -733
-271 -999 -228 175 785 126 938 487 200 -6 -80 -404 842 449 -763 -28 840 -831 506 -766
60 613 37 -49 -229 539 -730 -4 -711 29 -599 -500 -949 755 -675 217 -202 -615 -419 -419
284 136 -439 -473 972 809 -761 -512 -733 936 -267 -648 838 58 160 -873 670 -255 553 967
-134 413 -117 416 112 -920 235 -388 148 -185 969 340 385 -446 -279 703 333 -736 724 -725
538 -964 373 760 662 -765 -153 549 -833 934 -114 -692 -378 187 -799 785 -967 -286 696 -204
-343 958 508 -15 209 873 799 278 884 -852 683 579 441 -699 408 -626 533 745 262 723
-744 590 -972 482 -484 -371 908 -180 221 -136 -747 476 472 -656 -777 736 775 -896 -698 535
-539 -36 -395 -735 80 -623 -390 -380 -270 792 -984 610 -407 -206 467 100 -703 -288 -709 -496
655 -75 -282 51 158 969 -773 786 937 -841 543 -390 298 -938 534 -291 -978 681 102 595
-688 -795 193 293 -378 334 350 764 186 856 -278 -669 -104 796 843 -843 506 553 245 481
-763 -741 -327 -428 575 -107 892 528 -8 230 -809 -384 377 369 765 -280 -566 -7 290 -92
-590 815 256 -396 700 -291 887 -670 -299 563 -880 -315 99 -884 546 -721 -655 560 313 986
-523 -130 -679 168 -580 726 587 -907 569 -358 813 890 -607 329 529 209 227 -281 740 -204
-337 206 -945 -884 198 -121 -487 468 338 581 -640 194 -202 -722 -604 -32 -799 283 668 491
-739 -531 4 -921 -573 692 -379 -292 964 -686 273 -518 -774 -253 205 672 -209 230 838 43
-8 -293 424 -250 678 -35 -641 667 -90 620 747 -470 459 670 -6 -659 -585 -325 212 -622
-123 318 114 775 -359 983 802 -321 -598 467 896 617 -283 990 -545 387 -713 -483 863 766
-597 -782 -336 -660 -543 20 257 -756 -117 17 -472 -905 937 -318 -80 -34 -643 628 884 389
192 488 -127 733 226 714 418 407 -211 -600 -99 462 380 732 93 -992 -540 -49 -277 256
929 678 -845 460 -374 -685 -718 48 675 -930 -333 438 504 577 898 598 -742 -455 -205 -418
357 82 965 974 -479 -288 730 -909 -300 -94 -287 65 303 462 -925 -245 -132 637 -650 138
-439 550 -645 935 -45 758 20 -829 173 -50 -81 -423 781 -179 -912 -363 -487 -941 -391 309
710 909 -333 -289 453 86 -98 -510 140 150 -184 -652 -750 -927 -345 380 -505 -812 960 -514
715 220 443 -800 -185 60 118 110 -678 216 -903 441 -65 459 -733 -586 -85 189 199 925
-717 473 -671 980 298 283 -245 -692 53 37 -139 228 152 666 815 -572 780 -93 156 -702
751 118 523 -893 -87 -602 13 987 -412 71 403 -659 911 -611 997 205 113 163 -993 -969
-142 215 220 -532 685 -597 661 -924 60 -702 -350 -225 872 709 375 550 935 -308 -304 899
344 292 586 820 101 -828 39 -354 -718 -964 -24 -685 254 886 -441 6 93 -431 947 -918
71 -985 -126 -659 218 427 -447 880 413 713 -517 -536 -215 781 106 -445 -428 -198 -756 692
-930 -91 -777 -188 -531 -155 -958 756 -660 -386 595 786 -633 -758 113 922 414 -564 245 162
-594 -460 -534 285 772 474 -476 -890 839 -191 -625 572 964 -559 -44 114 -419 628 421 -154
454 916 -868 201 -699 -346 -770 -284 615 171 -538 -221 -573 660 -483 516 -44 -718 848 264
-702 -675 -21 331 417 692 -146 -436 57 111 176 -969 -217 -544 871 483 -448 -393 418 585
-537 -203 501 826 -313 -322 -836 78 277 -671 -446 -142 -633 938 945 491 918 902 -138 143
-696 179 202 53 479 -44 123 -672 605 -194 484 -890 -624 309 -645 329 -286 -280 -439 412
-42 -370 -401 -296 -875 -359 4 619 291 176 871 557 -818 -976 -890 469 -539 506 209 757
659 47 -607 -520 -138 -228 -486 -402 83 -171 783 -112 653 -275 106 -74 -498 207 -263 -450
147 775 -196 345 918 773 770 660 -503 730 299 60 -284 407 -351 -284 -737 -474 -835 773
-665 693 228 51 -650 -968 926 -698 -129 196 -491 830 741 -11 -964 201 -446 -915 427 774
-401 -319 289 -84 71 -632 -969 -507 654 -753 -417 897 -855 -881 -171 660 -88 -838 883 -294
839 -636 545 853 -93 -825 438 951 -237 686 -56 -333 -323 127 -601 212 -531 -229 -427 369
-224 356 563 -113 473 -258 -856 173 989 582 -520 -738 -794 839 350 184 -297 -264 507 844
-484 -920 -794 -7 567 -51 88 258 -646 -380 968 619 -509 -838 993 -72 333 -852 -970 -276
-203 147 481 570 378 180 641 783 792 -722 470 -773 -782 551 332 837 285 -116 -266 -230
-776 -175 -965 534 -61 531 485 731 -87 -519 971 -801 -268 -835 956 577 -23 500 -205 311
745 -448 170 -115 864 663 753 322 245 -846 -292 -859 -583 153 -37 947 589 993 -369 -754
-187 345 -836 638 965 429 156 969 -904 -492 48 607 61 941 -955 -288 -522 744 -885 -639
120 -688 -921 699 -87 -85 -638 727 -427 -115 808 -465 892 796 852 123 -765 -885 668 201
208 -410 -459 -708 425 908 -513 -488 -1000 -228 -507 -250 -823 238 -81 -322 -808 -780 908 -700
567 357 518 -600 75 -563 -775 -769 544 -629 88 929 56 -239 -577 -848 512 187 665 991
418 500 126 -808 -374 -794 443 321 716 965 17 710 -897 709 202 -819 -423 -778 -600 -359
-703 765 441 873 -642 24 653 -100 -712 686 542 -71 104 -164 736 -606 914 -903 -633 -240
-884 32 -891 -335 -42 -609 -366 584 -53 926 -208 267 319 -685 438 870 12 323 -767 453
-456 -782 233 -946 454 965 -601 453 858 763 889 -294 -601 -56 -681 180 595 639 -733 -825
-623 851 -825 748 865 646 -669 -744 -410 215 832 672 -173 28 -876 9 433 484 388 -741
33 -934 518 -135 572 51 217 -733 656 -620 574 -470 34 37 -150 -465 965 -17 -551 909
914 -718 941 -75 -346 261 -451 461 -935 -667 -419 246 914 -710 -668 522 372 403 -991 -674
-803 -323 -295 -401 -741 361 392 -647 -976 -394 -196 725 739 -734 -260 -272 -807 313 -915 655
965 959 -160 443 -53 -232 476 585 160 380 -492 -784 -485 226 -318 -64 -734 742 -792 383
-725 -396 185 459 -199 289 -459 759 -141 773 -844 980 733 852 -911 416 977 -909 -690 464
173 971 -647 -367 533 385 -802 -543 -834 760 -889 -861 -449 75 -161 990 589 -892 -74 -452
-496 199 -364 -644 -230 468 62 -218 471 -91 88 -304 -151 -854 488 -123 219 660 511 -565
616 74 -419 -879 -859 -336 588 730 -187 -199 684 -721 -922 -40 -588 260 495 -132 994 -176
-167 558 -149 -990 531 302 -898 -981 639 407 463 658 934 -790 553 94 919 628 392 616
619 -994 -730 924 -5 -491 -263 -845 693 -165 -446 692 -99 322 805 -372 -29 -815 838 501
304 166 -250 642 195 652 -348 989 -553 410 -987 89 -954 308 -439 139 -576 534 352 736
944 -621 -456 -356 80 352 776 -281 106 243 364 -757 -743 -394 603 -237 -789 -436 -960 -460
-361 438 -162 -60 188 281 -986 345 445 998 204 -222 -732 -197 -688 -218 -531 361 704 -237
-295 245 852 295 -716 914 -520 989 419 148 422 -102 -550 -134 351 8 -338 -956 -606 267
622 131 827 51 -76 909 -154 -395 -755 106 255 184 119 500 -961 -868 192 -104 989 -987
364 -799 -501 -388 956 772 -941 401 -346 54 273 987 689 -925 925 243 -681 240 889 -776
622 444 828 845 -792 499 -817 -323 -153 238 135 -872 -851 410 884 -360 634 687 648 312
563 378 883 844 -107 -458 22 -192 879 212 -328 -638 -765 231 -847 -727 -915 -238 -1000 911
264 -342 -503 464 903 -436 -797 773 -813 903 -449 593 -336 -241 -146 366 820 207 716 40
391 -865 -6 -131 483 -546 -423 -586 834 -333 -828 -21 -574 550 974 654 -536 -1000 1 -34
-916 -255 512 -497 751 -821 32 -16 -410 166 1 783 487 -524 868 -771 558 -755 943 -556
-963 669 716 -375 579 842 469 -367 -447 -292 -120 -543 -748 340 78 864 675 721 -943 576
380 998 959 65 825 538 864 787 366 -550 -324 117 143 -986 -319 -436 -222 43 611 209
-723 41 249 517 294 622 -245 -447 -499 835 -148 -948 766 -471 232 -717 984 -403 -986 -967
72 111 185 -361 472 460 -606 559 997 -521 -423 -879 400 694 -438 61 -139 -159 494 592
872 -77 -70 -299 -88 596 -863 482 -444 523 44 -483 -630 -907 400 746 -965 -233 907 -789
64 577 -194 -792 324 434 -836 344 -733 802 -14 363 -637 290 -319 -530 472 -880 564 690
25 969 -194 472 -143 -941 -277 376 -243 90 206 -960 280 -191 685 -710 818 822 423 951
-62 385 919 -669 -194 -162 90 -121 219 304 -185 -87 -705 -492 448 -196 297 622 265 -266
959 -841 459 274 -465 708 748 -490 750 -567 -295 -918 -165 89 -940 845 -902 -507 -305 585
-233 -144 621 278 -718 796 -799 446 916 200 35 16 -734 -962 -456 66 -429 280 708 -36
935 -259 -969 744 526 -449 -178 -459 -169 320 796 -375 -593 -357 170 -211 -391 469 -376 769
-692 -855 37 886 -605 586 -230 -785 789 447 -730 482 -826 408 482 -49 380 752 -164 109
-7 -389 -744 -328 -826 -60 -149 -351 426 -664 -175 114 -281 860 -389 707 557 -321 722 662
-659 384 354 -255 -35 -821 950 416 764 -744 21 -870 -113 629 -800 157 169 559 -547 598
973 -401 -663 -701 -747 -572 771 986 -496 249 -258 755 22 963 684 -22 -26 662 227 -870
928 777 -417 749 429 -25 911 -191 -282 -518 227 965 -521 426 -955 -918 -770 -996 515 -499
-501 642 108 -762 270 267 -676 -421 -83 -309 -270 -790 -587 -523 -668 -42 972 -13 -574 -124
-968 506 -232 -698 754 -706 -77 -906 768 -182 121 -284 993 559 317 445 -689 356 869 710
-950 112 -629 70 -59 305 -268 7 352 923 -363 -599 478 -871 -858 -434 -323 615 -218 -359
962 308 682 101 631 966 894 517 -18 -331 723 -592 -369 -471 -968 527 -864 824 -359 -452
-295 880 271 -500 297 -86 -200 743 -510 -598 340 453 -857 -207 -861 -652 -291 -992 231 -990
-480 -839 -128 -405 -749 312 -151 -835 976 36 14 -526 842 -726 77 771 -538 -68 528 902
835 623 843 532 -686 -936 646 -499 789 -618 -11 819 -511 -808 -644 175 129 -437 797 -131
552 -521 -461 -490 -757 -319 146 531 352 -941 244 19 -377 -748 -642 -18 -567 -382 -837 -759
-557 -436 980 -698 514 -239 -438 342 -967 -647 362 834 -927 -869 889 366 -47 509 71 56
862 459 -743 874 807 24 251 779 -211 -397 298 917 -971 -755 -668 -289 -819 -422 108 -800
-711 -494 -726 -321 244 667 97 311 -470 -429 -917 107 996 856 -513 -646 214 295 -684 79
542 -565 695 189 526 -548 65 -510 -448 -98 -363 -89 -640 -805 -172 -271 -214 142 100 -184
186 900 -74 -741 -185 653 60 248 -191 576 222 -115 878 794 76 -751 453 772 939 -225
-411 -562 237 240 -406 -332 -827 -84 -522 370 998 873 -637 580 223 -594 -590 -741 600 44
612 -990 -146 891 -863 186 309 -24 138 901 363 713 -569 340 689 -264 -44 -251 -596 244
915 -601 -61 786 -884 -251 -375 -205 242 830 -46 333 432 666 -842 -494 625 -361 -562 -921
-681 63 200 494 -915 202 -191 876 962 -668 116 -628 -858 -487 513 -411 872 -535 536 -655
-64 209 -593 782 97 894 -694 -44 336 -591 375 -95 -74 282 162 -171 -696 900 410 266
145 611 822 -654 544 -510 394 590 -448 335 -295 -182 319 -619 -684 -87 -680 9 -23 656
463 -82 -546 422 -375 35 20 -572 -928 426 733 218 -961 377 674 43 351 -161 -320 573
182 -423 723 -16 -462 -189 282 -857 -917 -932 -130 -362 543 192 493 -139 200 -742 -801 -280
-895 207 -731 -233 404 -541 -476 808 -357 -406 212 -772 701 -976 -389 472 -149 -625 -441 363
-277 -100 365 -917 -674 -858 -971 -139 809 281 996 -945 362 -905 799 -783 924 -571 -844 -989
341 789 -371 274 980 -274 843 -968 -639 827 870 143 -272 800 938 -234 -917 78 -934 -453
97 543 -72 -561 705 640 357 -411 -915 662 204 -298 -335 477 -62 -910 -91 473 236 472
-24 -618 -705 695 -511 807 -723 857 535 371 -134 902 110 451 -298 -729 -781 -559 522 21
937 704 -362 247 -105 487 143 660 378 -790 740 -391 819 335 997 262 165 -317 -45 -710
792 -895 -799 -765 -735 -882 291 924 -366 -127 -606 387 783 -432 780 -529 -791 933 307 -513
565 -987 -411 -323 -31 911 -30 -501 746 -935 524 792 819 458 -162 -658 416 -667 -183 -221
676 162 649 -407 17 871 555 -784 -921 -720 615 398 81 -162 -666 481 -13 -850 951 -194
272 -822 -96 674 969 -425 124 359 -492 658 679 711 576 -363 -625 621 272 746 816 110
915 -389 267 723 489 219 -843 550 -112 -990 -713 67 756 734 -17 -813 37 953 -79 20
48 -928 881 843 523 -288 -33 -101 442 -758 -309 -534 130 -152 -863 -740 472 -742 -33 12
669 717 -158 267 -482 -429 -469 109 240 417 -692 -272 -568 -879 -523 453 390 -237 703 874
-644 19 -715 869 363 -858 810 203 642 186 -10 82 201 -978 -657 13 6 440 -278 -457
84 240 -102 -877 -235 172 1000 -938 660 228 457 -558 -755 -292 -932 -424 178 -924 -775 138
-499 241 690 -723 -465 -459 -618 805 -700 -183 708 850 -546 560 319 -59 -338 -613 -742 728
-2 -502 39 -780 225 -247 -758 70 514 -479 -834 271 -135 -90 563 -506 -122 -212 -395 660
358 701 412 964 -595 654 653 366 -149 -229 -576 -247 814 -161 810 -441 570 419 -536 -964
-538 76 640 -1000 99 -1000 447 -476 -363 960 -269 -234 762 -56 -550 568 -142 -250 822 39
-180 -699 452 582 -489 322 111 -561 940 849 -442 543 244 255 -737 -305 895 346 888 911
375 -922 -144 -694 621 -12 234 -554 -848 -382 211 126 -471 -858 -319 -750 501 -406 -302 -820
-317 44 538 -889 -527 -735 -852 -625 -579 -374 942 924 -400 932 -256 487 -760 506 206 589
-410 340 65 796 -543 817 296 579 -95 712 -436 565 800 18 -312 -914 323 -197 -626 -420
-456 579 990 -844 459 -8 -573 -900 -277 -761 836 842 315 -616 308 -122 -844 861 -651 -616
-745 846 181 -175 -484 589 -628 -490 999 -437 -542 576 -643 -998 886 886 -627 -873 400 300
-154 401 153 562 -410 -444 272 222 -61 -141 836 -820 293 860 -749 -875 990 -46 558 -305
-987 474 -685 858 203 -969 516 -614 644 654 -721 832 181 -686 186 -807 -23 -665 -419 -355
572 -138 -915 -756 -714 -917 513 -498 -231 263 16 -898 127 -282 862 157 -608 -476 -839 444
-431 -245 -456 800 -502 370 188 -569 -927 -624 842 -699 872 211 328 136 -56 -210 509 641
238 -660 366 849 -658 -882 67 714 -978 473 -515 281 -620 270 764 793 821 -324 249 -326
-165 -654 -871 -986 -610 735 601 -680 830 -481 323 -109 545 -538 -452 632 687 -692 -337 -416
590 -651 -338 -646 -515 -786 -441 -643 103 93 -822 -164 841 716 137 155 415 720 206 367
-441 798 425 -195 598 -441 -489 55 -796 -749 -56 -939 374 -66 -400 -398 -488 -456 127 541
799 -640 457 -954 -756 -130 -484 969 355 -445 -727 -836 -513 23 352 -198 -757 821 47 71
204 -145 488 -19 -828 268 -395 607 -559 902 134 317 -839 -966 407 -672 655 188 -684 320
483 744 -566 -937 -791 442 12 -708 329 652 808 274 -96 -911 -438 -452 782 11 -526 -901
325 783 665 232 618 -65 614 111 387 773 265 19 -505 -788 701 -475 716 -447 548 184
521 223 -449 -255 712 506 157 -623 438 -386 -47 -797 719 703 600 -139 -921 -88 -109 365
-876 -316 802 -775 804 -970 657 -918 -870 -22 146 380 -91 1 -912 154 43 859 -451 -745
-950 668 -670 32 -383 -488 194 -651 707 -219 828 -311 466 -416 291 -13 623 791 -176 -774
471 534 625 -99 -339 688 720 889 527 -563 -823 -703 439 875 658 -663 -428 245 705 143
745 -643 649 -880 410 -902 -880 -865 146 338 -803 -303 832 24 -860 -976 282 229 504 159
273 733 -267 574 -477 -860 546 80 775 -339 403 497 555 -120 123 -501 523 350 -244 869
-767 52 -779 230 352 774 -232 275 -256 -429 -992 990 -185 719 -929 -239 -991 209 -492 -31
-163 29 305 -426 -948 194 -133 -545 614 -751 -506 -400 189 198 -216 -138 -436 207 -856 -788
146 -161 707 -513 -399 -428 737 7 -279 -67 300 632 847 557 -406 968 -800 -792 355 -56
-526 65 411 -189 -796 -406 -756 898 -146 79 194 -658 -629 -348 -695 559 174 215 -743 811
52 -281 339 973 -870 -173 234 694 631 324 -955 488 -529 31 -692 -914 -185 -700 914 105
-863 -610 -878 517 -185 -996 163 -957 998 -119 -449 922 868 958 635 889 -322 376 306 -170
-757 -143 752 -912 -908 -245 -960 -902 -469 -286 -479 177 -805 -261 -737 833 187 -441 -791 -83
546 -778 -754 860 -457 33 -415 -931 -941 -818 -460 -595 -159 117 599 27 831 -771 970 469
-821 -366 -589 145 -641 -219 909 4 -634 99 -17 -235 925 898 -565 -725 507 -682 -134 -995
-883 -97 251 -138 8 219 -930 100 -700 -611 543 861 -442 -380 -45 326 725 157 221 803
-241 864 -244 9 -949 728 589 108 434 -407 -190 -133 781 672 -540 -398 999 -251 -368 -12
-251 -151 688 -823 -952 434 -331 923 -604 -609 547 -251 307 -413 -528 443 -499 203 -29 522
-451 -693 -237 774 -440 -263 133 631 147 -880 -690 226 542 -852 -266 -362 87 -548 -783 428
452 626 788 367 223 818 686 -145 50 627 -341 641 128 411 803 -96 642 538 776 682
-895 256 -537 32 216 275 15 -441 472 -415 712 672 335 -579 -827 -436 67 971 -411 -537
-124 819 -818 -206 579 -46 813 613 889 -289 498 361 -845 814 397 -530 -723 -938 -425 -62
-785 784 -131 -728 -806 -349 -531 113 -581 -507 755 -673 -661 71 -559 -107 726 492 179 111
113 528 563 -689 -44 21 -324 -154 -879 -361 -429 -318 749 376 1 -16 823 -897 -178 -990
103 -566 -431 -709 112 701 589 -237 -200 -493 14 -885 331 48 -233 -255 925 605 -498 768
126 208 742 277 724 382 675 703 818 -299 913 -748 147 -307 -419 569 -394 -289 -124 429
-119 -979 -511 -343 541 -213 631 339 415 232 977 -150 -719 -618 -864 -792 -775 -76 416 -421
-31 378 339 581 -980 493 936 -358 -548 567 504 -10 -825 -344 375 -876 -150 375 -277 647
755 178 309 211 405 -670 -256 -723 178 -459 614 -157 736 302 592 -357 447 -946 -544 -763
-390 -977 -368 -116 -519 -591 773 -328 -370 -573 933 147 133 -104 36 314 2 60 972 -363
753 -670 -625 599 -663 -49 -706 -238 428 -776 209 -853 -908 632 544 -598 -238 258 -268 378
-509 642 490 72 -301 -914 200 275 385 48 -387 -383 -514 -20 -637 448 747 -920 -89 -86
-356 -973 -593 -300 -851 -811 -961 -538 78 -525 91 822 -503 627 62 434 -488 518 506 -122
605 384 99 925 797 -274 -178 -812 -802 -226 872 -730 -106 603 511 -925 -462 532 786 268
616 -87 43 47 -201 -312 -236 -993 159 -34 -395 -889 -577 -135 -329 -226 612 611 -381 -331
-769 678 202 -345 -577 -365 221 -522 -427 -472 838 998 661 831 510 561 275 -541 -740 886
-744 157 -408 -202 394 -401 -538 -115 800 521 414 -256 648 -557 281 -964 826 -184 -165 -871
-278 970 908 72 650 -994 -171 949 -347 856 592 44 18 -56 -158 662 205 706 -982 996
-703 327 -813 878 234 -132 -232 -696 -274 751 63 191 813 367 763 -786 -485 868 540 490
175 774 152 -6 -164 933 -147 -361 75 282 792 829 75 53 -449 -178 991 80 -881 -241
588 436 -542 -501 245 -397 494 0 -218 477 -167 561 -426 111 -108 499 803 90 4 -642
1000 -544 836 243 -119 384 186 -576 -46 -648 -579 -823 -503 -826 327 41 -729 271 432 157
162 662 -331 119 -712 895 -506 -328 944 815 -7 48 -926 -613 933 -551 799 755 28 477
-21 -152 869 791 -136 152 -709 236 -266 942 444 232 358 502 -308 -792 965 552 -461 -146
405 -234 -353 -801 -268 331 -321 -843 -987 -211 -667 70 -601 -942 407 -517 -589 -192 212 307
-840 704 779 791 931 -484 -577 309 -348 948 956 -713 459 -125 614 -752 285 -228 657 -719
281 -30 740 -538 812 662 -568 -878 698 -933 776 -346 -89 675 177 335 -908 -465 715 883
386 104 -757 528 316 209 -463 561 860 -254 538 564 -605 535 -161 -387 228 -626 -353 -236
-377 -265 -380 893 -738 -877 -549 976 -256 790 -107 -337 -546 936 890 -240 -961 827 749 284
-299 215 -660 -302 -677 869 -268 122 -528 -867 363 296 -325 -958 811 -364 637 -893 -164 300
290 -983 -268 39 -998 -423 -772 494 -625 256 761 -57 724 204 766 -900 -371 -748 -80 99
86 -850 -878 426 687 -896 -384 361 399 775 997 -582 -461 270 -639 675 852 -372 -825 -758
735 -587 947 -641 -229 -974 563 756 -203 476 327 -254 -8 270 -74 553 666 -143 -723 -821
-105 -142 -660 219 -429 -242 -24 -664 -677 -677 2 -113 -735 163 -793 -187 33 -210 -126 125
930 -98 -895 160 16 -713 448 35 652 -637 558 770 502 107 -80 571 -220 610 -157 126
-447 -442 752 803 607 148 29 93 -814 48 -698 -287 -277 -186 806 734 -637 111 -626 196
662 275 751 -760 -569 697 288 -57 -211 492 -326 -868 364 -796 150 502 893 -974 866 73
-676 -835 946 -245 54 473 -227 -134 -94 -482 -523 -625 44 -514 -756 -391 415 -846 -653 -139
827 -136 455 849 -279 -13 -670 234 -325 599 -113 762 -816 774 475 -65 -973 -894 -812 667
699 702 -727 732 206 366 667 323 -369 -48 736 375 68 846 339 -32 531 579 709 773
-780 471 618 155 423 983 -241 502 306 326 188 794 128 -113 -12 795 -115 717 -897 -579
-88 667 620 -501 724 -178 -451 -796 -439 -319 -720 -945 -912 133 -461 563 288 286 388 -131
-269 988 220 954 315 -967 -541 227 33 769 -324 -28 283 717 440 -351 976 -135 244 373
-921 -87 -631 -751 482 -469 -210 648 -95 -559 -399 -636 538 -830 420 -39 -11 -218 871 -56
370 555 875 -154 -439 -942 -183 423 -116 -103 504 519 -849 9 526 786 -537 734 312 -731
-65 432 -455 24 334 816 304 -756 -637 303 526 -450 277 983 732 353 265 -186 62 -552
-70 -310 -301 -652 569 -680 90 410 -863 -693 245 310 95 -915 -723 126 -597 265 -538 431
-810 -757 -466 832 448 -856 214 -242 435 -708 878 256 -842 498 960 -58 971 -477 -610 -984
-490 613 506 12 -350 328 -745 -406 -913 687 440 -54 511 -415 349 86 495 752 379 -421
-385 -852 -493 -99 -459 667 -581 -65 273 55 -270 575 113 -786 471 140 811 886 -201 -684
-260 794 634 -434 641 67 745 -563 -428 333 -961 -755 -179 890 683 346 937 752 373 436
-305 655 -661 -423 -435 273 -776 982 868 979 -998 65 171 521 78 426 693 270 660 -100
151 -812 823 -196 270 -263 -890 -766 -118 -827 312 837 389 759 819 -792 -520 106 -633 975
686 934 -519 -515 -208 -246 -411 347 -335 -864 -492 -428 -680 -479 539 858 -428 150 772 -546
865 755 4 -366 -630 477 320 235 -22 353 -223 -580 -391 -439 -696 771 -19 -94 -117 -516
240 -133 868 -522 -632 -953 300 101 373 -221 850 292 730 371 962 -449 -182 -126 -334 -564
-385 40 766 726 438 -662 159 848 -390 54 -842 489 333 -537 713 -274 -649 16 640 539
-819 -621 -310 902 536 -78 613 357 247 672 825 236 -885 47 317 -393 -601 -379 684 256
-325 -170 -512 844 597 -847 -743 -645 -56 924 783 619 -328 -929 -164 -136 888 -149 -61 145
-167 652 165 720 864 504 -838 -254 997 -730 393 -339 92 -869 -629 -758 500 46 -229 804
518 -76 518 -490 -320 -737 -799 874 839 400 271 573 -574 415 -619 722 664 -233 818 -511
456 -4 89 625 768 -496 -637 37 -290 439 -891 -85 606 -622 344 43 -496 -14 -820 169
585 -601 806 -510 238 -944 -573 -778 -699 -140 -24 234 -936 -66 -228 625 -171 -190 -432 -663
667 -517 -698 903 261 354 -868 683 -338 825 -403 -861 526 -161 -254 -327 -316 127 616 -769
-383 -133 -754 580 -118 -383 -586 -254 -243 37 -45 -585 929 -519 878 -897 -915 -882 19 -43
-958 590 -35 -448 360 798 -228 552 892 -657 -781 910 105 -346 -120 406 -116 -142 679 -393
-244 -739 930 228 -293 -611 -644 555 74 728 -310 180 -102 424 242 -60 418 640 -630 -871
922 -66 277 -124 184 -229 -943 218 222 -809 -23 -672 502 -369 151 -631 201 594 523 -989
-928 -972 -313 505 467 702 -467 -792 -983 -386 841 236 739 974 -113 766 398 -164 -182 289
-116 -736 -280 -608 36 102 -168 204 -42 464 73 663 218 -95 -473 357 -241 345 241 -322
662 -98 111 -463 -23 -93 -116 840 -185 847 502 341 954 -426 670 85 -388 -676 254 -334
363 -14 385 -265 -822 -168 160 448 827 218 -569 661 -441 870 358 352 -140 837 -900 -75
-872 905 822 237 -193 543 102 712 970 -94 688 -373 906 25 614 -814 -942 -298 -178 837
330 917 911 -207 86 -248 688 -311 197 28 328 -62 -483 398 995 -174 565 -547 429 497
-731 100 875 723 -707 212 -551 437 -592 174 723 -221 751 -200 995 -874 248 788 993 -837
866 -245 -516 817 42 546 134 -364 -100 235 -661 -364 -860 -901 -408 -561 -131 -522 804 730
655 594 -423 930 610 -780 -797 348 419 -166 988 589 324 -854 132 -958 -306 901 -994 -541
-779 291 -32 80 939 450 703 -765 215 315 -250 881 529 310 -504 922 -845 575 954 -766
562 721 -349 -444 -1 -639 942 -157 -359 -185 482 -103 -185 -224 -401 68 778 -24 587 379
-802 499 764 -805 741 820 816 -386 -215 2 -618 -651 -864 -693 179 -791 825 -224 26 -63
-284 774 122 -534 417 691 -952 -216 654 465 601 -503 -889 -923 -107 913 -881 144 597 327
634 607 672 600 77 846 -668 -215 17 549 -741 326 870 273 195 400 474 980 -135 186
-867 545 -932 741 27 -270 470 107 -749 -976 359 694 -474 636 -858 757 117 -183 504 66
637 -175 210 932 507 -221 -903 71 -279 358 -757 -109 -335 543 654 78 200 -569 -182 442
687 -343 311 847 509 898 -562 -258 -642 73 -109 -1000 -220 -578 388 99 118 -37 57 -64
236 -64 -422 -409 349 -985 -870 -885 -777 -757 561 -715 618 -104 -306 -507 863 -881 -176 -434
-569 983 -495 729 980 -517 790 41 -807 962 -974 299 978 -478 -697 90 -467 -460 848 -768
-48 327 54 -963 -5 -692 -968 -222 -499 639 -912 -416 -337 -495 714 -613 -464 -501 591 55
502 840 385 -940 933 -356 -192 -56 -387 -458 552 284 -339 550 -808 -447 -233 48 -132 -686
253 694 215 480 295 132 64 795 -27 -432 922 -334 371 -594 -692 823 -738 434 602 325
-584 -552 665 -191 704 232 577 -481 531 -586 -543 -179 669 118 671 488 133 95 -521 -12
79 526 -431 645 -96 350 645 704 700 37 -827 -126 -233 189 941 318 -425 -997 678 778
-715 -666 -713 645 -302 -765 -956 34 315 -53 -111 255 477 -420 725 592 740 682 -720 -978
190 594 -220 -624 -229 -98 -429 804 -591 899 661 972 -228 236 -2 295 398 98 568 672
147 124 -502 319 -632 282 37 -893 734 915 -940 -526 657 573 508 -710 103 351 -589 -447
250 -750 930 712 -720 -417 69 -8 765 697 423 312 -581 400 484 174 22 -316 532 -67
-643 -429 -589 -8 960 191 -321 -864 905 124 -481 -338 867 167 781 548 -209 899 -564 -105
737 929 -302 -832 -966 -304 930 999 348 525 599 875 311 662 116 777 551 -97 407 -744
-944 -536 401 -826 276 -604 42 39 -28 -475 845 585 115 149 -423 -559 652 -506 -969 -806
-293 -910 591 -858 209 -225 440 -28 807 -145 -283 -509 -650 -288 465 257 -871 356 -842 174
362 221 -866 -975 -590 649 -480 397 -170 -24 -685 -745 -575 -850 263 82 -487 -811 -921 132
124 224 -898 643 -713 847 822 382 212 204 -300 -876 615 -312 -814 585 -723 60 -566 -215
-704 839 -668 842 131 150 617 777 126 668 393 -900 816 29 -259 -220 39 -406 -591 -350
-462 498 -926 -981 -904 294 260 208 247 484 -591 185 643 -763 -777 467 -829 989 193 750
260 -957 49 -786 291 -403 -407 -852 389 -152 351 922 967 959 -549 -273 28 -172 596 296
292 17 -874 -127 363 -874 716 -668 -165 -307 -390 801 -547 -366 -1000 319 -994 274 704 557
358 894 -740 495 696 -769 -161 -654 -350 -499 -859 -245 -998 -830 -986 523 -627 824 230 -571
5 976 0 -817 509 -191 -545 349 -906 11 14 -817 14 -777 -443 841 512 234 110 308
380 356 -54 -633 807 577 971 206 950 485 706 487 787 -730 -520 -715 -242 -626 434 -268
277 -481 -454 988 57 428 780 -148 -343 682 -367 745 -625 -910 641 -221 269 -609 737 320
640 296 -752 -39 -363 -850 -162 541 163 326 197 139 -67 928 -482 993 681 -3 647 -450
-784 974 985 246 -605 975 -55 -92 150 -402 131 703 -382 -586 486 650 182 -518 837 -44
175 991 -504 778 955 -1000 329 -960 906 -960 -609 41 850 -393 -784 -489 86 -405 -35 -827
89 -684 -532 555 -693 773 -610 651 -965 -714 -952 214 668 -421 -273 300 -251 -28 -149 -890
657 690 972 -829 723 -845 -636 3 -704 394 -176 -580 -80 285 270 -990 -970 -699 133 -385
498 812 -482 -198 -245 713 -588 550 323 915 -411 -160 732 -177 556 664 248 -444 156 156
871 -401 637 -123 512 201 -965 -53 890 549 -317 425 52 946 664 -861 951 -683 -363 916
771 543 799 -951 -931 456 949 997 839 164 -619 -337 607 289 -100 865 595 -736 872 623
488 81 -969 -471 226 355 -19 -800 -233 218 -547 -306 -234 -799 -737 -781 -329 264 507 -889
398 691 268 -661 -641 -85 -808 -192 -8 -330 959 987 982 260 -928 -438 -191 437 802 -40
-71 413 -387 -479 136 330 611 245 732 77 13 -994 807 -520 -565 383 579 109 -247 619
56 -995 -976 11 -33 78 44 696 -631 488 804 28 -132 969 -563 -127 -810 -503 321 -533
478 250 767 235 228 -482 973 -900 -657 610 973 639 875 -242 120 -668 -19 -926 80 122
-987 267 -627 -680 370 -773 -806 175 -830 663 -560 961 -619 964 -213 560 -637 530 721 -504
-718 849 266 531 309 -767 -555 -704 781 -719 226 951 -459 -181 520 344 955 339 324 -134
-484 442 966 -707 -993 65 -212 396 -355 781 -509 386 -704 -764 -434 -323 88 687 77 908
104 -374 929 -607 371 552 -292 540 212 -795 921 473 329 347 540 964 627 966 -849 -830
-170 523 -96 175 -876 -799 -297 -188 -302 600 868 -115 -669 -780 -442 415 -175 257 767 132
733 -237 523 732 409 -550 -937 -59 561 -827 -981 555 326 122 447 333 441 148 -852 796
-226 518 122 -384 -843 -330 -829 -740 464 -246 -458 28 560 -625 -932 -592 -101 738 804 -866
-345 -710 642 -71 -436 -594 -15 901 -514 -700 256 -754 661 892 -543 982 868 -641 -780 -487
568 434 -649 -996 -318 -823 -893 -873 -12 -323 100 861 73 873 -543 -314 -691 152 861 218
-913 -409 684 375 -633 -268 151 -680 -793 -101 -616 999 458 371 -548 -775 -417 -828 238 -398
-454 194 -113 157 -768 -888 -820 -286 430 592 -156 322 -459 -926 269 629 -727 903 -545 -838
-698 998 -865 -885 -149 271 -804 -461 433 -792 948 894 -432 -104 306 80 -827 588 228 -510
86 223 -625 -845 676 238 -934 -959 444 556 671 404 -893 482 97 -620 954 885 -513 -805
524 288 666 294 365 421 118 946 778 438 140 -172 26 175 128 -816 883 -798 -135 -352
489 -122 -776 -928 -124 -559 276 -926 349 -8 400 772 417 637 -102 492 534 -900 -535 395
385 964 556 835 -683 993 220 838 960 -89 -669 -87 699 767 336 -138 -235 109 -154 8
303 -663 -915 -6 -378 977 70 -386 153 101 -805 961 695 36 987 925 230 -820 165 -404
876 -402 -464 633 294 -279 377 560 620 -89 -970 387 41 116 -263 60 957 -466 319 422
938 116 -525 -671 -217 -46 784 440 -515 -734 701 -959 -560 458 339 777 0 -891 591 903
-611 -800 546 765 -861 359 -808 707 -629 -570 777 -71 358 78 719 -92 -122 51 -389 -94
312 -646 731 757 264 671 -252 358 154 382 342 -806 175 70 -965 303 293 -854 -223 996
-756 562 397 -956 583 -161 -778 -376 537 -56 293 584 -521 -740 -991 72 -553 -925 -342 -451
-552 162 689 -804 425 897 -797 976 -443 489 -92 -984 164 967 496 724 4 -738 -333 138
-51 242 712 778 -868 -245 -80 -732 -294 -12 934 610 642 -630 -551 -273 -432 722 -402 725
787 994 668 829 894 -774 -251 592 486 -70 424 -735 -373 -47 220 674 -215 -219 711 40
-120 646 -278 -620 -596 888 700 2 -988 667 997 -956 -37 -465 -818 -326 922 -360 -789 578
260 -442 749 831 -166 315 -981 832 39 737 196 692 494 926 -350 -19 -915 95 -5 964
924 275 -345 970 -491 -737 -585 994 339 -159 -270 -242 265 -649 205 -264 99 -94 -693 236
-168 594 77 -468 -333 -960 -493 80 -814 -125 668 378 610 -447 191 644 -563 -608 -409 -283
150 946 584 -84 -894 908 230 -50 446 912 -43 517 -442 -359 -824 464 -876 317 670 -944
-47 -536 -130 691 -387 766 -859 -492 430 -669 -780 331 246 595 107 -728 -212 44 -537 17
292 -643 249 -774 -697 21 885 956 -621 486 -524 812 -424 -793 684 -20 -853 -337 904 757
340 -636 653 -45 -814 -845 385 -615 563 -69 -473 895 306 497 -231 -664 905 -961 303 831
-421 -639 297 -341 162 587 116 210 372 -248 513 946 -911 205 -851 -963 737 36 728 97
609 -116 216 -983 -654 506 795 -617 -442 427 689 188 134 -520 210 -393 -537 65 -487 876
852 -186 -670 -598 -12 -363 -160 -807 -213 -27 -751 291 659 -802 -325 563 -794 105 502 -815
-520 -320 -528 352 -983 -663 749 729 323 630 -829 -186 -266 579 -16 -335 487 -35 116 -256
-533 334 74 -354 -635 -228 -201 -421 -708 683 -122 -401 110 956 218 803 -457 -761 216 -81
426 715 -762 613 -916 -80 -340 -924 -51 -633 -53 -348 192 777 -604 326 135 881 459 -388
736 -14 -378 -942 -963 55 -643 -144 -585 -752 -857 -127 804 -95 185 -628 -618 677 189 -369
-273 -236 -953 -446 729 165 -52 -800 -921 -181 480 953 -997 196 516 -759 633 557 247 -177
-452 543 232 847 -619 383 -63 -7 58 -735 -624 -987 841 -330 -974 -59 356 -229 -296 -191
-543 -963 -791 -690 -313 -447 -842 880 437 -710 700 -355 -287 -251 -687 -651 -561 811 281 838
857 336 -835 450 -661 542 874 -260 110 656 -427 -427 768 47 -969 -646 -374 -822 -544 -725
553 627 -281 -887 -672 -149 -468 -459 -284 -250 -357 10 -739 551 -886 498 153 -775 564 752
9 121 -355 -249 -164 451 121 -494 220 705 -483 -62 485 -593 -721 -504 258 433 -45 -475
-516 -328 483 693 978 -265 -989 397 -243 -499 797 666 -217 637 -467 232 940 583 -958 951
240 535 580 794 892 -522 709 -38 558 687 -286 142 -356 923 -998 -769 -277 -939 -393 920
277 -452 490 -30 993 866 827 575 793 589 -987 -884 414 -173 -870 -297 -476 73 -403 -699
355 -79 -560 928 -320 267 -455 -808 427 716 -448 -409 -200 -807 -140 136 -189 505 -950 787
186 -825 331 312 398 77 931 368 898 -504 181 609 403 838 727 211 -545 -455 69 -481
-115 876 -736 -776 122 -359 -276 -275 -532 445 -72 -112 -347 932 -767 722 47 -431 -19 -462
401 786 234 185 -37 72 207 560 -394 -282 793 806 901 -724 332 -121 61 309 -714 590
-204 212 588 -190 989 -907 489 631 -380 -944 976 -277 -778 -547 -967 490 452 -224 -239 -407
703 137 -16 795 458 -90 669 -530 -330 -124 710 -473 699 -667 877 315 -474 -675 228 40
-847 -930 347 810 264 -401 -703 -633 488 537 -20 506 -827 -309 -389 -915 -183 -99 168 -172
-623 -911 -899 -587 -128 -331 835 314 -524 -227 -673 -892 597 852 51 307 -689 473 455 586
-293 641 743 194 294 -576 -726 381 -607 449 247 -72 -513 739 845 140 -721 -87 -535 -491
-614 -305 -459 -785 -886 460 -562 249 949 -403 457 -309 66 -405 -939 -808 90 -474 372 959
-654 -274 -559 271 597 -475 -579 667 -56 426 20 -663 -749 194 -204 676 -390 696 367 663
606 199 440 -337 -925 -314 669 674 788 693 -265 927 -297 276 -442 -819 -96 340 542 996
-954 -34 -334 972 511 661 -286 -788 427 571 -768 -664 -811 580 -94 732 499 -310 818 400
464 336 -785 608 -954 676 945 669 -858 221 531 172 -140 -713 683 -557 -802 150 -250 -529
-324 -64 -664 -409 293 -511 -551 -293 -964 -585 -381 -626 906 561 128 -928 845 42 628 -560
152 -56 671 -897 -30 -247 -162 -274 -632 -115 -138 -286 -423 -435 288 67 -140 -495 253 -904
-281 -582 -969 871 366 493 722 -12 139 646 471 346 315 318 -135 -427 525 1 379 24
751 366 972 340 -644 -768 528 -439 417 -452 -882 979 -495 -933 807 590 986 790 84 -877
930 814 -554 -64 567 -400 -248 909 -308 682 629 -921 929 962 -114 -313 -105 -445 445 -935
231 -125 -461 -217 652 -504 -856 -535 -640 -689 833 371 825 -496 -940 -94 -546 -292 -889 -416
808 -667 164 -425 735 361 250 -570 -423 567 679 -7 -197 685 784 -853 -116 760 86 -573
2 -694 -481 91 -949 692 752 982 -409 617 -20 -636 -479 -201 989 135 186 -559 307 -774
906 -671 139 91 -859 -925 -958 -372 160 -791 -907 -334 -601 -494 -496 -358 -981 174 461 -591
-57 -176 560 81 -906 -974 -219 382 816 -173 586 -177 -397 -536 370 503 800 -211 -745 850
957 739 -686 167 333 832 426 -626 -473 -70 761 207 792 439 -745 466 -775 566 -347 549
749 -497 -81 876 962 -712 680 235 -407 366 286 -91 -675 515 430 -16 717 966 -552 -335
332 593 898 19 811 -75 524 69 -795 454 60 345 797 -989 -552 -36 782 -497 814 -612
755 704 -580 -846 357 -485 -36 -576 -635 -373 370 875 137 -501 359 -524 -20 958 884 446
-976 -136 773 515 394 -234 -692 -683 -806 -408 577 296 745 -754 -833 -945 -398 -850 256 495
987 705 -553 -283 -488 340 -995 959 475 -901 -595 -124 430 808 741 860 158 490 -772 914
-375 632 577 -639 -336 281 -896 -103 155 748 822 830 55 21 840 -395 965 778 582 -578
589 792 -438 718 -192 -880 743 908 -213 868 -300 -314 -839 997 -129 173 -866 -84 902 -462
155 -483 106 -545 -942 -558 732 -869 488 -373 -216 -548 -61 -320 -136 -946 -870 644 -394 -756
851 518 961 829 -246 74 686 -976 531 -401 567 56 -970 26 -959 527 -843 -197 -170 813
-850 815 -526 -835 -670 -456 68 338 63 -155 -878 -342 -305 -584 540 -748 -332 -396 321 -675
960 -64 397 483 115 281 -466 -329 614 773 115 822 -786 -741 -459 927 -240 847 17 -561
-337 -565 -73 -218 893 -765 -298 -107 419 343 196 -751 -644 -998 334 -912 514 18 -327 -332
-901 -349 154 936 -118 -809 46 -191 135 -205 498 -602 -34 413 -128 -424 5 923 -688 911
-375 649 -914 241 229 -105 488 426 204 -286 -430 -663 74 -258 80 -252 888 956 -115 150
921 -662 -756 -289 -353 456 345 696 -593 45 -509 398 906 184 395 -404 -664 467 -316 -148
-365 32 -943 47 535 104 -871 -379 -416 716 325 20 -977 -695 -100 265 -950 826 -657 258
-120 -711 549 379 -472 -174 945 639 402 -617 280 -612 -721 444 -330 -346 -938 530 -131 -913
-188 -242 96 -40 -555 44 695 592 5 -635 -899 -789 -486 -276 920 -31 -388 -673 -792 427
-370 -159 -559 95 694 -204 623 -640 364 -595 81 -793 354 858 184 2 162 408 58 -46
837 -88 393 920 523 -184 -183 -707 324 822 -620 242 -790 -646 289 947 497 -157 -650 518
153 211 -861 446 98 -516 700 -336 293 -62 -338 -847 892 -331 -456 -911 309 73 -153 538
671 581 -791 -708 969 -249 -358 -542 897 -620 -493 -648 -803 999 -257 211 -796 564 -966 -115
-507 591 -347 -595 -612 -157 -37 771 -756 141 -925 200 -577 671 -653 985 107 -723 44 -852
882 -27 531 -849 301 178 -325 -64 -679 -806 -323 -489 -535 -605 299 625 -477 806 128 -30
-141 874 -361 16 -106 -209 -964 -593 88 -584 308 205 21 545 744 544 756 240 -254 -29
746 -34 954 593 324 -297 -221 -840 33 554 -727 -247 331 -998 -180 61 163 827 -35 -277
-690 -967 849 31 422 -591 631 -746 -961 -981 935 -550 -587 520 726 587 -702 -681 112 -714
972 -972 53 706 -175 -245 99 677 -170 837 929 417 -724 318 754 -247 179 150 169 -294
631 707 891 201 271 492 757 675 -901 898 588 819 -658 -120 -854 -781 -926 -500 152 160
-500 -896 222 824 127 -222 277 -448 -300 35 580 564 -65 415 -392 828 995 -607 87 -249
728 -59 448 -241 884 -365 -439 -999 353 894 452 -92 199 390 742 269 -848 -935 -483 808
350 102 -745 -781 -733 259 651 803 -630 345 361 77 -843 592 -722 -645 678 282 425 539
-903 -923 -622 -636 116 -263 -887 -915 672 -883 -103 757 -63 -148 -330 562 325 -198 -908 -139
-694 944 -7 757 552 -370 -580 -428 -205 -998 894 433 977 347 -150 -825 -244 -866 792 -137
768 8 -620 218 970 -213 87 -417 730 -513 -142 599 -93 -406 178 647 852 -547 -113 -749
991 366 326 589 -954 680 -56 453 -290 101 543 819 83 642 66 -649 743 -515 424 59
834 -969 75 131 -289 252 -358 779 23 -326 823 -798 154 -651 935 925 -978 -874 -303 -456
-979 873 -334 603 838 -119 -839 619 458 157 488 -884 890 -31 878 -241 352 -91 755 265
877 141 -216 56 52 -419 -452 -101 -823 885 -241 195 626 711 956 -289 -518 -708 -364 759
-33 -876 -905 616 645 68 185 103 393 -461 228 662 -482 -855 -273 937 -281 -121 176 -828
-26 -345 -41 248 -886 999 -639 -956 -930 28 643 955 169 -7 -326 -282 -292 266 377 -966
606 -427 -15 73 -964 -48 728 -128 -14 -604 519 525 -32 -404 -604 -905 -792 -675 526 -463
61 -11 -965 -470 237 556 321 -339 -30 529 -357 -531 -120 565 -346 -451 115 413 27 449
-679 -799 170 518 -851 842 721 155 -59 -489 975 428 712 662 -201 -867 -116 617 -858 -544
-176 -858 294 -951 -173 -563 -407 672 -605 -314 274 -335 253 -641 391 -605 -618 736 -707 -55
732 180 -572 -443 329 -247 274 -881 -697 80 -604 -427 -681 -752 357 -257 997 -105 195 -986
-943 -506 -215 474 -822 478 461 -71 -272 822 522 -394 -116 -65 854 -742 -527 49 389 378
-314 417 -900 -561 79 -308 -250 972 -438 -893 -549 330 -66 422 -516 853 -753 202 -7 405
739 -280 -110 -161 941 -12 462 218 -566 -413 -608 387 535 -97 27 -931 939 -249 -174 -262
-934 -336 797 -900 -524 -737 491 -768 323 889 -436 618 -567 -650 404 -942 -320 449 205 5
482 0 -185 -214 936 961 -43 347 -80 -10 -664 -152 567 -176 356 812 -890 -94 -240 -680
903 748 902 191 28 -779 682 337 387 -308 24 -999 918 -383 -763 622 -875 -169 735 -990
-313 -795 936 731 194 -146 -13 232 788 188 966 699 407 -40 -1000 219 -755 -253 168 742
-647 -628 -622 717 982 557 991 -462 -165 860 -405 -733 819 -114 348 -750 850 -956 -437 381
-741 -81 295 491 81 -361 50 -494 -202 506 138 -47 819 821 127 143 -522 -190 -225 -845
-728 328 -390 -70 514 -910 -881 -679 -314 -204 -147 332 -358 -605 117 -623 -507 -682 -27 471
114 961 -994 27 126 -682 -102 -95 684 421 897 -253 -24 -267 605 -588 304 328 -600 -93
371 -774 -303 693 472 -692 371 -982 128 29 178 -608 -772 318 183 552 716 -952 94 -302
563 -423 448 25 -540 -25 -124 -39 240 630 -374 391 432 121 611 470 -630 686 764 -837
-688 -510 926 424 -375 -559 0 -313 -405 190 77 265 739 -366 -420 495 584 -715 -390 819
375 17 112 160 -689 758 635 -314 800 -807 -148 -692 -967 41 521 -964 -739 -465 -206 795
190 -834 -863 -303 -133 -969 784 84 62 612 643 493 23 -577 -683 -714 -711 -356 49 -734
679 767 244 584 202 -660 -997 636 -598 28 966 -966 -18 159 -24 -204 -660 -700 726 72
94 446 -273 419 899 -702 652 -448 414 829 -249 220 -594 -720 611 -552 74 245 -873 199
-393 -437 387 -62 259 -513 -356 342 439 437 -743 361 972 -290 105 746 -741 838 778 55
883 370 423 510 250 -284 -20 -269 -651 327 573 -182 572 203 -275 -521 -242 822 261 873
860 -187 -801 -788 -4 -460 -281 878 987 990 -496 -482 545 -438 -116 -94 40 525 -686 500
810 881 529 998 513 -327 80 78 748 -879 674 -599 50 -908 425 144 -177 714 -914 527
611 -828 -485 114 559 831 921 -258 -81 940 118 796 451 751 758 323 124 93 118 -887
-12 -88 -840 641 368 821 -210 -388 -375 -62 848 -980 -7 -865 147 977 -881 780 -137 104
-312 -216 -439 239 181 725 -259 -561 172 867 -444 -389 -554 -131 -573 214 367 -40 -699 512
-392 -665 792 160 528 616 305 -527 -672 522 -686 601 843 -497 -202 763 858 592 -643 984
-55 -183 -512 -146 -774 -441 653 677 -516 -376 -222 -138 -9 -834 602 -369 988 -148 -214 -37
-485 -516 -929 -298 -131 621 -303 -50 -982 19 170 -504 -874 -11 336 835 370 846 -99 -516
-546 275 407 -949 109 178 730 -756 402 253 -530 160 215 -355 -401 -479 350 -53 -606 -675
-90 765 -337 -853 -529 959 349 915 -9 -893 337 280 769 -942 -52 176 51 4 959 718
-639 -675 -790 655 -918 -532 811 -188 -505 208 -413 280 -7 -664 -804 -668 252 780 -866 -282
493 212 -651 169 -468 -550 628 19 -440 -799 221 178 894 990 -751 -348 -56 -388 -996 549
-662 316 -773 532 -572 170 757 -646 -272 -713 346 630 540 398 896 -947 283 -111 -193 190
-599 -874 717 -896 -449 961 -109 -867 -538 -896 -847 -510 -264 891 222 -432 -140 672 -387 413
-212 138 681 859 315 -560 -834 190 222 584 -90 50 51 312 -735 602 -506 -808 -4 367
671 -323 -237 625 921 -610 -827 392 -745 710 747 -421 311 -377 689 217 -894 298 105 801
447 569 869 -252 -202 910 145 317 788 -898 -197 963 -506 655 52 -749 -986 751 -688 532
-71 383 -182 -957 450 -290 737 475 286 -136 738 -233 -13 -386 -947 471 602 524 914 157
898 364 734 -135 505 57 537 -958 449 -258 826 -624 -322 442 884 -354 -503 109 962 152
-164 -673 321 -58 -875 328 -14 637 -876 -66 331 -984 425 269 -157 69 -482 420 224 959
364 -699 156 636 435 664 -330 646 -728 -341 -517 45 -842 468 718 -30 -311 -153 -943 879
685 -503 263 -54 -394 716 691 711 959 523 433 821 -866 682 -475 -778 687 983 408 275
929 -732 848 -624 28 283 376 -942 -849 186 844 166 553 -937 306 88 303 403 -281 559
-271 452 -39 -625 186 583 -895 683 -737 -305 8 -685 580 916 681 24 686 -536 902 -654
54 -455 346 402 -942 215 -947 -889 -713 416 -455 130 -369 713 211 999 289 287 822 869
306 -120 335 -30 -860 44 -916 538 -570 -714 397 -403 -176 329 20 -921 -745 898 -357 248
453 24 954 -309 384 -365 525 -493 453 -559 994 190 290 444 -251 -733 -70 -311 -350 -402
-695 490 107 227 367 -38 -920 783 678 -688 -973 -132 681 121 56 519 362 3 163 857
-806 651 -375 496 -465 786 808 63 -538 766 138 815 -612 846 200 744 -913 14 -258 319
800 706 -765 830 281 875 -451 -661 -474 113 425 276 271 300 -789 901 -752 -128 -143 -238
251 -801 59 369 -687 167 404 322 435 626 -53 -676 -659 122 -718 -717 514 375 -819 758
336 108 -556 748 673 593 577 -369 319 304 -184 -381 423 20 -776 750 -990 -657 136 177
-800 662 43 711 742 -786 -672 101 -927 -383 443 -989 -648 349 338 866 -880 -761 -284 833
906 -198 945 670 689 671 -886 677 -955 395 -636 938 -924 574 602 -875 28 -634 166 -916
-48 562 -914 -255 532 117 -756 -354 935 404 463 -370 -384 -889 -298 827 614 -568 -646 960
-577 868 -228 406 -61 735 829 717 758 -558 274 -525 -601 -860 -427 -72 -439 -936 756 -148
-543 963 -982 -442 609 -991 -3 466 968 -383 -344 607 -800 810 876 -119 502 759 933 95
80 129 426 -408 -130 479 -453 -601 250 608 864 -884 394 -648 990 605 -478 372 -870 228
399 719 -450 509 -770 -696 629 -119 -754 802 -352 -241 -271 -970 -801 -409 -127 110 236 527
240 -918 775 -678 750 912 591 -465 -773 -997 966 -980 -327 -101 -621 -425 -138 833 -643 -359
-576 -352 -448 -620 -401 472 152 -983 12 -651 581 -660 -159 -124 454 597 -687 836 174 468
-356 -155 467 -559 -29 272 -271 153 -800 878 -294 106 -870 805 -611 -38 526 -884 -125 477
-902 471 -559 316 -623 -490 -452 -193 -828 527 -845 -641 -998 425 -953 -478 -213 495 -433 509
685 -599 -808 2 366 -256 -936 102 -8 187 856 -40 -580 -504 -342 261 292 552 -974 603
9 -465 -726 59 -980 -684 -598 -315 -271 -716 211 -467 592 -530 383 -501 -243 199 365 -761
-311 -734 -44 -142 627 624 127 629 9 -860 46 -762 370 631 -958 -590 895 71 240 -124
-618 -365 -138 467 -425 -960 -206 -235 231 -160 -133 -18 -598 -847 709 -6 521 920 143 -908
-60 -87 0 115 836 184 967 -982 450 719 287 -826 -481 927 912 -997 260 848 887 305
-444 -936 -611 621 792 -725 227 673 -72 141 -902 -496 -154 902 -601 626 -463 -436 -647 -716
960 -611 531 572 654 -667 898 189 -638 8 -157 -503 -619 378 -955 -28 831 -762 336 -725
983 68 -5 -252 892 442 714 180 -195 968 883 292 388 -71 31 -893 37 -694 723 -59
-412 -992 408 365 -520 518 382 303 -33 885 -748 797 132 -871 -317 904 -345 347 758 448
-197 -128 326 -773 -587 172 784 -994 251 854 -167 -603 -729 -276 -422 -865 -494 673 29 253
-138 742 -992 -747 -5 554 154 171 296 -632 995 41 -568 -382 722 -324 -25 -832 211 376
-11 976 353 461 191 191 -734 -632 -757 320 -82 -174 -953 -466 -420 573 227 -317 432 -204
-299 -402 503 -531 -337 -504 551 -923 -983 525 111 -552 372 -410 -256 708 168 490 386 -504
739 371 224 847 -929 907 407 -47 -581 917 -215 -314 187 -861 887 447 -939 -345 891 -191
-52 833 57 -950 655 111 -41 268 -278 141 -359 536 -540 736 259 408 445 -928 364 -258
-726 354 416 -919 -970 -875 987 -197 985 953 -822 -500 878 -151 681 616 859 796 532 -701
433 760 -529 -909 493 -82 374 705 220 -974 -913 -330 891 763 -905 -672 621 -829 294 -992
258 115 -962 9 724 -775 -194 -530 655 -151 -808 -714 -694 635 -822 -235 -239 858 -527 -685
975 606 230 844 -769 -495 -120 23 293 559 741 -195 22 534 923 776 397 -735 -158 -763
-862 -671 -771 224 479 -954 -674 -632 220 -624 -615 549 -617 956 -760 -527 44 912 958 -303
-262 -288 -567 -465 -600 554 797 607 -690 -541 -428 769 603 102 -201 16 690 -497 -588 -623
296 -340 902 108 69 752 964 -690 576 250 734 171 -72 -556 367 451 -840 411 203 -207
469 -298 -460 955 316 -427 -263 678 278 -4 -229 892 -328 -408 -750 858 -902 -620 -494 -679
39 629 933 -924 -241 722 537 588 970 -824 870 -474 -63 -719 457 138 -550 -410 -742 87
-978 -878 -75 700 273 76 -495 157 -46 -66 -2 -446 -526 -587 845 -360 -437 -339 -332 984
710 868 -314 127 -864 66 -578 -270 953 401 -702 162 497 217 787 309 -620 573 532 916
-41 -754 901 533 387 433 639 -920 126 -15 -210 -339 -522 -441 -732 721 -354 675 -357 -557
569 -815 71 183 925 315 963 -242 479 325 -906 26 793 783 -748 -740 549 442 93 -394
837 420 -944 -521 62 94 -923 -486 790 229 -867 443 127 -711 -899 314 59 -364 -403 -113
284 -556 578 -665 233 -767 461 -101 -541 231 -231 -855 -229 -796 -381 -113 -633 -873 546 243
762 42 -86 334 -30 659 677 -748 231 405 -481 569 -378 -448 -772 -64 730 493 872 52
182 562 -766 -440 964 948 -118 -408 215 825 -849 -146 396 -212 97 -507 948 -216 -151 788
-761 583 -772 -914 -206 -502 -869 -386 827 -205 -403 -279 540 -26 -895 554 -600 676 58 540
-903 298 -896 -842 546 -774 -453 76 836 443 177 -309 -120 -152 200 -73 666 -201 197 -218
-454 531 541 910 505 742 740 42 568 859 -615 -364 141 -113 671 702 871 837 824 -424
-200 -584 119 131 -779 189 -260 704 -793 519 -703 -49 -751 -397 699 833 167 -937 -22 440
308 -492 -744 -527 -762 -317 -889 158 -165 -878 -738 432 -441 41 -154 -415 956 48 650 -855
-612 -389 497 -580 -958 -904 -829 787 985 -790 -485 295 974 979 -215 -997 -186 376 92 984
900 737 331 -40 889 132 -639 631 432 961 973 -844 -603 712 -500 -427 286 -562 603 -711
130 352 -368 -657 -722 -996 -898 -571 -481 -105 -627 -936 -914 615 459 -626 -6 -554 310 60
689 579 765 -334 -971 371 738 61 -242 -507 -429 -965 -511 799 -474 325 747 122 -105 -836
-268 330 -749 -825 -268 284 -532 512 423 -853 -912 475 31 -491 -753 534 -435 -821 899 -340
919 406 492 -253 -157 -959 104 -219 -932 -754 -314 575 -908 80 665 -399 -792 -132 961 703
915 -769 210 -474 408 -10 769 849 194 115 -789 731 114 335 552 -887 808 -965 -104 699
-527 -240 490 675 387 605 893 -441 216 548 -536 -523 -493 -886 265 855 532 -359 863 -505
-718 -372 72 894 740 808 -494 33 -486 -380 652 829 432 537 33 22 -503 -892 -673 -956
980 520 -491 -161 515 -281 524 -990 937 563 -775 -991 -734 149 796 161 853 -816 599 -628
-246 216 -228 -486 -706 711 -931 -677 785 -556 411 -280 632 -424 -577 -910 76 161 -493 -237
-335 -860 605 -501 -966 -215 210 -380 -896 -356 495 739 -63 379 -350 -770 936 -311 -667 642
846 805 -800 -945 67 -813 78 947 -42 -783 -994 -3 -146 786 -704 -56 -117 -776 481 147
-720 156 -506 171 -803 182 203 -157 -209 111 889 430 -812 720 -780 121 -477 -618 620 504
297 -771 776 -680 335 -806 635 -625 195 -998 102 -141 776 -137 -666 -22 -356 675 -637 744
604 392 -702 578 423 -712 -736 73 -704 -692 -142 -574 120 386 428 256 450 263 -171 -889
382 217 -201 505 56 333 -924 -767 -292 -719 -201 -185 -211 901 542 941 -472 544 -694 -531
988 386 -87 0 -444 611 -826 -560 855 -106 -359 -107 365 960 -51 -349 -389 657 257 85
97 -563 -913 554 -706 474 373 -523 408 577 253 -361 147 -947 -130 198 -980 574 441 -221
-734 474 -497 -404 306 -552 -289 690 80 612 997 -814 296 883 757 -546 487 -266 -744 -87
-441 41 888 -373 -711 885 -491 -907 -586 -421 -279 -227 241 317 -835 -41 -141 -452 -132 -327
-576 -751 852 670 -402 375 777 741 -625 282 -404 19 492 490 183 652 -318 209 -871 709
976 -499 -309 738 -342 431 94 -652 -514 -719 638 329 672 297 -198 -732 573 352 372 -879
740 -665 927 355 654 284 -223 331 431 639 -105 -98 473 164 662 78 -368 374 -242 -539
71 -575 764 -626 70 -403 553 -908 588 95 366 -940 946 914 988 -849 568 327 808 332
668 -319 -901 -989 90 927 -659 -593 174 329 662 588 -730 713 -868 -337 -653 232 753 413
-127 949 -839 -741 -520 -603 -563 -745 534 912 -33 -132 -907 546 211 115 42 923 -926 911
600 899 164 -646 924 -806 -96 492 984 -649 -87 655 -468 -357 -810 22 -594 -54 -765 473
-568 -887 -461 -256 906 -190 321 740 -873 16 54 461 -345 103 901 -190 -891 -20 851 583
-698 708 -640 642 986 -335 -218 -809 789 580 -610 -86 433 -733 -503 373 -292 -909 -300 596
653 665 605 -836 251 875 -946 -215 -949 -594 -793 814 -715 -581 -981 -994 36 833 144 -153
752 -533 600 -807 -233 947 335 247 4 -13 134 386 -32 358 986 -585 244 -102 96 -122
753 573 534 -468 -133 -610 922 -728 -925 -924 -528 -862 -315 9 -543 -359 -220 979 -161 -157
311 -201 871 -959 -297 -425 -965 247 -117 -137 123 42 188 -865 -489 -828 -420 -116 726 -554
897 -57 -29 21 -118 -156 -708 552 298 -304 -253 640 -759 -867 -281 177 68 -933 916 135
235 556 -832 -166 727 -433 311 371 738 202 -395 875 847 -397 -587 -580 -676 778 -329 -910
256 350 -453 -304 -312 -756 -39 -540 -487 -495 -579 665 -791 -308 751 498 -104 -669 232 261
89 -264 236 358 942 -534 -305 -438 349 -123 -293 328 -217 697 -853 874 557 -120 861 284
-961 652 -873 715 4 800 -214 221 -280 831 343 320 -354 -617 -245 543 967 873 818 -534
604 -795 -724 337 -992 -472 682 830 160 702 -266 574 -301 -972 -776 619 17 844 -916 804
250 -161 897 513 71 -723 833 -361 917 319 243 -487 -817 578 -835 140 112 -861 -249 -373
951 53 432 -705 -206 -671 259 443 421 426 -111 612 -841 191 -13 -61 496 15 344 -203
-644 432 -602 921 971 714 847 424 755 296 -499 233 -285 -42 318 302 -588 931 -408 -752
-195 416 64 -342 451 -735 289 4 625 302 647 518 583 252 320 535 427 106 -233 756
-33 133 -607 793 583 -528 146 -586 130 -500 -856 197 -440 -832 -690 -383 895 415 53 -996
136 -575 754 -196 -236 139 -328 277 -79 -889 474 -871 -623 425 943 27 -408 -754 -582 -121
354 -108 252 560 169 275 684 904 -608 617 -648 797 643 486 246 418 -272 396 -788 8
44 -140 165 384 101 599 610 679 784 616 716 839 361 627 -581 -32 762 -225 761 -453
157 518 -857 -824 -98 -66 537 790 -397 -63 -578 -510 0 -786 -939 979 919 -181 69 -329
856 682 -95 -695 -357 394 -981 -269 805 245 580 -673 2 0 586 -290 -940 252 182 433
195 -391 55 932 695 -378 -342 473 -449 916 -596 -306 7 -400 -519 -187 -864 231 -709 -169
928 28 -157 951 -262 -553 755 -71 -671 -669 -220 -178 -440 -296 -985 -569 -843 -134 851 232
-605 -753 155 324 -832 437 -496 681 827 -33 -600 508 639 -227 221 307 1000 300 -914 -538
767 766 -880 -814 978 986 428 -38 66 782 137 -9 435 363 -170 -889 -146 605 -354 -216
144 1 -856 918 -596 -722 -95 -11 483 852 439 955 -277 -372 631 -600 -868 300 5 41
-558 -228 528 -666 783 942 389 -416 -948 -831 -351 -463 -718 -98 72 -277 -846 -145 278 29
35 -58 282 728 -487 -969 -834 561 730 -276 121 513 -527 -241 -723 -618 -735 -575 -588 664
89 -136 69 -912 879 -308 500 -605 -692 548 -361 -808 807 866 -580 22 115 607 -787 951
886 -427 740 -236 -767 160 238 -800 -5 708 906 -190 -845 483 799 892 -266 -28 100 913
-744 230 -431 251 -492 256 -367 995 226 -281 354 -414 -862 -273 726 -294 439 -411 -640 641
-224 324 373 -583 -449 975 -636 973 691 631 782 -447 152 458 -58 577 -301 -75 105 226
630 -796 46 860 772 447 -544 -792 567 -704 -844 -328 933 660 85 -926 813 308 302 -718
-659 934 327 407 -785 181 594 -222 -957 109 -990 -496 -335 894 298 483 39 -213 -915 -10
438 -9 180 74 416 938 -673 -861 224 -659 -239 -224 712 -372 408 -432 -533 -571 308 770
497 313 772 -494 956 -506 320 -416 -438 -61 -844 -147 254 -711 46 617 799 332 -820 -716
-891 -483 -435 276 -226 873 -871 -433 891 537 493 -508 841 451 -984 -138 -914 997 -168 -542
29 270 -639 994 535 -152 -395 -653 -118 522 -464 813 186 993 512 523 685 808 -335 -773
740 -751 -739 -406 -962 -263 251 109 651 -769 763 89 -387 579 -97 961 -157 -265 -241 932
-705 -276 476 934 164 342 141 -766 -514 873 -507 -110 -82 276 633 739 -500 -633 -142 -151
488 -71 -751 614 364 503 -975 18 913 -413 510 -24 53 689 143 -845 195 943 257 -876
-313 -820 186 -719 968 -47 -190 258 -175 65 -264 -720 646 -810 -535 66 36 716 -522 987
981 -502 519 18 816 343 -754 -299 -155 -783 -685 73 -423 784 -795 635 -714 437 496 -545
-995 623 -699 -133 -346 -161 952 -954 -30 474 182 -600 327 429 384 736 -228 -207 923 923
-695 -141 -109 -69 519 159 108 -963 402 -988 435 827 877 5 810 329 -459 477 362 781
-571 262 -109 -954 -432 -119 21 82 53 -813 -432 313 -21 -338 435 649 -468 -334 242 -71
880 -394 -418 438 907 630 385 552 501 -595 790 -24 -661 424 -644 -556 -713 904 644 856
-56 501 735 164 -482 -110 411 528 -793 474 849 -516 395 637 -747 -774 -405 21 -225 174
-92 285 510 713 446 -764 -742 309 241 -679 -537 943 -843 271 459 -789 -848 -288 -542 -639
-90 936 758 892 -160 611 -802 -589 255 -779 537 -632 487 886 934 -331 888 311 661 -525
390 174 639 115 892 -908 757 514 356 -961 -960 95 160 103 387 446 626 833 -48 -861
-81 261 320 -16 -131 -456 102 11 -96 750 763 944 552 -962 985 939 600 -580 277 -198
409 918 595 240 391 770 -329 334 111 -669 760 -247 335 771 -259 982 573 -201 697 870
-903 317 338 150 937 -56 -610 -783 411 661 -938 828 448 179 649 454 -291 -998 915 -554
-276 -614 -77 182 -960 -490 -989 -397 -254 -701 222 -687 996 -735 -120 -341 -95 73 -825 -672
-617 741 693 473 978 -822 224 64 -573 -961 260 45 -400 -745 379 784 -732 897 851 358
-270 -106 -638 -393 294 -662 -958 -389 641 -936 291 -197 -583 -163 161 307 190 949 -429 273
332 778 -683 334 -695 181 -788 538 56 979 725 269 33 -514 -878 912 917 -581 304 -579
682 -116 593 768 636 267 792 -372 -476 -275 899 -777 335 -140 921 115 594 -999 423 900
-589 -304 915 481 663 607 843 -819 -316 355 -83 547 -93 127 936 579 668 172 -81 687
81 234 607 316 -151 592 -806 738 -332 -102 761 417 780 -135 932 95 -955 -470 517 301
-20 495 68 665 -325 414 -955 652 592 497 -998 944 -654 -344 -767 93 -657 496 946 -928
419 839 593 -646 970 315 -873 757 57 838 553 -660 -296 -753 -437 -75 519 270 -731 908
-590 863 964 530 791 199 501 143 874 372 -696 371 -908 -72 -183 -652 -909 668 -387 681
-494 855 203 -501 -427 -613 -733 -123 -269 162 290 45 -39 269 -186 378 -193 -407 -756 291
15 95 97 -271 -257 -220 103 486 933 506 340 -867 -779 391 -558 833 172 -752 -188 326
-271 912 818 -758 -79 -574 -45 4 70 -888 -957 -351 -153 -20 -853 104 -339 639 503 15
722 -981 726 -40 -30 -199 13 503 164 351 -434 -825 -732 -72 -429 -158 -51 192 145 952
-641 -675 502 457 765 -486 175 119 -488 -956 700 -996 581 -658 277 132 -175 649 390 -205
-856 541 292 9 -541 601 -138 616 292 296 913 -966 786 229 -863 832 574 831 427 333
-764 118 917 -731 -972 793 295 562 -130 -216 -1 -487 -662 -387 477 -377 530 -946 532 331
155 675 393 709 886 -804 42 787 -585 -340 389 -393 -714 -988 502 595 898 -860 661 -395
961 -969 -523 780 391 -581 185 -707 664 -526 -587 -186 -95 -568 946 -568 -783 -209 260 -522
349 -626 376 431 -459 -337 302 -974 529 -973 657 -307 -645 754 498 -625 236 -56 -820 11
956 240 -476 220 -528 -760 -355 -949 -568 -143 -479 -315 904 -694 702 671 229 -524 -935 131
-527 938 95 876 -865 25 -192 874 -840 414 -386 -684 809 399 -100 -899 -871 -107 -832 -988
-718 -405 18 -318 602 458 -438 -339 -742 470 -30 772 -321 101 -523 -509 -30 -84 -600 -190
-630 -911 503 -347 847 259 -154 620 96 850 365 560 -796 -295 -193 -96 -464 -849 -964 -963
-403 -400 791 241 897 987 -186 838 665 369 -752 243 117 559 797 -720 197 -40 428 33
13 477 -621 -160 729 -694 991 883 -873 -886 -486 698 808 543 121 -292 -597 -187 -482 67
497 -745 501 -719 -253 525 772 450 759 -454 -933 158 -481 560 -58 277 -29 270 -356 785
-13 366 734 760 960 -477 -711 -126 368 -355 913 98 86 -577 -407 220 396 -516 288 999
-245 146 -476 965 715 -79 -526 -217 538 86 382 -27 966 519 403 555 -858 25 -968 424
19 -619 -169 -150 450 240 -205 106 -590 470 656 -432 -677 874 221 -422 781 313 582 -945
-419 27 -160 932 997 611 491 3 53 416 -508 -276 459 748 -538 -17 -843 585 825 -180
15 -207 904 710 782 131 -295 -570 945 -472 -495 592 436 -796 -426 377 -1 -765 450 -430
306 722 -609 -371 -265 -47 -86 836 131 -446 10 -339 713 155 753 -253 182 -937 246 112
457 394 -82 -969 985 -949 115 364 -2 -649 -882 66 -963 -1 -686 248 -186 -74 793 -550
916 217 -786 -967 -964 61 497 798 -589 -205 28 563 433 806 29 -26 -943 164 249 -20
-909 418 -294 144 97 -773 760 -931 -423 -27 -314 -948 -116 -229 -453 -706 64 -709 393 251
-2 -157 42 179 878 -947 -681 -469 -15 -630 -312 -757 594 347 -420 -610 -106 527 -930 808
-784 225 352 -99 498 -975 -98 983 552 796 -186 721 -591 -954 808 -753 896 707 551 -979
-186 -426 218 -665 -900 9 -433 161 -726 440 -342 467 -687 724 205 993 -159 -110 -538 707
728 -670 263 331 576 -425 -85 989 311 410 64 -905 -975 607 -552 -167 -434 528 -699 834
-881 -228 -29 -245 859 933 -304 734 951 251 -72 769 -269 -447 81 136 936 409 -72 -665
-11 -319 -306 882 -982 -736 -310 476 -613 263 -983 122 645 -403 640 -897 4 39 -79 -460
-476 -473 21 -174 -434 726 -980 -901 -323 -494 -664 -421 -214 -151 -364 754 -839 713 196 -748
392 81 72 -612 234 -82 -82 816 -554 -502 441 -256 414 -910 -235 482 -932 -884 933 106
126 -525 -853 -410 -94 499 826 -845 -407 -331 241 930 -848 -707 896 333 199 447 -589 -363
869 -692 80 733 975 736 -908 911 -362 425 -923 441 -757 -598 -526 -738 -700 672 -991 57
-772 336 209 840 -948 -841 -916 642 -385 -72 -256 -112 530 17 -125 -644 877 521 -452 -658
-16 -586 878 2 106 986 -617 -330 -362 138 313 -650 -853 -370 -417 469 -222 507 837 -246
249 -844 -523 132 522 -419 -818 290 -977 277 680 471 451 -724 -650 509 485 947 -28 400
861 580 13 665 -794 902 -832 288 901 332 720 85 -964 -247 -767 -432 536 494 -923 760
-796 -515 942 -248 -309 -870 839 819 792 807 -219 981 -135 889 677 955 -658 705 -481 -429
49 732 375 820 223 573 583 -847 -731 808 -839 275 -245 -994 904 -556 419 177 -105 -300
192 -358 -280 -541 -842 -349 392 -245 851 506 -121 227 -353 292 303 188 -179 -935 186 218
-865 912 -506 242 -488 -820 299 371 -731 -531 673 -323 14 381 518 -798 -39 150 -286 290
152 994 -107 277 -568 547 865 -263 -63 477 -218 624 539 -234 112 185 -419 747 -143 882
407 -442 980 -354 -600 -743 353 -214 837 816 -185 652 943 583 -336 -593 130 701 54 -57
11 219 699 -981 -730 -354 -670 402 260 -199 819 999 -58 -962 662 -52 457 646 -776 -103
445 430 -526 -61 21 -792 -395 132 260 -667 885 -472 -592 942 -837 -403 -893 687 -616 -366
-618 -749 443 643 472 -519 -447 -98 281 -174 -253 825 -258 -601 91 -320 366 -492 -58 967
', '76237
', 10, 0, 87883, 6),
(1001, 10, 'stress', '20000
3894 -3865 3999 -678 -3744 -3129 -1164 -771 4177 141 -823 -3762 -2315 -4782 915 4573 1779 -4606 -1777 -1724
2872 2337 -954 2462 -1512 4759 1043 -4786 2868 2625 2352 -95 -3194 -3189 -155 1263 -4818 -2723 -4079 -3880
-588 -3686 4649 2095 -813 4952 4732 -4967 4355 -1821 544 9 3222 3071 -4375 -1690 -138 -880 -1458 -2191
3436 -4075 -2546 -3196 -797 -1035 -4945 -799 -4975 -4568 1420 4112 -1675 -3105 2957 -1732 962 -1917 2034 4243
-4864 -3299 3805 4981 1940 4501 -4319 4786 3100 2155 1712 272 1219 953 -1859 2849 4250 3140 -2141 -4122
4027 1466 -4105 4621 4617 3108 -2691 1951 -2426 -2864 4315 1182 -598 1808 -491 -3854 -2480 -2115 3437 -4979
-158 -2407 -2719 -2144 -4432 -388 -3716 2006 -4244 -3799 1462 -787 3346 -3194 50 4658 -3622 500 2970 -3829
-390 -2115 4670 -95 3799 -958 -2058 4277 -3614 4996 2206 -4283 243 2896 -1436 4594 3863 -664 -2749 4511
-333 3291 -729 2650 -4928 -2441 418 -2989 -1651 -2512 -4576 536 3517 -4576 -752 -3919 3656 -4583 -4504 3521
1067 -630 -2654 -845 -2324 -2101 -982 -4216 3405 70 198 -3047 -1922 4115 1855 -1138 -3350 -1720 -1685 -1140
-1540 2826 -3669 -3827 3694 2298 -3558 -1987 -826 -4055 -3073 -61 -3023 2022 -112 -4359 -4373 -2873 -1744 -100
-3384 -3392 895 -1610 -4577 2392 -2462 -3906 -1496 1197 -4357 1293 -1997 1843 -1457 1309 2517 4427 4912 842
-2476 -519 -889 -2656 4353 374 3202 1100 1342 2498 961 3448 37 174 -4234 2900 -343 3329 -4246 -1211
1431 -402 2502 399 1123 -4662 -4964 1232 -2306 -2213 -3543 3396 -2642 -4074 -2098 -3787 -1362 3541 -562 4779
-2021 -4064 -4199 382 4063 3904 -3248 -3561 4782 -3950 752 -4463 2281 -3464 313 -2672 2952 -1816 1968 -4400
988 3670 -1627 349 -2155 4595 845 -179 2005 4374 2515 685 4056 -1335 -2338 -691 1964 2495 3382 -4021
2547 1633 -4600 -3424 -940 -282 -3080 -3244 163 2030 -3511 -4250 3493 -4610 3530 -3119 -2639 -218 988 3862
-1169 -2072 -4175 -3008 -2603 1731 -657 2784 1733 -1199 4258 -167 1889 -3349 -1130 -3643 -331 334 -47 38
878 -1949 -3461 683 -4197 639 -1971 1655 2789 4857 4216 -331 -262 -1576 -3048 -3383 4526 -275 -3204 995
-1782 -4565 461 2473 -4704 3083 -4174 -3486 2855 3815 -4245 4144 498 782 2216 -4286 -2862 772 805 -2104
3480 -2826 2773 -829 -2053 -3572 924 -559 4090 -2673 -4932 1867 2960 -4190 -254 2493 -2273 2644 -1947 -2023
-4319 996 -813 6 -1288 12 -2411 2572 -2139 1383 -4433 4983 2384 -2722 -3855 3694 898 4855 4350 3291
-48 1499 -2308 3134 -824 -3816 1944 2085 387 3316 2642 -4277 -4019 -3821 -3010 119 800 3165 767 714
-1650 4230 1871 -4229 -1591 2473 -3543 -3498 -4057 -2090 4409 -2598 -4624 -2066 -3123 405 765 1788 -759 1937
-2908 2065 3784 1950 -843 -3939 -3631 -1276 -650 1698 1240 -2448 -3422 -4689 960 3693 -4224 -20 -694 -1525
1790 2276 -2918 3002 522 4994 3725 -144 -366 -207 -2942 4329 -466 -1818 3920 2560 -2356 -1978 544 997
-3175 -1238 3074 4837 2587 -467 -3120 1927 -3386 214 -3966 -443 4449 4028 4895 100 -541 3029 -797 -890
4914 4630 -484 -41 294 -4021 2835 -3975 -3013 -827 1 -2656 -4019 -867 -726 3709 -2693 -2176 -4047 -1203
-2071 565 -1900 308 -4922 4327 -4966 -130 1140 1733 -3229 -2668 -263 914 -207 -4055 -1677 3319 -999 -4741
80 2935 2222 -4052 266 2565 -644 -23 -2554 2845 3814 -806 -2224 -1670 2956 1121 -2655 3616 1642 -690
-859 2963 4556 571 -4243 -2219 -3262 268 4175 -3642 -356 -2802 1875 3093 2896 -3719 3989 -1927 -4647 3412
-1134 447 4052 -2915 -2692 1714 2085 2550 -868 -1377 4863 3905 3173 -236 3405 -2805 2519 3402 -1854 -2054
4654 935 890 3266 -914 1471 103 4378 4035 -1179 -4239 -4554 2944 2851 4397 -4950 2166 4600 3710 3367
-670 4046 4130 1421 -4227 -1532 684 2732 -3766 2497 -2318 1980 -337 4683 4621 1030 -1808 4191 -868 942
-575 -1969 -2510 -4793 -1568 693 -1829 -814 1350 905 -4053 109 -2402 4646 -1525 -3582 3964 4534 -4983 -2669
2064 -950 3266 1677 -737 -408 4004 -1158 204 3276 1810 2193 -4389 3126 90 2959 -3483 -1159 3596 -2721
1261 977 963 3248 -667 -2335 -1183 4734 1211 4672 -179 -359 -4377 -3940 -698 3325 -1870 -4730 -337 -4561
-3179 -4790 -62 3242 -3574 -1658 2923 2047 -1783 3385 -4406 3532 -2071 2473 3620 692 -2367 637 -4482 -1156
-950 535 188 883 -573 -3988 -3501 -500 3934 -3368 4831 -1067 -2869 991 -1027 3006 -2505 -4652 -4788 1574
-812 3638 1529 4887 725 -4193 -4591 598 -212 -1881 3535 4029 -773 -3784 -3547 -2360 -3579 -4771 -3809 -3574
-4926 4070 -4220 1198 -1676 -1948 1460 -1750 4592 -875 -977 3052 3154 -105 1140 2800 4563 -3072 897 -154
-290 -204 1291 -4723 -3801 -2363 2174 -2735 -3869 1242 1596 4026 4868 265 -3856 4891 4934 -598 -3638 -2303
-4390 -2166 932 -3609 1779 4622 2419 -3925 1697 2912 513 1546 -2298 -4381 -74 303 -4795 3985 4786 -1443
4259 3503 391 4531 2376 474 4585 -2596 2500 2288 4742 -4142 3110 -4286 -3547 -1736 4140 2177 -1076 3662
-2961 -2352 100 -4237 -2638 447 1093 3479 -993 4082 -612 -3508 -3803 3366 -4705 1081 -1355 -1682 2000 3656
-9 2280 -4605 -1176 -4284 1538 602 -3135 -2098 1198 1205 -727 359 -4011 778 -3760 1818 -3203 4981 -2940
2072 3803 753 2032 2418 -2357 1292 -4620 605 3250 -4662 -1972 3441 108 -3822 3888 3485 -4137 -4049 1177
-391 4213 -555 -2666 -1506 102 -2311 -1852 2997 -2345 -366 -2452 -2401 -1116 -840 1274 -4915 4528 3457 -4722
3376 3767 -1742 -4760 4242 428 -375 -177 3332 -2394 1605 4633 -4331 1790 415 -3253 1073 2789 1003 -4221
255 -1787 4988 3651 1171 -142 1486 1855 56 4022 -924 -1525 -3723 -1272 561 -1849 -331 2222 -376 -4974
-4295 -500 -1215 -2036 -279 -733 3556 -2251 4564 715 3235 -2786 3950 -2140 -589 2084 4314 590 -1147 -4341
4499 -4682 4911 -3649 -2474 -1274 4997 1355 -3220 -231 -901 4700 -1400 -1689 -3004 3048 1573 -4664 -1498 -1417
2618 -2758 -2826 1619 3623 -3485 2341 4542 -3067 2447 2640 -70 4435 -2532 381 4933 682 -1999 4981 -4036
-1494 1347 2332 16 3824 -3451 2988 4237 3941 2541 -3682 1268 1879 1253 1177 3180 -4064 4911 1448 207
2010 -3186 1712 -1048 -1183 1859 3500 -43 -1558 352 -1061 -3277 30 -371 2358 3823 -351 4626 -346 -4677
1694 -2260 -954 4572 498 3718 1143 -1488 -1429 -4209 -2480 -4123 -882 1755 -3441 4606 2068 3933 -4748 -2260
-2215 -524 1890 2676 1763 2721 -4504 -3108 3798 2635 1884 475 3909 -4705 3294 -4404 -406 3335 -841 1950
-4507 1047 2330 -703 3534 2745 4220 -1350 2865 3304 1463 331 -4838 -4131 -62 2158 -4088 -3537 -1984 -3054
-4969 1249 724 -2399 -1855 -4601 2817 -794 2152 -3723 -521 1309 -4929 877 -164 -560 3793 749 -1762 -700
2893 402 -3184 -2581 -1836 2251 -4993 4380 -4876 4459 -1320 4161 1129 812 -65 -2428 -2641 -1575 -4899 621
3564 2644 872 -2138 4535 -2034 988 1054 -735 -4640 1864 -1320 2619 2792 3281 -4359 2906 -1471 637 -2835
-4308 -4129 -178 151 -2239 -38 -4891 -555 1876 4666 -278 -3963 4060 4181 3612 2928 2023 -876 -2426 -3572
3849 1171 -2637 1276 3964 1937 2647 1404 1794 1073 -3436 -739 2268 -3563 3519 2976 3857 1831 409 1171
3779 2421 -195 -719 3854 2390 2935 2279 -4667 265 -2163 4953 331 -309 587 2669 -691 4676 -1796 -2348
389 -325 3758 -4530 4536 -3905 552 296 -1507 340 -3808 4082 -842 -3996 -4714 4696 970 1583 -4259 4858
-3611 1494 2400 3178 2925 2489 -3374 4318 4348 -3265 -4744 1454 1090 -1685 1635 -4180 -1900 -910 4298 2881
4095 1371 3795 -1442 2888 -877 2075 -368 2987 4923 -1045 -3633 -3602 2834 -2383 -2088 657 -734 2230 2714
-3479 31 -2364 3536 1269 1407 744 -1793 1838 -334 -4063 -3171 550 -1684 4844 -287 2362 -2657 45 -424
2280 3794 736 2997 -1474 -1872 -3778 4526 2964 -4897 2332 900 -1350 2976 3472 897 386 3800 -964 4023
-3080 2300 -2822 -1665 3592 2472 -3293 2021 -44 2445 2154 -892 2710 -1225 3169 716 1560 2309 -1855 -417
4659 210 -3662 3950 -4597 -2161 -2564 -3102 449 168 1820 -419 688 -2100 -2887 -3385 1996 3311 3856 -3099
2869 -1817 2129 1282 -139 -4210 -1014 -3647 -1729 4744 1309 2535 -1020 3478 -2161 -1730 1433 -206 -4617 -3449
-3392 -4640 4631 -3211 3672 63 -1921 -37 4692 3076 -4551 3229 -1866 -2536 -3896 -4400 -4488 2205 -4421 -3462
-2521 2707 3515 -2588 -3498 1308 4063 -3182 -1492 -2923 -2023 2647 4132 -4233 -1338 3335 4346 4833 391 940
458 -2403 -2386 -4075 -2970 2882 387 -4157 -65 4932 2594 -4033 3925 -620 -3448 -4195 2222 3328 4593 3114
-4513 -2481 -640 -3148 -4331 -602 4937 1656 233 4325 -3291 -2227 1336 -1113 1466 -4350 -4900 -4539 -3346 2825
-3763 -2778 1118 -1869 -4965 -3462 3040 -3807 -1154 -3530 -2845 1632 -4165 -1901 -1573 -3145 1563 -1731 2478 4232
-472 -4034 -611 1872 -4445 -3782 1209 2974 -4716 -4400 -2395 4153 2508 3292 -249 2383 2257 754 -937 2985
-3646 3299 -2252 -1165 323 4441 1438 4703 2758 -3658 -1035 -3787 -4321 1910 -3223 -499 -810 3067 341 -3130
2708 4019 1516 308 -4182 -1821 1949 2088 1934 -1844 359 915 135 3265 3365 2746 -4639 -659 652 2996
-2442 -3820 4926 -2730 -1719 -1072 -4828 3001 -1295 -3007 -62 -3563 4016 -214 -2262 -4632 -4329 2622 -1538 805
3290 551 -2392 2267 3271 -2289 2787 -3202 -2726 -4200 4695 -2068 4374 2656 -3780 2547 206 -3458 -4471 2637
-2798 1136 210 -1817 3250 1380 -4881 -3740 2400 880 -3820 2036 2000 3069 4988 3140 -1824 1465 718 -2548
-4573 2331 -1182 918 3899 1209 -4387 -1926 2826 -3888 1645 4340 2363 3370 121 2786 4130 1955 -1808 -1099
4780 -3449 3510 -3782 -542 -961 3424 -3886 -4405 2436 2832 2385 -1836 -4108 4752 1771 -1866 3645 -3039 3030
-988 -4481 1346 2678 4816 4249 1009 -687 -1661 -2802 -2412 -3656 -4145 -4118 -2452 -2095 -1051 296 -1649 -333
-2355 423 -4845 -3916 -1756 -4347 -4470 -3578 -1389 4261 -4856 857 -2359 4935 4037 -2656 -4396 -893 -1971 3190
3448 4518 -163 -4861 1477 -3611 4919 4333 2772 3968 -2731 -2840 386 -4896 1992 -1515 -3135 2539 -504 -3184
1481 -593 -1163 -2610 -82 -84 -3737 -1138 1641 4338 -1436 -2281 4008 110 965 -61 -3827 2915 3144 1588
2679 1890 -300 -1529 -4118 -2475 -1042 -4846 -3266 -3971 4871 2505 -4672 38 -1199 25 -4194 -117 -4381 -2684
-928 2184 4365 3591 3010 2852 -1630 -3707 251 1466 3002 4645 -2441 1289 4627 -3775 -320 -1062 1750 -4890
763 -2714 -2752 -4309 1309 -776 -3336 -4148 -672 -2864 -3845 -3443 -3376 152 -3039 3363 4145 50 -3918 -3171
3423 -4585 3522 -1498 -3539 3315 -1978 -3430 3839 2915 1572 -699 1261 1244 2520 4174 -4109 3047 -2873 3904
-709 2469 4627 4143 -1297 -1186 3602 1313 -2454 -4459 4846 -3477 -4256 -3765 1268 -3166 -2676 2750 -4657 -704
-1192 -3661 4413 -3255 619 3942 1901 126 1774 3688 -2574 -2975 -69 2317 -2423 2009 -1862 242 903 669
3240 4766 4380 -3582 -4749 4303 364 2376 563 2683 -193 3231 -4040 3516 -2757 3938 1666 -1625 -616 -121
-2873 1717 3058 1168 2020 2960 1005 -2243 -1573 1951 -3218 -1326 -3714 -3846 -1999 -1429 4450 1645 1148 -2445
-4607 2760 -1007 -3927 -2407 -4828 -548 397 -3384 -3706 -2655 -4133 2279 -842 3763 -2736 22 2162 1064 1175
1524 3589 -3475 1141 3353 -1345 -4760 2641 -553 -782 -3778 -1425 4663 -3208 1437 4427 -1269 -3622 -3608 1933
-3437 3243 3822 2657 3595 -4480 1735 269 -934 3623 -3449 1046 1906 1943 -4853 4698 666 4558 -4897 936
3368 -38 -4290 1459 3251 2071 -285 882 -4863 2871 3246 222 -4127 1925 -955 -542 4914 -2583 -4558 -1496
-2994 -1967 -1720 -605 4020 -3700 443 -3816 571 -3058 -3929 4523 4487 884 -4215 -81 -2077 -4977 4015 1934
-2321 14 -296 2882 -342 2426 2084 -1059 3876 4702 1146 1395 3619 3373 690 2495 -2321 -4953 -1169 145
-2799 -2049 -848 156 -1404 624 2187 -4816 -2926 -2673 1128 1095 900 -720 -1157 -2651 -2201 4926 1465 -3864
-3372 -1373 1015 2801 45 -2023 4758 814 -568 -3918 4571 -4639 -664 -3112 -2316 4325 3358 4281 881 3283
4826 -55 1671 192 -400 3749 -2358 933 3632 -3560 -1576 2544 2664 -781 3726 -3926 -844 2638 4883 2190
-1107 409 1781 -1758 -4482 666 -2801 -2712 2185 4157 470 685 -1859 -1561 -2645 4274 -2504 -2924 -2276 -3664
-3886 -976 -4406 2174 3894 4381 -611 -4259 -3212 -822 2318 -4349 -1198 3620 -2030 609 -4563 2977 -402 -353
-1737 -4737 900 -708 -3140 -4092 2169 637 -1263 -1826 3824 1709 -3644 1991 -1214 -366 -2852 1945 2896 4253
-2671 3705 -3783 1721 1353 298 2651 -71 971 4502 1744 464 3532 2788 2973 -2646 4826 4867 3359 2446
1663 -778 1914 -516 -667 -1113 1370 -2411 1344 -4304 -2642 -1467 3351 466 -467 -678 3269 4728 -485 2870
-685 -3262 -402 2201 1738 3572 -3140 -1159 -3557 1123 -4862 4682 -1511 -1557 904 900 -3333 4035 970 2641
-381 -1018 4164 121 -2710 581 968 536 -877 4154 -2769 4416 -4066 3229 -1969 4081 4142 -3946 -584 -175
2052 -1718 2202 2049 498 -3494 765 -3890 2471 537 4470 2547 3236 -4754 4960 844 3145 -4213 -3244 4073
785 -137 -1197 1228 -2006 2519 -437 799 -96 730 727 -4927 -2191 -229 -2197 1722 4312 36 4985 -1845
4443 1576 1983 1975 -1139 -4139 -432 -183 -321 -2225 2405 -1549 2991 268 3533 1438 3202 -3922 -2475 -534
1580 1269 -3762 2377 3051 -2150 3731 -890 -1687 -2688 4502 4622 4957 3254 -2078 -1010 1535 -1646 27 -1795
2720 -4828 -838 -1188 3619 -4845 -1078 -1137 -947 552 1109 -1648 -4725 -1512 802 -1742 4408 156 -435 -1252
-3151 -116 -3105 -193 -2346 -1372 -4592 1051 -1326 -4988 1892 4791 -4029 -798 -455 -2733 -1597 1566 2025 -2804
-923 3585 628 826 -361 1654 -853 634 1945 -2767 4013 4018 1559 -2104 -1027 -4005 3858 396 -4424 1723
-1604 -809 -3550 -3492 -3423 966 -2984 -2323 -3215 1457 2619 -4595 1568 -3171 2876 1212 -1968 4390 3597 3085
4178 -3079 -280 -1269 -434 -11 -4472 2139 -4679 -4980 -2004 -3306 615 -3321 -1180 -1227 -4741 -4616 -4750 1496
-3957 3475 1693 -4118 1652 -1430 -4469 3897 844 398 -3058 -146 4274 -1916 2705 4172 1491 -3418 4998 798
-2043 4077 -60 4782 1708 1526 -2779 -1811 2473 -3992 -1033 1233 4577 -1902 944 -818 -3478 3582 -4472 -559
-507 -940 -1213 3984 3878 -2343 -4514 -1011 200 3192 986 2490 -3250 -2833 -3432 -1823 -4610 -3944 -4783 606
781 -3273 2624 -729 2656 1034 -2098 999 -3918 -3240 -3476 3932 -1228 -2409 1987 2793 -3262 -3433 2055 1747
1960 4819 -3579 4095 -3957 2910 3630 406 -264 3210 2098 -4103 3461 4633 -4221 -2364 -2441 3398 -2650 -4431
254 1730 -2600 -1666 -3112 908 1333 4888 723 -3617 -1070 -2027 -4093 3070 950 -3269 -3308 3746 -2284 -2022
-2808 3653 -2312 2675 -669 667 -324 2711 -1095 -1939 4752 -762 -1611 -435 -4469 -3375 969 2895 -2186 4763
-4961 622 -2388 4445 -2007 -4238 -1258 -1310 2330 438 2055 56 -2290 4400 -3149 -1426 -3529 1503 1945 4310
4633 2310 2423 3448 -1103 -992 163 1945 -306 -1043 -176 265 2279 -4967 -1741 -1938 -4740 -1764 4194 4933
1161 469 -120 -1480 -4923 960 3378 1685 -4996 -2756 -3978 3836 540 2682 -726 -316 -3283 1690 -3091 -4901
3599 3224 1123 744 -3824 -2379 -711 4712 4011 -3698 -3081 -3967 1899 -2435 2798 1063 -1811 2552 -1005 1246
-2701 -528 3205 2581 -2897 -1447 -4501 -4856 1855 -4979 3648 3834 3327 4498 2836 -3090 3156 -4191 -2617 1489
1373 -4978 970 -465 1591 -1126 520 4062 1030 -2608 -4389 -1472 -4666 -3987 -1544 -1689 -2276 -4281 732 4542
-125 2384 2062 -3253 2290 3762 2298 -575 2285 -4713 1813 3851 -1739 -1500 3361 -3915 -4182 3996 -2220 -3586
1702 -3898 -1916 2890 -4419 967 4439 2225 -4760 -3461 1176 2598 -4674 -177 -1311 -1931 3207 4205 6 -4558
1168 1804 2262 4271 -1306 1242 -4355 1841 -3672 -4204 3259 3158 -874 -2061 2635 -4657 4089 3894 -1264 -3613
643 72 3028 -926 -1700 -1625 -2177 1762 3699 -3914 4766 1991 -3671 4628 1117 -2398 -1536 2478 -372 -1019
-78 1799 -3373 844 -2387 4743 -4186 -4764 -2138 -4676 845 1151 3926 2970 -2081 -767 3396 1741 -1280 -2886
3730 4137 -600 -4536 1004 1040 -4994 2809 539 -3547 1041 1016 2825 -796 -2902 1751 802 972 2899 1725
591 -4550 -3855 3170 -3526 688 -1958 1894 -1958 -4280 -4652 2331 -614 -982 -2963 -1385 -2678 -1338 1738 -2935
1395 4525 3949 -1215 667 -2853 -697 -2884 4049 4360 3255 -3964 545 -2399 2641 3796 -251 3323 -263 -2103
3625 1524 4546 1994 2690 -4772 3609 4615 -1574 -4519 -2564 1177 -2604 2746 -4676 531 3573 -2025 -2656 4787
-2507 1440 1302 -3626 1532 -1127 2790 3539 -179 2926 101 -817 -3981 2864 -2326 -3585 -4566 2303 2969 1474
4236 3136 3218 909 -1737 -1222 2647 1882 2030 208 -2485 122 -3133 4545 3821 765 -3363 1317 1054 -3765
4419 576 -4339 3894 4275 3671 3282 406 2482 -1226 -2572 -4743 -1579 -1869 266 -2390 -2924 2442 -2626 -339
-4113 1855 -3613 -4727 -4477 -4051 3284 -2452 -2046 3345 -3746 -4526 892 -3066 4405 61 3236 1934 -2727 -1667
636 -4382 2147 3550 -1727 2212 -3225 -734 -4227 3374 3112 -753 -164 -3890 4980 -2768 -4685 2170 -1676 3402
1213 698 -3439 -1871 4096 767 1652 -3683 -1188 376 -2290 873 -3186 -163 -167 3326 -2880 -3221 4823 -177
2696 -2208 -3409 -4630 971 1726 4085 -3251 667 -704 1075 -578 2840 1416 1423 435 -212 -413 -225 -4865
-1149 -3401 961 -626 -3541 -847 -948 -4655 3005 1448 -3692 1238 -2831 -3683 3136 4690 4394 338 -1378 2149
4937 -1697 2093 1665 -4631 -2586 -694 2891 -446 3717 3321 -2345 628 2687 -2089 3256 4219 -1964 1185 -4046
3673 131 628 -2529 -4752 3080 -4699 850 -4983 -869 -393 2413 2060 -160 225 2998 1162 856 2013 2333
-274 -460 4690 100 123 2696 3323 3440 -2667 162 -3270 -484 -1464 -4980 -3791 1537 -3059 -2945 -2362 -2521
1766 -754 3149 -4398 2893 -4925 -1859 -4656 -3286 3324 1559 1468 -4772 -328 -480 2113 1112 -248 3071 1062
-2792 3918 -1695 4337 -64 -4682 3062 663 -3527 -1460 1157 1561 -2171 4529 2735 -3680 2698 4613 -1184 384
-749 -169 -2826 -3772 3001 -2766 4377 -4457 -719 -2409 3370 -2459 -4117 -4880 -1502 667 -2024 2371 -2393 197
1453 755 4059 -589 -3449 3822 -3278 -3395 4687 2810 2974 -4508 -3049 -1776 -442 -4384 -1680 609 -3920 -1219
849 -28 -3715 240 2214 883 -307 -4555 -4716 2991 4869 2559 -153 -3460 -4936 4764 4595 381 -2338 3323
3505 -641 4454 -4337 2101 4971 1865 -3443 2442 4246 4814 4612 3302 2507 -853 1258 -4020 -1300 2405 -2161
-2579 -572 -143 1401 2478 400 -675 21 -251 1614 2055 -368 583 1252 3085 -3917 -4493 -3735 898 -4030
1525 325 -4358 3697 4502 -3632 -3836 -66 -3467 4302 -1507 1000 -4716 -3947 4056 -2191 -2008 -3072 4646 597
-3395 -3565 -772 566 1941 80 -2596 -4885 4968 1301 1044 2886 4637 4469 4247 -4906 -3797 1641 619 2578
-673 3411 -4278 969 -568 4877 -2612 1028 4087 -3640 -4630 2665 -3613 4765 4775 1527 3099 -2846 3220 901
-814 -822 3088 -2387 711 1462 1262 480 -2357 2542 -3591 2727 2189 777 3384 1781 662 4072 4702 2259
-4122 -4215 2371 -1516 -3207 4931 717 -3655 -3869 4616 310 2995 741 -1559 -3823 4132 -2191 -480 372 4148
1126 -2556 4076 -4961 517 1101 -1485 3984 -4265 -4289 -3670 1257 3728 -1862 611 -3342 17 -386 -4893 4545
3822 -3723 2689 -2297 4206 826 -621 2542 526 -3572 3238 -1600 3995 3559 1336 788 747 -2958 898 2179
-1065 73 -341 -2200 900 1417 3796 475 2105 -1920 2939 -851 4493 2434 -4003 -3175 -3518 -2089 -2741 4257
1847 1796 -2180 -1515 2341 3865 2701 -4616 -2434 -308 4995 -4745 1604 2227 -378 4785 -4238 2776 3563 -466
-2624 2665 -1548 115 3258 2591 -2317 3721 -1953 -2640 -4652 -1413 -844 3775 634 2076 4957 -3851 2492 717
3178 4506 -4892 1123 2282 -985 1708 4652 -5 -341 -1245 -2625 -2453 1767 -2533 -3147 -2571 -390 1898 -4704
-1217 4575 -4097 3837 -2340 -1226 -4761 -2610 -49 -2520 -1275 1943 -461 924 -4841 1253 3949 1984 -480 528
-4711 -1431 -4194 -4025 4793 4524 -1574 3045 -1813 -4522 -3664 4226 2328 -2184 4070 4031 615 -1523 -3799 1322
1183 1721 -617 2162 2185 3336 -1837 1731 -492 2525 4026 3069 -1370 1034 -625 -4904 -3650 4721 -1453 -3518
2017 -4440 -1137 119 -1568 -1646 -4716 2987 2607 4827 -2705 -1000 -4185 1211 44 -1686 3308 -4273 432 3440
-4221 -1403 4580 4415 -138 2418 2119 4116 -3503 -3706 621 -3416 3211 1611 4180 -2067 -3442 -317 -3051 -3990
-779 -873 -1453 -215 -88 -4284 -1445 -3541 -1353 -544 -1474 -1994 -567 -760 4344 1873 141 -4524 -1755 3386
-597 -1753 1903 4691 3475 785 -3867 1555 -1854 -499 4255 -3263 3648 -293 -3711 3895 -2979 -322 -3200 1547
-3835 84 -4661 -4743 -2544 -2922 2467 3865 473 221 -2066 -38 -2006 -589 2570 -1283 1874 -665 -3301 1341
1890 898 3300 4787 -1879 165 3926 2011 605 514 -3991 13 -2316 -1321 833 1618 -2748 2507 -125 4070
-4404 -2317 -3070 4663 -1414 -4210 2388 -2120 -205 -3060 -3788 -525 601 683 452 318 -4476 -2266 -3273 -2526
2773 4348 2083 -4519 -4566 2701 3278 1566 -57 -4466 2173 -3413 -4047 146 735 -4212 3002 -2010 -3750 -4949
-4331 1075 -1662 -2078 1797 3351 -1821 -2246 -3639 -2288 190 4119 260 4498 894 3116 1916 1141 1174 1635
3012 -118 -568 4828 124 -2363 -2809 2551 3823 -4308 -1395 4700 423 4651 -4653 -1952 -4985 2848 -2035 143
4697 -1360 721 1406 1229 -12 4347 -3662 2340 4549 4663 -1795 4842 -1710 -4937 -1417 993 2811 -675 -987
4784 -3935 710 -3472 662 -293 -3563 1357 -2817 -4155 -4404 1271 -1031 -22 801 1457 2606 -1798 -2196 -1949
150 1279 -3555 2269 -3537 -1676 -1699 -2520 3207 -2250 1163 2523 4608 3169 918 -1153 -3619 -1825 -2356 922
-539 226 -2500 2132 3902 -3804 4940 -862 2574 1263 -2404 -98 -281 1990 3995 4553 -354 -4442 4790 -949
-2172 3128 1232 2373 -1751 -1231 -2272 -1653 -2163 -2511 -660 -423 3623 -73 4350 4306 -1568 887 -4875 3626
-3621 908 4842 4261 -2626 3399 677 1045 -4286 -3750 -1088 -4251 -1688 -3844 -3664 -1448 -1352 3033 -3572 587
1655 -4770 -140 2899 2259 -3341 3364 2798 4894 4417 1526 4198 2343 -3035 -684 2357 -4470 2521 -1459 -2912
2145 1095 2856 3277 4681 -60 -847 1354 2440 190 -2046 -4376 1854 237 1000 -2059 -2322 -408 4508 -4553
-1122 -3681 -3500 -2840 966 1103 -4953 -355 2041 2538 -3595 4329 4154 2128 4089 2455 -444 -1924 3250 2751
3106 -1029 -3920 4143 -2166 2233 -511 -2006 -300 924 3858 -3005 518 -4153 1378 -2595 1785 -3227 -3663 4454
-4412 -3029 -3444 -87 3467 -499 -496 2524 -2431 -1865 3830 -4239 -3552 2769 -3523 3825 4028 372 3849 4029
4102 3146 -4950 -3787 3289 2939 -1317 3892 -4197 2801 4661 -3560 4220 4517 -2585 315 -2118 -439 2268 -3161
654 -1296 2482 4185 -4602 2130 1621 -4048 2815 3431 -2244 4631 -2269 1235 2418 1380 2225 -4005 195 2731
-2827 188 3482 3697 1649 4444 -1616 -4640 1522 2077 -554 1752 -1646 1198 33 -722 3753 4906 2740 -3070
-3333 4999 2393 4233 730 -4257 4492 3174 -1964 -586 -4403 -2032 3595 2578 -3918 -1246 -1311 3659 -3518 -2893
-2149 -118 3526 -984 -1191 -1035 3162 2576 705 3478 -2481 286 4613 4861 145 -469 -3441 -3926 -2307 4217
3328 -565 1431 323 -818 -3722 -372 -3928 -1261 -2464 2895 -859 -1587 -992 -361 346 -3091 -511 852 1179
-2413 4422 1198 3938 2886 1439 706 1821 967 -3428 -577 1584 3033 -1984 -884 549 974 -935 -3531 -4369
3894 -698 3256 3587 3824 3500 -2418 -3105 -1915 3077 -2750 3705 -656 -3462 -2344 3403 3018 975 4760 4835
850 -3634 -1093 987 -1534 2104 2068 2846 4453 3718 -1092 -2562 468 -2831 2373 -3957 419 975 4466 -2994
2048 -1403 -1398 -2448 4811 -3437 -2248 2357 4142 4438 -4511 612 -4120 1978 2884 2592 -3461 2896 611 3931
-3909 -469 1718 4713 2531 -3395 -3813 4670 1165 3541 -2497 -2281 1782 4108 1242 -1529 -1814 -3001 -970 3303
-3276 -1987 2932 1937 -3306 3567 -474 -3902 3380 -88 -4106 586 3627 -2096 -822 -3511 4817 -586 1976 -4660
4502 -694 4127 -1763 -4778 -4116 -1939 2640 3510 1749 2066 -1344 1527 -4746 -4370 4739 3644 2848 4374 -4058
-205 -2180 4418 -1580 2171 897 -848 2426 936 -3286 46 -2848 -1134 -3748 -3228 -4516 2145 -3564 2371 -1053
1053 -2931 -4161 1982 439 1082 1749 -4516 4585 -3168 -3692 -1438 -244 -1364 -3716 -3897 -717 -4608 886 -1879
-4889 -4723 2515 -1067 -441 631 4480 3039 -4576 3737 2937 677 2153 3859 -2222 -3396 -596 -1006 4715 1532
334 4011 -4001 1451 4223 -2615 2572 -2189 -3212 1040 4206 3609 -3104 -3094 582 -3408 -912 -3058 -468 999
-980 -3997 -4355 -1015 1384 1429 -4052 -1217 -2614 -1095 4799 674 334 3166 -1158 -792 3631 3835 -4531 -375
-4778 -52 1528 -2978 135 2508 -3982 1484 861 4286 1928 2298 -2971 -1497 4863 2596 4706 -1394 4381 -4373
-3701 3140 3861 2432 -4177 -3912 1420 -1901 686 -1364 2451 -2145 95 -4643 4327 -2379 -2676 4197 1315 -620
-3195 -671 617 4497 3785 3573 -819 -2000 1726 2953 2077 -4029 2285 2211 -4115 3052 -2326 940 -618 3524
-3465 -1668 -1143 -3732 2020 -2653 -3858 -1522 927 -2627 3855 -1698 -4246 -3525 2512 514 1716 1859 977 990
265 -4560 4083 -740 4963 -4658 914 -765 2336 16 2401 4120 1297 -3547 -2037 -2963 3779 -4084 1565 -3128
-1485 1399 3410 3430 4580 -2879 -1130 2086 2034 -446 -4925 -691 -2118 1173 -195 2733 -2034 -3920 44 1302
1494 4993 -4434 -276 4401 -25 4089 1338 4792 -800 -1775 -909 1491 -905 -3452 -989 3914 1803 3557 -38
474 2541 1049 -4690 -528 1687 -1651 1859 2571 253 -2684 796 -1617 -2360 767 -843 -3256 -2002 1383 1873
4654 1942 1805 4207 -3413 464 2901 -1247 4088 -2521 3690 -2307 -3711 -3642 -89 -774 -4095 -4434 3696 4499
745 -4944 2046 1983 -2938 2532 2590 4758 4188 -1175 2349 4670 3631 2559 3558 3044 1146 -3927 -4866 2714
3699 -4805 -1252 -400 -3151 -517 -2341 729 -4769 -4156 1774 -3006 4402 1993 -3078 3707 2541 3299 -3376 -3361
-1560 -1040 -1791 2134 2247 4774 706 3880 4215 -3570 -980 -305 3565 -3812 -3992 2413 1287 3967 4244 -313
-1820 4514 -3033 3418 4609 -2506 1993 -1233 1554 -209 -1370 -4493 2273 1748 -2303 2344 -734 -3455 2449 1445
675 -232 3196 -4205 1032 894 4926 1741 3829 651 1208 -1316 2456 2193 -2357 -4555 -269 -3624 -178 -2327
1707 -599 -909 1843 4439 -3925 1237 285 1739 -422 2142 -4341 -3537 2564 390 1654 3151 3707 3801 -1898
-1205 4738 2265 -2887 -4552 4213 4206 -1166 -556 -298 -1036 -3244 -3182 3265 -4205 4618 -1573 2908 4074 -2282
-1485 2011 -399 -4903 -4377 -652 -1541 3250 -3764 -891 -1176 3526 3427 -3086 -512 3812 -2278 679 3275 -4538
-4106 -1746 -4483 1196 -129 2785 -462 4888 556 -1504 -4708 -1725 3901 -4597 1283 -833 3540 -4696 -2194 1120
-3040 2461 -854 3820 2316 -2584 2042 -46 -4876 -3954 -3683 4400 3839 -1901 -783 4833 3605 964 -3431 -2175
608 2833 4758 -776 2601 4553 -2716 4275 -4351 -2980 -4466 -2687 4378 -119 4739 4986 1580 -4995 -527 4765
-2314 36 4229 2585 1807 -1373 4193 2485 -4422 -3090 651 -502 4572 535 1671 2597 262 3115 -3350 3159
-3066 2141 -3100 3799 3835 3357 4848 -1624 -4096 3061 -4439 4949 -1290 2166 -3804 -1151 32 -4709 -2831 -4868
-1677 -2988 2901 -1862 22 440 -3029 -620 4815 110 1972 1581 4039 4047 -3305 4209 -2186 4639 687 -3688
-4475 -3746 -505 868 -2501 3612 2850 1930 -1618 -3867 2145 -4959 -1573 4128 -3879 -1422 -31 2352 -3598 -18
-3568 -621 1688 1879 -2791 4488 2587 -1906 -4934 -1475 3056 -1989 -321 -1282 -4418 1041 1025 4796 -1284 -1695
4269 -348 3467 3722 -2534 743 -4296 -252 -1257 -2567 1799 -2697 3630 -4579 -2411 402 4518 -3367 4036 4326
-1414 -4893 -4179 1929 2728 -3804 1512 4674 1164 -4215 431 596 -2604 -1883 1835 3440 3080 2910 -4221 -1892
-4325 2040 1945 2937 4532 -4260 -3622 514 -4842 -2941 2172 2363 2232 -1113 3642 861 -1995 -175 3751 -4310
-2043 1102 -2376 -2272 -2881 4828 95 -1803 1429 1521 -3459 -4640 -2753 -4548 1402 2456 1230 -3899 715 676
1198 2280 -4124 3815 4838 1093 -1542 -1210 3082 238 610 -1724 2227 -4255 1280 -1184 2755 -166 -2487 -3452
-4125 3445 -657 -502 -3933 -3927 1247 -910 -3498 -4474 645 -1680 4406 -1072 2050 -2023 2796 -4310 -1135 2985
4194 897 768 -1952 -1934 1673 -524 -2321 4918 -232 588 69 2130 -3489 -2730 3300 1474 2288 -4913 -1037
2701 2311 453 4927 -856 3207 1002 3553 1418 -4337 -126 -110 -2771 1804 -2931 1159 3733 -1233 -1170 471
2037 -4701 1420 3230 2995 -4006 2766 4031 3826 150 -333 -465 -1286 2619 -2950 -4425 -3307 2560 -72 -4404
-2540 2726 -4026 -374 3717 -518 -1562 -3617 -3638 3463 -1879 -1227 4223 1425 -348 4254 2175 4819 -3692 688
-1342 -2974 2011 547 4948 -2646 113 4854 4578 -4566 -4017 184 -2324 4307 4298 -3119 2662 -1101 -2030 -2431
664 2095 -4469 -4444 -2066 -4599 2614 2040 1651 -2004 2244 2582 -3286 -4191 4835 645 4562 675 2779 -2881
3711 4158 -4104 -2282 -419 -263 2467 -2239 415 -4774 4141 -4336 -1603 943 -2609 -565 2872 4431 -106 4547
1651 -3104 4790 4971 -217 -1331 -4690 3673 -2061 -1800 115 1848 514 1405 641 -2111 422 -3801 2749 -126
415 -1843 -3695 2425 1517 3081 1623 2131 835 4257 -148 4420 3226 1663 -2656 2935 4061 -2657 4590 4300
-2662 -3756 -1633 -431 -3617 2105 -2368 -3395 -4141 -913 2697 4897 -775 -2566 4646 4024 -2149 -606 -173 -4104
892 2554 -2661 -1893 -1955 -4695 735 448 3657 1694 -2315 3953 -3275 2398 3877 -2632 -983 -3544 -432 244
4162 4212 1836 3893 -2024 -2439 2954 4668 3637 4137 3447 4875 4447 2077 -352 -2413 692 386 -2709 4962
-1608 2766 -3042 -823 -516 3166 -1209 186 -3249 4581 2739 -3207 4474 -2898 3121 -4278 4026 -902 -1269 2846
3797 2727 -2844 -1939 -2661 1594 3173 4689 -4913 3947 4672 333 -2433 -4091 -4658 1085 -53 3954 -291 -3294
3542 576 -4292 1466 2468 2793 3220 3744 -4542 3416 -2508 2537 -2947 -4910 2663 -1471 -2764 -3290 -990 -3843
-3679 -4367 4821 4712 86 2499 -1962 -4485 1708 -4415 -2178 3525 -4032 1590 -4569 4007 708 3887 785 2576
3383 -2412 -2661 1710 4094 -2141 -1860 2893 1538 -3420 3983 -3022 -456 -3774 -638 -4796 -300 2910 1995 -874
1316 2458 4627 -4604 2613 1969 2063 4823 3960 3449 -1489 754 -396 -4601 -3305 -2961 -2675 -4595 -2858 797
3289 -4090 1615 -1376 -255 -1815 4573 -4857 -272 2293 -689 -4173 2197 -469 609 1806 -4741 -646 -2209 4183
-4397 -4870 -314 -3034 3888 -2671 -750 -3515 -2782 -337 1738 -4395 -3216 4661 -946 4106 -594 -431 -1467 -3522
1367 505 -3922 -4556 -4809 -828 1866 -2229 -3917 -3790 161 -2030 -3133 -3927 1735 4313 -4017 3189 4618 1685
-3143 -578 2979 -323 1916 798 2444 3764 -2157 -3158 3761 -1213 4823 4250 -3619 2076 -2103 427 -4441 1377
-320 -2959 -1934 1753 3770 -4541 2170 1328 -164 2692 927 1314 4922 2685 423 -1626 2563 -620 -4874 -2010
3522 -1212 2710 -4059 -1475 3125 -4158 -404 2009 -4864 1150 -4753 2373 1281 -2121 -82 801 -2105 -1163 3547
447 -4113 -1925 -2723 3591 -711 3010 1661 -101 -3712 -3048 -3347 -3430 4410 -2589 2646 -3515 401 4515 4368
3497 -2460 -2313 3527 1450 -1938 1285 4560 4542 -4729 1876 1305 4741 -2295 -1148 2831 -21 -2655 -546 -4438
4578 2184 3428 -329 -645 2347 -1421 -1024 766 1702 2948 3428 -1502 -3056 70 -3577 -1462 2624 1552 -4767
-2296 -966 -4255 -906 4758 697 -2537 -206 3657 -3228 -3938 -1710 4646 1482 2522 -1060 1944 -2327 1994 2435
2786 -48 2161 557 -1525 -4996 -1259 -40 3825 4260 348 1250 2024 -2193 -4906 -3012 3631 -1100 1617 -4074
23 -4144 -2918 238 120 -3907 2383 -1569 -4066 2233 282 -4682 -3791 -2899 151 -1452 -2465 -315 1261 3532
4048 -4145 2031 3278 4127 4574 -4890 2225 -1140 -3175 -2253 3038 -2710 3360 2220 -2631 2179 1999 3100 1671
-4288 -293 -1928 3108 -2560 4516 -3035 -38 2258 2265 -1926 -1814 -1409 539 -899 819 4004 -2501 3527 -3413
2465 -4841 1627 -4455 -1691 -1484 4374 2878 -3959 2796 3001 -1544 1662 769 1673 4016 4576 -3675 -4985 -2767
734 1127 -4017 -721 -1279 -1927 4490 441 -4088 -3782 854 3358 4707 4276 2959 -3926 3460 -4303 -3975 1060
3216 1625 63 -2038 4805 -2505 -357 1026 1519 3844 3673 -2862 1038 542 3905 901 -1646 4670 -3955 3602
-3386 4238 825 -969 2817 953 1675 -2700 4907 4884 -913 -1231 -1278 2385 -4216 3862 -38 3515 -416 3700
-737 1639 -4696 3427 -1425 -786 2010 -4986 974 3983 -3195 1657 -2976 4798 1075 -4340 1121 4430 -3892 2894
-3455 1707 -988 2000 -1685 -3017 -4144 -3249 1361 -935 200 131 267 -859 -1574 -1894 3941 3021 3940 302
-4051 4884 -2436 1349 3961 -863 -2576 1055 2749 559 -4328 4133 3013 1536 -2300 2374 3006 -1964 -3340 4981
964 1248 -2684 -1909 1447 -2996 2131 -3917 508 -4284 1730 -1501 3123 3870 -2018 -1879 -342 -2652 3722 -812
-1005 -2985 4585 4623 1410 -1336 862 -1364 2785 -4090 4130 1537 695 3539 -908 4071 2609 4432 1630 4859
-401 -126 -197 3204 4847 -1858 -612 -747 -1291 -4706 -1708 -1027 -3550 2706 -1273 1277 4842 -3250 4834 -1051
-4878 3394 2219 2869 -1761 3676 3280 -2088 -3021 -4550 2881 3603 -1595 163 4181 3873 3957 -4640 -3467 -1684
3207 -1139 -3453 -3722 2953 -4868 1536 -3678 377 988 3585 -2325 3147 2879 -4415 3604 -4940 -677 -345 4808
-3788 -2395 -3290 1488 216 -4637 -1983 -2218 2530 639 -3046 1260 -4436 -615 1010 2107 -2852 778 2512 -2137
-2009 4007 -1190 4290 -4536 3448 -1075 1172 -2692 -3230 -710 -1432 3521 1713 381 -651 -3773 1246 3654 -1587
-2119 -1656 -3615 -751 3196 -4630 -4501 1515 768 -3698 2509 3924 -354 1471 4951 4549 -1881 3068 1020 -1365
-594 -2808 3025 -2877 -77 3182 -322 -1791 -3677 2848 -2586 -1988 -547 1472 1830 3238 -3956 1006 -45 -1039
4853 3003 -1078 -4112 2087 -3256 4214 -2234 -1650 -4936 1929 -1282 -4331 2089 -4697 -1109 -1719 2314 -426 4028
-2701 -257 -4048 -4794 4439 2243 233 705 -4344 3600 -3727 775 2923 4978 1936 4324 857 3552 -3449 3015
4408 -1963 -1298 852 -3039 -1280 -550 -2696 1220 -2480 4158 1193 3083 1659 303 378 3469 -610 -1933 -3531
-1717 -3397 -993 -3737 -4834 -90 -2445 338 839 -284 -1175 -3043 4521 -4204 -2228 -4121 4375 -4386 4012 -2032
1122 756 3957 3280 18 2435 -193 1499 1878 1953 2037 3636 2866 -3927 -3097 3101 1986 -969 2713 3544
-4389 -2863 -1788 2670 -637 3850 -4345 -1618 2785 3017 -3469 -3543 2709 -1084 -1893 -4135 -1279 3716 2741 535
-4896 -2792 -181 4805 -4606 -1149 -4298 4116 -4381 1718 628 -281 947 4779 1167 -3656 3142 4844 787 2681
-2597 1404 4569 4653 -2583 2474 4238 3703 -1487 -4429 -4952 2728 3137 -4980 2006 -586 -2086 2480 -521 717
-1887 -2850 2478 4428 -1304 -2517 -3853 367 -4498 -3409 -3181 1705 -4840 1469 1061 -3454 1272 3631 3557 1337
2935 4737 1771 -2383 -366 545 2436 4537 707 -3611 2013 -3179 2314 -4463 668 -3259 1509 -2264 3588 -3570
-1704 1418 -3677 3257 -3053 -796 606 4024 4549 -286 -3972 3960 2194 -2434 3472 -574 -2245 -830 2970 1703
-2161 -4818 -4965 -1963 3754 -4234 -1724 -4961 -4220 553 77 1243 -4115 4657 159 -4164 -4608 4983 4233 3355
927 2688 -184 -1813 -2322 3107 577 4448 3635 664 -4524 -297 -4054 -4746 1173 -128 3780 4195 2965 -1739
-3075 4536 -1454 -309 1676 -2115 1857 -1494 1191 -1190 -2631 4456 128 4733 -1869 -4608 448 1641 -3662 -3895
736 -1865 -4289 -2779 1164 -3032 -4496 2753 623 3782 -512 -4709 4857 -83 -3263 1292 -2923 1887 -2636 -4534
-234 2140 -1151 -2187 4200 -494 1035 570 4853 -1310 -3214 -4189 330 -3580 4796 -2442 1315 -4148 -74 2835
3320 -2904 -4432 -4165 1901 619 -2891 -2667 3020 -4511 2471 -903 2328 1105 -2555 -3666 -4212 2305 3765 510
-1473 2276 3774 -275 -445 2713 -890 4067 3894 -4638 -1283 -1064 -4075 3105 -4529 -530 -263 4775 -3175 4804
-3340 -17 -4746 4510 1137 -2533 -2042 -2796 -639 -3401 2271 630 1898 4073 -4545 1700 255 2039 2530 -124
4198 -3700 -3423 4932 -3054 -4334 -1627 1377 750 -1723 -4167 4659 4971 4582 -3253 -3478 -802 -4406 -215 4834
4186 3998 59 -2371 -193 -2506 -866 4009 2155 -132 -4705 -1435 2877 4155 -252 -4033 4283 -524 3527 -1112
3087 1555 4142 1010 -3153 -1665 -4837 -4703 1256 1480 -4769 -1928 129 -3238 701 2786 -3289 4696 -3307 -1830
4153 4636 -835 316 -3575 -3850 1247 226 -106 -2404 2683 1965 1562 1929 4453 697 4409 -4892 -3570 608
-949 -1885 2169 -4941 -4125 -2552 -1768 -4126 -2738 3511 -3877 2011 -2007 -2315 4105 -11 4694 4410 -1515 -2880
2566 524 -1861 2505 3810 3620 -3870 571 -3101 -1493 4431 -3801 -2371 4930 -1491 3802 -3287 -2249 -552 -466
-1945 -1999 253 3921 -930 1775 801 4742 925 2739 1040 2018 -3068 -299 3483 -2389 1681 735 -902 358
3241 -4094 2563 4195 2852 4629 2618 -3856 -3903 3150 4496 980 -746 595 -1978 1929 1753 -658 -3515 4024
-3337 -2193 128 -4932 -3987 -3248 4010 -3614 -2433 3284 3722 302 -4039 -4830 -1013 -95 -1027 1846 -1471 -3903
3030 -2725 1629 591 4189 2857 -565 -1657 -583 -1890 3101 -2775 3632 -1949 2877 -671 4711 -908 -3217 -2154
-2523 -3869 -3673 3409 594 2002 4110 1999 -2634 -14 -852 1776 2739 3278 -2234 2687 -1702 3541 1658 786
2845 -2230 -2131 -393 2811 -1699 -4026 2096 2721 -1258 -2671 821 -3481 -3182 -2324 734 -4222 -4445 -2407 2510
808 -749 -1354 2044 3410 280 3249 1848 1988 1102 987 651 927 -1594 2240 3676 651 -3254 -2811 2211
-806 1473 -3160 -3918 -2185 -597 -2831 1646 1668 1196 4038 -3490 1213 -4895 -1588 2885 209 1259 -4843 -2899
3371 -3453 -2097 3812 1488 -3800 4150 -4584 3320 2628 -4465 -94 -1800 3105 -1054 1541 2930 1988 2045 -1215
-3344 2022 -261 -3272 782 -747 -2751 1786 -2210 -1154 -3694 -790 410 -1224 3584 4172 -3686 -4444 -3527 296
-1172 611 -442 -894 -1597 -3978 -4185 948 3904 4533 2794 3549 3820 -2177 84 -4916 -2680 4680 884 1751
-3073 4044 -206 -1088 3984 1414 4780 767 -227 -1880 -2527 -2643 4156 -1159 -2970 -4733 -1617 -3518 4185 -487
3095 -4583 1835 1902 -3818 4059 1651 -2838 1033 3051 -1438 3416 1021 -350 -2485 759 934 -334 -321 1017
4328 3878 3899 -1148 -2650 2883 -1296 -2159 -2442 -1334 4767 -3154 -2824 4796 -1216 -2306 -2534 3109 -4669 2114
15 -3647 -854 -1152 3863 1381 2127 -4281 -729 -2015 1085 -1974 -3775 -1029 446 -2001 4168 4196 4767 4006
4666 4753 2253 -2677 -989 1695 -2020 1527 -4977 -2654 -4201 -2970 -422 2506 -1663 -2127 2028 2887 -407 -2637
4586 2570 2503 -1921 -3702 2541 760 1404 1482 -728 -3525 3282 4619 -2180 -265 -176 4822 -3479 3794 -339
2817 2442 -4893 3316 3501 1022 -3410 -733 1380 -524 2449 4239 -2377 3830 -915 -4456 -1228 -274 -3631 -3310
-3249 -1660 -2286 -2574 -4059 -4075 4201 -1811 387 3844 -2364 -1676 -3718 2274 -1940 -4341 -3183 -842 -1396 523
-107 -4958 -788 -2568 3409 -4673 -3947 2608 2870 3895 -3472 -4211 -1137 -4115 -1353 1308 -2640 -4737 -2508 1350
2120 4244 3823 -601 -4517 2838 -1468 -1402 767 -2700 2446 -3154 -4188 3993 247 -3402 420 -4390 -3249 1969
-1954 -1039 236 -1637 -363 4318 3334 -1222 -2294 2563 1416 102 -3297 3795 -1946 -2952 -3126 -3212 3944 424
2014 -2801 644 -4433 4429 -2267 -4190 -4755 -3543 3340 -4903 -2994 -2104 -4277 -1670 3693 -4705 1827 3355 8
2765 2846 -4013 -1301 4625 1778 1247 -770 -1084 4189 -4664 843 1525 3772 -3386 2824 -2895 -2689 265 -3236
-3180 -1761 1999 -4645 -629 2682 -575 -4547 4684 -604 -2585 -4841 -3290 -2129 -2172 752 -1077 -3980 853 -4114
-12 -3872 -536 165 -2288 4728 4649 -4909 1249 -4457 3864 -535 4600 -1016 4904 4666 4243 -962 2012 -1890
4746 287 1822 -4422 -1140 1350 1222 -2555 -1684 1190 3044 -1069 -2702 4167 -2969 -979 4227 936 2184 -3244
1200 1615 -4856 -4067 -3759 2402 -3721 -4539 -3587 -1771 3108 -2622 -618 675 -4829 1511 -4054 2536 3469 -3984
-1379 427 -332 -1574 -1315 1315 1331 -582 3971 -3645 3430 3343 -3706 3431 298 -1860 4781 5 -2470 -2875
-4647 -3536 3853 -3906 -4318 -4736 1783 1441 -4457 3462 -4735 -4904 3323 -4337 -2200 4757 4372 520 -2546 -2402
4198 -1311 -1201 -2021 -1683 2224 1205 -936 -3368 4901 1934 -4599 2807 -173 723 -4786 -2016 3267 -2516 -1969
4020 4344 1049 -1049 1742 -4476 3616 60 1331 343 4729 1518 -3731 1096 4439 -600 -4173 -3400 -3774 3172
-4430 2974 811 -243 2647 2115 368 1110 -854 -2278 -3386 -917 -2018 -1232 -3789 -1011 -3774 4510 2650 3244
2213 -1605 3012 381 1596 -4951 -4325 1919 -4618 2622 3179 1140 152 4683 -1603 4784 3521 2118 -3424 133
3481 691 -1580 2685 -1124 4001 -1571 -281 -3678 -4992 -2256 -2617 -1054 -787 -3762 4389 1950 -853 -4799 4275
84 -1087 3291 550 73 699 1034 -4602 1955 -3461 -4722 866 2017 -2854 -3462 -29 -3738 2956 825 4234
-3707 -3596 -864 -2020 4642 3148 4459 3714 -4020 -3457 -3805 -3336 1641 3414 1264 -1145 -787 143 -4258 1650
4761 1805 1723 23 -4038 -3301 -4175 -1434 -2861 522 156 -3826 -3354 -1007 -2044 4160 -2358 3560 -3887 -4828
-210 -4052 3623 3481 -70 3848 -2005 3942 -1459 -263 -3476 -1520 3688 -4562 -2253 -3663 -3328 228 1686 159
-3876 55 -3931 1741 -4560 -3833 4471 -2267 1039 4736 -4475 2319 -2619 690 -2536 -899 -267 1617 -2486 1417
-477 605 144 -3000 2955 -443 2033 331 -872 500 -1585 1833 -3899 -3669 2479 -3110 2416 562 -4188 2118
-4235 -403 3069 -4064 -2954 -1328 545 -216 -4873 1244 4293 790 -4903 -3185 1175 3910 4712 2753 974 1366
2022 -711 3591 -2438 -2284 -2699 3352 788 -1435 4111 4591 -3580 -3141 2261 -787 1711 -4610 3895 -1763 -536
-3082 -3401 -3819 3239 -4305 -1366 -1166 -2 -4300 754 4747 -4701 1157 2615 -806 4159 2741 -3179 -502 4723
3482 4638 4832 -4617 1943 4366 -4737 -903 -2819 3845 -3698 -260 -776 783 214 -667 -624 -565 -4341 1483
-1866 292 -1717 -216 -2460 4079 741 -4302 2592 1857 -407 -168 -1667 -1664 -4772 233 4324 -438 -456 -2269
-2286 -3545 1799 1183 1719 -4267 3652 -3047 3734 -4458 -3903 4372 2175 -1015 -3618 3417 -2299 3979 -1454 2062
-4324 -802 -4203 4880 -4120 -3998 4400 1792 4133 -4738 3139 657 4433 -217 3872 1615 1150 1885 3268 1118
4689 4503 1735 -2717 244 -1566 3465 -1586 1970 4247 3877 -3151 -2385 -4917 -3416 518 -4427 4904 1012 1141
4477 -4855 2722 -3448 3512 -2546 3465 855 4336 2791 -738 4990 -1346 1555 -1265 -3414 2076 4329 198 -4974
-3406 236 -4508 108 -3552 1661 4414 -3664 2171 3427 833 928 2692 3231 315 -302 467 3937 3018 -4438
3446 2275 -2755 3185 4352 -1391 4174 830 3508 -737 -3080 3519 3478 4176 2773 1930 -4337 2786 422 -4613
-4515 -543 1539 -3409 -2593 3996 4375 2336 -39 -959 3556 1445 -4326 -1765 -973 2314 1097 -707 -3205 -1143
-3118 -365 4588 -3299 4424 -880 4568 1306 -2267 -936 -2263 2964 555 1875 1241 -3979 -3184 1259 -4952 4445
4469 1952 -4186 4737 2614 -533 -4424 837 -2375 -1478 1149 176 -1516 -4007 4549 -3812 3539 -2380 -1283 4953
-2366 -3313 -3942 1823 3767 2595 2985 -2762 -1070 4381 -1188 -3927 2800 285 -4461 -4977 2119 4352 -271 1116
-1768 4763 -1826 -3297 2063 1547 -3651 1525 -2758 3433 2019 -130 1935 994 -4958 -4303 2915 -3110 -1992 840
167 -665 -1322 -3586 4845 652 3880 -263 -4093 -592 -3880 2378 -3697 -4168 321 4482 1815 844 -338 -574
4273 4758 -1840 2551 1349 -351 3611 -1890 -1988 771 -242 -1268 -1806 -3192 -4769 3399 -335 -3429 4931 2831
-4331 -4874 2173 -4087 3504 355 3460 -640 3809 3356 4000 4903 3921 -3196 3466 4246 3540 -112 539 -4406
3782 -4100 -1666 3358 3985 -282 -4427 -764 3560 -3813 -466 4667 4600 4149 1770 3383 -178 1393 3573 701
3562 -1566 828 306 679 1793 -1803 -587 4477 1023 -4493 4133 715 3716 227 -2342 4480 -2158 2102 1065
-2353 3674 -4580 -2103 -604 2409 -2059 1096 -2033 3360 -1583 1641 3788 -352 2764 -100 -3649 4617 -4526 -4111
479 -4949 -2634 -1528 -3567 -3178 -3260 -1511 -662 2277 4902 1372 -2763 693 -3149 1410 538 3984 3751 -2704
-1973 3205 -1753 1788 3138 3619 4422 -3561 1725 -342 -2748 4237 2263 -1061 -2344 -4043 2667 2536 -1532 4867
-4371 2266 -1919 1914 4007 2830 -4858 3558 -4232 -3442 3669 -2121 -1570 -2042 1077 -1458 3532 -4858 3545 4082
-4722 2373 1351 -4547 -2687 -2820 -2515 3962 413 -1039 -1851 3803 -3242 -3099 -3111 2226 4028 -2267 714 -1059
316 -3782 1951 -301 -2933 655 -449 -2701 4398 -4772 840 4895 3684 4519 -2357 -3698 29 -743 -2299 4681
1992 -4379 -4048 -2789 -3209 -4642 2394 3387 -741 2245 -3360 -3339 -3573 500 -3476 -494 -3282 4275 2045 3113
-1270 -776 -1350 -2461 -3689 -519 4567 -1107 1681 2263 -1524 -4675 -820 565 218 1850 3646 -662 -1546 -4822
3691 -2457 -3840 3326 -3113 -4039 -1285 -4484 -424 2011 1592 -1694 1839 411 -810 -757 1327 2519 4494 -2991
-2609 1803 3643 2718 292 -4052 2575 1810 -553 531 -4880 -4204 -1108 -640 -4302 735 3032 672 2348 4022
3791 -3914 1701 3446 -4157 -3227 3046 2625 -2534 182 79 -3130 -3136 3123 -3044 2269 2321 1822 -141 -577
-3798 -475 -3139 -3156 -2307 -329 -2027 -187 -4586 -1170 2184 -3716 -106 -45 3637 1277 -3823 -2645 4061 1001
1306 -690 4162 -2627 -2423 -681 -3223 -1979 3714 2095 -2417 2483 -1200 1482 -3642 -161 1327 -15 3546 -704
-1977 1750 4210 4768 -3001 3269 -1773 -1312 -2542 -3766 -2602 1892 435 -996 865 -4325 3938 -4208 1175 -2929
-4372 260 -3908 4158 909 2015 1544 4124 4861 2847 -4287 -2276 -812 -2341 -929 1090 1075 -2747 -3114 -2873
4920 4187 4094 -4095 -1988 -813 1786 -3881 3558 -1809 3132 3387 758 4329 1004 4960 277 866 -3681 -452
-3371 -1741 3333 -663 -1396 4234 1484 119 -985 547 506 299 2293 -2987 -2570 4982 2467 1283 -4107 -1328
1807 3628 2949 -3434 -1868 -4966 -4763 753 -4143 -1556 3429 -1196 1181 -807 -2406 -778 2776 3373 -4431 -559
3963 4605 578 -4425 -4245 -1864 -4409 3016 -236 -2941 -4677 -1320 3601 -3884 -4744 -2611 -790 -3127 -1166 -4378
968 -82 -2274 2642 -3155 -2246 -2357 -4197 581 3959 -3821 3824 1713 -3757 -22 -61 -4515 3715 955 2789
1865 -3424 -2625 -1526 4082 961 -2414 1529 1488 1519 -1253 151 -2241 -1594 2634 -1987 -701 -4779 -3540 -4841
3499 4749 2749 1490 519 -670 3910 -2553 -4690 4641 -381 -3381 -2158 -2081 2379 237 1935 -3369 -1521 -115
756 -3307 780 -816 -4254 -2238 -1800 -4337 -1587 -308 4564 -3546 2026 -1652 -4664 2215 2988 892 -4093 2413
-994 -1687 -3959 951 -888 976 -4724 -2032 -994 -4616 658 3311 -3649 2461 10 -3354 1566 1879 4939 3133
943 -68 874 1922 -3170 -3761 2273 -3559 -4490 -1138 428 1857 -2480 1450 4389 -4134 -1840 -3108 898 2431
378 -2922 3526 3503 -4985 -147 -4516 4143 -3614 -1879 1993 705 471 3851 3351 -2643 3605 -4000 2266 -1532
1337 1927 -1825 -1566 -4096 -4577 -1603 -3461 -2830 3672 3968 260 2564 -1929 -3729 -1029 1773 3508 1035 2686
3128 -1310 1401 -348 -4302 1536 -950 2750 361 -867 4749 1943 -1354 -3149 -1236 -4483 -3021 167 -772 362
-1975 2335 -1484 1908 -4257 4513 -2894 3092 765 -4621 1298 -4110 578 -1424 -116 1545 -1355 -2790 4167 3499
792 2532 2087 -4579 1767 3838 -4878 -3941 -630 -3795 1910 2833 -3342 1918 -2884 4879 -792 -1640 4305 3811
1563 -2580 -1428 640 -4070 1017 -4823 4071 146 -371 -4852 -1915 2460 -688 3092 -1707 1376 1076 -2560 4641
-4879 -566 -3075 4803 -2735 4841 4375 2731 1376 2789 -3311 -2086 -2165 -891 -4289 -1376 -2323 -579 -128 3551
-1518 -4128 -2195 4447 968 1581 -2115 -3441 -1815 -3524 -4062 2085 -3528 2622 -1232 -3295 2356 -4527 4575 -3165
3069 3492 716 -2211 2115 -2461 3793 1685 -585 -733 2910 3120 -4547 -400 3489 -2435 -36 -4106 3071 4632
-1497 1141 -1975 -4137 -4517 -3578 3479 -535 134 2145 -4411 4488 1040 -4971 -4431 -4999 2157 4390 -209 1648
3961 -4247 -3828 -4764 3867 4716 -3772 4358 -4400 2924 3674 197 -2408 -2772 303 2083 4115 -4326 -533 1530
-1487 -3993 3207 -2426 -4985 1456 2228 2835 4951 932 2799 1819 1742 3442 -3234 3490 3515 3392 1420 4699
-2814 -719 -4695 -3859 -3874 2686 4561 430 3256 -1412 4987 4899 -445 -2292 -552 1641 -655 -2097 1199 4511
-498 -3047 -566 707 3214 624 -1592 -2387 4225 2553 -2459 -2356 667 -3010 -3405 2109 -1252 1086 4409 3221
-2745 -2780 2639 2387 4387 107 4762 2909 -1869 -2561 -3743 2420 -3531 -4688 -3713 -1691 2717 2144 4791 1304
2317 46 4993 3397 4646 -2071 -3120 30 3488 4955 -4567 1201 457 -3324 -2000 4407 -1517 -4973 3271 1798
3204 -2659 3427 -3032 -358 1986 -2443 -3561 -3933 -356 4569 -2369 1329 -932 -4497 -810 3489 25 746 1762
-2820 -2076 1850 -493 -690 -1373 0 -869 395 1851 1773 468 1704 4318 56 -398 4603 -626 4480 1796
1719 -816 -1953 -2961 3826 -2915 3222 -1546 -1806 -2885 -366 -4249 -125 -3284 685 3111 360 -1998 -1030 -4458
1605 3786 1199 587 -3159 -2287 -1942 -4313 341 3788 -1236 -4166 -1572 -4632 -2072 -1339 -2020 4186 1100 -1702
2031 -958 -2044 -4666 -3176 -288 -2571 1112 -4132 3262 308 881 -836 555 3664 -4378 1050 -2281 4128 -1083
-148 -4379 4655 3144 1353 1887 -3844 953 3506 -1064 1260 -2773 -482 3398 2085 3479 3703 1278 -637 1357
103 1218 -2950 -3146 -4305 3386 2897 -757 2153 -399 -3478 524 -1268 -3197 18 -1828 -3262 -4421 3219 4545
2603 2489 2600 450 -477 4262 944 -288 -191 976 -3663 -724 174 2850 -4784 -4996 2358 -935 -4686 -4208
-3105 -4014 434 2236 64 -3685 4992 3257 -647 -2672 4926 -2887 3036 4225 -2760 1327 -4248 1456 -4912 726
3447 -2493 1106 3069 -3353 4622 3246 3761 2417 -338 3968 -119 -2876 -3193 2055 -2364 4945 1808 4828 386
-3529 -3147 -612 3495 2854 -2654 -4694 -68 -608 -4658 649 -736 -3616 3056 -4423 -1711 -2238 -2851 -2278 -3571
2347 -2534 -808 4136 3217 -816 1337 369 4488 -2294 4966 -4780 32 3866 4305 -3544 -1657 -600 1095 3208
2871 543 1051 -3089 -1875 4429 -2224 3979 4597 2923 -2105 1048 2323 1532 4733 -4306 -1539 -186 -2698 -3835
924 516 -743 2341 -2665 -1505 -1688 -78 -698 -4998 -3134 -4798 1522 109 -4362 -2140 -3084 -4631 -886 1762
-4168 2438 -2361 2925 -3420 -3798 -2039 1994 -2841 4712 -956 4468 -1065 2135 4938 -4150 -3695 3867 4020 -2611
-4830 2097 -1326 3984 3592 738 -3391 1491 3100 -1921 -3368 -874 -4134 -3439 -3762 -2917 -3541 -3520 -4039 -4674
-4823 -3468 2327 1508 1429 -1043 4621 3355 -1224 -4716 3827 3293 1157 4659 2245 -2474 -2383 -347 -2951 3340
4071 -3036 2406 -4271 -998 -4585 369 3060 2374 430 -2686 -4838 -4164 -360 -3756 -293 2723 -3935 3064 -3884
-2994 -4073 2550 4514 -1837 -3304 465 4833 -987 -4097 -4512 -3831 4157 -4362 4474 -1633 1868 -3751 -527 2452
-4778 -4042 -3965 3375 530 -2871 -2767 -4019 -4161 62 459 -712 4139 4717 -4545 -805 713 394 4673 -4966
-2372 1415 -3873 -1254 -3154 -278 3601 -3447 -3550 4179 4456 2603 -3993 -2491 -4953 4017 -927 -4249 -2890 -970
-2693 3995 -165 4591 808 -3915 -450 227 -4100 529 -4696 -4839 4554 -1221 -2342 137 -1944 1000 -2151 -4160
-920 1353 3701 -875 -3927 -2225 2057 1026 -2255 1969 2956 57 -1105 3923 -2219 4370 2782 -3650 3568 4246
-2885 2335 3855 -18 1537 -4856 979 1299 -427 -2077 -406 -3544 -2784 389 -921 1158 3424 3154 2033 -1294
918 3340 -231 4380 -1428 -3510 2550 -155 -1020 238 -4159 -283 205 2537 -3483 -1675 -1981 -3944 -2621 -3813
4218 2550 -2361 -1078 -4751 -2537 4360 3766 3746 -1648 4989 -1902 -3953 -4221 865 3453 4256 3683 330 1335
-4815 -421 3140 -4512 -4012 2930 -1459 2932 131 527 -2079 434 3717 1711 -1058 4712 -4365 -3490 -214 2401
2996 148 3634 584 -4062 2281 1786 -2483 -1136 -908 4296 645 -3088 1332 1748 2604 1548 2715 -3592 3606
-2726 1553 -3505 -4502 202 -691 100 -1361 -4981 1268 4437 -1244 2806 1700 2140 738 -2204 -3785 -4052 1380
-2195 -2995 1978 -1230 -3590 -2462 2955 -700 378 889 1316 -1610 -2644 -2869 -653 1802 1097 -4974 1188 1650
-3642 759 -4854 1802 -4877 3402 2421 1482 -3912 3045 -1184 -555 -650 -3232 2849 -157 2226 -4907 3962 -3455
3350 -2607 2450 -1140 1552 -3014 -4564 4061 3419 -2696 4542 -1583 -2516 -3966 1276 4555 -656 2477 -2767 4202
4843 983 -1399 -1440 -4823 1736 270 2770 -2318 4661 1889 540 -2539 -4086 847 2018 -4930 4177 3673 -3933
-854 3320 -3512 -2467 3055 -1741 -1496 -3756 22 475 1075 -408 -444 3927 -4884 -2626 -97 2083 -492 -120
4688 -457 4614 2259 -1128 -4655 867 3047 -4043 3659 3698 -4721 4677 -4247 -2078 -2272 2284 639 -711 -2522
-4811 -894 419 1621 -2737 138 -1379 -3276 -3408 3244 -4992 -2718 -3202 3512 482 -1038 169 -3380 -1274 97
2464 -3425 -3117 3012 4447 1556 -3593 401 4679 2612 -197 -3824 -1003 1349 4483 4054 1628 1171 315 -588
-3887 -2018 -1040 -3392 -4586 886 1906 -3361 -514 2715 1182 -3060 -2582 -3911 844 -1316 -2800 2064 -602 -567
-2692 -1830 2611 -1945 2006 3818 489 -2158 3923 3104 -3571 4207 -4008 -3137 -1980 4688 -4717 -1174 -2356 4165
-339 -2813 -484 -3800 -697 -3851 -3333 -1696 4777 -1637 989 -677 3493 -1082 -3431 -1511 1125 2381 3071 -4040
4385 3676 51 115 661 -2314 -4406 -2159 -570 -3323 -2391 1422 -513 2659 -3180 3031 -2033 -1261 4736 -636
-2864 -636 1798 2516 -3319 1290 -4835 -1431 2301 3440 -317 1060 4584 4944 -3453 1315 347 107 3745 -365
3890 -3805 287 -2760 3981 1662 128 4464 -904 -2721 2700 126 -1119 -3356 1998 523 -1164 1877 1400 4999
2722 3850 4707 540 -3839 2262 -2008 897 1948 479 2391 4313 3201 601 1700 -4866 1090 3426 -2142 2331
-2767 4255 -2161 -3494 -2612 1198 2774 -4330 3713 2954 -2085 944 136 1282 -3862 -3835 -651 -4563 1587 760
1200 807 751 4685 -4131 -2659 1421 -3867 -1963 -1869 2500 3955 -1789 3755 2766 -2581 1775 -3083 1531 964
-4523 -318 1284 3107 -3251 -3395 3319 714 -2460 4280 -4896 2879 4952 -4382 3625 -1157 2571 4111 -4038 1561
-127 -375 3250 1918 -2973 4986 -1357 -1757 -1643 -363 3060 -3719 428 781 554 -3521 -3094 2176 -971 -2824
1600 2099 2646 -2698 -1662 -939 431 3940 1643 -1049 -4516 4603 -1557 2483 1274 -4807 132 1062 -450 -2357
-739 -4712 -2085 -4012 -837 1327 665 -918 -1387 -3627 1062 -343 -3614 3722 -2112 1358 2341 4836 -2459 1276
3845 -8 -4760 4710 -3522 -4045 107 3581 3705 -986 -1784 -4503 -939 -3240 -471 987 2238 -3105 1040 -1569
-16 807 2961 4364 3239 4181 -4 1015 -3736 999 -1803 3884 3127 -1081 127 657 -4553 -4707 -4312 -954
-4659 -1110 -4528 -4314 4034 -654 -1617 -2949 2429 4220 -4793 -4521 -3982 1658 -991 -2085 -2979 -2649 -4527 -1175
-3625 -945 -1760 -3376 1468 1202 4353 4888 -88 -3198 4472 -1641 944 3133 -4888 1259 2455 2508 3990 -4898
1443 -2359 625 -3364 -4403 4389 -4751 -3043 4396 56 -1395 -1490 4608 4233 -2608 4923 4268 -4702 338 299
1002 3970 2981 2271 -483 -3743 -1239 4861 -1263 -697 -2384 -4511 3946 -2497 -1592 559 4229 385 -4155 -2024
234 -4434 3778 -3983 4438 -2084 191 -3639 -3017 2886 764 4613 3377 -170 -430 -4153 3653 1321 -589 4379
-1999 -1672 -294 -3225 -1282 -3665 -2328 -3039 2769 -1959 -1823 876 -1617 1373 3361 -453 -2335 1689 2157 3659
-2985 -3418 -3942 2431 -1566 -1491 408 638 -4368 -3762 -4057 456 4735 3293 -538 -2249 2121 1205 1765 -2054
233 605 -2357 799 4886 3213 3484 2098 2039 -2428 -1516 2151 1314 -3374 -2008 -4012 -2782 -3883 4849 1709
-3348 -2966 200 -4324 -2714 3589 3479 613 -1811 -2450 2663 -4839 2057 -2087 -3694 4457 4700 -4478 -4800 3359
-1339 -3513 -1457 1014 1962 769 815 4550 -744 -717 732 2966 4721 -3284 2769 -1018 -1391 2561 -879 353
895 -3108 1162 -2375 3874 -3173 84 297 3040 1602 1475 -2092 12 -3340 -633 -2591 -1839 -990 1307 -601
-4067 3104 4723 2596 4844 2822 1834 2830 -197 2534 2386 -3778 -2211 2288 3311 -928 3033 -387 -2578 3782
-4753 -3615 2035 -335 -3007 777 3583 -3713 -1654 402 -114 1582 2990 1226 -4914 548 1614 -3723 -2151 1603
-3068 2462 4833 -3754 -3887 -42 3722 -3064 1509 -412 -2839 -3772 -1513 -2959 -4721 -1791 1605 -2850 -3615 -2660
1439 -2515 4780 -3049 -4494 -4295 -1151 444 1540 2164 1752 -4001 1561 -477 498 -1988 1811 -2363 985 2392
-2777 1048 3563 2121 -2082 -3602 -648 2551 -3819 -1079 2972 -2709 -1950 -1110 -3263 -4316 4703 4389 -1410 -3130
2383 -1397 3480 664 -993 -503 2957 -3007 -2701 1180 1105 4899 -2902 -647 -4669 -1819 3333 -4691 2471 4643
3548 -1920 -928 -952 52 -2178 2397 -266 -802 2184 -4268 -1882 886 -64 1619 2288 417 467 -3534 3262
684 1289 -3079 -895 2406 -3900 2161 85 4574 1022 -2060 1027 756 2106 526 -2647 1467 -2528 -731 4886
449 3359 -1126 1004 -4486 1674 1342 2028 1553 -1475 -1299 1816 -3101 -2445 4594 2242 -4752 -1949 4111 -624
3896 4837 3013 -1875 -1173 3881 -117 -418 -4650 -4914 -1631 3288 4793 2280 -2519 1392 2633 2077 4331 3055
-3330 4760 629 263 4585 -1196 -4099 3084 -3862 1145 -2940 -2201 -4659 -2507 837 -2064 4615 3888 968 4681
-4033 2756 -2449 -1193 35 -2061 4509 332 -3956 -3947 -4992 2550 -524 -2588 -388 4780 1229 4134 -3171 -425
4863 4065 2219 -4743 4224 3175 -4981 -1943 51 -4721 2325 849 -2740 4999 -1005 -790 -1924 -3362 4265 -2923
-786 -830 4229 4622 209 -4300 2073 -3944 663 -2134 2030 3929 -2797 2378 -3162 3198 -510 -803 4646 -454
3754 -359 -535 2353 -3406 428 559 -3832 1635 416 3577 -2230 -2026 3609 -3275 -1784 -3318 -1795 -1977 -3083
-2643 2700 4762 4444 -3440 -3758 -3651 3009 -1612 1583 2054 -526 603 -2682 -899 608 2690 1078 3541 -2750
511 1421 1424 4516 868 610 -674 2051 -3130 2702 -291 4973 -4155 1579 1752 -2578 247 -3275 -4431 -4016
-454 232 2609 -3147 -1255 89 -211 672 -4180 -521 -3891 -751 4447 -847 -670 551 3725 3973 585 -2047
4856 -1136 -4104 -2406 1442 -2211 -1673 1612 3982 -216 -3846 1061 -244 4605 302 -1790 -2147 1071 -4032 3132
-4677 4512 -63 -2763 -3651 3781 3989 -116 746 293 -2886 -1353 -1588 305 -3407 4637 -4650 -2434 3558 -2593
-2798 224 -3562 -2463 -4792 -182 -792 -3773 -3283 -1771 -1182 555 1843 3319 1417 -3075 -2870 652 400 2572
2283 -308 -3206 3997 -1828 -2176 -3289 364 1986 -4240 -2482 -4932 -4250 1872 -1369 2825 -905 -3010 -1873 3858
1011 4140 -404 1211 -1147 1424 -3740 -176 3059 495 2123 4583 -2055 89 -3538 4046 -1627 -2640 -3825 -403
-2393 1775 -1581 -4073 900 3628 3503 -1713 2386 4604 660 -454 -758 677 3419 -2343 6 -3173 -1269 -1061
-2659 4821 2541 -741 -2091 -3343 -110 -4398 -2839 590 -3707 837 -4468 -1193 395 404 -4860 799 -1064 3614
-4435 -1543 -1146 -2139 4796 -2036 -534 3514 -2045 2828 -1131 3508 1449 2979 -24 -4344 -1524 706 4428 4548
4374 -1792 844 4666 -4587 3438 -235 3991 3282 3634 4750 1765 -2125 -95 -2282 -4675 -1201 4020 643 160
4765 4005 -4045 -2055 -1309 -4805 4318 -1867 -4654 -4955 -2947 -2401 3033 -156 -2231 -1375 284 -3312 1606 61
891 4079 -207 -4502 -687 -542 -4259 -2218 -94 -35 2154 -4818 3362 -4167 797 3897 2954 -4428 762 2156
-1286 1888 3065 -4575 253 429 856 3513 4677 -1404 -3314 -2496 -4129 187 1909 -4492 -4195 2599 -4685 -3381
-2095 -4728 1582 -1115 3586 2896 2800 3335 2343 -250 1704 -1509 2221 -243 4742 -4639 -2669 -3255 -4046 -634
-213 4394 2357 2333 3544 2323 -4983 -2527 -3530 -1814 47 2019 -3829 -2721 -600 421 -3762 1360 1750 -3404
1741 -2422 -4165 -291 -3625 -119 2833 2457 -4360 -3102 -3134 -258 -2309 1294 -3680 2416 -1683 1201 762 624
2992 692 -4974 3512 -3768 -2546 444 3128 -4630 -683 3120 -4813 -890 1885 128 -3933 -4689 -339 -1151 -1202
2393 4704 4683 2931 2177 1148 3622 -4748 3349 4535 3487 265 4336 -2613 -1351 106 846 1147 -94 -1195
-1880 3504 -3884 -2947 4028 -3499 3349 769 3001 4109 -2601 -2606 -3477 -4427 272 1679 545 -1483 1272 -360
-490 -2720 1463 -724 177 4981 1039 4531 4522 -4026 -457 -2930 1813 -2314 3384 688 4768 -4945 -1799 -671
-1835 -3631 2330 1091 440 2292 -868 1400 -132 -4798 -3838 4200 3563 -258 -4327 -4062 -919 4739 -3316 2939
-3626 4704 4297 345 -2897 1134 3347 3622 4203 -2791 645 1226 1108 4789 1992 -868 3954 -1456 2029 -1450
201 -492 4619 -1488 -2313 735 1491 -566 -19 -1688 -4479 -64 1466 3106 -2835 4425 901 -2771 2720 2563
-4941 2796 -4981 4766 2343 -1291 1106 -2611 -787 -1954 -4644 -3758 314 -2138 2692 -928 3762 745 -673 -3665
-3345 -166 2975 2380 273 -4143 -2471 1880 4308 4774 4021 -689 -3632 3639 82 3815 4517 -4979 -3741 980
4940 -305 4526 2935 231 2819 -253 -905 -1474 -3618 -1913 2323 204 792 317 -3481 -656 -4743 -4780 3574
110 4190 3447 -1922 4575 -4804 -2110 -3031 1658 439 470 4492 -347 -1895 -1514 690 4608 -2263 -3735 -3113
4714 290 4055 3131 -2003 2558 -4666 -636 742 -528 -4604 -2237 2416 -4152 -1681 2597 -2816 -883 -2380 -3503
3425 -1007 440 4399 -3093 4563 -474 -1616 -339 -1044 2992 -3109 2811 -3271 4773 -1822 109 -3410 3523 -57
-3079 -3496 3238 2442 -2522 -1969 4759 3380 2399 -942 -1481 -2540 -2364 2189 -102 -3685 -2445 75 2827 -3409
36 -78 1714 1473 -2718 1506 -540 -2784 1867 -1120 1584 2600 4045 -3643 -194 -4478 4561 -4024 3069 2541
2528 2036 -3016 2732 -591 435 2597 3318 -1503 239 -22 -2573 1030 -1982 4910 -80 -2306 2717 4035 -255
-3827 -2137 4774 3981 -3371 1495 4803 -637 4166 -616 -903 -2687 3421 1276 -3094 1023 -1438 -2697 -2434 770
-3146 1613 -3908 153 -2228 -4184 2752 1426 -2620 2817 1700 470 2232 -1914 -1051 3837 -4028 2607 -4615 3143
1369 -479 -642 -4682 1470 -1044 -4043 3951 199 -3756 -277 -1891 628 -4497 2685 -907 2665 4924 337 -30
-2262 -379 2339 -3324 649 -3006 3162 -522 -1458 3495 684 -4071 1606 -3264 -4916 1844 -4915 -2087 3022 -4104
4899 -3392 2634 1566 -143 1849 4113 2743 1862 4405 2379 -4065 -3142 1006 435 -4333 -1153 1038 -3890 -57
691 3068 4142 4203 3731 651 -1682 242 1174 4445 -1269 -914 569 4693 4441 -1938 -1877 929 3223 325
93 1376 -2729 -2811 4111 -288 -4004 -921 -1957 4989 3240 2443 1232 -1399 1048 -4740 -4645 4895 -2310 4897
-2042 -4224 -4056 -3746 -4015 4933 -1234 -4998 1349 -471 2780 -3653 -626 2061 2452 4752 1701 3282 705 -2439
-1557 -4980 -3332 3692 -1156 -4671 688 -3904 734 -2920 -4088 -4017 -2141 -915 -3003 -4157 2719 -4515 4145 -4379
3693 -2469 3499 -76 -1641 1063 -2107 2620 -4984 3016 -2037 -1995 1735 3907 -1462 -644 -4470 570 2938 -4333
2896 -2920 -2926 871 -3133 1037 2914 4435 -1761 4999 1999 -1031 -2548 -1158 -2516 -2223 -1031 -1868 2270 1
-3134 -4999 -272 -294 2186 -2513 -963 -528 4256 -502 -3464 -2336 -178 -4782 2580 1608 -4074 560 951 1461
-4706 -4004 157 -432 3001 -1540 3287 -3656 1930 -2060 -962 1240 -3054 -1952 -1247 -2338 2257 -876 -502 -4631
4867 2265 -820 -4726 3547 -4645 -82 2886 -1274 2855 4444 -1608 3849 -4113 1091 2789 4990 1049 4155 -3374
-649 -3698 26 2151 -3750 362 1336 -354 2918 3995 1106 2450 -3834 -3166 -1790 -3560 -3001 -4693 -421 -3366
-912 -1165 2096 -1227 -959 -3334 3557 2577 -4793 -731 -473 -4765 -4158 3006 2100 301 1268 2920 -3621 4381
4830 460 3497 -3710 1896 1824 -1068 -4394 -1433 3780 -1374 2147 2077 -4579 4488 2884 374 -2536 3710 -717
637 2764 -4561 -2989 -599 -4062 2623 1283 4447 -4073 533 -2740 -3430 4025 321 2533 -3167 -1711 -4579 -2171
-2103 -1625 -1397 1782 -4578 1260 894 3413 1241 -2546 672 -2301 174 1990 -3345 2612 2634 -4860 -1829 808
-4390 2538 -2778 3798 3982 -4589 -619 -1250 4158 -1731 -1560 -2622 2847 1344 -3720 4690 -1332 -2489 2640 3240
248 2269 4010 4880 405 1083 2755 4261 521 -3945 1 -3063 4332 510 2124 1129 3196 -2661 2885 595
3442 -2695 -1478 -4368 -502 3490 492 2573 3025 3694 -3255 2654 -4803 665 -2304 -4546 2770 -4476 4606 3408
-540 4024 779 354 517 -2128 -854 1448 1693 4113 2889 -2130 -3247 1001 3537 1588 3443 2867 753 -3073
-4745 -2548 2839 2966 -321 -4688 4052 3924 -805 -3589 1711 -3831 -4100 1411 -63 1354 1584 -2192 -2330 3017
4209 -2492 1828 4999 -4693 2325 -1464 2557 3884 -3971 2890 1120 2603 -4380 -3689 4099 3177 -2343 1842 4368
-833 1426 726 2830 2753 2034 -590 -3475 3172 -3543 4171 823 -2612 -871 2500 1102 1262 -1071 -1575 2073
-1029 4731 1095 3558 3314 4965 2181 -1433 4992 -4177 1604 331 -4576 -4301 3152 2945 -4800 1727 128 -2400
1423 -1730 -904 -2298 -4551 -822 1303 -2032 352 -524 382 -2955 -4792 2209 -1336 -4315 -4604 -4704 -639 4379
1947 468 119 -3054 1456 -3314 2414 -315 4491 -2161 -4694 -4610 -2263 -2150 -4114 -2692 1152 2824 -4571 184
-2424 -3084 -1277 -2655 -3904 606 -2268 -3077 841 -190 1689 4991 3939 -530 4677 -4388 3590 -2078 -4402 4291
3452 -4636 0 2318 -634 2727 4374 -1799 -2230 621 4535 1996 -4975 793 2098 351 -1107 2376 4058 -3480
-4818 -389 -112 -2761 -4517 -415 -3318 4554 -1713 -4491 2690 -3120 -3280 4472 1503 -721 -3593 -682 3375 -3649
1720 -4814 3106 -3815 11 -622 3044 -2615 -1300 4973 -4320 -4977 3509 670 -4223 453 -1411 2870 -1328 -1055
-4272 -1602 -2078 -2916 4336 -205 -78 1749 4937 -3679 4033 1987 -4364 1138 4473 3261 1525 -2845 400 -1801
-1602 -285 2845 1945 -4027 3800 -2196 -2037 1892 -2428 3303 -4945 817 -530 -4092 -1763 3694 -789 -506 2164
3502 1664 4997 3864 -4243 -4856 -815 -114 -4870 -139 2530 4259 -3842 3675 1166 2277 1860 2306 -708 -4266
-4371 -1306 -4767 1470 1658 -3000 4153 1747 745 3433 670 -4320 -25 2734 3361 -216 -1640 -1233 -4652 299
-168 3833 3579 3008 -2268 -1547 2123 -4155 -3830 819 489 -3043 -977 2259 -4867 3142 3849 -4331 982 -1678
2095 -3841 -4487 -4317 1163 -4323 4251 1169 -1727 -2678 -538 -2958 -574 -2176 -3305 -553 504 -2741 -6 3131
-2085 -923 -3742 -1841 -421 4177 -1634 -1575 3169 3634 -2261 4149 2828 4195 -4686 3579 1777 -2281 4188 -3776
-1964 -2546 3023 1998 -375 -2409 1138 4343 -898 652 1907 2060 2581 1456 4952 -4883 -4755 -4753 -3242 -4344
-399 -2432 2751 4068 -1768 1706 -3263 -2194 -3233 -3872 1450 -766 4086 -4025 3400 -3280 -358 -4896 4444 2257
1097 4514 1111 279 1375 -4412 -586 -1864 1143 -4044 2251 4286 2736 -4558 3959 3744 -708 2087 -202 -3668
-1505 3641 -1051 -4244 1384 1567 -4059 2306 -3927 -3867 -1565 -1475 4615 -846 4567 -4465 3222 -2158 -1196 -2648
-3527 -2051 3706 -4514 3541 -1526 861 -633 1454 -880 3130 1729 -3732 -4105 -2578 4379 3311 2415 -4935 625
-3800 228 4931 3217 4388 -1576 2714 -2267 4976 -931 2904 4876 -2218 3832 -1538 1528 -652 -4733 1799 3775
4394 -3950 -1965 4466 -2128 1724 -1418 4498 2017 -79 -2581 -4798 262 559 3986 -564 -663 700 1738 4321
-3402 1226 -1962 2059 2929 1146 2173 -1941 2296 3832 -3194 -2574 -3433 -4510 -4713 2773 3390 1949 -4502 78
-2811 -2648 2879 1510 4891 3150 -4423 520 -3019 1412 -3618 -537 628 -3837 -3456 -4014 3814 3793 -1737 -1375
527 -3328 -710 1485 779 -2398 1113 281 -4171 -2226 1100 2797 -1170 -2153 4855 264 697 4174 3332 -4685
-1359 -4835 3418 2569 -2028 612 1074 -1413 -4209 3684 -1940 1368 4587 -1664 2683 1165 -4743 -2404 684 3862
-3585 2339 1851 -1494 3225 -4611 -3903 -3181 2972 2112 4338 -3334 4775 -4446 3528 386 3263 1400 -3679 -3755
-3353 -3893 -1649 4209 -2515 2981 -176 3747 1205 -1642 4742 -119 1666 -1518 2838 -1857 3304 673 451 2653
-921 -1491 1122 -3464 1873 608 544 -2335 4164 -2097 1123 -3945 1647 4940 1201 -3970 2588 2955 -4206 2617
-1571 2905 -4939 -2179 -2030 1709 -208 3856 4507 -3215 -1228 -967 -9 -3515 -4334 2038 -3075 3124 -3395 -3086
-3422 -2692 4410 631 4594 2733 2979 3763 101 4628 2302 2848 193 4362 4733 -2487 -1359 1656 -1892 -2952
692 1904 -834 1335 1183 -4442 2748 -2443 1704 -4350 -4414 2832 -1483 1157 -1167 1390 -4620 3937 101 3500
29 4555 1493 -2687 -1608 3722 4305 -4671 1814 -3633 3565 1606 -1588 3893 1906 -1870 -2060 -1863 2658 4362
4023 -627 3041 4555 2504 3737 3404 -369 -604 3810 -2094 4169 -4072 4173 -562 4878 -2184 -3554 -1030 2301
-715 -4411 1823 3015 -3790 164 -895 4291 -572 770 -1486 -2165 -1879 -912 3767 -2888 -3466 538 1602 635
4093 -3343 2169 3544 -296 38 -4 2372 3887 4538 1766 3304 1122 1477 3897 2045 -3702 114 2671 -1519
-4688 3675 -813 -3929 3447 3511 -904 -727 -3227 -4193 -4992 -2062 2686 50 -456 -3724 -1364 4431 2312 2138
3365 -126 4554 -260 -2802 -4189 3330 3098 -2227 -4136 -949 -782 -2186 -4537 -3080 2633 -3886 2088 -2309 -4159
-4338 857 2047 -4045 -4486 -3805 -4267 847 -2058 4664 -1564 -435 -3145 -3803 -1606 3281 -2689 -4686 1304 -4710
4044 2106 2551 1729 -4089 -738 -559 3638 -4416 3179 -1906 -3677 -4628 -2635 415 -4884 -3116 -3207 2178 2029
3398 -4483 -3136 664 -2515 -2175 -216 -320 1659 -3923 -4753 2504 225 4262 -863 -3175 118 -3311 1762 3419
3868 3819 -2363 917 4111 551 1035 3359 -3718 2256 -2615 -3048 -3293 4722 2554 -859 -4067 -1922 -4304 3827
633 -457 1117 -3869 2570 -4563 -1713 -4708 -3341 1023 1278 -1838 1565 -1854 990 4159 -4159 -4847 -1656 152
-1806 3929 1746 3761 -2920 4024 -691 -3805 4434 2651 -1332 2561 2194 -2592 -2286 -1007 4907 -1838 -4658 3057
-2884 4396 1951 2245 2907 2728 -2457 -4194 4759 1304 1214 2226 -1258 -2928 -3890 -1240 4123 4332 4870 3951
4632 -4472 4463 63 -2716 1445 301 593 3017 -4788 763 -652 -168 -1211 -581 2282 -4197 377 -222 1294
-3534 1445 978 1239 -3842 -2610 -2778 -2762 32 -3056 -4824 -4361 525 -591 4246 1313 -588 -3570 -4698 -3333
4568 -4232 -2169 -1662 -1983 3102 -3575 1355 4873 -3260 2459 2117 3549 95 -2430 107 -3958 -406 542 -910
-3207 -4517 -1241 2741 4918 -3091 2400 2586 -3138 -942 -4157 -4696 -3079 -4551 4761 3672 -4376 -2527 3209 4301
-4655 -4567 -4173 529 -3340 1783 3042 -3669 3530 -224 1849 -2016 702 -2762 111 3576 90 1665 1217 4672
747 -2038 577 3177 4281 4243 3546 -4684 3143 -3819 3881 1513 -3405 3916 -1945 3153 -2773 1182 -3274 3157
2175 3984 -873 2783 4505 -3259 1365 -303 -234 733 3582 -2618 -115 -1893 2464 -3781 4566 3211 -1656 -1000
-1561 -1217 4937 -4917 1259 4522 3307 2568 -4400 4735 2300 3842 -2382 1602 2644 3432 2723 624 3396 974
2323 -3904 3026 2766 3017 -283 -2375 3278 -3062 3729 -2980 502 905 3777 -1256 -3311 -3450 -3997 -4904 636
657 445 -4086 1430 2248 4693 4442 4897 -511 1393 -144 3073 3307 1101 -2906 3299 -3123 -4827 -1809 1540
3988 -2084 3006 1071 791 -1594 3169 -2413 -3438 3122 -3112 -2567 2766 4644 -4916 -2951 -506 -398 1066 2040
1448 -4489 -4102 -1258 3170 -1589 1405 -4303 -3665 258 -1462 431 -3668 4916 -971 3893 -2356 2130 -4993 -3721
3790 2589 -2702 -4251 -2341 4245 4428 180 -321 1386 294 4658 -267 -3904 -3446 -2183 391 1703 -610 674
934 -1450 1945 65 -321 -3892 -3122 -3321 -754 -4832 -4563 -1149 4877 1738 1475 3819 4031 3291 -4824 -4903
-3704 3194 -4675 -3564 3832 -3643 2923 -1172 319 498 -4335 64 -1517 2701 -2763 -2300 -1863 -3684 -1444 4111
727 4427 -2400 3825 4211 -4789 4379 583 957 -2649 -1286 1491 563 3872 4979 3136 1609 2716 -4962 2498
4424 -4517 361 -1160 -4199 3747 -4757 -3412 -3357 3059 3610 4555 793 3361 3445 209 -3109 -2094 -4287 -1663
2080 2938 -4786 1770 2529 -3530 1723 4961 -3733 -3317 -4363 159 -441 -10 1022 -894 -1208 -804 -4055 3032
-3344 171 3988 -3234 1005 -3570 -2006 -2735 -4907 3083 1245 -4994 -4934 4469 947 -82 3988 -9 -4684 4793
4475 3188 2447 301 2248 -3852 2587 -1037 -2173 -39 -1240 3479 1254 4971 -4291 4738 3096 -641 -118 3749
-1481 361 -1457 821 -4965 768 -3067 -4415 1378 -980 2589 -4811 1550 -1670 4914 -4260 778 -3888 899 -2594
-3489 -4849 2274 -4260 436 2826 3160 40 223 -164 4361 -890 -187 1224 4843 3980 -1424 -572 -479 -4168
-2004 -277 -4168 4610 -2297 -254 389 -4147 2923 4858 -521 739 -4576 4375 -2573 4509 -4220 -1342 1043 -1594
-3157 -3732 -3737 2716 -2343 4967 4293 -3670 -1178 1073 -2007 1624 -4146 1634 324 785 -1813 -737 2704 -1422
-1403 2866 1173 -4774 4780 2976 4518 -4373 834 -4268 904 333 4184 -3811 1549 1927 686 2221 -2781 -330
-3975 -492 -433 -4186 1042 3176 -3665 -3428 1683 -3776 -20 1226 1182 -671 1523 -3650 2396 4519 2875 -3544
-3876 -538 2156 4140 -158 -1336 2716 -469 1941 4221 -4620 911 4056 -375 2384 -4419 -1074 -4525 4112 3035
-2658 3327 -2482 -1419 -1763 -3263 -4649 -3964 2204 -3359 1567 -1654 -1053 -628 265 3474 -4323 3454 -4244 916
1567 3632 -3396 -4988 -1849 1851 1805 4109 3631 3375 1271 1842 435 -2314 1948 4584 -1118 -2443 -1400 -464
-2972 -3865 1692 -1448 1153 -179 -2466 639 -3253 500 -1407 -1456 208 1538 3587 2841 -2258 -3080 3946 2727
-655 -1424 4380 617 -370 -4171 999 4780 279 -4223 -746 -2172 1721 -14 3238 -1805 -3612 -579 3125 2389
2607 3841 1689 -4822 2080 2285 1100 4990 1886 2691 3306 -491 2665 2866 269 -2674 948 2672 1548 3374
-2177 -605 1022 3290 3940 -1472 -939 3687 -4053 -83 -841 -4399 -3248 -1443 179 652 2075 4356 -2191 1753
-4020 -4204 2466 -2400 -1929 -813 2931 -1045 -1585 -3405 -4091 -2145 2787 -717 -3399 -665 -963 -2236 -2228 2311
-4949 -3097 -3036 55 -3045 20 -4231 4198 -4983 2278 -3338 -1744 1991 4964 -4803 -3343 3253 575 91 2825
2515 2824 3327 546 2432 -2305 -1326 3288 -1074 2091 -2820 -4419 2060 3001 -2567 -1002 747 -744 2228 1261
-4679 924 4886 -1936 4836 -321 1806 2070 -2154 4321 -1344 3411 -1484 -928 -745 40 -4122 -3147 3682 1347
-4435 -3296 1669 -509 -190 2892 -698 -2415 -3647 -3428 -2156 4830 -672 -3981 4264 -4998 -697 -1599 682 -2096
3470 -2956 2845 -1140 -2598 3386 -306 576 -3191 1138 -4658 4582 -1918 -3860 301 1533 -3077 -439 -2336 -3859
-1044 -3747 -2078 3002 -516 -3412 -907 342 -4721 -982 -1042 903 -4675 798 3161 3887 4085 3654 2585 3505
-1834 -210 4848 1728 -4487 -1157 4763 -2778 3033 1281 4514 1550 3592 -3822 660 844 -2400 870 4873 -1830
3040 3757 3993 -4410 3225 3670 -374 -3180 2218 -3950 4605 4672 -2674 -605 -729 2226 4758 4083 2782 -2126
2297 3407 4478 -3531 2953 -563 -1169 -37 950 -3843 -2489 1312 -3594 1278 1261 -2271 -4174 -1467 -3816 2161
-2501 -4705 867 -1825 -4194 -1641 -3911 3721 4769 2357 4467 1049 -995 958 -2048 -442 -3275 3389 3531 1086
2023 -3143 2649 -989 383 -1134 1831 -4225 -2197 -645 3766 3749 -2561 -4385 -1884 -4553 -2983 4912 1386 -683
3731 -1239 2064 3941 -1886 -930 3119 -2898 2949 1997 -1992 1603 -4396 1061 4362 2723 3974 4958 -4444 4417
-2090 -3733 4136 3576 3093 1976 4443 4839 -4122 2756 3196 -4477 722 3990 -4144 -1919 -208 -4240 4157 3715
2655 -3701 3735 1259 3712 241 157 256 940 2608 -4685 -4992 3126 4025 -344 -1979 -3620 -3638 -1915 1113
-3169 2969 -1475 -1453 -2259 4009 -3794 4215 -2996 299 1953 4513 -664 -1544 4016 -376 -3028 2 -1145 -2813
-1167 4034 974 3871 1295 -808 4713 -3909 -380 625 -3728 -1293 -4003 -2049 1288 -2681 3783 4688 -4376 -749
-2342 -84 -3488 -1497 -163 -3639 666 2219 4694 -140 -853 -3313 -1438 4886 355 -63 -1239 392 -3002 -2282
-4690 781 4647 -671 1539 -3195 -577 1165 -1748 -3105 4820 1100 1388 2001 3228 -4422 4565 -3332 -778 -2919
1702 1340 3846 -3578 -3614 68 -1276 1430 2186 3476 -2273 -4012 280 4635 1648 -2208 -4975 927 -333 4134
-3847 -2051 3089 4987 1164 3464 -564 -1032 -2045 4990 -2688 338 -950 2201 -3000 2259 -3928 2196 2631 2384
743 1331 3303 -3062 4321 4198 1111 -4017 -124 -3468 -3503 2956 3345 3611 781 1901 4047 3578 -6 934
-735 -4225 3014 -4664 497 -2869 -2392 264 1995 -2309 3168 4409 -2525 345 4349 4461 989 710 4270 2537
4317 3222 2999 155 1731 1636 -3211 -438 1759 125 -3965 3181 -1781 -1344 4257 -2613 450 205 3310 4630
-507 944 -2237 2643 -4562 -1469 576 -32 2863 -101 1091 244 578 -1838 1151 3237 -1751 -402 -839 -3110
-1150 987 -2130 -4077 -790 -1023 -1208 -732 252 1868 874 950 2369 -1004 59 -1620 -2410 -1779 -3783 -1478
-3988 -428 -1987 -4986 3903 -1371 -665 -20 -4266 3294 -2424 4331 -4685 -1313 -2754 -4492 -2670 1174 941 1698
2945 -1854 -281 818 1051 -1971 -2075 2112 -4019 3054 3280 131 1145 1011 101 3054 1232 2153 89 2871
-2347 396 1145 -1637 4022 3769 -4178 -84 778 -2080 2750 -2427 571 4844 4705 -2158 4628 4505 -1230 -3347
-1361 -2310 -3167 -3466 -4397 3491 -71 -1227 -2552 2602 -3704 1594 -3737 -3903 -1003 1637 742 3154 -2334 -2042
4821 -576 911 4016 -941 2162 2582 4318 1226 -1893 859 -3400 -3322 656 4750 1158 -4309 -3635 2763 -2605
-1531 -99 -1718 -429 -218 -1089 1648 -4760 -858 -3352 2506 4265 -4902 -385 -4126 -3159 -3122 -2057 1019 -3225
1642 1815 -2800 1048 121 -3700 2854 -1417 4529 -2348 3436 2629 -1277 2641 3647 -995 -3006 1724 3130 671
-4204 -1008 -2430 742 -689 -2867 -1028 -2829 -4510 -1681 -662 4375 1471 4725 -865 4257 -7 -72 -2827 -270
-2844 1072 -2693 -3898 -1803 1355 -2088 -3258 2499 844 -679 2740 3163 4700 1760 1561 -4800 -727 2006 -3771
4617 -4705 -1213 -3171 2852 744 -1685 -1215 2204 -954 2395 -4464 -463 -1312 2568 -3694 2531 4311 782 -3145
-3021 -4011 -2335 -91 -3993 -2224 1388 2944 -908 -2027 467 -3385 112 4830 -1522 850 4422 -2900 2905 -2909
-1354 2527 1805 4173 -3684 -1643 4645 -3952 770 -788 2989 -4656 -3492 3536 3537 3622 53 -2509 -1336 3564
136 -3221 1221 3719 -922 3010 -4031 -760 -3373 927 4503 -368 -413 -139 -2500 4972 -3100 -1384 -4649 3238
-3272 3860 647 -1012 -2202 3509 2385 673 1568 -68 -3447 493 -4413 -4883 4481 -1557 2615 2624 -3647 -4946
3569 -1042 3917 -3761 2624 1999 4317 2622 3689 -2250 -4924 -1903 -4137 790 -3438 3731 1276 2051 -3291 3956
-2035 -830 -728 96 2963 84 -1948 -2959 761 -4082 -3234 -3230 2043 -854 -4523 3791 3889 -2888 -598 -4254
-3573 1698 2275 1561 -2121 3438 1630 2126 1791 1282 2752 3801 -2816 -2050 -2178 3141 1029 -2969 -2230 -4084
17 -4990 -957 375 -2309 3321 3359 -1832 611 -4202 -665 1064 2906 -3337 4974 -35 512 -3978 -183 2120
1133 782 -2535 -352 -4067 -3256 1398 3312 4084 -1754 589 -3525 -3776 4203 2744 2870 -936 -2465 3955 4245
4345 4630 -1129 856 2611 -3994 -647 1761 -2855 -3204 1940 -2329 -1575 3552 -1189 4162 3555 4443 -4701 2582
1120 1705 -4266 1153 -1302 1297 2565 669 -4312 -2874 2485 2520 -1650 535 3835 1938 -962 2792 2255 481
-4370 -124 3909 2235 -4290 4644 395 1963 2597 -4074 -4437 -4145 2435 3297 -1137 -2458 -1896 1635 2693 -3210
-2959 -2667 -4971 250 -4515 -4345 2892 3300 -2374 -545 -2690 4923 709 -854 3548 -3868 4982 -2410 -4223 1811
-4519 -1988 -525 2978 2787 -3682 -1982 3354 1471 -3797 -584 263 438 -2119 -976 -743 -1670 -840 4805 2580
-4809 -3297 -962 4873 4779 -1545 -2498 -2663 9 -1267 -1150 -1081 1121 -3299 -3213 2532 3519 752 2941 -3199
-3472 4666 -3335 3274 1133 -352 -1827 -641 -1797 -1052 951 -491 -3653 -943 738 4831 4904 -1348 3816 -1753
-2491 3094 1258 2020 -1614 -3480 4760 1761 -4922 -1394 -2562 4292 4069 2690 -3901 1839 -796 2952 183 1796
2921 2370 -1341 -1548 2691 2815 674 3007 -1481 2789 -4396 117 4448 1402 2723 4567 1096 -4759 -940 3520
-3588 660 -1209 -2356 -4919 -4890 1880 3645 -446 -1727 1708 -3303 -461 2713 -624 1023 2552 -4321 35 1497
3342 -1652 1587 2323 -4787 -941 -4459 4911 3502 -27 1441 2668 -4083 -1812 -143 3464 -2808 -4012 -1837 2868
2162 -2885 3727 -304 -2195 2683 3029 4243 -2888 -3273 -3662 2646 -1453 -3636 -1662 -3721 1203 3377 1291 3692
-4966 -4977 1463 -142 3147 1358 1164 1551 3553 3573 -3421 2428 2499 2827 1427 1718 -3533 1549 3053 -2528
-288 -739 1286 910 -3321 -1378 3258 -3289 3492 -4691 -595 3507 1759 3968 -4956 2433 75 1150 1428 -456
1653 -4388 3816 2091 -2113 -2818 -4180 1859 -3504 35 -2954 -2228 -3920 3125 1530 641 2933 1874 4422 -598
-1373 -4442 778 -3545 4067 2857 -2576 3878 76 -3708 -4572 -1901 -4608 -732 -1869 -3557 -2117 1394 -1785 -4472
-1135 -414 -3551 -3477 -1278 741 2031 4890 564 -2169 -4650 -25 2236 -2884 -3446 3820 -993 -3758 -4685 -14
4949 3782 -2 -2536 -363 -279 2976 3044 -4089 -4847 1419 966 860 3110 2930 409 1497 1530 -2271 3490
-156 375 -759 -591 -1693 -4541 -1697 -3184 -2801 -73 -1102 -4320 -4571 1249 -424 1426 4523 60 3096 -778
2424 3957 901 -1261 -4857 2972 2291 -4829 1203 -907 824 -4419 -3169 -3906 660 3143 -4425 -3308 -3387 -4091
1366 466 -4313 -3673 4841 349 4680 -3408 1013 -2983 1577 -2743 4620 -4773 314 -1622 3794 -1711 -2023 -1815
4892 1323 3979 4062 4572 4762 237 3192 -3037 1886 -4277 2248 -2130 -3999 4244 3566 678 -587 77 -2127
-1080 2227 253 3685 638 -1958 1753 -2337 1042 -3464 2576 1613 -739 -3407 2523 -3355 4828 2627 1676 4700
-1163 -3458 3007 1332 -4727 3568 1510 -3648 4346 23 3902 3297 -1227 -4480 1280 795 -1137 -369 -4218 -561
1165 -1865 -1302 4133 3567 3396 -1490 248 -843 -4958 -4811 1102 -2937 -2355 2424 1995 2542 -616 -569 1655
390 2174 -947 625 -2404 2235 4023 372 3184 3506 -1554 -4170 953 3797 350 -4291 1092 3576 -2990 2742
4838 2242 -3377 -70 -172 -1381 -330 -2666 -48 381 -4309 1340 1226 -2291 -842 -174 -2563 2928 1887 -4610
4409 351 2050 2462 -1419 826 -674 -213 4618 1273 3088 2933 2468 -2482 457 3266 3793 -25 591 -1754
-2117 -1454 -3876 -4790 -3936 1845 2084 -3029 723 -4103 -3614 -365 1774 -4827 4777 -1988 1010 2279 -1797 -2225
-955 4240 -1899 -1592 -1108 -598 -4411 300 -1625 4808 1336 715 -395 2198 -1308 -2666 -3524 4107 4733 4844
1538 3594 3828 1379 -3741 2262 374 -3394 -4410 2721 -1779 -4354 3299 818 4960 -3344 2703 2942 -2045 2883
-2811 1373 -4494 -3335 -651 1076 3421 1959 -4880 -886 3244 -2249 -3437 -4202 -1909 546 -4090 2824 -2353 -1998
-4796 -4282 4728 3926 337 -3028 -3455 -3045 3044 1206 3752 2493 -1468 -594 1723 -1168 3236 -2991 882 4475
-1523 -3040 1802 -4522 1130 -1685 -740 -1920 -1140 -1046 508 -3266 3568 -3573 1900 -990 124 1623 1306 -4440
3313 3561 -4568 -2753 785 -1993 -614 589 -2696 2801 3342 -1793 1490 -3823 4767 2211 1653 4989 -5000 -3370
2831 4192 -1813 185 2846 509 -4250 -678 -244 2771 -4823 -4300 3663 -913 3901 4126 57 599 3698 -3723
2432 1907 -485 -3841 13 -3568 -2943 -855 -4302 588 4153 -2052 3937 3108 2607 72 976 -64 -3091 -1507
-3161 2444 4560 1017 4355 2331 693 458 -4728 1778 4235 1644 -1697 -3735 -2667 1513 -4631 -3462 -1356 -3905
751 496 858 -106 -2526 3381 -4879 2971 -1032 669 -1740 4567 -1067 3926 -4216 4494 -146 2779 744 -1329
-251 -42 949 -4047 3546 534 230 -3464 -687 3681 -1581 -3236 1321 -674 4334 -2892 -4481 3890 1712 206
766 4871 1858 3226 -701 -601 -1257 -1780 -1734 3782 -208 -2008 4843 -3724 -3555 -3219 -2939 651 -3954 3850
4482 -3348 4015 -4083 -3842 -1563 -2993 612 -164 -2045 -3014 350 53 4978 -1873 600 322 -4540 -832 -2032
-769 1972 931 177 4459 3361 -2599 620 -605 2276 4008 4380 -3146 4572 -1871 572 -471 -4408 -1957 -1305
1027 -966 2034 -2392 -3289 -2043 4820 -3413 -713 -2158 -1416 4414 4393 -542 -137 -4665 4896 1854 -3203 2870
-4014 -1627 -2079 3819 1199 1632 3176 -4061 3624 -204 2288 2752 -4040 -3597 2339 2628 3178 -3816 -932 1881
-1540 -806 -1443 1711 4904 -4555 371 1822 -1594 4378 -2194 1965 -576 3431 -635 -2719 863 -3275 -3879 343
4507 4363 -2850 -408 -1518 -3835 596 -2964 2711 -4108 -4621 -4520 -818 4315 -2645 -1321 -4496 3348 872 3167
4287 -4004 -835 -4582 -3503 1930 -469 4192 -3214 1467 -2918 1072 1283 2073 -2555 3894 -3793 659 2859 -1220
3855 641 2956 582 3893 -4757 -4149 -1352 -1791 1020 -1836 -4889 2473 2359 -1023 -2994 4265 309 3128 4198
-3939 2873 584 3339 3068 3312 -321 3911 4837 4074 2159 -328 3293 1742 -4234 2553 -3138 4556 -3066 -972
2951 -2288 -2071 1248 -1451 -4035 -2569 -4748 -460 -1686 -2102 3148 -3092 -3447 4106 22 1112 -542 2885 -3453
1846 -75 -4225 -3715 3261 1303 -2502 2660 -2303 3279 4937 -3902 3070 -4966 -3470 3045 -4223 1888 760 4779
-2788 2040 4632 -839 -1520 3254 1577 4762 -3001 4345 -3917 -2801 648 -2140 -2283 3172 -3875 4951 1679 -4111
-4253 4341 -3441 -2783 2358 4345 4669 2829 440 2472 1432 -4881 -2190 -1347 1915 3880 1768 3158 1370 3664
-2477 1368 -50 -3350 3312 4163 -3088 -161 -4811 -3141 4214 3534 2106 -1734 -3577 -4189 1450 -3393 -4431 1092
-4819 3715 -4820 -517 378 3944 -4657 -2957 -879 3677 -658 3159 -1411 4363 -87 4397 2939 4434 1649 1283
-3002 -922 3556 243 4526 -4530 3964 2984 -2640 3376 732 -83 -1096 -3041 -2556 -1318 700 652 -71 -1098
264 237 -2756 1268 -1057 -1084 -2268 -3266 1887 3224 -3220 3929 -818 -3874 -3149 3774 3149 -1207 -1211 34
537 -678 -3786 4664 3463 2824 4847 -4803 -4903 3203 3256 -610 -1123 4025 327 3013 1249 -4172 916 530
-757 -1705 1822 3898 3585 1662 -9 -3440 679 2372 -384 1108 -422 554 4896 -2160 -3794 3126 2763 -494
1812 508 3881 -3042 -3197 4235 -1098 -1581 2464 -161 -2018 46 2959 2655 4779 2736 1899 4773 -650 -3235
-4826 -1491 1105 1458 3089 -3667 -3253 3507 -3209 1059 -3742 -1129 1745 -1138 -4422 888 -1206 4926 -1469 3545
1281 -4082 743 -1802 -3316 1141 -323 382 -4050 -996 -2843 3587 751 4641 4472 4192 -3790 3617 4846 -1982
-1971 -2302 -1596 -3427 -2098 4231 -2088 -3924 -4189 1533 4528 2781 -1815 4356 42 -359 3764 602 253 4904
3586 3060 -3488 -600 -4041 311 -4977 900 4334 2787 525 -3437 2125 966 1243 -33 -1114 4928 -2744 -4674
-1022 -2173 -3097 -927 1482 -423 -78 380 609 -4163 -1722 4851 1601 3346 306 -4419 -433 2912 -2578 3394
-1444 -4292 1151 -4980 -3002 -2231 604 128 -3387 2395 4958 4260 2955 -3693 -3232 3949 2898 3441 -4139 -4567
-1522 2711 -411 4082 -105 1151 3949 -1779 -576 557 -32 489 -4085 2523 -53 2439 224 -3346 -4888 -703
-2674 26 -1030 -4742 227 -2092 2700 3468 -3213 339 -2723 -1746 3479 1540 -1149 -2420 -6 -3015 -1926 2535
4786 -4529 -3517 3705 3403 1527 -2309 122 -2065 2393 -3757 842 3709 4983 -496 1670 2094 -3806 1374 -4374
-926 -2066 -4844 -3669 2721 4772 78 3571 -3647 -3483 -4820 3757 4475 -835 -664 2581 1420 3384 -4549 -4538
1694 -909 2241 4634 -1903 -1519 4306 3520 -3525 -2303 1619 2951 -4049 1341 -664 4328 4373 -4255 3703 -1789
-4141 -1373 -313 2132 2497 878 4648 -1153 4939 1773 -2750 4335 4 -2424 -3491 670 4938 42 -723 -4780
-4555 4442 -1472 -2554 535 -2224 -341 -2762 -2814 -1864 1693 -4332 1203 -533 -3593 -4873 4374 -3650 4125 -1
-1520 -1999 1133 -1798 4272 2085 4778 4408 3041 1226 3201 -557 2056 3903 -1900 -3357 -217 601 3582 -3328
814 -4200 -4534 3493 -1933 -2317 -4487 186 4532 456 3255 -1122 736 4248 -4812 3551 -1546 4062 -4372 1727
4606 -1433 -3660 -752 -4129 3035 4865 2425 -579 1652 -2578 4982 1158 -3142 -3116 -1905 -3505 -2807 3523 -2256
355 -4453 -2911 -4522 -4812 -4195 -975 -3527 -3112 131 -2490 -3426 4563 2106 3307 471 -4825 -2046 4800 2949
-3321 -2985 -585 -3843 -1664 -3087 -117 2472 3721 -2816 -1791 -3517 -838 1276 -1827 1924 -1288 -3116 3219 3102
-2950 3587 -3401 -517 2824 -4531 1007 -4611 4375 -2341 2350 3445 458 -17 1702 -1698 -3233 4600 -1908 -1351
1962 1813 -1609 -4308 4440 558 3233 -4867 4887 -1246 -3069 -2947 -2197 -3280 -2239 -2355 2253 1841 1580 4327
-1873 -1715 1661 4271 -672 -3263 -3054 -3054 591 1251 2160 1585 3184 -2381 1525 -1604 3801 468 -1251 2459
-190 -4665 -1410 -3170 -897 -2572 1107 -2541 4962 1233 3625 -373 -3891 2505 -1945 -123 -205 -298 -4694 -4179
1539 4010 -2541 -2427 666 -2381 1764 -4444 1570 -2581 -1849 624 2468 4198 -3723 1729 3358 -4524 602 490
-4269 -1663 4050 2988 -3490 -2983 2307 3385 -3664 3233 -865 2392 -2001 -659 -1761 -2839 -3520 3282 -2512 -3338
-3022 -2817 4243 -3820 3182 415 4827 -1883 -3428 3831 -2489 1667 1699 -2629 2483 4249 2782 -3417 1048 -436
-2877 -398 -862 -507 -3138 -3048 -4639 2513 3609 -1501 4072 -3475 3772 1599 -3228 -1759 437 3672 -1806 -1080
-1193 3161 -4579 -2513 -1414 -1811 -4415 4829 3759 -2814 1140 -2230 2451 -2773 -4687 -3315 -3305 1135 4271 3107
3202 2862 4734 548 -3348 4278 -1008 -3775 -4581 1305 2120 -2143 1889 -2020 -2922 793 -400 3198 3806 -1601
-241 -2276 4781 3162 -1576 1143 -2646 -2475 -1014 2530 988 4991 297 -2099 -4558 -44 453 2176 -3354 960
-1257 -637 -4818 1665 -2443 -4491 2137 2885 -906 2232 1036 -193 -1460 -1491 4579 -4283 1212 -1892 21 -1126
-223 2456 321 4107 64 2717 -4973 -2101 -758 3038 3753 -445 3163 2750 -3197 -4676 1739 4153 3675 -1717
4849 -1864 4037 -2186 2786 4339 -1641 -1153 827 3512 -3282 2894 2728 -476 -2703 -2761 4447 -2197 -1828 -1247
2611 999 2199 2127 -4132 947 -2840 -2302 -2179 405 2335 3950 -3215 -3258 4844 -348 -625 -4613 1320 -1576
-164 2435 2487 2501 -4417 -3273 -2208 -4859 -1429 -2271 3916 -2298 -2793 3962 -292 746 -2267 3208 3819 -1983
-1437 -2608 -1811 355 2607 -3516 -419 -87 3145 -1545 -3301 200 -4758 987 4773 2919 -3072 -3750 928 -4586
2669 1156 -3707 -2883 4034 -2943 -2926 344 -4649 -571 -2442 -3975 -2984 -1638 2801 -2036 4439 4878 4040 -4934
-4510 2397 794 -3952 1599 -4942 3901 -3844 2094 -3466 -1697 681 -3814 -2875 -3688 -4294 4939 2435 -602 -4548
-2673 -95 2426 -314 -1560 -4275 2222 -749 2612 -3230 4636 1705 1728 361 1644 3843 3279 -2939 -655 -4634
-3859 2263 2287 -3592 -2098 -3080 -1521 -2163 -3871 4780 1520 1658 1763 766 3944 -2540 -2013 656 -4160 4803
4153 -208 2279 -2330 -1569 -1969 4569 -2385 -3001 -4422 1837 -2900 -2628 2834 67 -1225 -286 1186 -1529 714
3733 1832 -4579 -473 -2574 -806 1647 507 -2540 2516 2615 2289 3504 -4904 -2476 -1300 2011 904 -4318 3439
2857 2465 664 -2917 -3658 4323 1109 -3043 159 -1606 -2373 -3037 1127 1345 16 -3540 1866 -2061 -4284 3095
-1461 828 3204 3334 -3027 4023 -51 2006 -4969 -483 1842 -1082 2551 -1253 -1927 -491 3645 -3441 -814 -3678
3768 2988 -2472 4631 -4000 -4280 -1951 241 -4469 -1421 2517 3040 2043 -1685 -240 4600 -2265 4124 3619 -1397
-2480 3440 -1595 -1226 -2304 4518 1405 -3129 -4456 -3357 4891 972 21 -796 18 2887 2245 -3347 3317 777
2721 1794 1146 1759 -1072 2729 666 1843 4037 -3973 4549 -2264 -660 -3368 -3307 1662 -4142 -612 -4267 -130
1242 3800 4244 1391 -2327 -1955 -2224 -434 -21 -3494 2680 -1984 -4774 -234 -4949 2604 -4166 -2827 3959 4075
2416 3535 4319 2074 2129 213 -1875 1764 -2439 83 4187 -4791 -976 -2321 319 -3323 1757 -1652 -4078 -177
914 747 891 817 3218 -2267 -4509 3164 4887 -14 -3965 3101 1661 -4920 -99 4642 -4371 -4159 3959 4466
-4062 1155 -4060 -2381 2423 288 1709 -169 4989 -3031 -802 4323 -1215 969 2059 -1770 -4471 3538 1222 -3666
-2238 -672 -936 -169 1976 -3602 3096 1900 3263 1059 -4399 3778 -1057 2521 -724 -2711 4329 -2561 -1448 1224
707 -1307 2825 -875 3352 3885 903 4669 -1318 -1803 1816 1532 2542 3444 3030 298 2136 163 1040 84
-4407 45 -4810 -860 -460 -939 2359 4830 2439 3507 4437 4410 2076 3641 1721 -3038 2565 3077 -3780 -3788
613 -1033 474 3826 -3282 1048 -997 -561 -4579 -1815 -3654 -3383 -28 -3945 302 -4547 4883 737 -3211 -515
3942 -4609 1010 -307 -3535 3942 -3039 564 4727 3669 -2227 -1697 898 -972 4606 -2376 4118 -2302 -3875 26
1930 1414 -4949 -1763 4825 105 73 -363 -4761 3808 -4031 4445 -4917 -2283 -2205 -804 4071 -1136 -2756 2914
-1558 -4721 1645 4027 -4689 -2875 -2184 4519 -1694 -248 4262 2792 4732 2311 -1900 -4635 2628 4561 1934 -2696
4739 2955 3054 263 -1250 -3611 598 3011 -4224 3237 -4193 -895 3053 -4030 3093 4089 1307 3574 -3919 -4735
361 1162 3280 233 4959 -1453 1888 -4773 -4864 -4699 -1806 -1441 1617 4901 1788 -4725 1440 -2128 4837 -1217
-2966 -2055 -3808 -3535 -1784 4198 -637 -600 -1410 4760 2992 -2408 1664 -2062 -910 879 -3436 -343 -3150 -4125
-2484 3224 -1561 -2930 -1546 2064 1884 -1437 2511 3818 -2648 -3460 -4733 464 2342 4962 4432 -1283 1373 -3092
-1315 1426 2972 763 -2147 -2123 4363 -1007 2198 2063 -605 1901 3605 2512 2867 2647 1955 575 2852 2874
-4743 -268 -4961 -4373 -3440 4398 -2905 3750 1389 -2583 -1654 1689 467 -718 -2863 -4865 4253 830 -1697 2000
-4600 -2162 -1453 -4390 2072 -4534 3245 4189 -772 1628 3650 3683 -3370 4180 2278 -1485 3872 1577 -4530 -1309
-2903 -4857 -1580 3764 4479 -3736 -477 3547 -2651 4926 -1996 -864 -301 1881 2108 1576 -2033 2508 268 -1725
865 -2768 -4992 -731 -3659 4548 -3633 2721 -3520 1106 -661 -352 -1932 3278 1578 -2282 2608 2234 4235 -2548
1561 713 162 -2518 3839 -4726 902 3721 1051 -3142 -2845 -1269 1977 839 1013 2398 -3647 4828 -1718 1400
-3480 -4387 1049 3874 4650 -2904 -4383 1205 -2554 -1711 1605 -2466 84 -2389 -2422 4145 4670 2778 3252 -29
-2202 3486 -1854 -3845 3021 1249 502 1594 3330 -497 -2574 1322 4470 2509 -1404 2561 -445 -4037 -1863 4655
-2868 -2673 2185 -3016 4725 -2840 -4810 -2247 -3651 513 -4783 -3559 3911 1519 -588 4678 -719 4708 1119 2986
236 -2461 -3011 -1559 -4757 3395 3411 1149 3367 2277 -3295 -4923 3758 -1324 2483 -3468 -279 -48 2079 4722
-2237 3181 4284 -4240 -3995 3921 2159 3220 4615 -1357 -66 3841 1314 805 -395 648 914 941 -4666 2514
-234 3186 4164 3983 2458 3055 -486 2172 -3574 3705 -4025 -2430 -3894 -64 2318 3877 4261 -699 2425 -1647
1231 1347 1899 2257 1286 4556 -2223 -4504 -1474 -3902 367 -145 267 377 -3381 1232 -1902 4339 870 2068
110 -547 2568 3850 4378 -4700 -756 3103 4192 -4008 1019 -2404 1681 485 -4609 -2742 1467 -645 2202 446
3818 -738 -1142 1740 -1347 -3539 -2925 -1542 -259 4757 880 2251 2128 -736 -4245 -2063 1639 276 121 -2081
3535 4107 2705 -3934 2279 692 1398 2750 4928 -3627 550 -3499 -692 -2919 -2958 -850 133 3148 -2698 -4698
1731 -4945 -1399 2345 1956 3645 -818 -3160 163 2439 -1465 324 -2142 2644 901 -1435 -1923 50 4863 -409
-4764 -4345 3455 -2606 2874 4742 -1172 1787 1254 -2437 2458 842 693 -2410 -4751 4355 -2328 -3946 -3932 1455
319 -270 3153 -2813 -4538 -2887 -2878 -2657 2822 2871 -701 4955 -3622 -2549 430 -3731 -3755 124 -2198 691
-3298 -2409 1564 -2642 -4482 -4606 -1347 2145 -2614 3283 -3518 -1814 3881 4200 -1310 -721 1806 -2052 -2178 4760
-3222 -2791 2611 -474 2807 4843 -2934 -885 -2303 -1196 -245 4635 -2517 2308 -3089 3086 -3046 4732 246 -2040
-1409 -1343 3067 -4868 -4449 4624 932 -2737 1287 -1424 369 -1263 -787 907 -605 -3889 3639 1147 -2286 -120
-575 3879 -2315 3819 63 -1146 -1287 -2397 3113 -648 1673 -4569 -3105 -1711 4221 834 -3247 4263 1399 -4620
900 4082 1462 4934 -4889 -895 -246 -2671 -3353 -1226 2615 -664 1466 -1526 4988 -1529 1507 1104 -3920 -407
-1332 682 627 997 -2620 4340 3605 3335 -1184 2002 -1793 3692 -3214 4998 -2894 -4440 -4331 3676 -17 2051
1265 232 2519 -3953 -4588 -1025 4039 51 -2666 -1877 4108 1921 -2641 3748 2675 2806 -380 1308 -4938 4037
-768 -331 -3197 1795 925 -3728 -1932 -3728 1142 44 1333 -2383 684 -637 2497 -3535 -4969 1262 -2753 -1138
-3134 -593 4232 -2808 -2773 2161 -4685 4558 -4117 -1048 766 -156 -2778 819 -4380 1199 -833 88 3637 -1339
431 -2420 -334 -2236 3010 3325 1792 651 2904 -4592 -1817 -842 -2024 -1027 2347 -1867 -1054 1650 -1540 -2279
-1555 1842 590 -4734 3085 -1782 -2669 -1240 2577 -1884 -3753 4414 -3304 -1187 -1253 3254 -3064 -4863 2609 -2419
2894 2190 -2462 -4917 2129 -1159 3697 -3578 1575 -4508 1802 -2148 -1667 -4480 3883 -126 -781 -2532 -1700 -3924
-3205 -979 -2794 1094 -4153 -1619 -2779 1293 -4253 2458 2261 -1488 2327 3821 -4499 3269 4248 -1619 -4056 -2016
-1805 1137 4656 -747 -3376 1757 -3249 -1964 1644 4944 2170 3659 -1770 -4033 -4546 -979 2308 -3793 -2196 811
4664 4545 -399 246 563 510 3094 -25 -3733 -2517 2201 4185 -3668 -1372 -4980 2992 2900 -3821 -684 -1653
-4725 4132 1532 3311 -3055 3254 3549 -2502 -1729 -639 4082 2997 -573 2806 1815 -4641 -907 51 -631 3366
42 3400 -2438 880 -4832 673 -3132 38 -127 932 3588 1789 865 -250 -1114 -1465 -4739 3478 -824 2856
4229 -1817 966 478 -2789 -1736 -766 1129 2666 -1182 -1869 1396 3602 -3534 -2879 -966 3169 307 2481 -3556
-1911 540 1306 3839 -3503 3486 2752 -2107 -4973 2999 1719 -3178 1365 -3497 3392 2424 -1479 970 -1541 -3325
4931 2150 -769 -4505 -24 1625 -556 -757 -2972 4230 650 751 4203 -1769 -502 3560 -734 2111 -1048 -1541
-3331 2245 -859 -2658 3443 -2600 1760 -746 -2139 -4010 2130 -1347 3857 3211 -71 4437 1371 -1954 -3212 -1658
-1310 -3601 4962 -3010 -2649 2620 1717 2368 -3478 1422 -1790 4778 -4135 4497 1983 -1297 1498 -3259 -1789 3939
-3571 -4961 -4816 -2124 3087 2984 1038 4903 -6 -3288 1742 2089 -385 4447 -4787 4916 -998 -2243 -533 -4053
-287 2749 4294 -1874 -51 1396 -1529 -1596 3857 -3017 2616 -4378 -4469 -4640 -1996 1696 2225 -1087 -2198 -1641
2433 1360 -3351 971 333 4566 -3553 206 2279 752 -2043 1061 891 -1084 848 2968 2284 -3105 -1190 -1066
2956 4918 -3973 -2620 4015 -4154 -293 3483 1325 3880 -2355 3857 -2078 1035 -1386 -993 -3100 -285 344 -4790
-793 1065 -4206 1907 2020 4616 -1634 -4315 2794 2304 -4678 919 1437 -1552 4887 -2120 843 -2600 4141 -3401
3757 943 3659 2424 -764 2862 3177 2530 -745 2389 4127 88 3505 -3247 -3755 -3188 1261 3862 -4397 442
863 -824 -2514 2451 1850 29 -4006 162 -4335 -274 -1161 -4653 3227 -3190 422 -2213 -190 -326 4609 4605
3793 1053 450 4111 -2946 993 4564 2353 -2194 -3034 -2211 4739 -567 1362 -858 4845 -1291 -1342 2508 1714
-824 -2036 -3454 -185 -279 4425 -2798 -77 -3040 2053 2910 4815 -4629 920 -2642 -3819 4199 -1411 173 4293
1632 3991 2854 -3719 -4570 -2721 -4608 -4578 -3394 -2979 -3478 2036 4039 905 -9 960 3533 1815 -4882 -3692
4021 2693 -2040 4160 2238 -647 2851 -3350 1147 1480 1460 -2287 4300 4450 4019 -911 1613 3629 2884 3692
4527 4444 2481 -4310 3675 3652 -814 3043 -4900 3728 -3864 -618 3083 1327 -1102 2407 -3130 944 -4016 -1433
-1534 -2331 -1184 -303 4614 -1394 4784 373 -1266 4589 3108 4221 -2638 -3088 1193 -2296 2980 4552 3276 4104
0 -3571 1937 -3822 3156 -448 -1285 -1596 3026 4617 -2057 -74 2225 4216 -9 1765 -1349 -3716 -3509 2085
4302 3953 3771 -208 -1396 -1879 3690 -2921 3986 -2422 -3141 -702 3574 -1121 2616 -2519 -4097 1938 2886 419
2837 -3545 -605 -3598 4875 190 4331 -3426 -2619 3414 2039 -4068 -2751 -343 3365 3169 4372 -734 2260 2767
-1863 -2680 -1777 -3923 837 -716 -1986 1477 1094 -727 -2034 4192 -4798 -4085 494 670 -550 -3718 3245 1877
-4452 -1360 3114 -3121 -395 283 -4351 -3615 -277 -2755 4923 2384 -2620 869 -3372 2779 3694 2845 -4198 1159
2327 2827 -3209 1363 -1014 -4463 -367 2455 -1210 1071 -4134 139 -1597 -1442 488 -2671 2321 -2824 -4487 1604
-3975 3744 -364 -4216 2296 3654 3339 -3459 -3954 2874 -2530 3147 274 260 -4148 2675 1818 2935 -4997 3390
-3258 -4258 -2734 -3268 -3823 -2279 -3737 -536 -61 4843 243 -4988 -2705 -4511 1895 4958 -1192 2656 -2475 -2336
798 -1562 -4136 2604 -422 -44 -1117 -2591 -1759 -3402 1112 165 2830 -1338 -238 -1479 1778 -647 3171 -2220
-109 4876 268 854 -615 4947 -3834 4060 -2809 1149 -2144 -3588 3934 3077 2839 -4299 -2573 4636 -4588 3088
1994 4540 326 2261 457 -4850 -1787 3099 3587 -3249 -4833 -1852 -159 594 -90 -1982 -1521 -2485 4377 2919
3505 306 1127 3248 -164 -2707 3946 -361 666 4677 3200 751 1794 3913 -3563 3496 3337 -2054 843 -1135
1651 -3180 4178 -1234 712 -2398 3410 569 -2373 -1521 1156 -2484 -2126 2343 -2024 1090 1805 4105 3784 -4202
-1773 2419 -801 3337 3240 -3108 2294 -615 4581 1119 -2631 4367 2475 4532 -1815 -2659 -3985 1721 -4043 -173
-392 4998 3394 1593 4440 -3144 -4102 3292 -428 -2411 940 1100 -4866 4001 -4893 -4712 -3881 -2838 4797 -2528
-1312 1589 -198 2705 2761 -4071 1014 -4465 390 -887 268 2935 2800 4362 4937 -4163 -1829 4239 2073 1481
-4549 -2909 854 -2583 -1454 3965 2845 246 -2707 -3917 -4918 -1769 1876 -4782 4787 -3583 -248 -169 4548 -1876
3701 -3781 41 4355 2264 1465 -3544 815 3685 3901 1298 710 -4580 2714 21 1985 3372 2566 -2456 -2052
-2369 -897 576 -19 513 1433 -379 2156 4822 -385 -4592 2052 2759 -3935 3781 573 -3848 528 -2944 -4060
2281 2622 2862 -2076 -381 -1853 -2470 -1644 -1983 3411 -1250 -3800 -1745 2019 -3364 756 4395 1844 4521 2621
-3037 -4669 2864 -2448 -800 815 -1393 -220 -605 1962 -1789 1076 -1704 -240 -1309 -4220 -912 3755 -4213 -1598
1282 -2951 -4356 4701 -3471 1612 -2091 -4602 -1460 -809 -3332 -3521 277 -604 -1722 3422 4972 4209 -2080 -369
-1291 2768 2189 4635 -888 1519 4366 -4460 516 2818 -205 -3320 4489 -79 -1178 4736 -2808 749 1671 1570
606 -430 -2580 4857 -2472 391 -152 4147 -1200 -707 85 146 3386 4521 2661 3534 -718 726 -2390 1540
2786 2845 -3517 4283 1814 -4595 -4950 1535 1083 -4410 -2593 -1222 -3362 -393 4844 -3442 1206 4898 -1833 2510
2087 4281 2882 -4056 2233 4975 -4504 -3842 -1541 -2262 2412 2736 2934 3090 -1570 -1159 -3420 3343 1360 -2437
-2867 -1576 -3188 3633 1121 1706 3188 -975 -1174 -4463 -559 3859 -4786 -2679 772 -1111 504 4529 869 -3707
663 1697 -3312 -1597 -3066 -4495 -3412 4465 -2746 -3207 4347 -3912 -4849 4246 -4275 202 -4423 2157 -4990 -1625
1331 -1469 4669 788 -4932 416 -4396 -2750 1837 -121 -281 -3436 4854 2531 194 1191 -434 -426 -2784 3533
-4434 2742 439 -4567 4891 502 -3461 -219 -3870 484 -444 -2251 4307 -2006 -3580 314 -1456 -4159 2787 441
-3093 -3494 3257 1765 4385 -887 2124 -2394 2215 -136 2410 115 3775 -977 -442 4783 -1105 -3452 2060 3652
-3153 775 671 4538 4577 2375 -3168 4956 -101 1148 -1991 -1248 2412 -174 3828 -4780 -1870 4839 608 2117
-4667 -3135 504 3642 1087 -4599 4690 -4196 3269 2360 1938 4836 1431 -1042 3016 1685 3663 2280 -3129 -2694
4499 -4194 -4297 4803 1396 -3519 1633 995 -2 3987 -1273 3994 1487 -257 698 -4385 -2014 -4404 -40 -1185
-1179 4703 2083 3218 -2867 4808 2602 3511 3126 4241 -3274 -1824 2037 3287 1006 -2282 4336 -2578 1259 2797
-3017 -3710 -1546 1801 4816 -777 4373 2995 -1953 1784 -2989 1287 -4955 3501 -1857 2262 2430 1209 -328 3896
4076 -71 481 -84 535 -3053 1103 -4022 -2393 -4526 -3476 1835 -4429 -3192 -2521 -3975 2041 2915 4247 1532
-3061 248 -4221 -2204 3125 -4310 1436 -3683 -1846 -495 39 -3432 -3274 -3805 391 943 -3142 2911 3082 2778
713 2573 4910 2498 854 -2651 -3271 2151 1513 4671 -2552 1772 -1263 -3807 -4424 -1690 -673 1689 -3563 -3940
-3839 -3234 257 -681 -818 4935 911 1588 -1554 -4430 1987 -3352 -1046 -3700 3023 3643 3919 -1618 2969 3132
-4680 -1359 772 2983 -73 2126 -1312 2953 -4445 923 -298 4021 -4697 -4346 288 -1302 -1662 1845 2317 -3754
2253 2261 -3655 -3301 -338 1259 3123 -1460 -655 713 -2370 -4473 -1790 -4794 -3112 -3342 -3319 1976 3015 41
1590 4568 -603 -2140 3499 1673 -2834 911 3756 -2044 413 2632 -65 -2018 -3157 -2933 -4119 -251 -1566 -1857
3712 3200 -1645 -4933 1664 -4822 4275 -4343 -7 -4781 -1506 -4414 -4319 -3233 -2156 243 4424 4211 -379 4225
2065 -2907 4937 -1979 4940 -4455 3152 -4742 -4695 -3368 3026 1717 -4070 -1883 4573 2982 -1876 388 121 -185
-4110 -4534 -479 -480 711 -2984 -2653 4432 4824 1443 1297 -2716 3014 -2583 -3107 -4569 3080 3764 -168 3512
-258 3065 513 -186 3330 1965 149 585 -1287 3684 -2330 4522 -2387 2798 302 730 -4983 -2559 4861 -516
-3781 -2596 3493 674 4969 -44 1663 31 -3278 -4061 3795 461 1549 -2881 4461 -2175 3336 4652 -3745 2337
-1958 -598 1299 55 -2407 739 2014 -1106 3146 -386 196 996 -1350 -3908 -4214 -1042 -1545 -530 2850 1830
-691 3666 -1284 34 -857 -836 4710 2024 56 1551 4182 5000 2859 671 3823 4674 -72 -3537 4362 -4221
-283 -1597 -2160 2118 2452 -3167 -3717 -1918 -4379 -2890 -3919 -1907 3398 -1831 3261 498 3613 4642 -4068 1339
-1935 -2648 -2243 1001 -542 -3283 432 -2055 -3480 -3069 -4379 -395 4873 3433 456 -2365 4091 384 -1681 3867
-2021 3027 -1101 1032 -4761 1463 2370 1557 -865 -1797 172 653 -4545 -538 -661 2647 -584 -2425 4368 2740
-4987 1877 931 -370 1779 -4971 1876 1568 -4451 1870 1221 -289 3058 898 -2164 -3072 1778 -727 -2185 278
1615 1578 4012 -1060 -4728 2232 -4200 -4663 -953 -4340 2069 1604 4293 -103 -3007 2039 -4706 1223 2763 2597
-623 -4117 119 -4132 -2279 -188 -2519 2154 -337 2269 2590 2385 -3436 1124 -2202 2889 4016 -2408 290 -117
-1915 -4497 3612 3116 3880 -4971 -2697 -1387 4831 -4682 -2838 2349 3754 1931 2646 44 169 -4527 1055 3652
-576 -1940 2806 -4471 -1405 -2286 1886 4021 3330 3699 130 -2457 729 1021 3597 -628 -4435 2853 -1750 637
393 -1157 4927 1557 -2142 1097 1024 -4673 2502 -1908 4011 -2472 -2808 2273 4003 -2351 -4560 -220 4620 2534
1950 -4972 -818 416 941 -852 -1610 2667 -4306 724 252 646 4973 -4875 -294 1684 2837 4543 1736 3150
683 -4538 4093 561 -4630 -3516 -2403 -1641 -1030 -1199 -1054 1099 -4975 2403 2276 4632 -1912 170 1292 1468
2023 -1672 -1945 -101 -646 -4462 -3106 -1134 -1277 3701 -1642 3521 -2023 -2130 4621 2727 2525 -1681 4311 4600
1284 -355 -2758 -4781 3618 -1713 -3977 978 2604 -539 -3740 2638 4668 571 -4178 -3098 -2749 3729 -3739 -1942
4302 -3185 79 -4839 -158 -1199 -4114 2122 -2975 966 -2474 3687 1762 -2494 3063 -2706 2083 -1200 -2197 -1096
-2665 -3286 2684 -3146 -1058 3363 -2273 -2574 -601 1070 3457 -1602 4795 2604 3521 -1074 -3626 4862 2201 -3879
3459 -72 2139 4456 -1495 -2422 -2682 -225 4144 -1875 3951 -173 -4805 35 -2589 -2836 -2780 -4341 3769 -2888
3617 4065 1270 2624 -3992 3986 2871 -1466 183 -367 -2416 -4699 -1535 -1285 623 4162 -4677 -4901 -4193 1943
-2280 2820 3197 4997 88 3593 -3970 -1410 -180 1688 1508 4343 -4804 4589 -3885 134 2393 4304 1656 -1944
4441 -3895 647 4477 -1571 478 1858 1882 -823 -1686 4239 1196 2806 -1244 3535 2512 -4258 -4322 1211 -1169
3236 -3142 -958 -947 -812 1893 -1671 4465 4045 4640 -1643 377 4896 4356 369 2755 1642 4409 1889 -4582
-394 -4625 -4918 1998 4459 3111 -927 -4408 3349 -2478 -3970 -3230 -2604 -3432 -2828 -4619 -4761 -1688 4283 612
-1265 -4571 -1083 3147 -189 2530 438 4896 1990 4961 -3613 -3766 4370 -1446 1460 -3580 -4076 1191 -3317 85
699 -3555 1851 -4596 -3263 675 -331 1856 4904 4797 1821 -1915 -2823 -2297 -4928 292 -1228 -2086 4193 -4285
-3950 1782 938 501 -1654 -4070 -2326 -3470 2210 2519 2195 -3133 672 1399 -277 -2219 10 3448 -1375 662
-4954 -693 -4543 -430 4610 -2706 4568 -261 4557 739 -4966 -4927 -2305 1547 -3958 4070 953 -1679 -3790 3925
967 -1691 2604 3484 3024 -399 -1006 4710 -1687 4046 4555 4954 -4221 -2368 -296 -2011 -3886 168 1414 -4400
-1997 -4793 2318 712 -1241 -3276 2655 -4156 937 2034 -3548 -2150 3460 -4101 2888 -3442 -4526 -2489 -3379 2147
4494 4290 -2119 -3266 -2509 -1999 4135 -2519 4687 -1353 3268 1344 -3757 247 4396 702 2638 3931 -3815 -1164
-3661 3986 -2720 -3961 -842 3435 3541 -4243 -1245 -536 -3393 3389 4077 459 3873 4151 558 513 -3672 -3429
2193 1288 -4662 3530 2948 -1264 -2168 2463 -1706 333 4937 -11 1509 4818 589 -4806 -1144 -2462 2336 -786
3646 -983 3784 1333 2688 520 360 -1687 -2286 -2048 -2637 -4278 -251 -3305 4044 4564 -3486 4043 2730 4320
2165 -3768 -1775 553 4350 1559 3712 -3711 -434 -4876 2607 -1953 504 2380 -3030 -1049 2699 -2126 1480 -363
2057 -4919 3563 1713 -2751 1242 -2791 48 2233 4598 -3306 -155 -2706 3000 -1172 -1749 793 -2075 4885 -3616
-1028 -768 2106 2691 -4111 -4 1992 -278 3481 -4275 435 1022 344 -1175 -2592 202 -3729 1990 85 3357
-1725 -4226 -3084 -1331 -1434 681 3149 4803 3451 651 -4273 -725 -740 1206 -3328 4124 256 -3643 4661 -1772
-2637 -1396 1566 -10 2860 -4561 2058 -2409 -2893 1760 589 -2964 4873 -3139 3308 4922 -697 -3924 1925 -1710
-1175 -1145 1472 -2730 3681 -4756 2340 -4108 -4299 -4263 3852 2298 1628 -1686 1472 3631 -1584 -3114 -3927 -4379
2117 -1692 1831 -1575 2710 631 -2615 -1226 -400 -4454 1511 2025 -3729 -3876 332 -1050 -1642 1956 -2112 -4641
2335 -1156 697 -1917 -487 -4146 -122 1053 -419 -2794 -4994 -3159 -290 4405 -1009 4427 -2584 176 -2569 -1414
-70 3300 -726 -772 4241 962 60 -1727 3480 -1937 2252 -446 917 3741 2506 -3303 -3116 2052 1648 2929
789 3215 4240 -3517 4103 -3679 4518 -2886 -3207 -604 -2087 614 -4535 1396 852 -3827 4408 -4767 4202 -243
-2576 -2569 -4719 1911 3025 -3244 2381 3133 3969 2620 1397 238 2234 -4965 -1964 -4518 2798 -1849 4930 847
3153 -506 -591 -3892 -2490 -4020 -1312 2760 -2889 -4637 679 -869 3860 -3993 -2672 3849 -4326 2514 342 -231
3845 -3379 -879 622 -4337 4053 -2320 4189 4368 305 1231 -1097 363 2896 -640 -3751 -2830 2438 4734 3555
1370 280 4149 4504 -1324 3876 -481 1344 -2687 -1172 4182 -1625 4668 -3776 -6 -1588 -4179 1701 -3051 3580
-615 -579 566 934 -4533 -1374 2333 2866 -4637 -192 -1616 1001 1950 26 -4421 -3097 549 4329 -2739 428
-978 -4989 -4554 816 1538 -2800 3926 -2222 -4641 -4189 1884 2304 1616 -2366 -1586 -1121 -3289 2401 4272 3715
4208 -2085 856 2231 -4430 -2665 -4869 2346 -4693 2681 -1878 -3638 -201 -1103 1141 4001 4560 3861 -3310 2921
-2884 -391 -4280 1331 -3583 1700 -1532 -3300 2669 2442 -4227 -2725 -953 -3788 -4540 -4581 3921 -4499 -4713 3448
-1112 -4745 -1282 1607 1419 4511 -1100 1932 791 -1617 3301 819 2276 -503 -2223 3849 -1682 -1294 -2243 2366
-4532 3280 156 3173 56 4979 1209 2443 39 1928 3358 1021 -2238 2078 1073 4247 2688 2873 -3441 -1798
-672 312 -4603 4828 -4915 -2895 -64 3794 -4597 -2354 3261 -1434 1483 3890 -999 4431 -1275 4465 2981 -3866
-2981 -1578 4181 -4981 -1958 -338 -3279 -738 -819 825 242 4021 -408 -2135 691 861 380 -4186 -3566 992
-3647 2592 -3396 -3034 2780 -4995 -4204 -1689 -4594 3804 117 4102 1401 -2189 2948 1892 -4357 -1984 -1249 356
-3966 -554 3206 -3794 1150 -346 -2979 2958 -2322 -2546 -3225 2489 -3179 -917 3711 304 -677 2035 3894 -1189
882 -3732 2516 -1525 -281 -797 -3474 -4286 -2844 2150 -701 -834 4454 969 254 359 -4925 -4744 -2408 -2502
3240 -2483 -3480 2253 -1675 -363 4729 1106 55 490 -1674 4384 3835 -3428 -1454 -4011 -1558 485 -4528 3855
-4349 3646 3675 -2400 -2150 2859 -3605 923 -3843 289 4645 4457 -3834 -2260 -2144 -3880 1069 -4544 2536 1827
2237 -4553 -2590 1382 190 903 2376 2035 3982 -3367 3717 4362 1486 -4922 -1151 -1825 1783 -4889 678 2291
2232 -4171 -3616 310 -2353 -4446 -459 -1639 -1592 -1488 4419 4851 3014 -4928 -2873 -2792 1550 -3473 4162 3617
-2543 -2925 1161 1048 4083 553 2322 -3680 447 4266 1109 2205 -2348 3233 -4652 -1683 1486 -4819 577 -2812
-465 1677 1777 -409 2960 1352 3002 -2420 4511 4880 1480 -828 2513 2899 382 4210 555 -4807 743 2454
-2872 -4004 -2929 -4223 1004 1113 -991 -4127 726 1665 628 417 4397 -3199 761 -1670 -1104 -1333 -2298 2906
1439 3849 588 2593 287 3062 1762 -2555 2610 -3508 2956 4172 1986 2708 4442 4566 -151 -3788 -661 -2590
3806 -2196 -3420 4961 -1752 -3160 -146 -4395 -4645 -1979 -4833 4136 -1287 -3285 558 2151 4305 -3245 375 -3739
-4898 -237 3880 -1892 -3666 -3540 4339 2560 3920 3653 -221 -2649 -4193 -205 1638 737 3314 -3696 -4800 2880
3716 -2872 -916 3251 -3018 4436 160 -3345 4989 1162 1209 -1353 4656 -530 -4435 1393 -4135 1989 -2191 2190
-3874 -2304 -2743 3570 -3315 3687 -2702 1680 -3696 -1969 -822 -1207 -1897 2020 -2699 -413 4337 -701 -881 -4255
-2479 3747 -802 3467 1490 4634 4519 2198 -3916 -1512 3641 3950 1900 3149 -3817 1608 3073 -332 1649 2029
-2191 -1091 -4151 3345 -2401 -134 101 -822 2880 1579 131 -2628 3123 3668 2523 2777 4636 2675 439 -4370
151 -1701 -432 -1271 -2709 3082 438 -586 -1 1401 3946 -2039 1952 -2626 1625 2129 -1387 1020 4269 3274
-1377 -557 1478 -2849 1668 2238 3803 2768 4221 -3659 3649 -4704 3740 1934 4822 2079 4881 -2749 -4612 -3166
-3564 1802 -2552 -2364 2298 4771 260 2857 -4494 -2808 -3953 -345 1674 3616 -4537 -1898 -1064 -145 -2020 3345
-2668 179 637 523 -3174 2817 -2462 -3196 1884 -2229 1638 -3897 -2185 -2138 3024 -1043 -1439 -1446 747 -3442
-4187 3566 682 -99 206 2724 3247 -1873 1734 548 3961 1122 2430 -1532 -725 -1653 -2142 702 -3899 2761
-4781 -4207 -57 -4476 -4306 -2562 -1214 -2068 -36 1176 3131 4462 547 -3626 4364 -331 -1019 496 -1167 -22
-4381 -762 1076 -1882 -1293 -1193 2438 78 3411 3396 2074 -1249 1896 4312 -363 -4575 -50 1722 -233 -4284
-2527 -4462 -4129 -4606 -4059 -2834 -579 -3778 1622 3904 -255 2254 3968 611 -3621 -3573 -4939 -1115 2246 -805
844 -592 -4496 -2451 2163 3043 -4164 -554 530 -3484 -2665 -3632 -4227 -1975 2236 -4816 4232 3031 -2098 -61
2745 -3450 -1233 -3669 -4555 1242 -4455 -2010 1332 -2538 483 -280 -1173 -4303 -1755 -2201 -1369 -4824 -1660 2108
4701 956 -967 -4236 -1157 4233 1186 -2521 -2375 -1441 -2017 -4990 -627 3356 1098 -4535 1285 -1607 2001 -3198
-358 3741 -118 1537 4191 3827 55 4364 -1664 3435 -479 3677 -1823 2333 3519 2712 -695 1953 1726 2173
1701 -4301 -4094 2154 -820 3014 991 -1417 -579 3866 -807 4255 57 408 85 1446 -2383 1112 1483 849
3257 -3938 1594 -33 -4779 -2624 3201 -2279 -2484 4089 -2086 -1275 -1777 2917 4032 -4556 -242 3170 4671 -3521
1763 4533 -1595 3296 -821 -821 -4394 1209 3714 -2057 3889 2131 858 1255 2784 -1272 -3878 4502 2058 468
1372 -840 3272 718 3359 -4425 4490 3241 -4923 -2526 -1309 4432 -1528 -318 3563 -1792 -3784 1201 2948 4050
3104 -2217 3698 7 3322 4943 738 -1032 1521 -3104 3776 -2184 -314 2016 1655 -4837 4309 -1013 2124 4118
-557 4849 2371 1809 820 4647 2736 2589 1678 -3603 -3723 -3149 3963 2179 -3966 1673 3043 -667 1436 -1918
-2847 -2134 -4418 3222 -1124 -2346 -1002 -883 -3584 2906 1787 1209 -2260 2185 -3728 -4256 -1024 -1307 601 576
-1119 962 4581 4253 4998 681 3122 2795 -1277 -1881 2755 -1424 -3579 2358 2114 -4842 1475 4190 -373 -543
2781 -80 -3770 -1757 366 413 -2270 1244 804 -3280 4550 -4201 361 2489 -2277 -2307 -4247 -899 1952 1259
-453 -3278 -3076 1206 -332 -2286 4843 356 -4438 3112 -2870 1270 -4804 3865 1261 1092 -4189 -4929 4804 3263
', '286166
', 10, 0, 105544, 7),
(1002, 1, 'sample', '4 9
2 7 11 15
', '1 2
', 10, 1, 14, 4),
(1002, 2, 'sample', '3 6
3 2 4
', '2 3
', 10, 1, 10, 4),
(1002, 3, 'normal', '2 6
3 3
', '1 2
', 10, 0, 8, 4),
(1002, 4, 'normal', '5 10
1 2 3 4 6
', '4 5
', 10, 0, 15, 4),
(1002, 5, 'normal', '6 0
-3 4 3 90 0 0
', '1 3
', 10, 0, 18, 4),
(1002, 6, 'normal', '4 100
1 2 3 4
', '-1
', 10, 0, 14, 3),
(1002, 7, 'edge', '2 1
0 1
', '1 2
', 10, 0, 8, 4),
(1002, 8, 'edge', '6 5
1 4 2 3 5 0
', '1 2
', 10, 0, 16, 4),
(1002, 9, 'stress', '20000 -949738
-622601 -889608 -371397 -556539 677678 -876866 107551 336566 -742481 -572629 297268 415351 -375855 834692 -69341 -358649 -63831 951816 -52378 52723
-811779 289861 697778 -929378 -288291 520594 -928980 297922 -811873 -121564 943307 -824827 -177073 -298791 -273026 347162 392547 -664362 -914433 -768968
893604 -151581 -955349 435346 206104 -102484 915979 -726677 -718767 -635242 -175551 23270 -65364 104291 244991 -617119 -240111 -385779 -872504 548931
-512165 665079 -584586 -58581 191280 -404924 -459512 -67135 122635 846188 984028 295660 345400 -313778 -772625 843938 -341707 77028 -621427 -101126
-826800 372346 -345980 279543 707584 729044 -920622 -822443 116282 317654 -646675 -991608 935760 -276849 785102 700614 -580256 -564996 929661 -401891
-770214 598182 447210 36419 -611717 -133454 -23929 716095 604944 375655 -458292 839851 -451862 -330167 792286 586448 796756 942356 -134973 879177
-375525 -966561 -507762 957738 326872 666453 -681686 156354 82438 -701267 68812 612830 -727529 -806129 -941894 602356 -232150 290117 757095 -284259
-948587 232841 -101309 554950 -492371 -264236 -9655 910884 -707095 140946 -908265 703596 677254 -657578 -931728 -517405 -313598 781358 -368335 567937
-834145 984564 -411797 770058 639471 362242 630320 958276 -391398 279445 -542508 -382307 -183030 -364950 465937 -230425 157406 461431 292785 418300
-329700 -108492 465246 158361 596941 -472817 139329 612894 -988489 -908931 -857435 942969 -690750 -26233 -167366 798152 605699 400011 -708556 247858
896583 -373169 98947 855224 77571 -532260 -394573 247908 956963 670193 -332590 232356 -409794 -206033 161918 420352 113125 -382534 -229532 322471
-127170 207750 -73247 -567264 -596192 -860848 -414259 379677 -605110 -267489 939140 -642294 42320 -204572 382956 717093 -90442 178664 404435 550819
-32946 976559 353753 -179109 -556673 475468 -70130 877216 869399 -208160 -160696 -216174 943811 817570 315276 -306123 654616 -988068 -969690 960448
-732784 -903761 227590 -741361 -302238 -753665 568344 259568 68016 -60903 75839 -811471 -301640 278701 -559147 670435 900588 510988 526086 -380035
-404653 -21416 -192507 136847 928670 134653 -414126 -276498 -36814 714927 274568 -220080 -777182 -25882 549809 705226 -327459 467396 793583 526370
-343225 -812926 241413 -397217 270731 288202 -708543 -296595 205126 -800942 -454788 -563109 -945154 886108 -729086 -28555 -69429 312986 -975652 -906635
-641592 -27560 692601 987938 -621567 713145 542159 -720301 -849550 887160 729536 -447947 518404 660454 605796 -753342 -41709 -243437 -786423 571083
-937443 -426191 75404 -944337 98541 869832 487790 482677 -447363 195793 -674469 138538 979659 666534 808169 921920 -452872 -234584 238741 322852
-959073 -420518 -435380 -977505 242773 -465590 -766618 -322600 -933057 240093 496561 78503 629271 -709310 455469 704610 995068 166113 -709055 -415548
-790945 -446245 348539 591290 676672 -183420 -902170 991210 -316388 355594 247960 698213 542366 685140 -596987 -3750 -832886 -969119 -993592 -515912
841423 991200 251733 -765329 -811325 -923563 -167991 115716 2769 -997933 159602 408724 -119228 -312520 252502 -326189 100669 828600 22368 -969825
256917 -544137 -314282 206708 236618 -761163 16161 -886828 -352141 155489 -28624 -940668 -291290 -346050 676806 -92578 -382285 -492603 712602 82822
-307493 -673973 -819210 354141 -741210 205573 -506866 -384175 -221817 872976 45468 196406 -228826 187498 -208763 -650094 -58110 574130 861767 -738461
-680073 -522352 221239 -822861 -231022 -197243 503721 -361798 748221 -936928 -82850 -532323 -170230 795474 752373 776084 663951 117080 88541 723944
867141 -751808 353481 -776582 39402 -569459 428279 661953 -919859 -279693 865512 -65535 117683 -700558 782056 -736725 902032 560876 -931625 718229
-677321 -91375 -691563 870746 -750603 -3779 823556 -440960 820938 837015 -709881 -213179 203239 56375 -757000 179006 956413 420752 58746 893187
78083 -470914 850391 -597277 859376 133631 -782336 387292 -276672 536655 -915181 618686 145753 -844177 -387485 229839 509273 21058 903497 -383611
43069 -311794 3976 -715225 332193 -786563 708899 72193 841251 -199876 760433 -149056 261921 349461 365459 114693 242319 -555755 -989259 -251059
959178 -13216 -966922 -972966 543925 -481381 670767 68418 -545449 977132 218746 314659 124131 -379352 802455 879007 295204 967068 112661 194045
-696133 963799 -982379 832394 82326 950192 725837 -316716 -586874 -684395 -753900 849766 869960 -196147 826367 729573 -392089 251746 922425 -189575
187855 -548108 -448380 33361 -79885 -477865 -623462 -555268 824997 -879204 -213662 -940869 407007 -829448 -301356 -840978 -166092 721470 826759 -1924
-9512 -383495 -813662 340092 55155 -604249 157150 726751 -708179 448001 -768176 -746817 816773 -435690 429691 440021 219959 -171937 440103 596326
454390 -962187 511112 -347436 -537025 668115 443088 -678837 456668 840668 -639358 -300064 -192557 -387376 -872028 238270 -312357 -261934 -196923 -365665
458290 202685 257247 449488 731021 299643 732374 -461082 722544 -713554 -580138 157711 -343219 -654667 249766 -387397 -587754 -987688 811951 353321
-814625 -326981 -241632 -456789 -661390 908322 264783 352954 -291648 -745872 -699454 -989580 -68484 857012 -50914 833870 -273930 -671071 140198 -444270
928407 599507 -251008 627874 801282 -500632 769144 -236871 377097 -232404 -875844 -817981 915998 -144630 -676289 -851856 380967 190682 -566050 -624384
909525 259568 768201 -839879 192635 283836 187367 -854082 -561136 -136653 708091 793513 456203 -126759 396022 -982799 53233 10044 -577750 122746
764670 -201727 552181 976896 -79427 238729 165563 654805 80902 216246 505720 130249 123862 -979465 508791 634313 -888831 -22697 -803837 173195
-644507 -585451 -779023 10412 679439 -907419 486251 -894691 -617368 -427349 134525 -130097 565017 886866 433898 -445888 401862 -15264 -104339 177628
-172880 -463608 -903240 -322544 600769 -537952 -167149 -400348 -394548 -348753 -201382 88503 463163 36790 -742504 -719185 294590 -374682 473046 -134790
-967099 -955878 -294188 -915807 -644700 -707619 632513 -840258 -390094 -143853 815637 336171 -790635 593436 546396 -221856 5740 329296 -556362 933589
854425 254201 -600025 570576 177722 129939 -867369 -694267 -981810 971296 -752939 298614 932187 -306874 977264 -344997 -295436 -406994 679462 920710
-801598 -690944 990883 68245 554623 -279004 -684381 882000 461388 545008 667333 382424 53342 -349829 -642245 -436682 875446 899140 507850 -167951
-399813 -317746 759909 -644326 -454457 -418929 -6671 -632032 -433754 -683381 -596960 -635488 -138751 -767591 233031 457651 906804 788326 46369 394495
-897877 741838 237647 -791405 -895041 -962531 106014 213136 -254012 117245 592650 383094 163412 -580603 154070 -162019 228764 537494 437593 571045
848919 -429594 209551 -765938 892729 -880709 872495 -648648 729915 984981 403017 317427 642402 -337593 78775 -733738 73594 473389 -815049 420351
641498 274310 753022 -414066 643713 854292 858237 111947 -637393 723671 727686 -613172 -450296 -708917 846732 -4012 8240 387136 -334559 342035
260178 243004 280983 -607223 459896 293287 783374 -555398 722466 967079 518918 968294 -291700 793230 165248 -323990 -883008 809430 618758 -645883
668715 -211755 -973808 -582889 -763217 418217 -248165 -598886 420470 751149 -92234 361711 463101 138362 970038 -707633 -811716 -293140 92332 -187840
-947383 -98919 -592909 -359384 -260560 -524758 -348409 149555 225243 97698 501369 -392266 770806 423227 50635 -547956 472513 -346393 544229 54210
2981 -298157 983047 -171846 -574965 159504 -582564 902510 511927 -87243 -690100 79517 -535842 617143 748520 -798322 913169 -871653 -807165 -758218
629077 725583 -16518 -780810 355275 258244 -476907 64538 -238654 -351258 446734 -489405 -961623 -803218 -55343 309593 -240680 489572 645837 -627950
234702 -35783 818526 582695 -290845 -881657 -241265 225118 685963 -242972 -606783 -579644 129616 666488 67057 -684441 697956 -646961 841882 -449007
523502 838432 -58598 -355335 254491 -940621 913165 -969143 -595974 -191630 506357 435321 277877 187825 -344992 647333 614082 -453496 8962 880931
304679 935987 27869 -640130 225049 -550601 477902 311366 -953844 675180 -632927 -239944 -590247 -655815 -946909 -2168 -46503 -806689 558491 378357
970847 -281742 -721212 -602019 685337 166686 -130810 146159 96484 403365 193458 -857596 280203 -868834 961188 24848 436361 746782 44736 -699855
136034 -312200 -896039 -346122 -946459 -870081 -628583 -343737 286426 746329 141127 -691751 160173 832152 -16694 -705746 407997 -23630 -767555 -540070
935099 -180948 661940 -587173 443099 -583212 -702319 -646184 620189 -933960 -315055 -826111 735748 -106199 782693 455143 -821596 619813 232560 -831240
-47145 -722590 -499453 499011 -361571 -822950 -378714 -686367 -62877 -945305 127662 -205163 -726679 -249337 -236075 190828 696415 -274955 850093 772272
167013 -883245 114663 -685149 -203270 33152 50869 609324 962956 281204 841318 282810 30692 -197035 -219944 42570 -13993 864830 200341 -314242
46498 -552281 -426795 993913 -770260 -548153 -165195 -745187 806626 263593 582345 841790 -212929 679232 574251 61579 584761 -260386 -607252 -325318
-89866 -884074 896988 -704132 -614948 -733057 112960 -873627 379236 932952 -950426 77341 927584 -293865 -364294 -13911 133757 -849243 -459468 435326
-371616 439831 601566 674360 -107421 250276 -545915 782970 -846130 14720 -135954 -911836 15428 -667011 853840 376224 797095 466032 440044 297041
-4481 -835980 5908 -718052 248148 179960 421120 -276113 407873 398922 903603 -103225 -542279 252089 964762 -976073 -627546 788209 230821 358956
392052 98083 -44542 -492925 889784 199020 -327641 323557 -311432 -647565 -850182 368615 -653466 -124339 -110651 -973007 697940 -924414 -42652 -465380
-245738 509347 511134 749873 -100230 -461651 114085 -929533 640672 912387 202315 824197 -495310 968134 614295 681944 688831 901951 205019 931666
-719306 585645 347611 -852738 216682 -22968 208817 284122 929822 -997823 -184 -814123 599251 -514711 341293 -280647 -858763 542805 -811200 172508
-62258 -569356 -976565 787333 -740735 -841503 100850 -99699 676872 43915 -457455 -855542 970956 645637 349475 -673422 -660076 -532604 757534 -577871
-731952 974617 -576243 963912 694777 -448276 -965805 925626 219240 98466 18828 246367 243925 -51714 -389367 -508587 -924877 -987311 -259932 -22685
715411 -474402 549862 249541 -941394 966266 -375016 484210 154565 204077 989009 -26711 581848 420506 -510054 -814458 512455 198320 -389775 827208
-651507 162691 870385 671683 693475 -36407 635507 787043 806192 -920686 -981894 -972816 -579439 -462642 -821733 -479956 899380 -602042 980675 943341
-926186 913448 725165 -590709 134320 208047 624907 -462492 -266273 -941499 -697440 -40454 95629 -54744 -612064 -46938 952530 -960841 504158 598207
690417 877582 -691707 178748 400742 -502587 -286749 369042 52599 -66791 718211 -165946 -738683 -89553 347102 624444 608114 3424 -975893 6360
-591605 -512293 -763253 731436 429 -236673 684172 -31737 -652318 -672664 826440 121371 -945571 359106 726358 -667564 777328 483817 792195 886435
914026 677889 -954033 -37476 -610623 596872 -4755 536620 -373748 -785629 3021 543271 142311 -98173 473498 -470317 -316661 701958 -402852 313115
96106 809186 994412 486547 -33571 846083 113822 975587 -43069 -926252 -919199 -873050 -631251 25076 900814 626743 -102624 -188164 508035 533487
-679332 625245 921126 298129 -235273 81165 -927766 -646618 1726 733586 388946 387921 -829285 -458576 -427629 -252132 -911197 123293 -936563 -926256
134996 729695 450726 -833682 170896 -942821 -99785 -685863 -100580 -277753 933545 938012 -640510 460001 753422 791688 603852 156975 459265 829575
-328492 154668 337966 -764903 -673007 419733 867576 675055 724689 441170 -331591 -296905 361051 999347 -920446 789270 -475342 -227874 424474 -730672
10877 -705113 28873 -525114 761975 -454112 150975 777907 520678 -172825 -3467 -934778 466095 -679935 -554541 -3863 -985566 -439911 160698 251341
-50136 -27794 863142 71656 -314702 428715 757649 694111 612683 -375281 -74709 -693228 -144345 -937451 160047 -49603 -18506 -978152 -148058 -466208
926285 280384 -819868 350692 467132 -998599 85321 372616 194780 -865807 -997998 611066 -313840 -141910 -668587 -112548 -910224 -446474 671356 -402414
-842522 -589091 837920 788650 -303513 868180 -854819 -336641 415072 -873079 834014 427539 -875614 236324 -378490 -637199 889910 361680 521525 635130
-243242 57079 513330 -584975 992679 522399 -517770 621859 -979255 -310452 -185785 -617061 147917 -564800 246732 746095 -762942 -475364 -545826 -482075
37696 653874 -856052 -379909 917328 479623 -109434 755557 -812204 -719180 589204 -24223 143222 7490 584464 -729362 451790 -414700 -632515 -780155
613215 241734 -264150 717820 954087 -801550 34263 615420 39712 406708 -25633 425047 908757 628890 571394 -688723 415003 -895584 -929639 190955
456050 527006 -609497 69418 964578 635334 -963041 240441 126992 53953 447638 694489 -877640 791053 631085 175999 -400853 -341416 -119624 -964250
-794071 690647 -778410 282416 -111498 -620070 417729 482413 886811 238252 828257 -165690 -842910 420293 389959 -679915 102176 -108004 -220292 689845
734529 -173746 287830 18199 471241 518731 251802 334570 168081 849618 -160975 -921812 861313 693265 100316 -995082 -484483 -510374 -547694 135604
-581830 -946730 682541 -844368 85899 145126 -228697 -997209 275508 683378 -492942 -265918 83311 -82885 -368273 -612311 219359 -570410 894093 -600010
-293190 577701 542463 -620897 -51465 822563 -195233 148911 228172 144967 -497472 -183184 642560 -333024 773594 -774782 -10191 -642057 -441705 -433417
-412803 -555317 -769751 557248 299049 -803596 -629150 -34293 -416846 -395448 222228 -539701 780204 309492 -457117 -51192 669263 955498 845372 192917
-727016 -904572 -822440 -151476 -976531 446085 -670106 461395 323751 583555 -180144 51972 948760 -376312 53769 635079 -620005 942770 777649 477526
-478194 -781948 37644 290653 704932 777212 -304765 -956574 -152790 630329 775467 108659 -487227 -613504 -368294 588775 -748274 567299 456098 314760
-23307 -171833 213060 85457 561827 238882 -895392 -589708 -528742 -300173 10721 113644 -501487 796875 974951 -909675 493434 -716926 930954 -638110
-873564 -804092 974294 -209654 -797494 -864353 174029 -447894 -687606 -167564 917302 -655423 -440861 457522 -357382 -885064 -716048 412330 -240478 291054
-377495 -284387 -88953 944825 442803 939703 367872 879079 -43257 -309015 507953 479470 -969718 596339 6115 -20767 -662162 692922 -863807 584874
-244042 -373071 531399 -97702 -543216 -722852 646203 -484636 -881365 117478 453752 70642 704135 259483 208138 -10195 -455146 665080 353888 -226126
-578994 -976342 359229 -501298 -112686 -790307 -325145 -657891 646523 133165 835159 489595 -312599 412828 -629476 -920991 92502 -69733 671578 972941
139233 746435 41010 570373 -790587 337967 108995 107751 -639567 918642 -633654 -82886 131327 239304 -440888 415506 -537737 -828084 -507809 -542873
-918516 -875514 96303 564944 -181927 -155468 -338348 441801 339594 503324 834494 -838706 -474114 574563 -375047 -425072 435974 160842 -23278 352853
13092 508401 -510915 258963 -76442 694499 -316897 -667168 -39598 -715677 -790629 41176 431767 339446 658978 -605460 274738 -62038 -388678 942695
711223 356226 832890 -358518 380813 717465 -761088 977132 -724700 751578 319891 -467992 -242596 61787 766755 820864 -889004 885130 178484 -492373
139201 894638 127556 121323 483954 890987 194499 650379 -207935 187806 -906015 -833112 733191 -236342 522415 481722 270837 -996536 -170179 445738
613252 321270 -703561 665222 -296976 -988866 -369833 -386079 127210 331563 44431 -547571 -170988 497026 -566775 -905446 780707 659408 510703 -985102
-74511 -698069 -475178 -498971 -135164 122969 440496 -54490 -319730 181228 -413043 -178202 -259840 746897 511108 -89613 -941755 -702941 195627 26236
559103 -692733 904502 16038 739292 911121 -391840 -879329 -484036 607348 178121 415413 241931 417462 206285 -136699 -783659 332985 890895 538130
141246 -427375 728436 591313 -982441 947748 755287 401184 -64081 -493087 883888 -308638 -365940 581570 800047 295856 924988 320119 -273559 -549185
160079 900419 775887 -445423 -844766 -557835 -991332 444223 322259 -370970 749513 729029 -598869 543151 225327 -534842 949858 -433187 -169724 177762
346087 351157 902885 336900 22391 937572 -983971 26317 730626 553760 -968128 -437312 -559259 -674331 668672 -176048 74404 611432 259725 662386
-895719 -168289 -290224 278336 -80311 169781 -58619 -134446 -942907 -188918 -38991 188686 -766023 732519 175184 -709430 -106037 406534 497782 -958019
34574 550602 -197773 -735945 -25300 231850 723804 30197 -518582 -323360 -882058 -892834 -496964 684795 -610474 -156704 -349455 412864 473878 -370169
-110773 -413580 -911681 914899 86029 -296417 -652098 -914111 458085 -347592 -272969 -414356 -754797 -961191 510645 -437446 531767 -530790 -924589 -741686
418430 -636944 613922 900009 -548743 -558982 -738894 165612 707726 -251145 672751 -74193 460664 -970616 457322 954544 455174 115872 247988 862354
-208106 -135116 975983 -858487 -216713 -256013 -973785 -570000 -160065 320893 -638003 493512 -187538 -608806 7069 -768822 -510305 964376 -351520 316037
496323 250703 -832840 -34276 -274834 -411566 -615154 23746 -914349 749689 608415 -494374 865871 -515287 -496919 825373 734503 183831 -109976 -233673
-153969 -568161 -866195 -934447 -672936 -350121 2966 374894 -270747 -573678 -380447 911151 -373591 -667508 -80334 -794013 -630098 830600 -183675 -720104
323949 255243 160735 -78665 -868815 777335 -305125 754594 685494 68923 620394 159167 530213 585975 990655 -793016 -198142 -776258 214593 -496559
-184137 441588 -834430 672925 -100153 547345 797335 -3447 215913 -187991 494278 328690 568571 -754908 221984 549987 615813 872680 985005 218851
-609981 -758820 -890794 -602390 -832912 903974 -303021 -606855 -575983 116135 772021 -287067 154029 152880 -842392 863600 279079 792882 42254 613414
-817543 738241 -766857 -708852 -495828 860448 707402 789731 -133837 173041 134732 -35261 612939 429391 -622039 -237808 644567 -154150 -411726 712259
543919 945701 -73233 390446 672015 71066 -538180 345288 -625608 236358 54765 -618769 -566650 182806 806407 -878094 773315 -718524 -702452 -275282
842336 394819 344980 65022 -119825 59896 761408 652862 -290297 378500 776376 236259 990080 933944 177615 275123 762616 -70626 -893867 -717222
508947 957156 -931917 -954393 118451 -127372 855200 728984 286061 793744 594772 -631426 -382536 148846 925913 -684410 -486534 -526490 -89017 428317
708419 -85949 548725 -798725 666301 607473 172053 971964 -625308 430496 227456 -108557 -284227 711627 -482779 882408 -940570 -263668 424858 637306
-287894 791173 -426001 471713 102896 193303 868493 372432 715040 829489 -529056 30318 -725840 699379 -744592 -290296 -201232 -840367 904366 -193147
-32381 411188 579369 -698086 -905890 -405695 -439899 -414800 308656 442850 -633372 -782021 259026 -883161 -210871 378747 -673293 -442084 920634 484960
552395 -940548 -942203 338602 -494559 263608 247055 -694918 -547515 836668 -813421 248734 -838586 905748 -363599 25952 863387 193328 -894169 190235
-118943 645715 691594 507340 817229 318930 -867711 39377 -955760 -268248 892705 676411 -43087 -789578 268383 853080 138900 -613341 297050 622409
581340 -303572 319329 -187846 393389 545096 -540222 -501061 687377 89074 -499555 -538201 -832070 -750858 163352 -576568 796457 -218766 -364769 134876
441768 62814 12464 15552 -727439 228328 629584 -556701 13495 -632991 101032 -35585 -704885 891256 376875 924323 834350 387878 -217430 -104838
420032 -44452 740493 200946 635247 -533250 819408 -737810 -407079 -389692 -162475 -214893 131576 -138188 -619308 -512094 765431 -253461 -59243 -39195
851226 -458996 617260 480576 -467178 -568859 -206336 -81445 905506 -883853 -968000 106283 -977021 183839 867481 170126 286278 -524384 -429522 -405002
-509466 396238 877435 -970280 690082 829144 921812 -86030 907666 974001 129837 13945 76820 642587 923864 492900 85909 -176666 488835 689174
-418070 96170 707354 2217 434918 -888525 -29436 603230 617554 -257079 -219928 -677299 348335 959355 -100560 -40658 839095 -977614 725107 950906
-602079 44667 -755931 -815131 679804 -862188 278518 241530 21384 272669 767129 483575 880521 886079 -649785 -871325 -223390 985567 631757 809820
12241 198063 -185273 -289638 760632 -130195 701832 463235 895749 183129 327639 -538672 372076 981829 -240478 879964 -315664 527942 -509171 686580
743582 195208 158494 -627523 33631 890286 920580 138257 -359954 -950353 789278 -71811 -15701 412705 563633 781406 -57911 -973889 331873 -122469
-26423 -217271 -36935 499689 -196988 37758 289768 573320 -968863 -135738 295393 95378 -116051 -949928 421171 -626398 -696061 685690 382386 297546
15231 -19367 -673130 -104391 -521535 213909 828044 930428 -178051 426312 721876 -463518 -731273 280783 555887 -96183 143325 -3340 -556869 -8809
750206 958962 846198 548251 767582 183790 -706290 141808 -71820 -978356 -728482 776223 -238687 -90501 739181 -365264 245454 -15892 -798767 -197246
957185 -931509 300836 810499 -934512 905993 739856 505245 -140239 73695 339396 -153673 -220033 548331 -432728 -35841 60480 74072 487859 667202
742427 -371211 767152 -321084 123489 766933 696826 98768 -218089 -985041 -179760 -142839 -994883 727626 33944 748979 786163 957697 -957768 754002
871700 99294 12188 722422 -473601 -369024 -260203 47847 72058 651937 -13522 977009 -28813 979991 -31727 796420 -274749 700984 873240 -370773
-352696 -665437 137432 197746 246930 -319821 -218760 545012 -210662 163994 668306 89479 -323951 -204928 -677302 -425555 233255 -148178 -12438 -939283
816794 -340829 -632959 -15640 -225989 -690584 991198 319170 -900572 -878691 349237 -169512 849054 -404550 888285 312379 567220 324489 266089 283825
976998 -352326 458596 -4565 589195 -91740 50822 -191371 458294 -494536 -879090 -187443 21324 130633 549797 862556 -211600 -445965 -456174 95894
-276382 -653056 -384602 -857782 250461 667218 484663 -461621 29892 934217 994293 244751 426880 -61004 984321 -218401 399749 -95459 -937830 285611
192758 -63338 874087 52178 469 -838895 548949 99193 -379948 972734 929890 63060 293874 -692571 244797 581213 541585 -490137 -835900 938162
-782278 -468545 -9676 934762 -367194 -223619 906912 -482790 -437124 -470121 36555 -697684 -563150 -308029 526503 650652 552196 441584 -648786 -660260
-717065 247047 953329 661647 -241474 651679 -846888 835084 -419702 445214 971353 -629410 283083 529459 -15404 278400 237208 595748 -296685 -54962
-819414 291901 53373 -612104 -964281 397354 -592931 -498407 640179 -568043 -513001 482374 11152 497750 -92539 981509 -433808 -524175 -933592 430540
259713 -716693 602615 297121 893464 683035 228315 23960 -792697 741463 328573 715035 400109 -259855 -406841 -190952 -710406 578844 341704 -784777
-361763 994919 -863304 -917346 425396 -622952 -39300 542977 -363516 515529 -649713 -468380 167059 643143 17796 91423 -487751 642909 -826260 -501838
-924006 385619 -781107 -887951 689504 260249 -962362 257234 319804 -590212 937989 -591783 -980268 916167 682790 -742708 221450 928861 -529065 -637402
-893992 -556482 595777 -23976 -805169 931602 543318 -304113 -859003 221945 77151 -194460 981224 403697 -536845 -358022 465174 685962 -935091 -962459
205170 775678 -368145 467561 -622694 626635 738992 303977 375907 -958349 299937 25995 -422617 -666357 200063 -240694 783893 58509 -656361 460274
-418687 938247 705492 -705573 -93542 -38850 71627 925475 -924497 -275175 746074 -760426 812196 386953 -552018 -641384 840253 678331 232410 -585364
436591 -178005 688419 -691511 -710893 -509503 18358 -240105 351847 824106 868536 976143 -794332 -613116 -173250 609987 -761155 481440 462813 558069
731572 875085 -660789 741593 -878705 264213 -202759 -263061 770361 360193 950358 631685 -382183 934476 774078 -974042 -937425 -830450 328489 929468
-700718 80349 147090 -44502 665205 -259059 -471315 245894 601708 16681 -401633 775610 500423 834287 -11756 288810 -911617 -26321 -796260 555969
-228747 68742 -160378 -343912 11370 -117037 -252530 -18751 -874279 -697542 -190059 -672184 407488 877161 -50639 -58221 710154 780714 -579755 -156356
-920964 216588 826049 811345 -878658 -599388 -410549 -543682 522556 -575264 -370570 -645547 -586288 796957 942599 -956995 -576333 580249 -126256 427869
149364 309436 -951976 -655269 -830170 154189 688475 20140 541997 -178441 -134059 -642932 934090 799822 -258098 68277 -159164 -85979 -485894 -733962
280501 982466 468717 -50596 -884252 461673 -726611 195508 -923182 902734 -422963 -611939 673508 -750705 135460 -324764 49550 -780103 850737 450599
996960 -202928 691097 -13882 -662593 424754 -8835 -751577 755869 464805 -61160 112033 -869398 -83194 -188195 -704341 -532278 776681 498192 -691047
428945 31284 -689482 462471 -408650 -728627 526975 401815 -967574 858152 -79615 -521054 195301 -62761 183964 796521 758955 -346771 -290617 -790626
568766 -30775 -438140 -766758 -254451 -965252 -972608 739823 -462650 502974 677948 -171390 746292 -199056 -28740 -617168 -245376 513113 -329408 -297581
-163286 -874722 -727293 468536 -318046 31216 848933 -823101 -406150 -830684 -553844 -632057 296251 -906160 525052 64018 -897544 572943 975447 -655064
-986180 -906094 86721 -100530 980795 248534 827359 -20597 309961 234283 -371264 -135778 103308 694832 376582 461316 -716303 -522442 111138 253157
964769 497994 -103589 -680814 858029 -999237 -453490 -480301 517471 -271587 878690 436601 -543300 -221820 310815 -248535 -900189 710969 639304 852601
-515709 -343852 842080 996148 999791 -211512 582900 303735 65604 903694 634827 630056 -125613 861889 -326351 -430855 581253 298461 -815841 -358853
-223201 471496 440154 398386 -731229 -366232 524169 -529944 -810879 694815 -550305 630576 -324349 -265018 418646 535745 976267 610702 411434 -414182
-534296 603279 -913789 382948 -211602 -779385 94776 -213434 -490723 -744764 742428 631526 -177042 -787291 -630293 -995366 -81269 -250767 455392 -685174
414276 -708293 916819 906022 -313049 -948985 918985 -866207 847642 -307332 741338 86410 -91664 149488 848680 129809 649733 -892607 710398 -878898
664035 848604 -921827 -421597 -775472 712997 -5776 -537972 339112 454339 -632312 -820038 962871 -482967 -452010 -645612 -756231 -750809 -491664 -812696
427801 817898 -552630 686944 483162 870031 192967 -994956 11274 -711469 45975 -854196 575805 390705 966024 452242 636362 -775771 -956031 -747865
531221 -354394 367650 756649 886700 -962499 -903632 -740324 -992345 -894502 534817 508220 757498 -335129 149667 -910204 -328741 -374620 -938544 -160940
785663 -734497 348726 46935 -825447 113050 -461683 770354 513220 37890 -950314 -552107 816871 -654712 720015 466200 685445 84899 -81601 97015
-801957 336785 -533051 -777682 -638218 186851 -594363 -146484 774395 14277 904760 -978544 182996 -333013 -279811 520872 772960 -125218 -759724 524084
747347 -165002 -444196 648771 -86182 715891 -108258 817706 574178 -844039 -167090 -949168 439539 -478653 487966 798887 61711 -892520 285710 749648
-24364 653049 -337211 -275464 249648 -954713 848322 -58821 43558 503139 -37699 517703 -141303 987956 -875700 138580 -193496 -613220 -8350 -181011
-819583 518928 -219130 390870 -233782 145007 -646986 289413 -36211 -455951 -619474 961158 -514866 -620752 541847 762455 -827646 554705 718087 713550
468613 -292418 262897 684698 -867492 501298 167761 -353837 845440 -598784 -40308 149381 -223061 895566 230418 741935 -684763 -590476 100579 407505
-40387 -642261 521550 27281 -283792 -688972 720698 101653 -748806 164271 -195794 -736291 58556 -204072 -474061 199319 595108 -865698 -67619 633334
530527 191233 -262422 -621500 168514 -558766 -366731 356375 -761637 -681898 821151 -349773 501566 -823675 -750287 59841 -724582 441419 -781786 -559837
-107443 -53158 331322 -60246 -990862 -889021 909007 360467 -546475 84779 -94335 -906228 -785909 -265483 -841919 948914 -47726 -256482 -89876 -433885
-927342 843393 986698 945181 -943075 566319 -21399 -296820 -107312 -887285 -866562 326816 -798246 -277198 473868 -434496 -335704 -661208 318216 -998812
980229 283408 728307 -686561 836001 169938 -446474 -590733 -183689 293114 -332343 -951232 81671 -972898 -29342 451878 -786193 474111 -849424 995848
-384916 -307112 -195944 454140 913666 -63876 688592 -984723 -127921 -136584 -537577 184705 358848 -383169 -646014 -483977 875110 -700790 -132551 -321650
-534534 -382170 -246935 543262 -456372 488630 53481 82990 -938563 474978 -813265 -427541 -325151 -646862 406859 -887702 806126 -680913 565949 -507323
-402461 309511 648282 -480191 -684143 -399785 426536 719769 -134633 655756 632498 548174 -150685 -762098 13675 626280 -877428 232916 -39684 639888
-471430 -922535 18485 -140157 -324530 417004 679569 923967 -175988 -918013 -475349 -405952 232023 -389442 533195 14415 -577001 -717056 402109 837942
-275521 548347 -225294 -535455 -680201 -528892 -184307 859216 -473737 -485635 -979292 -249874 26511 795420 -332127 -591118 -798887 995772 -187717 790054
-858050 -305661 -287255 -7797 164016 88473 781295 -234638 111656 -675083 -312950 -137622 112746 -565670 531742 802294 845544 969877 -445774 -561932
442452 8945 328945 -858199 -416207 597209 684919 726735 -712460 60303 -521298 -661172 492824 751344 -35329 177702 -585503 -20619 -286626 402817
74509 25078 2053 343289 963361 -637962 200089 101629 917073 -713101 -632814 289162 -27483 -969376 -125519 380815 -412821 133422 966883 -940431
-970619 -998405 -354066 -409689 -302528 257382 618464 996828 -628378 -70116 -939076 -930537 -13418 885673 -181630 -167709 341176 83699 650527 606583
562070 558412 -852393 991838 101447 172130 336182 516504 -581856 239274 -123934 -730226 -916301 847493 -705873 763543 -924923 828606 -703486 355357
557135 506343 486546 -302542 -770541 -615718 806945 -900032 -752905 218765 255590 339755 235178 -257367 -92618 975160 679109 -243256 -66136 -809747
919107 -256279 977726 699290 731066 826172 -63625 -839553 925885 -286263 -753525 202745 -934552 -477168 -325787 5593 -625965 460757 -342533 137088
-162715 30932 -722414 -663299 416652 7076 32529 952974 338439 696086 759713 96396 -38882 279156 -942789 -630133 -170409 -229143 -647053 510621
-869905 194614 784228 -123580 88459 274616 435204 390471 410414 -309494 -818047 660282 413687 -930910 346407 451754 -614132 -541100 -328980 -425748
672434 -779819 588349 455841 745893 781788 589787 -117943 501926 106119 716968 -620772 -374074 -322127 61066 879542 131176 764528 -665160 -740459
-313607 524958 122454 766912 -592914 591574 -861173 152326 716491 -904671 245507 872784 746760 -385817 820932 -575226 -468569 546119 -145559 672594
-192070 999083 197412 -985107 653293 219029 -889552 199198 -383807 723949 -469289 -597573 865582 95489 521596 184272 -134132 -979084 121483 406798
453912 137111 609020 403442 -777673 278710 647813 144547 -949754 -906087 625538 -65731 538284 -7873 -380322 -788444 620362 -938306 232827 814709
270497 -601793 -388423 -277642 -576311 532494 830170 82138 -386315 -834823 -198589 -1219 645769 689484 -733436 -715019 -65645 -461126 -883931 983094
74847 651669 436889 -178479 70767 251167 429385 253609 525457 -460718 -713138 -995841 -663140 -796745 -733798 404282 155174 496952 735907 -563573
-97527 32927 234747 -712334 302966 705656 473525 -516790 940449 804897 -171861 733177 605423 10582 705209 -543392 772244 356655 -898661 843723
-497098 328770 50233 -571224 -809050 -271115 710640 -484677 -42660 696574 838391 194858 -229397 618062 809247 872088 -619548 -787423 -541585 605097
-386827 -207382 -172114 147884 -200407 878537 -346887 -982812 808019 366538 421673 403333 -115032 609780 -361552 889507 939112 -561987 494507 -406099
669329 -999696 -626708 -730894 824516 430724 -824808 63575 -845610 355635 -317093 188491 -595021 252013 345025 641972 -236057 443289 -502949 -137100
-431764 771932 962432 536941 -640223 -340212 -42215 900434 -840246 15160 167237 -668882 371111 575549 -200112 938859 110396 -430719 -453468 355069
383399 -864329 330813 -790270 -646797 67686 167356 -592326 828559 -745809 683211 -180322 -736702 -650096 -883811 129340 665587 843682 -654441 -325561
428120 496085 427197 -264865 -631743 438152 -440955 -280 372424 -196119 -691049 315614 20151 -447201 72205 -575094 506379 -207239 333454 926500
-972561 165944 -991129 -230515 -897631 -384085 433692 803144 428232 931563 -920549 114375 333292 299624 -433045 434420 -730481 923514 805794 944726
-673738 -435500 -910346 869599 -881928 835554 14728 -657127 -219256 -926355 137696 -45898 15570 449508 -519687 -114829 -668987 -634093 104636 592511
-654448 -229884 773434 214836 -307389 89465 647323 727995 912482 851286 375094 -567756 -978867 437614 -44154 -926060 595366 522966 -135565 -783294
-164751 484515 -474355 -800783 -374855 -825421 36092 178252 702303 440046 -183879 -885085 13426 -55288 684390 -483895 -431654 -532238 -421590 -747311
764077 820677 930539 723997 -986763 -55388 -390452 50591 3484 224407 303537 439443 54187 -940231 -207037 -119083 703347 105145 -452500 724291
716723 -489697 401314 -489684 -945310 -165554 410613 190109 711442 -604647 -777795 -644717 -669664 -300388 -607359 501751 -248434 948233 18124 -339547
-145662 -490236 -361959 -72134 -20855 -128008 383258 224325 -182745 -2403 156939 -626643 643899 827671 -740248 -572619 -120932 -374125 -720149 -829553
652804 429685 10609 570579 175993 50659 -228652 650746 -661511 584810 718199 715873 -695064 -714136 885428 -415257 291411 580666 -914155 -471567
901873 617121 -495629 -138684 -254842 -951423 -412146 -549652 -772708 -851452 -27326 -711916 424368 940722 5807 45311 -43148 -220810 91831 -587994
-972317 -156705 358415 -75841 -140916 -656821 -677271 -222388 -448274 284125 8472 963162 -133925 -859853 -328613 -248316 783116 981139 296909 -38171
-221636 853921 145096 752016 -502988 796417 -960034 77567 -539630 -241893 -199039 744948 795121 182342 161799 552772 808260 -375062 957038 -457427
371429 591209 732375 -690126 696471 649838 411047 -585074 646892 894400 168349 380012 788510 183241 -531828 979875 -431913 371577 171249 520385
-501963 -913122 -779785 -485721 -236286 -369691 565406 -522606 85570 -433930 860496 600610 833247 203725 -709826 -56369 -893574 497770 -491103 -95824
-927674 -417313 254807 -710398 -616721 -523919 805714 566857 883647 657814 -549439 -921486 528924 -997857 -691342 -611971 583809 416785 567382 264489
532052 977566 428534 378110 90610 -694534 380754 -676610 -377117 91583 -795578 451194 -723639 523729 236794 -547968 576273 -950913 -364245 340920
-263852 -662433 75162 -992325 461830 464792 151631 525688 46554 -73319 -811305 -550950 52813 -418880 95389 -618934 -851744 195258 -783756 885137
240468 883906 800851 949828 655302 469717 -38318 234121 -248123 -889207 500262 -545822 -228304 708707 -826650 -980353 924098 699156 247590 -332746
-109055 46704 297615 -552391 -166102 611053 -902610 -989405 126481 -396982 -238363 -993373 -739435 688671 -117580 12317 357597 330309 536714 44546
-7259 223037 880070 -582531 -936128 97915 219760 -351032 -305364 422519 -407619 -292888 318240 -183594 944078 949449 -45272 -787947 690547 -172898
273322 340284 715019 -152893 -631719 346244 -189109 -57746 -544866 418763 264617 271582 -282421 -165898 44010 -354950 345678 -743219 -602639 411890
371147 847638 988166 465319 679454 -527438 625322 919062 -786312 448994 -666634 878542 -426318 -258191 -393879 149824 308147 -932074 -262911 -885765
310264 989470 -848801 786069 -735599 -169523 767119 799954 -660925 -80833 -701329 -660499 355382 -847508 -150320 -947543 -766478 901046 -853437 398199
-915146 -341144 139733 -835034 -594485 935450 -649139 -955557 356393 118285 -158520 178192 -684851 -13631 296454 601102 793364 643805 -350463 -502058
-352956 -214042 875512 -202144 265529 -487603 141800 26693 -658552 -147838 292865 -450408 31554 -176743 836197 -943067 20965 -697744 -442517 -780045
169081 557185 -490574 163014 518962 746696 969163 -936153 829192 812912 -300339 49840 -790327 312996 834349 411603 410530 665231 -469642 971761
-981269 607247 -855490 294552 157214 -370466 -274846 -493013 -845417 -139461 594921 -743713 345270 705333 816981 709338 339245 635966 490034 718848
812238 792687 -702322 -361096 261564 -572336 414817 933986 -895850 481659 359096 -740836 987651 122070 -914884 945233 -125998 -172544 372663 -781541
998488 -718738 393636 -164825 -761570 632599 -165522 723349 -39563 -620064 388758 645196 -98507 -479877 -318366 339634 -595732 -103146 -693893 425588
-937076 674130 -276155 629998 168679 852365 -89274 -706070 -27200 897227 -941539 -51133 31632 496405 739968 309350 322746 -935587 877772 -291160
-839764 802378 -372283 -832657 -803328 -248987 -86190 197634 28669 796740 375166 -150034 -108755 -168262 -461271 963292 -817466 -468783 644897 -316418
-454947 748172 380433 -621410 248435 962196 -824649 689533 227551 316319 -33897 922132 362804 954589 -706394 866044 -586944 165043 -297712 787953
-758172 -851741 -68310 924091 447058 307910 560804 -147891 -220176 -39302 133248 327080 821635 -7578 -891719 581799 507832 136703 506668 313496
-417401 -372955 -183443 -265065 195679 155374 89595 593409 67742 -135185 432331 -816372 897960 -797661 254606 50659 195101 -358829 689733 830754
-364738 490270 -658883 -921145 -995331 -205207 -552421 300954 -375842 -400300 -675841 155391 -895954 -293374 -460383 904108 -875516 90194 -356561 -954370
-62893 -734479 -284761 810247 -472619 410776 -682143 -340198 922897 -891092 361792 -587430 -700679 514673 613140 801348 -822589 865881 951401 -426284
-542537 493068 855976 -273637 -98005 737079 140795 -979271 814355 -676806 483112 -466970 936390 894264 -370004 430231 693580 -359311 -133488 -715282
-151490 92140 518414 154691 417832 52686 726984 -338866 394482 637266 956836 279226 -743983 342712 -583404 876774 42660 -86610 453826 217940
-177337 -780939 723443 733323 -354543 -100040 -647569 -234635 -823457 -945119 -561801 497923 -84324 429568 -605166 599248 370712 -990444 -418449 -288848
104403 -842353 659715 363722 154573 286840 528541 586844 -1533 580189 -36600 255999 -863431 -266699 -702570 -544593 910961 -288076 -224518 619139
609086 412912 705480 253958 -483069 323204 -533862 820808 872249 -572746 -489239 409954 289894 931627 -872021 -439780 734404 372961 457355 -619150
76739 -636233 -295338 -495894 -509822 -224911 89794 166070 -889147 252446 -366362 -712896 644649 836860 -799930 -975862 237108 90668 -500013 153844
939223 522439 648056 729550 116663 773747 924758 -389815 326716 -741835 296826 152883 509551 796791 773953 782606 -778095 234215 457365 -518798
-195693 630970 788497 155111 89910 -227561 425213 -751814 -414950 -14078 516134 -562408 -591477 -26211 936358 856821 -536444 334735 -263608 -294996
-60371 -738075 -869129 -699298 -706615 159977 -319693 -146312 -811082 -877627 708889 716828 519297 -249721 125271 -494495 889211 344311 -781925 -790312
665011 221008 183263 -334751 602301 -81156 621233 582242 -915376 -303339 796181 577491 -612164 -369556 667240 -795204 -802755 896194 -748890 -748881
892216 -270153 -742606 -6203 -352483 165574 819941 580093 -468983 -438529 114269 189115 218893 -933310 806155 976962 -812455 -279862 -182104 -375188
238442 -817068 -963401 219466 780535 796276 160093 -77677 94481 -115755 479481 -257835 343027 -188092 283848 -835535 284729 -67922 -46895 754007
284100 471827 714752 951885 -760706 580645 -360940 -336865 -924232 595577 -522911 395945 636069 966391 814759 -974753 163086 -417062 176799 185719
717706 529232 -173289 182304 665770 -873241 -755023 -565489 619292 58799 645334 10063 -495694 382476 -774456 95162 -542553 -36379 -363300 -256831
914550 -497137 -530434 762362 -943217 -563571 -353194 -738678 -343971 -48688 478750 9902 -608954 -809096 -171002 514062 396796 -883983 416160 961395
-951537 607014 429587 -401164 519032 735828 783778 -196845 785480 349580 -461529 -688176 888898 339731 325011 -366844 -918630 -995990 -436718 935674
-581084 960886 322252 338410 -218462 491168 -943703 817407 -238849 -710651 -692615 -34346 390196 -498345 613945 639097 -172529 644364 394891 597304
-60676 -44521 -781028 -303053 479372 692561 526741 743177 707764 768043 238054 -264971 -731942 178317 178858 -30971 -390474 -991929 -14266 433215
-447522 -178995 829082 -411130 861498 -879077 20391 -430799 929293 70579 995131 869672 -780750 -306882 893036 807614 157279 373143 -179398 -684132
837876 -670529 312009 -376897 316837 373970 529418 -922745 -663495 614906 988820 -365806 -552077 -661894 -436921 895859 473475 92349 739034 -809998
843102 40958 346981 -956706 877500 -175992 -749497 131484 592883 -916946 -194297 -709779 319983 -241185 876649 -21576 -615020 -897952 -319094 915801
555271 -8451 -225225 -508115 676949 -866381 -708627 693193 612087 -152759 188560 -481635 -887595 294135 -53934 426113 -258872 405058 864544 -630864
-260601 -493961 962244 -33081 982427 -414361 941826 772000 827933 -668145 -18152 354369 379313 86885 351044 -466863 -375470 628906 492552 769413
76539 -625969 -229933 -462873 308340 -819404 212593 -548539 -824101 -922715 -399014 108769 -720946 -920953 392223 -388527 42299 -735343 110901 851642
312296 -337027 -44131 624297 -462212 -317986 -909175 670939 893027 -993169 -415820 -343945 -328121 -923142 879367 234518 324903 -804917 114229 -84838
634049 290019 -490966 433848 -516789 111789 -326497 767911 590115 984980 757258 -739494 -6443 178308 -85414 -301899 762966 -123279 -972463 -670822
-473002 -573993 163729 360909 771139 382555 378716 528578 811783 -622019 -22759 984065 4476 962475 -240067 -424549 271043 -651353 -921157 194945
-978107 506986 825394 -615341 894333 -167697 580030 -389095 -526382 365480 -660361 225714 994367 104788 22211 160298 -473807 343989 -917331 305322
159990 353059 -7141 515045 -396307 768726 -59455 -128993 622590 783042 24338 211851 -365143 -747089 68547 -888273 -107358 795945 927596 822682
212222 -158339 -813563 509922 -388696 342400 932588 818178 -460633 471970 748067 35815 -143053 -352890 993464 -285952 -641837 -464738 815909 178197
132916 315936 194704 -769230 947611 794791 887235 753626 -748721 -257495 451490 468965 468916 246450 894858 -457468 493333 857016 742243 485186
473919 -233208 -551860 -447256 -631953 69015 -198382 494069 175684 -394491 -602768 864133 303475 -959932 354702 110907 -6944 -135849 765171 -943376
376695 713406 855705 -581708 -32577 -497197 -492738 62731 777775 468244 511616 766520 -679768 363795 -257109 -882257 -331579 -457169 -595082 103742
-530357 192020 -839686 -499498 642282 -180345 -396700 793501 -109216 886285 967583 263728 -754948 -699125 701814 -385791 3155 443206 408386 363260
186524 -339216 -730084 304360 525549 -840998 59383 -844847 838580 -829322 -10217 -262288 595806 16728 293356 -300092 -558389 133632 -623282 -613294
168936 -845719 -735890 -847697 422158 -398470 22577 -175673 -324606 953006 -818672 -568168 932179 842451 -859716 263189 -247043 -539239 347463 831594
-384070 724520 718669 -797424 -988869 -940397 -85594 20983 -568559 -418155 424425 -456630 -424269 -765120 -131514 370480 -310563 -653602 277426 -543300
597928 -893960 804053 844610 994284 18883 -367607 -944179 -821967 926635 -416675 -143043 -196506 -473405 688554 78381 -626315 740881 -195880 29992
983844 -478869 652006 -94530 866250 -253397 -643110 340413 -146597 -897329 -595924 557109 197460 255796 143473 -516731 -593120 985746 353797 573412
896559 -78601 928624 -202350 -957405 356512 -531819 -476375 -569504 514470 204490 489473 -867503 -414191 -904334 806743 -665121 910999 -57126 808998
-728768 -872245 -108247 -687637 -212724 378226 693856 -736664 -771914 512587 -849074 603548 -831915 -535747 88576 248624 -700004 -4148 250711 71686
-380759 703751 696920 -353615 207616 302979 686798 576721 -284410 675750 496870 109718 923003 -19854 399309 485895 446051 -82626 -781883 -183887
-309009 -723198 877424 -411114 -609769 22329 423806 9287 -499570 939014 764963 4453 -30410 172162 792854 -766547 815558 357074 849402 916185
-24312 567626 -249236 -385778 397586 -486982 -187058 635206 76460 133294 -717645 -20125 -816401 735970 -351028 670145 -939236 128236 -143010 15880
-295185 999793 -100235 238486 299260 -977463 88386 669503 -11042 183114 -315616 -138853 -50056 518174 397164 -524194 672577 691780 846363 -324967
93342 -755957 -408433 -696973 -135372 843117 -848854 -580781 -970061 -383975 370640 953101 202939 -611270 -482599 -970302 -651896 -426829 985681 -682533
-422653 205267 934401 -169406 -983964 -500888 -293251 -760452 -778027 374989 132284 -882297 -230000 -803885 -485358 -519617 -487710 21948 265547 807676
397990 -70084 -351359 -876374 -524206 -164908 -253259 -838258 714314 271317 -799671 995916 -827703 -700648 754678 86267 -994515 -500184 396643 15523
647699 -148192 453809 941890 190982 -54390 368521 301696 -542794 508915 -975175 457084 828609 881833 891479 75143 581313 -274878 71297 658359
-648087 42072 -998692 -578424 -39116 213406 -387615 376289 -721977 -676417 762152 -621397 -57698 -245287 8754 -291024 946255 -750081 -981120 343987
-402834 -981495 594573 -221041 -251623 -331472 886321 -115525 -446356 807830 -958278 -537042 -681932 80325 -262839 -848064 910951 208815 -474893 22992
-122258 -640406 884431 -857091 -778573 -976513 766459 319821 585450 -140600 -677664 -857088 971952 -356727 312322 495702 59882 -488196 -42166 -196198
-722604 778634 -182767 436179 -81108 -7733 918730 -976062 71670 882297 -940931 243622 256769 877730 513971 -181023 -277974 677127 -532262 -562393
-627332 -302639 152013 271719 -244075 709657 209904 941313 -46829 -330172 -692301 964562 702928 165269 -729696 -981404 364156 537039 -615764 846050
-902900 -465138 -791194 520217 248366 -800710 -383690 -294998 761884 534014 969992 374587 -886645 -565246 565484 -606681 -987292 -124812 -939667 -482455
-915540 -683831 308197 -505867 -93601 730431 -862867 -889915 -891998 569235 -755474 -958910 -262189 -237663 357003 348378 181740 958538 33622 574928
-491811 -39903 799942 438585 -740224 -486050 410616 781744 297604 -548067 708816 260221 -86756 -561653 -863074 249030 -225741 -364815 609538 -474186
-921448 293718 119907 -272117 -628911 286701 -876182 -902490 -636903 984856 590105 73360 493228 754633 150190 534895 -14264 -760342 -63708 51115
-172946 329123 -779879 439075 343040 -896891 638416 363285 -775323 -957676 863615 -884915 -457253 173621 -42091 -863139 -855014 105318 409401 -926730
-619058 -23085 -190962 718811 904104 -767496 566023 -902364 550980 497978 371862 847994 980112 483766 -677088 -159388 969593 100875 -553305 405905
-219881 -184498 -352449 -969045 -193298 491948 -95970 848201 203836 781233 461965 -565788 913379 988129 -765789 863593 -511006 769878 234950 -549070
-193232 412735 -453522 -305102 776158 719520 -843783 735403 548389 303657 426895 267869 596515 -5347 -517581 -300434 -635676 344223 -530596 -324955
-953501 906915 961658 -720766 6462 -940666 -236850 8643 516605 34087 77978 -511023 -564105 931144 -93233 110818 -298055 895547 855113 -198279
-622423 -569703 200709 -38127 -381745 -504386 650874 -207484 -296090 -963946 -306527 -298497 -949131 729595 -519193 51579 -935842 -321383 -546540 -881300
395070 362021 -852418 893718 549551 417355 172705 -678552 -197950 468934 915290 -770217 355499 -785999 403322 83648 17923 946465 -26861 -926613
908379 90119 99720 -335143 -4381 947082 668931 350422 -422278 634863 486240 -565933 214584 854939 -46151 15694 521203 -384695 -975141 -957602
-685340 -498038 192814 -341614 -972891 85893 -519904 -160410 -827907 391025 -933418 -960848 -331449 190520 -605690 525982 764834 -814138 -811255 -64409
993145 229290 -398370 -668239 -956420 -839558 599391 437107 874556 364435 914695 929442 -392459 363879 643154 -766018 719844 448080 76594 125139
-905406 314243 -68916 459667 -702726 266945 -818451 818667 453246 -158531 -21947 -626854 743653 452389 -587782 605752 -429010 799962 455024 193122
-877266 -446518 550230 607360 140262 37935 688240 -134178 -401733 395524 142386 -2375 757407 484974 -192402 542737 -139412 -922128 264708 -600876
336361 280643 623126 -16689 386874 665643 -159964 755879 173794 -670483 -102109 448895 -784174 951082 -734644 -115057 -340873 293995 -290794 722721
-995134 -906921 317084 122050 -863423 815681 315437 257916 -433937 916878 -579447 828545 40033 162695 669333 502338 142662 -239085 726332 -498979
125655 580941 894189 25752 565037 -215230 -959233 437780 900934 -181518 377674 85385 481940 72254 -501768 -334495 693697 993834 192624 -479776
393137 -217259 -802412 823870 841220 278504 -324604 -215630 -547696 -475141 -676117 174126 -9551 -567584 -477096 -811703 609177 211221 631766 -472333
-84468 -373649 767580 -189171 -369663 15359 154848 -55456 298696 432823 385469 963754 119184 -389459 997191 -760673 719242 53310 180326 -279067
833925 -506697 -573367 -617065 -128652 -531337 944475 621837 -302020 773086 -589759 715047 745363 -116419 -791009 3958 524603 953106 -136815 -494802
456703 962022 -769337 453481 -790318 -634552 -663582 -679444 -644912 -981125 839632 -158125 50141 557697 702124 320356 1701 721621 19450 129719
-45792 448042 -909512 -828864 -52432 502350 -725485 635096 -510516 343034 340485 -423275 491223 -722747 710106 -43499 676567 -905198 676468 -106368
-935517 612714 208113 121831 -890554 -659041 -987463 -1444 297523 -762875 321057 -4985 815055 797381 -223970 749674 689126 554235 -661201 671311
-65597 -79475 -41556 657338 -498601 -135723 751450 -904185 34164 759773 -30778 996043 1633 515944 67465 -556835 -569854 -398512 -42288 681306
778689 352805 -835479 -881800 -809315 -80808 -350064 127798 -615069 -283768 -15466 926817 789317 -830604 281142 -335759 797838 37673 425567 -880184
-388052 147175 -858088 -466156 632941 -203521 -913929 -802171 -620719 828641 137029 -909657 -602332 -35769 -847755 81436 -309379 -746394 586811 -445939
-819500 -183177 600110 -214465 496764 -138527 990096 -855838 435702 -665542 -347682 745774 700156 -831416 -141300 74597 14788 535811 -388361 651522
-230495 77936 -999612 574225 461204 -454304 -700575 -352232 -309570 899788 975340 640818 581518 -14498 -229439 -729190 601932 947664 -547523 613810
277482 -202453 440013 -790644 90724 676291 -709429 -935461 -995751 -4105 -156472 -63352 -348916 -833186 -58150 -52833 555567 -917648 -465812 -681001
-323414 -599139 -582979 728948 271358 727678 711517 259288 552534 62791 335910 75706 -222630 -71446 575448 -264674 -462022 -428792 -417240 -152571
881574 820111 743940 -804367 684241 -929605 964553 672732 384684 -305102 -398158 -553773 -662076 908624 981743 -710834 -856375 -322485 -342882 849776
-228225 -516957 718928 -662724 383942 -898277 -184527 763237 -122778 306752 555041 855762 -429820 -581978 -471211 841064 250162 571915 -391870 776282
-996437 -111001 -76269 -553567 -891960 619641 690514 -292487 196331 -473825 -666070 -317202 225456 449166 842652 5983 847097 -24645 438974 -272091
580413 -817609 -799501 -515651 214979 943493 -42749 322273 829529 420381 63261 297815 537654 927965 -456570 774541 -892810 934310 -626199 -248273
-628013 487271 803640 -916338 606828 -261846 -269883 959123 912859 340022 835453 -480956 242920 314260 323290 -408302 647977 551311 -654784 812435
-925352 771373 -93744 -323670 633498 788691 -493445 261355 486192 446880 363606 753823 -933389 -576633 -376502 65828 311453 -678323 435721 -567818
964494 -684717 463309 -994545 265562 -237013 -95562 -482595 -791516 -840724 365967 -852025 -972635 23621 -896846 825075 283264 72204 931605 950350
-328993 123668 -391121 976638 245698 676109 -65164 301355 899031 786837 320940 -363690 -14845 441490 -107784 -799551 -443826 -141844 999672 -929373
-509444 -565757 536361 52485 605436 -971411 156959 -231416 -59329 -549069 -786519 492481 -283969 -210434 -419373 956529 -247101 -707035 306516 21073
924418 -960798 528248 499090 527563 946305 -876596 229024 -231612 -740337 -55050 955028 -777823 680684 -566560 -940466 -159953 202817 -979888 -809120
-671831 -718546 -340524 131074 -466065 766306 -63490 -230198 -410870 964208 -43001 -610217 -302504 187301 -400922 -188426 -296666 806872 -773221 367604
-909869 993713 167875 886760 777290 739298 -452555 -630288 -904458 -214947 417143 467485 -492609 -129461 293388 499453 -373905 -918047 438744 -172285
807091 -596644 -623163 654289 -148159 -183693 105791 -59552 329037 203477 184854 -129603 961334 -518993 -34616 512004 -668787 60669 -903512 -432904
-548164 -993865 -655155 -291906 -531392 525465 -541431 -146510 957974 -724521 747254 961685 -564648 -205896 589474 398778 -593865 -718075 -128074 -189482
-932374 319844 -628113 -945408 408797 192621 -6808 -251051 -822761 952682 922706 816298 -42892 528176 -64472 15233 -617914 -398778 -796870 -100963
60632 932368 -560486 277007 -285170 408906 629761 784703 741916 510913 868103 128998 177551 19174 532853 -634743 -952241 -124593 -709725 211178
-33206 -295160 -347601 -319742 377527 -603675 45946 651719 -468176 619209 -344527 -453694 -948853 780696 -531043 678728 920036 649935 -178617 616169
456448 558593 -865532 -478576 150673 -76600 -392081 -594152 -72642 472999 -313272 -242251 53596 -603215 698669 -255800 -915955 -656661 119543 -157811
-936776 331258 -93844 -514571 696577 507849 794508 602446 -634320 104385 -893504 358166 -796068 -420964 -499484 675852 916355 166882 -122466 -502115
205451 -312980 -837643 427008 -642610 -746519 -390725 -702723 281516 -963019 -108026 -509911 708882 -712631 832171 443893 -286638 -506562 -525963 694773
558617 -809597 954752 926280 718680 308403 21954 -184411 84459 895243 761487 858868 152505 -592470 -673629 -875599 479202 308593 307565 249031
192260 720132 -944208 810693 -295704 885970 534 -862115 -422284 931519 804223 70186 -287203 555117 444156 -948256 998538 -532407 -622729 -722531
604800 -35754 478521 -342965 -330979 684805 -234325 337592 994917 405229 -700157 365923 -452466 -374999 185023 769847 408252 202448 -866776 -34327
-7775 602371 142454 -643714 -436257 597838 -614318 835702 -47840 -758135 42129 -883640 145964 97141 -939697 397374 878662 616259 64442 898524
-890984 519014 -91934 -117037 457431 674084 -812891 -415126 -992141 -598777 -305355 776358 -234963 -815085 -795095 -208898 757125 295893 736222 840155
-90315 361187 -373515 265064 408870 488133 -685170 952165 -133733 -446008 286590 160455 -227212 -844326 747884 -90521 -797344 -71633 188398 -488974
905128 -548663 -364749 -651286 720049 523420 -828054 -190519 616306 460727 -388830 -996965 -336962 -869429 528412 52103 938457 -665451 576039 -246118
-238864 836471 10849 -580512 32679 352375 -707166 959422 299670 -852213 -905774 -921227 517929 172750 -724911 -226731 603752 510000 663855 -206058
161001 -871170 67500 -585041 -819068 915055 122252 138681 -179083 541460 -970351 149377 155455 197898 -276244 223815 -22994 613457 -484835 -684283
180474 -877973 155390 891698 -158999 -227630 -557900 747241 52856 645678 14527 201137 936443 814461 -440757 231356 -877353 -479531 632170 454922
-834795 829483 197212 626258 748163 -740862 92383 -598256 63297 -882604 649332 154297 -407147 95063 400627 49426 -918344 396984 -971262 -610832
-184694 -966208 -738562 585466 222409 -722730 558370 977077 -681007 -410431 -367143 -591231 277929 -269224 770150 -319388 60310 232019 309551 -125368
887082 467447 -262422 302795 -850371 453903 -591115 386479 -868823 -357857 -21799 -874459 680444 360435 709008 737908 695870 297386 468967 -430166
-368845 50036 -75925 -195885 675820 525308 928048 -860160 -580281 -669878 580228 -667876 245980 999080 694703 335484 32440 -760471 -457662 -488058
133912 -638664 287518 516998 611236 135544 47411 -21062 -467025 196999 -683577 822765 -580349 921144 -503118 -950209 -980972 -531833 -863391 -610256
330692 -405450 192558 863792 -410147 -486553 989232 -243295 82772 558858 -934893 -360009 -852814 381713 -936628 51076 108003 661811 -524986 121402
-61545 996525 -7179 939556 991932 -980911 -20473 569267 -402169 523678 -192054 35151 826936 496553 362978 106069 -629303 -832072 -109645 722660
680768 -708395 353144 582083 580887 255104 283608 796486 -126436 386414 -670750 811776 -224856 336109 450237 -35080 858516 -896982 117418 842682
500074 219487 510302 -815124 832076 997925 -298438 613588 559258 -62760 509526 -514079 -45787 991530 -869826 891919 35495 937369 -570520 427525
-36322 642087 79187 971455 250646 829197 -893810 -977345 674551 -705082 314567 768201 -46322 829893 285427 -349982 -512942 -266200 -998808 -290589
771938 495108 -887565 -511255 662839 124650 1717 297663 178447 397855 -247317 308215 -340678 -862390 963957 988061 -145534 -229768 663608 501745
-857906 134381 -995945 -261479 249288 -354442 -658968 -825252 -475197 -285001 -251131 -664928 -271401 328767 -986360 314414 180435 734986 -733106 -295030
-974829 954607 -639859 254229 553884 642573 -246006 148572 97032 184836 -583461 -449667 2052 -981164 -463415 -572517 -35695 -907159 547381 628074
-499551 -593763 -963507 673814 -808611 828211 264799 -497742 485598 28146 -416054 -83306 521747 167801 10949 -407597 597126 -383648 502025 -105261
-484894 105935 994069 -343678 -21513 146081 78700 -694943 -834131 689652 -566461 462445 -515975 -653276 -911170 722277 -404570 -786926 39587 9866
-174376 -536759 708299 -157283 267001 472358 168330 661610 8431 288771 934140 -652138 -150488 -659265 191183 182998 -414393 -246969 795327 22696
-804462 149965 -132862 -922255 86161 -996243 236503 -846309 -228510 317237 684921 -695073 -261943 234606 -675417 616306 -469961 -143881 -177233 982100
-812249 -363333 -589166 -356683 -55670 -123237 -450648 -476578 -403036 -577034 -156685 -802106 -98327 601216 468969 674518 307788 597562 66153 146557
-755340 -953674 -11724 896744 -448792 -548216 561135 691588 696299 776415 33239 891834 -973683 -544040 -55539 711843 81626 -960777 -107598 -852605
988001 -123220 515490 -922393 -978194 618580 -21030 506560 -529464 -993759 488307 869324 506461 -83411 -843501 -570009 -807598 -199349 667885 435744
-223640 303578 241792 -382975 108285 -842873 425478 342312 45825 180378 -80447 849278 -329876 -377718 750357 -824515 949253 470430 -223052 238112
486212 299726 235069 -785272 -746517 146036 809042 -249198 -895748 -307083 875662 -799068 -514065 -371103 -530235 18235 792117 677721 -588742 370820
607618 197169 -120854 516716 55962 18376 665164 -928723 -47988 388665 787208 -886398 871837 -80686 -530672 -531797 359965 -224349 -585161 -230697
-401288 -368207 -489074 -667944 -729370 -749667 727143 866303 70747 -677334 257398 414935 328867 489090 953968 819350 382149 193971 12094 -475944
-878953 982532 877729 986827 322655 -760988 26100 556694 250452 448933 -285077 181575 846699 -181777 -307584 -632075 48534 736073 567506 -885025
-449222 467846 -794220 476149 -171312 799738 244417 -891333 110321 -845728 745133 -29805 -971157 1534 626458 -374832 -812666 188867 -855041 673736
9425 -698069 904865 375272 558373 152079 328282 -271400 -792041 385729 -270913 240364 710995 -905509 -477317 90217 166575 -686872 -103212 379348
-501442 -552625 -88714 304122 -708697 248562 -946453 358403 -626640 -269082 999800 -368532 451337 -667134 740840 547981 109049 -896322 -586976 -439675
-802047 520958 496511 659652 -915398 737533 712720 542822 956620 247928 -946238 -452065 200857 -340800 -477520 -259789 281820 -826468 346746 12540
-39491 246609 -297776 -512528 760409 -832958 341620 -316247 -707999 -107877 419877 -445047 -510301 96823 -625057 -359848 -246941 540234 307712 162000
-1484 -97544 111876 -637335 -719340 -741094 -87334 691291 564002 361714 -368433 -599514 -845856 -970271 -206036 259388 -638815 -27970 908554 281072
-145668 177776 -243305 -645528 -185923 -96573 365525 -608530 576780 658130 528943 -432078 500765 -624757 -868611 -334211 -388970 656666 -754044 -204587
-168784 997259 303954 -49219 618680 -136910 -379722 375032 -163237 -52531 -901171 675809 -958351 -656361 -505310 726634 204095 -702022 505529 459856
280151 897651 324084 403563 964928 -336862 -112714 373035 789459 910528 -143833 155153 -317626 -360700 -110712 -899473 -982126 -637157 -703873 449633
-596726 -605395 -695208 892154 -858764 692663 -928779 820263 416913 -395312 597636 -572881 -202854 -556995 15255 -156202 626533 92345 623625 -639024
588998 366848 233787 -513071 674121 258186 -185107 966094 -980633 882375 -719843 -740981 -582516 2151 -166833 -773673 210364 -265327 -169399 -414035
942466 579428 388120 -596877 19656 819695 -156377 -707407 215786 372374 -506352 -265549 -709636 702862 -543809 175261 -113130 966430 -42954 -542610
-326534 425453 -881596 -846832 539347 -436098 341766 -911015 -481798 916341 -678619 140678 -85018 702852 -87194 -185714 -58310 -216010 193436 447113
-978928 854451 -610385 -109263 -988235 -87762 -223701 -197190 -516968 -759842 -576108 -369025 -377715 868586 30035 283183 666240 608976 -127053 -188491
-782513 -21894 553089 556578 885501 905134 503692 905324 -115802 -887310 -429227 27479 -846020 -847856 -414323 -723280 162596 839962 -155956 -108622
527902 -970087 546810 57885 -250795 -786214 -234892 -525033 -102222 -73692 107943 11430 -169041 349002 907212 -291835 -417328 689330 195913 207614
440855 -455920 -694685 869997 548373 -489689 -422299 464232 -668865 999827 -343458 348376 -566468 605807 267368 -556096 -349118 -213422 736698 -250613
-617266 20541 -448206 219021 518642 290780 -954615 -500809 385864 50616 527485 876806 833555 -85239 870416 -656130 -611064 -268638 -543932 -473773
530170 609843 -397015 -854747 -654025 301542 995406 -512805 261434 -781517 -208171 -606139 306107 952714 479039 140357 -528935 438554 -752412 433143
-244790 -592979 -829301 976685 875854 -170915 962723 -634707 927962 566118 -171324 -864130 -188133 -226005 -678416 943656 -868307 141417 -513512 399239
-28956 54674 -174085 -696526 503427 517617 -156282 446546 -25632 574587 -299881 -89006 73566 -142733 872302 294312 -147635 -674713 -527633 518016
-262366 387724 164377 -228300 2075 812052 -675092 -507155 -259814 -866643 -910482 209370 262092 -800670 662286 -869956 -701287 164120 -939360 -398842
-869686 -24183 -865831 454437 146036 -320407 837330 -483216 107788 549881 851014 -591064 -106222 970722 628576 -931105 -98590 199425 656843 99043
-874869 -60226 132227 950451 -593796 -339710 281315 -992389 341793 357567 -836343 196225 655469 -286266 52212 592301 -935757 550992 -475342 -468070
-32646 -680443 -998696 -882105 -426107 482161 -448575 -885084 573872 -265596 693125 786997 375668 989943 -40195 -645862 -470004 -885622 -230455 -168531
-845784 -356326 145992 -920343 974500 -315844 592610 843406 -916666 -673610 474784 -794276 860662 661409 -887875 -434601 304031 975682 702683 652983
555169 -617321 635677 520663 184382 -793205 -566037 -714836 787405 161233 500763 89930 868762 -698521 728830 -945558 -529836 506911 854028 -414932
-983749 -489162 654711 -44280 386392 577270 688631 516441 -553481 -657498 -989654 177425 -297438 332678 373659 -914097 -945458 -618468 -744814 728429
870789 919221 -2008 138551 465245 -349204 464701 -501289 -485190 253455 -165470 794370 929491 -546253 800554 -491122 -521604 230250 894696 -305712
-858325 -577781 -248765 -378198 -313344 -805071 428167 -255423 325800 538981 538919 272223 816507 197431 335134 404642 -440395 -624219 -205159 496433
-912983 -812556 512314 -340925 963691 302284 -691134 -955729 685683 293834 235179 515281 942002 905732 233740 703015 308862 133535 991374 538454
-322497 -651513 820475 -5984 477560 98106 345666 -303034 654015 139691 461500 711941 -178748 805612 249376 741074 523583 -375242 -639290 -333443
390831 -446652 291705 -634250 240913 80049 274340 -122805 661046 593284 901236 133656 894063 323281 -958742 43623 335916 291384 -522981 409170
483506 197596 471898 332045 -435996 -757369 -180323 -322740 295201 909715 96948 830071 -559039 168060 -320463 -963443 485195 486867 -518616 -101007
-835852 713399 -630211 453850 -963838 -141087 256745 225122 -246083 198156 -957090 -613108 -797511 906596 -44291 -476412 -240717 156969 610014 -376477
-312265 -297506 394352 -985525 894420 -491746 259735 922985 -394942 -747633 262989 -129475 462054 -561559 -8639 -283085 614260 789775 -605708 -329439
-767816 808626 944034 -658161 728616 -252156 143459 216042 363268 -163225 197253 872327 390746 370980 868264 786785 869366 197085 -445974 -410050
-323158 980925 303211 886754 834937 210606 -925983 135777 -479681 -266713 -205560 434987 196973 -701775 596439 -92315 -839859 643326 814627 -849622
-600430 140977 547768 -968676 253767 653846 -875859 684479 616964 225870 -840020 963807 74810 -161305 -361443 -672876 -644400 -386212 356681 -85161
-611521 -291693 694174 -191890 -468285 -139242 -319050 -614930 -48124 878342 218637 886085 871883 -461362 343314 561528 -446872 -864698 -765477 460820
322455 -283016 -225896 249804 730894 -917148 -978645 541349 -360715 -763395 -332705 -998286 -344397 776104 -817137 451325 -164948 -641634 574923 -255909
159291 -615783 895283 882773 -564210 -420841 -514546 -549062 522671 -137670 -740450 -101193 -476111 334903 -685508 -537477 -942969 115125 999078 277485
839135 -662796 -306328 -854646 526218 -367129 -352575 -84704 758792 -385203 117725 -537962 272276 -955112 -703330 -942055 -647663 -819782 -803499 39616
846144 -666687 -156922 -76552 -277686 -108506 -472468 -35641 579806 578393 550721 -644672 -172753 970259 321535 -963093 -649501 156618 107978 969080
-543530 349745 -758435 852766 -886656 -692987 99977 -149522 591024 -431101 -673563 711457 104039 -599185 955339 52184 293949 430469 290259 -795703
324537 -674854 -220094 125498 853578 -965794 217887 894305 360649 847132 452359 555557 158635 764106 -699899 -996610 34255 -667384 -9851 284112
347811 -509263 -564603 528934 329677 717566 -730430 304805 -962786 -180475 730408 -962579 -946928 305410 303912 -622310 366584 -919360 953938 1000000
-277841 840351 63671 -133746 -856107 -517572 -574835 -961189 -819864 -854278 780292 534281 352038 490680 -940451 -911543 880832 -715139 -959358 810500
-818108 -175562 4174 820998 -233831 -838917 913558 -603120 929819 -82361 197007 795264 42249 570114 398126 -291123 -496486 -282602 -371549 -682063
-673622 897191 984212 -91914 -14742 -397761 614030 6651 599404 -968446 87900 904260 -311932 -342617 -664604 -271471 -125224 972189 -359169 -838331
-912134 -100082 -601953 -116696 -531996 -251551 -813623 201613 -626082 -755770 -343041 -295495 -80845 977156 828714 769210 245709 21322 -704372 382830
383349 90377 266104 -119085 39860 849215 -673237 554462 233231 883183 -685909 481913 -216120 -190913 -72765 -440281 -258820 -510182 -179159 786429
-894534 799684 589045 -404520 -335109 -985175 -556080 -918547 -767949 -425714 -532749 414638 -885875 -33158 558254 285861 -110944 789112 299579 530897
-64862 -646354 -752690 -750426 -806389 170355 968522 51245 536224 411709 313265 255301 484275 728435 -57229 -795052 -498572 402244 923178 285062
-324129 -641796 531954 -46024 855183 -301784 -824066 887336 -320925 284155 -793724 -311780 -460774 -731492 -270006 556382 617677 337432 134656 -299558
3208 -431881 173261 -654356 74908 -910018 160414 418129 917412 112877 730570 508751 864624 -920954 898342 153853 -194104 -675268 206210 4973
284195 -480652 84004 952331 886267 -492258 863865 -775445 -586340 -83051 -477701 -122054 133000 127399 340559 184253 -946863 140998 -257964 -420348
-840954 348018 506866 -489297 73310 -644688 307556 -80325 -247253 817995 485836 -932373 825779 -380289 -408248 -280977 -592481 -553177 702346 953463
-392761 -896569 603113 -882413 -112012 324447 273712 -873225 233673 -220692 742388 762293 -31195 921846 -192575 -671533 -750130 161892 932886 740638
-864447 -243841 -362435 -232927 -539856 417391 -516225 599270 489551 -864540 548647 -385204 -450116 -861014 -780097 -107134 -630354 668856 -718428 -65149
-622932 840040 926942 852746 -971563 -340125 -639429 642268 -477323 195683 869170 717250 864396 462853 -867520 275455 -631630 -697173 898239 -967952
132824 509819 -639815 506654 -954425 153268 898805 507763 -857399 748888 282102 -651404 -789959 -959879 850182 -666166 522416 521510 311988 979620
501588 569285 -862614 -217774 -676969 315807 637215 114544 -288675 265093 472268 -339128 251743 304846 873201 69731 388259 -250935 240325 -170315
969739 620937 893350 556279 34896 99991 -392407 47006 385517 944586 423696 912764 828270 -985334 -337139 189322 -977756 -331931 246766 333992
-509981 109543 784452 381431 846184 -949535 555696 495671 850895 864541 586255 154576 -910332 -618497 253681 -939279 -868983 725375 -607703 -710234
287682 706302 -100685 -804513 -137023 356937 -472371 -162061 -821120 -373521 370615 269762 440463 255039 -500826 -813741 -777899 270232 346181 582819
-12274 561521 900536 864431 -552848 -656235 100572 874008 953124 314906 -147356 154571 758465 633504 -357362 238987 -865265 540360 -791273 380910
846323 -333021 365607 -43168 860078 -28600 916155 433634 -86165 -787128 -149024 -789304 -912221 -477809 393406 -108765 -752841 -343815 -652014 -195988
-442243 756377 875894 -795672 -585386 474412 827764 -607637 87887 -855547 671860 -411920 266746 295416 915609 868001 -467724 -608340 27573 620371
-680685 153566 -466337 11506 -697277 -748645 578327 519071 175077 876083 -695705 -564841 654700 9623 654669 616725 -774775 -604002 -559366 -49988
21126 834051 313296 -697743 390395 396590 -617010 287947 643191 313460 93931 -225495 74347 326206 -409512 -175596 -451292 900568 -507155 140570
-516002 -702450 -528684 93736 -905178 736259 930591 -159733 364962 -265088 677417 -260632 -706694 724076 885552 7430 -436693 236456 -658949 702168
-478859 -547439 -751009 -41808 -904923 612167 183984 61124 764809 4380 -286928 -193530 645009 995645 -24401 -816486 -553255 -978670 -549981 423970
-202969 241787 -860226 -167018 202107 313777 565580 133341 -291968 842463 -597683 -935354 -461592 322394 111704 -661179 -315450 667623 -394540 -86321
384455 941871 -559948 605458 -34310 865585 852914 117460 677920 6262 190377 -289291 515357 -240700 -575980 131353 457085 755186 -260162 -136554
-950600 251989 352798 566964 -963470 -366049 -640672 408221 -679988 -873384 550108 -196500 -242601 397700 -620258 938678 -136328 894386 31504 -157126
-610423 -758342 -969838 -282903 774434 627516 71510 786038 -576135 -237948 473906 -599576 -486316 -996283 361428 -617650 77158 244829 -422475 500450
573549 611214 -495401 -176536 436121 -184935 -346681 16116 996640 -942639 -396792 -944120 481354 -670502 -487687 -921262 -866572 80988 160827 732866
-672938 470389 951142 260186 249042 271240 659316 435430 -205354 -823883 -627781 -553023 543122 -942344 116482 586612 -781807 -693553 264588 -113778
634437 361247 -819188 741463 -342248 -324671 -102618 -935379 127913 -136606 7689 -265211 -885318 -132207 801249 -878677 -796311 -770020 12507 943057
-994279 184467 -426676 122634 -410988 992657 486682 -217230 229889 -68728 -497062 -255431 708039 507280 782039 851417 -909068 616830 235879 125410
881588 -444235 678807 -299155 37552 163842 370758 -958057 872888 -943629 963439 735644 -552707 -303219 420613 -189753 -626358 17611 -463331 867917
196084 -749324 641353 168173 -85716 -328721 -597646 896889 -3737 -591647 951744 941593 71293 509174 93078 601096 -339711 -294359 -770267 164198
-851511 -492636 -580667 748470 282367 839790 -492217 -12866 872082 806449 725893 -212904 -640834 -527131 688628 624499 -470344 152990 399585 -585603
29533 958992 980142 87236 131924 -67275 -18626 -58945 269701 -541639 -220213 303223 -548636 -306939 252069 -516617 -61283 609525 737902 3633
552089 577258 773544 -531230 495711 -126500 564416 590711 -652930 236186 653715 912817 13100 220977 480955 -631369 49577 -203785 972814 822934
-681604 831884 -239185 309245 -369958 78976 -652979 -165921 -536472 848978 128271 925678 -627871 -110472 -411913 558454 -956947 530036 -921270 -466317
993963 866146 -221717 716420 -12675 -240928 -950182 -252161 902773 637970 722923 -126468 911512 448803 -155610 -735499 -375453 -704630 685089 343372
-373721 469667 733183 -422596 -358754 129122 427084 286136 -24899 -211251 911477 541601 -542881 -277291 835267 -565705 -765888 155803 103005 959288
-629464 -736548 987263 -557604 -78919 -59995 501474 -942452 475368 -639599 968385 458070 372916 -857670 -71972 770298 24267 333297 489231 -805920
581174 -893744 -800260 974923 14444 131325 -295800 341236 627411 916311 -412493 883265 -179080 -139198 211681 -902267 796582 914801 19597 -130878
507288 -134776 -263592 -638299 -243138 223298 -369878 -288110 869705 -29299 -791225 -832503 -436795 -134713 211823 -739052 92312 326123 -757008 -776962
-212077 348724 960275 859084 751957 -774312 928523 214522 657460 -830643 261904 -851263 -632454 38450 385802 951899 326503 -29838 -225261 -195076
559996 355020 -854410 307561 -141369 686295 529749 -47305 94252 -121099 -500974 -299004 -976025 -608053 935196 -706477 891959 888024 -824169 -912276
427096 -315100 -492458 214896 877218 80416 -920523 796918 -901499 11748 -420008 607683 -488968 430759 727542 -998190 -961773 252758 -96604 -810203
-413934 637014 451880 995715 -249535 838524 -783036 460196 -41028 -34705 -220530 -797609 -538986 -213246 -576811 -374738 820838 200637 -486104 -822756
-943072 356672 -218662 -815729 -794577 -73023 -685266 -587805 -444933 -849924 -362358 422006 -664163 -17026 -510506 320841 -406253 80846 862879 733229
316951 111482 -686166 714874 -356244 -9919 375332 -423063 -833283 -693599 672214 162681 -869868 997934 -130478 -853438 325987 -312315 91747 -222554
2093 -765249 -378748 965367 369003 496893 -90118 -821890 475129 -202903 -249738 -539900 355479 684820 -934633 115668 -19800 -514769 -386274 -468881
-749533 -808131 -132729 -401283 -138165 45108 -689034 97901 373281 505943 353098 -711585 -612943 -679969 -422467 -664598 -801719 -964609 516368 318115
744532 23386 147978 -416894 -402643 45139 -646404 264204 -287467 499282 -134569 -413971 -462043 -225516 -486839 288933 394931 70665 821355 -150912
669397 -915384 -538945 -465525 -860756 -448404 -37136 815042 665837 -269534 1379 -634996 -888598 -976139 -973277 97522 -840427 630780 -303727 -644342
-319141 233954 765732 -224578 649271 104408 -348745 -67392 -864556 825430 537137 416358 -285024 877842 769502 -813013 692761 356489 608982 -632265
555385 45417 -78683 -925909 -378199 503175 578046 461004 329242 995972 540259 848468 664099 890002 303135 -603818 749041 4036 569228 -851034
-424922 139653 -473682 661757 860339 -192181 -659680 577463 301587 -474423 -357573 959849 572842 832936 465369 -792514 109139 1875 -109345 928145
71142 631595 133377 91187 218303 -100880 172340 -435059 -264289 209922 -473084 783090 -917946 -286226 -228816 -610970 382622 -403691 -18527 -346300
-115641 551699 825864 869125 -224081 -253099 482052 443078 316288 523381 816364 510118 -652028 495329 639570 -160999 -542026 642152 453037 311817
-474731 -561844 977060 -426216 -606572 -869468 -182597 -929572 201466 -151072 718439 -513200 -605457 -520271 658898 -56882 606737 -847255 -247514 403537
426733 -443554 397831 952694 -839885 235936 443005 -656232 -981917 -881487 -38661 915256 -942922 58377 -108636 519664 -577821 124326 -139638 127821
296604 148436 677827 -559995 120681 -932324 -974171 689958 280107 119412 -22873 784414 360352 442418 -61841 -236890 341043 370682 -663882 -573287
-377430 401524 -196390 797383 -158290 -33108 -658754 -735899 -888941 176845 434197 876980 -194419 -938709 -641867 -163420 443651 -600652 795360 825257
-209110 -756317 260121 336131 -202360 -428074 -478946 -527490 170732 7639 -222368 -788017 -706145 -242479 -238153 -831359 704575 833912 373058 -919750
-873215 -997869 833342 332537 275971 -424926 -55251 -899211 428707 -382992 404615 656540 -2320 323310 189839 635607 473323 -16858 -793785 270891
-146823 867408 -667707 864303 -309395 777516 -713834 927509 -147374 273684 -464850 -702765 404552 -637866 -226546 485199 -688635 -564303 511823 -787792
-458563 295181 -871844 -605538 534531 -475713 -924412 461984 -949859 -746119 -48004 -336049 -803833 -338927 190005 507971 -789041 -948692 -891635 -554549
966258 -838546 970971 -458560 -358943 -346678 951661 -677966 483698 809249 -855120 186281 -64154 -980276 -311523 -932737 108707 414172 -792875 -349031
-660921 -291643 878918 228203 -559378 156165 -413287 546586 -96806 555243 -708438 540079 657924 569439 916422 995596 856648 357834 -358179 -822635
105673 -900867 925747 158330 502237 259870 -408479 -954562 671191 -466495 803023 -122106 -964673 -357574 -948995 -783657 -850021 -703461 271786 645390
-876885 -137862 -468846 -971509 -118112 -757132 -461700 -217832 -239746 -408771 794370 305658 -281094 -478732 -923314 403817 -819251 347618 -745717 -440822
-250706 -5158 464900 228563 128209 -178634 977994 -324070 -702748 992866 -726510 925180 -374245 -552961 779698 -537833 630336 690870 -413980 -259680
695757 -680336 684273 258210 -158135 -156182 880360 480993 -83230 271547 897772 185595 320435 680011 86259 -39298 8475 -889893 848623 35470
-338131 -425391 942916 132666 -468346 -561088 973198 -407599 -583889 -665254 197020 815021 -274091 -50614 -707309 -477551 718422 101568 294844 -490422
746438 678500 423417 718493 -323947 -815135 72594 -885900 851984 140096 -348798 -953844 -2807 271071 493903 -664676 -384511 757844 869815 826932
-187488 -816593 773846 -425561 -976873 -579476 -877855 -959033 331920 -146642 459596 -87819 704787 525145 874901 905303 -608104 301269 518897 -761903
631081 501947 -635611 -647977 -660058 -382094 -654628 -35339 481160 -814128 583216 -454324 -632819 99579 914506 -267806 490060 -591477 -214452 376028
-603715 452600 -148978 729330 -171645 -73221 642283 -30436 616594 394508 -14085 -682628 75021 166994 -577967 602073 -311092 -139254 620621 173988
-980626 100407 -6749 -827288 928895 632763 356482 323887 -163787 18546 208093 860441 593355 921857 241098 -927739 660236 -624396 493994 -54345
877522 -813909 -261665 445300 -991797 -924776 877192 -746462 807954 -138978 60793 -943364 43249 197726 565669 380638 -63204 831343 244684 -396148
-642167 -88833 692862 158544 -320088 831501 219519 -25127 -419145 788924 774541 -323183 519074 -700917 751151 80205 -458351 -55390 -337972 -732298
-167702 489097 -634556 -120047 604943 169606 817739 -467621 806168 -502442 935480 734240 773604 -198567 -174010 186569 -168994 -181224 -426760 -743274
333827 336991 -569674 978799 -640755 284947 73246 737661 -804587 799765 -102709 -903928 186769 -404977 527887 64458 126209 -846134 550634 756202
-469961 986192 433092 994633 -847253 193771 639588 985883 -856947 881547 -556574 163465 946722 296578 -492283 -802160 -11127 -239324 188987 811263
-750095 816003 345025 -798550 -663093 -45133 859509 740877 614497 539337 -133927 171799 503283 149924 982504 412065 180736 366909 -755190 918017
-800666 41536 10220 796250 -845168 -393889 32776 -439367 647890 -75528 -212445 -184384 -87237 185034 -646495 -243036 909050 -576689 55603 952609
14923 15337 206201 -182956 611182 -604178 25718 904089 -217827 -545092 815169 319240 -165854 313207 978326 -552950 -20555 -712053 897708 836787
454825 619598 -236974 -472271 -291362 -873823 89157 33643 -790078 -934461 -450607 867846 897416 909119 -559972 519231 -503916 -739813 67404 -634327
-928773 354956 -190150 503387 171031 38239 -35499 882968 491919 216763 -216502 -384931 -348394 -794808 194888 344603 -244768 706557 -422162 -744029
-163315 -705674 106376 -236181 -534315 85590 -626075 263808 -986082 649143 611694 43146 504904 226018 425254 767635 589288 -510571 -721495 -329483
-466415 682514 313387 554616 636650 -147306 -596468 -740326 -510487 262122 995627 864453 176525 -654213 -430428 -488934 -579793 359006 483759 49960
171728 -186305 -298310 -12133 -720591 -753166 -940161 125624 994521 969600 -838462 637188 -107596 -954427 857742 -13302 117295 569616 -683361 -310546
-426842 569889 -880587 857442 493980 -927308 -760282 -973095 -748466 -828781 840263 -712166 862635 -400307 -63737 -595162 443125 130221 -999915 -3227
7886 89638 -26014 391323 843961 180015 -844705 959399 -92806 294234 -720714 -459842 -703663 -177429 -20836 600684 78688 837961 -986454 -707322
301950 222626 344719 102792 495003 818925 673231 -8394 -991468 -109152 209606 -26839 -950987 -371155 -544686 -174761 366892 -744955 -162125 -896725
84952 623829 -239187 -440013 481890 -962333 -538069 174744 799331 370434 684742 804102 717097 268416 636410 959450 -417119 242374 171378 796396
768793 -651880 -499852 722362 -884305 -810361 667838 73958 -541145 288015 -319785 -473148 -266576 -311220 355848 -845518 -736571 -659125 -334176 441381
-446629 83309 769740 264918 580950 943526 -268130 661774 16055 561983 9776 -281193 915088 -74684 627477 -583928 -366226 -617145 56115 444629
264818 987803 198162 -570444 -238940 764647 -308630 927041 -928798 -329867 153441 996626 776193 6188 -758155 964235 507167 668217 835104 329997
543444 970106 502275 -87588 391603 -50079 -707190 138248 699713 701304 276309 -201989 -266926 392983 -767469 868429 -950279 581494 -217566 -275569
795423 530072 -855565 -601003 -170688 627741 510728 586145 994949 -21793 53286 -565437 583822 561755 -485953 -633295 -559801 -794713 -731923 -848580
-762206 -219110 -217173 930247 362954 -673793 963297 -491471 885057 -29040 311693 611177 917259 200894 367999 573164 151342 999746 -747326 536618
-317606 -400932 423987 -914159 -823400 -262826 -550256 488279 269132 396603 420317 -140274 836917 -170165 -532454 54713 555538 -864984 851481 146697
492230 -807138 313390 -681186 928728 495195 303995 -261830 408499 -274567 -853162 80110 391792 -2802 -520701 396228 -855248 738956 279683 844992
880460 255400 -890336 149404 -747192 776347 967715 -353308 962702 898725 -833791 -217046 96208 -212213 65482 515757 94346 414215 -957192 98496
419860 -737048 -136543 -977069 -21093 543587 147448 -172439 -83053 -59641 623968 -522870 -782986 424126 -254014 893760 512677 979897 692300 -459883
368596 -754182 -702480 -612529 472402 -669439 -615296 604041 47155 -279767 752962 -965248 -317591 15840 -868421 625343 -117929 -933338 794536 754812
87532 -973760 -419000 421945 -655791 745590 288622 -999655 951381 953992 691170 -761692 -395012 494271 -704876 912693 512002 -175882 -982783 652130
399863 637840 86094 -418259 987149 -897937 308867 49636 -988851 930898 896067 -290143 830647 308734 -546038 -915724 956493 597978 -956637 37537
-764433 383373 -906723 -11383 -395646 -885170 751250 637939 -99522 -132126 900367 -421143 -604021 240269 254845 727402 80464 -871891 -532626 37943
600732 -623497 -940237 -886741 582685 -231378 445640 -107457 269861 -914459 78993 868253 -827325 905091 -729374 -173550 -356277 690692 656263 -773883
798935 -414122 997583 -366813 19341 708226 -575448 156235 -190560 -978955 454975 -30469 414213 -693917 362349 -299642 563377 316233 290349 513755
615789 -654149 576117 549842 341711 893764 -2046 159899 233730 -116270 -881274 808344 -199656 -789190 673299 117430 -563890 751900 -86349 -102506
473442 -636529 747302 -816264 458342 600398 -298985 -786058 852875 -501083 -62736 -116018 -92998 499874 882834 -871261 371687 733210 -900111 204353
775152 -716224 -195554 -515641 517014 -862955 594669 -45553 -692465 861477 432381 933183 -921472 -269838 612550 393234 -66586 524086 944999 -761694
-41740 406306 355147 829087 367816 -396916 -488742 -818678 724077 398154 162667 967550 -845585 -535085 569823 -906740 1102 -702754 -3024 31341
-212443 -902339 -717320 543494 910031 462692 657509 -328326 -248980 460525 334368 195453 -700549 -412045 787153 583859 385230 -540475 578446 -293567
-9529 -223778 851195 -999766 -888520 -240803 -268151 -865281 205894 941305 352622 -899129 316060 -833430 -883656 794593 -980028 -84637 275339 -420463
-361149 -590070 -901893 -973430 -147170 -287160 217302 -876007 -186705 632675 -619767 -648495 145843 -541758 -439077 612722 104870 -283711 -120636 260313
-952688 91530 296058 421750 -351153 -985366 66041 -981077 511368 -684744 -113255 433458 543784 391272 834792 438470 -29457 525078 106455 -8141
-163644 894364 100861 -303191 537901 970213 280888 415859 226998 959637 546496 -520612 -2510 -215499 692694 98160 245818 440691 -734004 233090
-759135 892604 -808260 343437 989713 828639 -560117 -953817 740484 -281945 -213221 527533 -943885 -597449 918258 -41365 265724 876210 505654 -133930
663958 -40637 -881519 -940909 -913295 -472811 -383783 -248113 -668928 278655 -622610 -894840 210953 561008 -508757 476044 -703507 805531 897133 -977367
672673 862377 -37794 -108822 725493 -879072 122557 -472940 883829 -738884 932659 850578 105978 -572724 -124577 521807 -411446 985557 975365 -757907
-462323 -811840 -286642 262713 -206794 -698015 -767024 391545 392257 -736747 -387557 710459 756884 -625772 -867219 -311719 -594084 -270613 -673211 70134
-593691 -118494 382802 -82004 590552 813480 -559023 707799 521807 52262 4767 -647580 689638 -273228 -373513 874149 945642 479601 635727 333753
626748 -658340 -96258 806634 -280172 -449222 -838554 -103329 -785646 789076 967074 -26294 -527090 704629 523097 -107813 -562167 607330 642113 297517
-311857 152077 -672044 649400 -91178 567164 -317775 -289911 -301266 -717080 424253 -622573 431834 -73912 -999054 675224 421331 631159 -100717 272641
550955 -639170 -640949 721950 812023 154805 -457602 761673 812331 -364298 -729319 330520 -762161 819310 673078 -510705 -66578 -449647 -886010 772100
48915 -306071 753353 -127729 -933434 652689 -461952 -564490 -382544 702300 692278 -208477 -85945 305698 933088 194046 -394536 -583300 -201468 229458
-863390 -866920 -277156 366554 -59928 -729855 80179 632644 -886800 -276351 -364355 -875462 -518481 474662 383657 724862 438087 5300 309407 -531038
-168818 576360 767209 -219789 -201894 543992 -453716 794995 -471388 959887 -297971 -133051 -716825 22504 -48584 593501 85011 23195 -247101 545076
723993 -428661 819850 45815 103844 832072 356035 773386 -652015 -409452 -491898 -38973 583245 992378 -251623 -103015 501318 -232562 814436 -649483
-266273 -548879 2645 -876706 -253244 -956181 -218774 596467 952771 960280 640810 -613373 -25038 474313 -425016 -990606 -47113 436027 561682 777330
-518369 -459068 95748 -581102 123436 460674 981328 -160301 -326006 721361 -621512 -357426 657467 832586 984255 -210879 755794 86523 156368 872675
-514232 -161519 -101281 607815 -610989 -630910 679760 -397136 252147 -448590 697877 -815647 -819268 -566691 -576404 -557956 376797 -563793 -829910 -635898
-153425 976887 601205 181590 -587982 -937977 -195616 -727799 270063 -936363 -323083 -12792 -732561 233712 -541767 -795764 -351684 440751 425351 -764943
199712 388175 -975300 76890 -513588 362728 -170972 -219099 339725 335827 -632054 473049 -149604 -680020 752676 557368 -404161 265883 -985860 338474
-264975 -507127 234702 547339 -103158 480599 583954 -775555 -882825 482702 -400570 395377 924642 757566 921121 910071 32873 -419161 -288698 955040
840820 706802 -519549 618214 565997 39252 513433 -437776 -497200 -633000 -131557 183686 832763 -261343 703879 -112419 -975931 -452968 464562 -563161
884754 -463243 -769370 -445482 375767 889191 -222008 588647 -818879 92958 -537551 192989 133761 942681 -463526 304975 550474 -17700 -57928 -467425
-113887 814842 730801 -845824 218138 295643 -721057 239022 -505383 -127881 -434063 732942 -176211 414616 547026 796926 641163 -273289 -502420 849849
-675617 357082 -613086 -256770 100520 -45097 -138880 -988715 194301 420405 134424 558110 863035 428177 149148 727017 -676409 940269 8870 -518063
996092 -471362 -250688 -616623 982709 -903292 -378012 538101 -610098 -627040 -649403 -837870 173936 937601 497330 -414854 -696000 860348 539070 -535122
-322847 743492 -149231 611321 48693 -680312 -526850 -825045 -902974 211744 69628 767156 -758017 -705996 878266 464222 429749 -655051 -343925 472612
768698 631711 -8803 -480261 -653643 345046 -210391 -633021 568596 176092 101807 965895 -790341 767780 569381 877206 418369 -740474 623453 805609
20030 206126 -313643 -342633 -425507 -623586 -811323 984313 995562 842045 -500742 -948634 -997981 912550 882817 796595 213441 -779283 156393 992743
-823429 -179651 -736478 641332 -735377 613223 -147353 220450 630538 73813 366305 431653 204882 -611598 -67544 -674359 792523 -307547 825102 389710
-369574 -855096 -331696 -201806 389006 979764 -625182 702256 -590282 -549868 853832 -812409 501486 161006 144187 357918 -646166 -712560 571940 -708202
845962 -691415 -748838 713339 -492391 535780 -549504 903598 -388932 -74593 17522 100684 908410 -356159 388981 537938 -683110 -472320 434679 -724306
-644953 575741 -611026 -81972 -798073 -228983 845773 690172 959501 -364326 -423236 -565615 -310162 799245 -796218 -4943 208350 -688581 -563977 -335294
-794274 935430 -254769 442860 558453 786176 28471 -177920 827994 -970341 -772075 -71471 -295616 675001 915532 286330 90643 103430 2963 396007
-717610 485700 942144 -780440 243224 863265 854088 15430 -110416 -804891 217870 234942 -392847 -324525 -258225 675891 34570 204766 -473358 780869
252045 -236949 -877470 685456 -167593 -876175 -63797 266335 240854 272123 8569 38750 958447 -62538 -522309 857894 830169 689709 -602263 -990839
489268 -239136 -724067 769586 338670 -872133 853168 147093 54845 -542261 806437 863965 930716 166511 -844892 -300041 -687184 -265075 -862452 -369562
494872 -230843 -932623 236103 -130488 802024 341549 52424 -392662 -366825 -744062 -420046 36778 -5899 -111302 -572261 -333238 -936844 -467765 -809222
416805 568316 798996 101194 5408 -12665 -719144 463576 874387 -28779 -300605 -23708 -633935 -782119 -432651 -542265 -686163 134416 -672202 64722
-504982 267074 -386274 124355 229603 865998 -111290 706539 295436 -580397 -745699 -59927 -110746 397576 -402733 648364 512569 80525 -782598 -507026
-825431 930565 216515 -564510 -209046 510361 998619 -865303 352771 105558 -568218 86048 -829529 -543441 802709 -700484 -650220 82880 -873764 -811278
-455905 427021 112673 -78304 -514672 -107660 639488 -331051 46115 157933 782061 433229 -551716 -567440 239127 450991 -931141 -396717 -547981 -583976
-885129 529471 -198800 329016 874519 -133445 328968 -44524 14105 940501 121998 496696 -406598 -901317 -906233 855331 68383 -767103 -76532 503946
-58600 258926 985709 -384647 148935 338364 198517 317664 -536090 -888185 37911 553205 -202056 -616733 -745032 324797 161909 -943571 -261359 501955
-29292 -908255 -519441 -729072 381866 -174855 190253 164429 242259 -55162 -20089 238771 689068 -228918 -20857 -933869 -612984 -720609 -314487 367038
733774 -619880 567427 110771 -192968 -771557 868117 -946787 -232313 -933030 -165577 -500588 -185530 275791 -601641 -490956 -980235 876551 377363 -56289
-601693 -307175 -528234 -371112 108876 786139 575131 -961619 167383 -233071 410594 -669559 804416 -540336 63925 179563 43652 -927888 -52904 -611575
-393677 809319 -783640 -757084 292846 -945717 -533171 945812 879687 -852449 344634 -959008 258578 -615708 865351 422113 -463657 152829 491198 785890
378844 -159913 19507 -589027 787015 720232 -115486 941080 -22430 888141 -413439 474778 -230249 -627934 -325531 758545 -868255 -375024 478281 512847
-298353 -604626 -265548 -481419 -1616 378485 -245153 487225 574828 380030 -513105 446377 -539034 -470970 874446 747337 -548595 -937758 442096 -18178
-659951 424729 876144 -862173 -853941 275557 -680145 -554002 204880 387092 566256 -212186 826395 639843 -914775 -623038 -105422 -503795 747971 -590026
951965 -456313 381810 146986 165823 -732576 -588827 508434 873761 44146 292993 -858862 31779 468103 -445121 139237 -754390 707235 -160038 817751
277777 302288 -560972 -638094 -714762 -480193 815748 -518857 -476808 383949 115832 339800 115943 -297000 -975034 108594 602420 -796143 -526743 249639
-685577 -330026 754358 -368849 147993 771328 -825181 530958 -790037 769748 -224376 789677 -611820 -250337 -967728 6775 -71602 534929 689071 588436
343826 583140 592571 369728 106167 -809179 53629 481805 382532 456914 -520517 -520210 -692550 -763511 -723051 -949345 160907 -860585 -426505 -606626
842680 -586116 -477185 306885 395062 -584992 -955623 -116739 -13601 101991 743910 465579 -886416 -622389 -501921 794182 -157406 661337 896585 -906259
529078 95553 -345569 602704 413492 393851 764863 -605717 -531 -862261 -671362 -156110 418136 -29851 422588 575315 724636 665549 348767 217460
-602120 436284 -278152 525373 732122 271491 176365 395429 305222 936406 897137 -623810 282047 -302745 655107 -645289 -318954 429455 -211369 -582008
63019 -733140 -619481 -600418 -240391 494188 -533219 -826376 -230365 226526 423621 -340625 158198 -867484 96780 148000 -123857 -27209 -658379 -550928
616989 -360072 -946746 378775 764506 -808520 -913855 -847012 648907 300165 695362 906612 914291 708508 704150 438574 -182540 -296378 26745 -663083
-834278 218926 -557117 -813277 398107 -167279 -926754 -424667 76010 545930 -970984 498042 -769118 995904 319618 287659 -201656 -645540 138742 509599
-566615 -199057 -145032 -588012 -545344 507700 735003 303036 326610 798338 699331 515786 219185 -243153 431591 -368526 -77825 43053 -592255 -315887
633677 -385181 -94100 292735 -164727 -988406 -533591 -193434 308838 828552 862353 739263 642628 285753 -887572 -318274 -802851 417730 808650 404781
-636705 -163699 255599 995616 492835 828057 -435473 616235 946659 670929 633297 745707 -678702 241506 127527 87507 395315 674348 73980 -80407
-396560 -503971 392966 816013 740371 995040 -588118 -732280 465329 611928 -857163 387141 39180 245756 773023 -458130 -592871 708161 297048 836473
584690 -319548 616094 -773495 -284028 -844797 -834120 330319 770274 935526 111980 -377895 -103450 122767 -98496 -66662 -950862 222548 346645 -817615
877420 284299 -805451 -871368 469405 -504608 686978 282457 -652915 -114402 -742781 -304650 493733 929779 22985 585810 -938426 931093 963530 292029
977431 -893966 -363929 588829 -679216 -20386 990462 -414350 -466283 -492948 820135 938896 568269 600845 -252005 -482652 -402026 -339342 -655687 -286874
-990791 -221773 910034 -584854 -897437 -12895 44620 -147127 441014 245430 -643964 560969 491569 56794 745843 502497 483771 484499 -293642 -323816
-489811 -741950 555479 -375651 -908196 877840 34944 315234 -663560 -783512 274665 -329559 719842 338615 680645 80915 -297757 -687507 732006 73512
-529372 -390237 842940 -65329 529044 375077 727334 249298 508386 -519561 856122 -512098 392347 -222361 113983 715978 -538352 602825 -343453 -690084
269734 -644961 319714 784071 510899 -958733 932269 899539 40960 562644 408565 47227 -876134 -817043 -110150 -321878 -706031 954432 127391 -516272
-748090 -380696 633511 782269 -616329 384807 366023 -671948 -4064 268208 624206 -5592 335448 -939179 132300 222802 505442 -766172 -871656 842803
-717984 663868 911320 737771 768665 -106983 408188 -910149 350490 753733 -367574 -382803 -261814 -636483 721872 -944823 -430897 770386 -982686 397872
-774054 -383794 -108923 683064 605668 658051 -574675 547941 -736425 -409630 -368240 -134153 -326409 363511 702023 285842 403437 -906728 297061 819753
750401 559439 550765 928544 -994868 993747 732274 978278 -165150 350445 778955 849067 466234 986818 -516322 -894930 -322025 289645 945936 -594044
-692330 -719027 398205 454276 479446 -6505 364657 919118 -122391 -387484 116623 -462932 -930149 -384371 661055 245815 656359 -180631 238038 -689205
773257 -863034 630170 705412 852353 -625216 -407460 -534885 651 715389 -58410 -646355 -52600 588426 -902628 286854 -871852 -987332 89942 444673
477827 330405 83141 984791 -219142 760252 -715958 347705 958582 336685 721850 152103 -732919 -139016 698887 812823 -876729 113916 -796597 422670
357579 682619 333640 565795 488101 692544 606008 59447 -179708 -920419 494814 47004 -661892 -482867 -839301 964652 830255 -908819 266318 -389538
-982017 -214319 -909159 258867 298293 -582351 -676699 338646 -174950 -636483 -203283 30758 -912996 -672205 854578 -556585 939612 -791387 144410 193430
-757673 484707 -541409 -78939 142872 -83216 -384372 -347288 -372241 928773 -137977 -918346 -941405 488102 -383648 49420 313133 -622815 171752 -104245
900682 122294 226815 741167 304194 730673 -973544 511391 -235959 -276528 92429 -880732 162575 305159 -836346 544945 597123 410682 922572 291711
-947315 630268 278246 815959 703082 -126142 938084 973939 -952486 940981 -363279 -973804 -929199 -227291 -681983 -535789 -786426 577480 -207520 -449581
-997739 -859165 307175 839537 294758 328040 -547755 -751353 799342 207831 427935 -297184 557622 170220 270369 -430708 957082 586019 13910 -170484
-267949 574108 -568388 -981467 -233120 -187944 -661031 853276 -744280 -568064 348014 976360 -2310 392994 89949 -352208 -177363 124190 -507622 534190
960622 -615392 -729985 -545234 823846 622961 59955 454464 758548 120031 454689 -759600 696535 -730280 -789644 -313754 356320 409950 -727362 634634
721344 -429184 467951 -876559 -310926 -67118 -622931 -75943 997821 781427 -678399 -485570 239698 -577430 -292323 -616771 210296 866696 -463391 881827
-184618 316251 -652195 -866583 318680 757921 -335660 -699755 106388 367968 232945 -979770 -282542 728230 628455 159566 950847 -839282 -881878 -650457
-956434 -356139 442823 132606 348449 855463 -861543 -223652 310659 635540 -534248 992989 468466 166028 785861 835443 638486 231444 808713 -306242
11492 631923 987490 -771939 101356 88559 -392816 -374035 -523133 926921 -270928 -214582 351738 -438290 -653506 451812 -336537 -622155 502861 886168
-396743 551390 160052 906655 48527 820380 -316817 -726297 -203836 -310996 769954 427931 579054 -664291 -567294 -219616 -68831 -816047 -831939 421428
424510 -299469 -249433 -73724 -251454 149631 -478073 -595895 -677843 -542339 371320 117298 250371 557452 -628677 625540 -996009 -250252 810345 643826
-34478 -160258 834409 719913 175582 -239759 -423452 22580 -375538 396201 -952511 -283912 834237 689982 763223 586310 215774 967467 -532048 281559
-298635 546984 -716564 621324 56866 -814259 790049 661894 -490864 883996 -11354 690479 982525 -44199 -694312 868165 -446776 -151420 -709836 -673597
-350814 777625 -918750 -908526 947105 854250 -838472 639533 155160 -922756 355791 -820354 -917083 -658151 -746530 -832464 989623 -759441 -662677 475385
-992950 760515 -129316 -380091 321210 39812 -753210 479849 388192 341518 959278 -501876 887049 -185317 -387657 227271 -537711 -656338 -218763 689594
592861 -62429 829553 991997 -999725 -494645 988807 929712 409790 -312042 873348 447918 101678 -441539 350456 70215 558632 -649068 441481 838302
291546 -845989 751288 577708 338209 62608 -811287 918645 812715 848344 248494 986918 874615 -545383 436608 925865 -956948 -471590 -489745 485185
978727 -816318 54932 820855 -892681 -121023 592644 775708 579570 938001 926049 433322 -959093 473177 213995 -186320 188675 -786912 -636079 -984417
-436392 473594 726398 222051 855151 201275 133053 -600604 -923921 -28556 -714980 297684 958158 993051 144321 236451 -889918 917913 932828 -468010
-713636 495169 824876 -864374 -558525 -203350 -302871 -10969 275651 175432 373655 904705 94935 -433303 822865 815253 331921 -343686 876343 -564508
62570 263520 432746 455755 919654 -771646 -271564 815619 -941423 -365566 -47547 853051 648394 -757123 -256197 -604989 756619 796297 -376028 -827465
-361031 -507569 -32274 892544 -947927 84742 -893694 670970 811478 -268302 -439727 266541 753949 18633 952502 470596 752202 65633 -135531 651915
276543 -484736 -949239 -386146 -321785 -684555 -342107 507768 777723 -818393 768959 -156459 764123 585054 -507812 787967 -358334 632698 846182 -816972
-632290 214476 516681 64956 -616678 716394 330319 941215 927397 -303112 -609374 706710 -703145 815279 521749 -887700 431093 -885783 17813 960076
-731970 -446375 -539328 -140790 -122234 -611475 964474 -223509 33509 -587237 907986 -231103 699530 998246 717015 977220 508803 889464 -789682 567892
-782596 758110 793924 558777 324992 -237841 -933093 -680740 459399 891901 -299826 -646260 -968988 897903 -876360 -802474 -755903 547086 -855154 -311317
602890 -482550 -124394 -119857 674759 992169 -662236 -957194 749872 -844398 -382645 -826048 -819248 208777 -565658 268733 335457 -911607 423155 -140854
9009 508760 952287 -82858 247649 -223857 258871 557388 175302 723583 -185416 -716812 -982877 -157322 -553684 -120252 -602543 -315508 172676 884345
-94984 493049 523386 60815 -41396 61370 -665045 -7896 -931935 839103 877433 998523 999729 -921469 -84007 538460 170783 545303 468066 -535800
-714036 964717 -828205 -404323 934787 -907435 -859614 548528 -958882 -967869 -454285 930476 -58329 102159 -586367 -456024 438989 -224225 449397 -325840
-322712 863006 230416 -559261 56261 -396289 186660 -336071 -983391 -720705 155007 -865395 422652 -129663 961781 622329 595259 -451128 -726252 -864023
527239 -327069 74582 476180 -550702 305990 420426 389706 -353624 -504725 813484 474200 -432494 -68885 -152012 -667599 282054 -975139 139451 91494
-467626 947253 -645972 997911 -578011 -460616 -436678 -637182 787439 -711930 920904 -651880 656768 532994 -769921 -160581 901536 574018 147426 -792490
198679 -807083 268958 478020 827401 -811589 72154 547034 688109 -994706 520876 593263 80548 303122 541410 778696 -372153 714144 397576 -311370
-744448 -996174 -672372 -445945 124571 -760549 713936 453598 139165 -414979 953434 229591 942651 -215215 408507 445177 -195064 321765 -392015 991926
818106 -574616 -834948 -542629 -323390 512140 -565635 -247315 -47854 -128756 -260080 302987 310943 -137653 236643 899599 523323 -997762 107108 9965
406141 468260 736856 -656943 139446 -675653 -771575 -57302 -877066 -276611 -198929 448811 -266139 -549058 -368423 -857903 64365 -780545 -47174 -386992
-105912 -343964 -754154 874842 -856136 -900789 -9822 598308 -268880 677877 266481 143495 -437990 -247658 439749 -235978 -892774 483621 115588 -639975
772435 -88743 -989181 134708 -806040 -27245 -870349 322838 893956 842339 -732040 -771612 33417 165456 -746875 -529375 -541884 -938182 885742 -253762
-377438 -248386 -803426 -754211 62935 -702899 -366871 696477 -566878 602484 634222 684522 238481 575580 371446 -74395 -891567 -839051 397783 578559
954895 -630830 507663 830362 -832790 -236788 135002 -483804 -474977 855251 -271093 -274891 849379 154286 -590130 739494 -759055 -92906 -880773 -58003
-341308 882539 702479 29422 804648 -33074 850292 612056 497407 -403821 -58033 -490823 327824 711417 130620 -26293 -918840 -795928 32939 381347
-783574 934992 258487 -64317 334987 487982 250262 -452609 125558 610911 -715499 -987804 572617 -933441 -504275 297019 50560 307094 252336 785086
-38266 -63637 662150 -487235 -626361 -934602 -152500 -623951 -900958 125323 766453 385469 850529 -349491 -177380 840463 -251788 -839507 -479676 356014
-211730 -501446 397266 87136 851697 -943034 523601 624838 -743075 181765 -497879 -322343 -963343 97794 894884 -261647 -336964 307315 -733101 33470
564516 167120 -305570 -471855 -25308 -787738 851736 110575 -464512 913674 -199761 -774500 544958 526910 117672 -790452 -262215 651517 -340991 185058
812041 144076 -341950 -79318 573015 -736010 618559 -717266 853130 -698608 760347 90131 -742701 567086 -22972 -665301 226196 624757 431116 897155
19125 -718215 -787766 281388 205607 -662566 -734178 -773482 154240 754620 -243924 355706 813201 -721632 979808 -611046 -887999 762712 -416175 -493053
-249702 -986661 835304 -833919 -36571 -244879 193569 -289847 965708 -105067 -775663 -5068 -888316 51850 167859 18740 -917370 -755329 561823 665150
294351 915027 690854 -923275 -760052 -487621 478641 24104 721995 -265179 336120 -579169 175080 296665 562201 -794564 873215 864261 -975732 -448795
-256127 698275 -654177 99917 821388 -546326 358435 874147 -959345 990074 -531938 -547914 -648224 132766 -542954 -518061 -903733 803421 -251152 278376
-584333 -393222 -308507 396611 -478366 7463 -202813 81358 -829687 828843 -284422 704402 427708 509389 -420731 16595 807639 -305755 409376 195607
266127 -704737 -783808 -250967 383474 -774554 995897 671366 575748 -146672 -501996 -759905 -425995 -897902 967548 428373 550452 -30962 770390 622422
352084 99634 -345563 -567416 -854042 366273 251433 537463 622734 911135 310911 -184581 55508 -827022 706499 249957 95248 -451742 -674260 385082
-916924 -860505 -946927 286365 -286223 -987086 244797 818210 -566273 135511 -407925 -365034 161306 48653 -473045 714332 500526 -218905 462919 -378993
-321590 856940 -143996 -682219 -244720 -827139 61279 768230 -559067 -765779 882234 -498035 820189 488951 -147488 -757093 250241 -946463 485648 -741511
720821 474819 -733665 -111910 910748 775193 898259 -758050 234115 -28219 -401748 -808498 854878 572262 658924 -246193 153229 340679 827537 795845
-725892 -359935 263317 338812 381967 302812 884421 901660 -694811 -146169 -403380 -407368 -417094 -667930 -394851 589570 310826 -7503 -548128 987183
-927795 -98009 905638 -457868 74134 -922009 669068 -389143 777685 672401 -137494 6489 -508479 932859 265227 586058 955002 -407795 -262352 -419726
-171545 -317376 -770398 215951 -333050 -811630 600590 -115658 -17766 493433 -613339 -142603 794549 -550461 -718189 601683 838122 273919 377819 77060
-281119 -685661 -504661 91667 -881013 -414880 3609 783388 3247 -463067 591545 853308 -178256 571815 -906315 591416 -184630 327498 -5199 -175004
-488155 -812948 -884136 -401447 308324 369530 -357206 -319069 452342 178594 109137 -475576 15144 -931054 257416 789969 -502120 -553163 87227 -628640
122572 864401 -567822 -312100 -927525 264696 -590501 -778851 -743305 84808 -240645 -681512 -188093 443987 -203451 -733753 670318 -636170 583236 -972435
879986 508292 923579 -511356 577566 -317448 -829671 -586543 -139735 509324 -129006 -574632 -105242 760773 629203 780938 722597 554755 422823 968660
773112 -837654 343529 709488 -170361 -708528 366724 -103697 -708945 -162217 130766 -753154 697811 174502 662386 -121686 346165 82158 -3551 -566588
46183 -442513 -599675 -128060 -56204 -450744 341512 -90066 59543 27222 412884 696452 -798804 815905 856131 437393 649886 924524 241733 945976
252856 472904 546226 -901598 -498973 842772 -620386 485952 216573 83653 727648 112549 438278 296661 976284 635320 193294 -190028 206186 118246
-870710 172273 63262 -332566 -96049 808513 735442 298521 886108 -249570 560527 -793224 -930705 404541 196137 -655861 -476489 -488999 579378 968049
-423456 -308319 741703 -687570 -959303 -918561 -638332 792228 -968901 -23931 405134 -21546 393598 -482706 300652 177489 -581202 -729018 -278793 -761996
957453 -260555 638709 76881 -857249 -685187 513442 -630238 -727108 992341 -820942 730646 681975 -517205 -62748 842214 957670 -461321 -360429 -77318
357393 -443930 -713100 867733 711596 871641 -44520 -550671 651170 -242380 -49941 281271 874448 -403007 -971013 -576174 699979 698947 135036 -640121
700135 -540020 -368795 806914 132342 941565 808517 -655513 -387319 -953057 185757 876275 -840534 -229722 -758574 558946 639425 443876 42742 -543499
-413556 359284 286063 678761 -563572 -273440 855543 -429035 3941 -886858 629219 -184988 -955808 -612816 495983 319737 -910603 -107553 668190 -452607
341064 508200 46785 719242 -521459 440246 327172 -234164 -897093 -479983 908747 -990448 -858502 234275 322355 478333 -872553 -13400 -320627 258233
-735253 13008 609968 902621 -360489 -814700 -267865 823995 966968 -425764 719491 -494266 -417217 -83508 736768 592529 831110 -647778 -592616 -221055
-706572 429670 -595457 -472978 187783 -884761 483658 37467 -452021 866897 -506326 556675 -413440 -255932 -40606 -53285 689464 785834 -195268 -426546
-762814 436816 -225099 852482 -697181 429509 20806 -512009 50830 581108 158224 247658 150801 -338821 305761 120582 137931 -844585 110990 -914123
927630 -729492 429977 50074 311663 -401633 229479 -123611 702331 -404039 -191913 249134 -144948 353709 8957 96271 344477 46343 780831 193090
-863770 219601 878172 -575706 -542043 461469 992699 399842 -664059 -250590 232777 82561 353116 808013 324923 797167 -607696 -65124 682833 -559493
88147 118662 -555370 311047 236307 18157 -374102 -841542 -604882 -678074 156279 -608663 773422 -259921 177450 571661 -746301 112446 861189 433226
305992 -771936 751032 -574304 -400343 -689694 -592893 -139187 -747941 -255668 907235 -796247 473062 -137100 324782 529970 161890 135904 -398859 787713
175265 660355 612775 -866762 397806 655623 548466 210627 911615 456270 218937 -530415 -906865 -49677 65796 293539 -897298 -845496 356796 -666605
788652 118486 -105603 89234 -750398 175894 514691 894754 -856709 492540 329386 -687335 986910 -739993 933197 237837 -430830 719198 -510060 938534
782687 -301063 628599 498144 545236 628759 -10652 463739 606350 -628443 318481 273258 156220 -723098 -611864 100083 -796747 763321 304527 389687
743484 385217 -144111 -557702 404219 -688169 -38298 265095 -547472 -651206 -105436 227652 -655563 428369 264979 -323778 -778049 691452 -201594 -863056
-903191 801175 -77654 354235 -990717 -840414 827201 966176 679143 776908 -382067 31919 554792 -169608 -220636 -284436 34746 761415 -369888 705108
-843025 -364279 -923085 -812588 839022 532398 -542490 -374089 -106413 -365611 914547 497844 -561935 -237377 345361 -271214 776470 -737952 -517463 994137
-946100 -386198 250897 440584 808316 320979 -590853 913770 -173618 919998 350086 -920685 812314 -662224 -338160 579809 641627 193539 -985608 -577314
375127 -147762 914313 307005 -282723 772542 873042 -72957 743273 -80966 -344704 555587 227043 965330 -621066 -393792 826937 -809247 558642 -66843
-510587 992389 -242850 109423 -763181 -869409 -7760 618486 -71297 -343689 -22649 958526 832702 -902599 -579895 24478 671107 -121506 -675199 783516
-114939 538678 -156719 358831 -831698 -220285 577369 592128 -856657 786635 -501651 -142076 -445097 370725 -198798 -969332 606458 -588046 -814556 -39284
49842 857579 714636 -834919 555041 955179 -991008 -251175 -433477 798516 791875 -69180 106409 405178 -207961 275015 116546 -787676 506675 -142060
728557 -559567 62219 404044 752893 -77056 -853605 433283 357549 -598983 -500036 554767 217392 -767166 879003 -927555 -426802 527353 -637887 -550509
-205600 911862 -767140 644839 922409 -723458 329352 163511 675297 -38809 425681 -877916 232114 -947643 -470980 -471467 597037 -535310 407626 154340
-283521 785084 98279 -311758 -440226 665778 398305 20033 -900165 966796 -824238 362715 -778206 806592 872778 255321 -928048 791805 23261 -511172
-931314 40074 -278469 517478 -723763 -163092 212982 692524 55939 115070 749969 -669928 -69803 965663 -734840 42864 237175 -175662 -166849 -133212
-142305 203210 -458 -16774 513836 -594809 92660 -855807 60871 807168 -62657 551348 -770867 -344246 -689087 168879 608699 -226238 189621 626932
-340096 -5630 383221 206407 297204 -503444 838525 388774 -207382 498593 -391045 359893 364960 120865 739641 -966656 -640495 481884 -153997 -19570
-976672 843007 -73415 -643923 664846 846102 -884887 -286353 -818846 -115928 485070 520562 -965251 -945019 -959280 -750067 -84362 342350 240557 -280136
664752 -89066 -38756 -332686 817518 -132683 783650 -348176 478877 364660 414921 268160 -848409 324887 -503538 -249442 667587 -218314 -650817 643254
648767 -144493 522903 842400 416825 -476726 104141 -723914 189098 -341367 467571 -380705 -199814 -533923 270773 -941514 -433356 -732531 -141904 -832774
704522 -61720 -411952 818690 -100613 399670 -972833 52179 -178164 -208201 -363063 559833 662825 -106283 -261050 -39435 602527 804459 -455555 -264022
25002 248531 -928607 162323 -364123 594155 216464 -602679 -391492 159830 -74371 109076 -603037 -340065 -803131 -955978 -629586 764163 -84141 -223048
-906505 101805 537315 -944965 261323 725742 -562068 302441 250296 -673076 696596 -383868 716855 -601572 965143 38361 132563 -877707 -541380 -275793
-665739 685182 -192314 403162 -270385 -937651 612736 -237582 728403 -968916 -389766 -912456 -143797 215403 649632 -56807 -226946 -687543 -778551 907814
-197647 913466 -238545 -681772 681946 996000 843682 -612883 -330798 -480156 -254357 206589 -130856 247190 416445 -716128 -593553 -172436 665880 464022
-7795 -625313 -524910 -977470 -914457 309601 47338 -459658 353761 543251 -642299 -683324 41903 -598662 229938 -83355 973410 -674104 37294 391883
-169895 -897836 -812006 565124 -276615 -428653 -229166 -839633 -657621 629233 -175597 -277498 -410354 -101774 -671809 489339 536353 713738 221680 -92231
762279 798927 955037 -874125 -658306 -159759 143188 280983 -400248 761469 497187 -735612 -286644 850292 787373 -365239 433678 117752 219265 -582862
-455051 -441558 52036 -921705 599885 791828 247295 359801 848918 500544 -949380 -421422 -610289 654010 -118294 -890971 -822579 -206523 -47191 -471299
366604 -425661 -331773 -537175 989800 951049 461915 -797961 308231 -28737 780598 -953174 663641 297720 -873390 -940244 872146 -454689 979859 -423672
985404 801439 207641 -42368 -523194 -544926 666178 67656 972753 222286 410358 -671770 754277 -941046 -238742 267111 -405804 -755909 -625406 451073
-431165 244757 -342025 446215 -993628 184881 930955 -188034 402879 843859 544082 -664477 513259 931090 390210 23254 351682 -359052 -122463 -908388
759803 -434446 -568640 -473122 -637881 -399965 473745 -893785 713383 865593 -816936 609384 438195 968488 -926860 595185 -967498 793189 -457679 746381
653984 735672 884443 591093 -926723 468761 -922929 -565306 -461640 -921776 357986 -201052 226516 65053 -299949 456285 -676067 507174 -259778 -979075
-852509 -288269 -982374 -514276 286366 -875113 -967495 154585 -647513 327566 -822514 287520 -958642 246260 458452 -104880 481583 -919529 318962 -773999
-659254 -154910 -667346 400859 -378404 -687233 464148 669117 -26654 -28588 -376299 989971 -7436 -433765 -372661 -482599 183260 -100679 603282 779796
-896786 289606 -829253 -812848 -385398 647636 748386 521307 -631196 709817 -532025 -959922 -661836 -718152 397091 -390098 310937 -722714 -540713 -554011
152559 96523 -428674 -24103 -533806 935818 -58566 563304 -918707 -258149 326022 421696 365748 -150505 806787 248917 -807270 -398385 -367085 782716
570797 -506617 -463699 -278693 -381176 336182 -358499 225152 501816 -269910 516599 380400 782047 -335908 -833655 259755 -924213 92798 491253 537034
444498 914680 -884801 671665 -141321 -792464 673342 -36318 -699634 198520 -751493 187965 -837851 746872 215732 -743272 -174920 -53916 -128793 -259821
718553 -579551 948445 -830191 -568130 -569437 -130806 -840367 248389 -892686 -593454 473387 -562273 293972 -947144 394163 503708 725535 883625 179773
-151598 -286476 880608 753232 -778679 -260952 401677 -521078 529298 -342613 178208 -653603 60697 211360 352265 -140803 939359 -202715 535887 132425
558586 -88681 573427 -45035 -469716 -523104 -487777 -699083 -627692 886209 -342426 489589 465024 168514 -395186 265878 -516676 -580645 -776076 13847
819888 883076 -497291 469124 280166 -227698 134282 190997 -716121 689712 396691 933744 -876349 756148 -441233 -62173 890137 -81777 922223 851339
6239 -994015 -459964 -85154 -622265 223803 -883286 540867 888452 475971 995655 806712 539561 523613 -321788 -920917 428118 975274 28905 487060
-341633 442144 -558634 30631 760079 -459188 974104 303172 483096 -366056 -417283 24261 62402 759990 -59553 -808671 808494 287586 307590 -751033
-803287 -689255 533894 -189835 -725686 770017 273887 -907846 457169 80784 -928078 -961018 -858343 906167 -635052 879820 -706839 -372099 -350488 -677860
-841359 -979446 173389 -932162 937623 -782959 332632 -143254 -998447 -332787 -541760 574347 925409 235665 547559 790156 -590897 404791 -482952 -498477
-602582 812028 112145 -774685 -172420 290357 845117 994753 -873714 -69704 781789 -453574 -856953 78131 986618 -835710 -980737 985489 -567473 -293645
711051 558991 -647080 -601006 924961 982208 -920963 511433 923792 -974949 988084 329230 -242247 -556824 -808314 -50518 209058 -187726 -992896 -673680
229271 603840 618731 -68402 -633046 428681 -136161 65438 -94364 394336 296428 -929545 -736763 205098 85462 99082 203298 962506 266961 434254
236687 974199 -111193 811011 -863423 182802 908331 -119943 854153 -538298 -561548 -874984 36033 -966583 -838306 -567998 -254422 917919 -661314 738570
199135 -598828 182735 929454 -126582 -150317 4140 -368065 -859259 -320296 224331 784396 -444431 258832 716298 326776 -889316 -941078 372276 885122
-985332 205215 688181 -88983 912255 -525037 715988 -380251 449798 -528707 788826 -735625 220165 -315735 352315 21190 153453 666576 -523972 423046
-947790 -850906 -512633 -829146 -803450 104438 194910 -886409 468641 706150 -139040 693905 -298722 268005 -195456 -62666 -615836 91662 -371364 -665033
-202683 476483 38554 -184672 -815698 659607 -830275 -975082 -378452 426562 873416 -396123 217354 -457065 5159 433 821581 -28833 -393984 -190462
543461 393925 392251 -131891 -162930 -453602 690607 -223162 -454238 -58573 455609 473840 908040 -237706 -662319 -568877 -29744 445851 -62544 953506
-322850 -893377 76801 -422112 429943 -421004 -348783 366133 -641653 127516 -889254 -114076 851687 403711 935150 845425 -718653 -380586 411511 -368272
364878 -934169 823439 -973499 948491 356571 -816141 83640 686329 934033 -265576 745130 -55299 101973 -769577 -474914 782425 -259865 -761002 -883667
697623 205420 -511264 -879736 92233 353872 764296 415333 -392398 117247 -871383 958075 -404277 789847 354536 -271434 -622794 -333631 487656 409720
668615 226575 888974 -517357 897339 708905 -648109 678609 -866069 645942 -239250 959351 22794 -311301 922838 -720664 652424 870334 378532 -172317
822082 -300478 311881 -59705 211946 -265652 -92481 273389 214794 -904860 630121 -133856 -852049 894175 -221105 -367969 240882 116560 -431428 -873625
531100 513116 635053 878146 -348218 -414742 -140244 -689324 850205 728540 683567 584723 -486275 322411 -913045 -548707 159170 599480 -758397 566208
960458 519188 100957 -506331 524251 447385 -370893 -11336 57724 -920294 -168155 -629453 -493041 -559857 678363 -63085 486467 -654208 904554 584277
-8523 -925284 -208359 424475 986927 -76256 352353 818795 -345995 -800768 -911630 890275 -677799 -682062 564511 574093 605999 348836 -93525 597722
535318 438322 654962 161893 -293965 682487 -522021 -427746 -501187 408711 -324726 262166 529924 384589 773356 504118 -621238 -178490 112129 -72419
227995 253882 -652702 769502 -611209 -523994 -82547 -282091 801603 -130998 -290949 1680 -969601 -926265 -288314 -493412 -51582 -557725 -428413 -596103
-64593 722008 -121041 -187530 -340583 -768122 915688 -941353 840313 685570 357914 -660938 869912 -888066 -164865 621004 -698489 608886 224417 521807
756744 328150 41733 42839 -117482 17681 -351500 352652 823726 240325 -117133 601848 -875374 131173 656661 920307 691795 -286742 985589 -888232
-977994 548713 948581 -950860 -616115 -384348 433058 -9525 708138 -747263 -453121 261648 38224 -287020 481807 -669571 -84546 -847017 -390757 -83089
626774 -179247 -12896 -948694 -680534 -84658 173231 -802271 -610126 -649084 619872 69276 630111 -17849 -222933 588925 653280 -217784 980308 -809247
-904424 -72710 559850 -397602 -952643 567447 831882 713517 31379 -754877 872814 -829769 49268 900537 489326 627598 -153922 -441195 -57983 -955450
816078 269527 102845 -758449 40409 -152917 292355 649159 -227461 896621 218867 836010 -248878 131976 -128341 84044 -515402 -574792 -965393 -41014
-9764 511096 -452336 -644588 9575 -249752 -953479 -786180 -896919 -933746 -532547 -342722 276094 -836894 439251 320632 -966576 103973 -931687 -537767
519745 -5498 -895558 -719427 994088 388021 -377684 120861 150789 -98734 -601454 -334958 -663213 -317691 -692663 -85402 -751636 880967 47611 -199157
-36810 725412 25566 -800542 330270 -345823 891703 -365296 696087 -676549 25177 517349 -928584 -287490 -200811 -637436 430914 366979 511617 667450
337628 456327 641714 -825434 -576822 475601 191909 -108753 927824 498095 864456 157216 -709066 934337 -153122 -841068 927658 655906 621826 -555122
688955 836608 28973 -252910 109889 874822 -376422 -936711 494361 -90500 905373 833571 -235156 -721965 -554230 337924 -676922 181251 345877 149783
-762578 925175 899075 284319 -100753 910672 705214 607130 489796 237333 -17692 -675477 -460083 -533492 -649561 -988441 -378772 599329 617529 -309314
-248600 360142 927952 -394627 866506 -479924 -89441 -327628 -508825 394930 811320 758540 -496122 -395699 295514 -242064 945122 -804608 -600194 -683499
-417791 340749 -437200 -701730 605551 -729910 655022 287639 -818333 192581 633954 -457 397740 -491642 352790 -132951 -374599 847035 330895 673764
-795383 -239906 466575 322673 -521245 451724 -485641 44665 713083 372285 477621 74946 51370 284329 743994 432052 -682839 136516 15055 474191
716998 -70581 901029 -751825 -991928 503736 -611898 678352 -514993 909369 -458964 -723977 475932 -167102 440842 658731 354878 124002 449128 438767
-521382 -230817 -757498 -259766 969073 328212 -162341 -606873 349769 -742318 -44225 -308425 -598666 541265 -64903 147720 -77494 454879 854983 793541
255199 -894148 23575 -3995 -435318 -20460 448882 648230 -124099 -867359 -323935 -679212 -561064 -411356 -122783 571216 -184381 -491116 507156 93300
799646 847245 434965 -879030 881221 -961528 -124861 -792227 -527090 613293 -432237 908426 228738 -1102 -168180 472131 -954341 -866855 -952604 -776082
-344308 -21527 250943 610778 455991 444867 531195 228101 -129845 -645151 565819 193872 -684579 215033 -863245 153767 -915208 -520125 650412 -648034
-324881 468796 -594472 227217 589465 774100 302592 -974925 -700142 -519440 -746826 -750225 709339 993168 -972310 -894114 927787 695626 -573293 -473202
284997 262083 895559 900158 -957059 -876737 -125615 46947 -541437 378563 -144237 -609381 223405 196494 779731 583219 751612 414170 946078 989586
-408713 -3507 886129 -572768 760711 -724195 137605 -765645 -546826 817988 -890600 627144 235563 -399162 -441064 124584 70993 335382 -534150 465442
-118819 548910 239663 -102176 -330391 453894 39306 -798826 882947 -439739 -504090 960391 85933 -801873 854886 -668 105701 141622 -981554 -634024
363573 -242390 103153 310046 -448326 -249632 -61566 262066 696827 -201630 721189 401985 -286582 842070 697172 329525 -386244 53889 -695498 886695
-963197 921103 -711737 1820 -986833 -860324 795288 -79184 356804 517209 -641121 566963 594207 57410 815839 229469 -675641 -681205 -171580 40832
-470227 -446178 -841888 -464069 -46378 -434540 749018 27878 -292479 -460327 319404 -684742 -138161 -492600 215000 750763 -329450 890277 254056 459720
-579724 201970 704258 954308 360385 -445123 8440 -550786 -300452 -389597 -763276 -535625 962545 807539 -640426 879072 -186952 -569767 -218770 -560415
-220481 -56196 -978638 -410351 205534 84694 -304169 573037 337975 -652230 817763 933485 -21038 -633126 604865 -245313 655603 861205 -289130 -102006
234515 -633097 43252 -968778 918736 284947 -722043 190620 879593 -685313 -355506 637272 301541 -987742 251980 -111181 -285867 191088 -26416 -160511
5842 306785 -263952 -266051 -753433 753542 366447 -705539 -437442 308441 123344 -640013 -253881 854317 -223563 -675893 224607 -112211 -138235 201973
-277809 -544589 -680876 140895 -786757 16259 619933 54546 484033 214280 92198 -958431 -247328 -593231 -818668 147874 158339 -821235 373599 19421
269502 -517339 201663 -551194 149306 -681773 -652458 -125732 966243 865765 322467 -654947 -42054 -923756 -955344 939465 811880 -28821 -592928 978749
602293 712165 498662 -115643 174853 525551 103357 -750343 38171 232943 -84299 118769 -973994 476970 -984669 448386 -576804 -221583 -41489 -87547
459274 -245201 -249679 233296 -114430 -41511 -542479 -455732 -809263 65462 656132 -653730 -720034 537656 -939327 116881 -943449 134296 -416157 -122497
331545 -238377 -150487 762900 -749511 921824 -121711 660177 -813340 262688 -884139 491565 -902538 -374322 873756 -232922 -195817 611924 30214 -667455
-98716 720322 925537 -790181 467273 -698717 -17242 81314 -951931 -652882 -94998 324751 881128 534563 325924 784023 -340499 -177412 679810 357096
-607065 -892914 351616 517602 -499616 -99921 744858 -535583 -99435 362636 -734221 48013 -678692 -503827 -687777 851417 411206 769413 -153289 586222
338347 -877514 798196 638938 -750519 -329513 73001 640788 -112969 652459 732350 -419407 700005 161966 -734279 -82791 -724395 -71246 -538560 635869
914981 -912761 115149 490537 804190 199543 -938735 482952 11182 -712972 -939073 366826 -203645 650701 -720732 -551071 -930188 446826 -574419 -640348
641976 -965907 -199149 653001 372874 -350791 -974262 -909937 689015 -493766 896237 -551830 -160549 -893903 366058 108132 742390 -244133 277335 658824
230560 439876 -43205 355143 -468336 -854479 862809 942883 -906095 -134123 911139 -212271 533503 -727622 847871 -722218 -75889 89194 866666 -559630
621878 -356189 762138 920512 -902295 -852454 -902384 549719 561474 -576553 -912197 -888411 -9549 407866 -959546 785982 942036 924396 -726336 59636
974598 39675 -283780 -171745 403470 -365717 706165 608067 308746 726681 267830 -654557 902850 -518609 179346 -849409 -693112 -853516 16861 -190053
-422777 70982 -865873 -458333 86963 931315 -920645 -118474 236220 -847515 743097 238963 136264 -216909 154217 -61459 -518668 -504698 -530513 -833606
-333155 -924044 962918 541525 937269 132440 452627 266442 -20754 344562 994609 6585 -188245 -1329 -974543 285281 -147492 165676 474028 -921448
220227 72895 783369 -83618 -140514 379064 670700 -79434 597252 -331610 79764 -54443 669958 -349896 -608293 500223 -390337 211899 -739290 154296
437585 35023 104172 -286090 -598096 522290 636072 407204 608509 218013 778356 146165 -122748 484030 -288282 -474899 679144 849890 541343 -901890
139431 327357 860830 314508 -305175 -784622 -558950 -149577 864098 981529 -122893 -780466 816070 -369862 402082 971305 -844569 -13173 -871507 -111862
423582 -325253 816946 -788093 403710 -627925 803649 971343 -773298 607949 307435 34305 930046 380126 196549 -352109 -355520 -656145 878457 -599463
65004 -963597 565096 151737 -981032 -82900 -696965 872604 -77957 -95234 -659467 -399680 -242089 767597 107841 65582 820608 669798 982635 548086
824471 757580 -183672 -586751 -470543 -63047 793012 284836 -430045 -420463 705681 -765480 736068 714167 -439177 55710 812511 -732337 423403 458988
966246 475655 621502 -794875 68026 492007 499930 435876 -960396 -152765 645476 192775 -925848 -184260 -185358 502241 23236 721368 957294 -793694
724144 -75632 518304 -900844 -429167 453533 -605533 -791735 15896 804362 406209 432043 -14507 588149 883792 627590 65977 -499875 -698899 -802195
-558893 -939794 -170987 148680 461920 658433 96948 140603 866269 615570 -993179 -178551 268794 -578390 -709240 383558 976479 -993839 -625213 -153075
347388 -629678 212840 105553 902268 858751 -886491 -67791 -955521 429005 410402 481709 -36756 -783063 196836 -398718 244728 -667680 -15980 516749
-195753 -288019 27074 -938204 955576 221197 978522 -745900 151924 -604162 -799447 555638 -103549 -672407 792105 898035 148624 471001 960389 -238524
828177 -558722 500189 121620 384224 513784 117319 -946586 -622252 74897 187909 146911 672760 376010 -906099 -73704 -729102 322671 450984 965032
-39413 -960421 -768591 -982539 531283 -465886 -261294 51996 -310441 547479 11143 685618 590185 240806 -622072 531327 -163884 99520 -401623 692707
-614637 222766 -820503 -102459 -415794 -324575 -455819 -834724 -723372 -171364 565422 -241105 -517088 -806030 -493394 218227 196657 252706 388629 -889576
243531 798663 618304 796871 -461919 -484324 -272782 -475207 240240 -172573 279327 501249 528594 496145 -519642 -663427 805251 907942 372669 686891
-824424 -666786 -112341 -280913 962438 273679 199326 -710768 -263458 -268420 -994873 -155619 267799 -214528 728888 -607605 387737 -142338 877754 -421357
148919 -971929 -701699 -850690 908786 105156 681144 -65036 -307315 11682 -476405 -628009 704346 537990 -381723 34828 370332 -747184 -584184 487739
85116 -302218 634901 620186 148987 -388402 -180642 935294 331797 549011 349073 -735550 642028 -403289 -873076 318762 866465 -565092 -27835 -745164
-164333 973347 -369488 -332071 463085 -452339 893965 183120 121214 448788 391647 37962 -882598 324606 -848281 -586401 -751469 769668 -510609 183475
-295186 33786 -24041 -820812 1817 76513 -972159 -390517 360173 -349895 -961882 905970 -274718 412469 -759606 -495510 -945381 757993 -295635 -219179
259521 -628168 224345 -205376 -832582 -312368 -602189 431173 -300996 -24734 283146 570207 -95977 -186973 420116 -538147 420942 853812 -480158 713089
-281634 862994 138491 -669149 -933420 -266769 262882 673922 253161 -670794 -935102 549269 861119 490145 -585889 -825469 -601675 -253064 -960212 41434
-274958 -939273 -132212 -739380 -172072 -920612 -182226 227352 626171 126722 550493 -150803 -937996 -664930 873786 -473581 -512337 -5657 -634017 -794704
-82962 641439 -120487 -871418 -983562 -820141 -285091 -277734 846660 -868705 -938940 -794966 326596 -142618 301426 921680 434275 212665 -123992 -458752
-320336 553602 829990 -653612 -768460 856338 -612875 -447448 -981212 326138 -714021 176261 -762938 -660852 664984 433592 -881022 -190324 360138 -78541
901438 -769632 33857 151894 -502542 845900 -371580 -296419 73789 -813989 -316113 -557187 -771365 577978 359132 -548456 -376257 990972 269873 -574341
-399220 892121 -707616 819071 -77554 383500 -918251 -750765 262725 -415324 942451 -6520 357492 -702985 -412379 611463 492543 -916999 -97916 986701
134773 -724920 628623 256270 154131 996458 -452125 -48495 926109 669675 -253823 -594041 -338602 908057 -119230 -494777 619717 -820483 779525 -955173
-586922 990692 -174362 731828 232014 -569995 611192 168299 -570014 748954 98153 22653 -665510 793916 -859154 -268790 731341 957169 -727115 -933232
177947 -651574 -432957 86674 -271370 536643 -212593 -498374 776004 733016 -504272 -534238 -473077 408618 -967674 -838993 -732606 -216885 -836356 -100953
46440 -802496 -882386 -124926 66318 -457006 -685372 -609890 492197 -878958 -137043 525212 171633 190750 -346064 720741 61775 -316760 979117 703564
325312 2589 50151 -794433 -923228 744144 770848 -911498 511794 -788530 -41764 -768825 -657214 -46386 -427905 -650754 -715435 -48500 223297 -492841
-930995 -716727 98298 -563028 -856005 75928 903834 -640105 434068 -605874 587321 -889559 -157410 -976846 495680 972673 -29041 -101160 -383133 477423
444329 642942 772494 982904 -441476 -80404 594395 700452 197069 -886580 86411 305375 111397 956711 183305 -466390 -876282 586138 411556 444123
870844 382019 -708597 102707 314561 -382597 725740 817008 765699 -391658 -751763 545729 748204 472173 -246177 6141 -215800 442598 442549 475777
215457 -200528 60092 -770253 243473 194384 -112877 343683 -848858 -437529 659408 185384 640491 -488599 312040 922920 -555406 237855 955364 976400
-233421 -891774 -852670 272857 312227 -33533 725410 -667171 171780 737451 869699 936669 -79030 -69429 154626 -417291 999634 925967 -114631 -907017
917250 164144 295045 -670158 509802 780124 -921579 -27680 842645 676914 514482 744357 -350349 63831 652481 581736 -832716 -382403 799385 362093
-661334 -189191 -265745 25060 -624678 739526 768730 375464 -394349 -238251 173275 -771526 -869798 996396 -971776 -214915 -47032 -802152 806842 -558561
-329137 657646 -201296 -709025 430016 601089 -808609 100518 806113 -196939 -800648 -396402 -654248 -220362 158860 -317197 -985741 -723523 -552942 749929
429159 -336611 -358647 -48853 -589544 460288 -607579 -671203 -193427 740675 441983 312267 915556 377297 923283 -811921 -661002 -788873 -14302 327597
348472 970806 -964273 558754 -992187 847577 -449967 -658042 -593733 -386952 -983742 255194 40959 230354 665409 -544985 465699 13072 972603 -322601
-724508 929573 58421 559926 856677 385570 754303 -969900 -232728 -706720 785572 -623882 -830130 943380 -316447 -601861 -369654 -232465 -650057 -119133
-802795 -543016 -618290 553244 -187993 276522 -396452 -66211 -638494 458393 579132 -122575 -497070 303762 757522 653012 752660 768059 -742310 -889686
963171 -338212 363249 -638275 -86486 313098 952361 -572545 -201516 -903739 685045 585526 -287047 -860780 938021 -465325 670886 -850276 243097 464052
760361 378421 917856 800153 -421514 -566309 971816 898886 -487666 -233099 -359711 -21667 858758 -380901 -784177 -583770 810975 300807 85278 401833
-270426 -947018 632210 -798115 -262018 248468 -416155 561229 -305006 165927 -744269 -822062 279568 -489322 814507 550853 318983 -881732 -4118 487496
-92467 -999570 102671 -745941 -191719 80757 -523520 -578531 93410 -177222 -152485 -714479 81304 -91850 393180 -56649 934855 -77493 729172 341227
-792161 -299901 -148109 -958585 -766741 485389 -119892 271849 197948 -541587 -314165 -795834 -717914 807637 903263 14753 397869 327648 -517016 -113209
-128060 87828 -814961 -760799 855937 -625052 -812802 461782 645696 -233703 993862 810721 900440 39542 368140 21093 238075 -518968 -573568 841679
879250 -768777 779653 -907052 -659192 175415 882292 476354 692908 -201252 590137 -384861 -262471 813007 423416 -992371 812809 -975444 -312302 565605
-115079 631844 -939349 -556475 -343964 -373322 -560425 -302281 681785 584287 -812574 624279 -247760 -346583 -48101 330257 572956 -799465 -402751 696166
980163 752031 -767319 239928 -79823 711405 -278798 -882295 650792 944493 -313393 898225 228467 -385470 -414147 282769 -978265 -863684 715089 -262787
-586153 -250451 704790 -445993 957871 -995201 -386769 -994296 529722 488631 -660917 415780 408980 529625 591614 523173 209725 -42833 -263393 -887429
132226 682581 -438724 -226701 -37859 284843 -395538 -492052 236546 927360 -173858 244055 827989 -660051 431631 388672 6802 427346 71464 -232561
280706 159749 783414 889442 450061 79794 -920174 585404 -623254 -71430 130437 -461063 605573 823308 -791536 -538310 776346 -960703 189856 -985507
-223806 -111929 -649442 -428321 190747 429463 67723 -772347 67772 -912044 -940072 842770 798501 -399215 -738713 -752416 379665 -255378 525030 978972
-980930 -159742 -413347 -490124 211793 142376 699846 -315661 627888 641433 -508522 -478689 377196 -724867 -59140 924757 -689102 -899240 -240194 -736487
-544965 -660822 297480 -111366 261311 -665940 -830904 -948980 121218 -519671 -877732 -303283 -19901 582151 -7489 -326524 -338109 -938047 95059 -622095
986251 821055 -490101 -111043 274093 -450736 -24968 -638822 45775 93989 -533623 -624797 -550366 651803 -356901 619109 -891983 140298 -531077 -337472
-820079 961347 -715092 111444 987009 -542547 485969 8507 952209 -697881 842340 -948616 -780986 861765 -39927 -141122 -570763 -235686 139462 649578
-408681 760746 950493 79817 -584409 -203309 -198459 -870345 -375460 90963 -828319 -961929 -232528 -747170 -9260 -138802 689751 -256564 323590 -579952
-265309 834350 992418 127997 -582438 -94064 889345 -5621 618896 -691884 919810 -355493 908899 -823650 732548 502156 -573689 383877 645305 295591
725656 16787 -588232 -169247 -978807 -188695 -334615 498623 313003 -848107 335758 -605894 590795 501459 97373 -800477 579302 -529915 -596361 873739
971419 541478 843439 596269 -422319 356405 822818 -379899 474900 -750290 949398 324416 -116444 848755 427343 423964 -525760 573466 587543 -694527
94942 -173847 -332359 809014 84070 -111202 43894 -602389 -524223 212990 80483 238293 -22337 235384 155218 -508430 -796767 381455 -982586 473506
982044 89683 533862 30911 -975586 -120644 -270395 -592025 -442379 -429921 -910451 81720 -282679 18081 -817913 715745 -97120 190651 -463145 -599682
362386 494228 791083 -985725 -103991 662694 -507099 905810 -513000 -710379 732369 -580504 -720246 -612484 711878 -62449 -308405 -491709 199568 53901
772620 159665 -116793 -315836 -256871 701040 272039 536297 665719 577083 956578 653290 -521706 -20983 -903064 49830 817594 -372333 -543610 -471204
159023 67716 895349 979189 -78029 60627 -773559 -275015 -455630 860050 -266386 -556506 372801 -470869 174526 630533 260242 -669967 -83969 -942417
822624 705667 263979 -910175 -478019 -479333 -880854 -14862 152613 -397628 -596876 -998321 -463202 341103 635056 645165 391343 -654625 546529 -161131
-697443 203694 575807 449272 -766557 319929 149085 975698 -475272 326214 -591331 22801 -126536 -734382 -101173 -578586 375230 776442 26019 -336961
883064 611021 -525944 -226979 -894078 10351 -913487 -734778 -453753 417408 485282 -953107 163258 890136 806351 -271468 -348257 685343 -854789 -655704
-136244 304496 -271107 -837482 678060 877579 143771 680976 785321 691468 157950 761847 314647 516048 385327 -525214 921438 332383 983542 -633166
470545 -244508 -558984 297592 77869 266752 445524 -552192 309049 80325 605657 -672432 -52296 533434 825687 208569 876807 589054 -824657 794842
-811790 137999 622542 79937 -612241 582494 458566 -45097 -765320 -36592 -905866 45083 752321 24172 970042 -926349 836012 -67321 -675670 -663766
713430 -54088 -524725 368996 -481505 9644 -63994 591197 -457915 474885 934996 -810538 490105 671608 95427 -34799 738451 649091 729897 -757928
311413 -703817 -750297 632541 915732 783117 482298 3292 -828082 135698 248274 346978 725556 570483 86928 -124335 -974571 -357831 -398387 316202
-367049 -947980 853532 661362 556494 388590 957179 992703 394165 488766 -63974 380949 -940341 548781 244256 -896375 -269212 -811640 -376539 -793988
-590307 556252 -562337 -227242 646331 982882 -989327 -561551 -728255 950836 -130624 564481 -983365 211592 -55929 521938 -349291 576159 -103679 -607840
-150518 155249 19953 35622 -12666 -684714 742507 275962 -154827 -18228 37283 372543 -824688 643586 471542 -983925 -331565 -712799 667328 988647
108060 -130532 -872465 -10472 517485 -432376 -825042 -979930 -31187 -807799 -663694 98609 916796 121961 165674 603833 322993 895513 574823 762849
-798261 367707 -892869 -51584 -849613 897695 -132678 -176497 -47797 -163867 262313 294998 -227180 656773 802214 -113236 -449101 -176798 -103596 -538871
-239287 -91212 -44743 566143 -999828 865812 -509112 577555 211195 5191 -675978 -458861 582283 -677552 945560 222059 -152077 -884223 67487 -169133
-422176 -824677 737094 -495040 -587679 -882508 -595633 -475224 -599939 -898529 143741 -297697 -677210 785445 255595 -523174 -520332 453264 27886 -613392
-600612 542616 137008 -509019 -153180 126531 990450 -543882 621540 -544733 534019 -629532 -736922 858226 -763595 -11198 -55930 -315349 163152 1784
-493484 679061 -873894 -517515 743418 343609 -496859 -963121 -907419 974543 842376 -850309 -921763 870490 -221065 -565179 719404 637062 -393002 298185
205942 -56254 572829 655963 933531 -393051 -748370 591113 -961214 137201 530445 533064 -294870 -538207 -617207 -909772 958417 136823 -416172 937961
321633 675864 -40837 -258160 -782725 -383429 -230394 346952 629947 426528 34069 -919434 -255124 920347 -716335 58110 -84393 -425040 -170537 -874999
-282663 796366 -722740 59060 -795657 -118309 746289 -212611 151476 97389 -32817 853879 511204 -66470 -264860 492307 570455 -737048 -624257 435802
688702 461697 -32155 194375 -79364 460414 439160 -240797 746713 117816 -153262 130108 -714364 -434505 700112 -451815 -382704 -111002 937674 -610376
489063 551972 -498350 518921 -48947 420110 653454 864021 976593 -692911 -471996 -319127 -344882 377393 860460 38913 -766415 -716824 371515 -165854
888260 -592774 -573225 702605 -626600 517103 -129282 742825 -191652 366144 -723023 573439 -580813 -200097 -419758 884669 179683 -509618 -845191 125668
-595725 -693564 -166533 279528 -58415 -860540 -408791 -475453 -835711 495884 -219506 -714372 -949160 -186553 940722 -315033 -305364 918578 -341575 303076
365211 858773 -510996 119148 408350 164148 268423 -185524 946387 819279 -233843 -571028 805177 -556989 -753973 542049 -291602 -10155 391947 687203
863896 24273 -588288 -495148 -772588 -841673 664648 393144 884336 -955856 345761 -987327 -266125 -62105 -923015 -206872 771550 -906575 518782 -430003
-439494 251150 -501326 -526526 -53265 -910782 442119 999171 -960309 482014 927163 -888129 214057 156580 -934944 -183742 -357407 -6595 -751302 525353
180224 -107567 678169 -346637 -375588 -488405 412107 -409060 502138 294126 -391392 -30924 988860 222837 100102 -414966 -640578 742539 781645 108312
547650 -959305 183243 338967 -112986 110427 288490 412507 -406298 -674235 912207 -875450 -416828 371173 949441 -231123 881914 -45445 214990 815965
-369295 383118 731530 819032 -972364 377942 298573 -338245 36897 229152 -38504 195141 586442 71238 -850222 -230006 -854046 801179 511872 322658
-304579 493198 651698 -606400 952981 450240 122587 -489870 -965577 820298 -992668 147677 -777169 -772921 590479 -456922 -894271 366690 603948 -377505
535646 -293936 -324050 991718 34608 189255 894426 53581 -425170 -542563 359666 482629 701573 -665492 -742840 23836 449658 781341 -293376 -892474
421427 76381 -8223 384577 -527959 -547162 792314 348218 -956006 931912 813167 -781811 749086 -901851 -696957 648115 312113 -917326 725392 17391
779990 602274 -387365 370391 586116 798587 -847764 -515335 -442489 -960861 754981 -876418 -100012 913460 415738 8217 337516 198161 889837 -144690
581400 436106 315308 365228 975824 443719 -210152 409477 820255 437454 -48370 783535 -8915 -284367 -178614 -641847 -332963 597158 142283 -237264
-913497 68615 581428 -965433 93557 -345651 163177 -154169 -678030 134204 128905 189387 879071 120184 -548799 420944 -120891 145552 34321 -72208
545006 349304 717560 587044 -255199 766937 355353 -194431 -566499 -142284 740952 -424617 -94658 48847 648496 36351 -218829 -97973 -386254 -775113
-146247 499226 -753658 51662 406588 -37941 -106647 64037 62436 728016 -379929 -843278 959679 -979287 -30853 86789 -373369 -486430 -214531 776424
-903859 74530 -99643 472862 227386 998359 677002 956660 -458055 -606008 -526293 788378 379707 916142 978821 -292585 335775 -388532 418011 -660689
148590 180943 337886 145259 -323425 -403464 845293 -631561 736326 376330 -990506 -745543 423728 -86553 -552771 589371 5888 -913313 -718899 807604
75024 -442236 203431 701492 952265 -852410 18914 804615 462242 756625 568452 581413 -485434 -465648 -920785 -286967 434333 179367 -444963 79622
998979 -581501 240459 -103556 -189222 -961972 679900 202040 -400264 741281 339968 -409211 719422 232885 -275894 -681113 159632 531547 -193075 -690330
-964991 34282 553836 836233 -857791 684884 777107 -990215 -674416 -609016 100162 319814 27548 409474 -900715 996187 -999178 -343280 -199338 -630393
432193 764261 -499567 -823325 -785569 169160 -42065 -704688 -613743 -575313 -547176 -927616 -981932 527307 75351 -368883 -301334 412797 963173 -558441
-497323 -227868 -920993 -225675 -994346 -571353 -521104 -676318 256457 -91981 647066 803357 355790 644754 665247 -604809 -838294 -995128 754280 853246
-258037 940005 -484611 -327451 358211 -535522 371309 -243469 506155 596176 303225 11117 353678 170909 173342 40825 -738184 -141143 845257 -380197
-889311 -268114 -916978 -857995 -546928 23379 -230258 -206985 -703448 748612 -854736 -674846 750763 -145444 -763210 -552639 38670 21359 756075 282018
-777236 653349 -774803 255820 -449239 -888271 122906 770188 -216997 -257650 611000 932705 -896664 -649181 491155 -886730 498531 903425 -53887 152065
-282452 643685 549327 -805561 -906670 -705842 -682574 304915 -106083 -912510 575292 -65277 832933 740126 -394863 971353 -907697 19428 980085 -609094
-404440 -848600 160815 -404964 294839 688955 -521071 -979337 726429 -791920 242038 906312 44227 717851 -797362 797902 -897489 708916 -526175 919827
841158 -833812 8285 -823006 -531567 610734 444032 -774489 357531 211775 144041 760044 628238 -572708 -964155 325202 314990 122809 382573 -451714
-261747 -347088 597191 827254 -296135 260371 560324 229219 -716103 258074 -909600 110560 -991022 -804178 -41870 -734620 -669831 -988997 2442 491948
505114 -652382 -119826 -136123 605840 -877633 -782982 59402 709594 445927 438151 -296029 -628640 -842787 872783 -520307 -872742 -83162 -904931 640114
-55396 137955 -554673 511548 704841 920216 226030 -103480 -295249 -890124 -323826 933945 441983 -125642 -917446 398953 -888931 -489248 -711343 -411347
47319 -270416 -372644 -328576 505469 5595 -182818 -767283 332192 694731 -649410 610821 -386734 882379 -830422 -842381 823877 668780 735532 845756
56987 309832 -922834 30761 931131 421353 484694 -77427 -922645 993243 935044 966507 -74322 271440 -868928 -131483 684824 14536 -110276 -729958
521294 535419 -938954 -561885 -322239 1196 310446 232382 991108 -814845 989325 857796 -100388 916502 -510733 857574 881347 -824371 -612879 193569
822317 523088 714712 -601805 -362796 -462651 217726 635531 858570 -498784 130497 -393378 368878 289250 -979379 224149 16422 569789 -28489 -560453
-604503 -415725 -937935 314367 478256 -891487 348925 -644219 983759 -534572 384786 645034 590163 349553 -27151 540715 -263580 437017 934938 -202961
-683074 908003 -974892 -971034 -502217 751172 -483875 819998 964228 -191760 -285389 604287 -396280 605080 -10187 -303259 -684301 -933206 223763 -780690
-592681 109355 -127516 716208 96894 -455760 912395 558947 551572 -724940 -981051 852236 67487 229176 786017 656166 -889574 -38819 -475752 -868018
187318 222570 -709834 644269 577135 -54058 -549967 878371 -505037 996723 -790380 -287894 778572 867608 530158 794069 653716 738244 -23783 902311
-337629 542117 -642082 777428 566131 671139 958826 -887048 -49821 207751 -205429 -56568 -579998 399761 -45305 160108 -791276 -554820 816078 -835824
-392752 -727140 -93534 -789799 -703578 -226363 -402780 992852 845401 -777089 117817 -427234 760871 -77806 -495686 -71716 37863 -47080 479468 374139
-810717 443728 -393097 201310 166899 -379665 -218521 -780912 -954662 89420 -96880 -626197 949710 -671949 441785 -176957 -542411 228250 -288397 -560744
-907550 174751 533024 -558371 393816 3205 -908175 673804 475562 151001 -101743 945841 619513 -913652 -401530 805243 674761 -56649 -527872 455220
-989792 -341591 69125 -887278 265171 -93283 87613 -596226 -333242 159588 -10865 -262587 -80121 -222195 496347 -783216 -239873 -322142 -698254 63590
516334 -363115 -76965 -891257 -338893 123172 -634024 145121 64232 -14943 767442 -280273 -520848 229995 659065 631351 672425 939434 153536 -40620
-168790 23584 10296 514203 334394 576325 -13381 202470 -880308 -480523 119939 -913500 -276364 89864 30776 -606802 697121 80763 539779 -631253
-757529 348873 813825 352719 371583 -781879 -153572 -619860 -290919 -428699 -569430 559174 -185167 375626 726395 806620 580528 154607 -463349 -956785
-338696 803203 39811 543607 -836795 -594421 540500 429275 486072 149672 -182906 293678 292230 222878 -382786 669772 325764 488117 258611 785846
-992772 -324590 640110 90407 850334 -733388 -797272 -758714 534616 -229139 -46216 -656036 12659 -548339 404421 -212703 -285847 616100 717224 -811632
743144 489408 822378 370569 734639 -529700 107292 -86964 322955 -893510 -89525 406918 -51478 479802 -674665 524062 -947003 329671 445355 72838
143397 -529385 -418729 -501042 -145342 201047 808035 -323243 -768310 -936701 -837332 116587 -449871 655834 211354 8469 -297922 -569094 872502 -88586
-919310 -715116 -463660 -460210 384902 24116 -941704 -695591 81683 -943565 -871515 70677 695782 125324 -665791 -162285 -683169 -655795 102496 440970
-265060 707155 -124225 -894409 426557 436919 -179770 232267 -782100 -27758 -31762 -223903 -934646 -538392 -98244 -508427 -548627 607474 4239 24862
-297153 -101164 -345495 -89786 -112945 -451063 323196 -954291 520198 -419880 144022 -212329 964514 319349 -92917 -447366 491506 -223881 352247 -369043
-431268 -167570 829811 -884497 368884 194218 402190 -78641 310543 4679 417445 659683 -691175 689098 -305979 879131 -926758 139079 -382260 790919
863234 -626253 -716029 685643 710678 -370452 567847 -264541 288891 939633 589746 -783932 154011 -84281 -907838 -4845 -322127 -500501 -260183 -356386
-34894 -57670 35831 709801 858356 -561977 746712 -292683 -406534 -579034 -52288 -656984 564943 -977236 480110 -692179 -318014 498700 48786 -790680
-103591 -765937 -222415 -410597 118081 174420 648131 -396915 509415 512682 -487667 955645 844675 486355 633659 -14000 939286 22508 398587 104215
-364951 -312162 -695179 448578 -533698 317586 -856956 754696 -317755 -250870 896073 890747 -707878 -363649 409988 540428 -550051 446577 88239 565349
584248 200472 531586 856236 160869 -384393 958106 -160062 223814 961851 235330 177793 433836 469374 688761 -367979 -518393 544024 -803664 813808
989375 573375 -1140 439749 702231 -413851 735686 -973371 839600 -278247 -760657 806601 131903 569998 905962 -57070 -750191 574757 -777821 -556668
46651 576498 -374246 -649115 724267 -806259 900303 384200 -32184 903734 -15295 668032 729646 -460617 483058 -8674 -124918 -463300 -854384 948939
891831 -748867 -313506 -400949 972794 913399 446980 898082 96896 -764694 427881 -420122 -779668 -780522 -422697 100333 -969768 7630 526537 572608
485821 799934 901265 -321411 -905520 8706 518434 -728158 92546 325854 327969 294178 -509310 -18554 -309911 971003 81975 -837393 -18820 -18832
-670052 76293 -107859 176395 -386089 -294888 -528769 842881 -722656 -834939 19559 -313291 -869845 794403 93141 743403 -450911 -857414 -384139 808713
-67811 216145 -97295 806051 155442 -876939 -942043 614042 702107 -89871 51749 798369 -889484 -873860 -32213 -844356 -159659 -306912 -632333 -677897
392638 -529888 -923953 -544296 409915 876774 529646 360378 -812571 -691735 -251764 147901 -744474 -666006 -49588 550387 -96574 891215 -289546 -140416
411101 -996326 332251 504899 149780 -456044 -199190 77657 264977 418217 -724619 443733 703575 548700 -608438 460453 -289445 952544 110832 839534
884962 926879 246853 529372 -355511 783764 -42109 525618 855517 305185 -758813 728935 308564 -574332 -257051 12298 597870 462581 361898 -986051
650625 -767625 -837648 623600 78537 -495582 -736672 -653595 -742422 631160 -399404 -649576 737961 835810 -88615 351532 298373 -953805 -474329 389356
-515618 -263542 -70615 -923566 -658049 555384 552375 778043 78867 689 -973791 11925 -293614 -612797 -279495 -995533 -189634 607436 -568035 -418508
-707135 243941 -205856 612307 -856801 -23336 37486 955021 965460 -978062 282386 -333821 -864141 -455696 756576 -913842 86979 841609 239796 281821
303962 -55510 -830073 -655397 -518464 688248 613025 -531687 343111 351893 -517146 522596 12368 399575 -554247 -872322 762300 -511888 -141218 -827328
', '48 3673
', 10, 0, 147683, 8),
(1002, 10, 'stress', '20000 1000000000
981242 -986643 -755307 55898 -315978 -335366 -533092 815151 -9286 411378 -242753 31580 995693 -841253 -603047 -692655 492141 -142273 -523177 117911
245835 -511886 540173 499419 819430 517258 592472 538766 580430 313016 581915 -928557 -76722 -213791 -308372 -509268 393633 610791 -745258 -666881
378758 -811096 774409 212603 365036 585177 862335 -359728 734476 695002 140582 -685552 -581111 865044 460218 596703 -805643 -38347 -367169 832249
400532 937711 396761 -229533 -634997 -643233 -970314 37769 508675 659626 -703748 -24767 927727 68212 966097 344825 -81365 -543679 -405799 135781
-290442 -430007 -213847 889200 -656868 625383 352679 -481490 -373279 -331026 -653420 697981 -285683 -179 338551 902609 378910 -498592 552057 -436845
606119 -360090 -2238 934679 -317546 681474 -866317 181961 824289 -924429 404465 -274315 832082 271967 582849 -720197 -938033 -132366 605448 230253
-550562 -40098 442378 668079 15021 974110 -886511 -893431 412919 -318905 -697871 989996 277419 581711 696332 -826968 -291370 -232403 -130439 32851
-356944 877502 610208 589325 -879994 -665679 576763 130369 677715 -47337 21028 237121 -689011 801048 -54577 24218 -341002 239531 -448515 55414
135755 579982 -691153 -487558 -710605 883894 987792 -562488 -743654 456925 770804 -890591 -932347 -121411 180404 89206 879115 573589 181788 293281
-972004 -884089 432872 111850 -806373 -151833 328486 -422637 93794 702378 -493626 -910460 -532718 -424723 -337728 -586988 -343467 -439275 -870834 -176553
409324 102005 667921 985976 -816636 -603309 125404 192412 686853 778568 -161326 239016 876363 -185361 393247 560281 421208 -370222 758348 988108
-41090 -171611 -13930 -905861 938726 -874480 -936445 -772510 -661450 613606 572865 -527884 570966 -434269 346592 255146 -353022 -172101 682593 -51936
-82735 640686 213977 -206603 803753 -948113 428962 -979669 -467640 303965 -108611 -840001 236036 -108790 -850836 985770 49027 285516 366130 -940597
-838592 542111 236265 358427 512573 851572 -276238 -625868 -829115 -737095 -416610 616586 291018 -501843 -622959 -652378 302030 576434 -839855 -666176
-740169 -640609 -276586 -157893 -856691 -57911 708286 -831593 -662786 89030 393507 -673124 904421 -763039 410727 143068 -491859 157433 949370 594227
981213 -54046 538875 166122 168059 -677241 227224 968145 870091 -933020 -757745 572941 130227 -698871 -805170 -746090 357846 -186896 665251 155990
375766 -488502 246085 -122301 487371 -762097 -715444 513767 294839 -833563 968310 549240 -700441 894348 868753 56492 639301 -684437 -705508 -236549
961549 403477 -466051 -719971 -867161 338894 638771 704152 -252232 970957 827850 537089 -922656 -490961 -56183 -530517 -743244 -737372 323422 551534
863813 -110376 -97902 -190455 -501137 -934902 631390 228771 -737080 -42524 123175 -373201 -300256 465447 933568 841501 -306332 -968414 281479 209922
-970594 -54906 326153 828601 -441688 863671 -201160 -814682 327958 505759 -128190 -804174 509055 739693 -126246 470397 550949 909043 -272592 -659504
-76634 -791568 -591093 876656 -434749 -729625 -54665 -767000 395890 -590556 52837 -827754 971927 775393 -543370 570111 -199173 -681951 354861 150780
-481554 432480 545315 -688862 402522 -286038 821498 909974 92467 586038 113592 777711 -392200 228594 -312646 45512 -29761 210893 -338172 -986118
-84063 -259602 457093 -123071 599510 436428 207773 120029 407489 600216 561950 -889949 -564971 475938 -536172 672536 843967 -580167 -238153 -598756
-368807 164026 -683476 394347 820998 -129218 -976953 -591369 406213 -551097 -249346 790496 89609 591859 78578 624164 -775515 -609485 -634033 -546121
889745 587861 -379562 684467 808338 137630 -960037 -549996 -16120 6063 -942041 -586183 909392 587501 129093 -255191 -458255 5125 732751 -967054
-204344 892320 540170 -980863 258618 591991 -75494 720780 -259701 -576886 -561859 -452046 286304 -608794 991905 777219 297708 283406 284842 984787
-691145 384345 828643 -105124 934121 766671 871182 498114 580452 -543089 777091 -94338 -579097 971551 898816 -204184 -553052 -590086 -791583 521665
-146135 263184 134422 -52764 -179414 -449752 475097 837273 -959753 -67426 -91322 705565 550165 117334 -21075 -529537 -133996 -665717 362881 911115
-189006 -890420 222101 887454 -639370 555498 251951 346473 -779131 59324 -979885 880243 156181 665376 449411 858386 -363890 -91506 949766 597190
792716 779636 -649555 -461275 857831 460925 691446 -692898 -402117 202005 124858 736215 70965 -717099 48006 397998 492287 -234648 -492227 836756
-339118 911654 -643982 -313533 -329417 -231511 -254182 97414 822133 420254 158567 -823050 -143718 -672050 -91689 766494 350725 -197577 744422 561017
812728 -461885 -149439 -707356 -729313 454126 -613313 -465579 904022 67260 -60074 727429 -97044 561925 -303384 162495 -454548 -781432 963875 282970
-900371 -985774 442128 935119 980566 871563 440263 -349047 -990342 -922490 -389049 926541 693534 -571861 447546 373159 -531857 -654693 655823 533018
414922 -985437 275703 405800 183229 76678 -512757 -184517 175642 -722954 338268 219477 276579 691225 -262297 824714 -757485 -523006 -124505 -557387
174824 180070 -687022 189380 842244 -328186 67188 -712270 -34914 -951171 606107 -138255 114240 -61493 320884 -89506 456572 -803092 -771744 753775
85863 -499710 910262 -149810 -488071 190371 -313487 -323929 473773 -670867 589530 231825 -453190 -410100 -609843 69576 671784 -403476 328968 272305
202913 658159 -932419 448472 996035 -264489 840889 -644495 -100403 140406 437074 895107 818436 901580 196058 -980438 -445372 -333149 849754 741956
-555362 914475 902102 -323902 182665 -297338 -75957 -320114 -368571 -606193 -788051 -677257 129566 538387 -415875 792794 782594 -777818 213407 -299577
76379 -710512 -25510 -118946 636805 -966545 158400 -935225 -283927 659934 -802771 637405 913276 -591519 -212034 740885 924562 -599608 -951780 -294456
28412 608148 766447 -665725 383101 987183 -825028 71753 277249 -408710 -534027 -343178 -932579 -538769 984535 -578349 -740873 -188651 -532125 386035
409608 470578 -64720 909814 503709 979067 16803 371726 -938181 -27172 93062 -254438 755475 -353510 432544 -885424 -755548 -535366 688509 -523000
-533624 716566 -313444 -48885 -731077 -647661 -963069 -420868 -104381 -254476 346581 -491926 400492 -867801 484013 825495 468880 769753 767538 -36926
-777681 -946256 227068 -664353 -917709 -673740 -691274 -770644 39560 217145 720759 212894 595136 180145 -290546 -282452 37602 137144 168506 -117615
-476271 -114266 -699589 -811423 129859 -530870 -622914 -134369 -569343 -11926 -305119 523866 663625 -726245 -960705 -377696 574072 -988450 -427446 -151752
-231508 -717838 -523439 185176 -649530 -921174 66754 124331 811503 644503 177176 320040 397566 -593064 -743324 488019 -187419 -784609 -276840 -522014
161981 -134581 274529 -552324 569638 477682 54232 -917786 -327790 -985769 750753 -328814 -912131 220134 906087 -899346 -479989 -835788 681837 825905
353393 -713970 87464 -881170 -971851 777329 -388769 -539375 426906 962352 -411756 194570 -290845 -860957 -852254 -833736 491453 555265 -204215 -877145
194171 -231483 -156453 679207 680597 -384383 674879 -577462 -888215 -732153 -751252 414857 -639092 -822159 -280508 306675 -250688 -476034 932503 -600181
432750 -161349 72511 765230 -26447 -758521 -693508 -877955 694832 -745728 188471 378443 653477 270703 963963 825675 180889 380860 -391368 -108880
-532482 38322 820772 -492217 -849344 80783 393603 -528942 218833 -923923 -959762 -839463 -912774 913200 534844 -867285 705768 325680 54717 -260414
723269 -573490 17168 -161254 667582 146631 564768 -390179 885077 234650 -701202 -570916 -324760 682545 -586709 -304958 408372 -138421 -428432 -565078
-742711 -44822 -678936 -409104 -655270 748803 -91796 957721 886611 -364441 773681 225968 670750 -604516 758458 -396072 -937654 255411 681871 18071
66131 67351 -972075 -86766 227664 -264462 985352 -958163 -916797 869665 -637937 263444 -120626 366159 -224972 -677675 -87602 -513637 -16414 358683
91918 445732 753596 841548 478640 267816 -825112 -860577 -853704 -705347 249006 -86383 103357 502318 -571634 77799 -278286 422459 765833 755114
489416 89288 -5188 -83390 854552 -681474 -531134 651486 -291509 -313738 392874 -600606 877820 -887241 -929127 128348 523398 -455040 431137 -829516
268771 23730 -980687 859265 -177207 -983434 -219019 231025 155710 189994 -592513 548184 -565417 773382 -656770 -94361 388107 118565 511192 -152481
484451 223111 -988690 128280 -623896 -468107 -535276 -840086 706560 381098 -994491 713707 556586 937821 -358152 -651584 -94995 10964 -561361 -347341
-501122 -231318 862482 967320 -14551 -635556 515889 889065 -812007 949511 780677 974345 -287940 -392995 -659301 -852735 38966 -303991 -635784 201009
-524583 198027 938945 -302201 -995320 766985 -151489 -145915 -345829 -131061 966820 -308293 -966997 -546542 262931 -128212 -966472 -548331 -502934 -117186
-670566 -643271 -766143 -107965 -434767 -829790 229080 -891485 -643077 -35133 804632 -592893 909480 -839938 802091 677639 360248 -19464 -65344 -206482
-681216 -730032 394867 565888 295821 613823 -915798 -16373 557927 91982 955339 44516 331278 478901 -757361 167153 852478 539063 -822886 -892602
-126411 -211255 -156724 -724435 -103795 -23801 452364 -30480 601231 -10364 -217095 883255 -202258 459998 -70317 92847 -917513 -841770 399934 -892689
365344 822673 837287 859091 -138215 -276677 801388 -624818 284433 528556 840999 -155352 94908 270472 -822965 -353618 -891433 479978 652641 -736920
291983 448495 -358952 315092 365977 127087 -74592 -944160 -116042 -148225 -327092 -468529 986265 789404 -208818 990232 -278298 91696 554957 783782
-587357 -980037 -955598 -906856 -210522 789419 -438145 359044 63831 -436137 409395 817651 -456809 892634 -193700 914555 352594 140505 -478957 539289
-120689 47178 554631 -759573 884823 -230527 5319 -111982 -155312 -979453 -179440 -54068 -211429 553980 7121 357766 863946 379177 594225 -527026
-173924 -773623 -879649 704767 -455150 -809758 -230311 466535 -126836 715488 726098 972789 -598586 -157581 -374678 248086 569631 982219 52425 185044
-744874 506902 -609905 510547 115376 -886951 809742 -380668 -250930 -665975 -113617 -285690 -350148 806565 -768553 580064 361092 841685 -919366 -724994
308384 -133276 -894593 -155914 -930030 -457706 605293 93183 847704 -744895 -301710 -775849 -366664 494178 731108 -841770 -500820 729599 -192794 331757
67383 -485918 -422456 -988200 426288 -973968 201696 -788766 -708385 -609216 231636 673317 -700064 -328807 -521260 350911 -419794 90296 478404 643810
701909 -843034 172999 -864177 -685438 567845 -226368 108214 -747046 253258 -993529 645235 -520865 439570 -83954 -304048 978127 48977 -684617 -838197
557946 -45551 891252 414496 -994805 -574572 -86180 -352485 502911 845210 613524 607729 690873 -161693 -899785 58309 -500046 533140 28658 -505688
-603306 -803199 127973 119571 558863 -191411 -703738 172313 -726107 -706181 -393306 -57033 380620 256525 -854159 -340154 723323 -825861 490983 -710013
-806375 -55210 501036 -33932 734519 -281563 131180 849330 940827 -396793 -722714 -221403 -249976 -504215 280901 731431 729966 804699 -413230 -564019
-42738 -553185 -852017 186646 -64616 331876 451284 136330 101980 878671 175411 -133430 -766219 -865328 755328 128575 695175 451247 -367463 -399894
-582525 -952143 -122900 979407 842006 610103 160807 -833195 -831805 -853108 171892 -236249 501647 -560104 479139 958837 -99081 680674 -63259 979812
562702 -200421 977435 -691586 510919 944907 -921841 -839533 -986805 988925 -986481 -926326 -125949 -519558 -564108 -141548 -317503 916642 -370879 -715697
-252817 932809 -599787 -60232 167427 -814386 -816737 -237478 123919 -885915 -361931 -372290 -559265 68433 660733 -989139 -616350 761002 790914 962359
-425513 -704688 -974459 -595270 -22689 508051 -782048 -587798 -194174 867612 -533669 311324 298101 301026 780266 539275 -877044 -2648 939939 -969401
890103 256793 -948700 894757 -347029 636547 -333672 -104525 -435926 -531906 -360678 -727421 38104 799031 -335733 614606 -857667 -855797 -236853 -8903
849569 -583772 633672 344482 488124 461394 427482 -971573 -925105 153584 770568 -696283 -430422 334742 767037 -16149 86962 -846564 764170 -271817
64385 273492 803297 783622 693167 -645185 -328678 -589088 -463148 108817 420058 286155 342297 -252113 -575023 -454495 -653830 -166853 447027 -335530
-428969 785406 189216 69970 -755812 -456598 571075 -233144 -125232 52702 496841 747107 -585716 236014 612200 199156 663635 241512 -535167 -977899
-452570 681360 -667166 335657 -471455 -82396 384692 884159 -208222 952160 -326014 -883156 61894 -379347 -166558 -440080 83879 489398 291141 962095
596289 -218373 -894922 -728473 587693 363938 522197 -667250 -727833 399213 -459277 -904799 855718 -189092 78107 -407586 -289492 -471834 -870241 242524
703798 -719704 -267681 -115000 -894238 -451110 -943197 618763 734013 379293 -196988 287775 -951921 -895726 -415035 452369 -665905 -889164 -355658 -673939
946333 274939 1191 426209 -73603 -35975 683197 16037 791925 -892913 -367209 331146 294395 328287 469672 579997 12647 -19397 743386 -267157
63435 617543 850335 -50363 -374436 -867498 810836 245844 -613665 944843 989896 617955 280647 -46905 112455 97067 -759672 -324739 -297990 -684105
560028 856812 -151762 -590318 -136241 957871 16031 801998 182391 -286494 170650 -194635 153125 -334492 331743 -276465 -998897 -280630 -470770 578393
-466525 890574 83018 -53520 140206 934932 350685 445271 782109 -277303 -987173 191380 837285 -818258 455024 453802 506072 804532 -665734 -825316
169082 -966806 787648 -720230 506616 -579492 580409 172009 -889498 -4063 858030 -663073 -256040 458095 -747615 673442 -907445 924251 626677 746921
-881977 -92489 -167562 489929 560320 -138528 584868 992469 51073 -246325 660156 -202297 -71399 -41687 831643 -7898 -334676 -780939 -883293 743553
154075 145261 -356524 -439792 -57699 169332 -161697 627059 -557819 -454756 176561 950863 -850624 -291965 -705959 549910 98067 225129 -722479 85141
-385199 -659670 226239 -650727 270344 -168697 606302 309034 856760 -845054 31371 446178 -688837 983351 -619479 -856880 997795 -560953 -442669 727792
-160805 661070 -558695 -159366 -524198 434693 -524210 -554153 408920 15111 828116 797158 199648 161486 343034 663142 -246497 45117 622940 -880916
71841 -38878 -370689 20753 -625580 -878399 998749 -804014 -458436 -461891 74940 82319 -502736 840330 -812072 -54384 -586209 487568 -685829 583879
-817248 -561520 -982023 -680050 -352598 -711919 -793421 -164392 -855222 -293464 807994 189792 -643295 -42521 466961 -845919 -813200 -975496 -105077 -879117
-977426 794765 856327 -134148 87501 -184566 380522 991271 420182 143297 -19374 332120 301132 878234 -357566 -244252 417142 -256880 453654 -281761
-965098 636934 -445501 -537529 -771293 300178 -117000 114972 894088 -186487 375946 -516459 287887 -196544 108131 -202411 -326960 -351920 -938883 786199
79983 951217 -240641 -61192 -97846 556718 111559 425174 667947 -326830 -664244 327304 -10020 794311 -732707 171405 -502598 377707 -390667 -74167
-938587 -980000 -797513 102253 251128 -519678 518801 646105 889705 -62944 278368 -916666 615234 -406073 499589 -788980 297556 489861 320465 -179883
122995 -34497 597824 -362902 -259874 460833 -16217 -853163 -578750 789340 648814 667233 -237350 675352 -197704 -168784 -413786 126351 836489 877766
-283274 -583345 -905354 239498 36611 57683 -8684 -610365 -803074 -19311 286090 -73737 -70817 969107 -759138 -83817 387682 -193635 -81597 -695488
876076 -608036 -85921 -143811 -422222 -822174 -587366 220523 171020 -483020 339468 252297 -302169 -604349 -199110 -692821 325155 186118 851770 584448
484826 175198 -955381 369063 -554396 477674 653476 572363 940571 828198 300071 190789 253379 280965 628983 867182 -700113 -547126 -521671 619907
930064 -997504 -821052 -104961 -319932 -203761 589218 -998755 -407639 -531853 719639 -723451 198437 -163744 -491959 -768804 -205861 969640 380092 922266
-838492 -431887 912046 -701946 -985939 -965127 -441141 376955 198455 847550 -220281 -908909 380561 -851293 -230432 720057 611727 677520 334123 -196086
847704 479939 -105831 111167 -508951 -728917 282134 -847211 -732291 -174585 210794 395557 -551463 639146 -461176 -83620 760886 716932 -990615 -723978
-238773 -13057 -288651 313980 60399 140209 921072 145820 -469934 740315 -346761 -545551 -189921 36996 413010 -904692 919331 -89395 936740 -301676
-904735 792414 -507160 -573551 699953 462612 435190 774789 -961543 817291 177445 -195261 228502 531084 -804725 -174128 -96053 -703754 -833606 -698939
178915 895429 690805 -87168 553269 770843 -356822 658830 -784409 -107172 -985936 33043 -160412 -400359 701035 984617 -578497 -278252 469782 597296
656449 492453 -762633 -608498 311298 -98000 946824 -531498 -613739 594680 821751 854222 -682007 721025 191362 -242129 848732 -430831 490785 -557931
428468 -759266 662056 366508 660600 979251 -499571 -816957 939128 557097 616521 775317 793317 858230 126471 -422190 -255697 -508492 558388 766776
-557053 846597 -506018 -584204 -948295 -888797 580344 -227872 -826915 202830 -135502 189959 720154 -685661 -552111 909726 -838749 -616775 -538504 -728771
-598739 -349345 -307915 -213626 -785283 572719 -237975 430901 -724932 117654 -218812 -661238 219552 -676934 -29155 137366 -671657 -344889 62560 -703887
849228 406511 -879653 924553 920129 -94858 -583312 -735401 -846571 -675392 522587 728099 -586111 31888 659058 510277 170727 -358194 -934 838168
589413 -101367 152665 -45553 263096 668243 -120803 -543829 730342 735527 841648 357122 746809 -523851 460082 -816410 -681468 70437 -222898 830767
543180 926529 329867 -183291 58354 -809802 205093 -975841 515964 328136 672824 -89415 580649 -19310 991871 267122 494816 -9284 378679 828273
-812712 577820 273461 -384115 366737 748690 375206 779073 613648 701019 660809 -90678 -272785 840537 113632 -860781 238949 -955779 -819651 23619
-299447 -811550 717639 -895851 953429 -98377 -837857 75269 -190661 -170176 -124980 -203898 143109 554972 -4232 -378934 754045 -484248 -758075 364279
393687 -893945 -270536 -31931 -553053 -333007 -544135 -851286 926916 -996605 274081 -237657 608240 -890211 684808 66085 336762 77622 -293651 -836688
91737 168776 -729439 -644553 641205 325616 -688622 -573386 818411 -623622 614440 352618 -318731 115776 -598646 48416 -214289 -441977 913348 202711
-318882 95644 343098 189311 -846757 -656481 -441869 806327 -729316 -261736 358288 688578 858157 631226 -757932 737542 -698815 -735021 972324 -725485
-290619 -112213 545643 -380965 -515384 -924181 -432136 -380372 498016 313005 -487940 -742770 182841 -592077 -278422 -731715 -63951 -522686 -624357 455179
-445683 -887767 -163335 493934 559952 344804 517759 54568 -898694 355690 -730624 -19440 630512 963262 -399711 -219086 200179 636984 -156576 -647411
988501 425328 982936 959795 -49586 559762 -486204 299956 200619 -857473 -493867 -613772 886344 -849197 -523423 -466797 884062 -531659 272813 -855015
811591 21435 545488 471734 -845558 -147649 965304 -977014 278415 -338356 289404 767270 512129 900253 -256623 871637 -103398 -24403 503185 144361
-527436 -472195 485289 746118 793666 -263160 538067 -91741 322797 264180 824945 -239318 751805 -726761 -509038 -950982 -90499 -830027 367313 841490
-165016 757291 749105 775881 196179 788011 53743 654316 -25632 197241 507154 688423 -21134 -343285 926432 382243 -111272 -713981 -321748 116428
977910 -851225 -692891 403346 -315722 143589 623493 876993 -148715 211356 279747 856524 504611 999899 929550 -840562 -266635 -281721 109279 -496212
-213776 258274 451477 326784 -856110 346463 -76176 716623 966599 -372586 654822 593181 96047 374361 -443803 -991834 602400 906612 757666 -877510
-276526 -542412 -560103 113722 -490499 -955293 -196726 373341 -683245 -863573 -561819 661207 -950417 757221 -568809 -293525 10572 -515364 -449410 -474969
-895478 -896433 167590 -1824 -884064 -657939 23181 144484 931762 -340737 866143 459674 -857060 715758 -265455 461868 425187 -420925 -108333 -257882
-566049 -436452 -159991 240773 -372233 -976199 927035 -280827 -766920 -898509 -123032 270436 680500 -936397 776738 -185057 590107 -775742 48007 -900147
-560017 826993 277262 -611585 461218 -691707 -940242 940405 432910 607558 857024 -645657 729161 97138 431343 -976916 -422016 -585114 -225481 -739111
-187918 674549 -218885 641122 -409840 -248217 537235 226642 708121 -893917 -302936 899318 -317815 84526 409722 89136 -194855 -493371 382750 64981
-108986 910172 386405 -720410 871302 320887 544156 -718613 331587 -9867 800047 565053 734029 -518255 -725697 962112 -234141 -967862 -847407 -322013
-712383 -683272 621230 34460 726146 521876 -78586 780174 -548904 -441009 -272852 246165 -325178 -287595 75496 513891 859695 -387877 360996 -707684
930663 342398 -508948 333618 -999531 845419 -985597 183203 -249384 484961 904550 -925846 -753034 -941782 143246 -306118 -189133 -518663 1741 -821455
792288 -422084 -566205 -833846 302021 196479 911431 707903 -405235 -785631 201922 145548 945346 -52994 -505860 -99400 -937358 315663 -486752 268963
-47398 305612 -833134 826602 -671273 -323424 410808 -281875 892168 -65877 718610 -112172 21792 -872662 -119725 -158933 299258 -897225 -741721 -152169
238111 -64752 993632 93908 507307 122035 -523542 944771 238659 -464617 -441273 -444469 78128 298236 -704631 735041 -655082 -124501 -363943 -695094
-801211 -41348 -290907 555013 -5545 -149276 -397000 -338565 -204546 123578 195117 646255 87065 -187149 -200328 504373 -891605 510227 -502910 599169
408012 173870 966478 683028 -60241 -2431 859845 -400599 -499293 582535 -395782 351928 -350714 -974858 266076 609902 266319 -206191 503274 768957
-414259 120212 620701 431344 460045 472667 672253 204172 235031 258048 718573 305879 -200752 307612 463008 940885 340483 -259690 953688 -494339
392836 -944330 868564 923617 -188304 -957570 484568 848656 -104404 -863811 123869 558245 528778 -530487 -433912 -762388 25626 -833881 -197819 -170178
531002 660947 -816332 184398 30409 -151711 -276679 847493 438093 818074 -165951 -468675 -595553 599960 871250 -457074 -328450 -116208 -843012 151537
661156 617526 -728702 119497 -852447 905509 571594 -430514 -212766 274617 924117 -18268 275215 -642597 -226873 748612 -232898 -533288 526732 903871
117727 -433444 160222 -17985 -156793 -82311 -447984 -680122 131223 -998509 -63727 -138376 250590 -702140 68486 -959565 45850 -229387 -100723 73376
431712 -149866 -110545 620028 605776 727083 -230883 387982 -38969 -207938 -169510 -26112 294281 613151 -355465 179710 -676261 392140 -854502 781111
-892175 -672095 -486142 -105696 -570425 539037 -414570 -950056 889908 379301 784212 -456430 -306258 833634 -102584 -64746 704716 918166 -419062 -696351
-584420 38116 856169 177426 -243405 -364352 921858 888110 -544569 653678 -123326 233812 -344345 -907468 6581 231997 596135 978900 97706 -308746
838241 682072 -920367 -120802 -505872 806713 -715183 796873 -916717 -618298 385637 638628 -141619 -286484 114078 439877 304461 284849 -886707 -516663
-424160 -552384 114242 460539 -637938 -709612 560543 109142 -672849 -28510 -296695 -645675 -113346 -174703 -83286 408227 669294 -8674 -617993 78040
-655908 774017 392027 436778 -973673 941748 345206 -537608 447490 205595 402352 722954 638160 373229 -311327 785050 798300 -191557 -598044 495088
-751562 -266848 -155634 525918 -844 -664495 -355257 -96601 -302912 443823 -879501 855016 412362 337209 95274 -195495 571215 777337 110578 57830
255897 -6273 -969767 -998569 591126 -620550 -696601 -813304 -431418 -677582 340262 383284 -358514 295433 -400656 -465313 904907 765499 -82910 -6522
727162 -421479 726178 -466686 440816 893502 -833247 -568133 -557508 730416 428669 -969118 -225760 -723444 -908517 -69185 -702613 699534 290494 260136
-970898 -456199 -580385 9330 65987 -505104 -680980 195167 726919 853771 -477727 800393 -96420 -666221 804264 -874936 -448734 353625 731028 -861147
-35629 211679 964313 929187 385593 183041 -893823 975061 -774050 897676 -44588 479197 -361035 -822927 658156 -545724 706113 265057 -848088 -292447
-991588 44070 -689452 -673811 -853106 849445 919659 526938 -52330 922166 340688 203728 -882086 -49738 190150 196151 203230 910166 -201630 -375223
312817 -194536 250080 -162244 826203 287450 -842519 677136 460575 58825 999977 -954204 483781 972566 969668 880989 -536881 697632 868850 -209153
245541 85277 966510 -876491 305437 381605 -350235 401962 883521 -879987 -402041 -133332 392242 -128929 914378 -156571 -919089 -297539 -555045 995798
-613272 905850 -959329 -713820 -92807 67456 -421467 949501 -31482 894674 -856760 -770949 -313302 600072 872393 675116 -440423 494426 -371195 435932
-88788 67440 -25959 -502176 868211 675429 -253613 -356295 624034 -991873 -377518 -53415 767261 496482 -120431 -400672 -596425 263098 -69658 -436292
688835 59527 443795 -798783 -177182 -253319 -352498 -292820 -881145 276199 529402 969748 174720 -695101 934123 -172869 501060 989830 964701 763822
956351 -513517 -121392 -881205 -195071 -914363 328030 718364 257022 -476949 -704465 -136166 -107629 841407 995798 -411272 589335 -135417 478369 -428036
-54477 -911147 -362569 467396 -75306 -774721 -981693 -72124 -513361 838074 -364379 -97138 297156 -917513 -128266 -855938 236198 587853 481083 577564
-901077 986653 -585369 -214420 915802 187906 74333 -117449 135233 28031 -653121 886250 -70659 436326 277610 821023 964783 940726 -331016 -405522
873179 -349743 -165221 -194164 -82250 -336572 -81849 -800148 973159 -304566 -994640 -169379 955159 684349 862425 -233741 637401 987004 -227492 -246392
734653 -831172 44270 -781425 295339 -336952 168588 304115 872990 -349864 -297371 85587 574863 -423963 -848686 -508164 688792 619494 19216 -361575
-720045 384710 -110867 268649 895016 -979932 -388119 841172 -501771 97411 -243144 504866 298489 -92184 803918 759908 -692084 -971132 -209352 981724
191024 66080 -21967 512701 749138 892495 -680722 -97845 -767086 483473 341314 432246 -713923 -354612 -470676 647516 -978913 -320709 -102054 -591658
983642 96273 764457 -335530 746112 195941 -865239 -924360 -371781 -692933 358611 31612 -941738 -214877 -890988 530602 33685 -672359 181136 -594849
-829264 192209 -600187 -56309 -680889 -721324 -959329 -999337 -714382 -854269 -711144 -713986 786243 578699 -191923 -689108 -593476 622598 68915 563596
688540 -734975 -251245 484681 -67003 401761 -681465 -229188 83051 373359 -53864 -384673 -752135 -782502 725932 -565762 346265 -162595 283985 159725
374312 921007 319455 -339317 -470585 562722 483314 81740 -822349 -670299 -144991 710031 -162381 -773831 140704 717313 39113 391467 -487945 943523
405035 -329899 -804066 -178298 -709114 -381559 -826774 995237 -545433 -979850 -106413 -662070 419861 -247759 -906747 255657 -351591 978826 430388 -103342
-337682 -252000 492009 -474453 228655 -805436 -54597 694017 -751480 -194995 -828762 -800477 492748 583344 -136029 -221287 -137405 707172 787239 922741
-359114 -714127 -150897 -970240 259611 -817194 -823222 313126 489786 306222 -730772 283121 438184 340665 808311 -102454 -710394 401942 593875 911804
709146 -102229 -899493 359824 -823806 639179 -441603 -785660 -661007 -851378 -36980 -545737 265107 917678 -320335 841128 -541207 700807 265301 -957511
878287 -641212 -736816 522793 985429 748773 241097 449207 -337534 624313 225024 69367 550982 158300 -85157 -960887 -263460 -968950 -4064 -790284
-416269 946509 401436 -398410 -117663 807321 -844378 967630 -401789 997117 -34215 -28096 962671 510970 645516 552824 472706 -48366 393542 169228
-31584 -35635 468394 -319267 495133 -735562 751435 -415225 -77679 703384 954595 -582022 454040 950627 -27889 -128960 500121 -33111 -710200 606109
-633579 -217669 -499189 -667439 -414761 -136615 423916 756398 547086 626321 529991 279232 -395970 660173 423233 899634 -803421 443071 -812797 -521637
528185 323568 -964595 504626 -408084 154421 756476 -825236 812498 -230350 -890892 535490 -693006 570082 -194470 -703699 572382 384157 104264 802522
-90812 -911532 193120 -627667 -964304 -703397 572610 -188497 748349 -893988 -801790 90792 -191151 863505 389086 -874540 612959 155737 450950 913190
-977799 -44567 735008 772469 -126586 408900 -415859 -979898 -346387 653088 -245831 -60248 -313395 946866 989337 -958296 -645903 738011 81431 319179
374857 197213 -73205 -503415 -331071 202988 -618978 -284176 -346445 -926916 -47836 -857608 788471 464952 -358359 -818337 794914 713682 880725 312392
-35230 -32392 139643 -678877 715839 -184631 -170482 39979 -805683 -992436 746601 -472983 19468 -533833 -600438 -315995 -673435 -58143 57523 -356422
-29100 -377100 896548 -229783 -517712 510606 -672255 -244933 589285 -30334 -271220 817729 39607 662219 -975341 295310 -388921 -56646 393953 -747301
-61408 -366948 -631005 538244 -258240 -528472 329846 517432 -699411 845808 365612 319114 -752957 109362 -221323 413451 483798 -566127 -949330 604581
930876 779155 667053 46006 -346245 910259 -658278 -776011 -939854 -154796 331925 159991 -130333 884890 -242141 868244 -351973 -850099 -844498 158002
-84676 32554 870385 -747762 387421 -275225 -761260 -234895 -271668 377344 -463576 808067 518888 -100218 -99267 -983730 -352253 -508645 -537874 537888
-842578 319035 -171317 974861 -562597 329332 -324819 789807 905089 710081 365394 706979 482985 62120 -78399 -635680 181913 -13789 742551 -196557
653485 126612 -56322 -366399 -734705 163283 -922697 429848 893211 292354 79585 403660 -134642 -735944 70505 -933833 -917464 -567266 766753 589448
-207442 -422372 -660448 506427 521718 498677 -748868 404806 -506807 619445 816404 588042 902896 535656 594751 -264096 -120484 -716794 437071 -917057
-527869 953583 -888911 416470 225902 726186 569617 143218 753924 466060 489528 154429 -650881 121462 -738519 916989 -718252 585801 298221 -790251
-936447 -952568 409628 -628519 -270463 -208855 -739203 -235377 -163083 989906 802140 -320124 694255 -510035 189203 577304 -573779 622798 92787 -212025
370666 -777167 819742 606332 595240 -575330 -889904 862308 547951 -382188 -993589 876843 555721 940834 -61913 459873 222470 702030 308191 -151034
971116 80485 -867260 -614339 -541672 -683774 869333 -743708 429840 80511 -230450 597624 66650 824754 -830249 -993593 -526384 703470 -929421 -328550
-723127 166547 -709638 440030 -647564 702404 -404914 -823385 574859 38269 -804390 979184 -682777 687989 993976 -407777 -154133 -537904 -320453 850903
705628 -917236 -901957 -884664 -202046 -729802 -226111 -830830 301245 -382373 781644 807607 777102 316590 220921 570527 -948821 151428 -115557 -108088
-518759 929449 -14512 -762502 -260237 667906 -685492 624408 -953842 980330 -249436 523923 -45280 -193387 618664 643864 -638479 390456 -561826 -24842
-712526 -303904 -550552 617862 -986710 329738 -407480 759643 -931172 408636 273349 197908 -597407 -513321 770906 325469 -388694 728291 -409436 -691329
234077 -490261 92832 -97506 -70675 732644 922905 165040 -383752 364619 -359560 -189850 934901 147391 -607590 583808 -746499 -204450 -662221 837554
969048 288717 -485353 298333 289830 -171816 -954468 -648824 -251038 -431012 -662441 -533193 -183409 470704 -614875 921264 -125586 -775355 -843714 -461051
-459017 517755 871892 -401416 -699151 244466 -73217 165111 944197 -141420 -429391 -34237 -798042 972987 939937 743871 780975 -932620 -146497 -901887
821603 700069 -97845 661536 781191 917246 639942 -527491 95553 671164 860092 -46917 801360 -163723 -621064 254152 698945 -832370 519819 -871365
-60190 -564696 126267 541213 232333 674027 -910327 181147 991197 -723965 251524 -358859 226474 -222287 -840476 34802 976101 258942 -718328 -641014
274665 -152154 -367457 -16893 -287466 388198 -395103 325614 530580 486221 -539101 69846 681973 949076 97546 108086 163105 450279 351352 -702138
643892 -777319 -853071 -363294 -680378 771627 -549047 -236445 541299 473988 -539585 447086 -940969 -145288 435916 -372158 150334 -291891 319742 529576
460161 623451 55924 -475043 608789 295875 979737 575411 277448 -897995 284120 485004 447398 -579032 549197 608048 -639170 -5847 731515 -710362
-220968 -133676 -336585 -750688 722965 -96794 687457 -33065 138339 989823 65877 311845 -565767 -290791 -720632 863881 934436 750893 765037 377547
389936 -46853 594357 318420 -615307 -158475 -861449 -250629 108046 -387007 -491778 168736 25808 388735 -665179 -727598 -716493 756795 -108903 505082
674540 -58474 180015 -155680 465877 -617728 299022 -405339 611773 -224223 883801 -555545 558551 789619 -705937 -358146 369110 -207978 205639 581429
622715 -849572 481558 -163852 -611509 -407898 252169 -607328 579372 -771441 -802818 -172981 -931697 -97745 -427497 671888 878699 420115 -696429 -128983
726879 334853 -750248 -377018 -74525 726657 221604 375427 -597644 -124386 -251879 -784643 487609 -284663 991881 559269 889893 -351253 -259554 792364
28955 283392 433900 588126 -250629 -728364 -964868 168445 -761640 25101 129938 138784 795070 -628524 -326423 396070 328845 -756946 78067 -83853
-648851 382413 526911 685650 -640426 265299 331056 -462272 986623 42818 -422121 271097 979577 407607 -828930 -312364 923243 39280 -441700 945941
269364 374051 -804219 -651526 -502614 -882598 560398 266732 473824 -59664 211295 -369284 393108 776795 -833530 -3832 -960797 -377114 399374 864129
904944 483378 -660982 -698921 -872272 -942299 -998862 -700185 -362627 -778025 834504 -26060 332449 192239 933710 953363 485943 -352944 901277 767952
-889863 159037 964464 -834585 263262 -315757 330298 -215285 -511949 -691845 645881 793128 -643895 -325706 975625 -119174 -541685 -630519 -859696 -858412
-936931 -393268 -671766 -787264 -249971 331945 -911918 662120 953668 -234595 -280834 -201632 -868255 -221425 991858 992229 317450 461637 474428 676906
-795944 -370947 -203897 870115 83570 932428 456796 -608803 -464356 123245 42812 89003 353761 -752925 -168591 488256 -853217 700140 656878 951445
-630770 -920252 -997895 -511781 -71672 901871 345033 983727 -601672 489009 -499324 763971 132826 298316 487003 -85018 93150 -161059 -461447 77329
-591066 -339889 604642 -98381 316586 -66194 -540173 113376 963885 -625138 762434 -111452 642529 377975 -836754 874234 -5221 453926 134920 -728523
322240 286363 793121 -833644 529686 -568829 517965 74624 -230906 878529 -961161 -152756 194649 325032 -465163 279004 989899 185027 -413173 466184
365815 927000 188467 518801 386289 -982164 -987789 -75869 169711 -523071 -499327 433522 -564308 502642 -704604 -892595 964770 833680 -370518 -253427
-874915 648925 -60898 -985065 -723041 10472 226722 811168 814049 -609799 891082 2803 946289 -256490 -244416 -269205 532350 351273 53679 -885694
503195 -40090 -139584 989024 -941821 171997 850922 -199271 -225750 37857 -284044 -869549 -769071 -64283 -346031 -115079 -953699 594880 170679 76495
-775193 -307516 -485701 934638 170014 178567 -2202 -809623 334752 -790831 660971 -472613 -30693 49193 95898 431210 989058 733811 -370890 -327401
298832 -200972 972255 -706338 664213 67720 -788484 -393579 541579 683795 75708 -144314 -526412 577731 -965421 -79851 899622 554392 -319564 -790680
-891350 -884617 -422914 168584 -474246 -868416 295708 71615 -635602 928791 -475977 -896190 -886293 28072 926164 -557534 398128 315269 801178 -777875
-108974 -41764 26474 490839 -799145 566575 -979388 -931019 -360084 920490 -467551 -53085 -177107 186796 486278 104516 437764 526475 -851347 -991223
999949 713130 -330045 -526799 630548 -812943 204892 -909562 -71149 520451 -594793 -906820 242985 73110 764843 -326786 -187002 -904514 140004 -797915
-411929 -109981 -592171 334399 -265553 320316 177623 -983432 -390438 83729 -652780 -752850 725885 804710 -558528 -12239 516626 -797644 353344 -447834
-99876 420442 -151320 -355292 692610 966884 -890550 -975131 -812158 810997 966764 996605 -596187 -309255 -821803 -355713 786576 261729 400310 -540405
781908 397361 100809 -506216 -820947 945292 -931305 669486 504840 -584013 348785 -657674 -212605 -277002 -370753 -723171 -637525 -825120 366472 -588338
883565 -33756 298825 793028 -276929 -660923 -810204 456825 -232084 -353132 -663725 -551038 299006 318441 -938584 -68006 317168 -991362 -701830 -595775
569440 886214 -439068 -223293 -91363 894785 -952832 -437003 -677326 51253 -50975 -895499 -857768 382455 627426 -224242 856571 -949803 323876 395672
-597790 -70950 605026 -437035 -317817 -24445 -30395 799851 598226 459525 776645 64988 712611 -755205 281326 -799248 -485892 508131 -474047 477659
501183 -110446 356766 -152743 -101627 -537266 942227 -185279 -18247 825619 -492930 890208 -247627 -458275 908883 554700 -698585 212755 -586957 -112912
665092 -476666 -275475 152413 712102 140897 -41758 -563448 -164713 770045 -501402 795479 259257 519661 -434859 587584 -329216 -600227 -203911 -493293
689049 490065 -202707 821306 538257 598862 560276 -203304 255215 92605 -725643 -361234 -119969 -593373 542893 12185 -118895 137803 -723167 -915612
-61929 -689492 189745 -483427 632249 -815707 181996 93075 -911304 223272 -459642 -764711 760362 173670 -955826 888052 621428 643270 -31491 120884
577651 937195 914392 -969017 611258 204698 266132 276229 -196673 -472278 818526 185628 -959715 867451 -382748 -243958 -727145 736032 -785382 -942216
166058 987752 -515471 -90786 -356888 -563783 308394 -961370 -540661 209201 -163194 -227042 -134692 514283 -716312 -932608 708593 153989 89972 -553942
253217 883856 -762620 478824 -711498 915361 -332769 -16294 447016 186081 -948575 -901113 -142209 -327603 -751633 -316351 -959840 -565962 -870060 -394498
-925510 -912203 -460672 430467 808839 -527034 -585126 -501078 -922507 -63503 -668424 -546768 -260286 892633 -313631 729837 -761266 -695984 -690598 211814
80102 675662 311583 242642 49194 -400780 -586722 -821586 -992710 -158041 830822 -521174 948919 -759647 -843077 206365 911414 -660461 345502 162984
-90365 -215148 366212 -288291 950165 131341 216526 -783693 -317387 -108942 41111 -19734 624449 -36429 409868 712710 -427178 366358 -303215 -291677
-829421 240612 -20475 -726110 -39357 751897 -512856 -282357 766437 164749 -421777 -200991 -582333 -588740 -139664 832809 -663069 852425 -984265 -726679
-819067 993379 -572187 399917 -578787 493329 -290514 -20043 -950447 600931 -20625 -842454 996567 576291 -674890 797047 446676 -351843 -144312 948601
374005 -326867 560087 123317 288795 -303595 -266203 -678730 91706 -953537 42832 175663 56269 170706 -904661 -601300 533208 819250 298041 -219527
646103 -750128 -894669 -284657 172082 -937551 -970497 573233 -233148 -921362 -464614 -974293 532923 -232905 576318 907497 -140146 867502 -161868 580347
-255266 102107 -664852 157339 -974094 -330537 583616 -904243 -710991 -237323 -712210 10822 -33064 959910 297423 -165435 287631 857189 -820342 582616
889746 -742356 -919714 902908 -878078 380711 -370151 657418 -114222 811044 231900 -125978 312711 219409 -311441 -951239 157254 872429 300292 755702
56863 -489510 289532 182292 429539 946827 -558946 734639 -720664 -298862 576043 705346 -530498 -650844 259273 -416935 -437580 75640 603081 649821
673585 742648 -741327 3873 457409 595215 93220 195992 -497086 534414 -433152 196355 -920889 -988837 57037 -175978 -709452 -16456 56076 -543357
326662 -906818 197688 -939259 -86686 -22880 -128867 83532 457748 -713438 -260801 314278 -856433 702062 312906 326702 -881444 -502091 700742 -573992
911981 961000 -284215 -889875 798569 399613 957299 260956 586512 -926340 822849 -867664 -749437 680550 -4864 348682 -859794 291010 -228638 -509199
416271 -391989 545750 446049 -400566 -636059 119604 -33594 -714512 348314 47375 875071 133483 421059 287033 891643 -363420 -88907 -691966 217682
-839519 -939963 -659483 -354457 958374 -588059 -331582 713317 756730 233976 310935 -190907 -661731 -657419 411768 -968086 -979879 -15449 72669 -141563
869217 -657076 -424689 442956 -346145 -381303 -737288 675206 -447708 300836 375838 -721495 -718233 238654 -214814 -923127 456784 82550 -86870 -491691
-699044 -872350 -114736 163859 349287 628522 9376 165897 205058 101124 -576063 -744617 959020 -659545 997976 296662 894982 -634542 434645 -514258
746918 -729754 603070 -456572 -438765 -403515 255759 -965465 419266 969770 870113 -162731 834357 -414163 385980 -446542 -837537 -245415 23599 -154818
984593 -483303 371459 550911 -516796 733302 296747 -723918 -357173 755696 440835 -193169 916939 158909 -632077 380479 526676 675925 -267511 694805
891627 -12902 245167 896043 -509409 -101297 -798661 70321 -803963 -634811 -816568 -149937 -440951 -247116 -555982 557922 11228 -430980 371860 -213995
-569292 -330808 -97510 978489 926930 71899 408452 400859 688504 318446 -390324 -639528 838024 -221176 78833 120422 -918416 -136293 -20450 -244173
170641 961284 107845 -437081 568434 19696 652678 533595 651240 80297 -35966 -469209 -497481 -216252 -965730 441998 939916 -267078 -539650 -595873
371454 -371660 938130 507238 193837 219055 -561128 307873 -463425 -583119 -670417 308027 493503 889014 863399 -72955 768817 361358 674173 -298191
-187601 -586065 -829496 -412840 -728123 -833993 -670057 -943018 991376 -142978 311388 202686 -521566 -291459 -197210 149815 -141274 174188 -935440 609207
-118323 -406806 557810 -728272 -20734 225125 187943 454199 -313675 -712221 64443 322955 -863079 785088 -499362 631853 -927556 985321 -305646 613097
-614441 456637 148868 -718587 -494545 940132 232875 -5381 -455489 976934 -719339 -828156 -800663 -954074 -502700 -950650 351619 907555 903648 -30217
593519 505214 -269032 -511990 839654 313830 299342 557607 347788 848033 875973 -607288 -778591 721208 -653936 -622819 23963 -440393 -887723 -47713
-250662 67166 919565 -527547 940913 -829801 627427 599516 865887 952573 854792 -220978 -827570 -800784 -832731 -822389 -111337 -4869 -168213 952773
-36251 -518684 -701941 -61952 -396809 -896952 -861425 -631236 -758034 328166 -339327 827534 -334620 635841 -399148 445739 349442 -561655 635207 218070
-17581 -675853 530473 -524260 241896 439973 10659 381440 644888 -514426 -106426 211905 191041 -359763 -876215 339867 738008 678919 567310 -823142
-520282 183131 659642 950518 815811 -187758 -643675 -169978 -908348 -115558 -572902 -353878 598877 -966833 -335625 560109 -254613 526306 518607 358241
-914200 916231 -352034 9522 -794877 563728 20518 173800 -873172 -389362 152803 -432978 -31743 -561144 508828 298259 667769 -921128 405176 581321
-246496 -834895 -167674 -210159 -224178 -263849 -210759 282750 540642 61413 838844 448347 125524 108612 -261108 356005 498065 856122 -468854 644883
951505 -656878 26827 812984 -889779 820454 -513887 -460860 510257 -211764 -452589 -387428 -471858 -130742 920206 -421644 -878882 574764 549046 -549887
380477 -257680 -684815 735963 -921070 -695925 -32664 -251676 -842286 551142 -957938 -193898 180579 -169721 -636704 -469305 -147787 712425 791905 912410
176769 -868116 -254627 271803 415975 867085 -431044 476728 -134374 199968 -496667 -885327 -981353 -844745 -512005 -396338 -103866 -970060 460384 -456456
-965086 166149 866662 -120435 -670145 396874 -858255 381998 664214 -271106 -996394 714243 495573 -839555 -360664 304674 473593 -469579 155850 -451680
538031 -641990 887784 770804 208491 850935 -886856 -104417 -396863 -977710 871279 368852 331218 760178 -142800 958989 937780 -881734 890481 -71334
327024 485176 -606018 -112055 -247957 -213345 -313094 706107 355017 -482791 -706758 -510107 -997947 -868030 372283 -15999 128109 374133 -96677 436451
567615 -379604 -757671 879161 328724 956539 -977425 -780445 579921 -513369 -668762 -832972 -632242 885280 934140 -693839 -333895 -739702 -725630 -409243
607822 298192 -995451 413721 383387 386656 634244 848032 20372 844598 195719 397926 -262606 -795051 -993190 216990 269621 -824646 -297655 694594
78176 115035 42992 -243902 826694 -613032 -733810 -656249 -888134 450514 457745 -740890 -846443 269537 -657514 -810475 334185 171878 523981 -516757
-536087 111321 896682 716840 840154 -138946 -922707 -564494 780085 -527862 -83367 -864370 -413289 849507 127664 450870 157075 807100 547065 -509963
360767 395616 -360244 -190433 70884 740670 356096 252383 -880217 393686 808564 525847 -860372 307136 -969819 -116180 300001 238141 -891612 -553873
857069 -287857 338907 -860545 133527 468184 -650073 360961 -579116 -61308 452373 558050 194897 467421 -197735 556833 672680 -235676 802679 154387
932468 -287315 86903 -355523 -862347 -95339 -380252 414821 868143 125230 -245469 -244419 -151825 662567 -82593 -556933 -579909 341621 64262 -423612
916335 -893883 776422 299619 -741813 -810908 24842 347604 602662 994656 715104 443024 883366 242798 -566204 501126 114190 -787695 -821170 498084
755081 929891 384796 -430889 -703638 309798 -746797 43603 57804 -902729 402149 300502 -2176 -671202 -297713 717213 995333 261893 -643629 339812
659956 924382 -827574 -496279 453138 -777926 595193 689224 -3690 612267 904681 668437 845615 399324 873414 50644 477509 125504 183321 -634949
-942070 -455506 888171 -99541 608288 -735849 -419742 -883583 -723287 490943 682499 -594895 -171970 41537 -967350 892247 971347 765053 -930215 83871
-4693 738077 -785373 -890485 101099 -369426 -298981 187046 -966920 268470 490244 380006 384091 108764 873111 912062 153806 441746 -469963 279699
-476607 759411 316626 -402950 503781 -532672 813381 252243 976003 -375888 -770256 -772376 505073 909117 260900 580546 206950 953060 345704 712970
-310854 -973000 -570463 659701 -332953 -706868 903489 823742 -470513 263439 982973 32306 777324 -466901 85689 -751079 566457 417262 494265 -99058
74716 270006 -226461 339449 196711 -299222 -908207 -693510 -570394 195982 18344 480372 -841203 111615 417952 -61275 993828 561904 -874026 262296
-115435 605256 874496 -258411 -777248 -304053 -244840 339060 -688881 658797 -607663 300411 170989 135792 944856 -160368 24642 -186488 698087 -651280
852232 763850 -986034 880656 278203 -494594 -255723 -367426 -60728 -361708 -794037 -950501 -906594 -240520 -481887 -246721 637768 749712 806125 538388
868077 62512 -529268 -168756 737737 908462 -913102 -346184 -899401 -818512 -732649 -494453 807312 501007 756552 -498261 -563767 -166106 379759 -566386
-338688 984669 113272 723389 945118 -195674 4710 409641 -90266 758232 -356672 295176 -495209 659980 291526 292080 880366 415514 -978219 5285
-260523 -396267 351120 -614726 562009 670414 852816 -159507 -64400 -832288 784078 801506 -674375 -8846 275174 384680 534922 511738 34258 -32902
551863 -726211 -246328 357052 -21517 -299449 531344 725071 -505418 -467588 141724 -716795 -716417 170902 612285 -287427 -403422 697814 600194 -461567
489157 805687 -459148 675578 -257511 -19349 113818 -26766 -808685 911223 -286833 342102 378149 772395 157005 -992254 830187 -727496 152955 -458684
90533 113307 -613469 -210497 -278018 -315155 22639 -476205 719019 585699 -676653 -20490 507663 -299460 826946 -182528 689270 -977463 612406 -986230
482934 994367 693763 -89476 271317 324049 -280175 -826166 663959 489611 451148 643393 -838311 -947943 -125796 -760327 471523 -376387 -647554 -643871
457384 875811 236689 582258 -973873 -367779 476802 -410803 -95978 -554160 -47270 781375 450283 -192836 192602 -584050 -844374 -57531 -864594 878749
62756 -286154 -764628 485638 -254295 -861504 25393 -709718 -364545 748101 551584 -144781 -121719 998391 -452700 -515385 516713 -681084 627547 825409
382642 -48557 943876 -320049 245019 626388 -45184 465955 237044 372938 782350 -979782 -9888 420153 207369 -475952 -13937 -582775 366772 307507
658388 -420508 -89080 -639611 -398794 -304384 -320271 -719892 457434 -771973 -859347 184537 979269 -458136 489838 -955650 213584 -429569 30026 150971
-361800 -901123 444848 -382776 -828026 270271 609988 -764939 -360893 142930 -393832 709886 135521 197659 -951460 387603 535535 -19570 1483 348819
-806691 -127367 797086 -857308 -639006 -778577 56661 130265 -470986 66446 125793 -655181 560987 -244799 -257897 -83276 539749 -880769 732801 115949
-759986 -972782 -162521 -937263 -338397 520806 -503228 630185 446305 -384231 770546 -550459 -549184 940973 662512 -631362 439923 346881 -751062 -752391
32514 -313710 -989848 -7904 317858 20365 298068 -378186 863107 -729757 -55514 753555 398944 -346205 771112 751845 -360520 566140 265288 -644467
387793 -863614 320567 -733327 -631927 818661 -394362 -156216 -835234 837920 -957784 120522 -46006 -311216 6293 419233 786252 355385 -175228 -737826
-90825 -223523 -314528 947964 -663029 -130617 290820 872273 778942 293842 29938 -45136 -393784 422987 -343885 -539323 812089 779889 471780 -989497
-433647 -205894 -684435 31340 -339245 894347 259878 -319378 -102010 649030 -707923 -227160 346763 390851 456600 812990 -996329 405543 946477 878338
902995 847009 -601172 -572696 -939138 -623808 712542 946510 436770 -243590 685398 -823967 -500283 440833 -78069 250392 197070 596917 -660585 -295130
370268 54012 -783006 12389 -577327 873862 -342926 747853 -170112 151653 529910 -827604 -248775 -675878 -78107 -500929 -98405 324764 -385367 -976526
21550 -560209 799109 874514 -700451 -454517 551891 -502657 -780658 -253416 63485 90335 508614 -748041 757813 500040 21799 807675 -821194 -964509
387478 475794 -910098 -253092 685675 876193 -784606 556284 63439 -551544 -46348 64810 -259726 -722543 922605 695534 622769 328523 3160 806939
-69943 -351616 -551695 -295907 471212 589308 -973169 -279565 254288 23558 291272 -270821 -904077 -725812 350848 -933477 -712713 -865129 193445 168627
565009 -603805 83265 -394034 -245381 -833666 308332 -924800 184203 -709814 -130976 804670 898152 -639198 476535 -104028 20955 -196775 799455 -30566
-335981 883669 -97250 -903941 -329339 79831 768991 -196697 -841994 -436775 -809287 -875855 -594373 -753264 863639 -446819 903158 -787213 -761310 516353
447124 261396 683032 515269 -320719 259878 -174466 -550336 395642 427054 -466633 -219820 -461598 -951231 153583 -840630 181368 196687 310635 -830599
-618128 -119844 -926740 61050 -505 784533 -899786 941787 -871299 -835387 -284961 -728947 -912128 630549 -169081 302682 -419807 -48785 241150 884526
677300 -936525 246733 -770189 -237167 714942 905167 453529 764198 -505793 -101909 -104352 -280708 87482 -83518 442468 642801 546895 -358027 -238914
-240169 -395895 -759673 194889 302667 -472331 -334579 599782 -626013 -592768 353305 -472242 -613171 336038 -235357 -891545 -744118 -745709 -503041 110214
686443 974698 -549238 312636 121010 457127 107784 -792008 -541697 925596 129634 448816 -329339 -442802 -741133 -231696 313666 -11675 205541 911805
416468 762823 439894 -170970 875111 -411450 -638004 455426 436277 933267 348906 -287685 -913328 667040 -537061 -675522 372290 -457655 899923 -734399
699359 -442973 860765 229682 -666996 165235 685564 -842489 40612 440964 927571 428773 -197369 -81180 -952629 378100 -380765 111891 -997188 952557
825853 -347572 864132 400047 632786 97171 796423 627354 715282 -714592 160193 647281 438926 -415443 633429 -803860 100797 992253 -511913 677727
-83751 311977 835685 525679 -505548 709597 -448571 150083 457023 -246460 -759317 -752848 -27597 -643906 562524 -93158 -272837 -895306 -263419 -398707
662913 720197 768990 -378180 576764 986898 -698168 -800675 -577692 901105 325842 -966617 900837 -19087 593200 879735 345225 -726901 487198 724142
281923 391379 620837 -668835 -696255 47067 -129207 172214 839758 -715144 323530 -376751 -508108 -103604 -372175 203055 194827 -875354 383937 698963
374666 -4687 -158391 121765 -667947 -633102 -673123 -706021 -423303 -873502 -542388 -844673 791190 802751 -424763 -549246 94831 127181 14374 -842478
207080 -514615 856699 -774508 -168780 390537 -486709 749060 290884 -544685 18137 770430 -895622 18152 -964914 -799229 914957 989267 716352 172333
110574 299734 611671 -865430 679960 -496071 -851799 929050 108346 675879 373208 854980 784217 -473965 -561 -499589 -298290 -134830 553755 731781
686691 -904001 -807473 655332 -909909 91319 939196 -294311 615537 157952 -305254 191757 -135641 161680 920865 925077 -744652 298348 -871835 660095
-256669 278615 334377 -259237 524833 413902 103872 -446090 996341 374839 364466 768603 -758121 -605441 753800 -689678 599609 -46561 313698 631533
964415 -683098 -520169 -913285 64257 -913443 -959038 768028 966758 299092 -817836 899495 496339 303178 -800893 -109763 262936 827246 677952 -19774
125509 -188534 529386 -340154 -724331 -650701 288782 -500580 -387284 -337307 -477099 923445 21693 400218 160386 -741663 124021 -838017 687829 -710400
-527272 -602293 -290206 93831 550358 479800 -989114 -80943 -22173 -927063 475780 713269 514194 -126632 857089 -315987 387540 -220048 -531375 -455512
-131330 -750948 527258 47197 -999728 -662030 -374846 -91543 887623 -298542 281992 -603247 -715366 983309 408466 -643771 759370 859500 61767 -860332
-908807 -982029 353120 118798 962780 -524080 -934997 -935251 -918308 -592415 431338 619212 -605848 545758 545332 798504 -686753 957749 829194 -413035
-804572 -320203 245496 -73928 -235849 -592141 576800 971132 -387869 978199 701480 -369895 -275392 -428627 -421548 168077 -658267 147783 668995 -376569
-972780 571215 -885174 -412264 688660 -297257 756836 174896 -404702 193527 981333 602428 -371178 381743 -761513 -439690 -397846 668830 -696195 463880
-798572 172676 90562 241938 -954491 360788 -107044 -934238 -193571 -51431 835621 -121071 -620656 -170502 113933 -589640 560768 -671918 -212356 -474701
970743 861862 -480329 -531947 -133224 411532 992354 574079 -961180 -21655 547563 -272455 -771682 905183 912721 876451 343822 -133695 288351 -897094
978128 767471 -471521 579858 113957 474311 622815 -135148 -875566 892249 -986354 -168276 407807 824391 -670547 920693 -186255 -94454 561070 -682947
-489049 -758530 -640549 646550 74600 339007 100210 574508 -974718 72414 -219273 234844 457407 916717 -188558 -511013 484524 278125 585066 -114492
108097 -796799 135807 -381841 33680 -474951 -997321 -186952 -485414 708362 -657912 635320 825875 110775 814195 987038 -218142 -163246 612761 -484639
437260 850732 332856 626942 763256 672768 -511950 -161287 -374662 -848214 478203 642669 -475936 795459 -500102 733622 388117 -251655 -419104 241748
239176 -525940 576652 -37779 272089 -851072 663962 405907 324311 211286 -128984 516164 304344 515215 430709 -218660 530061 -910693 155417 236933
294001 -515814 -679043 -72500 -546404 898636 -853310 547143 859285 878263 -479560 -987836 556059 -780027 744320 -951588 517928 -353686 450342 140217
586094 -970954 578053 -731421 364755 78156 -35399 651350 618830 824826 638074 614678 -353144 365587 619458 94260 613585 -113818 -339869 630568
440231 -928856 433869 748934 -486288 225114 592523 -21883 -896601 222840 443757 -595687 872711 401286 -882283 688745 242914 -289493 -279314 483428
-487966 -305730 -358989 -650116 -894149 -51015 -53762 567795 -70721 254388 905189 654837 752951 -404190 508612 591859 274321 -999687 912531 -294950
-47265 693982 -906145 392751 958807 -532720 -684559 289195 350080 29340 617588 -714025 744638 288854 -716403 -164445 -590255 -176090 597282 -832471
-65374 161857 449138 675485 346427 650293 153252 -427485 794296 -911824 422519 262502 648056 -182929 231373 486957 -50996 -218335 522754 -915845
-293562 918530 286371 -935264 -184719 115390 -900904 680078 -187318 -354983 793406 770013 -523787 -299449 -680308 4171 -144708 907576 -806403 -917471
-562628 -351541 -333023 330718 -348893 -34155 307580 -331744 816054 -552196 -183158 955186 181680 -672825 736845 -966636 349801 155839 904493 -380807
982378 394981 767309 -894308 -432445 424236 113460 765484 -53007 -330312 119894 621254 -26570 522793 73827 79363 385479 813556 -720049 -992087
70206 585609 57955 247414 -938360 -207904 625232 556574 10406 -581955 -298227 -523964 92734 -996884 -474246 776564 -208868 6862 -265798 -531128
-662753 223134 -744844 628855 -357281 747501 8788 -363962 243565 -735100 -773244 -650042 -78672 -416768 518497 -450857 -44395 412001 -431122 724088
879024 -842455 -419719 -112629 871610 286685 -444544 402618 -670828 -516879 -114782 -40230 140404 -881829 -172064 639552 660184 724288 841217 218237
899277 -514854 632895 -399201 -369854 -233821 -120994 -515686 -221618 884844 518086 -218833 525722 948922 -536262 -918475 -679134 817118 492907 369331
61374 737200 -45777 -782093 937450 -913627 865240 -41038 -658365 -106219 331439 386938 -712071 -145662 148854 -96902 -789431 -473078 319049 -109407
398718 -195904 -791128 -291193 -808040 448909 -273895 106794 -957910 -412877 -774261 -454367 842657 -827010 555976 644894 209449 666923 -878320 -13586
-181107 -796797 -107905 -513332 165245 146060 519249 500994 964094 -84932 -929242 695756 602474 -211579 -390314 159939 -508167 -257257 -805299 -387904
-259185 122394 -397334 224356 -668473 -953269 938959 -773558 333595 -13135 -285635 405308 241113 -476171 462571 -961786 -700486 867475 -254218 -993535
-733688 575303 -962971 578035 121852 -239722 245310 -17118 186025 -621627 563880 -793737 239418 584680 -205358 597187 326188 740523 389657 -450013
-532118 563605 -131059 -129096 108154 375085 790886 677978 -498132 -787397 650843 135150 -328473 -279824 -590809 777194 893206 236439 85474 210883
-329254 -92323 426204 382623 630228 560847 -783473 -427278 -267997 71487 -67128 314176 478441 -431602 189987 759551 -891400 871922 366961 459208
-228920 390712 176359 -346092 521261 -743643 -600083 -636754 588531 -223763 -55036 -412367 -921389 116302 -233944 622585 -391607 -590260 -10446 -588354
497636 -784332 73567 -230565 -405671 100710 4502 667005 525571 -57251 -137577 393439 497099 -285227 38428 -57117 -55567 -69582 992885 -990796
-314084 -696934 -435220 615864 222216 -400742 -174227 606476 -630484 -888474 -997105 692285 915654 -179854 974997 41552 321936 -2264 775877 -916559
585893 -367883 -816771 864433 -449251 799257 -175926 592855 762538 -451744 708157 -791399 406068 -305567 752684 482104 -974142 822383 -553786 568814
325861 674366 -827136 -766091 753439 593365 -555476 332440 -278359 -7572 -774769 531846 -288624 841050 578966 -314636 -884244 70826 272899 958261
14084 -377890 -329824 -919473 615886 599666 39206 -290917 -410483 -316280 -550363 659306 584914 -69812 -436597 695053 -335695 846406 625673 889508
-786119 357315 638641 -580225 -402723 643920 -1041 888420 -633718 819504 -549458 -958371 988414 -953192 792584 488598 449953 -343549 822178 -722232
579634 108866 167689 15111 750416 256598 -271713 365561 901962 733723 -792182 -248969 782084 50050 782711 556894 318531 -335340 -56211 -265744
-130049 856480 627195 -671443 -924682 748298 -115914 974847 -83833 -390062 -21889 743064 237484 836198 517195 617109 21926 -565314 150611 877674
125966 -517555 265016 592926 -70266 525344 280199 158196 -90995 -214609 686755 -322269 433896 -320193 854334 905418 13050 -62238 191727 640357
116844 508830 243319 -333316 -351645 -312119 -184635 -776291 586413 973795 -839365 987360 -24188 215088 -549774 925491 706149 100277 271131 -100987
865103 -505845 -227496 -558231 280498 -46866 741099 832557 312215 -992473 -330487 -631878 -486614 -705250 -379241 846994 -63761 178157 -137136 -909995
996951 -465010 -657623 959983 69941 653567 -844717 548032 166187 351701 19654 259188 -784955 551195 62174 -519224 -10890 153356 -245582 704154
-203150 -190033 -675015 202193 -896589 -706622 -550857 208097 -780602 -512020 692505 217699 326647 -490780 861715 351526 982911 83247 -660058 -896112
-958856 -492181 279401 -991081 -835256 494593 786891 -523369 -651785 177040 -312200 4496 -207871 -75369 -303914 -48033 -372070 139471 796035 -878582
-561858 1480 -400183 668686 -346999 -14266 -552389 842408 9597 -12151 -300382 -30740 -604916 319550 -586598 914457 -510798 29360 971654 -760972
220529 -451747 -241429 -941480 -174287 150638 553734 162752 840072 410823 170867 -348118 -361040 -378158 -877255 -676590 757839 -114842 -273410 -610314
619967 31474 714299 87298 70274 5544 783382 -166566 -736105 -732138 -496974 264429 372489 210494 -810055 -997401 577275 94591 -671979 427200
-134288 850988 -758387 754615 -959494 -854811 -740286 -110661 792067 -941517 859524 -370309 -412806 -839742 139010 178335 -938886 994367 996841 857642
-371974 -323993 644005 -586779 -365349 820697 563428 -679719 -919925 -100879 125255 646184 -491100 -141606 44543 121449 -819414 208759 -33630 -493630
576662 -728787 289694 -657560 -647368 406549 42690 -797065 -537682 -918108 941832 -139375 -751662 -430609 -185970 690687 316134 -180362 258689 630917
-443856 559566 -702958 -945549 -882930 -768511 416067 656131 862169 -87570 -501984 -480018 748913 -960528 -705578 -400683 244749 825665 -63232 -670738
-909364 941178 330953 -209166 -858068 426767 844383 -714937 -93750 -474903 621657 498737 614183 299654 -299917 -551210 500757 -8863 968555 -911588
-891916 -925630 750172 464091 379233 543444 -386861 -304848 -709120 -907356 958274 -295585 160188 120367 -543757 56565 -422205 -950814 494094 987071
-939858 -326840 -621379 343677 -285303 369714 -77814 -633037 -204877 -273939 108798 200503 -882127 6456 385199 689596 781561 -526548 -957033 -516639
450978 656393 -482012 425603 -102430 -118619 -41969 -186176 -398669 -132876 -931220 964495 -256269 -199527 -972781 -206251 -207285 -603972 689857 16308
-720921 790385 -665916 -113959 -366714 329120 499506 779400 41755 618036 727212 189728 667610 568995 503600 453203 648112 221655 29007 544342
-183348 763154 -155103 -89900 102529 -390087 -35540 -382996 -148816 487253 98006 774389 -332651 857147 711515 -518578 -749914 723882 -609443 -141583
-215075 -520628 -847145 718026 -597799 -409686 -952783 856865 687277 -150124 -959137 960807 -347268 283651 -96138 -442983 -546978 -102002 -859687 580227
-771829 -126452 -107479 -453809 471383 -222829 -101187 56555 -774866 282867 306404 284971 679791 189667 -123618 -672933 -798010 239762 -408761 192140
933775 740793 631563 -895096 -699009 -232287 381022 700564 -543055 636813 630139 215815 -834043 576983 -417418 -288850 575435 -823903 -19090 -994652
-610251 -490290 -139435 -127577 -58158 869896 89237 -210788 -191991 967228 132457 -924891 55891 994882 543242 -712477 392620 369261 -859356 -761152
-456870 105509 647926 -865220 444826 -121638 542580 -241473 -840314 -560007 -665357 -561655 -786499 -954922 -321393 -235849 -483909 840459 -450448 -489681
-579609 91232 -62358 -308307 -60712 -976439 -81101 462831 -423757 -507143 -617996 -712408 983392 508426 -546957 343107 -993168 -920455 -576344 -89225
-128236 -210976 270982 -614855 900664 786893 634055 -220758 476184 -201234 -926548 -798294 456644 185534 15682 529733 -651120 -705508 586703 -750351
950196 524832 875070 -934475 -219300 -698467 904400 57996 871911 323519 507321 -669592 -843791 373081 -119210 -583727 -733817 -463333 -110015 337621
928217 -598490 994530 316185 -458829 -591225 897968 884946 447963 230402 -787654 -883580 650940 217021 484533 291268 145586 391977 308201 -357504
-646343 440241 285913 555410 345233 714427 -470843 8603 -898490 -811750 675556 58300 -301837 -242836 -597614 -927919 -282537 -324547 -58622 -91189
223297 -330396 -205227 855432 318138 -934187 -120594 99613 291620 865364 859007 -502609 -44286 713246 778395 390884 -360024 -202536 413925 -751526
-692749 -276386 -114249 -624713 412462 -879817 -257139 -631305 844205 -386599 766627 -767351 331104 93610 -823579 -949447 790084 -629830 856753 -377484
-607071 827081 330539 138630 -162894 -579737 -340186 643943 -59282 299274 -798782 552110 449117 499622 -555113 402889 294898 -616546 447747 -669693
266163 935803 -964443 -158144 977375 933571 50568 394437 73367 -754594 367706 396773 -86740 -714788 -566227 212858 -624202 430372 -369624 -613707
-214506 15755 -939410 -511224 765470 824157 603816 -63580 -404915 800613 625342 -425708 171979 -182849 108074 549599 611775 -80250 577653 -769643
156192 -325381 -539373 666660 -836584 889826 -913468 774414 -830951 -303031 519389 -13520 969240 39091 -67380 -210818 -217310 379005 693787 995246
13985 61814 -900958 -356358 549147 -141652 321614 651369 -338974 776521 446207 -618499 -417366 430876 969401 -491229 -122566 813898 -303477 963645
-761385 -936296 208906 200016 -697168 -943497 -753219 -179600 -240409 20867 992809 272407 -80405 276058 987244 844085 16432 139144 274342 -757315
339875 644966 696800 611316 -615569 -114315 -371645 -374618 -775888 456184 334923 993183 -426964 -728291 426270 -655916 423145 939299 490512 -242271
-992248 -390852 -911489 -458061 786760 325568 824779 997294 -677633 -433556 971849 115708 -454885 973562 -560468 -922059 -70868 -705493 567714 536742
699109 448620 -187429 519267 -926475 570723 562612 -19986 882364 606330 94948 -38892 -561673 -355261 405011 -142526 168095 424978 -492491 453327
597471 235777 -657088 -545430 44042 593187 -639197 515203 -826422 -889903 -582381 995991 -15720 460199 430009 -264900 44727 965075 -442386 -622206
-421362 -225944 -925083 -204339 437199 -410706 -866760 -145598 -499223 -273194 -702444 649929 -392608 476000 -885105 -619423 -930083 262255 34500 586054
796298 -919385 -485268 142501 983405 -972650 636113 459935 -143593 -772845 -813428 403614 211763 -708104 879180 -105833 623930 -282057 432417 -61167
-708992 -788848 -168653 104434 489996 861581 285206 165432 -695758 543572 527994 683534 -54616 194687 -896718 371541 875533 -153481 155109 149832
878231 -81958 -348426 -515525 -446118 163663 424884 -348853 -286 346471 273052 -204678 -763961 -520400 -604579 -532582 448781 -235198 370958 756037
-798035 300694 -546627 -299415 -614135 924176 252113 -985563 872730 168241 754623 -1547 94711 -244618 488265 216008 -535099 241346 648361 -160740
232026 -975145 845039 691706 -264555 -174882 -353431 963659 37611 -503576 442047 350833 -13390 -412608 -691602 214626 395969 -661628 -790023 430429
-249919 110427 29345 -739076 -875309 335090 -878885 662670 -974935 -391796 -110686 -306568 -160835 -358973 -565211 -858918 -997378 143475 -40754 -739486
468079 -618772 673562 -180034 840455 -547769 -385521 -231280 259674 -199580 -483815 -567170 -779512 -486468 -796732 -373032 342850 232310 -208824 973997
614518 873082 427596 644610 -467199 162655 -549640 672358 -474977 659106 -721467 -528817 688993 285903 891284 773182 646624 -569208 699859 -771455
316901 -253081 -830527 -641146 647590 877377 -616744 -858170 107648 -854763 920581 79866 -535327 478480 960673 385863 496479 -734641 993353 -217428
-539823 -507905 -55900 955884 -752339 -62803 -598270 -286682 24614 281938 348318 517524 -608093 -692429 539775 191325 -710484 -727744 -998477 833972
310942 984007 -901177 69611 378937 367217 -678095 -401334 975158 363840 190892 411878 765339 865152 -159478 -267440 -698314 -727811 84929 505137
-353442 -220380 404931 -310067 -201445 238578 909562 -294549 102038 -136597 -716972 -942830 912013 974936 -778304 774210 -115750 839319 -234741 -896762
51716 -112705 -286076 551450 -18015 338974 -747186 599413 -802773 -85630 -81602 -554450 -244640 -668743 -388972 433225 17890 688646 318884 999372
-73895 -789821 -130414 -591313 -102442 -735969 542398 26707 296229 209833 633815 -73318 224510 -781783 902965 -393009 221418 128298 -992281 871612
-990048 -227195 -131138 -590011 8776 212376 185137 -331756 -841356 252465 391702 813160 -741281 -283057 -941246 217825 -534318 397805 -798336 -231388
-291216 385803 989505 729544 -268423 -561867 963574 471398 586756 -710055 -67671 387478 -322912 -205351 -370855 -418050 91934 -675234 -995013 632499
-533470 1290 731373 18265 -894541 592181 -443184 -112221 997387 -552704 -830849 -603853 -555006 673719 344013 576401 -145379 -712379 228399 343881
641760 497696 -347819 576513 -650621 -152268 -960442 -216463 -20197 -643391 227898 -698418 54558 997256 -640723 -36644 -465886 342360 554388 549877
-243912 444286 815945 324087 103376 12400 -586361 -407879 442246 243864 -270999 -648 -265526 723415 -529964 950595 -447685 533172 986694 -753935
987978 579115 -23213 678942 263440 -991638 607362 -192761 189730 809402 -935156 -695683 820942 -40702 -314604 454698 351964 199579 -592905 752283
-563489 -676870 -433618 342503 642927 285755 312203 -641153 -674793 -208321 684844 -190818 -29032 830584 13420 -910116 -766737 135359 321258 -613772
912206 -949636 168401 240410 507372 583232 -988450 69212 656719 404097 686890 791527 932401 -912184 852306 391624 190512 696937 510450 -374019
651755 -918290 578863 -22191 -760763 461322 -990584 -923226 -378610 -183997 -43964 529635 -470834 85004 -354826 988020 -186604 736582 487456 -102627
-29291 -357870 649438 103947 -10566 -626302 -329615 741481 -538183 -731125 232774 614987 763432 254679 -39453 652485 -514010 451642 -818571 -584770
-30187 533806 639261 215548 -756525 -355376 386671 -492641 335091 -229773 557119 -140628 -887486 262176 -319572 -932423 -717098 -440422 -934823 396828
-207338 -65796 410894 113627 568829 -717507 -919312 498448 -214282 951599 636239 -584533 -746229 528934 -638834 -849063 92143 664928 150359 759109
-128274 -29893 -919234 88319 307786 913099 124390 -765065 738894 941154 368642 -214911 527933 -141170 612349 -956041 500230 287643 -558458 -706935
742790 190762 -163684 314389 897313 -524594 920766 26677 268637 -332661 -225732 127297 -887999 -422579 733042 -37832 -943874 157033 -869336 300648
21157 657138 145712 566520 -614172 734592 -504740 -722628 -990769 -825237 257369 764067 -48404 -107326 559588 -635893 -906375 464840 -763401 -339765
464192 766532 927032 -767219 -908379 -992743 589281 394317 -662123 -970575 279773 -78948 -955102 711156 277703 877862 -393835 332761 164645 -928289
-964943 -747951 -223585 -32234 361226 930829 514809 850457 130610 650978 -243899 724683 974111 -136444 -286836 223280 54385 -18016 -324283 -356632
464797 -676566 -606236 -39026 -873585 -741303 -266048 -366812 67297 545347 -948118 -123525 -110939 -230316 876495 -789456 116027 -883850 653464 -258775
-876431 -476119 -622731 465955 942600 496932 -176927 -398200 637392 -962471 344643 -729368 -275899 311900 -241644 -418968 -472681 566196 725243 535521
468655 -544242 442452 74848 473588 382956 885120 294888 -566115 -451096 -67246 -442485 630921 116638 799946 -147562 211119 -125753 177613 445561
-152110 -248507 -573347 -206909 -555941 -558022 233928 -860417 204903 939149 492221 -414739 -335054 107002 -481886 -138160 -947500 -75882 672543 -139447
-348424 659193 -129187 -74693 -427751 898171 203120 -728607 -383208 374313 353109 -479555 242133 -49345 -699579 -353934 -472738 -638308 -146822 -419005
-962449 206858 -389406 546374 294837 -898561 914021 664042 -383277 -791109 -671189 566691 295739 -828980 -385166 315177 880259 -206889 123901 -697588
-811548 -206562 -623425 661187 782703 823564 -664338 -912250 -870249 38458 -222312 797572 738525 -139941 356400 -870185 413946 -506603 318014 189740
196653 -978491 -84497 -816043 623825 640687 400986 859782 77941 309550 257209 -545410 -165573 631933 721835 129432 -928211 -811153 130118 94913
-867404 -452398 533064 431088 372413 -651385 101260 -730699 -765090 509056 366282 421099 -566441 -756661 -231395 -709395 997380 834944 -527507 -500839
398956 -1535 -894160 -948904 467551 -685006 279666 660115 -334697 230520 626409 -869047 379419 875490 373144 -865408 -548046 -24248 -571333 -917817
-784145 -938901 -298747 550828 861376 426640 80191 -18233 992836 918945 -884431 755521 642895 -205569 871648 651643 77766 577258 621660 -212889
-983533 -487914 -59759 -817922 -268714 553698 53746 416057 184215 789910 675945 -188079 846386 807666 -195491 406465 711465 -469012 702418 -87347
386145 937946 -400670 -31723 452493 -716185 -854931 -936998 -375484 -754651 -438456 15768 -119445 319377 -482125 -499842 31165 -327453 -683431 -635573
113207 -168457 455773 -810755 -971124 -535378 -881368 127725 -755497 746881 903687 -678355 796503 -108052 -305930 -712500 916690 -884479 -441787 625795
311534 760433 493837 32971 -967261 57857 495480 -625228 966335 -32986 754846 259121 168960 516446 969813 -658457 -621419 -764809 990047 308615
-811144 -42191 865540 -884248 -183463 -893664 -577426 884637 -822463 -764144 626439 -278467 265696 -908522 -386285 -879898 582047 -790699 410832 677594
902094 -124396 -858636 153115 -733673 -853283 -956412 -674053 505508 636915 280971 -877195 -102456 -314675 135773 -683789 575853 -421673 815208 -134221
-588022 627962 -205893 -972983 503974 -189615 592422 -339835 149592 228198 261800 341994 -308670 327392 338078 -529312 -820057 -673522 -939635 -632734
-243678 -983243 -976497 -161880 367138 -264318 -183576 -624833 -622950 -447577 -601775 -198135 -406874 -168970 -743594 -635062 -245095 906951 988976 -508869
801160 974326 663462 -988825 -38939 931759 97366 -909556 490461 643555 595045 -652333 -914325 -437344 -121447 781951 488783 -800984 214453 509372
37170 751708 765385 902576 -235415 -151072 480217 -226401 -210939 282832 258865 -841640 328348 883807 557334 518265 82685 -771146 -182730 568692
-238791 -79900 391056 -390461 383879 -883740 -260922 -886614 723015 -886142 805342 -467081 -242745 856300 -721691 327815 -125346 -27033 -709458 -142686
710108 -312141 -398943 -615069 -509489 423532 -731925 -718466 461308 44896 -591679 960427 -843987 -23383 -326306 -414459 -865279 -795441 -295815 235526
37133 510999 -616353 278505 74573 53578 828891 -627390 371123 -893641 416379 -304391 -858049 847987 -834732 532045 -402997 207022 880703 522475
-291449 -179 930876 -280562 530531 725620 215663 -399509 129425 880052 -439945 447939 422300 487940 -776219 -236520 -768766 108054 -993281 256581
346531 456401 651453 795918 -59971 624414 -926880 -483447 -832268 420470 -723935 -457542 679532 593484 603328 -22913 614239 300451 -676816 -537707
404459 -685579 37591 -775749 -190053 73955 958950 665225 -64754 -441417 -281764 57784 950039 386090 985063 -509231 -273268 673497 -732921 409987
-490257 967348 521534 -807062 -242284 -528779 190754 -337983 -534544 -470137 342894 452474 456600 419514 683780 947835 586368 -665966 950922 -610961
-844890 42886 -811409 69264 501714 82454 719156 915903 -51820 407441 -672737 122537 -561758 -808129 -477224 948887 397062 -377733 -18771 -386699
-820888 823998 188893 287958 -549588 937520 175301 -612848 -856965 488113 -960491 -142816 -140528 952966 -531177 737281 775890 -471386 -588313 744215
-91478 810677 634803 820087 265590 -666005 -679565 376183 417181 -250258 106684 -48979 202061 -545900 442978 -577459 931653 234171 387105 887083
-71684 586971 -146355 -281347 451675 -444299 -34212 176020 338270 774970 702920 446317 602620 -796825 -129477 -197478 -15319 926649 212723 -699051
874069 53580 40940 268468 175092 366283 -865506 376517 521152 -874263 947274 -848869 -955970 -448269 527613 -711418 -732301 -747111 508026 -239872
-213969 -533000 -656029 729695 -667626 -819964 675759 461043 -492453 -99856 -209446 -317550 648497 244065 332168 575891 -344660 600644 158191 -776454
-55302 -555642 205227 356695 -159419 -326526 -576655 -798520 -122535 -911165 -719658 -334185 339716 348721 959917 496346 -879563 749129 337197 691363
682617 -135110 -488370 -269941 265741 483440 113931 -102950 -897898 2797 -701712 -785532 788853 930268 277613 -568455 -76096 422059 -159653 -321230
496065 972727 363918 -696445 629637 -575054 883784 915363 -775894 475374 -993957 -159118 -660083 -644183 129218 974471 353615 304799 -502215 935711
44073 -729178 385561 -670293 351253 -735474 956140 919931 16290 182756 -861477 264454 888933 792939 876612 -587468 -891447 -535359 12015 -732610
744347 48667 158644 203214 955416 -155864 715861 588993 919331 906651 -486163 -186260 -863507 817778 -490993 -805969 166214 -203599 -678167 296462
505035 -371107 -72256 469681 358785 479971 599082 995923 665713 -933435 -503512 -793424 49153 440445 -92612 964870 -740172 -472190 664323 -541491
570461 -825516 -597461 58502 201500 590869 977297 208084 334302 636913 495034 -632820 867896 -598062 -981355 -292328 338133 -636935 976384 964816
377204 239556 -840754 -548561 403415 -314816 222045 332303 -266313 510234 627703 49836 -304791 -284767 707167 637164 -31569 971879 -745737 -349631
-773428 -575607 -311870 309572 -653505 298720 833559 658663 846744 -246838 -107440 -426119 -557230 -269564 -305407 -73239 -914945 -220242 -864267 -106156
-715744 445531 -657274 -718809 -407344 596797 600074 -568463 958543 -163580 -796976 -234329 625032 -159293 296668 -970063 64136 -2181 -82392 650155
125577 -281936 593554 -833637 478280 556098 -666672 576520 -490967 281676 617186 -105210 -885756 -686187 -807682 -175192 -264082 990509 211828 -814952
639302 273416 -347068 400310 982275 -123159 365599 118263 812456 80810 -158972 -493833 -40675 210722 200139 643303 -748358 945419 -951967 511528
812125 812633 -895744 637047 -420206 339713 -664247 -487573 302307 -469951 621971 -290068 -693530 -153433 85974 563964 672101 -306877 -373259 399820
351241 467217 -161354 739858 -719290 -928184 -604612 -299688 -413042 -911620 -369803 460707 -81175 -96549 -798728 -370933 -764871 717314 -238328 -761919
40874 390929 -123910 -888784 -206313 451012 -189385 -633498 302108 771666 -425242 804748 932990 596809 -630870 -812609 248301 437576 -572016 -478122
-97632 468327 433052 226950 720883 -696988 -5326 101927 -891057 -53364 -502560 -384046 -953247 591584 269233 -513213 -839725 869599 443779 909702
978346 -698488 -229764 -161367 -907816 594815 -186682 653273 -160473 -662887 35804 158827 -105487 -497559 -359818 197371 349565 897620 -597331 245115
-515793 -787212 523905 851828 893816 576911 79139 612259 -194180 -259507 930434 987318 698962 -578344 747546 748725 7455 -60925 -886839 406715
-632473 2634 -681726 -366613 -730606 508116 -655379 -343470 324318 143402 571121 -115255 -680258 37199 -985372 992110 293583 433820 -305227 47146
-683638 -743968 470475 -309443 915523 404499 82385 -983283 -510854 762139 380254 35769 719535 791108 -248881 898680 427575 11169 796800 -120306
697401 -416487 934985 889136 858565 -613603 -210038 -170964 -381042 -780437 -993399 -470844 415759 -220166 -544471 -618128 -6947 -169507 -335124 473792
52660 -759602 -620862 208309 -640976 499447 -761114 747296 893712 694416 865282 -187607 577834 542241 -451043 -530782 -666367 343712 -548016 298795
-223683 -409141 -511900 200733 667545 -241325 228236 474047 -427545 -507857 -677564 617841 910159 585031 -992718 -39611 867984 39632 479999 771539
446095 -429226 177036 -55104 -772680 -786173 626722 -387947 524190 372695 -771243 -934134 631918 676876 928958 104451 -267113 189823 157198 -100563
-621368 454063 -449143 -498860 996508 -888450 403345 694401 694908 806597 -998598 -314584 715318 -55027 660738 -911629 988979 -701903 -863681 424211
-481449 165878 -959152 528578 -697602 99069 590471 395208 -290468 -168949 50420 -361548 507245 -753092 -225392 509441 288839 -777758 -290125 -168231
-492770 -181476 936633 -898131 258668 904424 -859799 802651 600595 301627 636562 507697 112920 -977887 -179862 -778701 903192 921041 165857 552530
-82481 -75829 914738 -892206 327518 -744246 268227 -807735 789910 -683029 814252 -785854 -190284 733317 -799609 -413863 651579 58155 -395184 365486
-356274 -983598 287755 859287 533381 -213058 -687308 -807704 230633 -751434 237036 -225268 -869135 -820811 63442 462189 -129593 767016 63497 806870
845822 37688 -320715 -996688 670129 748714 634361 862673 724838 -126958 890832 -692756 783103 -662347 -223435 569866 204750 -788504 306138 -541376
-90907 177753 974802 6980 -163036 383931 933856 -634594 -283156 -23886 -676950 -36715 -278385 349463 799368 -746993 596093 226128 -455249 -93905
-585860 374562 714276 608958 426515 531697 710301 -254457 -386974 -170437 532869 -608965 207652 -96102 -482137 434485 724545 287930 358001 364213
-424586 83718 158879 389966 -379336 -728639 -82895 889272 125583 688506 -455006 -480590 845534 22175 583133 -839625 493536 499273 459463 -976873
-868644 285799 -303967 269412 -357903 -818434 -666543 56716 918196 -232547 -816641 602446 89398 -738180 122531 390672 -591232 -515977 -422136 964815
631537 516802 -655742 -222764 -297873 958439 467190 -457752 -538332 367096 316910 -203027 648251 -984150 313231 -336284 -315061 -309130 -335815 -528629
-744009 788908 648497 262863 99127 -92771 79263 -640158 236023 -192540 654766 868991 -715293 987822 682393 -621319 532425 -958521 -499121 -422592
925503 93466 319958 -896215 517100 199876 792417 -350988 325634 -446274 537779 652183 -630058 -445901 247154 -479229 111532 -421660 -492465 513986
-377456 46400 665153 -241428 502025 -633778 -135999 553371 -498140 -212283 714666 94917 -780731 -343416 -275638 -636601 -108870 -87438 663680 221991
611748 -752550 -730992 -23392 -330342 927170 246350 851289 129482 -458879 414034 -977409 990189 786604 66398 209070 681167 502729 -674599 -137057
373190 -238841 -905006 778803 -484371 282026 544029 -648563 947208 124443 434385 -822730 -767642 -995437 -230989 -594812 808204 382480 -564320 594111
898982 -55971 -452636 -92686 86333 -918572 -946661 219058 -974958 602947 -7553 529951 -99039 815952 138966 -867381 869773 -405784 -963052 -577652
-719714 -850589 156803 -139976 219040 170307 -553891 173613 -348899 842740 486968 559800 -601864 -715973 -206110 764792 -213166 461822 -16533 443887
396922 110827 -400268 -878148 923081 246765 -21168 -974562 -44399 697106 83567 -328446 715729 600927 -440301 -216158 -580249 980466 -640397 71598
-654049 352472 469536 335237 -282332 -712726 707136 725161 -39624 -559149 775585 605926 -697422 -309990 -352772 -988613 102816 28129 441563 -658986
333646 463007 308762 -135585 264325 370624 -514727 731673 466435 611508 683033 920783 -275037 608949 -356273 -851157 -655952 755299 823431 -631837
997240 -692830 -138937 -769507 -39428 -996307 229062 -411938 -108242 521799 113764 -773605 315002 -168929 -944557 -810983 -288765 -406876 -226859 -674689
571730 979218 39469 -919173 986482 263608 536655 637643 558507 135957 160893 781362 243904 -547161 -563459 274315 811020 269596 670794 -84793
51473 959057 -631905 717122 301236 -358171 -570964 322368 -284195 471310 -577006 527749 592362 -212310 -219849 -265107 267803 -418915 -879622 338817
903937 339551 -546212 -358196 -93777 511030 576681 628779 235606 661122 425740 451754 -377920 -845886 159224 831681 -958810 666664 -723523 649018
-271876 877050 -791999 -301145 418233 606192 -68162 -732745 -724102 -164919 -816879 -791838 987600 -268756 -590586 -737679 692739 300273 -311737 -218502
257760 -79445 -913335 -433495 189357 -175697 -610378 48078 -792593 -823865 -582312 290376 -701752 -835944 -699104 -414716 -716650 893009 53524 819658
581105 705629 -813356 684276 -112348 -945631 -918480 -939480 543822 -949143 -161736 -213215 292266 712560 361584 537757 834912 -77154 -924049 494506
768796 101396 968541 -22333 246240 142833 -319360 630340 495513 -516268 -632802 870040 563229 799985 319867 205605 -988877 704136 566431 753619
-874036 -311962 -581423 -141959 902095 79840 -650856 -772300 -176013 -414784 -688283 -14822 -247827 -101997 944028 -15035 841943 9013 704937 -105433
-441609 -152072 -287495 155413 628522 175997 734647 -579444 708983 -886022 -982215 466293 -868399 -521023 -550935 -541919 778370 769560 -483761 -19512
850864 208366 680757 823862 718424 -999717 737409 -155920 -628150 947471 -861631 -882281 -752530 15664 38892 239395 910986 675031 -866548 282813
808762 221690 -933605 -925048 953982 154210 938677 138138 774460 203117 -920765 -320541 -770474 -266915 -627921 482826 953668 -883795 -916184 469490
-513028 335705 -770911 -774642 -659168 152781 -18216 -147835 -344196 335972 -645690 -254586 -578109 136235 -512257 662137 -699772 493267 504615 685606
-960152 -620523 -789753 -778639 -696150 466356 -608612 -264245 487853 -672301 -654202 -380251 187825 -18876 38450 147916 577030 -993731 271112 -411653
842075 -686117 787782 -41527 -391158 947185 -488490 -186295 -674882 -634191 -354824 295797 989326 976836 -270689 -12139 168646 236833 426369 -426935
-864754 173594 -701441 -331717 403977 306597 -868109 64335 -901759 927182 251323 -163641 487476 395811 564899 858440 540167 -9844 128723 471031
592850 352859 -851104 -491991 -904136 311925 152110 -431476 -744657 -115203 853244 345597 -85690 -624359 902006 861182 -336929 188157 305730 -744911
-466402 111572 -122524 -474588 887405 109667 -116553 933306 45454 -211995 692970 80017 -240809 -117792 438213 167184 -792898 -49354 391283 665996
-662431 191620 716870 -801734 928823 254030 -778757 -892874 598397 506196 929042 834429 -427580 841962 -627647 433605 -378794 -456017 340004 427618
574169 -802551 968112 385174 -70924 -424213 -791908 396203 78003 720845 -769794 -769592 -326101 884724 -122408 735949 567138 325144 -573962 626979
-518114 411638 58976 -390620 902685 221636 -653825 370977 -907564 721021 -95242 566296 -220279 553047 -361419 -943656 43880 -931359 -932935 -573123
-689584 38733 -209625 580967 -929983 -70555 677909 466834 634134 349128 -969796 197936 26558 215237 801130 -875096 -963873 -17247 -800867 -387019
-611171 844803 105318 834686 992688 363969 -756461 -242494 -383871 -524685 -480339 892542 -861876 -210504 -966230 418249 -52122 463226 -179103 340458
-417701 139425 -871502 721943 182125 878779 -263839 982413 158131 847136 -478504 -893411 -592997 -218621 -67427 -315535 -232428 513663 -433487 -308235
205394 -871601 758262 -826858 357984 390643 704587 -200978 495616 705380 502529 -438687 -97002 -108735 -824157 -738241 629705 316071 963032 651280
490844 909420 139448 -91783 48663 617501 -499769 -564514 285233 -636992 -664393 -191260 -365196 84632 -364017 658387 230023 487792 -465707 -30349
-170957 827232 -212555 744521 -343476 208664 83126 -354137 -600494 -287786 351048 784548 -184230 681383 571111 -582395 -435996 713714 -857502 -469746
-513853 -328874 -498288 -425082 800054 -661343 810936 652689 357034 491043 32768 15592 -376565 -824876 -731144 -370862 850011 -597633 -147583 634683
908520 -172489 -561155 -85197 658236 -940912 -33224 943550 -873572 615817 -118366 278691 456840 -169160 68771 -870719 -503225 -646059 -243746 -376549
-394106 576540 -301508 315605 1651 -213100 -455185 961817 -940119 -85321 749997 -493322 -759120 -506054 -137750 607996 -455681 108841 117982 690620
-442529 -540745 80198 -929695 -572213 354996 -966670 -308354 919053 654160 98227 -730994 445342 582143 -418205 -535340 -879295 -198851 -497439 -820546
-37373 -70899 -894466 706859 -654744 84474 -286558 -756510 260142 40465 245840 48949 -264728 -864571 678180 513886 669134 -256480 -305883 311892
203895 201348 382202 -68215 -972665 510450 594356 -305039 -527791 -833049 -57098 -649087 -372730 540777 18861 -521667 591310 -300101 -126952 -490348
-956608 -296112 689011 -805681 -938413 11545 -743362 668496 659184 437216 232766 201765 -519357 828880 58741 -437864 -261784 -779838 -756962 80685
-507272 -49566 -871299 -718692 828534 -983968 74048 765877 -53472 357300 176498 663587 582157 732609 -852497 507196 -678759 -308805 846723 516875
-796292 -157063 -60671 -819467 247364 -648420 -551043 409537 -992162 136722 824701 -812388 -607770 228737 864831 -558727 60505 -335729 -401383 -407916
257044 369883 -249710 253932 -727357 889462 -304709 -30744 -986600 -246219 239977 -589641 853901 456941 843043 -831633 -408706 337087 -928525 -914051
161591 690950 -763672 -283851 259195 305271 249941 -960350 -860696 -821574 227483 -708039 -794741 -593462 -106450 514082 915449 -77796 -224944 170788
-184530 666951 681584 -619777 332632 -344578 -271728 -913990 286765 32530 26994 423784 280103 -486305 463497 248298 15590 -654513 -972962 469819
-858260 -462444 626196 775982 135655 966926 -54398 564383 -101519 851513 277405 853164 -880878 -189764 851137 -798434 255060 -494889 757609 676049
587477 590318 346366 816283 -866453 -186928 922686 961331 -355286 20993 298660 -906485 -120241 -75577 -797334 -590896 968210 -135959 141560 748038
-945101 58871 839931 597182 583108 -440949 892041 959679 -781077 325386 -625402 195176 639629 -590913 -51076 253058 -779122 391866 209056 295919
297119 -285861 45941 -374139 403527 -574700 -903665 -438911 266482 -593220 -101519 -640697 947479 -36272 761032 -800292 -820202 529067 471812 455081
156748 869830 932731 -291614 -706434 -941436 -232594 398223 519383 -512211 -845838 -710523 921372 -612184 40094 -827368 -266869 -680270 366541 330756
-29954 -453540 -732195 522457 402644 -373953 617674 929781 -334089 -587718 9339 178615 -284268 317123 698905 -536562 -124462 -331484 645163 820720
184597 165492 -792658 944806 809887 439291 358245 410257 -625197 -194597 -358192 88927 395022 -299806 -570541 -66209 416165 664802 812051 -330070
-121841 -773438 326770 718178 -160610 -630219 610411 -933882 652844 96262 -682327 295845 727441 -309228 -956381 272655 282295 136238 413818 256924
-924004 432980 -961590 864269 -638764 -283435 -54452 600330 536747 -123926 -138323 -884705 -371336 -915647 904668 59060 -414214 -852683 -166342 2441
366469 -828087 -549302 -535210 590057 814844 -778477 367314 711285 519007 -785004 -915282 -181543 263825 -626613 569420 680515 -542723 -205433 705876
-745378 314778 -713667 -155729 299178 -181617 -441625 -232608 -281135 -120318 -678899 203738 439893 -825890 51132 -813681 242565 255299 901620 -947425
269708 137134 -515970 -772855 -409475 -146256 986176 -754178 815650 448598 -910914 -431097 -313313 235024 -635693 -232189 -369583 321136 229148 355139
288983 594724 485203 -486997 127117 -923264 934943 958951 567111 8165 129155 627969 -50887 -439098 -621667 706830 3131 61391 840254 -220160
861229 975586 -154578 268283 948153 -330170 168523 753727 780892 -582796 104680 843527 -124031 112068 -702868 609174 -734045 940471 -691323 -528619
-502549 -88839 -122568 -844526 -978497 -100302 440561 -441492 719656 -946503 178727 822666 945281 6912 182688 311234 -866854 718246 513783 190831
123980 332918 295057 -198241 271957 932540 -500120 742073 -274614 -801255 -908339 -565522 344705 56927 958588 911963 -162055 887290 -247183 62307
313673 -512945 -753650 -68353 -827806 -486989 -989699 241983 716960 -690450 130957 246997 193746 426955 -879307 -103931 -830063 -794392 -768556 -857181
306999 627122 376579 621314 -219372 -782596 -53392 -445750 933909 -420075 863150 -634805 41510 -400034 -776220 -696176 -557726 -981207 -757542 909035
-71964 -17186 -338790 -863132 546100 437971 -47915 310463 -731485 165718 318938 413639 781979 808755 419760 883631 220087 225898 802363 -986443
-771349 -487737 354995 710730 -835841 -567433 517816 114919 410379 -850961 595448 -873535 -683220 -408243 -197385 529621 634553 802262 526870 -897921
483239 151169 -450171 358556 107422 -947156 -447200 273225 558338 716908 101504 -82657 -155335 424434 -40745 -806746 -612136 33550 -994656 -473440
-427241 612282 861369 281454 376633 -240361 453080 739575 -194560 -2165 804632 -546983 465964 625725 -182245 -443229 286117 255819 330329 483936
-660565 -866891 -241458 -410662 -923360 559010 -222894 12283 -886233 219639 865027 -682619 -86593 913994 780007 -330084 -662550 -145615 -619717 -99319
-243816 477558 510239 -758865 537417 496016 -140892 644161 -875643 -375716 -53801 -822887 -221567 -738325 -201789 -918663 340879 -757396 -526103 925815
-127231 134060 -850054 209565 -993420 -967576 -937309 809771 445799 -746947 -279365 -31807 500559 412560 878544 -510422 691395 -336528 696777 692417
-233433 843251 -600211 536551 -260397 -963069 -637894 341988 -483554 -301988 441176 169243 412546 -840810 -540295 -856189 59326 -240927 -296186 188276
907393 -917878 -917017 289165 890856 -308178 598019 -692714 -273360 -101916 770193 -355158 -843089 42705 185189 664592 996038 942512 -889596 228971
-546983 -339424 133896 -895788 415128 -997648 -325262 -230832 -238406 -113613 -970433 -942718 -339744 70433 -856116 378232 492418 -185520 450171 -594194
187539 -818273 535605 93230 -358857 -435710 614643 694129 171855 62974 -390006 -271471 -765389 41478 56892 -544576 589952 304359 416408 250234
-235409 534646 -177127 837487 417049 663289 -126061 838192 868452 -968124 -546986 193554 -892677 -489357 -659065 26229 -248314 -104530 -133325 -654853
-112835 -70179 507083 918572 -872886 -471779 -477318 923043 225389 592706 -308204 -318978 -237000 403424 -686309 620608 452132 -459399 -846243 179070
12348 611364 -571400 950813 -553768 10262 140351 261400 -437145 -562990 899810 193212 -364144 -439116 -539328 150296 -921239 661976 671321 -582908
103325 -125568 792003 35000 -594621 -609663 -92323 994220 894688 371678 -513061 998714 -442231 -295371 265614 428765 -24637 -211921 748825 873684
-230449 -721542 -486474 828637 -848528 -460144 -903280 -910329 -473921 336401 609490 159941 -441047 -953396 -180761 942820 844169 568385 -151713 165146
-973667 -678804 608734 -428870 32273 744491 90150 741205 -824039 -333285 969522 398115 -52307 558909 -221321 -159905 197711 859457 368527 -179065
328469 883421 126807 850702 127474 -523797 447083 423686 -134211 -232900 826973 626333 -299535 -607498 35351 668318 871232 795157 -271070 379013
503022 159256 -996690 -504186 -753089 -466580 352418 502813 477972 -65997 964502 39732 638979 -924616 -363293 88527 423626 722535 126667 605173
-892609 378616 -307665 -916385 851514 -412579 537602 -278371 74731 -859040 -565773 653715 602846 -477279 9398 -894679 572431 955101 306000 625609
-262169 -705751 231679 626993 -239776 429366 -952659 206371 564457 240803 -660204 646279 -232061 702812 -845139 133311 639985 538997 541158 75910
774292 -723020 -53142 986205 747763 480578 678883 -617080 757167 260711 250547 -619075 -399013 869865 672297 41304 371892 -729467 -959164 -23331
-930430 -155148 330000 -810431 520175 -131398 950300 -404056 567639 100990 -60906 426575 400476 897824 -707118 761436 -303230 127520 544268 -170804
-961979 589386 957193 353309 -48425 -815519 -139086 -808351 -586305 940561 -935253 -351942 284689 302762 -213195 -413397 -166953 -319551 -351150 -551455
123129 -344135 -447995 -865238 63267 630586 431459 420641 608614 -114792 -755008 716920 -952617 804235 969046 9755 371979 -948778 -360165 -555548
-442165 -909708 -910855 -965190 -4625 -840415 -233691 113201 -468990 668162 -549717 859737 779060 -178259 526735 -433560 -640185 66229 497910 -649872
858945 -177716 -500964 -434227 235744 449391 -629834 866278 -335538 409007 -876373 478113 389326 -334120 -793439 -675799 586881 -511820 593712 -365318
-914527 852816 553759 345786 -144370 -375786 -795038 -195852 -381609 -746544 -459366 -338173 450954 -217346 -972092 913926 617169 -127338 513570 -114866
489451 202711 -523544 -613523 668365 -402431 486199 32443 860150 2806 -891230 572666 59378 335767 279265 -918080 -730314 -138467 -168102 -594163
-654914 -179328 -183725 632374 -707020 -984348 850308 -587533 -398149 -235753 -868601 -279132 270778 -649988 -706081 -140888 508560 -393591 133698 -340377
-588359 -153389 406141 -150182 221294 417793 403986 -294711 -843129 585235 -208020 -602077 639648 119488 -736288 -919822 -181750 94616 585997 453633
959033 -819232 -728517 243925 644747 -399579 -127143 -545424 784652 882235 -158025 771751 779608 119928 -677472 -170422 112090 -964 412621 404183
185286 712426 150510 591983 -556827 -976309 -890376 67648 582491 322664 647149 225340 695765 -470310 -447197 389437 698496 -296517 340807 59614
39497 -30278 286145 923322 -515297 175181 261043 756284 214617 -875494 -459245 163455 -694357 355979 -638083 209678 -367625 433348 96489 99671
460064 911058 -379295 -463123 -723170 933253 295743 182137 -235523 -822096 -59765 -970264 140852 -711033 888297 528583 -699421 647491 -716261 -676493
-751831 925018 -676999 29963 -952457 403395 581825 79487 329037 130361 346274 -870961 -804099 332429 -628376 -333306 -744178 -837376 18240 701519
-391570 -710900 679566 -403306 -261539 488255 -853127 -515811 524018 836892 792178 -89637 853044 227741 424181 298823 816642 121489 -530479 -498477
510256 321599 912711 -839060 514980 -414518 -72340 862501 107122 69607 -724906 -783706 939996 69495 15606 -52556 652720 -599189 -662551 952651
-582306 839107 -317591 243441 -131985 142729 -371540 -9423 459072 -780785 -469225 -988949 -856394 343083 -340939 -124727 729818 20852 814284 -962808
-977614 898004 677342 -188826 711765 -256464 409984 -641291 508370 -144821 -199738 806057 -433548 803643 208349 994685 333878 -379006 -208898 66526
563416 314818 -686578 -657492 344498 -371839 144218 571488 597233 230590 524919 159296 113720 710484 -509229 -667799 312423 488294 -525912 566369
-419771 -448409 -797576 -802739 538368 159135 335005 396533 497774 -12895 932612 977453 -297857 807556 -2446 174310 -945828 726049 -366296 638829
570678 -507616 -565832 -295097 481385 -148442 711993 -592374 221098 -397891 425844 845495 -565487 -42849 72810 487933 393117 478018 -399563 830626
488017 -546562 -68473 313094 128066 -115853 -626765 985135 -628533 -314578 500869 160877 298109 438520 -702603 72121 255787 -538613 767861 535429
-126372 867882 -319885 -343677 527713 -938149 514882 194897 -816924 840709 -311677 -628428 -912338 990631 -551531 -26566 -987424 865240 -772334 757589
-903610 513142 -747154 313380 254942 795504 655579 -89671 310738 -53516 -570307 -856972 105894 -26403 855469 869097 811348 221606 -99447 963614
965807 -814724 366934 -316146 880498 145550 -995709 -859621 781061 -870249 -39003 153962 308607 -976425 188694 955784 771158 242038 337993 892058
902200 294589 -920425 265568 693329 144524 -732108 384338 -71124 -114214 -357575 -53927 -250632 66283 265050 -209133 961989 -133314 19105 359658
-84580 -120666 922687 -146651 733340 607532 -396170 452044 803344 755643 -364437 -962501 122343 658361 -753734 523972 -752288 -554369 581336 -315958
875870 415182 586899 -48910 -544927 -275686 816799 421071 168507 -182599 -302983 457522 -337766 -546210 -443713 964698 -530624 903831 579342 -627668
209765 31146 798263 -520183 -21800 8831 -620303 625123 357243 -142478 502724 -233200 466095 -513262 -577858 -727819 361513 -826004 -680798 850908
-763759 51099 -467630 -749028 623037 192199 238251 -108426 -230403 -999966 42486 -970492 809227 370195 86459 -34835 -566672 142449 -117587 702583
202661 -588798 660432 854104 -886595 99344 848180 -85068 -49262 562841 -227149 635927 241332 16934 493992 -747363 -917032 744268 -856458 -370274
40904 -229140 564849 -962020 -578576 467924 -939102 -51178 677875 -965492 -652707 524697 2762 19443 497904 995103 723893 639325 -782316 -977455
956966 317130 343870 -353214 307981 597136 -67804 873075 982845 -260113 837983 76688 -427854 880138 931152 -924338 20900 138380 -209834 -353060
-162909 104377 107931 647470 810237 -829306 116721 -858608 975993 -93186 -224108 549931 536946 457335 -214677 -686086 -205558 590346 112881 289413
867075 954673 -363614 -356033 545853 677711 760089 -867445 -559669 -76112 66455 -957850 897373 195385 -956321 -422339 -50944 -213622 746021 -582670
-557913 96777 922238 -128204 940688 264734 167953 106548 510444 -257737 -566555 -132690 239921 -820851 562287 -375860 830227 -96919 -138464 -307805
182793 -97232 807440 679440 -132273 771086 -800107 247174 -688137 536188 837616 116282 80983 -730590 47582 240128 -558972 -942039 -60731 -466485
586088 -432260 -607003 -333689 -394006 321725 -80385 -138510 -794466 218285 176692 253924 -110505 -914539 -252984 -36323 -26202 274801 324612 -718187
189160 660239 -729958 -512414 346153 -434434 531641 -590104 -342004 -133269 154448 323858 852813 -32016 -15082 -803242 -441248 -845071 830150 -54320
-727819 -354685 -690518 -481609 860457 842415 160137 460255 155577 -2540 23983 -29660 -806834 -742134 -893489 5861 -905987 -254706 -563517 -360527
-710279 844741 -717619 819745 -593575 -382945 824319 -55813 -858114 -303055 -996666 611101 -842270 -978680 -372677 -722151 -388252 613627 -159014 188316
-319762 875348 -450472 -464392 -662362 319289 -385479 -182237 -449635 979023 561439 -193106 -747144 614446 -531262 -38331 -326799 765821 -832045 476161
-846186 76997 159393 -706906 468495 103276 -1676 -874819 373170 710854 576628 315258 -850703 -941460 665877 375173 -837734 823770 -874736 -505125
-296995 -474151 -972813 -486531 895017 519236 656167 -432639 -640303 -928344 438985 -624739 80651 902617 81221 168431 542653 143469 -286187 189638
653736 601711 -889450 -418544 366341 678520 -830256 -102692 740565 -519906 -600233 393934 -888610 -518321 707368 -723502 377688 262616 -932945 -565816
-60994 -409056 -642787 465757 -327496 318686 263397 228294 759234 -42660 -656796 -931508 -901045 -87212 603772 -812327 893772 -195379 644357 161571
-522938 817891 -775834 -501310 596153 135297 -113587 165494 463477 -169384 654783 -794005 -866798 856094 768567 296561 -350890 -323961 844014 -730027
460525 -404055 25 -925974 696675 -886977 -84660 -366353 217399 443259 335406 935568 64501 52683 -46476 894218 -894975 -531533 -968478 -746553
727596 -310729 536433 -493049 -259413 861708 11309 -100378 -870208 451909 631916 -795432 -408690 197564 78300 771926 -831510 -38784 -777988 -959475
-617328 63136 -64175 -755031 -385932 -819764 205093 317546 348874 610031 -868373 -801069 -163698 342333 -734127 401563 -687481 -404613 -651516 380109
-356009 256371 -740662 -914416 412345 -342276 77350 -296341 569471 89614 375820 64449 -895895 -354104 -404986 -182343 -951411 64331 797290 -458004
-571575 -39464 -425060 245686 -634655 785671 480966 -415575 200668 -834140 -244726 717487 871042 117415 -476519 -148208 -532920 -316390 -940319 492641
-328062 -386472 958429 971884 775990 313926 -502864 569542 253923 -790157 909979 663279 -682154 830563 396303 -646005 460304 351856 57655 -512289
-706472 441094 641753 -448393 -718579 867250 -274802 614183 -398808 -136232 853824 -303586 361453 -956552 -207065 -641659 780565 481035 196129 906530
-806532 -804301 325714 -77652 -36425 130103 -792620 245994 -434287 -957260 -859747 736589 452812 258051 -362427 361135 608660 -663050 -267274 541504
-348163 114518 -482444 241115 -487686 604132 467140 174372 -876003 -191818 885662 -488430 -275352 -90886 67761 -983208 626127 48929 -390965 -816023
105809 406561 -987600 317231 -162325 669019 -850254 429484 813521 -319141 -379945 -192453 941054 -217852 -606564 -484912 -182946 -923756 797235 128626
487265 -42430 731091 -958669 -558965 547203 459763 310509 -623063 184101 -963937 -986618 460320 596112 -54006 -936605 658621 509746 -222883 701603
-385661 198581 -766695 -26531 -450610 806442 -721806 800379 -879746 -36243 778633 -297516 -906466 380664 -515343 603394 -216279 -445177 933814 -716821
-560121 -449342 -993996 828758 206431 -948285 99423 -277076 -452920 -119198 -951847 -262755 -707889 489663 -131732 461294 -699906 -852339 -316003 -647501
-765648 942578 146290 -412864 6386 383418 -887351 -619953 -684117 353239 -979522 -295514 268167 546399 -233364 470880 -866289 994097 196150 646231
938737 -697284 487296 495292 458502 -939215 -806571 799399 307801 -677592 825863 -635047 782060 -420346 -823722 302414 497777 -542189 488039 26223
777391 758019 198864 132404 -457671 700429 -612176 -743083 -831811 -664228 643997 -502221 285857 176521 -495927 903379 -794454 -139160 -199918 996694
629014 -121231 762413 345884 -122671 -520124 44393 795931 448076 700866 -284766 975151 -226528 -683536 588431 882495 536458 -899534 200618 -342298
834061 209639 -116859 -717110 -65316 273547 -254505 -76586 -230082 -949075 718034 -569856 -635589 -493997 457735 262314 -706416 -189015 -956626 683638
-651543 -925857 676895 -500763 807136 558092 598950 -625478 -977998 130389 -691140 216561 633535 -347607 546770 -466779 725549 -151320 -288888 659256
-126077 177512 -908686 -129263 -199934 -487525 -537070 392271 338679 761213 770643 -605591 650369 952630 92618 107807 -898501 834504 -592177 913077
906314 461645 404843 -246416 626072 -961177 -415778 618152 985034 196961 296841 448428 567347 147270 61583 595011 187811 786908 -661504 779004
-919369 261382 348033 -785583 -585373 -286910 -400075 584756 747642 937753 -788431 -427611 -762010 10162 701084 -675086 739962 -92813 622688 277233
-16605 -111829 -804129 745448 57558 703837 686302 -696152 3763 346691 292743 486274 -291894 -689244 -24820 -224619 406608 389507 -362443 527241
945617 -368661 993759 -266903 -559660 865190 750883 -67021 -91620 -723013 508293 112644 417168 -38541 -170493 -263231 62544 -19240 -827541 -620290
-513015 -413886 -355041 112522 -83496 84526 -638442 816654 264845 104677 719355 -518229 659353 550257 713340 288843 74100 -559290 333643 277511
535359 -822078 -808853 231565 -420194 -597312 555558 220109 348304 -712317 -875689 64162 -781556 -28020 455887 154992 -89941 -114136 423558 789381
-170672 985794 683501 -964325 451763 -265502 -611552 -808104 -800095 -299865 314835 427636 462097 240304 -679346 -248267 -520361 127929 864144 -369441
-208856 348598 -160226 -134748 853601 -631571 -447225 503008 782095 -750377 230856 -90762 447171 -340016 -526172 -474922 525651 -653826 -46870 584486
-193737 -944526 -904422 -342166 -360065 357368 -471925 685413 538190 342024 318669 289179 -635782 893999 655648 707570 389190 537061 985432 516567
-486770 -191186 -366175 580142 72696 -430491 460217 536857 829044 241845 -561764 -748444 -733415 -324420 155910 520281 -787004 -748530 -288700 441870
-473546 -403764 -832051 591093 -970564 986283 -83002 598442 -49794 323358 -498818 -641485 594919 516784 -576241 -765815 -897803 -925379 712034 -185479
324282 -905248 -80974 -258715 -21341 -632356 868109 -53363 97920 168300 689544 -160314 -328183 -639496 545054 -962855 743902 433364 140146 857839
279078 23165 239601 881094 894979 -786084 -759118 427259 -513412 463515 115907 108730 524812 -893346 663425 -815658 -137124 817987 228113 323921
685055 861604 641256 -974717 721564 214964 -494951 917950 188033 -568745 -697116 190557 -222544 87956 -697461 340416 994663 948375 -301291 267281
236920 356756 692759 982100 -556498 368198 -632465 -208806 -532258 555403 35479 -251350 -2094 -282403 -617490 944639 426294 -424695 -181797 -824775
-462431 76654 -323917 269929 8368 -934805 392270 148934 513661 -581228 619540 692465 763658 -681923 786582 767554 659022 537019 -721443 529801
-227378 634505 217922 16797 532830 633707 -737252 -317619 -945206 -53369 -968580 483775 -430490 -402171 -146409 -866808 945793 -810276 686786 -810126
-427147 590097 -799320 -850113 -946381 818745 -24741 -971573 -917178 422275 -131422 -657996 437658 -600182 394990 106976 -714819 -987691 -904318 -897806
-681277 422884 683658 421126 660584 -125594 -406612 -918019 -351208 -628807 -234972 -221430 274469 -398192 -240889 484936 -825404 -27793 -103471 -981035
897229 -958777 -218149 513637 -509520 -907582 -472431 -827873 -298089 523653 -452262 274317 390802 -782901 157429 -978087 -282203 899916 -873813 213903
738380 -812633 -260543 674939 -48901 585012 -295931 612951 -466546 108181 53493 577480 -627125 402038 682855 -730061 -489069 -314922 580234 398484
-348969 -317722 -354746 506144 -477988 -393482 -937370 802053 797291 -30364 -535312 528859 -693764 781455 -893869 579087 678746 484062 616992 -141964
-343263 77551 -115141 -734268 884303 -321075 -129363 58118 -618719 201126 83327 -952940 -19966 441135 -677708 -182627 406007 755072 -974616 -717970
-523197 -888890 -809035 -971364 544424 -894311 -316255 -486974 921744 522748 427651 773604 635584 -1032 592276 181336 162791 515873 325443 -263678
-666097 949674 615130 939062 -636635 922739 -4443 779257 -476309 -52603 328391 158692 -14863 424584 159360 963097 675198 411496 174183 -123248
-518621 -476266 796354 543210 -487792 -805306 855294 532675 533761 -57613 -927574 -996100 -237205 53035 932093 532428 -596282 -999777 -357438 374169
659115 -816231 650904 -125981 -774065 -742155 398400 -931441 -958754 -493138 570314 266667 307116 385163 60930 -736641 -526528 651231 -48205 538478
-906587 -865314 -484780 -402702 827566 -734915 603260 -644396 675594 57646 -157431 -411798 735955 65744 -856858 -514475 -73815 176206 377483 776449
151991 -164457 465855 231997 367890 -86917 340421 -435691 677960 -44303 -689919 -50064 774953 -105113 -81964 922075 742281 143094 -768405 -787199
438349 802913 621023 547750 252450 -235489 133947 948648 80182 -483087 742931 839531 374912 432976 313421 359995 909055 287834 -168172 -156183
647586 -977314 259815 548230 -699731 -723732 452595 199787 -431053 -404538 17723 309732 -938127 -151117 962032 8322 789804 -937537 245344 52463
-813045 -172053 344128 322172 -341606 255760 -787966 -718519 -66010 789370 600823 379301 840650 -249566 363645 -815500 -369056 962881 -529278 581550
62306 280953 -210131 -868506 -292793 258714 -433783 864470 453806 -4061 -535646 -731319 301765 -370003 652922 -121394 358817 978736 -529771 -196047
-750083 -645585 -321073 728667 380690 384028 393389 -207479 -359335 626683 769979 779594 -480872 -93754 957190 979707 63300 353055 -744534 -507134
-893006 963099 -796548 626665 -293268 24060 183001 136902 -867087 -770241 -878552 -360985 -717090 -555451 937679 574901 -278679 867281 -196357 927425
687997 -301233 595133 908447 949904 -62252 73359 423872 223955 637429 211408 -542098 -78373 480076 -232343 -904894 37376 696338 559672 412833
313166 328870 -960802 -48376 694063 -798575 -153712 230882 -541004 -745359 11176 566484 853529 -513354 268198 -690297 949021 -530667 769841 -152451
-317836 -266119 542149 896541 -570604 -847933 210470 930818 -289980 -503455 614377 -335245 -843195 776757 948637 740556 -853394 29667 -122785 853637
-601735 891353 982892 -636995 36949 -800147 -718459 821688 775262 954545 506633 -922099 312412 -476079 -816795 376597 141285 -246257 -205000 -726451
421412 -525910 264820 825163 -505488 -867648 783804 -340717 -20211 -990468 -903049 75244 -762455 408515 888610 -550005 405469 598119 617073 -265831
755177 -915830 766943 -815547 715047 819753 5081 -175156 19495 -863271 488700 -412231 2853 530509 -701338 830382 100390 -962406 645258 134469
622414 -87674 571609 -486858 823872 -611088 -393531 -799479 -708734 852870 483825 295253 -446537 -532701 -959851 738753 347783 221704 -928261 616393
583643 -174455 -512457 616014 -458455 841421 715453 177914 -671210 -72157 290696 502743 -106120 -248853 -714066 -460606 -71641 220683 583745 -273065
624499 -698734 -236921 884704 685562 346787 538876 341615 474103 430262 406977 -299365 498501 -561014 -79366 -133960 -998090 342850 423976 808061
49948 -576070 -7020 289971 114074 759521 726747 172741 -12969 752772 308303 -184289 550755 644292 -490106 -783279 -935311 -413238 -422696 -522771
-81984 -272645 663542 470991 741811 -965670 849578 -507529 986525 950369 -782190 -556685 -1479 922818 109691 -140340 296104 119410 805376 -519472
-133715 -797425 -835245 -842818 -922566 409019 -370658 312780 -401201 -837904 632478 207991 -405813 -191206 -37973 742385 333437 959840 -832773 681891
659622 697675 -1456 -853123 -685877 918869 -956807 358811 -451502 -686325 -162117 287088 263228 -190574 -918687 376276 -62310 649194 941662 166916
394439 -514806 -759965 170018 582547 473638 -388040 -13122 -441032 786879 35674 823094 -24756 -627258 -80371 -191424 523401 -678754 835410 -347910
379526 -185963 -438160 -847095 945503 -998705 -882476 -874546 653456 -477378 -261388 773715 421390 511182 79892 -192815 123703 142566 -126243 -567826
631659 222683 335841 217113 -932473 -329407 -670967 -816709 109732 158525 759115 29887 -158248 -613808 741825 631687 825204 471326 -491291 -270328
-134131 -246823 -828139 -870318 611197 -179323 -78657 -518047 -5934 -777101 -405070 268538 553492 825278 -546310 -888014 -380699 -911384 -328223 -747964
-212836 789609 -486348 492520 819579 706688 -212256 -669997 -610294 -318817 -193179 798433 -247228 224471 -97180 306369 767459 827677 -382202 -855775
612907 -297316 -969654 967476 -373952 793204 -232234 589693 815274 -409262 -483842 -963442 -738912 990446 719231 985280 646324 149699 51603 433460
-337268 936850 533251 -137424 868136 -664134 -863243 113009 -950966 -73926 -437822 257828 -913767 305850 446934 -57013 997735 518431 148168 -805879
-449575 326461 -896786 388279 -562900 851669 825714 -642801 -926680 -725483 319325 -352908 74887 318218 889730 -991748 -629319 927292 995033 -263149
-766456 -463432 846458 600899 -768397 -374836 -343750 -96922 -117486 -655415 -900390 374710 475799 139010 659045 848128 691573 349129 654784 271916
450726 687516 -530953 -784694 -253433 -643451 -268696 524622 -611525 106681 -221896 684280 -697724 -542035 -701293 -55847 12870 -828161 10676 956658
51919 -149342 -950619 -195040 -553978 881709 152833 613858 60913 714142 -130296 -691539 -89621 9837 -80896 -534760 27456 434626 346373 618979
-738900 149196 309192 98416 -527996 538236 634972 361812 183192 -294056 453302 -619164 -88131 804236 692367 -38938 421696 743102 -480970 -848467
-709933 -905638 -795454 251371 376036 604979 937058 -609212 729757 482508 826835 289889 -364452 -939242 -243395 -568778 -484768 -983578 -839857 438139
-81379 -162719 689188 -658938 51045 -604867 993264 875487 88718 54446 975266 -401992 925178 673896 366809 -525953 -977260 -396095 -601909 -870283
-524050 -437221 832311 253155 865788 424720 421610 790679 -856662 715698 345583 -601385 59459 927402 346710 -167860 -76559 -386030 -707519 -848724
775786 533917 133917 -988504 389669 496141 766192 -862470 49554 -751689 -271681 850000 501809 -722198 -701217 -602695 378366 -536072 20774 -631751
370308 -6934 738857 -815378 524271 -516772 997933 -949913 -771560 -200937 -690393 747411 508553 455663 -34556 488509 -304147 -724182 -117113 -643829
-2278 -711962 -845170 628857 -222721 658192 -277668 675775 -996935 -428519 896326 937690 431843 -246591 -640862 -255432 -427784 -977114 -827002 -715453
868225 -594364 -104742 101995 636186 -653099 -467295 -57844 -379739 991120 -835333 -553526 -613371 -575175 167587 -80071 -850447 -300283 636570 -242873
817503 230639 -887609 -592236 913403 -542673 -60393 -806752 -871357 709599 866586 992274 -123015 795695 -70121 -858648 -472897 -107768 -730954 188351
163658 315787 -306543 152993 -713222 379869 479728 17180 -275325 -288437 163514 53245 556188 -977743 -757333 -14424 -542251 -333464 119658 795001
-627187 -120347 -211092 179640 671045 -545781 801213 -782367 302687 491465 970377 580943 634403 -736620 -605239 -426447 -518851 447830 710922 -437273
-972799 -477621 -621073 657461 176694 -192063 -662007 -916225 -894572 -535079 -646427 450772 -873310 656389 -987078 850410 169332 -393680 707047 -547264
-172682 -745394 579876 565053 170150 -53214 751253 541183 -309744 139668 791547 -418052 199558 518678 -631007 -870448 429868 17978 375446 -90363
718655 -192687 532986 -615495 -86530 948675 291149 -279076 63888 259022 -639601 -261910 903882 -954267 -279559 682873 335784 31258 -156568 -925014
922648 -144209 181547 -542179 561663 694169 -831940 -818617 -209239 486122 595123 -302027 -300533 -917641 -747000 -296553 -919582 -983115 -79323 17614
-698059 -29051 -845823 -461277 523525 -431144 626032 467495 107992 -897557 -978931 854813 952998 904904 -839769 715878 -208947 690759 -418716 349651
601055 183264 -747189 -765343 238417 944757 -349630 342279 586856 240608 16803 48803 846379 -500857 360416 728827 -730875 613492 903279 316884
-406407 -930318 -589674 392033 853144 435182 730611 -337564 -669907 943873 254131 -153580 -774483 -127059 -356673 -71383 316941 878893 -200592 -99411
-761737 -134413 -902002 475318 -831994 -530265 -789508 256672 -850136 298681 329523 -180015 -149342 -267096 -169457 -567490 -630662 273763 -737754 -601126
920999 -103792 629123 -186719 -693941 33180 -760755 -194120 96282 936592 -882276 -913177 820903 -20206 597339 65229 -118953 -304392 486670 -285242
-465808 -370841 -950680 -773206 -607985 296666 -237777 -789171 278603 419087 979762 -498729 -874658 -628411 -263910 971338 -70718 440051 277034 -981901
-625191 -565364 -788468 629856 -575158 373328 -469211 92105 734518 -323330 -304949 515704 708815 -368281 -125061 111754 108076 -936576 210931 935977
708344 492761 -159182 -17516 287076 -822118 -59048 983275 30036 366199 555770 746936 -521980 -70697 128155 -673084 -821061 -109965 58992 -481183
235295 -962791 261760 -691614 925017 -708227 -156944 836694 280197 -473203 -28035 -194296 -368945 -351391 721555 -903314 -796667 -178431 579141 407927
369570 512240 -827704 -506066 82556 962623 948948 -926656 -760213 994451 960167 -297497 573587 -790319 -169422 37689 754313 575374 462322 422703
612534 730246 -220313 -181636 -918835 -776180 -34821 167998 -822342 -869924 -465843 -649678 -578487 -229117 228904 -559990 548851 -477018 973826 797044
-279588 282541 396059 506810 281791 638686 454375 -9445 -190297 654155 132624 489109 -222685 829869 121392 -315459 43797 -279044 -323576 -871658
-922235 -118045 -622287 -646286 756183 -821147 403864 699847 -909936 717430 -799415 -831290 -397542 -919800 -753422 292975 300130 -7524 -631453 88532
-632375 562806 621652 -838328 -776910 -449092 603811 475969 -479905 -311306 -175248 -548914 -989314 839750 -663159 475198 736371 -491977 168681 -145744
-269225 812134 370852 -517918 -264651 -721887 265758 -756965 395236 -864757 -917390 -111458 -36316 72416 -269181 962291 705586 -250516 753230 230140
135720 -828987 -630134 686343 622222 296084 293839 698518 870202 498300 794618 944992 841090 -389517 451718 -505646 297017 607582 884366 512932
247326 -615615 -616135 -335527 959382 -204056 -47117 317755 -362568 -289076 -674107 -207023 444063 355604 -818389 517348 493835 8093 11544 -235371
-653507 909071 -159273 -869189 -415157 507854 -762874 704915 -657786 -305294 -92126 -515925 -147138 -934056 280134 793989 -409993 227675 -229857 536958
64191 -193073 -360676 -3028 -754301 -987402 765705 16404 516889 354655 -326931 -467144 434003 -334659 129351 -336217 672702 304373 -868322 384135
416687 -36985 -302024 -514107 446692 -612006 52886 740132 -127697 -735836 377297 -430666 -874211 576882 -438268 -621425 -151099 -624442 -996091 924871
565553 -971668 -803875 154036 -265107 516155 -707834 810970 -133187 -626832 142316 -672004 -407468 366653 -181834 616334 -466626 192944 905290 774273
-570455 -184391 956554 -641216 852605 991562 -829779 -20338 206654 375409 464720 -537273 -759480 -384497 -862660 519143 -795389 -621684 -967498 -288119
-339415 -90185 -856492 -816644 660502 -142049 -472071 464649 603844 440390 -533225 -241220 726378 -430024 -421062 462988 27912 383182 -626634 842771
-729184 -918588 412155 688340 780723 12768 280448 502945 -524584 -488995 -341656 -146831 -856491 -370391 29192 393374 633867 -415925 -625467 -11516
106423 636363 -637844 -59519 311080 726068 -808034 802640 -44300 -925607 -299280 839527 410859 196835 -966373 257715 -589027 -733141 -879735 -688178
-947354 -954700 556138 948427 -289105 -175314 998791 387546 316055 -669708 -280004 -792850 256359 948490 291555 21892 -577006 322478 -223453 907998
-620339 -906450 -13903 214354 121926 -119468 79149 -805297 769518 -5377 -253465 959500 282383 993801 -972691 -247814 -152925 522497 597879 333194
452633 -55403 -463653 832737 282229 856351 -996232 -869421 298676 -322315 -144765 392339 405912 168898 -85613 -151003 969603 -820957 -185178 -56549
981069 840130 -385484 451860 953583 -18415 163786 460414 460753 -83991 811979 275261 130510 -282418 402353 402719 -656132 822844 -383674 24130
349676 -481511 -461584 -625778 -114370 -999844 -906728 522103 -129022 903572 545650 94033 -769720 -162484 -347395 -335496 606860 859999 -687952 -645893
-928034 212527 377386 669170 -489966 -310488 637915 851708 99681 754009 854322 532109 -68158 987332 -171619 528519 -843677 -663742 145164 975140
470712 388998 -684429 650887 637265 594448 650344 491522 -13798 -289153 736264 -126392 -7489 65618 766125 -68131 978693 -107524 338622 787410
-841360 -675735 -243967 794456 132631 230756 -781585 897756 629244 -77807 126084 424293 -159140 -450866 -384620 903861 695572 126755 -955447 122491
-761636 -216595 76535 751880 -628015 461654 755439 415192 -16232 648085 -158122 -42037 -645912 -982465 740858 608007 283540 988723 5177 922282
-175938 194115 -327839 -266027 -57457 -517279 824362 118442 394083 -98575 937831 446658 -1483 -627739 942024 538113 -562565 384950 7691 620189
-472198 -872577 -507173 252235 -849262 851029 -432788 -500257 513117 -189757 -574910 237649 242317 403636 476056 649622 258234 906342 -112576 -764907
-400565 873409 -410761 242660 304548 -818061 -113264 453072 882523 -357124 676525 -576589 11964 70483 -112259 -168449 629979 911398 -367865 -356016
-879841 -458732 745869 -139166 -158153 285555 -663339 425000 959846 707827 638294 -312543 -325488 -727529 -513007 294115 432095 381472 -986134 -640670
6900 -761417 -82053 -414817 -337704 -544032 748124 492775 -372947 598250 527482 -391576 -71731 -767664 -866631 -435362 -792739 -354329 200282 300146
-771462 424226 -995852 -814723 350056 -681055 896727 887297 -699965 -308204 -369552 -325661 658848 -642435 401673 903456 239278 964476 368305 -327258
-370110 -322775 -585578 18856 424220 601554 595663 -588118 -341815 29918 -85032 -920542 -155459 395991 -156670 -131426 -69733 -69583 -505015 -941630
438575 -381552 -242009 -699859 979303 -203039 -807276 -233216 -731443 -997701 -45609 19045 -792873 299126 276161 660883 -132865 424672 244082 626654
87743 -863503 951112 363587 -814625 164660 -913947 -527514 963775 -522818 8061 262413 547284 730328 -861450 -443118 614092 546075 139838 375751
-721549 -411227 34468 -354570 642707 666082 935068 -288365 863865 761667 375460 -749344 -558686 313557 -182800 349268 952193 -401271 170690 -799863
795465 -134580 -403545 779594 -861462 274334 -464271 -41497 597026 -856605 -815596 -731016 -939960 756554 396806 571799 935134 534211 486847 -283778
-311744 212409 730976 502211 -977898 -117994 -418910 187650 550484 964767 950712 -792824 541983 -154485 -879903 -946736 -764054 -685946 -843784 -284745
371188 275081 -483400 -228285 884453 99445 -754614 966286 427952 536082 41499 -953778 16401 789116 -826434 842515 749521 511423 180356 -490606
-211358 -368466 155298 -86891 177475 -522292 739877 602266 -315198 531085 163274 -73594 446976 -480422 889054 380230 -858462 647968 455036 -623320
81975 369944 -740076 121746 -186911 -621825 272627 571764 -23417 -195447 -981734 -381616 -119633 -345350 -186365 -509070 149324 -897934 314532 -164175
-131828 649418 -825195 714536 -196841 915405 871607 71929 -517210 -693608 -472403 -254035 -802497 -56584 -38493 129725 -988399 322084 746336 574333
-747626 -474057 -813667 645895 53546 -177558 -777179 167274 -351499 -760451 -327032 910064 836260 -597033 -636713 798360 803957 -210494 -548843 86347
-597017 547568 -170127 90860 -859884 675644 961719 445560 -196411 659160 976453 718413 -570552 319390 -773146 -264948 463448 170003 -976141 -453167
-598292 934806 -627843 173513 798686 -652605 -309479 -617020 547757 909311 -492452 -460811 371090 545694 309445 -453947 -231087 -387755 636466 213581
-706411 243437 -272823 -319331 -875563 -874783 -761084 552468 592574 973698 54275 291458 -657412 -30544 -775478 84669 111518 -341288 -190837 -496748
-923134 388957 -548555 636517 -894908 493389 -166154 274891 394197 -586779 200456 435326 702211 -679330 -707590 -668362 -313494 -332460 -490547 555805
17398 273462 -369869 -869184 460285 -699312 120148 -217838 -531703 564147 -896620 162884 728811 636624 -682764 -213639 871467 940226 719680 -261064
-894948 699845 61952 245672 634905 -887190 755143 934397 881538 -452372 -920775 955883 632806 122741 -396952 560012 228632 572462 780037 213305
-112073 505555 129924 -614342 249055 467571 521258 -334821 -917152 300522 693998 -246910 840246 190198 349496 -92173 333660 -272714 173467 590688
882691 467044 -536632 -344242 -532740 294131 -450082 -937896 -824694 -290455 -736266 -758233 416007 -460031 466457 838760 10070 -328437 -783875 -332400
58461 -414305 74183 -134454 -952869 115980 47734 -974283 352502 94920 -510658 343839 846041 -636479 236069 -101652 -251401 198017 741562 -654025
-748884 232901 148686 -646504 -634195 99378 413105 -743121 -380523 422642 629455 944848 264518 595440 437412 -229576 576923 147461 330619 177095
-103694 401521 660134 -82473 -837791 735075 -318038 5291 -508654 -417719 -102549 944588 768087 683917 -144834 -305057 -431719 -634393 650709 -54480
469331 828017 209695 635267 -650668 579052 764855 -947993 -471050 589970 724221 358295 441713 -258600 -457058 -404511 -448441 -871858 161490 -153786
684225 -968073 785701 -486101 967141 -83407 -235072 -957219 663800 935973 -847834 31640 -190164 -504128 -592626 25647 552241 -53495 408935 -13721
-176789 41850 736347 146837 642878 -147046 -308369 -714023 -73826 384986 -76440 930994 290189 -490989 242368 -128754 -794817 235772 -499997 477987
949896 -975117 650566 238428 -321278 171253 -405055 816124 -360853 -557017 -813837 -448285 -433539 -807465 665765 -256551 74922 838201 -214308 864191
-994615 -195136 136123 -630086 -694420 -207891 -45559 -278743 -862546 -691986 -976451 -840943 99412 -614342 -110586 -163342 -261424 132591 454525 -872377
623876 765033 35587 -454548 -149220 30729 -396960 264399 503266 616423 638742 -621486 -509681 957915 -826846 266838 -516371 -722717 347275 559912
-122698 793567 532527 -830635 21955 -725073 -617604 328782 -25959 513338 -612216 -280322 4659 -81257 -372136 -78883 -659229 234736 749499 161815
79599 686802 -581389 742951 66944 -910088 298004 652540 732471 474365 -760357 608734 212428 87156 984899 137923 -277718 537508 30310 -226445
793636 865528 -841698 759693 911882 618842 -304903 844880 -797275 -785102 707609 379983 18045 510226 -138291 -130738 659904 843409 770125 934102
622198 289998 577906 -266702 478390 828954 -639257 -549327 945980 63480 -111706 338045 -941758 116524 -151993 594827 686462 876851 760528 406477
392061 -655927 583811 -868654 514472 -659152 488727 973249 -944916 180104 -592029 252226 -817803 -672409 -570055 25539 -934814 958476 -489933 -850351
256343 -186204 -305547 -475893 936737 -167602 -901727 896121 700498 -994691 -671900 -380257 -1435 554253 -569889 174405 325670 -819211 832004 889654
-15179 399950 821589 124786 273201 -496787 109335 -386750 406003 893974 32816 852375 347064 -712787 -678456 986865 -999192 134610 -923997 -481981
-680738 -630938 -865176 -569763 565887 -915521 386216 99270 801361 8476 235587 -116767 -202215 145383 784210 -314199 314077 47636 -775105 146838
309050 215207 -772190 -934039 947237 385398 -934812 -276070 -528712 183361 -357681 897291 94606 -541810 -67930 -751359 877597 -521180 854809 575488
-932766 722478 -212013 -287079 154483 512392 124494 -401997 -811192 -950901 -76815 -94928 802066 216781 -486649 -161611 981274 674657 373545 -244389
205192 -875353 -133651 -406529 -674128 -538282 724031 -253907 486686 457343 322277 -276965 -115640 -567328 -111503 -693390 -71532 319999 -887120 137855
803699 -153758 317172 -736403 831688 185740 -682441 -287954 -838476 388779 -127277 222489 718367 -190238 928704 497381 211320 -690012 -138400 -279260
951383 904374 259452 -6797 590581 583354 517965 -267665 -280633 126608 -796467 -421318 773362 -760750 950355 983924 -923602 -739191 647782 -762852
-506422 -407890 302731 -88259 -868731 -243467 874786 -657527 849995 78362 352965 645941 -758361 -992876 221523 598471 -788508 844375 929521 904717
719401 -174658 -251388 4585 -251840 847076 -937940 -114026 -549202 -585902 -998411 284341 784021 393010 -605852 946933 927325 228336 -718923 -975807
800590 774934 -76072 -995383 539565 -92596 973220 437463 -62790 -283832 238925 737663 995266 -451730 -800498 -315497 454046 500629 436877 581434
-532930 114922 466731 283624 514755 -899869 612054 841236 -694423 611680 941471 -490177 930209 764822 693557 257542 656646 -663743 503825 -92112
457397 -645614 -383176 887389 740247 -908700 -702862 737829 270494 -59386 614856 -681544 -659291 476148 952896 -155055 260667 750918 400322 -689155
613144 492910 10721 312359 -953409 344649 571882 -857058 954178 175851 124791 -71850 -360517 -656367 203976 -984777 892518 673480 -714110 -518424
429170 177868 -104924 -393937 343607 772193 36562 -859366 252578 648231 -247434 520050 737474 672768 869075 -692554 216027 -178679 109476 -84378
376440 129992 822964 132132 313707 651302 -359568 322022 -808790 414119 287503 -373205 -954675 464366 -351266 928455 262212 -192498 -633503 -835070
159743 -82685 304314 754367 -155230 -494479 210939 98468 996391 322405 796667 912869 -450916 -39397 -516651 -377048 414279 -353301 -41138 868581
-580101 -35554 -721275 -484009 722999 734681 995508 -650482 -526126 301079 -35786 -601388 -619177 -575709 994451 -846887 -550259 36418 701321 146213
629797 38731 948548 -493129 275783 -115195 735684 -832659 -374492 -218003 922803 951310 -730554 990213 -191217 813660 -450468 -202186 599913 516126
14221 951183 39064 -820236 -118172 -157117 481055 5364 -984525 78068 845330 442793 872247 -197764 -412638 648623 933681 831964 45876 -441102
-861948 -35929 428358 -695322 740740 651936 697672 -721171 -51505 947647 693303 -615187 -585187 469184 570657 77567 -874863 -973201 598920 -104907
-422807 -407577 -216108 -501794 -585326 -213035 725026 830511 -186420 -913073 401894 -999340 -405577 -36782 571845 684835 213395 -390389 72499 -60697
-512591 -617104 -504059 -607432 197639 308849 -885940 -379896 -319615 82726 -141226 -963477 -739198 223473 -172427 -83184 -262428 794138 -540154 -275558
-531021 -935508 -47654 -231349 -767862 -823214 -831821 -227017 -506891 -882839 -40154 424426 -695400 189105 -457536 -365112 114608 -430181 469895 574652
-687594 -885247 711683 -392137 997723 237374 -193256 -731904 788639 355185 -979076 -578786 -332594 -822468 -255100 886029 -735993 -552733 221447 -917803
-299157 -205626 934160 357365 409957 261202 936290 -732107 42890 478956 -393082 564814 470350 600381 -958824 -152990 -412912 484750 579244 880767
-756906 -448939 481458 197219 60707 -498479 8919 235534 747060 -813998 202533 -118618 421866 -884447 276581 -675324 -43027 215977 -719975 209317
-8643 244882 -428133 159877 316741 995448 57314 -880793 396361 -983944 -421251 -877805 627104 -3375 -880076 -318750 -235663 83761 717967 -725692
581644 -61945 887723 682457 -611500 806213 -472294 -450045 375466 -419637 136236 -482030 -869453 579299 -386266 396454 361444 10375 98560 -633171
-992078 677313 336282 864483 179773 367856 71535 888636 -527591 289686 922520 335189 221098 -762290 -394362 -275393 -725816 -117024 -757385 420577
104097 610232 4979 273548 -926011 -668881 214608 760312 -292307 -384648 192733 779909 953172 544737 520730 815963 -479639 -28043 -623463 -534437
149247 989941 314591 -396996 254447 -870382 -663713 -369605 722122 41058 271978 -247147 231262 13648 340472 -174143 -578299 926634 -250254 523392
275583 -764657 -276749 -29278 -672563 -165113 923595 -634161 -814164 379654 402621 660560 645909 134340 417237 316877 140183 -635489 391449 429421
-673343 -696704 -329537 -652372 492692 -648730 883092 825065 -14476 -51755 -722194 -434868 403053 423856 947250 493806 211135 313713 -843871 -514078
-293512 -810326 -285021 594300 351236 -317067 -735773 25177 163665 -226424 18288 -812096 626228 547687 -729455 -539394 -725845 443932 910205 874501
127297 -702654 -876054 40388 -496235 -168850 618171 998649 -396426 227597 315775 21452 -935525 331709 -105685 -1432 -883435 -440539 277549 -170808
943633 -127761 854238 -876671 -770061 -393190 282663 -130472 -280191 -967614 -242270 68020 -408970 -31643 594919 852006 378403 -3267 -114331 998983
379010 -333209 571228 903700 379032 -762022 231422 -663899 -136048 -197071 -295731 -377455 -594203 45693 824570 843784 927972 331173 -922830 488237
620222 -589137 -345569 -376204 802085 -215384 209473 181752 218210 45463 519657 -275082 -84318 -881925 -350359 50615 359145 523428 -514659 580663
982169 670845 -367706 898094 339504 -764774 811589 -815707 -688631 -484632 -960949 -486250 566943 549191 -281695 -916959 746586 575443 726101 571606
-419843 -359789 997612 833570 -784647 -674365 -65870 -868825 673498 -622349 519677 -927421 -318401 845473 -919549 175934 31918 -679539 -966388 16082
-664422 468347 -156618 -234753 956376 -201403 140857 666424 -956368 -986825 116363 -417254 -786308 89481 592003 -653722 270489 -878641 223418 594863
679543 634278 924797 510837 909278 564252 513433 -14780 -347825 503396 555397 -160141 -498121 710714 839554 -297209 -17508 -526528 -692166 654785
-267785 -493818 424456 -512153 -554993 -142063 611867 -172426 -438150 -754046 -764019 985267 -962203 112775 -751199 67947 -124269 -410840 786012 522173
-723828 283676 -589112 142018 19656 -767773 438281 49002 964930 -405868 581287 -582194 916208 955172 980918 -61930 -451231 -417085 -803033 -715478
98424 -868663 -421358 986662 432161 -685639 -501583 594893 654086 378013 81404 574182 494158 268261 -844467 447154 -967675 -697260 130465 -552416
361542 874047 -745543 20940 -461064 -529761 -75552 776414 -667919 -646872 992111 935552 215877 373943 -45320 959398 406140 735118 -268017 398499
466884 -875280 -397783 877494 -268838 -548703 513908 692511 -780146 779716 -783321 823880 580634 -185844 681530 407490 -355573 -61043 447302 669106
715694 399690 434641 951760 -831430 -208246 157089 -712127 590865 -985052 -877617 906302 680791 473764 -467673 498888 23004 437356 -302058 6449
-569696 971465 -834461 408223 -189357 258027 -848370 -542230 613753 -672206 798345 981061 27546 52822 529861 698063 -692842 339789 -722979 -803850
-691640 211055 -852795 -29808 5318 427390 -12130 267552 775567 319419 71118 -410492 -873110 659075 356261 -372003 -594293 -917281 -845904 -997704
-686281 117912 350698 -735713 964979 337833 -870872 -785110 -914764 -365443 -57058 -838617 -949775 400022 22064 254293 275902 -367124 561491 842732
-194400 290273 -653748 85468 -480642 406330 167233 257067 -957259 753239 -51596 -486322 30256 113017 337903 329174 104681 446240 -781634 925848
-627547 676049 -572014 -387780 647749 -40998 -831186 299667 -759953 164639 -167928 -517292 -217993 -508596 826046 -312398 -479953 -711077 436916 325929
776733 106549 848955 387686 600988 -349811 -412817 -708297 -632099 977310 720274 -776385 -445400 -451928 -905093 -40486 -959505 -438328 954217 157272
-330104 -762513 720563 154642 419879 109288 300039 -931797 99636 341362 76636 853865 457260 269398 300747 -845370 364993 -535169 -800371 -860004
-617260 -418434 -357412 -436305 552903 446263 -854539 798322 -567295 -233797 -470842 772249 755431 373046 -925314 -842214 869536 68170 -43226 -215106
319150 64302 514672 -701627 -100249 972889 -798241 -345804 -48735 913918 -659330 -292407 372711 101806 113635 -946146 339212 222308 373851 -911911
351218 336434 -369721 -771447 923407 138064 473019 765909 368704 887867 199904 -358845 -42963 -551410 -320389 -527124 451871 -273915 338774 544395
-995942 585467 -203989 634589 225571 -818708 190907 198684 712758 891984 715643 158883 -303673 -93741 290058 -854508 -191493 -990662 -360113 990801
-943819 100976 647557 -741883 249544 -673138 193275 -46144 -603854 366039 -92150 597019 705126 869714 377135 118508 563963 -45638 -568285 -368184
886461 184761 -240121 81983 616344 -218811 210727 -641668 482528 -610656 761335 419293 -78195 197205 -573961 145916 103775 173712 -492182 -969107
753408 440942 913740 422687 72803 762795 -233710 -488471 411870 -701004 173425 -582994 -434770 839275 467647 465569 -780513 207088 -298783 -397230
-711866 -986744 272899 858558 788841 833983 -47989 -148748 -34896 684587 839833 242658 621903 216753 -731295 833075 971466 -320853 389591 -705261
-428575 713857 384187 -629442 340384 -913784 513560 685512 -555516 47617 755140 97681 839551 -348666 481364 915161 68723 673812 23245 -842935
874272 -805054 -721057 -242686 552648 625137 777697 -104206 -779403 37065 -329002 -775036 613511 -193886 -501235 609071 831220 826464 -613561 -579616
984567 -774715 237102 515965 688978 -609407 562039 -629533 36193 248259 -25770 404033 -664332 314590 998457 -737953 646342 -839918 109791 -671661
496577 989501 293453 -154854 455072 531360 -810386 -137166 372524 350420 -327942 792375 746373 175921 40725 -472514 814397 343326 251946 151144
-588584 114886 927966 951901 -119024 706543 625526 -663145 -36890 628121 -491006 -699113 -731753 29124 -377104 279345 -887861 -775243 471170 780807
-617151 78189 -259658 668546 812689 318348 44460 -881579 -512643 952532 226673 556520 539454 -551 869317 724093 -355717 140953 -565881 717008
182467 993039 431804 525807 221036 973861 -229243 -436470 296537 343842 -871768 875838 -409263 694250 285123 386237 -966535 315937 -903192 -232924
-509648 418173 667414 142489 297676 -11799 -851076 -487749 603601 989322 368284 -647867 -40047 356042 -918359 -7680 -191663 874385 210833 -624497
-623822 352532 479178 98950 212291 -846495 30334 12033 -58808 340222 -813424 272687 -553412 703516 -549720 848090 545676 -336633 -934335 538047
355445 -590709 -996701 512337 968235 -343053 698089 -974287 167718 -277556 498593 475744 922280 403443 -4526 462572 -25760 -94680 -213922 -969225
151227 -190908 668498 -73091 -388178 -687893 236477 -35740 749564 -394873 190624 -49712 -826061 -920305 -433965 -828167 -962955 -248185 233018 -873737
-119459 509146 -843734 768944 -413722 -281550 286402 306612 927662 919349 -53454 23669 915391 714005 852365 -326186 -648404 -717383 -298204 283170
-690667 382482 -652673 474961 854180 938577 975974 -452523 -411145 223441 959685 91938 549169 -600417 -568137 -52534 -428899 -206753 -45670 224718
129533 -409119 -938524 -926928 -359743 -209873 715820 136533 634702 310820 -619597 -527835 740933 108141 28825 -499085 661436 -735157 -488174 -705746
852628 -398678 -55121 815416 872468 706444 276077 -676008 581433 541690 -292139 -659970 444110 455097 -328100 208091 -247922 -82507 735292 -653351
-244331 -34791 -391647 -778671 369236 -683459 -104622 343562 545765 -294880 -905812 -245166 319440 -489414 -175809 483 273393 -579253 -364083 247986
-858264 530697 -99598 970495 865288 -360022 882178 459866 445623 -843012 281054 845592 -328749 700519 435500 -285966 -976680 -777358 743717 696782
-737582 -270763 812575 277355 -512700 -533448 -659701 -826818 106984 489040 161170 -431462 -752381 -504204 801812 -494942 -441094 -936880 -775530 -912566
386043 98119 909365 417969 514628 -538858 980563 -952273 714532 237860 -663543 983460 32253 -37895 -127802 -372636 834692 -905345 245213 -506492
550677 -125620 458424 -588377 -582780 846462 -34986 970512 436344 923656 -768063 -996993 -655365 641512 -416787 -988345 -739308 -330775 -236785 -796357
774595 -333856 -929825 -675173 -841796 -52601 -294967 800422 661754 -483233 -636278 412008 -789153 -221021 -134092 400088 -218210 -790581 -740170 514538
906582 597952 -474682 57727 200191 -445837 391200 -325663 560503 -428693 813542 730618 -812474 -584741 -589174 815762 -41498 128208 -636832 -292417
-320171 -795667 -336909 -111845 -389840 -262825 612542 967832 -900790 301325 838974 -529939 102544 -96114 733518 -479878 -400572 501710 4727 183751
477140 238383 -367454 -628373 -489863 53854 -973084 452747 983401 -351964 226945 57269 -183619 -772550 -507538 967004 -603824 -72936 -437878 -916006
134859 -993753 -777426 -38744 921413 -526963 626438 -513307 840938 865870 -627016 -402338 -301457 659681 260629 -929368 407551 -871384 -613181 658750
112621 -324756 -854149 -287318 -415311 -10485 -412601 -506989 867556 -333212 511392 -723278 460529 259443 -782313 -733549 485831 -142273 214614 734253
-366493 386337 453524 970619 -592518 692102 -850857 799707 -707064 14423 -810490 754964 378595 334801 721919 696148 44681 510046 -823359 -190725
-622741 305175 -529747 -227091 517901 -609350 275422 -469617 921932 346665 -75279 -474755 651989 77064 47307 84253 577739 634043 -852187 48353
780721 -475915 -65617 459672 864956 -270071 -669058 -718868 94390 -738268 719090 48964 -525316 176198 316165 854619 -770671 762944 438089 85471
-696408 -501220 432126 -844685 400852 980613 576007 -112213 -236482 479384 -120817 -527411 -14232 116180 585878 999067 856172 379381 922457 494167
783329 54453 -269114 -693964 -879762 -972543 941176 -992052 -901977 276473 237259 -817853 -334866 746070 -309383 45944 747362 -494149 753173 90847
483043 6043 160150 7602 -211967 289249 402913 353814 827480 -668156 -25128 -494478 -79516 -173567 -196849 -116471 28523 -551582 -766868 246333
-67137 -50576 -142704 587758 -312252 -660774 -609539 -784709 757268 -707589 788999 -449166 649760 106135 -778899 806609 -968987 618467 760899 -23390
-553686 -668698 -50961 938127 832641 191369 -152917 -569870 222296 902253 -4245 -325493 -667186 -411879 301221 -643532 28386 895654 648310 419200
339742 -724551 -753738 -125595 -79100 -450217 -80915 962427 -97837 -82033 -282994 335024 813501 -135970 3698 808853 340432 -395879 560648 493952
-942691 -292600 937777 -520874 -587713 601201 -420751 347423 -489009 -898399 935022 -429946 -351572 -949925 420695 947096 331733 -152922 183494 -605291
332018 -877177 105977 -518521 417316 205753 233212 -804096 540317 460766 301554 -743017 -943330 -999111 541688 313675 -401536 235906 411279 919529
783507 82921 311681 167632 -924073 121941 -119540 -580220 489290 686931 -306757 -891864 -714481 911701 -398127 210102 269223 403595 998163 57896
152672 942818 -262727 290259 -138799 -80307 -718702 -789762 240311 -781767 813240 -647139 464077 709493 14511 697526 -947275 -334773 -304698 -253704
801304 952698 825132 -948441 113106 -130792 -631531 -80348 479244 481893 -472542 -512178 549959 -112563 392063 -994154 26720 377279 129028 424550
-814173 113080 -255367 -383761 -491236 -561655 201077 -854802 612262 -563408 -62839 744077 -764253 -506407 -926559 -466657 489495 -669169 951771 14398
-684039 -285304 193094 927360 -398509 -718353 295907 -515309 -505563 -281943 992776 -411021 -105327 806869 -356666 962109 70362 -867832 750290 882874
111668 -547381 -506453 -653881 206158 380841 480314 -662371 595376 273549 -492818 -218413 -660958 -573224 740740 850923 564276 882607 284530 -178491
-190556 -420796 635183 -760217 877519 443739 330644 -886119 -755582 742574 975269 566452 285571 745862 -180962 -415500 -202828 -997099 695737 483380
743200 -842427 324959 -313761 -330079 -298015 651375 -760736 -329227 149357 562338 669723 -231207 -321492 912544 -937932 -124782 -164681 698934 921871
-358851 654490 317044 -888402 -556524 -220422 827030 -722387 -878790 517589 476154 -399865 604479 -784417 -953097 -896962 -254206 -71339 -752107 -693174
547233 981018 -217561 228916 413441 -879333 -462265 417011 -765666 20583 -43655 -622749 -40218 721975 -140308 -287971 -301273 140906 -700977 432264
797337 -90894 22595 -938115 -918227 312018 567704 940437 117190 -617067 179953 70746 101423 -181321 -813643 -34232 -977773 995399 195674 95575
791540 560007 550428 879115 -115770 -320079 390502 -509536 862752 135019 -493039 -866506 -854405 318333 556744 506009 -110148 966347 -612508 -703273
263603 223977 -372964 271970 -758786 418267 -753747 681307 -887277 -206192 706726 156279 -203466 -399721 188914 -49863 283374 -756735 208536 982195
-642042 -973786 150003 824175 -507139 936915 860046 -97278 173912 126887 813651 359022 -909396 -717429 23180 -846528 415876 -742411 -632633 -419516
-525577 -205050 -908972 -63612 585610 -373113 -392316 -579037 753720 975154 378100 -558892 -37419 438523 -784894 297808 924382 796875 -822105 144326
451229 127787 -880849 -347633 -781146 593784 660468 814065 403926 -381741 840808 904883 94988 -438655 990570 -969805 -953773 761503 -65407 -514707
-726940 903427 372370 277377 -453669 -549096 626816 -982498 -963259 582898 33715 897837 798458 -731151 -705594 536774 221636 -308139 -440469 -559443
-88191 945157 -203371 928382 911854 106349 820329 315155 236825 753580 484090 894460 399354 342885 679512 519517 90651 361185 -234361 -439452
154651 25711 -657629 -32380 512698 954087 317483 -358894 842698 404248 975955 -717548 701785 -709034 -26474 -569242 -820135 -951372 809258 636402
23433 408671 -420542 898212 438690 749139 -990062 -171029 -824266 -218141 -147304 -277521 -63543 -636670 -711040 -384225 687573 -313250 937 -132244
767850 -576975 892069 -956117 515405 -794646 -690705 -748961 429174 -236065 -3973 -393722 -132355 628042 170658 838561 838457 625555 676119 46422
-255846 -370674 526348 679982 32471 18710 855844 -835544 210746 -288641 399148 341132 691487 100408 -984276 -821945 918638 206109 -351178 -933078
585962 -99000 16517 750562 51473 -490716 -896544 399211 68650 183022 2629 154586 -148927 255930 431840 201967 -258174 203400 -425115 416799
224105 -482060 -216339 16624 -3630 644232 893752 -747592 -37519 -62488 -672551 -789060 -209621 922167 173383 872059 510720 -274117 -930952 121856
-415843 334516 -125509 612995 -464787 29631 -431202 981590 105222 646878 -342181 -43328 -395519 846927 -615532 9109 -393606 -373781 686423 -982590
215495 161304 854882 72 -888514 -862943 353736 520055 986701 983683 -258622 408978 110339 -668412 16205 757822 -109000 517584 834704 -171524
677752 -889016 -432365 -575324 -596234 -527012 -458223 289392 856919 760178 599258 439123 -623332 -745696 -683348 -523812 471057 775013 773729 588136
77501 -589051 -843375 -634313 146562 970451 -296300 898490 751247 319672 -80056 202109 632941 699164 909348 -268755 -423720 949182 466400 -194175
-998354 -827295 344514 133774 383401 680997 -664951 137475 780926 680124 -865892 198383 -933190 -496680 713341 -97990 808212 -15491 -210557 -441801
-770935 448961 -430561 770392 -349010 -73033 -774839 -10436 254114 -223195 -842744 -234157 -805142 -207093 -448192 78657 512170 -944620 388130 696557
-97410 -328382 -433243 -220479 -988524 352069 -795435 -851657 -182135 -994832 -160053 605858 -966455 -497116 -195040 19625 170994 213651 -689162 220220
261793 -649123 -795369 678493 143154 528510 602156 -203294 172282 48394 -247496 -490804 -251720 -608600 -926405 156946 -158466 -973783 -340819 -539491
-179100 -379505 -153169 329412 -847716 -521539 289784 -556076 59493 738126 -872158 -81346 -542559 -583157 -554702 941430 -579918 -996426 934418 151256
675903 -623769 -713325 381290 577186 -857573 732696 154328 -688022 -760098 -174527 393572 64598 -197362 -245568 -448910 973398 616214 34004 -355203
477503 471746 -23393 978636 -601074 -123661 -431201 -498464 891324 711936 -15087 615890 -598575 264554 754936 149734 -680201 -636869 -227423 -268339
-957452 -331334 -865817 755530 317679 301316 -970900 949939 -746223 -808112 25882 -788508 -760478 627272 -734716 -232621 -151267 586876 597628 521376
-421477 173889 375980 -533294 -88902 -670444 -939175 -745298 329045 -188251 536311 -777862 -472793 598497 -21623 267040 -627524 -18687 -842805 55313
363899 -683591 58854 139807 -840380 937892 674066 192981 40396 107946 -654525 -937727 -899445 -989329 -902240 -80316 619649 779516 314545 -298897
-548291 -112198 401554 470333 630211 808977 624908 -56004 -73257 367233 640421 -421692 815413 -924679 292623 -896287 126439 -645245 -594862 996058
-718712 -173521 563393 969039 -263277 -853436 601838 73298 440529 -721816 -830656 542741 -670513 896369 130265 574194 -777148 -539961 587768 -113399
-771261 265823 156706 523379 -497172 -82495 719406 168763 418636 396125 -774635 -855513 775470 769708 -575320 -44712 -94711 841633 -315892 454655
620682 -188347 892913 124533 243374 518392 648685 -619328 -536166 -128623 -109549 674368 517699 902573 -18609 -179163 70009 -6607 -263785 -934104
-643362 711163 899846 727620 35494 -670777 933594 581381 231939 -662212 26695 367416 781541 544005 308792 -292993 570536 -127777 -475631 -285612
-467790 521254 -574541 798927 401819 472121 410087 -813224 148012 300092 703345 370835 -372337 -371309 -391982 521788 -497752 -43210 -333088 -302651
-452305 -950212 -211235 -333729 673551 -855006 -636269 -954731 485778 -923704 968597 665798 582414 -25319 -637080 617144 -414541 700856 -147885 -752625
-305638 836002 -389241 -14621 -137804 -695560 -667842 372542 -671749 -636788 561177 -327069 742465 24012 146377 292264 608840 -162913 945322 582269
-704963 -66019 610880 -721982 -837051 388051 -563757 -308059 8975 -40405 -995485 134335 -186342 -360347 -809587 500646 -930347 -3949 -635379 -278497
-310699 -190876 -681837 -25735 344593 29123 -809563 269279 -513309 543646 -415418 -579103 -958545 -6606 -151964 248671 -386601 174339 757985 595430
892833 -380256 -996878 -901994 -138774 -940068 -937242 -879890 -970298 652537 -815598 -804232 -394269 -137095 518849 619106 65700 868169 -32319 996336
242189 33972 -168792 785134 982616 -69525 995851 995270 -107040 -193933 -934156 145793 319581 293575 -83260 915831 -90359 -57692 506938 365371
-839200 -551315 -403700 611462 -800592 614087 384233 370890 -35896 -934122 -786159 -557528 -282116 831050 435753 -651941 -451769 -589135 -972899 567096
-389649 -540889 -755919 997507 -812565 -960334 15543 86845 423807 -155688 -291965 316700 -54976 688784 679964 798660 614217 124843 477497 -448328
-706266 -53898 144316 136169 -575975 -69128 -166372 -104363 238950 69019 -575126 -562694 528845 417570 377340 -893040 -280774 597262 -564294 -292973
297131 527811 -489690 -167955 451056 -689738 975453 566576 -25494 808838 397526 -77747 -488570 147375 574164 858244 603141 339553 750311 -251041
-991931 682044 893188 149350 645079 -413365 617284 -947560 511022 402 -311163 -555698 -973471 -915870 140509 -181358 -947355 -541469 983144 757888
-737076 349872 -860397 -338497 486332 850686 -293669 486533 -304034 202889 626295 -91930 -510411 215397 747102 -309059 -652471 221783 -947742 -278815
-66942 186743 -191761 -687885 293871 517563 828963 501767 832457 222719 -445709 -714709 40415 945262 -315810 -343019 462505 92977 993018 -692880
27816 960126 44959 -937475 867430 -682604 24735 469832 161725 543947 -499534 881650 -997990 147721 484380 -417672 -70423 73627 -472015 -366157
-650794 139583 714036 17527 -137906 80060 5807 -761878 -998497 557123 -49659 -837266 -628466 -84514 68827 -876207 405545 -383786 -555601 204293
-665059 582582 -66259 369336 754633 13993 48169 -380533 -175230 109941 383570 317798 475824 -800973 926544 -688386 655541 851647 -928802 -470466
-878494 -494783 366196 -734124 -198289 128891 -865013 -568815 744491 741533 -93226 -936723 551257 200605 355856 -549907 -963380 -282789 150413 252055
-207610 -667131 -554234 417839 -983260 -208453 -313257 693322 147503 934103 -364353 -570652 -619296 -75021 -243311 536479 -76247 541711 562265 118159
260164 792202 -699912 -97876 -124255 648614 -484742 954542 654811 -140231 -136148 -603093 -669418 -430456 -453386 72369 -770025 -140962 793754 -751780
-397673 467809 -37405 122303 592689 121326 -265500 -290 -554993 -434772 414467 -470688 -545753 343183 -939614 17163 416788 436699 -253170 -338451
773 -238071 -486322 914773 -97867 -765593 308182 -888263 465430 -375928 -34559 690209 -788401 -925082 118348 -418873 277983 161549 496726 -15139
', '-1
', 10, 0, 147949, 3),
(1003, 1, 'sample', 'level
', 'YES
', 10, 1, 6, 4),
(1003, 2, 'sample', 'algorithm
', 'NO
', 10, 1, 10, 3),
(1003, 3, 'normal', 'abccba
', 'YES
', 10, 0, 7, 4),
(1003, 4, 'normal', 'abcba
', 'YES
', 10, 0, 6, 4),
(1003, 5, 'normal', 'ab
', 'NO
', 10, 0, 3, 3),
(1003, 6, 'normal', 'a1b2b1a
', 'YES
', 10, 0, 8, 4),
(1003, 7, 'edge', 'a
', 'YES
', 10, 0, 2, 4),
(1003, 8, 'edge', '0
', 'YES
', 10, 0, 2, 4),
(1003, 9, 'stress', '3jly222jtl2qkdnggc8u95jimwzbikwz4q0d4esxke92ijf5dwti37arlgu0l4v3bzrt3an1nrqchc5wvk9au284pxde9dmx8d8l0bsozfemd32up792p8ftub3ufq0uve8lb9joa7fp2clj6b1gspjq4bwxcn2qv8b5prp24lcjr8youfv31xi2pu8dketf3p1hn6amgn24sxg5m9o8cavq5b3l7mn8nsf8hukcfakcwafnotjzvxe3oya44sv5toajxbwcfu7zk1q6o3ri9jdyqyi4kbywk174jfi67oscjw92g4cp6s79i33osyczvx9vggs3v1ou52zu2pe9qquic0hv8u2re9btz0s4naejp7khtwosd7wa67uxgd2y2c6p6rrezmkewpdixuops7cgmr5q4fujrcrmxecbqh447tmc8cmr3309uuuk0oyrgmuf8qbir3yxalfhozz0e0eyi9dsp7e6mhbu0smtau05k08r58bwg6qdmanqsxxm45265p6p2c1ylwga2p552resbaf6ykyu5g35vub8ntqsu8v2en92u5leli17uzbgshum3fz7qta7vcy6bsb3sii1lzodoy7nsywsj82u8aw6jxxm9zs53ky8g49o9qfveubclqz1b5zkz9occxm61rwpcjke0e89lkv80uwii0i85ilxu4sc4uk4gjqw58r1nbz8uva877nu3fe55ayf4cdbl273jefdtgw8658fx6u5ywzth94cu28c8snxvm3wyukaowtqdxtv0td0rc3qgxkm4gkep0d4ohs4vcofrs1ycpxhs8po7wqouk5u85q8i7i6zivsizgbpdt79v8vxqi14slx2owrth1uxuzq9ydfen14160kxdtjurd5nu1jun7aw45hwjm9t7ugkov3d1js088ugn00hvfgqze5r9hs8gjkquws0vqi2su62ykcegancbqbome2gjzidewslucxpn1ldsd6qdk7j2dqzp4gorz6vu4v1d0aculfiaoe5ns0bnq2jpj5lt23lgqt25gcv1542mtleb89hqg6638ekmd4ay909kemepufh8ecsq5jvbjaqqmuwhuyf8ixkap1i4sk2oa6txlchakdajqzcfgbuqm4zx4y9rcyavq403wxiofzaxx89rkm9spk9rq8q43pcno0wyi8oyhv1jkhmpqoz5x5tsvyt6l4m4q93uxasm6cfyk8c5hdnni42h713gqwfn41ykhxlmte3tupbqogc008fgzzx63if1cnxvoj5nfd2qxk0l9mojz21hn78p5qdvmw61djmpqxvbwqjgepse77slpp6lfqpfgp1j88i6crnroz6rz76kgdmt7xtkmf3v1umazq1zg1onocv8fhpzrp8mw5lcs0899dqo48m2hmrh6kg7sit6zb4yoc03vluvzw1ri69kcnivyqibshawkfdp1dkac8b9mdxk6ypzpekza9ogjb3c86mwlbskyowilk13trohoafmu4bfalo9bikjimf0uvzzbsg3892bxoyh9hbfystt6h35rl7lifsozfzmxhozy2gpa5s1trjfuhag5ve2y7b9sni7r12pdrg06w3zdf4wnteztcdvnd7ke16sl0otkuvqdk6bx3d33dqy2x3k6keiyfyaxmbged9wcyt3ke8d5lg850a834vhgylne2bpyatenptzdaluuqz1ksffvvxne52mncy0hq0roc3kb8hrb0ixprc4bqdwv9lzq5owm8bkacca30d88u9aptzzpxle5o75qjkm03fxf3hzzdxpein4wxtl2wvgea67ni3wr07yvyrf1otp21epux6x35g5lu5jts2m46n08hvqshvm2knovgz2gu124oip2qi2ux1dk2sob9kc200p0eifzsmx0xk9zmz98g5q9kq63l4lm74wshnttgpumehfr9bp92g5mwrhsgx9uzhr0r85tgte9mheawxnovbmkoh710fghxzncg0lf3iyv5obhb078d0700fyqaw1ys296ihdc47n2rldgugvbtwgdwxjvuiel937x0pld02g7h1xx0zzijiyqstqp0slzyipdn7wtur71dx51smusy6kt10tw6gwdbsqa7u34k33cbrf3nktfixpdf80pdo6w88cyac5qoceaakodasf3ge8b32g86slgfqvp4wrvgoqnbo07g426to4rp8qexmmc7jts9mp25gmql5vkgq06ukswce1t5h9wki862g6r9wx1qu4ux3msgkmqovrdezlsylrta3liapoii1v5mcm0weembtwpk1m61vvrakprzji2i6qxsevmbnxi5ad7ob63t7ghf5jau8cy6c6jsff0t3de56vufexit96lc7rdm5ohvk4q7goi2hzyu5bb6yzsd0yx9qfog346b3lvzeiebau6c83eogkcpx5wep0ervwmpobk4w61f3o8ouetktofbdau58bn0m0lgmmo85dhltjelb94xmxi4wlti5ajvr6gjqc8iwbbsxrvxjqqer1jwcjsdlus95za78rslrhh8csygsv5nggk8ag2usjuocxo3lzygtje5aaht3tha3ibuw419ieopp3dgl04yxdff90kwt2d961k0cwnl8hydqssiby5coirmgssyl9k2fl3tln1kz3okljm8354tfp3jdobs7sc2pozt1xe11qvu616skwi6bxdl2ce0kpnd3wftur2at6mk9u4f3bqg4ica4u6ng4ixfkpdghuzurqvxljhdssf0cculmg6eqjsz9pd0rasleway24mmn0edwerjgzifmtxrry1uo2a9xt8tf4mtw2724v52384a10mp1qv6h84zj5czf0ik6pmsm0mtl8hmyl6v4xi85mhoka0sc4145tvq1tlo96c2wnfwe38ixwl8mrsjhxyya4zjwbie0310u0srb1qewj70fq3b5g1xohozuhmgcrztdan2gn5a1nfy4jp0gtf2f7ubbbz7femdsygm1u7qst3szxgxj2ocxv32uxgc1q9u24hn8e2re95m3ud68nzftf3uapkofuatnmvy3qizaczhbp7vh4c6p6lptcv4q7oixcbf1s0ehi6ynfh3mfpamhlaig4noi8cd5nhhfok43nhdnpb7ja0e7w0f0k94d74ifjcpr2lcd38cjvse5egqm1xrrf3237j4ca55mavst1heo61c6z2gg7xojl20doiaxdzrovad3lxiut9mqdjuz26bhzuaqaifxzdqc987z43x6moy59sowx91t7y4r5i9mx82o00l8b9clup0dqik2o0mp8upuiq16j0tw856o4y74c80sufhk9rv23lkd9fz534oqs1ejmncis16j970vkyxzbmiacwhn5sp4bmsvilqa6szmtp6tzb4ikx5grsv7tki0qlopjmf96ylsslh5wwhs2dfcrtsymby8e0q4idur47r525cr5lreoby0d5g4fc868caq0s89zwt45qdhdhw8jftbb1ifux9ioh6iig65topt2uverjyd3cl0vfykgg1urcenxox15dj5aya5uq2z7kxf6kry9cnba273ln5e8r2zri5vqwz63odigve3xu3ag4xqd76a98v1ktiep3bj2hzkb2ccw75edy62ahvknn4uorsem1a5xlqql87o9ll8lfpx7a3ftl6ysi3ws1quompcic9wsdpe0hu2jnalf5yoy1mhjmtchb6nzty0jzxuwad2f960uewvqihd5q3n963yyi5y9oosjedgu2d0f43upofjz8odk1zdnphfqfj1cjwgh3057ixp7s1te9geoj9bur2ucwsvnwxoj9orwjnztr7h1rdhuexnmrijj6xrej5bpyvy593pcl9n2x29r6lk30q86cwzmcmrwwvyhz01qgnlowmk8ral9f3g6jeln87dokii6cepyf0ppnnte59cwczqtqagsglg1d16wa8p14xzeo5fk3o89w2rtcewbfkqcj6fr6kwq74egrlozkh44j57lj7uqbxth1d4d6a0fm2ezhxfv5x1krf5o81224f73flu8vg3mjm07mzrsl9zty6fwe9dix50egg5695i0uwdai68fj8neb777eqamwmgs3k6ohccb179ukhrbv6hgll7r2bh3ksgrm36b551q3njcdul0putn5ip0t3ukz2ab05k5el0pty2ob3ajqusjzbjkt8pgb48nvtzg1c1ngxhrr6aauhckvnn8ewt4l0x6rmwoc7nfsbncgg3cyujcjkdomo76mzg6cqwo8s1xn5gt5x8sgnxj2ggvv6dnbjm7h03cwf3hpxd0y9rg6458i6pi8axyx7osxqnnxnc0wpjnfbv0biikh1n9ghcbo97i6lwpzt1b95n2y7mykhnmhbmp945nk0iue86xz55voq7td340s6zupwea4m2u0yu56seyafah8lobydgwb5qdswubier4ssslbkbiahi6zyerxra2z6ccvl8vxtpino0o30v7wtzl80twp5vzay5s4e983zpjrm1pil8883iil7qmcu7bdtow69guxt4nsbwn5xmtkhm263wdy6c5fdfck1iuq57bv7305oz2a3rfhfckwqscecm6p4b1g6uw0ey7zmz0gup7rp0jpd4n40znjp3accvsmkqtcex4zas6t4f9o3mk6rd81kk9uxa4cabx42mzvwk44wi93ud7u3pin5i9gdoctyhswzns4u3d154mqsicr13n6bkp3lkwjqk0c8ntzu8x0eudbsj5bpdh8h94bwqj1ibccs40q11cqhx3n8p1e91krjl6k3gemsez6yjlclqlrnodqjmzm8lj12u74co51wtsuuhyygmdexx6regtq9gcqe1g1t3wsdihnt9x3036hgou3v0ldszu06rzkgv8lud6q568z6ga93x18u328i3a6fxo71bmbsdiay9vyg5lyv6d509n00gy424piemqcl3c1uif69qdg6fkewv7i4qcintmga0nirskipu1hpb8s1d91d38bovoy5o952j1ekiktz8gcuv0o55ckb0bfttcjgg28xkff5vjemmnvrg5s711w22wkhvpgzgsbiumk3jcmbdgxmlbv59qjnymc2zfndejsog36jf21m1ja007s3yoyxcc129wg3w3s7sc499wj8gr7w0vsjmmpnw3i7at7h5cidqtxxqqy35c8a4oaij006xodupo80ld3ueiedva7n8wb22ggnuydor87uc0k0abndemmy4xvc4d00dg6q5anfw7zosbsot3m8nijbsl42v2oafjoqormsz37hfq4figcdlk1w9lmh99amscp1q0yjfcf57cfb6d0rnemkfmpggsvn3wnb9udguc02em3vkogid9tv7m33g7yfhora9ilbmspuu85lvzi8tum76uaqt5zx8n3sh16jv9c8sepa0lv46ptknu009npafbbhwlw1b8m9jxii0b0gs5c0u48vyxspp78935cjjrkihkkp92viez8rcw49aoypkiaba9gc1zqfbhobq1rzlcrn15ufh42ethgujwtghcw555ibp34jcf2v4f920ukia70l3589izwew9boqrojz3x5n0o7lyktlmrforsxxs7l3nvthzoam4qb1peoasgshofd3awyyrleckqgwjsxpmj7wd6i0sjd36b9znmfd1wypga9hxhpzfi1lzyg8grqm3xh3tx4m5o7p0w041vwzhwcfq4wgd5bjgih9q4p6hg79rdzm3tpwqrmhqzwekc833lq5vewk602gwc6812jd811qzah5bplwi7ctwyubqnjngn5jdc0wwiuyp481ixrf2ugufasq3q86jnwyt068eio9hnyasa5u8ms2v0zw5s39a4p1cuqnlltjaf6i1byw0eebfscskbj4365dpxl87mthcjhmwkohkfsqjcb8i4e92frwe2d80lnaipc6ieskgpo81syzjy4b02tvse5ndnkjsh2uh3dzw43g9udqtaqjl100wd84hlg1w9w0bvfht8cycnq51zxdnmam6bohqh8nkr5pkotp75383id6g1jn6d6gr4h1zt65sxeen4jjypzdg2iumhua7bbm2kig0ecbxbo9xm67lg1ljgdhxoxwfoim0vsbya5izmck8scg775w6vq86cuyflbhnei0r1q9wgjc82z2ozcw9zrwkpi8rq5vass8ls8tz4hcwoe4ms2zqlb4oh885fs13qn79nrtw47s3bzrsm85rsf8ywl1pggyuwkz2qyxy36b5dsrqjuygrnl6tdja5v1jc87rgygzqaw5e6mhorb37ydlg7rwkd5hojmvxbg2m5g81vmelmvd3t1095cmcfye9be46xocf9ch4k3mvqptaz3joqqpb04xezggewn5yyek8ct3glnxxz08y8i7sz1kcmwzvq4sledbmb1xrhe5tr8lg1d87d9mvpqghgnvlul6wbfrooyu4vh74bhcxames67gi4301k9d4cfxu2l2kqux22upv7fclg07hp08pwbuisb61fcirg5q99m0bquse4stwlyjvi9t93u9fegzwrcqmu191jwf29r3ttfcetdi8bs4yw2hlbg9xjpl7oy8jgf0rj48dil2p81jdqxona5f5nmcdqs7z2rnvqoz46xtmss0kijqu6vy1f2ownkdrlteo0dyf5xa8doewzqcd9jj5kdyjsiujvteva13crel7f6xquf9h6pmm6597q24yiu5j1gx19s6m1b5kuj2hx7rm221v2375kjt4t2aoin7jerewwba4qlxmpcrm49xjy9ri9r3ukta22kl606jw8kcck5x0iqs4oq77d4q0ip7ysoeps9qjhidtqxpxp4qeidx9hkh3g6q4vaaqnpt68vwk5cff1ksbsevo0m9yunqs309avup1tolqsigoya8ej7jjaipama62vcbgvywozjldkrp6da64lm438vf5z9e74n7juw4c1l6ibm50oj892qcacf7at0b5hll2gzauv99gyulo6o11siv1s4cz12s3wr8cju0nb29svmkwsl0gmz9om1uan5kyfy1lm48jcvpeeadtblejcnyvsb7ufmf7zsf5yex3e7uhlzj9ow02ry1gtm1dyx6m9ia7ywccpbnkb4z7rhkngd9v4frep5916cd4gp1n4yb41clk4uaky996wgx18pbaehgu3q567lgacgdor8chtcf2prpoq805jo0pbsmuz1g0g8hq7ic5hhklk3xcsavccwod23jn0fj38jwvile587sc356gzp6apu0dz9bifk4ikv9hk7qjvq06sxcqu59qzzzkf84byney6l8jk5yw8lnjr45ai76u01jcs8gr66hcfkoa6gcphe4o8zbhgfsmf0n2znjti5hfzcvzip7u7h1hemkut96ar8fgmsp54aqqhqvou89e7ef15qxy6kg1espvinn3ngl1vcu4hfbz3nblg1jh8xc5494td672s4ca8vexga1kzxvptztfrtpa86rx9df240cu9vcpcrljuvr76v65bm6zhu3us5f1q4j4odwr3sa1fq2339ysqpfyxpnrs9bc2g15qwr312rs7bs7frb35ibvom81uciew7ps12dh0axtsktpqj6i4fe0oidrb82tzeof0so3hhu64o2i0ltd1k6pth256mjf2teics9jgl7p07me8q2xog0en7b7f7uiqybpl8iemsddv3dv69g5qdrut8a7kbwome1vio567z463oqhw2urb5siec5aeph6ruohbvvnqxfgig4etefho9ta9agvk17yxvp6wpyxbpisfil33jlnhknfkehr4syca0drrcp2hjm77ilstza5janaw4lf2txq3nbokfp4unsu00ej10q5lbgi5tmbhl5bwe8zzfm7eh36rh4nqhxpsjkayrxgipsr16gj51rj6lywc4buemhwwbjy9f0qf963uex2r4287hpqab29okrimnhzf3wwcgljhu1myqj2qnkznqhmvygze3rcs2ai38kygx209bo9qmt4buhbkpdh2l0yqcy38arqzxdmtl5vyfyffu1webeyx2g6l6lld1tlxli175udha69veuamwgqvcnymacl20vd1n0xdpaxtahy3z1wcm2h3yw11c9yj0zkkgax5k8cy8bzp7vzcgts951n7u7q4hlzx54g0mx7zxo4v88x45rbrf96pjmxkcaqcinvt5ioww7z58n36trb1wsbfhynlmvduptw5n4xybmdgsjeq8ccq9ibtupscz93qihzbrftlf731txj5ph0wgf1zvxycf5pdl22d7kpsel50crm4c85gnkf63dhctbd52pi7288ka2uyn9wfb9ombsbq2r4q9ki081kj1uj7wa7xxtymigz5jsa8d4hv7eax7qcuu8lf5d7tvep620c5yczq5laxddtw85u4yeu2ydvqtd8a273nckdfip3f1077l7hmb8vfa2yloa08w5u2bwswg8eqrhvhkn86podqp2wylua5dvellkqbs01s6i3jt2i6232p1i2w1ltkrheizboay5s9hh2ejrecsl3f3bg0r42t1zvrn5p758pgvkclolkehouh02f352rvgql0hwyk9ae9jrp6vq43idh4rxkuw96wlk94ls7hjsljfo4128dpoe83q5jcg7ansk3tm61fmzjc94g7nmpzpdfoy1u4xot1kgf8ulzwwacqmo4bja4y62uf0plmhmfkj4zy9n3jrh5k2y3l51u96q31tg4w2ovjomio4bnpm3ovgvfa49zkafohmc6kvevic36rmpiincdj0jkkxwn67o0f9bmuz6crzlo5p5vkokyr98h1t5cyp4zr22wayqmjgg8ibbeqxg1mx11u1tiztks8pk38k11evf1ziotudb6qdi10li9m2o7mau0exem2dekxpvfxm04vyfpja0qygs63y4ivkqfaf7q5b4njzcar5r9svp45csym0rlf3ehwwa3hans350buvz18us930xt638ld2qe7wls4gcpn004k8a4fd1rbq8s590kk0vq7j23agmt74eh9q7olt4n4bp4khhd6njlvdckm7jhf5pch4ohg85h6hrv7ksmiknbb85mi2lfv5ixwg7grxyvpj67rzs3geqmk2w2feiyiel1wdxqrhx2r5e3zilfio7tb2mjfyzsakiml5rpbo2ualjyzd91wl1c0ezla8m6xvoecpx0yub0k9c8noamcm6f9u87ppbrb670djyr6awrf1v98izs2s0o4v7yg69fwf656jtdxk0e7rj72g0l0jfyvcs3snkzyd1fuhn8goyjb02ohf692q2konlkk22uf3wadaj2400bali99uazzh67agjwoki4q2rrls7j2efmpqxi3vny1pffk8bgjmadn5dgkoperuh16btss05ii3l5v0bf941zznpcwkm1sk6ft748l0o6ib8bmgtp3rtz9iplbr3bnztzfrw1lwvj61i08gif8aqr48ok32f4xq2usvgytc9ddeufbjf9pjef6gspdulso2vwj659n8ej0mqwrlsmpkvri8owuvhws8kxleiolneziop08miap3mt4csxxejddngm2b7wud3rqk8adad4a4kj8fe2fspt5v8u9d921mtj1gx4sp5awos2g87q1d4nymkergobun02bz73re70rmt29armbyyag21u2nk9ohtt1ic4huxz2uv3is3xlrgcykfsm7rpgn85uwvion80xq3i53jukxnj7abtobdz8sq2hnmclx6upivq0rjuskc8i7omra9mkr1fgg7pacorlz5nqobi3kw1w27bj1dvpypuocaroipijcpkp79uef1pj3q40cnkzx7ftgw4l6zf3lt3ig89gqe8hmizx9aqd5ss3gky0ryq68q99ygj5ceg8n1qtdp4jnqn7hguofb0r8kgf7v26w58frcqh9n0eywsvixdgoaasi5n0p21k0mtsjtpb5tkp0zaf57ah9hk13jf938ujiwjh7kt8wxa4cabto3c092p1anzmbbbm3q1ncpxvcgq74vdfguhqg96sa7z3bf9c2ot73owwnvbre2tbt7r17czalhye1do82v6w0wwp4a2z69iqnbxvudo49cwkr9sp652cdql6kmoxltwouxgk8ad1vtm5674sk40evhqx2f2mje4xxguxyl695ypwq4sn6a9yoz1wsdthe4fa2795b0qwepg7vwz3tgzytbye1wgkwgmycx8ejz3oqqnkii0gcz25i47v199kvwy9gl2rbrpw8bbdlj3lsynbyc9l8at02jbg7hr1d7yd1crc2iq60t3s1bb7sayi8fbx370zwgw35fzkdcx7ik0a7hgzlz2xk97qzhi1f1fd1li7ht4uuq9evyaci2tsqhtykcwl7w6vmzlq4mju33oxn2ndvtzwbfgm6crhx7srejybyipx0dn65q66vyhtb41epwxhosj9f7czbcbp77vu37a5r8vbgtr3hn93fmtq1td2wn21szsxf1f8lsnria53nquk3bcygivegz3axhnwu2660yxuu6imnblcrhv194w4t2fd9e6hm31sgrz3z0d8i7v3nr02frbp4fgxfzyt9ckqzkoylxgjxfbzmqy3asw4ri4xvxn61o72tl4gxx71qjnv7691au91igo0fb9ueagwee5s5er504vf8mzd9pzijtn56qbwonr7w1i9qhw7mg61yav1uxyrjfg1c34mrqrqzsv8him2inx8diwzq0r16zzmlacnm86rtoxcxvl3vhtu0uwwqllpm579umzzn92qt3iljkmg8imuucu8ghjdu6llx3guhkj927p4j9wq2gdg5p45kdvnk3383mtio4xb6p5lalngz4sisn1f6gy2b3rcd166lb2xxc3v6krbztsmo3f025fdhwzkhmqs8x70bcwcls8e1t5ecma10uc9fpbjggv9xgij6we4si8nkwalev44fl0r1tauos03og63m0biatvtacbh22qdf2h7b53g7qzv1farybpbnydoh3tk0pt6izksekard3kcujd9x9mhng4h0t7z0otd98lpwygie0bphrvp92vdff8181j0qcqoln1olg80kzxmppbm4q0uzwezw7723gdu7vp05pz59hcigmsufaz3xrxg99lwoz0uujhmuqcsn7pgrr3oyfc5wm9t3rh7syc9d3s4fbxk9dh6bicyc08ztbtcd9s733tdx5buly8m18ylmd14u7pmrbnq2ang60m34g3mfs7p56vzcf3kf6rsh84ua84pwwrrgc9f9hk4gbn8ywvt61buu2skdbktrs2oc07uwhpwd3qajkj2zr7o9jgx2albg0hmk47ig5wurijgk9qr42crn78g8cg9inwru3eaxhh21kra4sq0qzvn1tbhhnh6a6zwd2z5b1wlt7fofg357l0ltyr1bvnn0sqee5v07vj91b4vk1ziavgvw8ad56d7kghnrpgg4y5eiawi9fmysj6s0o9mnfrifjzyl2gchbjk75onj1zcm0e43no50z4r97ys2y67nwrd5irnjtjzhy4cv5dv0axyyry6mm31hy2fxq7kxutd0be338osxgx3epq4jjy03884kvj6sw0y9f3j4lazjpiqnl7m9bcqt9kmp65qa8lw61t708n14tpi2kajeaced3aado4z7b60qxwoidu1tt4h9eiwwubry4jqksewpnkms4byg05fg77mtyi84m8l3mwsopjekw0xkgtfciriztbn96lv01vb3ujjg7zuxcuoc2cs8vdy9940tnl1e4mpza4g0a46oaevvofeymzetzy5kl7bpkwub3n1xfbvrbl7kthn0kiihw2p36mn09r2f3on1f3oyi5bp9duj9zegt1vlnajpwzijkuwf2ne6rbsspvhxk1lq2v5oidyym0zxjxt6pp14xzwely9vc7pi08iadpyyod1f9il43p9esneizwhgx9b8jnetb2b9istt5ocfue4ysj2cutksjro4xe196idlxp5gn8f7xxbgdwjqwn96ute3lbnp12q4jjk7gm7rd9vk8l3u7943et7xderskfmln6tb65nlclkhxln2qg7uusof48yq8acxntbd710axdyqmeuvg6jf5mgdrzwu0g4wasd1irdve6jgrw16grohao9u0vj0hoad22dzkussjiwi2o0p8k8dpsu0oo1hqciee9rzia4nwis3gjtv98dnssvvhx48d0q8cfyux2rywvccvnvlgqjiqsb8443z16sj93l4qpp3u9604i20sc7l8ku9hcu2dv7nelafr3df8zq7hsv0u0jdz0htn2piwdy1xcnx9fpbrylha6801eir6exhztp6itxdkbrv9xo863dd3728r9kdshfgjz7g7ybvq9nrvym3khwc0u6jqt49k35u804x7jx5cho7canne0sb21xtl4cab8w4w4inzaocpu54d41myhtix0el8gtww6fy7as0cpdisl4t4j3ip6ddon6pashs780pe31c3nifl1yul5sjc0mnqz2hw912575qunyetropgbpg08243fgbkuz53rzyachwg2uofmvy1h2561p08dboh2g2rz9pu8d983h8xkeg3cvhjrl77r3soui9foytjh39ug4s1w6fklbigun9iwet40nyauyxwnzo5c0lnqgflxwot1kgt99gd0677lgjtqe6oup0sp3ktpsv8zikte45m68ts428d8anvvwbe5hku4zpgqdh79ji26r7y6113fvosvganqls2ai2hwq9ka2a8gnrha53tuaicxnrhv1095c345kxy1kyavt82i94e0vam48qwihw9oxr4ro56j059mwk641j4qg7bfbp36wdd3latgvkhyz3cetvh6300a7j44l43d5oy7ls20am47fm97ewke2f9o66d69zqil8v4q5j6sziaab3l7jzeh90il8ahcsu3227t28nf5w3isdchwllascvol40gcp9mbe9xwejp1opm6x1k0oegi0angul25f0c2ygq3qkhkg8atml4q41cdq0p0oq6cx6xk4dckcxlp5wskynj4ubkwgpspwa3kxi3aam6h6jm6o0xv4uhlnmsah73fn6z1lziboiq3v4i3mjzin2cnwxfuy1jiy1arok40ztn0oqsuve2c6itcl8r33izydqi0alozi9amrp9geertkn81qfmujqb3xiioadjaux55hpa0a24wpj7q5jvnx5lkvhxez1j7ydyqzyz3g0orzwd4k283kwrp4vgb9nxs5hdwz1gnhihxfln64yi4rjp916rloeab8yyp7c5155waekztym7l2krk2y0mm0slz7nslegk1sfe113jhsxkgogjfh3y1n2ngdxa0dr2sp2mngq253t4qsf9h36pg25ygk8r87dn994nmemydhsos0iiq7b2aovrqovjtir6lva1x395fy2lff60kzqrt4argyq183xalk2bw601da9hpk09k8npq1nn7p4uviy603a957indchv4zqdvnhkfhd32dty7ygosski3gag051ejkz32qkevryfqqyoav1tdwl6f3ap0u8srkv7e385dio8sfk3l31919kfoiypqjhsl4hmoo9to4lkzqf69o50fd1snlpoylghyhvothzu9cmrn1rsmc12hf3ph0pw64491w9d2d4kbxh8826r3k5c7oxhqua3ede4o0rraalrfz1x2zns1thm1xo7l0pde9ppl1yrio8y5tvhzbvf1bti3l1awcx0hd3ptxa4zknt6xf83ox4bi0kj364nb0okp2dxtlttugwja2eq5zf08vc9d7e9z4isqrtol95ua19ci7xz7a8nck4203vnf39mbewpk8e6pe4k06zbt54utc7drjxu9txb87rnlpcmuex2a0yn2xqfdnslopcekqjkhv1spllmwnogsxmxbe3x2ej0lqfdwxcswchr8odq10bgxh6jgww7qzazpv7s854s5juusmt2dxnk7ladp6v1knmvyxymwxcqut7j2rmzttc5h9u4j83evn2ha04ynprikzddxb953aeow8qcsc5zqw2y4mw8v6scu7tk4ttwtw8fb9hryxpdvow1kxycqvo9bz90v7i1ywtg1fzskpemepxlcq91nguwrcalmp3i6uro07390vh9ao3qnsrpb347yc19xevfe917a27vdci0utfmjhj8qrpl13urvx4fnr9cq44icxoqq4j3cyvt0j0qz219i2zovt6fndd34zwrlo2tyjz5s4qbny1l6jiojlz157yazb7e4dqs6ba3w8g11qrbgzrlt2ndvwyk6nur1kzp1d1n0bmfg0n4f90zvvlv56sfvk3332ok5r03q48lmcsvh3k5vibc73bn9f2uxl2wjzyqjcrpb699ixbwwox494e4b1y7a10m23418evwkbyswnmpj2me8icuh7wbyuf5b1xoh2u176w9pyrxxqskrknjz8t6009o1q76qu2z0w0aok6s80gktiz4550mulrb5of1zmcydo0iofmo9w0yaos0irmh7nm6nmdowha7vqhxlr8fv1nmicyu9fo184h9h4rojf2j127ruubruasrwwzrrqbv4k0ryv5k7ocdos6kut3n7tk1jcr4o5owuojp64dwt5jc0668esq3qbtsfw9evevy3fh2xomcm8710xfp032qktrhwf3nlprb8lc2i8ti9f267pzz3ricb0ddtunc39hytvqmie4xj0vz3q1b9bobk8zvhzf5vimehxayge0bgq11yanrh0nr80ios2ilmuue0kadlgpmlqifw50i2fncjnrs1zfawk25426nho3flcclwas8c5q79qo6vlumac5zh5547t78aifzq7o1d0z99zex4rv9yo151g03xec2tthefu7vvzyi9orylr03i3ulf9togjmsh4pbogkt8ow9acmghcolgp0pa8664nrh0znsvxsmnxopxojz22vlfkcz5dipcpbtp4ht7oh9ssjf77cr3w5eiwapj8oeiho4fojp5muppkl2gqajiefgwqh89zqrc4zixkdr6rtb3l3dbjkugtrpijvfwdr48c7i8d5b2nbg7xe5yqfg99l13qg6jvy0k7qiixmju1by7v999lj50owoxnwz4r8a5ozrwtwu4swxbfculfqawkt7mh3tzfkcrvyi3tpelk50qr18qzqm32k4oz5tayhqy9xldkuk9k2gpjn6eif4s0l7g6k515japlaj94kb1ibc149jr6qt1vli991i2pg3a1e64n260je894k6i3cott2seiz7jqdizn7hb15meazondjgzcxnqkdytpedjnhz027kp4nhtnvnzjbobqz25rmxlzujpw5bavkozzjy2mnre0bh37pyqv0x6eh5cv3n1y2etfq7tpkwvf800eyfdwzwvouyos55tdrq83py12l2io91vfcbdaus66yfnys5eg4ivizpgtqk6l09cupcoymc05r3srmhonn7os3fp05hx61jw9h14aw6v9taro1bkid3p6kj15rfoog3wokoccw8djvo840y4ec2m65lk0lm39vlrs2o9hrzq698q8kra4ta0zg5dxv7nowo0y3t5tyazhs85rzi6deqx0rlhef91cocuaki8fju2c2ggbi3cp616e9y3knzmnnsfrb5bo0ztx7k9ce2cxnccubco6vghzbqmpd0hf4r0v8045jcg8h7009suk2032unn7o3xpxt9ft9htzeqkzkilci236jjh6q6832rh07k0tmibwfucvu1nge5vn59voewkrjxddsyfbp2t7mu2vebb66jgt1qljuaglls5vajnhu72i86stk4bjigkk5c63egye9np0yjhmm4bcp7us6cdx60geubtw84oy8xwmwjqdgtuhvm6s1cvaju2facxiib3wdghwxgolgc7arhbwmt8vj23axn4gv8yl9hox69gr2zlo7xzkt0hamwp0fa9hzgcdo7gpz9twrvexzdlottrt8umxtx0w37nw4112j13c666fd236hazm395zdt31x2n9883leadhu5acb7ticvm88deak6goex51qpvdrjd7ph5eia92t064ouipjob83dwqk254amzbyqhjh7j7o8s2mwxrkhnlspl1f79gmma68zzsl02h131z1yqqk0bcco0kditfx8lc9jffgfljf78c1fzc5lbdkesfzmhwebc8wkg2uhy56f88c73tqk6o6spyga0p8zi8mbn23iperiyivc8tt89l4zm4o1qfexndnfryknscchwgqodaykm1slal9txii57c4ez15x8gfdq6wpuszmz0j7omagwdcg34eycvpyvjomihfzh8smo1kg6kpu1ot2sln8mbtwovmt9xq0ooru7u1ywl1gwmzcgqmmlei89sboj0fryus9chs830b5fihhouje65pvkkmaakppcioerev8pbt3p1hwlc04dst1tig5sqnfn48334egxvkvk1t6vn0weznwboq2mai2ps7uviugalq0wml23lm86khszuvha0kgwfhiig1b6l8ljwmx2s5dvxwtuzk7g6blzsi7io3wkth456rafefzoa1zdflgt7issk8znm2nl978l0n97qtupqlm8ygepkljheyr6jndzfzc8atqdkyxjt5i4gd1q8wkdcnjwnp75uesb7yxwq557utqgf3n14j4d0edemkqnc7eubor35udl5a9arn7kljji9xtz3cgxt2bhzy2coexqj0vp6hkns4ik6nzabhuldjmw8eumlj6emv6aw7smbf1dq40zaj6ezgn53586fvc3vmw958bxp5v84gxcl8xo15krbn8wgdmtj9c3fsqmtwlzcmuy9dpj5zthkrmf8psxv2dzi0qcmq1wqpdcj85ipq5u2xzg9fj3shu8l0s77zs7rmwl03nxwqd0a0vk07gfv2z9g18vepq0821z7a0v4jot666oae9srcs3dzec01otjy0x9thp3f4240y2v94p52vyc2qq4ky7pzob6zx7dtjtoumoiqqgia2ilvb116gsspzbzmun57g0srt8uxw5knvsqver63ts1f47qm1le7ga7msaxztn507mb8z1m8ijekm6sr0gs9nt4js88j6dphmiowx6h3ivg7wbobs9178tet1knd0v6f4o4e1l422ujnhh8of6lgcgxtin5xuio30zcyia6yc376gbgyo5xawvt8c1tdh8y7e2gt4glau73pdeo0yla8911bzg1cawxu44yawrmvyl1lm8bd2z15rl7oh63hsrfubj11jftggnf1j1a326pxyalei2rf7yo3vl7lo3jyb327ac6cg5z8mh058e8bp4p59janweq8qbfcinnhpyaloq8jwwx3u93d2m358i258lp3kga2ui8ef1ew12qbp22blkktkzkdvzxbdsqccqzi2oypddw4rwzqiz5dqvygfgwiolibkjl6fgnaoj0lo4i43qqrbm5hkk0lhcuvqvd8hlav9qkpblf7w475lvj4kywu0sgmihf0wey4lf8ejlv1w2sn5hane37qul1tmq3es19gl5spiwpti41xuh5kx5g7eqg58uq6f912amf1ki3lurrdofku7fhc0zdg9unv8se30ka1y3h3zl3gypgh2xbsut8ainzd7fhhjm5rjpxlzsw16oeln64z1spxyswmdtszxfp55zmmv9kaigu9p4tmldqy3jtdb6j6bcf2stgbtnv42ui8wu2sb7303mw6pddrxfh6pk68dm6fnq7ffjop00kmz1a8vwwzewx6xshu72pz2d36ef7rwk2d1hoqcd13c9zkxz37yh6mdatb175fblscq3xfrigrqln2mtkg4jver4o1reuehtjes323e0yqfrt5n5zhcgd8o13g03oj99k46t0uulwfvo07d0bds2224i7tf7cdem5hikx0epvjyk7pkf6tysxb67kgmy3cz1fe341oi878wk1f8cz3zdzofu70ngsjagf6piavzb3yduebac8lrom4xnp71g562ugpbdo91yuheb3ctamkgfjzirr3nc700kzoemqsqx2rrgfq50tj2vbjsmp65dk67tny2jrr8k27ocjuvv099bsrkre3a99fv74ebv01mic1c6kxlkywc9yvb7kul2coz2zsz19mtkm1dxp490u1l6w79h5a32m8iq3wh080hg9c5tz20jd9eqgzxfqhratzmicf4vkl8pkyqmaen1hu8cs4s9efgdrswzfekqm8qfbn3d41o8dx2cwtfs34r6e9iy6i77aod9rach30ga8vw5qay5upemde8o423c1wkjnnotd9dyujckgx51v06r35817l2ie73kvvaptgazwvc9tmcgl3t9nfzamdeovfd0ypvz3cok78epzzbjy26olz51oaghk611cwhy2jfja9nqdbqnusbuvy0taq6akbi652pf610dovak12t0ykjgj83f26msej15p0fke8n5i897ipx4mhr66eibwf8rj56bovis6gsrtflmzz7qysvt6erjwkq7ixjezq46khfrvd690w29wbsuuj05euinij9cr4oimhq33mx2ilbcnj5qy6oomcrmdsftxnxoj40izy62b2gb04iysgc6x9kbhsifcnfbppanrxaov1rhghf909ob16pp853798p516chsj7gn189dikfzl83fy6703pfp6ddninpao711sqne6ek6pzc9ck7le43sfqa6106obxbxybo9s3pqfs7oy9unltwqnlg7mv8zvxqoij41zut3wx147nw0nmi9o119i4k9d7025e63ygkm68isqruql9b3eflq9bglu1ylqcpz5pdx2ah23yeewrvlhkdc08x5xtdiomek1p5q56cpt5xko1f490f24ugylktskee0zb6ic8dbfuo2q1nfziri7tzon20frd3dnrq7czzqmh1gv0qpk9al6axomzcdfdaje6f3im9k7q56ivb23dytcuibn3jyz0ybjhf8362n5chercwen2a5ly3lm2m077ewkcs80lm3mreirf7zxf3j9rniqovu2jnsur2jj95k86j9eg48x1da2zl7sjy6ht0ockj0wckfshkbigmnrruyvn5fpzetqpsopxolu5xb9mdx1mrfcqsd5ay4a4cjkdwq7t79sa5x6kdp0wnbdgx8qy1xtafnpsr2f874wh673o55tbtl834w50ys1616no026d2xcayd250qzm8clqrh9y35xsd8ksopcyndd1x1dv5ls6q18enwx9wlo527kb7gdvhhzlmj8vqbx1kjvc1es4eykztbqffamz03dh261ztbio9bws6e326x43b7twtwyd22evu8f6adve0738iyo483l6ipg0r30xy90agqc04q67i2fwp51l9bneidtqibht9hotbmi3d5mii8u1t4rkfz3rjt50vzc059vssi9lr6son8ionnaqquq1wm20l2giviihuajlub58z5zvqeq0eubb3xv8qsvtncjoetitkdejclj5jkhwd2gsykjazd0dxjojglvkuk7uvz4v7kp8ashks22dxzjcevovgh0xvnznv1p7per519g0beer6cogs29vgmgpak321r4179ly96w2ssuollcz92d5fx26w4hirz6ptbwi2pa2nq250cmu6rart80833khmwqszn191fo0nhsh64sthc0zmpdozu6mqf86xl4q380q27h0j9oyr8yi13fu8e7ay7mjbku95123j6u43zwo8xi8l79zusdniue3v0x4jjimtldstb9hifg7uz3fnsv6qm3y3dyeee2cl654585dijw4qziomf2vk6rfnq6map6r94alvsu3vwzvhdda4ymylp8aif9aw46hszv5tx1n5qxgqpch52lm1p2fdli29lr3gitniwd3a9uarp69wahde4bhq9eyj76ct7c7tfsfyo92mp2q1pbuf54sxvl7g1tg28d3xp8dv8ipl16itm43isdw2b6wxn5zcrhngxgcm7spfetddu3erw9lb1w4a50xfr1epoi8ohbdw8ca1pcz8wkkfilu3rjh7f2ttobkylebpbejfvys707kbwqnsymeosxwkb48ksspekk0frp4gp8yxjjoepq45iuv87tesy7trgmrgbtv2ypphfex6pz5zlkk5griqrxnr3ec8j7t5jp5sf5v1e8ok4q7wvtfjeowm7znmry8dmid01ogzu1fqp545mudzy3h3rmc47yrc13yyy6pba4ksftoij7tgmdi8pm9x9870cei6r0g4i2em1eljnrtv5sqcywa54c0wrl7p0bfhii4hfl2gisnatpbd09jje2ic1jen1xvw58151qllmxhondslv4c35wu6nrn7bxqa2lsrvym2karhc5zk5hcx9jiq8c6rs06fbxs3xefm4hdmf7gxdb5h8rnum7e8w1au5r43p2girspyd0bwrn2uecevotgg8prmjsyrf41n8b7qsnyag7m6gefs1kb7vu7m89rznt3je1je4chge10p843rpcnfxp8egm4hd2i605ieg1e6kat6rf31v1q637x7f671iy5ththz1feiriort6rxbi5d8ym5tcrq8de4w0itweg8q94qj41lhhw9en7dofkkql0sbkhi5hwpkdms3a3lvh2gviycnx18lko2gxsxekug9u9wcuwamwsvyl9a8ozy39cxdhisqyzjdhna5q05inb2we2lfcvojq1mz9ba2jzklmm3z0z48ff1a08sq1m67olidujolb8xhiya4wglliz4f825rvx73a3gbqpb85vz1gzr5kjgt0l3qlm2uriam30vm8669ohznab2t0fc98iuu2s9nn2xyuk8n8zp7on1qavu4y99e2v5183dphg2hvouekuz3cdd3wyc16nxmgwb5rggst35abte00ki5gttypn7xz71s670zn0hu4zkdbp35tzel8vv1jx3cx7v9qwpf2evjsoyt5pc6o7qtk9bmxuurewa7t2frztdihu8lwzdwgaji9angfl11uqjg8ujb8971e9t1uhgjos4xu2kgj549q3b713xhbkemgswgveh1omugqqkdhxe2e100vjj6bcgt56u8f60e86xyi3pp1rkumlmeqet9icv5alcmyzga5ft013lyz75kmtab2slfvcktjctzrxj92jhgp08njccfsfihpsxb2xpk1y60y322bw33vzy0ffevp7qb1rkw4qtdhkzhk7sui1dnsj1k95mikhisex5krt0c4zwrofqdcoayt66obzrzrub4ahcxumyurvhm6flil66t1lw6vnl80bhura8ck7tv81k1ai1wfof67t870986q2rewxspkhtm3we8q2wzf3a43yz8fj5629t7b6hedbs6f6dqym1sgxxuenynebo6c20ks6d0lup2wer2q6ys2yexpzds4hs9eweh2vd6mm1t4ooom5gkvmic3pih62yspy2afloj40aj1pzwaxogvo9hf16elu9xshrsb8ue4eo4x0ch5pypllmtbk7io6ldndk36r6fe841zxee68bfhr2h07ui7h23xpavt5qf0jj3cco0ykahfgc1erd7u9ts37xl42ea8ak4lw7kled7qdaxknjsqsk6171ywzsl5m84lu0dh137fyxt6ltdabg8jt8hof9wc6jc49sfgxgz9cbadbrvculxj7kqx7ogiuzhgf5llmvjnzwtioyw2j1w101eircya537yo3nxx7piojwtlcxm6dy46q3gwluhfyvtemk29hsrnaz7oevcgu77rbdeeerkobxummwtnho6q4p5j9evqj22nbmq5ardiuynyvtx0v5v5q2lx0m4a49v324z6afakk3vlo24jmx5biyijkwwly027v93lu0m0az8rt20ww5xji4ncelb043rvmhfm7h81qzy3gpj9rhc10fu0ee3wi3a3t2tkuvswfnhzt18uyah3sp3ub9haqet5rmt2taeojpvvtca408xtb4f7z7ihacs53bbf0xq711nsuqhel2pgt075f74tk5imv61v7unh5sd2ovigodoh7d09yn0qcoemglvcc7qgrb7bgz83ivxu2uf5d9mi8k7edl455z85cqtmt58poveaktki6nbxllbarky8zk6fl8rstq2lrjypaq559w0kj3t79rwowvpu0fixd02u9eywn787lzc47cm6x83xad3yb1ol7sk73cpj8lczf9vjkba1eqrusl1n818sboozj2xrlhlac5m6bzpkcl1mz1hzv5ge735fkyyks7kxg9iu58k1wnncu9ne4i5fp5pmlitmirllx5ehfchv3a6645l711e9j1co2fdo175i0037i6krvgj77ukr78mt5aa2kx199vb9y9x3mlvkd8s2j0wvfksx4x6jcbudczgq1z7dtfn2t9ovofhj3p769n3nf83xd267jrdshwf9wva4lqjqwegxhp2m4u65sdsxjna23yjpswxevnxt5fldo5l7y7pggh0t8o6qer1qyd60y6hveucujzhmrkgmgimu2tjbfvefsbq6j6jvh7bf6afs29pddy7igbwsx15roqyvimseg6bbk469prej38ye2avthmsa2a3es7pml7m9ejnzsuxnnolxnecyge1h0po97n6ev9tuqeg9excmuiq68c2ke3jxftt422ket4yziql32wmq8t7yp5o5cc04t3czme3isf2foo7hr83r348ng0gxt7iemsk02lnxpydbf0k4wby2rp8dz278cfdpl58ptnkcqkzl1u86fbn36uv82ifcnag8l7qjgxbfavfa450ti2389u4fo3h6d3u55lshmzrq1fpm9f6xmkrytw5004rvyb9e9jr4pctg4jrlccnj5cbm2w1jaj58ez8kwouvm9ui0lfv1hyr6crkyuvfkl25au6jqa3noiqq7ea0hyz2c905cgg9sheyivgv1166vyyjxnznpvldau65ubrzyf0b6phg54hb2d7gncgsnveezu3rw2df1ufl9fdh2ecuvct8u0daxsmp4pu262xssk7tjytg4azqxwg93tlpwv7g5u6sg3w2y7menywwamjemncqnrise5j3ux8y8lixa2687bqllg5ed0fhyuf4tkld0kyef4j89gj1swruu51j95y1qh0a4z7t2y1d5dfeugk160pxzc8vsj1hgf6btuueirmft5dvqpvemphx99lvweyhk3glerq2tqezo4kl8886l8koixx3i58uu4n13cg3b5w84uaflcpsszfb1nx8q0z6th3taegf3pbrh6c2ifrujq775euu5ulfg07lw4563pdx35er8p7qbap0u67icws7b3jva5ijcostzybfxkn86txs9hpqp3zsyjlufm3gzyi4y497wcn1bjz9cmihh2l9npkyy9ehtv5bbwvaxvblsm3290is1yx2d7seu8xyumw8oxsv6sjrucib1jl9pmy29fpza5r79boeain3yetcukctdg6bltqp08g82tglprbzw2842gv0hlpnvm1iumn6jmnzz5w3b3thdarglew51156r1owu5d5omsx5ijhz2zgi3epptauif9i1par9488ougzxpdqaktfl4ft94o2fqew4o4y807xlimt7kg2nwtlx7d0peiqu2eguacd6vmoprnt2k9rwtgi8tarijbl3dorq2fqcyjihfudy0oocnwq0dtzhphy293423rqxpo0iadnpgo5o31d7m144d1iqgpg9zgefvx3jkckaocy7ath5tlh6aas9h7zh3nhz8m9a7lw4ero3dtzkbu430z8815gbi7h1ct9sbooyh04sj193bxogfbadbli7zu7ntf8012b8tfttkjflmvczdm3o9o7p9sqc9km5ahwsrzorlcs2ves9cpcx7ha19qpmpchv74x64s2w0tk1hyfyhph86rykgjnstusauypk7hh4buo2ndip4xuefk7sw3nmws4ul64fku1ldq6g61tcy69sla949gzn98bi0rgbw6g9dch9u94hyaok4f26iefmh0qtg39xjnupg7rxhnudsr64vy5n97rawrugzlu9p6sm2cqy5mb0su0bf0ijefrgjn9nj741hm4tlsx9g1ckl69cj84lw0ln3c4k48fsifihadai8h6onej2wndfpifeffniogvwhsrrtq169u2cusajh162ly2rzrp5fdwvd1lm9aavbfl37gfzw2jzr3ydjvy3qhwewl27nxx1plkl5hsznphjzjyoucnnpjwwhyixqck5i6j6chgscrsvg94qs2ixwuuqbk1wgc18u0yjg052emhjip0uvn1sxxrgy8ckykzv9o9l0waoq56nesn6zgxderhgdbui8uzkkpdjf4lpnxw4qaxatqfw4gt8j5kokep61swjna8v4z6cn90e22rokfuykizpqdpgvyr99zgdeh7ft793sk73hordyqj5wpv1kmcw3y0dogv1odynj2v6g5sxvg0ev0pgu2cdw23e9yldzjrmg0vs4dmnr1sa1g05myen5bxeddrshl8udb10gava93pk1r4jc0tnm0lli5vw0nyhkaqi4939l7ox5n8n2avwedqgri8r3kayi350eheqieu88edg4gb6huf6kbdc0w4nhajvcmp9mp70t252yuj6svwnv8gsydiy9wrbn2gyyw55wyyg2nbrw9yidysg8vnwvs6juy252t07pm9pmcvjahn4w0cdbk6fuh6bg4gde88ueiqehe053iyak3r8irgqdewva2n8n5xo7l9394iqakhyn0wv5ill0mnt0cj4r1kp39avag01bdu8lhsrddexb5neym50g1as1rnmd4sv0gmrjzdly9e32wdc2ugp0ve0gvxs5g6v2jnydo1vgod0y3wcmk1vpw5jqydroh37ks397tf7hedgz99ryvgpdqpzikyufkor22e09nc6z4v8anjws16pekok5j8tg4wfqtaxaq4wxnpl4fjdpkkzu8iubdghredxgz6nsen65qoaw0l9o9vzkykc8ygrxxs1nvu0pijhme250gjy0u81cgw1kbquuwxi2sq49gvsrcsghc6j6i5kcqxiyhwwjpnncuoyjzjhpnzsh5lklp1xxn72lwewhq3yvjdy3rzj2wzfg73lfbvaa9ml1dvwdf5przr2yl261hjasuc2u961qtrrshwvgoinffefipfdnw2jeno6h8iadahifisf84k4c3nl0wl48jc96lkc1g9xslt4mh147jn9njgrfeji0fb0us0bm5yqc2ms6p9ulzgurwar79n5yv46rsdunhxr7gpunjx93gtq0hmfei62f4koayh49u9hcd9g6wbgr0ib89nzg949als96yct16g6qdl1ukf46lu4swmn3ws7kfeux4pidn2oub4hh7kpyuasutsnjgkyr68hphyfyh1kt0w2s46x47vhcpmpq91ah7xcpc9sev2sclrozrswha5mk9cqs9p7o9o3mdzcvmlfjkttft8b2108ftn7uz7ilbdabfgoxb391js40hyoobs9tc1h7ibg5188z034ubkztd3ore4wl7a9m8zhn3hz7h9saa6hlt5hta7ycoakckj3xvfegz9gpgqi1d441m7d13o5ogpndai0opxqr324392yhphztd0qwncoo0ydufhijycqf2qrod3lbjirat8igtwr9k2tnrpomv6dcauge2uqiep0d7xltwn2gk7tmilx708y4o4weqf2o49tf4lftkaqdpxzguo8849rap1i9fiuatppe3igz2zhji5xsmo5d5uwo1r65115welgradht3b3w5zznmj6nmui1mvnplh0vg2482wzbrplgt28g80pqtlb6gdtckuctey3niaeob97r5azpf92ymp9lj1bicurjs6vsxo8wmuyx8ues7d2xy1si0923mslbvxavwbb5vthe9yykpn9l2hhimc9zjb1ncw794y4iyzg3mfuljysz3pqph9sxt68nkxfbyztsocji5avj3b7swci76u0pabq7p8re53xdp3654wl70gflu5uue577qjurfi2c6hrbp3fgeat3ht6z0q8xn1bfzsspclfau48w5b3gc31n4uu85i3xxiok8l6888lk4ozeqt2qrelg3khyewvl99xhpmevpqvd5tfmrieuutb6fgh1jsv8czxp061kguefd5d1y2t7z4a0hq1y59j15uurws1jg98j4feyk0dlkt4fuyhf0de5gllqb7862axil8y8xu3j5esirnqcnmejmawwynem7y2w3gs6u5g7vwplt39gwxqza4gtyjt7kssx262up4pmsxad0u8tcvuce2hdf9lfu1fd2wr3uzeevnsgcng7d2bh45ghp6b0fyzrbu56uadlvpnznxjyyv6611vgviyehs9ggc509c2zyh0ae7qqion3aqj6ua52lkfvuykrc6ryh1vfl0iu9mvuowk8ze85jaj1w2mbc5jncclrj4gtcp4rj9e9byvr4005wtyrkmx6f9mpf1qrzmhsl55u3d6h3of4u9832it054afvafbxgjq7l8gancfi28vu63nbf68u1lzkqckntp85lpdfc872zd8pr2ybw4k0fbdypxnl20ksmei7txg0gn843r38rh7oof2fsi3emzc3t40cc5o5py7t8qmw23lqizy4tek224ttfxj3ek2c86qiumcxe9gequt9ve6n79op0h1egycenxlonnxusznje9m7lmp7se3a2asmhtva2ey83jerp964kbb6gesmivyqor51xswbgi7yddp92sfa6fb7hvj6j6qbsfevfbjt2umigmgkrmhzjucuevh6y06dyq1req6o8t0hggp7y7l5odlf5txnvexwspjy32anjxsds56u4m2phxgewqjql4avw9fwhsdrj762dx38fn3n967p3jhfovo9t2nftd7z1qgzcdubcj6x4xskfvw0j2s8dkvlm3x9y9bv991xk2aa5tm87rku77jgvrk6i7300i571odf2oc1j9e117l5466a3vhcfhe5xllrimtilmp5pf5i4en9ucnnw1k85ui9gxk7skyykf537eg5vzh1zm1lckpzb6m5calhlrx2jzoobs818n1lsurqe1abkjv9fzcl8jpc37ks7lo1by3dax38x6mc74czl787nwye9u20dxif0upvwowr97t3jk0w955qapyjrl2qtsr8lf6kz8ykrabllxbn6iktkaevop85tmtqc58z554lde7k8im9d5fu2uxvi38zgb7brgq7ccvlgmeocq0ny90d7hodogivo2ds5hnu7v16vmi5kt47f570tgp2lehqusn117qx0fbb35scahi7z7f4btx804actvvpjoeat2tmr5teqah9bu3ps3hayu81tzhnfwsvukt2t3a3iw3ee0uf01chr9jpg3yzq18h7mfhmvr340blecn4ijx5ww02tr8za0m0ul39v720ylwwkjiyib5xmj42olv3kkafa6z423v94a4m0xl2q5v5v0xtvynyuidra5qmbn22jqve9j5p4q6ohntwmmuxbokreeedbr77ugcveo7zanrsh92kmetvyfhulwg3q64yd6mxcltwjoip7xxn3oy735aycrie101w1j2wyoitwznjvmll5fghzuigo7xqk7jxlucvrbdabc9zgxgfs94cj6cw9foh8tj8gbadtl6txyf731hd0ul48m5lszwy1716ksqsjnkxadq7delk7wl4ka8ae24lx73st9u7dre1cgfhaky0occ3jj0fq5tvapx32h7iu70h2rhfb86eexz148ef6r63kdndl6oi7kbtmllpyp5hc0x4oe4eu8bsrhsx9ule61fh9ovgoxawzp1ja04jolfa2ypsy26hip3cimvkg5mooo4t1mm6dv2hewe9sh4sdzpxey2sy6q2rew2pul0d6sk02c6obenyneuxxgs1myqd6f6sbdeh6b7t9265jf8zy34a3fzw2q8ew3mthkpsxwer2q689078t76fofw1ia1k18vt7kc8aruhb08lnv6wl1t66lilf6mhvruymuxcha4burzrzbo66tyaocdqforwz4c0trk5xesihkim59k1jsnd1ius7khzkhdtq4wkr1bq7pveff0yzv33wb223y06y1kpx2bxsphifsfccjn80pghj29jxrztcjtkcvfls2batmk57zyl310tf5agzymcla5vci9teqemlmukr1pp3iyx68e06f8u65tgcb6jjv001e2exhdkqqgumo1hevgwsgmekbhx317b3q945jgk2ux4sojghu1t9e1798bju8gjqu11lfgna9ijagwdzwl8uhidtzrf2t7aweruuxmb9ktq7o6cp5tyosjve2fpwq9v7xc3xj1vv8lezt53pbdkz4uh0nz076s17zx7npyttg5ik00etba53tsggr5bwgmxn61cyw3ddc3zukeuovh2ghpd3815v2e99y4uvaq1no7pz8n8kuyx2nn9s2uui89cf0t2banzho9668mv03mairu2mlq3l0tgjk5rzg1zv58bpqbg3a37xvr528f4zillgw4ayihx8blojudilo76m1qs80a1ff84z0z3mmlkzj2ab9zm1qjovcfl2ew2bni50q5anhdjzyqsihdxc93yzo8a9lyvswmawucw9u9gukexsxg2okl81xncyivg2hvl3a3smdkpwh5ihkbs0lqkkfod7ne9whhl14jq49q8gewti0w4ed8qrct5my8d5ibxr6troirief1zhtht5yi176f7x736q1v13fr6tak6e1gei506i2dh4mge8pxfncpr348p01eghc4ej1ej3tnzr98m7uv7bk1sfeg6m7gaynsq7b8n14frysjmrp8ggtoveceu2nrwb0dypsrig2p34r5ua1w8e7munr8h5bdxg7fmdh4mfex3sxbf60sr6c8qij9xch5kz5chrak2myvrsl2aqxb7nrn6uw53c4vlsdnohxmllq15185wvx1nej1ci2ejj90dbptansig2lfh4iihfb0p7lrw0c45awycqs5vtrnjle1me2i4g0r6iec0789x9mp8idmgt7jiotfsk4abp6yyy31cry74cmr3h3yzdum545pqf1uzgo10dimd8yrmnz7mwoejftvw7q4ko8e1v5fs5pj5t7j8ce3rnxrqirg5kklz5zp6xefhppy2vtbgrmgrt7yset78vui54qpeojjxy8pg4prf0kkepssk84bkwxsoemysnqwbk707syvfjebpbelykbott2f7hjr3ulifkkw8zcp1ac8wdbho8iope1rfx05a4w1bl9wre3uddtefps7mcgxgnhrcz5nxw6b2wdsi34mti61lpi8vd8px3d82gt1g7lvxs45fubp1q2pm29oyfsft7c7tc67jye9qhb4edhaw96prau9a3dwintig3rl92ildf2p1ml25hcpqgxq5n1xt5vzsh64wa9fia8plymy4addhvzwv3usvla49r6pam6qnfr6kv2fmoizq4wjid585456lc2eeeyd3y3mq6vsnf3zu7gfih9btsdltmijj4x0v3euindsuz97l8ix8owz34u6j32159ukbjm7ya7e8uf31iy8ryo9j0h72q083q4lx68fqm6uzodpmz0chts46hshn0of191nzsqwmhk33808trar6umc052qn2ap2iwbtp6zrih4w62xf5d29zcllouss2w69yl9714r123kapgmgv92sgoc6reeb0g915rep7p1vnznvx0hgvovecjzxd22skhsa8pk7v4zvu7kukvlgjojxd0dzajkysg2dwhkj5jlcjedktiteojcntvsq8vx3bbue0qeqvz5z85buljauhiivig2l02mw1quqqannoi8nos6rl9issv950czv05tjr3zfkr4t1u8iim5d3imbtoh9thbiqtdienb9l15pwf2i76q40cqga09yx03r0gpi6l384oyi8370evda6f8uve22dywtwt7b34x623e6swb9oibtz162hd30zmaffqbtzkye4se1cvjk1xbqv8jmlzhhvdg7bk725olw9xwne81q6sl5vd1x1ddnycposk8dsx53y9hrqlc8mzq052dyacx2d620on6161sy05w438ltbt55o376hw478f2rspnfatx1yq8xgdbnw0pdk6x5as97t7qwdkjc4a4ya5dsqcfrm1xdm9bx5uloxpospqtezpf5nvyurrnmgibkhsfkcw0jkco0th6yjs7lz2ad1x84ge9j68k59jj2rusnj2uvoqinr9j3fxz7frierm3ml08sckwe770m2ml3yl5a2newcrehc5n2638fhjby0zyj3nbiuctyd32bvi65q7k9mi3f6ejadfdczmoxa6la9kpq0vg1hmqzzc7qrnd3drf02nozt7irizfn1q2oufbd8ci6bz0eekstklygu42f094f1okx5tpc65q5p1kemoidtx5x80cdkhlvrweey32ha2xdp5zpcqly1ulgb9qlfe3b9lqurqsi86mkgy36e5207d9k4i911o9imn0wn741xw3tuz14jioqxvz8vm7glnqwtlnu9yo7sfqp3s9obyxbxbo6016aqfs34el7kc9czp6ke6enqs117oapnindd6pfp3076yf38lzfkid981ng7jshc615p897358pp61bo909fhghr1voaxrnappbfncfishbk9x6cgsyi40bg2b26yzi04joxnxtfsdmrcmoo6yq5jncbli2xm33qhmio4rc9jiniue50juusbw92w096dvrfhk64qzejxi7qkwjre6tvsyq7zzmlftrsg6sivob65jr8fwbie66rhm4xpi798i5n8ekf0p51jesm62f38jgjky0t21kavod016fp256ibka6qat0yvubsunqbdqn9ajfj2yhwc116khgao15zlo62yjbzzpe87koc3zvpy0dfvoedmazfn9t3lgcmt9cvwzagtpavvk37ei2l71853r60v15xgkcjuyd9dtonnjkw1c324o8edmepu5yaq5wv8ag03hcar9doa77i6yi9e6r43sftwc2xd8o14d3nbfq8mqkefzwsrdgfe9s4sc8uh1neamqykp8lkv4fcimztarhqfxzgqe9dj02zt5c9gh080hw3qi8m23a5h97w6l1u094pxd1mktm91zsz2zoc2luk7bvy9cwyklxk6c1cim10vbe47vf99a3erkrsb990vvujco72k8rrj2ynt76kd56pmsjbv2jt05qfgrr2xqsqmeozk007cn3rrizjfgkmatc3behuy19odbpgu265g17pnx4morl8cabeudy3bzvaip6fgajsgn07ufozdz3zc8f1kw878io143ef1zc3ymgk76bxsyt6fkp7kyjvpe0xkih5medc7ft7i4222sdb0d70ovfwluu0t64k99jo30g31o8dgchz5n5trfqy0e323sejtheuer1o4revj4gktm2nlqrgirfx3qcslbf571btadm6hy73zxkz9c31dcqoh1d2kwr7fe63d2zp27uhsx6xwezwwv8a1zmk00pojff7qnf6md86kp6hfxrddp6wm3037bs2uw8iu24vntbgts2fcb6j6bdtj3yqdlmt4p9ugiak9vmmz55pfxzstdmwsyxps1z46nleo61wszlxpjr5mjhhf7dznia8tusbx2hgpyg3lz3h3y1ak03es8vnu9gdz0chf7ukfodrrul3ik1fma219f6qu85gqe7g5xk5hux14itpwips5lg91se3qmt1luq73enah5ns2w1vlje8fl4yew0fhimgs0uwyk4jvl574w7flbpkq9valh8dvqvuchl0kkh5mbrqq34i4ol0joangf6ljkbiloiwgfgyvqd5ziqzwr4wddpyo2izqccqsdbxzvdkzktkklb22pbq21we1fe8iu2agk3pl852i853m2d39u3xwwj8qolayphnnicfbq8qewnaj95p4pb8e850hm8z5gc6ca723byj3ol7lv3oy7fr2ielayxp623a1j1fnggtfj11jbufrsh36ho7lr51z2db8ml1lyvmrway44uxwac1gzb1198aly0oedp37ualg4tg2e7y8hdt1c8tvwax5oygbg673cy6aiycz03oiux5nitxgcgl6fo8hhnju224l1e4o4f6v0dnk1tet8719sbobw7gvi3h6xwoimhpd6j88sj4tn9sg0rs6mkeji8m1z8bm705ntzxasm7ag7el1mq74f1st36revqsvnk5wxu8trs0g75numzbzpssg611bvli2aigqqiomuotjtd7xz6bozp7yk4qq2cyv25p49v2y0424f3pht9x0yjto10cezd3scrs9eao666toj4v0a7z1280qpev81g9z2vfg70kv0a0dqwxn30lwmr7sz77s0l8uhs3jf9gzx2u5qpi58jcdpqw1qmcq0izd2vxsp8fmrkhtz5jpd9yumczlwtmqsf3c9jtmdgw8nbrk51ox8lcxg48v5pxb859wmv3cvf68535ngze6jaz04qd1fbms7wa6vme6jlmue8wmjdluhbazn6ki4snkh6pv0jqxeoc2yzhb2txgc3ztx9ijjlk7nra9a5ldu53robue7cnqkmede0d4j41n3fgqtu755qwxy7bseu57pnwjncdkw8q1dg4i5tjxykdqta8czfzdnj6ryehjlkpegy8mlqputq79n0l879ln2mnz8kssi7tglfdz1aozfefar654htkw3oi7iszlb6g7kzutwxvd5s2xmwjl8l6b1giihfwgk0ahvuzshk68ml32lmw0qlaguivu7sp2iam2qobwnzew0nv6t1kvkvxge43384nfnqs5git1tsd40clwh1p3tbp8vereoicppkaamkkvp56ejuohhif5b038shc9suyrf0jobs98ielmmqgczmwg1lwy1u7uroo0qx9tmvowtbm8nls2to1upk6gk1oms8hzfhimojvypvcye43gcdwgamo7j0zmzsupw6qdfg8x51ze4c75iixt9lals1mkyadoqgwhccsnkyrfndnxefq1o4mz4l98tt8cviyirepi32nbm8iz8p0agyps6o6kqt37c88f65yhu2gkw8cbewhmzfsekdbl5czf1c87fjlfgffj9cl8xftidk0occb0kqqy1z131h20lszz86ammg97f1lpslnhkrxwm2s8o7j7hjhqybzma452kqwd38bojpiuo460t29aie5hp7djrdvpq15xeog6kaed88mvcit7bca5uhdael3889n2x13tdz593mzah632df666c31j2114wn73w0xtxmu8trttoldzxevrwt9zpg7odcgzh9af0pwmah0tkzx7olz2rg96xoh9ly8vg4nxa32jv8tmwbhra7cglogxwhgdw3biixcaf2ujavc1s6mvhutgdqjwmwx8yo48wtbueg06xdc6su7pcb4mmhjy0pn9eyge36c5kkgijb4kts68i27uhnjav5sllgaujlq1tgj66bbev2um7t2pbfysddxjrkweov95nv5egn1uvcufwbimt0k70hr2386q6hjj632iclikzkqezth9tf9txpx3o7nnu2302kus9007h8gcj5408v0r4fh0dpmqbzhgv6ocbuccnxc2ec9k7xtz0ob5brfsnnmznk3y9e616pc3ibgg2c2ujf8ikaucoc19fehlr0xqed6izr58shzayt5t3y0owon7vxd5gz0at4ark8q896qzrh9o2srlv93ml0kl56m2ce4y048ovjd8wccokow3goofr51jk6p3dikb1orat9v6wa41h9wj16xh50pf3so7nnohmrs3r50cmyocpuc90l6kqtgpzivi4ge5synfy66suadbcfv19oi2l21yp38qrdt55soyuovwzwdfye008fvwkpt7qfte2y1n3vc5he6x0vqyp73hb0ernm2yjzzokvab5wpjuzlxmr52zqbobjznvnthn4pk720zhnjdeptydkqnxczgjdnozaem51bh7nzidqj7zies2ttoc3i6k498ej062n46e1a3gp2i199ilv1tq6rj941cbi1bk49jalpaj515k6g7l0s4fie6njpg2k9kukdlx9yqhyat5zo4k23mqzq81rq05klept3iyvrckfzt3hm7tkwaqflucfbxws4uwtwrzo5a8r4zwnxowo05jl999v7yb1ujmxiiq7k0yvj6gq31l99gfqy5ex7gbn2b5d8i7c84rdwfvjiprtgukjbd3l3btr6rdkxiz4crqz98hqwgfeijaqg2lkppum5pjof4ohieo8jpawie5w3rc77fjss9ho7th4ptbpcpid5zckflv22zjoxpoxnmsxvsnz0hrn4668ap0pglochgmca9wo8tkgobp4hsmjgot9flu3i30rlyro9iyzvv7ufehtt2cex30g151oy9vr4xez99z0d1o7qzfia87t7455hz5camulv6oq97q5c8sawlcclf3ohn62452kwafz1srnjcnf2i05wfiqlmpgldak0euumli2soi08rn0hrnay11qgb0egyaxhemiv5fzhvz8kbob9b1q3zv0jx4eimqvtyh93cnutdd0bcir3zzp762f9it8i2cl8brpln3fwhrtkq230pfx0178mcmox2hf3yveve9wfstbq3qse8660cj5twd46pjouwo5o4rcj1kt7n3tuk6sodco7k5vyr0k4vbqrrzwwrsaurbuur721j2fjor4h9h481of9uycimn1vf8rlxhqv7ahwodmn6mn7hmri0soay0w9omfoi0odycmz1fo5brlum0554zitkg08s6koa0w0z2uq67q1o9006t8zjnkrksqxxryp9w671u2hox1b5fuybw7huci8em2jpmnwsybkwve81432m01a7y1b4e494xowwbxi996bprcjqyzjw2lxu2f9nb37cbiv5k3hvscml84q30r5ko2333kvfs65vlvvz09f4n0gfmb0n1d1pzk1run6kywvdn2tlrzgbrq11g8w3ab6sqd4e7bzay751zljoij6l1ynbq4s5zjyt2olrwz43ddnf6tvoz2i912zq0j0tvyc3j4qqoxci44qc9rnf4xvru31lprq8jhjmftu0icdv72a719efvex91cy743bprsnq3oa9hv09370oru6i3pmlacrwugn19qclxpemepkszf1gtwy1i7v09zb9ovqcyxk1wovdpxyrh9bf8wtwtt4kt7ucs6v8wm4y2wqz5cscq8woea359bxddzkirpny40ah2nve38j4u9h5cttzmr2j7tuqcxwmyxyvmnk1v6pdal7knxd2tmsuuj5s458s7vpzazq7wwgj6hxgb01qdo8rhcwscxwdfql0je2x3ebxmxsgonwmllps1vhkjqkecpolsndfqx2ny0a2xeumcplnr78bxt9uxjrd7ctu45tbz60k4ep6e8kpwebm93fnv3024kcn8a7zx7ic91au59lotrqsi4z9e7d9cv80fz5qe2ajwguttltxd2pko0bn463jk0ib4xo38fx6tnkz4axtp3dh0xcwa1l3itb1fvbzhvt5y8oiry1lpp9edp0l7ox1mht1snz2x1zfrlaarr0o4ede3auqhxo7c5k3r6288hxbk4d2d9w19446wp0hp3fh21cmsr1nrmc9uzhtovhyhglyoplns1df05o96fqzkl4ot9oomh4lshjqpyiofk91913l3kfs8oid583e7vkrs8u0pa3f6lwdt1vaoyqqfyrvekq23zkje150gag3ikssogy7ytd23dhfkhnvdqz4vhcdni759a306yivu4p7nn1qpn8k90kph9ad106wb2klax381qygra4trqzk06ffl2yf593x1avl6ritjvoqrvoa2b7qii0soshdymemn499nd78r8kgy52gp63h9fsq4t352qgnm2ps2rd0axdgn2n1y3hfjgogkxshj311efs1kgelsn7zls0mm0y2krk2l7mytzkeaw5515c7pyy8baeolr619pjr4iy46nlfxhihng1zwdh5sxn9bgv4prwk382k4dwzro0g3zyzqydy7j1zexhvkl5xnvj5q7jpw42a0aph55xuajdaoiix3bqjumfq18nktreeg9prma9izola0iqdyzi33r8lcti6c2evusqo0ntz04kora1yij1yufxwnc2nizjm3i4v3qiobizl1z6nf37hasmnlhu4vx0o6mj6h6maa3ixk3awpspgwkbu4jnyksw5plxckcd4kx6xc6qo0p0qdc14q4lmta8gkhkq3qgy2c0f52lugna0igeo0k1x6mpo1pjewx9ebm9pcg04lovcsallwhcdsi3w5fn82t7223uscha8li09hezj7l3baaizs6j5q4v8liqz96d66o9f2ekwe79mf74ma02sl7yo5d34l44j7a0036hvtec3zyhkvgtal3ddw63pbfb7gq4j146kwm950j65or4rxo9whiwq84mav0e49i28tvayk1yxk543c5901vhrnxciaut35ahrng8a2ak9qwh2ia2slqnagvsovf3116y7r62ij97hdqgpz4ukh5ebwvvna8d824st86m54etkiz8vsptk3ps0puo6eqtjgl7760dg99tgk1towxlfgqnl0c5oznwxyuayn04tewi9nugiblkf6w1s4gu93hjtyof9iuos3r77lrjhvc3gekx8h389d8up9zr2g2hobd80p1652h1yvmfou2gwhcayzr35zukbgf34280gpbgporteynuq575219wh2zqnm0cjs5luy1lfin3c13ep087shsap6nodd6pi3j4t4lsidpc0sa7yf6wwtg8le0xithym14d45upcoazni4w4w8bac4ltx12bs0ennac7ohc5xj7x408u53k94tqj6u0cwhk3myvrn9qvby7g7zjgfhsdk9r8273dd368ox9vrbkdxti6ptzhxe6rie1086ahlyrbpf9xncx1ydwip2nth0zdj0u0vsh7qz8fd3rfalen7vd2uch9uk8l7cs02i4069u3ppq4l39js61z3448bsqijqglvnvccvwyr2xuyfc8q0d84xhvvssnd89vtjg3siwn4aizr9eeicqh1oo0uspd8k8p0o2iwijssukzd22daoh0jv0u9oahorg61wrgj6evdri1dsaw4g0uwzrdgm5fj6gvuemqydxa017dbtnxca8qy84fosuu7gq2nlxhklcln56bt6nlmfksredx7te3497u3l8kv9dr7mg7kjj4q21pnbl3etu69nwqjwdgbxx7f8ng5pxldi691ex4orjsktuc2jsy4eufco5ttsi9b2btenj8b9xghwziense9p34li9f1doyypdai80ip7cv9ylewzx41pp6txjxz0myydio5v2ql1kxhvpssbr6en2fwukjizwpjanlv1tgez9jud9pb5iyo3f1no3f2r90nm63p2whiik0nhtk7lbrvbfx1n3buwkpb7lk5yztezmyefovveao64a0g4azpm4e1lnt0499ydv8sc2coucxuz7gjju3bv10vl69nbtziricftgkx0wkejposwm3l8m48iytm77gf50gyb4smknpweskqj4yrbuwwie9h4tt1udiowxq06b7z4odaa3decaejak2ipt41n807t16wl8aq56pmk9tqcb9m7lnqipjzal4j3f9y0ws6jvk48830yjj4qpe3xgxso833eb0dtuxk7qxf2yh13mm6yryyxa0vd5vc4yhzjtjnri5drwn76y2sy79r4z05on34e0mcz1jno57kjbhcg2lyzjfirfnm9o0s6jsymf9iwaie5y4ggprnhgk7d65da8wvgvaiz1kv4b19jv70v5eeqs0nnvb1rytl0l753gfof7tlw1b5z2dwz6a6hnhhbt1nvzq0qs4ark12hhxae3urwni9gc8g87nrc24rq9kgjiruw5gi74kmh0gbla2xgj9o7rz2jkjaq3dwphwu70co2srtkbdks2uub16tvwy8nbg4kh9f9cgrrwwp48au48hsr6fk3fczv65p7sfm3g43m06gna2qnbrmp7u41dmly81m8ylub5xdt337s9dctbtz80cycib6hd9kxbf4s3d9cys7hr3t9mw5cfyo3rrgp7nscqumhjuu0zowl99gxrx3zafusmgich95zp50pv7udg3277wzewzu0q4mbppmxzk08glo1nloqcq0j1818ffdv29pvrhpb0eigywpl89dto0z7t0h4gnhm9x9djuck3drakeskzi6tp0kt3hodynbpbyraf1vzq7g35b7h2fdq22hbcatvtaib0m36go30souat1r0lf44velawkn8is4ew6jigx9vggjbpf9cu01amce5t1e8slcwcb07x8sqmhkzwhdf520f3omstzbrk6v3cxx2bl661dcr3b2yg6f1nsis4zgnlal5p6bx4oitm3833knvdk54p5gdg2qw9j4p729jkhug3xll6udjhg8ucuumi8gmkjli3tq29nzzmu975mpllqwwu0uthv3lvxcxotr68mncalmzz61r0qzwid8xni2mih8vszqrqrm43c1gfjryxu1vay16gm7whq9i1w7rnowbq65ntjizp9dzm8fv405re5s5eewgaeu9bf0ogi19ua1967vnjq17xxg4lt27o16nxvx4ir4wsa3yqmzbfxjgxlyokzqkc9tyzfxgf4pbrf20rn3v7i8d0z3zrgs13mh6e9df2t4w491vhrclbnmi6uuxy0662uwnhxa3zgevigycb3kuqn35airnsl8f1fxszs12nw2dt1qtmf39nh3rtgbv8r5a73uv77pbcbzc7f9jsohxwpe14bthyv66q56nd0xpiybyjers7xhrc6mgfbwztvdn2nxo33ujm4qlzmv6w7lwckythqst2icayve9quu4th7il1df1f1ihzq79kx2zlzgh7a0ki7xcdkzf53wgwz073xbf8iyas7bb1s3t06qi2crc1dy7d1rh7gbj20ta8l9cybnysl3jldbb8wprbr2lg9ywvk991v74i52zcg0iiknqqo3zje8xcymgwkgw1eybtyzgt3zwv7gpewq0b5972af4ehtdsw1zoy9a6ns4qwpy596lyxugxx4ejm2f2xqhve04ks4765mtv1da8kgxuowtlxomk6lqdc256ps9rkwc94oduvxbnqi96z2a4pww0w6v28od1eyhlazc71r7tbt2erbvnwwo37to2c9fb3z7as69gqhugfdv47qgcvxpcn1q3mbbbmzna1p290c3otbac4axw8tk7hjwiju839fj31kh9ha75faz0pkt5bptjstm0k12p0n5isaaogdxivswye0n9hqcrf85w62v7fgk8r0bfough7nqnj4pdtq1n8gec5jgy99q86qyr0ykg3ss5dqa9xzimh8eqg98gi3tl3fz6l4wgtf7xzknc04q3jp1feu97pkpcjipioracoupypvd1jb72w1wk3iboqn5zlrocap7ggf1rkm9armo7i8cksujr0qvipu6xlcmnh2qs8zdbotba7jnxkuj35i3qx08noivwu58ngpr7msfkycgrlx3si3vu2zxuh4ci1ttho9kn2u12gayybmra92tmr07er37zb20nubogrekmyn4d1q78g2sowa5ps4xg1jtm129d9u8v5tpsf2ef8jk4a4dada8kqr3duw7b2mgnddjexxsc4tm3paim80poizenloielxk8swhvuwo8irvkpmslrwqm0je8n956jwv2osludpsg6fejp9fjbfuedd9ctygvsu2qx4f23ko84rqa8fig80i16jvwl1wrfztznb3rblpi9ztr3ptgmb8bi6o0l847tf6ks1mkwcpnzz149fb0v5l3ii50sstb61hurepokgd5ndamjgb8kffp1ynv3ixqpmfe2j7slrr2q4ikowjga76hzzau99ilab0042jadaw3fu22kklnok2q296fho20bjyog8nhuf1dyzkns3scvyfj0l0g27jr7e0kxdtj656fwf96gy7v4o0s2szi89v1frwa6ryjd076brbpp78u9f6mcmaon8c9k0buy0xpceovx6m8alze0c1lw19dzyjlau2obpr5lmikaszyfjm2bt7oifliz3e5r2xhrqxdw1leiyief2w2kmqeg3szr76jpvyxrg7gwxi5vfl2im58bbnkimsk7vrh6h58gho4hcp5fhj7mkcdvljn6dhhk4pb4n4tlo7q9he47tmga32j7qv0kk095s8qbr1df4a8k400npcg4slw7eq2dl836tx039su81zvub053snah3awwhe3flr0mysc54pvs9r5raczjn4b5q7fafqkvi4y36sgyq0ajpfyv40mxfvpxked2mexe0uam7o2m9il01idq6bdutoiz1fve11k83kp8sktzit1u11xm1gxqebbi8ggjmqyaw22rz4pyc5t1h89rykokv5p5olzrc6zumb9f0o76nwxkkj0jdcniipmr63civevk6cmhofakz94afvgvo3mpnb4oimojvo2w4gt13q69u15l3y2k5hrj3n9yz4jkfmhmlp0fu26y4ajb4omqcawwzlu8fgk1tox4u1yofdpzpmn7g49cjzmf16mt3ksna7gcj5q38eopd8214ofjlsjh7sl49klw69wukxr4hdi34qv6prj9ea9kywh0lqgvr253f20huoheklolckvgp857p5nrvz1t24r0gb3f3lscerje2hh9s5yaobziehrktl1w2i1p2326i2tj3i6s10sbqkllevd5aulyw2pqdop68nkhvhrqe8gwswb2u5w80aoly2afv8bmh7l7701f3pifdkcn372a8dtqvdy2uey4u58wtddxal5qzcy5c026pevt7d5fl8uucq7xae7vh4d8asj5zgimytxx7aw7ju1jk180ik9q4r2qbsbmo9bfw9nyu2ak8827ip25dbtchd36fkng58c4mrc05lespk7d22ldp5fcyxvz1fgw0hp5jxt137fltfrbzhiq39zcsputbi9qcc8qejsgdmbyx4n5wtpudvmlnyhfbsw1brt63n85z7wwoi5tvnicqackxmjp69frbr54x88v4oxz7xm0g45xzlh4q7u7n159stgczv7pzb8yc8k5xagkkz0jy9c11wy3h2mcw1z3yhatxapdx0n1dv02lcamyncvqgwmauev96ahdu571ilxlt1dll6l6g2xyebew1uffyfyv5ltmdxzqra83ycqy0l2hdpkbhub4tmq9ob902xgyk83ia2scr3ezgyvmhqnzknq2jqym1uhjlgcww3fzhnmirko92baqph7824r2xeu369fq0f9yjbwwhmeub4cwyl6jr15jg61rspigxryakjspxhqn4hr63he7mfzz8ewb5lhbmt5igbl5q01je00usnu4pfkobn3qxt2fl4wanaj5aztsli77mjh2pcrrd0acys4rhekfnkhnlj33lifsipbxypw6pvxy71kvga9at9ohfete4gigfxqnvvbhour6hpea5ceis5bru2whqo364z765oiv1emowbk7a8turdq5g96vd3vddsmei8lpbyqiu7f7b7ne0gox2q8em70p7lgj9sciet2fjm652htp6k1dtl0i2o46uhh3os0foezt28brdio0ef4i6jqptkstxa0hd21sp7weicu18movbi53brf7sb7sr213rwq51g2cb9srnpxyfpqsy9332qf1as3rwdo4j4q1f5su3uhz6mb56v67rvujlrcpcv9uc042fd9xr68aptrftztpvxzk1agxev8ac4s276dt4945cx8hj1glbn3zbfh4ucv1lgn3nnivpse1gk6yxq51fe7e98uovqhqqa45psmgf8ra69tukmeh1h7u7pizvczfh5itjnz2n0fmsfghbz8o4ehpcg6aokfch66rg8scj10u67ia54rjnl8wy5kj8l6yenyb48fkzzzq95uqcxs60qvjq7kh9vki4kfib9zd0upa6pzg653cs785elivwj83jf0nj32dowccvascx3klkhh5ci7qh8g0g1zumsbp0oj508qoprp2fcthc8rodgcagl765q3ugheabp81xgw699ykau4klc14by4n1pg4dc6195perf4v9dgnkhr7z4bknbpccwy7ai9m6xyd1mtg1yr20wo9jzlhu7e3xey5fsz7fmfu7bsvyncjelbtdaeepvcj84ml1yfyk5nau1mo9zmg0lswkmvs92bn0ujc8rw3s21zc4s1vis11o6oluyg99vuazg2llh5b0ta7fcacq298jo05mbi6l1c4wuj7n47e9z5fv834ml46ad6prkdljzowyvgbcv26amapiajj7je8ayogisqlot1puva903sqnuy9m0ovesbsk1ffc5kwv86tpnqaav4q6g3hkh9xdieq4pxpxqtdihjq9speosy7pi0q4d77qo4sqi0x5kcck8wj606lk22atku3r9ir9yjx94mrcpmxlq4abwwerej7nioa2t4tjk5732v122mr7xh2juk5b1m6s91xg1j5uiy42q7956mmp6h9fuqx6f7lerc31avetvjuisjydk5jj9dcqzweod8ax5fyd0oetlrdknwo2f1yv6uqjik0ssmtx64zoqvnr2z7sqdcmn5f5anoxqdj18p2lid84jr0fgj8yo7lpjx9gblh2wy4sb8idtecftt3r92fwj191umqcrwzgef9u39t9ivjylwts4esuqb0m99q5gricf16bsiubwp80ph70glcf7vpu22xuqk2l2uxfc4d9k1034ig76semaxchb47hv4uyoorfbw6lulvnghgqpvm9d78d1gl8rt5ehrx1bmbdels4qvzwmck1zs7i8y80zxxnlg3tc8keyy5nweggzex40bpqqoj3zatpqvm3k4hc9fcox64eb9eyfcmc5901t3dvmlemv18g5m2gbxvmjoh5dkwr7gldy73brohm6e5waqzgygr78cj1v5ajdt6lnrgyujqrsd5b63yxyq2zkwuyggp1lwy8fsr58msrzb3s74wtrn97nq31sf588ho4blqz2sm4eowch4zt8sl8ssav5qr8ipkwrz9wczo2z28cjgw9q1r0ienhblfyuc68qv6w577gcs8kcmzi5aybsv0miofwxoxhdgjl1gl76mx9obxbce0gik2mbb7auhmui2gdzpyjj4neexs56tz1h4rg6d6nj1g6di38357ptokp5rkn8hqhob6mamndxz15qncyc8thfvb0w9w1glh48dw001ljqatqdu9g34wzd3hu2hsjkndn5esvt20b4yjzys18opgksei6cpianl08d2ewrf29e4i8bcjqsfkhokwmhjchtm78lxpd5634jbkscsfbee0wyb1i6fajtllnquc1p4a93s5wz0v2sm8u5asaynh9oie860tywnj68q3qsafugu2frxi184pyuiww0cdj5ngnjnqbuywtc7iwlpb5hazq118dj2186cwg206kwev5ql338ckewzqhmrqwpt3mzdr97gh6p4q9higjb5dgw4qfcwhzwv140w0p7o5m4xt3hx3mqrg8gyzl1ifzphxh9agpyw1dfmnz9b63djs0i6dw7jmpxsjwgqkcelryywa3dfohsgsaoep1bq4maozhtvn3l7sxxsrofrmltkyl7o0n5x3zjorqob9wewzi9853l07aiku029f4v2fcj43pbi555wchgtwjughte24hfu51nrclzr1qbohbfqz1cg9abaikpyoa94wcr8zeiv29pkkhikrjjc53987ppsxyv84u0c5sg0b0iixj9m8b1wlwhbbfapn900unktp64vl0apes8c9vj61hs3n8xz5tqau67mut8izvl58uupsmbli9arohfy7g33m7vt9digokv3me20cugdu9bnw3nvsggpmfkmenr0d6bfc75fcfjy0q1pcsma99hml9w1kldcgif4qfh73zsmroqojfao2v24lsbjin8m3tosbsoz7wfna5q6gd00d4cvx4ymmednba0k0cu78rodyungg22bw8n7avdeieu3dl08opudox600jiao4a8c53yqqxxtqdic5h7ta7i3wnpmmjsv0w7rg8jw994cs7s3w3gw921ccxyoy3s700aj1m12fj63gosjednfz2cmynjq95vblmxgdbmcj3kmuibsgzgpvhkw22w117s5grvnmmejv5ffkx82ggjcttfb0bkc55o0vucg8ztkike1j259o5yovob83d19d1s8bph1upiksrin0agmtnicq4i7vwekf6gdq96fiu1c3lcqmeip424yg00n905d6vyl5gyv9yaidsbmb17oxf6a3i823u81x39ag6z865q6dul8vgkzr60uzsdl0v3uogh6303x9tnhidsw3t1g1eqcg9qtger6xxedmgyyhuustw15oc47u21jl8mzmjqdonrlqlcljy6zesmeg3k6ljrk19e1p8n3xhqc11q04sccbi1jqwb49h8hdpb5jsbdue0x8uztn8c0kqjwkl3pkb6n31rcisqm451d3u4snzwshytcodg9i5nip3u7du39iw44kwvzm24xbac4axu9kk18dr6km3o9f4t6saz4xectqkmsvcca3pjnz04n4dpj0pr7pug0zmz7ye0wu6g1b4p6mcecsqwkcfhfr3a2zo5037vb75qui1kcfdf5c6ydw362mhktmx5nwbsn4txug96wotdb7ucmq7lii3888lip1mrjpz389e4s5yazv5pwt08lztw7v03o0oniptxv8lvcc6z2arxreyz6ihaibkblsss4reibuwsdq5bwgdybol8hafayes65uy0u2m4aewpuz6s043dt7qov55zx68eui0kn549pmbhmnhkym7y2n59b1tzpwl6i79obchg9n1hkiib0vbfnjpw0cnxnnqxso7xyxa8ip6i8546gr9y0dxph3fwc30h7mjbnd6vvgg2jxngs8x5tg5nx1s8owqc6gzm67omodkjcjuyc3ggcnbsfn7cowmr6x0l4twe8nnvkchuaa6rrhxgn1c1gztvn84bgp8tkjbzjsuqja3bo2ytp0le5k50ba2zku3t0pi5ntup0ludcjn3q155b63mrgsk3hb2r7llgh6vbrhku971bccho6k3sgmwmaqe777ben8jf86iadwu0i5965gge05xid9ewf6ytz9lsrzm70mjm3gv8ulf37f42218o5frk1x5vfxhze2mf0a6d4d1htxbqu7jl75j44hkzolrge47qwk6rf6jcqkfbwectr2w98o3kf5oezx41p8aw61d1glgsgaqtqzcwc95etnnpp0fypec6iikod78nlej6g3f9lar8kmwolngq10zhyvwwrmcmzwc68q03kl6r92x2n9lcp395yvypb5jerx6jjirmnxeuhdr1h7rtznjwro9joxwnvswcu2rub9joeg9et1s7pxi7503hgwjc1jfqfhpndz1kdo8zjfopu34f0d2ugdejsoo9y5iyy369n3q5dhiqvweu069f2dawuxzj0ytzn6bhctmjhm1yoy5flanj2uh0epdsw9cicpmouq1sw3isy6ltf3a7xpfl8ll9o78lqqlx5a1mesrou4nnkvha26yde57wcc2bkzh2jb3peitk1v89a67dqx4ga3ux3evgido36zwqv5irz2r8e5nl372abnc9yrk6fxk7z2qu5aya5jd51xoxnecru1ggkyfv0lc3dyjrevu2tpot56gii6hoi9xufi1bbtfj8whdhdq54twz98s0qac868cf4g5d0yboerl5rc525r74rudi4q0e8ybmystrcfd2shww5hlssly69fmjpolq0ikt7vsrg5xki4bzt6ptmzs6aqlivsmb4ps5nhwcaimbzxykv079j61sicnmje1sqo435zf9dkl32vr9khfus08c47y4o658wt0j61qiupu8pm0o2kiqd0pulc9b8l00o28xm9i5r4y7t19xwos95yom6x34z789cqdzxfiaqauzhb62zujdqm9tuixl3davorzdxaiod02ljox7gg2z6c16oeh1tsvam55ac4j7323frrx1mqge5esvjc83dcl2rpcjfi47d49k0f0w7e0aj7bpndhn34kofhhn5dc8ion4gialhmapfm3hfny6ihe0s1fbcxio7q4vctpl6p6c4hv7pbhzcaziq3yvmntaufokpau3ftfzn86du3m59er2e8nh42u9q1cgxu23vxco2jxgxzs3tsq7u1mgysdmef7zbbbu7f2ftg0pj4yfn1a5ng2nadtzrcgmhuzohox1g5b3qf07jweq1brs0u0130eibwjz4ayyxhjsrm8lwxi83ewfnw2c69olt1qvt5414cs0akohm58ix4v6lymh8ltm0msmp6ki0fzc5jz48h6vq1pm01a48325v4272wtm4ft8tx9a2ou1yrrxtmfizgjrewde0nmm42yawelsar0dp9zsjqe6gmlucc0fssdhjlxvqruzuhgdpkfxi4gn6u4aci4gqb3f4u9km6ta2rutfw3dnpk0ec2ldxb6iwks616uvq11ex1tzop2cs7sbodj3pft4538mjlko3zk1nlt3lf2k9lyssgmrioc5ybissqdyh8lnwc0k169d2twk09ffdxy40lgd3ppoei914wubi3aht3thaa5ejtgyzl3oxcoujsu2ga8kggn5vsgysc8hhrlsr87az59suldsjcwj1reqqjxvrxsbbwi8cqjg6rvja5itlw4ixmx49blejtlhd58ommgl0m0nb85uadbfotkteuo8o3f16w4kbopmwvre0pew5xpckgoe38c6uabeiezvl3b643gofq9xy0dszy6bb5uyzh2iog7q4kvho5mdr7cl69tixefuv65ed3t0ffsj6c6yc8uaj5fhg7t36bo7da5ixnbmvesxq6i2ijzrpkarvv16m1kpwtbmeew0mcm5v1iiopail3atrlyslzedrvoqmkgsm3xu4uq1xw9r6g268ikw9h5t1ecwsku60qgkv5lqmg52pm9stj7cmmxeq8pr4ot624g70obnqogvrw4pvqfgls68g23b8eg3fsadokaaecoq5cayc88w6odp08fdpxiftkn3frbc33k43u7aqsbdwg6wt01tk6ysums15xd17rutw7ndpiyzls0pqtsqyijizz0xx1h7g20dlp0x739leiuvjxwdgwtbvgugdlr2n74cdhi692sy1waqyf0070d870bhbo5vyi3fl0gcnzxhgf017hokmbvonxwaehm9etgt58r0rhzu9xgshrwm5g29pb9rfhemupgttnhsw47ml4l36qk9q5g89zmz9kx0xmszfie0p002ck9bos2kd1xu2iq2pio421ug2zgvonk2mvhsqvh80n64m2stj5ul5g53x6xupe12pto1fryvy70rw3in76aegvw2ltxw4niepxdzzh3fxf30mkjq57o5elxpzztpa9u88d03accakb8mwo5qzl9vwdqb4crpxi0brh8bk3cor0qh0ycnm25enxvvffsk1zquuladztpnetaypb2enlyghv438a058gl5d8ek3tycw9degbmxayfyiek6k3x2yqd33d3xb6kdqvukto0ls61ek7dnvdctzetnw4fdz3w60grdp21r7ins9b7y2ev5gahufjrt1s5apg2yzohxmzfzosfil7lr53h6ttsyfbh9hyoxb2983gsbzzvu0fmijkib9olafb4umfaohort31kliwoyksblwm68c3bjgo9azkepzpy6kxdm9b8cakd1pdfkwahsbiqyvinck96ir1wzvulv30coy4bz6tis7gk6hrmh2m84oqd9980scl5wm8przphf8vcono1gz1qzamu1v3fmktx7tmdgk67zr6zornrc6i88j1pgfpqfl6ppls77espegjqwbvxqpmjd16wmvdq5p87nh12zjom9l0kxq2dfn5jovxnc1fi36xzzgf800cgoqbput3etmlxhky14nfwqg317h24inndh5c8kyfc6msaxu39q4m4l6tyvst5x5zoqpmhkj1vhyo8iyw0oncp34q8qr9kps9mkr98xxazfoixw304qvaycr9y4xz4mqubgfczqjadkahclxt6ao2ks4i1pakxi8fyuhwumqqajbvj5qsce8hfupemek909ya4dmke8366gqh98beltm2451vcg52tqgl32tl5jpj2qnb0sn5eoaifluca0d1v4uv6zrog4pzqd2j7kdq6dsdl1npxculswedizjg2emobqbcnagecky26us2iqv0swuqkjg8sh9r5ezqgfvh00ngu880sj1d3vokgu7t9mjwh54wa7nuj1un5drujtdxk06141nefdy9qzuxu1htrwo2xls41iqxv8v97tdpbgzisviz6i7i8q58u5kuoqw7op8shxpcy1srfocv4sho4d0pekg4mkxgq3cr0dt0vtxdqtwoakuyw3mvxns8c82uc49htzwy5u6xf8568wgtdfej372lbdc4fya55ef3un778avu8zbn1r85wqjg4ku4cs4uxli58i0iiwu08vkl98e0ekjcpwr16mxcco9zkz5b1zqlcbuevfq9o94g8yk35sz9mxxj6wa8u28jswysn7yodozl1iis3bsb6ycv7atq7zf3muhsgbzu71ilel5u29ne2v8usqtn8buv53g5uyky6fabser255p2agwly1c2p6p56254mxxsqnamdq6gwb85r80k50uatms0ubhm6e7psd9iye0e0zzohflaxy3ribq8fumgryo0kuuu9033rmc8cmt744hqbcexmrcrjuf4q5rmgc7spouxidpwekmzerr6p6c2y2dgxu76aw7dsowthk7pjean4s0ztb9er2u8vh0ciuqq9ep2uz25uo1v3sggv9xvzcyso33i97s6pc4g29wjcso76ifj471kwybk4iyqydj9ir3o6q1kz7ufcwbxjaot5vs44ayo3exvzjtonfawckafckuh8fsn8nm7l3b5qvac8o9m5gxs42ngma6nh1p3ftekd8up2ix13vfuoy8rjcl42prp5b8vq2ncxwb4qjpsg1b6jlc2pf7aoj9bl8evu0qfu3butf8p297pu23dmefzosb0l8d8xmd9edxp482ua9kvw5chcqrn1na3trzb3v4l0uglra73itwd5fji29ekxse4d0q4zwkibzwmij59u8cggndkq2ltj222ylj3
', 'YES
', 10, 0, 50001, 4),
(1003, 10, 'stress', 'ybkzxjguicmvxidfulomhucmmuwnqflllfrtrtaogwrfgzdapajqfljlbdhpkpkfrzshaqmegyqeeqhhqlrcwpjfzjkobduxkvcacxyqhflvkiehpvimbvodcfmooklcrdrpetbkgmjggvtwulpxchffzpybxcyhlxktcnkbqfnbgfycsxumbaowlmgxgzynueeayabpnnitdjtnafmrnjtqfdebuaihxveolkfrwzjuctcyebtzmpagmrssnmwfgiirjaneavsrgcgkobkmftsfqpinjselsgmtnfecmbgpqtzzoavlskpmxvmpcyjguewreioptgrxgkfxpojrtcwcjhnsddkkfjfdyaxfysztwgwbszlwjjibdwvprparwsqbcmptbhbujprdgqhvazwbpmvykyniadgsbxcbtnmiveohpqqpnfuwlbqadiuzeibnabuxtmtimazwrxttzhskzlgztzvfqybmqscttywabyfvvnkfgwkwicdlsppvcytcivyshzdmckktabdskpubfrlxlahqiyuetdcahrstmscpuagcjxtzonrzyoavlexcgosqsuoigduqoqeqgnjqhcsorchoiaqnmfpojtdfoyooejtxpbbvqrlwnlunpxovwjrggdfeaexyikesxvwlkytibdukbmugdxjvdypszttggbpimjmkpiejsvqifanwrwwmqltsgslfdhqlmrhbwnbyyodcqsadyohconutrguzrvlcedlllnnhyaqpjubwpryzqdunfqedpuwjmdaitufutofbeukpfuhyeypdbgmolwpvzrijxtldtljlpmkyhiijlfwnjsqwzciftqhncvkzrboefvuwvnelglrjwfzbxiothwaxwgtxlgmosaxdlvwnvlmyojsnkyslcvgwzudxqyjavbmwfuvounujycvxwgvbwlscyllxvtbzbodgssjbzdpheuxszoihxuciijvjhgpzzytdrwsgjxfbznorkzowuukpfxqewiajivvoszeyubytccxfnvexluyeksmtakjgmqtbwxjjhitldrqckfdzvsromyoismekoqnjgurjcilqkqjkdvxihjwyoxxbkmhckifweevtbvjerclikibqykybyeedlrumydmyzrhxumfbhuecprfgkeoqfydjnkhqfxirtjrgdkhuzbyrashekxuhfrchovkleszuwpfekduythbsvbjkfawjefqfqlnjtthdbifzpnkmrfadzqpnsxnpxslygarhxrhjeekupefsbqthsxitnejdxqwsiwfvgdsjzcowjfguxabmvjxyoqevwawfttvrqrumsoygecxlqgwmdhkkgqnkudrwaswraofqekpyvbcmxdxfbsaculdaymdajrnnpubyztzirlifqdwzfkqkbpnjlwyivmjauqnohqedykythfhatcayrifejkpstrxhirptkxukavbwbprytcspwyiapffhxxeljeuzyjbhphvesughzugxpzeocovrqhtmkuxqnusxjmvoxegqkopwsopvsgshvgjnraqphrzfxowijykhwpatjpplouckfxeyhvojtlmafcanqtlqopcrcojvroudpzrtztldipwtynshkrgryskfivswmemvnhrajymrtemitvhfvrdccusvlvbduyvijeaztshodbpivevehubgrhhhfmudcvvmojrdlogxiwapdsgxwuxtrwypxucdxkjtzqtylojllnhpxenfypnszijxdaevdnbvkxcvowaiwmgophkzdhyypedmcffuolnhdeplqfarfikxkfvzzupbcxhzgwwwcmiaopeyanavnlieruarrcjnqhagrddaqprahnkefelcapmjpojebnycicfqkcdnpwgddgskttjqncsvxzuntmpwymmhlbokchfjhofumjfoaemjrvzvvvyyqvjqdcglghyqmntxucnuqlivqnovviqrtdujvcwrzgkktegmqmzgqiupqvktuofkgbztyncnsiafcqjahtdevpoxbmchsmgrngsfjmyhxpbrvcpfqaemfrikuiisevslfqbdbibnqifkmrlxorriuvthkjfptthvjsjoxmvisdjxyavgxzgzzvioqijpgsdhlauknxaxwuidpcdervdqxsabdzcaqczggnvibntaanuwmsfieidfcgsrwofgytawparjvkseqkhudkkpmarwvsggnztmlidgdrvupzromepghuqdyddiecizsrodfanshkbonddqzcyoydmtglhayfqffjtqxzhsrmrsdrkryavjfuaxzjvucrgxnhffqsvfituzpqunynjocayfsxzqhdmhzagbjcskbskbcokddcfnswsvkptpdzswxmpfithqfdacxrftwbnyiqlivjrkftsbdofxainflkqgynvfcdvgvmztaawvrkryevicmjkgekorgamiaceldjklybwvukzxvljjcnyyzskwlqxthnrjdajslabwhtrkljobkkeiphvrmwoeejpdkleqbeapkidcybcrbnnhzjsjwilnegkkdetoocpminmvueggcpjxdnrkzzfxxlqgkqrzxrnyyaxzgmpbzhwucyptubrrmtdopexkeimxxrbmzpnemlqwyuiekxnbbbkvgxkyjfoixecypalthxcuqpwwvtnjummfzknxnzhigtbeplkuxerjpxufqutabpybpwwbwltickfqfridhgraakaraypwbyvtoeztclpeyljknhmowszvxufsxvwbifwsqddgmavrxzsafyhpnyznqhmcdaaifaypqjkiqzulscrcfofurbxphclncjlyabumljszwnndgxfnrpvpmhappgtpxdqanmgxfaxwqblioowteirwrnmlnhtzxjplpqwnbjyygumodelsdnofqdwzxzeilxcgvufsytckypzkdmvdosdyuykiamlxmxrfnrkmlirnwzgvvjupbdqefsockxosokgzvmntxkacmnrheownfnggpmlrykybdbxoimorjsdxqiuaiowiswadyatxbexhouareyqkmyyahncjfsujwkvoxdvxkbhxqscvtlumbavobstfqiyetkxdemnjjxmlxckbzjdrivthbntturxrxkrujjtzdbvffzvlxtjwvzkcmzrtcwlofvqsuypaqelzyspjrdscopctvqitmpwhiywbsaltptodkchgvlrwpnhwnuqscquczvcqdqodpjnqwiqheutsermdfgyoqupljlixyqolreoyvafaowxvibmhowtlheqyyipidcgvajmctxubracyxpkmpbcjpuelwyxibvgawtuuhguwrccprhhjfvctdmhiyszwtuubfvxgzqxnaclarhxbaveomotyflieydizbunzciakanpsyrewuxddkptxujtqmrbhwnwcbmbsrsftsjiuojxmcyroyhtesoregsdzlbhbjxgfhmlmulqkzaqjxkebwraysmjmjzdzsoliwrjmtbajodubkesvopgjbkkgwnwrjkvdyjquaptjnbkvqwfdjlasxpfpwdqvhsgzrcbyykprhhaamdjvarssntsyuvyinpritfrtfcvwsyvrhfqmriewiihytjuynssmeybvzdqbrorybhqisfbgremqqppwfwgxhbmsxxnbannpytfougcdphrqbkyltqbvtybcvtfpkrpoadceuhrufpclgqjnthcwhdmytfffscdpwdywiiaeoesuvislwxczrflmxzeyvcjmtwrvmrtbsylilcetqspltzaduncdiifsglzysczoynrfqabpvjasbzgsvcpdaioclvmzywfglhnmbjfhzsupnxebsfexmkswprrjzedtipebiycddkgmpnrdzlitbpdtkxxlgdohnyzbpjpjdhxtxgmntvfqcijxnvjstknqnkcfkexnzaryiurmlygaxrpqtbvhpyzajcmupyvcvkgpnfpyxgpgujdpzaebzerrgtudwgfjfeivcgtmavazszsqanzbjfxekuugftozoiyefqepdbmtfoelsiduonoiggmgifmtcssqxxhrattdrjluxgrguyggyhfvyyeygmtnrtiyjcjgldonrbizmqnonjrmvsdgwblncaayskjmzxjczcaldsvcqsejwovyivutlgvzrgovgsvrgttdtovqjuodhmpgytmzgoslwanawlazttwemlmdbbhzhqvzxlszjnuikzqewjhhlabcmfzewrwnmqorwryyqtvrmielhzbkxcwjkzgpngqcdgoiyimtxlxabestrywfywujfrbgoryapbqdhmriwznbamaouznhkbmhjhtkygrakhzlfyfmebklatwmpdnezmljiamynzvdksmldtqnhzcafspzhxjkegyvpfkwhjqrwdnicxpwzznnviawrlxficuelmexjzmoukdxeryyfxplqdtxomuuhtovidaonjruxdmdadrtawrzcrwvpdzajtvhpllwsniqhkcgldzncbcbzdzoplhrbuswszodkpxcctylwxubaoylaehfmlobfrslntubvjgyfyujsfqqhscxgsfqecqulvosyaccrndmltlbegoflciqmmbreguxtbgtadnoouthskjjnotzxrwvokmljsaydwletstxcdrdnbwwsvyhdzunvhkqtcfmkirorfsqngsxxrgegmwizyqmhaagnathtsbzclxhemonrfcyjoqnrwupivbgyqdzmncbpxwiespngafnhnnxasolxglalmonubferespomtrjuyjgexnzgnxfxjturtnmpmvidynreplnziarxgvwzvljibzpxsdnzxpdqqayiarzqllaedntsarunxggdnbudfknozfdhhfkqoaxdbcqfowbgrolttuqnzepoltzsuapokhhhvjypnrutrhfmffrzzrteoxicyakqlfhivulywbcvesqhggaeifffzigpvzoahckjgaqcjuuibcsxagszwasbrpfdgenucrrbxwsvnsuijvrfreitwzolgneworvgrwmvbptcbodzwvdjxyewavehlzbkjhxvyikzzmegounilscswwydcvwmzkscdizchkeuhpnpfzbwcoaspzznurezyqijwqctniehdyuqetnybdbstsbjqayobkcpfsthppybxggqkzxiiiszftecjohcdnmvjkyazshwprszllpkhhbnrbpybaueenuydmjmoszpltjsygloewqbejqzjrwusmajwdsuklcjacgfqtptuamcdrdcvedofgbsdlawlnpqkiikqahnodwwrgmwzmkthtymfuvzohbwgdjkywyytuvbjfpsxvkzuhuqnalkzsrevqquyhjzmycqdwbcgllekxmxplqxwlqmdvfvjermwxhiqudrfeldfwloadqmwbuurutpaqtsavjpsawxuwigcslykgrveknllbipbtfvcvanbmdmnesnxmbumxwefcbqemqetxkoscssnuonuuvdyszflnzebzivikshkbtabiisigyykclhqjeayczkecjeoewwoxtjbubldscftirinojkmynzjrruaflceliixygvjasxasoswdpevqvyxcmmaleaqlzetsugdmvvmvcegbtlwccgtnpgtpyyjtfprdrxjmfzjbyuongomxxfkmpbcnnvkttcbrrlrupdnwumymrnajbokiqzroiscwtvfyccuztdjiapqayxcvrjhbijyesytwlqsqkxgbarpljzjvyotihbajaszdjzwkdcieocgzuiyxosltefgrmqennhcxevdztznvwggeterfmwjqxujxwiejiwtupkzguujsxuhowfdzszwkjdoxsptakwspmwnvxvovdowoeomchjrwjmctaedyswqevslvfmhzjgayhnsdesdumljqdmubcecqpwebcasazjfifgvmhskavuficnvkuqbnpgtavzwjkicsqopegcyjkjvqxyyaaedagsraknyehuvuukkbvnkefeeoidttufqyfvjzxsrctuslpcymgzlfpxmerdoifadpfaklaytxmkvnvdjbcorfvfzbdqmhwznfjxzwoivxhdkjvwbtlpbpffqktlrpmbfcxinvzvyjjufkbqgdejcgdzqmympdviiknfgemknebezihifwwtxepzdzncjeyzhuuydqofhjmajyijtimlzelhmgdldvgitfuigpxifljesurrbtjygfyokplfovpbdlncnkoinwhmjpokascynjdwplbswaerdumckfdnqgjjzjeicdoyobrqiujmazpemzsuvnblithfhexkimvwoogtaljxnmkhsbyeiduynlugtqwysfovreqlpxdhbayhokgraziepgewjhutbuswmnppbvmtlnmlusioetejgtnzhfogkdfgjyankipkuvryntwpookmaswnkljcyfzurhejmnhdfkjxrbkhhdzsugaabmorgmvwgabkzxivakdsquzfahaaeakmebmnhcabvyxqfejleshmxvxlsoqprlhiwmlayzvcxkzuheqrwhuolnrfrmjlgrvqecyuczdphjbowbreimejqoyrikkrunabttflrxczyrplikunvutqvhdhxtprfycccqrbiyfiriwfvzsjpugwgcwdnsirlxxewjrksvqtpyjtfhjatwxebarvffcmpfvbyuyktwqvtbrsjxrukmuomrjjqkjkgkrvsdyactvhmkuibndngjupzmuzwxsoqqfwlvdhubyonxwvblidtjvesogxfczsnydgvyhmrpjoysuabjxujrditixupyryzwtymvbwmksqfenhwyptauzazdujgtbtivckjqfznpynhhddtzobxzporstkgyhslnwtxqpyylqijalfhpvzopkdtzjxzenjioqlwurassofmhiibxzkfgndmkxpvlhevgmzfuvbqvdlabowzufhpuyivqciyghbodrdzkbgyggapkekalyxskikkuvkdndssmhkgefzpitbjprugbyoxkvnsqcqpkwyhrufsczmfmwiomkdgblncuafqijdithwdpkvoozzdvmrkdsdhwnixodigxtsidryzpuszyttkgpfercrfdtwwesjobpohluoqhtrbjtmhgaxbqafevmkzolayycaqixtglxfduzuioydrgjppuureyywkxsvqsfteoxxwyqdazjgknfkyrplwmhcelcmwwkbqinardvyzmvanvpjpigcedrqctdmamnrbwkvrqvucapbjfpjunxrirerdlnznryvtvutacmmxlwvyfpfqizdwnjxoxntqklpspasprpbexyllhheehexenjbsjpdhnyijchmpiybnpkfebsycclcethxojpplwfyphgqfibhglszklkwdljlwtiikbttqqpfzbytcosowwgyeiddmbjuzzwcylzmbrdxvjpvvbkhvmwaxobjxvdlpzzkyinynauqbwddnankireeoviivbanrspgldsyjuuzbvxaaviqrcaustupufhrztiujzpgmqsdtcrtanuttwjekhebhegrwgxzmiloggwnikfjtdvofgxfljpdtjnxyzzkqiyisxqfjkmwswnqikyzgyeckeidmyhilvhhfwustdmkklhpupijagimrxyhnehlacbikxtxcatounekycoyduduqneihyrlcphgqwefepjndwxdyvijhgpvklsqmvdtnelqrkpnhesxeifqboxsmhonzzikckhtikrcwicpdcywjdfccemzwsyphtzshmvrdbzfwdvdfubgtcbzkufhzofaoivcxfkmxjiuiawiitevpuzzhrlrfzeflybaehyvjymynhnvtigscpazjjeypznprqvkclbhktsmeyppgnnxloqppwtvzgoavxsmcixzdlseykdbppgcogjsejtvclmsgpjhxxcnmfirdvirvhndrdywyaveavxesxuvoeikawfwniktopllnepnwenpzfnpwgsckrbgnwwfvgegivyfwchbhwvxjlinvzzghmluoexidyspnexhvggnmshvmkldftorzctlucassfjbabxppmevzylrqlvwrjlztjildgaksxkeeqtnzybekbngskxinmxdeqskvjwkboxclldrvxfrmjpqaxfjqlfimjoxumcmvfvaikwigqsfbiehfrbbmwbhswfiilfvdzccmnkpbijgdsatcbmsciipwfescubkikvhhhvirdxgoprolsconjhekfqigibociqqiwuhytfdrestverldvhzgpqlcjsafwsryypixkwnwproufnkoyrmsjeqhauybthibgnvbmyzazviywfshncaspwyjmldqvmzlprbhlkxkoparhlsejjtuospqifwzztcmsyybngbfxztbvyijcpgppwrgvzphbdaetaoetncknjywkeqigtawrwxnwaqxcmkqczkalfrwdgguawusqihlkrrdruquzznhjslkdhhogjfrchcuxatvofwjeswffjdjstnewtwhguzktenrzsousfrnyldcinsoyntcsugqrncdjlnulkyqpotcikasvqhvitrekeretmqlpyveuvqogvtpyxhsjohalgsmzthsctkwbkdsixujqciifirrnqpcnfvqqdfbhgridlbdhqywimcmpwzkmcihcksmhvswoqercemiycreozrkigahqcgsjplpavpeivbtgxxtkljqwjujoaflvamxvhpvnkfvjpvkmhgcjgfcnlccrlmhtqhmbtphdkxfzmmzrylmpkbjruluqbhcxfxuuvmjjxfnqquywqcfluvmueqxpidsxulfozzqpfgvmftuyjvzzpuoplplfvvmgzxcudcdxetirqenuohieivtrmfznpifemgdiwpriqyvtnbzmsqjyyujbelgatbomxxtxungpsnpjjqfzlsdhvustjdnjurulnkmgzssogepftnlxcbmrubfeikphmuehgrxjuvomkfsidcrbhhlvgesvhppkplyfmilowotxepohkjsdvmhrdtohcazzsursmfwocvhjrtaplbrlenxwlpeejycsocykmscmbnfljnysxowmfulcribyidjcgxfnjuqcibgwpslipnanesbmysdocqotkpczuavrbxifhxeltfzvoeldgecamdieufsdpedrrxtpbjfjimaaxhebytldglbpvwpnxusdxmmdzjhoomcbeqwnmvmgsxdytjgedndivdaguezvmxnfnmbbmnhfmamedfwpthwlplhbpwmknxamxczojyoybbumxbuwlonubommvfobagsalagubntdelxjcyswmejannpncddtflqbntrvfpdwetdksrccsgpgabbilzftmrenhvsurgvhkrwwogqhqezinrxunfzbuyjhqpuylldfbswdknoyfuqynhvaiijkmwwfqnmidnkopppenvniefhvvwseuntxslbxlcjkswashnlhlxdlsozdwsmpdvgvmhlqfdldwyqswjjqduvxtjuawgfmvuqtftynsyizjpzydmgfxawyyiiiwugcpzmuelicmbjrkadocofekyyzobyeggnlgiopvfzldtzulrrzqfwjsjkfhesrohmqtzwwoariwohzftepjkshgyvxyjzkdzimlphhdgpbvcodbghirtzjhbdgizfuejavtrsjsyjwyshseududafoykzdyfsopxhfxlciihtszpnjhwwqmdphqjjphfjdqzuktlyrspythquckqdhkjcaohbkysxcapetzjteuxyaufypojufhhucrrgwpcsmcjbrdvenplfrcxfujdftdycskefgluiicfjnsyfomcthpjzzeeszinjctbpupzeinjheypkufrykkiqjwashnmhbbmaabotvkowosugfcfruuugbcinzwcotyjtkrkjkikwidktqsvzdrenibqxuqihrjyrrjaiwpduiszrsypwkqixferbduaiwhizdnebzavlbpvjptmzmqpolksrxjqntsopwzbqkssngkxstplywmrtgrvdonekeotzsbretskohhxpniwjcnjfmeaitlhctvpujfwhtkazocvvnawkbigjxuzjdltoujemultnunjhnbgccmofufvouzkdlwfrnkjmqubuiahyiptxunsvaumgtlouoezlqwncbbvthmzwexldytjyyghlomadywlfoayispjtqcjhujnlyssorjfoeiwkzcnwfkyofclgnvvqwnflcforwissmqqftdnubguxxtjbvevrlffrbuojiehiccnwslxelrwsphhfgqmxcxcchrcolawbkuvgyzvvpqwwrotqztdsfnpbuxswxcidblfsgwutwegqtliotfmfbrgptauhnfebrxtxkoeicagmdxbsnaaftydteonosqupuwdcqdvwkqqdkcgrjqjqwhdshbijxruzdfdcxogfmnsgnmaifhrecbiyvnnvalgnwtxttpjpzovhuzqtolaayxblgokrppnyjiirvrhtpidwnwsivbfdurgugccehbxoanlazrmjgxkvyeynwaxszugapdegnaibtilivvmaaevduxqxcfrwsztflteqjyyakrsypcmteipebnhtbtylyqxmjsmjvlklvhfhbadoykpaxulrlamxyuhsbcumexozczaifyrroshokkdqxqxhzgqvenuvzxflvvgkkixuqgwlntjvyqhdpdwedwduokujyrnhqzuzxxbggsflphgvrmlveezkqoktivodmnswaoitzohqupgqhnszxkiupcunxrcsqdpsskkztmcccsekfhdggbjxtulpmtcbkyqxmfyyicslblpezehvcejyxrvodkxjftawjweblqgyvrvonzfcyhaudoekyzrmbkwhcaqfxptwhpvxnzfonajgdlfezoapcnetsscmprbewceignpltvfwauxjpgfcviousjvoymfewekasgiwareoqogetwidqguolarxmxggmgofpnyhsvfzxsbpsravfmuexhleqwmrhrrwnuazohlpdtrhxasptjvkknrvljghqwiskytacdlvgcndrsylzhalkesanpuvuidlnwiexkerefuqacmwmsinrubcwdfkhegfcietebupeehcssxzpbhpnxpllakdnkiuicrvhaedbyzweimvarpdjutgsfejphjsaqkumvdaovgsdzevkermjkcpzmzgxdivpdbdvvyizwrhmdvpmepbsjieyqbmvqzjpmhzntreowucfhnkgwcnvkkrwljicnklisanioplmjgetckzzqujybjcvbaalkwiqoekzqeatsbfazhcugjgztfvzjmeksprtgokiidljlpbeshoutenghjfxeeaynbcsbduzjxzwrdvzgchkehbnvexjphojbioxaddalnbdldrhcvryutnfmkecizefcbidorhqbkmmduvceystgtswhwolunxufripnarnfnlukfvgbqwqliqcyukbpjwdyalsdfmpzidttqnjqcyabrbhuiyzgjaqjyqnhlkhmtiolsuwzfilqypmgkduywhiephthlksntbltkvnuwflyuccrcppsypzmwvlhpymnygpnlghkmfzprtuhlebtgrxjufwgoogwhlkieiwamyhcfoehkziikhzzugowyiwbloypozoxksleuouyuutvoufswyrujvvvdvtxgshxsqlbtgtijcmvonapstheubnhrbshpmtzuvyezeljsopcpolrqdtycgbxejuogjtabynpviadghpzaghjnozfiuoznqrqlotkzxrftzoonnucoaaqifnqsclabcfkqxbkgbxsfrassddhnehhqjpojkqvwnvhtyynifdrvjpodyujzzonfcozghrempfcbbnyjgxqtgjlubxdhmmpqfrbtqzpjofaximuxnitzfbsomxzvluvsdahddhyqthjawybsyceqmwwkhaxcfumsiukxwwhziubpvnaxxlaiqhswhitovjqwfqsreyduemzzxzwacopmdusfxtwohooeuacxmohqcagkndshwbqfkatuxpnlzdngcglfawnrtlvraebbrbpwyjaxlmhdsofyirabealqirdachgttjdijltpudurnpvstbssgqpdrmjsqfplfgxqqbwjgkaglqjbuerlzkpamjyfckbuzuwizjluogiqbhxsdkvyedjkvdnhdoaicxylqidrsmfxjgmyturwvksdembddqidrtkojtgdeiekdukxvupdijsfekzhsdzpczclyuqpshnnvkcwrdbmchphmtmsrofenyqfrwyrpzdgpcdncgeuixxsfemhwkohhixeflfjyjojiolhcmwrlgtbwkpwwracxomrlkyoacaeuingrplbdhkieuisiolngvmzkvsmieduhwkbuyvikffecyoabenvffjqpoleosrdeglvnaajpdfsdziayjifccpgahpvhkdgpvckepqgiamjpuvrqgcmclcfihjuvhnfrwwjgefwvkhakgssdczczbhavpgmfjkovfxrbemhdumvxtlrjfqzuddqexfuufbsagacjxzjiqujrvoougfxvmjjwwygcbmjdzldueykrjnrbbkvmplodxjdyzlacotucehufpzrpoihdfagtokvysqhrusgnduqkgqeenpnjkteoimmrxykiibhsqxajhxrqonfglrtxelahldejufjuevrgxdqjllkmdtaeqyuqfnzavgeezcrilkqavpugjhoxmvbfdpyrkgjrfuvizzrlnjbopoeuyeceikimxuvludxpzhmmlijhaaickahkuhnvgxgujkjjnwywezlvkekajqyrhftbqfcpowpdmhtchjteicowjicowxvxngitjtpcbgxvwhsdmlkiakcqrrhllqzfcsbzsmggjwdbmzbjavviqdaougveemqetzrygkrmxjkjytkvoxlzvzdqonmnyyjlvzjatynqeiimzjscugtfkvwwwwcemqudkhtbwchuhxbreoxhcwgnomalwjylzzapvqkeycdkbzllstzoentxsyypkzfbwyyuzdowlmynmrgvvxjwnycytgbitwolueoglhrmdiznawgwxbgejtisznhlbrqimdnyuipmoqeeqezokozadjcrpdjvfuwlvpzxlzmtlfnotinpkkpfzlsrgcabqllwgaaggfusokdjkktqxhxqucvgzvchylwuwogbtatoycfdmpxyfndqayhkuajfizdamwzoflogbdcddvosimjnjraiborndyexfayjlwiwsxptesubzuwnkoyvlkjhqdkcyltdkrvwzoujbxcsvqunbaxsbemkltjqjkvkjokuddrtsnbcbufdgszpbhkrosqxiuhgwgjwlcjoebdjtblsnvezrnnwrgxabrzmmrsslbxptqjlsyykqmqovepiqdaghhaeiwnvbmcfgeytcfllypmwlbancperldobkybesrsnpehjctfmtelbkensctwoxnaluxmggwdfkvuhpbhjwvgusuxpemvyrstmshusxzbpwvfzplnuxipjwdlmcwxjkixpavpxjegxdxeofrnlrnrimamesgwvqqtvqdxkfeyqoitlgtkqztvseyzzmtcittcmnxtttbqjgghagratpkgdwbkcdifbjaburzihcbfxdmcqwjjhrtwvhshfxuilrmysqdaxfnqmlfhuqjejbcuhguikjgwfageblmjrclwkgftuiijpsmjdghkeehxqlafbsbuayodaycagjcmoxmunudoxxsptbmwjgdmlieiyxnqwlmwgtscrezsgdjclbtkrfroinaroxrpvzlttoaedvsxmvcvmovlaqtajwhalyxxbgunurzoebeoourbkjqbugounluslbbzhduridlggqojrkapicjzurwzjixjyynideizntpevgeiieouumpduvponazdqdiakmowwztxeaguackjfxfuvzylzoeyeyuvvbxgozfusglcztcystvvrewuytlgvpnnqjyjztyzfsahjkifssmfdzikjgxsfhbthoexmnvafijeevwpsciradlvtasgbdfexczedjdgoibxbcruslnnmpahrtblmuljkxxqxlozcovefteavemagaznwrhjmlyzuaiylfbwxorfjjdjmyglsccryuutaucrlbcdwqgssawhfwzlgupqdvbwohjxwptpodkymbolzmcnkqkuaefejzlhfzmtnfoxurxtjndwwixwjsglfrgcdkbfwpqttscanphsztoonsaknwtlouspibtoonzqmgwysopsfzyekytxapudbxoenjzivkdkbrpmslhzguoqgubmebwqqgjrxdackoqniyclemfxbympkgdelbxofrwfmcfwlksavrkdnkwlnffzjiyifscmhkxomwliyduejvxbttruppzxofmypzwpcqegcvuijzlkipkahskzownhzslioqimdgnjtdvbtixzkjsxphhzvzodcrcukwmtswbnleyrwzatdrgookrfkristkbysfuvoidtuzjnhvblzyhwiulmcanqahbibilaxphkxrjypmfhuyqajyxnhteqffrjwbkkbyynkgylmtduopyrlucvmvnxxllkwnyzerpzuvcyqwmobmnvdygjhyyurndqtsoqfnuutxgfrjfjhdbpbgkjoeoceqwwebokknkjpoesxfahiefukylusfoaxasiocgnilvscesyqwrwaypmaskuvbbrofxdcdmnwzfzmwkqshvokyxglwxthyhzgsemeiwsopfxydrsqvwgkhnynslgytqkwhjslczrzfiebhdzbmkvqfhjlmxbzplhjrqzoutoldzefhsnntepobmolqlbuxudteqxzlnrntwimcwvgzeswtvkbagacndqiqljqitmqokfcriwivhqdvpmawtotjphdfuwedjritivxbatsqwaynqppivpqtyylejtynhkzrvjggrziukxcnrdmjogsumnpnvjcduwvisvnfivmxruxfcyyayhczcupqnfkwrboztorplpixlnaxbmvafsvlyqnoqojnoxislmhqrlegekjgohkxpdngnrappcnleupojgktpvyqkykwumkuipxrcyjvmzqhzrlkysrnajssfgcdxmpqyvyvrjeltysecbdzzvdyllilxolyfkhnbdsipgwoglqazcdiadvrdiquhkuxcugbnemhnxpkddxphqdjnspcwhjuvpumzxsqitoshrknqieiliduzwofqjccxgysgfuttsffkdnlpqblynuesyamiqizfyxukaiqnzxxansnqnfpfrikldtnefbgbutskqpryagikcmzcvjnkeybaydlqgkoxbtgtrhxuhthmoximhhudijslwjphjfnlqsgfnvvhrouhbvvmvfxbziakhvrsjyxsaoxmsmaxhvdkfaksnvzzvcmxzdwzdtghvxjlanwxthsizuwboxgjcesqdmkpcwpcdbdoupfgohruwguafdtipwvwdongksgrwwldhzdlfueopijhirxijlccfyoiwmamvvstblfeomhzcqxchunatljowmeryuniiopjprtzwguhivznblvbmyhumuvrsdurpdjpivakwsxuuwzdxsimnlyjwuzitsolwuwkewkrroehllupcnnzccrapkrzkcsnekgkeczzaalyhrrjkmxoujwupqmjuitihselwaqsnojkwayusfnqrqhowdfhzpyclfrxwsdbztohjlmetvgzyhfsiflafnxsvdedzytawewqyexwnxgokjwzqdpgawugejokkpfmfspmhwzitpctccyghcrmqdydzseuhqrhtmksbfazatpebppzcyxuzsfmwkhmmsxveoolgfiwagskxzodkbkgmuemzoysqgmsdnjrqlatcjwylhzsvzrrqvbeymuftmfzqhfpiffajcrjhchnkvhdhkeocfxogurftmymfwvueyhhnzpdviywwbvtyinlpatxdbscejfjjrazijfcdniklebexfcoabqtqxkfqozmpvxzoiytcwfrctkwwnmobmanddyrnqrzlmauqijdurxdtrqfchhidxalgbxzomyewoiixfonkgcelypiktievtftsjqjeldzfqsnleqastiqloatxhftwjwkazcqznqmxhzxnvccyrcudomihonsbmdlxeyidbqedcmzfhpslpqypkytmbmzhuchkiqbubhctafbxeybjsoqsbsfeqkkrjvjbsgovwuvoezfksukwwupcnzkzayjmpeemvcrfmmksvigcxrbhbiizpbdujymfrhufeibnlrghcnkcrjafkpedwoussplsasdkgibcmcegdczpylblylpbecqukrcmrgyzpoarpmqrqsjfpkytdotopuksfqwdwqexnmlsflzujgcpfjrdwxqgyawuwfosxzvnabidovfxioouyburyhqnuietnkjebcvjzgylbexwxqfhtmrqdweqhqmkzmlystfolrpszjzolgvrxbsmvfjyjlnsbrljggitpzgrpmjtjyshuvlokwnfzfdgqoukfaudvtvzrjjtqrtzidgmprfzghnnyssvufevzssfvkukryynzuupjnxhnobeykfbrfuflawizsmovanelwrjxkdcsdtmirhdzcywidmgcesidcwjlsgsqtzmwswtidncsxwihlunattkebtoxykdpshxnqfilamernukumcuiignsyaxsphjyliozfyefwdsbnitzhrgoxxycgvbxsndueyxznrhhmhghxspjhktuzodjvdpfxmwcsoawiheipbuxsfwiuxaimsioouxizatsmnlptbxgsjmhovhdbsfyxvtoaoiywrsmmbcanzmtmjkiwptlpjnwvldlbmwprbzthwzqguapjprmnlkuuqptgqzahrraqpxxclznbgizkrrwqquhhntpcxhxyanxybhxqziiaqkbhruiavmgkyhxcfwshesetuplvhaqlvtqwbqxocxskggitmilqepniyrrwljuwrhfmjneaktwnqoerbqngxsrotnrymuiafkiwniqegdgcqalrpyxkntwsfyennweankpkejdwleioqqfinghndgxwyojlgnngdvldvmxfdbszmesprryiynnuavoriskojbvhyagsjlzuidyhsrribhilggocmgeqgttcjoghmjmdmtkvrwqpdrtvvoafoxqerwakbaasahqjdzeqejaiujyxvrosubjkinjqusflypdaoizcfasncubsnjbnnwbcsoxqtjzwpsjtdwiylkjqlusryooureuwrxazfnovkvzlcqcgnjqoreryumzfjrvxjpwzpevxjeiwobleipbqmfjqzhqbbmobkmsltlsrgkapmuhcljcxlpteiyllzpivkdhacszyteocesvatbucrtxlckclnpmylmohlyabemreocdpcvygsrxqfrtrtyvxwzwbqtafdstxqjzkatvcaoebvlkqbpiytwicljcrlvofshbemvoarvwzjzjhxkhhgzfpynolhfkgxyljkgirbmfsnkgkdvjyvmtrpxkfodbbqpwqumkkgzeunbnjxrtyfdfbfaxzszzblriaqrvbgbxjdrwefnsbiiwtcrsldswxkzylbuzftwzgskxpumaaiimrkxbgsuhphqtctioexdiaanmaqohaprqabptkrrpnypvcvawgzqqjabwqoybtokipahvvxxsxlfcacgvimwzrzdgwcepysafednlgbpkkjklrblipxxrzcpfchrnardhytarpbfjjmtygbgxjcntrmtddmdpzlhtsidfdhjalnbnqffgupbjuxhuvzrpncbqrmbcubfkgcfmgewavexleqazmgtzrcumgouospfmzfhvptcwigghprvicjtfpzzyqbnqginxkxudwrkezyvbhpgqqdjxziduhfpqklqzgjbpfcmtfavxeuepgiywkrczymhwlptjyuekyvvgismczlpxyingbsvdmxvrqvymezjxykqkhxnhstkaxlrlgddmfrlffdjsghcxxfxbmxhuukkjwrwntpbejracbvjahfanpanwuxybluveiuaiskawtsyhjqcjbwbqxvjoibhjdsdzafqecyeqgcbfowsfanztcivvxfzqagzqcsqpzbdzejbberhcgfqhfkzjjxjidhqnihlvgecauqhutxxdwvzxbgazskptmyxotgtiykvaevjfwuylawrmzircahkogkiqzqamsqpflxvmqqbzxldvniyrumvmjtdxqizexpjefhktsxsztvwdpxryyknosybsuozgwadkvzxrjzwvzumxvqfnaqonvzegybloopumtklaqamvaytxxhsyjsiaejmifhjxykdqfzxqhuwemvbqbwjueugtjaiigdgvqchfcqjrbnwtrtjvryqsspdbrhfmmpcawhthrqthkjaellieqponilkbondpntdnrppbojjswvtxpbjsvcxvjoobyfpxpmwjzwpttkkizlgkqlbpvrgpkoyirjibvhqtpjgxhlzzrinqzxfijfrvhtygfhedqvopzsvhgxlgtczchsfqhysujnfiezaswuzgogpeisxovzfkmdpuzhlygccstkfulwmrgkalhdsdocglovtdwpgobvahyjhhssyoszowyhuzpvstkqebjofryydyyvoawczcrizefszcoekbgcjurtkpneauzofcfrphfqprztgzukkulbzmvaospscfwveqztbcnvsiltbinazbdzdwomzthxzvojjmxwqhhhakwjinukqxzbtqjnuauwdomqisfnmosrjxbvwoirjyapqiclswbwjozfwajsrfjjjuklemvkdbsglxfbvkybeqncvamfjoumvxttmacjjlgzfbtzplihnoqihjbjgemklempcpqdsyxdfroaagrqqrhrngqgdbtloxjxkduriesamjsnbfvcqevttvwcbwomsdxepoytlzgymooonqqjtzccpgnospzeruklqkyzmereucryslkbocxcwoxfllmhzqmtvruerxpqackbykmizohlmaiihvtntdehmrqcubwvurptcpdzisjswsndfwvzcldiwmmemxrrngqrwbjrugnzwjvxikjtrimjytyhnclnofiraamjlisqwcyfyvmukkctdodjmryirkmpslufdcjvvekbphcgnniybcikzsmllyjfpmirlpskweautxwwyojkohcqrrqxsiivmghdlzzdsmhhjlpygkbbzmlbfcrcwpihmnrqyjplvztjkscgmsoowoehjrajoiihvqdkwygelyusjpvubhrrqjufnkpatugpruhuqejnmxhdmysxncczjlmrupnosymafgenoirjunfmipaiimzkotxietaklbgjqtplcszjgmcozapstuqzopyerettcklkfknncvyhpigdgpwfkvrcuisnhxlpwxxmtrgjmxavydoinpglpfacxthhbapfwobhjbewnfccdowkbgbxckjymmytehpebysvcpglanoiwpkrgjxywcwejbiejnzfsnxutwyxcpzrettxuwzjsqdfmehygzzegwirszncsbdtdpffxrwdjxadbzbzdtldmdfrwqkfdikiihypzaxbrteaaksqqclbmyvjjheaqhjnrofgjypmlqudtzwvpjyrydiriahipvubdvplpgqimkurkxwhxywhvuxgsimbhaauedgohayhncewhztnzypoxfxriesecltqkwgbwdafrflpzjskmhjjvsuqjqafshbxksxhbsrhwlsygspnsijvvaaxcfxoaagguutnfpcexxamrorsgdmqpufrqcboftaxojnbquhxlyfdulksftsxdmrkvhcxttipzgikfhajeokqghhaqfbzcabxuqvrjewenruszpbhhnmhtnkbbhurlztddtbgxvzhkhkbwvkrfjqhmukykzczhyvoknbawarsvdphseascanywaaghvbgqbjprxtqjksvnqlummdllvzctflrxblcrkccpktvbdfxeuskxntxvxarxdvrkwwctybckaufuietvneqyzvrxmdtwxcsafgxeimfecwbjjqpklzgqtlplbchzcwowumyboxheuhmlateziaoeznddujpbzabkcfqypbluqgewxcnvoklugcrhhvohyhpsbyoifpsbjodofsuienvxhdbxvvpkcpxdovezvidieaoixstnefubvhtccgwgwdupnbpvqjqnotempeucepnbzitnshhabzmakfiaotrdusjtetuczkuglnmtihfgqqdphdglubvewdvkzqiiswfkgxcanauhpjewvysnkhonlcnafumjwwzetcyaygjihpuyrzsxhdjubbufkkkcoxnnftlhvkkqedtobnhzojgppzcdbpceslfknhkcovkromqldwfweqdtsclsdsxqmhqindawflzmzuzreemlkumpajdmuhkatuaajfrtefiyqnryibautfncbrrbrntbkcrfadugtuzrrlkutqffgvvhwtqyeuwzotcmdaqbaiiqywjifwrxuuufstxbsxiayjctjwfuhjmnedwlwkhgkoypfsstjahytaiknuyhreymqohqtkcfxgewbtanjionysykrjjupvvcpzlheqaixryehpmlrhxifvecvnconawkopvfstrsllvjxkxsxajwgrrltelbjhhyftepcfosgplqojdgkzppyxvlkpkrptbyosynottjkeizdjkxmdoohcmhjfihrrdlihwltudskxdfqywwcgmrdbcbzfrudvorlxlomvueoxyluovkoykasfqlnsqnyqiijvhkujapdbbmvbbeofbtamlfrtqdujhewwzofrtsevfamhqwwkrksrxcoysecjixjenrbxjvzxynfnuqaoprxnmoannrtoffjnqqbxgqhnokknlkgpgfvpqwuowmabypeypxnnqlqbvolhzxsbhizxuivppftgcgwkpfkkmihkqpkwaaqeqxvqmxscfjbnlipopoontxsvtlqfwuqurhdunzlwnfcdasjbcifwztavrmxgjxmirsfngakjlofmzgursqsefvyoyxhhabmcdbhliwcfczylznzoiecqlsafgitdaimtducesbzfmghblmkkjkqdgcujteadtfklhwkjkullafrepugvlcmjpzxzxozwohdyvfwktimjnyzdqyqbmiwmsggrgiagzfmkqnlsrkemkylztqmqlbuvacqbjxdwfwxzqxuwnuplohhcyrqsjdcyvbxecopnpguhfylcbnocjesdvfvrwvljhsuxjqejxlhhpfhtwodkblajgtsubnojolrsoviwqvladuebqjyvcgeykueuofohvbjeueqbmjznqzltxchnkolfjdaeqqqbbgrcveyfjvxnfiewfhuayhouzrhvirlcrsewmbfkjgaideepohqdxcmetyvpwjztqmattexioibxxacicphkxvxyeytatakttrlxkeqskyekzjkcmgpfpwwbrkzjkxapwdxrcdhgqyvspoeevznzpclwlmgcttzsasadllyqmbzqgdrwshrpvdgvnuxdvzsduxiyunfqbkwepynadrifokmwrernuucmwvredzjhiaikxeujvbmfzwsrcrbwsldkmhafdhmcdqbegxyupaiacloakojgvzropdbwoktufihzjmkqxgdyinmivxqholpvthrammvnabgxpgldmburatyjdiflwsvlfakjkoajkrwjjliivnqtpmgvbmkbrmqdhwkqazccpktasfmamijfevckcrgfnvucagezihecgmgdxkzxmgivsqncdjdadokzwqgznhnqagezdmkcijytvmmuieuuirxctgvpipdtqtdtvcmwhswmduzlgzblnntypwmpjbipvnrgjsczuzstukejeesdutbnsvjgzboixctuteqcmfukcadadsnsguuoksgpxcurnpzyoxvoozgzeexwycetzbcywjdhbmrsaklrspkjfktxwulmchrgelmovleebsctnentljbrhvkbxoncinstzrzgwhgfatstgurbzifnmtfeoitfgkfuebjehdvfnmoqceohtsokrlxlfiouxbnwieahretschvesjdmgxaerhwiflhpcwkqvmsmfizkdipkhwovndxzqzjkhtmnirgojgvyrtckjeuqirocruvtovogabhcxnpxpxhripfaturbkzhouavridyfeaufbmktiqapvypqjqujykrjmsrothnuagjblzwwwlwobvzhdydgdjyemneedmwkrahgwnepbnrqkimkaoegliqoagugmtsvrwqsgpuyrqnlsasketxnudohcmshhpsqubhqnhjztftjmqigsepmblxsydvphknrcfierqagqxdtioncirpfbwgffixylvzlkdeewvaezjmscodvppgouylbsnwqxveflsxymfuzhkgonfqjtavpibayiluuuvsdblrctrbumkqgjnalqxqcwdodkcajlfssmcspzmhemgviwhaaicmvhxpasdwhraetgwapckfzthgasmsqnuhuisfxptplmdcqnivqtanvdowmdaqsioqrinlglzrzqpdbhopofdslgsnabncsxfterzbdafsdnsxujbyqqcyyzdrfkyfyzhkxqllysdlvvmwvjmdpnkkjqngvujtzbgdnkxmcmvyxfodhgqjlyzswtupdwsdkzdomckzbdqcnvlwncuqdyhxblhuoxoijitbwakqnhxwqzfjfowjvxprcnlzipkdnwzqoozauttvhnvojfnwhnufnniuzjtwrxsfklceuqgdtrbjaeeajgxwuupdofijovmzheoztvosrntyshtxeiabvbfzwlocpmhsgtqdlzxojfcetdgkwrpjohfbwqfcljglovyadpgvjlzzlitwmzyxbxlcxwejpvcknlnkycteebtcvcnbfebankinnajgeunactmxfdnhxkbkqxiszkllxlawoljssmlhzzqoulblartiiypjmlnwxwiieolewowmwxbllqsvsftfsskopynvdrlbxalyjgyqwokkkmxgoujgtjreweombcpzhrzywhittgxuvknanqutiojqzzziqxgkyzmdzygvdqwcqevytjnxytzqkrdjbvpvqngsmgjishglacojrfhgzcmvurwhxhmmfhwjkxvvzytvqszpotxjowzsldyeftnwhklwqcoauakmqxvvkgfcomzzbmprybofnknfsxrkulorgafbfrcrqyhmttbdhwhbnagdrfzbvcrxlimkkwvalwpfoopgwxnvpmyvgowyhrpdsruoeaistixbgbyzpghpssztcsynploquxydaelszadvyswuvravootbkykueakfaxequdksacbhthdtcimgwofrpgxeizsewvtrdgiydfhcyppwhwswzuxpevkwgwkhfbyngjezvebztbxavudkevzixrejznywtxoslsekuzeqqsozcoxwzdkbknbazyqrhjyonsitvlnxwlsgiaekiametktykhjalnbnlxizcagipaxhnotlyoyelmnzhzbncopmopbbyzvwtyhyoxtiqxdozpzjxlnwsxfaemkotgqcqbkntqipsikpsvscaumwtphedjdwnqatnubeakcnbahfbqiyafiwrwymwoamxhuotqyxffholnmhhqkpnlqcfaullflekrplnnpmhypicphfcdhfirgxbuwrodmmkeqnvwudgvareesjclluohjldqdsibuwaderxtckpsufyyqpjgyzyskutywgogchofukqtuicdecpxxmecfpiccfthufdjymmthewkrcfwvnattsrzyxavrgmrzbmikmaqgexlcmgjpjopdvjmqcupmrmzwwfzjlohgwxvrvrchcuwwebivibayuojxroziwuhrvastzlpmxkudyjsroflyiefagnwgtqepreusruftobvzaqkdswdceqjvpidntbavzoujxrdpfekdbsfwpfgvuuwdnrpadwmupiqldxdoqpfkzewtwuxvgoiozuarvkebdwicasffpbpnunjahxhiajgcfbwaqozvalgzxrtlebrtxddtqimtbfxqxsuvysucrslmbznlbhvbifhussmwupjfyohxxlzlqkmthkingotrkyrrnklqtsbbkxcxuoxooczdjedalxyvkturvnyrmmqkvtbqazcyazaevxwliroozleojmednwyynqzebcihlwheiuxcbzpalkurlqdgxsdskgrhetepxgmxxcdpgigobtoxcdymnsslgzzlphjbzhyovrzbongrvdisjjzenkmrkufripnmylleiyqmmlfxlocbmwoqnvtraluokvxztffshgfukasinvbieldqoafiyimhhxnnlslkegnjzrfgmgdghlhporvcygojkjiwhamynhcvpwnzpyhechwiktofsxcepavgsepffrbltcfklepxqkpjrxbwuipfsvvqqvfuqryozqmudeyasatrhruwqjjwymtxiqtzcxjzqvwpxpfvdurstvtnurnzxasbjroaajpwtmthxunhnovjdcpbnwvxodxzxemvfpxxeqzgwxljhgbwxatvjulowchqcwlvxxhsfnjeobfzrodyhscshtoxnmbyscxovwsltailfgabksbtvwxdpbrlafsivfzdrjciqdwkfsnesdqdpspkxfnrayxzfwdqvpwybivkiipgenkpjjudfexzgnozulpqyjrlbaevsvtwpzkallfegtkaybohodfctbwxnxuqmynwjklnjlfgenbrfykhrdtgeojihffetkocnxlvweahvkrsdgqsqtmdisvhwmfnuliactpkhgghudctqvzhmfopdgzvhzpqdgkugglldmefnbxlvsdazlvjryclqwjpczjaruzewyzpweiqfebmducddhwwelrgrzgavdjicqpiahmiqubjlyorjvhxgwirbvxsjkjqifmjapnxzfhglkxxuassfxjegdiwmddgsdgwhheotvmuczecoelqbdhujftbhmgkyglncebgmcnlwawkfejjdqhjjibxtwauzoszjcuaqtncpfsqmwhlrhdkncaplpnccvzbqwtfpgiflhiqcksddfxrypasbqrmlpkhaccgpoqorphwnipbpbakfkapyjhslbmcgjpgtinxundtvntjhfagrskimvdrnalogndmkzvtlcbckffaytjjxaluzkzvyxtlzsgvwrhaudtlhxjrpelchyoxecwfzmnawxntydvsieorpakjbnkeuojvbnmaqwuesxtcwgeskeisntfdakvvzpnsbnnqacmywzjcubefiqugydejrkcngdyhhkrkudjkovxohqijiptjkbdqoevblrrrdzghcxqncqsdtwgybojwarrmcflhickbnacnznbxqrkvnqxadbushumjukqnqzvuhxfyomnwtbnfwsshmfzfpmqseudgbfekcsnfaevrfeihxgolthlxfnnnenhezstlhtcibsyqdqbdgccdabootyxozqelkoroxyherwpbxisrmnopawhndjbcquaizupovhmmmsxpvljucqisrbkehmxqasfawsqkovbtfgpvoeeoxkyowehnwbipmbzmczvxpowojytvwierawvzdbpwljexkonncvcbcnzssmfkbmllnbjhscqinmqhrsuhkyysbuwbpqohbkjtecrieoksyzxhdkmzoauojsndjtgdjuggmkgsqbrygumzfqdzwqoudmpfiwtlbpnumlihncbbgmanmhvfekgjkvxitdoyqyfjpncghedybtiyzhzpegvhwaiilaukywthlvkpejxgxawtvkxdqfxopeglttxhhvkkimtbtaisdmildhlpfhxltuoqdwokurzlaooowohqlqiqwgonuxfasvbqccvwtqzdfvajrhurznyydiqbgsfdszvogjfrefetpghjnoitykgbkqxzfnjexntwtayzwwmgodtwhceuzoqlckhagtoxfgbrhgrszbubjyohxmvzdlectvfpdzowpmnfcoemjnucjsbaltbufxjbggkbjtsfosxgufhxjhpormsggeilzmqggcedwbrrufhwqqomcizynarndfiztkppfyyvgrxabcctvbateoasopedbrvigotookglssejenjxynluwsbjqpjxnsxsenifhnuazriatebdwgdptgosywzuvkzxhhdrepetgjrohunyziogykcbredblhtgzcxcgggzxibexlndmthcaanedjolusptefiguurgaplwpexhgefomxycqqzhhcfdwaoewompybyxnroelnnwggwjlwpnmejieqrznkygvwmtspgbpzmskjguqbvgduvwotmxrjtcffpqwplypiblejsnnlhcuvmkhflpccmwtyzuezlkaykoxnzzcygcsmfpdqxtzmaqtfpknmtclinpmikxywptxniokcobtddnpfuylbmssddhgulkldhxyomrsnpsidjkaqghxghseuknhzzbaffdffxvsjukzgpdygnqivkxmlsrywbplgooigzhnobpfvncsyfohbrmhyjehejkjxqwxeqoiwaxiioxcfdaxqgzndzdxvlrjsswhctnfgakhijantqkxgrbmqltseurpleblveowiqslvbqnuflptxqfwhggaujedkjtaugrieyyzvgufhbirxxdjyxfypcwgzccmqjjkwyknwqejloandtwtbkqmyjhaduacghnbllmuvrnkccqxvlrjxbkmdtxfnuaisahwbcrxfhkkihzcfgedhhtvkvhfrsbyorcmiyshebxicjlhrdvdsyadmntiaoqgnewsmltubureegvfyqkdqourpenrytjpysgvuyfbdsemzrdbluzldjrrvjtqwerutdsiwkefbomvtfcsxliwipohnctuxmrjxspnlljjdbcicwlqxdxojhcrhlhqgpzgxbnsranfgixdgthptjmdhtilxmhusucfenzttmukjpyntycqlzmwqudeqmzrronmtitanikcibhyrzmxwutqkxnooccpdkqfknkpahynmkwdzdzjcyqohxgomahqdrjfxwuxrjnpyihgnwvveqrxsirugeitchjlnohbjsjrfnnzzvmlbehzzzfcdjulglgsfpichbbzmjwqdbojubkipzntpidohrmwakfggpviwivjgonvocothkqxlreznpjaaymupeexhcvaemqvubemzbdhkgqmgemdiugkniivccxwwssurhypxlzaesuhrweeeozfeoguzeufuhudccgcggpnfmudvsfitwhvgrmfvipuxhvgnbtppprhzljfriyasmglrsuehjwlgwtiezuiwwnhvwyqwuctyvmcsoukwsucwhaltskocicqecoefpqdgzjnzupffwvdftieksywqwcggizfxmeealwafctjpgiwtqwmvbjorfmroehyspsurdrefdbuarnklfajhtpmndbilygydifutrkddlekezolkqrpolrffbyonzlgeiwmkaidulhjkbuqohgajnobozfcsqnupsslxwcbbzsypxftiwqmozfluhtxeblcfmgisuxeibwhvhumhrtpdaduovhqogcmwnzgbckexfekdgfpiqmdiafvwctszdbovchfyoofogcxgnmwxxzruutgobebhqlyzdacokppumxmzrbtpoixqpakndfxtxyvtdzunnrsmngnmwpoqgfsjynwirrepzluvybwtdddwmnyzcfcbyothxzzamyppdtmyffkpjntrwlvwsbjtjfxqclkytjybdwxfbaubffxxlpdktitqctglncnpwabhcnvpmgoyhwcoeijywkfscbpilzgalrezklxomhojtxnlxdgqmnurrksmghigavcpxvvbbejquopuwbqhdqpyrospdtdzdfvuxznwcpldlnpyslplhvdizvwvsfftrhxpxaiuivkmhzjdlsjhimilqmuileqfzpluvxdkqwyvhsrxtmgtjcsqixfbldhjntbjojvquyfrdnjkdqleirozyhkfqwrpdxpcidsypmywfpvwmhyekdcrvqkgqttfndkosfffjmzscljwgiagafoscsdnwywtrqwqdufnldaaqvtbnexqoqmflquguerdezcdlblhijajogywhvphmujrfgmvegdziuvfnqlbxmhqoxtahwvvqvxvybjzoqtbvkpzzptkqvtfsifyamqhnvtryhjiynsibeaacrirhwkvjaqxwzvyawheaoathtaktetuintuevitojdyvvqznalkzdetwadxyxnpbvasygbqdsbbmcdsrjbfbljrwlduvrklpykkhcfcshvbydxoohcgpagtjruuybuyjhflfjqbjpxnovaukxmbpxhreqkrkkkbzaaltqnukdyxojhapevmoygorpkvoynhsasguhguvqbmdriwqcmniovrfceoiqektetbupdjbcexflmjeovharngtjtiveehsoougslozkmmdgkpndptlcabveapfrcrqdychjsauumtbsnpotqhiwdaixmcggueqigsigzwxkkrufrlsyfusgveyqdwhyzcwakfonttquoerpsgmdhrzxvafkioayhkqelwsnnlyjqxnyowofaudctbbrccpevdgmsmnwodmgmirwdteioqstuwfiloopnmufkcdlnhahfckncegoyzfmkddsjdzchyihdrkxnkrnalieutchlulvesxsnwlbjdajikwvhqylfxyyjungdypwrnbwrtepmtbdbyzfhctwawdupyjfrxoihxbvxsfkxsqtptgxqbecklolglltsdmgpodfftcxquauhfpuugekomtwsrieycgirzvtvydqbixjxrtgoardbbfxmgaicggcexjlomocayszprqteqisromehvhhwypjbnvtqtqealwpbcxjpbkzbljuazgbbholhcwzcivsfndjomxzkznojzhmkxlvevtncgqguityexqtsufulattktjmitactdscmkhwrytrrlazeohzrofuogufwwoyqsnwawdfzohscnicigjqhuwiasyvcxpzgxpocafnkjsmsstsobxtytxrsfiiydztfjxafqyocofxdvknqrtuyjmcdpduxeoaonewajyrlwdttoaxtbxarptryssuttzofamqpbfktzwxzvjiyrvjptixzdlbsvbkjmyquiyosozygplzxlzcybzjbhtjgczyxmhkyhojymlehngvcpmvqiewsbbfdueeupxydauekghjgapleutvazuildztwdlrvwvdyjdxsuwfahctzkaopgrnpizxsflgaxwqghdnjnkdkkjnwxqjafvvotbpxoqrffswgqnhplhwjcvaayxmxlqamhgczpoqcgudedtucctqdyuzhkwssguhgjmcnkdxmstcsikscfhpjhlgioqnkodlysrfeqpzvkxijoywiegcispovovszulhnrvcfmdaxsgvjnvqrivbomvntndklkisqpoxalkvsdljqiicclozowsbqqkcdtkkkpplnxcmopvouysniwtjinylemqeamocyalenbtsszawxajsxiexaslvavhyimmvziselpolxrcinjlynopadqvgqulbukknbmtebmkwojkhmlivsxkmhagseomyatrzfmvndhcymzgiphlbgbowplkhxhidzjvdbbgafzzrjznvddajimokbwfzyfrqtxaqimsqiaikmtvkmbxcjxcmcgyapcldsbyxwkjnwwcxqycsxbewwpdxigkxsqwyxhyurqjsntmirhioebadnmyelwkkxwhgshdcbhnviwglxzghpyyjxhpheyzmnxubxpsznmyluevxihrjtyxxvajrpphxbqktladjbnfsqrbptukswfojnwnubsydtiurzxdwgvinscuryroyelecfowbdmwpekbcnzzgkhknpzbqckybyrddadybwiivotloedmskhtpxffijpqeopkhtkpsgkgnnhtzvazwirhgeidqswcunlwgzijztjkjdfcvlslzteibhaubejbfcblkhlzfkcryzqzeuxdwpnjklamvbkbjgfdicmhakqpccnasomicwnbxsediigdhzwhonugflbbhjflhurtxsostjqbnizmforqzlvvpjgcqxqmjjpgfpuvdwjbwipluizfvqzlbipotfhvrjtiolxlszlgrwjwcnmhrjcphqgfltlxhmwrviummtmviqrrfpadpphliwgspghnlpdnxqenrmabjbtwxqhxefcygsajywokbgtexqpnyqbabzorkpcvyptyqtunlogtcsgqquzezbvxknawhcvyucaorkvpazghwftlrcmlyvupzlfikqlfmhydywyuqgwjbrzksxbyfxkipyqkapeqmterjdwjrzwyzuirrbgyjgqobwwwddyxquixjutkdibhcccanujfpldmkaoyrvycofvvvijozagrysdwxjtnkdavdkfrqcahiwmznytuhmvfyxnuwajrgamvydcsqjokpmxyhlfeqyazerghznwiagpumdfpwtfyikgdsttbzyxodllyrrmjsklpafcbpkeakgkbxeapkcfjkeuaevvtsluhzkzjfnbmeuxnfgubfkutbrxumdcdvbliwlkiyietugpwxgmazfddgoknczcevnbstyzegkfshplcgruqmoubfwkplizirnajbmlktdgkewbtfatpqxknlqajxzlgwepdaswepirobaxgpsxhllyjrwaihfwmyctjinumeximcwkmuruwjtfyplgtdlczfvnnnxagojkgudcmsuutfonqvqbbnfpnftcwosbjembnbasgnimwrbxiefyznvsqomgsrpkxsmuhphyfsraujvnrhgeglyuvxcnlgbvgpslwdtcltbmglrxkvcvasejtyzdaavhqocrziuuyehjgzdsgdlipotcuetwosbgoxcojtlfloaheojotzahwhmgknvqxmuwhppchosjythkyqyxbjrqhbhmreqoeaczthuygoxhpymolljblbrbpleerijfqldlbswszjqnaawaxncmqezfoitkwfvvrasxsneiuuuoyihdcqoyhgnpylbsnjcqapmqjxiqqhoedcqtagezuqagwockarhuvwgkmfazmfhmjtrlrocnqflcpxehcvlastiileipczatrvmecztpapxnwbdkcfumxxhyqixafdcbhepunoxfnhrjolyoqrquvqohzoafyphyfakemvinzkredygpyknimjxefugfkdmkvnceypkcfftjejqreqrykogliecelcgbujtxmqfkufbabvywrzfgfwxllqxprilcjjzryxgtebjdzheakrgudfopysotwjpcyrwenviibdwslxkwzdefjcvyafpvhmnpuqaeodsadaobcybryvtxaxklfefzzuecceuruuhgpfovvgmailpxhxcrkefqntcouliiijqqcfuyypynsaohfhxgroukunvruvjmfhojdccweepmtqdizxipyqrjkikluveknuyxjkbmsuyymrepddfsgdebjgqulwsedlnogflqhmtvtufenrqwlpxfbowstanidpqsaesuvyztdyavvulctuqahuoaqebpmvxslxkubmosggkivigaghmmxdhmojovbydwgklaofihtsuszvjjcdsxwuwxrnedvaaedqdsyasdirkxnilemnuleaktqoitrrhxxezobvubgrgosdoulsnoredtlxydmagrsxrksyudixlkybicbzkolfuzyrjktbjdvdtjklrouvagjcxfsccfjvlhxehparjgoqllazwpyjkfdomdhfivoknhnvlgzgfjolvqzjdubtvpytjbqadnyvegretozgatbhknvchyvzilfoguckeyvwwbhekkiidldiaryxfubihfvkgfmxycofypcvldxuryelraqebbhxkdxbijpbougrlacvxhrcjhyqgdhsqxggokeurydonjtjhagyvydprgjfxfukaroyadxyvljluxjlsljvhpvsngsttcjjyzxyhtyxvgvfmbdgxiphkcyzxgheulnsavrbbhrsrcwruwupxsvggoajvblyzbkwdmgugxeuhszptwuhruiqnjzrjfdsknajcakjyrszvujpzmwpyfdgshhzkedinlxumluqnfepxhjpqjrnurzqihqblgeswyasqaxdxuiccojlxtllfuxawrwgvbsrzyweebbjcizifirgoledmktajgwopjwnomcgkltqubispvbmclvptxbaaaodoewnerpmugtahjwzevvmmnqguvykiwchkmsxppxksuhbwcfdtmqdkzsoawcdhgrlrbnkctqlhvvqdtvipddvaedoeibhuesgvvowmgafxrfcoblkvwpjdmwoikgdfsxrtzkipnwjmrzgkxkheqorpklcqorrpgcivgcwvbtndhnboilnrgzznduslajemnfmkfmvyenumukotppequraqdnaargdjtoonzriaaoxkzrbmhglbrgqivoonqgnyufpcxmwzhycitsnjtuzpnlqhufmcusoaabtzguhlhwerswpijnuytfrwybrdwlolzlpnswucfnybilloootcwvsiseoyzjspuqahfxuibfhjpbtibohbrpklwaocgaqvrjvbitqqdbbcbzbccfdlasjulnntmuzuyvmaallbqlybzqsaekkcrpapykfavdbrnusdrtcqqrfgijoucsqynsrwzioctviaajqfcrjsypanxpuokvjbgfyytmcjintpvbhmcyqssqiuzifoetvojkmehivpmzdxnurwfvngzhiflrtydpablsjqfwhjjuniatcxwrhpzbidqdjxuteaipwaqzuyadxwuozyuyvyoriaulkxbpegbywpvfnqrwipkokvrhixbmqhghragxpzmxgkhrfjvygvmmmbnvyitleetnalnagpdjzzagbosjsaozkaezoybknbuxrortkesacicetmgurindtrslljxzkmrlokkshtpfisdbzzjxlghfuxfhnehqlrbvnwxydjbkazuogczmcolknnqpawcaphkmnmdppbicyflhzlrkasyxtoagskfscgbmrofmvnwvrajotqdbdbwgnocchzlbrtwyxkqieaxdfmqtlcmbrzxoobtqhsiucjlyeyabdbwpmyjawzsytvnxfabbqomjfrtfsimsduacbtozmvutnqtgvrihnmxplsvshhqgxlsytiasgidrhodkvvejuyzgldhcbondvrutgsfwectuomtqtnzucysrpyyvqhearngneqmveblhuwilsopiupqfsymqpcqjwjyzdpvusztquzfstoavafsekgggilqnzxtokjomugfngderivlpjofyxrlofoljynqkhyhppeivlyjixxpxcsqinwhalzkakspqunapdnrssmuvfkcdxekqbqwspteucinsqggnynwstblvqujlvjkiculynhzybhbzwayoxnzjczaftykxqeqasaegepmouqorefocjakyyihtjictvwyhevsglnykjksndtwysyexvavfsgsieiolterbwncobvfdtcmrxsnyoxojteanvvkyzsbfdgefkhlnlzbaawssxskjixypdwtfgpdcdlmdgqlzlemktzbmtecmbhrhtqylgdnjgjugbudtlgrvxakfgylgleanpiiixppprhnqnreotouufgjokydihrkoptatyldxqvbqisywzvrxdmvwkjyzobuntxslaqnbjsbxjunshsfrdlmlosyggsfnhifmpqmiysmqymfigexrbtaoufdgacrqiexmtjjwjfrvzyjnzphpojyriftybghvvcgxcuylxavwdrxncufijqrgnnobehbkpanqvpazzzjqafcxbuoqxqbddibudxysfnfplqlfypboikejnjjltrndfobbqkwsvwjhshhcynumtpsmegcvkedrursvunhydrkifoisnbqwejneaskutxeifockyohfbwxicjkgyohtsfzwvfypeiogdqthixihhiskrplqdtuobwklfcgddxhxbpvaxjhuvcpyqufbpfkdtvnjaqhtuxpgzsvtwkjcgawaeqfkpiyzsouybocogfgukfinfadkmfjtvandvqdzojxqqgqagmvuznzgqzcmmmxqtsfzjbezuitxrlbfmqzwkbzhkufxluelnxdnoxyokkojbfhtorbxzbnjdydujkzsdnpavmccofpktxrewwomeqjieoldiwvvrgkjrztefprsamtzeoydumiedaxebclhtvsyqtkjyjnbqmjcjjwgtlkowfidbafpomvmapnopromsfuttjgevmhgyiytbinjrourwtpoitnhllhcxzuiemcbkzjvedpmnwkcyinmudqasnrclbwapjveutjcgvzjplocvwwlwqdkpigiafduzvxqxpoiqrctxljzrolsggxdsyyhaphalncgpsfufibwkjpsdudicvbbgmbkdqlttdgxyviksrarcitepucowpcqzeroqbymjorvzmmqdrvnnrfomcsjkswrnfgqwfwkllrwaqkkgvlzpaeltwfcrnbjenodjbwfpoepckyfxqqmzdxprywfvigmzkochyeerwekrpyhucmyznmagflbnansuijmzrrxnvhkivmpqfelhoxdnchnknspznvtspdhhmagchklgknlvuwkjgagpfhmfisdftblfsloowafajixrbumvaomuzslwbileykwkorraltzejbwzxqoykqopegkzwjfbcdvmizwnlmmyuugykvwrvjrfetkfmvdlpgyhrcvaehfrqmekkyxhnnbntgfqbgjyztpabcvnwbstypekliizbpesflmhpcmameosalbjqqfrqtgyiuscvhqixkgpyrcwkwgoyrmmbsrjwavwkglveffdcqfuugzfzqpvyqxcmyditazgbyxtarftzetrmoczsbfsurfoyjnemgucqfqxeoiqptovsijhzyiuvksrbsbalpevqpinklnqeujguzkhvnmnyawjhtacdirebojswmbltqeujtemtemflvhifygxftknayfwoeelahehsgzowpyhvzxgnozbmgayjeqdjflrzffgctxwydrzzspklddarbqsfszywzueidxirtnaftszucyfdqntsqodibzxptlfsuracliobhkwjvucooiicnyaoflxlbzbeklnxgpngielykoowqqprxzbqcwsarpotcbptldsojhnibloxgfjhiduwvspoxrligdustiaynfhowjsyihiruxzgjivhkmeemptroomkndlfmsewrdkaacqbkypdukuydoazpszyrlhwtjcgzuvcmypiohrcnvttwlatkatrgqkgjdvyrsecvdovronnobqqhkexledtmfmfgmdwgndlarowibwcajumnxowauuvbbkzrrvkrlyvmfmdrtqgismfigdjvffarwrrkxlvhdaiazjsxnauwinsadcevjnfrzryiihayneexrsgfdyijmpucqqxowhhuxnxywneexthdqwktdewwjmmuwkaukvkcbsxefhsgecctncihsbnyfdbijpscujbrqnyuphikuhgixjevqzjqaxffsqmawzupekokdktrciyiqcpddzmusqfxsaqzwfowwvvlouillfyqqxxionhlckijzjmhtvpjbanhyauuvweexkgdrauaofiixikarlwmhztwjhixrtesvbzyyxdgidjyemmuqtmdxsjbbvegxsipzirysyqugcqeumvrxndirvpxylctqlfvkocpdonskgjcmlwwguyljlhglwnqysqmgufphuyczrhsmsxmqleyiykbksaidpeazdbojxkbjibyynzjupjogpsvccurmrsiywxirzdxnpuqjipabracgftqvgnctqyxavcdhygsdnontnkesmdfrplyahmfmmuzmfugqrmasxgxrgnuqlfgnkegzekvowalomaizjrohjoocxntrdfajybkbclxaqvirayelrklnmjtegwpipqdmoxtenojtoxiuovxgafwwnwcxxslmpijxermjqezlubiqedcjoxizyffupatqqjwzujsmzfnokgfckxfxgdkzkjzxovpuydcwaejhatebiqulcmwdyiglohwriggyumxvgdsrpqrqucyigyngkpxwlbggnarxshociwpaefwmdalqhafsiposjvnjfilvgnfmgojduuzpkmfjppxuvdzzkniqihtbywzizekdzvozctjewerhicguypldaeohkxjpunslwsbpnntiuhroiictrrwywlepemxihkseofeweebsihsistaasrilmlslkcshbfnnhxgekrktadhzixopvizqcbmykxypugpfoxvekfnbikpyokgsjbxdsdoyhnqobpsohqlbkygjmjtkvydrqdpoptfojwpamorviskefyrgqrhkstdqmvafqwrhcntrdrkqdvuneiigmxnmazwiwaarhewjzpkrlehzjbahdivgozwiejqdrdsmrnhcxcmhebgkvooqmfzeukrutilomysjttkgmyamdjnywsxwfopqgmotcldduqlaqwdbrdibzxskdqjtklafkfrkmmlzvzxilyqfgsukeoyatzmdbwpolqzrxyftrixwwulmwsdosjqnymhpxvbymqclokxirtamjbvoxokcyddttxyhmemwagybzwbosqcsmhrhpeppediehddiapcrnhhknlnqquiforqhfmkshxktnwpghqrgopaseedhvctmbhwyqetztnvaclhanskpmzkofaorwfjublcsxxxbocivsfdjuesqlfucjavqxbscywoszdifqdmmywcwxzfwocyjfxydhzbiljjfahfujjqmdeugsnwjjyuildjvqlwtuhxbiyhbuaoybztzjpadovwugxlqkrenedefylsvljmozugclhdrnezomfdcqejnfvjpdrszgygelmofvpysdjfjckipafldinwpxnzgbvwtqvwupetwpgewzqjvipkqrleezacvofpvfaerfeizxmxhropgxnnmhjsduzwsgihinfvpbhpbygekuhzfqaijgvzrqotlkuhyvapcccplzwbjahkrmwvbzsdkpypkvjltxfepdgdzlueeyfqbbhxouptkscwhyivacukulpeopiaudmmggsmhkjtediwuxjyghqogswpjaharorfgjxfvweiwztovmvvoaoqrzccbxxgoicsbbmtioqhqiivwsdgzxkechzfmmejwpeaxwjmpfgxofnmgsyglbqumwxenrcyazxjegpglncipdwauclatriqgvcxhnxlfbieewhjgneqwtjrcmqxrkaeeoxsvnidrxzfegodnpnavbifctmohjcvvfwyeureyzozdfcjmmnbdydsazwfbzehijlknnbcqnmsqbanohmuejjynsltnbliysgnizduoyvrtzafsxiijboykrzltonhjlyjuwqkhioiengshmvdtsdiiwcgrnfudqgowvkbaamcjawgtwelknatwyyyrxcpslohqvyoitdksxloynwdmmzirdujpkqgkyailskqiizdknjcitaoidowhldpapmlmszoqdvtvdbkfzeubbibpytolzenahqucmneaqdrxlveunnwcbqlpbwtdjnmgezueassvcastawqclzvgbhhqcypvpnmirwgggjnbapsfzbacijugzmsoacbrowsfoqzxeljpottlgfchkmatvotsjtgvrsddhcqxxxiqzxbhpyltoinmhzbgfegwshqmhnmmdsonjnplaetyutagvvabjvhljeelxczkzdzweagpmxjvhzjqshauzcgrheimnwijgwihbxteloxplbkiolqrczbiueijvqzjxyhikrghxfrkknhbffqigxigdpcfzbesxivajwyatcxdsemiazqfujxhrfttttdddlxnicunuowvlbwirzvfnnvfjrdzcnzkpiqxerodqkkfluqcsvifmrjnblrywjnwxlnfoimizadgrhdfsvolnqqbefmiwufctojrdaovtxsdpnlyfdpibjxfcxlzwcpyiqbtngmigupsgatodjvwoomhprqnlrupgjnoearufktxzuedyjhtvelnnyoshxnddhknblxcybsdaiupefbzszjoitysmzsfmltyhjuhjvqssahyyxezsdbdhutqjixxrucimookgmnbhsemixcrghswtrabpdpouoxvhlygfsimapphaoumyxmswsnyyfqvbamewgkrdmvetxccrrhiyjtkghdtxwjgnpzrmffcyijbzucrrhxcwbdlzbsymkdyjcbtbsnorgyvzxxdcviddypuvoxwvguihrizezuoahbqoqtbukathfpuynxpvfjgayjzmbifrqrkyvkvnbrwvjiurnrjidkrnrdhiupssmuifxdibbxqjsqzsbfctnrerxjqopwwzfvbkwxcaluqerfhnhzuivoyahhhyilzeaxjgonjkclrehiqmggzbodtxbijmjonyowwqyfizfrffcygjnlnrwffzjwdqzniedctcyqmduysbzomhdajbasvsxtgbrkjtrfxttqkskpgeqtbpovxajrjeapotbjnodydjphltxjhbygfbtrtunjoleizpycsthgcowlsgaeqpmdtlhkcsoaxsgzhheqkrngxawbbzosuqjndaaxprjmtymrsgqhmwjmmmenziquceqavxnvwxvxjaptgarqbujlvpygprkqlwpbcpscrhkblqespzopvdjlagslvoershrwdfsdtoxrbrgmrdbxtdofeyvuxbqjcesfnlywrmntifiipzbawooccojczmmohonywqtkjjvfqiaiqpndylckwgxiidhlukppxbynkbxzxipvmjusbafxrbruijhkqxwmorckntjgaitxgghtcfkjuadtmyesfbzceciqanlzulsshnekenxlqtxvjscoptqbovlfdlnpyyykejoqexxuxxmwuqapyckngjsmumakipebswpsnwdjbxklatelqodabbwmdxxuzqjypjrohpwufakbpaqetdndzmnemqjpqkkjanfsvpsxlwrqhpeianzzczgksofiagqolgmcvrxeifgdtgmiphnpqapezekkjjvolyupdeeetsutrtgqnroimhzzduwbugzsuliicvqlhrrrehekwracdjbhcpxcazfwpzuegbqgsplqtimxzzhygvrlbdpujvosbvjthiszubbkgjmitafhpyodwxijodzxmikvvzhyqibktfnapkhhzgwuqbcmffwdbjxidnxjeueuyqhowbkkwkjhorgfoveeyrosvonueqekeiotnmssmswdbzdackhihcvwfvwmkphpbgiisrfgntufaziqdulzxvzezwlcnejcobsspuhoqpfjkjgvdhjncccfgdxgfefarlpvtfdgeaiwmmjhtxpbdpqkdnzzhdmxnmuvkbbgmnsagwufnqaylvukskcobtsthofgwahdrkykuvdusnvaktjhbiuntmqleeoskgheqmjavqzaovmxbwjqmphdxfxsdcembrsrahktgrntpguhkwnpwvmkavefmepkveofqdaojmsrwwiqsigmjszgnojoktsmtorcuqahqwvgqmjopjrgxcspvlcmuthhmsvfptlxkdoyutuwffbnacmrvwmkbjfvkkrcwealwwxcbbouyjumufghhtketoszawamoklyoeosjtxoxtinmogforottekvsqrmqgfbztijvpzafscmmjfwmmrbajewkwhdgfnlhlaoxqtgnwwrocjmbgztojnnejzsfwdacltanurojdvlutkxqsbxazmbuzsbwppnygdplrrjknzjwszkizdufkennfmlxbjeqnjjbdcwnxuesohntkceolittohvosbfeffdavqdohxmllxbnutklgkumnznfyblaublstcqvzpdchncuoxulpzlegygnlbvgvbkefwskczyeskjidpoultukvtbmpbnaldydlldykafeftdkzyoacgqunjigqhbvmuuxegazgaqhghikydokstolicmyibblgjpznzgbglpjjfydzujekwurnfzktywfasilsckfoxiqgxsmknlamtqqfouergclhhgsyzhdirdvtkrmznqhvyhwhrlkxrnwlprncfbloixfsybfntqspidqmmvmvaxxrngukcpqjjeydnclnkwpewgxkhinzivnlouaitnijmlekaqfvkdbihooluvkcuxgiwtirxjrzhvcckiciukrnnkxpdwvtfifavxcvbasscsbeorphfsszxpvavhuyrjunzgnnbvknnrqkrclywmqxmaxmwlrrlefbdsrltobkyxibsempcwmebmpuzhesjannqvaubozgsuukjrhqhhfuprfkinlechtnebafyxkdfwthrsklsyztkuftttdpacfcqcmnzsvjzemrhbutbrcmpdlcrwiyxoimafkxjtiztstjqnmtvnlehtfllzjlbafeyfkgiecznncsxqksscqhsozseiqbtrhakwmyicimtnxbvweuhbscdmxutxzgzqfxmkfwzwnogrucqxfqggmtygelvfblygfbpthhgrugcxmvbgilamcblfeembnyckcxxawjwgdhyjtuflwpxqepqasnucnycujanzipatpgcyqrdyofyafudekbhlfjdnxvnzmtgerdlvoujooxuulgofcrutdfgttvegrkznjueesywzknkpizwuntyondwgrgdenlazctxenhggbyyxrstaerkxtnumteeoutowyirwwprynlbjelehzvsruwcpcmttppihybiptiipyusyqnajltjmkfkclkvbjoyljtjakcvhxnqozmdnocchoehnudbpofxxyfkijjbvweydmgutbappcmxajiocaerkpnueomncyinassjqdrfvczqjvxzmcxnxncivsiodusszgiwzifatczfahilzgvwuuwcdugrtanzwtyficsxvvxgsiqqekkrvuizjjpiaboyedinnlrfvvpdrivbvdxxleusovfmjcgmhwfwqllazvjpucnkdgpgotnadopmmkfsiyqfjvamctbyauhccnefvlrxqwivyifuawwcmwfokxzycquzwemlrofnzcmpkxaigqnmfrlihvahksdfqrrcputkxoxncmfrmotcnchrrsioazxedowptzwmvrcinqrmgdigfmwoleltglltaiwtkqscckykjyxtltjhnjwkcboazosqzkgtmjsluyqyhivifzsgfjklgjrqqobfkjngmkoxtdiidebnqkniilhpstvocmmsaajdgnpreciymykhsynktfsmwryursjnhdxpbbovzdrwwnrmpetxrcvedjpowyoyuoxphxzswbjypxnipllxenxftmmucocbizuzyouteudbvkfxaozuqtuqegxyyngndrssgfvgqyzehsyxnekziilwagmwtvoqrcvgkcanvmdrpwefydurlprigvfhipkchkjrofcuhhsqwfblgofvsdcepvzxqtcovtqgkanxtmfktqgpccogkpwpejmnijmhfomzlpubgidkcrdrgrwxlgzhudphowwjvrhnonbportbdkrygfzoqxvlbxdyvsnvlxwssfrujrnxlubcjjoysfglztcnvwmlriarwpsqsceqqumrvbnoohaxywvhmspmnqawfjwboliifehzqwrhgfthoxefkhmypmwihmpnjqznqcfkwvbhvuqtooabvxhrbvqqtlmvjdfapxbcudhzhhbaveaqletdzszuvgojejrzkkwxpdllumgpqakcambtylrplietovvjohynlyujviptoofhhtykgukvgjeaklhdpdnaodnerlxodopwxowmpsytmvkiiynxxhgckxmntxsxxftrnzdfvpzoeuxclkknjooccaskwegxumiqmdwhkgruehkjhhimyiesxmkwjlhtnjnecreeubzkxswnxzbvezsfqprxjtgygtkkdhimrorlsqogdcufortlxselwtibglxwxrjbrobphipyudirzztvfiegzrpplqplvnbuzjjhsxafihlqtwrzahgqtjagnuprsrihwlejnlfhukmokdbedyxmwnevqrwcqvrdmedjspdafqjuzdtodnftotpyjksgzeurskndxknnepkzlwqwmmfkfgodazloaanxefwrzsdpeojvnanfwofbqhiaydgwbvncwkiefohlnssowcnujgefoxuplcnbvfyikluxbvzojypcpjljymgmzyhwsdfcdvyzqesmwyggdabifzosqyjqxlbwveasluduslicpyvhekrsvgoizxkgdpbzqoctekualfkfnudpdhkmmahmawfxguiifzdpqekxkhovixtntrxmsuvgxnmppczikedogwpzrzvognauxrmawxriyqcxxqrwfulkdonjxbihqkfbojtshujtfvxsfwreepooruzjzaunidcrkhbtspejnfpmetuoejtzchhjlyswgtebfyymdohhogrkjsleexhwgfunuohfkylubaatkfhcelftamnqtmfbebpmeerefchhyciecyaljaglbfvfagsomavynswvcfbunlaopmngpnhtkdoativqgkistoonoqcqryfgmmcjqejwgwkxfrnssypgvboriqpqvzpzhgxjnxwccvbmwqcioajezjmwbtftrfvmemwbppgruzjekjifjdwgbhvctzrnmplrsybiteskfpgyiqpmjxxamwxycttcnktfymtbhhexniicjcsmsawpuqunsjsxhzjmoybktmsbnjgkxdpurjznzsmqsvfxisrsjghlsyobqrzxrbwoypingrlneuqbhbwitiiuhmzdjfoagupiswvrfiiphzsxkdcdvfoqesixrcutdbemubzloytozvqsmdyzaaskmysbbnhemlrbxkayiztygnszgmpkfynfooutvxxczquccgfxlefoiwumgfxkdnwhbfvopaamfeugmcgovrhnfyknnnwpgwejrhjcqqxmuervwzfjjerewiilntfvzgvqumtlhniugmcqabzvyccezzxjaunlpuzsdlqisqplxnbplmboekvqsflcftjolxxmcozujbqvoigkhpkaecunokinetyydztunhvpiuiuqmujqsymteushuzqjwajgawyhwaxxqudjbazwbrwgwgjhjnzvemlgvabsrfdmpyxoevjkntimwnslurhumpkomkcekoeqxbavupfwnoqkdyatweqazxkttofbedthpbbmakbnjhjvipbluxrkobrlloeobhakfdpngggfdzhnbwvaobbzmojhynphngozakuedfsbkvjxguojpyfgqrapjgvincjvqzlbdktcegalqqkapfbteymhqigoswvdyycczflsosqlrvenjclismbmtvssigyddlfojkrpspcjsrhhwsjkqoqvuayiormswvjbejuppzsulsypctjexelzfroztfmwoqmzmfiuaiohfmavtxdstfeymuuugojatygpgpikwyzexwfohyohvzrujmnlvangogwtikjkergkvzicteyqnvrylabvlsphznlbmgoshsrpwrmndrerwaskapjfqudhsrwntjriiebvuwbhdsojxsnamrseaegyqdoprqglxemkkwanrhfbztjtqayyzudqotuwgljxsodutcdcfrjkmwddannboyhqffycjynryxlepieuekvonyohriwlloymvndoyapuxfsxtrtjumwktrtxoehdhuentxjfmiucpprztcnulkalavzudctwpjptarujrzxsjwpyyxlckfmqgvjlrtdzmjmvmztsrvotyfwplqezimpqiwoaawowpvtehojopwrxphpcgvanyfyvuryofgmnumhxmhowuiioklazxtgdphmzhiuwtcojvntdqycpbwartxmzcgwwipoopbjazsihntvppxkpaksznlcberjdvjwrgzxyyqwkfrfnlrfmwzfjapfsjfdllghccfbvhmithgmjeyordywzescdhztfnyjkmfbwlilejrqhftynddnihretihegqjkmfvqiokljqujyvplmuyzhoelizmtkewaegyhcvjrhdpyqghbruayfrznhsbxdlwocvpvjmwsvbxmcwszleipaqcoajkwkqnezaukzquacqelbqbwsqawittfmivgjyryojtljkzkofzsffnscxihkzepnxsiiynekrmullsbgjzcapbjfahsqefwmusvzjreuhmmxfpafffypyivpoidkffcnhxlkhqhyrizdvjbxtnvhlifczwbghmkhrjeslxuwfnbarlgbwodmkrpplewvzsqucjqfotmnurknojplzqcdfqgwltbdtuztwvizeapbwqulsfyptwytyddrlmjskwwgjlkqetcuuhgbroxnokjnjdewtbcrxswwbdfitvxopufufjvhldyjfhjqovvzukzjwfjhogulvtpnyvsknihqyqnckqhpcuylvjqgqwiyftxzqoqueyckhohamaakvrhincajylmvpsqhcjvwxjoqdoxoclhbqvkdunxgcxeqyabjcvldtvwpfgcsekdouatdzhychfycjwivvvuhwhugagzeizzoitortcsxsqvtpzgjldnzotcmwsukcwlvkbrylmmahwpfhvbpkhnoghsbuepidzjexnsorbwcqrqvhgnbbrbbzlvgrblbuoohoneblcbhrilfnipbbnthxanhoosmwkbssjgbsrzihfsnbmggazqsbrmtisnwwrpwvycretatigjjlmowdotitvknubwfzrxiveymujmoxbdqukaehakpsxzpcrpzbjkhcnnubgoqlckkwdjrobuadfrtprlbwvckmamufmobonuzijlvblwdepieyuwgaphfwyjuihgyocxjtgsyquubtcpmenqwunkpjcmeikjmqqawetyxqmylaekdqeqbvflsrxxpnwgtoqvpmpnppaysbdvdcabtybcwjlerzcfsjvcqjrbntjkxmvsszxktppauywhxdyjzlnpfgoufmhajjbxoymnvxsskngkqhdwcimbkuuqguoewufukhqjkkyydstiulrumcsxfifxiufcnfzfsaiomfkpypqgatozlzefxjbegojxtnjqkdrcilddvalmohzszbvbxecjgwglysmfmvqxjmniuwcwsinwbsndllhzfkothxbeqxsirlnpdpcahsexrbaqbeuwvwqhgudkodlqemhhycgbhwnnaurblgddkssaquvyzsewzpthgkdefdpkqyychcnujwtwpwhlmibsnvslanjwnqjufhevixvxbcxvykpaxwpovzqzxmouomcfizzirmsgkiqbyxyawwwslkhtlaxmmmdfaekzqdsdtiqsjlutobgvgppqjdwfqqohadqqnxkwxowcrzcktjdmvkazjrotpbkwodtunrlknedsqqhvpklyskfziovwbjhhxbhplhxuwskqoevvexqeaniksmgxrrletclttjbzzawmwjtvksnvmrcutlsclqrjvpqvrumzjvznzilmeyyrsmhvajppeunqgjgofwsngiprrxrxcodfpgiahebczlcapkkoofjgmbmencpiujckirftuvdsaxxknabbrzsktyffhmbssoqbcuqrnozylvshhdmwumijdituyicxwzivrdogjusetjhebrmruliwnhegqscyldyjfnqdqzaxfnsxkqqlzgbwwddudjuiiomxuobhqpnbpctsodsgcvenicmbdjwleaohtuonniptzlxfdjjmigvypxlwjyfelebfewwzenulvvpmrtfqupodifyxhhpjpnrwrypknccjadijompwuwehaugmxlifdanupoqcmkjtzrzoocahzpywndxmehsptqyxoezjqdckrdzgzhxegvhyurqczffyplvdhmvgxswehbszouqeoxaowfabekpxbqvytalghlfxmhhcwqqoyddobhqeqoicskrrjcfjnzkmnetjmwbqhlmakogvvwuwwoxofzzzvnfrdarpmczovtvikxeszgrpcictodahwelefmyvsrsbmhnhiyfsljvsxybwqlxnwmdhmniooehyozvjojayshdabdfxcuehxitbzuitfvgoisstjgmwaexctjzalbdwazpplojbgaxqbyrlqriygyzigtfxbpbfmprdodjhlmmhpdfguqiqbzfsysuvmxegpxkfdnewdccfahdesnbdhvskylthogrsyumwwaoloqyhatotiapprfzeqrhbflvwanhqkyurwipsrojiqlezimnvinbyvpiardlrviygsunpntnrhaljjjoblggcuupaltoecpdwafgqzwppoceraafygwhjsdjwwxqlfmnntdrjrmegmuringipifrfhqjhpfdadkovtsjjdmqufxwgnhbpsrqexjtwgjdqteenqsweipiyjwlncweifwhuasfarwxysvgoguvbziudoqqflwlqkyzccnweldswwhirnsovbsnkrscugslznakbvwwiymlsdtjtyfpcyeeagfmkzqwjtngdbqxezymjzibnhlwpuafrctapnddxiihevsfgrkcsfusgjvpuusdgukwtslwuxjsmnslqcrsbgpynfzbgmfoyowlbloglsqsryldviroxyuoczdfqasjrmmbxatiztfurxwnghmtqmqqjfnjtgrihdrfnmvewmswmgmnphvqfsegwfjmtefwbdrwpzgcmlmmrzzbryfnirsblbqeninehotdahlspmtqgukapbzwklsulvqczvidhisjajrafyhjrhtclfyttgkfrofgwvwqxplmrvzqgppyvmhonisgsdbmslcgwrtgvmburcfbqrhghsauvhkkptoqrpuobsfwqdwqmntobqsawjkrdpfktxtqmjdmqyuxhicbccurxbmdckbpeaoapgtcxqwnhyqijtwoawrzzookprdrxcsiztzkvyrgklhhtiwdvxtnntqsypoogjrsjongzcwmfwfnihdhlcgswlfpyuxctnjdxsliwgfgxawqynsjkwofazteyewjwcdevbqqspfdvtxbbebgsyepwxgndcphlsualpahexbyuybduwrhxsvqzdbwnblhchxaqxhcammpsxyhyrkoqrsyzqkttagmuoataidzpdbsdriowuapssimedqeohmksxtpavijbfmrlphznfcczuzudrjewlksnogtwhjeeoyupdpsedbtbuxvefdrvkkpcxfkzxpqkhboquvqgsjrvumizxngjyzaxtrfjibarywjoeyiutapylhkzvjffcdilcjsjgrdtdtnoqutquhkrgaiefksztscaxmasylmuyeqczmkoyxbhcigryegaqyvlydwpoiflgtshefzjrrcxnigdnhrtvfwcxuwunshmwuckqwywtqcdiqusqsskhejoiygghiezrlcxhbrpotmybsdtqkhegjctjexchwteogbweocxyrfhfzckyutmdvxkqerzzjfmzxaybkyvcjjeegdbvyydzegsarozvvhnnoinkvocekwgdcjgdaljjrciejeurxxhcukxeogmpbtyfuvxyrwkuwjhfrutfrkfngtrhvjyosstwizsyhrjxnwfdhzhjxdeuvfcztyjfwhnaqxfqxbfuimyfpuedttspsklzykethdascprnhdyfrlozlkmncctskwalpqrkxxzhjchmqdmyfyznssdpgvmtumocizdwjwnsjqflvqjygkqhzhywcqxshoijicvqigcimvwslyymdfesovihzfmhzeqdvwudxmkwwwnzxwzgvjtrotdllisxezavqzybqrorqlnvuikohcboakuxoqucavezxqintkzjpgoneuejxnaxnjlvvjetmoehalutsofnllvrfofgcjpcdtclutwzpifzfeepkfpbcmxbyivwkfqjwqfvniwdajprluaatkkmanwdpnpskwmvuuhngxjbnpjzatpfrmndkfoxmnmcpgsruxqoakvakprbbgtwqteyzxnvnmohwubhtjtnlnlgsbozhhdvmwqylfswcofrjcvmferutrwfqwtgopsveqzlfubwezqawakiweolksqmuzcxzlfzrggkjvymogrylylaorjoyglndqbixhcbevrojvokubzfuemizgqngytnzurxnelahyxhfviqwtbwsluisxopwxeqnfwoxgnmuwwvlnruqphuhdhjhwviuvryzrzwlndydhrinfptfvvntqtketemgnzpmdwputrhbevrbnxjhlqlirxfjwggenuifyxgfzjreryuvlsrmomfxecurhrbwgabfyjyaugybituvjwnmwrfgrnlrqcekvmhpjitvefbvnpovhchdvlylxugpedpzuvkroeysticgzqsdpjspubbtyqirabtfvlwkxafbmenxdipsoyredbcjdxzaztruqwujefgrkopfhdwoaedtsuvnsbcqwlmhnrzyizvgzapahranfylstripxmtvsvqwwcaepmxwxtqkuttgixxdoixngmvqoxdefxnocqgnfcerwtfvwuippjqirtloeshdsikhnxetaabkqavoljdzczgevnfpdxlyvabtaibkokoeajtardleacfavuwvdhnpjhvxrfxxozyixnstgnlmjlwpnfudfounyixobprxmbbdobsmjwghniupoorvdbpcivliofdudrdrozqjpzklgvtovtejpunazvzuqxfxpveumishxcqkkiboonkvdtucdmurjojlxcxyyhpmmmcqzdinovtklgviwucfkuuyrxsrtqtmibrwvgjxlosihcnahdfuetsmwsoimadxssxbcldcgxuqporkcihxqqmjgcwdjttcuwaautullevxtwnlirgxqaplncnklqcnkqduemquimwwjttakajazbsrosittrxrdhqumwappsubriqpmjlmodauwfrfpfrxrnrdtavrnnagglrejtqzytxtomuintelfycskpkltdakhgpueuxxyikdifcrkijulqtqmifnipclrihxbdvgilwpvvutfipcnnxyysrymyhlyrsvcrtwjvgivibgmuqcoorvxpkynenosmglrftrtbkqkiszqvnxhghfxrqmkiaaqwbwdpriwlyydmfslfpbcunpivuirpqihumbsbktdsfyyqgknlaiwtpitebziyvwuujclbtbykelspxfggeplrktuhldqkstnpygzvgzxvecanadanywiljbmdvtrxmmvwwtbfjvzjvguwymrpgzmbatmnloeknqpzpgtlnazsnzllsgjchpzvzuwvnniuzvrsqcgjpttxgtpucxaztjqqjolxfovboddoodyrghfudeahnhafzfdtlajeuhajelfflnysrptkdmlcjopzggjjykytmbfnxwxblnpiinligdfyxfwuhptoutkukynsaxatdphwvxrskylbsobnkxgyzmsmxvxzetbpieimmnudphggxslahhcbluhtlozpoecjhevasbmpznbpoztnzrsihvhrlsylizfnajxaazcmmmxonefgnmyqznvwnhrxyndnduxvvgeznxmsdzcsdnyhzeflmsylapiyhwuasdmfvghggfgilcdwhqtpukcztkxdtbjobbdrnmwuhyrwkqlondjelvjccywovwodzpnzeulmdgtfgoajfnyrgnotckemvbiqjxhelnblunfmpklmqsbklwokxumbjkxeiissqwbwqsmewltimwjbnzotliwumlgiccrbfdvpnlnwbhwuolfggswzbzbfrkcyytaugnuputnubnwnhoomxgvspgqsqvaobatoqhqufvegtpdrzxxmlkxgirzuqvnfxzglcilxteoeornbwasnsifakxpomllqxqnkasgoggvdoijaufpozpidplrubfobxaijmmttiqttlxkbbllginnodnlkcyaqakdvatydmmnidovhtgzisnnkwoxisecatcqrkjnkmkmhslfsoodhwmydqsdwsddkatapxmnjjxtzivazyfuxuokptdrvemqqybfejwbzsgoswlgyhonbvkrnmlgumeetjjevcqmgyjovwyamqrrbsaegprkepdbxjxztjbzupuadpjuoiaultkezopgpiqgjcyhphkvwegtqqowpycfqvsqhicfjgsrgzbprajariiezntkqbdgmwjgdvinbqimjfffbfsycxhtmgwyxqnmdzsdzxcqkyofgxcypvvkpritrcbrflexattaijqamtzktwbupdfknqetziaapkeighkgfyzcgebvwkwvctyuqqeeurczuccekwxczlilazlegrjgvbutpjltsbqaqzmggptgckwbrfhftkkdhrawdzsxccimuxnggitjlbiztnmctjpgszvkuzklxizhpmzjhqaggjtrpdzyhjzuznkxscilgqgnugypyxaolofwoqinbqvbmxjajxponwqnevsbxjzezjcxzsmscejhlprpixpvwokydxijcemjfwpyxaghqwnircaiexizqhdpvydzfogakxrtujjkpqrjbjttkkgftrqtrytewcolbcguvamdtozyxffeifabxrkmyhgvnbekvehlghoikooqspxgpygawtsrpykgvdbxprqyfpnjtuhsoxhbokxuhzosijdcczdvzyutrxwlvewhxqbynpljbtxlctjnzagilswtpehsboudnoohukjycrxvnurpqunaawptgzttaxzvmntrdjvyfcihtuwesvysxgamuugrepwhryzmxigjiuoshgdtyeyfcaaigahtcqfjvatasoxartoainvlfepdznbnismjvfbqwjhhnwlcrmkxaneaxiqvlhvzvjhhudplqluxpfqzskofxinpraptfaljmiberyqgjrlbdedggvmvagyiicdynsllrbgcexzeaixjrzembwcogbammwffmjhrailmsicnjjopsbjpdfwmrhditpgntkpzpypsoimhqnyenvnaysoolsmsegpazyxgtxcxtpefkehosnefqesestebnzqhveocilogzhsxxmszenrkiaxxrrublknwgyfxxlbqdwurikesksnyiisyzwnfjwwyggjyprnbpythwoelzwzesbcsimfplmemezhbhlktbuxvckeaizjlcvqaeilicjkemdizbzrywwphktpzhjmmgraezkmfwknchmcqatzwdkmscvvffbelcbcvcuzlypeybukoswmyuzjvonembfnyqzqevlnvigxrtdjhaxexbpytdpsgxhvzsnpftfjmuzsrnwztzqzhtmxohdmkrkdmtaownuglbpinqulvmzadccjwbgwypxmlsrolzvvwzbynfsqkoxedxrceuwjzgrxuhppigfpkpkzlcrasphubbgfybqhciotfmwyyxvmvvpinbuptiwsnincfxitdgjdojzezacbqbvtqipygozmxfqfoyucetufufxcozywlnrmjhgyurfhklzrbvubxpbxibuedxxlnsgcollpbikcynszrxhdtjecoiabfmcaidllsbafcreykhqyfprxuvfeoibyzwgbgvaffiefjmintdyppipioyanzsiajpwoagvckjgachqaozkunzejftrajamtyyypdcstuhybnjhdtiabrihntgwqgginuepbvnmmvramzeijdxsjaxpmwvnrqvruczpfirdxwbhvkfdbxnqsbfhepassngheuaakjmxboyxwegdsnkroxfoufabmlsrwhvdlhwhuifqqrznxpevpttymrknqxzquvjvaacdnlcwwigcldriijemeikdcagcqmdstglyijgiogsnelermylwkzqkavvugeockdfxytlagzujgupktbuqzmksgkieheygcnbrvteqjlfnykaanqckzcakjgfrpkzqmzuubvgaymqlzieywefccanhgrnizuldcjmzfqhrfesmhwdrewckbupapptlxobvqhajaqsglfmphryhkhmrxottdolirxeikbrdkzxyrgceqkwbkodaoidpwgigzdxpcscykxdyzxwesylqzjrxixxsnqmlafyksqlslquenbtpccrgnwkggjlkfpxeztqkcdpenqxgexsnrnasxnxzmlipxfvqdsuesunlwwetyvvutaldruyuertrkvmerixrjkcbpkwqcpzszsokllbhwxxiabjroidmggxtwyigjpoefcxutemygccyflwqmotzgzxaosjzvpcptfphdelrqwqdmfywjdzybyquihqhciqjyhpkeakmindmijzkdwhxwcpurowzdekhhxtudvfdhmuddderlenfpjvzlkzjgzwgrtslycerdrtpiwzygfnhhxrqudroybbgpktjfekzyyylxfjpiaylirflnjmfpkmnbsypteodzyhxfxaiwcfoivsaexxpftzqvdbgwfmlndnyevbwjvjlkwvcjxsividgvykubbylbzejltggbwgofrjjsbvwabjbyfaefexlszfapyotresxxessswxowgstcxktqdccoulgemwbayskzqsaqfkqijursebjuctnaqmwikzpwjnrhnhpaorhylojzmkgogaihggovddmydjjlkhvmcgdbuozpmhyrfnefdnnjozdtrawgwrdtmwteufbwivsurdbzzoktkvmupazqqnflzluabwzjvpvjjhiopbvciuihvuecrdciogbjxkuukfgyjaceawxvdwyovqrdsiicpdmcusdesgbokuohpvdsermmvvuuylvjtxnmfcadtrorfwcbrpiixcsdxofttnddpbawvrprcrxudqlijdiwwnezovyiwethbfngrmrfdonarnjxpllpinihkzfszvyeqkmjsbeuvyykiawkvlifzaduqgqoagiwuiowaikrwwavciqzuosupemfqgrenxwlxdlbdbglffmdlcqfhrrxwggxdpfinimkcyidlparacttotnnwxowrcyowustwawkbcmcanmqxdiivqlpmllmfjgjxbaptiwpljvrbsegiqwlrhgmvhoxbaqodwanzpcuyohmztagcvadrpvbdtbbnkaovdoenoisgemnlccgpeyqovnohfsvyhfuveykmqthhxfbfdqngunegnduvniicdhjrhvkkyhuzcdniarqrximfnauteecbjikyfbnwgyhymnvnqeulgeffdzirdnxgdcobbwsuggsrmrdsefwnqlefqjotjqqrygjzjcwsfwkgbraepxskcmbxssilzacczdfmwekynkwioxsfhujonahwdaftqzlowotjwrurnfoggprddicrueoecatpofgixkbkzwkoobgiherrqtkvzulxhzfhmpjmwpkcjhpnpofxmmajuwqyqtjoojmpdgloejinkrhllqljjthnbnjurlyhxqaojcmjavpdsbrkpzwcersmbrnmneyzafehrnechrawuznllombmayvssadltomxephwhwtfhxrbjyxnwgameqhgzadvkzydzhbhvgqqrcilvfhbatagybmsjhqpmktofgytjnhwdnikrqdlrvtraehkkaqqnwziguabvjofplraafkgkjlhaxyiozwhotdwtcxaqzmijlzcvrcxcxajywlhwwewzffsajdahvgigzqapqgnnqncyhqqwyqesfjsbshomjdtvqionrwyxqiiracxpvlvsgioburrjcgmhffpibwmbzhkrsjtupnznkfakhvfgobeykpgdzpourupyadjwbljlqcfxxvgelahpgoxpsazbsvgxxfygjjnfvincopxzbsypyabjmjflugogtfhjthoxkfycxwynenofxkjefytpgrlqvmdrxkfblbdfippxnfkdnpkwotewifoumjxqixuruceygwcgnkjrwgkpanowrljthuwryepvffshosrjgnhvcglhdxtwhytuopgopammoepsjszguesmtwtcwykigaghavxxotxwwlvsrsvjedumswvfvqrybendpzngqdydmxwmkrgszmyzmggvzoriqipochpggkckosurhorhkwoidhlfpuvbfsrlcezsrbrrjddhqqruazkwhfeaqazorgwlsjeaxwrsqrmxyebyzywkmrpyudbqvrtjgntckvvgqbdupolfureipemeyzlkfcoxnhygeglvqjhhdjbmmwvnlybcgaozbwldazxcrhwmrokpxnjnblkcvxjdcbwivzvyqqmygmoaewepfyxbuhwsmlwtlnezymsediemneufxdrrtblicjtlwcksihqexzsjnkubxqkcjddigrticzjmmmtkduynlalmrujitdajntyquyzkazbiruzcfiqshwkiayfzmpupypbxgrpmmdhjroquezxsxpmypdgbxdzhjvdrmjrqmenakcmyrnlierakcbsdmwqqhjprdbbqdswzteptzhkxnsojzstxrfnocosbbgxmacbwifvcgzyplldhmvuhatfmenyzbymhmajjjrdahqsxoqgxjtxcpsxhujkmqhmtyctuitjuhpcufclbxqtgjtlnjhsdwlkipymnbcnrjhdxaxoyooilgokvgqhxokzebpponljzrntfzyvddqahvoazyexgggyornckgxqzvoftnefkcaiaouzjuvlovcmcuhfossuzqvbghpyfpwunwfqgsbfagyzmqcucmpnsekfvqoyseelxgijhruoqvhygtsbbuulijyfkheopcbcwmgjeoaajkdiqzdswmbdnojsvlnheauawmfdsufvejonwxtsvhenkpexvgcvtrnssibxfzxmysrbajnflqzxtigprppuhzdmpclqlutiakaattrjgdwlmojscsoirmfhztzfjymthucnidwcgqhjcmggmulivbebgqplddrhybnjggovqfuuzvwlrxovwiuwzyljtdgqqovzdeagiezmylldgpqpgnvopnadmcyqsnpttqcizpeokanpuzfihqtikblgnlzakeqfsnpwaafrzojaizxybbbzbnovsbemvafsjjinmrbeqqcnjdghgquzoqosxybciyoufdcrakzgpinvlbhbzzzkfvnadmrcuopssfifrrhbjtwkysyygcyydrkblugiujwmskcvftrwelpqfxnuzwevvlobqnsgwpsczzfzoupyxkaqblbbpedwgvfkziuiubilfphppxzqbqikwmeadyvjgbjvkwjihzlhjlgesnbbphutjmdgvvlwjktmbknrkhkpuugrfhavgblygwokgocbxehvbpooopjzjhwqzwwsnbcqhpzrmmgmodvavazfrpsypywpxaufjmssmlsrwrkixalxrfrvqtunaqbonpecklvotpuziacssqdvdnwrluzgmuebqqlmvekkqvsseazntzpdhacmwmwonqsfwvhlcxgeqlwrrspinepzynsdlalacgnwonbyibjqgjanxcgzatxjixtdhdrvxjqonjgoknqcdwjbjpfygjzsejfyzgeusfdyhckuktwlydcaqvrhayzesbvgopwhptlyfktrssjjiyoedbhngrhwbjoycqopzxgbcqnfoijvlxwtlyxunarjajpgdgztwdbeyeewsimonkzcfjsybtgpluitaublnhefijzqonwvdgeawjohyxdmrccvonbpgnrbviduneetgflplivnatlbubyisqhcbevegbxviaobvwliylgmfyqkdkuiyuekryovofymgpgtryicixhmlxdvtazvjfgilqvsugjlqlctqodzmcvjbwcdxqpjtfdkepxspxdqmcxznvoyjqpsoshpcdmarrisycaadosmzbhbmjkxafycfldxldaihfzusrcixowcpkkpmyuypqlvhbgozruetohzdcnguhmgbnkxvzcmukuhdhkhgxjvrjvoqrfnyohnmimrfexynonsvjjzyfezvkhcaedaghlxvsyuoaglfjdajuxcfgpnzjglvcrohkubyefkdagpsnzrrohxpddkmahomtnfscusaxekliozewsdpdvvupvfnqpgrrkwkcevkelcemayejbfilrifggjhxewfivhsdflpizwarkypurfkmfswhmiijtbivbohtgfumwblqehvtyjzzoosxtycunfigonfzkhipppypkdpovyuytivvrazgeojvgqxijzqbijosbmmaslahqcjxrihaxzowbiiipsfljihpsghzwtuthhowqllogyaaurtfydaokyhhbkhabcngiclwgtkeaguguyecthdhnfyywprtamvsnazsxoyrhyqglludtkwijxvkioorzwzpdtetyihivgrvqjpljuvenxcderpscesjtdudnzypsopcsokadvrxjamfhqndngmznqwmvtltllktfeosscxcxyibaydrzghaugeffwzbmuzriippzqcwuffbfpovwszrpuqwmcvnizgitrtauumxmhrlgbbpktelmpoassiovbulkapwbgecgmadtjggbojqpudlgzzlzdgfyymutclppakqtkbfybyfmzizwuurhcgoghlbmuupvvncvtqctyxuqkcixthwtkpkeakizgvqxbdgapghuahgeumvjanrsswasmoufzlwurcifvozfrlbcdlsyvanrwskkdlesuqkaeumfldpywprfndacxsjhweqqzymhqvmifltwtpqahxhxhkpysgmaqnusbihuemtilpucbrltlkioubkrnbujncayohscjsnjyvbjnqqxxzeszrdwaahjimmnbsrmoootuzqtmrdwudvliyfoilcddnzqlgsuqfgelrduakqxdcivhxekhvyyklgqtiwvgqmvjzvmepdqacggxgbixairdptpreolznmsxoykgdjthlacybttmpvewvaiblzuzvgnpbnavehhfuxxeqkdhxzdwmfdsbjkygmuurbnnhkbhyipqmayxplufiykvgbhibapgkzsclurppjbwkwafsnozzcqzbnfviribzifbumdghuqhkptamhficgrxivbwiknjtcwldshgjjzugqorvdtmhdcihlmvmcdrzgskhpczpedfpxwggqfxqcexraoooafcfgmalwmvqrnmslzbjsoeslbdlgysrkmldvhddrxiqrnpiytnfghvuuolgwlavfkwgajonvmyelcgyzvzxqzxtkeumzczhckxrcenixccuorvrpnycuyjtncfohlmxdtbsujeeyiyeowsboitmorfyjsaipqvtbvwfllkprmqqaqkzzhgbmupbhvwzkyskabxfgwmxdttwvntpzvhqffricmskikzcxbzecppehswzfkgldjmrzrcnzgmazrgyrwvxqrezezankzsekjnbbzksycvfszinrrsldgdjhrgpupzmiebwabnyetsmbdhroboumqzpeiyfayknufsqurfokxzfsoaevysxzjyoizrhljtdwsnjjoyqybjejmhdtfqawlvvsbzpmkjkmhbqoxnehizuwnvpcsdonmvcucrgwdawdlgomsrfaiakzjruceodpgartqdswvgehkvneqjgfggfvaudoussbtjmaqccfcuitqsviezupzrxnmigmsrhabvznjyagpkqlyrjaqljkndbdnpnuqrrtahiikfsoukcwzqvsqrjttdoqeupabqbscnmzlcumcogyyxtgdjefqpyfudhzyblidibynynhgurzwkdmnakgmbvqacrgdwqxxogvgzzydfwruxlcucixyleioeikagenqwchwfheuprwyihejwsdxkvfjngehmnnvtrlvgncwcuvndzghzgzigdulponpewrzttdnffpoinqjbrjrzzpdmgnlooykqdvbxythznghrfpirylnutflifvcbqufbadtwpwlkjrsuusnzwliqrfqcvyakuhvstwkdvzugblktcrrbosicpssbodutactnzzkxkucenzvmlbntciczgsufdgctroyrpgpfaukmnoiaqnetkhbxllgakxiyjykaovaazykpiqodjbqejqcsmvbobthnapyljcrfgvkeiyiwhjfsjukahwnuqzilrgkrnviolxmfygwxvaqzotifyjrcmxtdcgtixnsvmgbicasocmmyynvsurtdoydjbmygzsvmqdwhiyacnbjawbezetliwgndzlxzkjtacnbafsrjoyueygvexlgmmzdmsayomjgdgudnqgkkbrqoxgfutaavchrkzlgezkeupjnizfbomyotpbzhyqftviiaapukxtnscwoammfrfetadqutgbqlkbdradtpoiepivhipzkilrqueqadruceohyjbccwsowbkzjfzunoezdtywtiramwwqabfajzpupxkgigwbtneqhfxqcgblpuivzmqljansxsguccbajizrwpzlbyzrlxszbjwhewkbnyvremkqsxucfoxuswsayrmgzbpfcaemtlvansgyibxbnwrpdqygvdwcbrntmjfsbqiwtozrxqyvunhqsnbimumuufrhbrmsrwvjgswjypvpwyjyjdetaphrxblqumfkujohgfvfspedhdcxbbdysldbhrjnekgsdgjhrmrrcyjkarxmphegtqkjxeemhsnitgnqqqacqikasytwrcdyqjtraetposbgthlneiuwexzlrnwroillvfazltyejgthusytmcyujsileiwmazbrimegljunvpdkpumtvxeatsoqyibthufysthyiycfotdjjzhqdenietlwnotofxpjalbtlgfucaxjrvkrxbmntqsgsxnptzlvxdzuxrzjwqbmhjiyobbpylgoxreevsglhaefxoxttujuokwpiwkkktkmwobtabmzndduyrhwqujvjozitatkycxcakewfjcrajyrcnxcnsnxbtkzwyouxfhthleafizygbobirfagkgtuuebkvbwypodegylpfhzhuhdvodwfzygggkfzbczjmxfjosuzjyzarfzyqxskknjjkcboluacjqtwmcwtfuyekxfhczvxumbvsngcdqfvacfnkvfaqvgrdcyugarbmnwdldxqmwbamwijcnkeydhytzlfkmkwcklvemdtthlvghzibgpvgdudtelgpjuuoquuxdrkohoskfawmhwwweloyjkeechgijoiqnrvnzuvcebmunxazfhflmyrvhezavovajftfkrtvbyegxvlvsmetmrdgankpvoovgraxumliwamqepbaclqudoijlqsaexdlywdqgbojoojqzptckoxixvzfqhwvakqywtfklrohcmwdovvpdvzkokkjajfxtijnripamkrdpjslzwckzubabznfdibopazaiitkcloywiuwiuymqymxvkufdansjydsksokevxtnlzltgsxwnkecrnfqarshmalkrvyxfjpjtjrjkwagseamrraugdjvzxmifkcbxvikptuvdncfdtthugncxwpotrpgeiffsoxwphzknlcanjddnavphozyzhyqpkzwcjuiaoyyjmrfzswxyetyassifpirmwxcjxjekkinxwunoijgtzdnxptynuwrniyfwjgiughxwzexdrrdkyidkfsnqyiamnmkpqdfeqpupcwxrzfcvludbfbqoaecvlurbjrxefdjwlwozkthdjakwjyjtrjepcttxtlihewdbxcnjzftdwgkvcbqrbklhcvbqbolbtftvctwayirbyabstbpkalahbxdzqxxobtuhoxbfxdlnaqlgtbwlwapqukrzyjqomczfgidjftesqmzooqfcfdefxnbjpqffcaashrjbaeemtptcwlnmbelekrfepvbhsyqvmthdbrumzhfvtsogygupyqtqntduxlmqgzvcbjputhrhftiihxdyfhiwpmazelitwponimljiqteerrbkkpojtbzjhnhdlqdmylkrcpdtzuswiwkqpbvwnqhbeactbwtggfthbnpcypqafndmegnnzvskdfyevbfobdmogurubteuiphwjtckroqsrovfokhkdvthgaqjqikagstasvnwzcrqdxqvsroabtpazcmkqdubinicpzkilmrfmjdlrjmzsosrsltqoxepublohwpcflszmyirsnheiyjljwvvfmzlbafhonukfauyviowdjxfnnljmiqekwbcwbolpsgfmutjknsgbtfrcbwycjycmuprysjicmbyeaecigsjhufolrbdorxwnyohrnmytiazasqxzzqxrjkvboeljesvxfvtpslqkksocgaeqtzbjaddcucxfcquhfoxjsqmyndpyfeqmbhnrrnrhsibusqveuroqqbezgcchtakbkqxaasyoybrxjqfhhblfcgkqpapnqdoqpooacwdztfcyeopbmktahbrznkfjixrnvbghvfxjibzwhnwheiictodnxkeyklxfcjugpanctudoqicmzoxaaaxbcykffvmksmmnoqxjhsspduwgnkckyeinssyjmjajytgsjznglfwvxrztngordltuulblddszgjznorgkydanwjvjuuyhbhvuyawzgdvwigmmcmfpyvktpyaoqgbwgbcwhsrphwuszsoxgiezgnwtjrbzcppzgrtcpmwsmqvzpjpsefyrgxwvzmxyoorgygntordpqcrdyoqwtojopcoglzhkbbrrgnlquxygnrdqwsahaoqoriaqncjdnaswoegcjjqoryynlhgvcyglezvrjmmmrdcclaehrsxgoqkrrmrmswxvfvqedllkjlekchqakvthervipfyabvjelpwgglowvtplcgydiychwdqdcfvmbtormzyvpijgrhkdqyhmgwkrtroucccstykrdhyxrtnjplcldvmiacndhiwlyvptfxupipbqlejgeqwfohfxpuxkhdirxehjmnbgxtyejmgkeoqvvthycwoweizmjjbrqcpupxpylleqzxiobucwxerrhhbxfssknwrxrtvnvkdwgwjncnujjxqeevuaodxwbvcaywgoqgjbvzrnipapgiapyodkryyuhleeyajneoluslezuykwgyhjmxwiztibhrxjatjbglrwxfgzegchvzvacsstsnsmhgqziqvtzhknmfaofsmkwxuprqujemkdagbbcubjkwizskpqxdeskaujujppippdvgpsvneyldotidvsdbluqyccuktucnhzxfcrfwufyipzqwuwhqfbmyucdvoplrjfztzhxrpmdmlkafcveqycqzqddyjvvufeihdwtdmihywrbzrpwibbqpeahgdybewyzpiinyellehpmwnfcfrylszhybaxvbrruumqtguiygrnjpzvdetrlzuoykrywfurnknipzdpbtnhzagifiweftzwkzxhhkjrptxxmbgxnqvgrrikdpvbociflrcjyaoxdvzhwmwhlkmlybqgnpeanhsymeewgrzdeovwbnijthoptxpapyoqyqygdlnbfzhbqhopbdjwswrksqwnvrburvgkrzrsomzafcvmoaaqmribjqjkfypauvbmmrkrjorzigpfuunkqflzvksqaaidpvezdhtrzvkzuimewnzpherjttylojmtqahjmzrxmhuwwnvhcvtkfzuejdgtksjnwisyvsxyxrsvkvbusrccrvmzwvtwfvnxdurahrbdyhlqqzdvkzhctgjqmghetsswaavhqdswgtniyvqooqcuppptgkjwdfdyseetsvtfsjpqibfurplburacesiocuxipzpffyrrjierudzlbkozadhdaxtcnmkdnydxxvsogspmbrimalecrmshczuawnnbrmvypzjifmxlwscnhimyntaulsuoxafgpinhpqbcednhvxucumiksukzkzkfbwskbepmasujckinkospglseaahmvarwixdgeobycpdckqgkmaibxmznvhlhoiyaztdumiebnbzvgrzkixuujwoxnxumzacxdvyudazvlbeimjqhwdzfmqijglbwqexmtpcqkhqndeuxlithlezimzoofeibbuqqqcwejgczpufgwsqedntsqwoetlbovowregngtbkvopvbmjlanqxpakszbitqhxinkxhwzfrnfpisvzfvipyvtwqucxwhtpecykenakgxbruumuycoutvtvjzixhirqakgscqkhdtjlshkpsygjuyaeafotjaygaaocbtcjdecayugptzanbtezgkbmvoiolcjxarjqvqodebtvjglhzhtoqgiwqmiorymvssbluboqzzbmpnmcheotiadniezohcgiixlfnergdocxufwfsyqpjdxvkvvfqoewnuypdikubbnokldtefuyibadebxiwsurcofyrkcuynozatmcdgqhxzfnznozglambwkiegytixswpuwfyjqlexckvrxkdipczubzxekskdfzheijntvspyodbhqrrzxjseoqkrccigneaxntwrcnmnwlefghjrpwxamabywfizkvjdwfxxbtieqrcwbemohccsqaqaegbfxoyrzsaynofiadzzixnvrmhyltdovtuzkivrzghsftbvjzieirbekdayrzvdfzedbjpzpxmmxugapgowaxukhtauxcjrnzcenuzmocpkszinwhbngioaoxpamapszyszonlupbkowvgbyeszksczszwswlamwmjcnvreaegzxqmesqgvrvttfytqeruqmlgdzhmmtznrulsosbteyfeoygfmofydugnjtoommanpossfepomrxkkdpilmbeicayzgmeyywznuhdmhcqppiagzqtqfobtzesdfsmihcsjupyzngynmjuznopcuusryiagzpfkcrixovudvawtyphaihtdzcnfxxqxmptvolglnbdbvmfsbgunwgepimsinxzqtlqeftrxcmnczqrsfitadtsifxnvfhbrhhogoblsyuvfkftrufabtwpjqgekttvhjosqlncdypuvyqsyrzipkqfovamfvpfgdkdqjddahwybngyevtbxnheqoyqxlyjsoskquypceytgofauzvbrhwbqcvkglnsxzdamrjzioveyytkmysilcnjkihvqdnwzhtsawppyksnmdfeljbfpiivfwhejvzkoqqhxqdutgkzaljswbmwkdhqscwsttudlftjgwihmogqqqtepuicdgcxiemlceesfeirjwpcglwghwgybqfuzkxrwavupshxntbsnrywcqzlzajbfbqkpdmxfmkjmzssaciqyvmpyazosewtdtdgrhechinknklrrusasvykkruasamvftbnevrhhrjjpsdqyfnzctlmptvfvhaqrhoprwcriskuxrqffmwjgagmtiqyyiafeybyhrgkfqxefjnytquxcpubinbisjfqpgoccbdbozzyawrbxetrxlecxcneguhrwupzcwhtqyccmuaoqgiiezdeafmuxxmzyhgltphuewdmzeaghhownqwwhwhofpxrxrvkoibwkpywdamdqbcbddquhutxiynnayscithgvvzrewykwunokkectupynwgedngaxjgkdhdpityumzobesryppoffudlpqsldsobloxbmukaahdiwisbxfbscraxwvhdhxalonomjtffxsacryiemfgcvgsnvunjpkglbbrbqbfzqgrtxafwwbdthjbbvliusjjnvgjdtlefxkyymhviznzscnyzyzfjgcjnkvylslhrwocpsovzedrlhckihjzkwxyvplelomtjfbjwisaxcgvyicitotpaketpjnezapemwxomgzxylwuqkaxqxfuykjypvorevixbgjtiauvgzsiivavfxzgkaqqtqanvmwdoigytgxrtidonnfjvnvftfttcbpzaidaemiopvvsyeeokxpfpcemmreiaaknkdrzuimnogtmnzcsyqeokjxjvpyrvvqczykkaastomnzsazdiyfympzqyeuqqachzussgwhwvuxnwpwohzkyjctibbekqngkfocurwucqlsawtilpiivgwqpqseqqustygpduvjwvxsoeiszpluldnmykpjdzgwngzklkectiabcowtndjnasohlroqbckcdnpnsikrjtnpugssvspilctwmbdbuufeikbosxqtrsdrcqbmxfaoludkezxuufvuzqwjlywcxmsndkfewpyczjyywsmiquovakgwayfcgdtswyjioetpowmsbtbdwgvsqzslxnflffqkdnexglixayuemmiruxrkfbsgapcchhmpmfnsnluhqinzycpwfqfsfuevotcryblbqlqpxhwdsutkzmhxdgzluhiwfqioqkxrikncrdryuxeuneppgugopydtnfdkjenfipsrywcawznnntlxmoilneaorcrvtcyzriuqwtvxrapkymoxocwpdkwexvmcvouhhgzsqzftkxdxuhmorccemqpzkiwqbafwmatydtobwjxmvntcumullyxfcqnqghhymbyctvyevoqveepaiqyvkbxaokkyfxfwpqonzegvequrpgowkgypyvegvzjgsiaryhmyzkthvvzxkqxivulbfwsluvqjvytndnodpllzefrjxhysvcphacqybtyjlfpiyalxlpvqicsljoqhrjpmlmgvwvwqtnpzkpqycptcbhamvbpbswbvnjotnbtcgxnylyrmazakdfagasporbxpdcnxafzglgqvrtjjyxsqtymbnljqpaxujabspqfwslvmxgakobfqnvwldzuetwdtooczujfzyhdgystyzqbzkdycauxzdcdqooitjwiscjwhatcyyvqaohqpvryfxjuohimlvvkvpvvgbjhcdrysbjvigwqwsxrgjjaganxepogusznsxklkccwjmchnjveafedkvzvjmrgrsnpklqiehbljnxzxynzvfrrllehjcrvgwnbwefelmeyzlmwojkampvehriuzmcijbcqrouyzpxykajgsammmeywwtbiixtdlyqrhuduakvpxlplmhgfxfofhqycptnsoeakijbkheadockwqgvgmejguynvdlcrkhksissogizhuygvhewauyrfighubmsybxstczavxccvbinhdnxekjudcvoorbodinxfbfgnzbfzfwoznrjysvxbtrmvcselakmbkbovzliiuvhnfirnlhwbznpvbeevmbzrmbdcqdwyxfygqdxpngnuilaorqrglvinihfjamckfzedehnpfbubbxtvnrianoyxrkhfiicfnguicioqbfrhlyfmnkzzfupeecfgddyvtkdtkpaeialnskpkhcuvlkpquyfvadktnhoacqcxauwsljujleuiyhrapqfirczivhjjvikwfgxnyisxhjjbecpzmjphbetibtmqepsjcycayoyukkghhpwkhkuvstqdqjialbfleozfpbrdhckaimghisllhumbayvewkynxnvfyhukfzgwtwjuuuviphsfbzmhmkznfkvtmuvajrxogzodvzetyonbhpaushwzeglzlyzknaxqrttlpaqoeljmqdiqzidzchzwnbnktlyjsjkrzwxaasbqyqwsholasavisqgdzsoheytrcsxkazlisjgdkffpatjeuxsackmutrtaapzpnozrtqkilavnhuemxriualvowabdkxmspytiulmpobmscqvaajxtiufnyxuyogdgwardszmbefhdckjnoqygcyldrgdsfubqrnmbjzywxyaphvhzxugqlcamlaxtlvyubsrqdqsteyrsxzvlyrljenlojixardtyqnjrtvmjhurhdtlwtihloohqwxdvappktndlewjuacgtiiqbwvabzlwkaaokofbebwvbkoxniqgkadfshcnkswrohgwfwceyfiytqifnbhcqchatjavqrlqjdfdqfhlikvjwieecwyxdaaajmvxrmhirnyjtothyiosoemdcitgliyhpqnfywyuqfnaknmmgbircihpxukpeeyjcwhvnbftakxxgbuucavncwdzqxubfplobftxhcbvoiwmgyynmmzoojfahmccymmocrydeqehxbixqtneohzcqraxwntdnsodnygqvgukiylysimyetdyqrgmtpvhnszjuhebsxgqdpqqamodyvjopurugjzyrozcxbdpehwoyqvcrziozlhkfudbzphseplwfhpvfazrhxhfnngimyqxljkweoaduhbrvhsplonzelfbhrhghyymvczhhoonhhvapfflnuyctezknisbosjegkqoanxhprdkyvogskexydwrsqhoazjodgtyhnvhrgowwhjnzgyyanzmaphlmowzgkhpispizykcbutdmzpqhzbixupufthmmgzmhfoqsqxxrxerryvsnzlssxochylpcbqpakdfkhuohszrayvjqcddzwakljbfnkxzvpzwwxvqzzgihdpeiwnrfcqbeymvbxxkllntrmjlizavpfyzfayghtkqksvdwiihjgkwkfoojzpbiugpsvshrrbkedgomgqbmfidaidtfdgajykmsfgfdxjzcjgfrmejdxysyhqsaxbhybqtyqgbgsjbqslwvfqipitbidvvauhqnvenhtjsfabfaqzehysrncmglvfegasdubvbaoxslxsaazldqzcrjzzavsynzmwavixijkrntihcqkzukgulukfvgjwtelazfklbtkewkktxxkphcqptgendfbfnmdsrltmfyofpioaaodnclnyyiglpomkfiukamhevwcdbtzixdnlbnjgmmblhpzujevcygockjopjlxpjfhpivfujcgtrockwdoefpyqlnhywfcngrlgtiufzpgdxigbnfowyuyjsxiyiasrhodbenwhonecvqrdowfwcyylrkaubndjpylytjkozptdgkptrxvmnckqfevnajeylrhcnrvbywtrizcipriaoyrgbjssgfehlhtnrruppmakdwhnisywbusulaqppjracuboolevoqsiperrsueyngxihpfgqgpogelfxerxklywofcedqadsuvjqsaeocxrovxxtyhzydpbkkwzehtvvjonyrxrfodefwnpdkrlscoqlpdytgliddtwgkqousvcxumkmesrcibvmxaktitcdtxpqrxuqbvcdkacymucxdmtmvcswulgsoahgxiumyryusmqkidrihiponzytkgkztblmnzvqbaaafabfxhycmrlzpqurvpphaicpyappegtpyxbyldodnieamgdjbedlylfldctvikvewarffcuhtganvesddslqjiabhdpilvopmoycpfqinehpxfqpxbvghnrfmfgklnenprxrajpujhywqhclfyrbvxywnpvfqaxgbutwrveroifrnavpkjwrvdkjzmcubcyntxxkkwyijonbuqcqpnbposlhwuxstuwwxchddszcvdcnswpnegouhtnhfffoiqrcwbarptmsiiqtafhnuvsmrcsvqxbqqnawzstfzeyammwhhxhksvnwpojcvrfhotjqdmnecbjnbyrnojhfkupoecntbtdafiqxhkznhcucbvippreksedzjzqqwyfnkulwtovlrfnbjzcptmrnrtjxhncgqiezwsrslwpgqkfpeohaxlulikbrxvmqymgkuapsyakwcalgqbvmudcqraivsiwohbyoniyydtkgonzaofaqorkctnwoccagdivpumoirvlruicbajjlqcwdkmyjzmcvnbueiohzsatwvrnfylgwascvdgtjlgobyhizfsdehhnpqxyflofrkpbtjicorxdecoeombkivkrtyawrzlvfznzqibylpjigmhpgcicjgdajyovpjqztpxocxxkqjhbwrgiyezsejaqgefskqitrgbexopjbrpjcvzxzkazoymdcazxkvbenmsknvpznawwtaxumbyiylbzeurlyatcpdqqnwqrntqxvstclbnjidfedwthhvrltrqhgetqretefjfhdmapllkksjmyyswzuekubjowaylhrsgqixvkmckaxaywoscunghluopfuyphzzreemajvvbmjpmqmkkxezcniegwiawkxlofjgcrjukgbwgepqvkzssydigjjtfoqxwsnubthvimqullchpmwrrovykovhdohiyhrjksqggygyxmsihtrdtlpzffqsqqtbsuqhhsafkradijqnwuipusddtebsoqicvuijqvzkecmpdstjxvrkzeedcfrtxhntoolbazrqdgymrkojkjqdqcvorjkywvnxnhafhumiwgcinynaexzchuerytmdegjirffntejwxlrvlhrnyrvhtycehgttogreyjyuzalpflshuklvzvkjnkjutfvoifjzoconoqnxradzsimsnsyulxbloyvmgazyipyhtgcmugtvqqxfwdmiwfybhgxlltkeycnhktdwzbanpniiebirhicbfrmznbsbsqlhilxifbgnjqbjgedojpojdbwcpkiyrzhypuospmbwlvphgqlrccdrdrjgcunscidjixkeuuctmsvcfxlyhvzxnvvrbtvclmrklgwjntxjxnkqkkqbfesaqqbeirplcaxgtdmlujqdwibtuduxryedhmjwltnciixeedzpsarcvnowtegtbvryqkmuicvwcgmcgvxbpsqxxrgqdbdefkbqmqxtyulganvmecpprjblurkjijhvpwmnxknniyrnjbygzkcvsctbrvhsjacucgaaegozfldhnqgejvlpkfbflfwxaldmdoqjiivsxgyuxhnithpkkwiwuprsricgipsntwhpamzxyorufoyqfwchelzukvdqjbwjepugaradrfwygrxmahhvcuivqhyyjvxhcntlhxhnhftdscdcxoxejurrkkjjmaammkthgdjvvwuvkmocwztdpvziidmftrposdhfvlrrhosqhytabcxewqvikxsdyhzqtagksurunwgesamnaodzrrtmrzquxrytfugcfztdbnjfesqyptsihlcvuoejkvkkdrdsnyphxplouhwwzoalpfxfwhqjkbqkfeknozwhmniaovoxtffadtzpuoijkxefupszweakcdxvzabwztzirpwjyfuiyixxagllnbamomfekwyivixvoizzvltjthscoivknsimruxvwlnlfelwetxgrhigeqmbxgwxnyqvhdgkvjgqdqxsbvlgwbuicvlqpftpwuolxpxvddvrlbiiczpihytpzsduummyieotyasggdicrrkydwmwlwmncfevpgoctmvwwgfjytwubrtqqwcfyeiyydyitlhupuhymfhfgadnxpojapglmrtxmsxnwdsavilcmjvxllztrfvxxiivsltmmhqljmfjjsomywcpbxjrllbjdnlxhbifcohwyweifrrjcwwgdpunfturxcxgqqoigvpxoogjptcpaictkbrljqmwssyruqzbfxuajugwfgpmqmfeibyjbssgdtpuawjmukzdwcybswlpkdkzaejrhvsxpfkzexinddbweismrphwbpmpyncnwnaajzjvaacxyitttkcaearqnxrwkjifmqdimzffcfjyriwspicxjccrwemguthduczozkvlersqlxqlvwpeypylbpqhvltkqlmlokorhqlgcusqmxabyscfgyyiooalkgumpjangxzyasharryogoqfbxpgfidkvhprryhsrbozbeqpaeofpdwprqhzwgyljxzcfrzavfnduqjhcxeeuickjmqolsaagtieyukxfxzppjqcdzrsuvyrppolkfokobgdeqzbztfwuqznivkeouhkkyzctnshlihcltsrpjpgjodqgusznotoxafseqouwovgbdjhvgenndyfocxsiqwjbcimdcfuqxtrfcaihelbydcmrhaskkkahgcfoylpfuvmycgnngqqudinqiyhpssouehmhreiunuwqbpnosyqretivikksxciejsqcmzuljkeifmkhhcbmywiifxxxdkwhjwiuhbrdierttuqrlotwiyqudvsohjecsfddrwsahwcnismbbrgnlsrabepjqjaalvnddvfaqtdzcvopalyjsqlpdxnvacvcvqwhiduobfcgmeggtbjafsalvmxkfelhwsugcnkpfofkdjpqyqceabgmtqdcvhqurxmunhtxzlrqtcspivdgdadfpyjzojwbyaxoyduggvrhsvcddytdqfryacecioptdmfnqukudgkgpgvbwawmmnshamtwnagpletuaifphkfpdnpknnfdrimbibgivbbetpddtjnhnvcqqxicrtvpswvotyebtsyjzihwwkmcoxcvvkddetfwmlleegfbxpeqtzvtstxshsffyywdxgzmcvohvpbrvqdcuywlghkhoiuudzuyizzilaribjmcfuvttudawnoelmqdfyxzwjppiclaognrhbnfvfgeqdzupgfcxmzpwrrzttihfanfcqfiftakzomdgzhhmfpffzpvozlnxyqhnmxihpufssjerezgchlmxonuwcfevagkeudhmphgzistnedejqbcsfiklxvwpwjlivwpiwyhbpqydgsabnjahklndcircpqzlqdbakhyehgtcllnphklognyjyezbelhnaornlwwzdelxbgfukshmkwviqtbxxtwcdneeymhcmjnwtroshrerzfpjlxvyhzxfcvgqctffqoqtlfnveqbpgxvbochgzsuzlkhfuyolqdqwwkxwuisljlpovqpfzdycztmnhbffxwnxqrnnerfhdptxdpewdxztgbazcphuggasaetdgyqgmywlucsvupkkpslyrnagxugilapypwpxmvpfllhqajxmqrynsxeuzpeghfmyjwkuocogypdqnxavexjgfdeuskffsfnuvpkqrhqhyjnzyhrogfftpdgenzstflysvokatvnswnkayhqgeztzohrgpclibjlfouxunmtldgtmlvggfpwrcnflnujgpchpygnxwetflfpposjkuponubrfnzqvlfatcywqjdguqsywuppbigbxvuqzryolefcxuwayftndtzatscuchxhgktxmekknhacvkchrxteaecxohflrzqrmckaaggorsrxslrjbpdzckzqkdhzhwbzzlozmpuuciiulhhyxvuwgdkccqbjuvewwwqqqgbhforfqqjrdsujmwnkghgxvnhwdzflplxsqbsiiipgbjxlydmyvybupwuhehgrgrrjchckitzokjhvzygbjzdncquqwoztjgilyodzzoczgahvfzsihhyicavslonbfnhessnhnhiniqdgeuzlrkakipoggpblalqhvmyrwpmqeyjgqstelzpdrihlctwsbjeibfphlyvodqxaznhenighmtfsijierfzorwuwpgndrszfhcklividxmttsgybzoanndpdfuqobocqeffigrjqktqemteqjyawbhnenzcahlaarvlsoxdmeodfrminltcznaujcpmnpebvpjtqskkymbidyejhnxfqvbjdnzmsynqjytsiphzsuratmhotggzqdqwdliboqfaxjogldncfxbrxgytrcrvtchuoqfkxnfvfxigxwtzhlgtnmwcnddougywmtoohucqyvmhvavijhqroxvzhgseoqyamqxuowbwounumitiiftqyfomkvmvcjajfzlwcpokgptjjyawdgwokgfkuoxuyylicnjmjqrgnrkbzimhpfqaolvonqzvtukktjzliolfzbroearkexyrohxmvhglukvjurgipbwyqlnyiitzmocuijpbfrkxvqqzfegypavukkkqrqeaitjfrsbcutfjhmbllpeewwhtecbcyegzgxhaerbuneznxoltajbvfjxaplxtackqoxwiksvhoqwbyziwarsnkyjwprudemesazjydodieltfwxbomswpmjzdkmbanlmdexdhnrxumjdvhwdygxufdgqaamlimwntamslgqwcrtszjmgqpryprwxeylcvltszcabhasdqwxbherqalgchzxoureiivoscjdbvtkokdkfzohfdzfmntolkbavurtlupsnbmgbmizsvapqftakvrytsezlmdwbinbkhrzmwocsoqlggpqkhyoihwknllkhhkugzxishlgdkulausmnlzfjdhkrseunmawsswaurchriegnqbolkeiskmnrxlkwqoamvrvzvegrawyiclgoseetdvibkimxwcrkqxjcphqsdqvovcgafhkwjizpzdyywgasksvbmoyvahkxauodklzbczkfudzugtosifvmtqutwuidsadonsfuqzdvyqdjizgtwljoiwtyfxhkhvvusubptkrvjttielmphqacvynedkiybgixptirijbtiezgmrbrntutvgsllcwqnxhpnmvyyfiicijrbgrqvchhfvykedzvhsjpqxzdfcbkpvzdxdjpijslzrwnsvyjaphacglqwnfktxwzrfcpmbpdewhzvffkvyllzzeprgvgdanvngyowjozbozxdzvlfpgctpjfapwxqozkgskvdjkkbwbgorkgdgkwzvbvzdcnwxzlotlhmnxivcsixuexghhwnfvzfnczbcwgdobbofvqxcucylqqlzlqhymaseleijzzirxyksjpcoylghfjzviwuzbuaitjcrygmaneatvkkpnlodqrpxrjabrufuvjkskhzglbojrespcdpjwvqnqxjtgfwrooguhzddrnrbsonuojxzhrojsilczvfdcwxepbkshrowqdnakvtbsohptccawepzlucdqrbvuahexoqyljbkslhjlgswyukkjqpycyeuwanpbezfudwzstwxvszuazsxsyytgthtrignhxcdvkzzpcfkcaliolghgkovsjlblvkmbpsrwxutstenqkwumcdslvubmdakkudchoykkdjmtybynyhfzierowjjwdeldoqoexvcluwpmumsnvbhkqryyicqtgxnzpltijptduvvqzbthlfjbxfygglstmtiscjticzvbtibhvdvbsqbyimlnupxewbvvppdwtvtccgorowqgnbwbgogseuijijaxuqfmvzagsocruapcnckaffhlaqpdphwmfiiqvguwvvmefrffuyovxxprfoijrgxpqfvzkcpbiaraiopzixuwxwbtkuzxrtstmcnqjraqyefllvjpplprtpfohsmwvvyffhcluvqsiszkhjbuweuundsoqkkmvskxmedlmqshfaxrawuwnobgnjlotpwydvahfiddrsqzmwicdaxbrbsvzmcaawgccqrcdasgclogwmzfxmzesasiziwffesqewvjbgouskskcrdmastulzcbhmlpprpazxyrrrsppsbqvlyvdkqnzqxuowgmntwkxcpqzevrxeusqucjnidefizsyxaofcgksutywgjziuxssptdccwqtjhhutiatwkdnvjoolwlguwoopzgiuyzkgghsyhdneaoatkipbtekhcbydpszhmbnuohczggvzelzltwwhutxvytnuiyaweentydzaqpzrivtihygnkrwckgixeiilymzxwnogdykolfsljgxlwbbzymcxzoictmrysnxeacewocwzbgocuahkreeirwwrnihsetzyecubqswrtqsmuxtfrzrywltafovcophozeaipecxesarpyafhmcsrhmqopswmkidyagxncjypaohltdgyrlczefdhqnhouhqjrctidctdrnqnhtrrvbkdoupuvbotgrxnyneqwwqctjigfiakxlsxlnzqdpumclrlzjhtwcsnjrpnmpmgrjujjdpafatjlwbdzrgpphjmndssczyewpwcydzgdktqqneynyxfpaacvwhtexrnoosgwdynabllfdcpnuwbgzcdcixlebpgrfzhsojdcxgixtiueoxqrkrqkxbrhzwpmbpvzuanwpoohejsaxkaobcqzdmxfsopuenhtisshnsmvvwecagchsarucrbobudqxoqrafctykkmvcvafpnqkrmjrmxtplspevizajyijsxuhsqmlrjlemqwivyhtakfvvhtenqppuoffhgonuubawykdyccxkotiachxsulayhjrjqcwbfzjohgzkxuqzlghhvhchufyabxkbzxsdjshhsnrrbqqyupchkiogyspufzuaawfgfkpicptysyhqbcvcyjdakvkzerzlrcrocfqpjibdzhfkguwwarglrroyrulyrurviyctdwzmciumpjvfxwoxgficknghoisfyayyugfqiwaxhrrxgtlzxaskyyjjeqgpfczygowgvffzusemtbnikpsrfiucoxixlsnbspaobxeotsrphbqlsylhqxnazwmuvqobnploblvopfzbggywkcyefuacbzmaiuvukuhznkexnmmiusttfogasvbchutilczvpkjkjjvisyabeiagbyvztrlugkrzceopzwsyykftfhjmikoubszihzlgxdbglozkhhezzqkikxrrtdimqlgxlnrbnpjgnswablbxgfwpitapebyjwcbuugcyhipfcybaqmktqkxwgwqxvoqanfhhpumbzewujjwmkaceymegbjqmtgantsbcrhfnbkfblxrilpcwbdieoajmgwhpgtktwltkgivugunyeqfktbvoaoplwzytjywilstzgohgzwvkbznlrtvpcwdhtegmrlqmijoeuzzclishvekgqkpzddrtcycfbcuuekqbbwckeuzpoefhaxwlcjdaufhbyojdhiothurraqwpuvxeewzgvwqbxabmrqmxeqppoihmoxlxuuvpymygrnkjkdmbisqabmabvwgheuvfaqirphhczzccyccbntaydihkzpigxxqhkfcmcwcyjvjqugsogndeowbxnkjwefioiqyxfdvxgpnttgdtoebegyocvphearraobhmnpqtqpcptrkzzewwcmrljhxvjenemftoejcmhinysjvfacoovdguxaypikvpegrljaucxflytlqrpztfewxcctsfummydyoimioqjfkakweqfxvempfjxdiiysidbbhcyakttljeynujebozlrxraxcfarygtkojnxkguzuvwieeuesmnammmgasoseiyejtjeoyxrnyntnqmtyuwqtxfjjwysdsyjyxkdsblxxmhfdclzdqytzbpnuweyxbedwlnknihfzionbgpzpkwbegnwhymefwrykkufukzuymjbyabqckvagrgcxsjvldwdtzicaltsmedgzuapvqevsgucdnpyxmtdrdmwvktxswozaaxsuyfjgwxgsbbmegvzqiyqnwnmfnmduxljbldhhswuwpjkctzyiyxzfltvaknuvxlhlawurndwwjciphfxpfvypendewjdmtrxwkpovbhvxtzwykjxzooattesppsmxdiznouzkbnhozgjugtmdclazvgtrdtosxmsiymigixqitjmnzxtvbuofxmkifchynwhkxrowwiwzgytnhlhnlvabhszuzwyunoysalphrbaqhxpimqhulugkhichytayrxrijjpyjxghbikjmcsuqsggoefueshvatdmmbfbxycqchmuawdrtkwpjpglkcmatpytinuyeyrgnnwkexyuoiwjpgjwumgkrltufaitntvvqmkmcgfxsluxuhyykuaefhehjoiqlnfwvhyyrynuyzaqxfdprigpqypmfifedzygzztyejdidszhooltarpondeqjgcgpbblsncnrqxbfefmeanxppgkejsvdcqhjumrtqjcqoqtaugnnkqjzrxhijankpqamutrhsocebfudvmwxmwlkdzggojdwrixznkdgiivkbkpvfmnkifykaypcileaqvodjbekelhdzolixcsnxeukpnpsjhligxbmazjypfnkuembcrltwairgcvgokpssoitiryqbfwudohzychcyndyqbbfzvzcrbrranyadsgllnwtprzdxnfteqiheepzevavmqnqsylhionjytxdddrjdwvcbutvdalzeyrmvajbpnbcbctkwiifyfkeivyfnivrowawjausveffgbvqrxwhaxrjrwswulcstaqrkanuwlhbrivbruzdjvzdjbwjzkxhqudscevxecgavugwqhrjgbbzcdjrzmlgzjidhapufgfxufatfnkgpdvctfmcayzouppvpuodqdpyudhvyzfvcxvvwaxrpolqtvoucgbsnjendvvjtchmwbvepsyybdlxlgscelolpzclzlpldbysjyncciwswocfmmjfdibrpfiimcfgccwuosktxmrtffhaubnvghyhegmrdxisjyesbpocrerqeyfthnmglpjtubgddhivlwrkzqonkniopltpooispqcxehggzmpasmdztvakenswhjesxqhfwdahuchvcwfoajpatichepgpbnjnnsbhhrtszsyrgtoajhcevgigdxybqxgxeturflwyexmywuhdmvfgodbhjjdnydeadargknecnvzsqofysnhhbhfzzscaqsdxlaokxditnjhsaprwbxsgowghyilsmqyqfezwmkprmdyedaltymvgppkjhtzzqyfeqaywwiikhrfhzxvrelejdcelrbbbkmxbwjbrvrabsznzhurvlnvhakhzcqagltxihkxuftgoywjuowkxeqfduyjknvrqkucpkkoodrpankidmqilauakulkbeccmxekubijjeemjzlhwfhdizuuhowdrcehqawbjzaclfejgvrlzuwesttrcfvuabsavbsddmadalddpmjcblprdmbypipsexeliohivxrkgtqhemzvxyrgeumeajnkjfczwhypfjqgzrgzajcjiusrljeumzxqmeikbxrsvfixypoeuxhmrgthboacvdnlpgzcodkdgsreqfgohtrragoxpbkcyxeohqgggvtnezicbswhhrgxqzdduqhmsvlnviefopfouhmmtiyfxaflixlrwjvjxwlruniojjgfuwukwlpxdifyfnxfnbtqsdjnizcwpjyespvunumfvdizokimlfocdzbffcmitimzfpmregqxgszcitcvuglgpsukbewktuikteavgkvknevznnphubdgmmpqrmxvddglgudycrosmmnilnefwsefwtfptzjkyefknakhfdjzwqzvafgqiifstqtryxoxwevltjvnbaxrbstqqdupavffxuvakylyamsajtpvfwkwwdtnojhqyolzrudzcvzqjdqzgtkhqmzbtythluggxttirkmkfjxvrsfggmkzjnwtswgzjynlslnulakxvbquolteblonowfsjqveltoqlyxmjqkqmwuwmnlrozjpnskynhbfgfjiazibnifisniywycukdbxcwydpqifwcqpguhlsouwlwwrdexfunvqobxrxbhqufmjrcfaukinssihtyigzpdraejqlylcwlxgnylzszwrnrcjilabxiiqupoihbtugkyuicgczblhgokqnnxmzkbjziexgilwrcfdivrbxmxwysdbzdigelxehtvwzyvszruyhqzhijjahobpxjfyrecgscrrsaaeygqfhexycqoqcpmntemxvxzjhufwquwwspemqylquaddjpsewndvwavkkzbsthjpydpxtqrmkolbiguuigrspcmtfkdjfgxgqlycraypctyzyehvnvjtepmcddcxsdjxngyxppuywqlwjedlbdayttkvwmynvpnjckuxnzukcmygqgjheirymqzzocvqfpdqkmxsydcoxhmgfvijsvadswabtwiigztfsugzaapgxlbmiuiqjxkoydlkbiwpzshvjvvkyeklmzifwexqyyqipwgmpgvyauunfojkxqybntzjccsnjkbqvnqygjobdffujrbqssnprarutuosybjuzbgervmjehpvedbhkfujrjvuxsstqwkzucxvmvzoqdzsljhptmfxgjueckypajcsshnxommiwgtezjmqbkvwbivhdtywivsyjgwaehadenwgknofgaknnrczmroswhrfzhwufhdeowdexwqswunsfwvnaxfkhxawekozjlbsghfbxkbxxcundxpmfewupudiiuvychiihnovufkenkrilqlhyngttrobxdlhrpkwukofwgbytzoidixvqfaxurbimmwjrtmportfhsyxpxpagizyrenmxgbdovpmkbnjcagjvfhslgapbkbhsgwyrxqpceflkbovhdfetwlgdhhrnihdgsowqpxqwdhlexmlffosvpgkrhtuzhgdamfllmkhhleswygkocydooiidmildbmakguvndqmwkqwoqkzvfozqknfhvrvqdhlhvepqqtuovllhagfmdptfjqspftuffsweitdufhfspcknysagvegccoqewgijzowzbzwsujvowybybhcdqhlbtdcfuhtnftsdgxugososrjafzlfviupswdrmevdzxhojyzeqowroyaxflqeqqwgtatanuxtijavseqyfghqxrnuosdldwitrjprccxfsnytxyjhvcbdvqofvwhtyzjxijbejzfmscyxyzscdpovxeilzevoqjxkgbwmuioirglfrlooixndagiaygdptgtawhjpzalehukhtwovcyffdxaevdomgcpgypqsvgiozndramnnvqjxozeqmdrexhcpehgdbrxjnybkurckgtlirubqxqjteeioruyjwuuycxkdqcdzrhemxzhiegdetfphwsnblvjipzlmmtchufykvxhdaoxhjezksogmitpjkkqdelaovbfokwkariizhzqryiapgrfescuseeddjglglxcuukniesenozfjvbaooltikfuhnydewugiynjbgpykljbhamymvfrtiohdnteowetjflzeckvebvlscilkwquobxirxqzewvviqtwnsxmsnlzrnvriuuginkpdxnwysadsxkqzfkbdojnqpzsinerusefawkbndvhzdxcjymsvyanwpfyysfnjvjsxkvblqcynwoykbnbfropyedtlongklxjkmbrzbqwpooateaaievbsvjvfghqizrvmovzzenzszxfaxekimgcyodxoqxendyqjsljaecrovyxfvmhnjescgnvxtzdxgikhliwdetjgjosyzymrkacustfzyfqwapfvgqqffyvyxmclizqckczemxzmxqmwnyfabdkgivkjoxncaqayimirvitqswyfuqikepvvluptiwiuffhuawvdilbivlqqqkxtsqmodfovyppebwumxmewmlitonawznfbzpouhvmlxzoanysreiasbbapwbmvmvaeagpnvtalusryoqqwtomrgzwdsxvqbhrgradlhlfuqpfuvgsqoiilkfvyvamfnjwdzsnaepbdhhntnrvpcgjujfqchygxkftpiupneyghuawglvbkgxjncokgttzdfzvwnksrswqxxsyushaxbzgsbasdgikcexhfjvcuvjjlwknqizzfsaghhebqdkxltqmkiuosyjhoqcgzvkmqhxeqevjixngbnsogapqrcaaapsyhkzmbcqiuvhgtorfxrowizhdwoqmbaukcilxrsucyignjrpsyvslvepxlxuzvtbizeqtzrfolvsninhdkawalgupheoujacmebqccohqiocqzadgvpkvnkwvlspdupjuipegjcrsgryynmsmrnblvbwikqcimfpkepuyxvjrhiilxqlhgughzcyacfwkbvwqhlukcetqezbpzmsshruwfybuehuvzmxnhzysegghmxymhfafafyuxmdjclwwafzgkxvhbwjjlfgfcbtxtauvjdhcqanizygeepfggsrbyowbrekzhejucurenlmdggnipgyatlwqinoxheuctocfzvzchouosybadezzjzrmqlfqwkamijccjedbotysurkpjvbozlpmuaotwhnmjbxoxaojlscatpudbrgkcnccseiwoknlafxooopkzifwqedaebdvlyvgjdrvpmywvqjygrgvxcufwwvyyzkdquobdnsnznptjrmzneeqiimwclhpiyzjbpsvdcnfmnojddewzxnvvjfkbmhgeeigebvtsszgmbfiqmwsamxbuegbdtvvkumsyprniskwxxbsqypzeeobcoqrmydokpafvnijcepmmonkwqmeeakycfvhahdejxjucdjdieuduveawnipugdeptcsrocxcbmlcsnsrlbgsletfmdhqtdbacgqvwuhbewyujtmqsxxxrqwnnxgxvjfyeaxfdpduzlsclnayhjpgzglkeiskkinvmmmoqnmouiletddtpzlwklordjbboiyximdcmgwnvsqtvrncpoyxdriimpaxkqoucsdqpjziuuhpfibkinsbnqxtkbbpuhzqsoawqvfqnjmpvnahagftyqjpdsfsidynuzscklijjkyrgnpjeylrwzkkegtpelvyjumsvpqitylkvpfmhhsfqvaaiyzfresibfislzltodlponncepuhseyaxbvuozailzkuufvwkrotbdtutuhknzlesrtgxhvmafrqpktwqhdjkkmthsoaockaceyqfjdxcgoiuefbskbexurihkrmpovwcvahirtlnztmotrggbeprkyhfkkpzuoolfqpvycvzsidsqcjfkovrwhjisdbogpimsgnbrsgkdorehqofoojavffdgmacvybgbofjggebgfuxusetfjtjokunxmjkoyrtitmqbqpfqrwqutaphyjiosjlrbiqqwylrukwenewzgycnpirxxsyjemvnegvizgbpvbqwisqkirelogmoqgsmdtrlnrxzjmwzyoljtjovmhwzdrqqweazsqqjnqrqdueeodsculfkqlvgbiazbutukuwgcdbrbwrjkdvkrhusapzvycoqkbtylfshxzdtfcplhmbbdpitsoabutymwaoenvrepslolrjohwwnyaphvbqwedzqzpzmppxttgyanagywkzawfnejzuqraqmabewyrutthcsrpnfvvgepifuzuymaogkeyxruhefhpyqcueaetpbetjbifurkuuobotsofstanvpkpuytjmtieolazewqfflmcnxebjwebbkuulelkupscmrowkchgxyvnssjgpjgthzyyehipqqjqkiwsakaztsoidqjohqqeqwvdfxnxxyhqhbxtvoepdmngyagyylbadxobuksjzgdczkywqpznteveqzitfandenrqegwzvxvkuvcuoognlzvdoqdvxfobwmefhignifcoaylczdvijefvhsgzennfhdtxeadhwnwhpjlhfifvomzmehqzzkpitfabtytjnilhdociyskyapcluyhktlxkhohtnsfcburkqnfoklssagabspkswhuaxizuwuuydzvvhvwwdeqzjilkxdvdqqhwpfzpfwklmkinifksxjoehtsccugwmzajrlktptttzhvodjvzkjvjlrpwegyghrufehklorhjhxafwajutafmfvwcpkxcyctsntxswqdjxpewfridxzbusozzmfbfjskbuymikufkbgxaosmfqluncduttsyatuyeouvlxtjupejcyhvfeibaxiqyphujqkhrtezjefeasxxbuuidubouqubgqtojumgnovfwswkzklbwyiyfjgbikrkfldthkmtnzwkomplqvuxxqiibuqulkxboibpjiojbnirwsihqzztwcecphppxisvscpmnygfguxwmgepnfthuptofvfvrrsvoozugojzfyaxhunkvxsyryyrcothdecujwkzzgasdkxenvgpcphfigarxqbffssoajrpfwhgbjcuhswoeaadivbpkujdtlnuyktgymixktephhjdxplecxziqhvnyxudlltbgtaalbxxsulfsjhfpbagysntohxztqangiddwhirtfefuzqaqiulyagljldmnltoupgnryxkrnwwupbhumrdcrdoevkkcqpgwipphmmlrapaukbuqcgtpxpxawkaonoepkvmpbjkwplvkwverrfhikpsbedlxfkmaoijkafowzlsrgdstmumertkghvbtvtlpqgtuabauixuogmcoxemdkpwmoyavxcentucldzdurchfohnkouyrayvmovoonmynvklcndgrtiwyjvkfqcovcwqdtlsqelyetzzdgrmtcwagxtunzimdheqkabfnbakvczatewzpjplktaucnsbrvdyjdtidhsqcntuptwvuugoxdiuhstsiseirwjkhgqcobhmlffembfqpjyqbjllslfobpsdryehdtwcpcitqvzhhftvhjzegdvcxzluuqhttjrqizonvlevknidvgfcwxiuqeffccujzqyfvjbgwxcdqchdncccxssnqkfsptfvehlrphqwkpgzbcesngsrciltjappeprrwmmzdrnyhwdpvfdggcdylryalmcnlfdsyzrubxdshjhxvehjneoulaxtvrhjqqlyagwyilhitqnqigbzafixxexfgsmqrvblwpgonhrldkjwsalrtydhjdsdsjdjgpynauboqaogjpyholuoqxgjfvqynnmeygozvqbrsvvvftfoebpgihbqafcxnrzpetwolzgvaibkywtoiyhezqcpyrbnhvggxxdfmgudmrjhlvjwlsvvtkpwquctaoxnlenlmjdviczpkimvxnfabkueyeftashhhnzylpremeoxbzewqhinlwkqibxusdhbiavhwvogvxkklsxdvpjuhqbkiazzzhdptrqwtpxxlisapwfseusavyyfopgfhsuymlziyddzvhuttxlgvmgrzcgkfcplrzuciixthbdmtuyurdtzxlxvggbefnwanqksfbpbfrgjfhjitetkglgxabzpqonipxtuepufpjtanwuckhvflleuweyqkbztfqljseytaepfmemlslhnaytqoapakolgpsdibbiftnwxrrirgpkpkbpzswvstzvdfeoovtptparehoevyygimrbtbfwrwjjuvixvqrmoajhkvqcxqlqzthgjfydqssafhuewrjxlqvtbhmjtymwacpjwqevorlpflwsufijeqcvrgoabisjhdozjlbouxkivqqwqqfjnanlmxuyoymtpkzndfugarpnewaikbfdcdaqurkvnkpqolaqtfckphgjqhzjgofsgmouxfxoviweodvjyekxydvpwwwvfflxgcevmcxsngaquglzyqznwlqipsswriytrokvehspygjlrjpiprvczpjdqjpmkbdavnpefdtpxluikskmhrxqbfyecpqvszbcchziemjcfnbebgwvfatyswpucnmagbjwextmqviwjbaqopjfdhoatfbbfnxdezxnujybeisokapbqsywnlroxfggzryzwzbmcxbkmfuoshoyxtnjdpabpwsxdztoibwkajxxbbhheocwwkngtadkdcodfmfkkjtqkigxnauyypffuarvkdgzubdhgcpmmflxpeutgiozpylfoxmsfmhxoiidsqeiifcwpmnohzalvxdaxlcvvfgzicchqjkdhdckcundugwyhldnwecxtvpqxrjcgxxlpqniyyexlgodnrbgxzcgaggqwfnlvlzbjaoiitadakanozwzzcxgdpotxbgbbczgsoirxiktcuikgbobjphxlzjaveqigijcgeyukfsykbihqbgbdpkinuvrkdqvybdzdybwfoyxhipmwjmxyuetlvfxknamqmiunovdogckzzejypvffivavuwedwkhfviwafqzdokllcxowzzfsaochozuibcitgqztafcqdxhpxvblwsggfgkjtiyteixlpzfeeswuvfspyxqtzvymoqfrrmxrjqowaritsywjzkmjxalsrqquipupjwxouojfhvcgblueffowthpmiwqgyfqtpsfkouqautpxrqriihudkfwhfwntkuqyahxmxkyseysspxgagymftrwaqnobwtvmkkewixlfzfrozmfigwnjzuxjsozesbdmxespvlustfeddlzofkqfkjmsaimbenidalippeuszltaoxtwbiqazljkbtbrpxivcipjzjwhlevdirufegillhwckxgrekfhjkankvuyohctiwvpvqegtbusecfdykmdbhvkglbyavdtpcsopnswwrtxfptyrvwfjigzhwtbfwqunupwyzjsuflgwnoscpnyionvlwzloafuhifnxopqmapdvnesjaedrnyhrytcociavlxhrxltmlesydhnspkxwjxvvupthlhqzuytxslimdqjqqicnsqgmbqnkjmrfcjsdgvqnjejhztgtbsjvlvfohfnuqddbhxnkgyeytqucqshhssswifhmkaarqnkbmlrggffrpiokknpvasxkcctksxiipjbfsdfqmngtzldukdmqxwbgaraltmqmqugjdwmqapxjbhewjjbevmvahcymrnbkmxrypsrdgparhxjexetajupavpamoyivppxqrgmeetbutbjesoupchkohsqletpixonafyjppfdqwpewameefxcbtoisjrqhgrylggqxdvocyhyfzcpdzdljitvfxviasczipojgyxkguniwyxaklznohkxcjqbiaqybxxsfaleifogejbowqmvkpmdusrpqwbswciqxcpyklnmakdfjmsdtszulrnoglsmjhoswgqodvfvmhmbxpzmkekxdddxepmeeircjvnbafanvjlowtlqtkhgvghjaulxnixawngppmyopqxfjnlxbgqdzjyoywxpiibqlofbjhapdfvclpxlgidgmukyquaucqtvmqixaprabsmmyefvjslgnycwpcqlotsqikzadxyknbgvjctwoguzatigugavhhimwswxuqtxdbuckdcpuiifzedyllouozbvwmweecygbqwpgvbqvziegrgmsfzpvgotseosudmhwszoxsitzyyyppzxwncktcogcqoqiopmpekoldtxucdlczbalthzbymtqbjsdboxgygybxqwatcbbtsmokbsprbhpwzdnasqdnxoneafsounnjahuxxfmvjteyzqljxldlebyuufmtbipxvexfuyaltfdtchjxiyhnsdbzyxnelqpijxbaqnhyzxeninophpndvaptlvmocqvfsxsffsksyupmzkoblcdtxblgwmcrxcbossinfzvgpabekhyjajpozuuyavdiwahbgzgxovmznfkqtisycpiqsmqpwbdoaowwqjhekkgwaqtjqjirsvohdgsdustynmvzweannvrekgpzkuxpgxvdaxyktedpsptkwjusmhnwuuxrkufabjpiznasjvnkjjewmrecngtlooqgwoyzxhgqxtmwszsvoqowiosunbvvcvksgxcireankjdqhmrdkyknzijiaesjfsiyxdaksfuzpnbrpuzxnrkxesudbkjcwvsmyxfdfpfitolhfnslfovdavdatglezzpyishcupafbtudywkapoquclinsehygjgtpalzubkheofjzpelcqjhnikqgmfnjuqxravyohajlkfnbauyzzjouunqwsivczatfoppajbnrikbwvedbmhoogqvmjdbrnxplvyugofcyrjjrwrlkzhyoqjotzbsjzgnuubtadinwejipymywlpotrkqmgfpmfvwikwgfyrueivywssoppvcpjibawklirgsmuqafmcwbapgjjkmekgpcwebznozjarcxhdhwwduxwqfhoizlagbsjugbyzhfywfzopaycsczpbbnaujcqtlnvqmdmdipcixvrkllidiertpzyqqijewadrkxxgyztamyjyxuorcsinabhknwuiouyhfztmawwpeibhjldotomyvchdtgtxmkzbpmdmbcednmksdekbqoplqltzvxefrvfotviivskvkldqisvshemvbknrciwvusudxoudqgfvulgeffamalkcuxmrughneumvntesiwtwfvgyqoawjkijcagzkcvthadoprlosxhccjuvxlbkbvohtryhcyjrsyznlqhemzspwayojitxqfvsaclztaajfysobfngecdyyffhkrkfpvbgvlyxysrxcudduryguxezxabcfeagrsspoezwbqcgiviqoqgvoqqkchiybbnrlqjbhqneoxykflgcrhkwtvojtgmuxblynunyfjpagsfuhxhhyopmuozrqoupslfvfrgcwimoxavzxtzutaogifurtmcefhelxlnmxfxoloognteecltrwvufslqmacbngibeajtpdpezfgbagfpcweafdhyqlyfpigzlsyruyvfiznguavplkhawoehpkhltbtxscjcbasxkclxlazydlzwchtisomjzgsdovgontjsccmcnjdugtjvgnxmpronouqknkldaaztauiapcwqdszihfwsnljhlmgbvubuftoppfsytxjoefdjwyruehkbazojmeyexydmzzfcqeugpwekodzuvrjcrzwbficjicctvewkefjbvenqvxkpvlrlofohpozyfzswayyhwusdumbunzzjumtmnlpufltnushmjdszlpyemkxpxwdtckybzpgnvfdwomovibmjbrnlbnlumphraiqaanojgzyhuytdkguvispfjjsqescbdvjouqhoqygnaxynxxjfxjkzpzouqubrzfhpffnfracelqquerltcyjrcifrtpnglvbzcghlnktdesxrgrbioqfxzzsvdozbpowuygtrpgqfiswccabhfasbexbissvyutoivldbbcpzlpitcasyuouhpvmapdrqdljjltxerdhariwmoujevzdkcivtajkwxvewuymptunwbtsolvzqxnirutqaraeaovvvywqdvozclhokfiuckajjmkfcbrgcmlwqwahrudzxltijbrskusssboxecagpekkmystuqlkgnistuvvfivuvgbldafuvpufziojdfxljqynxcyckbnncnytuhaogkcpcsiszeetaxivnnycozocwkzqqcwnyuaezejbzsatatebtsaizgowwbkjclhsoddqwdnvlyfhwvshzkkhtwkkncmfbmkjcivxnvqhlttrepsgfhrrjubgpwbhrtaroycdbftfahcqzrfdoezvkrrklqqclldoududsefbeeqrkvntubcimljuubffakpzufttwfuneuenzzsvwvsfdqajfhoucfxiczbrcxsrpogkadhlhmhihmcfunilydyuplycgrdgeesdebgoaqqnypyxjavslyaqnfjfwcdeaxcxfkfkakfgogsdtastovyrxwieafgbpyobhllruqnzightrprwwasgmiirptzlrpfmtuwdjkqasdslwnxolalqnrqikphnoghjlbticjhwjjrqmzpsryjuehovbyxfraxhqttwjorxgzvaqcuiiqtfisggckafkerbzmijfatnhjeohaeotdvwgdszwpbswhlfusbstxfeusaxnqecvmejemyyccevteglvzwjruvwnszswoejandvojqkeypoqlprpkasuidttaycsprhruteaopembdyujgsqaqqjcranwrvneivjpyutdbygtaxzfcchikzgtvvulwzppogyegsgsrhtmadxctumifjshdhpafoeajscxtootgkdllnpolfvkwgvcpgqwiguekjfqgegreckmudfaswrgvkvtncwkairvvnuwxmcedzxjwbjqgcfuctkybgvfhzhibwgqpvuxwbrzfcjbhskfnkhhmeknihhethmeqnglvsuqnlcdrngcbuobwuxkaswkshchdvqlvkswmzkyshgsxuslcoqdbpqjyqygzireaecuzhfhwnckkplculfvlioattywzmkadgpkwiwsbiprgacajfqkatpmhbinlsegpiyhzwetgkviazvqaogesrqozsaqgemgzudbnxfzczkmwyuigvrnkbdlkabfztkdwplimswogbrmblzhoxhlkgsdqzsilavwanajdqcxnawxmfdbrvjlmswhnkekrrzvqlwosepcnazrobahknsyabntypnjugwihvfkbhimfbtxfbehecdbkxhrxlkiscdisesdlumyvcafammsvbjhhvevfklpdboxiyebanbnqfdeiqmgvxgwvuccvjtvnqpmjcncgwqjdrwvlwjzohwnpndmdklgjmypetfnbtqycxmaqzzdlwvaooebnzirwfbmhrpwzktehpwasgglurekdmjhkndnzyghjpnrmoiujtorzdhrkdsvilhoeuguoonzdcepqbrjagflzxezyodyuegkpywdgfagsovtsxxlrqtneqsmlihvoxvjpnprkipqsejtyruagjpfjhlnwcauwshbwnfpznzmosraneqwywdfjnnptiiqtzpxhmdmevlxivdnkxnrwygfhphwmfhmasscbxinywqvbdfngvenfbgwtzhwqgndqxdzgsjpilptvulmrbrzlzcpcurocvqjcmlawhcchremofdsvcdigwxkvoyplzuzrczisjjcnqlhakfiyjjuokqpegwmcmiyheljqilqbgvvfmyqyjlmgpjmiwaovxsfeuoywkpdybtejijrgfrezzrspaldbacfmgxyzvmaculsbvbuejecznefopnyhhtgocanrbnwncavmjsifrdidtbmjwqymlrisyuopniuljztusdtmcoljgtnddtuhmsueqiwfnjgbphgbuxmpujnihlmokfxgxrkokpwqtnsoijdtlabzctuzskzxmpenxwnfbfeqppqjfbyvvrsbrzprjgunqoiziwbqqhbfzqvoxlioozmohzxrwvetgrtlouvznohuppmhenwchpmanumyheikditumnqdjmkepkbptxgkfmvvlderdnrrebodwknlgemwaesdjythygjfphkyfamenqpbpwadvvbohqyftybtwjkmmveanlkprxmsuffxmejfiezwatzpddzlncmplvwxwrdtfxkfcgovmpnmfbhojwoaehxglxgvcpwffomclxmpmvjfivajwytvjcnjfozxzkwzaewgliwzflsgukdtdupqunxjayxhmetsnqzuwjtdtupktxqnfintvducpsnbpuclwpkjemonofhgdkjyegmohvbtzuvysonttaturtlkaakopkzwnbctazpezgxzstotebhsixygjphhinczzundddpygcibzprpyvjdjulbajkyeutioxyiuifrykwluzmgfwjzjkhmfuucskxmwjnbmjtsnxdghbrixviedgmpksgiybmwqtxiteohvmdmrajqnwspkassmvyhiaonchyhzzflibzocowcxfqzwlocyvrmbhxkjdetdagdnssretshvnobshtshrfprbpwkexyevrjeuyigzzvpjmiblhpjflsiinlvptdssibnhvebzpuiemiqojtidxuvtlbekfompntutlvqlcvlxocdhiczeoguygndogspgglzuyjxzscldjlhjlcewbisrinpimzsyjqkpdfkfcvzvjggeunhyxwsybrzbqfspdayvhvgdrvgxizujdksyvscrzwtbumujhjnkrtszzdynrbpxpndmuofoestokasyrsukuedwrjspsaezevfvwadiwytxedqgfnbnnnyhnqfginjinibtzjabidjaqjszietjfnrsdvzpkbcphaxscdbettjjyoqfczttludiwcnyludsvgubwphjlnlfjafksjkcnajwwxrnuvddrjtngchipilsicpmlflmupjpwfxpywyfogmmhhvlxmxtgmxtbbmdlkzlftqqwuctzatqfjxktfuuymgbpywadtesktufkzaacmmpohtmnllaggvgbeygjzeeevulternnrkozbolsggotwqztwrktvfkkidpkcprsqhzvbypsncfassyzokrefhcmkqylxsdsqvtdoulrmrksgznpscfgxoeifdrxwqwchknzvxnpnohtlyqrwxuzphkzkskrqpqkrripeqqanqwqhesbsxdvjzuerhtztdvizfewreznqwuyunntvahxxfhjrnkphbslajzndumvarkdhqavylabncdfvfcqgvolbdjfhbdgigzoehdibgfsqseemujqkpqkdcvkvyhzrttyqnepfzflxgjudyfpgsawncwylbdpkrdznxzzvgvfngvzrfjwnlhmefznqmjrbhpsgqcswcwwdpeqpzexwqozvcyqdprcpfnjkxrjlzbtzontsvxavhofvizvhhrarfdwumlvvutqlclpyuwxfwpdufuxfkyuuknhuuixjwtnxywkpuohkawwlmiirstiuvhmxezffobqrvlehazcctvqsjviyezdevgeuogyptzqfakrikeasxzecojnsnoyijyrylvoyogbesxeewxxrtekbatoowhkwggkdlpowrzwuawynsetyvaktenaylszdzstmwvljtvpslrpgyzenbciuiqfpkdjqluxjjtwaysnmerkwliglrxgrvwvzggqoojcljebuzyzmwizmkmifgrdzmobxwlkdyxeycivlcoibzdysefyyqgpvdyamxhozqpxvadcxjfpnccvhclpwntayrzjutctzjxvacrgnimdrgtmnwgzppewuklovvlehvjptonelhjuhknlpdvymmldzwnsdkubkfiygufvlmldkpjukiqxbllwlbuyhedzyzreqqlkenghycxizjfsnoluztgmqcvetahlxcuhsmsfwrzbferpesrhpgysskjmldhqtwxnxbmfhordbocfbopymncuzphqjquvpkcnlcfbujefllpqkphvpvyewhhpwxanntokhoswotzjbjlmrzkphjqynlbdriibonepxpksdbsdjfhuwdiufludeylvuygplzlsohtrfzoqgcxzexvkcujyuxyntoxfbcwptlbjdmagsnaeatbzdjxivifbngdkleyjoomcmevhzyoumtfkebmrnrlscbwhrtqegdgamuowdjjuaiutikobcczmlnesynlidqtqrfotnhxdkmhrqgthjgxrmygjeabutswwmyzjitijigmgbamomkfjkrmmzossaburrdcxdqytgjclcmibrrdsivlglmmeqimwifwhzbecgoowkyaagcoqllivmygebuodaphzrabrapjryughzwbspsufdethttkezbbxyhpaynfydhsjicpiguuzhfpyhxxlripbmhtsjjxpanvkqidkiflazwdtbkgxmdijsuwxgkyepmxppkwycwatboubygrgyekotltcdeldaqgtiinpekeewgcnwhjrwbltqwxfxnwyhnboysysupukgpdxsedmjnxmokioagqwwadvcdhakwhbthbvcybigdlqznwuoxcujtprffhcqltztlydrifjbwxkottzsvsevujzvuqspreuqvjrnbarbmwbmkmtljabphdeuhxutusyuglcvzdzxlpnhccqfixynwjdoywfsjsdkkioeguvwfoexbtuucxubcosaoisvusyzisdyqoaetumhmasametztkzghjlguuafcunabvyotcvhqcfwrslkbzilmohqurisztxbpqdquhlskhumyodjyxzzmsgdomdzdcmeeopdysdnfrcgvqbdytzyhdgzjdeyhxcsidllgdrcnfpckkgkwzyiciyjywvubvmmvenqzvpmxwzbcnsalssmgyotvaibppmwcsdxsoaphdygfgejzouuklbbejbtnsxptppryysalbsvfapqkobrrufuuywgfyxsmlpekyohlvqkslubsjjaozbphgojocsksuujkjiudrhyndgehbjhcfswqednmpleihfvfmfaaibyhlvgzjeepxfupwqmxmjxgosegrlraqngdgryteazvlmyzgsxwyquigocibgupopnzulxvmcjvdfwmaftwfmqsepnahncddhdfekwvwzwfcvkyuvdpruoazquqijxhdgjiynqnitzjgfykovxjhxgztnjlpgxnrwhisitqbisxxgzzjspwoxtnlygkvdpystbasasimldjmlxcnftyquzzlrflshecreddiykvvhdmbeebpikxkcdavfzbofsxrjuixcnoylhuyvymnlxohkwzexrlwcivywuoxtbjpcwtqsejhzkwszwhvvjwwusvybwgumbgotpnadqzflifdovfntbnosnzrhkvmfoktppxsoqelnhgyqrfvmidbdtedgrhuhwkdpqofryvzncterwzdyurloykbzjddsrooenordecluuwmolaopsecxcogxlqmdckqyblopmjcwlmivnhhhedqaefpkfbhyqoubnvlemrdijguotrljahbhazgkdjnxxjtuaikrujvhnzviqftmplxhlhbvehluwsaubhhcaiahqatvvztsnhtnrifecvlepyzxbzywiqynjnpbjohgltklmoqttfqyjtynwawdfhywfnytzpltkjcpyrnzwqlaxneojvpeceavylddkvxjtfnjbojiukqswkgzhvtsnnuxwsbimiuvysaupoyxfxrhhzueeaprqygxqvaeushpkwqwajqkhrjundhipwuorslkkwvqqthcuvhrzxxkbnkhghivqgvnsjtykzivtpwnuplalcelvnojgfdbfyipeopulvvkcxgvzvfeueaggkvupfkmlhelkdlisvmwfouqsxlzgsuyqjdbfphrsjpihvdewjuobvmmqezomlrqyiafcbsjcdaulkftgcpgxagktgzfzapiyyjlamswsfetlxewdpdiixnazjauohjmikmugfjzaeshubrcduaaijwboeexyqgbwrxrganvadkhpsdkmircosatrgpjzxhobayyvydfuzptfueprkxufvwsdmtjsxapmcotyfggevwchbpziptrfjrbznyvhwzklgqirbxlxfxfksefglfzwyhatmhmpigcyrmvktawuwvpsgshzyoqmpgukxgkukzyeinipqcnaxgyijqraxnkjnfddkihejsqyhliwoimdtnbytvuufzayzevmhpdiamnbwcvilxtqjcbsiewcekbkbvylwdoytbtqaricyybjqvgonrrkflzevgjqjybpijjijbqkdywvbtysiuxlobitratvtpcnvjjipjsayptvtpskuklcpxfbhdplnvwbbkimkgokheemrocpqkpxwyjqlbmbmpktoeltpusuhyemnkcvelambszcyozzavweamgqxjzvikylpoupkggthlkfcuunveyzjpsfwmlnqhnwyvrfdkqlfuizkozwwsluafjbbvladowxjyqtquomvfxragqifluvlqmspuebntgckselwxdtnixtjzjvihbioaiaagccwiyaxyvglwqnkmydbmupfaxmfarjlnckxvjcagenxoyyjqqjfzjcjexnwoldctfiqpxwvnsxxsghoolvgszqjwqxgkavhfzzpxceanmbxuxwmlfftgkjxyezeuwhrqenikgzftzfwcmfthhlporlnyvqsxczkxhsgxfxbnjaceimppaoguemxjsycfglysoefxvionvpebzqcztawdapqwyoednriabfvmqzdnldvimqyptdohcdnetbryqlbtzzmwqaegpdkdwrfiszpcpfxhoactynqtbtwgpmbgzaifmpsavwlhmkfxgvntpzafxbrpqxwfaujnhuhdelpubtvehxbkpuouwgnvlzntqsarlmsdutvvkvnmutxvavzlkaroinxbyoetntqtphhgosbzyvzxmdneuogjvreyptcvxazxxrxpjtiymtvhmqxtewkjxshfllqykuxfpghostbvqpjlofjvgxvzbvivvlxhxgtgtuyccrzwjxoqehelvegppayqzpvhpasphsqlfpjulswiihvxhojubfhqsjyurzfnhchvwfnqfcrmbwwndygbtggmkssoaruaapryhrwnlxgrnoimqpyesxzojpzqxvkfxcnarvzpqhxdmakrkjminkqxdabwwykxovvugtaceucrhmvscurvxsrobpobpdobweaacqznbtpjewnpijwskebtsnjovjdlavveeqjvkzcvdoxsxaicgyppfdstyxcvhgyffurexfatqzfzxpgklapbhlssapxnweusjgsuodklrafczucyoyqhpnxsjdlfnwmpbimfpquduvotuckztbwqqoiwpzmmcmbdeicltzxmcpmcygrlvjgmpqxaoslbfcxopjprlfbuyxbhwogjxbqojloqmwgnlbsowbpxciydaamkviqgruodjimptfsxeimabousmejjspcdrawfzesmsoxvrcajrljeebmwyctcdckpuainuguovggfyzmbrnmthnninyjvtsoluboolextnrgmuezuqkkmfcomlxhhjwujsnjebqqknzjiwduzqvlhwfryxkqtxsiphzdzybddtqiqtrsgndhjbsjcykbnacjbumjcvrohhhexlquxwlzfctvicffufrtwdrsavrzwdsxibscqkqcvgfdgbeanbcaaniefoidsjbgspjvuoqhsycnfxdjkwfhyneydxgmcfrtkcqxlgvkplqlhxhkkdhvteqghwrykgeshypythuqlrhctccixksgwkpkgcmaikwldzlaririppapmdgohuixgynscjxbsjraifensxosilamkopfeewqfgounznhkiqxvdzyhpzfpvbtuoenhdaodqyvcdozuxzljshpkkctfzmzysgjzwxstyzrrysplyhtpmtpwxtkkabibamwhfpjxhkwgfaavuwescazfpgolajqtdnkntrdgzyasikolyxajaghbyhqanrvplyavrcjcvorawlqjlxlunvdwxhsalhngbqivrpohtlncvtejmgfrcedndjdlrwcddyjgtdornaffcjlqdvnudkxpmaiofgxvdrtxseibpelomeqrjfspzxbvrikapiabfthrdtzpsysnhyctqzakcgkllmvxnpiilgnieamdhvssjdzsjvfsdtdjkndfsgrxjqzttminsaqedjqqyuxjwkzcgiozyrpssuurrlwkvxmhfrvxekebihrkuhtgjwjguelmxrmiqkndytvmaguliyqleqtypjeueztsdmaovvqfcebdmgvjtydmkgxgujvkpssvmosxlknavbgpfhbkguknruhjkvwxvcpwwzykjhevnvnrputcuvfaunhcvaignpmbdwnzlfitszhcxqcjqesuvggkfpjtdfitqrqmnxwqpejgtmokdhtfubmjgubxeooybdvwsbncgcigfrsbbaryyzlmegtohwoekkaimmqtpbadbkdgxflahfkvedwhmnwdftjiwrylpyihyunwqxprtigfjrczslcfoakpzvickwrfrbwohrngpvvcrmhstpqcvlurzzgfngbeqkokeojetwivitqjrsfjrlxeccndxdbjyinwkrqhsdbxekhunrwtiznjycpwzzskrgabowaiznkqvjdyottobxzdlqhvnqatjuwhjnrnvxupfnrnzabviigftutveaesfpbvmbbookncilwyjcmdsfmxljtbxoccepqynhybbkwupsoiifzljphfyrartvdjoylwbbktjdciktmiudmblrpnheyzuilweniznjlzrdplqkshofyqcebvinkxblnguhosebnczuapkphhbrnuwicuefpkpsccvvnzmaokbjxlgfcyyjtsqxadifcalsbsrgosolinarlpjhxdoroyxewdjperzaftocnjqpljjexuiclgipmkjcgcjbqskjvflaadlricwwatgcvniemjcaulugfqyuukelzcqxqlzcwvozplbwyllvounczcznrjitscooymkjdwclgfulitkjilmobmyqauuhowjhfswkgbzhwriifyejnhgntkaghkvnmcdviczvrnzbzafhfegdwufkfropcgwjwscasebtpuhjpnqoeeyirnvcvcgljchawlwczpywtywhovaxyqwgmmiqdnxokvwwlznsjpmpzunkefdniwuuhankfvgypqtuhevyecswagovrfdsrnvhhvjaojemisvjmbcifxivgwzosludfeomyhswcgjteliglehpunubhsvvytyytbvzkcufczeakcnehqiapbebprydrrbdavbvbazcrihncjllflrphemspepzxoifagvtvzzbavdsugnddyuvpgcettyxbebheielssakaynvennqjqtxcsodbwthvhtwbeylrdedmpjmlsflsqlsvatihxhdkwxaluwysrbgrtaveavobdstycjtaoaasvltcrgzoqmtbntlznshcebbpqjvvuwgzsmwbcxggnsasnalffqqgablaujtsxldbitebkpxszwraadqeasdxvqnwrmwgrovcarzdmzpaspiijniuxgsqirwvrlghnggfrrmmbyenusjmjynsgftwfvarlktnbwfetkvahsovqqtmrygnvnammacqgrygrtternojahxdrffjqndrgyhcookpcttrbjzhnepahqdisivmeculxrwgmthqqvakdnhzibaijgejjmjaxkxnhgbraueaainzqkkgexfpxzehjpsguvjvuwsgdaotzsrhkiwnozxwohakrsifwugmivgxqewmdvyxalvsysuxffkvtvjjrjqhhoinziyjkohskwvjuuoikuvwoczzekznppkoxybrldwfaathtxhxmobmlzuumuclbvfjqerzfaochsjeviknvhwnjyjweemqvlnujzpgpsuipyiidobgppiuubcllmugpdupkcuwivvowhzsilikeswlqpuuxxnzrabdhhsnqhlnpaeofkuypdyekrunxoztcnuegzedrdsdgkfksqencxqhhyjyxzdnjysgmjefknetykkqfunqpvjmnzyarqetyfqtpqsggdgjmtaomnevjqjknktcpgutcqpuorvtiiqkytbqvcxghkdxbeqtnrjzacxypcgruzckotpghiugxczbiztcyamljqpvnwusvoetbvabbyqncirgxdrnoiuokjkanoidwgtqxixriwfvbpvssghhsfxgjiuzcixrnmkfksdyobsjxdynpjsxkpxuoewgzkvigxyvzdehoiqsgjtcttxwithwcoxyoikwpnzggzparkwuwoloqincnkqpckcyiujxujjowokcpjvnhrssagfcivaaskepcudkoiqlolusslhxqkixhnydriwxogsfoocoiemcmsapthsjyiwozfowfwynataegkdxvszfbcocbmlybochlrjzgvjpvoroxkizofjurgenscxmzngfuwqdwjwwuczofihoxwtlvshrudfdxwlzqjdiziwwtuubmhhxevbuklvdrltrvahidrhxxwuiwcqxqildovgewsbojpbrxewbatgjuvjwcoehvkmzkywyvmlhxlhcgfxzmiuhrtehhbyqnlgvyjoqvrcrjicdbxvuxcoihjzvpfbsovwxrzlugxnfoqcaydhchgzhiliezuqcrghrbvihvenqxcvvgfeoagxczmqdqbocilvqxnritbvfxsowsurfvimwgvsfyiyrswtsxgcnvzdkmmuhqxbdetfwvtsetfhskkhlmfcywiyilkduoxavmmjyiypgnimgzkckjfdtmuxfiecuteioyrtmopvggyiuunbjbyxauvjtotxfujenielfudihmpgiugegfdhennghfhcpbubabhqfhfceengyordhuprgihkowatecamwjsnjafejedqbiegnybjhqvsnyfauftctzfscupctytgsesikrhpelljhonobsomeymfxtisuwtqdeilagmelrfcslkawiavxkuqxbtstznyurphdfqdqeoogncdqtsgdhkppptagzbdqqnbsczmnfamqdnigzdbvofustczyclodtafdzlehkkdyumgyrnmtwmiojmefmepqklxtdzkrxfrmqhafsusisxbunexuwbqhdzzddukmtgmhuuaeccnvobxbshylrptuldyeiequywgmciirykanhevfsjoygvfhkimreciikbznuliirhbjjlfntkofglzetwrgozbuueyucruaaachtfacflmcfewvwmmtwoqmfvnmmcpempwscvpcsykzvtuucaaomumktoyrykzmchlsynktgxaavaizxwjhexhiwagpgzhnhwk
', 'NO
', 10, 0, 100001, 3);

-- =====================================================================
-- 种子数据 6：端云模型通道（4 条）
-- =====================================================================
INSERT IGNORE INTO `ai_model_config`
(`id`, `channel_code`, `channel_name`, `vendor`, `provider_type`, `base_url`, `api_key`,
 `model_name`, `role_binding`, `timeout_seconds`, `max_tokens`, `temperature`,
 `max_concurrency`, `health_status`, `health_note`, `last_probe_at`, `is_enabled`)
VALUES
(1, 'local-qwen', '本地 Qwen', 'Ollama', 'OLLAMA',
 'http://host.docker.internal:11434/v1', '', 'qwen2.5-coder:7b', 'SYSTEM_FIXED',
 8, 2048, 0.30, 4, 'ON', NULL, '2026-10-20 08:00:00', 1),
(2, 'cloud-ds', '云端 DeepSeek', 'DeepSeek', 'OPENAI_COMPATIBLE',
 'https://api.deepseek.com/v1', '', 'deepseek-chat', 'TUTOR_OPTIONAL',
 8, 2048, 0.30, 8, 'ON', NULL, '2026-10-20 08:00:00', 1),
(3, 'cloud-qwen', '通义千问 Max', 'Qwen', 'OPENAI_COMPATIBLE',
 'https://dashscope.aliyuncs.com/compatible-mode/v1', '', 'qwen-max', 'TUTOR_OPTIONAL',
 8, 2048, 0.30, 8, 'ON', NULL, '2026-10-20 08:00:00', 1),
(4, 'cloud-zhipu', '智谱 GLM-5.3-Flash', 'Zhipu', 'OPENAI_COMPATIBLE',
 'https://open.bigmodel.cn/api/paas/v4', '${OJ_ZHIPU_API_KEY}', 'glm-5.3-flash', 'TUTOR_OPTIONAL',
 40, 8192, 0.30, 8, 'ON', NULL, '2026-10-20 08:00:00', 1);

-- =====================================================================
-- 种子数据 7：通道与三角色绑定（5 条）
-- local-qwen -> ROUTER(10) + GUARDRAIL(10)
-- cloud-ds    -> TUTOR(10)
-- cloud-qwen  -> TUTOR(20)
-- cloud-zhipu -> TUTOR(30)
-- =====================================================================
INSERT IGNORE INTO `ai_model_role` (`id`, `channel_code`, `role`, `priority`, `is_enabled`) VALUES
(1, 'local-qwen',  'ROUTER',    10, 1),
(2, 'local-qwen',  'GUARDRAIL', 10, 1),
(3, 'cloud-ds',    'TUTOR',     10, 1),
(4, 'cloud-qwen',  'TUTOR',     20, 1),
(5, 'cloud-zhipu', 'TUTOR',     30, 1);

-- =====================================================================
-- 种子数据 8：教学演示数据（其余表各 0~1 条，仅为联调可视化）
-- =====================================================================
INSERT IGNORE INTO `teach_assignment`
(`id`, `display_no`, `title`, `class_id`, `teacher_id`, `mode_type`, `status`,
 `allowed_ai_stages`, `ai_quota_per_prob`, `tab_switch_limit`, `problem_ids`,
 `start_time`, `end_time`, `extended_minutes`)
VALUES
(1, 'A-0001', '第一次平时练习：基础算法', 1,
 (SELECT `id` FROM `sys_user` WHERE `username` = 'T001'), 1, 2,
 '[1, 2, 3, 4]', 3, 5, '[1001, 1002, 1003]',
 '2026-10-20 09:00:00', '2026-10-27 23:59:59', 0);

INSERT IGNORE INTO `assignment_submission`
(`id`, `display_no`, `assignment_id`, `user_id`, `class_id`, `status`,
 `submitted_at`, `undo_deadline_at`, `revoked_at`, `problem_count`, `answered_count`, `remark`)
VALUES
(1, 'SA-0001-01', 1, 1, 1, 'SUBMITTED',
 '2026-10-21 10:12:00', '2026-10-21 10:12:05', NULL, 3, 1, '第一题已通过');

INSERT IGNORE INTO `teach_student_score`
(`id`, `assignment_id`, `user_id`, `problem_id`, `best_submission_id`, `max_score`,
 `min_time_used`, `draft_language`, `draft_code`, `submit_count`, `ai_used_count`,
 `tab_switch_count`, `red_flag_type`, `prev_max_score`)
VALUES
(1, 1, 1, 1001, 1, 100, 52, 'cpp', '#include <bits/stdc++.h>
using namespace std;

int main() {
    ios::sync_with_stdio(false);
    cin.tie(nullptr);
    int n;
    if (!(cin >> n)) return 0;
    long long best = LLONG_MIN, cur = 0;
    for (int i = 0; i < n; ++i) {
        long long x;
        cin >> x;
        cur = (cur > 0 ? cur : 0) + x;
        best = max(best, cur);
    }
    cout << best << ''\\n'';
    return 0;
}
', 1, 0, 0, 0, NULL);

INSERT IGNORE INTO `judge_submission`
(`id`, `display_no`, `user_id`, `problem_id`, `assignment_id`, `language`, `code`,
 `status`, `oi_score`, `pass_case_count`, `total_case_count`, `case_details`,
 `time_used`, `memory_used`, `error_msg`, `first_failed_case_id`, `first_failed_summary`,
 `sandbox_instance_id`, `rejudge_count`, `is_overdue`, `finished_at`)
VALUES
(1, 'S-00001', 1, 1001, 1, 'cpp', '#include <bits/stdc++.h>
using namespace std;

int main() {
    ios::sync_with_stdio(false);
    cin.tie(nullptr);
    int n;
    if (!(cin >> n)) return 0;
    long long best = LLONG_MIN, cur = 0;
    for (int i = 0; i < n; ++i) {
        long long x;
        cin >> x;
        cur = (cur > 0 ? cur : 0) + x;
        best = max(best, cur);
    }
    cout << best << ''\\n'';
    return 0;
}
',
 3, 100, 10, 10, '[{"caseId": 1, "seqNo": 1, "kind": "sample", "status": "AC", "score": 10, "gotScore": 10, "timeMs": 4, "memKb": 1024}, {"caseId": 2, "seqNo": 2, "kind": "sample", "status": "AC", "score": 10, "gotScore": 10, "timeMs": 3, "memKb": 1024}, {"caseId": 3, "seqNo": 3, "kind": "normal", "status": "AC", "score": 10, "gotScore": 10, "timeMs": 5, "memKb": 1152}, {"caseId": 4, "seqNo": 4, "kind": "normal", "status": "AC", "score": 10, "gotScore": 10, "timeMs": 4, "memKb": 1088}, {"caseId": 5, "seqNo": 5, "kind": "normal", "status": "AC", "score": 10, "gotScore": 10, "timeMs": 5, "memKb": 1104}, {"caseId": 6, "seqNo": 6, "kind": "normal", "status": "AC", "score": 10, "gotScore": 10, "timeMs": 4, "memKb": 1088}, {"caseId": 7, "seqNo": 7, "kind": "edge", "status": "AC", "score": 10, "gotScore": 10, "timeMs": 3, "memKb": 1024}, {"caseId": 8, "seqNo": 8, "kind": "edge", "status": "AC", "score": 10, "gotScore": 10, "timeMs": 3, "memKb": 1024}, {"caseId": 9, "seqNo": 9, "kind": "stress", "status": "AC", "score": 10, "gotScore": 10, "timeMs": 46, "memKb": 2048}, {"caseId": 10, "seqNo": 10, "kind": "stress", "status": "AC", "score": 10, "gotScore": 10, "timeMs": 48, "memKb": 2048}]',
 48, 2048, NULL, NULL, NULL,
 'sbx-0001', 0, 0, '2026-10-21 10:12:06');

INSERT IGNORE INTO `judge_sandbox_instance`
(`id`, `sandbox_instance_id`, `submission_id`, `user_id`, `problem_id`, `language`,
 `state`, `judge_node`, `container_id`, `cpu_limit`, `mem_limit_kb`,
 `cpu_percent`, `mem_used_kb`, `exit_code`, `started_at`, `finished_at`,
 `last_heartbeat_at`, `log_ref`)
VALUES
(1, 'sbx-0001', 1, 1, 1001, 'cpp',
 'EXITED', 'local', 'ctr-0001', 1, 262144,
 0, 4096, 0, '2026-10-21 10:12:05', '2026-10-21 10:12:06',
 '2026-10-21 10:12:06', 'ok: 10/10 cases passed');

INSERT IGNORE INTO `ai_chat_record`
(`id`, `session_id`, `user_id`, `problem_id`, `assignment_id`, `submission_id`, `trace_id`,
 `stage_type`, `hint_level`, `user_message`, `numbered_code`, `selected_channel`,
 `actual_model`, `is_fallback`, `tool_called`, `ai_response`, `guardrail_action`,
 `guard_reason`, `guard_replacement`, `ttft_ms`, `tokens_in`, `tokens_out`, `cost_cents`)
VALUES
(1, '1_1_1001', 1, 1001, 1, 1, 'trace-0001',
 2, 1, '为什么我的答案会错？题目说是连续子段，我该从哪里入手？', NULL, 'local-qwen',
 'qwen2.5-coder:7b', 0, NULL,
 '先别急着写代码。请想一想：如果已知「以第 i-1 个元素结尾的最大子段和」，那么以第 i 个元素结尾的最大子段和与它有什么关系？试试自己举一个含负数的例子推一推。',
 0, 'NONE', NULL, 420, 812, 196, 0);

INSERT IGNORE INTO `ai_call_feedback`
(`id`, `record_id`, `user_id`, `helpful`, `reason_code`, `comment`)
VALUES
(1, 1, 1, 1, NULL, '提示没有直接给答案，自己推出来了');

-- ---------------------------------------------------------------------
-- 3. 收尾
-- ---------------------------------------------------------------------
SET FOREIGN_KEY_CHECKS = 1;
