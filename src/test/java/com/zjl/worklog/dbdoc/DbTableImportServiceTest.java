package com.zjl.worklog.dbdoc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.dbdoc.dto.ColumnInfo;
import com.zjl.worklog.dbdoc.dto.ImportRequest;
import com.zjl.worklog.dbdoc.dto.IndexInfo;
import com.zjl.worklog.dbdoc.dto.ManualEditView;
import com.zjl.worklog.dbdoc.dto.ImportResult;
import com.zjl.worklog.dbdoc.dto.ParseResult;
import com.zjl.worklog.dbdoc.dto.TableSchema;
import com.zjl.worklog.dbdoc.entity.DbDatabaseEntity;
import com.zjl.worklog.dbdoc.entity.DbDocEntity;
import com.zjl.worklog.dbdoc.entity.DbSystemEntity;
import com.zjl.worklog.dbdoc.entity.DbTableDocEntity;
import com.zjl.worklog.dbdoc.entity.DbTableVersionEntity;
import com.zjl.worklog.dbdoc.mapper.DbDatabaseMapper;
import com.zjl.worklog.dbdoc.mapper.DbDocMapper;
import com.zjl.worklog.dbdoc.mapper.DbTableDocMapper;
import com.zjl.worklog.dbdoc.mapper.DbTableVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 导入合并语义单测。
 * 这里盯的是这次改造最要紧的几条承诺：
 * 新表才 insert、变了才覆盖、没变只 +1、脚本里没出现的表绝不物理删除、
 * 带别的用户前缀能自动归到那个用户名下。
 */
class DbTableImportServiceTest {

    private SqlParseService sqlParseService;
    private DbAssetService assetService;
    private DbDatabaseMapper databaseMapper;
    private DbTableDocMapper tableDocMapper;
    private DbTableVersionMapper versionMapper;
    private DbDocMapper batchMapper;
    private DbTableImportService service;

