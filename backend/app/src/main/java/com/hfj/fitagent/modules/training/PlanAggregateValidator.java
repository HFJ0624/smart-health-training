package com.hfj.fitagent.modules.training;

import com.hfj.fitagent.common.web.FieldError;
import com.hfj.fitagent.modules.training.dto.PlanDraftSpec;
import com.hfj.fitagent.modules.training.entity.ExerciseReferenceEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.hfj.fitagent.modules.training.PlanImportErrorCode.CARDIO_LOAD_FORBIDDEN;
import static com.hfj.fitagent.modules.training.PlanImportErrorCode.CARDIO_REPS_FORBIDDEN;
import static com.hfj.fitagent.modules.training.PlanImportErrorCode.CARDIO_REST_FORBIDDEN;
import static com.hfj.fitagent.modules.training.PlanImportErrorCode.CARDIO_SETS_FORBIDDEN;
import static com.hfj.fitagent.modules.training.PlanImportErrorCode.DUPLICATE_POSITION;
import static com.hfj.fitagent.modules.training.PlanImportErrorCode.DUPLICATE_WEEKDAY;
import static com.hfj.fitagent.modules.training.PlanImportErrorCode.MOBILITY_LOAD_FORBIDDEN;

/**
 * 训练计划聚合的唯一规则源：手工新建/编辑与 JSON 导入<b>共用</b>这一套规则。
 *
 * <p>规则来源：docs/p1-plan-import.md §2–§8（字段规则、上限、类型×字段矩阵、校验顺序）、
 * docs/p1-database.md §3.6–§3.8（列容量与数据库 CHECK 能表达的部分）、
 * docs/p1-api.md §7（聚合结构）。
 *
 * <p>顺序（§8 的短路策略）：6 标量范围与长度 → 7 days 结构 → 8 position_no → 9 字典匹配
 * → 10 类型×字段矩阵 → 11 跨字段。结构层（6–8）一旦有错就不再查字典：
 * 半份校验只会产生误导性错误（比如 items 结构已经崩了，还去报"动作不存在"）。
 *
 * <p>本类不做"类型/必填/键名"检查——那是导入解析器（{@code PlanImportParsers}）与
 * Bean Validation 的职责；两者相加就是 §8 的 1–5 阶。
 * 反过来说，凡是"两个入口都必须成立"的业务规则都只在这里写一遍。
 */
@Component
public class PlanAggregateValidator {

    // ---- §8 上限表 ----
    public static final int MAX_DAYS = 7;
    public static final int MAX_ITEMS_PER_DAY = 30;
    public static final int MAX_ITEMS_TOTAL = 120;

    // ---- §2/§3/§4 字符串长度 ----
    static final int PLAN_TITLE_MAX = 160;
    static final int DAY_TITLE_MAX = 120;
    static final int NOTES_MAX = 800;

    // ---- §4 数值范围 ----
    static final int MAX_REST_SECONDS = 3600;
    static final int MAX_LOAD_DECIMALS = 2;

    // ---- 列容量（docs/p1-database.md §3.8），超出即数据库写入失败，提前以字段错误拒绝 ----
    static final int MAX_SMALLINT_UNSIGNED = 65535;
    static final long MAX_INT_UNSIGNED = 4294967295L;
    static final BigDecimal MAX_LOAD_KG = new BigDecimal("999999.99");

    static final String WEEKDAY_LABELS = "一二三四五六日";

    private final ExerciseDictionary dictionary;

    public PlanAggregateValidator(ExerciseDictionary dictionary) {
        this.dictionary = dictionary;
    }

