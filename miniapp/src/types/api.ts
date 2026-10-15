/**
 * 统一响应包与通用类型。
 * 字段与 docs/p1-api.md §1（响应包 / 分页 / BigInt / 时间）逐一对应。
 *
 * BigInt 约定：所有 `id`/`userId`/`dataRevision` 等 64 位整型在 JSON 中以**字符串**传输，
 * 前端一律当字符串使用，不参与算术（docs/p1-api.md §1「BigInt」+ §9）。
 */

/** 稳定机器码：前端只 switch `code`，不解析 `message` 文案（docs/p1-api.md §1、§2）。 */
export type ApiErrorCode =
  | 'OK'
  | 'VALIDATION_FAILED'
  | 'UNAUTHENTICATED'
  | 'SESSION_EXPIRED'
  | 'ACCOUNT_DISABLED'
  | 'RESOURCE_NOT_FOUND'
  | 'VERSION_CONFLICT'
  | 'INVALID_STATE_TRANSITION'
  | 'ACTIVE_PLAN_EXISTS'
  | 'MALFORMED_JSON'
  | 'PLAN_IMPORT_INVALID'
  | 'UNSUPPORTED_SCHEMA_VERSION'
  | 'FORBIDDEN_SERVER_FIELD'
  | 'UNKNOWN_FIELD'
  /** 模型接入与费用（docs/agent-v1-design.md §6 §10 §11） */
  | 'CREDENTIAL_MISSING'
  | 'CREDENTIAL_INVALID'
  | 'MODEL_NOT_CONFIGURED'
  | 'PROVIDER_UNAVAILABLE'
  | 'BALANCE_UNAVAILABLE'
  | 'BUDGET_EXCEEDED_TASK'
  | 'BUDGET_EXCEEDED_DAILY'
  | 'INSUFFICIENT_BALANCE'
  | 'WAITING_CREDENTIAL'
  | 'UNKNOWN_CHARGE'
  /** run 提交与会话并发（§10 §11） */
  | 'IDEMPOTENCY_KEY_CONFLICT'
  | 'RUN_ALREADY_ACTIVE'
  | 'RUN_NOT_CANCELLABLE'
  | 'PAYLOAD_TOO_LARGE'
  | 'RATE_LIMITED'
  | 'WECHAT_LOGIN_FAILED'
  | 'WECHAT_CONFIG_MISSING'
  | 'INTERNAL_ERROR'
  /** 封装层自身产生的错误（网络失败、超时、响应不是合法响应包），不是后端错误码。 */
  | 'NETWORK_ERROR'
  | 'TIMEOUT'
  | 'BAD_RESPONSE'
  /** 后端 §5 声明的工程护栏码：`measuredAt` 晚于当前时间 +5 分钟。 */
  | 'FUTURE_MEASURED_AT'
  | string

/** 所有响应同形：`{ code, message, data, traceId, serverTime }`。 */
export interface ApiEnvelope<T> {
  code: ApiErrorCode
  message: string
  /** 无数据时后端返回 `null`（例如 GET /api/me/profile 无档案、GET /api/plans/today 无 ACTIVE）。 */
  data: T | null
  traceId: string | null
  serverTime: string | null
}

/** `VALIDATION_FAILED` 的 `data.fields=[{field,code,message}]`（docs/p1-api.md §2）。 */
export interface ApiFieldError {
  field: string
  code: string
  message: string
}

/**
 * 从错误响应里读取逐字段错误，两种形状都要认：
 *   1. 契约 §2 写的 `data.fields = [{field,code,message}]`（`utils/request.ts` 已解析进 `ApiError.fields`）；
 *   2. 后端实际的 `data = [{field,code,message,rejected}]` —— `GlobalExceptionHandler.unprocessable()`
 *      与 `modules/profile/dto/FieldErrors.field()` 都是把 `List<FieldError>` 直接作为
 *      `ApiResponse.data` 下发（全仓 grep 无任何一处把字段错误包进 `fields` 键）。
 *
 * 只认第 1 种会让表单永远拿不到服务器的逐字段错误（「提交失败」态要求展示 `data.fields`），
 * 所以这里取并集并按 `field|code|message` 去重。纯函数，不猜测任何文案。
 */
export function readFieldErrors(data: unknown, existing?: ApiFieldError[] | null): ApiFieldError[] {
  const coerce = (raw: unknown): ApiFieldError[] =>
    Array.isArray(raw)
      ? raw
          .filter((x): x is Record<string, unknown> => typeof x === 'object' && x !== null)
          .map((x) => ({
            field: typeof x.field === 'string' ? x.field : String(x.field ?? ''),
            code: typeof x.code === 'string' ? x.code : String(x.code ?? ''),
            message: typeof x.message === 'string' ? x.message : String(x.message ?? '')
          }))
      : []

  const fromData = Array.isArray(data)
    ? coerce(data)
    : data && typeof data === 'object'
      ? coerce((data as { fields?: unknown }).fields)
      : []

  const out: ApiFieldError[] = (existing ?? []).slice()
  fromData.forEach((e) => {
    const duplicate = out.some((o) => o.field === e.field && o.code === e.code && o.message === e.message)
    if (!duplicate) out.push(e)
  })
  return out
}

/**
 * 取某个表单字段的服务器错误文案。
 * 后端的 `field` 就是请求体属性名（record 组件名 / Spring 字段名），嵌套时形如 `equipment[0]`；
 * 匹配「精确 / 前缀数组 / 末段属性名」三种形式，匹配不到返回空串（不编造错误）。
 */
export function fieldErrorText(errors: ApiFieldError[] | null | undefined, field: string): string {
  if (!errors || errors.length === 0) return ''
  const target = field.toLowerCase()
  const hit = errors.find((e) => {
    const f = (e.field || '').toLowerCase()
    return f === target || f.startsWith(`${target}[`) || f.startsWith(`${target}.`) || f.endsWith(`.${target}`)
  })
  if (!hit) return ''
  return hit.message || hit.code
}

/** 分页元信息 + 数据体（docs/p1-api.md §1「分页」：`data.page/size/total/hasNext`）。 */
export interface ApiPage<T> {
  page: number
  size: number
  total: number
  hasNext: boolean
  /**
   * 后端 `common.web.PageResult` 是 record `(page, size, total, hasNext, items)`，
   * JSON 里数组字段就叫 `items`。之前这里并列了 list/records/content 三个可选键来猜形状，
   * 现在契约已定稿，多余的猜测一律删掉——留着只会让真正的字段改名错误被静默吞掉。
   */
  items: T[]
}

/** 通用分页请求参数（`page` 从 1 起、`size` 默认 20 上限 100）。 */
export interface PageQuery {
  page?: number
  size?: number
  sort?: string
}

/** ISO-8601 UTC 瞬时值（`2026-10-13T07:20:00.000Z`）。 */
export type InstantIso = string

/** 业务日期 `YYYY-MM-DD`。 */
export type BusinessDate = string

/** 状态迁移类接口携带乐观锁（docs/p1-api.md §1「乐观锁」）。 */
export interface LockVersionParam {
  lockVersion: number
}

/**
 * 每个页面必须实现的 5 种状态（docs/p1-scope.md §4 末段）：
 * 加载中 / 无数据 / 提交失败（含服务端错误码文案）/ 操作成功 / 登录失效。
 * `error` 承载「加载失败」与「提交失败」（后者用 `StateBlock` 的 `submit` 属性叠在内容之上）。
 */
export type PageState = 'loading' | 'empty' | 'error' | 'success' | 'sessionExpired'
