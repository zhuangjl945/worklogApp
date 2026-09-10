package com.zjl.worklog.ticket.dto;

import com.zjl.worklog.config.entity.SysConfigEntity;
import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单「流转」规则：目前只有一项——报修人超时未确认时，由系统替他确认。
 *
 * <p>业务动因：维修人员标记完成后，工单停在「待报修人确认」。报修人忘了点，单子就一直挂着，
 * 受理台的待办角标消不掉、科室完成率也失真。所以给这段等待加个时限：超时即视为已解决，
 * 状态照旧走 20 -> 30，只是在流转记录里写明是「系统自动确认」，与报修人亲手确认区分开。
 *
 * <p>数据源是 sys_config 的 ticket_flow 分组，管理员在「参数配置 → 工单流转」改完即生效
 * （下一轮扫描就会读到新值，不用重启）。本对象与 {@link TicketFormRules} 一样是
 * 「读出来就已经夹好」的值对象：字段全是基本类型、区间全部收敛过，调用方不必再判 null。
 *
 * <p>用 JavaBean 而不是 record：这个类可能被序列化给前端，record 的访问器没有 get 前缀，
 * 字段改名时前后端约定会悄悄错位。
 */
@Data
public class TicketFlowRules {

    /** sys_config 里的分组名，参数配置页显示为「工单流转」 */
    public static final String CONFIG_GROUP = "ticket_flow";

    /**
     * 自动确认时限的硬边界（小时）。
     *
     * <p>下限 1 小时：不给「0 小时」这种把刚标记完成的工单当场确认的参数留口子；
     * 上限 168 小时（7 天）：再长就等同于关闭，真要长期等报修人确认应该直接关开关，
     * 而不是填一个夸张数字还以为它生效。
     */
    public static final int HOURS_MIN = 1;
    public static final int HOURS_MAX = 168;

    /** 默认时限：8 小时 */
    public static final int DEFAULT_HOURS = 8;

    /** 是否启用「报修人超时未确认则自动确认」 */
    private boolean autoConfirmEnabled;
    /** 从维修人员标记完成开始计时，超过多少小时未确认就自动确认 */
    private int autoConfirmHours;

    /**
     * 默认值 = 开启 + 8 小时，与预置 SQL 保持一致。
     *
     * <p>兜底分支（参数被删空、查库异常）为什么默认「开」而不是「关」：
     * 默认关的现象是「工单又永远停在待确认」，正好是当初要解决的问题；
     * 而自动确认只推动「已完成标记且超时」的工单，不会误伤处理中的单子，
     * 误判面比登记表单参数小得多。不想自动确认，请在参数配置页显式关掉开关。
     */
    public static TicketFlowRules defaults() {
        TicketFlowRules r = new TicketFlowRules();
        r.autoConfirmEnabled = true;
        r.autoConfirmHours = DEFAULT_HOURS;
        return r;
    }

    /**
     * 把 sys_config 的行解析成规则。缺失的键沿用默认值，非法值丢弃该键继续往下读。
     *
     * <p>这里不抛异常：参数配置页允许自由增删键，不能让一次误删把定时任务打挂。
     */
    public static TicketFlowRules fromRows(List<SysConfigEntity> rows) {
        TicketFlowRules r = defaults();
        if (rows == null || rows.isEmpty()) {
            return r;
        }
        Map<String, String> kv = new HashMap<>();
        for (SysConfigEntity row : rows) {
            if (row != null && row.getConfigKey() != null) {
                kv.put(row.getConfigKey().trim(), row.getConfigValue());
            }
        }
        r.autoConfirmEnabled = boolOf(kv, "auto_confirm_enabled", r.autoConfirmEnabled);
        r.autoConfirmHours = intOf(kv, "auto_confirm_hours", r.autoConfirmHours);
        r.clamp();
        return r;
    }

    /** 时限夹进安全区间 */
    private void clamp() {
        // 0 / 负数是明显误填，退回默认值；超上限的按上限夹住：管理员想把节奏放慢，填个大数就该看到大数的效果
        if (autoConfirmHours < HOURS_MIN) {
            autoConfirmHours = DEFAULT_HOURS;
        }
        autoConfirmHours = Math.max(HOURS_MIN, Math.min(HOURS_MAX, autoConfirmHours));
    }

    /** 生效中的时限：关闭时返回 null，调用方据此决定「要不要给前端下发倒计时字段」 */
    public Integer effectiveAutoConfirmHours() {
        return autoConfirmEnabled ? autoConfirmHours : null;
    }

    private static boolean boolOf(Map<String, String> kv, String key, boolean fallback) {
        String raw = kv.get(key);
        if (raw == null) {
            return fallback;
        }
        String v = raw.trim().toLowerCase();
        if (v.isEmpty()) {
            return fallback;
        }
        return switch (v) {
            case "1", "true", "yes", "on", "enabled" -> true;
            case "0", "false", "no", "off", "disabled" -> false;
            default -> fallback;
        };
    }

    private static int intOf(Map<String, String> kv, String key, int fallback) {
        String raw = kv.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            // 参数页用 el-input-number，正常只会存整数；这里顺手容忍「8.0」这类手改出来的写法
            return (int) Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}