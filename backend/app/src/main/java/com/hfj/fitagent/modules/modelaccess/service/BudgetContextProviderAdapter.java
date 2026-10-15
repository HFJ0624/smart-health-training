package com.hfj.fitagent.modules.modelaccess.service;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.billing.api.BudgetContext;
import com.hfj.fitagent.modules.billing.api.BudgetContextProvider;
import com.hfj.fitagent.modules.billing.api.UnitPrices;
import com.hfj.fitagent.modules.modelaccess.Vocabulary;
import com.hfj.fitagent.modules.modelaccess.api.ModelSelection;
import com.hfj.fitagent.modules.modelaccess.api.PriceQuote;
import com.hfj.fitagent.modules.modelaccess.api.ProviderRef;
import com.hfj.fitagent.modules.modelaccess.entity.BudgetPolicyEntity;
import com.hfj.fitagent.modules.modelaccess.mapper.ModelCredentialMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 把 modelaccess 的三件事（供应商白名单、生效报价、用户策略）合成 billing 需要的 {@link BudgetContext}。
 *
 * <p>依赖方向说明（设计 §2 硬规则第 4 条的落地方式）：接口归 billing 所有，实现归 modelaccess 所有，
 * 因此 {@code billing → modelaccess} 这条边不存在——billing 看不到任何 Mapper 或实体，
 * 而 modelaccess 只多依赖一个纯接口。两边都不碰对方的表。
 *
 * <p>档位与保守性都固定在这里：给出的单价<b>永远是 PEAK 档</b>（协议 §7：节假日日历未核实，
 * 不能假定某时刻处在谷段），因此预占不会因为"当前正好是谷段"而算少。
 *
 * <p>失败语义：拿不到供应商 / 没有 ACTIVE 模型 / 没有生效报价都抛 {@code MODEL_NOT_CONFIGURED}；
 * 没有策略行时不抛错而是给出 {@code enabled=false} 的上下文，由 BudgetGate 拒绝发起调用
 * ——"没设预算"的正确后果是"不花钱"，不是"随便花"。
 */
@Component
public class BudgetContextProviderAdapter implements BudgetContextProvider {

    private final ProviderCatalogService catalogService;
    private final BudgetPolicyService policyService;
    private final ModelCredentialMapper credentialMapper;

    public BudgetContextProviderAdapter(ProviderCatalogService catalogService,
                                        BudgetPolicyService policyService,
                                        ModelCredentialMapper credentialMapper) {
        this.catalogService = catalogService;
        this.policyService = policyService;
        this.credentialMapper = credentialMapper;
    }

    /**
     * 单供应商部署的便捷解析：优先用"该用户唯一一把 ACTIVE 凭据"的供应商；
     * 没有凭据时退回"白名单里唯一的 ACTIVE 供应商"。任何歧义都直接报错，不替用户挑。
     */
    @Override
    @Transactional(readOnly = true)
    public BudgetContext contextFor(long userId) {
        List<Long> providers = credentialMapper.findActiveProviderIds(userId);
        if (providers.size() == 1) {
            return contextFor(userId, providers.get(0));
        }
        if (providers.isEmpty()) {
            if (catalogService.activeProviderCount() == 1) {
                // 还没有凭据也要能给出上下文：BudgetGate 会因策略缺失/凭据缺失拒绝，而不是放行
                throw new BizException(ErrorCode.CREDENTIAL_MISSING, "尚未配置模型 API Key");
            }
            throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED, "无法唯一确定供应商，请在调用点显式指定 providerId");
        }
        throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED,
                "该用户在多个供应商上有在用凭据，必须显式指定 providerId");
    }

    @Override
    @Transactional(readOnly = true)
    public BudgetContext contextFor(long userId, long providerId) {
        ProviderRef provider = catalogService.requireProviderById(providerId);
        ModelSelection selection = catalogService.selectModel(provider, null);
        PriceQuote peak = catalogService.peakQuote(selection, null);
        UnitPrices prices = new UnitPrices(Vocabulary.canonicalCurrency(peak.currency()),
                peak.hit(), peak.miss(), peak.output(), peak.priceVersion(), peak.rateTier());

        BudgetPolicyEntity policy = policyService.requirePolicy(userId, providerId, prices.currency());
        if (policy == null) {
            // 没有策略：保持币种与单价可解析，但 enabled=false 让闸门拒绝发起（绝不按"不限"处理）
            return new BudgetContext(providerId, provider.providerCode(), prices.currency(), prices,
                    null, null, null, false);
        }
        return new BudgetContext(providerId, provider.providerCode(), policy.getCurrency(), prices,
                policy.getMaxDailyCost(), policy.getMaxTaskCost(), policy.getMaxModelCalls(),
                Boolean.TRUE.equals(policy.getEnabled()));
    }
}
