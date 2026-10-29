package com.zjl.worklog.dbdoc.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 列（字段）信息 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnInfo {
    /** 字段名 */
    private String name;
    /** 数据类型，如 VARCHAR、INT */
    private String type;
    /** 长度，如 "255" 或 "10,2"，无则 null */
    private String length;
    /** 是否可为空 */
    private boolean nullable;
    /** 默认值 */
    private String defaultValue;
    /** 注释 */
    private String comment;
    /** 是否主键 */
    private boolean primaryKey;
}
