package com.zjl.worklog.work.mapper;

import com.zjl.worklog.work.dto.WorkCategoryStat;
import com.zjl.worklog.work.dto.WorkCategorySummary;
import com.zjl.worklog.work.dto.WorkloadUserDeptCategoryStat;
import com.zjl.worklog.work.entity.WorkRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface WorkRecordMapper {

    long count(@Param("userId") Long userId,
               @Param("categoryId") Long categoryId,
               @Param("categoryIds") List<Long> categoryIds,
               @Param("statusIds") List<Integer> statusIds,
               @Param("title") String title,
               @Param("createTimeFrom") LocalDateTime createTimeFrom,
               @Param("createTimeTo") LocalDateTime createTimeTo);

    List<WorkRecord> selectPage(@Param("offset") long offset,
                               @Param("size") long size,
                               @Param("userId") Long userId,
                               @Param("categoryId") Long categoryId,
                               @Param("categoryIds") List<Long> categoryIds,
                               @Param("statusIds") List<Integer> statusIds,
                               @Param("title") String title,
                               @Param("createTimeFrom") LocalDateTime createTimeFrom,
                               @Param("createTimeTo") LocalDateTime createTimeTo);

    WorkRecord selectById(@Param("id") Long id,
                          @Param("userId") Long userId);

    List<WorkCategoryStat> statsByCategory(@Param("userId") Long userId,
                                           @Param("endTimeFrom") LocalDateTime endTimeFrom,
                                           @Param("endTimeTo") LocalDateTime endTimeTo);

    List<WorkCategorySummary> getCategorySummary(@Param("userId") Long userId,
                                                 @Param("startTimeFrom") LocalDateTime startTimeFrom,
                                                 @Param("startTimeTo") LocalDateTime startTimeTo);

    List<WorkloadUserDeptCategoryStat> statsUserDeptCategory(@Param("endTimeFrom") LocalDateTime endTimeFrom,
                                                            @Param("endTimeTo") LocalDateTime endTimeTo,
                                                            @Param("deptId") Long deptId,
                                                            @Param("userId") Long userId);

    int insert(WorkRecord entity);

    int update(WorkRecord entity);

    int softDelete(@Param("id") Long id,
                   @Param("userId") Long userId);

    int transferOwner(@Param("id") Long id,
                      @Param("fromUserId") Long fromUserId,
                      @Param("toUserId") Long toUserId,
                      @Param("toDeptId") Long toDeptId);
}
