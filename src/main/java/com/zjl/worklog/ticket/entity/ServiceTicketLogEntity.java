package com.zjl.worklog.ticket.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 工单流转日志（兼作对报修人可见的回复与仅内部可见的备注） */
@Data
public class ServiceTicketLogEntity {
    private Long id;
    private Long ticketId;
    /** 0-报修人 1-员工 2-系统 */
    private Integer operatorType;
    private Long operatorId;
    private String operatorName;
    private String action;
    private String remark;
    private String imageUrls;
    private Integer visibleToReporter;
    private LocalDateTime createTime;
}
