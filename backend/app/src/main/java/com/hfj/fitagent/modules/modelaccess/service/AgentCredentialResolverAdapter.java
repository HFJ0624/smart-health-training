package com.hfj.fitagent.modules.modelaccess.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hfj.fitagent.agent.model.deepseek.ProviderCredentialResolver;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.modelaccess.Vocabulary;
import com.hfj.fitagent.modules.modelaccess.api.ProviderRef;
import com.hfj.fitagent.modules.modelaccess.crypto.KeyProperties;
import com.hfj.fitagent.modules.modelaccess.entity.ModelCredentialEntity;
import com.hfj.fitagent.modules.modelaccess.mapper.ModelCredentialMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * {@code ProviderCredentialResolver} 的生产实现：让 {@code agent/**} 只看到"取明文"和"标失效"两个动作，
 * 永远不 import modelaccess（docs/agent-v1-design.md §2 依赖方向）。
 *
 * <p>句柄里的 {@code providerId} 是供应商码而数据库存的是数字 id，因此这里以 credentialId 读回行、
 * 再用行里的 {@code user_id}/{@code provider_id} 组装解密上下文；核对不过就直接判无效。
 * AAD 本身绑定了 {@code userId|providerId|credentialVersion}（§6.1），跨行搬密文在这一步就会解密失败。
 */
@Component
public class AgentCredentialResolverAdapter implements ProviderCredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentCredentialResolverAdapter.class);

    private final ModelCredentialMapper credentialMapper;
    private final ModelCredentialService credentialService;
    private final ProviderCatalogService catalogService;

    public AgentCredentialResolverAdapter(ModelCredentialMapper credentialMapper,
                                          ModelCredentialService credentialService,
                                          ProviderCatalogService catalogService) {
        this.credentialMapper = credentialMapper;
        this.credentialService = credentialService;
        this.catalogService = catalogService;
    }

    @Override
    @Transactional(readOnly = true)
    public String decrypt(com.hfj.fitagent.agent.core.CredentialHandle handle) {
        if (handle == null) {
            return null;
        }
        ModelCredentialEntity row = credentialMapper.selectOne(new QueryWrapper<ModelCredentialEntity>()
                .eq("id", handle.credentialId()));
        if (row == null) {
            log.warn("凭据行不存在，按无可用凭据处理 credentialId={}", handle.credentialId());
            return null;
        }
        if (!Vocabulary.CREDENTIAL_ACTIVE.equals(row.getStatus())) {
            throw new BizException(ErrorCode.CREDENTIAL_INVALID, "模型 API Key 已撤销或判定无效");
        }
        if (!Objects.equals(row.getCredentialVersion(), handle.credentialVersion())) {
            throw new BizException(ErrorCode.CREDENTIAL_INVALID, "模型 API Key 已被替换，本次调用未使用新 Key");
        }
        ProviderRef provider = catalogService.requireProviderById(row.getProviderId());
        if (!provider.providerCode().equalsIgnoreCase(handle.providerId())) {
            throw new BizException(ErrorCode.CREDENTIAL_INVALID, "凭据与运行绑定的供应商不一致");
        }
        char[] raw = credentialService.decryptForCall(new com.hfj.fitagent.modules.modelaccess.api.CredentialHandle(
                row.getUserId(), row.getId(), row.getCredentialVersion(), row.getProviderId()));
        try {
            // 明文只活在这一行到"拼请求头"之间；String 无法主动擦除，所以不返回 char[] 的包装副本
            return new String(raw);
        } finally {
            KeyProperties.wipe(raw);
        }
    }

    /**
     * 401 之后把这一把 Key 判为 INVALID：此后 {@code requireUsable} 拒绝新调用，
     * 已在途的调用追不回来，界面按"已发生费用"如实显示（§6.1）。
     *
     * <p>条件更新（只动 ACTIVE 行）本身就是幂等的；异常一律吞掉——把 401 变成 500 会让用户
     * 以为"是我们的错"，而真正需要知道的是"Key 无效"。
     */
    @Override
    public void markInvalid(com.hfj.fitagent.agent.core.CredentialHandle handle) {
        if (handle == null) {
            return;
        }
        try {
            ModelCredentialEntity patch = new ModelCredentialEntity();
            patch.setStatus(Vocabulary.CREDENTIAL_INVALID);
            int updated = credentialMapper.update(patch, new QueryWrapper<ModelCredentialEntity>()
                    .eq("id", handle.credentialId())
                    .eq("credential_version", handle.credentialVersion())
                    .eq("status", Vocabulary.CREDENTIAL_ACTIVE));
            if (updated == 0) {
                log.info("凭据未标记为无效（已非 ACTIVE 或已被替换）credentialId={}", handle.credentialId());
            }
        } catch (RuntimeException e) {
            log.warn("凭据失效标记失败，忽略 type={} credentialId={}",
                    e.getClass().getSimpleName(), handle.credentialId());
        }
    }
}
