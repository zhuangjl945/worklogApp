package com.zjl.worklog.work.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class WorkRecordExpense {
    private Long id;
    private Long workRecordId;
    private String expenseType;
    private BigDecimal amount;
    private String description;
    private LocalDateTime expenseTime;
}
