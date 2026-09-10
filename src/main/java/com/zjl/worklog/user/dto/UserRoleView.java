package com.zjl.worklog.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 人员角色设置页的行视图。
 *
 * <p>与 UserView 分开是有意为之：这一页要按科室看人，所以必须带 dept_name；
 * 但不需要 createTime，更不该把 password 之类的字段再多端出去一次。
 * 接口只回这九个字段，收口面越小越好。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserRoleView {

    private Long id;
    private String username;
    private String realName;
    private Long deptId;
    /** 科室名由 SQL 直接带出；账号没绑科室时为 null，界面显示「未绑定」 */
    private String deptName;
    /** 角色枚举名；脏值由 Controller 统一按 Role.of 归一后再回给前端 */
    private String role;
    /** 角色中文名，与 Role.getLabel() 同源，前端不再各存一份映射 */
    private String roleLabel;
    private Integer status;
    /** 最近一次改动该账号的时间（MySQL ON UPDATE 自动维护），用来核对调整是否真的落库 */
    private LocalDateTime updateTime;
}