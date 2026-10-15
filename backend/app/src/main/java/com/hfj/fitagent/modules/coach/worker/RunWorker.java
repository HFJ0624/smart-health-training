package com.hfj.fitagent.modules.coach.worker;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hfj.fitagent.common.AppClock;
import com.hfj.fitagent.modules.billing.UsageSettlement;
import com.hfj.fitagent.modules.billing.api.ReservationToken;
import com.hfj.fitagent.modules.coach.CoachProperties;
import com.hfj.fitagent.modules.coach.entity.ModelCallEntity;
import com.hfj.fitagent.modules.coach.mapper.AgentRunMapper;
import com.hfj.fitagent.modules.coach.mapper.ModelCallMapper;
import com.hfj.fitagent.modules.coach.service.RunCancellationRegistry;
import com.hfj.fitagent.modules.coach.service.RunDispatcher;
import com.hfj.fitagent.modules.coach.service.RunExecutionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 单机有界后台执行器 + 租约恢复（docs/agent-v1-design.md §10）。
 *
 * <p>刻意只用 JDK 线程池，不引入消息队列：本轮是单机部署，队列上限是工程闸门而不是吞吐目标，
 * 而"提交失败但看起来成功了"是最坏的结果——所以队列满时 run 留在 QUEUED，由补偿扫描再领一次，
 * 而不是假装已经受理。
 *
 * <p>三处必须成立的并发约定：
 * <ol>
 *   <li><b>派发在事务之后</b>：事务里就派发的话，Worker 可能读到一个还没提交的 run，
 *       然后按"不存在"把它丢掉。</li>
 *   <li><b>领取是条件更新</b>：{@code UPDATE ... WHERE status='QUEUED'} 影响 0 行即退出，
 *       同一个 run 不会由两个 Worker 并行跑，补偿扫描与派发竞态也只有一边赢。</li>
 *   <li><b>租约靠心跳续</b>：续租返回 0 行说明这一行已被接管（进程重启后扫描器判定过期），
 *       本 Worker 立刻停止写入并放弃结果，避免两个进程都认为自己还在跑同一个 run。</li>
 * </ol>
 */
