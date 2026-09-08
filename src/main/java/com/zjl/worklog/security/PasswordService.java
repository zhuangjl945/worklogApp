package com.zjl.worklog.security;

import com.zjl.worklog.user.entity.UserEntity;
import com.zjl.worklog.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 口令服务：把「校验 / 哈希 / 存量明文升级」收敛到一处。
 *
 * <p>背景：系统原先使用 NoOpPasswordEncoder，user.password 里存的是明文。
 * 现在统一改为 DelegatingPasswordEncoder（默认 bcrypt，带 {bcrypt} 前缀），
 * 但库里已有的明文必须平滑迁移，否则所有老账号都会登录失败。
 *
 * <p>迁移策略是「双保险」：
 * 1）启动时 {@link #migrateAllLegacy()} 批量升级；
 * 2）登录时 {@link #matchesAndUpgrade(UserEntity, String)} 兜底，
 *    万一启动迁移没跑成功（例如数据库账号只有读权限），用户仍能登录，且登录成功后立刻补升级。
 */
@Service
public class PasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    /** DelegatingPasswordEncoder 的哈希前缀标识，例如 {bcrypt}$2a$10$... */
    private static final String PREFIX = "{";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public PasswordService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 生成带 {id} 前缀的口令哈希，用于新建账号和管理员重置密码。
     */
    public String encode(String rawPassword) {
        if (!StringUtils.hasText(rawPassword)) {
            throw new IllegalArgumentException("密码不能为空");
        }
        return passwordEncoder.encode(rawPassword);
    }

    /**
     * 校验登录口令；若库里还是历史明文，则在校验通过后顺手升级为哈希。
     *
     * @param user        数据库里查出的用户（password 为库中原始值）
     * @param rawPassword 用户输入的明文口令
     * @return 口令是否正确
     */
    public boolean matchesAndUpgrade(UserEntity user, String rawPassword) {
        if (user == null || !StringUtils.hasText(rawPassword) || !StringUtils.hasText(user.getPassword())) {
            return false;
        }
        String stored = user.getPassword();

        if (stored.startsWith(PREFIX)) {
            // 已是哈希，走标准校验
            return passwordEncoder.matches(rawPassword, stored);
        }

        // 历史明文：用常量时间比较，避免口令逐字符的计时侧信道
        boolean matched = MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                rawPassword.getBytes(StandardCharsets.UTF_8));
        if (matched) {
            // 升级失败不能影响本次登录，只记录日志（不打印口令内容）
            try {
                userMapper.updatePassword(user.getId(), passwordEncoder.encode(rawPassword));
                log.warn("检测到明文口令并已完成升级: userId={}, username={}", user.getId(), user.getUsername());
            } catch (Exception e) {
                log.error("明文口令升级失败，将在下次登录重试: userId={}", user.getId(), e);
            }
        }
        return matched;
    }

    /**
     * 批量把无 {id} 前缀的存量明文口令升级为哈希。
     * 幂等：升级后的记录带前缀，不会再被 selectNeedPasswordUpgrade 选中，多实例重复执行也安全。
     *
     * @return 成功升级的条数
     */
    public int migrateAllLegacy() {
        List<UserEntity> pending = userMapper.selectNeedPasswordUpgrade();
        if (pending.isEmpty()) {
            log.info("口令哈希检查：未发现明文口令");
            return 0;
        }
        int ok = 0;
        for (UserEntity u : pending) {
            try {
                userMapper.updatePassword(u.getId(), passwordEncoder.encode(u.getPassword()));
                ok++;
            } catch (Exception e) {
                log.error("口令迁移失败，该账号将走登录时兜底升级: userId={}", u.getId(), e);
            }
        }
        log.warn("口令哈希迁移完成：明文账号 {} 个，成功升级 {} 个（其余将在登录时重试）", pending.size(), ok);
        return ok;
    }
}
