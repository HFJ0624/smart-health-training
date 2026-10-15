package com.hfj.fitagent.modules.coach.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.coach.entity.AgentStepEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * agent_step 写入与读取（{@code AgentStepSink} 的实现依赖它）。
 *
 * <p>进度摘要用三个 COUNT 而不是把步骤读进内存再数：轨迹条数有上限但读取成本不该由
 * "GET /runs/{id}" 的轮询节奏放大——小程序在一次 run 里会反复轮这个接口。
 */
@Mapper
public interface AgentStepMapper extends BaseMapper<AgentStepEntity> {

    /** 某类事件的次数：MODEL_CALL 计模型调用、TOOL_RESULT 计真实执行过的工具。 */
    @Select("""
            SELECT COUNT(*) FROM agent_step
            WHERE user_id = #{userId} AND run_id = #{runId} AND event_type = #{eventType}
            """)
    long countByEventType(@Param("userId") long userId, @Param("runId") long runId,
                          @Param("eventType") String eventType);

    @Select("""
            SELECT COUNT(*) FROM agent_step
            WHERE user_id = #{userId} AND run_id = #{runId}
            """)
    long countByRun(@Param("userId") long userId, @Param("runId") long runId);

    /** 重放同一个 run 时 step_no 撞 UNIQUE 就丢弃：这条 SQL 不给"覆盖既有轨迹"的路径。 */
    @Select("""
            SELECT COALESCE(MAX(step_no), 0) FROM agent_step
            WHERE user_id = #{userId} AND run_id = #{runId}
            """)
    int findMaxStepNo(@Param("userId") long userId, @Param("runId") long runId);

    /** 已存在的步骤号（幂等重放判定，避免同一 run 里写出两条同序号轨迹）。 */
    @Select("""
            SELECT step_no FROM agent_step
            WHERE user_id = #{userId} AND run_id = #{runId}
            """)
    List<Integer> listStepNos(@Param("userId") long userId, @Param("runId") long runId);
}
