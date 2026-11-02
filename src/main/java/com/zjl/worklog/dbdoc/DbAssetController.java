package com.zjl.worklog.dbdoc;

import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.api.PageResponse;
import com.zjl.worklog.dbdoc.dto.CatalogView;
import com.zjl.worklog.dbdoc.dto.ColumnInfo;
import com.zjl.worklog.dbdoc.dto.DiffPreview;
import com.zjl.worklog.dbdoc.dto.IndexInfo;
import com.zjl.worklog.dbdoc.dto.ManualEditView;
import com.zjl.worklog.dbdoc.dto.ImportRequest;
import com.zjl.worklog.dbdoc.dto.ImportResult;
import com.zjl.worklog.dbdoc.dto.TableChangeItem;
import com.zjl.worklog.dbdoc.dto.TableDetailView;
import com.zjl.worklog.dbdoc.dto.TableSchema;
import com.zjl.worklog.dbdoc.entity.DbDatabaseEntity;
import com.zjl.worklog.dbdoc.entity.DbDocEntity;
import com.zjl.worklog.dbdoc.entity.DbSystemEntity;
import com.zjl.worklog.dbdoc.entity.DbTableDocEntity;
import com.zjl.worklog.dbdoc.entity.DbTableVersionEntity;
import com.zjl.worklog.dbdoc.mapper.DbDocMapper;
import com.zjl.worklog.security.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 表结构资产目录 REST API。
 *
 * 三级目录：业务系统（/systems）→ 数据库用户（/databases）→ 表（/tables），
 * 再加两个动作接口：/preview-diff 导入前先看差异，/import 把脚本合并进目录。
 * 与 v1 的 DbDocController 共用 /api/db-doc 前缀，互不覆盖。
 */
@Validated
@RestController
@RequestMapping("/api/db-doc")
public class DbAssetController {

    private final DbAssetService assetService;
    private final DbTableImportService importService;
    private final DbDocMapper batchMapper;

    public DbAssetController(DbAssetService assetService,
                             DbTableImportService importService,
                             DbDocMapper batchMapper) {
        this.assetService = assetService;
        this.importService = importService;
        this.batchMapper = batchMapper;
    }

    // ============================== 业务系统 ==============================

