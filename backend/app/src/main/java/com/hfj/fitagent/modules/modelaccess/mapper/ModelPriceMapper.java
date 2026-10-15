package com.hfj.fitagent.modules.modelaccess.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.modelaccess.entity.ModelPriceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * model_price 持久化。
 *
 * <p>本表无 JSON 列，自定义 {@code @Select} 可以安全返回实体（DECIMAL(20,10) → BigDecimal）。
 *
 * <p>所有查询都带 {@code enabled = 1}：报价默认 false，未被显式启用的行（协议 §1 的
 * {@code deepseek-v4-pro}）在结构上就没有被读到的路径，因此不存在"代码里的默认价"这种东西。
 */
@Mapper
public interface ModelPriceMapper extends BaseMapper<ModelPriceEntity> {

    /** 某模型某档位的生效报价，按版本倒序；多于一个币种时由 Service 拒掉而不是折算。 */
    @Select("""
            SELECT id, model_id, price_version, currency, rate_tier,
                   input_hit_per_million, input_miss_per_million, output_per_million,
                   source_url, source_checked_on, enabled, created_at, updated_at
            FROM model_price
            WHERE model_id = #{modelId} AND rate_tier = #{rateTier} AND enabled = 1
            ORDER BY price_version DESC, currency ASC
            """)
    List<ModelPriceEntity> findEnabledForTier(@Param("modelId") long modelId, @Param("rateTier") String rateTier);

    /** 指定币种的生效报价（取版本最大的一行）。 */
    @Select("""
            SELECT id, model_id, price_version, currency, rate_tier,
                   input_hit_per_million, input_miss_per_million, output_per_million,
                   source_url, source_checked_on, enabled, created_at, updated_at
            FROM model_price
            WHERE model_id = #{modelId} AND rate_tier = #{rateTier} AND currency = #{currency} AND enabled = 1
            ORDER BY price_version DESC
            LIMIT 1
            """)
    ModelPriceEntity findEnabled(@Param("modelId") long modelId, @Param("rateTier") String rateTier,
                                 @Param("currency") String currency);

    /** 该模型生效报价涉及的币种（预算币种默认值推导用；多于一种即歧义）。 */
    @Select("""
            SELECT DISTINCT currency FROM model_price
            WHERE model_id = #{modelId} AND enabled = 1
            ORDER BY currency ASC
            """)
    List<String> findEnabledCurrencies(@Param("modelId") long modelId);
}
