package com.zjl.worklog.dbdoc;

import com.zjl.worklog.dbdoc.dto.ColumnInfo;
import com.zjl.worklog.dbdoc.dto.IndexInfo;
import com.zjl.worklog.dbdoc.dto.TableSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 把解析出来的表结构还原成一份可读的 CREATE TABLE 文本。
 * 用途：目录里点开一张表，能直接复制一段建表语句给别人（提工单、写变更脚本、对比线下库）。
 * 原文 SQL 仍然完整存在批次里，这里是「按结构化结果重排」的版本，口径统一、行数可控。
 */
public final class DdlRenderer {

    private DdlRenderer() {
    }

    /** 还原建表语句，MySQL 风格；无字段时只输出注释头 */
    public static String render(TableSchema schema) {
        if (schema == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String qualified = schema.getDbUser() == null || schema.getDbUser().isEmpty()
                ? schema.getTableName()
                : schema.getDbUser() + "." + schema.getTableName();
        sb.append("CREATE TABLE ").append(qualified).append(" (\n");

        List<String> lines = new ArrayList<>();
        if (schema.getColumns() != null) {
            for (ColumnInfo col : schema.getColumns()) {
                lines.add("  " + renderColumn(col));
            }
        }
        List<String> pk = new ArrayList<>();
        if (schema.getIndexes() != null) {
            for (IndexInfo idx : schema.getIndexes()) {
                if ("PRIMARY".equalsIgnoreCase(idx.getType())) {
                    pk.addAll(idx.getColumns() == null ? List.of() : idx.getColumns());
                } else {
                    lines.add("  " + renderIndex(idx));
                }
            }
        }
        if (!pk.isEmpty()) {
            lines.add("  PRIMARY KEY (" + String.join(", ", pk) + ")");
        }
        if (lines.isEmpty()) {
            lines.add("  -- 未解析到字段，原始脚本可能是 ALTER / COMMENT 单独成句，请查看来源批次");
        }
        sb.append(String.join(",\n", lines)).append("\n)");
        if (schema.getTableComment() != null && !schema.getTableComment().isEmpty()) {
            sb.append(" COMMENT '").append(schema.getTableComment().replace("'", "''")).append("'");
        }
        sb.append(";");
        return sb.toString();
    }

    private static String renderColumn(ColumnInfo col) {
        StringBuilder sb = new StringBuilder();
        sb.append(col.getName()).append(' ').append(col.getType() == null ? "" : col.getType());
        if (col.getLength() != null && !col.getLength().isEmpty()) {
            sb.append('(').append(col.getLength()).append(')');
        }
        sb.append(col.isNullable() ? " NULL" : " NOT NULL");
        if (col.getDefaultValue() != null && !col.getDefaultValue().isEmpty()) {
            sb.append(" DEFAULT ").append(col.getDefaultValue());
        }
        if (col.getComment() != null && !col.getComment().isEmpty()) {
            sb.append(" COMMENT '").append(col.getComment().replace("'", "''")).append('\'');
        }
        return sb.toString();
    }

    private static String renderIndex(IndexInfo idx) {
        String cols = idx.getColumns() == null ? "" : idx.getColumns().stream()
                .filter(v -> v != null && !v.isEmpty())
                .collect(Collectors.joining(", "));
        String head = "UNIQUE".equalsIgnoreCase(idx.getType()) ? "UNIQUE KEY " : "KEY ";
        return head + idx.getName() + " (" + cols + ")";
    }
}