    @BeforeEach
    void setUp() {
        sqlParseService = mock(SqlParseService.class);
        assetService = mock(DbAssetService.class);
        databaseMapper = mock(DbDatabaseMapper.class);
        tableDocMapper = mock(DbTableDocMapper.class);
        versionMapper = mock(DbTableVersionMapper.class);
        batchMapper = mock(DbDocMapper.class);
        service = new DbTableImportService(sqlParseService, assetService, databaseMapper, tableDocMapper,
                versionMapper, batchMapper, new TableSchemaDiffer(), new TableSchemaCodec(new ObjectMapper()));

        // 批次插入时回填一个 ID，模拟自增主键
        when(batchMapper.insert(any(DbDocEntity.class))).thenAnswer(invocation -> {
            DbDocEntity entity = invocation.getArgument(0);
            entity.setId(7L);
            return 1;
        });
        when(tableDocMapper.insert(any(DbTableDocEntity.class))).thenAnswer(invocation -> {
            DbTableDocEntity entity = invocation.getArgument(0);
            entity.setId(100L);
            return 1;
        });
        when(databaseMapper.refreshTableCount(anyLong())).thenReturn(1);
        when(batchMapper.updateStats(anyLong(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(1);
    }

    @Test
    @DisplayName("目录里没有的表：新增行 + 一条 NEW 版本")
    void testNewTableIsInserted() {
        stubParse(schema("USER_INFO", "用户表", col("id", "BIGINT", false)));
        ImportResult result = service.importSql(request(1L, 10L), 9L);

        assertEquals(1, result.getAddedCount());
        assertEquals(0, result.getUpdatedCount());
        assertEquals(0, result.getUnchangedCount());
        verify(tableDocMapper).insert(any(DbTableDocEntity.class));
        verify(tableDocMapper, never()).updateStructure(any());
        verify(tableDocMapper, never()).touch(anyLong(), anyLong());

        DbTableVersionEntity version = captureVersion();
        assertEquals("NEW", version.getChangeType());
        assertEquals(7L, version.getBatchId());
        assertEquals("首次登记", version.getDiffSummary());
    }

    @Test
    @DisplayName("已有表结构变了：覆盖快照 + CHANGED 版本，并把旧字段数带出来")
    void testChangedTableIsOverwritten() {
        DbTableDocEntity existing = row(50L, "USER_INFO", "BIGINT", false, null);
        when(tableDocMapper.selectFullByDatabaseAndName(10L, "USER_INFO")).thenReturn(existing);
        stubParse(schema("USER_INFO", "用户表", col("id", "VARCHAR", "32", true)));

        ImportResult result = service.importSql(request(1L, 10L), 9L);

        assertEquals(1, result.getUpdatedCount());
        assertEquals(0, result.getAddedCount());
        verify(tableDocMapper).updateStructure(any(DbTableDocEntity.class));
        verify(tableDocMapper, never()).insert(any());

        DbTableVersionEntity version = captureVersion();
        assertEquals("CHANGED", version.getChangeType());
        assertTrue(version.getDiffSummary().contains("字段变更"), version.getDiffSummary());
        assertEquals(1, result.getChanges().get(0).getOldColumnCount());
    }

    @Test
    @DisplayName("结构没变：只做一次确认，不产生新版本")
    void testUnchangedTableOnlyTouched() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<ColumnInfo> cols = List.of(col("id", "BIGINT", false));
        DbTableDocEntity existing = row(50L, "USER_INFO", "BIGINT", false, null);
        // 目录里的 JSON 与新解析出来的完全同源，保证比对一定判为未变化
        existing.setColumnsJson(mapper.writeValueAsString(cols));
        existing.setIndexesJson("[]");
        when(tableDocMapper.selectFullByDatabaseAndName(10L, "USER_INFO")).thenReturn(existing);
        // 目录里那行的 table_comment 是空的，这里也必须留空，否则「注释变更」会把它判成有变化
        stubParse(schema("USER_INFO", "", cols.toArray(new ColumnInfo[0])));

        ImportResult result = service.importSql(request(1L, 10L), 9L);

        assertEquals(1, result.getUnchangedCount());
        verify(tableDocMapper).touch(50L, 9L);
        verify(tableDocMapper, never()).updateStructure(any());
        verify(versionMapper, never()).insert(any(DbTableVersionEntity.class));
    }

    @Test
    @DisplayName("MERGE 只留缺失痕迹；REPLACE 才把没出现的表标成疑似下线")
    void testMissingHandling() {
        stubParse(schema("NEW_TABLE", "新表", col("id", "BIGINT", false)));
        DbTableDocEntity stale = new DbTableDocEntity();
        stale.setId(88L);
        stale.setDatabaseId(10L);
        stale.setSystemId(1L);
        stale.setTableName("OLD_TABLE");
        stale.setStatus(1);
        stale.setColumnCount(3);
        when(tableDocMapper.selectIdNameByDatabase(10L)).thenReturn(List.of(stale));

        ImportResult merged = service.importSql(request(1L, 10L), 9L);
        assertEquals(1, merged.getMissingCount());
        assertTrue(merged.getMissingTables().contains("OLD_TABLE"));
        verify(tableDocMapper, never()).markOfflineByIds(anyList(), anyLong());

        ImportRequest replace = request(1L, 10L);
        replace.setMode(DbTableImportService.MODE_REPLACE);
        ImportResult result = service.importSql(replace, 9L);
        assertEquals(1, result.getMissingCount());
        // 一轮 MERGE + 一轮 REPLACE，各写 1 条 NEW 和 1 条 MISSING，共 4 条版本，且一张表都没被物理删掉
        verify(versionMapper, times(4)).insert(any(DbTableVersionEntity.class));
        verify(tableDocMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("脚本里出现别的用户前缀：在同一系统下自动建档并归到那个用户")
    void testQualifiedPrefixCreatesOwnDatabase() {
        // 只有 HIS 有档案，LIS 需要自动创建
        when(databaseMapper.selectByUk(eq(1L), eq("HIS"), anyString())).thenReturn(database(10L, 1L, "HIS"));
        when(databaseMapper.selectByUk(eq(1L), eq("LIS"), anyString())).thenReturn(null);
        when(assetService.createDatabase(any(DbDatabaseEntity.class), anyLong()))
                .thenReturn(database(11L, 1L, "LIS"));
        stubParse(List.of(
                qualifiedSchema("HIS", "USER_INFO", col("id", "BIGINT", false)),
                qualifiedSchema("LIS", "SAMPLE", col("id", "BIGINT", false))));

        ImportResult result = service.importSql(request(1L, 10L), 9L);

        assertEquals(2, result.getAddedCount());
        verify(tableDocMapper).insert(argThat(row -> row.getDatabaseId() == 11L && "LIS".equals(row.getDbUser())));
        verify(tableDocMapper).insert(argThat(row -> row.getDatabaseId() == 10L));
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("LIS")), result.getWarnings().toString());
    }

    @Test
    @DisplayName("关掉自动建档后，异前缀的表并入所选用户并给出提示")
    void testAutoCreateOff() {
        when(databaseMapper.selectByUk(eq(1L), eq("LIS"), anyString())).thenReturn(null);
        stubParse(qualifiedSchema("LIS", "SAMPLE", col("id", "BIGINT", false)));

        ImportRequest req = request(1L, 10L);
        req.setAutoCreateDatabase(false);
        ImportResult result = service.importSql(req, 9L);

        assertEquals(1, result.getAddedCount());
        verify(assetService, never()).createDatabase(any(), anyLong());
        verify(tableDocMapper).insert(argThat(row -> row.getDatabaseId() == 10L && "HIS".equals(row.getDbUser())));
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("已按开关归入")), result.getWarnings().toString());
    }

