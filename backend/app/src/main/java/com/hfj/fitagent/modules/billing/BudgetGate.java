package com.hfj.fitagent.modules.billing;

import com.hfj.fitagent.common.AppClock;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.billing.api.BudgetContext;
import com.hfj.fitagent.modules.billing.api.BudgetContextProvider;
import com.hfj.fitagent.modules.billing.api.CostResult;
import com.hfj.fitagent.modules.billing.api.ReservationOutcome;
import com.hfj.fitagent.modules.billing.api.ReservationToken;
import com.hfj.fitagent.modules.billing.entity.BudgetDailyUsageEntity;
import com.hfj.fitagent.modules.billing.entity.BudgetReservationEntity;
import com.hfj.fitagent.modules.billing.mapper.BudgetDailyUsageMapper;
import com.hfj.fitagent.modules.billing.mapper.BudgetReservationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 预算闸门：发起模型调用<b>之前</b>的唯一放行点（设计 §6.3，"不是默认放行"）。
 *
 * <p>承诺的边界要写清楚，否则界面会撒谎：这是一个<b>保守预占 + 逐轮阻止后续请求</b>的应用侧闸门，
 * 不是供应商硬额度。已经发出去的请求花掉的钱<b>无法撤销</b>，本类也没有任何撤销供应商扣费的路径。
 *
 * <p>事务与远程调用的顺序：本方法是短事务，<b>提交后才允许发 HTTP</b>。
 * 把远程调用包在事务里会让行锁跨网络等待，一次慢响应就能把同一天的账户锁死。
 *
 * <p>锁序：当前只有 {@code budget_daily_usage} 一层（{@code agent_run} 本轮不存在，见 V17 注释）。
 * 该表落地后必须改成 {@code agent_run → budget_daily_usage} 的固定顺序，
 * 并且要反过来——先锁账户再锁 run 的两个事务会互等成环。
 *
 * <p>估算口径（协议 §7）：输入全按<b>未命中</b>价、输出按 maxOutputTokens 全价、档位恒取 <b>PEAK</b>；
 * 中国法定节假日日历没有核实到的机器可读来源，因此<b>永不</b>假定当前处在谷段。
 */
@Service
public class BudgetGate {

    private static final Logger log = LoggerFactory.getLogger(BudgetGate.class);

    private final BudgetContextProvider contextProvider;
    private final BudgetDailyUsageMapper dailyUsageMapper;
    private final BudgetReservationMapper reservationMapper;
    private final CostCalculator calculator;
    private final AppClock clock;

    public BudgetGate(BudgetContextProvider contextProvider,
                      BudgetDailyUsageMapper dailyUsageMapper,
                      BudgetReservationMapper reservationMapper,
                      CostCalculator calculator,
                      AppClock clock) {
        this.contextProvider = contextProvider;
        this.dailyUsageMapper = dailyUsageMapper;
        this.reservationMapper = reservationMapper;
        this.calculator = calculator;
        this.clock = clock;
    }

    /**
     * 预占一次调用的预算。单供应商部署下的便捷入口（供应商由 {@link BudgetContextProvider} 唯一确定）。
     *
     * @param estimateInputTokens 本次请求的输入 token 估算（调用方按已构造的消息估算，不猜缓存命中）
     * @param maxOutputTokens     取自 {@code budget_policy.max_output_tokens}，不是供应商硬上限
     */
    public ReservationOutcome reserve(long userId, long runId, int attemptNo,
                                      long estimateInputTokens, int maxOutputTokens) {
        return reserve(userId, contextProvider.contextFor(userId), runId, attemptNo,
                estimateInputTokens, maxOutputTokens);
    }

    /** 显式指定供应商的同一条路径（未来 run 绑定供应商后用这个重载）。 */
    public ReservationOutcome reserve(long userId, long providerId, long runId, int attemptNo,
                                      long estimateInputTokens, int maxOutputTokens) {
        return reserve(userId, contextProvider.contextFor(userId, providerId), runId, attemptNo,
                estimateInputTokens, maxOutputTokens);
    }

