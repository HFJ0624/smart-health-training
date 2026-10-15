package com.hfj.fitagent.agent.model.deepseek;

/**
 * 传输层失败。
 *
 * <p>{@code requestSent} 的判定刻意保守：只有能<b>证明</b>请求从未离开本机（DNS 解析失败、连接被拒、
 * 主机不可达）才置 false。其它 IOException（读超时、断连、写了一半、被取消）一律按"可能已送达"处理——
 * 官方已说明非流式响应期间服务端会持续返回空行保活（protocol §9），连接建立即视为已发出。
 * 判错方向的代价是不对称的：误判"未送达"会释放一笔其实已经发生的费用。
 */
public class DeepSeekTransportException extends RuntimeException {

    private final String errorCode;
    private final boolean requestSent;

    public DeepSeekTransportException(String errorCode, String safeMessage, boolean requestSent, Throwable cause) {
        super(safeMessage, cause);
        this.errorCode = errorCode;
        this.requestSent = requestSent;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean requestSent() {
        return requestSent;
    }
}
