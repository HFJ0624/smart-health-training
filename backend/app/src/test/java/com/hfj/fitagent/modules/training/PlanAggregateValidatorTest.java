package com.hfj.fitagent.modules.training;

import com.hfj.fitagent.modules.training.dto.PlanDraftSpec;
import com.hfj.fitagent.modules.training.entity.ExerciseReferenceEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 聚合校验器的规则矩阵测试（docs/p1-plan-import.md §2–§8）。
 *
 * <p>这里覆盖的是"手工编辑与导入共用"的那一半规则（第 6–11 阶）：
 * 同一份实现，两个入口，所以用例只描述规则本身，不关心它是从 DTO 还是从 JSON 来的。
 *
 * <p>字典用 mock：校验器只关心"代码能不能命中、命中的是什么类别、是否 ACTIVE"。
 */
class PlanAggregateValidatorTest {

    private ExerciseDictionary dictionary;
    private PlanAggregateValidator validator;

    @BeforeEach
    void setUp() {
        dictionary = mock(ExerciseDictionary.class);
        validator = new PlanAggregateValidator(dictionary);
        stubDictionary(TrainingFixtures.squat(), TrainingFixtures.rowing(),
                TrainingFixtures.catCow(), TrainingFixtures.pushUp());
    }

    private void stubDictionary(ExerciseReferenceEntity... rows) {
        when(dictionary.loadByCodes(any())).thenReturn(TrainingFixtures.dictionaryOf(rows));
    }

    private PlanValidationOutcome validate(PlanDraftSpec spec) {
        return validator.validate(spec);
    }

    private static List<String> codes(PlanValidationOutcome outcome) {
        return outcome.errors().stream().map(PlanIssue::code).toList();
    }

    private static List<String> paths(PlanValidationOutcome outcome) {
        return outcome.errors().stream().map(PlanIssue::path).toList();
    }

    private static boolean has(PlanValidationOutcome outcome, String code) {
        return codes(outcome).contains(code);
    }

    // ==================== 类型 × 字段矩阵（§5） ====================

    @Nested
    @DisplayName("§5 类型×字段矩阵")
    class CategoryMatrix {

        @Test
        @DisplayName("STRENGTH 给时长 → STRENGTH_DURATION_FORBIDDEN")
        void strengthWithDuration() {
            PlanDraftSpec spec = TrainingFixtures.spec(TrainingFixtures.trainingDay(1,
                    TrainingFixtures.item(TrainingFixtures.SQUAT, null, 4, 8, 10,
                            new BigDecimal("60.00"), 30, 90)));

            assertTrue(has(validate(spec), PlanImportErrorCode.STRENGTH_DURATION_FORBIDDEN));
        }

        @Test
        @DisplayName("STRENGTH 缺组数 → REQUIRED")
        void strengthWithoutSets() {
            PlanDraftSpec spec = TrainingFixtures.spec(TrainingFixtures.trainingDay(1,
                    TrainingFixtures.item(TrainingFixtures.SQUAT, null, null, 8, 10, null, null, null)));

            assertTrue(has(validate(spec), PlanImportErrorCode.REQUIRED));
        }

        @Test
        @DisplayName("STRENGTH 合法（含负重 0=自重）→ 通过")
        void strengthWithBodyweightLoadZero() {
            PlanDraftSpec spec = TrainingFixtures.spec(TrainingFixtures.trainingDay(1,
                    TrainingFixtures.item(TrainingFixtures.PUSH_UP, null, 3, 10, 20,
                            BigDecimal.ZERO, null, 60)));

            assertFalse(validate(spec).hasErrors());
        }

