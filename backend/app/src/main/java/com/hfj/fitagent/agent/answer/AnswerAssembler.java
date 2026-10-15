package com.hfj.fitagent.agent.answer;

import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.core.StopReason;
import com.hfj.fitagent.agent.model.ModelResponse;
import com.hfj.fitagent.agent.trace.EvidenceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回答组装：模型只出<b>引用</b>，数值/单位/日期/规则版本一律按引用从工具结果里取（design §9）。
 *
 * <p>三种丢弃，都记账但不伪装：
 * <ul>
 *   <li>引用了不存在的 ID → 丢弃该引用，计入 {@code missingFields}（UNKNOWN_CITATION:xxx），不报错；</li>
 *   <li>模型给出的数值与工具值<b>不一致</b> → 事实卡片仍取工具值，模型的说法记为冲突并被丢弃；</li>
 *   <li>模型给出的数值没有对应引用 → 根本没有进入事实卡片的通道（{@link FactCard} 只能由证据构造）。</li>
 * </ul>
 *
 * <p>自由文本改写策略：不悄悄重写模型 prose（改了用户就看不到模型真正说了什么），
 * 而是在文本末尾追加一段<b>服务端校正</b>，明确"以下数值以工具核实结果为准"。
 * 结构化事实（factCards）始终是权威口径，界面应把它们作为可核对区块展示。
 */
@Component
public class AnswerAssembler {

    private static final Logger log = LoggerFactory.getLogger(AnswerAssembler.class);

    /** 模板约定的机器可读引用行：{@code CITATIONS: ev-xxx, ev-yyy} 或 {@code CITATIONS: NONE}。 */
    private static final Pattern CITATION_LINE = Pattern.compile("(?im)^\\s*CITATIONS\\s*[:：]\\s*(?<ids>.*)$");
    private static final Pattern BARE_EVIDENCE_TOKEN = Pattern.compile("ev-[0-9a-fA-F]{8,32}");
    private static final int MAX_SUMMARY_CHARS = 160;

    private final CitationValidator citationValidator;
    private final ObjectMapper objectMapper;

    public AnswerAssembler(CitationValidator citationValidator, ObjectMapper objectMapper) {
        this.citationValidator = citationValidator;
        this.objectMapper = objectMapper;
    }

    /**
     * 正常结束路径：finish_reason=stop 的回答。
     *
     * @param stopReason 由 Runner 决定：拿到可用回答时是 SUCCEEDED，收尾调用被截断等仍可能带别的值
     */
    public AgentAnswer validateAndBuild(AgentContext ctx,
                                        ModelResponse response,
                                        EvidenceRegistry registry,
                                        UsageSummary usage,
                                        StopReason stopReason,
                                        boolean costKnown,
                                        boolean costUncertain,
                                        boolean isStale,
                                        boolean contextTruncated) {
        Parsed parsed = parseContent(response == null ? "" : response.content());
        CitationValidator.ValidationResult validation = citationValidator.validate(parsed.citations(), registry);

        List<FactCard> factCards = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        for (Citation citation : validation.accepted()) {
            Optional<EvidenceRegistry.Entry> entry = registry.find(citation.evidenceId());
            if (entry.isEmpty()) {
                continue;
            }
            String value = valueOf(entry.get(), citation.field());
            if (value == null) {
                conflicts.add("NO_VALUE:" + citation.evidenceId()
                        + (citation.field() == null ? "" : ":" + citation.field()));
                continue;
            }
            if (citation.hasClaim() && !sameNumber(citation.claimedValue(), value)) {
                // 模型自己填的数与工具值不一致：丢模型的值，卡片继续用工具值，并把冲突记下来
                conflicts.add("VALUE_CONFLICT:" + citation.evidenceId()
                        + "=" + value + "(model:" + citation.claimedValue() + ")");
                log.debug("引用与模型数值冲突 evidenceId={} evidenceValue={}", citation.evidenceId(), value);
            }
            factCards.add(new FactCard(citation.evidenceId(),
                    labelOf(entry.get(), citation),
                    value,
                    unitOf(entry.get(), citation.field()),
                    entry.get().evidence().sourceTable(),
                    entry.get().evidence().sourceId(),
                    entry.get().evidence().businessDate(),
                    entry.get().evidence().ruleVersion(),
                    entry.get().dataRevision()));
        }

        List<String> missing = new ArrayList<>(parsed.missingFields());
        validation.rejected().forEach(ref -> missing.add("UNKNOWN_CITATION:" + ref));
        missing.addAll(conflicts);
        missing.addAll(incompleteReads(registry));

        String answerText = parsed.answerText();
        if (!conflicts.isEmpty()) {
            answerText = answerText + "\n\n【服务端校正】以下数值以工具核实结果为准，模型正文中的不一致数字已作废："
                    + String.join("；", conflicts.stream().map(AnswerAssembler::humanizeConflict).toList());
        }
        if (!validation.rejected().isEmpty()) {
            answerText = answerText + "\n\n【服务端说明】回答中引用了本轮不存在的来源，相关结论不成立："
                    + String.join("、", validation.rejected());
        }
        if (contextTruncated) {
            answerText = answerText + "\n\n（为控制上下文长度，更早的对话轮次已省略）";
        }

        List<String> evidenceRefs = factCards.stream().map(FactCard::evidenceId).toList();
        return AgentAnswer.of(factCards, evidenceRefs, distinct(missing), ctx.dataRevision(), stopReason, usage,
                costKnown, costUncertain, isStale, contextTruncated, answerText.trim());
    }

