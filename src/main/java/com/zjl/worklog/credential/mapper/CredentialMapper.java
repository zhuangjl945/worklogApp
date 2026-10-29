package com.zjl.worklog.credential.mapper;

import com.zjl.worklog.credential.entity.CredentialEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 凭据保管库 Mapper 接口
 */
@Mapper
public interface CredentialMapper {

    /**
     * 新增凭据
     *
     * @param entity 凭据实体
     * @return 影响行数
     */
    int insert(CredentialEntity entity);

    /**
     * 根据 ID 查询凭据
     *
     * @param id 主键
     * @return 凭据实体
     */
    CredentialEntity selectById(@Param("id") Long id);

    /**
     * 分页查询凭据列表
     *
     * @param keyword  关键词（模糊匹配名称/账号/URL）
     * @param category 分类
     * @param offset   偏移量
     * @param limit    每页条数
     * @return 凭据列表
     */
    List<CredentialEntity> selectPage(
            @Param("keyword") String keyword,
            @Param("category") String category,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /**
     * 按条件统计凭据数量
     *
     * @param keyword  关键词
     * @param category 分类
     * @return 数量
     */
    int countByFilter(
            @Param("keyword") String keyword,
            @Param("category") String category);

    /**
     * 更新凭据
     *
     * @param entity 凭据实体
     */
    void update(CredentialEntity entity);

    /**
     * 根据 ID 删除凭据
     *
     * @param id 主键
     * @return 影响行数
     */
    int deleteById(@Param("id") Long id);

    /**
     * 查询即将过期的凭据
     *
     * @param days 天数范围（从今日起多少天内过期）
     * @return 即将过期的凭据列表
     */
    List<CredentialEntity> selectExpiringSoon(@Param("days") int days);
}
