package com.hfj.fitagent.modules.coach.tools;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工具 parameters JSON Schema 的装配口。
 *
 * <p>只保留一件必须由构造保证的事：{@code additionalProperties:false}。
 * schema 是给模型的<b>唯一</b>参数面声明，而"未知字段拒绝"这件事在本项目里有三道防线，
 * 少写一处不会立刻出事、只会在某次模型自由发挥时才暴露：
 * <ol>
 *   <li>这里的 {@code additionalProperties:false}（模型侧声明）；</li>
 *   <li>{@code ToolExecutor} 用全局 ObjectMapper 解析，而 {@code JacksonConfig} 开了
 *       {@code FAIL_ON_UNKNOWN_PROPERTIES}（解析侧硬拒）；</li>
 *   <li>{@code ToolExecutor.FORBIDDEN_FIELDS} 的反射兜底（身份字段专项）。</li>
 * </ol>
 * 第二道已经足够拦停，但 schema 才是模型看得懂的那一份：不在声明里写 false，
 * 等于允许模型继续试——每次试错都要过一次预算闸门。
 *
 * <p>schema <b>不含</b> userId / conversationId / credential 之类的字段：身份来自
 * {@code AgentContext}，结构上不存在的字段比"文档里写了不要用"可靠（AGENTS.md）。
 */
public final class CoachToolSchemas {

    private CoachToolSchemas() {
    }

    /** 无参数工具（get_profile）：只接受空对象。 */
    public static String empty() {
        return "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}";
    }

    /**
     * @param propertySchemas {@code 属性名 -> 该属性的 schema 片段}，顺序即声明顺序
     */
    public static String object(List<String> required, List<String[]> propertySchemas) {
        String properties = propertySchemas.stream()
                .map(entry -> "\"" + entry[0] + "\":" + entry[1])
                .collect(Collectors.joining(","));
        String requiredCsv = required.stream()
                .map(name -> "\"" + name + "\"")
                .collect(Collectors.joining(","));
        return "{\"type\":\"object\",\"properties\":{" + properties
                + "},\"required\":[" + requiredCsv + "],\"additionalProperties\":false}";
    }

    /**
     * 业务日期：ISO {@code YYYY-MM-DD}。日期格式校验属于 AGENTS.md 允许的
     * "单位/正数/长度/枚举/日期格式"那一类，不引入任何医学阈值。
     *
     * <p>JSON 里反斜杠要写两遍（{@code \\d}），解析出来的 pattern 才是正则 {@code \d}
     * ——少写一层就是 schema 与 {@code ToolExecutor} 的解析口径不一致。
     */
    public static String isoDate(String description) {
        return """
                {"type":"string","format":"date","pattern":"^\\\\d{4}-\\\\d{2}-\\\\d{2}$","description":"%s"}"""
                .formatted(description);
    }
}
