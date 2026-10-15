package com.hfj.fitagent.modules.coach.vo;

import java.time.Instant;
import java.util.Map;

/**
 * {@code GET /api/coach/runs/\{id\}} 的响应（§11.1）。
 *
 * <p>三条契约都在类型上落实：
 * <ol>
 *   <li><b>费用只在 run 响应上</b>（§11.1 第 1 条）：{@code costKnown}/{@code costUncertain} 是
 *       DECIMAL(20,10) 的<b>字符串</b>，而 {@code answer} 里不放金额——两处都能放就一定漂移。
 *       两个值都来自 {@code agent_run} 行的列，不由前端算、也不由内存里的 ledger 猜。</li>
 *   <li><b>{@code answer} 是持久化的完整 AgentAnswer</b>（§7）：直接透出 {@code result_payload}，
 *       页面重进读同一份事实快照，不依赖内存，也不拿 {@code agent_step.result_summary} 的截断文本冒充。</li>
 *   <li><b>{@code fakeModel} 必须存在</b>（§11.1 第 2 条）：缺失时前端只能显示"无法确认是真实还是模拟"。</li>
 * </ol>
 *
 * @param stopReason 未识别的取值前端原样显示机器码，所以这里也照实透出不做映射
 * @param progress   进度摘要来自 agent_step 的计数（工程事实：跑了几个调用、几步轨迹）
 * @param errorCode  稳定机器码（如 UNKNOWN_CHARGE），无错误为 null
 */
public record RunStateVo(String runId,
                         String conversationId,
                         String status,
                         String stopReason,
                         Progress progress,
                         Map<String, Object> answer,
                         String costKnown,
                         String costUncertain,
                         String currency,
                         Boolean isStale,
                         Boolean fakeModel,
                         String errorCode,
                         Instant createdAt,
                         Instant finishedAt) {

    /**
     * @param modelCalls 本 run 声明过的模型调用次数
     * @param toolCalls  本 run 真实执行过的工具次数（缓存复放不重复计数）
     * @param stepCount  轨迹总步数
     */
    public record Progress(Integer modelCalls, Integer toolCalls, Integer stepCount) {
    }
}
