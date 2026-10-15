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
 * budget_daily_usage：当日预算账户（V16），三列分账。
 *
 * <p>{@code settledCost + reservedCost + uncertainReservedCost} 之和就是已占用的额度：
 * 少算任何一项都会变成"默认放行"，尤其 uncertain —— 把用量不明的钱当 0，
 * 就等于允许用户为一次可能已经扣费的调用再花一次预算。
 *
 * <p>本行是并发预占的串行点：预占事务先 {@code SELECT ... FOR UPDATE} 锁住它再做加减，
 * 所以同一天账户上的两次并发预占不会互相看不见（不会双花）。
 *
 * <p>三个金额列 NEVER 单独更新：它们只可能通过 mapper 里的显式 UPDATE（带 {@code >= 0} 守卫）变动，
 * 避免 {@code updateById(entity)} 用一个过期的读值覆盖掉并发写。
 */
@TableName("budget_daily_usage")
@Getter
@Setter
public class BudgetDailyUsageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long providerId;

    /** 账户币种；不同币种是不同账户行。 */
    private String currency;

    /** 业务预算日：服务端按 Asia/Shanghai 从"调用开始时刻"推导，客户端不传。 */
    private LocalDate budgetDate;

    /** 只可能由带守卫的显式 UPDATE 变动（见 mapper），{@code updateById} 永远不碰它。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private BigDecimal settledCost;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private BigDecimal reservedCost;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private BigDecimal uncertainReservedCost;

    @Version
    private Integer lockVersion;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
