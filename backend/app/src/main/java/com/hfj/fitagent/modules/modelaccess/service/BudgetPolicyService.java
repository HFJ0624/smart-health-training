package com.hfj.fitagent.modules.modelaccess.service;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.modelaccess.Vocabulary;
import com.hfj.fitagent.modules.modelaccess.ValidationErrors;
import com.hfj.fitagent.modules.modelaccess.api.ModelSelection;
import com.hfj.fitagent.modules.modelaccess.api.PriceQuote;
import com.hfj.fitagent.modules.modelaccess.api.ProviderRef;
import com.hfj.fitagent.modules.modelaccess.dto.UpdateBudgetPolicyRequest;
import com.hfj.fitagent.modules.modelaccess.entity.BudgetPolicyEntity;
import com.hfj.fitagent.modules.modelaccess.mapper.BudgetPolicyMapper;
import com.hfj.fitagent.modules.modelaccess.vo.BudgetPolicyVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 用户自己的预算策略（{@code budget_policy}）读写（设计 §6.3、§7 V15）。
 *
 * <p>三条口径：
 * <ol>
 *   <li><b>上限是"正数或留空"</b>：留空 = 该维度不设限；写 0 会被拒（列上的 CHECK 也拒），
 *       因为 0 在界面上极易被读成"不限"。两个上限同时留空也不允许保存——
 *       那等价于设计明确禁止的"默认放行"；</li>
 *   <li><b>币种是身份的一部分</b>：{@code (user_id, provider_id, currency)} 唯一，
 *       已存在的策略不允许就地改币种（改币种＝另一份账户），所以 PUT 只在没有策略时才用 currency 新建；</li>
 *   <li><b>{@code budget_timezone} 无写入路径</b>：实体里该列是双 NEVER，
 *       值由数据库 DEFAULT 产生，业务日归属永远由服务端按 Asia/Shanghai 推导（AGENTS.md：客户端不传日期）。</li>
 * </ol>
 *
 * <p>{@code enabled=false} 不等于"放行"：BudgetGate 会拒绝发起任何调用。
 */
@Service
public class BudgetPolicyService {

    /** DB 列是 TINYINT UNSIGNED：先按列宽拒绝，错误才可读。 */
    public static final int MAX_CALL_GATE = 255;

    private static final Logger log = LoggerFactory.getLogger(BudgetPolicyService.class);

    private final BudgetPolicyMapper policyMapper;
    private final ProviderCatalogService catalogService;

    public BudgetPolicyService(BudgetPolicyMapper policyMapper, ProviderCatalogService catalogService) {
        this.policyMapper = policyMapper;
        this.catalogService = catalogService;
    }

    /** 读取本人某供应商的策略；没有即 null（页面显示"未设置"，不是 404）。 */
    @Transactional(readOnly = true)
    public BudgetPolicyVo find(long userId, String providerCode, String currency) {
        ProviderRef provider = catalogService.requireProvider(providerCode);
        String requested = resolveReadCurrency(provider, currency);
        // 未指定币种时只在"该供应商只有一份策略"时才唯一确定；多币种即要求显式指定，不挑一个
        BudgetPolicyEntity entity = requested == null
                ? findSole(userId, provider)
                : policyMapper.find(userId, provider.providerId(), requested);
        return BudgetPolicyVo.of(entity, provider.providerCode());
    }

    /** 创建或更新本人策略（PUT 语义：带 lockVersion 的幂等覆盖）。 */
    @Transactional
    public BudgetPolicyVo upsert(long userId, String providerCode, UpdateBudgetPolicyRequest request) {
        ProviderRef provider = catalogService.requireProvider(providerCode);
        validate(request);

        String requestedCurrency = blankToNull(Vocabulary.canonicalCurrency(request.currency()));
        BudgetPolicyEntity existing = requestedCurrency == null ? null
                : policyMapper.find(userId, provider.providerId(), requestedCurrency);
        if (existing == null && requestedCurrency == null) {
            // 未指定币种时先看是否已经只有一份策略（就地改），否则从生效报价唯一确定币种（新建）
            existing = findSole(userId, provider);
        }
        if (existing != null) {
            return toVo(update(userId, provider, existing, request), provider);
        }
        return toVo(create(userId, provider, request, resolveCreateCurrency(provider, requestedCurrency)), provider);
    }

