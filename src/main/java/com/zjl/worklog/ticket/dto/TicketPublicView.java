package com.zjl.worklog.ticket.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.zjl.worklog.ticket.entity.ServiceTicketEntity;
import com.zjl.worklog.ticket.TicketStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 报修人视角的工单视图（脱敏）。
 * 不含受理人手机号、来源 IP、内部备注、科室ID 等员工侧信息。
 */
@Data
public class TicketPublicView {

    private String ticketNo;
    private String title;
    private String content;
    private String location;
    private Integer status;
    private String statusName;
    private Integer urgency;
    private List<String> images;
    private String assigneeName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime dueTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime acceptTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime doneTime;

    /** 已处理时长（分钟），让报修人知道还要不要等 */
    private Long waitedMinutes;

    private List<LogItem> logs;

    @Data
    public static class LogItem {
        private String action;
        private String operatorName;
        private Integer operatorType;
        private String remark;
        private List<String> images;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime createTime;
    }

    public static TicketPublicView of(ServiceTicketEntity e, String statusName) {
        TicketPublicView v = new TicketPublicView();
        v.setTicketNo(e.getTicketNo());
        v.setTitle(e.getTitle());
        v.setContent(e.getContent());
        v.setLocation(e.getLocation());
        v.setStatus(e.getStatus());
        v.setStatusName(statusName != null ? statusName : TicketStatus.labelOf(e.getStatus()));
        v.setUrgency(e.getUrgency());
        v.setAssigneeName(e.getAssigneeName());
        v.setCreateTime(e.getCreateTime());
        v.setDueTime(e.getDueTime());
        v.setAcceptTime(e.getAcceptTime());
        v.setDoneTime(e.getDoneTime());
        return v;
    }
}
