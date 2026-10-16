package com.hfj.fitagent.modules.metrics.controller;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.web.ApiResponse;
import com.hfj.fitagent.common.web.FieldError;
import com.hfj.fitagent.modules.metrics.TrainingMetricsService;
import com.hfj.fitagent.modules.metrics.vo.MetricsVos;
import com.hfj.fitagent.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 统计接口层：只验"身份与日期解析 + 转调"，不验任何指标算法。
 *
 * <p>算法一律在 {@link TrainingMetricsService} 里测（{@code TrainingMetricsServiceTest}）。
 * 这里刻意把 Service 打桩成一个常量：如果哪天有人在 Controller 里顺手补了一段计算，
 * 这些用例就会因为"Controller 需要 Service 之外的口径知识"而变得难以维持，从而暴露出越界。
 */
class MetricsControllerTest {

    private static final long USER = 7L;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 14);
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);

    private TrainingMetricsService metricsService;
    private MetricsController controller;

    @BeforeEach
    void setUp() {
        metricsService = mock(TrainingMetricsService.class);
        controller = new MetricsController(metricsService);
        authenticate();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("当天对比：身份只从认证上下文取，日期按 YYYY-MM-DD 解析后转调 Service")
    void dayEndpointResolvesIdentityAndDateThenDelegates() {
        MetricsVos.DayComparison payload = emptyDay();
        when(metricsService.day(USER, WEDNESDAY)).thenReturn(payload);

        ResponseEntity<ApiResponse<MetricsVos.DayComparison>> response = controller.day("2026-10-14");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("OK");
        assertThat(response.getBody().data()).isSameAs(payload);
        verify(metricsService).day(USER, WEDNESDAY);
    }

    @Test
    @DisplayName("周统计：日期解析成周一后转调，Service 负责校验周界与算法")
    void weekEndpointDelegatesParsedMonday() {
        MetricsVos.WeekSummary payload = emptyWeek();
        when(metricsService.week(USER, MONDAY)).thenReturn(payload);

        assertThat(controller.week("2026-10-12").getBody().data()).isSameAs(payload);
        verify(metricsService).week(USER, MONDAY);
    }

    @Test
    @DisplayName("日期格式非法：VALIDATION_FAILED + INVALID_DATE_FORMAT 字段错误，且不带任何指标计算")
    void illegalDateFormatBecomesFieldErrorNotFiveHundred() {
        assertDateError("2026/10/14", "businessDate", "days");
        assertDateError("14-10-2026", "businessDate", "days");
        assertDateError("2026-1-5", "businessDate", "days");
        assertDateError("2026-10-14T00:00:00Z", "businessDate", "days");
        assertDateError("", "businessDate", "days");
        assertDateError("   ", "businessDate", "days");
        verifyNoInteractions(metricsService);
    }

    @Test
    @DisplayName("周起始日格式非法同样给字段错误，不会被周界校验抢先或吞掉")
    void illegalWeekStartFormatBecomesFieldError() {
        assertThatThrownBy(() -> controller.week("not-a-date"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    FieldError error = (FieldError) ((List<?>) biz.payload()).get(0);
                    assertThat(error.field()).isEqualTo("weekStartDate");
                    assertThat(error.code()).isEqualTo("INVALID_DATE_FORMAT");
                    assertThat(error.rejected()).isEqualTo("not-a-date");
                });
        verifyNoInteractions(metricsService);
    }

    @Test
    @DisplayName("未登录：UNAUTHENTICATED，且绝不会用某个默认身份去查别人的统计")
    void unauthenticatedRequestNeverReachesService() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> controller.day("2026-10-14"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
        verifyNoInteractions(metricsService);
    }

    // ================================================================== 小件

    private void assertDateError(String raw, String field, String which) {
        assertThatThrownBy(() -> {
            if (which.equals("days")) {
                controller.day(raw);
            } else {
                controller.week(raw);
            }
        })
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    FieldError error = (FieldError) ((List<?>) biz.payload()).get(0);
                    assertThat(error.field()).isEqualTo(field);
                    assertThat(error.code()).isEqualTo(raw.isBlank() ? "REQUIRED" : "INVALID_DATE_FORMAT");
                });
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(USER, SHANGHAI, true), "opaque-token", List.of()));
    }

    private static MetricsVos.DayComparison emptyDay() {
        return new MetricsVos.DayComparison(WEDNESDAY, 3, "workout-completion-v1", "42",
                List.of(), new MetricsVos.SelfInitiatedSummary(List.of(), List.of(), List.of()),
                new MetricsVos.DayRecordSummary(0, List.of(), 0, 0, 0, null, null, null, List.of(), List.of()),
                List.of());
    }

    private static MetricsVos.WeekSummary emptyWeek() {
        return new MetricsVos.WeekSummary(MONDAY, MONDAY.plusDays(6), "workout-completion-v1", "42",
                List.of(), List.of(), List.of());
    }
}
