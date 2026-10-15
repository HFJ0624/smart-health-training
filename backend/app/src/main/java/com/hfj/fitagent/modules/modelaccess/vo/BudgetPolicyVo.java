package com.hfj.fitagent.modules.modelaccess.vo;

import com.hfj.fitagent.modules.modelaccess.entity.BudgetPolicyEntity;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 预算策略出参。
 *
 * <p>两个上限原样带出（含 null），让界面能区分"不设限"与"设成某个很小的值"；
 * {@code budgetTimezone} 只读回显，写入路径不存在。
 *
 * @param id            字符串化 BIGINT
 * @param providerId    字符串化供应商 id
 * @param currency      限额币种；与 model_price.currency 一致才可比
 * @param budgetTimezone 业务日推导时区（恒 Asia/Shanghai）
 * @param lockVersion   乐观锁版本，PUT 必须带回
 */
public record BudgetPolicyVo(String id,
                             String providerId,
                             String providerCode,
                             String currency,
                             BigDecimal maxTaskCost,
                             BigDecimal maxDailyCost,
                             String budgetTimezone,
                             Integer maxModelCalls,
                             Integer maxToolCalls,
                             Integer maxOutputTokens,
                             Boolean enabled,
                             Integer lockVersion,
                             Instant createdAt,
                             Instant updatedAt) {

    private static String str(Long value) {
        return value == null ? null : Long.toString(value);
    }

    public static BudgetPolicyVo of(BudgetPolicyEntity entity, String providerCode) {
        if (entity == null) {
            return null;
        }
        return new BudgetPolicyVo(str(entity.getId()), str(entity.getProviderId()), providerCode,
                entity.getCurrency(), entity.getMaxTaskCost(), entity.getMaxDailyCost(),
                entity.getBudgetTimezone(), entity.getMaxModelCalls(), entity.getMaxToolCalls(),
                entity.getMaxOutputTokens(), entity.getEnabled(), entity.getLockVersion(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
