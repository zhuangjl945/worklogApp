package com.zjl.worklog.ticket.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 问题工单主表 */
@Data
public class ServiceTicketEntity {
    private Long id;
    private String ticketNo;
    private Long channelId;
    private Long deptId;
    /** 问题所在科室；deptId 仍是受理科室（权限边界） */
    private Long bizDeptId;
    private Long categoryId;
    private Integer status;
    private Integer urgency;
    private String title;
    private String content;
    private String location;
    private String contactName;
    private String contactPhone;
    /** 图片 objectKey 的 JSON 数组字符串 */
    private String imageUrls;
    /** 报修人查询令牌的 SHA-256 */
    private String accessTokenHash;
    private Long assigneeId;
    private String assigneeName;
    private Long workRecordId;
    private String submitIp;
    private String submitUa;
    /** 最晚受理时间（提交时按紧急度写入） */
    private LocalDateTime dueTime;
    private LocalDateTime acceptTime;
    private LocalDateTime doneTime;
    private LocalDateTime closeTime;
    private Integer rating;
    private String ratingComment;
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
