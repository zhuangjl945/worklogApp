package com.zjl.worklog.ticket.mapper;

import com.zjl.worklog.ticket.entity.TicketChannelEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TicketChannelMapper {

    int insert(TicketChannelEntity entity);

    int update(TicketChannelEntity entity);


    TicketChannelEntity selectById(@Param("id") Long id);

    /** 手机端只带渠道码，这是公开接口唯一的查库入口 */
    TicketChannelEntity selectByCode(@Param("channelCode") String channelCode);

    long count(@Param("deptId") Long deptId, @Param("status") Integer status);

    List<TicketChannelEntity> selectPage(@Param("offset") long offset,
                                        @Param("size") long size,
                                        @Param("deptId") Long deptId,
                                        @Param("status") Integer status);

    /** 同一科室下渠道名称查重（便于管理员分辨贴在哪里的码） */
    TicketChannelEntity selectByName(@Param("deptId") Long deptId, @Param("channelName") String channelName);
}
