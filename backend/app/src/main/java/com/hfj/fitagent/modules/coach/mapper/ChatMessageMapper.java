package com.hfj.fitagent.modules.coach.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.coach.entity.ChatMessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * chat_message 持久化。
 *
 * <p>{@link #findMaxMessageNo} 必须在 {@code SELECT ... FOR UPDATE} 锁住会话行<b>之后</b>调用
 * （由 {@code MessageOrderAllocator} 保证顺序）：max+1 本身不是原子操作，
 * 它的原子性完全来自会话行锁，UNIQUE(conversation_id, message_no) 只是最后一道防线。
 *
 * <p>本表有 4 个 JSON 列，实体读取一律走 BaseMapper（自定义 @Select 会丢类型处理器）。
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {

    @Select("""
            SELECT COALESCE(MAX(message_no), 0) FROM chat_message
            WHERE conversation_id = #{conversationId} AND user_id = #{userId}
            """)
    int findMaxMessageNo(@Param("conversationId") long conversationId, @Param("userId") long userId);

    /** 历史回放的边界口径：会话内已经落定的 assistant 正文条数（用于"上一轮回答"的兜底读取）。 */
    @Select("""
            SELECT COALESCE(MAX(message_no), 0) FROM chat_message
            WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND role = #{role}
            """)
    int findMaxMessageNoByRole(@Param("conversationId") long conversationId, @Param("userId") long userId,
                               @Param("role") String role);
}
