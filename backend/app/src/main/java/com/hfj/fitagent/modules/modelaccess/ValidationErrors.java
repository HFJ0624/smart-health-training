package com.hfj.fitagent.modules.modelaccess;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.web.FieldError;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * modelaccess 的字段级校验出口（形状与 {@code modules/profile/dto/FieldErrors} 一致：
 * payload 是 {@code List<FieldError>}，由 GlobalExceptionHandler 放进 {@code ApiResponse.data}）。
 *
 * <p>为什么各模块各有一份而不是复用 profile 的那个：跨模块引用 DTO 会形成 profile ← modelaccess
 * 的依赖，而 AGENTS.md 规定跨模块只走接口，常量与校验工具不算接口。三行重复换来的是模块边界。
 *
 * <p>枚举校验刻意不用 Bean Validation 的 {@code @Pattern}：项目要求非法取值给
 * {@code INVALID_ENUM_VALUE} 这个稳定码（docs/p1-database.md §5 补充规则 1）。
 */
public final class ValidationErrors {

    private ValidationErrors() {
    }

    public static BizException field(String field, String code, String message, Object rejected) {
        return new BizException(ErrorCode.VALIDATION_FAILED, message,
                List.of(FieldError.of(field, code, message, rejected)));
    }

    public static BizException fields(String message, List<FieldError> errors) {
        return new BizException(ErrorCode.VALIDATION_FAILED, message, List.copyOf(errors));
    }

    /** 空/空白即拒绝，返回 trim 后的值（不做任何大小写折叠）。 */
    public static String requireText(String field, String value, int maxLength) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw field(field, "REQUIRED", field + " 不能为空", value);
        }
        if (trimmed.length() > maxLength) {
            throw field(field, "TOO_LONG", field + " 长度不得超过 " + maxLength, trimmed);
        }
        return trimmed;
    }

    /** null 表示"未填写"；非 null 必须精确命中大写字面量。 */
    public static void requireEnum(String field, String value, Set<String> allowed) {
        if (value == null) {
            return;
        }
        if (!allowed.contains(value)) {
            throw field(field, "INVALID_ENUM_VALUE", field + " 取值不在允许范围内", value);
        }
    }

    /** 可空且为 0 / 负数 / 非整数都不允许——"0"在这里是"不许设成 0 元上限"。 */
    public static void requirePositiveOrNull(String field, BigDecimal value) {
        if (value == null) {
            return;
        }
        if (value.signum() <= 0) {
            throw field(field, "NON_POSITIVE", field + " 必须大于 0 或留空（留空表示不设限）", value);
        }
    }

    public static void requirePositive(String field, Integer value) {
        if (value != null && value <= 0) {
            throw field(field, "NON_POSITIVE", field + " 必须大于 0", value);
        }
    }

    /** DB 列宽是 TINYINT UNSIGNED（≤ 255）：先拒，错误才可读，而不是让 MySQL 报 1264。 */
    public static void requireRange(String field, Integer value, int min, int max) {
        if (value == null) {
            return;
        }
        if (value < min || value > max) {
            throw field(field, "OUT_OF_RANGE", field + " 取值范围是 " + min + "–" + max, value);
        }
    }

    /** 一批错误合成一条（前端一次看到全部问题，而不是修一个报一个）。 */
    public static BizException collect(List<FieldError> errors) {
        List<FieldError> copy = new ArrayList<>(errors);
        return fields("参数校验未通过", copy);
    }

    /** 供应商码不允许为空/未知；语义上属"后端没有这份配置"。 */
    public static BizException unknownProvider(String providerCode, Collection<String> known) {
        return field("providerCode", "UNKNOWN_PROVIDER",
                "该供应商未在后端白名单中（已配置：" + known + "）", providerCode);
    }
}
