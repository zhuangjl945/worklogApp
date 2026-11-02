package com.zjl.worklog.dbdoc;

import com.zjl.worklog.dbdoc.dto.ColumnInfo;
import com.zjl.worklog.dbdoc.dto.IndexInfo;
import com.zjl.worklog.dbdoc.dto.ParseResult;
import com.zjl.worklog.dbdoc.dto.TableSchema;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.Index;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SQL DDL 解析服务。
 * 解析 CREATE TABLE / ALTER TABLE / CREATE INDEX 语句，输出结构化表信息。
 * 仅做语法解析，不连接任何数据库。
 *
 * 解析策略（按顺序）：
 * 1. 先由 {@link SqlScriptSplitter} 把整段脚本切成单条语句，避免一条语句报错就全军覆没；
 * 2. 每条语句交给 JSqlParser 正式解析；
 * 3. JSqlParser 解析不了、或解析成它不支持的语句（FULLTEXT KEY、PARTITION BY、
 *    SQL Server 的 IDENTITY 等），退到 {@link MysqlDdlFallbackParser} 做容错解析；
 * 4. 两条路都失败才记一条警告并跳过该条语句，整份文档照常返回。
 */
@Service
public class SqlParseService {

    private static final Logger log = LoggerFactory.getLogger(SqlParseService.class);

    /** 与表结构文档有关的语句开头 */
    private static final List<String> RELEVANT_HEADS = List.of(
            "create table", "create index", "create unique", "alter table", "comment on");

    /** 与表结构文档无关、静默跳过的语句开头 */
    private static final List<String> IGNORED_HEADS = List.of(
            "insert", "update", "delete", "merge", "select", "with", "drop", "truncate", "rename",
            "use", "set", "begin", "commit", "rollback", "start", "savepoint", "lock", "unlock",
            "grant", "revoke", "call", "do", "show", "desc", "describe", "explain", "analyze",
            "optimize", "repair", "check table", "go", "declare", "if ", "while", "handler",
            "create trigger", "create procedure", "create function", "create event", "create view",
            "create materialized", "create sequence", "create extension", "create type", "create domain",
            "create schema", "create database", "create user", "create role", "create tablespace",
            "create or replace", "create package", "create policy", "create rule", "create server",
            "alter database", "alter schema", "alter system", "alter index", "alter view",
            "alter sequence", "alter function", "alter procedure", "alter trigger", "alter user",
            "alter role", "alter session");

    /**
     * 解析 DDL SQL 语句。
     *
     * @param sql DDL SQL 字符串（可包含多条语句）
     * @return 解析结果，包含表结构列表与未能解析的语句警告
     */
    public ParseResult parseDdl(String sql) {
        // 表名 → 表结构的有序映射，保证输出顺序与建表顺序一致
        Map<String, TableSchema> tableMap = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        if (sql == null || sql.isBlank()) {
            return new ParseResult(new ArrayList<>(), warnings);
        }

        List<SqlScriptSplitter.StatementChunk> chunks = SqlScriptSplitter.split(sql);
        if (chunks.isEmpty()) {
            warnings.add("没有识别到 SQL 语句，请确认粘贴内容里包含 CREATE TABLE 语句。");
            return new ParseResult(new ArrayList<>(), warnings);
        }

        int ignored = 0;
        for (SqlScriptSplitter.StatementChunk chunk : chunks) {
            String raw = chunk.sql();
            String head = statementHead(raw);
            if (isIgnored(head)) {
                ignored++;
                continue;
            }

            // PostgreSQL 的 COMMENT ON：JSqlParser 认得但不产生表结构，这里直接落到已有表上
            if (MysqlDdlFallbackParser.applyCommentOn(raw, tableMap)) {
                continue;
            }

            String cleaned = preprocess(raw);
            boolean handled = false;
            try {
                Statement stmt = CCJSqlParserUtil.parse(cleaned);
                if (stmt instanceof CreateTable ct) {
                    handleCreateTable(ct, tableMap);
                    handled = true;
                } else if (stmt instanceof Alter alter) {
                    handled = handleAlterTable(alter, tableMap);
                } else if (stmt instanceof CreateIndex ci) {
                    handleCreateIndex(ci, tableMap);
                    handled = true;
                }
                // 解析成功但不是建表类语句（SET / USE / INSERT 等）不影响文档
                if (!handled && isRelevant(head)) {
                    // JSqlParser 把 SQL Server 的 IDENTITY 等写法归为"不支持的语句"，交给容错解析器
                    fallbackParse(cleaned, chunk.startLine(), head, tableMap, warnings,
                            new IllegalArgumentException("该写法不在 JSqlParser 支持范围内"));
                    handled = true;
                }
            } catch (Exception primaryError) {
                if (!handled) {
                    fallbackParse(cleaned, chunk.startLine(), head, tableMap, warnings, primaryError);
                }
            }
        }

        // 只被 ALTER / COMMENT 引用、最终既没有列也没有索引的表是解析副产品，去掉
        tableMap.entrySet().removeIf(entry -> {
            TableSchema schema = entry.getValue();
            boolean emptyColumns = schema.getColumns() == null || schema.getColumns().isEmpty();
            boolean emptyIndexes = schema.getIndexes() == null || schema.getIndexes().isEmpty();
            boolean noComment = schema.getTableComment() == null || schema.getTableComment().isEmpty();
            return emptyColumns && emptyIndexes && noComment;
        });

        if (tableMap.isEmpty() && warnings.isEmpty()) {
            if (ignored == chunks.size()) {
                warnings.add("脚本里没有建表语句（只有数据写入或其它语句），因此没有可生成的表结构文档。");
            } else {
                warnings.add("脚本里没有可解析的建表语句。");
            }
        }
        // 把「HIS.USER_INFO」这类限定名拆成 库用户 + 表名：映射表的键始终是完整名，
        // 同一脚本里不同用户下的同名表不会互相覆盖；对外输出的 TableSchema 带上 dbUser，
        // 导入时就能按「系统 → 数据库用户 → 表」归到正确的目录里。
        Set<String> dbUsers = splitQualifiedNames(tableMap);

        return new ParseResult(new ArrayList<>(tableMap.values()), warnings, dbUsers);
    }

