package com.hfj.fitagent.modules.coach.vo;

import com.hfj.fitagent.modules.coach.entity.ChatMessageEntity;

import java.time.Instant;
import java.util.List;

/**
 * 消息行（§11.1 规范化决定第 5 条，字段名逐个对齐，不得另起一名）：
 * {@code \{ id, messageNo, role, content, runId, createdAt, factCards[], missingFields[], isStale, dataRevision \}}。
 *
 * <p>三条容易写歪的地方：
 * <ul>
 *   <li>{@code role} 是<b>小写</b>字面量（user/assistant），前端用 {@code role:'user'} 匹配；</li>
 *   <li>{@code dataRevision} 是字符串（BigInt 对外一律字符串），而 {@code messageNo} 是 INT，保持数字；</li>
 *   <li>{@code factCards} 为 {@code null} 时前端<b>不补造</b>，所以这里也照实给 null，
 *       而不是给一个空数组让页面显示"无事实卡片"这种带结论的文案。</li>
 * </ul>
 */
public record CoachMessageVo(String id,
                             Integer messageNo,
                             String role,
                             String content,
                             String runId,
                             Instant createdAt,
                             List<java.util.Map<String, Object>> factCards,
                             List<String> missingFields,
                             Boolean isStale,
                             String dataRevision) {

    public static CoachMessageVo from(ChatMessageEntity entity) {
        if (entity == null) {
            return null;
        }
        return new CoachMessageVo(str(entity.getId()), entity.getMessageNo(), entity.getRole(),
                entity.getContentText(), str(entity.getRunId()), entity.getCreatedAt(),
                entity.getFactCards(), entity.getMissingFields(), entity.getIsStale(),
                str(entity.getDataRevision()));
    }

    private static String str(Long value) {
        return value == null ? null : Long.toString(value);
    }
}
