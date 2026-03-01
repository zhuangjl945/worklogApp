package com.zjl.worklog.work.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkRecordTransfer {
    private Long id;
    private Long workRecordId;
    private Long fromUserId;
    private Long toUserId;
    private Integer status;
    private String reason;
    private String reply;
    private LocalDateTime createTime;
    private LocalDateTime handleTime;
}