    /**
     * 完整校验一个聚合草案。
     *
     * @param spec 归一化后的提交模型（手工 DTO 或导入解析结果）
     * @return 错误为空时 {@code days()} 给出服务端规范化后的聚合，落库只读它
     */
    public PlanValidationOutcome validate(PlanDraftSpec spec) {
        List<PlanIssue> warnings = structureWarnings(spec);
        List<PlanIssue> errors = new ArrayList<>(scalarRules(spec));
        errors.addAll(structureRules(spec));
        if (!errors.isEmpty()) {
            // 结构层失败：不做字典查询（§8 短路策略）
            return PlanValidationOutcome.failed(errors, warnings);
        }

        Map<String, ExerciseReferenceEntity> byCode = dictionary.loadByCodes(collectCodes(spec));

        List<PlanValidationOutcome.ResolvedDay> resolved = new ArrayList<>();
        for (int i = 0; i < spec.days().size(); i++) {
            PlanDraftSpec.DaySpec day = spec.days().get(i);
            List<PlanValidationOutcome.ResolvedItem> items = new ArrayList<>();
            boolean restDay = Boolean.TRUE.equals(day.isRestDay());
            List<PlanDraftSpec.ItemSpec> rawItems = day.items() == null ? List.of() : day.items();
            for (int j = 0; j < rawItems.size() && !restDay; j++) {
                PlanDraftSpec.ItemSpec item = rawItems.get(j);
                String prefix = "days[" + i + "].items[" + j + "]";
                ExerciseReferenceEntity exercise = byCode.get(item.exerciseCode());
                if (exercise == null) {
                    errors.add(PlanIssue.of(prefix + ".exercise_code", PlanImportErrorCode.EXERCISE_NOT_FOUND,
                            "动作代码不在有效动作字典中：" + item.exerciseCode(), item.exerciseCode()));
                    continue;
                }
                if (!ExerciseDictionary.STATUS_ACTIVE.equals(exercise.getStatus())) {
                    errors.add(PlanIssue.of(prefix + ".exercise_code", PlanImportErrorCode.EXERCISE_DISABLED,
                            "动作已停用，不能用于新计划：" + exercise.getExerciseCode(), exercise.getExerciseCode()));
                    continue;
                }
                errors.addAll(categoryMatrix(prefix, ExerciseCategory.parseStrict(exercise.getCategory()), item));
                errors.addAll(itemCrossFields(prefix, item));
                items.add(new PlanValidationOutcome.ResolvedItem(j + 1, exercise, item.targetSets(),
                        item.repsMin(), item.repsMax(), item.targetExternalLoadKg(),
                        item.targetDurationSeconds(), item.restSeconds(), item.notes()));
            }
            // 休息日同样要落库（is_rest_day=true 且条目为空），否则页面上的"休息"卡片会丢
            resolved.add(new PlanValidationOutcome.ResolvedDay(day.isoWeekday(), day.title(),
                    restDay, day.notes(), items));
        }
        if (spec.endsOn() != null && spec.startsOn() != null && spec.endsOn().isBefore(spec.startsOn())) {
            errors.add(PlanIssue.of("ends_on", PlanImportErrorCode.ENDS_BEFORE_STARTS,
                    "结束日期不能早于开始日期", spec.endsOn().toString()));
        }
        if (!errors.isEmpty()) {
            return PlanValidationOutcome.failed(errors, warnings);
        }
        return new PlanValidationOutcome(List.of(), warnings, resolved);
    }

    // ==================== 阶段 6：长度与数值范围 ====================

    private List<PlanIssue> scalarRules(PlanDraftSpec spec) {
        List<PlanIssue> errors = new ArrayList<>();
        if (isBlank(spec.title())) {
            errors.add(PlanIssue.of("title", PlanImportErrorCode.REQUIRED, "计划名称必填"));
        } else if (spec.title().trim().length() > PLAN_TITLE_MAX) {
            errors.add(PlanIssue.of("title", PlanImportErrorCode.TOO_LONG,
                    "计划名称不得超过 " + PLAN_TITLE_MAX + " 字符", spec.title()));
        }
        if (spec.startsOn() == null) {
            errors.add(PlanIssue.of("starts_on", PlanImportErrorCode.REQUIRED, "开始日期必填"));
        }
        // P1 的 training_plan 没有备注列，所以顶层 notes 一律拒收，而不是"接受后悄悄丢掉"。
        // 手工创建路径（PlanUpsertRequest）本来就没有这个字段，两条入口的形状由此保持一致。
        // 计划日与动作条目的 notes 有真实列，不受影响。
        if (spec.notes() != null && !spec.notes().isBlank()) {
            errors.add(PlanIssue.of("notes", PlanImportErrorCode.PLAN_NOTES_UNSUPPORTED,
                    "P1 的训练计划没有备注列，请不要在 JSON 里写 notes（计划日与动作的 notes 支持）", spec.notes()));
        }

        List<PlanDraftSpec.DaySpec> days = spec.days();
        if (days == null || days.isEmpty()) {
            return errors;
        }
        int total = 0;
        for (int i = 0; i < days.size(); i++) {
            PlanDraftSpec.DaySpec day = days.get(i);
            String dp = "days[" + i + "]";
            Integer weekday = day.isoWeekday();
            if (weekday == null) {
                errors.add(PlanIssue.of(dp + ".iso_weekday", PlanImportErrorCode.REQUIRED, "星期必填（1=周一…7=周日）"));
            } else if (weekday < 1 || weekday > 7) {
                errors.add(PlanIssue.of(dp + ".iso_weekday", PlanImportErrorCode.INVALID_WEEKDAY,
                        "星期必须是 ISO 口径 1..7（1=周一）", weekday));
            }
            tooLong(errors, dp + ".title", day.title(), DAY_TITLE_MAX, "当日主题");
            tooLong(errors, dp + ".notes", day.notes(), NOTES_MAX, "当日备注");
            if (day.isRestDay() == null) {
                errors.add(PlanIssue.of(dp + ".is_rest_day", PlanImportErrorCode.REQUIRED, "是否为休息日必填"));
            }
            List<PlanDraftSpec.ItemSpec> items = day.items();
            if (items == null) {
                errors.add(PlanIssue.of(dp + ".items", PlanImportErrorCode.REQUIRED, "动作列表必填（休息日给空数组）"));
                continue;
            }
            total += items.size();
            for (int j = 0; j < items.size(); j++) {
                itemScalars(errors, dp + ".items[" + j + "]", items.get(j));
            }
        }
        return errors;
    }

