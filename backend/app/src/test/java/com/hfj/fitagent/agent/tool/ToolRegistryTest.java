package com.hfj.fitagent.agent.tool;

import com.hfj.fitagent.agent.AgentTestSupport;
import com.hfj.fitagent.agent.core.AgentContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 注册表启动期规则：重名即失败、schema 不可解析即失败、白名单投影。
 *
 * <p>对应 docs/agent-v1-protocol.md §3："name 在同一次请求内必须唯一"，
 * 以及 baseline §3.3 的取舍——本轮没有训练执行记录，因此执行类工具<b>不注册</b>，宁缺毋伪。
 */
class ToolRegistryTest {

    @Test
    @DisplayName("两个工具同名 → 启动即失败，不出现后者覆盖前者")
    void duplicateToolNameFailsFast() {
        AgentTool<?, ?> first = new NamedTool("get_weight_trend");
        AgentTool<?, ?> second = new NamedTool("get_weight_trend");

        assertThatThrownBy(() -> new ToolRegistry(List.of(first, second), AgentTestSupport.mapper()))
                .isInstanceOfSatisfying(IllegalStateException.class,
                        e -> assertThat(e.getMessage()).contains("工具名重复").contains("get_weight_trend"));
    }

    @Test
    @DisplayName("parameters schema 不是合法 JSON 对象 → 启动即失败")
    void brokenSchemaFailsFast() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(new BadSchemaTool()), AgentTestSupport.mapper()))
                .isInstanceOfSatisfying(IllegalStateException.class,
                        e -> assertThat(e.getMessage()).contains("JSON Schema"));
    }

    @Test
    @DisplayName("只把白名单里的工具声明发给模型（注册表里可以有本轮不注册的能力）")
    void whitelistProjectionHidesUnregisteredCapabilities() {
        ToolRegistry registry = new ToolRegistry(List.of(new NamedTool("get_weight_trend"),
                new NamedTool("get_profile"), new NamedTool("get_workout_records")), AgentTestSupport.mapper());

        List<ToolDefinition> declared = registry.definitionsFor(Set.of("get_weight_trend", "get_profile"));
        assertThat(declared).extracting(ToolDefinition::name)
                .containsExactlyInAnyOrder("get_profile", "get_weight_trend");
        assertThat(registry.definitionsFor(Set.of())).isEmpty();
        assertThat(registry.unknownAllowedNames(Set.of("get_weight_trend", "get_ghost")))
                .containsExactly("get_ghost");
    }

    @Test
    @DisplayName("按名字查找是精确匹配：不猜「最接近的那个工具」")
    void lookupIsExact() {
        ToolRegistry registry = new ToolRegistry(List.of(new NamedTool("get_profile")), AgentTestSupport.mapper());

        assertThat(registry.find("get_profile")).isPresent();
        assertThat(registry.find(" GET_PROFILE ")).isEmpty();
        assertThat(registry.find("get_profiles")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
    }

    static final class NamedTool implements AgentTool<AgentTestSupport.EmptyArgs, String> {

        private final String name;

        NamedTool(String name) {
            this.name = name;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(name, "测试工具 " + name, "{\"type\":\"object\",\"properties\":{}}");
        }

        @Override
        public Class<AgentTestSupport.EmptyArgs> argumentType() {
            return AgentTestSupport.EmptyArgs.class;
        }

        @Override
        public ToolResult<String> execute(AgentTestSupport.EmptyArgs args, AgentContext ctx) {
            return ToolResult.ok(name, List.of(), ctx.dataRevision());
        }
    }

    static final class BadSchemaTool implements AgentTool<AgentTestSupport.EmptyArgs, String> {

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("get_broken", "schema 坏掉的工具", "{\"type\":\"object\",");
        }

        @Override
        public Class<AgentTestSupport.EmptyArgs> argumentType() {
            return AgentTestSupport.EmptyArgs.class;
        }

        @Override
        public ToolResult<String> execute(AgentTestSupport.EmptyArgs args, AgentContext ctx) {
            return ToolResult.ok("", List.of(), ctx.dataRevision());
        }
    }
}
