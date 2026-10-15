package com.hfj.fitagent.modules.modelaccess.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hfj.fitagent.common.AppClock;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.modelaccess.ModelAccessProperties;
import com.hfj.fitagent.modules.modelaccess.Vocabulary;
import com.hfj.fitagent.modules.modelaccess.ValidationErrors;
import com.hfj.fitagent.modules.modelaccess.api.CredentialHandle;
import com.hfj.fitagent.modules.modelaccess.api.ProviderRef;
import com.hfj.fitagent.modules.modelaccess.crypto.CredentialAad;
import com.hfj.fitagent.modules.modelaccess.crypto.CredentialCipher;
import com.hfj.fitagent.modules.modelaccess.crypto.CredentialFingerprint;
import com.hfj.fitagent.modules.modelaccess.crypto.KeyProperties;
import com.hfj.fitagent.modules.modelaccess.crypto.KeySuffix;
import com.hfj.fitagent.modules.modelaccess.crypto.SealedCredential;
import com.hfj.fitagent.modules.modelaccess.entity.ModelCredentialEntity;
import com.hfj.fitagent.modules.modelaccess.mapper.ModelCredentialMapper;
import com.hfj.fitagent.modules.modelaccess.vo.ModelCredentialVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/**
 * 用户自带 Key（BYOK）的保存、替换、撤销与取用（设计 §6.1）。
 *
 * <p>四条不变式都由本类落实：
 * <ol>
 *   <li><b>替换发生在同一行</b>：{@code credential_version + 1}，因此"每用户每供应商一把在用凭据"
 *       是数据库事实（UNIQUE(user_id, provider_id)），不是应用层自觉；</li>
 *   <li><b>同一把 Key 重复保存不推版本</b>：靠独立密钥的 HMAC 指纹判等，
 *       否则用户每次点保存都会让绑定了版本的 run 失效；</li>
 *   <li><b>撤销是真的撤销</b>：状态转 REVOKED 的同时把密文与 nonce 写成 NULL——
 *       "应用记得不用它"不叫撤销；</li>
 *   <li><b>明文只有一个出口</b>：{@link #decryptForCall(CredentialHandle)}，
 *       且只允许供应商外呼路径调用（余额接口与未来的 DeepSeek 适配器）。
 *       读取接口拿到的是 {@link ModelCredentialVo}，类型里没有材料字段。</li>
 * </ol>
 *
 * <p>身份只来自 {@code AuthenticatedUsers}，userId 由 Controller 传入，永不出现在请求参数里。
 */
@Service
public class ModelCredentialService {

    /** 明文 Key 的长度上限：只做长度/空白校验，不判断格式对不对（AGENTS.md：不引入未经说明的规则）。 */
    public static final int MAX_KEY_CHARS = 200;
    public static final int MAX_LABEL_CHARS = 80;

    private static final Logger log = LoggerFactory.getLogger(ModelCredentialService.class);

    private final ModelCredentialMapper credentialMapper;
    private final ProviderCatalogService catalogService;
    private final CredentialCipher cipher;
    private final CredentialFingerprint fingerprint;
    private final ModelAccessProperties properties;
    private final AppClock clock;

