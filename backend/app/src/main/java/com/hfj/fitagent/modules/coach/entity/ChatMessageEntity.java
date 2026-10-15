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
import java.util.List;
import java.util.Map;

/**
 * chat_message（V22）：会话消息。
 *
 * <p>{@code role} 是小写字面量（§11.1 第 5 条：前端按 {@code user}/{@code assistant} 匹配），
 * 与 chat_message 之外的一切大写枚举分开。四个 JSON 列同样要求
 * {@code autoResultMap = true} + 逐字段 {@link Jackson3TypeHandler}，因此本表读取只走 BaseMapper。
 *
 * <p>{@code factCards} 是<b>服务端按引用从工具结果取值</b>写下的完整事实卡片（§9：个人数值不由模型填写），
 * 历史消息展示时不需要重跑任何计算。缺失项走 {@code missingFields}，不以 0 代替未知。
 */
@TableName(value = "chat_message", autoResultMap = true)
@Getter
@Setter
public class ChatMessageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long conversationId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long runId;

    /** 会话内单调序号，在会话行锁下由 MessageOrderAllocator 分配。 */
    private Integer messageNo;

    /** user / assistant / tool / system（小写）。历史回放只取前两种。 */
    private String role;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String contentText;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String toolCallId;

    @TableField(typeHandler = Jackson3TypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private List<Map<String, Object>> toolCalls;

    @TableField(typeHandler = Jackson3TypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private List<String> citations;

    @TableField(typeHandler = Jackson3TypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private List<Map<String, Object>> factCards;

    @TableField(typeHandler = Jackson3TypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private List<String> missingFields;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long dataRevision;

    private Boolean isStale;

    /** COMPLETE / INCOMPLETE（停止收尾的回答必须如实标 INCOMPLETE）。 */
    private String status;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
