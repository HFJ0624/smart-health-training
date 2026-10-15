package com.hfj.fitagent.modules.coach.support;

import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.core.DataRevisionReader;
import com.hfj.fitagent.modules.auth.entity.UserAccountEntity;
import com.hfj.fitagent.modules.auth.mapper.UserAccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 陈旧判定与业务日期的读数入口（docs/agent-v1-design.md §9）。
 *
 * <p>这是 coach 模块里<b>唯一</b>被批准直接读 {@code user_account} 的组件，理由与
 * {@code modules/training/PlanRevisionTicker} 完全对称：修订号与时区都是基础设施列，
 * 把它们收在一个文件里，评审时只需要看这一处就能确认没有越界读别人的表（AGENTS.md 架构边界）。
 *
 * <p>读不到修订号时返回 {@link DataRevisionReader#UNKNOWN} 而不是 0：0 会被 Runner 当成"修订号变了"，
 * 于是每一次核对都宣称结果过期；读不到时区时回落 UTC 并打日志，业务日期宁可注明是推导值，
 * 也不能因为一个空列就不回答问题。
 */
@Component
public class CoachDataRevisionReader implements DataRevisionReader {

    private static final Logger log = LoggerFactory.getLogger(CoachDataRevisionReader.class);

    private final UserAccountMapper userAccountMapper;

    public CoachDataRevisionReader(UserAccountMapper userAccountMapper) {
        this.userAccountMapper = userAccountMapper;
    }

    @Override
    public long currentRevision(AgentContext context) {
        if (context == null) {
            return UNKNOWN;
        }
        return revisionOf(context.userId());
    }

    /** run 开始前取快照与结束时比对走同一个口径，否则陈旧判定没有意义。 */
    public long revisionOf(long userId) {
        Long revision = userAccountMapper.findDataRevision(userId);
        if (revision == null) {
            log.warn("修订号读取为空，按未核对处理 userId={}", userId);
            return UNKNOWN;
        }
        return revision;
    }

    /** 本人时区：业务日期由服务端按它推导，客户端不传（AGENTS.md 时间约定）。 */
    public ZoneId zoneOf(long userId) {
        UserAccountEntity account = userAccountMapper.selectById(userId);
        String raw = account == null ? null : account.getTimezone();
        if (raw == null || raw.isBlank()) {
            log.warn("账户未带时区，按 UTC 推导业务日期 userId={}", userId);
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(raw.trim());
        } catch (RuntimeException e) {
            log.warn("账户时区无法解析，按 UTC 推导业务日期 userId={} type={}",
                    userId, e.getClass().getSimpleName());
            return ZoneOffset.UTC;
        }
    }
}
