package com.zjl.worklog.user.mapper;

import com.zjl.worklog.user.dto.UserRoleView;
import com.zjl.worklog.user.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserMapper {

    UserEntity selectByUsername(@Param("username") String username);

    UserEntity selectById(@Param("id") Long id);

    /**
     * 查询仍使用历史明文口令（无 {id} 前缀）的账号，供启动时批量升级为哈希。
     */
    List<UserEntity> selectNeedPasswordUpgrade();

    long count(@Param("username") String username,
               @Param("realName") String realName,
               @Param("deptId") Long deptId,
               @Param("deptIds") List<Long> deptIds,
               @Param("status") Integer status);

    List<UserEntity> selectPage(@Param("offset") long offset,
                               @Param("limit") long limit,
                               @Param("username") String username,
                               @Param("realName") String realName,
                               @Param("deptId") Long deptId,
                               @Param("deptIds") List<Long> deptIds,
                               @Param("status") Integer status);

    /** 统计指定角色且处于启用状态的人数 */
    long countByRole(@Param("role") String role);

    /**
     * 人员角色设置页专用：按角色/科室/关键字筛人并带出科室名。
     *
     * <p>不复用 selectPage，是因为这里必须带 dept_name（页面按科室看人），
     * 而且排序要按角色权重走，和工作记录那套分页条件不是一回事。
     */
    long countRolePage(@Param("role") String role,
                       @Param("deptId") Long deptId,
                       @Param("keyword") String keyword,
                       @Param("status") Integer status);

    List<UserRoleView> selectRolePage(@Param("offset") long offset,
                                      @Param("limit") long limit,
                                      @Param("role") String role,
                                      @Param("deptId") Long deptId,
                                      @Param("keyword") String keyword,
                                      @Param("status") Integer status);

    /** 只改角色：授予/收回角色时不顺带覆盖姓名、科室、状态 */
    int updateRole(@Param("id") Long id, @Param("role") String role);

    int insert(UserEntity entity);

    int update(UserEntity entity);

    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    int updatePassword(@Param("id") Long id, @Param("password") String password);
}
