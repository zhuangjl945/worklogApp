package com.zjl.worklog.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 安全相关的 Bean 装配。
 *
 * <p>这里补上了之前缺失的 CorsConfigurationSource：SecurityConfig 里的
 * .cors(Customizer.withDefaults()) 会去找这个 Bean，找不到就等于跨域配置完全没生效，
 * 之前同源部署看不出来，一旦手机端换域名就会静默失败。
 */
@Configuration
@EnableConfigurationProperties({JwtProps.class, CorsProps.class})
public class SecurityBeansConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProps props) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        List<String> origins = props.getAllowedOrigins() == null
                ? List.of()
                : props.getAllowedOrigins().stream()
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .toList();

        if (origins.isEmpty()) {
            // 未配置来源：注册空规则，跨域请求一律拿不到放行头（同源部署不受影响）
            return source;
        }

        CorsConfiguration cfg = new CorsConfiguration();
        // 用 AllowedOriginPatterns 而不是 AllowedOrigins，便于将来按需写子域通配
        cfg.setAllowedOriginPatterns(origins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        // X-Ticket-Auth 是手机端「凭单号+访问令牌」查进度用的头，属于访客态凭证
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Ticket-Auth"));
        cfg.setExposedHeaders(List.of("Content-Disposition"));
        // 纯 Bearer 认证，不依赖 Cookie，因此无需允许凭证，避免 CSRF 面扩大
        cfg.setAllowCredentials(false);
        cfg.setMaxAge(3600L);

        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }
}
