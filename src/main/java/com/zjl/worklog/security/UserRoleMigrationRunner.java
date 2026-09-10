package com.zjl.worklog.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 角色列的启动期迁移：给 user 表补 role，并把内置 admin 升为 ADMIN。
 *
 * <p>放在 ApplicationRunner（顺序在口令迁移之后）：此时数据源已就绪，且失败只记日志不阻断启动——
 * 角色收口依赖这一列，缺列时 JwtAuthFilter 会把所有人降级成 USER，属于「权限收紧」而不是「权限放大」，
 * 所以宁可带着告警启动，也不要因为迁移失败让整个系统起不来。
 */
@Component
@Order(20)
public class UserRoleMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserRoleMigrationRunner.class);

    private final JdbcTemplate jdbcTemplate;

    public UserRoleMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'role'",
                    Integer.class);
            if (exists == null || exists == 0) {
                jdbcTemplate.execute("ALTER TABLE `user` ADD COLUMN `role` VARCHAR(20) NOT NULL DEFAULT 'USER' "
                        + "COMMENT '角色：USER-普通员工，DEPT_ADMIN-科室管理员，ADMIN-系统管理员' AFTER `dept_id`");
                log.info("user.role 列已创建，存量账号默认按 USER 处理");
            }
            // 空值兜底成最小权限
            jdbcTemplate.update("UPDATE `user` SET `role` = 'USER' WHERE `role` IS NULL OR `role` = ''");
            // 引导管理员：否则升级后没人能进「员工管理」去授予角色
            int bootstrapped = jdbcTemplate.update("UPDATE `user` SET `role` = 'ADMIN' WHERE `username` = 'admin'");
            if (bootstrapped > 0) {
                log.info("内置 admin 账号已置为 ADMIN，请尽快在「员工管理」中为各科室负责人授予 DEPT_ADMIN");
            }
        } catch (Exception e) {
            log.warn("user.role 迁移失败，角色收口将按最小权限（USER）运行，请手工执行 db/user_role_module.sql：{}", e.getMessage());
        }
    }
}