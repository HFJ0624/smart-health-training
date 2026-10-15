package com.hfj.fitagent.modules.coach.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * model_call（V20）：逐次远程调用台账。
 *
 * <p>coach 侧对它<b>只读</b>：这张表由模型调用网关（写预占的地方）负责插入与推进状态，
 * 本模块只按 §10 的恢复规则问一个问题——"这个 run 里有没有可能已经发出去的调用"。
 * 之所以在本模块声明实体而不是让 billing 引用它：agent_step 的外键要指向它，且恢复判定必须
 * 能在 run 失去 Worker 后独立执行。
 *
 * <p>{@code usagePayload} 是 JSON 列（同 agent_run 的类型处理器约束）。四个 token 分项<b>可空</b>：
 * NULL 是"供应商没回报这一项"，0 是"确实为 0"，两者绝不能互换（协议 §6）。
 */
@TableName(value = "model_call", autoResultMap = true)
@Getter
@Setter
public class ModelCallEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long runId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long reservationId;

    private Integer attemptNo;

    private Long modelId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long priceId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer credentialVersion;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String providerRequestId;

    /** PREPARED / SENT / SUCCEEDED / UNKNOWN / FAILED。 */
    private String status;

    @TableField(typeHandler = Jackson3TypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private Map<String, Object> usagePayload;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long promptCacheHitTokens;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long promptCacheMissTokens;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long completionTokens;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long reasoningTokens;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal calculatedCost;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String currency;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant sentAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant finishedAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
