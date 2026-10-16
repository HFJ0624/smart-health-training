package com.hfj.fitagent.modules.workout;

import com.hfj.fitagent.modules.auth.mapper.UserAccountMapper;
import org.springframework.stereotype.Component;

/**
 * 训练写操作的数据修订号自增（docs/p1-architecture.md §4 第 3 条的唯一豁免，本模块一份）。
 *
 * <p>与 {@code PlanRevisionTicker} 同构：这是 workout 模块里唯一被批准碰别人表的地方，
 * 单独包一层，评审只看这一个文件就能确认没有越界读。
 * 递增必须由 SQL 完成（读出来 +1 再写回会在并发下互相覆盖）。
 */
@Component
class WorkoutRevisionTicker {

    private final UserAccountMapper userAccountMapper;

    WorkoutRevisionTicker(UserAccountMapper userAccountMapper) {
        this.userAccountMapper = userAccountMapper;
    }

    /** 必须处在调用者的业务事务里：修订号与业务写分开提交，统计就会在"数据已改、号未改"的窗口里给出过期结论。 */
    void bump(long userId) {
        if (userAccountMapper.incrementDataRevision(userId) == 0) {
            throw new IllegalStateException("user_account 不存在，无法递增 data_revision: " + userId);
        }
    }
}
