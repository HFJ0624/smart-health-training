package com.hfj.fitagent.modules.auth.service;

import com.hfj.fitagent.common.AppClock;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.util.Ids;
import com.hfj.fitagent.modules.auth.WechatProperties;
import com.hfj.fitagent.modules.auth.entity.UserAccountEntity;
import com.hfj.fitagent.modules.auth.mapper.UserAccountMapper;
import com.hfj.fitagent.modules.auth.vo.LoginResultVo;
import com.hfj.fitagent.security.SessionTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * 登录编排：换身份 → 找/建账号 → 发会话令牌 → 刷新最近登录时间。
 *
 * 事务边界是刻意设计的（docs/p1-database.md §6、docs/p1-architecture.md §5）：
 * 本类<b>不</b>标注 @Transactional。远程 HTTP 与"发令牌"都不该占用数据库连接/事务，
 * 唯一的写事务是注册那一步的 REQUIRES_NEW 短事务；外层再包一层事务，反而会在撞唯一索引后
 * 留下 rollback-only 状态导致重查失败。
 */
@Service
public class WechatLoginService {

    private static final Logger log = LoggerFactory.getLogger(WechatLoginService.class);

    private final WechatCode2SessionClient code2SessionClient;
    private final WechatProperties wechatProperties;
    private final AccountProvisioningService provisioning;
    private final SessionTokenService sessionTokens;
    private final UserAccountMapper userAccounts;
    private final AppClock clock;

    public WechatLoginService(WechatCode2SessionClient code2SessionClient,
                              WechatProperties wechatProperties,
                              AccountProvisioningService provisioning,
                              SessionTokenService sessionTokens,
                              UserAccountMapper userAccounts,
                              AppClock clock) {
        this.code2SessionClient = code2SessionClient;
        this.wechatProperties = wechatProperties;
        this.provisioning = provisioning;
        this.sessionTokens = sessionTokens;
        this.userAccounts = userAccounts;
        this.clock = clock;
    }

    /**
     * 真实微信登录。请求体只有 code；appId 来自后端配置，身份完全由服务端向微信核实的结果决定。
     */
    public LoginResultVo login(String code) {
        WechatCode2SessionClient.WechatIdentity identity = code2SessionClient.resolve(code);
        return openSession(wechatProperties.appId(), identity.openId(), identity.unionId(), false);
    }

    /**
     * 微信登录与开发登录共用的会话开通路径：同一套建号规则、同一套令牌机制、同一套响应形状。
     *
     * @param devLogin 仅影响会话标记与响应的 devMode，不改变任何身份判定逻辑
     */
    public LoginResultVo openSession(String appId, String openId, String unionId, boolean devLogin) {
        AccountProvisioningService.Provisioned provisioned = provisioning.provision(appId, openId, unionId);
        UserAccountEntity account = provisioned.account();

        if (!AccountRegistrar.STATUS_ACTIVE.equals(account.getStatus())) {
            // 登录是状态检查点：非 ACTIVE 不发令牌（docs/p1-database.md §3.1）
            log.warn("账户状态非 ACTIVE，拒绝登录 userId={} status={}", account.getId(), account.getStatus());
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }

        SessionTokenService.Issued issued = sessionTokens.issue(account.getId(), resolveZone(account), devLogin);
        userAccounts.touchLastLoginAt(account.getId(), clock.now());

        return LoginResultVo.of(Long.toString(account.getId()), issued.token(), issued.expiresAt(),
                account.getNickname(), provisioned.newAccount(), devLogin);
    }

    /**
     * 会话需要业务时区来推导业务日期。存量行若写了非法时区，按默认时区兜底而不是让整个登录失败——
     * 档案接口会拒绝写入非法时区，读取时的兜底只影响日期归属，不伪造任何身份或权限。
     */
    private static ZoneId resolveZone(UserAccountEntity account) {
        String timezone = account.getTimezone();
        if (Ids.isBlank(timezone)) {
            return ZoneId.of(AccountRegistrar.DEFAULT_TIMEZONE);
        }
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException e) {
            log.warn("账户时区无法解析，按默认时区开通会话 userId={} timezone={}", account.getId(), timezone);
            return ZoneId.of(AccountRegistrar.DEFAULT_TIMEZONE);
        }
    }
}
