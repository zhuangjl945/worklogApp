package com.zjl.worklog.config;

import com.zjl.worklog.config.entity.SysConfigEntity;
import com.zjl.worklog.config.mapper.SysConfigMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoggingLevelServiceTest {

    private static SysConfigEntity row(String key, String value) {
        SysConfigEntity e = new SysConfigEntity();
        e.setConfigGroup(LoggingLevelService.GROUP);
        e.setConfigKey(key);
        e.setConfigValue(value);
        return e;
    }

    @Test
    @DisplayName("级别解析：合法单词认，脏值和空值回落")
    void parseFallsBackOnGarbage() {
        assertEquals(LogLevel.DEBUG, LoggingLevelService.parse("debug", LogLevel.INFO));
        assertEquals(LogLevel.INFO, LoggingLevelService.parse("  INFO  ", LogLevel.WARN));
        assertEquals(LogLevel.INFO, LoggingLevelService.parse("verbose", LogLevel.INFO));
        assertEquals(LogLevel.WARN, LoggingLevelService.parse("", LogLevel.WARN));
        assertEquals(LogLevel.WARN, LoggingLevelService.parse(null, LogLevel.WARN));
        assertTrue(LoggingLevelService.isValidLevel("OFF"));
        assertFalse(LoggingLevelService.isValidLevel("VERBOSE"));
    }

    @Test
    @DisplayName("applyNow 把三个键分别写到 root / 业务包 / MyBatis")
    void applyNowSetsLoggers() {
        SysConfigMapper mapper = mock(SysConfigMapper.class);
        when(mapper.selectAll()).thenReturn(List.of(
                row(LoggingLevelService.KEY_ROOT, "WARN"),
                row(LoggingLevelService.KEY_APP, "DEBUG"),
                row(LoggingLevelService.KEY_SQL, "ERROR")));

        LoggingSystem loggingSystem = mock(LoggingSystem.class);
        LoggingLevelService svc = new LoggingLevelService(loggingSystem, new SysConfigService(mapper));
        svc.applyNow();

        verify(loggingSystem).setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.WARN);
        verify(loggingSystem).setLogLevel(LoggingLevelService.LOGGER_APP, LogLevel.DEBUG);
        verify(loggingSystem).setLogLevel(LoggingLevelService.LOGGER_MYBATIS, LogLevel.ERROR);
        verify(loggingSystem).setLogLevel(LoggingLevelService.LOGGER_IBATIS, LogLevel.ERROR);
    }
}
