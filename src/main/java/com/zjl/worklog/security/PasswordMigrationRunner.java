package com.zjl.worklog.security;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时执行一次存量明文口令迁移。
 *
 * <p>放在 ApplicationRunner 而不是 @PostConstruct：此时数据源与 MyBatis 已完全就绪，
 * 失败也不会阻断应用启动（登录路径里还有兜底升级）。
 */
@Component
@Order(10)
public class PasswordMigrationRunner implements ApplicationRunner {

    private final PasswordService passwordService;

    public PasswordMigrationRunner(PasswordService passwordService) {
        this.passwordService = passwordService;
    }

    @Override
    public void run(ApplicationArguments args) {
        passwordService.migrateAllLegacy();
    }
}
