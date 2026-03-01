package com.zjl.worklog.dept.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeptTreeNode {

    private Long id;
    private String deptCode;
    private String deptName;
    private Long parentId;
    private Integer sortOrder;
    private Integer status;
    private List<DeptTreeNode> children = new ArrayList<>();
}
