package com.hfj.fitagent.modules.profile.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 修改身体记录（docs/p1-api.md §5：需 lockVersion；clientRecordUuid 不可变）。
 *
 * <p>{@code clientRecordUuid} 之所以仍然出现在请求里：小程序的编辑表单会把手上的整条记录回传，
 * 服务端把它当作"一致性核对"而不是可写字段——值与库中不同即 IMMUTABLE_FIELD，相同则原样保留。
 *
 * <p>{@code measuredAt} 可选：修正测量时间时服务端会重算 businessDate；不传则保持既有测量时间与业务日期。
 * 同样不接收 {@code businessDate} 与 {@code source}——它们只由服务端推导。
 */
public record UpdateBodyRecordRequest(

        Instant measuredAt,

        @NotNull(message = "体重不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "体重必须大于 0")
        @Digits(integer = 4, fraction = 2, message = "体重最多 2 位小数")
        BigDecimal weightKg,

        @DecimalMin(value = "0", inclusive = false, message = "腰围必须大于 0")
        @Digits(integer = 4, fraction = 2, message = "腰围最多 2 位小数")
        BigDecimal waistCm,

        @DecimalMin(value = "0", message = "体脂率不能为负")
        @DecimalMax(value = "100", message = "体脂率不得超过 100")
        @Digits(integer = 3, fraction = 2, message = "体脂率最多 2 位小数")
        BigDecimal bodyFatPercent,

        @Size(max = 500, message = "备注最长 500 字")
        String notes,

        @Size(max = 36, message = "clientRecordUuid 长度不得超过 36")
        String clientRecordUuid,

        @NotNull(message = "lockVersion 不能为空")
        Integer lockVersion
) {
}
