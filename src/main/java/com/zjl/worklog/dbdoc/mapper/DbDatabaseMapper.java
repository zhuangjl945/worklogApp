package com.zjl.worklog.dbdoc.mapper;

import com.zjl.worklog.dbdoc.entity.DbDatabaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 数据库用户/模式 Mapper —— 表文档的第二级归属。
 */
@Mapper
public interface DbDatabaseMapper {

    /** 新增，自增主键回填 */
    int insert(DbDatabaseEntity entity);

    /** 按 ID 更新可编辑字段 */
    int updateById(DbDatabaseEntity entity);

    /** 按 ID 删除 */
    int deleteById(@Param("id") Long id);

    /** 按 ID 查询（联查系统名） */
    DbDatabaseEntity selectById(@Param("id") Long id);

    /** 列表：按系统/关键字过滤，带真实表数与系统名 */
    List<DbDatabaseEntity> selectList(@Param("systemId") Long systemId,
                                      @Param("keyword") String keyword,
                                      @Param("status") Integer status);

    /** 某系统下的全部启用库（下拉用） */
    List<DbDatabaseEntity> selectBySystemId(@Param("systemId") Long systemId);

    /** 按 (系统, 库用户, 环境) 精确取一条，导入时用来复用已有目录 */
    DbDatabaseEntity selectByUk(@Param("systemId") Long systemId,
                                @Param("dbUser") String dbUser,
                                @Param("env") String env);

    /** (system_id, db_user, env) 查重，编辑时排除自身 */
    int countByUk(@Param("systemId") Long systemId,
                  @Param("dbUser") String dbUser,
                  @Param("env") String env,
                  @Param("excludeId") Long excludeId);

    /** 该库下登记的表数量，删除前的守卫检查 */
    int countTables(@Param("databaseId") Long databaseId);

    /** 重算并回填 table_count */
    int refreshTableCount(@Param("id") Long id);
}