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

    @Test
    @DisplayName("自动确认候选语句锁死「待报修人确认」状态，且带了条数上限")
    void autoConfirmCandidateQueryIsGuarded() {
        Configuration configuration = parseAll();
        String sql = configuration.getMappedStatement("com.zjl.worklog.ticket.mapper.ServiceTicketMapper.selectConfirmTimeoutIds")
                .getBoundSql(new java.util.HashMap<String, Object>())
                .getSql();
        // 丢了 status = 20 就会连处理中的工单一起替报修人确认掉，这是这条任务最危险的失败方式
        assertTrue(sql.contains("status = 20"), "自动确认候选没锁定待确认状态：" + sql);
        assertTrue(sql.contains("deleted = 0"), "自动确认候选漏了软删过滤：" + sql);
        // 丢了上限，「参数刚打开、积压一夜」就会一轮改动全库工单
        assertTrue(sql.toUpperCase().contains("LIMIT"), "自动确认候选缺少批量上限：" + sql);
        // 起点用完成时间，且要兜住完成时间为空的历史脏数据
        assertTrue(sql.contains("done_time"), "自动确认候选没有按完成时间筛选：" + sql);
        assertTrue(sql.contains("COALESCE"), "自动确认候选没有兜住 done_time 为空的数据：" + sql);
    }

    @Test
    @DisplayName("「只看超时」只捞仍待受理且过了最晚受理时刻的单")
    void overdueFilterIsAcceptDeadlineOnly() {
        String xml;
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("mapper/ServiceTicketMapper.xml")) {
            assertNotNull(in);
            xml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            fail(e);
            return;
        }
        int overdueAt = xml.indexOf("overdue != null");
        assertTrue(overdueAt > 0, "找不到 overdue 筛选片段");
        String fragment = xml.substring(overdueAt, overdueAt + 450);
        assertTrue(fragment.contains("status = 0"), "超时筛选必须锁死待受理：" + fragment);
        assertTrue(fragment.contains("accept_time IS NULL"), "超时筛选应排除已受理：" + fragment);
        assertTrue(!fragment.contains("status IN (0, 10, 20)"),
                "超时筛选不能再把处理中/待确认算进去：" + fragment);
    }
}
