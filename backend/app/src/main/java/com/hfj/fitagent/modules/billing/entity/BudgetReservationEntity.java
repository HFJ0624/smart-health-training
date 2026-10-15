package com.hfj.fitagent.modules.billing.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * budget_reservation：逐次调用的预占台账（V17）。
 *
 * <p>{@code reservedAmount} 落库后<b>不再改写</b>——它是"当初占了多少"的凭证，
 * 改了日账户的分账就复现不出来。因此本类不把它开放给任何 update 路径：状态转移用带守卫的
 * 显式 UPDATE（{@code WHERE ... AND status = 期望值}）完成，绝不用 {@code updateById} 整行覆盖。
 *
 * <p>{@code runId} 没有外键（{@code agent_run} 本轮还不存在，见 V17 注释），
 * 所以跨表归属完全靠 {@code WHERE run_id = ? AND user_id = ?} 的双条件限定。
 *
 * <p>{@code settledAmount}/{@code settledAt} 可空且 {@code updateStrategy = ALWAYS}：
 * RELEASED 行必须能把 settled_amount 保持为 NULL（列上有 CHECK 约束）。
 */
@TableName("budget_reservation")
@Getter
@Setter
public class BudgetReservationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long runId;

    /** 该 run 内第几次模型调用（从 1 起），受 budget_policy.max_model_calls 约束。 */
    private Integer attemptNo;

    private Long providerId;

    /** 所归属的当日账户行；预占/结算都锁这一行。 */
    private Long dailyUsageId;

    private String currency;

    private LocalDate budgetDate;

    private BigDecimal reservedAmount;

    /** NULL 表示尚未落定（不是 0）。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal settledAmount;

    /** CONSERVATIVE / ACTUAL / UNKNOWN。 */
    private String estimateQuality;

    /** RESERVED / SETTLED / UNCERTAIN / RELEASED。 */
    private String status;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant settledAt;

    @Version
    private Integer lockVersion;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
