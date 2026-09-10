package com.zjl.worklog.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjl.worklog.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/**
 * 按 {@link RequireRole} 收口接口。
 *
 * <p>这里不抛 BizException 而是直接写响应体：preHandle 阶段抛出的异常能否被
 * {@code @RestControllerAdvice} 捕获，取决于 HandlerExceptionResolver 是否拿到 mappedHandler，
 * 这个链路不值得赌，直接输出与全站一致的 {code,msg,data} 结构最稳。
 * HTTP 状态仍返回 200，前端按 code 分支处理（与既有接口约定一致）。
 */
@Component
public class RequireRoleInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;
    private final PermissionService permissionService;

    public RequireRoleInterceptor(ObjectMapper objectMapper, PermissionService permissionService) {
        this.objectMapper = objectMapper;
        this.permissionService = permissionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }
        // 方法上的注解优先，其次看类上的（整个 Controller 同级门槛时更省事）
        RequireRole rule = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), RequireRole.class);
        if (rule == null) {
            rule = AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), RequireRole.class);
        }
        if (rule == null) {
            return true;
        }
        CurrentUser cu = UserContext.get();
        if (cu == null || cu.getRole() == null) {
            write(response, ApiResponse.fail(401, "未登录或登录已过期"));
            return false;
        }
        boolean allowed = Arrays.stream(rule.value()).anyMatch(cu.getRole()::atLeast);
        if (!allowed) {
            write(response, ApiResponse.fail(403, rule.message()));
            return false;
        }

        // 第二道门槛：管理员在「权限设置」里调过的功能开关。它只会比内置下限更严，
        // 所以内置注解已经拦住的情况在上面就返回了，这里只可能额外拦住「配高了」的。
        Permission permission = rule.permission();
        if (permission != null && !permission.isPlaceholder()) {
            Role min = permissionService.minRole(permission);
            if (!cu.getRole().atLeast(min)) {
                String msg = StringUtils.hasText(rule.permissionMessage())
                        ? rule.permissionMessage()
                        : "该功能需要「" + min.getLabel() + "」及以上角色（可在 系统管理-权限设置 调整）";
                write(response, ApiResponse.fail(403, msg));
                return false;
            }
        }
        return true;
    }

    private void write(HttpServletResponse response, ApiResponse<Void> body) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}