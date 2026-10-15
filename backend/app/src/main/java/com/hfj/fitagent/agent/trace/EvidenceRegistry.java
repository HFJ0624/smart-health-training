package com.hfj.fitagent.agent.trace;

import com.hfj.fitagent.agent.tool.Evidence;
import com.hfj.fitagent.agent.tool.ToolResult;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 本 run 的证据集合：模型只能引用这里已经存在的 ID（docs/agent-v1-design.md §9）。
 *
 * <p>两个用途合在一起是有意为之：
 * <ul>
 *   <li><b>存在性校验</b>：{@link #contains} / {@link #unknownRefs} 让"引用了不存在的东西"当场暴露；</li>
 *   <li><b>取值来源</b>：{@link Entry#data} 是工具返回的原文快照，事实卡片的值/单位/日期/规则版本
 *       只从这里的 data + evidence 取——模型的自由数字没有任何一条路径能写进事实卡片。</li>
 * </ul>
 *
 * <p>一个 run 一个实例，非线程安全（顺序执行工具）。同一 ID 重复登记保留<b>第一次</b>的快照：
 * 同一 run 内 dataRevision 固定，后写覆盖前写只会让"哪一次读取支撑了结论"变得含糊。
 */
public final class EvidenceRegistry {

    /**
     * @param dataSnapshot 工具返回结果的 JSON 快照；null 表示这次调用没有可用明细（结构化错误）
     */
    public record Entry(String evidenceId,
                        Evidence evidence,
                        String toolName,
                        JsonNode dataSnapshot,
                        boolean dataComplete,
                        Long dataRevision) {

        public boolean hasData() {
            return dataSnapshot != null && !dataSnapshot.isNull() && !dataSnapshot.isMissingNode();
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** 登记一条来源；返回是否新增（重复 ID 返回 false，快照保持首次）。 */
    public boolean register(Evidence evidence, String toolName, JsonNode dataSnapshot,
                            boolean dataComplete, Long dataRevision) {
        String id = evidence.evidenceId();
        return entries.putIfAbsent(id, new Entry(id, evidence, toolName, dataSnapshot, dataComplete, dataRevision)) == null;
    }

    /** 工具结果落地：把 result 里声明的每条来源都登记进本 run 集合。 */
    public int registerAll(ToolResult<?> result, String toolName, JsonNode dataSnapshot) {
        if (result == null) {
            return 0;
        }
        int added = 0;
        for (Evidence evidence : result.evidenceRefs()) {
            if (register(evidence, toolName, dataSnapshot, result.dataComplete(), result.dataRevision())) {
                added++;
            }
        }
        return added;
    }

    public boolean contains(String evidenceId) {
        return evidenceId != null && entries.containsKey(evidenceId.trim());
    }

    public Optional<Entry> find(String evidenceId) {
        if (evidenceId == null || evidenceId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(evidenceId.trim()));
    }

    /** 引用了但不在本 run 集合里的 ID（按输入顺序，便于计入 missingFields）。 */
    public List<String> unknownRefs(Collection<String> refs) {
        List<String> unknown = new ArrayList<>();
        if (refs == null) {
            return unknown;
        }
        for (String ref : refs) {
            if (ref == null || ref.isBlank()) {
                continue;
            }
            if (!contains(ref)) {
                unknown.add(ref.trim());
            }
        }
        return unknown;
    }

    public List<String> ids() {
        return List.copyOf(entries.keySet());
    }

    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public int size() {
        return entries.size();
    }
}
