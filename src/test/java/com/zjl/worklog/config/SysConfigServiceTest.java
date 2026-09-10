package com.zjl.worklog.config;

import com.zjl.worklog.config.entity.SysConfigEntity;
import com.zjl.worklog.config.mapper.SysConfigMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 系统参数运行期读取的行为约束。
 *
 * <p>这个类存在的意义就是「参数页改的东西要真的生效，而且改坏了不能把系统搞瘫」，
 * 所以钉住的三件事：脏值一律回落默认值、查库异常不许往外抛、快照能被 invalidate 立刻刷新。
 */
class SysConfigServiceTest {

    private static SysConfigEntity row(String group, String key, String value) {
        SysConfigEntity e = new SysConfigEntity();
        e.setConfigGroup(group);
        e.setConfigKey(key);
        e.setConfigValue(value);
        e.setConfigLabel(key);
        return e;
    }

    private static SysConfigService serviceWith(SysConfigEntity... rows) {
        SysConfigMapper mapper = mock(SysConfigMapper.class);
        when(mapper.selectAll()).thenReturn(List.of(rows));
        return new SysConfigService(mapper);
    }

    @Test
    @DisplayName("整数取值容忍 8.0 这类写法，脏值和缺失回落默认值")
    void intToleratesDecimalNotationAndFallsBack() {
        SysConfigService svc = serviceWith(
                row("rate_limit", "ip_count", "8.0"),
                row("rate_limit", "ip_window_seconds", "abc"),
                row("rate_limit", "phone_hour", "  "),
                row("rate_limit", "channel_qpm", "999999"));

        assertEquals(8, svc.getInt("rate_limit", "ip_count", 30));
        assertEquals(30, svc.getInt("rate_limit", "ip_window_seconds", 30));
        assertEquals(60, svc.getInt("rate_limit", "phone_hour", 60));
        assertEquals(60, svc.getInt("rate_limit", "not_exists", 60));
        // 限流阈值不做夹取：参数页给多少就用多少（夹取只留给有硬天花板的上传大小）
        assertEquals(999999, svc.getInt("rate_limit", "channel_qpm", 30));
    }

    @Test
    @DisplayName("长整型与布尔取值：多种写法都要认")
    void longAndBooleanParsers() {
        SysConfigService svc = serviceWith(
                row("system", "jwt_expire_seconds", "86400"),
                row("ticket_flow", "auto_confirm", "ON"),
                row("ticket_flow", "notify", "0"),
                row("ticket_flow", "weird", "maybe"));

        assertEquals(86400L, svc.getLong("system", "jwt_expire_seconds", 7200L));
        assertTrue(svc.getBoolean("ticket_flow", "auto_confirm", false));
        assertFalse(svc.getBoolean("ticket_flow", "notify", true));
        // 认不出来的写法按调用方给的默认值走，不猜
        assertTrue(svc.getBoolean("ticket_flow", "weird", true));
        assertFalse(svc.getBoolean("ticket_flow", "weird", false));
    }

    @Test
    @DisplayName("直传大小上限：兜底 10MB，并按住 1MB~50MB")
    void uploadSizeIsClamped() {
        assertEquals(10, serviceWith().maxUploadFileSizeMb());
        assertEquals(8, serviceWith(row("upload", "max_file_size_mb", "8")).maxUploadFileSizeMb());
        // 填 0 或负数会让所有直传失败，按最小可用值执行
        assertEquals(1, serviceWith(row("upload", "max_file_size_mb", "0")).maxUploadFileSizeMb());
        assertEquals(1, serviceWith(row("upload", "max_file_size_mb", "-5")).maxUploadFileSizeMb());
        // 填 999MB 不能突破硬天花板
        assertEquals(50, serviceWith(row("upload", "max_file_size_mb", "999")).maxUploadFileSizeMb());
        assertEquals(50L * 1024 * 1024, serviceWith(row("upload", "max_file_size_mb", "999")).maxUploadBytes());
    }

    @Test
    @DisplayName("查库失败不往外抛，沿用上一份快照或默认值")
    void dbFailureNeverEscapes() {
        SysConfigMapper mapper = mock(SysConfigMapper.class);
        when(mapper.selectAll()).thenThrow(new RuntimeException("db down"));
        SysConfigService svc = new SysConfigService(mapper);

        assertThrows(Exception.class, () -> mapper.selectAll());
        assertEquals(30, svc.getInt("rate_limit", "ip_count", 30));

        // 先成功一轮，再让库挂掉：已加载的快照要继续沿用，限流不能因此失守
        SysConfigMapper okFirst = mock(SysConfigMapper.class);
        when(okFirst.selectAll()).thenReturn(List.of(row("rate_limit", "ip_count", "5")))
                .thenThrow(new RuntimeException("db down"));
        SysConfigService svc2 = new SysConfigService(okFirst);
        assertEquals(5, svc2.getInt("rate_limit", "ip_count", 30));
        assertEquals(5, svc2.getInt("rate_limit", "ip_count", 30));
    }

    @Test
    @DisplayName("快照有 TTL 缓存，invalidate 后立刻读到新值")
    void invalidateRefreshesSnapshotImmediately() {
        SysConfigMapper mapper = mock(SysConfigMapper.class);
        when(mapper.selectAll()).thenReturn(List.of(row("rate_limit", "ip_count", "5")))
                .thenReturn(List.of(row("rate_limit", "ip_count", "9")));
        SysConfigService svc = new SysConfigService(mapper);

        assertEquals(5, svc.getInt("rate_limit", "ip_count", 30));
        // 30 秒内不该再查库，否则高频的限流判断会每请求打一次 DB
        assertEquals(5, svc.getInt("rate_limit", "ip_count", 30));
        verify(mapper, times(1)).selectAll();

        svc.invalidate();
        assertEquals(9, svc.getInt("rate_limit", "ip_count", 30));
        verify(mapper, times(2)).selectAll();
    }
}
