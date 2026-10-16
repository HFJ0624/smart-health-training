package com.hfj.fitagent.modules.metrics.controller;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.web.ApiResponse;
import com.hfj.fitagent.common.web.FieldError;
import com.hfj.fitagent.modules.metrics.TrainingMetricsService;
import com.hfj.fitagent.modules.metrics.vo.MetricsVos;
import com.hfj.fitagent.security.AuthenticatedUsers;
import com.hfj.fitagent.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 训练统计接口（docs/stage2-design.md §5）。
 *
 * <p>本类<b>只做三件事</b>：取身份、把路径上的日期文本解析成 {@code LocalDate}、转调 Service。
 * 一个指标都不在这里算——口径集中在 {@link TrainingMetricsService}，页面与 Agent 工具才能拿到同一个数。
 *
 * <p>日期用文本入参而不是 {@code @DateTimeFormat}：绑定失败会先撞上 Spring 的类型转换，
 * 错误码只能落到通用的 {@code INVALID_TYPE}；自己解析才能按契约给出
 * {@code VALIDATION_FAILED} + {@code INVALID_DATE_FORMAT} 的字段错误（且绝不是 500）。
 */
@RestController
@RequestMapping("/api/metrics/training")
public class MetricsController {

    private final TrainingMetricsService metricsService;

    public MetricsController(TrainingMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    /** 当天"计划安排 vs 已确认执行"对比与记录摘要。 */
    @GetMapping("/days/{businessDate}")
    public ResponseEntity<ApiResponse<MetricsVos.DayComparison>> day(@PathVariable String businessDate) {
        CurrentUser user = AuthenticatedUsers.require();
        return ResponseEntity.ok(ApiResponse.ok(
                metricsService.day(user.id(), parseDate(businessDate, "businessDate"))));
    }

    /** 本周（周一至周日）场次数、不同训练日期数、有依据的动作时长与缺失说明。 */
    @GetMapping("/weeks/{weekStartDate}")
    public ResponseEntity<ApiResponse<MetricsVos.WeekSummary>> week(@PathVariable String weekStartDate) {
        CurrentUser user = AuthenticatedUsers.require();
        return ResponseEntity.ok(ApiResponse.ok(
                metricsService.week(user.id(), parseDate(weekStartDate, "weekStartDate"))));
    }

    /** 只接受 {@code YYYY-MM-DD}：带时间、带时区或简写日期都在这里被挡下，不进 Service。 */
    static LocalDate parseDate(String raw, String field) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "日期不能为空",
                    List.of(FieldError.of(field, "REQUIRED", "日期不能为空", raw)));
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "日期格式必须为 YYYY-MM-DD",
                    List.of(FieldError.of(field, "INVALID_DATE_FORMAT", "日期格式必须为 YYYY-MM-DD", raw)));
        }
    }
}
