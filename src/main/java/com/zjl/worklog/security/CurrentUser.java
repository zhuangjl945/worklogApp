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
    /** 登录态角色。永不为 null：JwtAuthFilter 里缺失或非法值一律降级为 Role.USER */
    private Role role;
}