    /**
     * 非正常结束路径（预算/时间/次数/供应商失败）：<b>不额外发一次"免费总结"请求</b>，
     * 用确定性模板把已经核实的事实 + 停止原因返回（design §4 第 13 步）。
     */
    public AgentAnswer stoppedAnswer(AgentContext ctx,
                                     EvidenceRegistry registry,
                                     UsageSummary usage,
                                     StopReason stopReason,
                                     boolean costKnown,
                                     boolean costUncertain,
                                     boolean isStale,
                                     boolean contextTruncated,
                                     String reasonDetail) {
        List<FactCard> factCards = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();
        for (EvidenceRegistry.Entry entry : registry.entries()) {
            String value = valueOf(entry, null);
            if (value == null) {
                continue;
            }
            factCards.add(new FactCard(entry.evidenceId(), entry.evidence().describe(), value,
                    unitOf(entry, null), entry.evidence().sourceTable(), entry.evidence().sourceId(),
                    entry.evidence().businessDate(), entry.evidence().ruleVersion(), entry.dataRevision()));
            evidenceRefs.add(entry.evidenceId());
        }

        List<String> missing = new ArrayList<>();
        missing.add("ANSWER_NOT_GENERATED:" + stopReason.name());
        missing.addAll(incompleteReads(registry));

        StringBuilder text = new StringBuilder(512);
        text.append("本轮没有生成自由文本结论（停止原因：").append(stopReason.name()).append("）。");
        if (reasonDetail != null && !reasonDetail.isBlank()) {
            text.append("说明：").append(reasonDetail.trim());
        }
        text.append('\n');
        if (factCards.isEmpty()) {
            text.append("本轮没有已核实的事实可呈现——不编造、不用 0 或估算填补。");
        } else {
            text.append("以下是本轮已经通过工具核实的事实，仍可使用：\n");
            for (FactCard card : factCards) {
                text.append("· ").append(card.label()).append("：").append(card.value());
                if (card.unit() != null) {
                    text.append(' ').append(card.unit());
                }
                text.append("（来源 ").append(card.sourceTable()).append('#').append(card.sourceId())
                        .append("，").append(card.asOfDate()).append("）\n");
            }
        }
        text.append("\n注意：以上是工程闸门/供应商状态导致的停止，不是健康或医学结论。")
                .append(usage.describe());

        return AgentAnswer.of(factCards, evidenceRefs, distinct(missing), ctx.dataRevision(), stopReason, usage,
                costKnown, costUncertain, isStale, contextTruncated, text.toString());
    }

    // ---- 模型输出解析 ----

    private record Parsed(String answerText, List<Citation> citations, List<String> missingFields) {
    }

