package com.oj.teaching.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 教学活动。endTime 已含 extendedMinutes，接口不可再次叠加延时。 */
@Data
@TableName("teach_assignment")
public class TeachAssignment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String displayNo;
    private String title;
    private Long classId;
    private Long teacherId;
    private Integer modeType;
    private Integer status;
    private String allowedAiStages;
    private Integer aiQuotaPerProb;
    private Integer tabSwitchLimit;
    private String problemIds;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer extendedMinutes;
    private LocalDateTime createTime;
}
