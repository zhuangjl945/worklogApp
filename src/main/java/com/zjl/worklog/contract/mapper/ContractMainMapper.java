package com.zjl.worklog.contract.mapper;

import com.zjl.worklog.contract.entity.ContractMainEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ContractMainMapper {

    long count(
            @Param("contractNo") String contractNo,
            @Param("contractName") String contractName,
            @Param("status") Integer status,
            @Param("contractDirection") Integer contractDirection,
            @Param("supplierId") Long supplierId,
            @Param("managerId") Long managerId,
            @Param("deptId") Long deptId
    );

    List<ContractMainEntity> selectPage(
            @Param("offset") long offset,
            @Param("limit") long limit,
            @Param("contractNo") String contractNo,
            @Param("contractName") String contractName,
            @Param("status") Integer status,
            @Param("contractDirection") Integer contractDirection,
            @Param("supplierId") Long supplierId,
            @Param("managerId") Long managerId,
            @Param("deptId") Long deptId
    );

    ContractMainEntity selectById(@Param("id") Long id);

    ContractMainEntity selectByContractNo(@Param("contractNo") String contractNo);

    void insert(ContractMainEntity entity);

    int update(ContractMainEntity entity);

    int updateStatus(@Param("id") Long id, @Param("status") Integer status, @Param("updatedBy") Long updatedBy);

    int markDeleted(@Param("id") Long id, @Param("updatedBy") Long updatedBy);

    List<ContractMainEntity> selectExpiring(@Param("days") int days, @Param("status") Integer status);

    List<ContractMainEntity> selectExpiringWithDept(@Param("days") int days, @Param("status") Integer status, @Param("deptId") Long deptId);

    int markExpiredBefore(@Param("date") LocalDate date, @Param("updatedBy") Long updatedBy);
}
