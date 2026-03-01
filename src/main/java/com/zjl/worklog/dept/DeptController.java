package com.zjl.worklog.dept;

import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.api.PageResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.dept.dto.DeptTreeNode;
import com.zjl.worklog.dept.entity.DeptEntity;
import com.zjl.worklog.dept.mapper.DeptMapper;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Validated
@RestController
@RequestMapping("/api/depts")
public class DeptController {

    private final DeptMapper deptMapper;

    public DeptController(DeptMapper deptMapper) {
        this.deptMapper = deptMapper;
    }

    @GetMapping
    public ApiResponse<PageResponse<DeptEntity>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String deptCode,
            @RequestParam(required = false) String deptName,
            @RequestParam(required = false) Long parentId,
            @RequestParam(required = false) Integer status
    ) {
        if (page < 1) page = 1;
        if (size < 1) size = 10;
        long total = deptMapper.count(deptCode, deptName, parentId, status);
        long offset = (page - 1) * size;
        List<DeptEntity> records = total == 0 ? List.of() : deptMapper.selectPage(offset, size, deptCode, deptName, parentId, status);
        return ApiResponse.ok(PageResponse.of(page, size, total, records));
    }

    @GetMapping("/my-root-children")
    public ApiResponse<List<DeptEntity>> myRootChildren() {
        CurrentUser cu = UserContext.get();
        if (cu == null) {
            throw new BizException(401, "未登录");
        }
        if (cu.getDeptId() == null) {
            throw new BizException(40001, "用户缺少科室ID");
        }

        List<DeptEntity> all = deptMapper.selectAllEnabled();
        if (all.isEmpty()) {
            return ApiResponse.ok(List.of());
        }

        Map<Long, DeptEntity> byId = new HashMap<>();
        Map<Long, List<DeptEntity>> childrenMap = new HashMap<>();
        for (DeptEntity d : all) {
            byId.put(d.getId(), d);
            if (d.getParentId() != null) {
                childrenMap.computeIfAbsent(d.getParentId(), k -> new ArrayList<>()).add(d);
            }
        }

        DeptEntity cur = byId.get(cu.getDeptId());
        if (cur == null) {
            throw new BizException(40001, "用户科室不存在或已禁用");
        }

        // 向上追溯到一级科室（root）
        while (cur.getParentId() != null && byId.containsKey(cur.getParentId())) {
            cur = byId.get(cur.getParentId());
        }

        Long rootId = cur.getId();

        // 返回 root 及其下属所有子科室（扁平列表）
        List<DeptEntity> result = new ArrayList<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(rootId);

        while (!stack.isEmpty()) {
            Long id = stack.pop();
            DeptEntity node = byId.get(id);
            if (node != null) {
                result.add(node);
            }

            List<DeptEntity> children = childrenMap.get(id);
            if (children != null && !children.isEmpty()) {
                children.sort(Comparator
                        .comparing((DeptEntity n) -> n.getSortOrder() == null ? 0 : n.getSortOrder())
                        .thenComparing(n -> n.getId() == null ? 0L : n.getId()));
                // 使用栈：为了保持排序后从小到大输出，这里倒序 push
                for (int i = children.size() - 1; i >= 0; i--) {
                    stack.push(children.get(i).getId());
                }
            }
        }

        return ApiResponse.ok(result);
    }

    @GetMapping("/tree")
    public ApiResponse<List<DeptTreeNode>> tree() {
        CurrentUser cu = UserContext.get();
        if (cu == null) {
            throw new BizException(401, "未登录");
        }
        if (cu.getDeptId() == null) {
            throw new BizException(40001, "用户缺少科室ID");
        }

        List<DeptEntity> all = deptMapper.selectAllEnabled();
        if (all.isEmpty()) {
            return ApiResponse.ok(List.of());
        }

        Map<Long, DeptEntity> byId = new HashMap<>();
        Map<Long, List<DeptEntity>> childrenMap = new HashMap<>();
        for (DeptEntity d : all) {
            byId.put(d.getId(), d);
            if (d.getParentId() != null) {
                childrenMap.computeIfAbsent(d.getParentId(), k -> new ArrayList<>()).add(d);
            }
        }

        DeptEntity cur = byId.get(cu.getDeptId());
        if (cur == null) {
            throw new BizException(40001, "用户科室不存在或已禁用");
        }

        // 向上追溯到一级科室（root）
        while (cur.getParentId() != null && byId.containsKey(cur.getParentId())) {
            cur = byId.get(cur.getParentId());
        }
        Long rootId = cur.getId();

        // root 及其下属所有子科室（扁平列表，用于组树）
        List<DeptEntity> list = new ArrayList<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(rootId);

        while (!stack.isEmpty()) {
            Long id = stack.pop();
            DeptEntity node = byId.get(id);
            if (node != null) {
                list.add(node);
            }

            List<DeptEntity> children = childrenMap.get(id);
            if (children != null && !children.isEmpty()) {
                children.sort(Comparator
                        .comparing((DeptEntity n) -> n.getSortOrder() == null ? 0 : n.getSortOrder())
                        .thenComparing(n -> n.getId() == null ? 0L : n.getId()));
                for (int i = children.size() - 1; i >= 0; i--) {
                    stack.push(children.get(i).getId());
                }
            }
        }

        Map<Long, DeptTreeNode> map = new HashMap<>();
        for (DeptEntity e : list) {
            map.put(e.getId(), new DeptTreeNode(e.getId(), e.getDeptCode(), e.getDeptName(), e.getParentId(), e.getSortOrder(), e.getStatus(), new ArrayList<>()));
        }

        List<DeptTreeNode> roots = new ArrayList<>();
        for (DeptTreeNode node : map.values()) {
            Long pid = node.getParentId();
            if (pid == null || !map.containsKey(pid)) {
                roots.add(node);
            } else {
                map.get(pid).getChildren().add(node);
            }
        }

        sortTree(roots);
        return ApiResponse.ok(roots);
    }

    @PostMapping
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody CreateDeptRequest req) {
        DeptEntity existed = deptMapper.selectByDeptCode(req.getDeptCode());
        if (existed != null) {
            throw new BizException(40001, "deptCode已存在");
        }

        DeptEntity entity = new DeptEntity();
        entity.setDeptCode(req.getDeptCode());
        entity.setDeptName(req.getDeptName());
        entity.setParentId(req.getParentId());
        entity.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());
        entity.setStatus(req.getStatus() == null ? 1 : req.getStatus());

        deptMapper.insert(entity);
        return ApiResponse.ok(Collections.singletonMap("id", entity.getId()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Boolean> update(@PathVariable Long id, @Valid @RequestBody UpdateDeptRequest req) {
        DeptEntity existed = deptMapper.selectById(id);
        if (existed == null) {
            throw new BizException(40001, "科室不存在");
        }

        DeptEntity update = new DeptEntity();
        update.setId(id);
        update.setDeptName(req.getDeptName());
        update.setParentId(req.getParentId());
        update.setSortOrder(req.getSortOrder());
        update.setStatus(req.getStatus());
        deptMapper.update(update);
        return ApiResponse.ok(true);
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Boolean> updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateStatusRequest req) {
        DeptEntity existed = deptMapper.selectById(id);
        if (existed == null) {
            throw new BizException(40001, "科室不存在");
        }
        deptMapper.updateStatus(id, req.getStatus());
        return ApiResponse.ok(true);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        DeptEntity existed = deptMapper.selectById(id);
        if (existed == null) {
            return ApiResponse.ok(true);
        }

        long children = deptMapper.countChildren(id);
        if (children > 0) {
            throw new BizException(40001, "存在子科室，不允许删除");
        }
        long users = deptMapper.countUsersInDept(id);
        if (users > 0) {
            throw new BizException(40001, "科室下存在用户，不允许删除");
        }

        deptMapper.deleteById(id);
        return ApiResponse.ok(true);
    }

    private void sortTree(List<DeptTreeNode> nodes) {
        nodes.sort(Comparator
                .comparing((DeptTreeNode n) -> n.getSortOrder() == null ? 0 : n.getSortOrder())
                .thenComparing(n -> n.getId() == null ? 0L : n.getId()));
        for (DeptTreeNode n : nodes) {
            if (n.getChildren() != null && !n.getChildren().isEmpty()) {
                sortTree(n.getChildren());
            }
        }
    }

    public static class CreateDeptRequest {
        @NotBlank
        private String deptCode;
        @NotBlank
        private String deptName;
        private Long parentId;
        private Integer sortOrder;
        private Integer status;

        public String getDeptCode() {
            return deptCode;
        }

        public void setDeptCode(String deptCode) {
            this.deptCode = deptCode;
        }

        public String getDeptName() {
            return deptName;
        }

        public void setDeptName(String deptName) {
            this.deptName = deptName;
        }

        public Long getParentId() {
            return parentId;
        }

        public void setParentId(Long parentId) {
            this.parentId = parentId;
        }

        public Integer getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(Integer sortOrder) {
            this.sortOrder = sortOrder;
        }

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }
    }

    public static class UpdateDeptRequest {
        @NotBlank
        private String deptName;
        private Long parentId;
        private Integer sortOrder;
        private Integer status;

        public String getDeptName() {
            return deptName;
        }

        public void setDeptName(String deptName) {
            this.deptName = deptName;
        }

        public Long getParentId() {
            return parentId;
        }

        public void setParentId(Long parentId) {
            this.parentId = parentId;
        }

        public Integer getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(Integer sortOrder) {
            this.sortOrder = sortOrder;
        }

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
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
