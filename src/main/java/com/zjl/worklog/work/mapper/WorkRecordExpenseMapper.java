package com.zjl.worklog.work.mapper;

import com.zjl.worklog.work.dto.WorkExpenseStat;
import com.zjl.worklog.work.entity.WorkRecordExpense;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WorkRecordExpenseMapper {
    List<WorkRecordExpense> selectByWorkRecordId(@Param("workRecordId") Long workRecordId);

    WorkRecordExpense selectById(@Param("id") Long id);

    List<WorkExpenseStat> statsByExpenseType(@Param("userId") Long userId,
                                             @Param("expenseTimeFrom") java.time.LocalDateTime expenseTimeFrom,
                                             @Param("expenseTimeTo") java.time.LocalDateTime expenseTimeTo);

    int insert(WorkRecordExpense entity);

    int update(WorkRecordExpense entity);

    int deleteById(@Param("id") Long id);
}
