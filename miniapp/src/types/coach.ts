/**
 * AI 教练（自研 Agent 第一版）前端类型。
 *
 * 唯一依据：`docs/agent-v1-design.md` §9（事实/引用/陈旧）、§10（run 状态集与取消语义）、
 * §11（接口清单）、§12（小程序要求）、§3（`AgentAnswer` 契约）、`docs/agent-v1-protocol.md` §6
 * （用量缺字段填 null 不填 0）、`docs/agent-v1-baseline.md` §3.3（本轮只有三个真实工具）。
 *
 * 通用口径沿用 `types/api.ts`：64 位整型（`id` / `runId` / `dataRevision`）在 JSON 里是**字符串**，
 * 前端不参与算术；瞬时值是 ISO-8601 UTC；金额是 `DECIMAL`，用 `string | number` 宽容接收后按
 * 定点口径格式化，绝不把「未知」渲染成 0。
 */

import type { InstantIso } from './api'

/* ==========================================================================
   会话
   ========================================================================== */

/**
 * `chat_conversation.status`（design §7 V17）。本轮代码只出现「新建后 ACTIVE」与「archive → 归档」
 * 两种真实形态，其余取值原样显示不猜语义。
 */
export type ConversationStatus = 'ACTIVE' | 'ARCHIVED' | string

export const CONVERSATION_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '进行中',
  ARCHIVED: '已归档'
}

export function conversationStatusText(status: string | null | undefined): string {
  if (!status) return '未知状态'
  return CONVERSATION_STATUS_LABELS[status.toUpperCase()] ?? status
}

/** `POST /api/coach/conversations` 与 `GET /api/coach/conversations` 的会话行（design §11）。 */
export interface CoachConversation {
  id: string
  title: string
  status: ConversationStatus
  lastMessageAt: InstantIso | null
}

/* ==========================================================================
   消息
   ========================================================================== */

/** `chat_message.role`（design §8：历史回放只取 user + 最终可见 assistant）。大小写宽容比较。 */
export type CoachMessageRole = 'user' | 'assistant' | string

/**
 * `GET /api/coach/conversations/{id}/messages` 的消息行（V22：role、正文、run_id、结构化列）。
 * `factCards` / `missingFields` / `isStale` 是可选的结构化列：后端不下发时前端**不补造**，
 * 历史消息就只显示服务端存下来的正文。
 */
export interface CoachMessage {
  id: string
  conversationId?: string | null
  /** 会话内单调序号，服务端在会话行锁下原子分配（design §8） */
  messageNo: number
  role: CoachMessageRole
  content: string
  /** 该消息由哪个 run 产生；user 消息也可能带（提交时即绑定） */
  runId?: string | null
  createdAt?: InstantIso | null
  factCards?: FactCard[] | null
  missingFields?: string[] | null
  isStale?: boolean | null
  dataRevision?: string | null
}

export function isUserMessage(role: string | null | undefined): boolean {
  return (role || '').toLowerCase() === 'user'
}

export function isAssistantMessage(role: string | null | undefined): boolean {
  return (role || '').toLowerCase() === 'assistant'
}

/* ==========================================================================
   事实卡片与回答（design §3 / §9）
   ========================================================================== */

/**
 * 服务端组装的事实卡片：`AnswerAssembler` 只按引用取**工具提供的**值/单位/日期。
 * 模型的自由文本永远不是个人数值的来源；卡片缺席时前端也不合成。
 */
export interface FactCard {
  id: string
  label: string
  /** 服务端原值：数字或文本（例如「生效中」「休息日」）；null = 无已确认记录，不得渲染成 0 */
  value: string | number | null
  unit: string | null
  /** 来源日期 / 瞬时值（业务日期或 UTC 串，服务端给什么显示什么） */
  asOf: string | null
  sourceTable: string | null
  sourceId: string | null
  ruleVersion: string | null
}

/** `AgentAnswer`（design §3）。`evidenceRefs` 是本次 run 证据集合里存在并被校验过的 ID。 */
export interface AgentAnswer {
  answerText: string
  factCards: FactCard[]
  evidenceRefs: string[]
  missingFields: string[]
  dataRevision: string | null
  isStale: boolean
  runStatus: RunStatus | string
  stopReason: string | null
  /** 契约未定形（design §3 只写「usageSummary」）：只按已知键展示，未知键原样列出，不猜语义 */
  usageSummary: Record<string, unknown> | null
  /**
   * §11.1 第 1 条已定稿：金额只在 run 状态响应顶层。`result_payload` 里不再写这两个键，
   * 下面三个可选字段只为兼容历史快照存在，页面一律以 run 顶层为准（chat.vue `costKnownOf()`）。
   */
  costKnown?: string | number | null
  costUncertain?: string | number | null
  currency?: string | null
}

