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
 * workout_session_set。一组一行，is_completed 是统计里"已确认完成组数"的唯一依据。
 * reps / external_load_kg / duration_seconds 的 NULL 是"未记录"，与 0 完全不同义：
 * 自重必须由用户显式填 0，服务层不替任何人补值。
 */
@TableName("workout_session_set")
@Getter
@Setter
public class WorkoutSessionSetEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long entryId;

    private Integer setNo;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer reps;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal externalLoadKg;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer durationSeconds;

    private Boolean isCompleted;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String notes;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
