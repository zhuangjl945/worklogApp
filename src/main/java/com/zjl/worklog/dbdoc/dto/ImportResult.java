package com.zjl.worklog.dbdoc.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 一次导入的结果汇总，前端据此提示「新增 3 张、变更 1 张、未变 20 张」 */
@Data
public class ImportResult {

    /** 本次分析批次 ID（db_doc.id） */
    private Long batchId;
    /** 归属系统 */
    private Long systemId;
    /** 归属系统名称 */
    private String systemName;
    /** 主归属数据库用户记录 ID */
    private Long databaseId;
    /** 主归属库用户名 */
    private String dbUser;
    /** 合并方式：MERGE / REPLACE */
    private String mode;
    /** 脚本解析出的表张数 */
    private int parsedCount;
    /** 目录新增 */
    private int addedCount;
    /** 结构变更 */
    private int updatedCount;
    /** 结构未变（只确认仍存在） */
    private int unchangedCount;
    /** 目录里有、本次脚本没有 */
    private int missingCount;
    /** 每张表的处理明细 */
    private List<TableChangeItem> changes = new ArrayList<>();
    /** 本次没出现的既有表名（最多列 50 个） */
    private List<String> missingTables = new ArrayList<>();
    /** 解析告警 */
    private List<String> warnings = new ArrayList<>();
}