/* ==========================================================================
   run 状态机（design §10）
   ========================================================================== */

export type RunStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'BUDGET_STOPPED'
  | 'LIMIT_STOPPED'
  | 'DEADLINE_STOPPED'
  | 'WAITING_CREDENTIAL'
  | 'COST_UNKNOWN'
  | string

/** design §10 的完整状态集：除 QUEUED / RUNNING 外都是终态（终态不可被普通重试改回 RUNNING）。 */
export const NON_TERMINAL_RUN_STATUSES = ['QUEUED', 'RUNNING'] as const

export function isTerminalRunStatus(status: string | null | undefined): boolean {
  if (!status) return false
  const s = status.toUpperCase()
  return !NON_TERMINAL_RUN_STATUSES.includes(s as 'QUEUED' | 'RUNNING')
}

export const RUN_STATUS_LABELS: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '正在处理',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
  BUDGET_STOPPED: '预算上限停止',
  LIMIT_STOPPED: '调用次数上限停止',
  DEADLINE_STOPPED: '超时停止',
  WAITING_CREDENTIAL: '等待有效 API Key',
  COST_UNKNOWN: '存在用量不明'
}

export function runStatusText(status: string | null | undefined): string {
  if (!status) return '未知状态'
  return RUN_STATUS_LABELS[status.toUpperCase()] ?? status
}

/**
 * 停止原因（契约取值见 docs/agent-v1-design.md §11.1 第 8 条，11 个取值 + 库里成功态映射）。
 * 未列出的取值一律原样显示机器码：宁可不翻译，也不把不认识的原因猜成一个认识的原因。
 */
export const STOP_REASON_LABELS: Record<string, string> = {
  COMPLETED: '已完成并给出回答',
  NO_CREDENTIAL: '没有可用的 API Key，本次未发起任何模型调用',
  BUDGET_STOPPED: '预算闸门在发起调用前拒绝，未新增费用',
  LIMIT_STOPPED: '达到本次任务的调用次数或结果长度上限',
  DEADLINE_STOPPED: '超过本次任务时间上限，已停止后续调用',
  CANCELLED: '已请求取消；已经发出的调用不因此免费',
  REPETITIVE_LOOP: '检测到重复请求或重复工具读取，已停止以免空转烧预算',
  PROTOCOL_INCOMPLETE: '供应商返回内容不完整，或工具消息配对不合法',
  PROVIDER_ERROR: '供应商侧失败（401/402/429/500/503 等）',
  LOOP_EXHAUSTED: '达到允许的模型调用次数仍未拿到可用回答',
  UNKNOWN_CHARGE: '已发送的调用用量不明：保留不确定预占，未重发'
}

export function stopReasonText(reason: string | null | undefined): string {
  if (!reason) return ''
  return STOP_REASON_LABELS[reason.toUpperCase()] ?? reason
}

/**
 * 每种停止原因对应的下一步；没有可做的动作就不给按钮（不放假入口）。
 * 停止态只有一行文字是不够的：用户需要知道现在能做什么。
 */
export function stopReasonAction(reason: string | null | undefined): string | null {
  switch ((reason || '').toUpperCase()) {
    case 'NO_CREDENTIAL':
    case 'CREDENTIAL_INVALID':
      return '去模型设置配置 API Key'
    case 'BUDGET_STOPPED':
      return '去模型设置调整预算'
    case 'UNKNOWN_CHARGE':
    case 'COST_UNKNOWN':
      return '本次已发生的费用按实际用量记录，可在模型设置查看预算余量'
    default:
      return null
  }
}

/** 停止原因是否需要用错误色带强调（其余按普通说明处理）。 */
export function stopReasonIsBlocking(reason: string | null | undefined): boolean {
  return ['NO_CREDENTIAL', 'BUDGET_STOPPED', 'PROVIDER_ERROR', 'PROTOCOL_INCOMPLETE', 'UNKNOWN_CHARGE', 'COST_UNKNOWN', 'LOOP_EXHAUSTED']
    .includes((reason || '').toUpperCase())
}

