package com.zjl.worklog.contract;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ContractMapperXmlTest {

    private static Configuration parse() {
        Configuration configuration = new Configuration();
        String resource = "mapper/ContractMainMapper.xml";
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "找不到映射文件：" + resource);
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (Exception e) {
            fail("解析 " + resource + " 失败: " + e.getMessage(), e);
        }
        return configuration;
    }

    @Test
    @DisplayName("到期查询与过期扫描都排除长期合同")
    void expiringSqlSkipsLongTerm() {
        Configuration configuration = parse();
        String expiring = configuration.getMappedStatement("com.zjl.worklog.contract.mapper.ContractMainMapper.selectExpiring")
                .getBoundSql(new HashMap<String, Object>())
                .getSql();
        assertTrue(expiring.contains("is_long_term"), "到期查询必须排除长期合同：" + expiring);
        assertTrue(expiring.contains("end_date IS NOT NULL"), "到期查询不能把无止期合同算进去：" + expiring);

        String expired = configuration.getMappedStatement("com.zjl.worklog.contract.mapper.ContractMainMapper.markExpiredBefore")
                .getBoundSql(new HashMap<String, Object>())
                .getSql();
        assertTrue(expired.contains("is_long_term"), "过期扫描必须排除长期合同：" + expired);
        assertTrue(expired.contains("end_date IS NOT NULL"), "过期扫描不能把无止期合同标过期：" + expired);
    }
}
