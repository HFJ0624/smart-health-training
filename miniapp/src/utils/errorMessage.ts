/**
 * 错误码 -> 用户可见文案（docs/p1-api.md §2 错误码总表的「前端处理」列）。
 *
 * 原则：只 switch `code`，不解析 `message` 文案；但服务端的 `message` 原样展示给用户
 * （5 态里的「提交失败」要求显示服务器 message），`INTERNAL_ERROR` 额外展示 `traceId`。
 */

import { isApiError, ApiError } from '@/utils/request'
import type { ApiFieldError } from '@/types/api'

export interface FailureView {
  /** 一行结论（按 §2 的前端处理口径生成） */
  title: string
  /** 服务端 message 原文 + 必要的补充说明 */
  detail: string
  /** 稳定机器码：页面 switch 它，展示时原样给出（§1「响应包」） */
  code: string
  traceId: string | null
  /** 逐字段错误（VALIDATION_FAILED 的 data.fields） */
  fields: ApiFieldError[]
  /** 结构化导入错误（data.errors / data.warnings） */
  importErrors: Array<{ path: string; code: string; message: string }>
  importWarnings: Array<{ path: string; code: string; message: string }>
  /** 会话失效：页面渲染第 5 态 */
  sessionExpired: boolean
  /** 乐观锁冲突：页面据此提示「重新加载」 */
  versionConflict: boolean
  /** 状态机不允许：页面据此刷新详情 */
  invalidTransition: boolean
  /** 已有 ACTIVE 计划：激活类操作引导二次确认替换 */
  activePlanExists: boolean
  retryAfter: number | null
}

/** §3.1 微信错误码映射：只针对 `WECHAT_LOGIN_FAILED` 的 `data.wechatErrcode`。 */
export function wechatErrcodeText(errcode: unknown): string {
  const n = typeof errcode === 'number' ? errcode : Number(errcode)
  if (!Number.isFinite(n)) return ''
  if (n === 40029) return '登录凭证无效，请重试'
  if (n === 45011) return '操作过于频繁，请稍后再试'
  if (n === 40226) return '该账号登录被拦截'
  if (n === -1) return '微信服务繁忙，请稍后重试'
  return `微信侧返回错误码 ${n}`
}

/** code -> 结论文案（§2 表）。未列出的 code 走通用文案，不猜语义。 */
function headlineOf(code: string): string {
  switch (code) {
    case 'VALIDATION_FAILED':
      return '提交内容不符合校验规则'
    case 'UNAUTHENTICATED':
      return '登录状态无效，请重新登录'
    case 'SESSION_EXPIRED':
      return '登录已失效，请重新登录'
    case 'ACCOUNT_DISABLED':
      return '账户已被停用，需要退出登录'
    case 'RESOURCE_NOT_FOUND':
      return '数据不存在或已变更，请刷新'
    case 'VERSION_CONFLICT':
      return '页面数据已过期（他人已修改），请重新加载后再试'
    case 'INVALID_STATE_TRANSITION':
      return '当前状态不允许该操作，已为你刷新数据'
    case 'ACTIVE_PLAN_EXISTS':
      return '已有生效中的计划'
    case 'MALFORMED_JSON':
      return '不是合法的 JSON，请检查语法'
    case 'PLAN_IMPORT_INVALID':
      return '训练计划校验未通过'
    case 'UNSUPPORTED_SCHEMA_VERSION':
      return '模板版本不受支持，请下载新模板'
    case 'FORBIDDEN_SERVER_FIELD':
      return '输入包含服务端生成的字段，请移除'
    case 'PAYLOAD_TOO_LARGE':
      return '内容过大，请精简后重试'
    case 'RATE_LIMITED':
      return '操作过于频繁，请稍后再试'
    case 'WECHAT_LOGIN_FAILED':
      return '微信登录失败'
    case 'WECHAT_CONFIG_MISSING':
      return '后端未配置微信 AppID/AppSecret，这不是你能修复的问题，请联系维护者'
    case 'INTERNAL_ERROR':
      return '服务暂时不可用'
    case 'TIMEOUT':
      return '请求超时'
    case 'NETWORK_ERROR':
      return '网络不可用'
    case 'BAD_RESPONSE':
      return '后端响应格式不符合契约'
    case 'FUTURE_MEASURED_AT':
      return '测量时间不能晚于当前时间 5 分钟以上'
    case 'UNKNOWN_FIELD':
      return '请求里有多余字段'
    case 'CREDENTIAL_MISSING':
      return '还没有配置模型 API Key'
    case 'CREDENTIAL_INVALID':
      return 'API Key 无效或已被撤销，请重新配置'
    case 'MODEL_NOT_CONFIGURED':
      return '后端还没有可用的模型与价格配置'
    case 'PROVIDER_UNAVAILABLE':
      return '模型服务暂时不可用'
    case 'BALANCE_UNAVAILABLE':
      return '无法获取供应商账户余额'
    case 'BUDGET_EXCEEDED_TASK':
      return '本次任务预算不足，已停止后续模型调用'
    case 'BUDGET_EXCEEDED_DAILY':
      return '今日预算已用完，已停止后续模型调用'
    case 'INSUFFICIENT_BALANCE':
      return '供应商账户余额不足，请充值后重试'
    case 'WAITING_CREDENTIAL':
      return '等待你配置可用的 API Key'
    case 'UNKNOWN_CHARGE':
      return '有请求已发送但结果未知，费用待确认'
    case 'IDEMPOTENCY_KEY_CONFLICT':
      return '这个幂等键已用于另一个问题'
    case 'RUN_ALREADY_ACTIVE':
      return '这个会话还有正在进行中的问题'
    case 'RUN_NOT_CANCELLABLE':
      return '任务已进入终态，无法取消'
    default:
      return '操作失败'
  }
}

