package com.zjl.worklog.sqlsnippet;

import com.zjl.worklog.sqlsnippet.mapper.SqlSnippetMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 常用 SQL 的隔离条件写在映射文件里。这里确认每条读写都带创建人，而且不是可省略的 if。
 */
class SqlSnippetMapperXmlTest {

    @Test
    @DisplayName("映射文件能解析，接口方法都有语句")
    void everyMapperMethodHasStatement() {
        Configuration configuration = parse();
        for (Method method : SqlSnippetMapper.class.getDeclaredMethods()) {
            String id = SqlSnippetMapper.class.getName() + "." + method.getName();
            assertTrue(configuration.hasStatement(id, false), "缺少语句：" + id);
        }
    }

    @Test
    @DisplayName("查询、修改、删除都按创建人收口，关键字搜标题、SQL 和备注")
    void statementsAreOwnerScoped() throws Exception {
        String xml;
        try (InputStream in = resource()) {
            assertNotNull(in, "找不到映射文件");
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertFalse(xml.contains("userId != null"), "创建人条件不能写成可省略的 if：" + xml);
        assertFalse(xml.contains("createdBy != null"), "创建人条件不能写成可省略的 if：" + xml);
        assertTrue(xml.contains("created_by = #{userId}"), "按 id 查询和删除要带创建人：" + xml);
        assertTrue(xml.contains("created_by = #{createdBy}"), "更新要带创建人：" + xml);
        assertTrue(xml.contains("title LIKE"), "关键字要搜标题：" + xml);
        assertTrue(xml.contains("sql_text LIKE"), "关键字要搜 SQL：" + xml);
        assertTrue(xml.contains("remark LIKE"), "关键字要搜备注：" + xml);
        assertTrue(xml.contains("category LIKE"), "关键字要搜分类：" + xml);
        assertTrue(xml.contains("category = #{category}"), "列表要能按分类精确筛选：" + xml);
        assertTrue(xml.contains("db_type = #{dbType}"), "列表要能按数据库类型筛选：" + xml);
    }

    private static Configuration parse() {
        Configuration configuration = new Configuration();
        try (InputStream in = resource()) {
            assertNotNull(in, "找不到映射文件：mapper/SqlSnippetMapper.xml");
            String resource = "mapper/SqlSnippetMapper.xml";
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (Exception e) {
            fail("解析映射文件失败: " + e.getMessage(), e);
        }
        return configuration;
    }

    private static InputStream resource() {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream("mapper/SqlSnippetMapper.xml");
    }
}
