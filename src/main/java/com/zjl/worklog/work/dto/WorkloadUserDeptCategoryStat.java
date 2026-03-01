package com.zjl.worklog.work.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class WorkloadUserDeptCategoryStat {
    private Long userId;
    private String realName;
    private Long deptId;
    private String deptName;
    private Long categoryId;
    private String categoryName;
    private Long totalCount;
    private BigDecimal totalAmount;
}
