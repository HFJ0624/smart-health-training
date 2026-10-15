package com.hfj.fitagent.modules.coach.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * agent_run（V19）：一次提问的后台执行任务，也是"单会话只有一个活动 run"的数据库现场。
 *
 * <p><b>没有</b> {@code activeConversationId} 字段，而且不允许加（AGENTS.md）：该列是
 * {@code GENERATED ALWAYS ... STORED} 派生列，任何 INSERT/UPDATE 带它都会被数据库拒绝。
 * 应用侧唯一的用法是捕获它的 UNIQUE 冲突并转成 {@code RUN_ALREADY_ACTIVE}。
 *
 * <p>两个 JSON 列都必须走 {@link Jackson3TypeHandler} + {@code autoResultMap = true}
 * （Boot 4 的 classpath 没有 Jackson 2 databind）；也因此本表的读取只能经 BaseMapper
 * 的条件查询——自定义 {@code @Select} 会丢类型处理器，JSON 列会退化成字符串。
 *
 * <p>{@code budgetSnapshot} 是发送前展示与追溯用的快照，<b>不是</b>放行依据：执行时每一次调用
 * 都重新过 BudgetGate（§11 约束"有 estimate 也不跳过执行时的闸门"）。
 * {@code resultPayload} 是完整 {@code AgentAnswer} 的快照，事实不因费用状态更新而被静默重写。
 */
@TableName(value = "agent_run", autoResultMap = true)
@Getter
@Setter
public class AgentRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对外标识：服务端生成的小写 UUID。 */
    private String runUuid;

    private Long userId;

    private Long conversationId;

    /** 客户端幂等键（规范化小写 UUID）。 */
    private String idempotencyKey;

    /** SHA-256(conversationId|规范化问题文本|语言与日期参数)；不含凭据。 */
    private String requestHash;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long credentialId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer credentialVersion;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long modelId;

    /** 设计 §10 的状态集；终态不可被普通重试改回 RUNNING。 */
    private String status;

    /** 预算侧快照（策略上限、币种、PEAK 单价、保守估算）。 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private Map<String, Object> budgetSnapshot;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String currency;

    /** run 开始时的 user_account.data_revision；AgentContext 与陈旧判定都以它为准。 */
    private Long contextRevision;

    /** 完整 AgentAnswer 快照（终态写入）。 */
    @TableField(typeHandler = Jackson3TypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private Map<String, Object> resultPayload;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String stopReason;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorCode;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal estimatedCost;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal settledCost;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal uncertainCost;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant cancelRequestedAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String workerId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant leaseExpiresAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant startedAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant finishedAt;

    @Version
    private Integer lockVersion;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
