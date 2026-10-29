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
}
