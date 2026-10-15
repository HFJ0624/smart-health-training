package com.hfj.fitagent.modules.coach.worker;

import com.hfj.fitagent.modules.billing.UsageSettlement;
import com.hfj.fitagent.modules.billing.api.ReservationToken;
import com.hfj.fitagent.modules.coach.CoachProperties;
import com.hfj.fitagent.modules.coach.entity.ModelCallEntity;
import com.hfj.fitagent.modules.coach.mapper.AgentRunMapper;
import com.hfj.fitagent.modules.coach.mapper.ModelCallMapper;
import com.hfj.fitagent.modules.coach.service.RunCancellationRegistry;
import com.hfj.fitagent.modules.coach.service.RunExecutionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台派发与恢复规则（docs/agent-v1-design.md §10；提示词 B12、B14 第 13 项）。
 *
 * <p>这里验证的是两条"错了就会烧钱或丢数据"的时序：
 * <ol>
 *   <li><b>派发必须在事务提交之后</b>——提前派发会让 Worker 去领一个还没提交的 run，
 *       结果是"用户看到排队，Worker 什么都没跑"；</li>
 *   <li><b>失联 run 只要有一行可能已发出的调用就不重发</b>——重发一次可能已送达的请求，
 *       等于把一次扣费变成两次，且无法追回。</li>
 * </ol>
 */
class RunWorkerTest {

    private static final long USER_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-10-13T02:00:00Z");

    private RunExecutionService execution;
    private AgentRunMapper runMapper;
    private ModelCallMapper modelCallMapper;
    private UsageSettlement settlement;
    private RunCancellationRegistry cancellations;
    private RunWorker worker;

    @BeforeEach
    void setUp() {
        execution = mock(RunExecutionService.class);
        runMapper = mock(AgentRunMapper.class);
        modelCallMapper = mock(ModelCallMapper.class);
        settlement = mock(UsageSettlement.class);
        cancellations = new RunCancellationRegistry();
        worker = new RunWorker(execution, runMapper, modelCallMapper, settlement, cancellations,
                CoachProperties.defaults(), new AppClockFactory().fixed());
        worker.start();
    }

    @AfterEach
    void tearDown() {
        worker.stop();
    }

    @Test
    @DisplayName("没有活动事务时立刻派发；有事务时必须等到提交之后")
    void dispatchWaitsForTransactionCommit() throws Exception {
        CountDownLatch outside = new CountDownLatch(1);
        when(execution.execute(eq(1L), anyString())).thenAnswer(invocation -> {
            outside.countDown();
            return true;
        });
        worker.scheduleNow(1L);
        assertThat(outside.await(3, TimeUnit.SECONDS)).as("无事务时应当直接派发").isTrue();

        CountDownLatch inside = new CountDownLatch(1);
        when(execution.execute(eq(2L), anyString())).thenAnswer(invocation -> {
            inside.countDown();
            return true;
        });
        List<TransactionSynchronization> registered = new CopyOnWriteArrayList<>();
        TransactionSynchronizationManager.initSynchronization();
        try {
            worker.scheduleAfterCommit(2L);
            assertThat(inside.await(300, TimeUnit.MILLISECONDS))
                    .as("事务还没提交，绝不能开始跑").isFalse();
            TransactionSynchronizationManager.getSynchronizations().forEach(registered::add);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(registered).hasSize(1);
        registered.get(0).afterCommit();
        assertThat(inside.await(3, TimeUnit.SECONDS)).as("提交之后才派发").isTrue();
    }

    @Test
    @DisplayName("启动扫描补领遗漏的 QUEUED：进程在派发前崩掉也不会永远卡在队列里")
    void startupScanPicksUpLeftoverQueuedRuns() throws Exception {
        when(runMapper.findQueuedIds(anyInt())).thenReturn(List.of(3L));
        CountDownLatch latch = new CountDownLatch(1);
        when(execution.execute(eq(3L), anyString())).thenAnswer(invocation -> {
            latch.countDown();
            return true;
        });
        when(runMapper.findStaleRunningIds(any(), anyInt())).thenReturn(List.of());

        worker.afterSingletonsInstantiated();

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("失联 run 存在可能已发出的调用：转 FAILED + UNKNOWN_CHARGE、预占转不确定，且不退回队列")
    void staleRunWithPossiblySentCallIsNeverResent() {
        when(runMapper.findQueuedIds(anyInt())).thenReturn(List.of());
        when(runMapper.findStaleRunningIds(any(), anyInt())).thenReturn(List.of(9L));
        when(runMapper.findUserIdById(9L)).thenReturn(USER_ID);
        when(modelCallMapper.selectList(any())).thenReturn(List.of(sentCall(9L, 700L)));

        worker.afterSingletonsInstantiated();

        verify(settlement).markUnknown(new ReservationToken(USER_ID, 700L));
        verify(runMapper).failWithUnknownCharge(eq(9L), eq(USER_ID), any(Instant.class));
        verify(runMapper, never()).reopenAsQueued(anyLong(), anyLong());
        verify(execution, never()).execute(eq(9L), anyString());

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ModelCallEntity>> patch =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(modelCallMapper).update(eq(null), patch.capture());
        assertThat(patch.getValue().getSqlSet()).contains("status").contains("finished_at");
    }

    @Test
    @DisplayName("失联 run 一次调用都没发出：退回 QUEUED 再领一次，不产生第二次扣费")
    void staleRunWithNoSentCallGoesBackToQueue() throws Exception {
        when(runMapper.findQueuedIds(anyInt())).thenReturn(List.of());
        when(runMapper.findStaleRunningIds(any(), anyInt())).thenReturn(List.of(10L));
        when(runMapper.findUserIdById(10L)).thenReturn(USER_ID);
        when(modelCallMapper.selectList(any())).thenReturn(List.of());
        when(runMapper.reopenAsQueued(10L, USER_ID)).thenReturn(1);
        CountDownLatch latch = new CountDownLatch(1);
        when(execution.execute(eq(10L), anyString())).thenAnswer(invocation -> {
            latch.countDown();
            return true;
        });

        worker.afterSingletonsInstantiated();

        assertThat(latch.await(3, TimeUnit.SECONDS)).as("退回队列后应当重新领取").isTrue();
        verify(runMapper, never()).failWithUnknownCharge(anyLong(), anyLong(), any());
        verify(settlement, never()).markUnknown(any());
    }

    @Test
    @DisplayName("取消只影响本进程真正在跑的 run；不在本进程时靠数据库标记收敛")
    void cancelSignalOnlyTouchesInFlightRuns() {
        assertThat(cancellations.signal(123L)).isFalse();

        cancellations.register(123L).request();
        assertThat(cancellations.signal(123L)).isTrue();

        worker.notifyCancel(123L);
        worker.notifyCancel(999L);
        assertThat(cancellations.signal(999L)).isFalse();
    }

    private static ModelCallEntity sentCall(long runId, long reservationId) {
        ModelCallEntity entity = new ModelCallEntity();
        entity.setId(600L);
        entity.setUserId(USER_ID);
        entity.setRunId(runId);
        entity.setReservationId(reservationId);
        entity.setStatus("SENT");
        return entity;
    }

    /** 固定时钟的小工厂，避免测试里到处 new AppClock(Clock.fixed(...))。 */
    private static final class AppClockFactory {

        private com.hfj.fitagent.common.AppClock fixed() {
            return new com.hfj.fitagent.common.AppClock(Clock.fixed(NOW, ZoneId.of("UTC")));
        }
    }
}
