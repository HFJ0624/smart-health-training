package com.hfj.fitagent.modules.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.auth.entity.UserAccountEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

/**
 * user_account 持久化。
 *
 * incrementDataRevision 是"数据修订号"的唯一入口：档案/目标/体重/计划任一变更后，
 * 必须在同一个业务事务里调用它，报告类功能据此判断旧结果是否过期。
 * 修订号用 SQL 的 data_revision = data_revision + 1 自增，不在 Java 里算好再写回，
 * 否则两个并发写会互相覆盖增量。
 */
@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccountEntity> {

    @Select("""
            SELECT id, wechat_app_id, wechat_open_id, wechat_union_id, nickname, role, status,
                   timezone, data_revision, last_login_at, created_at, updated_at
            FROM user_account
            WHERE wechat_app_id = #{appId} AND wechat_open_id = #{openId}
            """)
    UserAccountEntity findByAppIdAndOpenId(@Param("appId") String appId, @Param("openId") String openId);

    /**
     * 激活计划/目标前锁住账户行，把同一用户的状态迁移串行化。
     * 固定加锁顺序：先 user_account，再目标行，避免与其它事务形成反向等待。
     */
    @Select("SELECT id FROM user_account WHERE id = #{id} FOR UPDATE")
    Long lockById(@Param("id") long id);

    @Update("UPDATE user_account SET data_revision = data_revision + 1 WHERE id = #{id}")
    int incrementDataRevision(@Param("id") long id);

    /**
     * 只读取修订号：给"run 开始时快照 / 结束时比对"用（docs/agent-v1-design.md §9）。
     * 不进 {@link com.hfj.fitagent.modules.auth.entity.UserAccountEntity}——该实体按约定不声明这一列。
     */
    @Select("SELECT data_revision FROM user_account WHERE id = #{id}")
    Long findDataRevision(@Param("id") long id);

    @Update("UPDATE user_account SET last_login_at = #{at} WHERE id = #{id}")
    int touchLastLoginAt(@Param("id") long id, @Param("at") Instant at);
}
