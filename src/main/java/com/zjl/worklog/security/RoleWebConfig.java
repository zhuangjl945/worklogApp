package com.zjl.worklog.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册角色拦截器。只挂 /api/**，手机端免登录接口（/api/public/**）不受影响。
 */
@Configuration
public class RoleWebConfig implements WebMvcConfigurer {

    private final RequireRoleInterceptor requireRoleInterceptor;

    public RoleWebConfig(RequireRoleInterceptor requireRoleInterceptor) {
        this.requireRoleInterceptor = requireRoleInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requireRoleInterceptor).addPathPatterns("/api/**");
    }
}