    /**
     * 拆分「用户/模式.表名」限定名，并回收脚本里出现过的库用户集合。
     * 三段式（库.模式.表）只取最后一段当表名，其余整体当用户前缀，够运维场景用。
     */
    private Set<String> splitQualifiedNames(Map<String, TableSchema> tableMap) {
        Set<String> dbUsers = new LinkedHashSet<>();
        for (Map.Entry<String, TableSchema> entry : tableMap.entrySet()) {
            String qualified = entry.getKey();
            TableSchema schema = entry.getValue();
            int dot = qualified.lastIndexOf('.');
            if (dot > 0 && dot < qualified.length() - 1) {
                String user = qualified.substring(0, dot);
                String table = qualified.substring(dot + 1);
                schema.setDbUser(user);
                schema.setTableName(table);
                dbUsers.add(user);
            } else {
                schema.setDbUser(null);
                schema.setTableName(qualified);
            }
        }
        return dbUsers;
    }

    // ============================== 语句级处理 ==============================

    /**
     * 交给 JSqlParser 之前的预处理：去注释、剪掉分区定义子句、去掉结尾分号。
     * 分区子句与表结构文档无关，但会让 JSqlParser 直接报错，所以先剪掉。
     */
    private String preprocess(String statement) {
        String sql = SqlSyntaxUtils.stripComments(statement).trim();
        int partition = SqlSyntaxUtils.indexOfKeyword(sql, "PARTITION");
        if (partition > 0) {
            sql = sql.substring(0, partition).trim();
        }
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        return sql;
    }

