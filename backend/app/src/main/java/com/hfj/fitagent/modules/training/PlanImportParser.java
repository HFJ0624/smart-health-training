package com.hfj.fitagent.modules.training;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.util.Ids;
import com.hfj.fitagent.modules.training.dto.PlanDraftSpec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导入文档的解析器：把原始 JSON 文本变成 {@link PlanDraftSpec}，并顺带完成
 * docs/p1-plan-import.md §8 的第 3–5 阶（schema_version → 未知/禁用字段 → 类型与必填）。
 *
 * <p>为什么不直接用 DTO 绑定（{@code mapper.readValue(text, PlanImportDocument.class)}）：
 * <ol>
 *   <li>绑定失败是<b>异常</b>，一次只能报一个字段，而这里要求一轮就把用户的所有问题报全；</li>
 *   <li>类型判定要严：{@code "8"}、{@code true}、{@code 0/1} 都必须按 INVALID_TYPE 拒绝，
 *       这依赖我们对每个键自己判定，不能交给绑定层的宽松转换；</li>
 *   <li>错误要带 JSON 路径（{@code days[2].items[0].reps_max}），路径是我们拼的，
 *       不是异常信息里的。</li>
 * </ol>
 *
 * <p>因此这里把文档读成 {@code Map}/{@code List} 的通用树，逐键判定。
 * 读的是<b>原始文本</b>（不是已解析的对象）：preview 与 confirm 都从文本重新走一遍，
 * 客户端没有机会用"已规范化的对象"绕过校验（§10）。
 *
 * <p>金额/负重用 {@link BigDecimal}，不用 double：{@code double} 会把 0.1 存成
 * 0.1000000000000000055…，而 2 位小数是硬规则（docs/p1-database.md §3.9）。
 */
@Component
public class PlanImportParser implements PlanImportParsers {

    /** 只接受 ISO local date（严格模式，年份用 uuuu 而非 yyyy：缺 era 的严格解析才不会被宽松补齐）。 */
    private static final DateTimeFormatter STRICT_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private final ObjectMapper mapper;

    public PlanImportParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Result parse(String content) {
        Object root = readJson(content);
        if (!(root instanceof Map<?, ?> document)) {
            throw new BizException(ErrorCode.MALFORMED_JSON, "导入内容必须是 JSON 对象");
        }
        List<PlanIssue> errors = new ArrayList<>();
        Map<String, Object> doc = castMap(document);

        // 阶段 3：schema_version —— 不支持就到此为止，后面的一切都没有意义
        if (!checkSchemaVersion(doc, errors)) {
            return new Result(null, errors);
        }

        // 阶段 4：未知字段 / 服务端字段黑名单（逐层，包含数组元素内部）
        checkKeys(doc, errors);
        if (!errors.isEmpty()) {
            return new Result(null, errors);
        }

        // 阶段 5：类型与必填
        PlanDraftSpec spec = readDocument(doc, errors);
        return new Result(spec, errors);
    }

    // ==================== 阶段 2：可读性 ====================

    private Object readJson(String content) {
        try {
            return mapper.readValue(content, Object.class);
        } catch (RuntimeException e) {
            // Jackson 3 的解析异常是非受检的；语法错误与"合法 JSON 但内容不合规"要分开报
            throw new BizException(ErrorCode.MALFORMED_JSON, "不是合法的 JSON：" + rootMessage(e));
        }
    }

    private static String rootMessage(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        return (newline < 0 ? message : message.substring(0, newline)).trim();
    }

    // ==================== 阶段 3 ====================

    private boolean checkSchemaVersion(Map<String, Object> doc, List<PlanIssue> errors) {
        if (!doc.containsKey("schema_version")) {
            errors.add(PlanIssue.of("schema_version", PlanImportErrorCode.REQUIRED,
                    "schema_version 必填，值为 " + SCHEMA_VERSION));
            return false;
        }
        Object value = doc.get("schema_version");
        if (!(value instanceof String text)) {
            errors.add(PlanIssue.of("schema_version", PlanImportErrorCode.INVALID_TYPE,
                    "schema_version 必须是字符串", value));
            return false;
        }
        if (!SCHEMA_VERSION.equals(text)) {
            errors.add(PlanIssue.of("schema_version", PlanImportErrorCode.UNSUPPORTED_SCHEMA_VERSION,
                    "只支持模板版本 " + SCHEMA_VERSION + "，请下载最新模板", text));
            return false;
        }
        return true;
    }

