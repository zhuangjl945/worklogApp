package com.zjl.worklog.user.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserEntity {

    private Long id;
    private String username;
    private String password;
    private String realName;
    private Long deptId;
    /** 角色枚举名：USER / DEPT_ADMIN / ADMIN；库里为空按 USER 处理（见 Role.of） */
    private String role;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
