package com.zjl.worklog.ticket.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 登记渠道（一个二维码一条记录） */
@Data
public class TicketChannelEntity {
    private Long id;
    private String channelCode;
    private String channelName;
    private Long deptId;
    /** 问题所在科室（业务科室）；受理科室仍是 deptId */
    private Long bizDeptId;
    private Long defaultCategoryId;
    private Integer needPhone;
    private Integer dailyLimit;
    private String remark;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
