package com.zjl.worklog.work.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkWeeklyItem {
    private Long id;
    private String title;
    private String categoryName;
    private Integer statusId;
    private LocalDateTime endTime;
    private Integer isImportant;
    private Boolean overdue;
}
