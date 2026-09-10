package com.zjl.worklog.security;

/**
 * 行级数据范围判定。
 *
 * <p>接口门槛（谁能调）由 {@link RequireRole} 管，这里只管「这条数据他能不能看/能不能改」，
 * 两者分开是因为同一接口常常读放宽、写收紧（例如科室看板人人能看，但只有科室管理员能改别人的）。
 */
public final class DataScope {

    private DataScope() {
    }

    /** 能否查看他人数据：本科室（DEPT_ADMIN 起）或跨科室（ADMIN） */
    public static boolean canViewOthers(CurrentUser cu) {
        return cu != null && cu.getRole().atLeast(Role.DEPT_ADMIN);
    }

    /** 能否跨科室查看：仅系统管理员 */
    public static boolean canCrossDept(CurrentUser cu) {
        return cu != null && cu.getRole() == Role.ADMIN;
    }

    /** 是否管理员（员工/科室/参数等系统管理的写权限） */
    public static boolean isAdmin(CurrentUser cu) {
        return cu != null && cu.getRole() == Role.ADMIN;
    }

    /**
     * 这条记录归谁管：本人、或记录人属于其科室且角色达到「编辑他人记录」门槛的人、或系统管理员。
     *
     * <p>deptEditMin 由 {@link PermissionService} 按 RECORD_EDIT_DEPT_OTHERS 权限点给出，
     * 管理员把它调高到 ADMIN，就等于关掉「科室管理员可改本科室同事记录」这条能力。
     *
     * <p>注意 dept_id 为 0 是历史兜底值（账号没绑科室时写入的），不能当成有效科室，
     * 否则所有无科室记录会被同一个 0 串成「同科室」。
     */
    public static boolean canEdit(CurrentUser cu, Long ownerId, Long ownerDeptId, Role deptEditMin) {
        if (cu == null || ownerId == null) {
            return false;
        }
        if (ownerId.equals(cu.getId())) {
            return true;
        }
        if (cu.getRole() == Role.ADMIN) {
            return true;
        }
        if (deptEditMin != null && cu.getRole().atLeast(deptEditMin)
                && ownerDeptId != null && ownerDeptId != 0L) {
            return ownerDeptId.equals(cu.getDeptId());
        }
        return false;
    }

    /** 同一科室（双方都绑了科室才算） */
    public static boolean sameDept(CurrentUser cu, Long deptId) {
        return cu != null && cu.getDeptId() != null && deptId != null && cu.getDeptId().equals(deptId);
    }
}