/**
 * AI 教练接口封装（`docs/agent-v1-design.md` §11 接口清单）。
 *
 * 统一口径：
 *   - `user_id` 一律来自认证上下文，**客户端不发送任何 userId**（§11 约束）；
 *   - 一切请求走 `utils/request.ts`，页面不直接 `uni.request`；
 *   - 金额 / ID 按 `types/api.ts` 的宽容口径接收（BigInt 是字符串，DECIMAL 可能是字符串或数字）；
 *   - 只有 `POST /runs` 携带 `Idempotency-Key`：同一个问题重试**必须复用同一个键**，
 *     键由页面持有（与 `api/training.ts` 的导入幂等键同一手法）。
 */

import { get, post } from '@/utils/request'
import type { ApiPage, PageQuery } from '@/types/api'
import type {
  CoachConversation,
  CoachEstimate,
  CoachMessage,
  RunState,
  RunStep,
  RunSubmitResult
} from '@/types/coach'

const conversationsPath = '/coach/conversations'

function enc(value: string): string {
  return encodeURIComponent(value)
}

/**
 * `POST /api/coach/conversations`：新建会话。
 * 契约（§11.1）已定稿：请求体是空对象 `{}`，标题由服务端从首个问题截取。
 * 返回 `{ id, title, status, lastMessageAt }`。
 */
export function createConversation(): Promise<CoachConversation> {
  return post<CoachConversation>(conversationsPath, {})
}

/** `GET /api/coach/conversations?page=&size=`：本人会话分页，数组字段是 `items`。 */
export function fetchConversations(query: PageQuery = {}): Promise<ApiPage<CoachConversation>> {
  return get<ApiPage<CoachConversation>>(conversationsPath, {
    page: query.page,
    size: query.size,
    sort: query.sort
  })
}

/**
 * 数组字段读取：契约（design §11.1 第 4 条）已定稿 `messages` 与 `steps` 一律返回 `{items:[…]}`。
 * 这里仍对三种形状容错（`{items}`、裸数组、`items` 为 null），是因为后端旧构建可能仍在跑，
 * 而不是契约没定——页面因此不必各自猜一次。
 */
export function readListItems<T>(body: ApiPage<T> | T[] | null | undefined): T[] {
  if (Array.isArray(body)) return body
  if (body && Array.isArray(body.items)) return body.items
  return []
}

function readList<T>(body: ApiPage<T> | T[] | null): T[] {
  return readListItems<T>(body)
}

/**
 * `GET /api/coach/conversations/{id}/messages?after=&limit=`。
 * `after` 按「只返回 `message_no` 大于它的消息」实现（增量拉取用），首次不传。
 * 页面拿到后按 `messageNo` 升序渲染，不依赖服务端排序。
 */
export async function fetchConversationMessages(
  conversationId: string,
  after?: number,
  limit?: number
): Promise<CoachMessage[]> {
  const body = await get<ApiPage<CoachMessage> | CoachMessage[]>(
    `${conversationsPath}/${enc(conversationId)}/messages`,
    { after, limit }
  )
  return readList<CoachMessage>(body)
}

/**
 * `POST /api/coach/conversations/{id}/estimate`：**不调用收费模型**（§11），
 * 返回假设、最大调用次数、限额、可用工具与凭据状态。
 * 请求体（§11.1 定稿）：`{ question }`；预估假设与该问题相关，页面不额外提交任何可信字段。
 */
export function estimateRun(conversationId: string, question: string): Promise<CoachEstimate> {
  return post<CoachEstimate>(`${conversationsPath}/${enc(conversationId)}/estimate`, { question })
}

/**
 * `POST /api/coach/conversations/{id}/runs`：幂等提交，返回 **202** `{ runId, status:'QUEUED', … }`。
 * 同键同 `request_hash` 返回同一 run；同键不同问题 → `IDEMPOTENCY_KEY_CONFLICT`；
 * 会话已有活动 run → `RUN_ALREADY_ACTIVE`（§7、§10）。
 */
export function submitRun(
  conversationId: string,
  question: string,
  idempotencyKey: string
): Promise<RunSubmitResult> {
  return post<RunSubmitResult>(
    `${conversationsPath}/${enc(conversationId)}/runs`,
    { question },
    { header: { 'Idempotency-Key': idempotencyKey } }
  )
}

/** `GET /api/coach/runs/{id}`：状态、进度摘要、持久化结果、已知/未知费用（§10、§11）。 */
export function fetchRun(runId: string): Promise<RunState> {
  return get<RunState>(`/coach/runs/${enc(runId)}`)
}

/** `GET /api/coach/runs/{id}/steps`：脱敏操作摘要，**不含**模型原始推理文本（§11）。 */
export async function fetchRunSteps(runId: string): Promise<RunStep[]> {
  const body = await get<ApiPage<RunStep> | RunStep[]>(`/coach/runs/${enc(runId)}/steps`)
  return readList<RunStep>(body)
}

/**
 * `POST /api/coach/runs/{id}/cancel`：幂等取消。
 * 只有这个调用才是取消；客户端停止轮询**不是**取消（§10）。
 * 已发送的模型调用不会因取消而免费，界面按 run 返回的费用原样展示。
 */
export function cancelRun(runId: string): Promise<RunState | null> {
  return post<RunState | null>(`/coach/runs/${enc(runId)}/cancel`)
}
