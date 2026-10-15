package com.hfj.fitagent.agent.tool;

import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.core.RunLimits;
import com.hfj.fitagent.agent.model.ToolCall;
import com.hfj.fitagent.common.AppClock;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具执行器：把模型给出的 {@code arguments} 字符串变成一条<b>可信</b>的 tool 消息。
 *
 * <p>顺序（任一步失败都以结构化错误结束，并且<b>仍然</b>产出 tool 消息以完成协议配对，design §5.4）：
 * <ol>
 *   <li>白名单检查（{@code AgentContext.allowedTools}，服务端集合）→ 注册表查找 → 未知名 {@code UNKNOWN_TOOL}；</li>
 *   <li>参数<b>严格</b>解析：未知字段拒绝（官方明确"模型不保证生成合法 JSON"，protocol §3）；</li>
 *   <li>身份字段兜底：参数里出现 userId / credentialId 之类一律 {@code FORBIDDEN_IDENTITY_FIELD}。
 *       主防线是"授权来自 AgentContext、参数类型里没有身份字段"（{@link AgentTool} 的契约），这里是第二道；</li>
 *   <li>Bean Validation：只做单位/正数/长度/枚举/日期格式，不引入任何未经说明的医学阈值（AGENTS.md）；</li>
 *   <li>带超时执行（工具自身上限与 run 剩余时间取小）；</li>
 *   <li>结果限长：超出 {@code maxResultBytes} 就<b>丢弃明细</b>并置 {@code truncated=true} 与
 *       {@code dataComplete=false}，模型不能把半份数据当成全份；</li>
 *   <li>本 run 结果复用（{@link ToolResultCache}，键 = toolName + 规范化参数 + dataRevision）。</li>
 * </ol>
 *
 * <p>工具执行保持顺序（design §14）。内部那个单线程执行池<b>不是</b>并发调度器：它只为"给一次同步调用加超时"
 * 而存在（JVM 内无法中断一段不检查中断的阻塞代码，只能把 Future 取消当作尽力而为的信号）。
 */
