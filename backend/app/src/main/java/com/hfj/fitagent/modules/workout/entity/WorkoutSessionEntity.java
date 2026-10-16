package com.hfj.fitagent.modules.workout.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.hfj.fitagent.modules.workout.WorkoutSessionStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * workout_session。
 *
 * `active_user_id` 是数据库派生的 STORED 生成列（草稿态才等于 user_id），实体里**不声明它**，
 * 任何 INSERT/UPDATE 都不能带它——"每用户至多一个进行中草稿"完全由 UNIQUE(active_user_id) 兜住。
 * timezone 是开始时刻的快照：账户之后改时区不回写历史记录，否则补录记录的业务日期会被追溯改写。
 */
@TableName("workout_session")
@Getter
@Setter
public class WorkoutSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 幂等键；服务端规范化小写后入库，编辑时不可变 */
    private String clientRecordUuid;

    private LocalDate businessDate;

    private String timezone;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long planId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long planDayId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer planRevisionNo;

    private Instant startedAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant endedAt;

    private WorkoutSessionStatus status;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String notes;

    @Version
    private Integer lockVersion;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
