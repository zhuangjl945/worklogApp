package com.zjl.worklog.user;

import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.api.PageResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.user.dto.UserView;
import com.zjl.worklog.user.entity.UserEntity;
import com.zjl.worklog.user.mapper.UserMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/count")
    public ApiResponse<Long> count(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String realName,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String deptIds,
            @RequestParam(required = false) Integer status
    ) {
        return ApiResponse.ok(userMapper.count(username, realName, deptId, parseDeptIds(deptIds), status));
    }

    @GetMapping
    public ApiResponse<PageResponse<UserView>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String realName,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String deptIds,
            @RequestParam(required = false) Integer status
    ) {
        if (page < 1) page = 1;
        if (size < 1) size = 10;

        List<Long> deptIdList = parseDeptIds(deptIds);
        long total = userMapper.count(username, realName, deptId, deptIdList, status);
        long offset = (page - 1) * size;

        List<UserEntity> records = total == 0 ? List.of() : userMapper.selectPage(offset, size, username, realName, deptId, deptIdList, status);
        List<UserView> views = records.stream()
                .map(u -> new UserView(u.getId(), u.getUsername(), u.getRealName(), u.getDeptId(), u.getStatus(), u.getCreateTime(), u.getUpdateTime()))
                .toList();

        return ApiResponse.ok(PageResponse.of(page, size, total, views));
    }

    private List<Long> parseDeptIds(String deptIds) {
        if (deptIds == null || deptIds.isBlank()) {
            return null;
        }
        try {
            return java.util.Arrays.stream(deptIds.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::parseLong)
                    .toList();
        } catch (Exception e) {
            return null;
        }
    }

    @PostMapping
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody CreateUserRequest req) {
        UserEntity existed = userMapper.selectByUsername(req.getUsername());
        if (existed != null) {
            throw new BizException(40001, "username已存在");
        }

        UserEntity entity = new UserEntity();
        entity.setUsername(req.getUsername());
        entity.setPassword(passwordEncoder.encode(req.getPassword()));
        entity.setRealName(req.getRealName());
        entity.setDeptId(req.getDeptId());
        entity.setStatus(req.getStatus() == null ? 1 : req.getStatus());

        userMapper.insert(entity);
        return ApiResponse.ok(Collections.singletonMap("id", entity.getId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<UserView> detail(@PathVariable Long id) {
        UserEntity user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(40001, "用户不存在");
        }
        return ApiResponse.ok(new UserView(user.getId(), user.getUsername(), user.getRealName(), user.getDeptId(), user.getStatus(), user.getCreateTime(), user.getUpdateTime()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Boolean> update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest req) {
        UserEntity existed = userMapper.selectById(id);
        if (existed == null) {
            throw new BizException(40001, "用户不存在");
        }

        UserEntity update = new UserEntity();
        update.setId(id);
        update.setRealName(req.getRealName());
        update.setDeptId(req.getDeptId());
        update.setStatus(req.getStatus());

        userMapper.update(update);
        return ApiResponse.ok(true);
    }

    @PutMapping("/{id}/password")
    public ApiResponse<Boolean> updatePassword(@PathVariable Long id, @Valid @RequestBody UpdatePasswordRequest req) {
        UserEntity existed = userMapper.selectById(id);
        if (existed == null) {
            throw new BizException(40001, "用户不存在");
        }
        userMapper.updatePassword(id, passwordEncoder.encode(req.getNewPassword()));
        return ApiResponse.ok(true);
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Boolean> updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateStatusRequest req) {
        UserEntity existed = userMapper.selectById(id);
        if (existed == null) {
            throw new BizException(40001, "用户不存在");
        }
        userMapper.updateStatus(id, req.getStatus());
        return ApiResponse.ok(true);
    }

    public static class CreateUserRequest {
        @NotBlank
        private String username;
        @NotBlank
        private String password;
        @NotBlank
        private String realName;
        private Long deptId;
        private Integer status;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getRealName() {
            return realName;
        }

        public void setRealName(String realName) {
            this.realName = realName;
        }

        public Long getDeptId() {
            return deptId;
        }

        public void setDeptId(Long deptId) {
            this.deptId = deptId;
        }

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }
    }

    public static class UpdateUserRequest {
        @NotBlank
        private String realName;
        private Long deptId;
        private Integer status;

        public String getRealName() {
            return realName;
        }

        public void setRealName(String realName) {
            this.realName = realName;
        }

        public Long getDeptId() {
            return deptId;
        }

        public void setDeptId(Long deptId) {
            this.deptId = deptId;
        }

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }
    }

    public static class UpdatePasswordRequest {
        @NotBlank
        private String newPassword;

        public String getNewPassword() {
            return newPassword;
        }

        public void setNewPassword(String newPassword) {
            this.newPassword = newPassword;
        }
    }

    public static class UpdateStatusRequest {
        @NotNull
        private Integer status;

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }
    }
}
