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
        /** 角色枚举名：USER / DEPT_ADMIN / ADMIN。取的是 token 里的生效角色，与实际鉴权一致 */
        private String role;
        /** 角色中文名，前端直接显示，不用再维护一份映射 */
        private String roleLabel;
        /**
         * 库里的角色是否已不同于 token 里的生效角色。
         *
         * <p>角色写进 JWT 是为了不给鉴权加一次查库，代价是「管理员刚改完角色，当事人不重新登录看不到变化」。
         * 这里显式把这个差值告诉前端，由界面提示「重新登录后生效」，否则用户只会觉得按钮莫名失灵。
         */
        private boolean roleStale;
    }
}
