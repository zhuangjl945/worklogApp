package com.zjl.worklog.dbdoc.mapper;

import com.zjl.worklog.dbdoc.entity.DbDocEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 数据库文档 Mapper
 */
@Mapper
public interface DbDocMapper {

    /** 插入文档记录，自增主键回填到 entity.id */
    int insert(DbDocEntity entity);

    /** 按 ID 查询单条 */
    DbDocEntity selectById(@Param("id") Long id);

    /** 分页查询（按创建时间倒序） */
    List<DbDocEntity> selectPage(@Param("keyword") String keyword,
                                 @Param("offset") int offset,
                                 @Param("limit") int limit);

    /** 按关键字统计总数 */
    int countByKeyword(@Param("keyword") String keyword);

    /** 按 ID 删除 */
    int deleteById(@Param("id") Long id);
}