    /** 取语句开头的小写关键字（最多两个单词，如 create table） */
    private String statementHead(String statement) {
        String s = SqlSyntaxUtils.stripComments(statement).trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        int words = 0;
        for (String part : s.split("\\s+", 4)) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(part);
            if (++words == 2) {
                break;
            }
        }
        return sb.toString();
    }

    private boolean isRelevant(String head) {
        for (String prefix : RELEVANT_HEADS) {
            if (head.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isIgnored(String head) {
        for (String prefix : IGNORED_HEADS) {
            if (head.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * JSqlParser 处理不了时的兜底：用本地容错解析器再试一次，仍失败才记警告。
     *
     * @return 容错解析成功返回 true
     */
    private boolean fallbackParse(String cleaned, int line, String head,
                                  Map<String, TableSchema> tableMap, List<String> warnings,
                                  Exception primaryError) {
        try {
            if (head.startsWith("create table")) {
                TableSchema schema = MysqlDdlFallbackParser.parseCreateTable(cleaned);
                tableMap.put(schema.getTableName(), schema);
                return true;
            }
            if (head.startsWith("alter table")) {
                List<String> unsupported = MysqlDdlFallbackParser.applyAlterTable(cleaned, tableMap);
                for (String action : unsupported) {
                    warnings.add(String.format("第 %d 行 ALTER TABLE 中的动作暂不支持，已忽略：%s", line, action));
                }
                return true;
            }
        } catch (MysqlDdlFallbackParser.UnsupportedDdlException fallbackError) {
            log.debug("容错解析器也无法处理第 {} 行语句", line, fallbackError);
        } catch (RuntimeException fallbackError) {
            log.warn("容错解析第 {} 行语句时出现预期外异常", line, fallbackError);
        }

        warnings.add(String.format("第 %d 行的语句暂不支持，已跳过：%s（解析提示：%s）",
                line, preview(cleaned), briefError(primaryError)));
        return false;
    }

    /** 语句预览，避免警告里塞进整段 DDL */
    private String preview(String sql) {
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "...";
    }

    /** 把 JSqlParser 冗长的报错压成一行短句 */
    private String briefError(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        msg = msg.replaceAll("\\s+", " ").trim();
        int expecting = msg.indexOf("Was expecting");
        if (expecting > 40) {
            msg = msg.substring(0, expecting).trim();
        }
        return msg.length() <= 160 ? msg : msg.substring(0, 160) + "...";
    }

    // ============================== JSqlParser 路径 ==============================

    /**
     * 处理 CREATE TABLE 语句
     */
    private void handleCreateTable(CreateTable ct, Map<String, TableSchema> tableMap) {
        String tableName = tableNameOf(ct.getTable());
        String tableComment = extractTableComment(ct);

        List<ColumnInfo> columns = new ArrayList<>();
        List<IndexInfo> indexes = new ArrayList<>();
        Set<String> primaryKeys = new HashSet<>();

        // 先从列定义中收集 PRIMARY KEY 列名（列级别的 PRIMARY KEY）
        if (ct.getColumnDefinitions() != null) {
            for (ColumnDefinition cd : ct.getColumnDefinitions()) {
                if (cd.getColumnSpecs() != null) {
                    String specs = String.join(" ", cd.getColumnSpecs()).toUpperCase(Locale.ROOT);
                    if (specs.contains("PRIMARY KEY")) {
                        primaryKeys.add(cleanName(cd.getColumnName()));
                    }
                }
            }
        }

        // 解析表级别的 PRIMARY KEY 索引
        if (ct.getIndexes() != null) {
            for (Index idx : ct.getIndexes()) {
                IndexInfo info = buildIndexInfo(idx);
                if (info == null) continue;
                if ("PRIMARY".equals(info.getType())) {
                    primaryKeys.addAll(info.getColumns());
                }
            }
        }

        // 解析列定义
        if (ct.getColumnDefinitions() != null) {
            for (ColumnDefinition cd : ct.getColumnDefinitions()) {
                columns.add(buildColumnInfo(cd, primaryKeys));
            }
        }

        // 解析索引
        if (ct.getIndexes() != null) {
            for (Index idx : ct.getIndexes()) {
                IndexInfo info = buildIndexInfo(idx);
                if (info == null) continue;
                indexes.add(info);
            }
        }

        tableMap.put(tableName, TableSchema.builder()
                .tableName(tableName)
                .tableComment(tableComment)
                .columns(columns)
                .indexes(indexes)
                .build());
    }

    /**
     * 处理 CREATE INDEX 语句：把独立创建的索引归到对应表上。
     */
    private void handleCreateIndex(CreateIndex ci, Map<String, TableSchema> tableMap) {
        String tableName = tableNameOf(ci.getTable());
        TableSchema schema = tableMap.computeIfAbsent(tableName, name -> TableSchema.builder()
                .tableName(name)
                .tableComment("")
                .columns(new ArrayList<>())
                .indexes(new ArrayList<>())
                .build());

        Index index = ci.getIndex();
        if (index == null) {
            return;
        }
        List<String> cols = indexColumns(index);
        // JSqlParser 4.9 没有 isUnique()，唯一性体现在 Index.getType() 上
        boolean unique = index.getType() != null
                && index.getType().toUpperCase(Locale.ROOT).contains("UNIQUE");
        schema.getIndexes().add(IndexInfo.builder()
                .name(simpleName(index.getName() != null ? index.getName() : (unique ? "UNIQUE" : "INDEX")))
                .type(unique ? "UNIQUE" : "INDEX")
                .columns(cols)
                .build());
    }

    /**
     * 处理 ALTER TABLE 语句。
     *
     * @return 语句里的动作被识别处理返回 true；整条都不认识返回 false（交给容错解析器再试）
     */
    private boolean handleAlterTable(Alter alter, Map<String, TableSchema> tableMap) {
        String tableName = tableNameOf(alter.getTable());
        TableSchema schema = tableMap.get(tableName);
        boolean recognized = false;

        for (AlterExpression expr : alter.getAlterExpressions()) {
            AlterOperation op = expr.getOperation();
            if (op == null) {
                continue;
            }
            switch (op) {
                case ADD -> {
                    if (schema == null) {
                        schema = emptySchema(tableName, tableMap);
                    }
                    recognized |= addColumns(schema, expr);
                    IndexInfo added = buildIndexInfo(expr.getIndex());
                    if (added != null) {
                        schema.getIndexes().add(added);
                        recognized = true;
                    }
                    recognized |= addNamedIndex(schema, expr.getUkColumns(), expr.getUkName(), "UNIQUE");
                    recognized |= addNamedIndex(schema, expr.getPkColumns(), "PRIMARY", "PRIMARY");
                }
                case MODIFY -> {
                    if (schema == null) {
                        return false;
                    }
                    recognized |= replaceColumns(schema, expr, null);
                }
                case CHANGE -> {
                    if (schema == null) {
                        return false;
                    }
                    String oldName = cleanName(firstNonNull(expr.getColOldName(), expr.getColumnOldName()));
                    recognized |= replaceColumns(schema, expr, oldName);
                }
                case DROP, DROP_PRIMARY_KEY, DROP_UNIQUE, DROP_FOREIGN_KEY -> {
                    if (schema == null) {
                        return false;
                    }
                    recognized |= dropTarget(schema, expr, op);
                }
                case RENAME, RENAME_INDEX, RENAME_KEY, RENAME_CONSTRAINT -> {
                    if (schema == null) {
                        return false;
                    }
                    recognized |= renameTarget(schema, expr, op);
                }
                case RENAME_TABLE -> {
                    String newTableName = cleanName(expr.getNewTableName());
                    if (schema != null && !newTableName.isEmpty()) {
                        renameTable(tableMap, tableName, newTableName);
                        recognized = true;
                    }
                }
                case COMMENT, COMMENT_WITH_EQUAL_SIGN -> {
                    if (schema == null) {
                        return false;
                    }
                    String text = expr.getCommentText();
                    if (text != null) {
                        schema.setTableComment(cleanCommentValue(text));
                        recognized = true;
                    }
                }
                default -> {
                    // ALGORITHM / LOCK / RENAME 之类动作与表结构文档无关
                    recognized = true;
                }
            }
        }
        return recognized;
    }

    /**
     * ALTER TABLE RENAME COLUMN / RENAME INDEX：只改名，不动结构。
     */
    private boolean renameTarget(TableSchema schema, AlterExpression expr, AlterOperation op) {
        String oldName = cleanName(expr.getColOldName());
        String newName = cleanName(expr.getColumnName());
        if (oldName.isEmpty() || newName.isEmpty()) {
            return false;
        }
        if (op == AlterOperation.RENAME) {
            for (ColumnInfo col : schema.getColumns()) {
                if (col.getName().equalsIgnoreCase(oldName)) {
                    col.setName(newName);
                    return true;
                }
            }
            return false;
        }
        for (IndexInfo idx : schema.getIndexes()) {
            if (oldName.equalsIgnoreCase(idx.getName())) {
                idx.setName(newName);
                return true;
            }
        }
        return false;
    }

    /** 表改名：保持原有顺序，只换键名和表名 */
    private void renameTable(Map<String, TableSchema> tableMap, String oldName, String newName) {
        TableSchema schema = tableMap.remove(oldName);
        if (schema == null) {
            return;
        }
        schema.setTableName(newName);
        List<Map.Entry<String, TableSchema>> entries = new ArrayList<>(tableMap.entrySet());
        tableMap.clear();
        boolean placed = false;
        for (Map.Entry<String, TableSchema> entry : entries) {
            if (!placed) {
                tableMap.put(newName, schema);
                placed = true;
            }
            tableMap.put(entry.getKey(), entry.getValue());
        }
        if (!placed) {
            tableMap.put(newName, schema);
        }
    }

    /** 索引名不带模式前缀：Oracle 导出脚本里常写成 SCHEMA.IDX_NAME */
    private String simpleName(String name) {
        String cleaned = cleanName(name);
        int dot = cleaned.lastIndexOf('.');
        return dot >= 0 ? cleaned.substring(dot + 1).trim() : cleaned;
    }

    /** 表不存在时补一个空壳（ALTER 一个没建过的表） */
    private TableSchema emptySchema(String tableName, Map<String, TableSchema> tableMap) {
        TableSchema schema = TableSchema.builder()
                .tableName(tableName)
                .tableComment("")
                .columns(new ArrayList<>())
                .indexes(new ArrayList<>())
                .build();
        tableMap.put(tableName, schema);
        return schema;
    }

    /** ALTER TABLE ADD 列 */
    private boolean addColumns(TableSchema schema, AlterExpression expr) {
        if (expr.getColDataTypeList() == null || expr.getColDataTypeList().isEmpty()) {
            return false;
        }
        for (AlterExpression.ColumnDataType cdt : expr.getColDataTypeList()) {
            schema.getColumns().add(buildAlterColumnInfo(cdt, false));
        }
        return true;
    }

    /** ALTER TABLE MODIFY / CHANGE 列：同名替换，CHANGE 还支持改名 */
    private boolean replaceColumns(TableSchema schema, AlterExpression expr, String oldName) {
        if (expr.getColDataTypeList() == null || expr.getColDataTypeList().isEmpty()) {
            return false;
        }
        for (AlterExpression.ColumnDataType cdt : expr.getColDataTypeList()) {
            ColumnInfo col = buildAlterColumnInfo(cdt, false);
            String key = (oldName == null || oldName.isEmpty()) ? col.getName() : oldName;
            boolean replaced = false;
            for (int i = 0; i < schema.getColumns().size(); i++) {
                if (schema.getColumns().get(i).getName().equalsIgnoreCase(key)) {
                    schema.getColumns().set(i, col);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                schema.getColumns().add(col);
            }
        }
        return true;
    }

    /** ALTER TABLE ADD UNIQUE / ADD PRIMARY KEY 这类带列名的形式 */
    private boolean addNamedIndex(TableSchema schema, List<String> columns, String name, String type) {
        if (columns == null || columns.isEmpty()) {
            return false;
        }
        List<String> cols = new ArrayList<>();
        for (String c : columns) {
            cols.add(cleanName(c));
        }
        String indexName = (name == null || name.isEmpty()) ? type : cleanName(name);
        schema.getIndexes().add(IndexInfo.builder().name(indexName).type(type).columns(cols).build());
        if ("PRIMARY".equals(type)) {
            for (ColumnInfo col : schema.getColumns()) {
                if (cols.contains(col.getName())) {
                    col.setPrimaryKey(true);
                }
            }
        }
        return true;
    }

    /** ALTER TABLE DROP 列 / 索引 / 主键 */
    private boolean dropTarget(TableSchema schema, AlterExpression expr, AlterOperation op) {
        if (op == AlterOperation.DROP_PRIMARY_KEY) {
            schema.getIndexes().removeIf(idx -> "PRIMARY".equals(idx.getType()));
            schema.getColumns().forEach(col -> col.setPrimaryKey(false));
            return true;
        }
        if (op == AlterOperation.DROP_UNIQUE || op == AlterOperation.DROP_FOREIGN_KEY) {
            String name = cleanName(firstNonNull(expr.getConstraintName(), expr.getColumnName()));
            if (!name.isEmpty()) {
                schema.getIndexes().removeIf(idx -> name.equalsIgnoreCase(idx.getName()));
            }
            return true;
        }
        String target = expr.getColumnName() == null ? "" : expr.getColumnName().trim();
        String upper = target.toUpperCase(Locale.ROOT);
        if (upper.startsWith("INDEX ") || upper.startsWith("KEY ")) {
            String idxName = cleanName(target.substring(5).trim());
            schema.getIndexes().removeIf(idx -> idx.getName() != null && idx.getName().equalsIgnoreCase(idxName));
            return true;
        }
        if (upper.startsWith("PRIMARY")) {
            schema.getIndexes().removeIf(idx -> "PRIMARY".equals(idx.getType()));
            schema.getColumns().forEach(col -> col.setPrimaryKey(false));
            return true;
        }
        String colName = cleanName(target);
        if (colName.isEmpty()) {
            return false;
        }
        schema.getColumns().removeIf(col -> col.getName().equalsIgnoreCase(colName));
        return true;
    }

    /** 由 ALTER 的列定义构建列信息，与建表路径口径一致 */
    private ColumnInfo buildAlterColumnInfo(AlterExpression.ColumnDataType cdt, boolean pk) {
        String name = cleanName(cdt.getColumnName());
        boolean nullable = true;
        String defaultValue = null;
        String comment = null;

        List<String> specs = cdt.getColumnSpecs();
        if (specs != null) {
            String specsUpper = String.join(" ", specs).toUpperCase(Locale.ROOT);
            if (specsUpper.contains("NOT NULL")) {
                nullable = false;
            }
            for (int i = 0; i < specs.size(); i++) {
                if ("DEFAULT".equalsIgnoreCase(specs.get(i)) && i + 1 < specs.size()) {
                    defaultValue = normalizeDefaultValue(specs.get(i + 1));
                    break;
                }
            }
            comment = extractCommentFromSpecs(specs);
        }

        return ColumnInfo.builder()
                .name(name)
                .type(extractType(cdt.getColDataType()))
                .length(extractLength(cdt.getColDataType()))
                .nullable(nullable)
                .defaultValue(defaultValue)
                .comment(comment != null ? comment : "")
                .primaryKey(pk)
                .build();
    }

    /**
     * 构建列信息
     */
    private ColumnInfo buildColumnInfo(ColumnDefinition cd, Set<String> primaryKeys) {
        String name = cleanName(cd.getColumnName());
        boolean pk = primaryKeys.contains(name);

        boolean nullable = true;
        String defaultValue = null;
        String comment = null;

        if (cd.getColumnSpecs() != null) {
            List<String> specs = cd.getColumnSpecs();
            String specsUpper = String.join(" ", specs).toUpperCase(Locale.ROOT);
            if (specsUpper.contains("NOT NULL")) {
                nullable = false;
            }
            // 提取 DEFAULT 值
            for (int i = 0; i < specs.size(); i++) {
                if ("DEFAULT".equalsIgnoreCase(specs.get(i)) && i + 1 < specs.size()) {
                    defaultValue = normalizeDefaultValue(specs.get(i + 1));
                    break;
                }
            }
            // 提取 COMMENT
            comment = extractCommentFromSpecs(specs);
        }

        return ColumnInfo.builder()
                .name(name)
                .type(extractType(cd.getColDataType()))
                .length(extractLength(cd.getColDataType()))
                .nullable(nullable)
                .defaultValue(defaultValue)
                .comment(comment != null ? comment : "")
                .primaryKey(pk)
                .build();
    }

    /**
     * 构建索引信息，对所有字段做防御性 null 检查。
     * JSqlParser 会把 FOREIGN KEY / CHECK 约束也放进索引列表，这些不属于索引，直接丢弃。
     *
     * @return 不是索引时返回 null
     */
    private IndexInfo buildIndexInfo(Index idx) {
        if (idx == null) {
            return null;
        }

        // type 防御：FOREIGN KEY、CHECK 等约束的 type 可能为 null 或不是索引
        String rawType = idx.getType() == null ? "" : idx.getType().toUpperCase(Locale.ROOT);
        if (rawType.contains("FOREIGN")) {
            return null;
        }

        // JSqlParser 会把没有名字的约束拼成字符串 "null"，要当成没有名字处理
        String indexName = idx.getName() == null ? "" : simpleName(idx.getName());
        if (indexName.isBlank() || "null".equalsIgnoreCase(indexName)) {
            indexName = null;
        }

        List<String> cols = indexColumns(idx);
        if (cols.isEmpty()) {
            // 没有列的条目（外键、CHECK 约束）不是可用索引，写进文档只会误导
            return null;
        }

        String type;
        if (rawType.contains("PRIMARY")) {
            type = "PRIMARY";
        } else if (rawType.contains("UNIQUE")) {
            type = "UNIQUE";
        } else if (rawType.contains("FULLTEXT")) {
            type = "FULLTEXT";
        } else if (rawType.contains("SPATIAL")) {
            type = "SPATIAL";
        } else {
            type = "INDEX";
        }

        return IndexInfo.builder()
                .name(indexName != null ? indexName : type)
                .type(type)
                .columns(cols)
                .build();
    }

    /** 安全地取出索引列名 */
    private List<String> indexColumns(Index idx) {
        List<String> cols = new ArrayList<>();
        if (idx.getColumns() == null) {
            return cols;
        }
        for (Index.ColumnParams cp : idx.getColumns()) {
            if (cp != null && cp.getColumnName() != null) {
                cols.add(cleanName(cp.getColumnName()));
            }
        }
        return cols;
    }

    // ============================== 小工具 ==============================

    /**
     * 表名统一取 "模式名.表名"，没有模式名时就是表名。
     * 建表、改表、注释三类语句必须用同一套名字，否则 COMMENT ON 找不到表，
     * 还会给同一个表凭空多出一个空壳记录。
     */
    private String tableNameOf(net.sf.jsqlparser.schema.Table table) {
        String full = table.getFullyQualifiedName();
        return cleanName(full != null && !full.isEmpty() ? full : table.getName());
    }

    private String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }

    /**
     * 默认值口径统一：字符串默认值去掉引号并保留原样（可能是中文），
     * 其它（NULL、数字、函数名）统一大写，方便与容错解析路径对齐。
     */
    private String normalizeDefaultValue(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        char first = value.charAt(0);
        if (first == SqlSyntaxUtils.SQ || first == SqlSyntaxUtils.DQ) {
            return SqlSyntaxUtils.unquote(value);
        }
        return value.toUpperCase(Locale.ROOT);
    }

    /** 去掉反引号、方括号、双引号包裹 */
    private String cleanName(String name) {
        return SqlSyntaxUtils.cleanName(name);
    }

    /** 提取数据类型 */
    private String extractType(net.sf.jsqlparser.statement.create.table.ColDataType dt) {
        if (dt == null) return "UNKNOWN";
        return dt.getDataType() != null ? dt.getDataType().toUpperCase(Locale.ROOT) : "UNKNOWN";
    }

    /** 提取长度/精度 */
    private String extractLength(net.sf.jsqlparser.statement.create.table.ColDataType dt) {
        if (dt == null) return null;
        List<String> args = dt.getArgumentsStringList();
        if (args == null || args.isEmpty()) return null;
        List<String> cleaned = new ArrayList<>();
        for (String arg : args) {
            if (arg == null) {
                continue;
            }
            // Oracle 的 VARCHAR2(10 BYTE) / CHAR(1 CHAR)，长度只保留数字
            String value = arg.replaceAll("(?i)\\s+(BYTE|CHAR)$", "").trim();
            if (!value.isEmpty()) {
                cleaned.add(value);
            }
        }
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }

    /** 提取表注释 */
    private String extractTableComment(CreateTable ct) {
        // JSqlParser 把 COMMENT='xxx' 放在 tableOptionsStrings 里
        // 格式可能是 [COMMENT, =, 'xxx'] 或 [COMMENT, 'xxx']
        List<String> opts = ct.getTableOptionsStrings();
        if (opts != null) {
            for (int i = 0; i < opts.size(); i++) {
                if ("COMMENT".equalsIgnoreCase(opts.get(i))) {
                    // 找到 COMMENT 后，跳过可能的 "=" 符号，取下一个非 "=" 的值
                    for (int j = i + 1; j < opts.size(); j++) {
                        String val = opts.get(j);
                        if (!"=".equals(val)) {
                            return cleanCommentValue(val);
                        }
                    }
                }
            }
        }
        return "";
    }

    /** 提取 ALTER 列的注释 */
    private String extractColumnComment(AlterExpression.ColumnDataType cdt) {
        if (cdt.getColumnSpecs() != null) {
            return extractCommentFromSpecs(cdt.getColumnSpecs());
        }
        return "";
    }

    /** 从列规格列表中提取 COMMENT 值 */
    private String extractCommentFromSpecs(List<String> specs) {
        for (int i = 0; i < specs.size(); i++) {
            if ("COMMENT".equalsIgnoreCase(specs.get(i)) && i + 1 < specs.size()) {
                return cleanCommentValue(specs.get(i + 1));
            }
        }
        return null;
    }

    /** 去掉 COMMENT 值外层的引号 */
    private String cleanCommentValue(String val) {
        return SqlSyntaxUtils.unquote(val);
    }
}
