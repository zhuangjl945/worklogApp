package com.zjl.worklog.sqlsnippet.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 个人常用 SQL。
 */
@Data
public class SqlSnippet {

    private Long id;
    private String title;
    /** 本人随手起的分类名，可空 */
    private String category;
    /** mysql / oracle / postgresql / sqlserver / dm / kingbase，可空 */
    private String dbType;
    private String sqlText;
    private String remark;
    private Long createdBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
