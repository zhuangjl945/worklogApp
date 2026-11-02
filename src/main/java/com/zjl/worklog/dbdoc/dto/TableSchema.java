package com.zjl.worklog.dbdoc.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 表结构信息 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableSchema {
    /** 表名（不含模式/用户前缀） */
    private String tableName;
    /**
     * 脚本里写明的数据库用户/模式名，取自「USER.TABLE」这种限定名的前缀。
     * 没写前缀时为 null，导入时归到本次选定的库用户名下。
     */
    private String dbUser;
    /** 表注释 */
    private String tableComment;
    /** 列信息 */
    private List<ColumnInfo> columns;
    /** 索引信息 */
    private List<IndexInfo> indexes;
}