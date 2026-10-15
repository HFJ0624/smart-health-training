package com.hfj.fitagent.modules.modelaccess.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * model_catalog：模型能力目录（V11）。
 *
 * <p>模型 ID、上下文与输出上限、是否支持 tools 的<b>唯一</b>存放处：代码里不写死任何一个数字
 * （设计 §5 第 8 条）。读不到 ACTIVE 行就报 MODEL_NOT_CONFIGURED，绝不回退到内置默认模型。
 *
 * <p>{@code capabilitySnapshot} 是 JSON 列：必须 {@code autoResultMap = true} + 逐字段
 * {@link Jackson3TypeHandler}（Boot 4 的 classpath 没有 Jackson 2 databind，且禁止包扫描 type-handler）。
 * 因此本表<b>不能</b>用自定义 {@code @Select} 返回实体——自定义 SQL 不带 autoResultMap，
 * JSON 列会退化成字符串（沿用 {@code TrainingPlanMapper} 的同一约束）。
 */
@TableName(value = "model_catalog", autoResultMap = true)
@Getter
@Setter
public class ModelCatalogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long providerId;

    /** 请求体 model 字段的取值（如 deepseek-flash）；服务端规范化小写后精确匹配。 */
    private String modelCode;

    /** 界面显示名（可为官方"实际模型版本"）。 */
    private String displayName;

    private Boolean supportsVision;

    private Boolean supportsTools;

    private Boolean supportsJson;

    private Integer maxContextTokens;

    private Integer maxOutputTokens;

    /** 核查原文快照，NOT NULL；形状由协议文档约束，本类只做透传。 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private Map<String, Object> capabilitySnapshot;

    /** 能力核查日期（不是创建时间）。 */
    private LocalDate sourceCheckedOn;

    /** ACTIVE / DISABLED。 */
    private String status;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
