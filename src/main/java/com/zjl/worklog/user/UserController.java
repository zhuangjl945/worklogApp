package com.zjl.worklog.user;

import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.api.PageResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.user.dto.UserRosterView;
import com.zjl.worklog.user.dto.UserRoleView;
import com.zjl.worklog.user.dto.UserView;
import com.zjl.worklog.user.entity.UserEntity;
import com.zjl.worklog.user.mapper.UserMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.PasswordService;
import com.zjl.worklog.security.Permission;
import com.zjl.worklog.security.RequireRole;
import com.zjl.worklog.security.Role;
import com.zjl.worklog.security.UserContext;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/users")
// 员工与角色维护是系统管理动作：只给系统管理员，科室管理员也不行
@RequireRole(value = Role.ADMIN, permission = Permission.USER_MANAGE, message = "仅系统管理员可维护员工与角色")
public class UserController {

    private final UserMapper userMapper;
    private final PasswordService passwordService;

    /** 统一转 UserView，roleLabel 由后端给，省得前端各维护一份中文映射 */
    private static UserView toView(UserEntity u) {
        Role role = Role.of(u.getRole());
        return new UserView(u.getId(), u.getUsername(), u.getRealName(), u.getDeptId(),
                role.name(), role.getLabel(), u.getStatus(), u.getCreateTime(), u.getUpdateTime());
    }

