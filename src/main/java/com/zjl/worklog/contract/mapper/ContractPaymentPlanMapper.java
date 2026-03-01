package com.zjl.worklog.contract.mapper;

import com.zjl.worklog.contract.entity.ContractPaymentPlanEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ContractPaymentPlanMapper {

    List<ContractPaymentPlanEntity> selectByContractId(@Param("contractId") Long contractId);

    ContractPaymentPlanEntity selectById(@Param("id") Long id);

    void insertBatch(@Param("plans") List<ContractPaymentPlanEntity> plans);

    int updateRecord(ContractPaymentPlanEntity entity);

    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    BigDecimal sumActualAmountByContractId(@Param("contractId") Long contractId);

    int markOverdueBefore(@Param("date") LocalDate date);

    List<com.zjl.worklog.contract.entity.ContractPaymentPlanEntity> selectExpiring(@Param("days") int days, @Param("status") Integer status);

    List<com.zjl.worklog.contract.entity.ContractPaymentPlanEntity> selectExpiringWithDept(@Param("days") int days, @Param("status") Integer status, @Param("deptId") Long deptId);
}
