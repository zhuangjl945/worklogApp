package com.zjl.worklog.dbdoc.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务系统实体 —— 数据库表文档的第一级归属。
 * 表结构必须挂在某个系统下，回答「这张表是哪个系统的」。
 */
@Data
public class DbSystemEntity {

    /** 主键 */
    private Long id;
    /** 系统名称，如「门诊住院信息系统」 */
    private String systemName;
    /** 系统编码，如 HIS，可空 */
    private String systemCode;
    /** 厂商 */
    private String vendor;
    /** 主流数据库类型：mysql/oracle/postgresql/sqlserver/dm/kingbase */
    private String dbType;
    /** 系统版本 */
    private String version;
    /** 系统负责人 */
    private String ownerName;
    /** 备注 */
    private String remark;
    /** 状态：1-在用，0-停用 */
    private Integer status;
    /** 排序号 */
    private Integer sortNo;
    /** 创建人 ID */
    private Long createdBy;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新时间 */
    private LocalDateTime updateTime;

    // ===== 以下为非数据库字段，列表接口顺带返回的统计 =====

    /** 该系统下的数据库用户个数 */
    private Integer databaseCount;
    /** 该系统下已登记的表个数 */
    private Integer tableCount;
}