package com.hfj.fitagent.common.error;

import com.hfj.fitagent.common.web.ApiResponse;
import com.hfj.fitagent.common.web.FieldError;
import com.hfj.fitagent.common.web.TraceIds;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 唯一的异常出口。目标有两个：
 * 1. 客户端永远拿到统一响应包，且永不看到堆栈、SQL、微信原始响应体；
 * 2. 校验失败必须结构化到字段，让页面能逐字段提示，而不是弹一个"参数错误"。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    ResponseEntity<ApiResponse<Object>> onBiz(BizException e) {
        ErrorCode code = e.errorCode();
        if (code == ErrorCode.INTERNAL_ERROR) {
            log.error("业务异常 code={}", code, e);
        } else {
            log.debug("业务异常 code={} message={}", code, e.getMessage());
        }
        return ResponseEntity.status(code.status())
                .body(ApiResponse.error(code.code(), e.getMessage(), normalizePayload(code, e.payload()), TraceIds.current()));
    }

    /** Bean Validation：@Valid 用在 controller 入参上失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Object>> onInvalidBody(MethodArgumentNotValidException e) {
        BindingResult results = e.getBindingResult();
        List<FieldError> fields = new ArrayList<>();
        for (org.springframework.validation.FieldError fe : results.getFieldErrors()) {
            fields.add(FieldError.of(fe.getField(), codeOf(fe), fe.getDefaultMessage(), fe.getRejectedValue()));
        }
        for (org.springframework.validation.ObjectError oe : results.getGlobalErrors()) {
            fields.add(FieldError.of(oe.getObjectName(), "GLOBAL_VALIDATION", oe.getDefaultMessage()));
        }
        return unprocessable(fields);
    }

    /** 方法参数级校验（@Validated + 约束注解）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiResponse<Object>> onConstraint(ConstraintViolationException e) {
        List<FieldError> fields = e.getConstraintViolations().stream()
                .map(this::toFieldError)
                .toList();
        return unprocessable(fields);
    }

    /**
     * 请求体解析失败。必须区分两种完全不同的客户端错误：
     * - JSON 本身不合法 → 400 MALFORMED_JSON（前端提示"格式错误"）；
     * - JSON 合法但含未定义字段 → 422 UNKNOWN_FIELD 并指出是哪个字段（契约 §1/§2，验收 A18）。
     * Jackson 3 的 FAIL_ON_UNKNOWN_PROPERTIES 抛出的异常会被 Spring 包成
     * HttpMessageNotReadableException，所以必须下钻 cause 链判断，不能一律报 400。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Object>> onUnreadable(HttpMessageNotReadableException e) {
        log.debug("请求体解析失败: {}", e.getMessage());
        String traceId = TraceIds.current();
        String unknownProperty = findUnknownProperty(e);
        if (unknownProperty != null) {
            Object payload = Map.of("fields", List.of(FieldError.of(unknownProperty, "UNKNOWN_FIELD",
                    "请求体包含未定义字段：" + unknownProperty)));
            return ResponseEntity.status(ErrorCode.UNKNOWN_FIELD.status())
                    .body(ApiResponse.error(ErrorCode.UNKNOWN_FIELD.code(), ErrorCode.UNKNOWN_FIELD.defaultMessage(),
                            payload, traceId));
        }
        return ResponseEntity.status(ErrorCode.MALFORMED_JSON.status())
                .body(ApiResponse.error(ErrorCode.MALFORMED_JSON.code(), ErrorCode.MALFORMED_JSON.defaultMessage(),
                        Map.of("fields", List.of(FieldError.of("body", "MALFORMED_JSON", "请求体不是合法 JSON"))), traceId));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResponse<Object>> onTypeMismatch(MethodArgumentTypeMismatchException e) {
        List<FieldError> fields = List.of(FieldError.of(e.getName(), "INVALID_TYPE", "参数类型不正确", e.getValue()));
        return unprocessable(fields);
    }

    /** 唯一索引冲突正常应由 Service 捕获并转成语义错误；漏到这里说明是意外重复，按 409 处理而不是 500。 */
    @ExceptionHandler({DuplicateKeyException.class, DataIntegrityViolationException.class})
    ResponseEntity<ApiResponse<Object>> onDuplicate(org.springframework.dao.DataAccessException e) {
        log.warn("数据约束冲突", e);
        return ResponseEntity.status(ErrorCode.DUPLICATE_REQUEST.status())
                .body(ApiResponse.error(ErrorCode.DUPLICATE_REQUEST.code(), ErrorCode.DUPLICATE_REQUEST.defaultMessage(),
                        null, TraceIds.current()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Object>> onUnexpected(Exception e) {
        String traceId = TraceIds.current();
        log.error("未预期异常 traceId={}", traceId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR.code(), ErrorCode.INTERNAL_ERROR.defaultMessage(),
                        null, traceId));
    }

    private ResponseEntity<ApiResponse<Object>> unprocessable(List<FieldError> fields) {
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(ApiResponse.error(ErrorCode.VALIDATION_FAILED.code(), ErrorCode.VALIDATION_FAILED.defaultMessage(),
                        Map.of("fields", fields), TraceIds.current()));
    }

    /**
     * 字段错误统一成 data.fields（契约 §1、前端 utils/errorMessage.ts 都按这个形状取）。
     *
     * 业务代码（PageQuery / DateRange / 各模块 FieldErrors）一律传 List&lt;FieldError&gt;，
     * 由这里单点包装：既避免每个模块各拼一次 Map，也避免前后端形状漂移。
     * 已经是 Map 的 payload（如导入的 errors/warnings、微信的 wechatErrcode）原样透传。
     */
    private static Object normalizePayload(ErrorCode code, Object payload) {
        if (payload instanceof List<?> list) {
            boolean allFieldErrors = list.stream().allMatch(FieldError.class::isInstance);
            if (allFieldErrors && code == ErrorCode.PLAN_IMPORT_INVALID) {
                return Map.of("errors", list);
            }
            return Map.of("fields", list);
        }
        return payload;
    }

    /** 下钻 cause 链找 Jackson 的未知属性异常，返回属性名；找不到返回 null。 */
    private static String findUnknownProperty(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof tools.jackson.databind.exc.UnrecognizedPropertyException unknown) {
                return unknown.getPropertyName();
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }

    private FieldError toFieldError(ConstraintViolation<?> v) {
        String path = v.getPropertyPath().toString();
        String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
        return FieldError.of(field, "VALIDATION_FAILED", v.getMessage(), v.getInvalidValue());
    }

    private static String codeOf(org.springframework.validation.FieldError fe) {
        return fe.getCode() == null ? "VALIDATION_FAILED" : fe.getCode().toUpperCase(Locale.ROOT);
    }
}
