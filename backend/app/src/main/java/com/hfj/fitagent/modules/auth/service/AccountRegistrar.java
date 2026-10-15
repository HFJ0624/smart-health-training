package com.hfj.fitagent.modules.auth.service;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.auth.entity.UserAccountEntity;
import com.hfj.fitagent.modules.auth.mapper.UserAccountMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号写入的唯一落点：一次 `user_account` 插入，跑在自己的短事务里。
 *
 * 为什么单独成一个 Bean 而不是 AccountProvisioningService 的私有方法：
 * `@Transactional` 靠代理生效，同类内部自调用会绕过代理，REQUIRES_NEW 会静默退化成"跟着外层登录方法的事务"，
 * 一旦插入撞唯一索引，外层事务被标记 rollback-only 后无法再复用连接重查（docs/p1-database.md §6 明确要避免这个形态）。
 *
 * `created_at` / `updated_at` / `data_revision` 由数据库默认值与显式 SQL 维护，这里一律不设
 * （Entity 上它们的 insert/update 策略是 NEVER，Java 侧写值只会被丢弃或造成与数据库不一致）。
 */
@Service
public class AccountRegistrar {

    static final String ROLE_USER = "USER";
    static final String STATUS_ACTIVE = "ACTIVE";
    static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    private final UserAccountMapper userAccounts;

    AccountRegistrar(UserAccountMapper userAccounts) {
        this.userAccounts = userAccounts;
    }

    /**
     * 新账号注册：首轮登录自动建档，无独立注册表单（docs/p1-scope.md §1.1(a)）。
     * 唯一索引 {@code uq_user_account_1} 冲突时抛 {@code DuplicateKeyException}，由调用方处理。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserAccountEntity insert(String appId, String openId, String unionId) {
        UserAccountEntity account = new UserAccountEntity();
        account.setWechatAppId(appId);
        account.setWechatOpenId(openId);
        account.setWechatUnionId(unionId);
        account.setRole(ROLE_USER);
        account.setStatus(STATUS_ACTIVE);
        account.setTimezone(DEFAULT_TIMEZONE);

        if (userAccounts.insert(account) != 1 || account.getId() == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "账号创建失败，请稍后重试");
        }
        return account;
    }
}
