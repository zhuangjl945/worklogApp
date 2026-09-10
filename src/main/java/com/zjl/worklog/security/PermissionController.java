package com.zjl.worklog.security;

import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.exception.BizException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限设置接口。
 *
 * <p>读接口刻意不设门槛（登录即可）：前端要靠它渲染菜单和按钮，
 * 如果只有管理员能读，普通员工就只能永远按内置下限渲染，管理员「把某功能调严」会看不到效果。
 * 读到的内容也只是「哪个功能要什么角色」，不含任何业务数据。
 *
 * <p>写接口同时受两道门槛：内置 ADMIN + 权限点 permission.manage（后者固定为 ADMIN，
 * 故意不给配置——能改权限的入口若可调，管理员一失手就把自己和所有人都锁在外面）。
 */
@Validated
@RestController
@RequestMapping("/api/permissions")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /** 全量权限点 + 当前生效门槛 + 内置下限，前端菜单收口和权限页共用这一份 */
    @GetMapping
    public ApiResponse<Map<String, Object>> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Permission p : Permission.catalog()) {
            Role min = permissionService.minRole(p);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", p.getKey());
            item.put("label", p.getLabel());
            item.put("description", p.getDescription());
            item.put("group", p.getGroup());
            item.put("minRole", min.name());
            item.put("minRoleLabel", min.getLabel());
            item.put("floor", p.getFloor().name());
            item.put("floorLabel", p.getFloor().getLabel());
            // locked：这一行不给改；customized：当前值已高于内置下限
            item.put("locked", p.isLocked());
            item.put("customized", min != p.getFloor());
            items.add(item);
        }
        List<Map<String, String>> roles = new ArrayList<>();
        for (Role r : Role.values()) {
            Map<String, String> opt = new LinkedHashMap<>();
            opt.put("value", r.name());
            opt.put("label", r.getLabel());
            roles.add(opt);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("roles", roles);
        return ApiResponse.ok(result);
    }

    /**
     * 批量调整门槛。
     *
     * <p>低于内置下限的请求不会报错，而是被抬到下限并在 adjusted 里如实回传，
     * 让界面能明确告诉操作人「这项不能放宽」。
     */
    @PutMapping
    @RequireRole(value = Role.ADMIN, permission = Permission.PERMISSION_MANAGE, message = "仅系统管理员可调整权限")
    public ApiResponse<Map<String, Object>> update(@Valid @RequestBody UpdateRequest req) {
        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw new BizException(40001, "没有需要保存的权限项");
        }
        List<Map<String, String>> adjusted = new ArrayList<>();
        for (Item item : req.getItems()) {
            Permission p = Permission.ofKey(item.getKey());
            if (p == null) {
                throw new BizException(40001, "未知权限点：" + item.getKey());
            }
            Role requested = Role.of(item.getRole());
            Role applied = permissionService.save(p, requested);
            if (applied != requested) {
                Map<String, String> a = new LinkedHashMap<>();
                a.put("key", p.getKey());
                a.put("label", p.getLabel());
                a.put("requested", requested.name());
                a.put("applied", applied.name());
                a.put("appliedLabel", applied.getLabel());
                adjusted.add(a);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("permissions", permissionService.snapshot());
        result.put("adjusted", adjusted);
        return ApiResponse.ok(result);
    }

    /** 全部恢复内置下限 */
    @PostMapping("/reset")
    @RequireRole(value = Role.ADMIN, permission = Permission.PERMISSION_MANAGE, message = "仅系统管理员可调整权限")
    public ApiResponse<Map<String, String>> reset() {
        permissionService.resetAll();
        return ApiResponse.ok(permissionService.snapshot());
    }

    public static class UpdateRequest {
        @Valid
        private List<Item> items;

        public List<Item> getItems() {
            return items;
        }

        public void setItems(List<Item> items) {
            this.items = items;
        }
    }

    public static class Item {
        @NotBlank
        private String key;
        @NotBlank
        private String role;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }
    }
}