package com.zjl.worklog.work.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class WorkExpenseStat {
    private String expenseType;
    private BigDecimal totalAmount;
}
