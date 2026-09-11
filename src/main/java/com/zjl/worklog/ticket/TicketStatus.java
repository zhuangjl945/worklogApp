package com.zjl.worklog.ticket;

import java.util.List;

/**
 * 工单状态与状态机。
 *
 * <p>刻意不做成 work_status 那样的可编辑字典表：状态与「允许哪些跃迁」强绑定，
 * 如果允许管理员随意增删状态，受理台和手机端会出现无人处理也无人能关闭的死状态。
 */
public enum TicketStatus {

    /** 已提交，等待科室受理 */
    PENDING(0, "待受理"),
    /** 已受理/已派单，处理人正在处理 */
    PROCESSING(10, "处理中"),
    /** 处理人标记完成，等待报修人确认；超过参数时限未确认则由系统自动确认（TicketScheduler） */
    WAIT_CONFIRM(20, "待报修人确认"),
    /** 报修人已确认（含超时后系统自动确认），或管理员直接完成 */
    DONE(30, "已完成"),
    /** 归档，终态 */
    CLOSED(40, "已关闭"),
    /** 信息不全/不属于本科室职责，退回报修人，终态（报修人可重新登记） */
    REJECTED(50, "已退回");

    private final int code;
    private final String label;

    TicketStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static TicketStatus of(Integer code) {
        if (code == null) {
            return null;
        }
        for (TicketStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return null;
    }

    /** 给前端展示用的中文名，未知状态原样返回码值，避免 NPE 把列表打挂 */
    public static String labelOf(Integer code) {
        TicketStatus s = of(code);
        return s == null ? ("未知状态(" + code + ")") : s.label;
    }

    /** 是否终态：终态工单不再计入待办角标（受理超时只看待受理） */
    public boolean isFinal() {
        return this == CLOSED || this == REJECTED;
    }

    /** 受理台「待办」口径：还需要人干活的状态 */
    public static List<Integer> openCodes() {
        return List.of(PENDING.code, PROCESSING.code, WAIT_CONFIRM.code);
    }
}
