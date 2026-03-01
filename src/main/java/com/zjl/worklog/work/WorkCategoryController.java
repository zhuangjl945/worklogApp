package com.zjl.worklog.work;

import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.api.PageResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.UserContext;
import com.zjl.worklog.work.entity.WorkCategory;
import com.zjl.worklog.work.mapper.WorkCategoryMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/work/categories")
public class WorkCategoryController {

    private final WorkCategoryMapper categoryMapper;

    public WorkCategoryController(WorkCategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    private Long currentDeptId() {
        CurrentUser user = UserContext.get();
        if (user == null) {
            throw new BizException(401, "未登录");
        }
        if (user.getDeptId() == null) {
            throw new BizException(40001, "用户信息异常：缺少科室ID");
        }
        return user.getDeptId();
    }

    @GetMapping
    public ApiResponse<PageResponse<WorkCategory>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) Integer status
    ) {
        if (page < 1) page = 1;
        if (size < 1) size = 10;

        Long deptId = currentDeptId();
        long total = categoryMapper.count(deptId, status);
        long offset = (page - 1) * size;
        List<WorkCategory> records = total == 0 ? List.of() : categoryMapper.selectPage(deptId, offset, size, status);
        return ApiResponse.ok(PageResponse.of(page, size, total, records));
    }

    @GetMapping("/enabled")
    public ApiResponse<List<WorkCategory>> enabled() {
        Long deptId = currentDeptId();
        return ApiResponse.ok(categoryMapper.selectAllEnabled(deptId));
    }

    @PostMapping
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody CreateCategoryRequest req) {
        Long deptId = currentDeptId();
        // admin 全局查重：同一个 categoryCode 在全系统唯一（避免 admin 误创建导致多个科室重复）
        // 如果你希望 admin 也可以在不同科室重复，则需要改成从请求里传 deptId。
        WorkCategory existed = categoryMapper.selectByCode(req.getCategoryCode(), deptId);
        if (existed != null) {
            throw new BizException(40001, "categoryCode已存在");
        }

        WorkCategory entity = new WorkCategory();
        entity.setDeptId(deptId);
        entity.setCategoryCode(req.getCategoryCode());
        entity.setCategoryName(req.getCategoryName());
        entity.setDescription(req.getDescription());
        entity.setStatus(req.getStatus() == null ? 1 : req.getStatus());

        categoryMapper.insert(entity);
        return ApiResponse.ok(Collections.singletonMap("id", entity.getId()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Boolean> update(@PathVariable Long id, @Valid @RequestBody UpdateCategoryRequest req) {
        Long deptId = currentDeptId();
        WorkCategory existed = categoryMapper.selectById(id, deptId);
        if (existed == null) {
            throw new BizException(40001, "分类不存在");
        }

        WorkCategory update = new WorkCategory();
        update.setId(id);
        update.setDeptId(deptId);
        update.setCategoryName(req.getCategoryName());
        update.setDescription(req.getDescription());
        update.setStatus(req.getStatus());
        categoryMapper.update(update);
        return ApiResponse.ok(true);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        Long deptId = currentDeptId();
        WorkCategory existed = categoryMapper.selectById(id, deptId);
        if (existed == null) {
            return ApiResponse.ok(true);
        }
        categoryMapper.softDeleteById(id, deptId);
        return ApiResponse.ok(true);
    }

    public static class CreateCategoryRequest {
        @NotBlank
        private String categoryCode;
        @NotBlank
        private String categoryName;
        private String description;
        private Integer status;

        public String getCategoryCode() {
            return categoryCode;
        }

        public void setCategoryCode(String categoryCode) {
            this.categoryCode = categoryCode;
        }

        public String getCategoryName() {
            return categoryName;
        }

        public void setCategoryName(String categoryName) {
            this.categoryName = categoryName;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }
    }

    public static class UpdateCategoryRequest {
        @NotBlank
        private String categoryName;
        private String description;
        private Integer status;

        public String getCategoryName() {
            return categoryName;
        }

        public void setCategoryName(String categoryName) {
            this.categoryName = categoryName;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }
    }
}
