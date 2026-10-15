package com.hfj.fitagent.agent.tool;

import com.hfj.fitagent.agent.core.AgentContext;

import java.time.Duration;

/**
 * 一个可被模型调用的工具。
 *
 * <p>实现位置在 {@code modules/coach/tools}，只允许注入既有 Service 与只读端口，
 * 且必须只读——本轮不开放任何写操作（自动改计划/激活计划在范围外）。
 *
 * <p>授权是<b>构造性的</b>：{@code A} 里不允许出现 userId / credential / budget 之类字段，
 * 身份来自 {@link AgentContext}（服务端装配，客户端无法影响）。
 * ToolExecutor 还有一道反射兜底，但兜底只是兜底——真正的边界是"参数类型里没有身份字段"。
 *
 * @param <A> 参数类型（record + Bean Validation 注解）
 * @param <R> 业务数据类型，必须能序列化成 JSON
 */
public interface AgentTool<A, R> {

    /** 暴露给模型的声明：name 全局唯一（重名启动即失败）。 */
    ToolDefinition definition();

    /** 参数类型；ToolExecutor 按它做严格 JSON 解析（未知字段拒绝）后做 Bean Validation。 */
    Class<A> argumentType();

    /**
     * 执行读取。失败也要返回结构化 {@link ToolResult}，不要抛异常冒充协议错误：
     * 抛出去会被执行器捕获成 INTERNAL_ERROR，但语义（"没有这条记录" vs "后端坏了"）就丢了。
     */
    ToolResult<R> execute(A args, AgentContext ctx);

    /** 单次执行的时长上限；null 表示用执行器的默认工程闸门。与 run 剩余时间取小者。 */
    default Duration maxDuration() {
        return null;
    }
}