    /** 系统列表（带每个系统的库数、表数） */
    @GetMapping("/systems")
    public ApiResponse<List<DbSystemEntity>> systems(@RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) Integer status) {
        return ApiResponse.ok(assetService.listSystems(keyword, status));
    }

    /** 新增系统 */
    @PostMapping("/systems")
    public ApiResponse<DbSystemEntity> createSystem(@Valid @RequestBody SystemRequest req) {
        DbSystemEntity form = new DbSystemEntity();
        form.setSystemName(req.getSystemName());
        form.setSystemCode(req.getSystemCode());
        form.setVendor(req.getVendor());
        form.setDbType(req.getDbType());
        form.setVersion(req.getVersion());
        form.setOwnerName(req.getOwnerName());
        form.setRemark(req.getRemark());
        form.setStatus(req.getStatus());
        form.setSortNo(req.getSortNo());
        return ApiResponse.ok(assetService.createSystem(form, currentUserId()));
    }

    /** 修改系统 */
    @PutMapping("/systems/{id}")
    public ApiResponse<DbSystemEntity> updateSystem(@PathVariable Long id,
                                                    @Valid @RequestBody SystemRequest req) {
        DbSystemEntity form = new DbSystemEntity();
        form.setSystemName(req.getSystemName());
        form.setSystemCode(req.getSystemCode());
        form.setVendor(req.getVendor());
        form.setDbType(req.getDbType());
        form.setVersion(req.getVersion());
        form.setOwnerName(req.getOwnerName());
        form.setRemark(req.getRemark());
        form.setStatus(req.getStatus());
        form.setSortNo(req.getSortNo());
        return ApiResponse.ok(assetService.updateSystem(id, form));
    }

    /** 删除系统（名下有库或表时会被服务层拦下） */
    @DeleteMapping("/systems/{id}")
    public ApiResponse<Boolean> deleteSystem(@PathVariable Long id) {
        assetService.deleteSystem(id);
        return ApiResponse.ok(true);
    }

    // ============================== 数据库用户 ==============================

    /** 数据库用户列表 */
    @GetMapping("/databases")
    public ApiResponse<List<DbDatabaseEntity>> databases(@RequestParam(required = false) Long systemId,
                                                         @RequestParam(required = false) String keyword,
                                                         @RequestParam(required = false) Integer status) {
        return ApiResponse.ok(assetService.listDatabases(systemId, keyword, status));
    }

    /** 某系统下启用的数据库用户，SQL 分析页的下拉用 */
    @GetMapping("/databases/enabled")
    public ApiResponse<List<DbDatabaseEntity>> enabledDatabases(@RequestParam Long systemId) {
        return ApiResponse.ok(assetService.listDatabases(systemId, null, 1));
    }

    /** 新增数据库用户 */
    @PostMapping("/databases")
    public ApiResponse<DbDatabaseEntity> createDatabase(@Valid @RequestBody DatabaseRequest req) {
        return ApiResponse.ok(assetService.createDatabase(toEntity(req, new DbDatabaseEntity()), currentUserId()));
    }

    /** 修改数据库用户 */
    @PutMapping("/databases/{id}")
    public ApiResponse<DbDatabaseEntity> updateDatabase(@PathVariable Long id,
                                                        @Valid @RequestBody DatabaseRequest req) {
        DbDatabaseEntity form = toEntity(req, new DbDatabaseEntity());
        return ApiResponse.ok(assetService.updateDatabase(id, form));
    }

    /** 启用/停用数据库用户 */
    @PostMapping("/databases/{id}/toggle")
    public ApiResponse<DbDatabaseEntity> toggleDatabase(@PathVariable Long id,
                                                        @RequestParam Integer status) {
        return ApiResponse.ok(assetService.toggleDatabase(id, status));
    }

    /** 删除数据库用户（名下有表会被拦下） */
    @DeleteMapping("/databases/{id}")
    public ApiResponse<Boolean> deleteDatabase(@PathVariable Long id) {
        assetService.deleteDatabase(id);
        return ApiResponse.ok(true);
    }

    // ============================== 目录与表 ==============================

    /** 三级目录概览 */
    @GetMapping("/catalog")
    public ApiResponse<CatalogView> catalog() {
        return ApiResponse.ok(assetService.catalog());
    }

    /** 表目录分页：可按系统、库用户、表名/注释、字段名过滤 */
    @GetMapping("/tables")
    public ApiResponse<PageResponse<DbTableDocEntity>> tables(
            @RequestParam(required = false) Long systemId,
            @RequestParam(required = false) Long databaseId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String columnKeyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(assetService.pageTables(systemId, databaseId, keyword, columnKeyword, status, page, size));
    }

    /** 表详情 */
    @GetMapping("/tables/{id}")
    public ApiResponse<TableDetailView> tableDetail(@PathVariable Long id) {
        return ApiResponse.ok(assetService.tableDetail(id));
    }

    /** 人工补充说明 / 手工调整在用与疑似下线 */
    @PutMapping("/tables/{id}/manual")
    public ApiResponse<DbTableDocEntity> updateTableManual(@PathVariable Long id,
                                                           @RequestBody ManualRequest req) {
        return ApiResponse.ok(assetService.updateTableManual(id, req.getRemark(), req.getStatus(), currentUserId()));
    }

    /**
     * 人工修订表结构：已经登记的表不再只能靠重新导脚本改，页面就能改表名、注释、字段和索引。
     * 与导入共用同一套差异判定 —— 真变了才覆盖当前快照并留一条 MANUAL 历史版本，
     * 那条版本的 batch_id 为空，事后一眼分得清「人改的」还是「脚本导的」。
     */
    @PutMapping("/tables/{id}/structure")
    public ApiResponse<ManualEditView> updateTableStructure(@PathVariable Long id,
                                                            @RequestBody StructureRequest req) {
        return ApiResponse.ok(importService.updateStructureManually(id, req.toSchema(), currentUserId()));
    }

    /** 手工建档一张表：现场只有零散几张表、凑不出一份建表脚本时，直接在目录里补 */
    @PostMapping("/tables")
    public ApiResponse<DbTableDocEntity> createTable(@RequestBody StructureRequest req) {
        return ApiResponse.ok(importService.createTableManually(req.getDatabaseId(), req.toSchema(), currentUserId()));
    }

    /** 删除表登记 */
    @DeleteMapping("/tables/{id}")
    public ApiResponse<Boolean> deleteTable(@PathVariable Long id) {
        assetService.deleteTable(id);
        return ApiResponse.ok(true);
    }

    /** 某张表的历史版本 */
    @GetMapping("/tables/{id}/versions")
    public ApiResponse<List<DbTableVersionEntity>> versions(@PathVariable Long id) {
        return ApiResponse.ok(assetService.listVersions(id));
    }

    /** 单个历史版本快照 */
    @GetMapping("/table-versions/{versionId}")
    public ApiResponse<DbTableVersionEntity> versionDetail(@PathVariable Long versionId) {
        return ApiResponse.ok(assetService.versionDetail(versionId));
    }

    /** 某个库用户下的全部表结构，ER 图用 */
    @GetMapping("/databases/{id}/tables")
    public ApiResponse<List<DbTableDocEntity>> databaseTables(@PathVariable Long id,
                                                              @RequestParam(defaultValue = "300") int limit) {
        return ApiResponse.ok(assetService.databaseTables(id, limit));
    }

    // ============================== 预览与导入 ==============================

    /** 导入前差异预览，不落库 */
    @PostMapping("/preview-diff")
    public ApiResponse<DiffPreview> previewDiff(@Valid @RequestBody PreviewRequest req) {
        return ApiResponse.ok(importService.preview(req.getSql(), req.getDatabaseId(), req.getTables()));
    }

    /** 解析并增量合并进目录 */
    @PostMapping("/import")
    public ApiResponse<ImportResult> importSql(@Valid @RequestBody ImportRequest req) {
        return ApiResponse.ok(importService.importSql(req, currentUserId()));
    }

    /** 把 v1 遗留的未归类记录重新导入目录 */
    @PostMapping("/batches/{id}/rearchive")
    public ApiResponse<ImportResult> rearchive(@PathVariable Long id, @RequestBody ImportRequest req) {
        DbDocEntity legacy = batchMapper.selectById(id);
        if (legacy == null) {
            return ApiResponse.fail(404, "历史分析记录不存在");
        }
        req.setSql(legacy.getSourceSql());
        return ApiResponse.ok(importService.importSql(req, currentUserId()));
    }

    /** 某批次涉及的表变更清单 */
    @GetMapping("/batches/{id}/changes")
    public ApiResponse<List<TableChangeItem>> batchChanges(@PathVariable Long id) {
        return ApiResponse.ok(importService.batchChanges(id));
    }

    // ============================== 内部 DTO ==============================

    /** 系统表单 */
    @Data
    public static class SystemRequest {
        @NotBlank(message = "系统名称不能为空")
        private String systemName;
        private String systemCode;
        private String vendor;
        private String dbType;
        private String version;
        private String ownerName;
        private String remark;
        private Integer status;
        private Integer sortNo;
    }

    /** 数据库用户表单 */
    @Data
    public static class DatabaseRequest {
        private Long systemId;
        @NotBlank(message = "数据库用户/模式名不能为空")
        private String dbUser;
        private String dbName;
        private String dbType;
        private String host;
        private Integer port;
        private String env;
        private String jdbcUrl;
        private Long credentialId;
        private String remark;
        private Integer status;
    }

    /** 表的人工维护字段 */
    @Data
    public static class ManualRequest {
        private String remark;
        private Integer status;
    }
    /**
     * 人工维护表结构的请求体，形状与导入用的 TableSchema 一致，多一个 databaseId（仅手工建档要选归属）。
     * 这里不挂 @NotBlank：字段级的底线校验全部集中在服务层的 normalizeManual 里，
     * 一条路上只留一处校验，避免出现接口文案和服务层文案打架。
     */
    @Data
    public static class StructureRequest {
        /** 归属数据库用户 ID：手工建档必填；编辑已有表时忽略，改归属不是这个接口该干的事 */
        private Long databaseId;
        /** 表名 */
        private String tableName;
        /** 表注释/业务含义 */
        private String tableComment;
        /** 字段明细，顺序即在页面里的展示顺序 */
        private List<ColumnInfo> columns;
        /** 索引明细；主键也只认这里 type=PRIMARY 的那一条 */
        private List<IndexInfo> indexes;

        /** 摊成服务层通用的 TableSchema。dbUser 留空，归属由服务端按现有行或所选库用户决定 */
        TableSchema toSchema() {
            return TableSchema.builder()
                    .tableName(tableName)
                    .tableComment(tableComment)
                    .columns(columns)
                    .indexes(indexes)
                    .build();
        }
    }

    /** 差异预览请求：SQL 脚本与结构化表清单二选一 */
    @Data
    public static class PreviewRequest {
        private String sql;
        /** Excel 数据字典等整理好的表结构，非空时跳过 SQL 解析 */
        private List<TableSchema> tables;
        /** 对照哪个库用户目录；不传则全部按新增预览 */
        private Long databaseId;
    }

    private DbDatabaseEntity toEntity(DatabaseRequest req, DbDatabaseEntity entity) {
        entity.setSystemId(req.getSystemId());
        entity.setDbUser(req.getDbUser());
        entity.setDbName(req.getDbName());
        entity.setDbType(req.getDbType());
        entity.setHost(req.getHost());
        entity.setPort(req.getPort());
        entity.setEnv(req.getEnv());
        entity.setJdbcUrl(req.getJdbcUrl());
        entity.setCredentialId(req.getCredentialId());
        entity.setRemark(req.getRemark());
        entity.setStatus(req.getStatus());
        return entity;
    }

    private Long currentUserId() {
        return UserContext.get().getId();
    }

    /** 前端下拉常用「系统 + 库」两级一起取，省一次往返 */
    @GetMapping("/options")
    public ApiResponse<Map<String, Object>> options() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("systems", assetService.listSystems(null, 1));
        map.put("databases", assetService.listDatabases(null, null, 1));
        map.put("catalog", assetService.catalog());
        return ApiResponse.ok(map);
    }
}