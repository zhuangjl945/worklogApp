package com.zjl.worklog.config;

import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.config.entity.SysConfigEntity;
import com.zjl.worklog.config.mapper.SysConfigMapper;
import com.zjl.worklog.security.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「参数配置」页的写入闸门。
 *
 * <p>要钉住的原则只有一句：页面上能改的，必须是真的能生效的。
 * 改造前 system/upload/rate_limit/oss 这些分组写进库没人读，界面回「已保存」而行为一点不变，
 * 管理员会以为自己已经把限流调过了——这比不让改更危险。
 */
class SysConfigControllerTest {

    private final SysConfigMapper mapper = mock(SysConfigMapper.class);
    private final SysConfigService sysConfigService = mock(SysConfigService.class);
    private final LoggingLevelService loggingLevelService = mock(LoggingLevelService.class);
    private final SysConfigController controller = new SysConfigController(mapper, sysConfigService, loggingLevelService);

    private static SysConfigEntity row(long id, String group, String key, String label) {
        SysConfigEntity e = new SysConfigEntity();
        e.setId(id);
        e.setConfigGroup(group);
        e.setConfigKey(key);
        e.setConfigValue("1");
        e.setConfigLabel(label);
        return e;
    }

    private static SysConfigController.BatchItem item(long id, String value) {
        SysConfigController.BatchItem b = new SysConfigController.BatchItem();
        b.setId(id);
        b.setConfigValue(value);
        return b;
    }

    @Test
    @DisplayName("只有运行期真有读取方的键算生效")
    void runtimeEffectiveWhitelist() {
        assertTrue(SysConfigController.isRuntimeEffective("rate_limit", "ip_count"));
        assertTrue(SysConfigController.isRuntimeEffective("system", "jwt_expire_seconds"));
        assertTrue(SysConfigController.isRuntimeEffective("upload", "max_file_size_mb"));
        // 整组放行：紧急程度/工单流转/登记表单都是动态键
        assertTrue(SysConfigController.isRuntimeEffective("urgency", "anything_new"));
        assertTrue(SysConfigController.isRuntimeEffective("ticket_flow", "auto_confirm_days"));
        assertTrue(SysConfigController.isRuntimeEffective("ticket_form", "require_phone"));
        assertTrue(SysConfigController.isRuntimeEffective("logging", "root_level"));
        assertTrue(SysConfigController.isRuntimeEffective("logging", "app_level"));
        // 这些的实际取值来自 application.yml，页面上改了没人读
        assertFalse(SysConfigController.isRuntimeEffective("oss", "bucket"));
        assertFalse(SysConfigController.isRuntimeEffective("oss", "endpoint"));
        assertFalse(SysConfigController.isRuntimeEffective("system", "site_name"));
        assertFalse(SysConfigController.isRuntimeEffective("upload", "max_request_size_mb"));
        assertFalse(SysConfigController.isRuntimeEffective("rate_limit", "not_a_real_key"));
        // 脏数据兜底：组为 null 不认，键带空白仍能命中
        assertFalse(SysConfigController.isRuntimeEffective(null, "ip_count"));
        assertTrue(SysConfigController.isRuntimeEffective("rate_limit", "  ip_count  "));
    }

    @Test
    @DisplayName("批量保存里混进不可生效的键时，整批拒绝且不落库")
    void batchRejectsNonEffectiveKeysAtomically() {
        when(mapper.selectById(5L)).thenReturn(row(5L, "rate_limit", "ip_count", "单IP提交次数"));
        when(mapper.selectById(9L)).thenReturn(row(9L, "oss", "endpoint", "OSS Endpoint"));

        BizException e = assertThrows(BizException.class,
                () -> controller.batchUpdate(List.of(item(5L, "30"), item(9L, "oss-cn-shanghai.aliyuncs.com"))));

        assertEquals(40003, e.getCode());
        assertTrue(e.getMessage().contains("不会生效"));
        // 半批写入会在页面上呈现成「有的保存了有的没有」，最难解释，所以一条都不能写
        verify(mapper, never()).updateValue(anyLong(), anyString());
        verify(sysConfigService, never()).invalidate();
    }

