package com.zjl.worklog.supplier.mapper;

import com.zjl.worklog.supplier.entity.SupplierMainEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SupplierMainMapper {

    long count(
            @Param("supplierCode") String supplierCode,
            @Param("supplierName") String supplierName,
            @Param("deleted") Integer deleted
    );

    List<SupplierMainEntity> selectPage(
            @Param("offset") long offset,
            @Param("limit") long limit,
            @Param("supplierCode") String supplierCode,
            @Param("supplierName") String supplierName,
            @Param("deleted") Integer deleted
    );

    SupplierMainEntity selectById(@Param("id") Long id);

    SupplierMainEntity selectBySupplierCode(@Param("supplierCode") String supplierCode);

    void insert(SupplierMainEntity entity);

    int update(SupplierMainEntity entity);

    int disableById(@Param("id") Long id, @Param("updatedBy") Long updatedBy);

    int enableById(@Param("id") Long id, @Param("updatedBy") Long updatedBy);

    String selectMaxSupplierCode();
}
