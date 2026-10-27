-- 第五步增量迁移：保留现有数据，幂等扩展交作业时间精度。
USE oj_cloud;
ALTER TABLE assignment_submission
  MODIFY submitted_at DATETIME(3) DEFAULT NULL COMMENT '提交时间（毫秒）',
  MODIFY undo_deadline_at DATETIME(3) DEFAULT NULL COMMENT '5秒撤销截止时刻',
  MODIFY revoked_at DATETIME(3) DEFAULT NULL COMMENT '最近一次撤销时间';

SET @step5_sync_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='oj_cloud' AND table_name='judge_submission' AND column_name='teaching_synced_at'),
  'SELECT 1',
  'ALTER TABLE judge_submission ADD COLUMN teaching_synced_at DATETIME(3) DEFAULT NULL COMMENT ''教务已接收成绩时刻，NULL由后台重试'''
);
PREPARE step5_sync_stmt FROM @step5_sync_ddl;
EXECUTE step5_sync_stmt;
DEALLOCATE PREPARE step5_sync_stmt;
