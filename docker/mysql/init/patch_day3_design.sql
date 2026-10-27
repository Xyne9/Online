-- =====================================================================
--  Day 3 数据库设计修复补丁（在 init_oj_cloud.sql 之后执行）
--  《基于 AI Agent 与微服务架构的启发式智能在线判题系统》
--
--  背景：2026-10-27 导入 D:\todo\oj_problems_repo 的 1000 道题时，
--        对 oj_cloud 做了一轮设计审计，发现 8 处「能跑但不严谨」的问题。
--        本文件是全部修复的**幂等**版本，可重复执行。
--
--  执行方式：
--    Get-Content docker\mysql\init\patch_day3_design.sql -Raw |
--      docker exec -i oj-cloud-mysql mysql --default-character-set=utf8mb4 -uroot -proot oj_cloud
--
--  修复清单：
--    P0-1  加 7 个外键（全库原本 0 个，注释里的关联关系没有任何约束保障）
--    P0-2  修 uk_prob_seq —— 原三列唯一键含可空列 deleted_at，实际失效
--    P0-3  加 CHECK：score ∈ [0,100] 且为 5 的倍数；difficulty ∈ [1,5]；state ∈ (0,1,2)
--    P1-1  补 judge_submission (assignment_id, problem_id, user_id) 索引
--    P1-2  补 ai_chat_record (stage_type, selected_channel, create_time) 索引
--    P1-3  补 teach_student_score (assignment_id, problem_id) 索引
--    P1-7  修正 prob_test_case.input_text/output_text 注释（原文写「全文」，
--          实际存 4KB 摘要，照注释实现 DAO 会让判题 100% 误判 WA）
--    P2-1  补 prob_test_case (problem_id, is_public) 索引
--    新增  prob_question_kp 多对多关联表
--    统一  collation 到 utf8mb4_0900_ai_ci
--
--  幂等实现说明：MySQL 8.0 不支持 `DROP INDEX IF EXISTS` / `ADD CONSTRAINT
--  IF NOT EXISTS`（那些是 MariaDB 语法）。所以统一用「查 information_schema
--  → PREPARE 动态 SQL」的模式，已存在就 `DO 0` 跳过。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 统一字符集（库默认原是 utf8mb4_unicode_ci，表是 0900_ai_ci，
--    跨表比较会抛 ERROR 1267 Illegal mix of collations）
-- ---------------------------------------------------------------------
ALTER DATABASE `oj_cloud`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 2. P0-2 修复 prob_test_case 唯一键
--    原：UNIQUE KEY uk_prob_seq (problem_id, seq_no, deleted_at)
--    MySQL 中 NULL 不参与唯一性判定 → deleted_at 为 NULL 时该键失效，
--    实测同一题同一 seq_no 可无限插入有效行。
-- ---------------------------------------------------------------------
SET @has_uk := (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prob_test_case'
                  AND INDEX_NAME = 'uk_prob_seq' AND COLUMN_NAME = 'deleted_at');
SET @sql := IF(@has_uk > 0, 'ALTER TABLE `prob_test_case` DROP INDEX `uk_prob_seq`', 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_uk2 := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prob_test_case'
                   AND INDEX_NAME = 'uk_prob_seq' AND COLUMN_NAME = 'seq_no');
