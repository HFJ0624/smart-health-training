package com.hfj.fitagent.modules.profile.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 新增身体记录（docs/p1-api.md §5）。
 *
 * 刻意不接收的字段：
 * <ul>
 *   <li>{@code businessDate} —— 由服务端从 measuredAt 按用户时区推导，客户端传了就是伪造归属日期；</li>
 *   <li>{@code source} —— 本轮恒为 MANUAL，由服务端写入；</li>
 *   <li>{@code id} / {@code userId} / {@code lockVersion} —— 身份来自认证上下文，新建没有既有版本可比。</li>
 * </ul>
 *
 * <p>{@code clientRecordUuid} 必填：它是 (user_id, client_record_uuid) 唯一索引的幂等键，
 * 同一条逻辑记录重试必须复用同一个值。数值上限只对齐库列精度与契约明示的 0–100 体脂区间，
 * 不引入任何未经说明的医学阈值。
 */
public record CreateBodyRecordRequest(

        @NotNull(message = "测量时间不能为空")
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

        @NotBlank(message = "clientRecordUuid 不能为空")
        @Size(max = 36, message = "clientRecordUuid 长度不得超过 36")
        String clientRecordUuid
) {
}