    // ===== 结构化来源（Excel 数据字典） =====

    @Test
    @DisplayName("Excel 整理出的表清单：不解析脚本，走同一套增量合并并留来源")
    void testStructuredTablesSkipParsing() {
        ImportRequest req = dictRequest(1L, 10L, List.of(
                schema("USER_INFO", "用户表", col("id", "BIGINT", false)),
                schema("DEPT_INFO", "科室表", col("id", "BIGINT", false))
        ));
        ImportResult result = service.importSql(req, 9L);

        assertEquals(2, result.getAddedCount(), "两张表都该按新增处理");
        assertEquals(2, result.getParsedCount());
        // 关键承诺：有表清单就不该再去碰 SQL 解析器
        verifyNoInteractions(sqlParseService);

        DbDocEntity batch = captureBatch();
        assertEquals("EXCEL", batch.getSourceType());
        assertEquals("检验表结构.xlsx（2 张表 / 2 个字段）", batch.getSourceRef());
        assertNull(batch.getSourceSql(), "Excel 来源没有原始 SQL，不该造假进去");
        assertEquals(2, batch.getTableCount());
    }

    @Test
    @DisplayName("结构化导入也能比对目录：已有且没变的表只确认存在")
    void testStructuredPreviewAgainstCatalog() {
        defaults();
        // 目录里这行要和 Excel 那份完全一致（含表注释），否则差异判定会判成「表注释变更」
        DbTableDocEntity existing = row(100L, "USER_INFO", "BIGINT", false, null);
        existing.setTableComment("用户表");
        when(tableDocMapper.selectFullByDatabaseAndName(10L, "USER_INFO")).thenReturn(existing);
        List<TableSchema> tables = List.of(
                schema("USER_INFO", "用户表", col("id", "BIGINT", false)),
                schema("NEW_TABLE", "新表", col("id", "BIGINT", false))
        );

        var preview = service.preview(null, 10L, tables);
        assertEquals(1, preview.getAddedCount(), "只有 NEW_TABLE 是新增");
        assertEquals(0, preview.getChangedCount());
        assertEquals(1, preview.getUnchangedCount(), "USER_INFO 结构一致，算未变化");
        assertEquals("HIS", preview.getAgainstDbUser());
    }

