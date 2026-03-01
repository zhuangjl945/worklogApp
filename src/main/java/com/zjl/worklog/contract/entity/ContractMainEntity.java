package com.zjl.worklog.contract.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class ContractMainEntity {
    private Long id;
    private String contractNo;
    private String contractName;
    private Integer contractType;
    private Integer contractDirection;

    private Long customerId;
    private String customerName;
    private Long supplierId;
    private String supplierName;

    private BigDecimal contractAmount;
    private BigDecimal paidAmount;
    private Integer totalPlanCount; // 总期数
    private Integer paidPlanCount;  // 已付期数
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate signDate;

    private Integer status; // 10草稿 30执行中 40已完成 50已终止 60已过期
    private Integer paymentTerms; // 1一次性 2分期 3按里程碑 4月结
    private String paymentSchedule; // JSON字符串

    private String contractFileUrl;

    private Long deptId;
    private Long managerId;
    private Long renewFromId;   // 续签来源合同ID
    private Integer renewCount; // 续签次数
    private String renewFromNo; // 续签来源合同编号（展示用）

    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deleted;
}
