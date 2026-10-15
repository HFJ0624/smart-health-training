package com.hfj.fitagent.agent.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;

/**
 * 一条来源证据（docs/agent-v1-design.md §9）。
 *
 * <p>{@code evidenceId} 由来源坐标哈希派生，<b>不</b>用自增序号：同一条记录在同一 run 内被多次读取时
 * 得到同一个 ID，Runner 因此可以复用已验证结果而不制造第二个事实；而模型也无法靠猜 ID 引用不存在的东西
 * （猜中一个 16 位十六进制前缀的概率对回答没有意义，且校验仍以本 run 的证据集合为准）。
 *
 * @param sourceTable  来源表名，字面值（如 body_measurement）
 * @param sourceId     来源行主键的字符串形式；BigInt 对外一律字符串（AGENTS.md）
 * @param planRevision 计划 revision（不涉及计划时为 null，不填 0）
 * @param businessDate 该证据成立的业务日期（服务端按用户时区推导）
 * @param ruleVersion  参与计算的规则版本；本轮没有 calculation_rule，因此多为 null
 * @param dataRevision 读取时的 user_account.data_revision
 */
public record Evidence(String sourceTable,
                       String sourceId,
                       Integer planRevision,
                       LocalDate businessDate,
                       String ruleVersion,
                       Long dataRevision) {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public Evidence {
        if (sourceTable == null || sourceTable.isBlank()) {
            throw new IllegalArgumentException("证据必须有 sourceTable");
        }
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("证据必须有 sourceId");
        }
        if (businessDate == null) {
            throw new IllegalArgumentException("证据必须有业务日期：没有来源日期的事实不得展示");
        }
        sourceTable = sourceTable.trim();
        sourceId = sourceId.trim();
        ruleVersion = ruleVersion == null || ruleVersion.isBlank() ? null : ruleVersion.trim();
    }

    /**
     * 稳定 ID：SHA-256(坐标拼接) 的前 16 个十六进制字符，带 {@code ev-} 前缀便于界面识别。
     *
     * <p>注意这不是防伪造签名——它只需要"同一来源同 ID、不同来源几乎不同 ID"。
     * 真实性由 EvidenceRegistry 的集合成员校验保证，不由 ID 本身保证。
     */
    public String evidenceId() {
        String canonical = String.join("|",
                sourceTable,
                sourceId,
                planRevision == null ? "-" : planRevision.toString(),
                businessDate.toString(),
                ruleVersion == null ? "-" : ruleVersion,
                dataRevision == null ? "-" : dataRevision.toString());
        return "ev-" + hexPrefix(canonical, 16);
    }

    /** 给模型看的短描述：只有坐标，没有个人数值。 */
    public String describe() {
        return sourceTable + "#" + sourceId + "@" + businessDate
                + (ruleVersion == null ? "" : "/" + ruleVersion);
    }

    private static String hexPrefix(String value, int chars) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // JVM 必然支持 SHA-256；真出现就说明运行时被裁剪过，宁可失败也不要退化成一个可预测的 ID
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        StringBuilder sb = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) {
            int b = digest[i / 2] >> ((i % 2 == 0) ? 4 : 0) & 0x0F;
            sb.append(HEX[b]);
        }
        return sb.toString();
    }
}
