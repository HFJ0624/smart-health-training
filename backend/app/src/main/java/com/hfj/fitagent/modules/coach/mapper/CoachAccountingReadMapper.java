package com.hfj.fitagent.modules.coach.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 预算账本的<b>只读投影</b>（estimate 与 run 响应用）。
 *
 * <p>为什么不直接调 billing 的 Mapper：AGENTS.md 的模块边界是"跨模块只走接口"，
 * 而 billing 当前暴露的 {@code BudgetGate}/{@code UsageSettlement} 都是<b>写</b>路径
 * （预占、结算、释放），{@code BudgetDailyUsageMapper.lockAccount} 是加锁读——
 * 一个 GET 预估不该去锁日账户行，更不该为了读余额把 {@code ensureAccount} 的插入顺带执行了。
 * 因此这里只做 SELECT，不写 billing 的任何一行。
 *
 * <p><b>需要 billing 侧补一个只读口</b>（见交付说明的"缺口"）：届时本类应当退役，
 * 而不是长期留两份对同一张表的读逻辑。
 *
 * <p>三列分账的求和口径与 {@code DailyBudgetAccount.committed()} 一致：
 * settled + reserved + uncertain_reserved，缺任何一列都会把"还剩多少"算多，
 * 而算多的后果是闸门放行了一次本该拒绝的调用。
 */
@Mapper
public interface CoachAccountingReadMapper {

    /** 当日已占用额度；账户行不存在时返回 null（= 确实还没发生任何调用，不是 0 元可花）。 */
    @Select("""
            SELECT settled_cost + reserved_cost + uncertain_reserved_cost
            FROM budget_daily_usage
            WHERE user_id = #{userId} AND provider_id = #{providerId}
              AND currency = #{currency} AND budget_date = #{budgetDate}
            """)
    BigDecimal sumDailyCommitted(@Param("userId") long userId, @Param("providerId") long providerId,
                                @Param("currency") String currency, @Param("budgetDate") LocalDate budgetDate);

    /**
     * 单个 run（任务）已占用额度：SETTLED 取实际额，其余在途/不明取估算额，RELEASED 不计。
     * 与 {@code BudgetReservationMapper.sumTaskCost} 同一个 CASE 口径。
     */
    @Select("""
            SELECT COALESCE(SUM(CASE WHEN status = 'SETTLED' THEN settled_amount ELSE reserved_amount END), 0)
            FROM budget_reservation
            WHERE user_id = #{userId} AND run_id = #{runId} AND status <> 'RELEASED'
            """)
    BigDecimal sumTaskCommitted(@Param("userId") long userId, @Param("runId") long runId);

    /**
     * 单个 run 里"已发出但用量不明"的金额合计：这些预占既不归零也不重发，
     * 但必须计入 run 的 uncertain_cost 让页面显示"存在用量不明"（design §6.3、§10）。
     */
    @Select("""
            SELECT COALESCE(SUM(reserved_amount), 0) FROM budget_reservation
            WHERE user_id = #{userId} AND run_id = #{runId} AND status = 'UNCERTAIN'
            """)
    BigDecimal sumUncertainCommitted(@Param("userId") long userId, @Param("runId") long runId);
}