/** `POST /api/coach/conversations/{id}/runs` 的 202 响应体。 */
export interface RunSubmitResult {
  runId: string
  status: RunStatus
  estimatedCost: string | number | null
  currency: string | null
  /** §11.1 第 2 条：estimate / runs / run 三处都必须带该标记，缺失时页面显示「无法确认」 */
  conversationId?: string | null
  idempotencyKey?: string | null
  fakeModel?: boolean | null
}

/** `GET /api/coach/runs/{id}`：状态 + 进度摘要 + 持久化结果 + 已知/未知费用（design §11）。 */
export interface RunState {
  runId?: string | null
  conversationId?: string | null
  status: RunStatus
  stopReason: string | null
  /** 服务端进度摘要；契约未定形，页面只原样显示文本 */
  progress: string | Record<string, unknown> | null
  /** 终态时服务端持久化的完整回答（V18 `result_payload`）；进行中为 null */
  answer: AgentAnswer | null
  costKnown: string | number | null
  costUncertain: string | number | null
  currency: string | null
  isStale: boolean
  /** run 顶层错误码（§11.1 载荷契约）：页面必须显示，否则失败只剩一个状态字 */
  errorCode?: string | null
  /** §11.1 第 2 条要求的模拟模型标记 */
  fakeModel?: boolean | null
  createdAt: InstantIso | null
  finishedAt: InstantIso | null
  /**
   * 简报里 `GET /coach/runs/{id}` 的字段清单没有「发送前预估」，但 `agent_run` 有 estimated 费用列
   * （design §7）。后端若下发 `estimatedCost` 就用它复现发送前预估；缺失时页面显示「本页未保留预估值」，
   * 不拿已核算金额冒充预估。
   */
  estimatedCost?: string | number | null
}

/** `GET /api/coach/runs/{id}/steps`：脱敏操作摘要（V21：`arguments_redacted` / `result_summary`）。 */
export interface RunStep {
  stepNo?: number | null
  runId?: string | null
  /** 步骤类型（如 MODEL_CALL / TOOL_CALL）；未在契约里定形，原样显示 */
  stepType?: string | null
  toolName?: string | null
  /** 面向用户的脱敏摘要：优先服务端 summary，再退到 result_summary */
  summary?: string | null
  resultSummary?: string | null
  status?: string | null
  errorCode?: string | null
  createdAt?: InstantIso | null
  durationMs?: number | null
}

export function stepSummaryText(step: RunStep): string {
  const s = (step.summary ?? '').trim()
  if (s) return s
  const r = (step.resultSummary ?? '').trim()
  if (r) return r
  if (step.toolName) return toolLabel(step.toolName)
  return '（服务端未提供该步摘要）'
}

/* ==========================================================================
   本轮真实存在的工具（baseline §3.3 / design §1）
   ========================================================================== */

/**
 * 本轮注册表里只有这三个工具；`get_workout_records` / `get_training_progress` /
 * `get_weekly_training_summary` **不存在**（训练执行记录业务缺失），
 * 因此快捷问题也绝不提供「今天完成度如何」这类无工具可答的入口。
 */
/** 名字与后端 CoachToolNames 逐字一致；新增工具时这里必须同步，否则操作摘要会露出英文原名。 */
export const COACH_TOOL_LABELS: Record<string, string> = {
  get_profile: '读取档案与目标',
  get_training_plan: '查询当天计划',
  get_weight_trend: '读取体重记录',
  get_training_progress: '查询当天训练完成',
  get_weekly_training_summary: '查询本周训练统计'
}

export function toolLabel(name: string | null | undefined): string {
  if (!name) return '未知操作'
  return COACH_TOOL_LABELS[name.toLowerCase()] ?? name
}

/* ==========================================================================
   发送前预估（design §11：不调用收费模型）
   ========================================================================== */

/**
 * `POST /api/coach/conversations/{id}/estimate`。
 * `credentialStatus` 取值与 `model_credential.status` 同源（V13/design §6.1），
 * 未列出的取值原样显示。
 */
