package com.hfj.fitagent.modules.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.billing.entity.BudgetReservationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * budget_reservation 持久化：状态机 + 幂等。
 *
 * <p>每一个转移都是"比较并交换"：{@code WHERE id = ? AND user_id = ? AND status = <期望值>}，
 * 影响 0 行就说明这次回调重复或顺序错了，账本因此不会动第二次（幂等防线在这里，不在应用内存）。
 *
 * <p>{@code reserved_amount} 出现在 SET 子句里<b>只有</b>从 reserved 平移出去的三条语句，
 * 且都不改 {@code reserved_amount} 本身——它是当初占了多少的凭证。
 */
@Mapper
public interface BudgetReservationMapper extends BaseMapper<BudgetReservationEntity> {

    /** 台账行读取（含金额，不含任何秘密材料）。 */
    @Select("""
            SELECT id, user_id, run_id, attempt_no, provider_id, daily_usage_id, currency, budget_date,
                   reserved_amount, settled_amount, estimate_quality, status, settled_at, lock_version,
                   created_at, updated_at
            FROM budget_reservation
            WHERE id = #{id} AND user_id = #{userId}
            """)
    BudgetReservationEntity find(@Param("id") long id, @Param("userId") long userId);

    /** RESERVED → SETTLED：只有还在 RESERVED 的行会被改，重复结算拿到 0 行。 */
    @Update("""
            UPDATE budget_reservation
            SET status = 'SETTLED', estimate_quality = 'ACTUAL', settled_amount = #{actual},
                settled_at = #{settledAt}, lock_version = lock_version + 1
            WHERE id = #{id} AND user_id = #{userId} AND status = 'RESERVED'
            """)
    int casSettle(@Param("id") long id, @Param("userId") long userId,
                  @Param("actual") BigDecimal actual, @Param("settledAt") Instant settledAt);

    /** RESERVED → UNCERTAIN（已送达但用量不明；估算额留在 uncertain 桶里继续占用额度）。 */
    @Update("""
            UPDATE budget_reservation
            SET status = 'UNCERTAIN', estimate_quality = 'UNKNOWN', settled_at = NULL,
                lock_version = lock_version + 1
            WHERE id = #{id} AND user_id = #{userId} AND status = 'RESERVED'
            """)
    int casMarkUncertain(@Param("id") long id, @Param("userId") long userId);

    /** RESERVED → RELEASED（确定请求从未发出；settled_amount 必须保持 NULL，见 ck_budget_reservation_4）。 */
    @Update("""
            UPDATE budget_reservation
            SET status = 'RELEASED', lock_version = lock_version + 1
            WHERE id = #{id} AND user_id = #{userId} AND status = 'RESERVED'
            """)
    int casRelease(@Param("id") long id, @Param("userId") long userId);

    /**
     * 单次任务的已花费/已占用合计：SETTLED 取实际额，其余（RESERVED/UNCERTAIN）取估算额，
     * RELEASED 不计（从未扣费）。COALESCE 保证空 run 返回 0 而不是 NULL——0 在这里是"确实没有支出"。
     */
    @Select("""
            SELECT COALESCE(SUM(CASE WHEN status = 'SETTLED' THEN settled_amount ELSE reserved_amount END), 0)
            FROM budget_reservation
            WHERE user_id = #{userId} AND run_id = #{runId} AND status <> 'RELEASED'
            """)
    BigDecimal sumTaskCost(@Param("userId") long userId, @Param("runId") long runId);

    /** 同一 (run_id, attempt_no) 已有台账即幂等复用，不重复占款（唯一键 uq_budget_reservation_1）。 */
    @Select("""
            SELECT id FROM budget_reservation WHERE user_id = #{userId} AND run_id = #{runId} AND attempt_no = #{attemptNo}
            """)
    Long findIdByAttempt(@Param("userId") long userId, @Param("runId") long runId, @Param("attemptNo") int attemptNo);
}
