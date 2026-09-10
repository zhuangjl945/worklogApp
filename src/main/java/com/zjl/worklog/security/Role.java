package com.zjl.worklog.security;

/**
 * 内置角色（三级，够用且可预测；要自定义角色再演进成 RBAC）。
 *
 * <p>层级顺序按「权限递增」排列，{@link #atLeast} 依赖 ordinal()，
 * 因此新增角色只能追加在 ADMIN 之前，不能插到中间。
 */
public enum Role {

    /** 普通员工：只管自己的记录，本科室数据只读 */
    USER("普通员工"),
    /** 科室管理员：本科室数据可编辑，可维护本科室工作分类 */
    DEPT_ADMIN("科室管理员"),
    /** 系统管理员：跨科室数据 + 员工/科室/参数等系统管理 */
    ADMIN("系统管理员");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 未知/缺失角色一律降级为最小权限，绝不默认放行 */
    public static Role of(String raw) {
        if (raw == null || raw.isBlank()) {
            return USER;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return USER;
        }
    }

    /** 当前角色是否不低于目标角色 */
    public boolean atLeast(Role target) {
        return this.ordinal() >= target.ordinal();
    }
}