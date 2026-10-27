package com.oj.teaching.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oj.teaching.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 系统用户 Mapper（表 {@code sys_user}）。
 *
 * <p>继承 {@link BaseMapper} 拿到按主键/条件的标准 CRUD；下面两个自定义语句
 * 只做「用标准 CRUD 表达不了」的事：按唯一账号查、以及只更新登录时间一列。</p>
 *
 * @author oj-cloud
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 按登录账号精确查用户（{@code username} 上有唯一索引，命中至多 1 行）。
     *
     * <p>显式列出列名而不用 {@code SELECT *}：一来避免表结构新增列时把
     * 敏感列（如未来的 salt / 密保答案）顺手带出来，二来让「谁在读密码」一目了然。</p>
     *
     * @param username 学号 / 工号 / 登录账号
     * @return 用户；不存在返回 {@code null}
     */
    @Select("SELECT id, username, password, real_name, role, class_id, weak_tags, status, "
            + "last_login_at, create_time FROM sys_user WHERE username = #{username} LIMIT 1")
    SysUser selectByUsername(@Param("username") String username);

    /**
     * 只回写最近登录时刻。
     *
     * <p>不调 {@code updateById} 是因为那个方法会把整个实体写回去——
     * 只要有一列在实体里是 null（例如本次没有查 weak_tags），就会被写成 NULL，
     * 属于典型的「更新丢失」。单列 UPDATE 永远安全。</p>
     *
     * @param userId 用户 ID
     * @param loginAt 登录时刻
     * @return 影响行数（正常为 1）
     */
    @Update("UPDATE sys_user SET last_login_at = #{loginAt} WHERE id = #{userId}")
    int touchLastLogin(@Param("userId") Long userId, @Param("loginAt") LocalDateTime loginAt);
}
