package com.zjl.worklog.work.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkRecordLog {
    private Long id;
    private Long workRecordId;
    private Long userId;
    private String logContent;
    private LocalDateTime logTime;
}
