package com.hfj.fitagent.agent.answer;

/**
 * 模型给出的引用。
 *
 * <p>关键设计：模型<b>只</b>给引用（哪个来源、哪个字段），不给数值。
 * {@code claimedValue}/{@code claimedUnit} 存在的唯一目的是"核对后丢弃"——
 * 它们与工具提供的内容不一致时，事实卡片仍然取工具值，模型的说法被记为冲突并忽略（design §9）。
 *
 * @param field 工具结果 JSON 里的字段名；为空表示"引用这条来源支撑的整份读数"
 */
public record Citation(String evidenceId,
                       String label,
                       String field,
                       String claimedValue,
                       String claimedUnit) {

    public Citation {
        evidenceId = evidenceId == null ? null : evidenceId.trim();
        label = blankToNull(label);
        field = blankToNull(field);
        claimedValue = blankToNull(claimedValue);
        claimedUnit = blankToNull(claimedUnit);
    }

    public boolean hasClaim() {
        return claimedValue != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public String toString() {
        return "Citation[" + evidenceId + (field == null ? "" : " field=" + field) + "]";
    }
}
