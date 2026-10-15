package com.hfj.fitagent.modules.profile.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求 DTO 的数值护栏。
 *
 * <p>只验"必填 / 正数 / 小数位 / 列范围"，不验任何医学区间：docs/p1-scope.md §2 明确本轮
 * "不添加未经说明的医学阈值"，所以 300cm 的身高在契约层是合法的（库列 DECIMAL(6,2) 说得过去），
 * 服务端没有资格替用户判定"不可能"。
 */
class ProfileRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static Set<ConstraintViolation<UpsertProfileRequest>> profile(BigDecimal heightCm) {
        return validator.validate(new UpsertProfileRequest(heightCm, null, null, null, null, null, null));
    }

    @Test
    @DisplayName("身高必填")
    void heightIsRequired() {
        Set<ConstraintViolation<UpsertProfileRequest>> violations = profile(null);

        assertThat(violations).hasSize(1);
        ConstraintViolation<UpsertProfileRequest> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("heightCm");
        assertThat(violation.getMessage()).contains("不能为空");
    }

    @Test
    @DisplayName("身高必须为正：0 与负数都不接受（对齐 ck_user_profile_0）")
    void heightMustBePositive() {
        assertThat(profile(BigDecimal.ZERO))
                .anySatisfy(v -> {
                    assertThat(v.getPropertyPath().toString()).isEqualTo("heightCm");
                    assertThat(v.getMessage()).contains("大于 0");
                });

        assertThat(profile(new BigDecimal("-1")))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("heightCm"));
    }

    @Test
    @DisplayName("身高最多 2 位小数，且不超过 DECIMAL(6,2) 的整数位")
    void heightMustFitColumnScale() {
        assertThat(profile(new BigDecimal("175.123")))
                .anySatisfy(v -> assertThat(v.getMessage()).contains("2 位小数"));

        assertThat(profile(new BigDecimal("10000.00")))
                .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("heightCm"));
    }

    @Test
    @DisplayName("合法身高不产生任何字段错误")
    void validHeightHasNoViolations() {
        assertThat(profile(new BigDecimal("175.00"))).isEmpty();
    }

    @Test
    @DisplayName("体重必填且必须为正；体脂只对齐 0–100 与列精度")
    void bodyRecordNumericGuards() {
        CreateBodyRecordRequest missingWeight =
                new CreateBodyRecordRequest(Instant.parse("2026-10-13T07:20:00Z"), null, null, null, null,
                        "3f1a2b3c-4d5e-4f60-8a7b-9c0d1e2f3a4b");
        assertThat(validator.validate(missingWeight))
                .anySatisfy(v -> {
                    assertThat(v.getPropertyPath().toString()).isEqualTo("weightKg");
                    assertThat(v.getMessage()).contains("不能为空");
                });

        CreateBodyRecordRequest zeroWeight = new CreateBodyRecordRequest(
                Instant.parse("2026-10-13T07:20:00Z"), BigDecimal.ZERO, null, null, null,
                "3f1a2b3c-4d5e-4f60-8a7b-9c0d1e2f3a4b");
        assertThat(validator.validate(zeroWeight))
                .anySatisfy(v -> assertThat(v.getMessage()).contains("大于 0"));

        CreateBodyRecordRequest badFat = new CreateBodyRecordRequest(
                Instant.parse("2026-10-13T07:20:00Z"), new BigDecimal("72.50"), null, new BigDecimal("100.01"), null,
                "3f1a2b3c-4d5e-4f60-8a7b-9c0d1e2f3a4b");
        assertThat(validator.validate(badFat))
                .anySatisfy(v -> {
                    assertThat(v.getPropertyPath().toString()).isEqualTo("bodyFatPercent");
                    assertThat(v.getMessage()).contains("100");
                });
    }

    @Test
    @DisplayName("创建请求不接受 lockVersion：DTO 里根本没有这个键")
    void createRequestsCarryNoServerOwnedFields() {
        assertThat(fieldNames(CreateBodyRecordRequest.class))
                .doesNotContain("lockVersion", "businessDate", "source", "userId", "id");
        assertThat(fieldNames(CreateGoalRequest.class))
                .doesNotContain("lockVersion", "status", "userId", "id", "dailyIntakeTargetKcal");
        assertThat(fieldNames(UpsertProfileRequest.class))
                .doesNotContain("activityCategory", "dietPreferences", "professionalGuidanceRequired",
                        "nickname", "userId", "id");
    }

    @Test
    @DisplayName("更新请求必须带 lockVersion，且身体记录不接收业务日期与来源")
    void updateRequestsCarryLockVersionOnly() {
        assertThat(validator.validate(new UpdateBodyRecordRequest(
                null, new BigDecimal("72.50"), null, null, null, null, null)))
                .anySatisfy(v -> {
                    assertThat(v.getPropertyPath().toString()).isEqualTo("lockVersion");
                    assertThat(v.getMessage()).contains("不能为空");
                });

        assertThat(fieldNames(UpdateBodyRecordRequest.class))
                .contains("lockVersion", "clientRecordUuid")
                .doesNotContain("businessDate", "source", "userId", "id");
        assertThat(fieldNames(UpdateGoalRequest.class))
                .contains("lockVersion")
                .doesNotContain("status", "userId", "id", "dailyIntakeTargetKcal");
    }

    private static List<String> fieldNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
