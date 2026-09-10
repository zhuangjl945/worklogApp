package com.zjl.worklog.config.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统参数配置实体
 */
@Data
public class SysConfigEntity {

    private Long id;
    private String configGroup;
    private String configKey;
    private String configValue;
    private String configLabel;
    private String configDesc;
    private Integer sortOrder;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