export interface CoachEstimate {
  /** 预估假设：契约未定形（字符串数组 or 键值对象数组），`assumptionLines()` 两种都认 */
  assumptions: Array<string | Record<string, unknown>> | Record<string, unknown> | null
  maxModelCalls: number | null
  maxToolCalls: number | null
  estimatedCostLow: string | number | null
  estimatedCostHigh: string | number | null
  currency: string | null
  /** 预算余额币种；契约只给了一个 `currency`，有独立字段时用之，没有就同 `currency` */
  budgetCurrency?: string | null
  availableTools: string[]
  credentialStatus: string | null
  budgetRemainingTask: string | number | null
  budgetRemainingDaily: string | number | null
  /**
   * design §12 要求「Fake 模式在页面上必须可见标注」，但 §11 的 estimate / run 载荷字段清单里
   * **没有给出这个标记的字段名**。这里按最可能的命名宽容接收（`fakeModeOf()` 会试多个候选键）；
   * 后端定稿后应收敛成单一字段。缺失时页面显示「无法确认」，不当成「不是模拟」。
   */
  fakeModel?: boolean | string | null
}

export const CREDENTIAL_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '已配置',
  MISSING: '未配置',
  INVALID: '已校验为无效',
  REVOKED: '已撤销'
}

export function credentialStatusText(status: string | null | undefined): string {
  if (!status) return '未知'
  return CREDENTIAL_STATUS_LABELS[status.toUpperCase()] ?? status
}

export function credentialIsUsable(status: string | null | undefined): boolean {
  return (status || '').toUpperCase() === 'ACTIVE'
}

/** 假设列表：字符串直接取，对象按 `key: value` 原样列出（不改写、不翻译、不补造）。 */
export function assumptionLines(estimate: CoachEstimate | null): string[] {
  if (!estimate) return []
  const raw = estimate.assumptions
  if (Array.isArray(raw)) {
    return raw
      .map((item) => {
        if (typeof item === 'string') return item.trim()
        if (item && typeof item === 'object') return kvLines(item).join('，')
        return ''
      })
      .filter((s) => s.length > 0)
  }
  if (raw && typeof raw === 'object') {
    return Object.keys(raw)
      .map((k) => `${k}：${scalarText((raw as Record<string, unknown>)[k])}`)
      .filter((s) => !s.endsWith('：'))
  }
  return []
}

/* ==========================================================================
   金额展示（design §12 + protocol §6：缺字段填 null 不填 0）
   ========================================================================== */

export type Money = string | number | null | undefined

/** 费用显示精度：0.0001 元（低于它一律写「低于 0.0001 元」，不写 0.00）。 */
export const MONEY_DISPLAY_PRECISION = 4

export const MONEY_UNKNOWN_TEXT = '未知（服务端未给出该金额）'

export function moneyOf(raw: Money): number | null {
  if (raw === null || raw === undefined) return null
  if (typeof raw === 'number') return Number.isFinite(raw) ? raw : null
  const trimmed = String(raw).trim()
  if (!trimmed) return null
  const n = Number(trimmed)
  return Number.isFinite(n) ? n : null
}

function currencyUnit(currency: string | null | undefined): string {
  const code = (currency || '').trim().toUpperCase()
  if (code === 'CNY') return '元'
  if (code === 'USD') return '美元'
  // 未知币种代码原样给出；完全没有币种时不加单位（避免把币种猜成人民币）
  return code
}

/** 金额 -> 文案。null/非法 -> 「未知」，绝不输出 0 表示未知。 */
export function formatMoney(raw: Money, currency?: string | null): string {
  const n = moneyOf(raw)
  if (n === null) return MONEY_UNKNOWN_TEXT
  const unit = currencyUnit(currency)
  const suffix = unit ? ` ${unit}` : ''
  const threshold = 10 ** -MONEY_DISPLAY_PRECISION
  if (n !== 0 && Math.abs(n) < threshold) return `低于 0.0001${suffix}`
  const fixed = n.toFixed(MONEY_DISPLAY_PRECISION)
  // toFixed 可能把极小非零值压成 0.0000：再拦一次
  if (n !== 0 && Number(fixed) === 0) return `低于 0.0001${suffix}`
  return `${fixed}${suffix}`
}

/** 两个金额是否同币种（不同币种绝不合并展示、不折算汇率：design §6.3）。 */
export function sameCurrency(a: string | null | undefined, b: string | null | undefined): boolean {
  return (a || '').trim().toUpperCase() === (b || '').trim().toUpperCase()
}

