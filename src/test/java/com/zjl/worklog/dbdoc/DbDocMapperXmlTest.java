package com.zjl.worklog.dbdoc;

import com.zjl.worklog.dbdoc.mapper.DbDatabaseMapper;
import com.zjl.worklog.dbdoc.mapper.DbDocMapper;
import com.zjl.worklog.dbdoc.mapper.DbSystemMapper;
import com.zjl.worklog.dbdoc.mapper.DbTableDocMapper;
import com.zjl.worklog.dbdoc.mapper.DbTableVersionMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 表结构目录这组映射文件的自检。
 *
 * <p>XML 写错（少一个 id、include 引了不存在的片段、标签没闭合）编译器完全发现不了，
 * 只会在真正调用那一刻才炸；人工修订又是这次新加的路径，所以这里把
 * 「接口方法都能找到语句」和「人工修订那条 UPDATE 的口径」都变成构建期断言。
 */
class DbDocMapperXmlTest {

    private static final List<String> RESOURCES = List.of(
            "mapper/DbSystemMapper.xml",
            "mapper/DbDatabaseMapper.xml",
            "mapper/DbTableDocMapper.xml",
            "mapper/DbTableVersionMapper.xml",
            "mapper/DbDocMapper.xml"
    );

    private Configuration parseAll() {
        Configuration configuration = new Configuration();
        for (String resource : RESOURCES) {
            try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
                assertNotNull(in, "找不到映射文件：" + resource);
                new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
            } catch (Exception e) {
                fail("解析 " + resource + " 失败: " + e.getMessage(), e);
            }
        }
        return configuration;
    }

    @Test
    @DisplayName("目录这组映射文件能被 MyBatis 正常解析，接口方法都有对应语句")
    void everyMapperMethodHasStatement() {
        Configuration configuration = parseAll();
        for (Class<?> mapper : List.of(DbSystemMapper.class, DbDatabaseMapper.class,
                DbTableDocMapper.class, DbTableVersionMapper.class, DbDocMapper.class)) {
            for (Method method : mapper.getDeclaredMethods()) {
                String id = mapper.getName() + "." + method.getName();
                assertTrue(configuration.hasStatement(id, false), "缺少语句：" + id);
            }
        }
    }

    @Test
    @DisplayName("人工修订用的 UPDATE 改表名与结构，但不碰分析次数、批次归属和在用状态")
    void manualStructureUpdateKeepsImportOnlyColumns() {
        Configuration configuration = parseAll();
        String sql = configuration.getMappedStatement("com.zjl.worklog.dbdoc.mapper.DbTableDocMapper.updateManualStructure")
                .getBoundSql(new HashMap<String, Object>())
                .getSql();

        // 人工修订要能把改错的表名一起改掉，这是它和导入用的那条 UPDATE 最大的区别
        assertTrue(sql.contains("table_name"), "人工修订应能改表名：" + sql);
        assertTrue(sql.contains("columns_json") && sql.contains("indexes_json"), "人工修订要能覆盖字段与索引：" + sql);
        // 这三列归导入语义管：手工改一次不等于「又被脚本分析了一次」，
        // 也不该因为有人动了结构就把对账得出的「疑似下线」悄悄拉回在用。
        assertFalse(sql.contains("parse_count"), "人工修订不该累加分析次数：" + sql);
        assertFalse(sql.contains("last_batch_id"), "人工修订不该动批次归属：" + sql);
        assertFalse(sql.contains("first_batch_id"), "人工修订不该动批次归属：" + sql);
        assertFalse(sql.contains("status"), "人工修订不该改在用/疑似下线状态：" + sql);
    }

    @Test
    @DisplayName("历史版本语句仍带 batch_id，人工修订留的版本靠它区分「不是脚本导的」")
    void versionInsertKeepsBatchIdNullable() {
        Configuration configuration = parseAll();
        String sql = configuration.getMappedStatement("com.zjl.worklog.dbdoc.mapper.DbTableVersionMapper.insert")
                .getBoundSql(new HashMap<String, Object>())
                .getSql();
        assertTrue(sql.contains("batch_id"), "版本插入应保留 batch_id（人工修订时写入 NULL）：" + sql);
        assertTrue(sql.contains("change_type"), "版本插入应写处理类型：" + sql);
    }
}