package com.zjl.worklog.work.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkRecord {
    private Long id;
    private Long userId;
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
}
