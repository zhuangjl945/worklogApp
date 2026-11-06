package com.zjl.worklog.sqlsnippet.mapper;

import com.zjl.worklog.sqlsnippet.entity.SqlSnippet;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SqlSnippetMapper {

    int insert(SqlSnippet entity);

    SqlSnippet selectById(@Param("id") Long id, @Param("userId") Long userId);

    long count(@Param("userId") Long userId,
               @Param("keyword") String keyword,
               @Param("category") String category,
               @Param("dbType") String dbType);

    List<SqlSnippet> selectPage(@Param("userId") Long userId,
                                @Param("keyword") String keyword,
                                @Param("category") String category,
                                @Param("dbType") String dbType,
                                @Param("offset") long offset,
                                @Param("size") long size);

    List<String> selectCategories(@Param("userId") Long userId);

    int update(SqlSnippet entity);

    int deleteById(@Param("id") Long id, @Param("userId") Long userId);
}
