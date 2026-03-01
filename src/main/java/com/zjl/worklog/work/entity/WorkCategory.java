package com.zjl.worklog.work.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkCategory {
    private Long id;
    private Long deptId;
    private String categoryCode;
    private String categoryName;
    private String description;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
