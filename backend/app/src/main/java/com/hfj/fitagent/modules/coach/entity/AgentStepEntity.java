package com.hfj.fitagent.modules.coach.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

/**
 * agent_step（V21）：run 的脱敏操作轨迹。
 *
 * <p>这行数据<b>不含</b>隐藏推理，也<b>不含</b>任何密钥材料：
 * {@code argumentsRedacted} 存的是 {@code TraceRedaction.redact} 处理过的文本，
 * 由落库口包成 {@code \{"redacted": "…"\}} 再进 JSON 列（原文未必是合法 JSON，包一层才不会写入失败）；
 * {@code resultSummary} 只是"几个来源 / 是否完整"这类工程摘要，回答正文永远在 agent_run.result_payload。
 * 接口层（{@code RunQueryService}）只返回 summary 与状态，参数原文不出现在任何响应里（§11.1 第 6 条）。
 *
 * <p>轨迹是可丢的诊断材料：{@code AgentStepSink} 吞掉一切异常，写失败绝不触发模型调用重发（§10）。
 */
@TableName(value = "agent_step", autoResultMap = true)
@Getter
@Setter
public class AgentStepEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long runId;

    private Integer stepNo;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long modelCallId;

    /** TraceEventType 的字面值，不用枚举 name() 反序列化（前端按字符串匹配）。 */
    private String eventType;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String toolName;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String toolCallId;

    @TableField(typeHandler = Jackson3TypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private Map<String, Object> argumentsRedacted;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String resultSummary;

    private String status;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long durationMs;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorCode;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
