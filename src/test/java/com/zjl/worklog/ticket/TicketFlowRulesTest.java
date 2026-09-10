package com.zjl.worklog.ticket;

import com.zjl.worklog.config.entity.SysConfigEntity;
import com.zjl.worklog.ticket.dto.TicketFlowRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「工单流转」参数的解析单测。
 *
 * <p>这个开关决定的是「系统能不能替报修人点确认」，属于会改数据的默认值：
 * 参数缺失、值写错、分组被删空，都必须落在一个说得清的地方（默认开启 + 8 小时），
 * 并且绝不能因为一次脏值就抛异常把定时任务打断。
 */
class TicketFlowRulesTest {

    private static SysConfigEntity row(String key, String value) {
        SysConfigEntity e = new SysConfigEntity();
        e.setConfigGroup(TicketFlowRules.CONFIG_GROUP);
        e.setConfigKey(key);
        e.setConfigValue(value);
        return e;
    }

    private static List<SysConfigEntity> rows(String... kv) {
        List<SysConfigEntity> list = new ArrayList<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            list.add(row(kv[i], kv[i + 1]));
        }
        return list;
    }

    @Test
    @DisplayName("配置缺失时是「开启 + 8 小时」，与预置 SQL 一致")
    void emptyConfigUsesProductDefault() {
        TicketFlowRules r = TicketFlowRules.fromRows(List.of());
        assertTrue(r.isAutoConfirmEnabled());
        assertEquals(8, r.getAutoConfirmHours());
        assertEquals(8, r.effectiveAutoConfirmHours());
    }

    @Test
    @DisplayName("分组被删干净（null）也不抛异常")
    void nullRowsFallBackToDefaults() {
        assertEquals(8, TicketFlowRules.fromRows(null).getAutoConfirmHours());
    }

    @Test
    @DisplayName("开关认 1/0，也认 true/false/off；脏值回默认而不是猜")
    void switchToleratesDirtyValues() {
        assertFalse(TicketFlowRules.fromRows(rows("auto_confirm_enabled", "0")).isAutoConfirmEnabled());
        assertFalse(TicketFlowRules.fromRows(rows("auto_confirm_enabled", "false")).isAutoConfirmEnabled());
        assertFalse(TicketFlowRules.fromRows(rows("auto_confirm_enabled", "off")).isAutoConfirmEnabled());
        assertTrue(TicketFlowRules.fromRows(rows("auto_confirm_enabled", "1")).isAutoConfirmEnabled());
        // 管理员写了个「是」：按默认值（开启）处理，同时时限仍是 8 小时，不会跟着变成 0
        TicketFlowRules junk = TicketFlowRules.fromRows(rows("auto_confirm_enabled", "是"));
        assertTrue(junk.isAutoConfirmEnabled());
        assertEquals(TicketFlowRules.DEFAULT_HOURS, junk.getAutoConfirmHours());
    }

    @Test
    @DisplayName("关闭后不再下发时限：前端据此不显示倒计时")
    void disabledMeansNoDeadline() {
        TicketFlowRules off = TicketFlowRules.fromRows(rows("auto_confirm_enabled", "0", "auto_confirm_hours", "12"));
        assertFalse(off.isAutoConfirmEnabled());
        assertNull(off.effectiveAutoConfirmHours());
    }

    @Test
    @DisplayName("时限夹进 1~168 小时：0 和负数回默认，超上限按上限")
    void hoursAreClamped() {
        assertEquals(TicketFlowRules.DEFAULT_HOURS, TicketFlowRules.fromRows(rows("auto_confirm_hours", "0")).getAutoConfirmHours());
        assertEquals(TicketFlowRules.DEFAULT_HOURS, TicketFlowRules.fromRows(rows("auto_confirm_hours", "-3")).getAutoConfirmHours());
        assertEquals(TicketFlowRules.HOURS_MAX, TicketFlowRules.fromRows(rows("auto_confirm_hours", "2000")).getAutoConfirmHours());
        assertEquals(2, TicketFlowRules.fromRows(rows("auto_confirm_hours", "2")).getAutoConfirmHours());
        // 参数页理论上只会写整数，但手工改库写成 8.5 也不能让任务崩掉
        assertEquals(8, TicketFlowRules.fromRows(rows("auto_confirm_hours", "8.5")).getAutoConfirmHours());
        assertEquals(TicketFlowRules.DEFAULT_HOURS, TicketFlowRules.fromRows(rows("auto_confirm_hours", "abc")).getAutoConfirmHours());
    }
}
