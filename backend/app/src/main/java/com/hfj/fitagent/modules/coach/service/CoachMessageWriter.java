package com.hfj.fitagent.modules.coach.service;

import com.hfj.fitagent.modules.coach.entity.ChatMessageEntity;
import com.hfj.fitagent.modules.coach.mapper.ChatConversationMapper;
import com.hfj.fitagent.modules.coach.mapper.ChatMessageMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 会话消息顺序的唯一写入口（docs/agent-v1-design.md §8）。
 *
 * <p>{@code message_no} 必须在<b>会话行锁</b>下取 {@code MAX+1}：先查后插的写法在并发提问时会给出
 * 两个相同的序号，然后撞 {@code uq_chat_message_1(conversation_id, message_no)}——
 * 那时用户看到的就是"消息交错"。行锁由 {@link ChatConversationMapper#lockById} 提供，
 * 因此调用方必须处在一个事务里。
 */
@Component
public class CoachMessageWriter {

    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;

    public CoachMessageWriter(ChatConversationMapper conversationMapper, ChatMessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    /**
     * @param role     {@code user} 或 {@code assistant}（小写字面量，数据库列是 ENUM）
     * @param complete 正文完整性：{@code status} 只有 COMPLETE / INCOMPLETE 两个合法值，
     *                 停止或失败收尾的确定性模板写 INCOMPLETE，页面据此显示"本条不是完整回答"
     * @return 已落库的消息行（含数据库分配的 id）
     * @throws IllegalStateException 会话行锁拿不到——认证主体与数据库不一致，或会话已被并发删除
     */
    @Transactional
    public ChatMessageEntity append(long userId, long conversationId, Long runId, String role, String content,
                                    List<Map<String, Object>> factCards, List<String> citations,
                                    List<String> missingFields, Long dataRevision, Boolean isStale,
                                    boolean complete) {
        if (conversationMapper.lockById(conversationId, userId) == null) {
            throw new IllegalStateException("会话行不存在或不属于本人，无法分配消息序号: conversationId="
                    + conversationId);
        }
        int next = messageMapper.findMaxMessageNo(conversationId, userId) + 1;

        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setUserId(userId);
        entity.setConversationId(conversationId);
        entity.setRunId(runId);
        entity.setMessageNo(next);
        entity.setRole(role);
        entity.setContentText(content);
        entity.setFactCards(factCards);
        entity.setCitations(citations);
        entity.setMissingFields(missingFields);
        entity.setDataRevision(dataRevision);
        entity.setIsStale(isStale);
        entity.setStatus(complete ? "COMPLETE" : "INCOMPLETE");
        messageMapper.insert(entity);
        return entity;
    }

    /** 会话列表按最近活跃排序用。 */
    @Transactional
    public void touch(long userId, long conversationId, String title, Instant occurredAt) {
        conversationMapper.touch(conversationId, userId, title, occurredAt);
    }
}
