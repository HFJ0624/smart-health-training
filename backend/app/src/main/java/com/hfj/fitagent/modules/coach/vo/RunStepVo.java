package com.hfj.fitagent.modules.coach.vo;

import com.hfj.fitagent.modules.coach.entity.AgentStepEntity;

/**
 * 脱敏操作摘要（§11.1 规范化决定第 6 条）：
 * {@code \{ stepNo, eventType, toolName, summary, status, errorCode, durationMs \}}。
 *
 * <p><b>永不</b>返回原始参数、{@code reasoning_content}、请求体或密钥：
 * 实体的 {@code argumentsRedacted} 在本转换里<b>不</b>被带出——即使它已经过脱敏，
 * 参数原文也不是界面需要的东西，而"少一个出口"比"记得过滤"可靠。
 * {@code summary} 是工程摘要（几个来源 / 是否完整），不是回答正文；正文只在 run 的 answer 里。
 */
public record RunStepVo(Integer stepNo,
                        String eventType,
                        String toolName,
                        String summary,
                        String status,
                        String errorCode,
                        Long durationMs) {

    public static RunStepVo from(AgentStepEntity entity) {
        if (entity == null) {
            return null;
        }
        return new RunStepVo(entity.getStepNo(), entity.getEventType(), entity.getToolName(),
                entity.getResultSummary(), entity.getStatus(), entity.getErrorCode(), entity.getDurationMs());
    }
}