        @Test
        @DisplayName("CARDIO 给组数/次数/负重/休息 → 四条 forbid 全报")
        void cardioWithEverythingForbidden() {
            PlanDraftSpec spec = TrainingFixtures.spec(TrainingFixtures.trainingDay(1,
                    TrainingFixtures.item(TrainingFixtures.ROWING, null, 3, 10, 20,
                            new BigDecimal("5.00"), 1200, 60)));

            PlanValidationOutcome outcome = validate(spec);
            List<String> codes = codes(outcome);
            assertTrue(codes.contains(PlanImportErrorCode.CARDIO_SETS_FORBIDDEN), () -> "实际=" + codes);
            assertTrue(codes.contains(PlanImportErrorCode.CARDIO_REPS_FORBIDDEN), () -> "实际=" + codes);
            assertTrue(codes.contains(PlanImportErrorCode.CARDIO_LOAD_FORBIDDEN), () -> "实际=" + codes);
            assertTrue(codes.contains(PlanImportErrorCode.CARDIO_REST_FORBIDDEN), () -> "实际=" + codes);
        }

        @Test
        @DisplayName("CARDIO 缺时长 → REQUIRED")
        void cardioWithoutDuration() {
            PlanDraftSpec spec = TrainingFixtures.spec(TrainingFixtures.trainingDay(1,
                    TrainingFixtures.item(TrainingFixtures.ROWING, null, null, null, null, null, null, null)));

            assertTrue(has(validate(spec), PlanImportErrorCode.REQUIRED));
        }

        @Test
        @DisplayName("CARDIO 只给时长 → 通过")
        void cardioDurationOnly() {
            assertFalse(validate(TrainingFixtures.spec(
                    TrainingFixtures.trainingDay(1, TrainingFixtures.cardio(TrainingFixtures.ROWING)))).hasErrors());
        }

        @Test
        @DisplayName("MOBILITY 没有任何训练量 → MOBILITY_NO_TARGET")
        void mobilityWithoutTarget() {
            PlanDraftSpec spec = TrainingFixtures.spec(TrainingFixtures.trainingDay(1,
                    TrainingFixtures.item(TrainingFixtures.CAT_COW, null, null, null, null, null, null, null)));

            assertTrue(has(validate(spec), PlanImportErrorCode.MOBILITY_NO_TARGET));
        }

        @Test
        @DisplayName("MOBILITY 给负重 → MOBILITY_LOAD_FORBIDDEN")
        void mobilityWithLoad() {
            PlanDraftSpec spec = TrainingFixtures.spec(TrainingFixtures.trainingDay(1,
                    TrainingFixtures.item(TrainingFixtures.CAT_COW, null, 2, 8, 10,
                            new BigDecimal("1.50"), null, 30)));

            assertTrue(has(validate(spec), PlanImportErrorCode.MOBILITY_LOAD_FORBIDDEN));
        }

        @Test
        @DisplayName("MOBILITY 组+次、以及时长两种写法都合法")
        void mobilityVariantsAccepted() {
            assertFalse(validate(TrainingFixtures.spec(TrainingFixtures.trainingDay(1,
                    TrainingFixtures.item(TrainingFixtures.CAT_COW, null, 2, 8, 10, null, null, 30)))).hasErrors());
            assertFalse(validate(TrainingFixtures.spec(TrainingFixtures.trainingDay(1,
                    TrainingFixtures.mobility(TrainingFixtures.CAT_COW)))).hasErrors());
        }

        @Test
        @DisplayName("字典里类别无法识别 → INVALID_ENUM_VALUE，不静默放过")
        void unknownCategoryInDictionary() {
            stubDictionary(TrainingFixtures.exercise(9L, "MYSTERY_MOVE", "YOGA"));
            PlanDraftSpec spec = TrainingFixtures.spec(TrainingFixtures.trainingDay(1,
                    TrainingFixtures.strength("MYSTERY_MOVE")));

            assertTrue(has(validate(spec), PlanImportErrorCode.INVALID_ENUM_VALUE));
        }
    }

    // ==================== 结构与上限（§3、§8） ====================

    @Nested
    @DisplayName("§3/§8 结构与上限")
    class Structure {

