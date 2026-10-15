package com.hfj.fitagent.agent.trace;

/**
 * 轨迹事件类型。固定字符串常量：前端与 agent_step.event_type 列都按字面值匹配，
 * 用枚举 name() 反序列化会把内部实现细节变成对外契约的一部分，改动就有破坏兼容的风险。
 */
public final class TraceEventType {

    /** 一次远程模型调用（含被预算拒绝的那次）。 */
    public static final String MODEL_CALL = "MODEL_CALL";
    /** 一次工具调用（声明了要调用什么）。 */
    public static final String TOOL_CALL = "TOOL_CALL";
    /** 一次工具执行结果（成功或结构化错误都要留痕）。 */
    public static final String TOOL_RESULT = "TOOL_RESULT";
    /** 上下文被按轮次边界裁剪。 */
    public static final String CONTEXT_TRUNCATED = "CONTEXT_TRUNCATED";
    /** 引用校验丢弃了不存在于本 run 证据集合的引用。 */
    public static final String CITATION_REJECTED = "CITATION_REJECTED";
    /** 回答组装完成。 */
    public static final String ANSWER = "ANSWER";
    /** run 结束（含停止原因）。 */
    public static final String RUN_STOPPED = "RUN_STOPPED";

    private TraceEventType() {
    }
}