    // ==================== 阶段 4 ====================

    private void checkKeys(Map<String, Object> doc, List<PlanIssue> errors) {
        checkKeySet(doc, ROOT_KEYS, "", errors);
        Object daysValue = doc.get("days");
        if (!(daysValue instanceof List<?> days)) {
            return;   // 类型错误留给阶段 5 报，避免同一处报两遍
        }
        for (int i = 0; i < days.size(); i++) {
            Object dayElement = days.get(i);
            String dayPath = "days[" + i + "]";
            if (!(dayElement instanceof Map<?, ?> dayMap)) {
                continue;
            }
            checkKeySet(castMap(dayMap), DAY_KEYS, dayPath, errors);
            Object itemsValue = castMap(dayMap).get("items");
            if (!(itemsValue instanceof List<?> items)) {
                continue;
            }
            for (int j = 0; j < items.size(); j++) {
                Object itemElement = items.get(j);
                if (itemElement instanceof Map<?, ?> itemMap) {
                    checkKeySet(castMap(itemMap), ITEM_KEYS, dayPath + ".items[" + j + "]", errors);
                }
            }
        }
    }

    /**
     * 逐键判定：黑名单键 → FORBIDDEN_SERVER_FIELD（文案说明该字段由服务端生成）；
     * 其余未定义键 → UNKNOWN_FIELD。大小写敏感：JSON 的键本来就区分大小写，
     * 把 {@code Status} 也当成 {@code status} 反而会让"合法键名"被误放行。
     */
    private void checkKeySet(Map<String, Object> source, Set<String> allowed, String prefix,
                             List<PlanIssue> errors) {
        for (String key : source.keySet()) {
            if (allowed.contains(key)) {
                continue;
            }
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (SERVER_FIELD_BLACKLIST.contains(key)) {
                errors.add(PlanIssue.of(path, PlanImportErrorCode.FORBIDDEN_SERVER_FIELD,
                        "该字段由服务端生成，导入文件不能指定：" + key));
            } else {
                errors.add(PlanIssue.of(path, PlanImportErrorCode.UNKNOWN_FIELD,
                        "未定义字段：" + key + "，请参考模板文件"));
            }
        }
    }

    // ==================== 阶段 5 ====================

    private PlanDraftSpec readDocument(Map<String, Object> doc, List<PlanIssue> errors) {
        String title = requireText(doc, "title", "title", true, errors);
        LocalDate startsOn = requireDate(doc, "starts_on", "starts_on", true, errors);
        LocalDate endsOn = requireDate(doc, "ends_on", "ends_on", false, errors);
        String notes = optionalText(doc, "notes", "notes", errors);
        List<PlanDraftSpec.DaySpec> days = readDays(doc, errors);
        return new PlanDraftSpec(title, startsOn, endsOn, notes, days);
    }