    /**
     * 两种都接受：JSON 信封（{@code {"answer":…,"citations":[…]}}，含 ```json 围栏）
     * 或自然文本 + {@code CITATIONS:} 行 / 裸 {@code ev-…} 记号。
     *
     * <p>宽容解析是刻意的：本轮不使用 Chat Completions 的 {@code strict}（Beta，需 /beta 前缀），
     * 所以不能假设模型一定给出合法 JSON。解析不出结构时，宁可只拿到自由文本 + 从文本里认出引用，
     * 也不要因为一个逗号把整轮已验证事实丢掉。
     */
    private Parsed parseContent(String content) {
        String raw = content == null ? "" : content.trim();
        if (raw.isEmpty()) {
            return new Parsed("", List.of(), List.of());
        }
        String unfenced = stripCodeFence(raw);
        if (unfenced.startsWith("{")) {
            Optional<Parsed> fromJson = parseJsonEnvelope(unfenced);
            if (fromJson.isPresent()) {
                return fromJson.get();
            }
        }
        return parseProse(raw);
    }

    private String stripCodeFence(String raw) {
        if (!raw.startsWith("```")) {
            return raw;
        }
        int firstBreak = raw.indexOf('\n');
        int lastFence = raw.lastIndexOf("```");
        if (firstBreak < 0 || lastFence <= firstBreak) {
            return raw;
        }
        return raw.substring(firstBreak + 1, lastFence).trim();
    }

