package com.hfj.fitagent.common.error;

import org.springframework.http.HttpStatus;

/**
 * 对外稳定机器码。前端只 switch code，不解析 message 文案。
 * 字段级导入错误码不在这里，见 modules/training/import 的 PlanImportErrorCode。
 */
public enum ErrorCode {

    OK(HttpStatus.OK, "成功"),

    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "参数校验未通过"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "未登录"),
    SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录"),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "账户不可用"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),

    VERSION_CONFLICT(HttpStatus.CONFLICT, "数据已被修改，请刷新后重试"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "当前状态不允许该操作"),
    ACTIVE_PLAN_EXISTS(HttpStatus.CONFLICT, "已存在生效中的计划"),
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "请求已处理"),

    MALFORMED_JSON(HttpStatus.BAD_REQUEST, "不是合法的 JSON"),
    PLAN_IMPORT_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "训练计划校验未通过"),
    UNSUPPORTED_SCHEMA_VERSION(HttpStatus.UNPROCESSABLE_ENTITY, "模板版本不受支持"),
    FORBIDDEN_SERVER_FIELD(HttpStatus.UNPROCESSABLE_ENTITY, "该字段由服务端生成，不能指定"),
    UNKNOWN_FIELD(HttpStatus.UNPROCESSABLE_ENTITY, "存在未定义字段"),

    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "内容超出长度限制"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试"),

    WECHAT_LOGIN_FAILED(HttpStatus.BAD_GATEWAY, "微信登录失败"),
    WECHAT_CONFIG_MISSING(HttpStatus.INTERNAL_SERVER_ERROR, "后端未配置微信凭据"),

    // ---- 模型接入与费用（agent-v1）----
    /** 本人还没有可用的凭据 */
    CREDENTIAL_MISSING(HttpStatus.CONFLICT, "尚未配置模型 API Key"),
    /** 凭据存在但供应商拒绝：需要用户重新配置 */
    CREDENTIAL_INVALID(HttpStatus.CONFLICT, "模型 API Key 无效或已被撤销"),
    /** 后端没有可用的模型目录/价格配置（运维问题，不是用户问题） */
    MODEL_NOT_CONFIGURED(HttpStatus.INTERNAL_SERVER_ERROR, "后端未配置可用的模型与价格"),
    /** 供应商侧失败且结果不明 */
    PROVIDER_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "模型服务暂时不可用"),
    BALANCE_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "无法获取供应商账户余额"),
    /** 单次任务预算不足：本次调用不发起 */
    BUDGET_EXCEEDED_TASK(HttpStatus.CONFLICT, "本次任务预算不足，已停止后续模型调用"),
    /** 当日预算不足：本次调用不发起 */
    BUDGET_EXCEEDED_DAILY(HttpStatus.CONFLICT, "今日预算已用完，已停止后续模型调用"),

    // ---- 会话与 run（agent-v1）----
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "相同幂等键已用于另一个请求"),
    /** 同一会话已有活动 run（数据库唯一约束保护） */
    RUN_ALREADY_ACTIVE(HttpStatus.CONFLICT, "这个会话还有正在进行中的问题，请等待或先停止"),
    RUN_NOT_CANCELLABLE(HttpStatus.CONFLICT, "该任务已进入终态，无法取消"),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务暂时不可用");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return name();
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
