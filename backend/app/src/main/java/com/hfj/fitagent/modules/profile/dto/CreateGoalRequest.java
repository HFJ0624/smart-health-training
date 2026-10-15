package com.hfj.fitagent.modules.profile.dto;

import jakarta.validation.constraints.DecimalMax;
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
 * 新建目标（docs/p1-api.md §4.3）。
 *
 * 创建不接受 {@code id}/{@code userId}/{@code lockVersion}/{@code status}：状态由 {@code activate} 推导，
 * 身份来自认证上下文。{@code dailyIntakeTargetKcal} 不出现在请求里（本轮不暴露饮食目标）。
 *
 * <p>{@code activate=true} 的语义是"激活并替换当前 ACTIVE"：服务端在同一事务里先归档旧 ACTIVE 再激活本条，
 * 顺序不可颠倒，否则 UNIQUE(active_user_id) 会在事务内看到两行同 user_id。
 */
public record CreateGoalRequest(

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

        Boolean activate
) {

    /** 缺省即激活：契约把 activate 的默认值定为 true。 */
    public boolean activateOrDefault() {
        return activate == null || activate;
    }
}
