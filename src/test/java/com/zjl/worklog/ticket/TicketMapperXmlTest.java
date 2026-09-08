package com.zjl.worklog.ticket;

import com.zjl.worklog.ticket.mapper.ServiceTicketLogMapper;
import com.zjl.worklog.ticket.mapper.ServiceTicketMapper;
import com.zjl.worklog.ticket.mapper.TicketChannelMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * MyBatis 映射文件自检。
 *
 * <p>价值在于：XML 写错（少一个 id、include 引了不存在的片段、标签没闭合）编译器完全发现不了，
 * 只会在生产启动或第一次调用时才炸。这里把「接口方法都能找到对应语句」变成一条构建期断言。
 */
class TicketMapperXmlTest {

    private static Configuration parseAll() {
        Configuration configuration = new Configuration();
        List<String> resources = List.of(
                "mapper/ServiceTicketMapper.xml",
                "mapper/ServiceTicketLogMapper.xml",
                "mapper/TicketChannelMapper.xml",
                "mapper/WorkRecordMapper.xml",
                "mapper/UserMapper.xml"
        );
        for (String resource : resources) {
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
    @DisplayName("新增的三个映射文件能被 MyBatis 正常解析")
    void mapperXmlParses() {
        parseAll();
    }

    @Test
    @DisplayName("接口里的每个方法都有对应的 SQL 语句，避免运行期 BindingException")
    void everyMapperMethodHasStatement() {
        Configuration configuration = parseAll();
        for (Class<?> mapper : List.of(ServiceTicketMapper.class, ServiceTicketLogMapper.class, TicketChannelMapper.class)) {
            String namespace = mapper.getName();
            for (Method method : mapper.getDeclaredMethods()) {
                String id = namespace + "." + method.getName();
                assertTrue(configuration.hasStatement(id, false), "缺少语句：" + id);
            }
        }
    }

    @Test
    @DisplayName("受理时写入工作记录的来源列存在于 insert 语句中")
    void workRecordInsertCarriesSourceColumns() {
        Configuration configuration = parseAll();
        String sql = configuration.getMappedStatement("com.zjl.worklog.work.mapper.WorkRecordMapper.insert")
                .getBoundSql(new java.util.HashMap<String, Object>())
                .getSql();
        // 只要 SQL 里出现这两列，就说明工单转工作记录不会把来源丢掉
        assertTrue(sql.contains("source_type"), "work_record insert 缺少 source_type：" + sql);
        assertTrue(sql.contains("source_id"), "work_record insert 缺少 source_id：" + sql);
    }
}
