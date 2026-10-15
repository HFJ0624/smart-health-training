package com.hfj.fitagent.modules.training.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 计划聚合提交（{@code POST /api/plans} 新建、{@code PUT /api/plans/{id}} 就地编辑）。
 *
 * <p>一次请求 = 一个完整聚合（头 + days + items），服务端在同一事务内重建子行；
 * P1 不提供 {@code /api/plans/{id}/days} 这类细粒度接口（docs/p1-api.md §7）。
 *
 * <p>Bean Validation 只负责"存在与类型"，长度上限、数值范围、星期唯一、类型×字段矩阵等业务规则
 * 全部在 {@code PlanAggregateValidator} 里，与导入共用同一套实现。
 *
 * <p>{@code id}/{@code userId}/{@code status}/{@code source}/{@code planGroupUuid}/
 * {@code revisionNo}/{@code basePlanId}/{@code goalSnapshot}/{@code createdAt} 等服务端字段
 * 不在此 DTO 中；全局 {@code FAIL_ON_UNKNOWN_PROPERTIES=true} 会把它们挡在门外
 * （docs/p1-database.md §7：客户端永远不能通过传 userId 决定身份或归属）。
 *
 * @param lockVersion 仅 PUT 必填（取自上次读取）；POST 新建时忽略
 */
public record PlanUpsertRequest(@NotBlank(message = "计划名称不能为空")
                                String title,

                                @NotNull(message = "起始日期必填")
                                LocalDate startsOn,

                                LocalDate endsOn,

                                @NotEmpty(message = "至少安排一个计划日")
                                @Valid
                                List<Day> days,

                                Integer lockVersion) {

    /**
     * @param isoWeekday 1=周一…7=周日；范围校验交给校验器（与导入同一条规则，报 INVALID_WEEKDAY）
     * @param isRestDay  休息日；true 时 items 必须为空
     */
    public record Day(@NotNull(message = "isoWeekday 必填")
                      Integer isoWeekday,

                      String title,

                      @NotNull(message = "isRestDay 必填")
                      Boolean isRestDay,

                      String notes,

                      @NotNull(message = "items 必填（休息日给空数组）")
                      @Valid
                      List<Item> items) {
    }

    /**
     * @param positionNo 可缺省，服务端按数组顺序规范化为 1..n
     */
    public record Item(@NotBlank(message = "exerciseCode 必填")
                       String exerciseCode,

                       Integer positionNo,

                       Integer targetSets,

                       Integer repsMin,

                       Integer repsMax,

                       BigDecimal targetExternalLoadKg,

                       Integer targetDurationSeconds,

                       Integer restSeconds,

                       String notes) {
    }
}