    /**
     * 核心路径。事务边界在这里：读取账户 → 判断额度 → 落预占 + 加日账户 reserved，一次提交。
     *
     * <p>拒绝时抛 {@code BUDGET_EXCEEDED_DAILY} / {@code BUDGET_EXCEEDED_TASK}，
     * <b>调用方因此拿不到任何"已经发起"的副作用</b>：闸门在远程 HTTP 之前。
     */
    @Transactional
    public ReservationOutcome reserve(long userId, BudgetContext context, long runId, int attemptNo,
                                      long estimateInputTokens, int maxOutputTokens) {
        if (attemptNo <= 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "attempt_no 必须从 1 起");
        }
        if (runId <= 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "run_id 不能为空");
        }
        // 关掉预算控制 ≠ 放行：用户想花钱就得显式开着策略
        if (!context.enabled()) {
            throw new BizException(ErrorCode.INVALID_STATE_TRANSITION, "预算策略未启用，本次模型调用不发起");
        }
        // 两个上限都是 NULL 等价于"默认放行"，设计 §7 V15 明确禁止
        if (!context.hasAnyLimit()) {
            throw new BizException(ErrorCode.INVALID_STATE_TRANSITION, "预算策略至少要设置一个上限才能发起调用");
        }
        if (context.peakPrices() == null) {
            throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED, "没有生效的 PEAK 档报价，无法保守预占");
        }
        Integer maxCalls = context.maxModelCalls();
        if (maxCalls != null && attemptNo > maxCalls) {
            // 次数闸门与金额闸门同属"单次任务上限"，用同一个码，界面按 message 区分
            throw new BizException(ErrorCode.BUDGET_EXCEEDED_TASK,
                    "本次任务的模型调用次数已达上限（" + maxCalls + "）");
        }

        // 幂等：同一 (run, attempt) 已有台账就不重复占款（唯一键 uq_budget_reservation_1 是最终防线）
        Long existingId = reservationMapper.findIdByAttempt(userId, runId, attemptNo);
        if (existingId != null) {
            BudgetReservationEntity existing = requireOwned(userId, existingId);
            return new ReservationOutcome(new ReservationToken(userId, existing.getId()), existing.getProviderId(),
                    existing.getCurrency(), existing.getBudgetDate(), existing.getAttemptNo(),
                    existing.getReservedAmount(), true);
        }

        // 业务日：由"调用开始时刻"（即现在）按 Asia/Shanghai 推导，客户端不传日期
        LocalDate budgetDate = DailyBudgetAccount.budgetDateOf(clock.now());

        BudgetDailyUsageEntity slot = new BudgetDailyUsageEntity();
        slot.setUserId(userId);
        slot.setProviderId(context.providerId());
        slot.setCurrency(context.currency());
        slot.setBudgetDate(budgetDate);
        dailyUsageMapper.ensureAccount(slot);
        Long accountId = slot.getId();
        if (accountId == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "未能分配当日预算账户行");
        }
        BudgetDailyUsageEntity locked = dailyUsageMapper.lockAccount(accountId, userId);
        if (locked == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "当日预算账户行加锁失败");
        }
        DailyBudgetAccount account = DailyBudgetAccount.from(locked);
        // 币种一致性：账户行与上下文币种不同即说明归属算错，绝不允许跨币种求和或相减
        account.requireSameCurrency(context.currency());

        CostResult estimate = calculator.estimate(estimateInputTokens, maxOutputTokens, context.peakPrices());
        if (estimate.isUnknown()) {
            // 用量或单价不明时不猜金额：宁可不发起调用，也不给出一个偏低的预占
            log.warn("预算预占无法核算，拒绝发起调用 missing={}", estimate.missing());
            throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED, "保守估算无法核算，本次调用不发起");
        }
        BigDecimal estimated = estimate.amount();

        if (!account.fitsDaily(context.maxDailyCost(), estimated)) {
            throw new BizException(ErrorCode.BUDGET_EXCEEDED_DAILY, dailyExceededMessage(context, account, estimated));
        }
        BigDecimal taskSpent = nullToZero(reservationMapper.sumTaskCost(userId, runId));
        if (!DailyBudgetAccount.fitsTask(context.maxTaskCost(), taskSpent, estimated)) {
            throw new BizException(ErrorCode.BUDGET_EXCEEDED_TASK, taskExceededMessage(context, taskSpent, estimated));
        }

        if (dailyUsageMapper.addReserved(accountId, userId, context.currency(), estimated) != 1) {
            throw new BizException(ErrorCode.VERSION_CONFLICT, "预算账户并发变动，请重试");
        }

        BudgetReservationEntity reservation = new BudgetReservationEntity();
        reservation.setUserId(userId);
        reservation.setRunId(runId);
        reservation.setAttemptNo(attemptNo);
        reservation.setProviderId(context.providerId());
        reservation.setDailyUsageId(accountId);
        reservation.setCurrency(context.currency());
        reservation.setBudgetDate(budgetDate);
        reservation.setReservedAmount(estimated);
        // settled_amount 不设：NULL 表示尚未落定，不是 0（列上有 CHECK 绑定 settled_at）
        reservation.setEstimateQuality(EstimateQuality.CONSERVATIVE);
        reservation.setStatus(ReservationStatus.RESERVED);
        try {
            reservationMapper.insert(reservation);
        } catch (DuplicateKeyException e) {
            // 同一 (run_id, attempt_no) 的并发重复请求：整事务回滚，先前那条台账仍然有效
            throw new BizException(ErrorCode.VERSION_CONFLICT, "本次调用的预算已预占，请勿重复发起");
        }
        return new ReservationOutcome(new ReservationToken(userId, reservation.getId()), context.providerId(),
                context.currency(), budgetDate, attemptNo, estimated, false);
    }

    private BudgetReservationEntity requireOwned(long userId, Long id) {
        BudgetReservationEntity entity = reservationMapper.find(id, userId);
        if (entity == null) {
            throw BizException.notFound();
        }
        return entity;
    }

    private static String dailyExceededMessage(BudgetContext context, DailyBudgetAccount account, BigDecimal estimate) {
        return "今日预算不足：已占用 " + plain(account.committed()) + " " + context.currency()
                + "，上限 " + plain(context.maxDailyCost()) + "，本次保守估算 " + plain(estimate);
    }

    private static String taskExceededMessage(BudgetContext context, BigDecimal taskSpent, BigDecimal estimate) {
        return "本次任务预算不足：已占用 " + plain(taskSpent) + " " + context.currency()
                + "，上限 " + plain(context.maxTaskCost()) + "，本次保守估算 " + plain(estimate);
    }

    /** 错误信息里的金额：去掉科学计数法，不做任何"抹零"。 */
    private static String plain(BigDecimal value) {
        return value == null ? "未设限" : value.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(CostCalculator.MONEY_SCALE, RoundingMode.HALF_UP) : value;
    }
}