    private void itemScalars(List<PlanIssue> errors, String prefix, PlanDraftSpec.ItemSpec item) {
        if (isBlank(item.exerciseCode())) {
            errors.add(PlanIssue.of(prefix + ".exercise_code", PlanImportErrorCode.REQUIRED, "动作代码必填"));
        }
        positive(errors, prefix + ".position_no", item.positionNo(), MAX_SMALLINT_UNSIGNED, "动作顺序");
        positive(errors, prefix + ".target_sets", item.targetSets(), MAX_SMALLINT_UNSIGNED, "组数");
        positive(errors, prefix + ".reps_min", item.repsMin(), MAX_SMALLINT_UNSIGNED, "次数下界");
        positive(errors, prefix + ".reps_max", item.repsMax(), MAX_SMALLINT_UNSIGNED, "次数上界");
        positive(errors, prefix + ".target_duration_seconds", item.targetDurationSeconds(),
                MAX_INT_UNSIGNED, "时长秒数");

        BigDecimal load = item.targetExternalLoadKg();
        if (load != null) {
            if (load.signum() < 0) {
                errors.add(PlanIssue.of(prefix + ".target_external_load_kg", PlanImportErrorCode.NEGATIVE_LOAD,
                        "外部负重不能为负；自重动作记 0", load));
            } else if (load.stripTrailingZeros().scale() > MAX_LOAD_DECIMALS) {
                errors.add(PlanIssue.of(prefix + ".target_external_load_kg", PlanImportErrorCode.TOO_MANY_DECIMALS,
                        "外部负重最多 " + MAX_LOAD_DECIMALS + " 位小数", load));
            } else if (load.compareTo(MAX_LOAD_KG) > 0) {
                errors.add(PlanIssue.of(prefix + ".target_external_load_kg", PlanImportErrorCode.OUT_OF_RANGE,
                        "外部负重不得超过 " + MAX_LOAD_KG + " kg", load));
            }
        }

        Integer rest = item.restSeconds();
        if (rest != null && (rest < 0 || rest > MAX_REST_SECONDS)) {
            errors.add(PlanIssue.of(prefix + ".rest_seconds", PlanImportErrorCode.REST_OUT_OF_RANGE,
                    "组间休息必须在 0.." + MAX_REST_SECONDS + " 秒之间", rest));
        }
        tooLong(errors, prefix + ".notes", item.notes(), NOTES_MAX, "动作备注");
    }

    // ==================== 阶段 7–8：结构、顺序 ====================

