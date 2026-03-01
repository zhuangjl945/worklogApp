package com.zjl.worklog.work.mapper;

import com.zjl.worklog.work.entity.WorkRecordTransfer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WorkRecordTransferMapper {

    int insert(WorkRecordTransfer entity);

    WorkRecordTransfer selectById(@Param("id") Long id);

    WorkRecordTransfer selectPendingByRecordId(@Param("workRecordId") Long workRecordId);

    List<WorkRecordTransfer> selectPendingByToUserId(@Param("toUserId") Long toUserId,
                                                     @Param("limit") Integer limit);

    long countPendingByToUserId(@Param("toUserId") Long toUserId);

    List<WorkRecordTransfer> selectByFromUserId(@Param("fromUserId") Long fromUserId,
                                                @Param("limit") Integer limit);

    int updateStatus(@Param("id") Long id,
                     @Param("toUserId") Long toUserId,
                     @Param("fromUserId") Long fromUserId,
                     @Param("fromStatus") Integer fromStatus,
                     @Param("toStatus") Integer toStatus,
                     @Param("reply") String reply);
}
