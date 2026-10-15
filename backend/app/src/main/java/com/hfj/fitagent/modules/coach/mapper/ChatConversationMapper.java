package com.hfj.fitagent.modules.coach.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.coach.entity.ChatConversationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

/**
 * chat_conversation 持久化。
 *
 * <p>{@link #lockById(long, long)} 是消息序号分配的入口：会话行是"同一会话内顺序"的串行点，
 * {@code SELECT ... FOR UPDATE} 让并发提问各自拿到唯一的 message_no（设计 §8）。
 * 双条件 (id, user_id) 让越权读取在 SQL 层面就拿不到行。
 *
 * <p>本表没有 JSON 列，所以自定义 {@code @Select} 返回实体是安全的（agent_run / chat_message 不适用这条）。
 */
@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversationEntity> {

    /** 行锁读取：调用方必须处在事务中，锁随事务提交释放。 */
    @Select("""
            SELECT id, user_id, title, status, last_message_at, lock_version, created_at, updated_at
            FROM chat_conversation
            WHERE id = #{id} AND user_id = #{userId}
            FOR UPDATE
            """)
    ChatConversationEntity lockById(@Param("id") long id, @Param("userId") long userId);

    /**
     * 首条消息定标题 + 刷新 last_message_at。
     *
     * <p>{@code title} 只在仍为空时写入（COALESCE）：标题来自第一个问题，后续提问不得覆盖它，
     * 否则会话名会随对话推进漂移，历史页就无法认出同一个会话。
     */
    @Update("""
            UPDATE chat_conversation
            SET last_message_at = #{occurredAt},
                title = COALESCE(title, #{title}),
                lock_version = lock_version + 1
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int touch(@Param("id") long id, @Param("userId") long userId,
              @Param("title") String title, @Param("occurredAt") Instant occurredAt);

    /** 归档：只动 ACTIVE 行，重复归档影响 0 行（幂等）。 */
    @Update("""
            UPDATE chat_conversation
            SET status = 'ARCHIVED', lock_version = lock_version + 1
            WHERE id = #{id} AND user_id = #{userId} AND status = 'ACTIVE'
            """)
    int archive(@Param("id") long id, @Param("userId") long userId);
}
