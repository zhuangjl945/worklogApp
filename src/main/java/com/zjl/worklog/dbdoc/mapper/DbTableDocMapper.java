package com.zjl.worklog.dbdoc.mapper;

import com.zjl.worklog.dbdoc.entity.DbTableDocEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 表结构目录 Mapper。
 * 列表查询一律不取 columns_json / indexes_json / source_ddl 这类大字段，
 * 详情才取全量，避免一页十几张表把内存和带宽吃光。
 */
@Mapper
public interface DbTableDocMapper {

    /** 新增，自增主键回填 */
    int insert(DbTableDocEntity entity);

    /** 结构变更时全量刷新当前快照 */
    int updateStructure(DbTableDocEntity entity);

    /**
     * 人工修订当前结构：连表名一起改，但 parse_count / 批次归属 / 在用状态一律不动。
     * 手工在页面上改一次不等于「这张表又被脚本分析了一次」，两件事得分开记。
     */
    int updateManualStructure(DbTableDocEntity entity);

    /** 结构没变：只累加被分析次数、把疑似下线的表拉回在用 */
    int touch(@Param("id") Long id, @Param("updatedBy") Long updatedBy);

    /** 仅更新人工维护字段（备注、状态） */
    int updateManualFields(DbTableDocEntity entity);

    /** 按 ID 查询全量（含 JSON 大字段） */
    DbTableDocEntity selectById(@Param("id") Long id);

    /** 按「数据库用户 + 表名」查全量，增量合并时的比对基准 */
    DbTableDocEntity selectFullByDatabaseAndName(@Param("databaseId") Long databaseId,
                                                 @Param("tableName") String tableName);

    /** 某库下的表名清单，用于算「本次脚本里没出现的既有表」 */
    List<String> selectNamesByDatabase(@Param("databaseId") Long databaseId);

    /** 某库下的表全量结构（ER 图用，表数量通常几十到几百，够用） */
    List<DbTableDocEntity> selectFullByDatabase(@Param("databaseId") Long databaseId,
                                                @Param("limit") int limit);

    /** 分页列表（轻量列） */
    List<DbTableDocEntity> selectPage(@Param("systemId") Long systemId,
                                      @Param("databaseId") Long databaseId,
                                      @Param("keyword") String keyword,
                                      @Param("columnKeyword") String columnKeyword,
                                      @Param("status") Integer status,
                                      @Param("offset") int offset,
                                      @Param("limit") int limit);

    /** 分页总数 */
    int countPage(@Param("systemId") Long systemId,
                  @Param("databaseId") Long databaseId,
                  @Param("keyword") String keyword,
                  @Param("columnKeyword") String columnKeyword,
                  @Param("status") Integer status);

    /** 某系统下的表数量，删除系统前的守卫 */
    int countBySystem(@Param("systemId") Long systemId);

    /** 某库用户的表清单（只取 id/表名/状态），导入时算「本次没出现的既有表」 */
    List<DbTableDocEntity> selectIdNameByDatabase(@Param("databaseId") Long databaseId);

    /** REPLACE 模式专用：把本次脚本里没出现的表批量标记为「疑似下线」，不物理删除 */
    int markOfflineByIds(@Param("ids") List<Long> ids, @Param("updatedBy") Long updatedBy);

    /** db_database.db_user 改名后，同步表目录里的冗余列 */
    int renameDbUser(@Param("databaseId") Long databaseId, @Param("dbUser") String dbUser);

    /** 按 ID 删除 */
    int deleteById(@Param("id") Long id);
}