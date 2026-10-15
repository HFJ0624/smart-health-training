package com.hfj.fitagent.agent.core;

import com.hfj.fitagent.agent.model.ModelRequest;

/**
 * 交给网关的一次调用指令。
 *
 * @param attemptNo 本 run 内第几次远程调用（1 起），与 budget_reservation/model_call 的 attempt_no 同口径，
 *                  保证一次调用只结算一次
 */
public record ModelCallCommand(AgentContext context, ModelRequest request, RunCancellation cancellation, int attemptNo) {

    public ModelCallCommand {
        if (context == null || request == null) {
            throw new IllegalArgumentException("ModelCallCommand 需要上下文与请求");
        }
        if (attemptNo <= 0) {
            throw new IllegalArgumentException("attemptNo 必须为正数");
        }
        cancellation = cancellation == null ? new RunCancellation() : cancellation;
    }
}
