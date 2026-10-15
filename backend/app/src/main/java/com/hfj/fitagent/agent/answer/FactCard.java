package com.hfj.fitagent.agent.answer;

import java.time.LocalDate;

/**
 * 事实卡片：界面上"可点开看来源"的那一块。
 *
 * <p>除了 {@code label}（展示文案）以外，所有字段都必须来自工具：
 * 值、单位、日期、规则版本、数据修订号都由 {@code AnswerAssembler} 按引用从工具结果里取。
 * 模型没有任何一条路径能写进这里的 {@code value}——它给的一致就只是"被核对过"，不一致就被丢弃并记冲突。
 *
 * @param unit 只在工具结果里显式给出单位字段时填充；<b>不</b>由字段名后缀推断（宁缺毋伪）
 */
public record FactCard(String evidenceId,
                       String label,
                       String value,
                       String unit,
                       String sourceTable,
                       String sourceId,
                       LocalDate asOfDate,
                       String ruleVersion,
                       Long dataRevision) {

    public FactCard {
        if (evidenceId == null || evidenceId.isBlank()) {
            throw new IllegalArgumentException("事实卡片必须有 evidenceId");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("事实卡片必须有工具提供的值：没有值就不出卡片，改列 missingFields");
        }
    }
}
