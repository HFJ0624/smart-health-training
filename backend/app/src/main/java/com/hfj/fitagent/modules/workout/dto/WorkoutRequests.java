package com.hfj.fitagent.modules.workout.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 训练执行的入参集合。
 *
 * <p>刻意放在一个文件里以嵌套 record 表达：一次保存就是一棵"场次 → 动作 → 组"的树，
 * 拆成六个顶层类型反而看不出层级，也更容易漏掉 {@code @Valid} 的传递。
 *
 * <p>所有 id 都是字符串（契约：BIGINT 在 JSON 里是字符串）；所有"没填"都是 null，
 * 服务端不会把 null 变成 0，也不会用计划值补成实际值。
 */
public final class WorkoutRequests {

    private WorkoutRequests() {
    }

    /**
     * 开始或恢复一场训练。
     *
     * @param clientRecordUuid 幂等键，同键重复提交返回同一场次
     * @param planId           可选：参考的计划版本；与 planDayId 成对给出
     * @param planDayId        可选：参考的计划日；服务端据此校验归属并取当时的修订号
     * @param businessDate     可选：补录时指定的业务日期（用户时区口径）；不传即今天
     */
    public record Start(@NotBlank String clientRecordUuid,
                        String planId,
                        String planDayId,
                        LocalDate businessDate) {
    }

    /** 一组。{@code isCompleted} 必须由用户显式给出，服务端不替用户勾。 */
    public record SetInput(@NotNull @Positive Integer setNo,
                           @PositiveOrZero Integer reps,
                           @PositiveOrZero BigDecimal externalLoadKg,
                           @Positive Integer durationSeconds,
                           @NotNull Boolean isCompleted,
                           String notes) {
    }

    /**
     * 一个动作条目。
     *
     * @param exerciseId  动作字典 ID（字符串）
     * @param planItemId  可选：它参考的计划条目；给了就必须属于本场次参考的那个计划日
     * @param positionNo  场次内顺序，从 1 开始且不重复
     */
    public record EntryInput(@NotNull @Positive Integer positionNo,
                             @NotBlank String exerciseId,
                             String planItemId,
                             @Positive Integer durationSeconds,
                             @Positive BigDecimal distanceMeters,
                             String notes,
                             @Valid @NotNull List<SetInput> sets) {
    }

    /** 聚合保存：整棵子树按入参重建，必须携带场次乐观锁版本。 */
    public record Save(@NotNull Integer lockVersion,
                       String notes,
                       @Valid @NotNull List<EntryInput> entries) {
    }

    /** 结束确认。endedAt 不传即服务端当前时间；跨午夜不改业务日期。 */
    public record Complete(@NotNull Integer lockVersion, Instant endedAt) {
    }

    /** 只改备注/丢弃/删除这类单字段动作共用的版本载体。 */
    public record VersionOnly(@NotNull Integer lockVersion) {
    }
}
