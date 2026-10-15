/**
 * 唯一的 `uni.request` 封装（docs/p1-api.md §9）。页面一律通过 `src/api/*` 调用，禁止直接用 `uni.request`。
 *
 * 行为契约：
 *   - baseURL 由 `import.meta.env.VITE_API_BASE_URL` 注入，拼成 `{BASE}/api`，不硬编码；
 *   - 令牌从 `uni.getStorageSync('fa_token')` 读，统一加 `Authorization: Bearer <token>`；
 *   - 超时 10 s，超时归入「提交失败」态，**绝不静默成功**；
 *   - 401 / `UNAUTHENTICATED` / `SESSION_EXPIRED` → 清令牌 + `reLaunch` 登录页 + 仍然向调用方抛错
 *     （页面据此渲染「登录失效」态，提示保留一次）；
 *   - 仅幂等 GET 允许 1 次自动重试；写接口不自动重试（由用户显式重试，配合幂等键/UUID）；
 *   - 只 switch `code`，不解析 `message` 文案；`message` 原样展示给用户。
 */

import type { ApiEnvelope, ApiErrorCode, ApiFieldError, ApiPage } from '@/types/api'
import { clearToken, getToken, markSessionExpired } from '@/utils/storage'

const LOGIN_PAGE = '/pages/auth/login'
const DEFAULT_TIMEOUT_MS = 10000

/** 生产环境必须在发布配置里填真实 HTTPS 域名，否则宁可立刻失败也不发到相对地址。 */
export const API_BASE_URL: string = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/+$/, '')

function apiRoot(): string {
  if (!API_BASE_URL) {
    throw new ApiError({
      code: 'INTERNAL_ERROR',
      message: '未配置 VITE_API_BASE_URL：请在 miniapp/.env.<mode> 中填写后端地址（开发态为 http://127.0.0.1:8080）',
      httpStatus: 0
    })
  }
  return `${API_BASE_URL}/api`
}

/** 后端返回的结构化错误；页面据此区分「提交失败 / 登录失效 / 版本冲突 / 逐字段错误」。 */
export class ApiError extends Error {
  readonly code: ApiErrorCode
  readonly httpStatus: number
  readonly data: unknown
  readonly traceId: string | null
  readonly serverTime: string | null
  /** `VALIDATION_FAILED` 的逐字段错误（docs/p1-api.md §2） */
  readonly fields: ApiFieldError[] | null
  /** `RATE_LIMITED` 的 Retry-After（秒） */
  readonly retryAfter: number | null
  /** 传输层失败（断网/超时），HTTP 层面没有任何响应 */
  readonly isNetwork: boolean

  constructor(init: {
    code: ApiErrorCode
    message: string
    httpStatus: number
    data?: unknown
    traceId?: string | null
    serverTime?: string | null
    fields?: ApiFieldError[] | null
    retryAfter?: number | null
    isNetwork?: boolean
  }) {
    super(init.message)
    this.name = 'ApiError'
    this.code = init.code
    this.httpStatus = init.httpStatus
    this.data = init.data ?? null
    this.traceId = init.traceId ?? null
    this.serverTime = init.serverTime ?? null
    this.fields = init.fields ?? null
    this.retryAfter = init.retryAfter ?? null
    this.isNetwork = init.isNetwork ?? false
  }
}

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE'

export interface RequestOptions {
  /** 以 `/` 开头的接口路径，例如 `/me/profile` */
  url: string
  method: HttpMethod
  /** 查询参数：null/undefined/空串一律不发 */
  query?: Record<string, string | number | boolean | null | undefined>
  data?: unknown
  header?: Record<string, string>
  timeoutMs?: number
  /** 默认 true；登录类接口显式传 false，避免把过期令牌带上去 */
  withAuth?: boolean
  /** 会话失效时只清令牌、不 reLaunch（用于启动探活这类「静默」调用） */
  silentSessionRedirect?: boolean
}

