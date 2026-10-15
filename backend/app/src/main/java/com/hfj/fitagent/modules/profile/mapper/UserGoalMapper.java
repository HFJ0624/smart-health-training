package com.hfj.fitagent.modules.profile.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.profile.entity.UserGoalEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * user_goal 持久化。
 *
 * archiveActive 与 activate 必须成对出现在同一事务里，且顺序固定为先归档再激活：
 * 反过来的话 UNIQUE(active_user_id) 会在同一事务内看到两行同 user_id 而直接失败。
 * 两个语句都不写 active_user_id —— 它由数据库派生。
 */
@Mapper
public interface UserGoalMapper extends BaseMapper<UserGoalEntity> {

    @Select("SELECT id FROM user_goal WHERE user_id = #{userId} AND status = 'ACTIVE'")
    Long findActiveId(@Param("userId") long userId);

    @Update("""
            UPDATE user_goal SET status = 'ARCHIVED'
            WHERE user_id = #{userId} AND status = 'ACTIVE'
            """)
    int archiveActive(@Param("userId") long userId);

    /** user_goal 没有 confirmed_at 列（设计里只有 status 历史），激活时间由 updated_at 反映。 */
    @Update("""
            UPDATE user_goal SET status = 'ACTIVE', lock_version = lock_version + 1
            WHERE id = #{id} AND user_id = #{userId} AND status = 'DRAFT' AND lock_version = #{lockVersion}
            """)
    int activateDraft(@Param("id") long id, @Param("userId") long userId,
                      @Param("lockVersion") int lockVersion);
}
