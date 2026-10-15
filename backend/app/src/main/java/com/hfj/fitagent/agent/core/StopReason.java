package com.hfj.fitagent.agent.core;

/**
 * run 的停止原因。界面与轨迹按它分支，不解析自由文案。
 *
 * <p>区分原则：{@code LIMIT_STOPPED} 指<b>我方</b>工程闸门（次数/长度/字节）触发，
 * {@code BUDGET_STOPPED} 指预算闸门拒绝发起下一次调用，
 * {@code PROVIDER_ERROR} 指供应商侧失败（含 429 并发超限——协议 §9 把 429 归为供应商限流而非我们的闸门）。
 */
public enum StopReason {

    /** 拿到 finish_reason=stop 的可用回答。 */
    SUCCEEDED,
    /** 没有可用凭据：一次远程调用都没发起。 */
    NO_CREDENTIAL,
    /** 预算闸门拒绝下一次调用；不额外发"免费总结"请求，改用确定性模板返回已验证事实。 */
    BUDGET_STOPPED,
    /** 我方工程闸门耗尽（模型调用次数以外的次数/长度类上限）。 */
    LIMIT_STOPPED,
    /** run 截止时间到达。 */
    DEADLINE_STOPPED,
    /** 用户取消。 */
    CANCELLED,
    /** 检测到重复请求/重复工具读取：不让模型空转烧钱（design §4）。 */
    REPETITIVE_LOOP,
    /** 供应商返回不可用的 finish_reason，或上下文工具配对非法。 */
    PROTOCOL_INCOMPLETE,
    /** 供应商侧失败（401/402/422/429/500/503/超时断连）。 */
    PROVIDER_ERROR,
    /** 达到最大模型调用次数仍未拿到可用回答。 */
    LOOP_EXHAUSTED;

    public boolean successful() {
        return this == SUCCEEDED;
    }

    /** 停止原因对应的持久化 run 状态（agent_run.status，docs/agent-v1-design.md §10 的状态集）。 */
    public String runStatus() {
        return switch (this) {
            case SUCCEEDED -> "SUCCEEDED";
            case NO_CREDENTIAL -> "WAITING_CREDENTIAL";
            case BUDGET_STOPPED -> "BUDGET_STOPPED";
            case LIMIT_STOPPED -> "LIMIT_STOPPED";
            case DEADLINE_STOPPED -> "DEADLINE_STOPPED";
            case CANCELLED -> "CANCELLED";
            case REPETITIVE_LOOP, PROTOCOL_INCOMPLETE, PROVIDER_ERROR, LOOP_EXHAUSTED -> "FAILED";
        };
    }
}
