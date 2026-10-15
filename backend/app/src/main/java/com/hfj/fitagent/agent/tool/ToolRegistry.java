package com.hfj.fitagent.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工具注册表：启动期一次性装配，运行期只读。
 *
 * <p>两个 fail-fast：
 * <ol>
 *   <li><b>重名即启动失败</b>。官方要求 tools[] 的 name 在同一次请求内唯一（docs/agent-v1-protocol.md §3），
 *       而两个工具同名时"后者覆盖前者"会静默改变模型看到的能力面。</li>
 *   <li>parameters schema 解析不了也启动失败：schema 是发给供应商的，坏了就是一整轮 400。</li>
 * </ol>
 *
 * <p>{@link #definitionsFor(Set)} 是唯一的"给模型看什么"的投影：注册表里可能存在本轮不注册的工具
 * （baseline §3.3 的取舍），白名单外的名字连声明都不发出去，而不是"发出去再拒绝调用"。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, AgentTool<?, ?>> toolsByName;
    private final Map<String, ToolDefinition> definitionsByName;

    @Autowired
    public ToolRegistry(ObjectProvider<AgentTool<?, ?>> tools, ObjectMapper objectMapper) {
        this(tools.stream().toList(), objectMapper);
    }

    /** 测试与显式装配用：直接给出工具集合。重名在这里就会炸（不允许留到运行期）。 */
    public ToolRegistry(Collection<AgentTool<?, ?>> tools, ObjectMapper objectMapper) {
        Map<String, AgentTool<?, ?>> byName = new LinkedHashMap<>();
        Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
        List<String> schemaFailures = new ArrayList<>();
        for (AgentTool<?, ?> tool : (tools == null ? List.<AgentTool<?, ?>>of() : new ArrayList<>(tools))) {
            ToolDefinition definition = tool.definition();
            if (definition == null) {
                throw new IllegalStateException("工具 " + tool.getClass().getName() + " 的 definition() 返回 null");
            }
            if (tool.argumentType() == null) {
                throw new IllegalStateException("工具 " + definition.name() + " 的 argumentType() 返回 null");
            }
            AgentTool<?, ?> previous = byName.put(definition.name(), tool);
            if (previous != null) {
                throw new IllegalStateException("工具名重复：" + definition.name() + " 同时由 "
                        + previous.getClass().getName() + " 与 " + tool.getClass().getName() + " 注册");
            }
            try {
                var parsed = objectMapper.readTree(definition.jsonSchema());
                if (!parsed.isObject()) {
                    schemaFailures.add(definition.name() + "（parameters 必须是 JSON 对象）");
                }
            } catch (RuntimeException e) {
                schemaFailures.add(definition.name() + "（" + e.getClass().getSimpleName() + "）");
            }
            definitions.put(definition.name(), definition);
        }
        if (!schemaFailures.isEmpty()) {
            throw new IllegalStateException("工具 parameters JSON Schema 无法解析：" + schemaFailures);
        }
        this.toolsByName = Map.copyOf(byName);
        this.definitionsByName = Map.copyOf(definitions);
        log.info("工具注册完成 count={} names={}", definitionsByName.size(), names());
    }

    public Optional<AgentTool<?, ?>> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(toolsByName.get(name.trim()));
    }

    public Optional<ToolDefinition> definition(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(definitionsByName.get(name.trim()));
    }

    public boolean isRegistered(String name) {
        return name != null && definitionsByName.containsKey(name.trim());
    }

    public Set<String> names() {
        return definitionsByName.keySet().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** 声明列表按名字排序，保证同一 run 的重试/多次装配发出同样的字节。 */
    public List<ToolDefinition> definitionsFor(Set<String> allowedTools) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return List.of();
        }
        return definitionsByName.entrySet().stream()
                .filter(entry -> allowedTools.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .sorted(java.util.Comparator.comparing(ToolDefinition::name))
                .toList();
    }

    /** 白名单里注册表没有的名字（配置写错时给界面与日志一个准确说法，不静默少发工具）。 */
    public List<String> unknownAllowedNames(Set<String> allowedTools) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return List.of();
        }
        return allowedTools.stream()
                .filter(name -> !definitionsByName.containsKey(name))
                .sorted()
                .toList();
    }
}
