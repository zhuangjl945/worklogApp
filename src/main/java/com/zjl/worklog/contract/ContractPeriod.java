package com.zjl.worklog.contract;

import com.zjl.worklog.common.exception.BizException;

import java.time.LocalDate;

/**
 * 合同有效期：要么有明确止期，要么勾选长期（无止期、不进到期提醒）。
 */
public final class ContractPeriod {

    private final LocalDate startDate;
    private final LocalDate endDate;
    private final boolean longTerm;

    private ContractPeriod(LocalDate startDate, LocalDate endDate, boolean longTerm) {
        this.startDate = startDate;
        this.endDate = endDate;
        this.longTerm = longTerm;
    }

    public static ContractPeriod of(LocalDate startDate, LocalDate endDate, Boolean longTerm) {
        if (startDate == null) {
            throw new BizException(40001, "请选择开始日期");
        }
        if (Boolean.TRUE.equals(longTerm)) {
            return new ContractPeriod(startDate, null, true);
        }
        if (endDate == null) {
            throw new BizException(40001, "请选择结束日期");
        }
        if (endDate.isBefore(startDate)) {
            throw new BizException(40001, "结束日期不能早于开始日期");
        }
        return new ContractPeriod(startDate, endDate, false);
    }

    public LocalDate startDate() {
        return startDate;
    }

    public LocalDate endDate() {
        return endDate;
    }

    public boolean isLongTerm() {
        return longTerm;
    }
}
