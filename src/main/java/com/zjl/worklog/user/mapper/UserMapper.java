package com.zjl.worklog.user.mapper;

import com.zjl.worklog.user.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserMapper {

    UserEntity selectByUsername(@Param("username") String username);

    UserEntity selectById(@Param("id") Long id);

    long count(@Param("username") String username,
               @Param("realName") String realName,
               @Param("deptId") Long deptId,
               @Param("deptIds") List<Long> deptIds,
               @Param("status") Integer status);

    List<UserEntity> selectPage(@Param("offset") long offset,
                               @Param("limit") long limit,
                               @Param("username") String username,
                               @Param("realName") String realName,
                               @Param("deptId") Long deptId,
                               @Param("deptIds") List<Long> deptIds,
                               @Param("status") Integer status);

    int insert(UserEntity entity);

    int update(UserEntity entity);

    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    int updatePassword(@Param("id") Long id, @Param("password") String password);
}
