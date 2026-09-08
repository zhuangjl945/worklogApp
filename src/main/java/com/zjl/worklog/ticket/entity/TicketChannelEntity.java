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
    private Long defaultCategoryId;
    private Integer needPhone;
    private Integer dailyLimit;
    private String remark;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
