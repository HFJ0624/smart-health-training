package com.hfj.fitagent.agent.tool;

import tools.jackson.databind.JsonNode;

/**
 * 一次 tool_call 的<em>执行完毕</em>状态：包含要回传给模型的那条 tool 消息内容。
 *
 * <p>为什么要同时带 {@code result} 与 {@code modelPayload}：
 * <ul>
 *   <li>{@code modelPayload} 是<b>受字节上限约束</b>的 JSON 文本，只决定模型看到什么；</li>
 *   <li>{@code result}/{@code dataSnapshot} 是进程内拿到的完整来源，事实卡片按引用从这里取值
 *       （design §9：个人数值不由模型填写）。</li>
 * </ul>
 * 两者分开后，"给模型的文本被截断"就不会连带把已验证事实也丢掉，但 {@code truncated}/{@code dataComplete}
 * 会如实标注不完整，界面与结论口径都按不完整处理。
 *
 * @param fromCache  结果来自本 run 的复用缓存（未重新执行工具，也未重新花钱）
 * @param requestHits 该键在本 run 内被请求的次数（含本次），Runner 用它做 REPETITIVE_LOOP 判定
 * @param durationMs 实际执行耗时；缓存命中为 0
 */
public record ExecutedTool(String toolCallId,
                           String toolName,
                           ToolResult<?> result,
                           String modelPayload,
                           JsonNode dataSnapshot,
                           boolean executed,
                           long durationMs,
                           String cacheKey,
                           boolean fromCache,
                           int requestHits) {

    public ExecutedTool {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("ExecutedTool 必须绑定 tool_call_id");
        }
        if (result == null) {
            throw new IllegalArgumentException("ExecutedTool 必须有结构化结果");
        }
        if (modelPayload == null || modelPayload.isBlank()) {
            throw new IllegalArgumentException("ExecutedTool 必须有回传给模型的内容（失败也要回传结构化错误）");
        }
    }

    /** 缓存复放：内容不变，但必须绑定<b>新的</b> tool_call_id，否则协议配对会错。 */
    public ExecutedTool forToolCallId(String newToolCallId, int hits) {
        return new ExecutedTool(newToolCallId, toolName, result, modelPayload, dataSnapshot, false, 0L,
                cacheKey, true, hits);
    }

    ExecutedTool withHitCount(int hits) {
        return new ExecutedTool(toolCallId, toolName, result, modelPayload, dataSnapshot, executed, durationMs,
                cacheKey, fromCache, hits);
    }

    public boolean successful() {
        return result.successful();
    }

    /** 失败也要作为结构化 tool 消息回传（design §5.4：不执行"半个协议组"，但必须完成配对）。 */
    public String errorCode() {
        return successful() ? null : result.code();
    }

    @Override
    public String toString() {
        return "ExecutedTool[" + toolName + " id=" + toolCallId + " code=" + result.code()
                + " executed=" + executed + " fromCache=" + fromCache + " truncated=" + result.truncated()
                + " chars=" + modelPayload.length() + "]";
    }
}
