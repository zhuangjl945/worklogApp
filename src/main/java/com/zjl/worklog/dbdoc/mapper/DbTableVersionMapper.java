package com.zjl.worklog.dbdoc.mapper;

import com.zjl.worklog.dbdoc.entity.DbTableVersionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 表结构历史版本 Mapper
 */
@Mapper
public interface DbTableVersionMapper {

    /** 写入一个版本快照 */
    int insert(DbTableVersionEntity entity);

    /** 某张表的版本列表（不含大 JSON，只要摘要） */
    List<DbTableVersionEntity> selectLiteByTableDocId(@Param("tableDocId") Long tableDocId);

    /** 单个版本全量快照 */
    DbTableVersionEntity selectById(@Param("id") Long id);

    /** 某批次的版本数（批次详情用） */
    int countByBatchId(@Param("batchId") Long batchId);

    /** 某批次涉及的表变更清单（不含大 JSON） */
    List<DbTableVersionEntity> selectLiteByBatchId(@Param("batchId") Long batchId);

    /** 删除表时一并清理历史 */
    int deleteByTableDocId(@Param("tableDocId") Long tableDocId);
}