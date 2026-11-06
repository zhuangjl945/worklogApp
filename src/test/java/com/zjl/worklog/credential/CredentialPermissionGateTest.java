package com.zjl.worklog.credential;

import com.zjl.worklog.security.Permission;
import com.zjl.worklog.security.RequireRole;
import com.zjl.worklog.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 凭据库是全员共享的口令，列表和解密必须跟增删改同一道门。
 * 方法上若再标一个更低的角色，拦截器会用方法注解盖掉类注解。
 */
class CredentialPermissionGateTest {

    @Test
    @DisplayName("类级门槛是系统管理员，并挂上 credential.manage")
    void classRequiresAdmin() {
        RequireRole rule = CredentialController.class.getAnnotation(RequireRole.class);

        assertTrue(rule != null, "凭据库不能对任意登录用户开放");
        assertEquals(1, rule.value().length);
        assertEquals(Role.ADMIN, rule.value()[0]);
        assertSame(Permission.CREDENTIAL_MANAGE, rule.permission());
        assertEquals(Role.ADMIN, Permission.CREDENTIAL_MANAGE.getFloor());
    }

    @Test
    @DisplayName("接口方法不再单独放宽角色")
    void methodsDoNotLowerTheGate() {
        int endpoints = 0;
        for (Method method : CredentialController.class.getDeclaredMethods()) {
            if (!isMapping(method)) {
                continue;
            }
            endpoints++;
            assertNull(method.getAnnotation(RequireRole.class),
                    method.getName() + " 上的角色注解会盖掉类级 ADMIN");
        }
        assertEquals(6, endpoints, "凭据接口数量变了要同步这道门的覆盖范围");
    }

    private static boolean isMapping(Method method) {
        for (var annotation : method.getDeclaredAnnotations()) {
            String name = annotation.annotationType().getSimpleName();
            if (name.endsWith("Mapping")) {
                return true;
            }
        }
        return false;
    }
}
