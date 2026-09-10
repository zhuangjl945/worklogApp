package com.zjl.worklog.work.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkRecord {
    private Long id;
    private Long userId;
    /** 来源：MANUAL-手工登记，TICKET-手机端问题工单 */
    private String sourceType;
    /** 来源主键（source_type=TICKET 时为 service_ticket.id） */
    private Long sourceId;
    private Long deptId;
    private Long bizDeptId;
    private Long categoryId;
    private Integer statusId;
    private String title;
    private String content;
    private String imageUrls;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationMinutes;
    private Integer isImportant;
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /** 非表字段：列表查询用子查询回填的记录人姓名，科室看板要区分任务在谁手上 */
    private String recorderName;
}
