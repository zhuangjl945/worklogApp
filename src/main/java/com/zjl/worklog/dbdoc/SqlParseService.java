package com.zjl.worklog.dbdoc;

import com.zjl.worklog.dbdoc.dto.ColumnInfo;
import com.zjl.worklog.dbdoc.dto.IndexInfo;
import com.zjl.worklog.dbdoc.dto.ParseResult;
import com.zjl.worklog.dbdoc.dto.TableSchema;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.Index;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQL DDL 解析服务。
 * 解析 CREATE TABLE / ALTER TABLE 语句，输出结构化表信息。
 * 仅做语法解析，不连接任何数据库。
 */
@Service
public class SqlParseService {

    /**
     * 解析 DDL SQL 语句
     *
     * @param sql DDL SQL 字符串（可包含多条语句）
     * @return 解析结果，包含表结构列表
     */
    public ParseResult parseDdl(String sql) {
        // 表名 → 表结构的有序映射，保证输出顺序与建表顺序一致
        Map<String, TableSchema> tableMap = new LinkedHashMap<>();

        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (Exception e) {
            throw new RuntimeException("SQL 解析失败: " + e.getMessage(), e);
        }

        for (Statement stmt : statements) {
            if (stmt instanceof CreateTable ct) {
                handleCreateTable(ct, tableMap);
            } else if (stmt instanceof Alter alter) {
                handleAlterTable(alter, tableMap);
            }
            // 其他语句（INSERT / DROP 等）静默跳过
        }

        return new ParseResult(new ArrayList<>(tableMap.values()));
    }

