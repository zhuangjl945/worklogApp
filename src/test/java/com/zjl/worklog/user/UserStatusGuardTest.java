package com.zjl.worklog.user;

import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.PasswordService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 启用/禁用账号接口（PUT /api/users/{id}/status）的两条硬保护。
 *
 * <p>历史缺口：改角色、编辑员工都判「不能动自己」「必须留一个启用的 ADMIN」，
 * 唯独这个只改状态的小接口漏了判定，于是禁用自己、禁用最后一个管理员都能从这里绕过去。
 * 所以这里测的是 Controller 的实际调用路径，而不是只测 UserRoleGuard——
 * 丢保护的从来不是判定本身，而是「某个入口忘了调它」。
 */
class UserStatusGuardTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final UserController controller =
            new UserController(userMapper, mock(PasswordService.class));

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    private static UserEntity row(long id, String role, int status) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setUsername("u" + id);
        u.setRealName("用户" + id);
        u.setDeptId(3L);
        u.setRole(role);
        u.setStatus(status);
        return u;
    }

    private UserController.UpdateStatusRequest req(int status) {
        UserController.UpdateStatusRequest r = new UserController.UpdateStatusRequest();
        r.setStatus(status);
        return r;
    }

    @Test
    @DisplayName("管理员不能通过本接口禁用自己")
    void cannotDisableSelf() {
        UserContext.set(new CurrentUser(1L, "admin", null, "管理员", Role.ADMIN));
        when(userMapper.selectById(1L)).thenReturn(row(1L, Role.ADMIN.name(), 1));

        BizException e = assertThrows(BizException.class,
                () -> controller.updateStatus(1L, req(0)));

        assertEquals(40003, e.getCode());
        // 被拦下就绝不能落库，否则「提示失败但状态真变了」更难排查
        verify(userMapper, never()).updateStatus(anyLong(), any());
    }

    @Test
    @DisplayName("禁用最后一个启用的系统管理员会被拒")
    void cannotDisableLastAdmin() {
        UserContext.set(new CurrentUser(1L, "admin", null, "管理员", Role.ADMIN));
        when(userMapper.selectById(7L)).thenReturn(row(7L, Role.ADMIN.name(), 1));
        when(userMapper.countByRole(anyString())).thenReturn(1L);

        BizException e = assertThrows(BizException.class,
                () -> controller.updateStatus(7L, req(0)));

        assertEquals(40004, e.getCode());
        verify(userMapper, never()).updateStatus(anyLong(), any());
    }

    @Test
    @DisplayName("还有第二个启用的管理员时，禁用别人放行")
    void disableAllowedWhenAnotherAdminExists() {
        UserContext.set(new CurrentUser(1L, "admin", null, "管理员", Role.ADMIN));
        when(userMapper.selectById(7L)).thenReturn(row(7L, Role.ADMIN.name(), 1));
        when(userMapper.countByRole(anyString())).thenReturn(2L);

        assertDoesNotThrow(() -> controller.updateStatus(7L, req(0)));

        verify(userMapper).updateStatus(7L, 0);
    }

    @Test
    @DisplayName("普通员工之间禁用不触发管理员保护")
    void disableNormalUserIsNotGovernedByAdminGuard() {
        UserContext.set(new CurrentUser(1L, "admin", null, "管理员", Role.ADMIN));
        when(userMapper.selectById(9L)).thenReturn(row(9L, Role.USER.name(), 1));
        // 故意不 stub countByRole：它一旦被调用会返回 0，从而错误地拦住这次操作
        assertDoesNotThrow(() -> controller.updateStatus(9L, req(0)));
        verify(userMapper, never()).countByRole(anyString());
    }

    @Test
    @DisplayName("重复提交同一个状态值不算变更，两条保护都不该拦")
    void noChangeIsNotBlocked() {
        // 自己已经是禁用态，再点一次「禁用」：状态没变，不该被「不能改自己」拦下来
        UserContext.set(new CurrentUser(1L, "admin", null, "管理员", Role.ADMIN));
        when(userMapper.selectById(1L)).thenReturn(row(1L, Role.ADMIN.name(), 0));

        assertDoesNotThrow(() -> controller.updateStatus(1L, req(0)));
    }

    @Test
    @DisplayName("目标用户不存在时仍报 40001，不会走进保护逻辑")
    void missingUserStillThrowsNotFound() {
        UserContext.set(new CurrentUser(1L, "admin", null, "管理员", Role.ADMIN));
        when(userMapper.selectById(555L)).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> controller.updateStatus(555L, req(0)));

        assertEquals(40001, e.getCode());
    }
}
