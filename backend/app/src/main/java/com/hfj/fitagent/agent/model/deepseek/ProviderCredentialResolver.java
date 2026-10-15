package com.hfj.fitagent.agent.model.deepseek;

import com.hfj.fitagent.agent.core.CredentialHandle;

/**
 * 凭据解密端口——定义在 agent 侧，让 {@code agent/**} 永远不 import {@code modules.modelaccess}。
 *
 * <p>这是 B10 的落点：明文 Key 只在"发出 HTTP 的那一行"出现，
 * 不进 {@code AgentContext}、不进日志、不进异常文案、不进返回值。
 *
 * <p>实现方（modules/modelaccess）约定：
 * <ul>
 *   <li>按 {@code (credentialId, credentialVersion, providerId)} 定位密文行并 AES-256-GCM 解密；
 *       AAD 必须绑定 {@code userId|providerId|credentialVersion}，跨行搬运密文要解密失败；</li>
 *   <li>凭据不存在、状态不是 ACTIVE、或已撤销时：返回 null 或抛
 *       {@link com.hfj.fitagent.common.error.BizException}（{@code CREDENTIAL_MISSING}）——
 *       适配器把 null 归一化成 {@code CREDENTIAL_MISSING}，不会拿 null 去拼请求头；</li>
 *   <li>{@link #markInvalid} 必须是幂等的短事务，并且<b>不得</b>抛出未检查异常把 401 变成 500。</li>
 * </ul>
 */
public interface ProviderCredentialResolver {

    /**
     * @return 明文 Key；无可用凭据时返回 null
     */
    String decrypt(CredentialHandle handle);

    /**
     * 供应商返回 401 时把凭据转 INVALID（此后不允许启动新调用；已在途调用无法追回，界面如实说明）。
     *
     * <p>默认空实现是为了让"还没有实现凭据模块"的测试装配不至于炸在这里；
     * 生产装配必须覆盖它，否则 401 之后同一把坏 Key 会被反复使用。
     */
    default void markInvalid(CredentialHandle handle) {
        // 由 modules/modelaccess 实现
    }
}
