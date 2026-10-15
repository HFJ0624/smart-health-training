package com.hfj.fitagent.agent.tool;

import java.util.List;

/**
 * 工具的统一返回形状（docs/agent-v1-design.md §3）。
 *
 * <p>几条语义纪律：
 * <ul>
 *   <li>{@code missingFields} 列出<b>已知缺失</b>的字段名；缺数据必须显式列出，不以 0 代替未知（design §9）。</li>
 *   <li>{@code dataComplete=false} 表示本次明细不完整（被字节上限丢弃、或部分来源读取失败）。
 *       上层会据此把结论降级为"不完整"，而不是让模型把半份数据当成全份。</li>
 *   <li>{@code truncated} 专指"给模型的文本被截断"，与 dataComplete 分开：进程内拿到的完整数据仍可组装事实卡片，
 *       但模型看到的和结论口径都必须标注不完整。</li>
 *   <li>{@code evidenceRefs} 是 {@link Evidence} 而不是 ID 字符串：事实卡片的值/单位/日期/规则版本
 *       只能由工具提供的这份来源反查得到（design §9），只给 ID 就没法"按引用取值"。</li>
 * </ul>
 *
 * @param dataRevision 读取时的数据修订号；与 run 结束时不同则结果标记陈旧
 * @param detail       面向模型的<b>结构化</b>失败说明（哪个字段不合规则、缺什么）；不得包含身份、凭据或个人数据堆叠
 */
public record ToolResult<R>(String code,
                            R data,
                            List<Evidence> evidenceRefs,
                            List<String> missingFields,
                            boolean dataComplete,
                            Long dataRevision,
                            boolean truncated,
                            String detail) {

    public ToolResult {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("ToolResult 必须有稳定 code");
        }
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
    }

    public static <R> ToolResult<R> ok(R data, List<Evidence> evidence, Long dataRevision) {
        return new ToolResult<>(ToolErrorCode.OK, data, evidence, List.of(), true, dataRevision, false, null);
    }

    public static <R> ToolResult<R> ok(R data, List<Evidence> evidence, List<String> missingFields,
                                       Long dataRevision) {
        return new ToolResult<>(ToolErrorCode.OK, data, evidence, missingFields, missingFields.isEmpty(),
                dataRevision, false, null);
    }

    /** 部分成功：有可用来源，但明细不完整。 */
    public static <R> ToolResult<R> partial(R data, List<Evidence> evidence, List<String> missingFields,
                                            Long dataRevision, String detail) {
        return new ToolResult<>(ToolErrorCode.OK, data, evidence, missingFields, false, dataRevision, false, detail);
    }

    /** 结构化错误：仍然要作为 tool 消息回传给模型（协议要求配对），但没有任何个人事实。 */
    public static <R> ToolResult<R> error(String code, String detail) {
        return new ToolResult<>(code, null, List.of(), List.of(), false, null, false, detail);
    }

    public boolean successful() {
        return ToolErrorCode.OK.equals(code);
    }

    public List<String> evidenceIds() {
        return evidenceRefs.stream().map(Evidence::evidenceId).toList();
    }

    /** 同一 key 用于结果复用缓存：(toolName, 规范化参数, dataRevision)。 */
    public String resultCacheKey(String toolName, String canonicalArguments) {
        return toolName + "|" + canonicalArguments + "|" + (dataRevision == null ? "-" : dataRevision);
    }

    @Override
    public String toString() {
        return "ToolResult[code=" + code + ", evidence=" + evidenceRefs.size()
                + ", missingFields=" + missingFields.size() + ", dataComplete=" + dataComplete
                + ", truncated=" + truncated + "]";
    }
}
