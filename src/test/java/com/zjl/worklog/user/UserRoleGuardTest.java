package com.zjl.worklog.user;

import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.Role;
import com.zjl.worklog.security.UserContext;
import com.zjl.worklog.user.entity.UserEntity;
import com.zjl.worklog.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 人员角色设置的两条保护。
 *
 * <p>单条改角色和批量改角色都走这两个判定，所以这里钉住的是「批量勾选一串人点授予」这条路径上
 * 最贵的两个事故：管理员把自己锁出去、以及全系统最后一个管理员被降级后再没人能改权限。
 */
class UserRoleGuardTest {

    private static UserEntity user(long id, String role, int status) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setUsername("u" + id);
        u.setRealName("用户" + id);
        u.setDeptId(3L);
        u.setRole(role);
        u.setStatus(status);
        return u;
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("不能改自己的角色或状态；不改这两项时放行")
    void selfLockIsBlocked() {
        UserContext.set(new CurrentUser(1L, "admin", null, "管理员", Role.ADMIN));

        BizException e = assertThrows(BizException.class, () -> UserRoleGuard.guardSelfLock(1L, true));
        assertEquals(40003, e.getCode());
        assertDoesNotThrow(() -> UserRoleGuard.guardSelfLock(1L, false));
        assertDoesNotThrow(() -> UserRoleGuard.guardSelfLock(2L, true));
    }

    @Test
    @DisplayName("未登录（上下文为空）时不误判成「改自己」")
    void nullContextNeverLooksLikeSelf() {
        UserContext.clear();
        assertDoesNotThrow(() -> UserRoleGuard.guardSelfLock(1L, true));
    }

    @Test
    @DisplayName("最后一个启用的系统管理员不能被降级，也不能被禁用")
    void lastAdminIsProtected() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.countByRole(anyString())).thenReturn(1L);
        UserEntity onlyAdmin = user(1L, Role.ADMIN.name(), 1);

        BizException demote = assertThrows(BizException.class,
                () -> UserRoleGuard.guardLastAdmin(mapper, onlyAdmin, Role.USER, 1));
        assertEquals(40004, demote.getCode());

        BizException disable = assertThrows(BizException.class,
                () -> UserRoleGuard.guardLastAdmin(mapper, onlyAdmin, Role.ADMIN, 0));
        assertEquals(40004, disable.getCode());
    }

    @Test
    @DisplayName("还有别的启用管理员时，降级放行")
    void demoteAllowedWhenAnotherAdminExists() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.countByRole(anyString())).thenReturn(2L);

        assertDoesNotThrow(() -> UserRoleGuard.guardLastAdmin(
                mapper, user(1L, Role.ADMIN.name(), 1), Role.DEPT_ADMIN, 1));
    }

    @Test
    @DisplayName("升权与已禁用的管理员不受「最后一个管理员」限制")
    void promoteAndInactiveAdminAreNotBlocked() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.countByRole(anyString())).thenReturn(1L);

        // 升权：目标角色仍是 ADMIN
        assertDoesNotThrow(() -> UserRoleGuard.guardLastAdmin(mapper, user(2L, Role.USER.name(), 1), Role.ADMIN, 1));
        // 已经禁用的管理员：本来就不在「可用管理员」计数里
        assertDoesNotThrow(() -> UserRoleGuard.guardLastAdmin(mapper, user(3L, Role.ADMIN.name(), 0), Role.USER, 0));
        // 库里角色是脏值：按 USER 兜底，不算管理员
        assertDoesNotThrow(() -> UserRoleGuard.guardLastAdmin(mapper, user(4L, "ROOT", 1), Role.USER, 1));
    }
}