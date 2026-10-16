package com.hfj.fitagent.modules.metrics.support;

import com.hfj.fitagent.modules.auth.mapper.UserAccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 指标出参里 {@code dataRevision} 的唯一读取点（docs/p1-architecture.md §4 的读侧对应物）。
 *
 * <p>为什么单独立一个文件：metrics 是只读模块，既不能像 {@code WorkoutRevisionTicker} 那样递增修订号，
 * 也不该在 Service 里到处摸 {@code UserAccountMapper}。把跨模块的读收敛成这一个类、这一个方法，
 * 评审只需要看这里就能确认没有越界读别人的表；{@code TrainingPlanService}/{@code WorkoutService}
 * 目前都没有公开过"读修订号"的方法，所以不能改走它们的公开接口。
 *
 * <p>读不到时返回 null 而不是 0：0 会被当成"数据一次都没变过"，从而让页面宣称旧结论仍然有效。
 */
@Component
public class MetricsDataRevisionReader {

    private static final Logger log = LoggerFactory.getLogger(MetricsDataRevisionReader.class);

    private final UserAccountMapper userAccountMapper;

    public MetricsDataRevisionReader(UserAccountMapper userAccountMapper) {
        this.userAccountMapper = userAccountMapper;
    }

    /** 只读：BigInt 一律以字符串出参（契约 §1），缺失保持 null。 */
    public String currentRevisionText(long userId) {
        Long revision;
        try {
            revision = userAccountMapper.findDataRevision(userId);
        } catch (RuntimeException e) {
            // 统计本身不依赖修订号，读不到就标注为未知，不能把整个只读接口带崩
            log.warn("data_revision 读取失败，按未知处理 userId={} type={}", userId, e.getClass().getSimpleName());
            return null;
        }
        return revision == null ? null : Long.toString(revision);
    }
}
