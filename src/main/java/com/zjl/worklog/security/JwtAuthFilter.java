package com.zjl.worklog.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String LOG_FILE = "d:/Cursors/worklog/.cursor/debug.log";

    static {
        writeLogStatic("JwtAuthFilter CLASS LOADED");
    }

    private final JwtTokenService tokenService;

    public JwtAuthFilter(JwtTokenService tokenService) {
        writeLogStatic("JwtAuthFilter CONSTRUCTOR called with tokenService: " + tokenService);
        this.tokenService = tokenService;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        writeLogStatic("shouldNotFilter called for: " + request.getRequestURI());
        String uri = request.getRequestURI();
        boolean result = "/api/auth/login".equals(uri) || uri.startsWith("/assets/") || uri.endsWith(".html");
        writeLogStatic("shouldNotFilter: uri=" + uri + ", result=" + result);
        return result;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        writeLog("=== doFilterInternal START: " + request.getRequestURI() + " ===");
        writeLog("Request class: " + request.getClass().getName());
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
            writeLog("About to call filterChain.doFilter");
            filterChain.doFilter(request, response);
            writeLog("filterChain.doFilter returned");
        } finally {
            SecurityContextHolder.clearContext();
            UserContext.clear();
            writeLog("=== doFilterInternal END ===");
        }
    }

    private void writeLog(String msg) {
        String line = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + " [JwtAuthFilter] " + msg;
        log.info(line);
        try {
            Files.createDirectories(Paths.get("d:/Cursors/worklog/.cursor"));
            try (PrintWriter pw = new PrintWriter(new FileWriter(LOG_FILE, true))) {
                pw.println(line);
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private static void writeLogStatic(String msg) {
        String line = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + " [JwtAuthFilter] " + msg;
        System.out.println(line);
        try {
            Files.createDirectories(Paths.get("d:/Cursors/worklog/.cursor"));
            try (PrintWriter pw = new PrintWriter(new FileWriter(LOG_FILE, true))) {
                pw.println(line);
            }
        } catch (Exception e) {
            System.err.println("Failed to write log: " + e.getMessage());
        }
    }
}
