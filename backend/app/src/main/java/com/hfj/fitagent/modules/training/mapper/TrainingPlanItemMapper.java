package com.hfj.fitagent.modules.training.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.training.entity.TrainingPlanItemEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * training_plan_item 持久化。
 *
 * 删除顺序必须是 先 item 后 day：day 被 item 以外键 RESTRICT 引用，
 * 反过来会当场触发外键错误、让整个草稿编辑事务回滚。
 */
@Mapper
public interface TrainingPlanItemMapper extends BaseMapper<TrainingPlanItemEntity> {

    @Select("""
            SELECT id, user_id, plan_day_id, exercise_id, position_no, target_sets, reps_min, reps_max,
                   target_external_load_kg, target_duration_seconds, rest_seconds, notes, created_at, updated_at
            FROM training_plan_item
            WHERE plan_day_id = #{planDayId} AND user_id = #{userId}
            ORDER BY position_no
            """)
    List<TrainingPlanItemEntity> listByDay(@Param("planDayId") long planDayId, @Param("userId") long userId);

    @Select("""
            SELECT i.id, i.user_id, i.plan_day_id, i.exercise_id, i.position_no, i.target_sets,
                   i.reps_min, i.reps_max, i.target_external_load_kg, i.target_duration_seconds,
                   i.rest_seconds, i.notes, i.created_at, i.updated_at
            FROM training_plan_item i
            JOIN training_plan_day d ON d.id = i.plan_day_id AND d.user_id = i.user_id
            WHERE d.plan_id = #{planId} AND i.user_id = #{userId}
            ORDER BY i.plan_day_id, i.position_no
            """)
    List<TrainingPlanItemEntity> listByPlan(@Param("planId") long planId, @Param("userId") long userId);

    @Delete("""
            DELETE i FROM training_plan_item i
            JOIN training_plan_day d ON d.id = i.plan_day_id AND d.user_id = i.user_id
            WHERE d.plan_id = #{planId} AND i.user_id = #{userId}
            """)
    int deleteByPlan(@Param("planId") long planId, @Param("userId") long userId);
}
