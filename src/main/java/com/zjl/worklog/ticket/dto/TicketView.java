package com.zjl.worklog.ticket.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.zjl.worklog.ticket.TicketStatus;
import com.zjl.worklog.ticket.entity.ServiceTicketEntity;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 受理台（员工）视角的完整视图 */
@Data
public class TicketView {

    private Long id;
    private String ticketNo;
    private Long channelId;
    private String channelName;
    /** 受理人回复时要把图片传到同一个渠道目录下，前端需要它拼 dir */
    private String channelCode;
    private Long deptId;
    /** 问题所在科室（业务科室） */
    private Long bizDeptId;
    private String bizDeptName;
    private Long categoryId;
    private String categoryName;
    private Integer status;
    private String statusName;
    private Integer urgency;
    private String urgencyName;
    private String title;
    private String content;
    private String location;
    private String contactName;
    private String contactPhone;
    private List<String> images;
    private Long assigneeId;
    private String assigneeName;
    private Long workRecordId;
    private String submitIp;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime dueTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime acceptTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime doneTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime closeTime;

    private Integer rating;
    private String ratingComment;

    /** SLA 剩余分钟数，负数表示已超时；dueTime 为空时为 null */
    private Long slaRemainMinutes;

    /**
     * 系统将在何时自动确认这条「待报修人确认」的工单。
     * 自动确认参数关闭、或状态不是待确认时为 null，前端据此决定是否显示提示。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime autoConfirmAt;
    /** 距离自动确认还剩多少分钟（已夹紧不为负） */
    private Long autoConfirmRemainMinutes;

    private List<LogLine> logs;

    @Data
    public static class LogLine {
        private Long id;
        private String action;
        private Integer operatorType;
        private String operatorName;
        private String remark;
        private List<String> images;
        private Integer visibleToReporter;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime createTime;
    }

    public static TicketView of(ServiceTicketEntity e) {
        TicketView v = new TicketView();
        v.setId(e.getId());
        v.setTicketNo(e.getTicketNo());
        v.setChannelId(e.getChannelId());
        v.setDeptId(e.getDeptId());
        v.setBizDeptId(e.getBizDeptId());
        v.setCategoryId(e.getCategoryId());
        v.setStatus(e.getStatus());
        v.setStatusName(TicketStatus.labelOf(e.getStatus()));
        v.setUrgency(e.getUrgency());
        v.setTitle(e.getTitle());
        v.setContent(e.getContent());
        v.setLocation(e.getLocation());
        v.setContactName(e.getContactName());
        v.setContactPhone(e.getContactPhone());
        v.setAssigneeId(e.getAssigneeId());
        v.setAssigneeName(e.getAssigneeName());
        v.setWorkRecordId(e.getWorkRecordId());
        v.setSubmitIp(e.getSubmitIp());
        v.setDueTime(e.getDueTime());
        v.setCreateTime(e.getCreateTime());
        v.setAcceptTime(e.getAcceptTime());
        v.setDoneTime(e.getDoneTime());
        v.setCloseTime(e.getCloseTime());
        v.setRating(e.getRating());
        v.setRatingComment(e.getRatingComment());
        return v;
    }
}
