package com.hfj.fitagent.modules.workout.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.workout.entity.WorkoutSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

/**
 * workout_session 持久化。
 *
 * 所有状态流转语句都**不写 active_user_id**（数据库派生），并且带 `status` 前置条件：
 * 结束、丢弃都是"只从 DRAFT 出发"的单向迁移，重复调用命中 0 行，由服务层回读当前状态实现幂等，
 * 而不是把第二次「结束」当成版本冲突。
 */
@Mapper
public interface WorkoutSessionMapper extends BaseMapper<WorkoutSessionEntity> {

    @Select("SELECT id FROM workout_session WHERE user_id = #{userId} AND status = 'DRAFT'")
    Long findDraftId(@Param("userId") long userId);

    @Select("SELECT id FROM workout_session WHERE id = #{id} AND user_id = #{userId} FOR UPDATE")
    Long lockSession(@Param("id") long id, @Param("userId") long userId);

    /** 确认结束：只从 DRAFT 迁移，且必须携带乐观锁版本。 */
    @Update("""
            UPDATE workout_session
            SET status = 'COMPLETED', ended_at = #{endedAt}, lock_version = lock_version + 1
            WHERE id = #{id} AND user_id = #{userId} AND status = 'DRAFT' AND lock_version = #{lockVersion}
            """)
    int completeDraft(@Param("id") long id, @Param("userId") long userId,
                      @Param("lockVersion") int lockVersion, @Param("endedAt") Instant endedAt);

    /** 丢弃草稿：同样只从 DRAFT 出发；行与子行保留，active_user_id 由数据库派生成 NULL。 */
    @Update("""
            UPDATE workout_session
            SET status = 'DISCARDED', lock_version = lock_version + 1
            WHERE id = #{id} AND user_id = #{userId} AND status = 'DRAFT' AND lock_version = #{lockVersion}
            """)
    int discardDraft(@Param("id") long id, @Param("userId") long userId, @Param("lockVersion") int lockVersion);

    /** 结束后再编辑：COMPLETED 保持 COMPLETED，只递增版本，不改 ended_at 与状态。 */
    @Update("""
            UPDATE workout_session
            SET notes = #{notes}, lock_version = lock_version + 1
            WHERE id = #{id} AND user_id = #{userId} AND status = 'COMPLETED' AND lock_version = #{lockVersion}
            """)
    int updateCompletedNotes(@Param("id") long id, @Param("userId") long userId,
                             @Param("lockVersion") int lockVersion, @Param("notes") String notes);

    /**
     * 某业务日期区间内**已确认**的场次（统计与训练日历的唯一数据来源）。
     * 草稿与已丢弃永不参与，因此"计划了但没记录"不会被算成练过。
     */
    @Select("""
            SELECT id, user_id, client_record_uuid, business_date, timezone, plan_id, plan_day_id,
                   plan_revision_no, started_at, ended_at, status, notes, lock_version, created_at, updated_at
            FROM workout_session
            WHERE user_id = #{userId} AND status = 'COMPLETED'
              AND business_date >= #{fromDate} AND business_date <= #{toDate}
            ORDER BY business_date, started_at
            """)
    java.util.List<WorkoutSessionEntity> listCompletedBetween(@Param("userId") long userId,
                                                              @Param("fromDate") java.time.LocalDate fromDate,
                                                              @Param("toDate") java.time.LocalDate toDate);

    /** 当天的已确认场次（含全部列，供"计划 vs 实际"对比用）。 */
    @Select("""
            SELECT id, user_id, client_record_uuid, business_date, timezone, plan_id, plan_day_id,
                   plan_revision_no, started_at, ended_at, status, notes, lock_version, created_at, updated_at
            FROM workout_session
            WHERE user_id = #{userId} AND status = 'COMPLETED' AND business_date = #{businessDate}
            ORDER BY started_at
            """)
    java.util.List<WorkoutSessionEntity> listCompletedOn(@Param("userId") long userId,
                                                         @Param("businessDate") java.time.LocalDate businessDate);
}
