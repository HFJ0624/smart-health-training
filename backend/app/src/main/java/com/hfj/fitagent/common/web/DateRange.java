package com.hfj.fitagent.common.web;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 日期范围查询参数：跨度上限 366 天，防止无界查询。
 * 只接受 YYYY-MM-DD，不接受时间戳或带时区的值。
 */
public record DateRange(LocalDate from, LocalDate to) {

    public static final int MAX_SPAN_DAYS = 366;

    /**
     * @param defaultFrom 未提供 from 时的下界（含）
     * @param defaultTo   未提供 to 时的上界（含）
     */
    public static DateRange of(String from, String to, LocalDate defaultFrom, LocalDate defaultTo) {
        LocalDate f = parse(from, "from", defaultFrom);
        LocalDate t = parse(to, "to", defaultTo);
        if (t.isBefore(f)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "结束日期不能早于开始日期",
                    List.of(FieldError.of("to", "END_BEFORE_START", "结束日期不能早于开始日期", to)));
        }
        if (f.plusDays(MAX_SPAN_DAYS).isBefore(t)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "查询跨度不得超过 " + MAX_SPAN_DAYS + " 天",
                    List.of(FieldError.of("from", "RANGE_TOO_WIDE", "查询跨度不得超过 " + MAX_SPAN_DAYS + " 天", from)));
        }
        return new DateRange(f, t);
    }

    private static LocalDate parse(String raw, String field, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "日期格式必须为 YYYY-MM-DD",
                    List.of(FieldError.of(field, "INVALID_DATE_FORMAT", "日期格式必须为 YYYY-MM-DD", raw)));
        }
    }
}
