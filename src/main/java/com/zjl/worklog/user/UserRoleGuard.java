package com.zjl.worklog.user;

import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.Role;
import com.zjl.worklog.security.UserContext;
import com.zjl.worklog.user.entity.UserEntity;
import com.zjl.worklog.user.mapper.UserMapper;

/**
 * 改角色/改状态的两条硬保护，单条改与批量改共用。
 *
 * <p>为什么要从 Controller 里拎出来：批量授予角色最容易出的事故就是「循环里少判一条」，
 * 判定收敛成一个入口，单条和批量走的才是同一段代码，也才谈得上用单测钉住。
 * 这两条规则任何一条被绕过，结果分别是「操作人把自己锁在系统外」和「全系统再没人能改权限」。
 */
public final class UserRoleGuard {

    private UserRoleGuard() {
    }

    /** 不能改自己的角色或状态：管理员把自己降权/禁用后，没人能再把他捞回来 */
    public static void guardSelfLock(Long targetId, boolean roleOrStatusChanged) {
        CurrentUser cu = UserContext.get();
        if (roleOrStatusChanged && cu != null && targetId.equals(cu.getId())) {
            throw new BizException(40003, "不能修改自己的角色或状态，请让其他管理员操作");
        }
    }

    /** 系统里必须至少留一个启用的系统管理员 */
    public static void guardLastAdmin(UserMapper userMapper, UserEntity existed, Role newRole, Integer newStatus) {
        boolean wasActiveAdmin = Role.of(existed.getRole()) == Role.ADMIN
                && existed.getStatus() != null && existed.getStatus() == 1;
        boolean stillActiveAdmin = newRole == Role.ADMIN && (newStatus == null || newStatus == 1);
        if (wasActiveAdmin && !stillActiveAdmin && userMapper.countByRole(Role.ADMIN.name()) <= 1) {
            throw new BizException(40004, "系统需要保留至少一个启用的系统管理员");
        }
    }
}