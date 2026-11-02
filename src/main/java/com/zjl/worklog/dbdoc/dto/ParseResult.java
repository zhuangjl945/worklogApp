package com.zjl.worklog.dbdoc.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** SQL DDL 解析结果 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParseResult {
    /** 解析出的表列表 */
    private List<TableSchema> tables;
    /** 解析过程中的告警，例如某条语句暂不支持被跳过 */
    private List<String> warnings;
    /**
     * 脚本中以「USER.TABLE」形式出现的库用户/模式名集合。
     * 前端据此提示「这批脚本涉及 N 个用户」，导入时可以按用户自动建目录。
     */
    private Set<String> dbUsers;

    /** 兼容只传表结构的调用方式 */
    public ParseResult(List<TableSchema> tables) {
        this(tables, new ArrayList<>(), new LinkedHashSet<>());
    }

    /** 兼容「表 + 告警」两段式调用 */
    public ParseResult(List<TableSchema> tables, List<String> warnings) {
        this(tables, warnings, new LinkedHashSet<>());
    }
}