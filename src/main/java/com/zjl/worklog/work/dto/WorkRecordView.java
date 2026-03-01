package com.zjl.worklog.work.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkRecordView {
    private Long id;
    private Long userId;
    private String recorderName;
    private Long deptId;
    private Long categoryId;
    private Integer statusId;
    private String title;
    private String content;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationMinutes;
    private Integer isImportant;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
