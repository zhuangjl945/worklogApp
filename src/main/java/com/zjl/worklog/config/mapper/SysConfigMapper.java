package com.zjl.worklog.config.mapper;

import com.zjl.worklog.config.entity.SysConfigEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 系统参数 Mapper
 */
@Mapper
public interface SysConfigMapper {

    /** 查询所有参数，按分组 + 排序序号排列 */
    List<SysConfigEntity> selectAll();

    /** 按分组查询参数 */
    List<SysConfigEntity> selectByGroup(@Param("configGroup") String configGroup);

    /** 按分组 + 键名查询单条 */
    SysConfigEntity selectByKey(@Param("configGroup") String configGroup,
                                @Param("configKey") String configKey);

    /** 按 ID 查询 */
    SysConfigEntity selectById(@Param("id") Long id);

    /** 更新参数值 */
    int updateValue(@Param("id") Long id, @Param("configValue") String configValue);

    /** 插入新参数 */
    int insert(SysConfigEntity entity);

    /** 按 ID 删除 */
    int deleteById(@Param("id") Long id);
}
