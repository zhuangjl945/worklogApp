package com.zjl.worklog.dbdoc;

import com.zjl.worklog.dbdoc.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQL DDL 解析服务测试
 */
class SqlParseServiceTest {

    private SqlParseService sqlParseService;

    @BeforeEach
    void setUp() {
        sqlParseService = new SqlParseService();
    }

    @Test
    @DisplayName("解析单条 CREATE TABLE - 含主键、唯一索引、COMMENT")
    void testParseSingleCreateTable() {
        String sql = """
                CREATE TABLE `user` (
                    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
                    `email` VARCHAR(255) DEFAULT NULL COMMENT '邮箱',
                    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态:0禁用1启用',
                    PRIMARY KEY (`id`),
                    UNIQUE KEY `uk_username` (`username`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';
                """;

        ParseResult result = sqlParseService.parseDdl(sql);

        // 验证表数量
        assertEquals(1, result.getTables().size());

        TableSchema table = result.getTables().get(0);
        // 验证表名和注释
        assertEquals("user", table.getTableName());
        assertEquals("用户表", table.getTableComment());

        // 验证列
        List<ColumnInfo> columns = table.getColumns();
        assertEquals(4, columns.size());

        // 验证 id 列
        ColumnInfo idCol = columns.get(0);
        assertEquals("id", idCol.getName());
        assertEquals("BIGINT", idCol.getType());
        assertNull(idCol.getLength());
        assertFalse(idCol.isNullable());
        assertTrue(idCol.isPrimaryKey());
        assertEquals("主键ID", idCol.getComment());

        // 验证 username 列
        ColumnInfo usernameCol = columns.get(1);
        assertEquals("username", usernameCol.getName());
        assertEquals("VARCHAR", usernameCol.getType());
        assertEquals("50", usernameCol.getLength());
        assertFalse(usernameCol.isNullable());
        assertEquals("用户名", usernameCol.getComment());

        // 验证 email 列（DEFAULT NULL）
        ColumnInfo emailCol = columns.get(2);
        assertEquals("email", emailCol.getName());
        assertEquals("VARCHAR", emailCol.getType());
        assertEquals("255", emailCol.getLength());
        assertTrue(emailCol.isNullable());
        assertEquals("NULL", emailCol.getDefaultValue());
        assertEquals("邮箱", emailCol.getComment());

        // 验证 status 列（含默认值）
        ColumnInfo statusCol = columns.get(3);
        assertEquals("status", statusCol.getName());
        assertEquals("TINYINT", statusCol.getType());
        assertFalse(statusCol.isNullable());
        assertEquals("1", statusCol.getDefaultValue());
        assertEquals("状态:0禁用1启用", statusCol.getComment());

        // 验证索引
        List<IndexInfo> indexes = table.getIndexes();
        assertEquals(2, indexes.size());

        // 验证主键索引
        IndexInfo pkIndex = indexes.get(0);
        assertEquals("PRIMARY", pkIndex.getType());
        assertEquals(List.of("id"), pkIndex.getColumns());

        // 验证唯一索引
        IndexInfo ukIndex = indexes.get(1);
        assertEquals("UNIQUE", ukIndex.getType());
        assertEquals("uk_username", ukIndex.getName());
        assertEquals(List.of("username"), ukIndex.getColumns());
    }

    @Test
    @DisplayName("解析多条 CREATE TABLE")
    void testParseMultipleCreateTables() {
        String sql = """
                CREATE TABLE `dept` (
                    `id` INT NOT NULL AUTO_INCREMENT,
                    `name` VARCHAR(100) NOT NULL,
                    PRIMARY KEY (`id`)
                ) COMMENT='部门表';

                CREATE TABLE `role` (
                    `id` INT NOT NULL AUTO_INCREMENT,
                    `name` VARCHAR(50) NOT NULL,
                    PRIMARY KEY (`id`)
                ) COMMENT='角色表';
                """;

        ParseResult result = sqlParseService.parseDdl(sql);

        // 验证解析出两张表
        assertEquals(2, result.getTables().size());

        // 验证第一张表
        TableSchema deptTable = result.getTables().get(0);
        assertEquals("dept", deptTable.getTableName());
        assertEquals("部门表", deptTable.getTableComment());
        assertEquals(2, deptTable.getColumns().size());

        // 验证第二张表
        TableSchema roleTable = result.getTables().get(1);
        assertEquals("role", roleTable.getTableName());
        assertEquals("角色表", roleTable.getTableComment());
        assertEquals(2, roleTable.getColumns().size());
    }

    @Test
    @DisplayName("ALTER TABLE ADD COLUMN 合并到已有表")
    void testAlterTableAddColumn() {
        String sql = """
                CREATE TABLE `user` (
                    `id` BIGINT NOT NULL AUTO_INCREMENT,
                    `username` VARCHAR(50) NOT NULL,
                    PRIMARY KEY (`id`)
                ) COMMENT='用户表';

                ALTER TABLE `user` ADD COLUMN `phone` VARCHAR(20) DEFAULT NULL COMMENT '手机号';
                """;

        ParseResult result = sqlParseService.parseDdl(sql);

        // 验证仍然只有一张表
        assertEquals(1, result.getTables().size());

        TableSchema table = result.getTables().get(0);
        assertEquals("user", table.getTableName());

        // 验证列数增加
        assertEquals(3, table.getColumns().size());

        // 验证新增的列
        ColumnInfo phoneCol = table.getColumns().get(2);
        assertEquals("phone", phoneCol.getName());
        assertEquals("VARCHAR", phoneCol.getType());
        assertEquals("20", phoneCol.getLength());
        assertTrue(phoneCol.isNullable());
        assertEquals("NULL", phoneCol.getDefaultValue());
        assertEquals("手机号", phoneCol.getComment());
    }

    @Test
    @DisplayName("无效 SQL 抛出异常")
    void testInvalidSqlThrowsException() {
        String sql = "CREATE TABLE invalid (";

        assertThrows(RuntimeException.class, () -> sqlParseService.parseDdl(sql));
    }
}
