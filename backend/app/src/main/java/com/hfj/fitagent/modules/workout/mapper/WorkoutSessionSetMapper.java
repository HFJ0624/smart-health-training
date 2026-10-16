package com.hfj.fitagent.modules.workout.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.workout.entity.WorkoutSessionSetEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * workout_session_set 持久化。
 *
 * 组记录总是随 entry 一起读写（一次训练最多几十组），所以不提供单行接口：
 * 聚合保存时整棵子树先删后建，避免出现"entry 已换、set 还挂着旧 entry_id"的半条记录。
 */
@Mapper
public interface WorkoutSessionSetMapper extends BaseMapper<WorkoutSessionSetEntity> {

    @Select("""
            SELECT id, user_id, entry_id, set_no, reps, external_load_kg, duration_seconds,
                   is_completed, notes, created_at, updated_at
            FROM workout_session_set
            WHERE entry_id = #{entryId} AND user_id = #{userId}
            ORDER BY set_no
            """)
    List<WorkoutSessionSetEntity> listByEntry(@Param("entryId") long entryId, @Param("userId") long userId);

    /** 跨 entry 一次取回整棵子树：详情与统计都靠它，避免 N+1。 */
    @Select("""
            <script>
            SELECT s.id, s.user_id, s.entry_id, s.set_no, s.reps, s.external_load_kg, s.duration_seconds,
                   s.is_completed, s.notes, s.created_at, s.updated_at
            FROM workout_session_set s
            JOIN workout_session_entry e ON e.id = s.entry_id AND e.user_id = s.user_id
            WHERE s.user_id = #{userId} AND e.session_id IN
            <foreach collection="sessionIds" item="sid" open="(" separator="," close=")">#{sid}</foreach>
            ORDER BY s.entry_id, s.set_no
            </script>
            """)
    List<WorkoutSessionSetEntity> listBySessions(@Param("sessionIds") List<Long> sessionIds, @Param("userId") long userId);

    /** 多表删除：先清掉本 session 下所有 entry 的组，再删 entry。 */
    @Delete("""
            DELETE s FROM workout_session_set s
            JOIN workout_session_entry e ON e.id = s.entry_id AND e.user_id = s.user_id
            WHERE e.session_id = #{sessionId} AND e.user_id = #{userId}
            """)
    int deleteBySession(@Param("sessionId") long sessionId, @Param("userId") long userId);
}
