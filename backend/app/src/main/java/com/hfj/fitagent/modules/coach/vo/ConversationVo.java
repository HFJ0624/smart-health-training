package com.hfj.fitagent.modules.coach.vo;

import com.hfj.fitagent.modules.coach.entity.ChatConversationEntity;

import java.time.Instant;

/**
 * 会话行（§11.1 的 {@code CoachConversation}：{@code \{id, title, status, lastMessageAt\}}）。
 *
 * <p>字段名一个都不改：小程序已经按这份载荷实现（{@code miniapp/src/types/coach.ts}）。
 * {@code id} 是字符串（AGENTS.md：BigInt 在 JSON 里是字符串）。
 */
public record ConversationVo(String id,
                             String title,
                             String status,
                             Instant lastMessageAt) {

    public static ConversationVo from(ChatConversationEntity entity) {
        if (entity == null) {
            return null;
        }
        return new ConversationVo(str(entity.getId()), entity.getTitle(), entity.getStatus(),
                entity.getLastMessageAt());
    }

    private static String str(Long value) {
        return value == null ? null : Long.toString(value);
    }
}
