package com.zjl.worklog.dbdoc.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表结构目录实体 —— 一张表一行，是「当前最新结构」。
 * 重复分析同一张表时按增量合并：结构变了就更新本行并写一条历史版本，没变只累加 parse_count。
 */
@Data
public class DbTableDocEntity {

    /** 主键 */
    private Long id;
    /** 所属系统 ID */
    private Long systemId;
    /** 所属数据库用户 ID */
    private Long databaseId;
    /** 表名（不含模式前缀） */
    private String tableName;
    /** 表注释/业务含义 */
    private String tableComment;
    /** 数据库用户/模式名（冗余展示） */
    private String dbUser;
    /** 数据库类型（冗余展示） */
    private String dbType;
    /** 字段数量 */
    private Integer columnCount;
    /** 索引数量 */
    private Integer indexCount;
    /** 主键字段，逗号分隔 */
    private String pkColumns;
    /** 字段明细 JSON */
    private String columnsJson;
    /** 索引明细 JSON */
    private String indexesJson;
    /** 最近一次建表语句原文 */
    private String sourceDdl;
    /** 首次登记的批次 ID */
    private Long firstBatchId;
    /** 最近一次改变结构的批次 ID */
    private Long lastBatchId;
    /** 被分析过的次数 */
    private Integer parseCount;
    /** 人工补充说明 */
    private String remark;
    /** 状态：1-在用，0-疑似下线 */
    private Integer status;
    /** 创建人 ID */
    private Long createdBy;
    /** 最近更新人 ID */
    private Long updatedBy;
    /** 首次登记时间 */
    private LocalDateTime createTime;
    /** 最近结构变更时间 */
    private LocalDateTime updateTime;

    // ===== 非数据库字段 =====

    /** 所属系统名称 */
    private String systemName;
    /** 所属数据库用户描述（system.db_user@host/db_name） */
    private String databaseLabel;
}