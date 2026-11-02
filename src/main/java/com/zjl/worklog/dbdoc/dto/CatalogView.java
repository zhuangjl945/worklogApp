package com.zjl.worklog.dbdoc.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 表结构目录树：系统 → 数据库用户 → 该用户下的表名清单（懒加载，一次只展开一层）。
 */
@Data
public class CatalogView {

    /** 系统节点列表 */
    private List<SystemNode> systems = new ArrayList<>();
    /** 全部表张数，页面顶部概览 */
    private int totalTables;
    /** 全部数据库用户个数 */
    private int totalDatabases;

    /** 系统节点 */
    @Data
    public static class SystemNode {
        private Long id;
        private String systemName;
        private String systemCode;
        private String dbType;
        private Integer tableCount;
        private List<DatabaseNode> databases = new ArrayList<>();
    }

    /** 数据库用户节点 */
    @Data
    public static class DatabaseNode {
        private Long id;
        private Long systemId;
        private String dbUser;
        private String dbName;
        private String env;
        private String dbType;
        private String host;
        private Integer tableCount;
    }
}