    private List<PlanDraftSpec.DaySpec> readDays(Map<String, Object> doc, List<PlanIssue> errors) {
        Object value = doc.get("days");
        if (value == null) {
            errors.add(PlanIssue.of("days", PlanImportErrorCode.REQUIRED, "计划日列表 days 必填"));
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            errors.add(PlanIssue.of("days", PlanImportErrorCode.INVALID_TYPE,
                    "days 必须是数组", value));
            return List.of();
        }
        List<PlanDraftSpec.DaySpec> days = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            String path = "days[" + i + "]";
            Object element = list.get(i);
            if (!(element instanceof Map<?, ?> map)) {
                errors.add(PlanIssue.of(path, PlanImportErrorCode.INVALID_TYPE, "计划日必须是对象", element));
                continue;
            }
            days.add(readDay(castMap(map), path, errors));
        }
        return days;
    }

    private PlanDraftSpec.DaySpec readDay(Map<String, Object> day, String path, List<PlanIssue> errors) {
        Integer isoWeekday = requireIntegral(day, "iso_weekday", path + ".iso_weekday", true, errors);
        String title = optionalText(day, "title", path + ".title", errors);
        Boolean isRestDay = requireBoolean(day, "is_rest_day", path + ".is_rest_day", errors);
        String notes = optionalText(day, "notes", path + ".notes", errors);
        List<PlanDraftSpec.ItemSpec> items = readItems(day, path, errors);
        return new PlanDraftSpec.DaySpec(isoWeekday, title, isRestDay, notes, items);
    }

    private List<PlanDraftSpec.ItemSpec> readItems(Map<String, Object> day, String dayPath, List<PlanIssue> errors) {
        String path = dayPath + ".items";
        Object value = day.get("items");
        if (value == null) {
            errors.add(PlanIssue.of(path, PlanImportErrorCode.REQUIRED,
                    "items 必填；休息日给空数组 []"));
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            errors.add(PlanIssue.of(path, PlanImportErrorCode.INVALID_TYPE, "items 必须是数组", value));
            return List.of();
        }
        List<PlanDraftSpec.ItemSpec> items = new ArrayList<>(list.size());
        for (int j = 0; j < list.size(); j++) {
            String itemPath = path + "[" + j + "]";
            Object element = list.get(j);
            if (!(element instanceof Map<?, ?> map)) {
                errors.add(PlanIssue.of(itemPath, PlanImportErrorCode.INVALID_TYPE, "动作条目必须是对象", element));
                continue;
            }
            items.add(readItem(castMap(map), itemPath, errors));
        }
        return items;
    }

    private PlanDraftSpec.ItemSpec readItem(Map<String, Object> item, String path, List<PlanIssue> errors) {
        String rawCode = requireText(item, "exercise_code", path + ".exercise_code", true, errors);
        String code = rawCode == null ? null : Ids.canonicalExerciseCode(rawCode);
        Integer positionNo = requireIntegral(item, "position_no", path + ".position_no", false, errors);
        Integer targetSets = requireIntegral(item, "target_sets", path + ".target_sets", false, errors);
        Integer repsMin = requireIntegral(item, "reps_min", path + ".reps_min", false, errors);
        Integer repsMax = requireIntegral(item, "reps_max", path + ".reps_max", false, errors);
        BigDecimal load = requireDecimal(item, "target_external_load_kg", path + ".target_external_load_kg", errors);
        Integer duration = requireIntegral(item, "target_duration_seconds",
                path + ".target_duration_seconds", false, errors);
        Integer rest = requireIntegral(item, "rest_seconds", path + ".rest_seconds", false, errors);
        String notes = optionalText(item, "notes", path + ".notes", errors);
        return new PlanDraftSpec.ItemSpec(code, positionNo, targetSets, repsMin, repsMax,
                load, duration, rest, notes);
    }

    // ==================== 逐类型读取 ====================

    private String requireText(Map<String, Object> source, String key, String path, boolean required,
                               List<PlanIssue> errors) {
        Object value = source.get(key);
        if (value == null) {
            if (required) {
                errors.add(PlanIssue.of(path, PlanImportErrorCode.REQUIRED,
                        label(key) + "必填"));
            }
            return null;
        }
        if (!(value instanceof String text)) {
            errors.add(PlanIssue.of(path, PlanImportErrorCode.INVALID_TYPE,
                    label(key) + "必须是字符串", value));
            return null;
        }
        if (required && text.isBlank()) {
            errors.add(PlanIssue.of(path, PlanImportErrorCode.REQUIRED, label(key) + "不能为空白"));
            return null;
        }
        return text;
    }

    private String optionalText(Map<String, Object> source, String key, String path, List<PlanIssue> errors) {
        return requireText(source, key, path, false, errors);
    }

    /** §3：is_rest_day 必须是 JSON 布尔；0/1、"true" 一律拒绝，避免"看着像布尔"的值被静默解释成相反含义。 */
    private Boolean requireBoolean(Map<String, Object> source, String key, String path, List<PlanIssue> errors) {
        Object value = source.get(key);
        if (value == null) {
            errors.add(PlanIssue.of(path, PlanImportErrorCode.REQUIRED, label(key) + "必填"));
            return null;
        }
        if (!(value instanceof Boolean flag)) {
            errors.add(PlanIssue.of(path, PlanImportErrorCode.INVALID_TYPE,
                    label(key) + "必须是 JSON 布尔 true/false，不接受 0/1 或字符串", value));
            return null;
        }
        return flag;
    }

    /** 日期只接受 YYYY-MM-DD；斜杠格式、时间戳、带时区值一律 INVALID_DATE_FORMAT。 */
    private LocalDate requireDate(Map<String, Object> source, String key, String path, boolean required,
                                 List<PlanIssue> errors) {
        Object value = source.get(key);
        if (value == null) {
            if (required) {
                errors.add(PlanIssue.of(path, PlanImportErrorCode.REQUIRED, label(key) + "必填"));
            }
            return null;
        }
        if (!(value instanceof String text)) {
            errors.add(PlanIssue.of(path, PlanImportErrorCode.INVALID_TYPE,
                    label(key) + "必须是字符串日期 YYYY-MM-DD", value));
            return null;
        }
        try {
            return LocalDate.parse(text.trim(), STRICT_DATE);
        } catch (DateTimeParseException e) {
            errors.add(PlanIssue.of(path, PlanImportErrorCode.INVALID_DATE_FORMAT,
                    label(key) + "必须是 YYYY-MM-DD（ISO 日期），不接受时间戳、斜杠格式或带时区的值", text));
            return null;
        }
    }

    /**
     * 整数字段：只接受 JSON 数字（且值本身是整数）。
     * {@code "8"} 是字符串 → INVALID_TYPE；{@code 8.5} → INVALID_TYPE；{@code 8.0} 视作 8。
     */
    private Integer requireIntegral(Map<String, Object> source, String key, String path, boolean required,
                                    List<PlanIssue> errors) {
        Object value = source.get(key);
        if (value == null) {
            if (required) {
                errors.add(PlanIssue.of(path, PlanImportErrorCode.REQUIRED, label(key) + "必填"));
            }
            return null;
        }
        if (!(value instanceof Number number)) {
            errors.add(PlanIssue.of(path, PlanImportErrorCode.INVALID_TYPE,
                    label(key) + "必须是数字，不接受字符串或布尔", value));
            return null;
        }
        BigDecimal decimal = toDecimal(number);
        if (decimal == null) {
            errors.add(PlanIssue.of(path, PlanImportErrorCode.INVALID_TYPE,
                    label(key) + "不是有限数值", value));
            return null;
        }
        BigDecimal stripped = decimal.stripTrailingZeros();
        if (stripped.scale() > 0) {
            errors.add(PlanIssue.of(path, PlanImportErrorCode.INVALID_TYPE,
                    label(key) + "必须是整数", value));
            return null;
        }
        if (stripped.compareTo(MIN_INT) < 0 || stripped.compareTo(MAX_INT) > 0) {
            errors.add(PlanIssue.of(path, PlanImportErrorCode.OUT_OF_RANGE,
                    label(key) + "超出可表示范围", value));
            return null;
        }
        return stripped.intValueExact();
    }

    /**
     * 小数字段（外部负重 kg）：只接受 JSON 数字。
     * 具体范围（≥0、≤2 位小数）属第 6 阶，由 {@link PlanAggregateValidator} 判定——
     * 手工编辑接口必须共用同一条规则。
     */
    BigDecimal requireDecimal(Map<String, Object> source, String key, String path, List<PlanIssue> errors) {
        Object value = source.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            errors.add(PlanIssue.of(path, PlanImportErrorCode.INVALID_TYPE,
                    label(key) + "必须是数字，不接受字符串；单位固定为 kg", value));
            return null;
        }
        BigDecimal decimal = toDecimal(number);
        if (decimal == null) {
            errors.add(PlanIssue.of(path, PlanImportErrorCode.INVALID_TYPE,
                    label(key) + "不是有限数值", value));
        }
        return decimal;
    }

    private static String label(String key) {
        String label = FIELD_LABELS.get(key);
        return label == null ? key : label;
    }

    private static final BigDecimal MIN_INT = BigDecimal.valueOf(Integer.MIN_VALUE);
    private static final BigDecimal MAX_INT = BigDecimal.valueOf(Integer.MAX_VALUE);

    /**
     * 数值 → BigDecimal。Jackson 默认把 JSON 浮点读成 {@code Double}，
     * 这里用 {@link BigDecimal#valueOf(double)}（取该 double 的最短十进制表示），
     * 因此用户写的 "60.10" 会还原成 60.1 而不是 60.100000000000001，
     * 2 位小数的判定才有意义；Infinity/NaN 一律判不合法。
     */
    private static BigDecimal toDecimal(Number number) {
        if (number instanceof BigDecimal decimal) {
            return decimal;
        }
        if (number instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (number instanceof Integer || number instanceof Long
                || number instanceof Short || number instanceof Byte) {
            return BigDecimal.valueOf(number.longValue());
        }
        double value = number.doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        return BigDecimal.valueOf(value);
    }

    /**
     * 回显被拒绝的值时只回显标量由 {@link PlanIssue} 的构造器统一负责，
     * 这里不再各写一遍（同一个策略写两处的话，早晚有一处漏掉）。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> source) {
        return (Map<String, Object>) source;
    }
}
