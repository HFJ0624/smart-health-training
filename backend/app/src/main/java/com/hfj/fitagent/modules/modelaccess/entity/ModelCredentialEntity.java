package com.hfj.fitagent.modules.modelaccess.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * model_credential：用户自带的模型 API Key（V13，AES-256-GCM 信封）。
 *
 * <p>本类是安全边界的表达，<b>没有任何明文列</b>：字段集合只有密文、nonce、指纹、尾部标记与版本号。
 * {@code ciphertext}/{@code nonce}/{@code keyFingerprint} 永不出现在响应里（VO 层根本没有这些字段），
 * 也不写日志。
 *
 * <p>可空列的 {@code updateStrategy = ALWAYS} 是有意的：撤销必须真的把密文与 nonce 写成 NULL
 * （"密文都不在了"才是撤销，见 V13 注释）；{@code label}/{@code revokedAt} 同理，
 * 否则用户"清空备注"清不掉、复活后的行还留着旧撤销时间。
 *
 * <p>{@code credentialVersion} 是同一行的替换次数（首次 1），GCM 的 AAD 绑定了它，
 * 跨版本搬运密文会当场解密失败。run 绑定该版本，执行中不悄悄换 Key。
 */
@TableName("model_credential")
@Getter
@Setter
public class ModelCredentialEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户；从登录上下文取得，不接受请求参数。 */
    private Long userId;

    private Long providerId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String label;

    /** 最多 8 字符的 Key 尾部（utf8mb4_bin：只做 trim，绝不折叠大小写）。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String keySuffix;

    /** HMAC-SHA256 原始 32 字节；只用于"是否同一把"，永不返回。 */
    private byte[] keyFingerprint;

    /** AES-256-GCM 密文（含 16 字节标签）；撤销时置 NULL。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private byte[] ciphertext;

    /** 本次加密新取的 12 字节 nonce；撤销时置 NULL。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private byte[] nonce;

    /** 主密钥版本（fitagent.modelaccess.key-version）。 */
    private String encryptionKeyVersion;

    private Integer credentialVersion;

    /** ACTIVE / INVALID / REVOKED。 */
    private String status;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant validatedAt;

    /** 非 REVOKED 时恒为 NULL（列上有 CHECK 保证）。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant revokedAt;

    @Version
    private Integer lockVersion;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
