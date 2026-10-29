package com.zjl.worklog.dbdoc.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** SQL DDL 解析结果 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParseResult {
    /** 解析出的表列表 */
    private List<TableSchema> tables;
}