SET @sql := IF(@has_uk2 = 0,
  'ALTER TABLE `prob_test_case` ADD UNIQUE KEY `uk_prob_seq` (`problem_id`, `seq_no`)', 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------------------------------------------------------------------
-- 3. P1-7 修正 input_text / output_text 注释
--    实际存的是 ≤4096 字节摘要 + 截断标记；完整 .in/.out 在
--    data/testcases/{problem_id}/{seq_no}.in|.out（go-judge copyIn 直接读）。
--    判题比对**必须**读磁盘，不能读这两列。
-- ---------------------------------------------------------------------
ALTER TABLE `prob_test_case`
  MODIFY `input_text`  MEDIUMTEXT NOT NULL
    COMMENT '输入摘要(<=4096字节; 全文见 test_case_dir/{seq_no}.in, 判题比对必须读磁盘文件)',
  MODIFY `output_text` MEDIUMTEXT NOT NULL
    COMMENT '期望输出摘要(<=4096字节; 全文见 test_case_dir/{seq_no}.out, 判题比对必须读磁盘文件)';

-- ---------------------------------------------------------------------
-- 4. P0-3 加 CHECK 约束
-- ---------------------------------------------------------------------
SET @has_ck := (SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_tc_score');
SET @sql := IF(@has_ck = 0,
  'ALTER TABLE `prob_test_case` ADD CONSTRAINT `ck_tc_score` CHECK (`score` BETWEEN 0 AND 100 AND `score` % 5 = 0)',
  'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_ck := (SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_q_difficulty');
SET @sql := IF(@has_ck = 0,
  'ALTER TABLE `prob_question` ADD CONSTRAINT `ck_q_difficulty` CHECK (`difficulty` BETWEEN 1 AND 5)',
  'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_ck := (SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_q_state');
SET @sql := IF(@has_ck = 0,
  'ALTER TABLE `prob_question` ADD CONSTRAINT `ck_q_state` CHECK (`state` IN (0, 1, 2))',
  'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------------------------------------------------------------------
-- 5. P1-1 / P1-2 / P1-3 / P2-1 补索引
--    实测 EXPLAIN：判题落库与学情聚合两条主查询原本 type=ALL（全表扫）。
-- ---------------------------------------------------------------------
SET @has_ix := (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'judge_submission'
                  AND INDEX_NAME = 'idx_assign_prob');
SET @sql := IF(@has_ix = 0,
  'ALTER TABLE `judge_submission` ADD INDEX `idx_assign_prob` (`assignment_id`, `problem_id`, `user_id`)', 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_ix := (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'judge_submission'
                  AND INDEX_NAME = 'idx_prob_status');
SET @sql := IF(@has_ix = 0,
  'ALTER TABLE `judge_submission` ADD INDEX `idx_prob_status` (`problem_id`, `status`)', 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_ix := (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'teach_student_score'
                  AND INDEX_NAME = 'idx_assign_prob');
SET @sql := IF(@has_ix = 0,
  'ALTER TABLE `teach_student_score` ADD INDEX `idx_assign_prob` (`assignment_id`, `problem_id`)', 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_ix := (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_chat_record'
                  AND INDEX_NAME = 'idx_stage_channel_time');
SET @sql := IF(@has_ix = 0,
  'ALTER TABLE `ai_chat_record` ADD INDEX `idx_stage_channel_time` (`stage_type`, `selected_channel`, `create_time`)', 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_ix := (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prob_test_case'
                  AND INDEX_NAME = 'idx_prob_public');
SET @sql := IF(@has_ix = 0,
  'ALTER TABLE `prob_test_case` ADD INDEX `idx_prob_public` (`problem_id`, `is_public`)', 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------------------------------------------------------------------
-- 6. P0-1 加外键
--    全库原本 0 个外键，表注释里写的「所属题目 prob_question.id」只是注释。
--    加之前已确认无孤儿行（2026-10-27 实测 5 组关联全部 0 孤儿）。
-- ---------------------------------------------------------------------
SET @has_fk := (SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_tc_problem');
SET @sql := IF(@has_fk = 0,
  'ALTER TABLE `prob_test_case` ADD CONSTRAINT `fk_tc_problem` FOREIGN KEY (`problem_id`) REFERENCES `prob_question` (`id`) ON DELETE CASCADE ON UPDATE CASCADE', 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_fk := (SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_q_kp');
SET @sql := IF(@has_fk = 0,
  'ALTER TABLE `prob_question` ADD CONSTRAINT `fk_q_kp` FOREIGN KEY (`knowledge_point_id`) REFERENCES `prob_knowledge_point` (`id`) ON DELETE SET NULL ON UPDATE CASCADE', 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_fk := (SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_tss_assign');
SET @sql := IF(@has_fk = 0,
  'ALTER TABLE `teach_student_score` ADD CONSTRAINT `fk_tss_assign` FOREIGN KEY (`assignment_id`) REFERENCES `teach_assignment` (`id`) ON DELETE CASCADE ON UPDATE CASCADE', 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_fk := (SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_js_problem');
SET @sql := IF(@has_fk = 0,
  'ALTER TABLE `judge_submission` ADD CONSTRAINT `fk_js_problem` FOREIGN KEY (`problem_id`) REFERENCES `prob_question` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE', 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_fk := (SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_js_user');
SET @sql := IF(@has_fk = 0,
  'ALTER TABLE `judge_submission` ADD CONSTRAINT `fk_js_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE', 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------------------------------------------------------------------
-- 7. 新增：题目-知识点多对多关联表
--    prob_question.knowledge_point_id 是单值列，只够「主知识点展示」；
--    学情分析要按「这道题涉及的所有知识点」聚合（契约 §6.6.x 的
--    masteryPercent / weakTags），单值会让多标签题的能力评估失真。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `prob_question_kp` (
  `problem_id`  BIGINT NOT NULL COMMENT '题目ID',
  `kp_id`       BIGINT NOT NULL COMMENT '知识点ID',
  `weight`      DECIMAL(3,2) NOT NULL DEFAULT 1.00 COMMENT '该知识点对本题的权重(0-1)',
  `is_primary`  TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否主知识点(1是)',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`problem_id`, `kp_id`),
  KEY `idx_kp` (`kp_id`),
  CONSTRAINT `fk_qkp_problem` FOREIGN KEY (`problem_id`)
    REFERENCES `prob_question` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_qkp_kp` FOREIGN KEY (`kp_id`)
    REFERENCES `prob_knowledge_point` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='题目-知识点多对多关联(学情聚合用; knowledge_point_id 仅存主知识点供展示)';

-- 从现有 tags 回填（幂等：INSERT IGNORE + 主键去重）
INSERT IGNORE INTO `prob_question_kp` (`problem_id`, `kp_id`, `weight`, `is_primary`)
SELECT q.id, k.id, 1.00, IF(q.knowledge_point_id = k.id, 1, 0)
FROM `prob_question` q
JOIN `prob_knowledge_point` k ON JSON_SEARCH(q.tags, 'one', k.name) IS NOT NULL
WHERE q.deleted_at IS NULL;

-- ---------------------------------------------------------------------
-- 8. 难度重标定说明（数据修复，不在本补丁内自动执行）
--    题库源 difficulty 1000 道全是 "Mid"，初版映射按分类粗判产出
--    1%/22%/41%/9%/27%（困难 27% 倒挂）。已用
--    scripts/recalibrate_difficulty.py 重标定为 1%/19%/42%/35%/4%。
-- ---------------------------------------------------------------------