    private List<PlanIssue> structureRules(PlanDraftSpec spec) {
        List<PlanIssue> errors = new ArrayList<>();
        List<PlanDraftSpec.DaySpec> days = spec.days();
        if (days == null || days.isEmpty()) {
            errors.add(PlanIssue.of("days", PlanImportErrorCode.REQUIRED, "至少安排 1 个计划日"));
            return errors;
        }
        if (days.size() > MAX_DAYS) {
            errors.add(PlanIssue.of("days", PlanImportErrorCode.TOO_MANY_DAYS,
                    "一个计划最多 " + MAX_DAYS + " 个计划日", days.size()));
        }
        Set<Integer> seenWeekdays = new HashSet<>();
        int total = 0;
        for (int i = 0; i < days.size(); i++) {
            PlanDraftSpec.DaySpec day = days.get(i);
            String dp = "days[" + i + "]";
            Integer weekday = day.isoWeekday();
            if (weekday != null && weekday >= 1 && weekday <= 7 && !seenWeekdays.add(weekday)) {
                errors.add(PlanIssue.of(dp + ".iso_weekday", PlanImportErrorCode.DUPLICATE_WEEKDAY,
                        "同一计划里每个星期最多一个计划日", weekday));
            }
            List<PlanDraftSpec.ItemSpec> items = day.items();
            if (items == null) {
                continue;
            }
            total += items.size();
            boolean restDay = Boolean.TRUE.equals(day.isRestDay());
            if (restDay && !items.isEmpty()) {
                errors.add(PlanIssue.of(dp + ".items", PlanImportErrorCode.REST_DAY_HAS_ITEMS,
                        "休息日不能配置动作", items.size()));
            } else if (!restDay && items.isEmpty()) {
                errors.add(PlanIssue.of(dp + ".items", PlanImportErrorCode.EMPTY_TRAINING_DAY,
                        "非休息日至少要安排 1 个动作"));
            }
            if (items.size() > MAX_ITEMS_PER_DAY) {
                errors.add(PlanIssue.of(dp + ".items", PlanImportErrorCode.TOO_MANY_ITEMS,
                        "单日最多 " + MAX_ITEMS_PER_DAY + " 个动作", items.size()));
            }
            errors.addAll(positionRules(dp, items));
        }
        if (total > MAX_ITEMS_TOTAL) {
            errors.add(PlanIssue.of("days", PlanImportErrorCode.TOO_MANY_ITEMS_TOTAL,
                    "整个计划最多 " + MAX_ITEMS_TOTAL + " 个动作", total));
        }
        return errors;
    }

    /**
     * position_no 只校验"给定值不重复"；最终顺序一律按数组下标重排为 1..n，
     * 给定值与重排结果不一致时给一条 {@code POSITION_RENUMBERED} 警告（§4：缺省按数组顺序赋值）。
     */
    private List<PlanIssue> positionRules(String dayPath, List<PlanDraftSpec.ItemSpec> items) {
        List<PlanIssue> errors = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (int j = 0; j < items.size(); j++) {
            Integer given = items.get(j).positionNo();
            if (given == null) {
                continue;
            }
            if (given < 1 || given > MAX_SMALLINT_UNSIGNED) {
                continue;   // 已由 scalarRules 报过
            }
            if (!seen.add(given)) {
                errors.add(PlanIssue.of(dayPath + ".items[" + j + "].position_no", DUPLICATE_POSITION,
                        "同一日内的动作顺序不能重复", given));
            }
        }
        return errors;
    }

    /** 与错误分开收集：警告不阻断落库，但必须原样回显给用户。 */
    private List<PlanIssue> structureWarnings(PlanDraftSpec spec) {
        List<PlanIssue> warnings = new ArrayList<>();
        List<PlanDraftSpec.DaySpec> days = spec.days();
        if (days == null) {
            return warnings;
        }
        for (int i = 0; i < days.size(); i++) {
            List<PlanDraftSpec.ItemSpec> items = days.get(i).items();
            if (items == null || items.isEmpty()) {
                continue;
            }
            boolean renumbered = false;
            for (int j = 0; j < items.size(); j++) {
                Integer given = items.get(j).positionNo();
                if (given != null && given != j + 1) {
                    renumbered = true;
                    break;
                }
            }
            if (renumbered) {
                warnings.add(PlanIssue.of("days[" + i + "].items", PlanImportErrorCode.POSITION_RENUMBERED,
                        "position_no 不连续，已按数组顺序重排为 1..n"));
            }
        }
        return warnings;
    }

    // ==================== 阶段 10：类型 × 字段矩阵（§5，逐字实现） ====================

