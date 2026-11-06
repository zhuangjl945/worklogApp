package com.zjl.worklog.credential;

import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.api.PageResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.credential.entity.CredentialEntity;
import com.zjl.worklog.credential.mapper.CredentialMapper;
import com.zjl.worklog.security.Permission;
import com.zjl.worklog.security.RequireRole;
import com.zjl.worklog.security.Role;
import com.zjl.worklog.security.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 凭据保管库控制器。
 * <p>
 * 提供凭据的增删改查、自动脱敏和解密查看功能。
 * 密码和密钥以 AES-256-GCM 加密存储，列表/详情接口自动脱敏。
 */
@Validated
@RestController
@RequestMapping("/api/credential")
// 库里是全员共享的口令，解密接口一旦放开就是把数据库密码交给任意登录用户。
// 下限固定 ADMIN，权限点只允许再收紧，不允许放到普通员工。
@RequireRole(value = Role.ADMIN, permission = Permission.CREDENTIAL_MANAGE)
public class CredentialController {

    /** 即将过期的天数阈值 */
    private static final int EXPIRING_SOON_DAYS = 30;

    private final CredentialMapper credentialMapper;
    private final CredentialCryptoService cryptoService;

    public CredentialController(CredentialMapper credentialMapper,
                                CredentialCryptoService cryptoService) {
        this.credentialMapper = credentialMapper;
        this.cryptoService = cryptoService;
    }

