package com.zjl.worklog.dbdoc;

import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.dbdoc.dto.ColumnInfo;
import com.zjl.worklog.dbdoc.dto.DiffPreview;
import com.zjl.worklog.dbdoc.dto.IndexInfo;
import com.zjl.worklog.dbdoc.dto.ImportRequest;
import com.zjl.worklog.dbdoc.dto.ImportResult;
import com.zjl.worklog.dbdoc.dto.ManualEditView;
import com.zjl.worklog.dbdoc.dto.ParseResult;
import com.zjl.worklog.dbdoc.dto.TableChangeItem;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 表结构导入服务：把一份 DDL 脚本「增量合并」进三级目录。
 *
 * 这是本次改造的核心。v1 的行为是「一次分析存一整份快照」，重复分析只会越存越多；
 * 现在按 (数据库用户, 表名) 定位唯一一行：
 *   - 目录里没有        → 新增，并留一份 NEW 版本；
 *   - 结构确实变了      → 覆盖当前快照，并留一份 CHANGED 版本 + 差异摘要；
 *   - 结构没变          → 只把「被分析次数 +1」，不产生新版本，避免历史被无效快照淹没；
 *   - 脚本里没出现的表  → 记 MISSING（MERGE 只留痕；REPLACE 额外标成疑似下线，绝不物理删除）。
 *
 * 脚本里写了 USER.TABLE 限定名时，会按前缀自动归到对应库用户下（找不到就在同一系统下建），
 * 这样一个 Oracle 全库导出脚本也能一次导完，而不是全塞进一个用户里。
 */
@Service
public class DbTableImportService {

    /** 只做增量合并，不动脚本里没出现的表 */
    public static final String MODE_MERGE = "MERGE";
    /** 整库重新对账：脚本里没有的表标记为疑似下线 */
    public static final String MODE_REPLACE = "REPLACE";

    static final String CT_NEW = "NEW";
    static final String CT_CHANGED = "CHANGED";
    static final String CT_UNCHANGED = "UNCHANGED";
    static final String CT_MISSING = "MISSING";
    /** 人工在页面上修订过结构：和 CHANGED 分开记，历史里一眼看得出「这是人改的，不是脚本导出来的」 */
    static final String CT_MANUAL = "MANUAL";

    /** 缺失表名最多回传这么多条，再多前端也展示不下 */
    private static final int MISSING_NAME_LIMIT = 50;

    private final SqlParseService sqlParseService;
    private final DbAssetService assetService;
    private final DbDatabaseMapper databaseMapper;
    private final DbTableDocMapper tableDocMapper;
    private final DbTableVersionMapper versionMapper;
    private final DbDocMapper batchMapper;
    private final TableSchemaDiffer differ;
    private final TableSchemaCodec codec;

    public DbTableImportService(SqlParseService sqlParseService,
                                DbAssetService assetService,
                                DbDatabaseMapper databaseMapper,
                                DbTableDocMapper tableDocMapper,
                                DbTableVersionMapper versionMapper,
                                DbDocMapper batchMapper,
                                TableSchemaDiffer differ,
                                TableSchemaCodec codec) {
        this.sqlParseService = sqlParseService;
        this.assetService = assetService;
        this.databaseMapper = databaseMapper;
        this.tableDocMapper = tableDocMapper;
        this.versionMapper = versionMapper;
        this.batchMapper = batchMapper;
        this.differ = differ;
        this.codec = codec;
    }

    // ============================== 预览 ==============================

    /**
     * 导入前的差异预览（不落库）。
     * 没选库用户时对照「空目录」，所有表都会显示成新增，这时预览的用处主要是看解析结果。
     */
    public DiffPreview preview(String sql, Long databaseId) {
        return preview(sql, databaseId, null);
    }

    /**
     * 差异预览的结构化入口。
     * Excel 数据字典这类来源不是脚本，前端已经整理成 TableSchema，这里跳过解析直接和目录比。
     */
    public DiffPreview preview(String sql, Long databaseId, List<TableSchema> structured) {
        ParseResult parsed = pickTables(sql, structured);
        DiffPreview view = new DiffPreview();
        view.setTables(parsed.getTables());
        view.setWarnings(parsed.getWarnings());
        view.setScriptDbUsers(parsed.getDbUsers() == null ? new ArrayList<>() : new ArrayList<>(parsed.getDbUsers()));

        DbDatabaseEntity primary = databaseId == null ? null : assetService.getDatabase(databaseId);
        view.setAgainstDbUser(primary == null ? null : primary.getDbUser());

        int added = 0;
        int changed = 0;
        int unchanged = 0;
        for (TableSchema incoming : parsed.getTables()) {
            DbDatabaseEntity target = primary == null ? null : locateByPrefix(primary, incoming.getDbUser());
            DbTableDocEntity existing = target == null
                    ? null
                    : tableDocMapper.selectFullByDatabaseAndName(target.getId(), incoming.getTableName());
            TableSchema current = codec.toSchema(existing);
            TableSchemaDiffer.Difference diff = differ.diff(current, incoming);
            String type = existing == null ? CT_NEW : (diff.changed() ? CT_CHANGED : CT_UNCHANGED);
            if (existing == null) {
                added++;
            } else if (diff.changed()) {
                changed++;
            } else {
                unchanged++;
            }
            view.getChanges().add(TableChangeItem.builder()
                    .tableName(incoming.getTableName())
                    .dbUser(target == null ? incoming.getDbUser() : target.getDbUser())
                    .changeType(type)
                    .diffSummary(diff.summary())
                    .oldColumnCount(existing == null ? 0 : existing.getColumnCount())
                    .newColumnCount(incoming.getColumns() == null ? 0 : incoming.getColumns().size())
                    .tableComment(incoming.getTableComment())
                    .tableDocId(existing == null ? null : existing.getId())
                    .build());
        }
        view.setAddedCount(added);
        view.setChangedCount(changed);
        view.setUnchangedCount(unchanged);
        return view;
    }

