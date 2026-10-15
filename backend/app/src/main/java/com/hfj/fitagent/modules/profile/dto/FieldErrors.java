package com.hfj.fitagent.modules.profile.dto;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.web.FieldError;

import java.util.ArrayList;
import java.util.List;

/**
 * profile 模块字段级错误的统一出口。
 *
 * 形状与 common 层已有用法保持一致（{@link com.hfj.fitagent.common.web.PageQuery} 与
 * {@link com.hfj.fitagent.common.web.DateRange} 都是把 {@code List<FieldError>} 直接作为 payload，
 * 由 GlobalExceptionHandler 放进 ApiResponse.data），前端拿到的是一个错误数组。
 *
 * <p>枚举校验刻意不用 Bean Validation 的 @Pattern：docs/p1-database.md §5 补充规则 1 要求
 * 非大写字面量必须判 {@code INVALID_ENUM_VALUE}，而 @Pattern 给不出这个稳定码。
 */
public final class FieldErrors {

    private FieldErrors() {
    }

    /** 单个字段错误 → 422 VALIDATION_FAILED。 */
    public static BizException field(String field, String code, String message, Object rejected) {
        return new BizException(ErrorCode.VALIDATION_FAILED, message,
                List.of(FieldError.of(field, code, message, rejected)));
    }

    public static BizException fields(String message, List<FieldError> errors) {
        return new BizException(ErrorCode.VALIDATION_FAILED, message, List.copyOf(errors));
    }

    /** 服务端生成字段出现在输入里：按契约报 FORBIDDEN_SERVER_FIELD，而不是静默忽略。 */
    public static BizException forbiddenServerField(String field, Object rejected) {
        String message = field + " 由服务端生成，不能在创建时指定";
        return new BizException(ErrorCode.FORBIDDEN_SERVER_FIELD, message,
                List.of(FieldError.of(field, "FORBIDDEN_SERVER_FIELD", message, rejected)));
    }

    /** 创建后不可变的字段被改写。 */
    public static BizException immutable(String field, Object rejected) {
        String message = field + " 创建后不可修改";
        return new BizException(ErrorCode.VALIDATION_FAILED, message,
                List.of(FieldError.of(field, "IMMUTABLE_FIELD", message, rejected)));
    }

    /** null 表示"未填写"，交由列默认值；非 null 必须精确命中大写字面量。 */
    public static void requireEnum(String field, String value, java.util.Set<String> allowed) {
        if (value == null) {
            return;
        }
        if (!allowed.contains(value)) {
            throw field(field, "INVALID_ENUM_VALUE", field + " 取值不在允许范围内", value);
        }
    }

    /** 把一批非法值合成一条错误，而不是遇到第一个就只报一条。 */
    public static void requireEnumList(String field, List<String> values, java.util.Set<String> allowed) {
        if (values == null) {
            return;
        }
        List<String> bad = new ArrayList<>();
        for (String v : values) {
            if (v == null || !allowed.contains(v)) {
                bad.add(v);
            }
        }
        if (!bad.isEmpty()) {
            throw field(field, "INVALID_ENUM_VALUE", field + " 含未定义代码", bad);
        }
    }
}