    /**
     * 新增凭据。密码和密钥加密后存储。
     *
     * @param req 创建请求
     * @return 新凭据 ID
     */
    @PostMapping
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody CreateRequest req) {
        Long userId = UserContext.get().getId();

        CredentialEntity entity = new CredentialEntity();
        entity.setName(req.getName());
        entity.setCategory(req.getCategory());
        entity.setUsername(req.getUsername());
        entity.setUrl(req.getUrl());
        entity.setTags(req.getTags());
        entity.setRemark(req.getRemark());
        entity.setExpireDate(req.getExpireDate());
        entity.setCreatedBy(userId);

        // 密码和密钥加密存储
        if (req.getPassword() != null && !req.getPassword().isBlank()) {
            entity.setPasswordEncrypted(cryptoService.encrypt(req.getPassword()));
        }
        if (req.getSecretKey() != null && !req.getSecretKey().isBlank()) {
            entity.setSecretKeyEncrypted(cryptoService.encrypt(req.getSecretKey()));
        }

        credentialMapper.insert(entity);
        return ApiResponse.ok(Map.of("id", entity.getId()));
    }

    /**
     * 分页查询凭据列表。
     * 返回 CredentialListVO，密文字段替换为布尔标记（hasPassword/hasSecretKey）。
     *
     * @param keyword  搜索关键词
     * @param category 分类筛选
     * @param page     页码（从 1 开始）
     * @param size     每页条数
     * @return 分页结果
     */
    @GetMapping
    public ApiResponse<PageResponse<CredentialListVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {

        int offset = (page - 1) * size;
        int total = credentialMapper.countByFilter(keyword, category);
        List<CredentialEntity> entities = credentialMapper.selectPage(keyword, category, offset, size);

        LocalDate today = LocalDate.now();
        List<CredentialListVO> records = entities.stream()
                .map(e -> toListVO(e, today))
                .collect(Collectors.toList());

        return ApiResponse.ok(PageResponse.of(page, size, total, records));
    }

    /**
     * 凭据详情。密文字段置为 null（自动脱敏）。
     *
     * @param id 凭据 ID
     * @return 凭据详情（不含密文）
     */
    @GetMapping("/{id}")
    public ApiResponse<CredentialDetailVO> detail(@PathVariable Long id) {
        CredentialEntity entity = credentialMapper.selectById(id);
        if (entity == null) {
            throw new BizException(40400, "凭据不存在");
        }
        return ApiResponse.ok(toDetailVO(entity));
    }

    /**
     * 解密查看凭据的密码和密钥明文。
     *
     * @param id 凭据 ID
     * @return 包含 password 和 secretKey 明文的 Map
     */
    @GetMapping("/{id}/reveal")
    public ApiResponse<Map<String, String>> reveal(@PathVariable Long id) {
        CredentialEntity entity = credentialMapper.selectById(id);
        if (entity == null) {
            throw new BizException(40400, "凭据不存在");
        }

        Map<String, String> result = new HashMap<>();
        if (entity.getPasswordEncrypted() != null) {
            result.put("password", cryptoService.decrypt(entity.getPasswordEncrypted()));
        }
        if (entity.getSecretKeyEncrypted() != null) {
            result.put("secretKey", cryptoService.decrypt(entity.getSecretKeyEncrypted()));
        }
        return ApiResponse.ok(result);
    }

    /**
     * 编辑凭据。密码/密钥非空时重新加密，为空则保留原值。
     *
     * @param id  凭据 ID
     * @param req 更新请求
     * @return 操作结果
     */
    @PutMapping("/{id}")
    public ApiResponse<Boolean> update(@PathVariable Long id, @Valid @RequestBody UpdateRequest req) {
        CredentialEntity entity = credentialMapper.selectById(id);
        if (entity == null) {
            throw new BizException(40400, "凭据不存在");
        }

        // 更新非敏感字段
        if (req.getName() != null) {
            entity.setName(req.getName());
        }
        if (req.getCategory() != null) {
            entity.setCategory(req.getCategory());
        }
        if (req.getUsername() != null) {
            entity.setUsername(req.getUsername());
        }
        if (req.getUrl() != null) {
            entity.setUrl(req.getUrl());
        }
        if (req.getTags() != null) {
            entity.setTags(req.getTags());
        }
        if (req.getRemark() != null) {
            entity.setRemark(req.getRemark());
        }
        if (req.getExpireDate() != null) {
            entity.setExpireDate(req.getExpireDate());
        }

        // 密码/密钥非空时重新加密
        if (req.getPassword() != null && !req.getPassword().isBlank()) {
            entity.setPasswordEncrypted(cryptoService.encrypt(req.getPassword()));
        }
        if (req.getSecretKey() != null && !req.getSecretKey().isBlank()) {
            entity.setSecretKeyEncrypted(cryptoService.encrypt(req.getSecretKey()));
        }

        credentialMapper.update(entity);
        return ApiResponse.ok(true);
    }

    /**
     * 删除凭据
     *
     * @param id 凭据 ID
     * @return 操作结果
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        int rows = credentialMapper.deleteById(id);
        if (rows == 0) {
            throw new BizException(40400, "凭据不存在");
        }
        return ApiResponse.ok(true);
    }

    // ==================== 内部 VO / DTO ====================

    /**
     * 创建凭据请求
     */
    @Data
    public static class CreateRequest {
        @NotBlank(message = "凭据名称不能为空")
        private String name;
        private String category = "other";
        private String username;
        private String password;
        private String secretKey;
        private String url;
        private String tags;
        private String remark;
        private LocalDate expireDate;
    }

    /**
     * 更新凭据请求
     */
    @Data
    public static class UpdateRequest {
        private String name;
        private String category;
        private String username;
        private String password;
        private String secretKey;
        private String url;
        private String tags;
        private String remark;
        private LocalDate expireDate;
    }

    /**
     * 凭据列表 VO（密文字段替换为布尔标记）
     */
    @Data
    public static class CredentialListVO {
        private Long id;
        private String name;
        private String category;
        private String username;
        private String url;
        private String tags;
        private LocalDate expireDate;
        /** 是否设置了密码 */
        private Boolean hasPassword;
        /** 是否设置了密钥 */
        private Boolean hasSecretKey;
        /** 是否已过期 */
        private Boolean expired;
        /** 是否即将过期（30天内） */
        private Boolean expiringSoon;
        private LocalDateTime createTime;
    }

    /**
     * 凭据详情 VO（密文字段脱敏为 null）
     */
    @Data
    public static class CredentialDetailVO {
        private Long id;
        private String name;
        private String category;
        private String username;
        /** 密码（脱敏，始终为 null） */
        private String password;
        /** 密钥（脱敏，始终为 null） */
        private String secretKey;
        private String url;
        private String tags;
        private String remark;
        private LocalDate expireDate;
        private LocalDateTime createTime;
        private LocalDateTime updateTime;
    }

    // ==================== 转换方法 ====================

    /**
     * 实体转列表 VO
     */
    private CredentialListVO toListVO(CredentialEntity entity, LocalDate today) {
        CredentialListVO vo = new CredentialListVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setCategory(entity.getCategory());
        vo.setUsername(entity.getUsername());
        vo.setUrl(entity.getUrl());
        vo.setTags(entity.getTags());
        vo.setExpireDate(entity.getExpireDate());
        vo.setHasPassword(entity.getPasswordEncrypted() != null && !entity.getPasswordEncrypted().isBlank());
        vo.setHasSecretKey(entity.getSecretKeyEncrypted() != null && !entity.getSecretKeyEncrypted().isBlank());
        vo.setCreateTime(entity.getCreateTime());

        // 计算过期状态
        if (entity.getExpireDate() != null) {
            vo.setExpired(entity.getExpireDate().isBefore(today));
            long daysUntilExpire = ChronoUnit.DAYS.between(today, entity.getExpireDate());
            vo.setExpiringSoon(daysUntilExpire >= 0 && daysUntilExpire <= EXPIRING_SOON_DAYS);
        } else {
            vo.setExpired(false);
            vo.setExpiringSoon(false);
        }
        return vo;
    }

    /**
     * 实体转详情 VO（密文字段脱敏为 null）
     */
    private CredentialDetailVO toDetailVO(CredentialEntity entity) {
        CredentialDetailVO vo = new CredentialDetailVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setCategory(entity.getCategory());
        vo.setUsername(entity.getUsername());
        vo.setPassword(null);      // 脱敏
        vo.setSecretKey(null);     // 脱敏
        vo.setUrl(entity.getUrl());
        vo.setTags(entity.getTags());
        vo.setRemark(entity.getRemark());
        vo.setExpireDate(entity.getExpireDate());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        return vo;
    }
}
