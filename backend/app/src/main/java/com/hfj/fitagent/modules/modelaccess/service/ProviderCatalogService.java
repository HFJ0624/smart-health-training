package com.hfj.fitagent.modules.modelaccess.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.modelaccess.Vocabulary;
import com.hfj.fitagent.modules.modelaccess.ValidationErrors;
import com.hfj.fitagent.modules.modelaccess.api.ModelSelection;
import com.hfj.fitagent.modules.modelaccess.api.PriceQuote;
import com.hfj.fitagent.modules.modelaccess.api.ProviderRef;
import com.hfj.fitagent.modules.modelaccess.entity.ModelCatalogEntity;
import com.hfj.fitagent.modules.modelaccess.entity.ModelPriceEntity;
import com.hfj.fitagent.modules.modelaccess.entity.ModelProviderEntity;
import com.hfj.fitagent.modules.modelaccess.mapper.ModelCatalogMapper;
import com.hfj.fitagent.modules.modelaccess.mapper.ModelPriceMapper;
import com.hfj.fitagent.modules.modelaccess.mapper.ModelProviderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 供应商 / 模型目录 / 生效报价的解析出口（设计 §6.2）。
 *
 * <p>三张表在这里合成两个值对象（{@link ModelSelection}、{@link PriceQuote}），
 * 目的是让调用方<b>永远不需要</b>接触实体，也不需要接触凭据的秘密材料：
 * 报价与凭据在类型上就是两条互不相交的出口。
 *
 * <p>三条拒绝路径都返回 {@code MODEL_NOT_CONFIGURED} 而不是给个默认值：
 * <ul>
 *   <li>供应商不在白名单或非 ACTIVE；</li>
 *   <li>该供应商没有 ACTIVE 模型行（读不到就报错，绝不回退内置默认模型）；</li>
 *   <li>没有 {@code enabled=1} 的报价行——这包括 {@code deepseek-v4-pro}：
 *       官方两处公告自相矛盾（协议 §1），其种子行必须保持未启用，
 *       因此"实际可用"的模型目前只有 {@code deepseek-flash}，除非用户显式启用 pro 的报价。</li>
 * </ul>
 *
 * <p>币种规则：同一模型同一档位若同时启用了 CNY 与 USD 两种报价，本服务<b>不折算、不挑一个</b>，
 * 直接报 MODEL_NOT_CONFIGURED 要求运维消歧（设计 §6.3"跨币种永不相减"的同一立场）。
 */
@Service
public class ProviderCatalogService {

    private final ModelProviderMapper providerMapper;
    private final ModelCatalogMapper catalogMapper;
    private final ModelPriceMapper priceMapper;

    public ProviderCatalogService(ModelProviderMapper providerMapper,
                                  ModelCatalogMapper catalogMapper,
                                  ModelPriceMapper priceMapper) {
        this.providerMapper = providerMapper;
        this.catalogMapper = catalogMapper;
        this.priceMapper = priceMapper;
    }

