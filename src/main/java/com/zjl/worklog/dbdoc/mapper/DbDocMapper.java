package com.zjl.worklog.dbdoc.mapper;

import com.zjl.worklog.dbdoc.entity.DbDocEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 分析批次 Mapper。
 * v2 之后 db_doc 表示「一次 SQL 分析导入」，表结构本体在 db_table_doc。
 */
@Mapper
public interface DbDocMapper {

    /** 插入批次记录，自增主键回填到 entity.id */
    int insert(DbDocEntity entity);

    /** 按 ID 查询单条（含大字段） */
    DbDocEntity selectById(@Param("id") Long id);

    /** 分页查询（按创建时间倒序，不取 source_sql / parsed_json 大字段） */
    List<DbDocEntity> selectPage(@Param("keyword") String keyword,
                                 @Param("systemId") Long systemId,
                                 @Param("databaseId") Long databaseId,
                                 @Param("offset") int offset,
                                 @Param("limit") int limit);

    /** 按条件统计总数 */
    int countByCondition(@Param("keyword") String keyword,
                         @Param("systemId") Long systemId,
                         @Param("databaseId") Long databaseId);

    /** 导入完成后回填差异统计 */
    int updateStats(@Param("id") Long id,
                    @Param("tableCount") int tableCount,
                    @Param("addedCount") int addedCount,
                    @Param("updatedCount") int updatedCount,
                    @Param("unchangedCount") int unchangedCount,
                    @Param("missingCount") int missingCount);

    /** 按 ID 删除 */
    int deleteById(@Param("id") Long id);
}