package com.zjl.worklog.dbdoc;

import com.zjl.worklog.dbdoc.dto.ColumnInfo;
import com.zjl.worklog.dbdoc.dto.IndexInfo;
import com.zjl.worklog.dbdoc.dto.TableSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 表结构差异比对单测。
 * 比对口径直接决定「重复分析会不会把没改过的表也算成变更」，
 * 也算决定运维能不能在历史里看清到底改了哪一列，所以逐条钉住。
 */
class TableSchemaDifferTest {

    private final TableSchemaDiffer differ = new TableSchemaDiffer();

    @Test
    @DisplayName("完全一致的结构判为未变化")
    void testIdentical() {
        TableSchema a = schema("t_order", "订单表",
                col("id", "BIGINT", "20", false, null, "主键", true),
                col("status", "TINYINT", "1", false, "1", "状态", false));
        TableSchema b = schema("t_order", "订单表",
                col("id", "BIGINT", "20", false, null, "主键", true),
                col("status", "TINYINT", "1", false, "1", "状态", false));

        TableSchemaDiffer.Difference diff = differ.diff(a, b);
        assertFalse(diff.changed());
    }

    @Test
    @DisplayName("字段名大小写不同不算变更（Oracle 全大写、MySQL 常全小写）")
    void testColumnNameCaseInsensitive() {
        TableSchema a = schema("t", "", col("USER_ID", "VARCHAR", "32", true, null, "", false));
        TableSchema b = schema("t", "", col("user_id", "VARCHAR", "32", true, null, "", false));
        assertFalse(differ.diff(a, b).changed());
    }

    @Test
    @DisplayName("表注释的空格与首尾空白差异不算变更")
    void testCommentWhitespace() {
        TableSchema a = schema("t", "患者  基本信息", col("id", "INT", null, true, null, "", false));
        TableSchema b = schema("t", "患者 基本信息 ", col("id", "INT", null, true, null, "", false));
        assertFalse(differ.diff(a, b).changed());
    }

    @Test
    @DisplayName("目录里还没有这张表 → 判为首次登记")
    void testFirstRegistration() {
        TableSchema incoming = schema("t_new", "新表", col("id", "INT", null, false, null, "", false));
        TableSchemaDiffer.Difference diff = differ.diff(null, incoming);
        assertTrue(diff.changed());
        assertEquals("首次登记", diff.summary());
    }

    @Test
    @DisplayName("新增字段：摘要里点名是哪一列")
    void testAddedColumn() {
        TableSchema a = schema("t", "表", col("id", "INT", null, false, null, "主键", false));
        TableSchema b = schema("t", "表",
                col("id", "INT", null, false, null, "主键", false),
                col("age", "INT", null, true, null, "年龄", false));

        TableSchemaDiffer.Difference diff = differ.diff(a, b);
        assertTrue(diff.changed());
        assertTrue(diff.summary().contains("新增字段"), diff.summary());
        assertTrue(diff.summary().contains("age"), diff.summary());
    }

    @Test
    @DisplayName("删除字段与字段类型变化都能识别")
    void testRemovedAndModifiedColumn() {
        TableSchema a = schema("t", "表",
                col("id", "INT", null, false, null, "", false),
                col("remark", "VARCHAR", "64", true, null, "", false));
        TableSchema b = schema("t", "表",
                col("id", "BIGINT", "20", false, null, "", false));

        TableSchemaDiffer.Difference diff = differ.diff(a, b);
        assertTrue(diff.changed());
        assertTrue(diff.summary().contains("删除字段:remark"), diff.summary());
        assertTrue(diff.summary().contains("字段变更:id"), diff.summary());
        // 变更描述是「旧描述→新描述」，里面还带 NOT NULL 等修饰，只校验关键信息都在
        assertTrue(diff.summary().contains("INT"), diff.summary());
        assertTrue(diff.summary().contains("BIGINT(20)"), diff.summary());
        assertTrue(diff.summary().contains("→"), diff.summary());
    }

    @Test
    @DisplayName("字段顺序调整算变更，但不与内容变更混在一起")
    void testColumnOrderChanged() {
        TableSchema a = schema("t", "表",
                col("a", "INT", null, true, null, "", false),
                col("b", "INT", null, true, null, "", false));
        TableSchema b = schema("t", "表",
                col("b", "INT", null, true, null, "", false),
                col("a", "INT", null, true, null, "", false));

        TableSchemaDiffer.Difference diff = differ.diff(a, b);
        assertTrue(diff.changed());
        assertEquals("字段顺序调整", diff.summary());
    }

    @Test
    @DisplayName("索引的新增、删除、列变化都算结构变更")
    void testIndexChanges() {
        TableSchema a = schema("t", "表", List.of(col("user_id", "INT", null, true, null, "", false)),
                List.of(idx("idx_user", "INDEX", "user_id")));
        TableSchema b = schema("t", "表", List.of(col("user_id", "INT", null, true, null, "", false)),
                List.of(idx("idx_user_type", "UNIQUE", "user_id")));

        TableSchemaDiffer.Difference diff = differ.diff(a, b);
        assertTrue(diff.changed());
        assertTrue(diff.summary().contains("删除索引:idx_user"), diff.summary());
        assertTrue(diff.summary().contains("新增索引:idx_user_type"), diff.summary());
    }

    @Test
    @DisplayName("差异很多时摘要被截断，不能撑爆 diff_summary 字段")
    void testSummaryTruncated() {
        List<ColumnInfo> oldCols = new ArrayList<>();
        List<ColumnInfo> newCols = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            oldCols.add(col("col_" + i, "INT", null, true, null, "", false));
            newCols.add(col("col_" + i, "VARCHAR", "255", true, null, "很长的中文注释用来把摘要撑爆", false));
        }
        TableSchemaDiffer.Difference diff = differ.diff(schema("t", "", oldCols, List.of()),
                schema("t", "", newCols, List.of()));
        assertTrue(diff.changed());
        assertTrue(diff.summary().length() <= 900, "摘要长度应当被收住，实际 " + diff.summary().length());
    }

    // ===== 构造辅助 =====

    private TableSchema schema(String name, String comment, ColumnInfo... columns) {
        return schema(name, comment, List.of(columns), List.of());
    }

    private TableSchema schema(String name, String comment, List<ColumnInfo> columns, List<IndexInfo> indexes) {
        return TableSchema.builder()
                .tableName(name)
                .tableComment(comment)
                .columns(new ArrayList<>(columns))
                .indexes(new ArrayList<>(indexes))
                .build();
    }

    private ColumnInfo col(String name, String type, String length, boolean nullable,
                           String defaultValue, String comment, boolean primaryKey) {
        return ColumnInfo.builder()
                .name(name).type(type).length(length).nullable(nullable)
                .defaultValue(defaultValue).comment(comment).primaryKey(primaryKey)
                .build();
    }

    private IndexInfo idx(String name, String type, String... columns) {
        return IndexInfo.builder().name(name).type(type).columns(List.of(columns)).build();
    }
}