    private Optional<Parsed> parseJsonEnvelope(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isObject() || !root.has("answer")) {
                return Optional.empty();
            }
            String answer = root.path("answer").stringValue("");
            List<Citation> citations = new ArrayList<>();
            JsonNode array = root.path("citations");
            if (array.isArray()) {
                for (JsonNode node : array) {
                    String id = text(node, "evidence_id", "evidenceId");
                    if (id == null) {
                        continue;
                    }
                    citations.add(new Citation(id, text(node, "label"), text(node, "field"),
                            text(node, "value", "claimed_value"), text(node, "unit", "claimed_unit")));
                }
            }
            List<String> missing = new ArrayList<>();
            JsonNode missingNode = root.path("missing_fields");
            if (missingNode.isArray()) {
                for (JsonNode node : missingNode) {
                    String value = node.isString() ? node.stringValue() : node.asString();
                    if (value != null && !value.isBlank()) {
                        missing.add(value.trim());
                    }
                }
            }
            return Optional.of(new Parsed(answer, citations, missing));
        } catch (RuntimeException e) {
            log.debug("回答信封不是合法 JSON，退回文本解析 type={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Parsed parseProse(String raw) {
        List<Citation> citations = new ArrayList<>();
        Matcher line = CITATION_LINE.matcher(raw);
        while (line.find()) {
            String ids = line.group("ids");
            if (ids == null || ids.isBlank() || ids.trim().equalsIgnoreCase("NONE")) {
                continue;
            }
            for (String token : ids.split("[,，;；]")) {
                String candidate = token.trim();
                if (!candidate.isEmpty()) {
                    citations.add(new Citation(candidate, null, null, null, null));
                }
            }
        }
        // 机器约定行不属于给用户看的正文：来源区块由界面按 evidenceRefs 渲染
        String text = CITATION_LINE.matcher(raw).replaceAll("").trim();

        // 正文里裸写的 ev-… 也算引用：模型经常不按约定输出 CITATIONS 行，硬要它只会丢引用而不是丢事实
        Matcher bare = BARE_EVIDENCE_TOKEN.matcher(text);
        Set<String> known = new LinkedHashSet<>();
        citations.forEach(citation -> known.add(citation.evidenceId()));
        while (bare.find()) {
            String token = bare.group();
            if (known.add(token)) {
                citations.add(new Citation(token, null, null, null, null));
            }
        }
        return new Parsed(text, citations, List.of());
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isMissingNode() && !value.isNull()) {
                String raw = value.isString() ? value.stringValue() : value.asString();
                if (raw != null && !raw.isBlank()) {
                    return raw.trim();
                }
            }
        }
        return null;
    }

    // ---- 按引用取值 ----

    /**
     * @param field 指定字段就取该字段；为空则给一份确定性的紧凑摘要（仍然全部来自工具数据）
     * @return null 表示这条来源没有可用数值
     */
    private String valueOf(EvidenceRegistry.Entry entry, String field) {
        if (!entry.hasData()) {
            return null;
        }
        JsonNode data = entry.dataSnapshot();
        if (field != null) {
            JsonNode node = data.path(field);
            if (node.isMissingNode() || node.isNull()) {
                return null;
            }
            return render(node);
        }
        if (data.isObject()) {
            String summary = compactSummary(data);
            return summary.isEmpty() ? null : summary;
        }
        if (data.isArray()) {
            return data.isEmpty() ? null : "共 " + data.size() + " 条记录";
        }
        return render(data);
    }

    /**
     * 单位只在工具显式给出 sibling 字段时填充。
     *
     * <p>不从字段名后缀（weightKg → kg）推断：那是把命名习惯当成事实来源，
     * 一旦某个工具字段叫 {@code targetKg} 而值是磅，我们就在伪造单位口径（AGENTS.md：度量单位必须来自说明）。
     */
    private String unitOf(EvidenceRegistry.Entry entry, String field) {
        if (!entry.hasData()) {
            return null;
        }
        JsonNode data = entry.dataSnapshot();
        if (!data.isObject()) {
            return null;
        }
        if (field != null) {
            JsonNode sibling = data.path(field + "Unit");
            if (sibling.isString()) {
                return sibling.stringValue();
            }
        }
        JsonNode unit = data.path("unit");
        return unit.isString() ? unit.stringValue() : null;
    }

    private String labelOf(EvidenceRegistry.Entry entry, Citation citation) {
        if (citation.label() != null) {
            return citation.label();
        }
        if (citation.field() != null) {
            return citation.field();
        }
        return entry.toolName() == null ? entry.evidence().describe() : entry.toolName();
    }

    private static String render(JsonNode node) {
        if (node.isNumber()) {
            return node.decimalValue().stripTrailingZeros().toPlainString();
        }
        if (node.isBoolean() || node.isString()) {
            return node.asString();
        }
        return compactSummary(node);
    }

    private static String compactSummary(JsonNode node) {
        if (!node.isObject()) {
            return node.asString("");
        }
        Map<String, String> scalars = new TreeMap<>();
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            JsonNode value = field.getValue();
            if (value.isValueNode() && !value.isNull()) {
                scalars.put(field.getKey(), render(value));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : scalars.entrySet()) {
            if (!sb.isEmpty() && sb.length() + entry.getKey().length() + entry.getValue().length() + 2 > MAX_SUMMARY_CHARS) {
                sb.append("…");
                break;
            }
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    private static boolean sameNumber(String claimed, String evidenceValue) {
        String left = claimed.replace(",", "").replace(" ", "").toLowerCase(Locale.ROOT);
        String right = evidenceValue.replace(",", "").replace(" ", "").toLowerCase(Locale.ROOT);
        if (left.equals(right)) {
            return true;
        }
        try {
            return new BigDecimal(left).compareTo(new BigDecimal(right)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String humanizeConflict(String marker) {
        if (marker == null || !marker.startsWith("VALUE_CONFLICT:")) {
            return marker;
        }
        String body = marker.substring("VALUE_CONFLICT:".length());
        int eq = body.indexOf('=');
        return eq < 0 ? body : "来源 " + body.substring(0, eq) + " 的核实值是 " + body.substring(eq + 1);
    }

    /** 本轮读取里不完整的来源：让"数据不全"在 missingFields 里可见，而不是被回答文本盖过去。 */
    private List<String> incompleteReads(EvidenceRegistry registry) {
        List<String> notes = new ArrayList<>();
        for (EvidenceRegistry.Entry entry : registry.entries()) {
            if (!entry.dataComplete() || !entry.hasData()) {
                notes.add("INCOMPLETE_READ:" + entry.evidenceId());
            }
        }
        return notes;
    }

    private static List<String> distinct(List<String> values) {
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank() && !out.contains(value)) {
                out.add(value);
            }
        }
        return out;
    }
}
