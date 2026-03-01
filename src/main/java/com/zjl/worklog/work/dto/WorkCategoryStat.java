package com.zjl.worklog.work.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class WorkCategoryStat {
    private Long userId;
    private String realName;
    private Long categoryId;
    private String categoryName;
    private Long totalCount;
    private BigDecimal totalAmount;
}
