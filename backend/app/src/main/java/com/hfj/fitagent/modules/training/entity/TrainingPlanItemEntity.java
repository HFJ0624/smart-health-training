package com.hfj.fitagent.modules.training.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * training_plan_item。
 *
 * 可空列的语义严格区分：NULL = 未设定，0 = 明确设为 0（外部负重 0 表示自重）。
 * 因此这些列 updateStrategy=ALWAYS，允许把值清回 NULL；而 CHECK 只兜住局部约束（正数、reps 上下界），
 * 按 category 的字段矩阵由 Service 校验。
 */
@TableName("training_plan_item")
@Getter
@Setter
public class TrainingPlanItemEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long planDayId;

    private Long exerciseId;

    /** 服务端按数组顺序规范化为 1..n；UNIQUE(plan_day_id, position_no) 兜住重复 */
    private Integer positionNo;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer targetSets;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer repsMin;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer repsMax;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal targetExternalLoadKg;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer targetDurationSeconds;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer restSeconds;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String notes;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
