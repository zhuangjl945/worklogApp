package com.zjl.worklog.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/**
 * 把「参数配置 → 日志级别」落到 Logback。
 *
 * <p>yml 只提供启动瞬间的默认值；管理员改完参数页后这里立刻 {@link LoggingSystem#setLogLevel}，
 * 不用重启。值非法或库暂时读不到时沿用 INFO/WARN，避免把整站日志掐死或刷爆磁盘。
 */
@Service
public class LoggingLevelService {

    public static final String GROUP = "logging";
    /** 全局根级别 */
    public static final String KEY_ROOT = "root_level";
    /** 本系统包 com.zjl.worklog */
    public static final String KEY_APP = "app_level";
    /** MyBatis SQL 相关 */
    public static final String KEY_SQL = "sql_level";

    static final String LOGGER_APP = "com.zjl.worklog";
    static final String LOGGER_MYBATIS = "org.mybatis";
    static final String LOGGER_IBATIS = "org.apache.ibatis";

    private static final Set<String> ALLOWED = Set.of(
            "TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL", "OFF");

    private static final Logger log = LoggerFactory.getLogger(LoggingLevelService.class);

    private final LoggingSystem loggingSystem;
    private final SysConfigService sysConfig;

    public LoggingLevelService(LoggingSystem loggingSystem, SysConfigService sysConfig) {
        this.loggingSystem = loggingSystem;
        this.sysConfig = sysConfig;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void applyOnStartup() {
        applyNow();
    }

    /** 保存参数后立刻调用；缺项/脏值回落到 INFO（SQL 为 WARN） */
    public void applyNow() {
        LogLevel root = parse(sysConfig.getString(GROUP, KEY_ROOT, "INFO"), LogLevel.INFO);
        LogLevel app = parse(sysConfig.getString(GROUP, KEY_APP, "INFO"), LogLevel.INFO);
        LogLevel sql = parse(sysConfig.getString(GROUP, KEY_SQL, "WARN"), LogLevel.WARN);

        loggingSystem.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, root);
        loggingSystem.setLogLevel(LOGGER_APP, app);
        loggingSystem.setLogLevel(LOGGER_MYBATIS, sql);
        loggingSystem.setLogLevel(LOGGER_IBATIS, sql);

        log.info("日志级别已应用：root={} app={} sql={}", root, app, sql);
    }

    public static boolean isValidLevel(String raw) {
        return raw != null && ALLOWED.contains(raw.trim().toUpperCase(Locale.ROOT));
    }

    static LogLevel parse(String raw, LogLevel fallback) {
        if (!isValidLevel(raw)) {
            return fallback;
        }
        try {
            return LogLevel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
