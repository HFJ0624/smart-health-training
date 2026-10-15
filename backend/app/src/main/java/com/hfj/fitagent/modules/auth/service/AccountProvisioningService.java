package com.hfj.fitagent.modules.auth.service;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.util.Ids;
import com.hfj.fitagent.modules.auth.entity.UserAccountEntity;
import com.hfj.fitagent.modules.auth.mapper.UserAccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * "按微信身份找到账号，没有就创建"。
 *
 * 并发首登只注册一个账号，靠的是唯一索引 {@code uq_user_account_1 (wechat_app_id, wechat_open_id)}，
 * "先查后插"只是省一次插入的性能优化，不是正确性保证（docs/p1-database.md §3.1）。
 * 本方法自身<b>不开事务</b>：插入在 {@link AccountRegistrar} 的 REQUIRES_NEW 短事务里完成，
 * 冲突后重查使用的是新读，不会被回滚中的事务污染。
 *
 * 规范化口径：appId/openid/unionid 只走 {@link Ids#exactExternalId}（仅 trim，绝不折叠大小写）。
 * 这些串由微信生成且大小写有意义，折叠会把两个不同用户判成同一个账号，属身份混淆级风险。
 */
@Service
public class AccountProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(AccountProvisioningService.class);

    private final UserAccountMapper userAccounts;
    private final AccountRegistrar registrar;

    AccountProvisioningService(UserAccountMapper userAccounts, AccountRegistrar registrar) {
        this.userAccounts = userAccounts;
        this.registrar = registrar;
    }

    /**
     * @param account   找到或刚创建的账号
     * @param newAccount 本次调用是否真的插入了新行（并发场景下为 false）
     */
    public record Provisioned(UserAccountEntity account, boolean newAccount) {
    }

    /** 只要账号，不关心是否新建；需要区分首登时用 {@link #provision}。 */
    public UserAccountEntity findOrCreate(String appId, String openId, String unionId) {
        return provision(appId, openId, unionId).account();
    }

    public Provisioned provision(String appId, String openId, String unionId) {
        String exactAppId = Ids.exactExternalId(appId);
        if (exactAppId == null) {
            throw new BizException(ErrorCode.WECHAT_CONFIG_MISSING);
        }
        String exactOpenId = Ids.exactExternalId(openId);
        if (exactOpenId == null) {
            throw new BizException(ErrorCode.WECHAT_LOGIN_FAILED, "微信未返回身份标识，请稍后重试");
        }
        String exactUnionId = Ids.exactExternalId(unionId);

        UserAccountEntity existing = userAccounts.findByAppIdAndOpenId(exactAppId, exactOpenId);
        if (existing != null) {
            requireId(existing);
            return new Provisioned(existing, false);
        }

        try {
            UserAccountEntity created = registrar.insert(exactAppId, exactOpenId, exactUnionId);
            requireId(created);
            log.info("首次登录自动注册账号 userId={}", created.getId());
            return new Provisioned(created, true);
        } catch (DuplicateKeyException e) {
            // 另一个并发请求已抢先插入：它的短事务已提交，这里读到的一定是完整行
            UserAccountEntity concurrent = userAccounts.findByAppIdAndOpenId(exactAppId, exactOpenId);
            if (concurrent == null) {
                log.error("唯一索引冲突后仍查不到账号，索引与数据不一致");
                throw new BizException(ErrorCode.INTERNAL_ERROR, "账号状态异常，请稍后重试");
            }
            requireId(concurrent);
            return new Provisioned(concurrent, false);
        }
    }

    private static void requireId(UserAccountEntity account) {
        if (account.getId() == null) {
            log.error("user_account 行缺少主键，数据异常");
            throw new BizException(ErrorCode.INTERNAL_ERROR, "账号数据异常，请联系维护者");
        }
    }
}
