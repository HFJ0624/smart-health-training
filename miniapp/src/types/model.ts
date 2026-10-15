/**
 * 模型设置（BYOK）类型：DeepSeek 凭据摘要、供应商余额快照、本应用预算策略。
 *
 * 依据（唯一取值来源，不凭记忆）：
 *   - `docs/agent-v1-design.md` §6.1（凭据：任何读取接口只返回 id/状态/脱敏尾部/校验时间，
 *     **永不**返回密文、nonce、指纹、明文；替换=同一行 credential_version+1，撤销=清密文 + status=REVOKED）、
 *     §6.3（预算：保守预占 + 逐轮阻止，不是供应商硬额度；余额币种 ≠ 预算币种时分开显示、不猜汇率）、
 *     §11（接口清单）；
 *   - `docs/agent-v1-protocol.md` §7/§8（余额：三个金额字段是**字符串** → 展示即用字符串；
 *     `currency` 取值 `CNY | USD`；`is_available` 是布尔且**可为 null（未知）**；
 *     `is_expired` **不存在于当前文档**，因此本文件不声明、页面不显示）。
 *
 * 命名口径沿用 `src/types/profile.ts`：值类型 + 大写字面量常量表 + 展示文案 helper，
 * 页面不自己手写字符串。
 */

// ---------------------------------------------------------------------------
// 凭据
// ---------------------------------------------------------------------------

/** 凭据状态值域（design §6.1 + §11：读取只给状态，撤销后不可再发起新调用）。 */
export type ModelCredentialStatus = 'ACTIVE' | 'INVALID' | 'REVOKED'

export const CREDENTIAL_STATUS_LABELS: Record<ModelCredentialStatus, string> = {
  ACTIVE: '生效中（ACTIVE）',
  INVALID: '校验未通过（INVALID）',
  REVOKED: '已撤销（REVOKED）'
}

/**
 * 状态文案。`null` 是契约允许的取值（本轮接口清单里 status 可为 null = 服务端没有可用凭据行），
 * 未列出的字面量原样返回，不猜测语义。
 */
export function credentialStatusLabel(status: ModelCredentialStatus | null | undefined): string {
  if (status === null || status === undefined) return '未保存（null）'
  return CREDENTIAL_STATUS_LABELS[status] ?? status
}

/**
 * 凭据摘要（`GET/PUT/DELETE /api/model-credentials/deepseek` 的 data）。
 * 后端 `entity` 永不出现在响应里（AGENTS.md 架构边界），本类型即 VO 形状：
 * 只有 id / 状态 / 脱敏尾部 / 版本 / 时间戳，**没有任何字段能还原 Key**。
 */
export interface ModelCredentialSummary {
  id: string
  /** `model_provider.provider_code`，服务端规范化大写（如 DEEPSEEK），只读回显 */
  providerCode: string
  /** 展示名，不参与任何匹配；后端未给长度上限，页面不做本地长度判定 */
  label: string | null
  status: ModelCredentialStatus | null
  /** 最多 8 字符的尾部（design §6.1）；确认文案里只允许出现这个值 */
  keySuffix: string | null
  credentialVersion: number
  /** ISO-8601 UTC 瞬时值：服务端最近一次真实校验该凭据的时间 */
  validatedAt: string | null
  /** ISO-8601 UTC：撤销时间；未撤销为 null */
  revokedAt: string | null
}

/**
 * `PUT /api/model-credentials/deepseek` 请求体：只有 `apiKey` 与 `label` 两个键
 * （全局 FAIL_ON_UNKNOWN_PROPERTIES，多一个键即 422 `UNKNOWN_FIELD`）。
 * `apiKey` 不做大小写规范化，只去首尾空白；提交成功后页面立即清除内存里的值。
 */
export interface ModelCredentialSaveRequest {
  apiKey: string
  label: string | null
}

// ---------------------------------------------------------------------------
// 余额
// ---------------------------------------------------------------------------

/**
 * 余额快照（`GET /api/model-credentials/deepseek/balance` 的 data）。
 *
 * 语义边界（protocol §8 + design §6.3），页面上必须原样讲清楚：
 *   - 这是**供应商账户级**快照：可能被本应用之外的调用影响，也不是单把 Key 独享的额度；
 *   - 不是「本应用已花多少」，本应用的花费只看 coach 侧的 run 费用；
 *   - 三个金额是**字符串**（官方就是字符串），后端归一化后仍以字符串下发：页面不做任何算术，
 *     只按十进制字符串展示；
 *   - 查询失败**不清零**：拿不到就是 null，展示「暂无法获取」，绝不渲染 0。
 */
