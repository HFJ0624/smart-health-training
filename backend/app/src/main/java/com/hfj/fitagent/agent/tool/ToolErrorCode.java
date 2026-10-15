package com.hfj.fitagent.agent.tool;

/**
 * 工具结果的稳定机器码。前端与模型提示按它分支，不解析文案。
 *
 * <p>命名与 {@code common.error.ErrorCode} 保持可对话：工具内部失败最终要能映射成对外错误码，
 * 但工具体系不能反过来依赖 web 层枚举（agent/** 只依赖 common/**）。
 */
public final class ToolErrorCode {

    /** 成功。 */
    public static final String OK = "OK";
    /** arguments 不是合法 JSON，或与声明的 schema 结构不符（含未知字段）。 */
    public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
    /** 类型解析通过，但 Bean Validation 不通过。 */
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    /** 模型调用了白名单外/不存在/已下线的工具。 */
    public static final String UNKNOWN_TOOL = "UNKNOWN_TOOL";
    /** 工具参数试图携带身份或权威字段——结构上被禁止（不执行，不"信任一次试试"）。 */
    public static final String FORBIDDEN_IDENTITY_FIELD = "FORBIDDEN_IDENTITY_FIELD";
    /** 工具不在本次 run 的 allowedTools 白名单里。 */
    public static final String TOOL_NOT_ALLOWED = "TOOL_NOT_ALLOWED";
    /** 资源不存在或不属于当前用户（与"无权限"同形，不泄露存在性）。 */
    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    /** 执行超时。 */
    public static final String TIMEOUT = "TIMEOUT";
    /** 结果超出字节上限：明细被丢弃，dataComplete=false。 */
    public static final String RESULT_TOO_LARGE = "RESULT_TOO_LARGE";
    /** 本轮没有该能力（例如训练执行记录不存在）——如实说明，不用 0 或估算填补。 */
    public static final String CAPABILITY_UNAVAILABLE = "CAPABILITY_UNAVAILABLE";
    /** 工具内部异常。 */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ToolErrorCode() {
    }
}