    public ModelCredentialService(ModelCredentialMapper credentialMapper,
                                  ProviderCatalogService catalogService,
                                  CredentialCipher cipher,
                                  CredentialFingerprint fingerprint,
                                  ModelAccessProperties properties,
                                  AppClock clock) {
        this.credentialMapper = credentialMapper;
        this.catalogService = catalogService;
        this.cipher = cipher;
        this.fingerprint = fingerprint;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 保存或替换。同指纹 → 原样返回（版本不变，备注可更新）；指纹不同 → 同一行换新封套、版本 +1。
     */
    @Transactional
    public ModelCredentialVo saveOrReplace(long userId, String providerCode, String apiKey, String label) {
        ProviderRef provider = catalogService.requireProvider(providerCode);
        char[] rawKey = requireRawKey(apiKey);
        String normalizedLabel = normalizeLabel(label);
        try {
            ModelCredentialEntity existing = find(userId, provider.providerId());
            if (existing == null) {
                return insertFirst(userId, provider, rawKey, normalizedLabel);
            }
            boolean sameKey = fingerprint.matches(existing.getKeyFingerprint(), rawKey);
            if (sameKey) {
                // 同一把 Key：绝不推版本号，否则绑版本的 run 会因用户多点一次保存而失效
                return updateLabelOnly(existing, normalizedLabel, provider);
            }
            return replaceInPlace(existing, provider, rawKey, normalizedLabel);
        } finally {
            KeyProperties.wipe(rawKey);
        }
    }

    /** 撤销：状态 + 清空密文与 nonce。此后 {@link #requireUsable} 一律拒绝。 */
    @Transactional
    public ModelCredentialVo revoke(long userId, String providerCode) {
        ProviderRef provider = catalogService.requireProvider(providerCode);
        ModelCredentialEntity entity = requireExisting(userId, provider.providerId());
        Instant now = clock.now();
        entity.setStatus(Vocabulary.CREDENTIAL_REVOKED);
        // 两个 ALWAYS 字段真的写成 NULL：ACTIVE 行的 CHECK 要求"状态可用必有材料"，清空后状态必须不是 ACTIVE
        entity.setCiphertext(null);
        entity.setNonce(null);
        entity.setRevokedAt(now);
        if (credentialMapper.updateById(entity) == 0) {
            throw BizException.versionConflict();
        }
        log.info("凭据已撤销 userId={} providerCode={} credentialId={} version={}",
                userId, provider.providerCode(), entity.getId(), entity.getCredentialVersion());
        return ModelCredentialVo.of(provider.providerId(), provider.providerCode(), entity);
    }

    /** 脱敏读取；没有配置过即返回 null（页面显示"尚未配置"，不是 404）。 */
    @Transactional(readOnly = true)
    public ModelCredentialVo readMasked(long userId, String providerCode) {
        ProviderRef provider = catalogService.requireProvider(providerCode);
        ModelCredentialEntity entity = find(userId, provider.providerId());
        return ModelCredentialVo.of(provider.providerId(), provider.providerCode(), entity);
    }

    /**
     * 取用前的可用性判定：缺失 {@code CREDENTIAL_MISSING}，REVOKED/INVALID {@code CREDENTIAL_INVALID}。
     *
     * <p>返回句柄而不是密文：调用点先拿到"能用"的结论，真正解密推迟到外呼那一刻。
     */
    @Transactional(readOnly = true)
    public CredentialHandle requireUsable(long userId, String providerCode) {
        ProviderRef provider = catalogService.requireProvider(providerCode);
        ModelCredentialEntity entity = find(userId, provider.providerId());
        if (entity == null) {
            throw new BizException(ErrorCode.CREDENTIAL_MISSING, "尚未配置模型 API Key");
        }
        return toUsableHandle(entity, provider);
    }

    /** 与 {@code requireUsable(userId, providerCode)} 同样的可用性判定，供应商已由 id 给出（预算上下文路径用）。 */
    @Transactional(readOnly = true)
    public CredentialHandle requireUsable(long userId, long providerId) {
        ModelCredentialEntity entity = find(userId, providerId);
        if (entity == null) {
            throw new BizException(ErrorCode.CREDENTIAL_MISSING, "尚未配置模型 API Key");
        }
        return toUsableHandle(entity, catalogService.requireProviderById(providerId));
    }

    /**
     * 唯一产出明文的入口：<b>只允许供应商外呼路径调用</b>（余额网关、DeepSeek 适配器）。
     *
     * <p>解密前重读该行并核对版本：run 持有旧句柄而用户已替换 Key 时，这里抛
     * {@code CREDENTIAL_INVALID} 而不是悄悄用新 Key 继续跑（设计 §6.1）。
     * 即便绕过了版本核对，AAD 里的 credentialVersion 也已封进标签，搬运密文解不开。
     *
     * @return 明文 Key 字符；调用方用完必须 {@link KeyProperties#wipe(char[])}
     */
    public char[] decryptForCall(CredentialHandle handle) {
        ModelCredentialEntity entity = credentialMapper.selectOne(new QueryWrapper<ModelCredentialEntity>()
                .eq("id", handle.credentialId())
                .eq("user_id", handle.userId()));
        if (entity == null) {
            throw new BizException(ErrorCode.CREDENTIAL_MISSING, "凭据不存在，请重新配置");
        }
        if (!Vocabulary.CREDENTIAL_ACTIVE.equals(entity.getStatus())) {
            throw new BizException(ErrorCode.CREDENTIAL_INVALID, "模型 API Key 已被撤销或判定无效");
        }
        if (!Objects.equals(entity.getCredentialVersion(), handle.credentialVersion())) {
            throw new BizException(ErrorCode.CREDENTIAL_INVALID, "模型 API Key 已被替换，本次调用未使用新 Key");
        }
        CredentialAad aad = new CredentialAad(handle.userId(), handle.providerId(), handle.credentialVersion());
        return cipher.decrypt(entity.getCiphertext(), entity.getNonce(), aad);
    }

    /** 供测试与自检：只回答"有没有配置过"，不返回任何材料。 */
    @Transactional(readOnly = true)
    public boolean exists(long userId, String providerCode) {
        ProviderRef provider = catalogService.requireProvider(providerCode);
        return find(userId, provider.providerId()) != null;
    }

    private ModelCredentialVo insertFirst(long userId, ProviderRef provider, char[] rawKey, String label) {
        SealedCredential sealed = seal(userId, provider.providerId(), 1, rawKey);
        ModelCredentialEntity entity = new ModelCredentialEntity();
        entity.setUserId(userId);
        entity.setProviderId(provider.providerId());
        entity.setLabel(label);
        entity.setKeySuffix(KeySuffix.of(rawKey));
        entity.setKeyFingerprint(fingerprint.of(rawKey));
        entity.setCiphertext(sealed.ciphertext());
        entity.setNonce(sealed.nonce());
        entity.setEncryptionKeyVersion(properties.keyVersion());
        entity.setCredentialVersion(1);
        entity.setStatus(Vocabulary.CREDENTIAL_ACTIVE);
        entity.setValidatedAt(clock.now());
        // revokedAt 不设：非 REVOKED 时恒为 NULL（ck_model_credential_2）
        try {
            credentialMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 同一 (user_id, provider_id) 并发首插：让客户端重试，重试会走"替换"分支
            throw new BizException(ErrorCode.VERSION_CONFLICT, "并发配置同一供应商的 Key，请重试");
        }
        log.info("凭据已保存 userId={} providerCode={} credentialId={} version=1",
                userId, provider.providerCode(), entity.getId());
        return ModelCredentialVo.of(provider.providerId(), provider.providerCode(), entity);
    }

    private ModelCredentialVo replaceInPlace(ModelCredentialEntity existing, ProviderRef provider,
                                             char[] rawKey, String label) {
        int nextVersion = existing.getCredentialVersion() + 1;
        SealedCredential sealed = seal(existing.getUserId(), provider.providerId(), nextVersion, rawKey);
        // 新 nonce + 新密文 + 新版本；不删旧行，历史账本里的版本号仍可追溯
        existing.setCiphertext(sealed.ciphertext());
        existing.setNonce(sealed.nonce());
        existing.setKeyFingerprint(fingerprint.of(rawKey));
        existing.setKeySuffix(KeySuffix.of(rawKey));
        existing.setCredentialVersion(nextVersion);
        existing.setEncryptionKeyVersion(properties.keyVersion());
        existing.setStatus(Vocabulary.CREDENTIAL_ACTIVE);
        existing.setRevokedAt(null);
        existing.setLabel(label);
        existing.setValidatedAt(clock.now());
        if (credentialMapper.updateById(existing) == 0) {
            throw BizException.versionConflict();
        }
        log.info("凭据已替换 userId={} providerCode={} credentialId={} version={}",
                existing.getUserId(), provider.providerCode(), existing.getId(), nextVersion);
        return ModelCredentialVo.of(provider.providerId(), provider.providerCode(), existing);
    }

    private ModelCredentialVo updateLabelOnly(ModelCredentialEntity existing, String label, ProviderRef provider) {
        if (Objects.equals(existing.getLabel(), label)) {
            return ModelCredentialVo.of(provider.providerId(), provider.providerCode(), existing);
        }
        // 同一把 Key 只改备注：密文、nonce、版本都不动（版本一动，绑定旧版本的 run 就废了）
        existing.setLabel(label);
        if (credentialMapper.updateById(existing) == 0) {
            throw BizException.versionConflict();
        }
        return ModelCredentialVo.of(provider.providerId(), provider.providerCode(), existing);
    }

    private SealedCredential seal(long userId, long providerId, int credentialVersion, char[] rawKey) {
        return cipher.encrypt(rawKey, new CredentialAad(userId, providerId, credentialVersion));
    }

    private CredentialHandle toUsableHandle(ModelCredentialEntity entity, ProviderRef provider) {
        String status = entity.getStatus();
        if (Vocabulary.CREDENTIAL_REVOKED.equals(status) || Vocabulary.CREDENTIAL_INVALID.equals(status)) {
            throw new BizException(ErrorCode.CREDENTIAL_INVALID,
                    Vocabulary.CREDENTIAL_REVOKED.equals(status) ? "模型 API Key 已被撤销" : "模型 API Key 被供应商拒绝，请重新配置");
        }
        // 状态自称 ACTIVE 却没有可解密的材料：数据库 CHECK 已禁止，应用侧同样不配合
        if (entity.getCiphertext() == null || entity.getNonce() == null) {
            throw new BizException(ErrorCode.CREDENTIAL_INVALID, "凭据材料缺失，请重新配置");
        }
        return new CredentialHandle(entity.getUserId(), entity.getId(), entity.getCredentialVersion(),
                provider.providerId());
    }

    private ModelCredentialEntity find(long userId, long providerId) {
        return credentialMapper.selectOne(new QueryWrapper<ModelCredentialEntity>()
                .eq("user_id", userId)
                .eq("provider_id", providerId));
    }

    private ModelCredentialEntity requireExisting(long userId, long providerId) {
        ModelCredentialEntity entity = find(userId, providerId);
        if (entity == null) {
            throw new BizException(ErrorCode.CREDENTIAL_MISSING, "尚未配置模型 API Key");
        }
        return entity;
    }

    /** 只做长度与空白校验：不校验前缀、长度指纹、字符集——那些都是未经核实的供应商约定。 */
    private static char[] requireRawKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw ValidationErrors.field("apiKey", "REQUIRED", "API Key 不能为空", null);
        }
        String trimmed = apiKey.trim();
        if (trimmed.length() > MAX_KEY_CHARS) {
            throw ValidationErrors.field("apiKey", "TOO_LONG", "API Key 长度不得超过 " + MAX_KEY_CHARS, null);
        }
        if (trimmed.chars().anyMatch(Character::isWhitespace) || trimmed.chars().anyMatch(Character::isISOControl)) {
            // 不把原值放进 rejected：那是用户的密钥
            throw ValidationErrors.field("apiKey", "INVALID_CHARACTERS", "API Key 不能含空白或控制字符", null);
        }
        return trimmed.toCharArray();
    }

    private static String normalizeLabel(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String trimmed = label.trim();
        if (trimmed.length() > MAX_LABEL_CHARS) {
            throw ValidationErrors.field("label", "TOO_LONG", "备注名长度不得超过 " + MAX_LABEL_CHARS, null);
        }
        return trimmed;
    }
}
