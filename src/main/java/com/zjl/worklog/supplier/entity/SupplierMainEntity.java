package com.zjl.worklog.supplier.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SupplierMainEntity {

    private Long id;
    private String supplierCode;
    private String supplierName;
    private String supplierShortName;
    private String creditCode;
    private String legalRepresentative;
    private String contactAddress;
    private String contactPhone;
    private String bankName;
    private String bankAccount;
    private String accountName;

    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;

    private Integer deleted;
}
