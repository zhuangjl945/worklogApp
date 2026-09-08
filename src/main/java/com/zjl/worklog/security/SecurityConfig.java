package com.zjl.worklog.security;

import com.zjl.worklog.common.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    /**
     * 口令编码器：DelegatingPasswordEncoder，默认 bcrypt 并带 {bcrypt} 前缀。
     * 前缀的意义是「以后换算法不用洗数据」——校验时按前缀选实现，升级存量哈希可平滑过渡。
     *
     * <p>注意：历史的 NoOpPasswordEncoder（明文）已废弃，明文兼容逻辑收敛在 PasswordService 里。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtTokenService tokenService,
                                                   ObjectMapper objectMapper) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                // 真正的规则由 SecurityBeansConfig#corsConfigurationSource 提供；未配置来源时等于不放开跨域
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/login",
                                "/assets/**",
                                "/vite.svg",
                                "/favicon.ico",
                                "/api/auth/login"
                        ).permitAll()
                        // 手机端问题登记：静态页面入口 + 免登录接口（靠渠道签名与限流保护，不靠登录态）
                        .requestMatchers(
                                "/m",
                                "/m/**",
                                "/api/public/**"
                        ).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll()
                )
                .addFilterBefore(new JwtAuthFilter(tokenService), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            try {
                                writeJson(response, objectMapper, ApiResponse.fail(401, "未登录或登录已过期"));
                            } catch (Exception ignored) {
                            }
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            try {
                                writeJson(response, objectMapper, ApiResponse.fail(403, "无权限"));
                            } catch (Exception ignored) {
                            }
                        })
                );

        return http.build();
    }

    private void writeJson(HttpServletResponse response, ObjectMapper objectMapper, Object body) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
