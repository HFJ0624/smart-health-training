package com.hfj.fitagent.modules.modelaccess.vo;

import java.time.Instant;

/**
 * 凭据的读取形状（{@code GET /api/model-credentials/{providerCode}}）。
 *
 * <p>这个 record 里没有密文、没有 nonce、没有指纹、没有明文——<b>类型层面就没有这些字段</b>，
 * 所以"忘记脱敏"这类事故在本接口不可能发生（设计 §6.1：读取接口只返回 id/状态/脱敏尾部/校验时间）。
 *
 * <p>{@code credentialVersion} 是给界面的："这把 Key 被我替换过几次"，
 * 也是 run 绑定的那个版本，不含任何秘密。
 *
 * @param id                字符串化的 BIGINT（AGENTS.md：BigInt 在 JSON 里是字符串）
 * @param providerId        字符串化的供应商 id
 * @param providerCode      供应商码
 * @param label             用户备注名，可为 null
 * @param keySuffix         脱敏尾部（≤8 字符，且永不等于整把 Key）
 * @param status            ACTIVE / INVALID / REVOKED
 * @param credentialVersion 凭据版本号
 * @param encryptionKeyVersion 主密钥版本（轮换留位）
 * @param validatedAt       服务端最后一次写入该凭据的 UTC 时刻；本轮不做在线校验，因此它是落库时间而不是供应商确认时间
 * @param revokedAt         撤销时刻，未撤销为 null
 * @param createdAt         创建时刻
 * @param updatedAt         最后更新时刻
 */
public record ModelCredentialVo(String id,
                                String providerId,
                                String providerCode,
                                String label,
                                String keySuffix,
                                String status,
                                Integer credentialVersion,
                                String encryptionKeyVersion,
                                Instant validatedAt,
                                Instant revokedAt,
                                Instant createdAt,
                                Instant updatedAt) {

    private static String str(Long value) {
        return value == null ? null : Long.toString(value);
    }

    /** 由实体装配；实体不出模块，转换只发生在这里。 */
    public static ModelCredentialVo of(long providerId, String providerCode,
                                       com.hfj.fitagent.modules.modelaccess.entity.ModelCredentialEntity entity) {
        if (entity == null) {
            return null;
        }
        return new ModelCredentialVo(str(entity.getId()), str(providerId), providerCode,
                entity.getLabel(), entity.getKeySuffix(), entity.getStatus(),
                entity.getCredentialVersion(), entity.getEncryptionKeyVersion(),
                entity.getValidatedAt(), entity.getRevokedAt(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    @Override
    public String toString() {
        // 刻意不含 keySuffix 之外的任何可能被视为密钥片段的内容
        return "ModelCredentialVo{provider=" + providerCode + ", status=" + status
                + ", version=" + credentialVersion + "}";
    }
}