        @Test
        @DisplayName("同一星期出现两次 → DUPLICATE_WEEKDAY")
        void duplicateWeekday() {
            PlanDraftSpec spec = TrainingFixtures.spec(
                    TrainingFixtures.trainingDay(2, TrainingFixtures.strength(TrainingFixtures.SQUAT)),
                    TrainingFixtures.trainingDay(2, TrainingFixtures.cardio(TrainingFixtures.ROWING)));

            PlanValidationOutcome outcome = validate(spec);
            assertTrue(has(outcome, PlanImportErrorCode.DUPLICATE_WEEKDAY));
            assertEquals("days[1].iso_weekday", outcome.errors().get(0).path());
        }

        @Test
        @DisplayName("星期超出 1..7 → INVALID_WEEKDAY")
        void weekdayOutOfRange() {
            PlanDraftSpec spec = TrainingFixtures.spec(
                    TrainingFixtures.day(8, false, List.of(TrainingFixtures.strength(TrainingFixtures.SQUAT))));

            assertTrue(has(validate(spec), PlanImportErrorCode.INVALID_WEEKDAY));
        }

        @Test
        @DisplayName("休息日带动作 → REST_DAY_HAS_ITEMS")
        void restDayWithItems() {
            PlanDraftSpec spec = TrainingFixtures.spec(TrainingFixtures.day(3, true,
                    List.of(TrainingFixtures.strength(TrainingFixtures.SQUAT))));

            PlanValidationOutcome outcome = validate(spec);
            assertTrue(has(outcome, PlanImportErrorCode.REST_DAY_HAS_ITEMS));
            assertEquals("days[0].items", paths(outcome).get(0));
        }

        @Test
        @DisplayName("非休息日 0 个动作 → EMPTY_TRAINING_DAY")
        void emptyTrainingDay() {
            PlanDraftSpec spec = TrainingFixtures.spec(TrainingFixtures.day(1, false, List.of()));

            assertTrue(has(validate(spec), PlanImportErrorCode.EMPTY_TRAINING_DAY));
        }

        @Test
        @DisplayName("没有计划日 → days 必填")
        void noDays() {
            PlanValidationOutcome outcome = validate(new PlanDraftSpec("计划",
                    TrainingFixtures.STARTS_ON, null, null, List.of()));

            assertTrue(has(outcome, PlanImportErrorCode.REQUIRED));
            assertEquals("days", paths(outcome).get(0));
        }

        @Test
        @DisplayName("超过 7 个计划日 → TOO_MANY_DAYS")
        void tooManyDays() {
            List<PlanDraftSpec.DaySpec> days = new ArrayList<>();
            for (int weekday = 1; weekday <= 8; weekday++) {
                days.add(TrainingFixtures.day(weekday, true, List.of()));
            }

            assertTrue(has(validate(TrainingFixtures.spec(LocalDate.of(2027, 1, 1), days)),
                    PlanImportErrorCode.TOO_MANY_DAYS));
        }

        @Test
        @DisplayName("单日超过 30 个动作 → TOO_MANY_ITEMS")
        void tooManyItemsInDay() {
            List<PlanDraftSpec.ItemSpec> items = new ArrayList<>();
            for (int i = 0; i < 31; i++) {
                items.add(TrainingFixtures.strength(TrainingFixtures.SQUAT));
            }

            assertTrue(has(validate(TrainingFixtures.spec(TrainingFixtures.day(1, false, items))),
                    PlanImportErrorCode.TOO_MANY_ITEMS));
        }

        @Test
        @DisplayName("全计划动作总数超过 120 → TOO_MANY_ITEMS_TOTAL")
        void tooManyItemsTotal() {
            List<PlanDraftSpec.DaySpec> days = new ArrayList<>();
            for (int weekday = 1; weekday <= 7; weekday++) {
                List<PlanDraftSpec.ItemSpec> items = new ArrayList<>();
                for (int i = 0; i < 30; i++) {
                    items.add(TrainingFixtures.strength(TrainingFixtures.SQUAT));
                }
                days.add(TrainingFixtures.day(weekday, false, items));
            }

            assertTrue(has(validate(TrainingFixtures.spec(LocalDate.of(2027, 1, 1), days)),
                    PlanImportErrorCode.TOO_MANY_ITEMS_TOTAL));
        }

