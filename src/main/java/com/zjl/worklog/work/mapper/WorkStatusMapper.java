package com.zjl.worklog.work.mapper;

import com.zjl.worklog.work.entity.WorkStatus;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface WorkStatusMapper {
    List<WorkStatus> selectAllEnabled();
}
