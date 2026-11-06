package com.zjl.worklog.sqlsnippet;

import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.api.PageResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.UserContext;
import com.zjl.worklog.sqlsnippet.entity.SqlSnippet;
import com.zjl.worklog.sqlsnippet.mapper.SqlSnippetMapper;
import lombok.Data;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 个人常用 SQL。列表、详情、修改、删除都只碰当前登录人自己的记录。
 */
@RestController
@RequestMapping("/api/sql-snippets")
public class SqlSnippetController {

    private static final Set<String> DB_TYPES = Set.of(
            "mysql", "oracle", "postgresql", "sqlserver", "dm", "kingbase");

    private final SqlSnippetMapper snippetMapper;

    public SqlSnippetController(SqlSnippetMapper snippetMapper) {
        this.snippetMapper = snippetMapper;
    }

    @GetMapping
    public ApiResponse<PageResponse<SqlSnippet>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "15") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String dbType
    ) {
        long userId = currentUserId();
        if (page < 1) page = 1;
        if (size < 1) size = 15;
        if (size > 100) size = 100;
        String key = blankToNull(keyword);
        String cat = normalizeCategory(category);
        String db = normalizeDbType(dbType);
        long total = snippetMapper.count(userId, key, cat, db);
        long offset = (page - 1) * size;
        List<SqlSnippet> records = total == 0
                ? List.of()
                : snippetMapper.selectPage(userId, key, cat, db, offset, size);
        return ApiResponse.ok(PageResponse.of(page, size, total, records));
    }

    @GetMapping("/categories")
    public ApiResponse<List<String>> categories() {
        return ApiResponse.ok(snippetMapper.selectCategories(currentUserId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<SqlSnippet> detail(@PathVariable Long id) {
        SqlSnippet row = snippetMapper.selectById(id, currentUserId());
        if (row == null) {
            throw new BizException(40400, "语句不存在");
        }
        return ApiResponse.ok(row);
    }

    @PostMapping
    public ApiResponse<Map<String, Long>> create(@RequestBody SaveRequest req) {
        SqlSnippet entity = new SqlSnippet();
        entity.setTitle(requiredTitle(req));
        entity.setCategory(normalizeCategory(req == null ? null : req.getCategory()));
        entity.setDbType(normalizeDbType(req == null ? null : req.getDbType()));
        entity.setSqlText(requiredSql(req));
        entity.setRemark(normalizeRemark(req == null ? null : req.getRemark()));
        entity.setCreatedBy(currentUserId());
        snippetMapper.insert(entity);
        return ApiResponse.ok(Collections.singletonMap("id", entity.getId()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Boolean> update(@PathVariable Long id, @RequestBody SaveRequest req) {
        SqlSnippet entity = new SqlSnippet();
        entity.setId(id);
        entity.setCreatedBy(currentUserId());
        entity.setTitle(requiredTitle(req));
        entity.setCategory(normalizeCategory(req == null ? null : req.getCategory()));
        entity.setDbType(normalizeDbType(req == null ? null : req.getDbType()));
        entity.setSqlText(requiredSql(req));
        entity.setRemark(normalizeRemark(req == null ? null : req.getRemark()));
        if (snippetMapper.update(entity) == 0) {
            throw new BizException(40400, "语句不存在");
        }
        return ApiResponse.ok(true);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        if (snippetMapper.deleteById(id, currentUserId()) == 0) {
            throw new BizException(40400, "语句不存在");
        }
        return ApiResponse.ok(true);
    }

    private long currentUserId() {
        CurrentUser user = UserContext.get();
        if (user == null || user.getId() == null) {
            throw new BizException(401, "未登录");
        }
        return user.getId();
    }

    private static String requiredTitle(SaveRequest req) {
        String title = req == null ? null : req.getTitle();
        if (title == null || title.isBlank()) {
            throw new BizException(40001, "请填写标题");
        }
        String trimmed = title.trim();
        if (trimmed.length() > 200) {
            throw new BizException(40001, "标题不能超过 200 字");
        }
        return trimmed;
    }

    private static String requiredSql(SaveRequest req) {
        String sql = req == null ? null : req.getSqlText();
        if (sql == null || sql.isBlank()) {
            throw new BizException(40001, "请填写 SQL");
        }
        return sql.trim();
    }

    /** 搜索词只去掉空白，不做备注那种长度限制。 */
    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String normalizeCategory(String value) {
        String trimmed = blankToNull(value);
        if (trimmed != null && trimmed.length() > 50) {
            throw new BizException(40001, "分类不能超过 50 字");
        }
        return trimmed;
    }

    private static String normalizeDbType(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        if (!DB_TYPES.contains(trimmed)) {
            throw new BizException(40001, "数据库类型不正确");
        }
        return trimmed;
    }

    private static String normalizeRemark(String value) {
        String trimmed = blankToNull(value);
        if (trimmed != null && trimmed.length() > 500) {
            throw new BizException(40001, "备注不能超过 500 字");
        }
        return trimmed;
    }

    @Data
    public static class SaveRequest {
        private String title;
        private String category;
        private String dbType;
        private String sqlText;
        private String remark;
    }
}