    // ============================== 导入 ==============================

    /** 解析 + 增量合并 + 批次留痕，一个事务搞定，中途失败不留半截目录 */
    @Transactional
    public ImportResult importSql(ImportRequest req, Long operatorId) {
        if (req == null) {
            throw new BizException(40001, "导入请求不能为空");
        }
        ParseResult parsed = pickTables(req.getSql(), req.getTables());
        if (parsed.getTables().isEmpty()) {
            throw new BizException(40001, "没有可导入的表结构：贴脚本要有 CREATE TABLE，Excel 要至少有一行字段");
        }

        ImportResult result = new ImportResult();
        result.setWarnings(new ArrayList<>(parsed.getWarnings()));
        String mode = MODE_REPLACE.equalsIgnoreCase(req.getMode()) ? MODE_REPLACE : MODE_MERGE;
        result.setMode(mode);
        boolean autoCreate = req.getAutoCreateDatabase() == null || req.getAutoCreateDatabase();

        DbSystemEntity system = resolveSystem(req, operatorId);
        DbDatabaseEntity primary = resolvePrimaryDatabase(req, system, operatorId, result.getWarnings());
        result.setSystemId(system.getId());
        result.setSystemName(system.getSystemName());
        result.setDatabaseId(primary.getId());
        result.setDbUser(primary.getDbUser());
        result.setParsedCount(parsed.getTables().size());

        // 批次先落库拿 ID，后面的表记录和版本都要引用它
        DbDocEntity batch = new DbDocEntity();
        batch.setSystemId(system.getId());
        batch.setDatabaseId(primary.getId());
        batch.setDbUser(primary.getDbUser());
        batch.setTitle(resolveTitle(req, system, primary));
        batch.setSourceType(resolveSourceType(req));
        batch.setSourceRef(DbAssetService.trimToNull(req.getSourceRef()));
        batch.setSourceSql(req.getSql());
        batch.setParsedJson(codec.writeJson(parsed));
        batch.setTableCount(parsed.getTables().size());
        batch.setAddedCount(0);
        batch.setUpdatedCount(0);
        batch.setUnchangedCount(0);
        batch.setMissingCount(0);
        batch.setCreatedBy(operatorId);
        batchMapper.insert(batch);
        result.setBatchId(batch.getId());

        // 逐表合并；按库用户分组记住「本次出现过哪些表」，最后据此算缺失
        Map<Long, Set<String>> keepByDatabase = new LinkedHashMap<>();
        for (TableSchema incoming : parsed.getTables()) {
            DbDatabaseEntity target = routeDatabase(incoming, primary, req, operatorId, autoCreate, result.getWarnings());
            TableChangeItem item = mergeOneTable(incoming, target, batch.getId(), operatorId);
            result.getChanges().add(item);
            keepByDatabase.computeIfAbsent(target.getId(), k -> new LinkedHashSet<>())
                    .add(incoming.getTableName().toUpperCase(Locale.ROOT));
            switch (item.getChangeType()) {
                case CT_NEW -> result.setAddedCount(result.getAddedCount() + 1);
                case CT_CHANGED -> result.setUpdatedCount(result.getUpdatedCount() + 1);
                default -> result.setUnchangedCount(result.getUnchangedCount() + 1);
            }
        }

        // 缺失登记：目录里有、这次脚本里没有的表
        collectMissing(keepByDatabase, mode, batch.getId(), operatorId, result);

        batchMapper.updateStats(batch.getId(), parsed.getTables().size(),
                result.getAddedCount(), result.getUpdatedCount(),
                result.getUnchangedCount(), result.getMissingCount());
        for (Long databaseId : keepByDatabase.keySet()) {
            databaseMapper.refreshTableCount(databaseId);
        }
        return result;
    }

