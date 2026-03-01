package com.zjl.worklog.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtTokenService tokenService;

    public JwtAuthFilter(JwtTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String uri = request.getRequestURI();
        // 登录接口和静态资源不走此过滤器，防止干扰和循环引用
        return "/api/auth/login".equals(uri) || uri.startsWith("/assets/") || uri.endsWith(".html");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (auth != null && auth.startsWith("Bearer ")) {
                try {
                    String token = auth.substring(7).trim();
                    if (!token.isEmpty()) {
                    Claims claims = tokenService.parseClaims(token);

                    Long userId = Long.valueOf(claims.getSubject());
                    String username = claims.get("username", String.class);
                    Long deptId = claims.get("deptId", Long.class);
                    String realName = claims.get("realName", String.class);

                    UserContext.set(new CurrentUser(userId, username, deptId, realName));

                    User principal = new User(username, "N/A", Collections.emptyList());
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                } catch (Exception e) {
                    // 仅记录核心信息，避免触发全量 request 打印
                    logger.warn("JWT failed: " + e.getMessage());
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
            UserContext.clear();
        }
    }
}
