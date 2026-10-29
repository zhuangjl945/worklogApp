package com.zjl.worklog.dbdoc.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 索引信息 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexInfo {
    /** 索引名 */
    private String name;
    /** 索引类型：PRIMARY / UNIQUE / INDEX */
    private String type;
    /** 索引列 */
    private List<String> columns;
}
