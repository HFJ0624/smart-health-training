package com.hfj.fitagent.modules.training.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * training_plan（计划版本头，不可覆盖）。
 *
 * 刻意不声明 active_user_id：它是数据库派生的 STORED 生成列，
 * "每用户最多一个 ACTIVE" 交给 UNIQUE(active_user_id) + 激活事务，而不是应用层自觉。
 *
 * 本阶段没有 source_job_id / source_run_id 两列：它们指向 P3 的 processing_job 与 P2 的 agent_run，
 * 本轮不建那两张表。加回方式见 docs/p1-database.md §4.2。
 */
@TableName(value = "training_plan", autoResultMap = true)
@Getter
@Setter
public class TrainingPlanEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 同一计划各版本共享；服务端生成小写 UUID，永不由客户端指定 */
    private String planGroupUuid;

    /** 服务端取组内 max+1 */
    private Integer revisionNo;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long basePlanId;

    private String title;

    /** MANUAL / IMPORT / AI —— 本轮只产生前两种，AI 枚举值保留但没有写入路径 */
    private String source;

    /** DRAFT / ACTIVE / ARCHIVED / DISCARDED */
    private String status;

    /** 服务端生成，带 schema_version 与缺失项列表；客户端无法注入 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private GoalSnapshot goalSnapshot;

    private LocalDate startsOn;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate endsOn;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant confirmedAt;

    @Version
    private Integer lockVersion;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
