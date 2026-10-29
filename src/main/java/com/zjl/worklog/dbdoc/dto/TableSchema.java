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
    /** 表名 */
    private String tableName;
    /** 表注释 */
    private String tableComment;
    /** 列信息 */
    private List<ColumnInfo> columns;
    /** 索引信息 */
    private List<IndexInfo> indexes;
}
