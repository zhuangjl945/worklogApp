package com.zjl.worklog.work.mapper;

import com.zjl.worklog.work.entity.WorkCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WorkCategoryMapper {

    long count(@Param("deptId") Long deptId, @Param("status") Integer status);

    List<WorkCategory> selectPage(@Param("deptId") Long deptId,
                                 @Param("offset") long offset,
                                 @Param("size") long size,
                                 @Param("status") Integer status);

    List<WorkCategory> selectAllEnabled(@Param("deptId") Long deptId);

    WorkCategory selectById(@Param("id") Long id, @Param("deptId") Long deptId);

    WorkCategory selectByCode(@Param("categoryCode") String categoryCode, @Param("deptId") Long deptId);

    int insert(WorkCategory entity);

    int update(WorkCategory entity);

    int softDeleteById(@Param("id") Long id, @Param("deptId") Long deptId);
}

