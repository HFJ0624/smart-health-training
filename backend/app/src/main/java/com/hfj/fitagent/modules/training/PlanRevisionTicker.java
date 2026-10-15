package com.hfj.fitagent.modules.training;

import com.hfj.fitagent.modules.auth.mapper.UserAccountMapper;
import org.springframework.stereotype.Component;

/**
 * 数据修订号递增的唯一入口（docs/p1-database.md §6：档案/目标/体重/计划任一变更后，
 * 同事务 {@code UPDATE user_account SET data_revision = data_revision + 1}）。
 *
 * <p>这是 training 模块里<b>唯一</b>被批准的非本模块 Mapper 调用，所以单独包一层：
 * 正常规则是"不跨模块调用 Mapper"（docs/p1-architecture.md §4 第 3 条），
 * 而 {@code user_account.data_revision} 的自增是基础设施性质的——
 * 把它限制在这个组件里，评审时只需要看这一个文件就能确认没有越界读别人的表。
 *
 * <p>不自增成"读出来 +1 再写回"：并发写会互相覆盖增量，必须由 SQL 自己加。
 */
@Component
class PlanRevisionTicker {

    private final UserAccountMapper userAccountMapper;

    PlanRevisionTicker(UserAccountMapper userAccountMapper) {
        this.userAccountMapper = userAccountMapper;
    }

    /**
     * 必须处在调用者的业务事务里：修订号与业务写不同事务提交，
     * 报告类功能就会在"数据已改、修订号未改"的窗口里给出过期结论。
     *
     * @throws IllegalStateException 受影响行数为 0——账户不存在，说明认证主体与数据库不一致
     */
    void bump(long userId) {
        int updated = userAccountMapper.incrementDataRevision(userId);
        if (updated == 0) {
            throw new IllegalStateException("user_account 不存在，无法递增 data_revision: " + userId);
        }
    }
}
