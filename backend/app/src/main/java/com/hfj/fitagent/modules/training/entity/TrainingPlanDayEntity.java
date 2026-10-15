package com.hfj.fitagent.modules.training.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * training_plan_day。plan_id 指向具体版本，因此某个星期在某历史版本里的内容不会被后续草稿改写。
 * UNIQUE(plan_id, iso_weekday) 在数据库层保证"每个星期最多一个计划日"。
 * "休息日不得带动作"是跨表规则，数据库做不到，由 Service 校验。
 */
@TableName("training_plan_day")
@Getter
@Setter
public class TrainingPlanDayEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long planId;

    /** ISO 星期 1..7 */
    private Integer isoWeekday;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String title;

    private Boolean isRestDay;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String notes;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
