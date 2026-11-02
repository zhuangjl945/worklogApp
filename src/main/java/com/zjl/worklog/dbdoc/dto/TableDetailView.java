package com.zjl.worklog.dbdoc.dto;

import com.zjl.worklog.dbdoc.entity.DbTableDocEntity;
import com.zjl.worklog.dbdoc.entity.DbTableVersionEntity;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 表详情：元信息 + 反序列化后的字段/索引 + 历史版本 + 可复制的建表语句 */
@Data
public class TableDetailView {

    /** 目录行（含系统名、库用户名等冗余展示字段） */
    private DbTableDocEntity table;
    /** 字段明细 */
    private List<ColumnInfo> columns = new ArrayList<>();
    /** 索引明细 */
    private List<IndexInfo> indexes = new ArrayList<>();
    /** 历史版本（摘要，倒序） */
    private List<DbTableVersionEntity> versions = new ArrayList<>();
    /** 由结构还原的建表语句，供直接复制使用 */
    private String renderedDdl;
}