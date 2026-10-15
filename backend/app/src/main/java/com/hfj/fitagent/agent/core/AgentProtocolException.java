package com.hfj.fitagent.agent.core;

/**
 * 上下文协议非法（孤儿 tool 消息、缺失配对、半个工具组）。
 *
 * <p>单独一个异常类型，是为了和"供应商返回了不完整内容"区分开：前者是我们自己构造出了非法请求，
 * 绝对不能发出去（会当场 400 且白烧一次预算），后者是远端结果不可信。两者都收敛到
 * {@link StopReason#PROTOCOL_INCOMPLETE}，但错误码不同，排查方向完全不同。
 */
public class AgentProtocolException extends RuntimeException {

    public AgentProtocolException(String message) {
        super(message);
    }
}
