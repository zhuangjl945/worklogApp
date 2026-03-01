package com.zjl.worklog.work.entity;

import lombok.Data;

@Data
public class WorkStatus {
    private Integer id;
    private String statusName;
    private Integer sortOrder;
    private Integer status;
}