    /** 预算上下文用：精确取一份策略（币种必须已知，绝不做"随便挑一种"）。 */
    @Transactional(readOnly = true)
    public BudgetPolicyEntity requirePolicy(long userId, long providerId, String currency) {
        return policyMapper.find(userId, providerId, Vocabulary.canonicalCurrency(currency));
    }

    /** 该用户在该供应商上的策略币种清单（歧义判断用）。 */
    @Transactional(readOnly = true)
    public List<String> currenciesOf(long userId, long providerId) {
        return policyMapper.findCurrencies(userId, providerId);
    }

    private BudgetPolicyEntity create(long userId, ProviderRef provider, UpdateBudgetPolicyRequest request,
                                      String currency) {
        BudgetPolicyEntity entity = new BudgetPolicyEntity();
        entity.setUserId(userId);
        entity.setProviderId(provider.providerId());
        entity.setCurrency(currency);
        entity.setMaxTaskCost(request.maxTaskCost());
        entity.setMaxDailyCost(request.maxDailyCost());
        // budgetTimezone 不设置：由 DEFAULT 'Asia/Shanghai' 落值，实体该列是双 NEVER
        entity.setMaxModelCalls(request.maxModelCalls());
        entity.setMaxToolCalls(request.maxToolCalls());
        entity.setMaxOutputTokens(request.maxOutputTokens());
        entity.setEnabled(request.enabled() == null ? Boolean.TRUE : request.enabled());
        try {
            policyMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.VERSION_CONFLICT, "该供应商的预算策略已被并发创建，请重新读取后重试");
        }
        log.info("预算策略已创建 userId={} providerCode={} currency={}", userId, provider.providerCode(), currency);
        return reload(userId, provider.providerId(), currency);
    }

    private BudgetPolicyEntity update(long userId, ProviderRef provider, BudgetPolicyEntity existing,
                                      UpdateBudgetPolicyRequest request) {
        if (request.lockVersion() == null) {
            throw ValidationErrors.field("lockVersion", "REQUIRED", "更新预算策略必须携带 lockVersion", null);
        }
        if (!Objects.equals(existing.getLockVersion(), request.lockVersion())) {
            throw BizException.versionConflict();
        }
        // 币种不改：它是这份账户的身份；请求里带了不同的币种即拒绝而不是悄悄换行
        if (blankToNull(Vocabulary.canonicalCurrency(request.currency())) != null
                && !existing.getCurrency().equalsIgnoreCase(request.currency().trim())) {
            throw ValidationErrors.field("currency", "IMMUTABLE_FIELD", "已存在的策略不可修改币种", request.currency());
        }
        existing.setMaxTaskCost(request.maxTaskCost());
        existing.setMaxDailyCost(request.maxDailyCost());
        if (request.maxModelCalls() != null) {
            existing.setMaxModelCalls(request.maxModelCalls());
        }
        if (request.maxToolCalls() != null) {
            existing.setMaxToolCalls(request.maxToolCalls());
        }
        if (request.maxOutputTokens() != null) {
            existing.setMaxOutputTokens(request.maxOutputTokens());
        }
        if (request.enabled() != null) {
            existing.setEnabled(request.enabled());
        }
        if (policyMapper.updateById(existing) == 0) {
            throw BizException.versionConflict();
        }
        return reload(userId, provider.providerId(), existing.getCurrency());
    }

    /** 字段校验：只做单位/正数/长度/枚举这一类，不引入任何未经说明的阈值。 */
    private void validate(UpdateBudgetPolicyRequest request) {
        if (request == null) {
            throw ValidationErrors.field("policy", "REQUIRED", "请求体不能为空", null);
        }
        ValidationErrors.requirePositiveOrNull("maxTaskCost", request.maxTaskCost());
        ValidationErrors.requirePositiveOrNull("maxDailyCost", request.maxDailyCost());
        ValidationErrors.requirePositive("maxModelCalls", request.maxModelCalls());
        ValidationErrors.requirePositive("maxToolCalls", request.maxToolCalls());
        ValidationErrors.requirePositive("maxOutputTokens", request.maxOutputTokens());
        ValidationErrors.requireRange("maxModelCalls", request.maxModelCalls(), 1, MAX_CALL_GATE);
        ValidationErrors.requireRange("maxToolCalls", request.maxToolCalls(), 1, MAX_CALL_GATE);
        String currency = blankToNull(Vocabulary.canonicalCurrency(request.currency()));
        if (currency != null) {
            ValidationErrors.requireEnum("currency", currency, Vocabulary.CURRENCY);
            if (currency.length() != 3) {
                throw ValidationErrors.field("currency", "INVALID_CURRENCY", "币种必须是三位 ISO-4217 代码", request.currency());
            }
        }
        // 两个上限都留空 = 默认放行，设计 §7 V15 明确禁止这种策略存在
        if (request.maxTaskCost() == null && request.maxDailyCost() == null) {
            throw ValidationErrors.field("maxDailyCost", "AT_LEAST_ONE_LIMIT",
                    "maxTaskCost 与 maxDailyCost 至少要设置一个上限", null);
        }
    }

    /** 读取时的币种：请求给了就按请求；没给则该供应商只有一份策略时才唯一确定。 */
    private String resolveReadCurrency(ProviderRef provider, String requested) {
        String currency = blankToNull(Vocabulary.canonicalCurrency(requested));
        if (currency == null) {
            return null;
        }
        ValidationErrors.requireEnum("currency", currency, Vocabulary.CURRENCY);
        return currency;
    }

    private BudgetPolicyEntity findSole(long userId, ProviderRef provider) {
        List<String> currencies = policyMapper.findCurrencies(userId, provider.providerId());
        if (currencies.size() > 1) {
            throw ValidationErrors.field("currency", "REQUIRED",
                    "该供应商存在多种币种的策略，必须指定 currency", currencies);
        }
        return currencies.isEmpty() ? null : policyMapper.find(userId, provider.providerId(), currencies.get(0));
    }

    /** 新建时的币种：请求优先，否则由生效报价唯一确定（多币种即要求显式指定，不猜）。 */
    private String resolveCreateCurrency(ProviderRef provider, String requested) {
        if (requested != null) {
            return requested;
        }
        ModelSelection selection = catalogService.selectModel(provider, null);
        List<String> currencies = catalogService.enabledCurrencies(selection);
        if (currencies.size() != 1) {
            throw ValidationErrors.field("currency", "REQUIRED",
                    "后端存在多种生效报价币种，必须指定 currency", currencies);
        }
        return currencies.get(0);
    }

    private BudgetPolicyEntity reload(long userId, long providerId, String currency) {
        BudgetPolicyEntity reloaded = policyMapper.find(userId, providerId, currency);
        if (reloaded == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "策略写入后读取失败");
        }
        return reloaded;
    }

    private static BudgetPolicyVo toVo(BudgetPolicyEntity entity, ProviderRef provider) {
        return BudgetPolicyVo.of(entity, provider.providerCode());
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** 报价币种（预算上下文用）：没有生效报价就没有可用的预算币种。 */
    @Transactional(readOnly = true)
    public String quoteCurrency(ProviderRef provider) {
        ModelSelection selection = catalogService.selectModel(provider, null);
        PriceQuote quote = catalogService.peakQuote(selection, null);
        return quote.currency();
    }
}