    @Test
    @DisplayName("表清单里表名重复：直接报错，不静默合并成一张")
    void testStructuredRejectsDuplicateTable() {
        ImportRequest req = dictRequest(1L, 10L, List.of(
                schema("USER_INFO", "用户表", col("id", "BIGINT", false)),
                schema("user_info", "又一遍", col("id", "BIGINT", false))
        ));
        BizException e = assertThrows(BizException.class, () -> service.importSql(req, 9L));
        assertTrue(e.getMsg().contains("表名重复"), e.getMsg());
    }

    @Test
    @DisplayName("字段名缺失：报错报到具体是哪张表第几个字段")
    void testStructuredRejectsBlankColumn() {
        ImportRequest req = dictRequest(1L, 10L, List.of(
                schema("USER_INFO", "用户表", col("id", "BIGINT", false), col("  ", "VARCHAR2", true))
        ));
        BizException e = assertThrows(BizException.class, () -> service.importSql(req, 9L));
        assertEquals("表 USER_INFO 第 2 个字段缺少字段名", e.getMsg());
    }

    @Test
    @DisplayName("超过单次表数上限：拒绝导入并说明怎么拆")
    void testStructuredRejectsTooManyTables() {
        List<TableSchema> tables = new ArrayList<>();
        for (int i = 0; i <= DbTableImportService.MAX_TABLES_PER_IMPORT; i++) {
            tables.add(schema("T_" + i, "", col("id", "BIGINT", false)));
        }
        BizException e = assertThrows(BizException.class, () -> service.importSql(dictRequest(1L, 10L, tables), 9L));
        assertTrue(e.getMsg().contains("一次最多导入"), e.getMsg());
    }

    @Test
    @DisplayName("来源标记只认 DDL / EXCEL / SCAN")
    void testUnknownSourceTypeRejected() {
        ImportRequest req = dictRequest(1L, 10L, List.of(schema("USER_INFO", "用户表", col("id", "BIGINT", false))));
        req.setSourceType("ODPS");
        BizException e = assertThrows(BizException.class, () -> service.importSql(req, 9L));
        assertEquals("来源类型只支持 DDL / EXCEL / SCAN", e.getMsg());
    }

    @Test
    @DisplayName("贴来源不明的空请求：两种入参都没有时报人话")
    void testNeitherSqlNorTables() {
        defaults();
        ImportRequest req = new ImportRequest();
        req.setSystemId(1L);
        req.setDatabaseId(10L);
        BizException e = assertThrows(BizException.class, () -> service.importSql(req, 9L));
        assertTrue(e.getMsg().contains("请贴建表脚本"), e.getMsg());
    }

    // ===== 人工修订目录里的结构 =====

    @Test
    @DisplayName("人工改结构：覆盖当前快照，留一条 MANUAL 版本且不挂批次")
    void testManualEditWritesManualVersionWithoutBatch() {
        defaults();
        DbTableDocEntity existing = row(50L, "USER_INFO", "BIGINT", false, "[]");
        when(assetService.requireTable(50L)).thenReturn(existing);
        when(tableDocMapper.selectById(50L)).thenReturn(existing);

        TableSchema form = schema("USER_INFO", "用户表", col("id", "BIGINT", false), col("remark", "VARCHAR", "200", true));
        ManualEditView view = service.updateStructureManually(50L, form, 9L);

        assertTrue(view.isChanged());
        assertTrue(view.getDiffSummary().contains("新增字段"), view.getDiffSummary());
        verify(tableDocMapper).updateManualStructure(any(DbTableDocEntity.class));
        // 走人工修订专用语句，绝不碰导入用的那条（那条会把 parse_count +1、last_batch_id 覆盖掉）
        verify(tableDocMapper, never()).updateStructure(any());

        DbTableVersionEntity version = captureVersion();
        assertEquals("MANUAL", version.getChangeType());
        assertNull(version.getBatchId(), "人工修订不该把版本挂在某支分析批次上");
        assertEquals(2, version.getColumnCount());
    }

