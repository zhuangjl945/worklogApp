package com.zjl.worklog.credential.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 凭据保管库实体
 */
@Data
public class CredentialEntity {

    /** 主键 */
    private Long id;

    /** 凭据名称 */
    private String name;

    /** 分类：database/server/app/network/other */
    private String category;

    /** 账号（明文） */
    private String username;

    /** 密码（AES-256-GCM 密文） */
    private String passwordEncrypted;

    /** 密钥（AES-256-GCM 密文） */
    private String secretKeyEncrypted;

    /** 访问地址 */
    private String url;

    /** 标签，逗号分隔 */
    private String tags;

    /** 备注 */
    private String remark;

    /** 过期日期 */
    private LocalDate expireDate;

    /** 创建人 ID */
    private Long createdBy;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