/* ==========================================================================
   Fake 模型标记（design §12 + §13：Fake 只在 dev，且页面必须可见标注）
   ========================================================================== */

const FAKE_BOOLEAN_KEYS = ['fakeModel', 'isFakeModel', 'fake', 'fakeMode', 'isFake', 'mockModel', 'simulated']
const FAKE_MODE_VALUES = ['FAKE', 'FAKE_MODEL', 'MOCK', 'SIMULATED']

/**
 * 从 run / estimate 载荷里读「本次是否模拟模型」。
 * 返回 `null` 表示**载荷里根本没有这个标记**：此时页面必须如实说明无法确认，
 * 不允许把「没有标记」当成「不是 Fake」。
 */
export function fakeModeOf(payload: unknown): boolean | null {
  if (!payload || typeof payload !== 'object') return null
  const record = payload as Record<string, unknown>
  for (const key of FAKE_BOOLEAN_KEYS) {
    const value = record[key]
    if (typeof value === 'boolean') return value
    if (typeof value === 'string' && value.trim()) {
      const token = value.trim().toUpperCase()
      if (token === 'TRUE') return true
      if (token === 'FALSE') return false
      if (FAKE_MODE_VALUES.includes(token)) return true
    }
  }
  for (const key of ['modelMode', 'mode', 'providerCode', 'modelProvider', 'adapterCode']) {
    const value = record[key]
    if (typeof value === 'string' && FAKE_MODE_VALUES.includes(value.trim().toUpperCase())) return true
  }
  return null
}

export function fakeModeText(flag: boolean | null): string {
  if (flag === true) return '模拟模型：本次回答由 dev FakeModelClient 产生，不是真实 DeepSeek 调用，费用与供应商用量均未发生。'
  if (flag === false) return '真实供应商调用（DeepSeek）。'
  return '后端未返回模拟模型标记，无法确认本次是真实 DeepSeek 调用还是 Fake 模型。'
}

/* ==========================================================================
   通用小工具（展示服务端原值，不猜语义）
   ========================================================================== */

export function stringList(raw: unknown): string[] {
  if (!Array.isArray(raw)) return []
  return raw
    .map((x) => (typeof x === 'string' ? x.trim() : scalarText(x)))
    .filter((s) => s.length > 0)
}

function scalarText(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') return value.trim()
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  if (Array.isArray(value)) return value.map((v) => scalarText(v)).filter((s) => s).join('、')
  if (typeof value === 'object') return kvLines(value as Record<string, unknown>).join('，')
  return ''
}

function kvLines(record: Record<string, unknown>): string[] {
  return Object.keys(record)
    .map((key) => ({ key, text: scalarText(record[key]) }))
    .filter((row) => row.text.length > 0)
    .map((row) => `${row.key}：${row.text}`)
}

/** `usageSummary` 的展示行：已知键用中文标签，未知键原样 `键=值`，空值不显示。 */
const USAGE_LABELS: Record<string, string> = {
  modelCalls: '模型调用次数',
  modelCallCount: '模型调用次数',
  toolCalls: '工具调用次数',
  toolCallCount: '工具调用次数',
  inputTokens: '输入 token',
  outputTokens: '输出 token',
  totalTokens: '合计 token',
  promptCacheHitTokens: '缓存命中输入 token',
  promptCacheMissTokens: '缓存未命中输入 token',
  reasoningTokens: '思考 token（已含在输出内，不额外计费）'
}

export function usageLines(summary: Record<string, unknown> | null | undefined): string[] {
  if (!summary || typeof summary !== 'object') return []
  const lines: string[] = []
  Object.keys(summary).forEach((key) => {
    const text = scalarText(summary[key])
    if (!text) return
    lines.push(`${USAGE_LABELS[key] ?? key}：${text}`)
  })
  return lines
}

/** 回答生成后数据已变更的说明（design §9：事实保留原快照，不自动付费重跑）。 */
export function staleNoticeText(dataRevision: string | number | null | undefined): string {
  const rev = dataRevision === null || dataRevision === undefined ? '' : String(dataRevision).trim()
  return `回答生成后数据已变更${rev ? `（数据修订号 ${rev}）` : ''}：事实保留生成时的快照，页面不会自动付费重跑。`
}

