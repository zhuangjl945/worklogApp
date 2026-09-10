package com.zjl.worklog.security;

import com.zjl.worklog.config.SysConfigService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

@Service
public class JwtTokenService {

    /** 参数页把登录有效期调到多短都允许，但不低于这个值：否则一次误填就没人能登录 */
    private static final long MIN_EXPIRE_SECONDS = 60L;

    private final JwtProps props;
    private final SysConfigService sysConfig;

    public JwtTokenService(JwtProps props, SysConfigService sysConfig) {
        this.props = props;
        this.sysConfig = sysConfig;
    }

    private Key key() {
        byte[] bytes = props.getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(bytes);
    }

    /**
     * 生效中的登录有效期（秒）。
     *
     * <p>取「参数配置 → 系统基础」里的 jwt_expire_seconds，读不到或没配就用 application.yml。
     * 上限刻意钉死为 yml 那份：yml 的值过 JwtProps 的启动校验（正数且不超过一天），
     * 把天花板留在这里，管理员在页面上失手写个十年，也不会让 token 变成永久通行证。
     * 也就是说这个参数只能往短调，不能往长调——想延长登录时间必须改配置发版。
     */
    public long effectiveExpireSeconds() {
        long ceiling = props.getExpireSeconds();
        long wanted = sysConfig.getLong(SysConfigService.GROUP_SYSTEM,
                SysConfigService.KEY_JWT_EXPIRE_SECONDS, ceiling);
        return Math.max(MIN_EXPIRE_SECONDS, Math.min(ceiling, wanted));
    }

    public String generateToken(Long userId, String username, Long deptId, String realName, Role role) {
        long now = System.currentTimeMillis();
        Date issuedAt = new Date(now);
        Date exp = new Date(now + effectiveExpireSeconds() * 1000L);

        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .setIssuedAt(issuedAt)
                .setExpiration(exp)
                .claim("username", username)
                .claim("deptId", deptId)
                .claim("realName", realName)
                // 角色写进 token：一次登录内权限稳定，改角色需要重新登录才生效（避免旧 token 继续享受新权限）
                .claim("role", (role == null ? Role.USER : role).name())
                .signWith(key(), SignatureAlgorithm.HS256)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