    @Test
    @DisplayName("人工改结构但一个字没动：不写库也不写版本")
    void testManualEditWithIdenticalStructureSavesNothing() throws Exception {
        defaults();
        DbTableDocEntity existing = row(50L, "USER_INFO", "BIGINT", false, "[]");
        // 注释得与表单一致，否则「表注释变更」会把它判成有变化
        existing.setTableComment("用户表");
        when(assetService.requireTable(50L)).thenReturn(existing);

        TableSchema form = schema("USER_INFO", "用户表", col("id", "BIGINT", false));
        ManualEditView view = service.updateStructureManually(50L, form, 9L);

        assertFalse(view.isChanged());
        verify(tableDocMapper, never()).updateManualStructure(any());
        verify(versionMapper, never()).insert(any(DbTableVersionEntity.class));
    }

    @Test
    @DisplayName("人工更名：跟着改表名，并在摘要里写明原名")
    void testManualRenameUpdatesTableName() {
        defaults();
        DbTableDocEntity existing = row(50L, "USER_INFO", "BIGINT", false, "[]");
        when(assetService.requireTable(50L)).thenReturn(existing);
        when(tableDocMapper.selectById(50L)).thenReturn(existing);

        TableSchema form = schema("USER_PROFILE", "", col("id", "BIGINT", false));
        ManualEditView view = service.updateStructureManually(50L, form, 9L);

        assertTrue(view.isChanged(), "只改了表名也算变了，不能静默丢掉");
        ArgumentCaptor<DbTableDocEntity> captor = ArgumentCaptor.forClass(DbTableDocEntity.class);
        verify(tableDocMapper).updateManualStructure(captor.capture());
        assertEquals("USER_PROFILE", captor.getValue().getTableName());
        assertTrue(captor.getValue().getSourceDdl().contains("USER_PROFILE"));
        assertTrue(view.getDiffSummary().startsWith("表名更名:USER_INFO→USER_PROFILE"), view.getDiffSummary());
    }

    @Test
    @DisplayName("只有大小写不同的更名也要保存下来")
    void testManualRenameChangingOnlyCaseStillSaves() {
        defaults();
        DbTableDocEntity existing = row(50L, "user_info", "BIGINT", false, "[]");
        when(assetService.requireTable(50L)).thenReturn(existing);
        when(tableDocMapper.selectById(50L)).thenReturn(existing);

        TableSchema form = schema("USER_INFO", "", col("id", "BIGINT", false));
        ManualEditView view = service.updateStructureManually(50L, form, 9L);

        // 结构一模一样，但表名的大小写是用户特意改的，不能当成「什么都没改」丢掉
        assertTrue(view.isChanged(), view.getDiffSummary());
        verify(tableDocMapper).updateManualStructure(any(DbTableDocEntity.class));
        ArgumentCaptor<DbTableVersionEntity> captor = ArgumentCaptor.forClass(DbTableVersionEntity.class);
        verify(versionMapper).insert(captor.capture());
        assertTrue(captor.getValue().getTableName().equals("USER_INFO"));
    }

    @Test
    @DisplayName("人工更名撞到同库已有的表：拒绝，不能让唯一键 uk_db_table 报错")
    void testManualRenameIntoExistingTableNameIsRejected() {
        defaults();
        DbTableDocEntity existing = row(50L, "USER_INFO", "BIGINT", false, "[]");
        when(assetService.requireTable(50L)).thenReturn(existing);
        when(tableDocMapper.selectFullByDatabaseAndName(10L, "DEPT_INFO")).thenReturn(row(77L, "DEPT_INFO", "BIGINT", false, "[]"));

        BizException e = assertThrows(BizException.class,
                () -> service.updateStructureManually(50L, schema("DEPT_INFO", "", col("id", "BIGINT", false)), 9L));
        assertTrue(e.getMsg().contains("已经登记了表"), e.getMsg());
    }

