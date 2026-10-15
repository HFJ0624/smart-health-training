package com.hfj.fitagent.modules.modelaccess.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * model_price：版本化报价 + 峰谷档位（V12）。
 *
 * <p>三个单价都是"每 100 万 token 的金额"，列 DECIMAL(20,10) → Java 侧 {@link BigDecimal}，
 * 不用 double（AGENTS.md 度量规则）。
 *
 * <p>{@code enabled} 默认 false：报价必须"核查 + 运维显式启用"两个动作都完成才生效。
 * {@code deepseek-v4-pro} 因官方两处公告自相矛盾（协议 §1），其种子行必须保持 false，
 * 所以本模块的解析逻辑只认 {@code enabled = 1} 的行，没有任何"默认价"回退路径。
 */
@TableName("model_price")
@Getter
@Setter
public class ModelPriceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** FK → model_catalog.id。 */
    private Long modelId;

    /** 同一模型内的报价版本；官方调价时新增版本而不是改写历史行。 */
    private Integer priceVersion;

    /** ISO-4217 大写（CNY / USD）；不同币种永不相减、不猜汇率。 */
    private String currency;

    /** PEAK / OFF_PEAK（String，取值由 Vocabulary 显式判定）。 */
    private String rateTier;

    /** 对应 usage.prompt_cache_hit_tokens。 */
    private BigDecimal inputHitPerMillion;

    /** 对应 usage.prompt_cache_miss_tokens。 */
    private BigDecimal inputMissPerMillion;

    /** 对应 usage.completion_tokens；reasoning_tokens 是其细分项，不另计价。 */
    private BigDecimal outputPerMillion;

    private String sourceUrl;

    private LocalDate sourceCheckedOn;

    private Boolean enabled;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
