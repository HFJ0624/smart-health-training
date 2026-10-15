package com.hfj.fitagent.modules.modelaccess;

import com.hfj.fitagent.modules.modelaccess.crypto.KeyProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 模型接入配置：两把<b>彼此独立</b>的密钥 + 余额外呼参数。
 *
 * <p>密钥一律来自环境变量（{@code FITAGENT_MASTER_KEY} / {@code FITAGENT_KEY_FINGERPRINT_KEY}），
 * 不入库、不进 Git、不打日志（docs/agent-v1-design.md §6.1）。
 * 加密与指纹刻意使用两把不同的密钥，且没有从一把推导另一把的路径——
 * "从一个秘密推两个用途"会把指纹变成密文的旁路信息（同一个问题：两处都用它就可能互相泄漏）。
 * 因此两把密钥相同视为配置错误，启动即失败。
 *
 * <p>密钥缺失时不在构造期抛异常（沿用 {@code WechatProperties.configured()} 的做法）：
 * 未配置是运维状态，不该让整个应用在还没有任何模型调用需求的启动阶段崩掉；
 * 真正需要它的调用会拿到 {@code MODEL_NOT_CONFIGURED}。
 * 但"配了却配错"（不是 32 字节 / 不是合法 base64）是必须立刻暴露的错误，构造期直接失败。
 *
 * @param masterKey      AES-256-GCM 主密钥，base64 的 32 字节
 * @param keyVersion     主密钥版本号，写入 {@code model_credential.encryption_key_version}
 * @param fingerprintKey HMAC-SHA256 指纹密钥，与 masterKey 独立
 * @param balance        余额外呼的路径与超时
 */
@ConfigurationProperties(prefix = "fitagent.modelaccess")
public record ModelAccessProperties(String masterKey, String keyVersion, String fingerprintKey, Balance balance) {

    public static final String DEFAULT_KEY_VERSION = "v1";
    /** 协议核查给出的余额接口路径：docs/agent-v1-protocol.md §2（Base URL 不带 /v1）与 §8。 */
    public static final String DEFAULT_BALANCE_PATH = "/user/balance";

    /**
     * @param path        完整路径，与 model_provider.base_url 拼接；不接受任何请求参数参与拼接
     * @param timeout     单次余额外呼的总超时（连接 + 读取）
     * @param staleAfter  快照超过这个时长即标记为"陈旧"：官方余额是账户级的，可能被本应用之外的调用改变，
     *                    因此"上次问到的值"必须有可读的时效，而不是假装永远新鲜（设计 §6.3、协议 §8）
     */
    public record Balance(String path, Duration timeout, Duration staleAfter) {

        public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
        /** 默认 5 分钟：与小程序页面轮询节奏同量级，超过就明确告诉用户这是旧值。 */
        public static final Duration DEFAULT_STALE_AFTER = Duration.ofMinutes(5);

        public Balance {
            path = (path == null || path.isBlank()) ? DEFAULT_BALANCE_PATH : normalize(path);
            timeout = (timeout == null) ? DEFAULT_TIMEOUT : timeout;
            staleAfter = (staleAfter == null) ? DEFAULT_STALE_AFTER : staleAfter;
            if (staleAfter.isNegative() || staleAfter.isZero()) {
                throw new IllegalStateException("fitagent.modelaccess.balance.stale-after 必须为正");
            }
        }

        /** 只允许"以 / 开头、不含查询串"的路径段：外呼地址必须完全由白名单与配置决定。 */
        private static String normalize(String raw) {
            String trimmed = raw.trim();
            if (!trimmed.startsWith("/")) {
                throw new IllegalStateException("fitagent.modelaccess.balance.path 必须以 / 开头，当前: " + trimmed);
            }
            if (trimmed.contains("?") || trimmed.contains("#") || trimmed.contains("://")) {
                throw new IllegalStateException("fitagent.modelaccess.balance.path 只能是路径，不得含查询串或绝对地址");
            }
            return trimmed;
        }
    }

    public ModelAccessProperties {
        balance = balance == null ? new Balance(null, null, null) : balance;
        if (keyVersion == null || keyVersion.isBlank()) {
            keyVersion = DEFAULT_KEY_VERSION;
        } else {
            keyVersion = keyVersion.trim();
        }
        // 配了就必须是对的：长度/编码错误在这里失败，比在第一次加密时失败更容易定位
        if (hasText(masterKey)) {
            KeyProperties.require(masterKey.trim(), "fitagent.modelaccess.master-key");
        }
        if (hasText(fingerprintKey)) {
            KeyProperties.require(fingerprintKey.trim(), "fitagent.modelaccess.fingerprint-key");
        }
        // 两把密钥必须是两个秘密
        if (hasText(masterKey) && hasText(fingerprintKey) && masterKey.trim().equals(fingerprintKey.trim())) {
            throw new IllegalStateException(
                    "fitagent.modelaccess.master-key 与 fingerprint-key 必须是两个不同的密钥（设计 §6.1）");
        }
    }

    public boolean masterKeyConfigured() {
        return hasText(masterKey);
    }

    public boolean fingerprintKeyConfigured() {
        return hasText(fingerprintKey);
    }

    /** 两把密钥都就位才具备封/解能力。 */
    public boolean cryptoConfigured() {
        return masterKeyConfigured() && fingerprintKeyConfigured();
    }

    /** 只用于日志与启动自检：证明配置存在，绝不输出密钥内容。 */
    public String safeDescribe() {
        return "masterKey=" + (masterKeyConfigured() ? "<已配置 " + KeyProperties.REQUIRED_BYTES + " 字节>" : "<未配置>")
                + ", fingerprintKey=" + (fingerprintKeyConfigured() ? "<已配置 " + KeyProperties.REQUIRED_BYTES + " 字节>" : "<未配置>")
                + ", keyVersion=" + keyVersion
                + ", balancePath=" + balance.path()
                + ", balanceTimeout=" + balance.timeout();
    }

    private static boolean hasText(String v) {
        return v != null && !v.isBlank();
    }
}
