package com.hfj.fitagent.agent.answer;

import com.hfj.fitagent.agent.trace.EvidenceRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 引用校验：只证明<b>存在</b>与<b>归属合法</b>，不证明语义正确。
 *
 * <p>这条边界必须说清楚（docs/agent-v1-design.md §9）：通过校验只说明"这个 evidenceId 确实是本 run
 * 的某个真实工具读取产生的"，<b>不</b>代表模型围绕它写的那段话是对的。因此本项目不宣称实现了完整防幻觉；
 * 真正的防线是"个人数值只能由引用从工具结果里取"，让模型的自由数字没有任何路径进入事实卡片。
 *
 * <p>归属合法的根据：证据只能由带 {@code AgentContext}（userId 来自认证上下文）的工具产生并登记到
 * <b>本 run</b> 的注册表，所以"在这个集合里"等价于"属于这个用户、这个 run、这一次数据修订"。
 * 跨 run 的 ID 猜中也进不来——注册表是 per-run 的。
 */
@Component
public class CitationValidator {

    /**
     * @param rejected 不在本 run 证据集合里的引用（按出现顺序，已去重）；这些直接丢弃并计入 missingFields
     */
    public record ValidationResult(List<Citation> accepted, List<String> rejected, int duplicates) {

        public boolean hasRejections() {
            return !rejected.isEmpty() || duplicates > 0;
        }
    }

    public ValidationResult validate(List<Citation> citations, EvidenceRegistry registry) {
        List<Citation> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int duplicates = 0;
        if (citations != null) {
            for (Citation citation : citations) {
                if (citation == null || citation.evidenceId() == null || citation.evidenceId().isBlank()) {
                    continue;
                }
                if (!registry.contains(citation.evidenceId())) {
                    if (!rejected.contains(citation.evidenceId())) {
                        rejected.add(citation.evidenceId());
                    }
                    continue;
                }
                // 同一条来源支撑不同字段是合法的（"最新体重"和"目标体重"都来自档案）；
                // 只有 (来源, 字段) 完全重复才算重复引用
                if (!seen.add(citation.evidenceId() + "|" + (citation.field() == null ? "*" : citation.field()))) {
                    duplicates++;
                    continue;
                }
                accepted.add(citation);
            }
        }
        return new ValidationResult(List.copyOf(accepted), List.copyOf(rejected), duplicates);
    }
}
