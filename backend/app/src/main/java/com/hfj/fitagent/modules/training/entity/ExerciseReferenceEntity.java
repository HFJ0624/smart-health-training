package com.hfj.fitagent.modules.training.entity;

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

/**
 * exercise_reference（系统维护的动作字典，本轮只读）。
 *
 * category 不是展示标签：它决定计划条目的字段校验矩阵
 * （STRENGTH 必填组数禁时长 / CARDIO 必填时长禁组数次负重休息 / MOBILITY 至少一项训练量禁负重）。
 */
@TableName(value = "exercise_reference", autoResultMap = true)
@Getter
@Setter
public class ExerciseReferenceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String exerciseCode;

    private String name;

    /** STRENGTH / CARDIO / MOBILITY */
    private String category;

    @TableField(typeHandler = Jackson3TypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private List<String> muscleGroups;

    @TableField(typeHandler = Jackson3TypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private List<String> equipment;

    /** 本轮全部为 NULL：不引入未经来源核对的动作说明 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String instructions;

    /** 本轮全部为 NULL：不编造权威链接 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String sourceUrl;

    /** ACTIVE / DISABLED */
    private String status;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
