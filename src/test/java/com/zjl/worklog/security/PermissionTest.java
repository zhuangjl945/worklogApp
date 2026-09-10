package com.zjl.worklog.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「权限设置」的服务端不变量。
 *
 * <p>这些断言就是这套设计的全部承诺：配置只能收紧、哨兵不进清单、锁住的项谁都改不动。
 * 改 Permission 枚举时若这里红了，说明动到了安全下限，要当成代码评审信号而不只是改测试。
 */
class PermissionTest {

    @Test
    @DisplayName("配置低于内置下限一律被抬回下限（只可收紧，不可放宽）")
    void clampNeverGoesBelowFloor() {
        assertEquals(Role.ADMIN, Permission.USER_MANAGE.clamp(Role.USER));
        assertEquals(Role.ADMIN, Permission.USER_MANAGE.clamp(Role.DEPT_ADMIN));
        assertEquals(Role.DEPT_ADMIN, Permission.WORK_CATEGORY_MANAGE.clamp(Role.USER));
        assertEquals(Role.USER, Permission.BOARD_SCOPE_DEPT.clamp(Role.USER));
        // 收紧（高于下限）原样生效
        assertEquals(Role.ADMIN, Permission.BOARD_SCOPE_DEPT.clamp(Role.ADMIN));
        assertEquals(Role.ADMIN, Permission.RECORD_EDIT_DEPT_OTHERS.clamp(Role.ADMIN));
        // 没配过的权限点按下限跑
        assertEquals(Role.DEPT_ADMIN, Permission.RECORD_EDIT_DEPT_OTHERS.clamp(null));
    }

    @Test
    @DisplayName("「权限设置」本身固定给系统管理员，任何配置都改不动")
    void permissionManageIsLocked() {
        assertTrue(Permission.PERMISSION_MANAGE.isLocked(), "权限设置入口一旦可调，误操作后谁都改不回来");
        assertEquals(Role.ADMIN, Permission.PERMISSION_MANAGE.clamp(Role.USER));
        assertEquals(Role.ADMIN, Permission.PERMISSION_MANAGE.clamp(Role.DEPT_ADMIN));
        assertEquals(Role.ADMIN, Permission.PERMISSION_MANAGE.clamp(Role.ADMIN));
    }

    @Test
    @DisplayName("清单里没有哨兵、key 唯一、下限不为空")
    void catalogIsWellFormed() {
        Set<String> keys = new HashSet<>();
        for (Permission p : Permission.catalog()) {
            assertTrue(keys.add(p.getKey()), "权限点 key 重复：" + p.getKey());
            assertFalse(p.isPlaceholder());
            assertNotNull(p.getFloor());
            assertNotNull(p.getLabel());
            assertNotNull(p.getGroup());
        }
        assertFalse(keys.contains(Permission.NONE.getKey()));
        // 数量变了要同步 README 的权限点表格与权限设置页
        assertEquals(11, keys.size(), "权限点数量与文档不一致：README「权限设置」小节");
    }

    @Test
    @DisplayName("未知 key 返回 null，调用方决定忽略还是报错")
    void ofKeyRejectsUnknown() {
        assertNull(Permission.ofKey(null));
        assertNull(Permission.ofKey("  "));
        assertNull(Permission.ofKey("not.exists"));
        // 哨兵不允许被配置引用
        assertNull(Permission.ofKey(""));
        assertSame(Permission.BOARD_SCOPE_ALL, Permission.ofKey(" board.scopeAll "));
    }

    @Test
    @DisplayName("未知角色一律降级为最小权限，绝不默认放行")
    void roleOfFallsBackToUser() {
        assertEquals(Role.USER, Role.of(null));
        assertEquals(Role.USER, Role.of("ROOT"));
        assertEquals(Role.ADMIN, Role.of("admin"));
        assertTrue(Role.ADMIN.atLeast(Role.DEPT_ADMIN));
        assertFalse(Role.USER.atLeast(Role.DEPT_ADMIN));
    }
}
