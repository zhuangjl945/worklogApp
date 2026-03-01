package com.zjl.worklog.contract.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class ContractPaymentPlanEntity {

    private Long id;
    private Long contractId;
    private String planNo;
    private Integer planType; // 1收款 2付款
    private BigDecimal planAmount;
    private LocalDate planDate;
    private String conditionDesc;

    private LocalDate actualDate;
    private BigDecimal actualAmount;
    private String voucherNo;
    private String payMethod;
    private String remark;
    private Long recordedBy;
    private LocalDateTime recordedTime;

    private Integer status; // 10待付款 20已付款 30已逾期
    private LocalDateTime createdTime;
}
