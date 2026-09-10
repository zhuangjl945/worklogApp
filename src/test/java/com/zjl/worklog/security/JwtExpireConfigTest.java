package com.zjl.worklog.security;

import com.zjl.worklog.config.SysConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 登录有效期能不能被「参数配置」页左右。
 *
 * <p>结论是只能缩短、不能延长：application.yml 那份要过 JwtProps 的启动校验（正数且不超过一天），
 * 把它当天花板留着，管理员在页面上失手写个十年，也不会让 token 变成永久通行证。
 * 想延长登录时间必须改配置发版，这条路径才谈得上被 review。
 */
class JwtExpireConfigTest {

    private static JwtTokenService serviceWith(long ymlExpire, SysConfigService sysConfig) {
        JwtProps props = new JwtProps();
        // 密钥只影响签名，这里不签发 token，给个合法长度即可
        props.setSecret("unit-test-secret-at-least-32-bytes-long-000000");
        props.setExpireSeconds(ymlExpire);
        return new JwtTokenService(props, sysConfig);
    }

    /** 模拟「参数页读到的值」；传 null 表示没配过，此时 SysConfigService 会回落到调用方给的默认值 */
    private static SysConfigService configured(Long value) {
        SysConfigService sysConfig = mock(SysConfigService.class);
        when(sysConfig.getLong(anyString(), anyString(), anyLong()))
                .thenAnswer(inv -> value == null ? inv.getArgument(2) : value);
        return sysConfig;
    }

    @Test
    @DisplayName("没配置时用 application.yml 的值")
    void fallsBackToYml() {
        assertEquals(7200L, serviceWith(7200L, configured(null)).effectiveExpireSeconds());
    }

    @Test
    @DisplayName("比 yml 短时按参数页生效：这是这个接口的主要用途")
    void canShorten() {
        assertEquals(600L, serviceWith(7200L, configured(600L)).effectiveExpireSeconds());
    }

    @Test
    @DisplayName("比 yml 长时按 yml 执行：页面上调不长时间")
    void cannotLengthen() {
        assertEquals(7200L, serviceWith(7200L, configured(864000L)).effectiveExpireSeconds());
    }

    @Test
    @DisplayName("失手写 0 或负数也不会把登录变成秒退，下限 60 秒")
    void neverBelowFloor() {
        assertEquals(60L, serviceWith(7200L, configured(0L)).effectiveExpireSeconds());
        assertEquals(60L, serviceWith(7200L, configured(-1L)).effectiveExpireSeconds());
    }
}