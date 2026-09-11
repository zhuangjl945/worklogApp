package com.zjl.worklog.ticket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 工单紧急度时限 = 提交后多久内必须受理。受理之后停表，不能把「等报修人确认」继续算成超时。
 */
class TicketAcceptSlaTest {

    private static final LocalDateTime SUBMIT = LocalDateTime.of(2026, 9, 11, 8, 23, 19);
    private static final LocalDateTime DUE = SUBMIT.plusMinutes(5);

    @Test
    @DisplayName("dueTime 为空时不下发倒计时")
    void missingDueTimeIsNull() {
        assertNull(TicketService.acceptSlaRemainMinutes(
                null, null, TicketStatus.PENDING.code(), null, SUBMIT));
    }

    @Test
    @DisplayName("待受理且未到点：剩余分钟跟现在比")
    void pendingBeforeDeadline() {
        LocalDateTime now = SUBMIT.plusMinutes(2);
        assertEquals(3L, TicketService.acceptSlaRemainMinutes(
                DUE, null, TicketStatus.PENDING.code(), null, now));
    }

    @Test
    @DisplayName("待受理且过点：显示超时未受理")
    void pendingAfterDeadline() {
        LocalDateTime now = SUBMIT.plusMinutes(23);
        assertEquals(-18L, TicketService.acceptSlaRemainMinutes(
                DUE, null, TicketStatus.PENDING.code(), null, now));
    }

    @Test
    @DisplayName("时限内受理后停表，之后再等确认也不再涨超时")
    void freezeAfterOnTimeAccept() {
        LocalDateTime acceptAt = SUBMIT.plusMinutes(1);
        LocalDateTime muchLater = SUBMIT.plusMinutes(40);
        assertEquals(4L, TicketService.acceptSlaRemainMinutes(
                DUE, acceptAt, TicketStatus.WAIT_CONFIRM.code(), muchLater, muchLater));
    }

    @Test
    @DisplayName("超时后才受理：停表在受理时刻，处理中不再继续累计")
    void freezeAfterLateAccept() {
        LocalDateTime acceptAt = SUBMIT.plusMinutes(20);
        LocalDateTime later = SUBMIT.plusMinutes(50);
        assertEquals(-15L, TicketService.acceptSlaRemainMinutes(
                DUE, acceptAt, TicketStatus.PROCESSING.code(), later, later));
    }

    @Test
    @DisplayName("从未受理就退回：按离开待受理的时刻停表")
    void freezeWhenRejectedWithoutAccept() {
        LocalDateTime rejectedAt = SUBMIT.plusMinutes(10);
        LocalDateTime later = SUBMIT.plusHours(2);
        assertEquals(-5L, TicketService.acceptSlaRemainMinutes(
                DUE, null, TicketStatus.REJECTED.code(), rejectedAt, later));
    }
}
