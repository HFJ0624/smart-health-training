package com.hfj.fitagent.modules.coach.service;

import com.hfj.fitagent.agent.core.RunCancellation;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本进程内 runId → 取消信号的登记表。
 *
 * <p>取消有两个必须同时成立的语义：数据库里的 {@code cancel_requested_at} 是<b>事实</b>（重启后仍然有效），
 * 而这里注册的是<b>正在跑的线程</b>，只有它能立刻掐掉在途的 HTTP。只有前者会让用户等到超时才看到"已取消"，
 * 只有后者则会让重启后遗留的 run 永远没人管。
 *
 * <p>登记表的 key 是 run 而不是会话：一个会话同时只有一个活动 run，由
 * {@code uq_agent_run_1(active_conversation_id)} 保证，所以这里不会串号。
 */
@Component
public class RunCancellationRegistry {

    private final Map<Long, RunCancellation> inFlight = new ConcurrentHashMap<>();

    /** Worker 领取成功后登记；同一 run 重复登记时返回已有信号（补偿扫描与派发竞态的幂等保护）。 */
    public RunCancellation register(long runId) {
        return inFlight.computeIfAbsent(runId, ignored -> new RunCancellation());
    }

    public void release(long runId) {
        inFlight.remove(runId);
    }

    /** @return 本进程里确实有正在执行的这个 run 并已收到取消信号 */
    public boolean signal(long runId) {
        RunCancellation cancellation = inFlight.get(runId);
        if (cancellation == null) {
            return false;
        }
        cancellation.request();
        return true;
    }

    public boolean isRunning(long runId) {
        return inFlight.containsKey(runId);
    }
}
