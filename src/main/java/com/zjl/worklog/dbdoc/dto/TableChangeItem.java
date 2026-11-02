package com.zjl.worklog.dbdoc.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 单张表在某次分析里的处理结论 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableChangeItem {
    /** 表名 */
    private String tableName;
    /** 该表归属的数据库用户/模式（脚本里没写前缀时取本次导入选定的用户） */
    private String dbUser;
    /** NEW-新增，CHANGED-结构变更，UNCHANGED-未变化，MISSING-本次脚本里没出现 */
    private String changeType;
    /** 差异摘要，UNCHANGED/MISSING 时可为空 */
    private String diffSummary;
    /** 目录里已有的字段数，新表为 0 */
    private Integer oldColumnCount;
    /** 本次脚本解析出的字段数 */
    private Integer newColumnCount;
    /** 表注释 */
    private String tableComment;
    /** 落到 db_table_doc 的哪一行（预览阶段为 null） */
    private Long tableDocId;
}