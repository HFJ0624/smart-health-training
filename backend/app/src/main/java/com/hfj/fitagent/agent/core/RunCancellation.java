package com.hfj.fitagent.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * run 的取消位 + "尽力取消在途 HTTP"的钩子（docs/agent-v1-design.md §10）。
 *
 * <p>语义要如实：取消只是<b>不再发起后续调用</b>。已经发出的请求可能已经计费，
 * 钩子做的是 Future.cancel()，成功与否不保证，也不改变账目——界面必须显示已发生费用。
 *
 * <p>钩子只保留"当前在途的那一个"：循环是顺序的，同一时刻至多一个远程调用或一个工具执行在跑。
 */
public final class RunCancellation {

    private static final Logger log = LoggerFactory.getLogger(RunCancellation.class);

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<Runnable> inFlight = new AtomicReference<>();

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** 幂等：重复取消只置位一次并只触发一次钩子。 */
    public void request() {
        if (cancelled.compareAndSet(false, true)) {
            fireInFlight();
        }
    }

    /**
     * 注册在途动作的取消钩子。若此刻已经取消，立刻执行钩子——否则取消信号会漏给一个刚建立的连接。
     */
    public void attachInFlight(Runnable cancelAction) {
        if (cancelAction == null) {
            return;
        }
        inFlight.set(cancelAction);
        if (cancelled.get()) {
            fireInFlight();
        }
    }

    /** 动作正常结束后摘掉钩子，避免下一次调用误取消一个已经完成的 Future。 */
    public void detachInFlight(Runnable cancelAction) {
        inFlight.compareAndSet(cancelAction, null);
    }

    private void fireInFlight() {
        Runnable action = inFlight.getAndSet(null);
        if (action == null) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException e) {
            // 取消只是尽力而为；这里抛异常会把"用户点了停止"变成一次内部错误
            log.warn("取消在途调用失败 type={}", e.getClass().getSimpleName());
        }
    }

    public boolean cancelled() {
        return cancelled.get();
    }
}
