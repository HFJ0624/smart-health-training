package com.hfj.fitagent.modules.coach.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.coach.entity.ModelCallEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

/**
 * model_call 读取：coach 侧只做恢复判定与用量核对，写入由模型调用网关负责（设计 §6.3）。
 *
 * <p>{@link #countPossiblySent} 就是设计 §10 恢复规则的那个问题：
 * PREPARED / SENT / UNKNOWN 三者都算"可能已经发出去了"，只要有一行存在，
 * 失去 Worker 的 run 就只能转 FAILED + UNKNOWN_CHARGE，不能退回 QUEUED 重发。
 * PREPARED 也算：台账已经写入，说明预算已预占、请求已经在交给传输层的路上，
 * 从外部无从证明它没发出去——按"可能已计费"处理是这里唯一诚实的读法。
 */
@Mapper
public interface ModelCallMapper extends BaseMapper<ModelCallEntity> {

    @Select("""
            SELECT COUNT(*) FROM model_call
            WHERE user_id = #{userId} AND run_id = #{runId} AND status IN ('PREPARED','SENT','UNKNOWN')
            """)
    long countPossiblySent(@Param("userId") long userId, @Param("runId") long runId);

    /** 已结算费用之和（run 行的 settled_cost 从这里推导，不由应用内存猜测）。 */
    @Select("""
            SELECT COALESCE(SUM(calculated_cost), 0) FROM model_call
            WHERE user_id = #{userId} AND run_id = #{runId} AND status = 'SUCCEEDED'
              AND calculated_cost IS NOT NULL
            """)
    BigDecimal sumSettledCost(@Param("userId") long userId, @Param("runId") long runId);

    /** 用量不明的调用数：&gt;0 时 run 必须显示"存在用量不明"，不显示 0。 */
    @Select("""
            SELECT COUNT(*) FROM model_call
            WHERE user_id = #{userId} AND run_id = #{runId} AND status IN ('UNKNOWN','SENT','PREPARED')
            """)
    long countUsageUnsettled(@Param("userId") long userId, @Param("runId") long runId);
}
