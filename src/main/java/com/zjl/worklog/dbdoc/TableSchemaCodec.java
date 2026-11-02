package com.zjl.worklog.dbdoc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.dbdoc.dto.ColumnInfo;
import com.zjl.worklog.dbdoc.dto.IndexInfo;
import com.zjl.worklog.dbdoc.dto.TableSchema;
import com.zjl.worklog.dbdoc.entity.DbTableDocEntity;
import com.zjl.worklog.dbdoc.entity.DbTableVersionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 表结构与 JSON 大字段之间的翻译层。
 * db_table_doc / db_table_version 把字段明细、索引明细存成 JSON 文本，
 * 序列化口径集中在这里，免得两个 Service 各写一套 readValue 导致结构对不上。
 */
@Component
public class TableSchemaCodec {

    private static final Logger log = LoggerFactory.getLogger(TableSchemaCodec.class);

    private final ObjectMapper objectMapper;

    public TableSchemaCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 对象转 JSON 文本，失败即业务异常（不能静默丢结构） */
    public String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException(500, "结构序列化失败: " + e.getMessage());
        }
    }

    /** 字段明细反序列化，空文本给空列表 */
    public List<ColumnInfo> readColumns(String json) {
        return readList(json, new TypeReference<List<ColumnInfo>>() {
        });
    }

    /** 索引明细反序列化 */
    public List<IndexInfo> readIndexes(String json) {
        return readList(json, new TypeReference<List<IndexInfo>>() {
        });
    }

    /** 把目录行还原成一份 TableSchema，供差异比对使用 */
    public TableSchema toSchema(DbTableDocEntity row) {
        if (row == null) {
            return null;
        }
        return TableSchema.builder()
                .tableName(row.getTableName())
                .dbUser(row.getDbUser())
                .tableComment(row.getTableComment())
                .columns(readColumns(row.getColumnsJson()))
                .indexes(readIndexes(row.getIndexesJson()))
                .build();
    }

    /** 把历史版本行还原成一份 TableSchema */
    public TableSchema toSchema(DbTableVersionEntity version) {
        if (version == null) {
            return null;
        }
        return TableSchema.builder()
                .tableName(version.getTableName())
                .tableComment(version.getTableComment())
                .columns(readColumns(version.getColumnsJson()))
                .indexes(readIndexes(version.getIndexesJson()))
                .build();
    }

    /** 主键字段串：列表页直接显示，不用每次解析 JSON */
    public String pkColumns(List<IndexInfo> indexes) {
        if (indexes == null) {
            return "";
        }
        for (IndexInfo idx : indexes) {
            if (idx != null && "PRIMARY".equalsIgnoreCase(idx.getType()) && idx.getColumns() != null) {
                return String.join(", ", idx.getColumns());
            }
        }
        return "";
    }

    private <T> List<T> readList(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<T> list = objectMapper.readValue(json, type);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            // 历史脏数据不该让整个详情页 500：退化成空列表，但一定要留下痕迹，
            // 否则用户只会看到「这张表没有字段」，无从判断是被清空还是 JSON 写坏了。
            log.warn("表结构 JSON 反序列化失败，按空结构处理：{}", e.getMessage());
            return new ArrayList<>();
        }
    }
}