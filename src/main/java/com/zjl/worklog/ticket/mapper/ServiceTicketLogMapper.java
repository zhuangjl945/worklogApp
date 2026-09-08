package com.zjl.worklog.ticket.mapper;

import com.zjl.worklog.ticket.entity.ServiceTicketLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ServiceTicketLogMapper {

    int insert(ServiceTicketLogEntity entity);

    /** 员工侧：拿全量流转记录（含仅内部可见的备注） */
    List<ServiceTicketLogEntity> selectByTicketId(@Param("ticketId") Long ticketId);

    /** 报修人侧：只能拿到对其可见的记录，避免内部备注外泄 */
    List<ServiceTicketLogEntity> selectVisibleByTicketId(@Param("ticketId") Long ticketId);
}
