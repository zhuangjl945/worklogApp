package com.zjl.worklog.dept.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DeptEntity {

    private Long id;
    private String deptCode;
    private String deptName;
    private Long parentId;
    private Integer sortOrder;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
