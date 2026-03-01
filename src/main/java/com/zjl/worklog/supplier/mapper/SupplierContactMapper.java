package com.zjl.worklog.supplier.mapper;

import com.zjl.worklog.supplier.entity.SupplierContactEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SupplierContactMapper {

    List<SupplierContactEntity> selectBySupplierId(@Param("supplierId") Long supplierId);

    void insert(SupplierContactEntity entity);

    int deleteById(@Param("id") Long id);

    int deleteBySupplierId(@Param("supplierId") Long supplierId);
}