export interface ProviderBalance {
  /** 官方字段 `is_available`：余额是否足以继续调用。null = 本轮没拿到，不等于 false */
  isAvailable: boolean | null
  /** `CNY` | `USD`（protocol §8）；其它取值原样展示，不猜测 */
  currency: string | null
  totalBalance: string | null
  grantedBalance: string | null
  toppedUpBalance: string | null
  /** ISO-8601 UTC：这份快照的查询时间 */
  fetchedAt: string | null
  /** 服务端标记：这份快照不是本次实时结果（失败回落到上次快照时为 true） */
  stale: boolean
}

/** 「拿不到」的唯一展示口径：不写 0、不写空串、不写「—」以外的数字。 */
export const UNKNOWN_BALANCE_TEXT = '暂无法获取'

const DECIMAL_TEXT_RE = /^[+-]?(?:\d+(?:\.\d+)?|\.\d+)$/

export function currencyDisplayName(code: string | null | undefined): string {
  if (!code) return ''
  const upper = code.toUpperCase()
  if (upper === 'CNY') return '元（CNY）'
  if (upper === 'USD') return '美元（USD）'
  return code
}

/** 币种符号只用于给数字带上单位标签，**不做任何换算**（design §6.3：不猜汇率、不相减）。 */
export function currencyUnitLabel(code: string | null | undefined): string {
  const upper = (code ?? '').toUpperCase()
  if (upper === 'CNY') return '元'
  if (upper === 'USD') return '美元'
  return code ? code : ''
}

/**
 * 金额字符串展示：null/空串 → 「暂无法获取」；合法十进制串 → 「值 + 币种」；
 * 非预期形状则原样回显并标注未按十进制解析（不改造、不四舍五入、不做算术）。
 */
export function moneyText(value: string | null | undefined, currency: string | null | undefined): string {
  if (typeof value !== 'string') return UNKNOWN_BALANCE_TEXT
  const raw = value.trim()
  if (raw === '') return UNKNOWN_BALANCE_TEXT
  const unit = currencyDisplayName(currency)
  if (!DECIMAL_TEXT_RE.test(raw)) return `${raw}${unit ? ` ${unit}` : ''}（服务端原样，未按十进制解析）`
  return unit ? `${raw} ${unit}` : raw
}

/** `is_available` 三态文案：未知不等于「可用」，也不等于「不可用」。 */
export function balanceAvailabilityText(value: boolean | null | undefined): string {
  if (value === true) return 'is_available = true：供应商判断该账户余额足以继续调用'
  if (value === false) return 'is_available = false：供应商判断该账户余额已不足以继续调用（402 属供应商侧拒绝，本应用预算不能替它充值）'
  return `${UNKNOWN_BALANCE_TEXT}（本轮响应没有 is_available；不按「可用」也不按「不可用」处理）`
}

// ---------------------------------------------------------------------------
// 本应用预算
// ---------------------------------------------------------------------------

/**
 * 预算币种值域：protocol §8 只承认 `CNY | USD` 两种。
 * 选择币种只是「这份限额用哪个币种表达」，页面不做任何汇率换算，也不把两种币种的数字相减。
 */
export const BUDGET_CURRENCY_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'CNY', label: '人民币（CNY）' },
  { value: 'USD', label: '美元（USD）' }
]

/**
 * 预算策略（`GET/PUT /api/budgets/deepseek` 的 data）。
 * 限额为 null = 不设限（design §6.3 的可用额度算式里，没有该限额即不参与判定）。
 */
export interface BudgetPolicy {
  currency: string
  maxTaskCost: number | null
  maxDailyCost: number | null
  maxModelCalls: number | null
  maxToolCalls: number | null
  maxOutputTokens: number | null
  enabled: boolean
  /** 乐观锁：PUT 必须回传刚读取到的值，不符 409 `VERSION_CONFLICT` */
  lockVersion: number
}

/**
 * `PUT /api/budgets/deepseek` 请求体（§11 只有 GET/PUT，PUT 兼作首次创建）。
 * `lockVersion` 在还没有策略行时按 0 提交（「不存在即版本 0」的乐观锁惯例），
 * 服务端不认可时会给出结构化错误，页面如实展示，不本地兜底。
 */
export interface BudgetUpdateRequest {
  currency: string
  maxTaskCost: number | null
  maxDailyCost: number | null
  maxModelCalls: number | null
  maxToolCalls: number | null
  maxOutputTokens: number | null
  enabled: boolean
  lockVersion: number
}

/** 预算限额的展示口径：null 是「不设限」，不是 0。 */
export function budgetLimitText(value: number | null | undefined, unit = ''): string {
  if (value === null || value === undefined) return '不设限'
  return unit ? `${value} ${unit}` : String(value)
}

export function budgetEnabledText(enabled: boolean | null | undefined): string {
  if (enabled === null || enabled === undefined) return UNKNOWN_BALANCE_TEXT
  return enabled ? '已启用（超限即在发起前拦截）' : '已关闭（限额不参与判定，只受供应商侧余额与限流约束）'
}