    /**
     * 把 v1 遗留的、没归类的历史快照重新导入目录。
     * 走的是同一套合并逻辑，只是 SQL 从 db_doc.source_sql 里取。
     */
    @Transactional
    public ImportResult rearchive(Long batchId, ImportRequest req, Long operatorId) {
        DbDocEntity legacy = batchMapper.selectById(batchId);
        if (legacy == null) {
            throw new BizException(404, "历史分析记录不存在");
        }
        if (legacy.getSourceSql() == null || legacy.getSourceSql().isBlank()) {
            throw new BizException(40001, "该历史记录没有保存原始 SQL，无法重新归类");
        }
        req.setSql(legacy.getSourceSql());
        // 重新归类只认这条批次当初存的原文，顺带传进来的表清单一律忽略，免得来源说不清
        req.setTables(null);
        req.setSourceType(SOURCE_DDL);
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            req.setTitle(legacy.getTitle());
        }
        return importSql(req, operatorId);
    }

    /** 某批次涉及的表变更清单，批次详情页用 */
    public List<TableChangeItem> batchChanges(Long batchId) {
        List<TableChangeItem> items = new ArrayList<>();
        for (DbTableVersionEntity version : versionMapper.selectLiteByBatchId(batchId)) {
            items.add(TableChangeItem.builder()
                    .tableName(version.getTableName())
                    .changeType(version.getChangeType())
                    .diffSummary(version.getDiffSummary())
                    .newColumnCount(version.getColumnCount())
                    .tableDocId(version.getTableDocId())
                    .build());
        }
        return items;
    }

    // ============================== 人工修订目录里的结构 ==============================

    /**
     * 手工改一张已登记的表：表名、表注释、字段、索引都能在页面上直接改，不必再回去贴一遍脚本。
     *
     * 口径和导入完全一致 —— 先与当前快照做差异比对，真变了才覆盖并留一条历史版本；
     * 区别只有两点：版本记的是 MANUAL，以及 batch_id 留空（这一次没有人提交脚本）。
     * 下次再导同一张表时，比对基准就是这份手工改完的结构，改了什么在历史里查得到。
     */
    @Transactional
    public ManualEditView updateStructureManually(Long tableDocId, TableSchema form, Long operatorId) {
        DbTableDocEntity row = assetService.requireTable(tableDocId);
        DbDatabaseEntity db = assetService.getDatabase(row.getDatabaseId());
        TableSchema incoming = normalizeManual(form, db);

        String oldName = row.getTableName();
        // 只在大小写不同的更名也要落库（把 user_info 修成 USER_INFO 正是手工修订要干的活）；
        // 大小写不敏感的排序规则下撞到自己不算撞人，下面按 ID 排除
        boolean renamed = !oldName.equals(incoming.getTableName());
        if (renamed) {
            DbTableDocEntity occupied = tableDocMapper.selectFullByDatabaseAndName(row.getDatabaseId(), incoming.getTableName());
            if (occupied != null && !occupied.getId().equals(row.getId())) {
                throw new BizException(40001,
                        "「" + db.getDbUser() + "」下已经登记了表 " + incoming.getTableName() + "，换个不重名的再保存");
            }
        }

        TableSchemaDiffer.Difference diff = differ.diff(codec.toSchema(row), incoming);
        if (!renamed && !diff.changed()) {
            // 一个字都没改：不写库也不写版本，免得历史里多出几条一模一样的快照
            return ManualEditView.builder().changed(false).diffSummary("").table(row).build();
        }

        String summary = summarizeManual(diff, oldName, incoming.getTableName(), renamed);
        fillManualStructure(row, incoming, db, operatorId);
        tableDocMapper.updateManualStructure(row);
        writeVersion(row.getId(), db, incoming, null, CT_MANUAL, summary, operatorId);
        databaseMapper.refreshTableCount(row.getDatabaseId());
        return ManualEditView.builder()
                .changed(true)
                .diffSummary(summary)
                .table(tableDocMapper.selectById(tableDocId))
                .build();
    }

    /**
     * 手工建档一张表：现场只有三两张零散表、没有整份建表脚本时，直接在目录里补一行。
     * 校验与「改表」共用同一套 normalizeManual；版本记 NEW、批次留空，来源写清是没经过脚本的。
     */
    @Transactional
    public DbTableDocEntity createTableManually(Long databaseId, TableSchema form, Long operatorId) {
        if (databaseId == null) {
            throw new BizException(40001, "请先选择这张表挂在哪个数据库用户下");
        }
        DbDatabaseEntity db = assetService.getDatabase(databaseId);
        TableSchema incoming = normalizeManual(form, db);
        if (tableDocMapper.selectFullByDatabaseAndName(db.getId(), incoming.getTableName()) != null) {
            throw new BizException(40001, "「" + db.getDbUser() + "」下已有表 " + incoming.getTableName()
                    + "，直接编辑那张就行，不用再建一遍");
        }
        DbTableDocEntity row = new DbTableDocEntity();
        row.setSystemId(db.getSystemId());
        row.setDatabaseId(db.getId());
        row.setCreatedBy(operatorId);
        // 手工建档没被脚本分析过，parse_count 从 0 起算；首次进目录默认就是在用
        row.setParseCount(0);
        row.setStatus(1);
        fillManualStructure(row, incoming, db, operatorId);
        tableDocMapper.insert(row);
        writeVersion(row.getId(), db, incoming, null, CT_NEW, "手工建档（未经脚本分析）", operatorId);
        databaseMapper.refreshTableCount(db.getId());
        return tableDocMapper.selectById(row.getId());
    }

    /**
     * 人工提交结构的校验与归一。
     * 表名合法性、字段名非空且不重复、至少一个字段这些底线直接复用导入那套 normalizeStructured，
     * 另外补三条只有手改才会遇到的规则：
     *   1) 字段类型必填 —— 脚本解析不出类型还能留空，人手漏了类型，这份文档就没参考价值了；
     *   2) 索引引用的字段必须存在 —— 改了字段名忘了同步索引，是手工维护最常见的翻车点；
     *   3) 主键只认一个口径 —— 有「主键」索引就按它的列回写字段的 PK 标记，没有就按勾了 PK 的字段合成一条，
     *      否则列表里的「主键」列和字段上的 PK 标记会各说各话。
     */
    private TableSchema normalizeManual(TableSchema form, DbDatabaseEntity db) {
        if (form == null) {
            throw new BizException(40001, "没有收到表结构");
        }
        if (DbAssetService.trimToNull(form.getTableName()) == null) {
            throw new BizException(40001, "表名不能为空");
        }
        TableSchema table = normalizeStructured(List.of(form)).get(0);
        // 手工编辑不动归属：表名后面那段前缀永远是它现在挂的这个库用户，还原 DDL 才对得上
        table.setDbUser(db.getDbUser());
        table.setTableComment(DbAssetService.trimToNull(form.getTableComment()));

        for (ColumnInfo column : table.getColumns()) {
            String type = DbAssetService.trimToNull(column.getType());
            if (type == null) {
                throw new BizException(40001, "字段 " + column.getName() + " 没有填类型");
            }
            column.setType(type);
            column.setLength(DbAssetService.trimToNull(column.getLength()));
            column.setDefaultValue(DbAssetService.trimToNull(column.getDefaultValue()));
            column.setComment(DbAssetService.trimToNull(column.getComment()));
        }

        // 字段名（小写）→ 页面上填的原始大小写：索引列一律回写成它，避免 ID 和 id 两套写法混在一份结构里
        Map<String, String> columnNames = new LinkedHashMap<>();
        for (ColumnInfo column : table.getColumns()) {
            columnNames.put(column.getName().toLowerCase(Locale.ROOT), column.getName());
        }

        List<IndexInfo> indexes = new ArrayList<>();
        List<String> pkFromIndex = null;
        Set<String> usedIndexNames = new LinkedHashSet<>();
        if (form.getIndexes() != null) {
            for (IndexInfo raw : form.getIndexes()) {
                if (raw == null) {
                    continue;
                }
                String type = normalizeIndexType(raw.getType());
                List<String> columns = resolveIndexColumns(raw, columnNames, indexes.size());
                if (columns.isEmpty()) {
                    // 手改时常常先加一行占位，留着只会让「索引数」虚高，直接丢掉
                    continue;
                }
                if ("PRIMARY".equals(type)) {
                    if (pkFromIndex != null) {
                        throw new BizException(40001, "只能有一个主键，请删掉多余的那条主键索引");
                    }
                    pkFromIndex = columns;
                    indexes.add(IndexInfo.builder().name("PRIMARY").type("PRIMARY").columns(columns).build());
                    continue;
                }
                String name = DbAssetService.trimToNull(raw.getName());
                if (name == null) {
                    throw new BizException(40001, "包含字段 " + String.join("、", columns) + " 的索引没有填索引名");
                }
                if (!usedIndexNames.add(name.toLowerCase(Locale.ROOT))) {
                    throw new BizException(40001, "索引名重复：" + name);
                }
                indexes.add(IndexInfo.builder().name(name).type(type).columns(columns).build());
            }
        }

        List<String> pkFlagged = new ArrayList<>();
        for (ColumnInfo column : table.getColumns()) {
            if (column.isPrimaryKey()) {
                pkFlagged.add(column.getName());
            }
        }
        List<String> pk = pkFromIndex != null ? pkFromIndex : pkFlagged;
        if (pk.isEmpty()) {
            indexes.removeIf(idx -> "PRIMARY".equalsIgnoreCase(idx.getType()));
        } else if (pkFromIndex == null) {
            // 只勾了字段的主键标记、没建主键索引：合成一条，和 Excel 数据字典导入的做法保持一致
            indexes.add(0, IndexInfo.builder().name("PRIMARY").type("PRIMARY").columns(new ArrayList<>(pk)).build());
        }
        Set<String> pkKeys = new LinkedHashSet<>();
        for (String name : pk) {
            pkKeys.add(name.toLowerCase(Locale.ROOT));
        }
        for (ColumnInfo column : table.getColumns()) {
            column.setPrimaryKey(pkKeys.contains(column.getName().toLowerCase(Locale.ROOT)));
        }

        table.setIndexes(indexes);
        return table;
    }

    /** 索引类型归一：中英文都认，认不出来就报错，别悄悄存成一种谁也看不懂的写法 */
    private String normalizeIndexType(String raw) {
        String value = DbAssetService.trimToNull(raw);
        if (value == null) {
            return "INDEX";
        }
        switch (value.toUpperCase(Locale.ROOT)) {
            case "PRIMARY":
            case "主键":
                return "PRIMARY";
            case "UNIQUE":
            case "UNIQUE INDEX":
            case "唯一":
            case "唯一索引":
                return "UNIQUE";
            case "FULLTEXT":
            case "全文":
            case "全文索引":
                return "FULLTEXT";
            case "SPATIAL":
            case "空间":
            case "空间索引":
                return "SPATIAL";
            case "INDEX":
            case "NORMAL":
            case "KEY":
            case "普通":
            case "普通索引":
                return "INDEX";
            default:
                throw new BizException(40001, "索引类型不认：" + raw + "（可用：主键 / 唯一 / 普通 / 全文 / 空间）");
        }
    }

    /** 索引列归一：去空、去重、按字段表回写成原始大小写；引用了不存在的字段就说人话报错 */
    private List<String> resolveIndexColumns(IndexInfo raw, Map<String, String> columnNames, int ordinal) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> source = raw.getColumns() == null ? List.of() : raw.getColumns();
        for (String item : source) {
            String column = DbAssetService.trimToNull(item);
            if (column == null) {
                continue;
            }
            String key = column.toLowerCase(Locale.ROOT);
            String actual = columnNames.get(key);
            if (actual == null) {
                String label = DbAssetService.trimToNull(raw.getName()) == null
                        ? "第 " + (ordinal + 1) + " 条索引"
                        : "索引 " + raw.getName();
                throw new BizException(40001, label + "引用了不存在的字段 " + column + "，字段改名或删除后要记得同步索引");
            }
            if (seen.add(key)) {
                out.add(actual);
            }
        }
        return out;
    }

    /** 人工修订的差异摘要：比导入多一段「更名」，其余照抄比对器的中文结论 */
    private String summarizeManual(TableSchemaDiffer.Difference diff, String oldName, String newName, boolean renamed) {
        List<String> parts = new ArrayList<>();
        if (renamed) {
            parts.add("表名更名:" + oldName + "→" + newName);
        }
        if (diff.changed()) {
            parts.add(diff.summary());
        }
        String summary = String.join("; ", parts);
        if (summary.isEmpty()) {
            return "人工修订";
        }
        // db_table_version.diff_summary 只有 1000 个字符，撑爆了就该截断而不是让保存失败
        return summary.length() > 1000 ? summary.substring(0, 980) + "...（差异较多，明细见两版结构对比）" : summary;
    }

    /**
     * 把人工确认的结构写进目录行：JSON、字段数、主键串、还原 DDL 的口径与导入完全共用，
     * 唯一区别是不碰批次归属（last_batch_id 为空时也不能被覆盖成空，所以走 updateManualStructure）。
     */
    private void fillManualStructure(DbTableDocEntity row, TableSchema incoming, DbDatabaseEntity db, Long operatorId) {
        row.setTableName(incoming.getTableName());
        row.setTableComment(incoming.getTableComment());
        row.setDbUser(db.getDbUser());
        row.setDbType(db.getDbType());
        row.setColumnCount(columnCount(incoming));
        row.setIndexCount(incoming.getIndexes() == null ? 0 : incoming.getIndexes().size());
        row.setPkColumns(codec.pkColumns(incoming.getIndexes()));
        row.setColumnsJson(codec.writeJson(incoming.getColumns()));
        row.setIndexesJson(codec.writeJson(incoming.getIndexes()));
        row.setSourceDdl(DdlRenderer.render(incoming));
        row.setUpdatedBy(operatorId);
    }
    // ============================== 内部：合并 ==============================

    // 结构化来源（Excel 数据字典等）：表清单和脚本在这里汇成一条路，后面共用同一套增量合并。

    /** 一批最多 500 张表：再多多半是 Excel 选错了 sheet，先让人确认比闷头写库好 */
    public static final int MAX_TABLES_PER_IMPORT = 500;
    /** 来源：贴建表脚本，v1/v2 一直走的那条路 */
    public static final String SOURCE_DDL = "DDL";
    /** 来源：Excel 数据字典整理出来的表结构 */
    public static final String SOURCE_EXCEL = "EXCEL";
    /** 来源：直连数据库采集，预留给后续扩展 */
    public static final String SOURCE_SCAN = "SCAN";

    /** 表清单和 SQL 脚本二选一：有表清单就直接用，没有才去解析脚本 */
    private ParseResult pickTables(String sql, List<TableSchema> structured) {
        if (structured != null && !structured.isEmpty()) {
            List<TableSchema> tables = normalizeStructured(structured);
            Set<String> users = new LinkedHashSet<>();
            for (TableSchema table : tables) {
                if (table.getDbUser() != null) {
                    users.add(table.getDbUser());
                }
            }
            return new ParseResult(tables, new ArrayList<>(), users);
        }
        if (sql == null || sql.isBlank()) {
            throw new BizException(40001, "请贴建表脚本，或从 Excel 数据字典整理出表结构");
        }
        return sqlParseService.parseDdl(sql);
    }

    /**
     * 结构化表清单的底线校验。
     * 前端解析 Excel 时已经逐行校验过，这里只挡绕过前端的调用：接口是公开的，不能假定请求体干净。
     */
    private List<TableSchema> normalizeStructured(List<TableSchema> raw) {
        if (raw.size() > MAX_TABLES_PER_IMPORT) {
            throw new BizException(40001, "一次最多导入 " + MAX_TABLES_PER_IMPORT + " 张表，当前 " + raw.size()
                    + " 张，请按库或模块拆分后再导");
        }
        List<TableSchema> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            TableSchema table = raw.get(i);
            String seq = "第 " + (i + 1) + " 张表";
            if (table == null) {
                throw new BizException(40001, seq + "是空的");
            }
            String tableName = DbAssetService.trimToNull(table.getTableName());
            if (tableName == null) {
                throw new BizException(40001, seq + "缺少表名");
            }
            if (!tableName.matches("[A-Za-z_][A-Za-z0-9_$#]*")) {
                throw new BizException(40001, "表名 " + tableName
                        + " 不合法：只允许字母、数字、下划线等标识符字符，且不能以数字开头");
            }
            if (!seen.add(tableName.toUpperCase(Locale.ROOT))) {
                throw new BizException(40001, "表名重复：" + tableName + "，请确认 Excel 里同一张表没被拆成两段");
            }
            if (table.getColumns() == null || table.getColumns().isEmpty()) {
                throw new BizException(40001, "表 " + tableName + " 一个字段都没有");
            }
            List<ColumnInfo> columns = new ArrayList<>();
            Set<String> colSeen = new LinkedHashSet<>();
            for (int c = 0; c < table.getColumns().size(); c++) {
                ColumnInfo column = table.getColumns().get(c);
                String pos = "表 " + tableName + " 第 " + (c + 1) + " 个字段";
                if (column == null) {
                    throw new BizException(40001, pos + "是空的");
                }
                String columnName = DbAssetService.trimToNull(column.getName());
                if (columnName == null) {
                    throw new BizException(40001, pos + "缺少字段名");
                }
                if (!colSeen.add(columnName.toUpperCase(Locale.ROOT))) {
                    throw new BizException(40001, pos + "「" + columnName + "」重复");
                }
                column.setName(columnName);
                columns.add(column);
            }
            table.setTableName(tableName);
            table.setDbUser(DbAssetService.trimToNull(table.getDbUser()));
            table.setColumns(columns);
            out.add(table);
        }
        return out;
    }

    /** 来源标记：调用方给了就用（限三种），没给就按入参推断 */
    private String resolveSourceType(ImportRequest req) {
        String given = DbAssetService.trimToNull(req.getSourceType());
        if (given == null) {
            return req.getTables() == null || req.getTables().isEmpty() ? SOURCE_DDL : SOURCE_EXCEL;
        }
        String upper = given.toUpperCase(Locale.ROOT);
        if (!Set.of(SOURCE_DDL, SOURCE_EXCEL, SOURCE_SCAN).contains(upper)) {
            throw new BizException(40001, "来源类型只支持 DDL / EXCEL / SCAN");
        }
        return upper;
    }


    /** 单张表的新增/覆盖/仅确认 */
    private TableChangeItem mergeOneTable(TableSchema incoming, DbDatabaseEntity db, Long batchId, Long operatorId) {
        DbTableDocEntity existing = tableDocMapper.selectFullByDatabaseAndName(db.getId(), incoming.getTableName());
        TableSchema current = codec.toSchema(existing);
        TableSchemaDiffer.Difference diff = differ.diff(current, incoming);

        if (existing == null) {
            DbTableDocEntity row = newRow(incoming, db, batchId, operatorId);
            tableDocMapper.insert(row);
            writeVersion(row.getId(), db, incoming, batchId, CT_NEW, diff.summary(), operatorId);
            return item(incoming, db, CT_NEW, diff.summary(), 0, columnCount(incoming), row.getId());
        }
        if (!diff.changed()) {
            tableDocMapper.touch(existing.getId(), operatorId);
            return item(incoming, db, CT_UNCHANGED, "", existing.getColumnCount(), columnCount(incoming), existing.getId());
        }
        // 先记下旧字段数，fillStructure 会把 row 覆盖成新结构
        Integer oldColumnCount = existing.getColumnCount();
        fillStructure(existing, incoming, db, batchId, operatorId);
        tableDocMapper.updateStructure(existing);
        writeVersion(existing.getId(), db, incoming, batchId, CT_CHANGED, diff.summary(), operatorId);
        return item(incoming, db, CT_CHANGED, diff.summary(), oldColumnCount, columnCount(incoming), existing.getId());
    }

    /** 算出「本次脚本没出现的既有表」，按需标记疑似下线 */
    private void collectMissing(Map<Long, Set<String>> keepByDatabase, String mode,
                                Long batchId, Long operatorId, ImportResult result) {
        List<Long> offlineIds = new ArrayList<>();
        for (Map.Entry<Long, Set<String>> entry : keepByDatabase.entrySet()) {
            Set<String> keep = entry.getValue();
            for (DbTableDocEntity row : tableDocMapper.selectIdNameByDatabase(entry.getKey())) {
                if (keep.contains(row.getTableName().toUpperCase(Locale.ROOT))) {
                    continue;
                }
                result.getMissingTables().add(row.getTableName());
                writeMissingVersion(row, batchId, operatorId);
                if (MODE_REPLACE.equals(mode) && row.getStatus() != null && row.getStatus() == 1) {
                    offlineIds.add(row.getId());
                }
                result.setMissingCount(result.getMissingCount() + 1);
            }
        }
        if (!offlineIds.isEmpty()) {
            tableDocMapper.markOfflineByIds(offlineIds, operatorId);
        }
        if (result.getMissingTables().size() > MISSING_NAME_LIMIT) {
            result.setMissingTables(new ArrayList<>(result.getMissingTables().subList(0, MISSING_NAME_LIMIT)));
        }
    }

    /** 缺失表只留一条摘要版本，不重复存整份结构（它的上一版结构已经在目录里） */
    private void writeMissingVersion(DbTableDocEntity row, Long batchId, Long operatorId) {
        DbTableVersionEntity version = new DbTableVersionEntity();
        version.setTableDocId(row.getId());
        version.setBatchId(batchId);
        version.setSystemId(row.getSystemId());
        version.setDatabaseId(row.getDatabaseId());
        version.setTableName(row.getTableName());
        version.setChangeType(CT_MISSING);
        version.setTableComment(row.getTableComment());
        version.setColumnCount(row.getColumnCount() == null ? 0 : row.getColumnCount());
        version.setDiffSummary("本次脚本未包含该表");
        version.setCreatedBy(operatorId);
        versionMapper.insert(version);
    }

    private void writeVersion(Long tableDocId, DbDatabaseEntity db, TableSchema incoming, Long batchId,
                              String changeType, String summary, Long operatorId) {
        DbTableVersionEntity version = new DbTableVersionEntity();
        version.setTableDocId(tableDocId);
        version.setBatchId(batchId);
        version.setSystemId(db.getSystemId());
        version.setDatabaseId(db.getId());
        version.setTableName(incoming.getTableName());
        version.setChangeType(changeType);
        version.setTableComment(incoming.getTableComment());
        version.setColumnCount(columnCount(incoming));
        version.setColumnsJson(codec.writeJson(incoming.getColumns()));
        version.setIndexesJson(codec.writeJson(incoming.getIndexes()));
        version.setSourceDdl(DdlRenderer.render(incoming));
        version.setDiffSummary(summary == null || summary.isEmpty() ? changeType : summary);
        version.setCreatedBy(operatorId);
        versionMapper.insert(version);
    }

    private DbTableDocEntity newRow(TableSchema incoming, DbDatabaseEntity db, Long batchId, Long operatorId) {
        DbTableDocEntity row = new DbTableDocEntity();
        row.setSystemId(db.getSystemId());
        row.setDatabaseId(db.getId());
        row.setTableName(incoming.getTableName());
        row.setCreatedBy(operatorId);
        // 首次登记就是第 1 次被分析
        row.setParseCount(1);
        fillStructure(row, incoming, db, batchId, operatorId);
        row.setFirstBatchId(batchId);
        return row;
    }

    /** 把解析结果写进目录行的结构字段（新增与变更共用一套口径，避免两边对不上） */
    private void fillStructure(DbTableDocEntity row, TableSchema incoming, DbDatabaseEntity db,
                               Long batchId, Long operatorId) {
        row.setTableComment(incoming.getTableComment());
        row.setDbUser(db.getDbUser());
        row.setDbType(db.getDbType());
        row.setColumnCount(columnCount(incoming));
        row.setIndexCount(incoming.getIndexes() == null ? 0 : incoming.getIndexes().size());
        row.setPkColumns(codec.pkColumns(incoming.getIndexes()));
        row.setColumnsJson(codec.writeJson(incoming.getColumns()));
        row.setIndexesJson(codec.writeJson(incoming.getIndexes()));
        row.setSourceDdl(DdlRenderer.render(incoming));
        row.setLastBatchId(batchId);
        // parse_count 由 SQL 侧 +1（insert 路径在 newRow 里给初值），这里不重复累加
        row.setStatus(1);
        row.setUpdatedBy(operatorId);
    }

    // ============================== 内部：定位归属 ==============================

    /** 系统定位：给了 ID 用 ID；给了名称同名复用；都没有就顺手建一个 */
    private DbSystemEntity resolveSystem(ImportRequest req, Long operatorId) {
        if (req.getSystemId() != null) {
            return assetService.getSystem(req.getSystemId());
        }
        String name = DbAssetService.trimToNull(req.getSystemName());
        if (name == null) {
            throw new BizException(40001, "请选择业务系统，或填写新系统名称");
        }
        DbSystemEntity found = assetService.findSystemByName(name);
        if (found != null) {
            return found;
        }
        DbSystemEntity form = new DbSystemEntity();
        form.setSystemName(name);
        form.setDbType(req.getDbType());
        return assetService.createSystem(form, operatorId);
    }

    /** 库用户定位：给了 ID 直接用；否则按 (系统, 用户, 环境) 找，找不到就建 */
    private DbDatabaseEntity resolvePrimaryDatabase(ImportRequest req, DbSystemEntity system,
                                                    Long operatorId, List<String> warnings) {
        if (req.getDatabaseId() != null) {
            DbDatabaseEntity db = assetService.getDatabase(req.getDatabaseId());
            if (!db.getSystemId().equals(system.getId())) {
                warnings.add("所选数据库用户属于「" + db.getSystemName() + "」，本次以该库所属系统为准。");
            }
            return db;
        }
        String dbUser = DbAssetService.trimToNull(req.getDbUser());
        if (dbUser == null) {
            throw new BizException(40001, "请指定数据库用户/模式名，或选择已有的数据库用户");
        }
        String env = DbAssetService.normalizeEnv(req.getEnv());
        DbDatabaseEntity found = databaseMapper.selectByUk(system.getId(), dbUser, env);
        if (found != null) {
            return found;
        }
        DbDatabaseEntity form = new DbDatabaseEntity();
        form.setSystemId(system.getId());
        form.setDbUser(dbUser);
        form.setDbName(DbAssetService.trimToNull(req.getDbName()));
        form.setDbType(req.getDbType() == null ? system.getDbType() : req.getDbType());
        form.setHost(DbAssetService.trimToNull(req.getHost()));
        form.setPort(req.getPort());
        form.setEnv(env);
        form.setRemark("由 SQL 分析导入时创建");
        return assetService.createDatabase(form, operatorId);
    }

    /**
     * 表级归位：脚本里写了 USER.TABLE 时按前缀走，
     * 同一系统下没有这个用户就自动建一个（autoCreateDatabase=false 时不建，统一归入所选用户并给出提示）。
     */
    private DbDatabaseEntity routeDatabase(TableSchema incoming, DbDatabaseEntity primary, ImportRequest req,
                                           Long operatorId, boolean autoCreate, List<String> warnings) {
        String prefix = DbAssetService.trimToNull(incoming.getDbUser());
        if (prefix == null || prefix.equalsIgnoreCase(primary.getDbUser())) {
            return primary;
        }
        DbDatabaseEntity located = locateByPrefix(primary, prefix);
        if (located != null) {
            return located;
        }
        if (!autoCreate) {
            warnings.add("表 " + prefix + "." + incoming.getTableName()
                    + " 的库用户前缀与所选用户不同，已按开关归入「" + primary.getDbUser() + "」。");
            return primary;
        }
        DbDatabaseEntity form = new DbDatabaseEntity();
        form.setSystemId(primary.getSystemId());
        form.setDbUser(prefix);
        form.setDbName(primary.getDbName());
        form.setDbType(req.getDbType() == null ? primary.getDbType() : req.getDbType());
        form.setHost(primary.getHost());
        form.setPort(primary.getPort());
        form.setEnv(primary.getEnv());
        form.setRemark("由脚本中的限定名「" + prefix + "." + incoming.getTableName() + "」自动创建");
        DbDatabaseEntity created = assetService.createDatabase(form, operatorId);
        warnings.add("脚本里出现新的数据库用户「" + prefix + "」，已在该系统下自动建档。");
        return created;
    }

    /** 在同一系统、同一环境下按前缀找已有用户（不创建） */
    private DbDatabaseEntity locateByPrefix(DbDatabaseEntity primary, String prefix) {
        String user = DbAssetService.trimToNull(prefix);
        if (user == null) {
            return primary;
        }
        if (user.equalsIgnoreCase(primary.getDbUser())) {
            return primary;
        }
        return databaseMapper.selectByUk(primary.getSystemId(), user, primary.getEnv());
    }

    /** 批次标题：不填就用「系统 - 库用户 - 日期」，保证列表里一眼看得出归属 */
    private String resolveTitle(ImportRequest req, DbSystemEntity system, DbDatabaseEntity db) {
        String title = DbAssetService.trimToNull(req.getTitle());
        if (title != null) {
            return title;
        }
        return system.getSystemName() + " · " + db.getDbUser() + " · " + LocalDate.now();
    }

    private TableChangeItem item(TableSchema incoming, DbDatabaseEntity db, String changeType, String summary,
                                 Integer oldColumnCount, int newColumnCount, Long tableDocId) {
        return TableChangeItem.builder()
                .tableName(incoming.getTableName())
                .dbUser(db.getDbUser())
                .changeType(changeType)
                .diffSummary(summary)
                .oldColumnCount(oldColumnCount == null ? 0 : oldColumnCount)
                .newColumnCount(newColumnCount)
                .tableComment(incoming.getTableComment())
                .tableDocId(tableDocId)
                .build();
    }

    private int columnCount(TableSchema schema) {
        return schema.getColumns() == null ? 0 : schema.getColumns().size();
    }
}