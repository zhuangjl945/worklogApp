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
    @DisplayName("被截断的 SQL 不抛异常，只给警告")
    void testTruncatedSqlOnlyWarns() {
        ParseResult result = assertDoesNotThrow(() -> sqlParseService.parseDdl("CREATE TABLE invalid ("));
        assertTrue(result.getTables().isEmpty());
        assertFalse(result.getWarnings().isEmpty(), "应给出无法解析的警告");
    }

    @Test
    @DisplayName("一条语句写坏不影响其它表的解析")
    void testBrokenStatementDoesNotBlockOthers() {
        String sql = """
                CREATE TABLE `ok1` (`id` int NOT NULL, PRIMARY KEY (`id`));
                CREATE TABLE `broken` (`id` int NOT NULL, weird @@@ syntax here);
                CREATE TABLE `ok2` (`id` int NOT NULL, `n` varchar(10) DEFAULT 'x' COMMENT '备注');
                """;

        ParseResult result = sqlParseService.parseDdl(sql);

        // 三条都能出结果：坏的那条由容错解析器兜底
        assertEquals(2, result.getTables().stream()
                .filter(t -> t.getTableName().startsWith("ok")).count());
        assertEquals(3, result.getTables().size());
    }

    @Test
    @DisplayName("Oracle 导出脚本：DROP PRIMARY KEY CASCADE、以斜杠结尾的建表、COMMENT ON")
    void testOracleExportScript() {
        String sql = """
                ALTER TABLE WDHS.CIS_DIC_EXEC_FREQ
                  DROP PRIMARY KEY CASCADE;

                DROP TABLE WDHS.CIS_DIC_EXEC_FREQ CASCADE CONSTRAINTS;

                CREATE TABLE WDHS.CIS_DIC_EXEC_FREQ
                (
                  ID           NUMBER(8)                      NOT NULL,
                  BRANCH_CODE  VARCHAR2(10 BYTE)              NOT NULL,
                  CODE         VARCHAR2(8 BYTE),
                  CREATE_TIME  DATE DEFAULT SYSDATE,
                  DEL_FLAG     NUMBER(1) DEFAULT 0
                )
                TABLESPACE WDHS_DATA
                PCTFREE 10 PCTUSED 40 INITRANS 1 MAXTRANS 255
                STORAGE(INITIAL 65536 NEXT 1048576 MINEXTENTS 1 MAXEXTENTS 2147483645)
                /

                COMMENT ON TABLE WDHS.CIS_DIC_EXEC_FREQ IS '执行频次字典';
                COMMENT ON COLUMN WDHS.CIS_DIC_EXEC_FREQ.ID IS '主键';
                COMMENT ON COLUMN WDHS.CIS_DIC_EXEC_FREQ.BRANCH_CODE IS '机构编码';
                """;

        ParseResult result = assertDoesNotThrow(() -> sqlParseService.parseDdl(sql));

        assertTrue(result.getWarnings().isEmpty(), "不应有警告：" + result.getWarnings());
        assertEquals(1, result.getTables().size());

        TableSchema table = result.getTables().get(0);
        // v2 起「模式.表名」会被拆开：表名不带前缀，前缀单独记在 dbUser 上，
        // 这样同一份导出脚本里的不同用户能各自归到自己的目录里。
        assertEquals("CIS_DIC_EXEC_FREQ", table.getTableName());
        assertEquals("WDHS", table.getDbUser());
        assertEquals("执行频次字典", table.getTableComment());
        assertEquals(5, table.getColumns().size());

        ColumnInfo id = table.getColumns().get(0);
        assertEquals("ID", id.getName());
        assertEquals("NUMBER", id.getType());
        assertEquals("8", id.getLength());
        assertFalse(id.isNullable());
        assertEquals("主键", id.getComment());

        // VARCHAR2(10 BYTE) 的长度只取数字
        ColumnInfo branch = table.getColumns().get(1);
        assertEquals("VARCHAR2", branch.getType());
        assertEquals("10", branch.getLength());
        assertEquals("机构编码", branch.getComment());

        ColumnInfo createTime = table.getColumns().get(3);
        assertEquals("SYSDATE", createTime.getDefaultValue());
    }

    @Test
    @DisplayName("MySQL 全文索引、空间索引、分区子句都能解析")
    void testMysqlFulltextSpatialPartition() {
        String sql = """
                CREATE TABLE `article` (
                  `id` bigint NOT NULL AUTO_INCREMENT,
                  `title` varchar(200) NOT NULL COMMENT '标题',
                  `body` mediumtext,
                  PRIMARY KEY (`id`),
                  FULLTEXT KEY `ft_title` (`title`,`body`),
                  SPATIAL KEY `sp_title` (`title`),
                  KEY `idx_title_len` (`title`(50))
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章表'
                PARTITION BY RANGE (`id`) (PARTITION p0 VALUES LESS THAN (1000));
                """;

        ParseResult result = assertDoesNotThrow(() -> sqlParseService.parseDdl(sql));
        assertTrue(result.getWarnings().isEmpty(), "不应有警告：" + result.getWarnings());

        TableSchema table = result.getTables().get(0);
        assertEquals("文章表", table.getTableComment());
        assertEquals(3, table.getColumns().size());
        assertEquals("标题", table.getColumns().get(1).getComment());

        List<String> types = table.getIndexes().stream().map(IndexInfo::getType).toList();
        assertTrue(types.contains("PRIMARY"), types.toString());
        assertTrue(types.contains("FULLTEXT"), types.toString());
        assertTrue(types.contains("SPATIAL"), types.toString());
    }

    @Test
    @DisplayName("外键与 CHECK 约束不会被当成索引")
    void testForeignKeyAndCheckAreNotIndexes() {
        String sql = """
                CREATE TABLE `sys_user` (
                  `id` int NOT NULL,
                  `dept_id` int DEFAULT NULL,
                  `age` int CHECK (`age` > 0),
                  PRIMARY KEY (`id`),
                  KEY `idx_dept` (`dept_id`),
                  CONSTRAINT `fk_user_dept` FOREIGN KEY (`dept_id`) REFERENCES `sys_dept` (`id`) ON DELETE SET NULL,
                  CHECK (`age` >= 0)
                );
                """;

        ParseResult result = sqlParseService.parseDdl(sql);
        TableSchema table = result.getTables().get(0);

        assertEquals(2, table.getIndexes().size(), table.getIndexes().toString());
        List<String> names = table.getIndexes().stream().map(IndexInfo::getName).toList();
        assertTrue(names.contains("PRIMARY"), names.toString());
        assertTrue(names.contains("idx_dept"), names.toString());
        // 索引列不能是 null 名字或空列
        table.getIndexes().forEach(idx -> {
            assertNotNull(idx.getName());
            assertFalse(idx.getName().isBlank());
            assertFalse(idx.getColumns().isEmpty(), idx.toString());
        });
    }

    @Test
    @DisplayName("SQL Server 的方括号标识符与 IDENTITY 能解析")
    void testSqlServerIdentity() {
        String sql = """
                CREATE TABLE [dbo].[Order] (
                    [Id] [int] IDENTITY(1,1) NOT NULL,
                    [No] [nvarchar](32) NOT NULL,
                    [Amount] [decimal](18, 2) NULL DEFAULT ((0)),
                    CONSTRAINT [PK_Order] PRIMARY KEY CLUSTERED ([Id] ASC)
                );
                """;

        ParseResult result = assertDoesNotThrow(() -> sqlParseService.parseDdl(sql));
        assertTrue(result.getWarnings().isEmpty(), "不应有警告：" + result.getWarnings());

        TableSchema table = result.getTables().get(0);
        assertEquals("Order", table.getTableName());
        assertEquals("dbo", table.getDbUser());
        assertEquals(3, table.getColumns().size());
        assertEquals("INT", table.getColumns().get(0).getType());
        assertTrue(table.getColumns().get(0).isPrimaryKey());
        assertEquals("NVARCHAR", table.getColumns().get(1).getType());
        assertEquals("32", table.getColumns().get(1).getLength());
        assertEquals("DECIMAL", table.getColumns().get(2).getType());
        assertEquals("18,2", table.getColumns().get(2).getLength());
    }

    @Test
    @DisplayName("ALTER 的 MODIFY / CHANGE / DROP 动作会作用到表结构上")
    void testAlterModifyChangeDrop() {
        String sql = """
                CREATE TABLE `t` (`id` int NOT NULL, `a` varchar(10), `b` int, PRIMARY KEY (`id`));
                ALTER TABLE `t` MODIFY COLUMN `a` varchar(50) NOT NULL COMMENT '改后注释';
                ALTER TABLE `t` CHANGE COLUMN `b` `c` bigint NOT NULL DEFAULT 1;
                ALTER TABLE `t` DROP COLUMN `a`;
                """;

        ParseResult result = sqlParseService.parseDdl(sql);
        TableSchema table = result.getTables().get(0);

        assertEquals(2, table.getColumns().size(), table.getColumns().toString());
        // 剩下的两列是 id 和改名后的 c
        ColumnInfo c = table.getColumns().get(1);
        assertEquals("c", c.getName());
        assertEquals("BIGINT", c.getType());
        assertFalse(c.isNullable());
        assertEquals("1", c.getDefaultValue());
    }

    @Test
    @DisplayName("只有注释、只有 INSERT、空输入都不报错")
    void testNoDdlInputWarnsInsteadOfThrowing() {
        assertDoesNotThrow(() -> sqlParseService.parseDdl(null));
        assertDoesNotThrow(() -> sqlParseService.parseDdl("   "));

        ParseResult commentOnly = sqlParseService.parseDdl("-- 只有注释\n/* 块注释 */\n");
        assertTrue(commentOnly.getTables().isEmpty());
        assertFalse(commentOnly.getWarnings().isEmpty());

        ParseResult insertOnly = sqlParseService.parseDdl("INSERT INTO a VALUES (1);");
        assertTrue(insertOnly.getTables().isEmpty());
        assertFalse(insertOnly.getWarnings().isEmpty());
    }

    @Test
    @DisplayName("BOM 头、DELIMITER 触发器脚本不影响建表解析")
    void testBomAndDelimiterScript() {
        String sql = "\ufeffDELIMITER ;;\n"
                + "CREATE TABLE `log_t` (`id` int NOT NULL, `msg` varchar(255), PRIMARY KEY (`id`));;\n"
                + "CREATE TRIGGER trg BEFORE INSERT ON `log_t` FOR EACH ROW BEGIN SET NEW.msg = 'x'; END;;\n"
                + "DELIMITER ;\n";

        ParseResult result = assertDoesNotThrow(() -> sqlParseService.parseDdl(sql));
        assertEquals(1, result.getTables().size());
        assertEquals("log_t", result.getTables().get(0).getTableName());
    }

    @Test
    @DisplayName("单独 CREATE INDEX 会归到对应表上")
    void testStandaloneCreateIndex() {
        String sql = """
                CREATE TABLE `dept` (`id` int NOT NULL, `name` varchar(100), PRIMARY KEY (`id`));
                CREATE INDEX `idx_name` ON `dept` (`name`);
                CREATE UNIQUE INDEX `idx_name2` ON `dept` (`name`);
                """;

        ParseResult result = sqlParseService.parseDdl(sql);
        TableSchema table = result.getTables().get(0);

        assertEquals(3, table.getIndexes().size(), table.getIndexes().toString());
        assertEquals("INDEX", table.getIndexes().get(1).getType());
        assertEquals("idx_name", table.getIndexes().get(1).getName());
        assertEquals("UNIQUE", table.getIndexes().get(2).getType());
    }

    @Test
    @DisplayName("带用户前缀的建表语句按用户归组，并回收到 dbUsers")
    void testQualifiedNamesCollected() {
        String sql = """
                CREATE TABLE HIS.PATIENT_INFO (
                    ID NUMBER(8) NOT NULL
                );
                CREATE TABLE LIS.SAMPLE_INFO (
                    ID NUMBER(8) NOT NULL
                );
                CREATE TABLE ORPHAN_TABLE (
                    ID NUMBER(8) NOT NULL
                );
                """;

        ParseResult result = assertDoesNotThrow(() -> sqlParseService.parseDdl(sql));

        assertEquals(3, result.getTables().size());
        // 前缀只切最后一段，剩下的整体当用户，双引号已经由 cleanName 去掉
        assertEquals("PATIENT_INFO", result.getTables().get(0).getTableName());
        assertEquals("HIS", result.getTables().get(0).getDbUser());
        assertEquals("SAMPLE_INFO", result.getTables().get(1).getTableName());
        assertEquals("LIS", result.getTables().get(1).getDbUser());
        assertEquals("ORPHAN_TABLE", result.getTables().get(2).getTableName());
        assertNull(result.getTables().get(2).getDbUser());
        assertEquals(java.util.Set.of("HIS", "LIS"), result.getDbUsers());
    }
}

