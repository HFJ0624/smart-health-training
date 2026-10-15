package com.hfj.fitagent.modules.coach.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hfj.fitagent.modules.coach.entity.AgentRunEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * agent_run 持久化：幂等、原子领取、终态写入、恢复扫描。
 *
 * <p>两条纪律贯穿本类：
 * <ol>
 *   <li><b>状态转移全部是条件更新</b>（{@code AND status = ...}）。影响 0 行就是"这次不该动"，
 *       不是"重试到动为止"——同一个 run 因此不可能被两个 Worker 并行执行（设计 §10）。</li>
 *   <li><b>本类没有任何返回实体的自定义 SQL</b>：{@code budget_snapshot} 与 {@code result_payload}
 *       是 JSON 列，自定义 {@code @Select} 会丢掉 {@code Jackson3TypeHandler} 而把它们读成字符串。
 *       实体读取一律走 BaseMapper + QueryWrapper；这里返回的都是标量或 id。</li>
 * </ol>
 *
 * <p>{@code active_conversation_id} 不出现在任何语句里：它是 STORED 生成列（AGENTS.md）。
 * 单会话单活动 run 的判定方式是"插入后捕获重复键"，见 {@code CoachApplicationService}。
 */
@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRunEntity> {

    /**
     * 幂等判定用：只取 request_hash，不取实体（同一把键命中与否与 JSON 列无关，少读一列就少一处坏点）。
     *
     * @return 同 (user_id, idempotency_key) 已有行的 hash；无行返回 null
     */
    @Select("""
            SELECT request_hash FROM agent_run
            WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey}
            """)
    String findRequestHashByKey(@Param("userId") long userId, @Param("idempotencyKey") String idempotencyKey);

    /** 同键同 hash 时回读 run id（replay 返回同一个 run，绝不新插一条）。 */
    @Select("""
            SELECT id FROM agent_run
            WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey}
            """)
    Long findIdByKey(@Param("userId") long userId, @Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT conversation_id FROM agent_run WHERE id = #{runId} AND user_id = #{userId}")
    Long findConversationId(@Param("runId") long runId, @Param("userId") long userId);

    /**
     * 原子领取：QUEUED → RUNNING。
     *
     * <p>0 行 = 已经被别的 Worker 领走 / 已是终态 / 已被取消，调用方必须<b>什么都不做</b>地退出，
     * 不能"再试一次"，也不能把自己已经跑过一遍的结果写回去。
     */
    @Update("""
            UPDATE agent_run
            SET status = 'RUNNING', worker_id = #{workerId}, started_at = #{startedAt},
                lease_expires_at = #{leaseExpiresAt}, lock_version = lock_version + 1
            WHERE id = #{runId} AND status = 'QUEUED'
            """)
    int claim(@Param("runId") long runId, @Param("workerId") String workerId,
              @Param("startedAt") Instant startedAt, @Param("leaseExpiresAt") Instant leaseExpiresAt);

    /** 心跳续租：worker_id 必须还是自己，否则说明租约已被接管（本 Worker 应当停止写入）。 */
    @Update("""
            UPDATE agent_run
            SET lease_expires_at = #{leaseExpiresAt}
            WHERE id = #{runId} AND user_id = #{userId} AND status = 'RUNNING' AND worker_id = #{workerId}
            """)
    int renewLease(@Param("runId") long runId, @Param("userId") long userId,
                   @Param("workerId") String workerId, @Param("leaseExpiresAt") Instant leaseExpiresAt);

    /**
     * 终态写入：完整结果 + 费用 + 停止原因一次落定。
     *
     * <p>WHERE 限定"仍在 QUEUED/RUNNING"，因此取消后迟到的 Worker 不会覆盖已写好的终态，
     * 同一个 run 也不会被两次结算写花。
     * {@code resultPayloadJson} 是完整 AgentAnswer 的 JSON 文本（MySQL 侧按 JSON 列隐式解析）：
     * 页面重进读的就是这一份，<b>不</b>用 agent_step 的截断摘要冒充。
     */
    @Update("""
            UPDATE agent_run
            SET status = #{status}, stop_reason = #{stopReason}, error_code = #{errorCode},
                result_payload = #{resultPayloadJson},
                settled_cost = #{settledCost}, uncertain_cost = #{uncertainCost},
                finished_at = #{finishedAt}, worker_id = NULL, lease_expires_at = NULL,
                lock_version = lock_version + 1
            WHERE id = #{runId} AND user_id = #{userId} AND status IN ('QUEUED','RUNNING')
            """)
    int finishCas(@Param("runId") long runId, @Param("userId") long userId,
                  @Param("status") String status, @Param("stopReason") String stopReason,
                  @Param("errorCode") String errorCode, @Param("resultPayloadJson") String resultPayloadJson,
                  @Param("settledCost") BigDecimal settledCost, @Param("uncertainCost") BigDecimal uncertainCost,
                  @Param("finishedAt") Instant finishedAt);

    /**
     * 取消：只有 QUEUED 能被直接转成终态（一次调用都没发起，转了不会丢任何"可能已计费"的信息）。
     * RUNNING 走 {@link #markCancelRequested}，由执行中的 Worker 收敛到 CANCELLED。
     */
    @Update("""
            UPDATE agent_run
            SET status = 'CANCELLED', stop_reason = 'CANCELLED', cancel_requested_at = #{requestedAt},
                finished_at = #{requestedAt}, worker_id = NULL, lease_expires_at = NULL,
                lock_version = lock_version + 1
            WHERE id = #{runId} AND user_id = #{userId} AND status = 'QUEUED'
            """)
    int cancelQueued(@Param("runId") long runId, @Param("userId") long userId,
                     @Param("requestedAt") Instant requestedAt);

    /** 已请求取消的标记（幂等：COALESCE 保留第一次的时刻）。 */
    @Update("""
            UPDATE agent_run
            SET cancel_requested_at = COALESCE(cancel_requested_at, #{requestedAt}),
                lock_version = lock_version + 1
            WHERE id = #{runId} AND user_id = #{userId} AND status = 'RUNNING'
            """)
    int markCancelRequested(@Param("runId") long runId, @Param("userId") long userId,
                            @Param("requestedAt") Instant requestedAt);

    @Select("SELECT cancel_requested_at FROM agent_run WHERE id = #{runId} AND user_id = #{userId}")
    Instant findCancelRequestedAt(@Param("runId") long runId, @Param("userId") long userId);

    /**
     * 恢复：没有任何"可能已发送"的调用时，把失去 Worker 的 RUNNING 退回 QUEUED 重新领取。
     * finished_at 必须清空（列上的 CHECK 要求非终态没有终态时刻）。
     */
    @Update("""
            UPDATE agent_run
            SET status = 'QUEUED', worker_id = NULL, lease_expires_at = NULL, started_at = NULL,
                lock_version = lock_version + 1
            WHERE id = #{runId} AND user_id = #{userId} AND status = 'RUNNING'
            """)
    int reopenAsQueued(@Param("runId") long runId, @Param("userId") long userId);

    /**
     * 恢复：存在可能已发送的调用 → FAILED + UNKNOWN_CHARGE。
     *
     * <p><b>不重发</b>：重发一次可能已送达的请求就是把一次扣费变成两次（设计 §5 第 6 条、§10）。
     * 预占留在 UNCERTAIN 继续占用额度，界面显示"存在用量不明"。
     */
    @Update("""
            UPDATE agent_run
            SET status = 'FAILED', stop_reason = 'UNKNOWN_CHARGE', error_code = 'UNKNOWN_CHARGE',
                finished_at = #{finishedAt}, worker_id = NULL, lease_expires_at = NULL,
                lock_version = lock_version + 1
            WHERE id = #{runId} AND user_id = #{userId} AND status = 'RUNNING'
            """)
    int failWithUnknownCharge(@Param("runId") long runId, @Param("userId") long userId,
                              @Param("finishedAt") Instant finishedAt);

    /** 启动扫描用：租约已过期的 RUNNING 行（含各自 user_id，恢复更新必须带归属条件）。 */
    @Select("""
            SELECT id FROM agent_run
            WHERE status = 'RUNNING' AND (lease_expires_at IS NULL OR lease_expires_at < #{now})
            ORDER BY id
            LIMIT #{limit}
            """)
    List<Long> findStaleRunningIds(@Param("now") Instant now, @Param("limit") int limit);

    /** 启动扫描用：遗漏的 QUEUED（进程在派发前崩掉、或队列当时已满）。 */
    @Select("""
            SELECT id FROM agent_run
            WHERE status = 'QUEUED'
            ORDER BY id
            LIMIT #{limit}
            """)
    List<Long> findQueuedIds(@Param("limit") int limit);

    /** 归属校验用（越权与不存在同形）：只回答"这一行是不是这个用户的"。 */
    @Select("SELECT user_id FROM agent_run WHERE id = #{runId}")
    Long findUserIdById(@Param("runId") long runId);
}