    @Test
    @DisplayName("索引引用了不存在的字段：直接报人话，别存半截结构")
    void testManualIndexMustReferenceExistingColumn() {
        defaults();
        DbTableDocEntity existing = row(50L, "USER_INFO", "BIGINT", false, "[]");
        when(assetService.requireTable(50L)).thenReturn(existing);

        TableSchema form = schema("USER_INFO", "", col("id", "BIGINT", false));
        form.setIndexes(new ArrayList<>(List.of(index("idx_name", "UNIQUE", "patient_id"))));

        BizException e = assertThrows(BizException.class, () -> service.updateStructureManually(50L, form, 9L));
        assertTrue(e.getMsg().contains("引用了不存在的字段 patient_id"), e.getMsg());
    }

    @Test
    @DisplayName("主键只认一条口径：有主键索引就按它回写字段标记，没有就合成一条")
    void testManualPrimaryKeyReconcilesBothWays() throws Exception {
        defaults();

        // ① 只在字段上勾了主键、没建主键索引 —— 合成一条 PRIMARY
        DbTableDocEntity flaggedRow = row(50L, "USER_INFO", "BIGINT", false, "[]");
        when(assetService.requireTable(50L)).thenReturn(flaggedRow);
        TableSchema flagged = schema("USER_INFO", "", pkCol("id", "BIGINT"), col("name", "VARCHAR", "50", true));
        service.updateStructureManually(50L, flagged, 9L);
        ArgumentCaptor<DbTableDocEntity> synthesized = ArgumentCaptor.forClass(DbTableDocEntity.class);
        verify(tableDocMapper).updateManualStructure(synthesized.capture());
        assertEquals("id", synthesized.getValue().getPkColumns());
        List<IndexInfo> synthesizedIndexes = readIndexes(synthesized.getValue().getIndexesJson());
        assertEquals(1, synthesizedIndexes.size());
        assertEquals("PRIMARY", synthesizedIndexes.get(0).getType());

        // ② 只建了主键索引、字段没勾 —— 以索引为准回写字段标记，索引列也用字段表里的原始大小写
        DbTableDocEntity indexRow = row(51L, "USER_INFO", "BIGINT", false, "[]");
        when(assetService.requireTable(51L)).thenReturn(indexRow);
        TableSchema byIndex = schema("USER_INFO", "", col("ID", "BIGINT", false), col("name", "VARCHAR", "50", true));
        byIndex.setIndexes(new ArrayList<>(List.of(index("pk_id", "PRIMARY", "id"))));
        service.updateStructureManually(51L, byIndex, 9L);
        ArgumentCaptor<DbTableDocEntity> synced = ArgumentCaptor.forClass(DbTableDocEntity.class);
        verify(tableDocMapper, times(2)).updateManualStructure(synced.capture());
        DbTableDocEntity second = synced.getAllValues().get(1);
        List<ColumnInfo> columns = readColumns(second.getColumnsJson());
        assertTrue(columns.get(0).isPrimaryKey(), "字段主键标记要跟着主键索引走");
        assertFalse(columns.get(1).isPrimaryKey());
        assertEquals("ID", readIndexes(second.getIndexesJson()).get(0).getColumns().get(0));
    }

    @Test
    @DisplayName("字段漏了类型：人工维护必填，否则这份文档没有参考价值")
    void testManualColumnWithoutTypeIsRejected() {
        defaults();
        DbTableDocEntity existing = row(50L, "USER_INFO", "BIGINT", false, "[]");
        when(assetService.requireTable(50L)).thenReturn(existing);

        ColumnInfo noType = ColumnInfo.builder().name("id").type(" ").nullable(false).build();
        BizException e = assertThrows(BizException.class,
                () -> service.updateStructureManually(50L, schema("USER_INFO", "", noType), 9L));
        assertTrue(e.getMsg().contains("没有填类型"), e.getMsg());
    }

