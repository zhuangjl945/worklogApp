package com.zjl.worklog.dbdoc.mapper;

import com.zjl.worklog.dbdoc.entity.DbSystemEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 业务系统 Mapper —— 表文档的第一级归属。
 */
@Mapper
public interface DbSystemMapper {

    /** 新增，自增主键回填到 entity.id */
    int insert(DbSystemEntity entity);

    /** 按 ID 更新可编辑字段 */
    int updateById(DbSystemEntity entity);

    /** 按 ID 删除 */
    int deleteById(@Param("id") Long id);

    /** 按 ID 查询 */
    DbSystemEntity selectById(@Param("id") Long id);

    /** 列表，带每个系统的库数/表数统计 */
    List<DbSystemEntity> selectList(@Param("keyword") String keyword, @Param("status") Integer status);

    /** 编码查重（编辑时排除自身），编码为空不参与查重 */
    int countByCode(@Param("systemCode") String systemCode, @Param("excludeId") Long excludeId);
}