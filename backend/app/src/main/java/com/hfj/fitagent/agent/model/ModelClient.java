package com.hfj.fitagent.agent.model;

import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.core.RunCancellation;

/**
 * 一次远程补全的出口（docs/agent-v1-design.md §2 依赖方向：agent.core 只依赖本接口）。
 *
 * <p>实现方（DeepSeekClient / FakeModelClient）负责：
 * <ul>
 *   <li>在<b>发出 HTTP 的那一步</b>才向 ProviderCredentialResolver 取明文 Key（B10）；</li>
 *   <li>把传输超时压进 run 剩余时间之内（protocol §9）；</li>
 *   <li>失败一律以 {@link ModelProviderException} 表达，并如实标注 {@code requestSent}；</li>
 *   <li>不重试已发送的请求。</li>
 * </ul>
 * 预算预占与结算不在本接口内——那是 {@code agent.core.ModelCallGateway} 的职责，
 * 目的是让"过预算闸门 + 发远程 + 结算"始终是一个不可拆开的调用点。
 */
public interface ModelClient {

    /** 与 model_provider.provider_code 同口径（大写、trim）。 */
    String providerId();

    /**
     * 合成实现（Fake）必须返回 true：ModelClientLocator 据此在生产构建里拒绝它，
     * 让"生产环境静默连到假模型"在结构上不可能发生。
     */
    default boolean synthetic() {
        return false;
    }

    /**
     * @param context 提供凭据句柄、模型选择与截止时间；其中不含明文 Key
     * @throws ModelProviderException 本次调用没有可信结果
     */
    ModelResponse complete(ModelRequest request, AgentContext context);

    /**
     * 带取消句柄的重载：网关把手上的 {@link RunCancellation} 传下来，实现方据此尽力掐掉在途 HTTP。
     *
     * <p>默认实现忽略取消句柄（等价于两参数版本），这样不需要取消能力的实现（例如 Fake）零成本；
     * 生产实现必须覆盖它——"取消"对用户的承诺是"停止后续调用"，能掐掉在途请求就掐掉。
     */
    default ModelResponse complete(ModelRequest request, AgentContext context, RunCancellation cancellation) {
        return complete(request, context);
    }
}
