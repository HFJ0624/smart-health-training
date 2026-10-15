package com.hfj.fitagent.modules.auth.service;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.util.Ids;
import com.hfj.fitagent.common.web.FieldError;
import com.hfj.fitagent.modules.auth.DevLoginProperties;
import com.hfj.fitagent.modules.auth.vo.LoginResultVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 开发登录：在没有微信凭据时把前后端跑通，绝不用于生产（docs/p1-api.md §3.2）。
 *
 * 双开关：`@Profile("dev")` 与 `fitagent.auth.dev-login.enabled=true` 必须同时成立，Bean 才存在；
 * prod 下控制器不注册，且启动期加固校验探测到该路由即启动失败。
 *
 * 关键安全性质：入参只能是配置里枚举出来的 devUserId。openid 与 appId 由服务端映射生成，
 * 客户端没有任何途径指定身份标识——这与真实登录的口径一致，而不是"开发环境可以乱来"。
 */
@Service
@Profile("dev")
@ConditionalOnProperty(prefix = "fitagent.auth.dev-login", name = "enabled", havingValue = "true")
public class DevLoginService {

    private static final Logger log = LoggerFactory.getLogger(DevLoginService.class);
    private static final String OPEN_ID_PREFIX = "dev-openid-";

    private final DevLoginProperties properties;
    private final WechatLoginService wechatLoginService;

    DevLoginService(DevLoginProperties properties, WechatLoginService wechatLoginService) {
        this.properties = properties;
        this.wechatLoginService = wechatLoginService;
    }

    public LoginResultVo login(String devUserId) {
        String requested = Ids.exactExternalId(devUserId);
        if (requested == null || !properties.testUserIds().contains(requested)) {
            log.warn("开发登录请求了未配置的测试用户");
            throw new BizException(ErrorCode.VALIDATION_FAILED, "该开发登录用户未被配置",
                    List.of(FieldError.of("devUserId", "UNKNOWN_DEV_USER",
                            "只能使用配置内的测试用户：" + String.join("、", properties.testUserIds()), devUserId)));
        }
        log.info("开发登录 userId={}（结果不代表真实微信联调）", requested);
        return wechatLoginService.openSession(properties.appId(), syntheticOpenId(requested), null, true);
    }

    /** 稳定映射：同一个 devUserId 在任何一次启动、任何一台机器上都落回同一个 openid，因此重复登录复用同一账号。 */
    static String syntheticOpenId(String devUserId) {
        return OPEN_ID_PREFIX + devUserId;
    }
}
