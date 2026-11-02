package com.zjl.worklog.dbdoc.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表结构历史版本实体 —— 每次「新增/变更」留一份快照，用于回看结构是怎么一步步改的。
 */
@Data
public class DbTableVersionEntity {

    /** 主键 */
    private Long id;
    /** 对应的表 ID */
    private Long tableDocId;
    /** 产生本版本的分析批次 ID */
    private Long batchId;
    /** 所属系统 ID（冗余，表删了也能追溯） */
    private Long systemId;
    /** 所属数据库用户 ID（冗余） */
    private Long databaseId;
    /** 表名（冗余） */
    private String tableName;
    /** 本批处理类型：NEW/CHANGED/UNCHANGED */
    private String changeType;
    /** 本版本表注释 */
    private String tableComment;
    /** 本版本字段数量 */
    private Integer columnCount;
    /** 本版本字段明细 JSON */
    private String columnsJson;
    /** 本版本索引明细 JSON */
    private String indexesJson;
    /** 本版本建表语句原文 */
    private String sourceDdl;
    /** 与上一版的差异摘要 */
    private String diffSummary;
    /** 操作人 ID */
    private Long createdBy;
    /** 版本生成时间 */
    private LocalDateTime createTime;
}