    /**
     * 处理 CREATE TABLE 语句
     */
    private void handleCreateTable(CreateTable ct, Map<String, TableSchema> tableMap) {
        String tableName = cleanName(ct.getTable().getName());
        String tableComment = extractTableComment(ct);

        List<ColumnInfo> columns = new ArrayList<>();
        List<IndexInfo> indexes = new ArrayList<>();
        Set<String> primaryKeys = new HashSet<>();

        // 先从列定义中收集 PRIMARY KEY 列名（列级别的 PRIMARY KEY）
        if (ct.getColumnDefinitions() != null) {
            for (ColumnDefinition cd : ct.getColumnDefinitions()) {
                if (cd.getColumnSpecs() != null) {
                    String specs = String.join(" ", cd.getColumnSpecs()).toUpperCase();
                    if (specs.contains("PRIMARY KEY")) {
                        primaryKeys.add(cleanName(cd.getColumnName()));
                    }
                }
            }
        }

        // 解析表级别的 PRIMARY KEY 索引
        if (ct.getIndexes() != null) {
            for (Index idx : ct.getIndexes()) {
                if (idx == null) continue;
                if (idx.getType() != null && idx.getType().toUpperCase().contains("PRIMARY")) {
                    for (Index.ColumnParams cp : idx.getColumns()) {
                        primaryKeys.add(cleanName(cp.getColumnName()));
                    }
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
                if (idx == null) continue;
                indexes.add(buildIndexInfo(idx));
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
     * 处理 ALTER TABLE 语句
     */
    private void handleAlterTable(Alter alter, Map<String, TableSchema> tableMap) {
        String tableName = cleanName(alter.getTable().getName());
        TableSchema schema = tableMap.get(tableName);
        if (schema == null) {
            // ALTER 一个未建过的表：建一个空壳
            schema = TableSchema.builder()
                    .tableName(tableName)
                    .tableComment("")
                    .columns(new ArrayList<>())
                    .indexes(new ArrayList<>())
                    .build();
            tableMap.put(tableName, schema);
        }

        for (AlterExpression expr : alter.getAlterExpressions()) {
            if (expr.getOperation() == AlterOperation.ADD) {
                // ADD COLUMN
                if (expr.getColDataTypeList() != null) {
                    for (AlterExpression.ColumnDataType cdt : expr.getColDataTypeList()) {
                        // 提取默认值
                        String defaultValue = null;
                        if (cdt.getColumnSpecs() != null) {
                            List<String> specs = cdt.getColumnSpecs();
                            for (int i = 0; i < specs.size(); i++) {
                                if ("DEFAULT".equalsIgnoreCase(specs.get(i)) && i + 1 < specs.size()) {
                                    defaultValue = specs.get(i + 1).toUpperCase();
                                    break;
                                }
                            }
                        }
                        ColumnInfo col = ColumnInfo.builder()
                                .name(cleanName(cdt.getColumnName()))
                                .type(extractType(cdt.getColDataType()))
                                .length(extractLength(cdt.getColDataType()))
                                .nullable(true)
                                .defaultValue(defaultValue)
                                .comment(extractColumnComment(cdt))
                                .primaryKey(false)
                                .build();
                        schema.getColumns().add(col);
                    }
                }
                // ADD INDEX / ADD UNIQUE
                if (expr.getIndex() != null) {
                    schema.getIndexes().add(buildIndexInfo(expr.getIndex()));
                }
            }
        }
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
            String specsUpper = String.join(" ", specs).toUpperCase();
            if (specsUpper.contains("NOT NULL")) {
                nullable = false;
            }
            // 提取 DEFAULT 值
            for (int i = 0; i < specs.size(); i++) {
                if ("DEFAULT".equalsIgnoreCase(specs.get(i)) && i + 1 < specs.size()) {
                    defaultValue = specs.get(i + 1).toUpperCase();
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
     * 构建索引信息
     */
    private IndexInfo buildIndexInfo(Index idx) {
        if (idx == null) return IndexInfo.builder().name("UNKNOWN").type("INDEX").columns(new ArrayList<>()).build();
        String type = idx.getType() != null ? idx.getType().toUpperCase() : "INDEX";
        // 统一 PRIMARY KEY 的类型标签
        if (type.contains("PRIMARY")) {
            type = "PRIMARY";
        } else if (type.contains("UNIQUE")) {
            type = "UNIQUE";
        } else if (type.contains("INDEX") || type.contains("KEY")) {
            type = "INDEX";
        }
        List<String> cols = new ArrayList<>();
        if (idx.getColumns() != null) {
            for (Index.ColumnParams cp : idx.getColumns()) {
                cols.add(cleanName(cp.getColumnName()));
            }
        }

        return IndexInfo.builder()
                .name(idx.getName() != null ? cleanName(idx.getName()) : type)
                .type(type)
                .columns(cols)
                .build();
    }

    /** 去掉反引号、方括号、双引号包裹 */
    private String cleanName(String name) {
        if (name == null) return "";
        name = name.trim();
        char bt = 96; // backtick char
        name = name.replace(String.valueOf(bt), "");
        name = name.replace("[", "").replace("]", "");
        name = name.replace("\"", "");
        return name;
    }

    /** 提取数据类型 */
    private String extractType(net.sf.jsqlparser.statement.create.table.ColDataType dt) {
        if (dt == null) return "UNKNOWN";
        return dt.getDataType() != null ? dt.getDataType().toUpperCase() : "UNKNOWN";
    }

    /** 提取长度/精度 */
    private String extractLength(net.sf.jsqlparser.statement.create.table.ColDataType dt) {
        if (dt == null) return null;
        List<String> args = dt.getArgumentsStringList();
        if (args == null || args.isEmpty()) return null;
        return String.join(",", args);
    }

    /** 提取表注释 */
    private String extractTableComment(CreateTable ct) {
        // JSqlParser 把 COMMENT='xxx' 放在 tableOptionsStrings 里
        // 格式可能是 ["COMMENT", "=", "'xxx'"] 或 ["COMMENT", "'xxx'"]
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
        if (val == null) return "";
        val = val.trim();
        if ((val.startsWith("'") && val.endsWith("'")) || (val.startsWith("\"") && val.endsWith("\""))) {
            return val.substring(1, val.length() - 1);
        }
        return val;
    }
}







