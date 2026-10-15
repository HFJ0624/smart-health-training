package com.hfj.fitagent.modules.training;

import com.hfj.fitagent.common.IdempotencyService;
import com.hfj.fitagent.modules.training.entity.ExerciseReferenceEntity;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.training.vo.ImportConfirmVo;
import com.hfj.fitagent.modules.training.vo.ImportIssueVo;
import com.hfj.fitagent.modules.training.vo.ImportIssuesVo;
import com.hfj.fitagent.modules.training.vo.ImportPreviewVo;
import com.hfj.fitagent.modules.training.vo.PlanListVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 导入链路测试：字节上限 → 解析 → 校验 → 预览 → 幂等确认。
 *
 * <p>这里用<b>真实的解析器 + 真实的聚合校验器</b>，只 mock 字典、计划写入与幂等存储：
 * 导入的价值全在"整条流水线按 docs/p1-plan-import.md §8 的顺序判定"，
 * 把校验器也 mock 掉就只剩测桩了。
 *
 * <p>验收 A16（模板真实可导入）在单元层的等价断言：把交付模板从磁盘读出来走同一条流水线，
 * 必须<b>零错误零警告</b>——模板与规则一旦漂移，这个用例先红。
 */
class PlanImportServiceTest {

    private static final String TEMPLATE_RESOURCE = "docs/templates/training-plan-template.v1.json";
    private static final long USER_ID = TrainingFixtures.USER_ID;
    private static final String CANONICAL_KEY = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private ExerciseDictionary dictionary;
    private TrainingPlanService planService;
    private IdempotencyService idempotency;
    private PlanImportService service;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        dictionary = mock(ExerciseDictionary.class);
        // 类别必须与 V9 种子一致：类别决定校验矩阵，抄错就是在测一条不存在的规则
        when(dictionary.loadByCodes(any())).thenReturn(TrainingFixtures.dictionaryOf(
                seed("BARBELL_BACK_SQUAT", "STRENGTH"),
                seed("BARBELL_BENCH_PRESS", "STRENGTH"),
                seed("BARBELL_OVERHEAD_PRESS", "STRENGTH"),
                seed("DUMBBELL_ROW", "STRENGTH"),
                seed("LEG_PRESS", "STRENGTH"),
                seed("PUSH_UP", "STRENGTH"),
                seed("LAT_PULLDOWN", "STRENGTH"),
                seed("ROWING", "CARDIO"),
                seed("TREADMILL_WALK_INCLINE", "CARDIO"),
                seed("CAT_COW", "MOBILITY"),
                seed("HAMSTRING_STRETCH", "MOBILITY"),
                seed("HIP_FLEXOR_STRETCH", "MOBILITY")));
        planService = mock(TrainingPlanService.class);
        idempotency = mock(IdempotencyService.class);
        service = new PlanImportService(new PlanImportParser(mapper),
                new PlanAggregateValidator(dictionary), planService, idempotency);
    }

    private static ExerciseReferenceEntity seed(String code, String category) {
        return TrainingFixtures.exercise(code.hashCode() % 900 + 100L, code, category);
    }

    // ==================== 断言与夹具小工具 ====================

    private BizException previewFailure(String content) {
        return assertThrows(BizException.class, () -> service.preview(USER_ID, content));
    }

    private static List<ImportIssueVo> errorsOf(BizException e) {
        assertTrue(e.payload() instanceof ImportIssuesVo, "导入失败的 data 必须是 {errors, warnings}");
        return ((ImportIssuesVo) e.payload()).errors();
    }

    private static boolean hasCode(BizException e, String code) {
        return errorsOf(e).stream().anyMatch(issue -> code.equals(issue.code()));
    }

    private static ImportIssueVo find(BizException e, String code) {
        return errorsOf(e).stream().filter(issue -> code.equals(issue.code())).findFirst().orElse(null);
    }

    /** 在仓库里向上查找模板文件：测试的工作目录可能是 backend/app，也可能是仓库根。 */
    private static String readTemplate() {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 6 && dir != null; depth++, dir = dir.getParent()) {
            Path candidate = dir.resolve(TEMPLATE_RESOURCE);
            if (Files.isRegularFile(candidate)) {
                try {
                    // 显式 UTF-8：本机平台编码是 GBK，不写字符集的读法会把中文备注读成乱码
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new IllegalStateException("找不到模板文件：" + TEMPLATE_RESOURCE);
    }

    private static String doc(String daysJson) {
        return """
                {
                  "schema_version": "fitagent.training_plan/1",
                  "title": "测试计划",
                  "starts_on": "2026-10-12",
                  "ends_on": null,
                  "notes": null,
                  "days": [%s]
                }
                """.formatted(daysJson);
    }

    private static String trainingDay(String items) {
        return """
                { "iso_weekday": 1, "title": "训练日", "is_rest_day": false, "notes": null, "items": [%s] }
                """.formatted(items);
    }

    private static String restDay(int isoWeekday) {
        return """
                { "iso_weekday": %d, "title": "休息", "is_rest_day": true, "notes": null, "items": [] }
                """.formatted(isoWeekday);
    }

    private static String strengthItem(String code) {
        return """
                { "position_no": 1, "exercise_code": "%s", "target_sets": 3, "reps_min": 8, "reps_max": 10,
                  "target_external_load_kg": 60.0, "target_duration_seconds": null, "rest_seconds": 90,
                  "notes": null }
                """.formatted(code);
    }

    private static String cardioItem(String code) {
        return """
                { "position_no": 1, "exercise_code": "%s", "target_sets": null, "reps_min": null, "reps_max": null,
                  "target_external_load_kg": null, "target_duration_seconds": 1200, "rest_seconds": null,
                  "notes": null }
                """.formatted(code);
    }

    private static String validDays() {
        return trainingDay(strengthItem("BARBELL_BACK_SQUAT"));
    }

    // ==================== 验收 A16：交付模板可原样导入 ====================

    @Test
    @DisplayName("交付模板原样导入：流水线零错误、零警告")
    void deliveredTemplateIsValid() {
        ImportPreviewVo preview = service.preview(USER_ID, readTemplate());

        assertEquals(7, preview.days().size(), "模板排满一周 7 天");
        assertEquals(0, preview.warningCount());
        assertEquals(LocalDate.of(2026, 10, 12), preview.startsOn());
        assertTrue(preview.days().stream()
                .anyMatch(day -> Boolean.TRUE.equals(day.isRestDay()) && day.items().isEmpty()),
                "休息日必须原样保留：丢一天等于改了计划的形状");

        ImportPreviewVo.Item first = preview.days().get(0).items().get(0);
        assertEquals("BARBELL_BACK_SQUAT", first.exerciseCode());
        assertEquals("STRENGTH", first.category());
        assertNotNull(first.exerciseName(), "预览要显示动作名，而不是让用户读代码");
        assertEquals(0, new BigDecimal("60.0").compareTo(first.targetExternalLoadKg()),
                () -> "负重必须是精确十进制，实际=" + first.targetExternalLoadKg());
    }

    @Test
    @DisplayName("模板里的每一个动作代码都在字典种子里（防止模板与 V9 漂移）")
    void templateCodesExistInSeedSubset() {
        ImportPreviewVo preview = service.preview(USER_ID, readTemplate());

        List<String> resolved = preview.days().stream()
                .flatMap(day -> day.items().stream())
                .map(ImportPreviewVo.Item::exerciseCode)
                .distinct()
                .sorted()
                .toList();
        assertEquals(List.of("BARBELL_BACK_SQUAT", "BARBELL_BENCH_PRESS", "BARBELL_OVERHEAD_PRESS", "CAT_COW",
                "DUMBBELL_ROW", "HAMSTRING_STRETCH", "HIP_FLEXOR_STRETCH", "LAT_PULLDOWN", "LEG_PRESS",
                "PUSH_UP", "ROWING", "TREADMILL_WALK_INCLINE"), resolved);
    }

    @Test
    @DisplayName("预览返回服务端规范化后的顺序与星期标签")
    void previewIsNormalized() {
        String content = doc(trainingDay(strengthItem("BARBELL_BACK_SQUAT")
                .replace("\"position_no\": 1", "\"position_no\": 7")));

        ImportPreviewVo preview = service.preview(USER_ID, content);

        assertEquals(1, preview.days().get(0).items().get(0).positionNo().intValue());
        assertEquals(1, preview.warningCount());
        assertEquals(PlanImportErrorCode.POSITION_RENUMBERED, preview.warnings().get(0).code());
        assertEquals("周一", preview.days().get(0).weekdayLabel());
    }

    @Test
    @DisplayName("动作代码大小写混写仍能命中字典（§5：大写规范化 + CI 匹配）")
    void exerciseCodeIsCanonicalized() {
        ImportPreviewVo preview = service.preview(USER_ID,
                doc(trainingDay(strengthItem("barbell_back_Squat"))));

        assertEquals("BARBELL_BACK_SQUAT", preview.days().get(0).items().get(0).exerciseCode());
    }

    // ==================== §8 阶段 1–3：体积、可解析性、模板版本 ====================

    @Test
    @DisplayName("超过 256 KiB → PAYLOAD_TOO_LARGE，按 UTF-8 字节计数")
    void payloadTooLarge() {
        StringBuilder padding = new StringBuilder();
        while (padding.length() < PlanImportService.MAX_IMPORT_BYTES) {
            padding.append("计划备注");
        }
        String content = doc(validDays()).replace("\"notes\": null", "\"notes\": \"" + padding + "\"");

        BizException e = previewFailure(content);

        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, e.errorCode());
        assertEquals(413, e.errorCode().status().value());
    }

    @Test
    @DisplayName("字节闸门是 > 而不是 >=，且发生在解析之前：恰好等于上限不误判为 413")
    void sizeGuardIsStrictGreaterThanAndRunsBeforeParsing() {
        int limit = PlanImportService.MAX_IMPORT_BYTES;

        // 恰好等于上限：闸门放行，于是落到"不是合法 JSON"这一层
        assertEquals(ErrorCode.MALFORMED_JSON,
                assertThrows(BizException.class, () -> service.preview(USER_ID, "x".repeat(limit))).errorCode());
        // 多一个字节：必须是 413，不能变成解析错误
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE,
                assertThrows(BizException.class, () -> service.preview(USER_ID, "x".repeat(limit + 1))).errorCode());
        // 上限之内的正常文档照常通过
        assertNotNull(service.preview(USER_ID, doc(validDays())));
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    @DisplayName("截断/语法错的 JSON → MALFORMED_JSON，不进入字段校验")
    void malformedJsonIsReportedAsSuch() {
        String whole = doc(validDays());
        String truncated = whole.substring(0, whole.length() - 20);

        BizException e = assertThrows(BizException.class, () -> service.preview(USER_ID, truncated));

        assertEquals(ErrorCode.MALFORMED_JSON, e.errorCode());
        verifyNoInteractions(dictionary);
    }

    @Test
    @DisplayName("根不是对象（数组）也归为 MALFORMED_JSON")
    void nonObjectRoot() {
        assertEquals(ErrorCode.MALFORMED_JSON, previewFailure("[1,2,3]").errorCode());
    }

    @Test
    @DisplayName("schema_version 不等于模板版本 → UNSUPPORTED_SCHEMA_VERSION，且结构层就短路")
    void unsupportedSchemaVersionShortCircuits() {
        String content = doc(validDays()).replace("fitagent.training_plan/1", "fitagent.training_plan/2");

        BizException e = previewFailure(content);

        assertEquals(ErrorCode.UNSUPPORTED_SCHEMA_VERSION, e.errorCode());
        assertEquals("schema_version", errorsOf(e).get(0).path());
        assertEquals(422, e.errorCode().status().value());
        verifyNoInteractions(dictionary);
    }

    @Test
    @DisplayName("缺 schema_version → REQUIRED，路径是 schema_version")
    void missingSchemaVersion() {
        BizException e = previewFailure(doc(validDays())
                .replace("\"schema_version\": \"fitagent.training_plan/1\",", ""));

        assertTrue(hasCode(e, PlanImportErrorCode.REQUIRED));
        assertEquals("schema_version", errorsOf(e).get(0).path());
    }

    // ==================== §7：字段来源控制 ====================

    @Test
    @DisplayName("未知字段 → UNKNOWN_FIELD，路径精确到所在层级")
    void unknownField() {
        String content = doc(trainingDay(strengthItem("BARBELL_BACK_SQUAT")
                .replace("\"notes\": null }", "\"remark\": \"自定义\" }")));

        BizException e = previewFailure(content);

        assertEquals(ErrorCode.UNKNOWN_FIELD, e.errorCode());
        assertEquals("days[0].items[0].remark", errorsOf(e).get(0).path());
    }

    @Test
    @DisplayName("服务端字段黑名单逐项判 FORBIDDEN_SERVER_FIELD，与 UNKNOWN_FIELD 分开")
    void forbiddenServerFields() {
        for (String forbidden : List.of("status", "userId", "id", "planGroupUuid", "revisionNo")) {
            String content = doc(validDays()).replace("\"title\":", "\"" + forbidden + "\": \"X\", \"title\":");

            BizException e = previewFailure(content);

            assertEquals(ErrorCode.FORBIDDEN_SERVER_FIELD, e.errorCode(), forbidden + " 必须按服务端字段拒绝");
            ImportIssueVo issue = find(e, PlanImportErrorCode.FORBIDDEN_SERVER_FIELD);
            assertNotNull(issue, forbidden);
            assertEquals(forbidden, issue.path());
        }
    }

    @Test
    @DisplayName("同一层多个非法键全部报出，不静默跳过")
    void forbiddenServerFieldAtItemLevelListsAll() {
        String content = doc(trainingDay(strengthItem("BARBELL_BACK_SQUAT")
                .replace("\"notes\": null }", "\"exercise_id\": 99, \"status\": \"ACTIVE\" }")));

        BizException e = previewFailure(content);

        assertEquals(ErrorCode.FORBIDDEN_SERVER_FIELD, e.errorCode());
        assertEquals(2, errorsOf(e).size());
    }

    @Test
    @DisplayName("snake_case 写法同样在黑名单内")
    void snakeCaseServerFieldsAreForbidden() {
        String content = doc(trainingDay(strengthItem("BARBELL_BACK_SQUAT")
                .replace("\"notes\": null }", "\"lock_version\": 3 }")));

        BizException e = previewFailure(content);

        assertTrue(hasCode(e, PlanImportErrorCode.FORBIDDEN_SERVER_FIELD));
        assertEquals("days[0].items[0].lock_version", errorsOf(e).get(0).path());
    }

    /** 阶段顺序的可观察后果：字段来源问题优先于类型与字典问题，一次只报一个信封码。 */
    @Test
    @DisplayName("信封码优先级：命中黑名单即返回，不再叠加后续错误")
    void envelopeCodePrecedence() {
        String content = doc(trainingDay(strengthItem("BENCHMARK_PRESS")))
                .replace("\"starts_on\": \"2026-10-12\"", "\"starts_on\": \"2026-10-12\", \"status\": \"ACTIVE\"");

        BizException e = previewFailure(content);

        assertEquals(ErrorCode.FORBIDDEN_SERVER_FIELD, e.errorCode());
        assertEquals(1, errorsOf(e).size());
    }

    // ==================== §2–§4：类型与必填 ====================

    @Nested
    @DisplayName("§4 类型严格性")
    class TypeStrictness {

        @Test
        @DisplayName("字符串数字 \"8\" → INVALID_TYPE")
        void stringNumberRejected() {
            String content = doc(trainingDay(strengthItem("BARBELL_BACK_SQUAT")
                    .replace("\"target_sets\": 3", "\"target_sets\": \"8\"")));

            BizException e = previewFailure(content);

            assertTrue(hasCode(e, PlanImportErrorCode.INVALID_TYPE));
            assertEquals("days[0].items[0].target_sets", find(e, PlanImportErrorCode.INVALID_TYPE).path());
        }

        @Test
        @DisplayName("时长写成 \"10min\" → INVALID_TYPE 并说明单位固定为秒")
        void minutesAreNotAccepted() {
            String content = doc(trainingDay(cardioItem("ROWING")
                    .replace("\"target_duration_seconds\": 1200", "\"target_duration_seconds\": \"10min\"")));

            BizException e = previewFailure(content);

            ImportIssueVo issue = find(e, PlanImportErrorCode.INVALID_TYPE);
            assertNotNull(issue);
            assertTrue(issue.message().contains("秒"), issue.message());
        }

        @Test
        @DisplayName("is_rest_day 必须是 JSON 布尔：0、1、\"true\" 一律 INVALID_TYPE")
        void restDayMustBeBoolean() {
            for (String raw : List.of("0", "1", "\"true\"")) {
                String content = doc(trainingDay(strengthItem("BARBELL_BACK_SQUAT"))
                        .replace("\"is_rest_day\": false", "\"is_rest_day\": " + raw));

                assertTrue(hasCode(previewFailure(content), PlanImportErrorCode.INVALID_TYPE),
                        raw + " 不能当成布尔");
            }
        }

        @Test
        @DisplayName("日期只接受 YYYY-MM-DD：斜杠、带时刻、带时区、数字都不行")
        void strictDateFormat() {
            for (String bad : List.of("2026/10/12", "2026-10-12T00:00:00", "2026-10-12Z")) {
                BizException e = previewFailure(doc(validDays())
                        .replace("\"starts_on\": \"2026-10-12\"", "\"starts_on\": \"" + bad + "\""));

                assertTrue(hasCode(e, PlanImportErrorCode.INVALID_DATE_FORMAT), bad);
            }
            BizException numeric = previewFailure(doc(validDays())
                    .replace("\"starts_on\": \"2026-10-12\"", "\"starts_on\": 1760236800"));

            assertTrue(hasCode(numeric, PlanImportErrorCode.INVALID_TYPE));
        }

        @Test
        @DisplayName("缺必填：title / days / iso_weekday 各自报 REQUIRED")
        void requiredFields() {
            assertTrue(hasCode(previewFailure(doc(validDays()).replace("\"title\": \"测试计划\",", "")),
                    PlanImportErrorCode.REQUIRED));
            assertTrue(hasCode(previewFailure("""
                    {"schema_version": "fitagent.training_plan/1", "title": "t", "starts_on": "2026-10-12"}
                    """), PlanImportErrorCode.REQUIRED));
            BizException noWeekday = previewFailure("""
                    {"schema_version": "fitagent.training_plan/1", "title": "t", "starts_on": "2026-10-12",
                     "days": [{"title": "d", "is_rest_day": false, "items": []}]}
                    """);

            assertTrue(hasCode(noWeekday, PlanImportErrorCode.REQUIRED));
            assertEquals("days[0].iso_weekday", find(noWeekday, PlanImportErrorCode.REQUIRED).path());
        }

        @Test
        @DisplayName("休息日给空数组合法，给动作才报错")
        void restDayEmptyItems() {
            assertNotNull(service.preview(USER_ID, doc(restDay(3))));

            String withItem = restDay(3).replace("\"items\": []",
                    "\"items\": [" + strengthItem("BARBELL_BACK_SQUAT") + "]");
            BizException e = previewFailure(doc(withItem));

            assertTrue(hasCode(e, PlanImportErrorCode.REST_DAY_HAS_ITEMS));
            assertEquals("days[0].items", find(e, PlanImportErrorCode.REST_DAY_HAS_ITEMS).path());
        }
    }

    // ==================== 结构与矩阵：与手工编辑共用同一套规则 ====================

    @Test
    @DisplayName("两个 iso_weekday=2 → DUPLICATE_WEEKDAY，路径指向第二个")
    void duplicateWeekdayFromJson() {
        // 两个计划日都用 weekday=1，才是真的重复；原先把第二个改成 2，构造出的是合法的一周两天
        BizException e = previewFailure(doc(validDays() + ", " + validDays()));

        assertTrue(hasCode(e, PlanImportErrorCode.DUPLICATE_WEEKDAY));
        assertEquals("days[1].iso_weekday", find(e, PlanImportErrorCode.DUPLICATE_WEEKDAY).path());
    }

    @Test
    @DisplayName("CARDIO 动作给 target_sets → CARDIO_SETS_FORBIDDEN")
    void cardioWithSets() {
        String item = cardioItem("ROWING").replace("\"target_sets\": null", "\"target_sets\": 3");

        BizException e = previewFailure(doc(trainingDay(item)));

        assertTrue(hasCode(e, PlanImportErrorCode.CARDIO_SETS_FORBIDDEN));
        assertEquals("days[0].items[0].target_sets", find(e, PlanImportErrorCode.CARDIO_SETS_FORBIDDEN).path());
    }

    @Test
    @DisplayName("未知动作代码 → EXERCISE_NOT_FOUND，绝不自动新增字典")
    void unknownExerciseCode() {
        BizException e = previewFailure(doc(trainingDay(strengthItem("BENCHMARK_PRESS"))));

        assertEquals(ErrorCode.PLAN_IMPORT_INVALID, e.errorCode());
        ImportIssueVo issue = find(e, PlanImportErrorCode.EXERCISE_NOT_FOUND);
        assertNotNull(issue);
        assertEquals("days[0].items[0].exercise_code", issue.path());
        assertEquals("BENCHMARK_PRESS", issue.value());
        verifyNoInteractions(planService);
    }

    @Test
    @DisplayName("字典里已停用的动作 → EXERCISE_DISABLED")
    void disabledExercise() {
        when(dictionary.loadByCodes(any())).thenReturn(
                TrainingFixtures.dictionaryOf(TrainingFixtures.exercise(5L, "BARBELL_BACK_SQUAT",
                        "STRENGTH", "DISABLED")));

        BizException e = previewFailure(doc(validDays()));

        assertTrue(hasCode(e, PlanImportErrorCode.EXERCISE_DISABLED));
    }

    @Test
    @DisplayName("多处错误全部报出，每条都带可定位 path")
    void multipleErrorsAreNotSkipped() {
        String bad = doc("""
                {"iso_weekday": 1, "title": "a", "is_rest_day": false, "items": [
                   {"exercise_code": "BARBELL_BACK_SQUAT", "target_sets": 3, "reps_min": 12, "reps_max": 5}
                ]},
                {"iso_weekday": 2, "title": "b", "is_rest_day": false, "items": [
                   {"exercise_code": "ROWING", "target_sets": 2, "target_duration_seconds": 600}
                ]}
                """);

        BizException e = previewFailure(bad);

        assertTrue(hasCode(e, PlanImportErrorCode.REPS_MAX_LT_MIN));
        assertTrue(hasCode(e, PlanImportErrorCode.CARDIO_SETS_FORBIDDEN));
        for (ImportIssueVo issue : errorsOf(e)) {
            assertNotNull(issue.path());
            assertNotNull(issue.code());
        }
    }

    // ==================== 确认保存与幂等 ====================

    @Nested
    @DisplayName("确认保存与幂等（验收 A17）")
    class Confirm {

        @BeforeEach
        void stubClaim() {
            when(idempotency.normalizeKey(anyString())).thenReturn(CANONICAL_KEY);
            when(idempotency.claimOrReplay(anyLong(), anyString())).thenReturn(Optional.empty());
            when(planService.saveImported(eq(USER_ID), any(), anyString(), any(), any())).thenReturn(42L);
        }

        @Test
        @DisplayName("确认成功：DRAFT + revisionNo=1 + 回传幂等键")
        void confirmPersistsDraft() {
            ImportConfirmVo result = service.confirm(USER_ID, doc(validDays()), "key");

            assertEquals("42", result.planId());
            assertEquals(1, result.revisionNo().intValue());
            assertEquals(PlanStatus.DRAFT.code(), result.status());
            assertFalse(result.idempotentReplay());
            assertEquals(CANONICAL_KEY, result.idempotencyKey(), "服务端生成的键要回传，客户端重试时才知道用哪个");
            verify(idempotency).complete(USER_ID, CANONICAL_KEY, "42");
        }

        @Test
        @DisplayName("落库的是 DRAFT/IMPORT 的新组首版，导入不可能直接生效")
        void importedPlanIsDraft() {
            service.confirm(USER_ID, doc(validDays()), "key");

            ArgumentCaptor<PlanValidationOutcome> outcome =
                    ArgumentCaptor.forClass(PlanValidationOutcome.class);
            ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<LocalDate> startsOn = ArgumentCaptor.forClass(LocalDate.class);
            ArgumentCaptor<LocalDate> endsOn = ArgumentCaptor.forClass(LocalDate.class);
            verify(planService).saveImported(eq(USER_ID), outcome.capture(), title.capture(),
                    startsOn.capture(), endsOn.capture());

            assertEquals("测试计划", title.getValue());
            assertEquals(LocalDate.of(2026, 10, 12), startsOn.getValue());
            assertFalse(outcome.getValue().hasErrors());
            BigDecimal load = outcome.getValue().days().get(0).items().get(0).targetExternalLoadKg();
            assertEquals(0, new BigDecimal("60.0").compareTo(load), () -> "实际=" + load);
        }

        @Test
        @DisplayName("先占键再执行：claim 排在聚合写入之前，complete 排在之后")
        void claimsKeyBeforeExecuting() {
            service.confirm(USER_ID, doc(validDays()), "key");

            InOrder order = inOrder(idempotency, planService);
            order.verify(idempotency).normalizeKey(anyString());
            order.verify(idempotency).claimOrReplay(eq(USER_ID), anyString());
            order.verify(planService).saveImported(eq(USER_ID), any(), anyString(), any(), any());
            order.verify(idempotency).complete(eq(USER_ID), anyString(), eq("42"));
        }

        @Test
        @DisplayName("同键二次确认：同一 planId，聚合只写一次")
        void replayReturnsFirstResult() {
            String key = CANONICAL_KEY;
            when(idempotency.claimOrReplay(eq(USER_ID), eq(key)))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of("42"));
            when(planService.findHeader(USER_ID, 42L)).thenReturn(new PlanListVo("42", Long.toString(USER_ID),
                    TrainingFixtures.GROUP, 1, null, "测试计划", TrainingPlanService.SOURCE_IMPORT,
                    PlanStatus.DRAFT.code(), TrainingFixtures.STARTS_ON, null, null, 0, null, null));

            ImportConfirmVo first = service.confirm(USER_ID, doc(validDays()), key);
            ImportConfirmVo second = service.confirm(USER_ID, doc(validDays()), key);

            assertEquals(first.planId(), second.planId());
            assertFalse(first.idempotentReplay());
            assertTrue(second.idempotentReplay());
            assertEquals(PlanStatus.DRAFT.code(), second.status());
            verify(planService, times(1)).saveImported(eq(USER_ID), any(), anyString(), any(), any());
            verify(idempotency, never()).release(anyLong(), anyString());
        }

        @Test
        @DisplayName("执行失败：释放占位，允许同键重试")
        void failureReleasesClaim() {
            when(planService.saveImported(eq(USER_ID), any(), anyString(), any(), any()))
                    .thenThrow(new IllegalStateException("db down"));

            assertThrows(IllegalStateException.class, () -> service.confirm(USER_ID, doc(validDays()), "key"));

            verify(idempotency).release(eq(USER_ID), eq(CANONICAL_KEY));
            verify(idempotency, never()).complete(anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("校验不通过也释放占位，并且不落库")
        void invalidContentReleasesClaim() {
            BizException e = assertThrows(BizException.class,
                    () -> service.confirm(USER_ID, doc(trainingDay(strengthItem("BENCHMARK_PRESS"))), "key"));

            assertEquals(ErrorCode.PLAN_IMPORT_INVALID, e.errorCode());
            verify(idempotency).release(eq(USER_ID), eq(CANONICAL_KEY));
            verify(planService, never()).saveImported(anyLong(), any(), anyString(), any(), any());
        }

        @Test
        @DisplayName("confirm 从原始文本重新完整校验，不信任 preview")
        void confirmRevalidatesFromScratch() {
            service.confirm(USER_ID, doc(validDays()), "key");

            verify(dictionary).loadByCodes(any());
            verify(idempotency, times(1)).claimOrReplay(eq(USER_ID), anyString());
        }

        @Test
        @DisplayName("preview 与 confirm 对同一份文本给出一致结论")
        void previewAndConfirmAgree() {
            String content = doc(validDays());
            assertNotNull(service.preview(USER_ID, content));

            service.confirm(USER_ID, content, "key");

            verify(planService).saveImported(eq(USER_ID), any(), eq("测试计划"), any(), any());
        }
    }

    @Test
    @DisplayName("preview 不占用幂等键、不写库")
    void previewHasNoSideEffects() {
        service.preview(USER_ID, doc(validDays()));

        verifyNoInteractions(planService);
        verifyNoInteractions(idempotency);
    }
}
