package com.zjl.worklog.dept.mapper;

import com.zjl.worklog.dept.entity.DeptEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DeptMapper {

    long count(@Param("deptCode") String deptCode,
               @Param("deptName") String deptName,
               @Param("parentId") Long parentId,
               @Param("status") Integer status);

    List<DeptEntity> selectPage(@Param("offset") long offset,
                               @Param("limit") long limit,
                               @Param("deptCode") String deptCode,
                               @Param("deptName") String deptName,
                               @Param("parentId") Long parentId,
                               @Param("status") Integer status);

    List<DeptEntity> selectAllEnabled();

    DeptEntity selectById(@Param("id") Long id);

    DeptEntity selectByDeptCode(@Param("deptCode") String deptCode);

    int insert(DeptEntity entity);

    int update(DeptEntity entity);

    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    int deleteById(@Param("id") Long id);

    long countChildren(@Param("parentId") Long parentId);

    long countUsersInDept(@Param("deptId") Long deptId);
}
