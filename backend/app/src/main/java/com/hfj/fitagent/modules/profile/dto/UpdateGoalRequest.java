package com.hfj.fitagent.modules.profile.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 修改目标（DRAFT 与 ACTIVE 可就地改，ARCHIVED 一律拒绝，见 docs/p1-api.md §4.3）。
 *
 * 采用全量替换语义：内容与创建同形，状态不在这里改（状态迁移只走 activate/archive 两个显式动作），
 * 因此本请求没有 {@code status} 字段；{@code lockVersion} 必填，取自上次读取。
 */
public record UpdateGoalRequest(

        @NotBlank(message = "目标类型不能为空")
        @Size(max = 32, message = "目标类型取值非法")
        String goalType,

        @DecimalMin(value = "0", inclusive = false, message = "目标体重必须大于 0")
        @Digits(integer = 4, fraction = 2, message = "目标体重最多 2 位小数")
        BigDecimal targetWeightKg,

        @Min(value = 0, message = "每周训练次数不能为负")
        @Max(value = 255, message = "每周训练次数超出存储范围")
        Integer weeklySessionsTarget,

        @NotNull(message = "开始日期不能为空")
        LocalDate startsOn,

        LocalDate endsOn,

        @NotNull(message = "lockVersion 不能为空")
        Integer lockVersion
) {
}
