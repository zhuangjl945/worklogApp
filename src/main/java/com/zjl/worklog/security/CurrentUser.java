package com.zjl.worklog.security;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CurrentUser {

    private Long id;
    private String username;
    private Long deptId;
    private String realName;
}
