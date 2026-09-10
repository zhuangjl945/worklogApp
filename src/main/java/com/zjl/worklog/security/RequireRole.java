package com.zjl.worklog.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口角色门槛：标在 Controller 方法或类上，由 {@link RequireRoleInterceptor} 统一拦截。
 *
 * <p>value 里写的是「最低要求角色」，按 {@link Role} 的层级向上兼容：
 * 标 DEPT_ADMIN 则 ADMIN 也放行，标 ADMIN 则只有 ADMIN 放行。
 * 之所以不做精确匹配，是因为精确匹配会把大管理员挡在自己的系统外面。
 *
 * <p>注意本注解只解决「谁能调这个接口」，数据行级的归属判断仍由 {@link DataScope} 负责。
 *
 * <p>{@link #permission()} 是可选的第二道门槛，且只会更严不会更松：
 * value() 是代码里的内置下限，permission 指向「管理员可以在界面上调」的那道门槛，
 * 两者都要过。所以把权限点配置删了、读库失败、或者配得比内置还松，
 * 行为都退回内置下限，不会出现「配置丢了导致人人可管理员」。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    Role[] value();

    /** 关联的可配置权限点；NONE 表示该接口不参与权限设置 */
    Permission permission() default Permission.NONE;

    /** 权限点门槛未通过时的提示，为空则自动生成「需要XX及以上角色」 */
    String permissionMessage() default "";

    String message() default "当前角色无权执行该操作";
}