package com.zjl.worklog.work.mapper;

import com.zjl.worklog.work.entity.WorkRecordLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WorkRecordLogMapper {
    List<WorkRecordLog> selectByWorkRecordId(@Param("workRecordId") Long workRecordId);

    WorkRecordLog selectById(@Param("id") Long id);

    int insert(WorkRecordLog entity);

    int update(WorkRecordLog entity);

    int deleteById(@Param("id") Long id);
}
