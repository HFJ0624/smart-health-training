package com.hfj.fitagent.modules.training.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.training.entity.TrainingPlanEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

/**
 * training_plan 持久化。
 *
 * goal_snapshot 依赖实体 autoResultMap + Jackson3TypeHandler，所以这里不提供返回实体的自定义 @Select；
 * 读实体走 BaseMapper。返回标量的查询（锁行、取最大版本）不受影响。
 *
 * archiveActive 与 activateDraft 的顺序是硬性的：同一事务内必须先归档旧 ACTIVE
 * （让它的 active_user_id 变成 NULL），再把新草稿置 ACTIVE，否则 UNIQUE(active_user_id) 当场冲突。
 * 两个语句都不写 active_user_id —— 它由数据库派生。
 */
@Mapper
public interface TrainingPlanMapper extends BaseMapper<TrainingPlanEntity> {

    @Select("SELECT id FROM user_account WHERE id = #{userId} FOR UPDATE")
    Long lockAccount(@Param("userId") long userId);

    @Select("SELECT id FROM training_plan WHERE id = #{id} AND user_id = #{userId} FOR UPDATE")
    Long lockPlan(@Param("id") long id, @Param("userId") long userId);

    @Select("SELECT id FROM training_plan WHERE user_id = #{userId} AND status = 'ACTIVE'")
    Long findActiveId(@Param("userId") long userId);

    @Select("""
            SELECT MAX(revision_no) FROM training_plan
            WHERE user_id = #{userId} AND plan_group_uuid = #{planGroupUuid}
            """)
    Integer findMaxRevisionNo(@Param("userId") long userId, @Param("planGroupUuid") String planGroupUuid);

    @Update("""
            UPDATE training_plan SET status = 'ARCHIVED', lock_version = lock_version + 1
            WHERE user_id = #{userId} AND status = 'ACTIVE'
            """)
    int archiveActive(@Param("userId") long userId);

    @Update("""
            UPDATE training_plan SET status = 'ACTIVE', confirmed_at = #{at}, lock_version = lock_version + 1
            WHERE id = #{id} AND user_id = #{userId} AND status = 'DRAFT' AND lock_version = #{lockVersion}
            """)
    int activateDraft(@Param("id") long id, @Param("userId") long userId,
                      @Param("lockVersion") int lockVersion, @Param("at") Instant at);

    @Update("""
            UPDATE training_plan SET status = 'ARCHIVED', lock_version = lock_version + 1
            WHERE id = #{id} AND user_id = #{userId} AND status = 'ACTIVE' AND lock_version = #{lockVersion}
            """)
    int archiveById(@Param("id") long id, @Param("userId") long userId, @Param("lockVersion") int lockVersion);

    /**
     * 丢弃草稿：只改状态为 DISCARDED，行与子行全部保留。
     * 不提供物理删除——历史版本一旦能删，"计划不可覆盖"就失去了意义。
     */
    @Update("""
            UPDATE training_plan SET status = 'DISCARDED', lock_version = lock_version + 1
            WHERE id = #{id} AND user_id = #{userId} AND status = 'DRAFT' AND lock_version = #{lockVersion}
            """)
    int discardDraft(@Param("id") long id, @Param("userId") long userId, @Param("lockVersion") int lockVersion);
}
