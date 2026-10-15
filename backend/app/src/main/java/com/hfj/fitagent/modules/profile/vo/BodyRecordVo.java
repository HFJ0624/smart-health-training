package com.hfj.fitagent.modules.profile.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hfj.fitagent.modules.profile.entity.BodyMeasurementEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 身体记录出参，读取（列表/最新）与写入（新增/修改）共用同一形状，减少两套模型。
 *
 * <p>{@code businessDate} 与 {@code measuredAt} 分字段返回：前者是用户时区的业务日期（历史曲线横轴），
 * 后者是 UTC 瞬时值（判定"最新"的唯一依据）。两者混用就会让补记录变成改最新。
 *
 * <p>{@code created} / {@code idempotentReplay} 只在写响应里有意义，读响应里为 null 并按 NON_NULL 省略，
 * 这样分页列表不会被两个恒空字段污染，而 POST 的响应与 docs/p1-api.md §5 的示例逐字段一致。
 */
public record BodyRecordVo(
        String id,
        String userId,
        Instant measuredAt,
        LocalDate businessDate,
        BigDecimal weightKg,
        BigDecimal waistCm,
        BigDecimal bodyFatPercent,
        String source,
        String clientRecordUuid,
        String notes,
        Integer lockVersion,
        Instant createdAt,
        Instant updatedAt,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean created,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean idempotentReplay
) {

    /** 读视图：列表与"最新一条"。 */
    public static BodyRecordVo from(BodyMeasurementEntity e) {
        return e == null ? null : of(e, null, null);
    }

    /** 写视图：带 created / idempotentReplay 两个判定位。 */
    public static BodyRecordVo written(BodyMeasurementEntity e, boolean created) {
        return of(e, created, !created);
    }

    private static BodyRecordVo of(BodyMeasurementEntity e, Boolean created, Boolean idempotentReplay) {
        return new BodyRecordVo(
                str(e.getId()),
                str(e.getUserId()),
                e.getMeasuredAt(),
                e.getBusinessDate(),
                e.getWeightKg(),
                e.getWaistCm(),
                e.getBodyFatPercent(),
                e.getSource(),
                e.getClientRecordUuid(),
                e.getNotes(),
                e.getLockVersion(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                created,
                idempotentReplay);
    }

    private static String str(Long v) {
        return v == null ? null : Long.toString(v);
    }
}