function readIssueArray(err: ApiError, key: 'errors' | 'warnings') {
  const data = err.data
  const raw =
    data && typeof data === 'object' ? (data as Record<string, unknown>)[key] : undefined
  if (!Array.isArray(raw)) return []
  return raw
    .filter((x): x is Record<string, unknown> => typeof x === 'object' && x !== null)
    .map((x) => ({
      path: typeof x.path === 'string' ? x.path : '',
      code: typeof x.code === 'string' ? x.code : String(x.code ?? ''),
      message: typeof x.message === 'string' ? x.message : String(x.message ?? '')
    }))
}

export function toFailureView(err: unknown): FailureView {
  if (!isApiError(err)) {
    return {
      title: '操作失败',
      detail: err instanceof Error ? err.message : String(err),
      code: 'CLIENT_ERROR',
      traceId: null,
      fields: [],
      importErrors: [],
      importWarnings: [],
      sessionExpired: false,
      versionConflict: false,
      invalidTransition: false,
      activePlanExists: false,
      retryAfter: null
    }
  }

  const code = err.code
  let detail = err.message || ''

  if (code === 'WECHAT_LOGIN_FAILED') {
    const data = err.data as { wechatErrcode?: unknown } | null
    const mapped = wechatErrcodeText(data?.wechatErrcode)
    if (mapped) detail = detail ? `${detail}（${mapped}）` : mapped
  }

  if (code === 'RATE_LIMITED' && err.retryAfter) {
    detail = detail ? `${detail}（约 ${err.retryAfter} 秒后可重试）` : `约 ${err.retryAfter} 秒后可重试`
  }

  return {
    title: headlineOf(code),
    detail,
    code,
    // 契约明确 INTERNAL_ERROR 展示 traceId、不返回堆栈（§2）；其它码也带上 traceId 便于报障
    traceId: err.traceId,
    fields: err.fields ?? [],
    importErrors: readIssueArray(err, 'errors'),
    importWarnings: readIssueArray(err, 'warnings'),
    sessionExpired: code === 'UNAUTHENTICATED' || code === 'SESSION_EXPIRED',
    versionConflict: code === 'VERSION_CONFLICT',
    invalidTransition: code === 'INVALID_STATE_TRANSITION',
    activePlanExists: code === 'ACTIVE_PLAN_EXISTS',
    retryAfter: err.retryAfter
  }
}

/** 一行摘要，用于 toast。 */
export function briefError(err: unknown): string {
  const view = toFailureView(err)
  return view.detail ? `${view.title}：${view.detail}` : view.title
}
