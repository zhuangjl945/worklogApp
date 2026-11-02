package com.zjl.worklog.dbdoc;

import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.api.PageResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.dbdoc.dto.BatchDetailView;
import com.zjl.worklog.dbdoc.dto.ImportRequest;
import com.zjl.worklog.dbdoc.dto.ImportResult;
import com.zjl.worklog.dbdoc.dto.ParseResult;
import com.zjl.worklog.dbdoc.entity.DbDocEntity;
import com.zjl.worklog.dbdoc.mapper.DbDocMapper;
import com.zjl.worklog.security.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 分析批次 REST API（v1 的「文档」语义在 v2 里降级为「一次 SQL 分析导入的批次」）。
 *
 * 表结构本体已经搬到 db_table_doc，这里只负责：
 * 解析预览、批次列表/详情/删除，以及向后兼容的 /save。
 * 三级目录与增量合并在 {@link DbAssetController} + {@link DbTableImportService}。
 */
@Validated
@RestController
@RequestMapping("/api/db-doc")
public class DbDocController {

    private final SqlParseService sqlParseService;
    private final DbDocMapper dbDocMapper;
    private final DbTableImportService importService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public DbDocController(SqlParseService sqlParseService,
                           DbDocMapper dbDocMapper,
                           DbTableImportService importService,
                           com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.sqlParseService = sqlParseService;
        this.dbDocMapper = dbDocMapper;
        this.importService = importService;
        this.objectMapper = objectMapper;
    }

    /** 仅解析不保存（前端实时预览用） */
    @PostMapping("/parse")
    public ApiResponse<ParseResult> parse(@Valid @RequestBody ParseRequest req) {
        return ApiResponse.ok(sqlParseService.parseDdl(req.getSql()));
    }

    /**
     * 兼容 v1 的保存入口。
     * 带了系统/库信息就走 v2 的增量合并；什么都没带才退回「只存批次、不归类」的老行为，
     * 这样旧脚本、旧页面不至于一下子坏掉。
     */
    @PostMapping("/save")
    public ApiResponse<Object> save(@Valid @RequestBody SaveRequest req) {
        boolean hasTarget = req.getSystemId() != null || req.getDatabaseId() != null
                || notBlank(req.getSystemName()) || notBlank(req.getDbUser());
        if (hasTarget) {
            ImportResult result = importService.importSql(toImportRequest(req), UserContext.get().getId());
            return ApiResponse.ok(result);
        }

        ParseResult result = sqlParseService.parseDdl(req.getSql());
        String parsedJson;
        try {
            parsedJson = objectMapper.writeValueAsString(result);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException(500, "解析结果序列化失败: " + e.getMessage());
        }

        DbDocEntity entity = new DbDocEntity();
        entity.setTitle(req.getTitle());
        entity.setSourceSql(req.getSql());
        entity.setParsedJson(parsedJson);
        entity.setTableCount(result.getTables() != null ? result.getTables().size() : 0);
        entity.setAddedCount(0);
        entity.setUpdatedCount(0);
        entity.setUnchangedCount(0);
        entity.setMissingCount(0);
        entity.setCreatedBy(UserContext.get().getId());
        dbDocMapper.insert(entity);
        return ApiResponse.ok(entity);
    }

    /** 分页查询分析批次，可按系统、库用户过滤 */
    @GetMapping
    public ApiResponse<PageResponse<DbDocEntity>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long systemId,
            @RequestParam(required = false) Long databaseId
    ) {
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 10;
        }
        int total = dbDocMapper.countByCondition(keyword, systemId, databaseId);
        int offset = (page - 1) * size;
        List<DbDocEntity> records = total == 0
                ? List.of()
                : dbDocMapper.selectPage(keyword, systemId, databaseId, offset, size);
        return ApiResponse.ok(PageResponse.of(page, size, total, records));
    }

    /** 单条批次详情：原文 SQL + 本次每张表的处理结论 */
    @GetMapping("/{id}")
    public ApiResponse<BatchDetailView> detail(@PathVariable Long id) {
        DbDocEntity entity = dbDocMapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "文档不存在");
        }
        BatchDetailView view = new BatchDetailView();
        view.setBatch(entity);
        view.setChanges(importService.batchChanges(id));
        view.setArchived(entity.getSystemId() != null);
        return ApiResponse.ok(view);
    }

    /** 删除批次：只删这次的记录，已并入目录的表结构不受影响 */
    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        if (dbDocMapper.selectById(id) == null) {
            return ApiResponse.ok(true);
        }
        dbDocMapper.deleteById(id);
        return ApiResponse.ok(true);
    }

    // ===== 内部请求 DTO =====

    /** 解析请求 */
    @Data
    public static class ParseRequest {
        @NotBlank(message = "SQL 不能为空")
        private String sql;
    }

    /** 保存请求（v2 起可带归属信息） */
    @Data
    public static class SaveRequest {
        @NotBlank(message = "文档标题不能为空")
        private String title;
        @NotBlank(message = "SQL 不能为空")
        private String sql;
        private Long systemId;
        private String systemName;
        private Long databaseId;
        private String dbUser;
        private String dbType;
        private String env;
        /** MERGE / REPLACE */
        private String mode;
    }

    private ImportRequest toImportRequest(SaveRequest req) {
        ImportRequest importRequest = new ImportRequest();
        importRequest.setSql(req.getSql());
        importRequest.setTitle(req.getTitle());
        importRequest.setSystemId(req.getSystemId());
        importRequest.setSystemName(req.getSystemName());
        importRequest.setDatabaseId(req.getDatabaseId());
        importRequest.setDbUser(req.getDbUser());
        importRequest.setDbType(req.getDbType());
        importRequest.setEnv(req.getEnv());
        importRequest.setMode(req.getMode());
        return importRequest;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}