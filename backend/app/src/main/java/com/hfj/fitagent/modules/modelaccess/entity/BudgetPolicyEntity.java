package com.hfj.fitagent.modules.modelaccess.entity;

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

/**
 * budget_policy：用户预算策略（V15）。
 *
 * <p>两个上限的可空性语义必须钉死：{@code NULL} 表示"该维度不设限"，<b>不是</b> 0、也<b>不是</b>
 * "默认放行"。两者同时为 NULL 时 BudgetGate 仍拒绝发起调用（V15 注释），
 * 所以"限额字段可空"不等于"预算可绕过"。{@code updateStrategy = ALWAYS} 让用户真的能把上限清空回 NULL。
 *
 * <p>{@code budgetTimezone} 双 NEVER：本轮固定 Asia/Shanghai，列由数据库 DEFAULT 产生，
 * 任何接口都不写它——时区若可由请求决定，业务日归属就变成客户端说了算（AGENTS.md：日期由服务端推导）。
 */
@TableName("budget_policy")
@Getter
@Setter
public class BudgetPolicyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long providerId;

    /** 限额币种；必须与 model_price.currency 一致才可比。 */
    private String currency;

    /** 单次任务（一个 run 的全部模型调用之和）上限；NULL 表示不设限。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal maxTaskCost;

    /** 当日上限（按 budget_timezone 的业务日）；NULL 表示不设限。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal maxDailyCost;

    /** 派生/默认列：既不由应用写入，也不由应用更新。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String budgetTimezone;

    /** 单个 run 的模型调用次数上限（工程闸门，DB 列 TINYINT UNSIGNED ≤ 255）。 */
    private Integer maxModelCalls;

    /** 单个 run 的工具执行次数上限（工程闸门）。 */
    private Integer maxToolCalls;

    /** 保守预占使用的输出上限（不取 model_catalog.max_output_tokens）。 */
    private Integer maxOutputTokens;

    private Boolean enabled;

    @Version
    private Integer lockVersion;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