/** 线程里的一行：由服务端消息 + （必要时）某个 run 的持久化结果合成。 */
export interface CoachThreadEntry {
  key: string
  role: 'user' | 'assistant'
  text: string
  time: string
  factCards: FactCard[]
  missingText: string
  staleText: string
  runId: string
  /** 该回答来自 run 的持久化结果，而不是已落库的消息（页面要如实说明） */
  fromRunPayload: boolean
}

export interface CoachThreadOptions {
  /** 时区换算由页面注入（types 层不依赖 utils/date，保持纯函数） */
  displayTime: (instant: string | null | undefined) => string
  /** 需要合并/补挂的 run（当前任务或最近一次任务）；没有就只渲染消息 */
  runId?: string | null
  answer?: AgentAnswer | null
  runIsStale?: boolean | null
  runFinishedAt?: string | null
}

function factCardsOf(raw: FactCard[] | null | undefined): FactCard[] {
  return Array.isArray(raw) ? raw : []
}

/**
 * 把 `chat_message` 列表合成会话线程。
 *
 * 规则（design §8、§9）：
 *  - 按 `messageNo` 升序，不依赖服务端排序；
 *  - 某条 assistant 消息带 `runId` 且我们有该 run 的持久化 `answer` 时，
 *    用服务端结果**补齐**消息里缺失的结构化列（事实卡片 / 缺失项 / 陈旧标记）——
 *    两者都来自服务端，不存在前端合成数值；已有值一律保留，不覆盖；
 *  - 若这个 run 的结果还没落成消息，则在末尾补一行并标注「取自 run 的持久化结果」。
 */
export function buildCoachThread(messages: CoachMessage[], opts: CoachThreadOptions): CoachThreadEntry[] {
  const ordered = messages
    .map((m, index) => ({ m, index }))
    .sort((a, b) => {
      const an = typeof a.m.messageNo === 'number' ? a.m.messageNo : Number.MAX_SAFE_INTEGER
      const bn = typeof b.m.messageNo === 'number' ? b.m.messageNo : Number.MAX_SAFE_INTEGER
      if (an !== bn) return an - bn
      return a.index - b.index
    })
    .map((row) => row.m)

  const entries: CoachThreadEntry[] = ordered.map((m) => ({
    key: `msg-${(m.id ?? '').trim() || `${m.messageNo}-${m.role}`}`,
    role: isUserMessage(m.role) ? 'user' : 'assistant',
    text: (m.content ?? '').trim(),
    time: opts.displayTime(m.createdAt),
    factCards: factCardsOf(m.factCards),
    missingText: missingFieldText(stringList(m.missingFields)),
    staleText: m.isStale === true ? staleNoticeText(m.dataRevision) : '',
    runId: (m.runId ?? '').trim(),
    fromRunPayload: false
  }))

  const runId = (opts.runId ?? '').trim()
  const answer = opts.answer ?? null
  if (!runId || !answer) return entries

  const target = entries.find((e) => e.role === 'assistant' && e.runId === runId)
  if (target) {
    if (!target.text) target.text = (answer.answerText ?? '').trim()
    if (target.factCards.length === 0) target.factCards = factCardsOf(answer.factCards)
    if (!target.missingText) target.missingText = missingFieldText(answer.missingFields ?? [])
    if (!target.staleText && (answer.isStale === true || opts.runIsStale === true)) {
      target.staleText = staleNoticeText(answer.dataRevision)
    }
    return entries
  }

  entries.push({
    key: `run-${runId}`,
    role: 'assistant',
    text: (answer.answerText ?? '').trim(),
    time: opts.displayTime(opts.runFinishedAt),
    factCards: factCardsOf(answer.factCards),
    missingText: missingFieldText(answer.missingFields ?? []),
    staleText:
      answer.isStale === true || opts.runIsStale === true ? staleNoticeText(answer.dataRevision) : '',
    runId,
    fromRunPayload: true
  })
  return entries
}

/* ==========================================================================
   缺失项展示（design §9：缺数据明确列出，不以 0 代替未知）
   ========================================================================== */

/** 回答里缺失/未确认的数据项。 */
export function missingFieldText(fields: string[] | null | undefined): string {
  const list = (fields ?? []).filter((s) => (s || '').trim().length > 0)
  if (list.length === 0) return ''
  return `暂无已确认记录，无法据此判断：${list.join('、')}`
}