    /** 角色值白名单校验：库里脏值按 USER 兜底，但写入必须是合法枚举名 */
    private static Role parseRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return Role.USER;
        }
        String v = raw.trim().toUpperCase();
        try {
            return Role.valueOf(v);
        } catch (IllegalArgumentException e) {
            throw new BizException(40001, "非法角色：" + raw);
        }
    }

    // 两条保护的具体判定在 UserRoleGuard 里：单条改角色、批量改角色、编辑员工走的是同一段代码
    private void guardSelfLock(Long targetId, boolean roleOrStatusChanged) {
        UserRoleGuard.guardSelfLock(targetId, roleOrStatusChanged);
    }

    private void guardLastAdmin(UserEntity existed, Role newRole, Integer newStatus) {
        UserRoleGuard.guardLastAdmin(userMapper, existed, newRole, newStatus);
    }

    public UserController(UserMapper userMapper, PasswordService passwordService) {
        this.userMapper = userMapper;
        this.passwordService = passwordService;
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
                .map(UserController::toView)
                .toList();

        return ApiResponse.ok(PageResponse.of(page, size, total, views));
    }

    /**
     * 花名册：合同经办人、工单指派、任务转移这类「选人下拉」用。
     *
     * <p>方法上标 {@code @RequireRole(USER)} 是为了盖掉类级的 ADMIN——本注解是「最低角色」语义，
     * 写 USER 就等于「任何登录用户」。不加这一行会被类级注解拦住，三个下拉框直接全废。
     *
     * <p>允许跨科室查人名是有意保留的：这些下拉本来就要跨科找人，收口前的行为也是这样。
     * 角色体系要收紧的是工作数据（谁的任务谁能看），不是通讯录。
     * 只强制 status=1：禁用账号不该再被指派新工作。
     */
    @GetMapping("/roster")
    @RequireRole(Role.USER)
    public ApiResponse<List<UserRosterView>> roster(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String realName,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String deptIds,
            @RequestParam(defaultValue = "200") long size
    ) {
        long limit = Math.min(Math.max(size, 1), 500);
        List<UserRosterView> out = userMapper
                .selectPage(0, limit, username, realName, deptId, parseDeptIds(deptIds), 1)
                .stream()
                .map(u -> new UserRosterView(u.getId(), u.getUsername(), u.getRealName(), u.getDeptId()))
                .toList();
        return ApiResponse.ok(out);
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
        entity.setPassword(passwordService.encode(req.getPassword()));
        entity.setRealName(req.getRealName());
        entity.setDeptId(req.getDeptId());
        entity.setRole(parseRole(req.getRole()).name());
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
        return ApiResponse.ok(toView(user));
    }

    @PutMapping("/{id}")
    public ApiResponse<Boolean> update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest req) {
        UserEntity existed = userMapper.selectById(id);
        if (existed == null) {
            throw new BizException(40001, "用户不存在");
        }

        Role newRole = req.getRole() == null ? Role.of(existed.getRole()) : parseRole(req.getRole());
        boolean changedRoleOrStatus = (req.getRole() != null && Role.of(existed.getRole()) != newRole)
                || (req.getStatus() != null && !req.getStatus().equals(existed.getStatus()));
        guardSelfLock(id, changedRoleOrStatus);
        guardLastAdmin(existed, newRole, req.getStatus());

        UserEntity update = new UserEntity();
        update.setId(id);
        update.setRealName(req.getRealName());
        update.setDeptId(req.getDeptId());
        update.setRole(newRole.name());
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
        userMapper.updatePassword(id, passwordService.encode(req.getNewPassword()));
        return ApiResponse.ok(true);
    }

    /**
     * 启用/禁用账号。
     *
     * <p>这里必须与 update()、applyRole() 共用同一套硬保护：禁用某人和把他降级是同一类后果，
     * 少任何一条都会演成「管理员把自己关在门外」或「全系统再没人能改角色」的死局。
     * 早先版本这里只判了存在性，于是走 /{id} 拦得住、走 /{id}/status 拦不住，等于没有保护。
     */
    @PutMapping("/{id}/status")
    public ApiResponse<Boolean> updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateStatusRequest req) {
        UserEntity existed = userMapper.selectById(id);
        if (existed == null) {
            throw new BizException(40001, "用户不存在");
        }
        // 只在状态真的发生变化时才动用保护：重复提交同一个值不该被拦
        boolean changed = !req.getStatus().equals(existed.getStatus());
        // 本接口不动角色，角色维度沿用他当前的值
        guardSelfLock(id, changed);
        guardLastAdmin(existed, Role.of(existed.getRole()), req.getStatus());
        userMapper.updateStatus(id, req.getStatus());
        return ApiResponse.ok(true);
    }

    /* ==================== 人员角色设置 ==================== */

    /**
     * 角色人数概览：人员角色设置页顶部的三张统计卡。
     *
     * <p>只统计启用账号（countByRole 里带 status = 1）：禁用账号的角色没有实际意义，
     * 把它算进「系统管理员人数」会让人误判还剩几个可用管理员。
     */
    @GetMapping("/role-summary")
    public ApiResponse<Map<String, Long>> roleSummary() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Role r : Role.values()) {
            out.put(r.name(), userMapper.countByRole(r.name()));
        }
        return ApiResponse.ok(out);
    }

    /** 人员角色设置页的分页列表：带科室名，可按角色/科室/关键字/状态筛 */
    @GetMapping("/role-page")
    public ApiResponse<PageResponse<UserRoleView>> rolePage(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status
    ) {
        if (page < 1) page = 1;
        if (size < 1) size = 20;
        if (size > 200) size = 200;
        // 非法角色值直接归一成 USER，与 Role.of 的兜底口径一致，不给 SQL 拼脏值的机会
        String roleFilter = (role == null || role.isBlank()) ? null : parseRole(role).name();
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        long total = userMapper.countRolePage(roleFilter, deptId, kw, status);
        List<UserRoleView> views = total == 0
                ? List.of()
                : userMapper.selectRolePage((page - 1) * size, size, roleFilter, deptId, kw, status);
        for (UserRoleView v : views) {
            Role r = Role.of(v.getRole());
            v.setRole(r.name());
            v.setRoleLabel(r.getLabel());
        }
        return ApiResponse.ok(PageResponse.of(page, size, total, views));
    }

    /** 单人授予/收回角色 */
    @PutMapping("/{id}/role")
    public ApiResponse<Map<String, Object>> updateRole(@PathVariable Long id, @Valid @RequestBody UpdateRoleRequest req) {
        UserEntity existed = userMapper.selectById(id);
        if (existed == null) {
            throw new BizException(40001, "用户不存在");
        }
        boolean changed = applyRole(existed, req.getRole());
        Role now = Role.of(existed.getRole());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", existed.getId());
        out.put("changed", changed);
        out.put("role", now.name());
        out.put("roleLabel", now.getLabel());
        // 回读一次拿最新的 update_time：页面上的「最近变更」列要立刻对上，否则像是没保存成功
        UserEntity fresh = userMapper.selectById(existed.getId());
        out.put("updateTime", fresh == null ? null : fresh.getUpdateTime());
        return ApiResponse.ok(out);
    }

    /**
     * 批量授予/收回角色。
     *
     * <p>逐条走与单条完全相同的两条保护（不能改自己、必须留一个启用的管理员）。
     * 一条不通过不整批回滚，而是进 skipped 明细回给界面：批量场景里「部分成功 + 说清谁被跳过」
     * 比「整批失败、不知道卡在哪个人身上」有用得多。
     */
    @PutMapping("/roles/batch")
    public ApiResponse<Map<String, Object>> updateRolesBatch(@Valid @RequestBody BatchRoleRequest req) {
        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw new BizException(40001, "没有需要调整的人员");
        }
        List<String> updated = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (BatchRoleItem item : req.getItems()) {
            UserEntity existed = item.getId() == null ? null : userMapper.selectById(item.getId());
            if (existed == null) {
                skipped.add(skippedItem(item.getId(), null, "用户不存在"));
                continue;
            }
            try {
                if (applyRole(existed, item.getRole())) {
                    updated.add(existed.getRealName());
                }
                // applyRole 返回 false 表示他本来就是这个角色：不计数也不算失败
            } catch (BizException e) {
                skipped.add(skippedItem(existed.getId(), existed.getRealName(), e.getMessage()));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", updated.size());
        out.put("updatedNames", updated);
        out.put("skipped", skipped);
        return ApiResponse.ok(out);
    }

    /**
     * 改角色的唯一落库入口：校验角色值 → 两条保护 → 只写 role 列。
     *
     * @return true 表示真的写了库；目标已是该角色时返回 false
     */
    private boolean applyRole(UserEntity existed, String rawRole) {
        Role newRole = parseRole(rawRole);
        if (Role.of(existed.getRole()) == newRole) {
            return false;
        }
        guardSelfLock(existed.getId(), true);
        guardLastAdmin(existed, newRole, existed.getStatus());
        userMapper.updateRole(existed.getId(), newRole.name());
        // 回写内存对象，调用方拿到的就是调整后的状态，不用再查一次库
        existed.setRole(newRole.name());
        return true;
    }

    private static Map<String, Object> skippedItem(Long id, String name, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name == null || name.isBlank() ? "未知账号" : name);
        m.put("reason", reason);
        return m;
    }

    public static class CreateUserRequest {
        @NotBlank
        private String username;
        @NotBlank
        private String password;
        @NotBlank
        private String realName;
        private Long deptId;
        /** 不传即普通员工 */
        private String role;
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

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
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
        /** 角色：USER / DEPT_ADMIN / ADMIN */
        private String role;
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

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
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
        @Min(0)
        @Max(1)
        private Integer status;

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }
    }

    /** 单条授予/收回角色 */
    public static class UpdateRoleRequest {
        /** USER / DEPT_ADMIN / ADMIN */
        @NotBlank
        private String role;

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }
    }

    /** 批量授予/收回角色 */
    public static class BatchRoleRequest {
        @Valid
        // 给 message：不加的话校验器会先兜住，界面只能看到英文的 must not be empty
        @NotEmpty(message = "没有需要调整的人员")
        private List<BatchRoleItem> items;

        public List<BatchRoleItem> getItems() {
            return items;
        }

        public void setItems(List<BatchRoleItem> items) {
            this.items = items;
        }
    }

    public static class BatchRoleItem {
        @NotNull
        private Long id;
        @NotBlank
        private String role;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }
    }
}