        @Test
        @DisplayName("同一日 position_no 重复 → DUPLICATE_POSITION")
        void duplicatePosition() {
            PlanDraftSpec spec = TrainingFixtures.spec(TrainingFixtures.trainingDay(1,
                    TrainingFixtures.item(TrainingFixtures.SQUAT, 1, 3, 8, 10, null, null, 90),
                    TrainingFixtures.item(TrainingFixtures.PUSH_UP, 1, 3, 10, 20, null, null, 60)));

            PlanValidationOutcome outcome = validate(spec);
            assertTrue(has(outcome, PlanImportErrorCode.DUPLICATE_POSITION));
            assertTrue(paths(outcome).contains("days[0].items[1].position_no"));
        }
    }

    // ==================== 数值与长度（§4、§8） ====================

    @Nested
    @DisplayName("§4 数值与长度")
    class Scalars {

        @Test
        @DisplayName("负重为负 → NEGATIVE_LOAD")
        void negativeLoad() {
            PlanDraftSpec spec = TrainingFixtures.spec(TrainingFixtures.trainingDay(1,
                    TrainingFixtures.item(TrainingFixtures.SQUAT, null, 3, 8, 10,
                            new BigDecimal("-1.00"), null, 90)));

            assertTrue(has(validate(spec), PlanImportErrorCode.NEGATIVE_LOAD));
        }

        @Test
        @DisplayName("负重超过 2 位小数 → TOO_MANY_DECIMALS")
        void tooManyDecimals() {
            PlanDraftSpec spec = TrainingFixtures.spec(TrainingFixtures.trainingDay(1,
                    TrainingFixtures.item(TrainingFixtures.SQUAT, null, 3, 8, 10,
                            new BigDecimal("60.005"), null, 90)));

            assertTrue(has(validate(spec), PlanImportErrorCode.TOO_MANY_DECIMALS));
        }

        @Test
        @DisplayName("组数/次数/时长必须为正 → MUST_BE_POSITIVE")
        void nonPositiveNumbers() {
            PlanDraftSpec spec = TrainingFixtures.spec(TrainingFixtures.trainingDay(1,
                    TrainingFixtures.item(TrainingFixtures.SQUAT, 0, 0, 0, 5, null, 0, 30)));

            List<String> codes = codes(validate(spec));
            assertEquals(4, codes.stream().filter(PlanImportErrorCode.MUST_BE_POSITIVE::equals).count(),
                    () -> "实际=" + codes);
        }

        @Test
        @DisplayName("休息秒数超出 0..3600 → REST_OUT_OF_RANGE")
        void restOutOfRange() {
            PlanDraftSpec longRest = TrainingFixtures.spec(TrainingFixtures.trainingDay(1,
                    TrainingFixtures.item(TrainingFixtures.SQUAT, null, 3, 8, 10, null, null, 3601)));
            PlanDraftSpec negativeRest = TrainingFixtures.spec(TrainingFixtures.trainingDay(1,
                    TrainingFixtures.item(TrainingFixtures.SQUAT, null, 3, 8, 10, null, null, -1)));

            assertTrue(has(validate(longRest), PlanImportErrorCode.REST_OUT_OF_RANGE));
            assertTrue(has(validate(negativeRest), PlanImportErrorCode.REST_OUT_OF_RANGE));
        }

        @Test
        @DisplayName("reps_max 小于 reps_min → REPS_MAX_LT_MIN，路径落在 reps_max")
        void repsMaxBelowMin() {
            PlanDraftSpec spec = TrainingFixtures.spec(TrainingFixtures.trainingDay(1,
                    TrainingFixtures.item(TrainingFixtures.SQUAT, null, 3, 12, 5, null, null, 90)));

            PlanValidationOutcome outcome = validate(spec);
            assertTrue(has(outcome, PlanImportErrorCode.REPS_MAX_LT_MIN));
            assertEquals("days[0].items[0].reps_max", paths(outcome).get(0));
        }

