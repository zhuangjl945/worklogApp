package com.zjl.worklog.security;

import java.util.ArrayList;
import java.util.List;

/**
 * 可配置的权限点。
 *
 * <p>定位很重要：这里列的**全部是代码里已经存在的收口点**，配置项只是决定「这个角色门槛提到多高」，
 * 不是「凭空给某个角色开一扇门」。所以每个权限点都带一个 floor（内置下限）：
 * 配置只能往严了调（要求更高的角色），往松了调一律按下限执行。
 * 这样即便有人误把「参数配置」下调给普通员工，服务端也不会真的放行。
 *
 * <p>为什么不做成完全自由的 RBAC：那是另一个量级的改动（角色表、权限表、菜单表、动态路由），
 * 而这套系统的角色语义是固定的三级，能力点也是有限的十来个，枚举 + 一张配置表足够，
 * 且代码里每个收口点都能被检索到，不会出现「数据库里有条规则没人知道从哪来」。
 */
public enum Permission {

    /** 哨兵：注解里的默认值，表示「这个接口只受内置角色门槛约束，不参与配置」 */
    NONE("", "", Role.USER, "", "", true),

    // ── 系统管理入口 ─────────────────────────────────────────
    USER_MANAGE("user.manage", "员工与角色管理", Role.ADMIN,
            "新增员工、改姓名科室、授予或收回角色、启用禁用账号", "系统管理", false),
    DEPT_MANAGE("dept.manage", "科室管理", Role.ADMIN,
            "新建/修改/停用/删除科室与科室层级", "系统管理", false),
    SYS_CONFIG_MANAGE("sysConfig.manage", "参数配置", Role.ADMIN,
            "查看并修改系统参数（上传限制、限流、紧急程度等）", "系统管理", false),
    // 不给配置：能改权限的入口一旦可调，一次误操作就会让所有人都再也改不回权限。
    // 「固定」由 isLocked() 表达，别把它误当 placeholder 传进构造器——那样整行会从清单里消失。
    PERMISSION_MANAGE("permission.manage", "权限设置", Role.ADMIN,
            "调整下面这些权限点的角色门槛", "系统管理"),
    WORK_CATEGORY_MANAGE("workCategory.manage", "工作分类维护", Role.DEPT_ADMIN,
            "新建/修改/停用工作分类及其填写模板", "系统管理", false),
    TICKET_CHANNEL_MANAGE("ticketChannel.manage", "登记渠道维护", Role.DEPT_ADMIN,
            "新建/修改手机端问题登记渠道与二维码", "系统管理", false),
    // ── 服务工单 ───────────────────────────────────────────────
    // 下限刻意保持 USER：现网是「谁先看到谁先受理」的互助模式，默认不改它的行为。
    // 真正的意义是把这道门做成可配置的——管理员想把受理收回到「只有科室管理员」，
    // 在 系统管理-权限设置 里调高即可，不必改代码发版；改造前这里连开关都没有。
    TICKET_MANAGE("ticket.manage", "工单受理与流转", Role.USER,
            "受理、派单、回复、完成、退回、归档，以及把工单转成工作记录", "服务工单", false),

    // ── 数据范围能力 ─────────────────────────────────────────
    BOARD_SCOPE_DEPT("board.scopeDept", "看板/列表的「本科室」范围", Role.USER,
            "能看到本科室同事的任务（只读，除非另有编辑权限）", "数据范围", false),
    BOARD_SCOPE_ALL("board.scopeAll", "看板/列表的「全部科室」范围", Role.ADMIN,
            "能跨科室查看全部任务", "数据范围", false),
    REPORT_DEPT_CROSS("report.deptCross", "工作量报表的跨科室筛选", Role.ADMIN,
            "报表里能自由选择科室；未放开时科室管理员锁定本科室、普通员工只统计本人", "数据范围", false),
    RECORD_EDIT_DEPT_OTHERS("record.editDeptOthers", "编辑本科室他人记录", Role.DEPT_ADMIN,
            "在看板上推进本科室同事的任务状态（删除仍只允许本人）", "数据范围", false);

    private final String key;
    private final String label;
    /** 内置下限：配置值低于它时一律按它执行，配置永远不能突破 */
    private final Role floor;
    private final String description;
    private final String group;
    /** 哨兵项为 true，不参与清单与配置 */
    private final boolean placeholder;

    Permission(String key, String label, Role floor, String description, String group) {
        this(key, label, floor, description, group, false);
    }

    Permission(String key, String label, Role floor, String description, String group, boolean placeholder) {
        this.key = key;
        this.label = label;
        this.floor = floor;
        this.description = description;
        this.group = group;
        this.placeholder = placeholder;
    }

    public String getKey() {
        return key;
    }

    public String getLabel() {
        return label;
    }

    public Role getFloor() {
        return floor;
    }

    public String getDescription() {
        return description;
    }

    public String getGroup() {
        return group;
    }

    public boolean isPlaceholder() {
        return placeholder;
    }

    /** 该权限点是否允许被配置下调/上调：只有「权限设置」本身固定给系统管理员 */
    public boolean isLocked() {
        return this == PERMISSION_MANAGE;
    }

    /** 给前端和配置页用的清单（排除哨兵） */
    public static List<Permission> catalog() {
        List<Permission> list = new ArrayList<>();
        for (Permission p : values()) {
            if (!p.placeholder) {
                list.add(p);
            }
        }
        return list;
    }

    /** 未知 key 返回 null，由调用方决定是告警还是忽略 */
    public static Permission ofKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        for (Permission p : values()) {
            if (!p.placeholder && p.key.equals(v)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 把配置值收敛到合法区间：低于内置下限一律抬到下限。
     *
     * <p>「权限设置」本身不参与配置，直接返回下限，避免管理员把自己锁在门外。
     */
    public Role clamp(Role configured) {
        if (isLocked() || configured == null) {
            return floor;
        }
        return configured.atLeast(floor) ? configured : floor;
    }
}