@Component
public class RunWorker implements RunDispatcher, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(RunWorker.class);
    private static final List<String> POSSIBLY_SENT = List.of("PREPARED", "SENT", "UNKNOWN");

    private final RunExecutionService execution;
    private final AgentRunMapper runMapper;
    private final ModelCallMapper modelCallMapper;
    private final UsageSettlement usageSettlement;
    private final RunCancellationRegistry cancellations;
    private final CoachProperties properties;
    private final AppClock clock;

    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    private ThreadPoolExecutor pool;
    private ScheduledExecutorService janitor;

    public RunWorker(RunExecutionService execution,
                     AgentRunMapper runMapper,
                     ModelCallMapper modelCallMapper,
                     UsageSettlement usageSettlement,
                     RunCancellationRegistry cancellations,
                     CoachProperties properties,
                     AppClock clock) {
        this.execution = execution;
        this.runMapper = runMapper;
        this.modelCallMapper = modelCallMapper;
        this.usageSettlement = usageSettlement;
        this.cancellations = cancellations;
        this.properties = properties;
        this.clock = clock == null ? new AppClock() : clock;
    }

    @PostConstruct
    void start() {
        CoachProperties.Worker worker = properties.worker();
        this.pool = new ThreadPoolExecutor(worker.coreSize(), worker.maxSize(), 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(worker.queueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "coach-run-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.janitor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "coach-run-janitor");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 所有单例就绪后再做一次恢复扫描：这时 Mapper、事务与配置都已可用。 */
    @Override
    public void afterSingletonsInstantiated() {
        CoachProperties.Worker worker = properties.worker();
        // 启动这一次也必须"尽力而为"：数据库还没就绪时让它失败，等于把整个应用一起拖死
        // （健康检查会一直 red，而补偿扫描本来就是给"下一次"用的）
        scanQuietly();
        janitor.scheduleWithFixedDelay(this::scanQuietly, worker.scanIntervalSeconds(),
                worker.scanIntervalSeconds(), TimeUnit.SECONDS);
        log.info("教练后台执行器就绪 workerId={} core={} max={} queue={} lease={}s",
                workerId, worker.coreSize(), worker.maxSize(), worker.queueCapacity(), worker.leaseSeconds());
    }

    @PreDestroy
    void stop() {
        // 在途 run 被中断后不会写终态：它的租约会过期，下一次启动的扫描器按恢复规则处理（§10）
        if (janitor != null) {
            janitor.shutdownNow();
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    // ---- RunDispatcher ----

    @Override
    public void scheduleAfterCommit(long runId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submit(runId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(runId);
            }
        });
    }

    @Override
    public void scheduleNow(long runId) {
        submit(runId);
    }

    @Override
    public void notifyCancel(long runId) {
        if (!cancellations.signal(runId)) {
            log.debug("取消标记已落库，本进程没有该 run 的活动线程 runId={}", runId);
        }
    }

    // ---- 执行 ----

    private void submit(long runId) {
        try {
            pool.execute(() -> runOne(runId));
        } catch (RejectedExecutionException e) {
            // 队列满：run 留在 QUEUED，状态可由页面查询到，补偿扫描会再领一次。绝不返回"已受理"的假成功。
            log.warn("后台队列已满，run 保持排队等待补偿扫描 runId={}", runId);
        }
    }

    private void runOne(long runId) {
        CoachProperties.Worker worker = properties.worker();
        ScheduledFuture<?> heartbeat = janitor.scheduleWithFixedDelay(
                () -> renewLease(runId, worker), worker.leaseHeartbeatSeconds(),
                worker.leaseHeartbeatSeconds(), TimeUnit.SECONDS);
        try {
            execution.execute(runId, workerId);
        } catch (RuntimeException e) {
            log.error("run 执行线程异常 runId={} type={}", runId, e.getClass().getSimpleName());
        } finally {
            heartbeat.cancel(false);
        }
    }

    /** 续租 0 行 = 这一行已被接管：把取消信号打给本线程，它不会再写终态。 */
    private void renewLease(long runId, CoachProperties.Worker worker) {
        try {
            Long userId = runMapper.findUserIdById(runId);
            if (userId == null) {
                return;
            }
            int renewed = runMapper.renewLease(runId, userId, workerId,
                    clock.now().plusSeconds(worker.leaseSeconds()));
            if (renewed == 0) {
                log.warn("租约续期未命中，本 Worker 放弃该 run runId={}", runId);
                cancellations.signal(runId);
            }
        } catch (RuntimeException e) {
            log.warn("租约续期失败 runId={} type={}", runId, e.getClass().getSimpleName());
        }
    }

    // ---- 补偿与恢复 ----

    private void scanQuietly() {
        try {
            scan();
        } catch (RuntimeException e) {
            // 扫描是尽力而为的补偿动作，绝不能因为一次数据库抖动让调度线程停摆
            log.warn("补偿扫描失败，下一轮再试 type={}", e.getClass().getSimpleName());
        }
    }

    private void scan() {
        int batch = properties.worker().scanBatchSize();
        for (Long runId : runMapper.findQueuedIds(batch)) {
            submit(runId);
        }
        for (Long runId : runMapper.findStaleRunningIds(clock.now(), batch)) {
            recover(runId);
        }
    }

    /**
     * 失联 run 的恢复规则（§10）：只要有一行"可能已发出"的调用就转 FAILED + UNKNOWN_CHARGE 并保留预占，
     * 一次都没有才退回 QUEUED 重新领取。<b>不自动重发</b>——重发一次可能已送达的请求，
     * 就是把一次扣费变成两次。
     */
    private void recover(long runId) {
        Long userId = runMapper.findUserIdById(runId);
        if (userId == null) {
            return;
        }
        List<ModelCallEntity> possiblySent = modelCallMapper.selectList(new QueryWrapper<ModelCallEntity>()
                .eq("user_id", userId).eq("run_id", runId).in("status", POSSIBLY_SENT));
        if (possiblySent.isEmpty()) {
            if (runMapper.reopenAsQueued(runId, userId) == 1) {
                log.info("失联 run 无任何已发送调用，退回 QUEUED 重新领取 runId={}", runId);
                submit(runId);
            }
            return;
        }
        Instant now = clock.now();
        for (ModelCallEntity call : possiblySent) {
            if (!"UNKNOWN".equals(call.getStatus())) {
                markCallUnknown(userId, call, now);
            }
        }
        if (runMapper.failWithUnknownCharge(runId, userId, now) == 1) {
            log.warn("失联 run 存在可能已计费的调用，转 FAILED + UNKNOWN_CHARGE runId={} sentCalls={}",
                    runId, possiblySent.size());
        }
    }

    private void markCallUnknown(long userId, ModelCallEntity call, Instant now) {
        try {
            if (call.getReservationId() != null) {
                usageSettlement.markUnknown(new ReservationToken(userId, call.getReservationId()));
            }
        } catch (RuntimeException e) {
            log.warn("预占转不确定失败，台账仍标 UNKNOWN runId={} type={}",
                    call.getRunId(), e.getClass().getSimpleName());
        }
        // UpdateWrapper.set 绕开 JSON 列的类型处理器，但这一行只写字符串与时间列（AGENTS.md MyBatis-Plus 规则 2）
        modelCallMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ModelCallEntity>()
                .eq("id", call.getId())
                .eq("user_id", userId)
                .set("status", "UNKNOWN")
                .set("finished_at", now));
    }
}
