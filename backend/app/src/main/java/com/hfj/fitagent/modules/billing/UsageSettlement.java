package com.hfj.fitagent.modules.billing;

import com.hfj.fitagent.common.AppClock;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.billing.api.BudgetContext;
import com.hfj.fitagent.modules.billing.api.BudgetContextProvider;
import com.hfj.fitagent.modules.billing.api.CostResult;
import com.hfj.fitagent.modules.billing.api.PricedUsage;
import com.hfj.fitagent.modules.billing.api.ReservationToken;
import com.hfj.fitagent.modules.billing.api.SettlementResult;
import com.hfj.fitagent.modules.billing.entity.BudgetDailyUsageEntity;
import com.hfj.fitagent.modules.billing.entity.BudgetReservationEntity;
import com.hfj.fitagent.modules.billing.mapper.BudgetDailyUsageMapper;
import com.hfj.fitagent.modules.billing.mapper.BudgetReservationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 用量结算：把一次调用的预占落定成实际费用。
 *
 * <p>三条转移路径，语义各自独立，不能混用：
 * <ul>
 *   <li>{@link #settle} —— usage 已知：{@code reserved −= 估算, settled += 实际}；</li>
 *   <li>{@link #markUnknown} —— 请求已发出但 usage 不明：估算额平移到 {@code uncertain_reserved}，
 *       <b>继续占用额度</b>且不自动重发（重发可能变成两次扣费，设计 §5 第 6 条）；</li>
 *   <li>{@link #release} —— <b>仅</b>在确定请求从未送达时（连接建立前失败）释放预占。
 *       任何"已经发出去"的情形都不许调它。</li>
 * </ul>
 *
 * <p>幂等由台账状态机保证：所有转移都带 {@code AND status = 'RESERVED'}，
 * 重复回调影响 0 行 → 直接返回"账没动"，因此同一笔钱不会被移动两次。
 *
 * <p>超支检测的意义与局限：结算出的实际额高于估算时，钱<b>已经花掉且无法退回</b>；
 * 本类只做两件事——如实记账、返回 {@code shouldStop} 让调用方停止后续调用。
 * 界面不得把它说成"撤销了供应商扣费"。
 */
@Service
public class UsageSettlement {

    private static final Logger log = LoggerFactory.getLogger(UsageSettlement.class);

    private final BudgetDailyUsageMapper dailyUsageMapper;
    private final BudgetReservationMapper reservationMapper;
    private final CostCalculator calculator;
    private final BudgetContextProvider contextProvider;
    private final AppClock clock;

    public UsageSettlement(BudgetDailyUsageMapper dailyUsageMapper,
                           BudgetReservationMapper reservationMapper,
                           CostCalculator calculator,
                           BudgetContextProvider contextProvider,
                           AppClock clock) {
        this.dailyUsageMapper = dailyUsageMapper;
        this.reservationMapper = reservationMapper;
        this.calculator = calculator;
        this.contextProvider = contextProvider;
        this.clock = clock;
    }

    /**
     * 按实际 usage 结算一笔预占。
     *
     * @return {@code applied=false} 且 {@code status} 非 RESERVED 时表示"这笔已经落定过了"，金额未再次变动
     */
    @Transactional
    public SettlementResult settle(ReservationToken token, PricedUsage pricedUsage) {
        BudgetReservationEntity reservation = requireReservation(token);
        if (!ReservationStatus.RESERVED.equals(reservation.getStatus())) {
            // 重复回调：不动账，如实回报
            return SettlementResult.unchanged(token, reservation.getStatus(), reservation.getCurrency(),
                    "该预占已处于终态，本次结算未改动账本");
        }
        CostResult cost = calculator.compute(pricedUsage.usage(), pricedUsage.prices());
        if (cost.isUnknown()) {
            // 用量不明不当成 0 结算：保持 RESERVED，由调用方改用 markUnknown
            log.warn("结算用量不明，保持预占状态 missing={}", cost.missing());
            return SettlementResult.unchanged(token, reservation.getStatus(), reservation.getCurrency(),
                    "用量字段不完整，请改用 markUnknown 保留预占");
        }
        if (!cost.currency().equalsIgnoreCase(reservation.getCurrency())) {
            // 跨币种相减会把闸门变成随机数发生器（设计 §6.3）
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "结算币种与预占币种不一致: " + cost.currency() + " vs " + reservation.getCurrency());
        }

        DailyBudgetAccount account = lockAccount(reservation);
        BigDecimal estimate = reservation.getReservedAmount();
        BigDecimal actual = cost.amount();

        // 先移动台账状态（幂等闸门），再移动日账户：任一步失败整个事务回滚
        if (reservationMapper.casSettle(reservation.getId(), token.userId(), actual, clock.now()) != 1) {
            return SettlementResult.unchanged(token, ReservationStatus.RESERVED, reservation.getCurrency(),
                    "并发结算：本次未改动账本");
        }
        if (dailyUsageMapper.settle(account.id(), token.userId(), account.currency(), estimate, actual) != 1) {
            // 守卫未过说明账面不一致（reserved_cost < 估算额），必须回滚而不是留下半个账
            throw new BizException(ErrorCode.VERSION_CONFLICT, "预算账户与台账不一致，结算已回滚");
        }

        DailyBudgetAccount after = account.afterSettlement(estimate, actual);
        return evaluateLimits(token, reservation, after, actual, cost.currency(),
                "已按实际用量结算", ReservationStatus.SETTLED);
    }

    /**
     * 已发出但 usage 不明：估算额转成 uncertain 预占，<b>不释放</b>额度、不重发。
     */
    @Transactional
    public SettlementResult markUnknown(ReservationToken token) {
        BudgetReservationEntity reservation = requireReservation(token);
        if (!ReservationStatus.RESERVED.equals(reservation.getStatus())) {
            return SettlementResult.unchanged(token, reservation.getStatus(), reservation.getCurrency(),
                    "该预占已处于终态，标记用量不明未改动账本");
        }
        DailyBudgetAccount account = lockAccount(reservation);
        BigDecimal estimate = reservation.getReservedAmount();
        if (reservationMapper.casMarkUncertain(reservation.getId(), token.userId()) != 1) {
            return SettlementResult.unchanged(token, ReservationStatus.RESERVED, reservation.getCurrency(),
                    "并发转移：本次未改动账本");
        }
        if (dailyUsageMapper.markUncertain(account.id(), token.userId(), account.currency(), estimate) != 1) {
            throw new BizException(ErrorCode.VERSION_CONFLICT, "预算账户与台账不一致，标记已回滚");
        }
        // 总占用不变（settled + reserved + uncertain 之和守恒），因此这一步不产生任何"腾出额度"
        DailyBudgetAccount after = account.afterMarkUnknown(estimate);
        return evaluateLimits(token, reservation, after, null, account.currency(),
                "用量不明，估算额继续占用", ReservationStatus.UNCERTAIN);
    }

    /**
     * 释放预占：<b>只有</b>确定请求从未发出才允许调用（连接建立前的失败）。
     * 已发出而结果未知的调用必须走 {@link #markUnknown}。
     */
    @Transactional
    public SettlementResult release(ReservationToken token) {
        BudgetReservationEntity reservation = requireReservation(token);
        if (!ReservationStatus.RESERVED.equals(reservation.getStatus())) {
            return SettlementResult.unchanged(token, reservation.getStatus(), reservation.getCurrency(),
                    "该预占已处于终态，释放未改动账本");
        }
        DailyBudgetAccount account = lockAccount(reservation);
        BigDecimal estimate = reservation.getReservedAmount();
        if (reservationMapper.casRelease(reservation.getId(), token.userId()) != 1) {
            return SettlementResult.unchanged(token, ReservationStatus.RESERVED, reservation.getCurrency(),
                    "并发转移：本次未改动账本");
        }
        if (dailyUsageMapper.release(account.id(), token.userId(), account.currency(), estimate) != 1) {
            throw new BizException(ErrorCode.VERSION_CONFLICT, "预算账户与台账不一致，释放已回滚");
        }
        DailyBudgetAccount after = account.afterRelease(estimate);
        return evaluateLimits(token, reservation, after, null, account.currency(),
                "请求未送达，预占已释放", ReservationStatus.RELEASED);
    }

    /**
     * 落定后与两个上限比对：超限即 {@code shouldStop=true}。
     *
     * <p>策略解析失败（报价被撤下、策略被删）不能连带让结算失败——钱已经花了，账必须记上；
     * 因此这里降级为"停止判断"，并在 reason 里说明限额不可得。
     */
    private SettlementResult evaluateLimits(ReservationToken token, BudgetReservationEntity reservation,
                                            DailyBudgetAccount after, BigDecimal actual,
                                            String currency, String baseReason, String resultingStatus) {
        BigDecimal maxDaily = null;
        BigDecimal maxTask = null;
        boolean limitsAvailable = true;
        try {
            BudgetContext context = contextProvider.contextFor(token.userId(), reservation.getProviderId());
            maxDaily = context.maxDailyCost();
            maxTask = context.maxTaskCost();
        } catch (BizException e) {
            limitsAvailable = false;
            log.warn("结算后限额不可得，跳过超支判定 providerId={} code={}",
                    reservation.getProviderId(), e.errorCode().code());
        }

        boolean overSpent = false;
        if (limitsAvailable) {
            if (maxDaily != null && after.committed().compareTo(maxDaily) > 0) {
                overSpent = true;
            }
            if (maxTask != null) {
                BigDecimal taskSpent = reservationMapper.sumTaskCost(token.userId(), reservation.getRunId());
                if (taskSpent != null && taskSpent.compareTo(maxTask) > 0) {
                    overSpent = true;
                }
            }
        }
        String reason = overSpent
                ? baseReason + "；已超出限额，后续模型调用应当停止（已发生的花费不可撤销）"
                : baseReason + (limitsAvailable ? "" : "；限额当前不可得");
        return new SettlementResult(token, true, resultingStatus, actual, currency, overSpent, overSpent, reason);
    }

    private BudgetReservationEntity requireReservation(ReservationToken token) {
        BudgetReservationEntity reservation = reservationMapper.find(token.reservationId(), token.userId());
        if (reservation == null) {
            // 越权与不存在同形（docs/p1-api.md §1）
            throw BizException.notFound();
        }
        return reservation;
    }

    /** 固定锁序：先锁当日账户行，再改台账（agent_run 落地后必须在最外层先锁 run）。 */
    private DailyBudgetAccount lockAccount(BudgetReservationEntity reservation) {
        BudgetDailyUsageEntity locked =
                dailyUsageMapper.lockAccount(reservation.getDailyUsageId(), reservation.getUserId());
        if (locked == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "当日预算账户行加锁失败");
        }
        DailyBudgetAccount account = DailyBudgetAccount.from(locked);
        account.requireSameCurrency(reservation.getCurrency());
        return account;
    }
}
