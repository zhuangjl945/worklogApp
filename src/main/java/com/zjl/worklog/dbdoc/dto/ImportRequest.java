package com.zjl.worklog.dbdoc.dto;

import lombok.Data;

import java.util.List;

/**
 * 导入（解析并合并进表结构目录）请求。
 * 三种定位方式，从精确到省事依次是：直接给 databaseId → 给 systemId + dbUser → 只给 systemName/dbUser 让后端建。
 */
@Data
public class ImportRequest {

    /** 目标系统 ID，可空（配合 systemName 自动建系统） */
    private Long systemId;
    /** 目标系统名称，systemId 为空时用它查找/新建 */
    private String systemName;
    /** 目标数据库用户记录 ID，可空 */
    private Long databaseId;
    /** 数据库用户/模式名，databaseId 为空时按 (系统 + 用户 + 环境) 查找，找不到就新建 */
    private String dbUser;
    /** 库名/SID，随新建库一起落库 */
    private String dbName;
    /** 数据库类型：mysql/oracle/... */
    private String dbType;
    /** 数据库地址 */
    private String host;
    /** 端口 */
    private Integer port;
    /** 环境：prod/test/uat/dev，默认 prod */
    private String env;
    /** 批次标题，留空由系统名 + 库用户自动生成 */
    private String title;
    /**
     * 原始 SQL 脚本。
     * 与 tables 二选一：贴脚本报 DDL 来源，走 tables 的是已经整理好的结构（Excel 数据字典等）。
     * 两个都空由服务层给出提示，不在这里用注解卡死，否则 Excel 来源进不来。
     */
    private String sql;
    /**
     * 结构化表清单（Excel 数据字典、直连采集等非脚本来源）。
     * 非空时不再解析 SQL，直接按同一套增量合并写进目录。
     */
    private List<TableSchema> tables;
    /** 来源标记：DDL / EXCEL / SCAN，留空由服务端按入参自动判定 */
    private String sourceType;
    /** 来源说明，例如 Excel 文件名与工作表，只用于事后追溯 */
    private String sourceRef;
    /**
     * 合并方式：
     * MERGE（默认）只做增量新增与变更，不改动本次脚本里没出现的表；
     * REPLACE 额外把本次没出现、且属于同一库用户的表标记为「疑似下线」，不物理删除。
     */
    private String mode;
    /** 脚本里出现「别的库用户前缀」时，是否自动为它在同系统下建目录（默认 true） */
    private Boolean autoCreateDatabase;
}