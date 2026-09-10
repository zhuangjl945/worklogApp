package com.zjl.worklog.ticket.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.zjl.worklog.ticket.entity.ServiceTicketEntity;
import com.zjl.worklog.ticket.TicketStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 报修人视角的工单视图（脱敏）。
 *
 * <p>不变量：这个类<strong>永远不要</strong>出现 contact_name / contact_phone /
 * submit_ip / submit_ua / dept_id 这类字段，也不要为了「页面想显示一下」而加回来。
 * 原因是单号已改成可预测的短流水（ST100001 起），这个视图就是公开接口能吐出去的全部内容：
 * 报修人自己的手机号、姓名一旦进来，猜中密码的人拿到的就不再是进度而是个人信息，
 * 而密码只有 6 位数字，靠的正是「猜中也看不到别的东西」。
 * 员工侧要看完整联系方式，走 TicketView（登录态 + 科室隔离），两者不要互相复制字段。
 * TicketGuardTest 里有一条反射断言守这个不变量。
 */
@Data
public class TicketPublicView {

    private String ticketNo;
    /** 渠道码本身就是公开信息（印在二维码上），报修人补充照片时要靠它换上传凭证 */
    private String channelCode;
    private String title;
    private String content;
    private String location;
    /** 问题所在科室名称（不回科室 ID） */
    private String bizDeptName;
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

    /**
     * 系统自动确认的时刻：报修人在这段时间内不点「确认已解决」，工单就自动进入已完成。
     * 自动确认关闭或当前状态不是「待报修人确认」时为 null。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime autoConfirmAt;
    /** 距离自动确认还剩多少分钟，手机端用它渲染倒计时 */
    private Long autoConfirmRemainMinutes;

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