    @Test
    @DisplayName("可生效的键正常保存，并让运行期快照立刻刷新")
    void batchUpdatesEffectiveKeysAndInvalidates() {
        when(mapper.selectById(5L)).thenReturn(row(5L, "rate_limit", "ip_count", "单IP提交次数"));
        when(mapper.selectById(6L)).thenReturn(row(6L, "system", "jwt_expire_seconds", "登录有效期"));

        controller.batchUpdate(List.of(item(5L, "30"), item(6L, "3600")));

        verify(mapper).updateValue(5L, "30");
        verify(mapper).updateValue(6L, "3600");
        // 不等 30 秒 TTL，也不重启
        verify(sysConfigService).invalidate();
        verify(loggingLevelService).applyNow();
    }

    @Test
    @DisplayName("日志级别只认 TRACE/DEBUG/INFO/WARN/ERROR/OFF，脏值整批拒绝")
    void batchRejectsInvalidLogLevel() {
        when(mapper.selectById(3L)).thenReturn(row(3L, "logging", "root_level", "全局日志级别"));

        BizException e = assertThrows(BizException.class,
                () -> controller.batchUpdate(List.of(item(3L, "VERBOSE"))));

        assertEquals(40001, e.getCode());
        verify(mapper, never()).updateValue(anyLong(), anyString());
        verify(loggingLevelService, never()).applyNow();
    }

    @Test
    @DisplayName("权限门槛的行不能从参数页改，避免绕过 clamp")
    void permissionGroupRowsAreRejected() {
        when(mapper.selectById(77L)).thenReturn(row(77L, PermissionService.CONFIG_GROUP, "user.manage", "员工管理门槛"));

        BizException e = assertThrows(BizException.class,
                () -> controller.batchUpdate(List.of(item(77L, "USER"))));

        assertEquals(40003, e.getCode());
        verify(mapper, never()).updateValue(anyLong(), anyString());
    }

    @Test
    @DisplayName("空批次直接通过，不查库也不刷新缓存")
    void emptyBatchIsNoop() {
        controller.batchUpdate(List.of());
        controller.batchUpdate(null);

        verify(mapper, never()).selectById(anyLong());
        verify(sysConfigService, never()).invalidate();
    }

    @Test
    @DisplayName("参数不存在或 ID 为空都报 40001")
    void unknownTargetIsRejected() {
        when(mapper.selectById(404L)).thenReturn(null);

        assertEquals(40001, assertThrows(BizException.class,
                () -> controller.batchUpdate(List.of(item(404L, "1")))).getCode());

        SysConfigController.BatchItem nullId = new SysConfigController.BatchItem();
        nullId.setConfigValue("1");
        assertEquals(40001, assertThrows(BizException.class,
                () -> controller.batchUpdate(List.of(nullId))).getCode());
    }

    @Test
    @DisplayName("新建还没有读取方的参数时，如实回 effective=false")
    void createReportsEffectiveness() {
        when(mapper.selectByKey(anyString(), anyString())).thenReturn(null);
        when(mapper.insert(any(SysConfigEntity.class))).thenAnswer(inv -> {
            SysConfigEntity e = inv.getArgument(0);
            e.setId(100L);
            return 1;
        });

        SysConfigController.CreateConfigRequest req = new SysConfigController.CreateConfigRequest();
        req.setConfigGroup("ticket_form");
        req.setConfigKey("require_email");
        req.setConfigValue("1");
        req.setConfigLabel("登记必填邮箱");

        Map<String, Object> out = controller.create(req).getData();

        assertEquals(Boolean.TRUE, out.get("effective"));
        verify(sysConfigService).invalidate();
    }

    @Test
    @DisplayName("删除参数不能删到权限门槛的行")
    void deleteRejectsPermissionRows() {
        when(mapper.selectById(77L)).thenReturn(row(77L, PermissionService.CONFIG_GROUP, "user.manage", "员工管理门槛"));
        assertEquals(40003, assertThrows(BizException.class,
                () -> controller.delete(77L)).getCode());
        verify(mapper, never()).deleteById(anyLong());

        when(mapper.selectById(88L)).thenReturn(null);
        assertEquals(40001, assertThrows(BizException.class,
                () -> controller.delete(88L)).getCode());
    }
}