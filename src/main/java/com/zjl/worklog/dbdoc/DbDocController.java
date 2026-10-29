package com.zjl.worklog.dbdoc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.api.PageResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.dbdoc.dto.ParseResult;
import com.zjl.worklog.dbdoc.entity.DbDocEntity;
import com.zjl.worklog.dbdoc.mapper.DbDocMapper;
import com.zjl.worklog.security.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 数据库文档 REST API。
 * 提供 SQL 解析预览、文档保存、分页查询、详情查看、删除等功能。
 */
@Validated
@RestController
@RequestMapping("/api/db-doc")
public class DbDocController {

    private final SqlParseService sqlParseService;
    private final DbDocMapper dbDocMapper;
    private final ObjectMapper objectMapper;

    public DbDocController(SqlParseService sqlParseService,
                           DbDocMapper dbDocMapper,
                           ObjectMapper objectMapper) {
        this.sqlParseService = sqlParseService;
        this.dbDocMapper = dbDocMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 仅解析不保存（前端实时预览用）
     */
    @PostMapping("/parse")
    public ApiResponse<ParseResult> parse(@Valid @RequestBody ParseRequest req) {
        ParseResult result = sqlParseService.parseDdl(req.getSql());
        return ApiResponse.ok(result);
    }

    /**
     * 解析并保存文档记录
     */
    @PostMapping("/save")
    public ApiResponse<DbDocEntity> save(@Valid @RequestBody SaveRequest req) {
        // 解析 SQL
        ParseResult result = sqlParseService.parseDdl(req.getSql());

        // 序列化解析结果为 JSON
        String parsedJson;
        try {
            parsedJson = objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new BizException(500, "解析结果序列化失败: " + e.getMessage());
        }

        // 构建实体并保存
        DbDocEntity entity = new DbDocEntity();
        entity.setTitle(req.getTitle());
        entity.setSourceSql(req.getSql());
        entity.setParsedJson(parsedJson);
        entity.setTableCount(result.getTables() != null ? result.getTables().size() : 0);
        entity.setCreatedBy(UserContext.get().getId());

        dbDocMapper.insert(entity);
        return ApiResponse.ok(entity);
    }

    /**
     * 分页查询已保存文档
     */
    @GetMapping
    public ApiResponse<PageResponse<DbDocEntity>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword
    ) {
        if (page < 1) page = 1;
        if (size < 1) size = 10;

        int total = dbDocMapper.countByKeyword(keyword);
        int offset = (page - 1) * size;
        List<DbDocEntity> records = total == 0
                ? List.of()
                : dbDocMapper.selectPage(keyword, offset, size);

        return ApiResponse.ok(PageResponse.of(page, size, total, records));
    }

    /**
     * 单条详情
     */
    @GetMapping("/{id}")
    public ApiResponse<DbDocEntity> detail(@PathVariable Long id) {
        DbDocEntity entity = dbDocMapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "文档不存在");
        }
        return ApiResponse.ok(entity);
    }

    /**
     * 删除文档
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        DbDocEntity entity = dbDocMapper.selectById(id);
        if (entity == null) {
            return ApiResponse.ok(true);
        }
        dbDocMapper.deleteById(id);
        return ApiResponse.ok(true);
    }

    // ===== 内部请求 DTO =====

    /** 解析请求 */
    public static class ParseRequest {
        @NotBlank(message = "SQL 不能为空")
        private String sql;

        public String getSql() {
            return sql;
        }

        public void setSql(String sql) {
            this.sql = sql;
        }
    }

    /** 保存请求 */
    public static class SaveRequest {
        @NotBlank(message = "文档标题不能为空")
        private String title;
        @NotBlank(message = "SQL 不能为空")
        private String sql;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getSql() {
            return sql;
        }

        public void setSql(String sql) {
            this.sql = sql;
        }
    }
}