    @Test
    @DisplayName("手工建档：parse_count 从 0 起，版本记 NEW 且不挂批次")
    void testCreateTableManually() {
        defaults();
        TableSchema form = schema("TEMP_LOG", "临时日志", col("id", "BIGINT", false), col("msg", "VARCHAR", "500", true));
        // 建档接口最后要回查一次最新行，把自增 ID 100 的回查桩上，断言才有东西可断
        when(tableDocMapper.selectById(100L)).thenAnswer(invocation -> {
            DbTableDocEntity fresh = new DbTableDocEntity();
            fresh.setId(100L);
            fresh.setTableName("TEMP_LOG");
            return fresh;
        });
        DbTableDocEntity created = service.createTableManually(10L, form, 9L);

        ArgumentCaptor<DbTableDocEntity> captor = ArgumentCaptor.forClass(DbTableDocEntity.class);
        verify(tableDocMapper).insert(captor.capture());
        DbTableDocEntity row = captor.getValue();
        assertEquals("TEMP_LOG", row.getTableName());
        assertEquals(10L, row.getDatabaseId());
        assertEquals(1L, row.getSystemId());
        assertEquals(0, row.getParseCount(), "手工建档不是又分析了一次");
        assertEquals(1, row.getStatus());
        assertEquals(2, row.getColumnCount());
        assertNotNull(created);
        assertEquals("TEMP_LOG", created.getTableName());

        DbTableVersionEntity version = captureVersion();
        assertEquals("NEW", version.getChangeType());
        assertNull(version.getBatchId());
        assertTrue(version.getDiffSummary().contains("手工建档"), version.getDiffSummary());
    }

    @Test
    @DisplayName("手工建档撞已有表名：提示去编辑那张，而不是再建一遍")
    void testCreateTableManuallyRejectsDuplicateName() {
        defaults();
        when(tableDocMapper.selectFullByDatabaseAndName(10L, "USER_INFO")).thenReturn(row(50L, "USER_INFO", "BIGINT", false, "[]"));

        BizException e = assertThrows(BizException.class,
                () -> service.createTableManually(10L, schema("USER_INFO", "", col("id", "BIGINT", false)), 9L));
        assertTrue(e.getMsg().contains("直接编辑那张"), e.getMsg());
        verify(tableDocMapper, never()).insert(any());
    }
    // ===== 桩件与构造辅助 =====

    /**
     * Excel 来源的请求：没有 SQL，只有整理好的表清单。
     * 这是接口被绕过前端直接调用时的真实形状，所以校验都得在这儿兜住。
     */
    private ImportRequest dictRequest(Long systemId, Long databaseId, List<TableSchema> tables) {
        defaults();
        ImportRequest req = new ImportRequest();
        req.setSystemId(systemId);
        req.setDatabaseId(databaseId);
        req.setTitle("Excel 导入");
        req.setTables(new ArrayList<>(tables));
        req.setSourceType(DbTableImportService.SOURCE_EXCEL);
        req.setSourceRef("检验表结构.xlsx（" + tables.size() + " 张表 / " + tables.size() + " 个字段）");
        return req;
    }

    private DbDocEntity captureBatch() {
        ArgumentCaptor<DbDocEntity> captor = ArgumentCaptor.forClass(DbDocEntity.class);
        verify(batchMapper).insert(captor.capture());
        return captor.getValue();
    }

    /** 把数据库用户与系统的默认桩件配好，各测试只补自己关心的部分 */
    private void defaults() {
        DbSystemEntity system = new DbSystemEntity();
        system.setId(1L);
        system.setSystemName("门诊系统");
        system.setDbType("oracle");
        when(assetService.getSystem(1L)).thenReturn(system);
        when(assetService.getDatabase(10L)).thenReturn(database(10L, 1L, "HIS"));
    }

