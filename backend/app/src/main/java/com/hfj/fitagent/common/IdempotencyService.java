package com.hfj.fitagent.common;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.util.Ids;
import com.hfj.fitagent.common.web.FieldError;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 请求幂等：Redis 实现，TTL 24 小时（已在规划阶段确认的取舍——窗口外同键会生成新计划）。
 *
 * 采用「先占位再执行」，而不是「执行完再记」：否则两个并发同键请求都会读到空值并各建一份计划。
 * 占位值为 PENDING 表示同键请求正在进行中；完成后可读出首次结果。
 */
@Service
public class IdempotencyService {

    public static final Duration TTL = Duration.ofHours(24);
    private static final String PREFIX = "fa:imp:";
    private static final String PENDING = "__pending__";

    private final StringRedisTemplate redis;

    public IdempotencyService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 校验并规范化客户端幂等键；非法格式转为字段错误，不静默接受。 */
    public String normalizeKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return Ids.newUuid();
        }
        try {
            return Ids.canonicalUuid(raw);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key 必须是 UUID v4 文本",
                    List.of(FieldError.of("Idempotency-Key", "INVALID_UUID_FORMAT", "Idempotency-Key 必须是 UUID v4 文本", raw)));
        }
    }

    /**
     * 占位。返回首次结果（重放），或 empty 表示本次抢到执行权。
     *
     * @throws BizException 同键请求仍在执行中
     */
    public Optional<String> claimOrReplay(long userId, String key) {
        String redisKey = key(userId, key);
        Boolean claimed = redis.opsForValue().setIfAbsent(redisKey, PENDING, TTL);
        if (Boolean.TRUE.equals(claimed)) {
            return Optional.empty();
        }
        String existing = redis.opsForValue().get(redisKey);
        if (existing == null || PENDING.equals(existing)) {
            throw new BizException(ErrorCode.DUPLICATE_REQUEST, "相同幂等键的请求正在处理中，请稍后查询结果");
        }
        return Optional.of(existing);
    }

    /** 记录首次结果并把 TTL 重置为完整窗口。 */
    public void complete(long userId, String key, String resultId) {
        redis.opsForValue().set(key(userId, key), resultId, TTL);
    }

    /** 执行失败时释放占位，允许客户端用同键重试（避免把失败也固化成 24 小时的"重放结果"）。 */
    public void release(long userId, String key) {
        redis.delete(key(userId, key));
    }

    public void renewClaimOnDuplicateKey(long userId, String key, String resultId) {
        // 数据库唯一索引兜底命中时，把占位改写为真实结果，后续同键走重放分支
        complete(userId, key, resultId);
    }

    private String key(long userId, String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("幂等键不能为空");
        }
        return PREFIX + userId + ":" + key;
    }

    /** 供聚合写入在撞唯一索引时统一转换语义。 */
    public static boolean isDuplicateKey(Throwable t) {
        return t instanceof DuplicateKeyException;
    }
}
