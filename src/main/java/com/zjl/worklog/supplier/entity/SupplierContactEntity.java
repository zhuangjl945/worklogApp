package com.zjl.worklog.supplier.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SupplierContactEntity {

    private Long id;
    private Long supplierId;
    private String contactName;
    private String contactPosition;
    private String contactPhone;
    private String contactEmail;
    private Integer isPrimary;
    private LocalDateTime createdTime;
}