    private DbDatabaseEntity database(Long id, Long systemId, String dbUser) {
        DbDatabaseEntity db = new DbDatabaseEntity();
        db.setId(id);
        db.setSystemId(systemId);
        db.setDbUser(dbUser);
        db.setDbType("oracle");
        db.setEnv("prod");
        db.setStatus(1);
        db.setSystemName("门诊系统");
        return db;
    }

    private ImportRequest request(Long systemId, Long databaseId) {
        defaults();
        ImportRequest req = new ImportRequest();
        req.setSql("stub sql");
        req.setSystemId(systemId);
        req.setDatabaseId(databaseId);
        req.setTitle("一次导入");
        return req;
    }

    private void stubParse(TableSchema single) {
        stubParse(List.of(single));
    }

    private void stubParse(List<TableSchema> tables) {
        Set<String> users = new LinkedHashSet<>();
        for (TableSchema table : tables) {
            if (table.getDbUser() != null) {
                users.add(table.getDbUser());
            }
        }
        when(sqlParseService.parseDdl(anyString())).thenReturn(new ParseResult(new ArrayList<>(tables), new ArrayList<>(), users));
    }

    private DbTableVersionEntity captureVersion() {
        ArgumentCaptor<DbTableVersionEntity> captor = ArgumentCaptor.forClass(DbTableVersionEntity.class);
        verify(versionMapper).insert(captor.capture());
        return captor.getValue();
    }

    private TableSchema schema(String tableName, String comment, ColumnInfo... columns) {
        return buildSchema(null, tableName, comment, List.of(columns));
    }

    /** 带「用户.表名」前缀的解析结果 */
    private TableSchema qualifiedSchema(String dbUser, String tableName, ColumnInfo... columns) {
        return buildSchema(dbUser, tableName, "", List.of(columns));
    }

    private TableSchema buildSchema(String dbUser, String tableName, String comment, List<ColumnInfo> columns) {
        return TableSchema.builder()
                .dbUser(dbUser).tableName(tableName).tableComment(comment)
                .columns(new ArrayList<>(columns)).indexes(new ArrayList<>())
                .build();
    }

    private ColumnInfo col(String name, String type, boolean nullable) {
        return col(name, type, null, nullable);
    }

    private ColumnInfo col(String name, String type, String length, boolean nullable) {
        return ColumnInfo.builder().name(name).type(type).length(length).nullable(nullable).build();
    }
    /** 勾了主键标记的字段 */
    private ColumnInfo pkCol(String name, String type) {
        return ColumnInfo.builder().name(name).type(type).nullable(false).primaryKey(true).build();
    }

    /** 表单里填的一条索引 */
    private IndexInfo index(String name, String type, String... columns) {
        return IndexInfo.builder().name(name).type(type).columns(new ArrayList<>(List.of(columns))).build();
    }

    /** 解回目录行里的字段 JSON，断言时看的是真正落库的那一份 */
    private List<ColumnInfo> readColumns(String json) throws Exception {
        return new ObjectMapper().readValue(json, new TypeReference<List<ColumnInfo>>() {
        });
    }

    private List<IndexInfo> readIndexes(String json) throws Exception {
        return new ObjectMapper().readValue(json, new TypeReference<List<IndexInfo>>() {
        });
    }

    /** 目录里已有的一行：字段 JSON 用同一套 codec 生成，避免测试和被测代码对不上 */
    private DbTableDocEntity row(Long id, String tableName, String type, boolean nullable, String indexesJson) {
        try {
            DbTableDocEntity row = new DbTableDocEntity();
            row.setId(id);
            row.setSystemId(1L);
            row.setDatabaseId(10L);
            row.setTableName(tableName);
            row.setDbUser("HIS");
            row.setColumnCount(1);
            row.setColumnsJson(new ObjectMapper().writeValueAsString(List.of(col("id", type, nullable))));
            row.setIndexesJson(indexesJson == null ? "[]" : indexesJson);
            row.setStatus(1);
            row.setParseCount(1);
            return row;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
