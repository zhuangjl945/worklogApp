package com.zjl.worklog.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponse {

    private String tokenType;
    private String token;
    private long expireIn;
    private UserView user;

    @Data
    @AllArgsConstructor
    public static class UserView {
        private Long id;
        private String username;
        private String realName;
        private Long deptId;
        private Integer status;
    }
}
