package com.hfj.fitagent.modules.billing;

import com.hfj.fitagent.modules.billing.entity.BudgetDailyUsageEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 当日预算账户的<b>值对象</b>：三列分账 + 额度判断的唯一口径。
 *
 * <p>业务日推导：{@code budget_date} 由"调用开始时刻"按 {@code Asia/Shanghai} 推导（设计 §6.3、V15/V16 注释）。
 * 因此跨午夜的长任务里，每次调用各归自己的开始日，而单次任务上限（走 run 维度的求和）仍覆盖全部调用——
 * 这两个维度不是一回事，不能用日账本去近似任务上限。
 *
 * <p>币种隔离：本对象的一切算术都只在自己的 {@code currency} 内发生。
 * {@link #requireSameCurrency} 会在跨币种比较的第一时间抛错——
 * 猜汇率或把 CNY 与 USD 相减会把预算闸门变成一个随机数发生器。
 *
 * @param settledCost   已按实际 usage 核算落定的钱
 * @param reservedCost  已预占、请求在途或结果仍可知的钱
 * @param uncertainCost 已发出但 usage 不明的钱：仍占用额度，不重发也不归零
 */
public record DailyBudgetAccount(long id,
                                 long userId,
                                 long providerId,
                                 String currency,
                                 LocalDate budgetDate,
                                 BigDecimal settledCost,
                                 BigDecimal reservedCost,
                                 BigDecimal uncertainCost) {

    /** 本轮唯一允许的预算时区（budget_policy.budget_timezone 的固定值）。 */
    public static final ZoneId BUDGET_ZONE = ZoneId.of("Asia/Shanghai");

    public DailyBudgetAccount {
        Objects.requireNonNull(currency, "账户必须有币种");
        Objects.requireNonNull(budgetDate, "账户必须有业务日");
        settledCost = nullToZero(settledCost);
        reservedCost = nullToZero(reservedCost);
        uncertainCost = nullToZero(uncertainCost);
    }

    public static DailyBudgetAccount from(BudgetDailyUsageEntity entity) {
        return new DailyBudgetAccount(entity.getId(), entity.getUserId(), entity.getProviderId(),
                entity.getCurrency(), entity.getBudgetDate(),
                entity.getSettledCost(), entity.getReservedCost(), entity.getUncertainReservedCost());
    }

    /** 由调用开始时刻推导业务日（客户端不传日期）。 */
    public static LocalDate budgetDateOf(Instant callStart) {
        return LocalDate.ofInstant(Objects.requireNonNull(callStart, "调用开始时刻不能为空"), BUDGET_ZONE);
    }

    /** 已占用额度 = settled + reserved + uncertain_reserved：三者缺一即"默认放行"（V16 注释）。 */
    public BigDecimal committed() {
        return settledCost.add(reservedCost).add(uncertainCost);
    }

    /** 加上本次估算后仍是否不越过 limit；limit 为 null 表示该维度不设限。 */
    public boolean fitsDaily(BigDecimal limit, BigDecimal estimate) {
        if (limit == null) {
            return true;
        }
        return committed().add(estimate).compareTo(limit) <= 0;
    }

    /**
     * 单次任务上限判断：入参是<b>本 run</b> 已计入的钱（SETTLED 的实际额 + 在途/不明项的估算额），
     * 与日账户无关，因此不复用 {@link #committed()}。
     */
    public static boolean fitsTask(BigDecimal taskLimit, BigDecimal taskSpent, BigDecimal estimate) {
        if (taskLimit == null) {
            return true;
        }
        return taskSpent.add(estimate).compareTo(taskLimit) <= 0;
    }

    /** 跨币种守卫：只在同一币种内做差值/求和。 */
    public void requireSameCurrency(String otherCurrency) {
        if (!currency.equalsIgnoreCase(sanitize(otherCurrency))) {
            throw new IllegalStateException("预算金额跨币种不可比较: " + currency + " vs " + otherCurrency);
        }
    }

    /** 结算后的账面（不写库，纯推导，用于超支判断与测试断言）。 */
    public DailyBudgetAccount afterSettlement(BigDecimal estimate, BigDecimal actual) {
        return new DailyBudgetAccount(id, userId, providerId, currency, budgetDate,
                settledCost.add(actual), reservedCost.subtract(estimate), uncertainCost);
    }

    /** 用量不明后的账面：估算额平移，总占用不变。 */
    public DailyBudgetAccount afterMarkUnknown(BigDecimal estimate) {
        return new DailyBudgetAccount(id, userId, providerId, currency, budgetDate,
                settledCost, reservedCost.subtract(estimate), uncertainCost.add(estimate));
    }

    /** 释放后的账面：仅"确定从未送达"时才减少总占用。 */
    public DailyBudgetAccount afterRelease(BigDecimal estimate) {
        return new DailyBudgetAccount(id, userId, providerId, currency, budgetDate,
                settledCost, reservedCost.subtract(estimate), uncertainCost);
    }

    /** 落库前的 scale 归一（HALF_UP 到金额列的 10 位）。 */
    public static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(CostCalculator.MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String sanitize(String currency) {
        return currency == null ? "" : currency.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
