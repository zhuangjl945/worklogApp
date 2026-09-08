package com.zjl.worklog.ticket.mapper;

import com.zjl.worklog.ticket.entity.ServiceTicketEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ServiceTicketMapper {

    int insert(ServiceTicketEntity entity);

    /** 按主键查（不做用户隔离，科室边界由 TicketService 校验） */
    ServiceTicketEntity selectById(@Param("id") Long id);

    /** 按对外单号查，供手机端凭单号+令牌查进度 */
    ServiceTicketEntity selectByNo(@Param("ticketNo") String ticketNo);

    long count(@Param("deptId") Long deptId,
               @Param("statusList") List<Integer> statusList,
               @Param("categoryId") Long categoryId,
               @Param("assigneeId") Long assigneeId,
               @Param("keyword") String keyword,
               @Param("overdue") Boolean overdue,
               @Param("createTimeFrom") LocalDateTime createTimeFrom,
               @Param("createTimeTo") LocalDateTime createTimeTo);

    List<ServiceTicketEntity> selectPage(@Param("offset") long offset,
                                        @Param("size") long size,
                                        @Param("deptId") Long deptId,
                                        @Param("statusList") List<Integer> statusList,
                                        @Param("categoryId") Long categoryId,
                                        @Param("assigneeId") Long assigneeId,
                                        @Param("keyword") String keyword,
                                        @Param("overdue") Boolean overdue,
                                        @Param("createTimeFrom") LocalDateTime createTimeFrom,
                                        @Param("createTimeTo") LocalDateTime createTimeTo);

    /**
     * 带前置状态条件的更新（乐观并发）：只有当前状态仍在 expectedStatusList 内才会更新成功。
     * 返回 0 表示状态已被他人改变，调用方必须据此提示刷新，绝不能盲目成功。
     */
    int updateStatusCas(@Param("id") Long id,
                        @Param("expectedStatusList") List<Integer> expectedStatusList,
                        @Param("toStatus") Integer toStatus,
                        @Param("assigneeId") Long assigneeId,
                        @Param("assigneeName") String assigneeName,
                        @Param("acceptTime") LocalDateTime acceptTime,
                        @Param("doneTime") LocalDateTime doneTime,
                        @Param("closeTime") LocalDateTime closeTime);

    /** 回填受理时生成的工作记录ID */
    int bindWorkRecord(@Param("id") Long id, @Param("workRecordId") Long workRecordId);

    /** 报修人退回时清空完成时间，否则列表会继续显示上一次的处理完成时间 */
    int clearDoneTime(@Param("id") Long id);
    /** 报修人评价 */
    int updateRating(@Param("id") Long id,
                     @Param("rating") Integer rating,
                     @Param("ratingComment") String ratingComment);

    /** 单号序列：统计当天已生成的单号数量（并发冲突由 uk_ticket_no + 重试兜底） */
    long countByNoPrefix(@Param("prefix") String prefix);

    /** 渠道当日提交数，用于渠道日配额兜底（内存限流重启会清零，这条不会） */
    long countTodayByChannel(@Param("channelId") Long channelId);
}