    /** 白名单供应商（ACTIVE）。凭据与余额接口都先经这一步拿到 providerId 与 base_url。 */
    @Transactional(readOnly = true)
    public ProviderRef requireProvider(String providerCode) {
        String code = Vocabulary.canonicalProviderCode(providerCode);
        if (code.isEmpty()) {
            throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED, "供应商码为空");
        }
        ModelProviderEntity provider = providerMapper.selectOne(new QueryWrapper<ModelProviderEntity>()
                .eq("provider_code", code)
                .eq("status", Vocabulary.STATUS_ACTIVE));
        if (provider == null) {
            throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED, "供应商未配置或已停用: " + code);
        }
        return new ProviderRef(provider.getId(), provider.getProviderCode(), provider.getDisplayName(),
                provider.getBaseUrl(), provider.getAdapterCode(),
                Boolean.TRUE.equals(provider.getBalanceSupported()));
    }

    /** 当前 ACTIVE 供应商数（预算上下文用它判断"能否唯一确定供应商"）。 */
    @Transactional(readOnly = true)
    public int activeProviderCount() {
        return providerMapper.countActive();
    }

    @Transactional(readOnly = true)
    public ProviderRef requireProviderById(long providerId) {
        ModelProviderEntity provider = providerMapper.selectOne(new QueryWrapper<ModelProviderEntity>()
                .eq("id", providerId)
                .eq("status", Vocabulary.STATUS_ACTIVE));
        if (provider == null) {
            throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED, "供应商未配置或已停用: id=" + providerId);
        }
        return new ProviderRef(provider.getId(), provider.getProviderCode(), provider.getDisplayName(),
                provider.getBaseUrl(), provider.getAdapterCode(),
                Boolean.TRUE.equals(provider.getBalanceSupported()));
    }

    /**
     * 选定模型：调用方给了 modelCode 就精确匹配，没给则该供应商必须只有一个 ACTIVE 模型，
     * 否则"替用户挑一个"会把计费与能力都挑错。
     */
    @Transactional(readOnly = true)
    public ModelSelection selectModel(ProviderRef provider, String modelCode) {
        String normalized = Vocabulary.canonicalModelCode(modelCode);
        Long modelId;
        if (normalized.isEmpty()) {
            int active = catalogMapper.countActive(provider.providerId());
            if (active != 1) {
                throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED,
                        active == 0 ? "该供应商没有可用模型: " + provider.providerCode()
                                    : "该供应商有多个可用模型，必须显式指定 modelCode");
            }
            modelId = catalogMapper.findFirstActiveId(provider.providerId());
        } else {
            modelId = catalogMapper.findActiveIdByModelCode(provider.providerId(), normalized);
        }
        if (modelId == null) {
            throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED, "没有可用的模型目录行");
        }
        return loadSelection(provider, modelId);
    }

    /** 按 id 直接取模型行（预算上下文已有 modelId 时用，避免二次择一逻辑）。 */
    @Transactional(readOnly = true)
    public ModelSelection loadSelection(ProviderRef provider, long modelId) {
        // 含 JSON 列，只能走 BaseMapper（autoResultMap）；自定义 @Select 会丢掉 Jackson3TypeHandler
        ModelCatalogEntity model = catalogMapper.selectOne(new QueryWrapper<ModelCatalogEntity>()
                .eq("id", modelId)
                .eq("provider_id", provider.providerId())
                .eq("status", Vocabulary.STATUS_ACTIVE));
        if (model == null) {
            throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED, "模型目录行不可用: id=" + modelId);
        }
        return new ModelSelection(provider.providerId(), provider.providerCode(), provider.adapterCode(),
                model.getId(), model.getModelCode(), model.getDisplayName(),
                bool(model.getSupportsVision()), bool(model.getSupportsTools()), bool(model.getSupportsJson()),
                requirePositive("maxContextTokens", model.getMaxContextTokens()),
                requirePositive("maxOutputTokens", model.getMaxOutputTokens()),
                model.getSourceCheckedOn());
    }

    /**
     * 取生效报价。{@code currency} 为空时要求该档位下只有一种币种，否则拒绝（不猜、不折算）。
     */
    @Transactional(readOnly = true)
    public PriceQuote quote(ModelSelection selection, String rateTier, String currency) {
        String tier = requireTier(rateTier);
        String wantedCurrency = currency == null || currency.isBlank()
                ? null : Vocabulary.canonicalCurrency(currency);
        List<ModelPriceEntity> rows = priceMapper.findEnabledForTier(selection.modelId(), tier);
        if (rows.isEmpty()) {
            // 包括 deepseek-v4-pro：种子行 enabled=false，因此走到这里就是"没有已启用的报价"
            throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED,
                    "模型没有已启用的 " + tier + " 档报价: " + selection.modelCode());
        }
        if (wantedCurrency == null) {
            String distinct = rows.stream().map(ModelPriceEntity::getCurrency)
                    .map(Vocabulary::canonicalCurrency).distinct().reduce((a, b) -> a + "/" + b).orElse("");
            if (!distinct.contains("/")) {
                wantedCurrency = distinct;
            } else {
                throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED,
                        "同一模型同一档位存在多种币种报价，需显式指定 currency: " + distinct);
            }
        }
        // wantedCurrency 是入参且上面可能被推导过（非 effectively final），lambda 里必须用它的 final 副本
        final String resolvedCurrency = wantedCurrency;
        ModelPriceEntity row = rows.stream()
                .filter(r -> resolvedCurrency.equalsIgnoreCase(Vocabulary.canonicalCurrency(r.getCurrency())))
                .findFirst()   // mapper 已按 price_version DESC 排序，首行即最高生效版本
                .orElse(null);
        if (row == null) {
            throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED,
                    "模型没有已启用的 " + tier + "/" + wantedCurrency + " 报价: " + selection.modelCode());
        }
        return toQuote(row);
    }

    /** 保守预占用的报价：恒取 PEAK 档（协议 §7：节假日日历未核实，不得假定谷段）。 */
    @Transactional(readOnly = true)
    public PriceQuote peakQuote(ModelSelection selection, String currency) {
        return quote(selection, Vocabulary.RATE_PEAK, currency);
    }

    /** 该模型生效报价涉及的币种（预算接口的 currency 推导用）。 */
    @Transactional(readOnly = true)
    public List<String> enabledCurrencies(ModelSelection selection) {
        List<String> currencies = priceMapper.findEnabledCurrencies(selection.modelId()).stream()
                .map(Vocabulary::canonicalCurrency).distinct().toList();
        if (currencies.isEmpty()) {
            throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED,
                    "模型没有已启用的报价，无法确定预算币种: " + selection.modelCode());
        }
        return currencies;
    }

    private static PriceQuote toQuote(ModelPriceEntity row) {
        return new PriceQuote(Vocabulary.canonicalCurrency(row.getCurrency()),
                row.getInputHitPerMillion(), row.getInputMissPerMillion(), row.getOutputPerMillion(),
                requirePositive("priceVersion", row.getPriceVersion()), row.getRateTier(), row.getModelId());
    }

    private static String requireTier(String rateTier) {
        String tier = rateTier == null ? "" : rateTier.trim().toUpperCase(java.util.Locale.ROOT);
        ValidationErrors.requireEnum("rateTier", tier, Vocabulary.RATE_TIER);
        if (tier.isEmpty()) {
            throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED, "报价档位不能为空");
        }
        return tier;
    }

    private static boolean bool(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private static int requirePositive(String field, Integer value) {
        if (value == null || value <= 0) {
            throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED, field + " 缺失或非正数");
        }
        return value;
    }
}