        @Test
        @DisplayName("字符串长度上限：计划名 160、当日主题 120、备注 800")
        void stringLengthCaps() {
            PlanDraftSpec longTitle = new PlanDraftSpec("名".repeat(161), TrainingFixtures.STARTS_ON,
                    null, null, List.of(TrainingFixtures.restDay(1)));
            PlanValidationOutcome titleOutcome = validate(longTitle);
            assertTrue(has(titleOutcome, PlanImportErrorCode.TOO_LONG));
            assertEquals("title", paths(titleOutcome).get(0));

            PlanDraftSpec.DaySpec longDayTitle = new PlanDraftSpec.DaySpec(1, "题".repeat(121), true, null, List.of());
            assertTrue(has(validate(new PlanDraftSpec("计划", TrainingFixtures.STARTS_ON, null, null,
                    List.of(longDayTitle))), PlanImportErrorCode.TOO_LONG));

            PlanDraftSpec.DaySpec longNote = new PlanDraftSpec.DaySpec(1, null, true, "注".repeat(801), List.of());
            assertTrue(has(validate(new PlanDraftSpec("计划", TrainingFixtures.STARTS_ON, null, null,
                    List.of(longNote))), PlanImportErrorCode.TOO_LONG));
        }

        @Test
        @DisplayName("结束日期早于开始日期 → ENDS_BEFORE_STARTS")
        void endsBeforeStarts() {
            PlanDraftSpec spec = new PlanDraftSpec("计划", LocalDate.of(2026, 10, 12),
                    LocalDate.of(2026, 10, 11), null, List.of(TrainingFixtures.restDay(1)));

            assertTrue(has(validate(spec), PlanImportErrorCode.ENDS_BEFORE_STARTS));
        }
    }

    // ==================== 字典匹配（§4、§8） ====================

    @Nested
    @DisplayName("§4 字典匹配")
    class Dictionary {

        @Test
        @DisplayName("字典外的动作代码 → EXERCISE_NOT_FOUND，路径精确到 items[j]")
        void unknownCode() {
            PlanDraftSpec spec = TrainingFixtures.spec(
                    TrainingFixtures.restDay(1),
                    TrainingFixtures.restDay(2),
                    TrainingFixtures.trainingDay(4, TrainingFixtures.strength("BENCHMARK_PRESS")));

            PlanValidationOutcome outcome = validate(spec);
            assertTrue(has(outcome, PlanImportErrorCode.EXERCISE_NOT_FOUND));
            assertEquals("days[2].items[0].exercise_code", paths(outcome).get(0));
            assertEquals("BENCHMARK_PRESS", outcome.errors().get(0).value());
        }

        @Test
        @DisplayName("字典里存在但已停用 → EXERCISE_DISABLED（与「不存在」区分开）")
        void disabledCode() {
            stubDictionary(TrainingFixtures.exercise(1L, TrainingFixtures.SQUAT, "STRENGTH", "DISABLED"));
            PlanDraftSpec spec = TrainingFixtures.spec(
                    TrainingFixtures.trainingDay(1, TrainingFixtures.strength(TrainingFixtures.SQUAT)));

            assertTrue(has(validate(spec), PlanImportErrorCode.EXERCISE_DISABLED));
        }

        @Test
        @DisplayName("结构层有错时不查字典：避免半量校验产生误导性错误")
        void structuralFailureShortCircuitsDictionary() {
            PlanDraftSpec spec = TrainingFixtures.spec(TrainingFixtures.day(1, false, List.of()));

            PlanValidationOutcome outcome = validate(spec);

            assertTrue(has(outcome, PlanImportErrorCode.EMPTY_TRAINING_DAY));
            verifyNoInteractions(dictionary);
        }
    }

    // ==================== 规范化结果 ====================

    @Nested
    @DisplayName("规范化输出")
    class Normalization {

