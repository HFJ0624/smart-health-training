package com.hfj.fitagent.modules.workout.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.workout.entity.WorkoutSessionEntryEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * workout_session_entry 持久化。
 *
 * 删除顺序必须是 先 set 后 entry 再 session：set 被 entry 以外键 RESTRICT 引用，
 * entry 又被 session 引用，反过来删会当场触发外键错误并把整个聚合事务带回滚。
 * 聚合保存采用"先删子树再重建"，与 {@code TrainingPlanService.update()} 同一套口径。
 */
@Mapper
public interface WorkoutSessionEntryMapper extends BaseMapper<WorkoutSessionEntryEntity> {

    @Select("""
            SELECT id, user_id, session_id, exercise_id, plan_item_id, position_no,
                   target_duration_seconds, distance_meters, notes, created_at, updated_at
            FROM workout_session_entry
            WHERE session_id = #{sessionId} AND user_id = #{userId}
            ORDER BY position_no
            """)
    List<WorkoutSessionEntryEntity> listBySession(@Param("sessionId") long sessionId, @Param("userId") long userId);

    /** 统计一次读多个场次时用，避免按 session 逐条发查询。 */
    @Select("""
            <script>
            SELECT id, user_id, session_id, exercise_id, plan_item_id, position_no,
                   target_duration_seconds, distance_meters, notes, created_at, updated_at
            FROM workout_session_entry
            WHERE user_id = #{userId} AND session_id IN
            <foreach collection="sessionIds" item="sid" open="(" separator="," close=")">#{sid}</foreach>
            ORDER BY session_id, position_no
            </script>
            """)
    List<WorkoutSessionEntryEntity> listBySessions(@Param("sessionIds") List<Long> sessionIds, @Param("userId") long userId);

    @Delete("DELETE FROM workout_session_entry WHERE session_id = #{sessionId} AND user_id = #{userId}")
    int deleteBySession(@Param("sessionId") long sessionId, @Param("userId") long userId);
}
