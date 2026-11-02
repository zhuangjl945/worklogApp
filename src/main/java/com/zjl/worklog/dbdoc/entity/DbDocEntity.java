package com.zjl.worklog.dbdoc.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据库文档记录实体
 */
@Data
public class DbDocEntity {

    /** 主键 */
    private Long id;
    /** 文档标题 */
    private String title;
    /**
     * 这批表结构从哪来：DDL 建表脚本 / EXCEL 数据字典 / SCAN 直连采集。
     * 老记录没有这一列，读出来按 DDL 处理，不影响展示。
     */
    private String sourceType;
    /** 来源说明，例如「检验表结构.xlsx / Sheet1（12 张表）」 */
    private String sourceRef;
    /** 原始 SQL 文本 */
    private String sourceSql;
    /** 解析后的 JSON 结构 */
    private String parsedJson;
    /** 包含表数量 */
    private Integer tableCount;
    /** 创建人 ID */
    private Long createdBy;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新时间 */
    private LocalDateTime updateTime;
    /** 所属系统 ID（v1 遗留记录为空，表示未归类） */
    private Long systemId;
    /** 所属数据库用户 ID */
    private Long databaseId;
    /** 数据库用户/模式名（冗余展示） */
    private String dbUser;
    /** 本批新增表张数 */
    private Integer addedCount;
    /** 本批结构变更表张数 */
    private Integer updatedCount;
    /** 本批未变化表张数 */
    private Integer unchangedCount;
    /** 本批未出现但目录里仍挂着的表张数 */
    private Integer missingCount;
    /** 创建人姓名（非数据库字段，列表联查展示） */
    private String createdByName;

    /** 所属系统名称（非数据库字段，列表联查展示） */
    private String systemName;
}