    private List<PlanIssue> categoryMatrix(String prefix, ExerciseCategory category, PlanDraftSpec.ItemSpec item) {
        List<PlanIssue> errors = new ArrayList<>();
        if (category == null) {
            errors.add(PlanIssue.of(prefix + ".exercise_code", PlanImportErrorCode.INVALID_ENUM_VALUE,
                    "字典里的动作类别无法识别：" + item.exerciseCode(), null));
            return errors;
        }
        switch (category) {
            case STRENGTH -> {
                if (item.targetSets() == null) {
                    errors.add(PlanIssue.of(prefix + ".target_sets", PlanImportErrorCode.REQUIRED,
                            "力量动作必须设定组数"));
                }
                if (item.targetDurationSeconds() != null) {
                    errors.add(PlanIssue.of(prefix + ".target_duration_seconds",
                            PlanImportErrorCode.STRENGTH_DURATION_FORBIDDEN,
                            "力量动作不按时长设定，请改用组数与次数", item.targetDurationSeconds()));
                }
            }
            case CARDIO -> {
                if (item.targetDurationSeconds() == null) {
                    errors.add(PlanIssue.of(prefix + ".target_duration_seconds", PlanImportErrorCode.REQUIRED,
                            "有氧动作必须设定时长（秒）"));
                }
                if (item.targetSets() != null) {
                    errors.add(PlanIssue.of(prefix + ".target_sets", CARDIO_SETS_FORBIDDEN,
                            "有氧动作没有「组」的概念，请删除组数", item.targetSets()));
                }
                if (item.repsMin() != null || item.repsMax() != null) {
                    errors.add(PlanIssue.of(item.repsMin() != null
                            ? prefix + ".reps_min" : prefix + ".reps_max", CARDIO_REPS_FORBIDDEN,
                            "有氧动作不记次数，请改用时长", item.repsMin() != null ? item.repsMin() : item.repsMax()));
                }
                if (item.targetExternalLoadKg() != null) {
                    errors.add(PlanIssue.of(prefix + ".target_external_load_kg", CARDIO_LOAD_FORBIDDEN,
                            "有氧动作不记外部负重，请删除该字段", item.targetExternalLoadKg()));
                }
                if (item.restSeconds() != null) {
                    errors.add(PlanIssue.of(prefix + ".rest_seconds", CARDIO_REST_FORBIDDEN,
                            "有氧动作不存在组间休息，请删除该字段", item.restSeconds()));
                }
            }
            case MOBILITY -> {
                if (item.targetExternalLoadKg() != null) {
                    errors.add(PlanIssue.of(prefix + ".target_external_load_kg", MOBILITY_LOAD_FORBIDDEN,
                            "柔韧动作不记外部负重，请删除该字段", item.targetExternalLoadKg()));
                }
                boolean hasTarget = item.targetSets() != null || item.repsMin() != null
                        || item.repsMax() != null || item.targetDurationSeconds() != null;
                if (!hasTarget) {
                    errors.add(PlanIssue.of(prefix, PlanImportErrorCode.MOBILITY_NO_TARGET,
                            "柔韧动作至少要给一项训练量（组数、次数或时长）"));
                }
            }
        }
        return errors;
    }

    // ==================== 阶段 11：跨字段 ====================

    private List<PlanIssue> itemCrossFields(String prefix, PlanDraftSpec.ItemSpec item) {
        List<PlanIssue> errors = new ArrayList<>();
        if (item.repsMin() != null && item.repsMax() != null && item.repsMax() < item.repsMin()) {
            errors.add(PlanIssue.of(prefix + ".reps_max", PlanImportErrorCode.REPS_MAX_LT_MIN,
                    "次数上界不能小于下界", item.repsMax()));
        }
        return errors;
    }

    private Set<String> collectCodes(PlanDraftSpec spec) {
        Set<String> codes = new LinkedHashSet<>();
        for (PlanDraftSpec.DaySpec day : spec.days()) {
            if (day.items() == null) {
                continue;
            }
            for (PlanDraftSpec.ItemSpec item : day.items()) {
                if (!isBlank(item.exerciseCode())) {
                    codes.add(item.exerciseCode());
                }
            }
        }
        return codes;
    }

    // ==================== 小工具 ====================

    private static void tooLong(List<PlanIssue> errors, String path, String value, int max, String label) {
        if (value != null && value.length() > max) {
            errors.add(PlanIssue.of(path, PlanImportErrorCode.TOO_LONG,
                    label + "不得超过 " + max + " 字符", value));
        }
    }

    private static void positive(List<PlanIssue> errors, String path, Integer value, long max, String label) {
        if (value == null) {
            return;
        }
        if (value < 1) {
            errors.add(PlanIssue.of(path, PlanImportErrorCode.MUST_BE_POSITIVE,
                    label + "必须为正整数", value));
        } else if (value > max) {
            // max 用 long：target_duration_seconds 是 INT UNSIGNED，用 int 承接会溢出成 -1，
            // 于是所有合法时长都会被误判成超限
            errors.add(PlanIssue.of(path, PlanImportErrorCode.OUT_OF_RANGE,
                    label + "不得超过 " + max, value));
        }
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** ISO 星期 → 中文标签，供导入预览与页面显示；输入已由校验器保证在 1..7。 */
    public static String weekdayLabel(int isoWeekday) {
        if (isoWeekday < 1 || isoWeekday > 7) {
            return Integer.toString(isoWeekday);
        }
        return "周" + WEEKDAY_LABELS.charAt(isoWeekday - 1);
    }

    /** 供 Service 复用：把错误列表映射成 VALIDATION_FAILED 的字段错误。 */
    public static List<FieldError> toFieldErrors(List<PlanIssue> issues) {
        List<FieldError> fields = new ArrayList<>(issues.size());
        for (PlanIssue issue : issues) {
            fields.add(issue.toFieldError());
        }
        return fields;
    }
}
