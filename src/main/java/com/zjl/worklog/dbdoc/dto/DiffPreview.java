package com.zjl.worklog.dbdoc.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 导入前的差异预览：让用户先看清「会新增几张、会改哪几张」再落库 */
@Data
public class DiffPreview {

    /** 脚本解析出的表结构 */
    private List<TableSchema> tables = new ArrayList<>();
    /** 每张表相对现有目录的变化 */
    private List<TableChangeItem> changes = new ArrayList<>();
    /** 解析告警 */
    private List<String> warnings = new ArrayList<>();
    /** 脚本涉及到的数据库用户/模式（来自 USER.TABLE 限定名） */
    private List<String> scriptDbUsers = new ArrayList<>();
    /** 当前预览所对照的库用户目录（未选库时按空目录算，全部显示为新增） */
    private String againstDbUser;
    private int addedCount;
    private int changedCount;
    private int unchangedCount;
}