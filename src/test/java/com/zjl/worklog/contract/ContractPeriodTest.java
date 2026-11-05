package com.zjl.worklog.contract;

import com.zjl.worklog.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合同有效期：普通合同必须有止期；勾选长期则止期清空，且不会进入到期提醒。
 */
class ContractPeriodTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 12, 31);

    @Test
    @DisplayName("未勾选长期时必须有结束日期，且不能早于开始日期")
    void fixedTermRequiresEndOnOrAfterStart() {
        ContractPeriod period = ContractPeriod.of(START, END, false);
        assertFalse(period.isLongTerm());
        assertEquals(START, period.startDate());
        assertEquals(END, period.endDate());

        BizException missing = assertThrows(BizException.class, () -> ContractPeriod.of(START, null, false));
        assertEquals("请选择结束日期", missing.getMsg());

        BizException early = assertThrows(BizException.class,
                () -> ContractPeriod.of(START, START.minusDays(1), null));
        assertEquals("结束日期不能早于开始日期", early.getMsg());
    }

    @Test
    @DisplayName("勾选长期时忽略结束日期，存成无止期")
    void longTermClearsEndDate() {
        ContractPeriod period = ContractPeriod.of(START, END, true);
        assertTrue(period.isLongTerm());
        assertEquals(START, period.startDate());
        assertNull(period.endDate());
    }

    @Test
    @DisplayName("开始日期始终必填")
    void startDateRequired() {
        BizException e = assertThrows(BizException.class, () -> ContractPeriod.of(null, END, true));
        assertEquals("请选择开始日期", e.getMsg());
    }
}
