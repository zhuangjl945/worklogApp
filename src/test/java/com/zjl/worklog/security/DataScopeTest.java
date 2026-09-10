package com.zjl.worklog.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行级数据范围判定，重点是「编辑他人记录」这道门槛能被权限配置调高。
 *
 * <p>WorkRecordController 传进来的 deptEditMin 来自 Permission.RECORD_EDIT_DEPT_OTHERS，
 * 所以这里同时验证默认（DEPT_ADMIN）与调严（ADMIN）两种配置下的行为。
 */
class DataScopeTest {

    private static final Long DEPT_A = 3L;
    private static final Long DEPT_B = 4L;

    private static CurrentUser user(Long id, Long deptId, Role role) {
        return new CurrentUser(id, "u" + id, deptId, "用户" + id, role);
    }

    @Test
    @DisplayName("本人记录永远可改，删除另说（后端只允许本人删除）")
    void ownerCanAlwaysEdit() {
        CurrentUser cu = user(10L, DEPT_A, Role.USER);
        assertTrue(DataScope.canEdit(cu, 10L, DEPT_A, Role.ADMIN));
    }

    @Test
    @DisplayName("默认门槛下：科室管理员可改本科室同事，普通员工与跨科室都不行")
    void defaultThreshold() {
        Role deptEditMin = Permission.RECORD_EDIT_DEPT_OTHERS.getFloor();
        CurrentUser deptAdmin = user(11L, DEPT_A, Role.DEPT_ADMIN);
        CurrentUser plain = user(12L, DEPT_A, Role.USER);
        assertTrue(DataScope.canEdit(deptAdmin, 20L, DEPT_A, deptEditMin));
        assertFalse(DataScope.canEdit(plain, 20L, DEPT_A, deptEditMin));
        assertFalse(DataScope.canEdit(deptAdmin, 20L, DEPT_B, deptEditMin), "跨科室要的是权限点 board.scopeAll，不是这条");
        assertTrue(DataScope.canEdit(user(13L, null, Role.ADMIN), 20L, DEPT_A, deptEditMin));
    }

    @Test
    @DisplayName("把 record.editDeptOthers 调到 ADMIN：科室管理员就改不动同事记录了")
    void tightenedThresholdBlocksDeptAdmin() {
        Role deptEditMin = Permission.RECORD_EDIT_DEPT_OTHERS.clamp(Role.ADMIN);
        CurrentUser deptAdmin = user(11L, DEPT_A, Role.DEPT_ADMIN);
        assertFalse(DataScope.canEdit(deptAdmin, 20L, DEPT_A, deptEditMin));
        assertTrue(DataScope.canEdit(deptAdmin, 11L, DEPT_A, deptEditMin), "自己的记录不受影响");
        assertTrue(DataScope.canEdit(user(13L, null, Role.ADMIN), 20L, DEPT_A, deptEditMin));
    }

    @Test
    @DisplayName("dept_id 为 0 是历史兜底值，不能把无科室记录串成同科室")
    void zeroDeptIsNotARealDept() {
        CurrentUser deptAdmin = user(11L, DEPT_A, Role.DEPT_ADMIN);
        assertFalse(DataScope.canEdit(deptAdmin, 20L, 0L, Role.USER));
        CurrentUser noDept = user(14L, 0L, Role.DEPT_ADMIN);
        assertFalse(DataScope.canEdit(noDept, 20L, 0L, Role.USER));
    }
}