        @Test
        @DisplayName("position_no 不连续 → POSITION_RENUMBERED 警告，并且落库顺序重排为 1..n")
        void renumberedPositions() {
            PlanDraftSpec spec = TrainingFixtures.spec(TrainingFixtures.trainingDay(1,
                    TrainingFixtures.item(TrainingFixtures.SQUAT, 5, 3, 8, 10, null, null, 90),
                    TrainingFixtures.item(TrainingFixtures.PUSH_UP, 9, 3, 10, 20, null, null, 60)));

            PlanValidationOutcome outcome = validate(spec);

            assertFalse(outcome.hasErrors());
            assertEquals(1, outcome.warnings().size());
            assertEquals(PlanImportErrorCode.POSITION_RENUMBERED, outcome.warnings().get(0).code());
            assertEquals("days[0].items", outcome.warnings().get(0).path());
            assertEquals(List.of(1, 2),
                    outcome.days().get(0).items().stream().map(PlanValidationOutcome.ResolvedItem::positionNo).toList());
        }

        @Test
        @DisplayName("顺序连续时不给警告")
        void contiguousPositionsNoWarning() {
            PlanValidationOutcome outcome = validate(TrainingFixtures.spec(TrainingFixtures.trainingDay(1,
                    TrainingFixtures.item(TrainingFixtures.SQUAT, 1, 3, 8, 10, null, null, 90),
                    TrainingFixtures.item(TrainingFixtures.PUSH_UP, 2, 3, 10, 20, null, null, 60))));

            assertTrue(outcome.warnings().isEmpty());
        }

        @Test
        @DisplayName("休息日也要进规范化结果：否则页面上的休息卡片会消失")
        void restDayIsResolvedWithEmptyItems() {
            PlanValidationOutcome outcome = validate(TrainingFixtures.spec(
                    TrainingFixtures.trainingDay(1, TrainingFixtures.strength(TrainingFixtures.SQUAT)),
                    TrainingFixtures.restDay(3)));

            assertFalse(outcome.hasErrors());
            assertEquals(2, outcome.days().size());
            PlanValidationOutcome.ResolvedDay rest = outcome.days().get(1);
            assertEquals(3, rest.isoWeekday());
            assertTrue(rest.isRestDay());
            assertTrue(rest.items().isEmpty());
        }

        @Test
        @DisplayName("字典行随结果一起给出，落库只读它（id/名称/类别）")
        void resolvedCarriesDictionaryRow() {
            PlanValidationOutcome outcome = validate(TrainingFixtures.spec(
                    TrainingFixtures.trainingDay(1, TrainingFixtures.strength(TrainingFixtures.SQUAT))));

            PlanValidationOutcome.ResolvedItem resolved = outcome.days().get(0).items().get(0);
            assertEquals(TrainingFixtures.squat().getId(), resolved.exercise().getId());
            assertEquals(TrainingFixtures.SQUAT, resolved.exercise().getExerciseCode());
            assertEquals("STRENGTH", resolved.exercise().getCategory());
        }
    }

    // ==================== 手工侧字段错误映射 ====================

    @Test
    @DisplayName("同一份发现映射到 camelCase 字段路径，供手工接口的 VALIDATION_FAILED 使用")
    void dtoFieldMappingIsCamelCase() {
        assertEquals("days[2].items[0].targetExternalLoadKg", PlanIssue.of(
                "days[2].items[0].target_external_load_kg", PlanImportErrorCode.NEGATIVE_LOAD, "m", null).dtoField());
        assertEquals("days[0].items[1].repsMax",
                PlanIssue.of("days[0].items[1].reps_max", "X", "m").dtoField());
        assertEquals("days[1].isRestDay",
                PlanIssue.of("days[1].is_rest_day", "X", "m").dtoField());
        assertEquals("title", PlanIssue.of("title", "X", "m").dtoField());
    }

    @Test
    @DisplayName("结构型值不回显整份文档，只给类型标记")
    void structuredValuesAreCollapsed() {
        PlanIssue issue = PlanIssue.of("days", PlanImportErrorCode.INVALID_TYPE, "days 必须是数组",
                List.of(Map.of("items", List.of())));

        assertEquals("[array]", issue.value());
    }
}
