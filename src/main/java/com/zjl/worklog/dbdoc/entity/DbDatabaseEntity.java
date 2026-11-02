package com.zjl.worklog.dbdoc.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据库用户/模式实体 —— 数据库表文档的第二级归属。
 * 一个系统可以有多个库用户（Oracle 按 schema 拆、MySQL 按账号+库拆），
 * 回答「这张表在哪个用户下」。
 */
@Data
public class DbDatabaseEntity {

    /** 主键 */
    private Long id;
    /** 所属系统 ID */
    private Long systemId;
    /** 数据库用户/模式名，如 HIS_USER */
    private String dbUser;
    /** 库名/SID/服务名 */
    private String dbName;
    /** 数据库类型 */
    private String dbType;
    /** 数据库地址 */
    private String host;
    /** 端口 */
    private Integer port;
    /** 环境：prod/test/uat/dev */
    private String env;
    /** 连接串（不含口令） */
    private String jdbcUrl;
    /** 口令来源，逻辑关联 credential.id */
    private Long credentialId;
    /** 该用户下已登记的表数量 */
    private Integer tableCount;
    /** 备注 */
    private String remark;
    /** 状态：1-启用，0-停用 */
    private Integer status;
    /** 创建人 ID */
    private Long createdBy;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新时间 */
    private LocalDateTime updateTime;

    // ===== 非数据库字段 =====

    /** 所属系统名称，列表联查展示用 */
    private String systemName;
}