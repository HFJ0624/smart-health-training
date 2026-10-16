package com.hfj.fitagent.modules.workout.entity;

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
 * workout_session_entry。plan_item_id 为 NULL 表示用户自主添加的动作——
 * 它不参与"组数完成度"的分母，只单列成额外条目，避免把自主训练算成超额完成。
 */
@TableName("workout_session_entry")
@Getter
@Setter
public class WorkoutSessionEntryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long sessionId;

    private Long exerciseId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long planItemId;

    private Integer positionNo;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer targetDurationSeconds;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal distanceMeters;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String notes;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
