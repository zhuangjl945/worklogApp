package com.zjl.worklog.ticket;

import com.zjl.worklog.security.Permission;
import com.zjl.worklog.security.RequireRole;
import com.zjl.worklog.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 问题受理台的门槛挂在哪一层。
 *
 * <p>要钉住的是「读和写不是一道门」：ticket.manage 只管受理/派单/回复/完成/退回/归档/转记录这
 * 七个写动作，列表与详情只要登录。曾经把它挂在类级，结果是管理员一调严，普通员工连本科室
 * 有哪些待处理问题都看不见——而「看不见」比「改不动」更糟，同事会以为没人报修。
 */
class TicketPermissionGateTest {

    @Test
    @DisplayName("类级注解只要求登录，不绑权限点")
    void classLevelGateOnlyRequiresLogin() {
        RequireRole rule = TicketController.class.getAnnotation(RequireRole.class);

        assertNotNull(rule, "受理台必须有登录门槛，不能整个控制器匿名可访");
        assertSame(Permission.NONE, rule.permission(),
                "类级不能绑权限点，否则调严 ticket.manage 会连读接口一起挡掉");
        assertEquals(1, rule.value().length);
        assertEquals(Role.USER, rule.value()[0]);
    }

    @Test
    @DisplayName("七个流转写接口逐个挂 ticket.manage，读接口一个都不挂")
    void writesAreGatedAndReadsAreNot() {
        int writes = 0;
        int reads = 0;
        for (Method m : TicketController.class.getDeclaredMethods()) {
            RequireRole rule = m.getAnnotation(RequireRole.class);
            if (m.isAnnotationPresent(PostMapping.class)) {
                writes++;
                assertNotNull(rule, m.getName() + " 是写接口，必须显式声明门槛");
                assertSame(Permission.TICKET_MANAGE, rule.permission(),
                        m.getName() + " 应受 ticket.manage 约束");
                assertEquals(Role.USER, rule.value()[0],
                        m.getName() + " 的内置下限必须是 USER，保持互助受理的现状");
            } else if (m.isAnnotationPresent(GetMapping.class)) {
                reads++;
                assertNull(rule, m.getName() + " 是读接口，不该挂角色/权限门槛（登录由类级注解负责）");
            }
        }
        assertEquals(7, writes, "流转写接口数量变了要同步 README 与权限点说明");
        org.junit.jupiter.api.Assertions.assertTrue(reads >= 4, "读接口数量异常");
    }
}
