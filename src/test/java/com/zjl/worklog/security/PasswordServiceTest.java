package com.zjl.worklog.security;

import com.zjl.worklog.user.entity.UserEntity;
import com.zjl.worklog.user.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 口令迁移逻辑回归测试。
 *
 * <p>这是本次安全加固里最容易把现网「所有人登不进系统」搞挂的一处，
 * 因此把「明文兼容校验 + 登录时升级 + 哈希校验」三种形态都钉住。
 * 用小强度 bcrypt（cost=4）让测试跑得快。
 */
class PasswordServiceTest {

    /** 记录写库调用的假 Mapper，避免引入额外 mock 依赖 */
    private static final class FakeUserMapper implements UserMapper {
        final List<Long> upgradedIds = new ArrayList<>();
        final List<String> upgradedHashes = new ArrayList<>();
        List<UserEntity> pending = new ArrayList<>();

        @Override
        public UserEntity selectByUsername(String username) {
            return pending.stream().filter(u -> username.equals(u.getUsername())).findFirst().orElse(null);
        }

        @Override
        public UserEntity selectById(Long id) {
            return pending.stream().filter(u -> id.equals(u.getId())).findFirst().orElse(null);
        }

        @Override
        public List<UserEntity> selectNeedPasswordUpgrade() {
            return pending;
        }

        @Override
        public long count(String username, String realName, Long deptId, List<Long> deptIds, Integer status) {
            return pending.size();
        }

        @Override
        public List<UserEntity> selectPage(long offset, long limit, String username, String realName,
                                           Long deptId, List<Long> deptIds, Integer status) {
            return pending;
        }

        @Override
        public int insert(UserEntity entity) {
            return 1;
        }

        @Override
        public int update(UserEntity entity) {
            return 1;
        }

        @Override
        public int updateStatus(Long id, Integer status) {
            return 1;
        }

        @Override
        public int updatePassword(Long id, String password) {
            upgradedIds.add(id);
            upgradedHashes.add(password);
            return 1;
        }
    }

    private static UserEntity user(long id, String storedPassword) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setUsername("zhangsan");
        u.setPassword(storedPassword);
        u.setStatus(1);
        return u;
    }

    /** 用低强度 bcrypt（cost=4）跑测试，避免默认 cost=10 拖慢用例 */
    private static PasswordEncoder fastEncoder() {
        return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);
    }

    private static PasswordService serviceWith(PasswordEncoder encoder, FakeUserMapper mapper) {
        return new PasswordService(mapper, encoder);
    }

    @Test
    @DisplayName("encode 产出的哈希带 {bcrypt} 前缀且可反向校验")
    void encodeProducesPrefixedHash() {
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        FakeUserMapper mapper = new FakeUserMapper();
        PasswordService service = new PasswordService(mapper, encoder);

        String hash = service.encode("Passw0rd!");

        assertTrue(hash.startsWith("{bcrypt}"), "应带 {bcrypt} 前缀，实际=" + hash);
        assertNotEquals("Passw0rd!", hash);
        assertTrue(encoder.matches("Passw0rd!", hash));
    }

    @Test
    @DisplayName("存量明文口令：校验通过后立即回写为哈希（登录不能被升级失败阻断）")
    void legacyPlaintextMatchesAndUpgrades() {
        FakeUserMapper mapper = new FakeUserMapper();
        PasswordService service = serviceWith(fastEncoder(), mapper);
        UserEntity u = user(1L, "plain-123");

        assertTrue(service.matchesAndUpgrade(u, "plain-123"));

        assertEquals(1, mapper.upgradedIds.size(), "应触发一次升级写库");
        assertEquals(1L, mapper.upgradedIds.get(0));
    }

    @Test
    @DisplayName("存量明文口令：输入错误既不通过也不写库")
    void legacyPlaintextWrongPasswordDoesNotUpgrade() {
        FakeUserMapper mapper = new FakeUserMapper();
        PasswordService service = serviceWith(fastEncoder(), mapper);
        UserEntity u = user(2L, "plain-123");

        assertFalse(service.matchesAndUpgrade(u, "wrong"));
        assertTrue(mapper.upgradedIds.isEmpty());
    }

    @Test
    @DisplayName("已是哈希（带前缀）：正常校验，不再重复写库")
    void hashedPasswordVerifiesWithoutRewrite() {
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        FakeUserMapper mapper = new FakeUserMapper();
        PasswordService service = new PasswordService(mapper, encoder);
        UserEntity u = user(3L, encoder.encode("Passw0rd!"));

        assertTrue(service.matchesAndUpgrade(u, "Passw0rd!"));
        assertFalse(service.matchesAndUpgrade(u, "Passw0rd"));
        assertTrue(mapper.upgradedIds.isEmpty(), "带前缀的哈希不应再回写");
    }

    @Test
    @DisplayName("批量迁移：只处理启动时查出的明文账号")
    void migrateAllLegacyUpgradesEveryPendingRow() {
        FakeUserMapper mapper = new FakeUserMapper();
        PasswordService service = serviceWith(fastEncoder(), mapper);
        mapper.pending = new ArrayList<>(List.of(user(10L, "aaa111"), user(11L, "bbb222")));

        int upgraded = service.migrateAllLegacy();

        assertEquals(2, upgraded);
        assertEquals(List.of(10L, 11L), mapper.upgradedIds);
        assertEquals(2, mapper.upgradedHashes.size());
        for (String h : mapper.upgradedHashes) {
            assertTrue(h.startsWith("$2"), "应写入 bcrypt 哈希而不是明文，实际=" + h);
        }
    }

    @Test
    @DisplayName("空值防御：库里没有口令或输入为空一律判否")
    void blankValuesNeverMatch() {
        FakeUserMapper mapper = new FakeUserMapper();
        PasswordService service = serviceWith(fastEncoder(), mapper);

        assertFalse(service.matchesAndUpgrade(user(1L, ""), "x"));
        assertFalse(service.matchesAndUpgrade(user(1L, null), "x"));
        assertFalse(service.matchesAndUpgrade(user(1L, "abc"), ""));
        assertFalse(service.matchesAndUpgrade(null, "abc"));
    }
}
