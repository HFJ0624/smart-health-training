package com.hfj.fitagent.modules.training.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.training.entity.TrainingPlanDayEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * training_plan_day 持久化。
 *
 * 草稿就地编辑采用"先删子行再整体重建"，与计划头同一事务：任一失败完整回滚，不留半份计划。
 *
 * ⚠ 本阶段可以这样做的唯一原因是 P1 没有 workout_session，计划日还没有被任何执行记录引用。
 *    P2 接入实际训练打卡后必须改成增量更新（保留原 day id），否则历史训练会指向被删掉的计划日。
 *    这条约束已记录在 docs/p1-database.md §6 与 docs/p1-architecture.md §7。
 */
@Mapper
public interface TrainingPlanDayMapper extends BaseMapper<TrainingPlanDayEntity> {

    @Select("""
            SELECT id, user_id, plan_id, iso_weekday, title, is_rest_day, notes, created_at, updated_at
            FROM training_plan_day WHERE plan_id = #{planId} AND user_id = #{userId}
            ORDER BY iso_weekday
            """)
    List<TrainingPlanDayEntity> listByPlan(@Param("planId") long planId, @Param("userId") long userId);

    @Select("""
            SELECT id, user_id, plan_id, iso_weekday, title, is_rest_day, notes, created_at, updated_at
            FROM training_plan_day
            WHERE plan_id = #{planId} AND user_id = #{userId} AND iso_weekday = #{isoWeekday}
            """)
    TrainingPlanDayEntity findByPlanAndWeekday(@Param("planId") long planId, @Param("userId") long userId,
                                               @Param("isoWeekday") int isoWeekday);

    @Delete("DELETE FROM training_plan_day WHERE plan_id = #{planId} AND user_id = #{userId}")
    int deleteByPlan(@Param("planId") long planId, @Param("userId") long userId);
}
