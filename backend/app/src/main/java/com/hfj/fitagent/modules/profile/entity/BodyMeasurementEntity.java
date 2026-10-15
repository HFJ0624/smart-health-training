package com.hfj.fitagent.modules.profile.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * body_measurement。
 *
 * business_date 由服务端从 measured_at 按用户时区推导，客户端不能传；
 * "最新体重"按 measured_at 判定，所以补录更早业务日期的记录不会成为最新。
 */
@TableName("body_measurement")
@Getter
@Setter
public class BodyMeasurementEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Instant measuredAt;

    private LocalDate businessDate;

    private BigDecimal weightKg;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal waistCm;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal bodyFatPercent;

    /** MANUAL / IMPORT；本轮恒为 MANUAL */
    private String source;

    /** 客户端去重键，服务端规范化为小写；创建后不可变 */
    private String clientRecordUuid;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String notes;

    @Version
    private Integer lockVersion;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