function buildUrl(path: string, query?: RequestOptions['query']): string {
  const base = `${apiRoot()}${path.startsWith('/') ? path : `/${path}`}`
  if (!query) return base
  const parts: string[] = []
  Object.keys(query).forEach((key) => {
    const value = query[key]
    if (value === null || value === undefined) return
    const s = String(value)
    if (s === '') return
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(s)}`)
  })
  return parts.length > 0 ? `${base}?${parts.join('&')}` : base
}

function isEnvelopeShape(body: unknown): body is ApiEnvelope<unknown> {
  if (typeof body !== 'object' || body === null) return false
  // message 是给人看的文案，成功响应里后端就是给 null（ApiResponse.ok 不填文案，
  // 且 default-property-inclusion=always 会把 null 序列化出来）。
  // 早先这里要求 typeof message === 'string'，结果每一个成功响应都被判成 BAD_RESPONSE。
  return typeof (body as { code?: unknown }).code === 'string'
    && ((body as { message?: unknown }).message === undefined
      || (body as { message?: unknown }).message === null
      || typeof (body as { message?: unknown }).message === 'string')
}

function extractFields(data: unknown): ApiFieldError[] | null {
  if (data && typeof data === 'object') {
    const raw = (data as { fields?: unknown }).fields
    if (Array.isArray(raw)) {
      return raw
        .filter((f): f is Record<string, unknown> => typeof f === 'object' && f !== null)
        .map((f) => ({
          field: typeof f.field === 'string' ? f.field : String(f.field ?? ''),
          code: typeof f.code === 'string' ? f.code : String(f.code ?? ''),
          message: typeof f.message === 'string' ? f.message : String(f.message ?? '')
        }))
    }
  }
  return null
}

function parseRetryAfter(header: Record<string, string> | undefined): number | null {
  if (!header) return null
  const raw = header['Retry-After'] ?? header['retry-after']
  if (!raw) return null
  const n = Number(raw)
  return Number.isFinite(n) ? n : null
}

/** `uni.request` 的回调式 Promise 包装（uni-app 的 request 在部分快照版本不返回 Promise，统一自己包）。 */
function rawRequest(args: {
  url: string
  method: HttpMethod
  data: unknown
  header: Record<string, string>
  timeout: number
}): Promise<{ statusCode: number; body: unknown; header: Record<string, string> }> {
  return new Promise((resolve, reject) => {
    uni.request({
      url: args.url,
      method: args.method,
      data: args.data as never,
      header: args.header,
      timeout: args.timeout,
      success: (res) => {
        let body: unknown = res.data
        if (typeof body === 'string' && body.length > 0) {
          try {
            body = JSON.parse(body)
          } catch {
            /* 保持原样，交给 isEnvelopeShape 判定为 BAD_RESPONSE */
          }
        }
        resolve({
          statusCode: res.statusCode,
          body,
          header: (res.header ?? {}) as Record<string, string>
        })
      },
      fail: (err) => {
        const msg = typeof err?.errMsg === 'string' ? err.errMsg : '请求失败'
        const timeout = /timeout/i.test(msg)
        reject(
          new ApiError({
            code: timeout ? 'TIMEOUT' : 'NETWORK_ERROR',
            message: timeout
              ? '请求超时（10 秒），请检查后端是否已启动后重试'
              : `网络请求失败：${msg}`,
            httpStatus: 0,
            isNetwork: true
          })
        )
      }
    })
  })
}

/** 会话失效处理：同一时刻只跳一次，避免多个并发 401 造成连环 reLaunch。 */
let sessionRedirecting = false

function onSessionExpired(message: string, silentRedirect: boolean): void {
  clearToken()
  markSessionExpired(message || '登录已失效，请重新登录')
  if (silentRedirect || sessionRedirecting) return
  sessionRedirecting = true
  uni.reLaunch({
    url: LOGIN_PAGE,
    complete: () => {
      globalThis.setTimeout(() => {
        sessionRedirecting = false
      }, 1500)
    }
  })
}

/**
 * 发起一次请求并解包响应。
 * 任何非 `code === 'OK'` 的情况都会抛出 `ApiError`；调用方永远拿到「成功的数据」或「异常」，不存在第三种。
 */
export async function request<T>(options: RequestOptions): Promise<T> {
  const method = options.method
  const url = buildUrl(options.url, options.query)
  const withAuth = options.withAuth !== false
  const header: Record<string, string> = {
    'content-type': 'application/json; charset=utf-8',
    ...(options.header ?? {})
  }
  if (withAuth) {
    const token = getToken()
    if (token) header.Authorization = `Bearer ${token}`
  }

  // 仅幂等 GET 自动重试 1 次；写接口交给用户显式重试（配合幂等键）
  const maxAttempts = method === 'GET' ? 2 : 1

  let lastError: ApiError | null = null
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    try {
      const res = await rawRequest({
        url,
        method,
        data: options.data ?? undefined,
        header,
        timeout: options.timeoutMs ?? DEFAULT_TIMEOUT_MS
      })

      const status = res.statusCode
      const body = res.body

      // 401：契约里 UNAUTHENTICATED / SESSION_EXPIRED 都是 401（docs/p1-api.md §2）
      if (status === 401) {
        const code: ApiErrorCode = isEnvelopeShape(body) ? body.code : 'UNAUTHENTICATED'
        const message = isEnvelopeShape(body)
          ? body.message
          : '登录状态无效，请重新登录'
        const friendly =
          code === 'SESSION_EXPIRED' ? '登录已失效，请重新登录' : message || '登录状态无效，请重新登录'
        onSessionExpired(friendly, options.silentSessionRedirect === true)
        throw new ApiError({
          code: code === 'SESSION_EXPIRED' ? 'SESSION_EXPIRED' : 'UNAUTHENTICATED',
          message: friendly,
          httpStatus: status,
          data: isEnvelopeShape(body) ? body.data : null,
          traceId: isEnvelopeShape(body) ? body.traceId ?? null : null,
          serverTime: isEnvelopeShape(body) ? body.serverTime ?? null : null
        })
      }

      if (!isEnvelopeShape(body)) {
        // 响应不是契约规定的 `{code,message,data,traceId,serverTime}`：当作失败，不猜测成功
        throw new ApiError({
          code: 'BAD_RESPONSE',
          message: `后端响应格式不符合契约（HTTP ${status}）：期望 {code,message,data,traceId,serverTime}`,
          httpStatus: status,
          data: body
        })
      }

      if (body.code !== 'OK') {
        throw new ApiError({
          code: body.code,
          message: body.message || '请求失败',
          httpStatus: status,
          data: body.data,
          traceId: body.traceId ?? null,
          serverTime: body.serverTime ?? null,
          fields: extractFields(body.data),
          retryAfter: parseRetryAfter(res.header)
        })
      }

      // 走到这里才是成功
      return body.data as T
    } catch (err) {
      const apiErr =
        err instanceof ApiError
          ? err
          : new ApiError({
              code: 'INTERNAL_ERROR',
              message: err instanceof Error ? err.message : '未知错误',
              httpStatus: 0
            })

      const retryable =
        method === 'GET' &&
        attempt < maxAttempts &&
        (apiErr.isNetwork || apiErr.httpStatus === 0 || apiErr.httpStatus >= 500)

      lastError = apiErr
      if (retryable) continue
      throw apiErr
    }
  }

  // 理论不可达：循环要么 return 要么 throw
  throw lastError ?? new ApiError({ code: 'INTERNAL_ERROR', message: '请求未发出', httpStatus: 0 })
}

export function get<T>(
  url: string,
  query?: RequestOptions['query'],
  extra: Partial<RequestOptions> = {}
): Promise<T> {
  return request<T>({ url, method: 'GET', query, ...extra })
}

export function post<T>(url: string, data?: unknown, extra: Partial<RequestOptions> = {}): Promise<T> {
  return request<T>({ url, method: 'POST', data, ...extra })
}

export function put<T>(url: string, data?: unknown, extra: Partial<RequestOptions> = {}): Promise<T> {
  return request<T>({ url, method: 'PUT', data, ...extra })
}

export function del<T>(url: string, extra: Partial<RequestOptions> = {}): Promise<T> {
  return request<T>({ url, method: 'DELETE', ...extra })
}

/**
 * 分页体的数组字段读取。
 * 契约（docs/p1-api.md §1「分页」）只规定了 `data.page/size/total/hasNext`，没有为数组字段命名，
 * 这里按 `items → list → records → content` 顺序取第一个数组，后端定稿后收敛为单一字段即可。
 */
export function pageItems<T>(page: ApiPage<T> | null | undefined): T[] {
  return page ? page.items : []
}

/**
 * 分页元信息。`total` 在服务端没给时是 **null 而不是 0**：
 * 显示"共 0 条 / 已到最后一页（共 0 条）"是一种假数据，比"条数未知"更糟。
 * `hasNext` 缺失时保守取 false（宁可不给"加载更多"按钮，也不构造一个必定为空的下一页）。
 */
export function pageMeta(page: ApiPage<unknown> | null | undefined, requestedPage = 1, requestedSize = 20) {
  return {
    page: page?.page ?? requestedPage,
    size: page?.size ?? requestedSize,
    total: page?.total ?? null,
    hasNext: page?.hasNext ?? false
  }
}

/** 类型守卫，供页面区分五种状态。 */
export function isApiError(err: unknown): err is ApiError {
  return err instanceof ApiError
}

export function isSessionExpiredError(err: unknown): boolean {
  return isApiError(err) && (err.code === 'UNAUTHENTICATED' || err.code === 'SESSION_EXPIRED')
}

export function isVersionConflict(err: unknown): boolean {
  return isApiError(err) && err.code === 'VERSION_CONFLICT'
}