@Component
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    /** 默认工程闸门：单次工具执行时长上限。不是产品规则，只是别让一个慢查询拖死整个 run。 */
    public static final Duration DEFAULT_TOOL_TIMEOUT = Duration.ofSeconds(10);

    /** 参数里绝不允许出现的字段名（去下划线、小写后比较）：身份、凭据、预算、权威开关都属于服务端上下文。 */
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "userid", "accountid", "openid", "unionid", "wechatopenid", "conversationid", "runid",
            "credentialid", "credentialversion", "apikey", "key", "secret", "token", "authorization",
            "budget", "budgetid", "policyid", "cost", "maxcost",
            "datarevision", "role", "isstaff", "admin", "privileged", "allowedtools", "toolchoice", "model",
            "modelid", "providerid", "temperature", "maxtokens");

    private final ToolRegistry registry;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final RunLimits limits;
    /** 与 Runner 同一个时间源：否则"剩余时间"在两处算出两个答案，工具会在还没到期时被拒执行。 */
    private final AppClock clock;

    private volatile ExecutorService timeoutPool;

    public ToolExecutor(ToolRegistry registry, ObjectMapper objectMapper, Validator validator, RunLimits limits,
                        AppClock clock) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.limits = limits;
        this.clock = clock == null ? new AppClock() : clock;
    }

    /**
     * @param cache 本 run 的复用表；null 表示不复用（每次都真实执行）
     */
    public ExecutedTool execute(ToolCall call, AgentContext ctx, ToolResultCache cache) {
        long startedAt = System.nanoTime();
        String canonicalArguments;
        try {
            canonicalArguments = canonicalize(call.argumentsJson());
        } catch (RuntimeException e) {
            canonicalArguments = call.argumentsJson();
        }
        String cacheKey = call.name() + "|" + canonicalArguments + "|" + ctx.dataRevision();

        if (cache != null) {
            Optional<ExecutedTool> cached = cache.find(cacheKey);
            if (cached.isPresent()) {
                ExecutedTool replay = cached.get().forToolCallId(call.id(), cache.requestCount(cacheKey));
                log.debug("工具结果复用 tool={} toolCallId={}", call.name(), call.id());
                return replay;
            }
        }

        ExecutedTool executed = doExecute(call, ctx, canonicalArguments, cacheKey, startedAt);
        if (cache != null) {
            // 结构化失败也进缓存：同一份非法参数在同一 run 内重复出现时应当命中同一个键，
            // 由 Runner 的 REPETITIVE_LOOP 判定终止，而不是每次都重新解析一遍假装"可能会变好"
            cache.put(cacheKey, executed);
        }
        return executed;
    }

    private ExecutedTool doExecute(ToolCall call, AgentContext ctx, String canonicalArguments,
                                   String cacheKey, long startedAt) {
        if (!ctx.allowsTool(call.name())) {
            return failure(call, ToolErrorCode.TOOL_NOT_ALLOWED,
                    "工具 " + call.name() + " 不在本次运行的允许列表内", startedAt, cacheKey);
        }
        Optional<AgentTool<?, ?>> found = registry.find(call.name());
        if (found.isEmpty()) {
            return failure(call, ToolErrorCode.UNKNOWN_TOOL,
                    "工具 " + call.name() + " 未注册或本轮不提供该能力", startedAt, cacheKey);
        }
        AgentTool<?, ?> tool = found.get();

        Object arguments;
        try {
            arguments = parseArguments(tool, canonicalArguments);
        } catch (RuntimeException e) {
            // 只记类型：供应商原文可能包含用户输入，不进日志
            log.debug("工具参数解析失败 tool={} type={}", call.name(), e.getClass().getSimpleName());
            return failure(call, ToolErrorCode.INVALID_ARGUMENTS,
                    "arguments 不是符合声明 schema 的 JSON 对象（未知字段同样被拒绝），请只按 schema 字段重新给出",
                    startedAt, cacheKey);
        }

        List<String> forbidden = forbiddenFields(tool.argumentType(), canonicalArguments);
        if (!forbidden.isEmpty()) {
            log.warn("工具参数携带被禁止的身份/权威字段 tool={} fields={}", call.name(), forbidden);
            return failure(call, ToolErrorCode.FORBIDDEN_IDENTITY_FIELD,
                    "身份与预算字段由服务端上下文提供，参数不得包含：" + forbidden, startedAt, cacheKey);
        }

        List<String> violations = validate(arguments);
        if (!violations.isEmpty()) {
            return failure(call, ToolErrorCode.VALIDATION_FAILED,
                    "参数校验未通过：" + String.join("; ", violations), startedAt, cacheKey);
        }

        ToolResult<?> result;
        try {
            result = runWithTimeout(tool, arguments, ctx);
        } catch (ToolTimeoutException e) {
            log.debug("工具执行超时 tool={} limit={}", call.name(), e.timeout());
            return failure(call, ToolErrorCode.TIMEOUT,
                    "工具执行超时（" + e.timeout() + "），本轮未取到数据", startedAt, cacheKey);
        } catch (RuntimeException e) {
            log.warn("工具执行异常 tool={} type={}", call.name(), e.getClass().getSimpleName());
            return failure(call, ToolErrorCode.INTERNAL_ERROR, "工具内部失败，本轮未取到数据", startedAt, cacheKey);
        }
        if (result == null) {
            return failure(call, ToolErrorCode.INTERNAL_ERROR, "工具返回 null 结果", startedAt, cacheKey);
        }

        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        JsonNode dataSnapshot = result.data() == null ? null : safeToNode(result.data());
        CappedPayload capped = buildPayload(call.name(), result, dataSnapshot);
        // 被限长丢弃过明细，结构化结果 itself 也要改口：只写在给模型的文本里是不够的，
        // 事实组装与界面都读 ToolResult，两处口径必须一致
        ToolResult<?> reported = capped.droppedDetail()
                ? new ToolResult<>(result.code(), result.data(), result.evidenceRefs(), result.missingFields(),
                        false, result.dataRevision(), true, result.detail())
                : result;
        return new ExecutedTool(call.id(), call.name(), reported, capped.json(), dataSnapshot, true, durationMs,
                cacheKey, false, 1);
    }

    /**
     * 限长结果：{@code json} 是给模型的 tool 消息内容，{@code droppedDetail} 表示明细被丢弃。
     *
     * <p>进程内的完整快照仍然保留在 {@link ExecutedTool#dataSnapshot()}：模型看到的少，不等于已验证的少；
     * 但两边都带 {@code truncated}/{@code dataComplete=false}，谁也不会把半份数据当成全份。
     */
    private record CappedPayload(String json, boolean droppedDetail) {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object parseArguments(AgentTool<?, ?> tool, String canonicalArguments) {
        Class argumentType = tool.argumentType();
        return objectMapper.readValue(canonicalArguments, argumentType);
    }

    private List<String> validate(Object arguments) {
        Set<ConstraintViolation<Object>> violations = validator.validate(arguments);
        List<String> messages = new ArrayList<>(violations.size());
        violations.stream()
                .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .forEach(v -> messages.add(v.getPropertyPath() + " " + v.getMessage()));
        return messages;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ToolResult<?> runWithTimeout(AgentTool tool, Object arguments, AgentContext ctx) {
        Duration limit = effectiveTimeout(tool, ctx);
        if (limit == null) {
            // 剩余时间不足：不执行，也不把一个必然失败的读取算进账
            throw new ToolTimeoutException(Duration.ZERO);
        }
        Future<?> future = timeoutPool().submit(() -> tool.execute(arguments, ctx));
        try {
            return (ToolResult<?>) future.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ToolTimeoutException(limit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new ToolTimeoutException(limit);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("工具执行失败", cause);
        }
    }

    /** 工具自身上限与 run 剩余时间取小；剩余时间不足以完成一次读取时返回 null（不执行）。 */
    private Duration effectiveTimeout(AgentTool<?, ?> tool, AgentContext ctx) {
        Duration remaining = ctx.remaining(clock.now());
        if (remaining.isZero()) {
            return null;
        }
        Duration declared = tool.maxDuration();
        Duration limit = declared == null || declared.isZero() || declared.isNegative()
                ? DEFAULT_TOOL_TIMEOUT
                : declared;
        Duration bounded = limit.compareTo(remaining) <= 0 ? limit : remaining;
        return bounded.compareTo(Duration.ofMillis(1)) < 0 ? null : bounded;
    }

    private ExecutorService timeoutPool() {
        ExecutorService local = timeoutPool;
        if (local == null) {
            synchronized (this) {
                local = timeoutPool;
                if (local == null) {
                    local = Executors.newSingleThreadExecutor(runnable -> {
                        Thread thread = new Thread(runnable, "fitagent-tool-timeout");
                        thread.setDaemon(true);
                        return thread;
                    });
                    timeoutPool = local;
                }
            }
        }
        return local;
    }

    /** Spring 关停单例时调用（线程是 daemon，正常退出不依赖它）。 */
    public void close() {
        ExecutorService local = timeoutPool;
        if (local != null) {
            local.shutdownNow();
        }
    }

    // ---- 回传给模型的 tool 消息内容 ----

    private CappedPayload buildPayload(String toolName, ToolResult<?> result, JsonNode dataSnapshot) {
        int maxBytes = limits.maxResultBytes();
        ObjectNode full = envelope(toolName, result, true);
        full.set("data", dataSnapshot == null ? objectMapper.nullNode() : dataSnapshot.deepCopy());
        String fullText = writeAsString(full);
        if (fits(fullText, maxBytes)) {
            return new CappedPayload(fullText, false);
        }
        // 明细放不下：整段丢弃明细并显式声明不完整——绝不给模型半个 JSON
        ObjectNode shrunk = envelope(toolName, result, false);
        shrunk.put("truncated", true);
        shrunk.put("dataComplete", false);
        shrunk.set("data", objectMapper.nullNode());
        shrunk.put("notice", "结果超出 " + maxBytes + " 字节上限，明细未提供给模型；请如实说明数据不完整，不要估算");
        String shrunkText = writeAsString(shrunk);
        if (fits(shrunkText, maxBytes)) {
            log.debug("工具结果被限长 tool={} fullBytes={} capBytes={}", toolName,
                    fullText.getBytes(StandardCharsets.UTF_8).length, maxBytes);
            return new CappedPayload(shrunkText, true);
        }
        // 上限被配得连缩样信封都放不下。最小信封也必须带 dataComplete=false：
        // "不得让模型把被裁剪的子集当成全量"是契约，不能因为字节数不够就把它省掉。
        String minimal = "{\"code\":\"" + ToolErrorCode.RESULT_TOO_LARGE
                + "\",\"truncated\":true,\"dataComplete\":false}";
        String lastResort = "{\"code\":\"" + ToolErrorCode.RESULT_TOO_LARGE
                + "\",\"dataComplete\":false}";
        return new CappedPayload(fits(minimal, maxBytes) ? minimal : lastResort, true);
    }

    private ObjectNode envelope(String toolName, ToolResult<?> result, boolean withEvidenceDetail) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("tool", toolName);
        node.put("code", result.code());
        if (result.detail() != null && !result.detail().isBlank()) {
            node.put("detail", result.detail());
        }
        node.put("dataComplete", result.dataComplete());
        node.put("truncated", result.truncated());
        if (result.dataRevision() != null) {
            node.put("dataRevision", result.dataRevision());
        }
        if (!result.missingFields().isEmpty()) {
            ArrayNode missing = node.putArray("missingFields");
            result.missingFields().forEach(missing::add);
        }
        ArrayNode evidence = node.putArray("evidence");
        for (Evidence ref : result.evidenceRefs()) {
            ObjectNode item = evidence.addObject();
            item.put("evidenceId", ref.evidenceId());
            if (withEvidenceDetail) {
                item.put("sourceTable", ref.sourceTable());
                item.put("sourceId", ref.sourceId());
                item.put("businessDate", ref.businessDate().toString());
                if (ref.ruleVersion() != null) {
                    item.put("ruleVersion", ref.ruleVersion());
                }
            }
        }
        return node;
    }

    private ExecutedTool failure(ToolCall call, String code, String detail, long startedAt, String cacheKey) {
        ToolResult<?> result = ToolResult.error(code, detail);
        ObjectNode node = envelope(call.name(), result, false);
        node.set("data", objectMapper.nullNode());
        String payload = writeAsString(node);
        String bounded = fits(payload, limits.maxResultBytes())
                ? payload
                : "{\"code\":\"" + code + "\",\"truncated\":true}";
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        return new ExecutedTool(call.id(), call.name(), result, bounded, null, false, durationMs, cacheKey, false, 1);
    }

    private JsonNode safeToNode(Object data) {
        try {
            return objectMapper.valueToTree(data);
        } catch (RuntimeException e) {
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("unserializable", e.getClass().getSimpleName());
            log.warn("工具结果无法序列化为 JSON toolType={} type={}", data.getClass().getSimpleName(),
                    e.getClass().getSimpleName());
            return fallback;
        }
    }

    private String writeAsString(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (RuntimeException e) {
            log.warn("tool 消息序列化失败 type={}", e.getClass().getSimpleName());
            return "{\"code\":\"INTERNAL_ERROR\"}";
        }
    }

    private static boolean fits(String text, int maxBytes) {
        return text.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }

    /**
     * 规范化参数：解析后按<b>键名排序</b>重新序列化。
     *
     * <p>目的只有一个——语义相同的调用必须得到同一个缓存键。模型把 {@code {"a":1,"b":2}} 写成
     * {@code {"b":2,"a":1}} 不该被当成两次不同的读取。解析失败时原样返回（随后的严格解析会给出结构化错误）。
     */
    public static String canonicalize(String json) {
        String source = json == null || json.isBlank() ? ToolCall.EMPTY_ARGUMENTS : json;
        try {
            JsonNode node = CANONICAL_MAPPER.readTree(source);
            return CANONICAL_MAPPER.writeValueAsString(sortedCopy(node));
        } catch (RuntimeException e) {
            return source;
        }
    }

    private static JsonNode sortedCopy(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                sorted.put(entry.getKey(), sortedCopy(entry.getValue()));
            }
            ObjectNode copy = CANONICAL_MAPPER.createObjectNode();
            sorted.forEach(copy::set);
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = CANONICAL_MAPPER.createArrayNode();
            node.forEach(child -> copy.add(sortedCopy(child)));
            return copy;
        }
        return node;
    }

    /**
     * 只用于规范化的 mapper：与应用 mapper 分开，因为应用 mapper 的字段可见性/包含策略会随前端契约调整，
     * 而缓存键必须只由 JSON 语义决定。
     */
    private static final ObjectMapper CANONICAL_MAPPER = tools.jackson.databind.json.JsonMapper.builder().build();

    /** 反射扫参数类型的字段 + 扫 JSON 顶层键：任何一处命中就拒绝执行。 */
    private List<String> forbiddenFields(Class<?> argumentType, String canonicalArguments) {
        List<String> hits = new ArrayList<>();
        if (argumentType.isRecord()) {
            for (String name : recordComponents(argumentType)) {
                if (isForbidden(name) && !hits.contains(name)) {
                    hits.add(name);
                }
            }
        }
        try {
            JsonNode root = CANONICAL_MAPPER.readTree(canonicalArguments);
            if (root.isObject()) {
                for (Map.Entry<String, JsonNode> entry : root.properties()) {
                    if (isForbidden(entry.getKey()) && !hits.contains(entry.getKey())) {
                        hits.add(entry.getKey());
                    }
                }
            }
        } catch (RuntimeException e) {
            log.debug("身份字段扫描跳过（参数不是 JSON）type={}", e.getClass().getSimpleName());
        }
        return hits;
    }

    private static List<String> recordComponents(Class<?> type) {
        return java.util.Arrays.stream(type.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
    }

    private static boolean isForbidden(String field) {
        if (field == null) {
            return false;
        }
        String normalized = field.replace("_", "").toLowerCase(Locale.ROOT);
        return FORBIDDEN_FIELDS.contains(normalized);
    }

    /** 工具执行超时（内部信号，不外泄成响应）。 */
    private static final class ToolTimeoutException extends RuntimeException {

        private final Duration timeout;

        private ToolTimeoutException(Duration timeout) {
            super("工具执行超时");
            this.timeout = timeout;
        }

        private Duration timeout() {
            return timeout;
        }
    }
}
