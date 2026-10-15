package com.hfj.fitagent.modules.profile.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * user_profile。与用户 1:1。本表没有体重列——体重唯一真相是 body_measurement。
 *
 * 度量一律 BigDecimal（设计包约定：不用 float/double 存需要精确的值）。
 * 可空业务字段 updateStrategy=ALWAYS：用户把腰围/器械清空时必须真的写成 NULL，
 * 而不是被 NOT_NULL 策略跳过、导致"删不掉"。
 *
 * JSON 列必须显式指定 Jackson3TypeHandler：Boot 4 的 classpath 上没有 Jackson 2 databind，
 * 用 JacksonTypeHandler 会 NoClassDefFoundError。并且不能配置 type-handlers-package 包扫描，
 * 因为 Jackson3TypeHandler 没有无参构造，扫描会 NoSuchMethodException。
 */
@TableName(value = "user_profile", autoResultMap = true)
@Getter
@Setter
public class UserProfileEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private LocalDate birthDate;

    /** MALE / FEMALE / UNSPECIFIED */
    private String sexForEquation;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal heightCm;

    /** BEGINNER / INTERMEDIATE / ADVANCED */
    private String trainingLevel;

    /** LIGHT / MODERATE / VIGOROUS；本轮不开放写入 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String activityCategory;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant activityConfirmedAt;

    /** 本轮无消费方，不开放写入；列保留以兼容设计包 */
    @TableField(typeHandler = Jackson3TypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private List<String> dietPreferences;

    @TableField(typeHandler = Jackson3TypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private List<String> equipment;

    @TableField(typeHandler = Jackson3TypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private List<String> trainingConstraints;

    private Boolean professionalGuidanceRequired;

    @Version
    private Integer lockVersion;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
