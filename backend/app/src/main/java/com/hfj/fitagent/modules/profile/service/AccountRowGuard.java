package com.hfj.fitagent.modules.profile.service;

import com.hfj.fitagent.modules.auth.mapper.UserAccountMapper;

/**
 * profile 模块访问 {@code user_account} 的<b>唯一</b>收口点，两个动作都是文档明确许可的基础设施操作：
 * <ol>
 *   <li>{@link #bumpDataRevision} —— docs/p1-database.md §6"任一业务写操作在同事务内
 *       {@code UPDATE user_account SET data_revision = data_revision + 1}"，增量由 SQL 自增，
 *       不在 Java 里算好再写回，否则并发写互相覆盖增量；</li>
 *   <li>{@link #lockAccount} —— 同一节要求状态迁移前 {@code SELECT ... FOR UPDATE} 锁住账户行，
 *       并规定<b>固定加锁顺序：先 user_account，再目标行</b>，防止死锁。</li>
 * </ol>
 *
 * <p>除此之外本模块一律不引用 auth 的 Mapper（docs/p1-architecture.md §4 规则 3：不跨模块调用 Mapper）。
 * 之所以写成 package-private 的静态方法而不是注入一个跨模块 Service：这两处只有一行 SQL 的语义，
 * 收在同一个类里，代码评审一眼就能看到"这里动了别人的表"，而不会散落在三个 Service 里各写一遍。
 *
 * <p>两个方法都<b>不</b>自带事务边界：它们必须落在调用方的业务事务里，否则修订号会与数据变更分离。
 */
final class AccountRowGuard {

    private AccountRowGuard() {
    }

    static void lockAccount(UserAccountMapper accountMapper, long userId) {
        if (accountMapper.lockById(userId) == null) {
            // 令牌主体已经保证账户存在，走到这里说明同一事务里账户行被并发删除：不是用户可修的错误
            throw new IllegalStateException("user_account 行不存在，无法加锁: userId=" + userId);
        }
    }

    static void bumpDataRevision(UserAccountMapper accountMapper, long userId) {
        if (accountMapper.incrementDataRevision(userId) != 1) {
            // 归属外键已经保证账户存在，走到这里说明同一事务里账户行被并发删除：不是用户可修的错误
            throw new IllegalStateException("data_revision 递增未命中账户行: userId=" + userId);
        }
    }
}
