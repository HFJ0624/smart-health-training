/**
 * 训练执行记录接口封装（docs/stage2-design.md §5，源码权威：`modules/workout/controller/WorkoutController.java`）。
 *
 * 两条必须守住的调用契约：
 *   1. **开始/恢复是同一个 `POST /workout/sessions`**：靠 `clientRecordUuid` 幂等 +
 *      数据库单草稿闸门（`UNIQUE(active_user_id)`）。已有草稿时后端返回它并把 HTTP 降为 200、
 *      `resumedExisting=true`，所以页面绝不"先查再建"，也不新建第二条草稿。
 *   2. **幂等键由页面持有**：提交失败原地重试必须复用同一个值（同 `pages/mine/body/records.vue` 的
 *      `clientRecordUuid` 生命周期）。写接口不自动重试（`utils/request.ts` 只重试 GET）。
 *
 * 身份与时区不在参数里：`userId` 由服务端从认证上下文取，`businessDate` 只在补录时显式给。
 */

import { del, get, post, put } from '@/utils/request'
import type { ApiPage } from '@/types/api'
import type {
  WorkoutCompleteInput,
  WorkoutSaveInput,
  WorkoutSessionDetail,
  WorkoutSessionListQuery,
  WorkoutSessionSummary,
  WorkoutStartInput,
  WorkoutStartResult,
  WorkoutVersionInput
} from '@/types/workout'

/** `POST /api/workout/sessions`：201 新建 / 200 恢复已有草稿，体都是 `{session, resumedExisting}`。 */
export function startWorkoutSession(payload: WorkoutStartInput): Promise<WorkoutStartResult> {
  return post<WorkoutStartResult>('/workout/sessions', payload)
}

/** `GET /api/workout/sessions`：本人分页，服务端固定按 businessDate、startedAt 倒序。 */
export function fetchWorkoutSessions(
  query: WorkoutSessionListQuery = {}
): Promise<ApiPage<WorkoutSessionSummary>> {
  return get<ApiPage<WorkoutSessionSummary>>('/workout/sessions', {
    status: query.status || undefined,
    from: query.from || undefined,
    to: query.to || undefined,
    page: query.page,
    size: query.size
  })
}

/**
 * 进行中的草稿（首页「继续」的唯一依据）。
 * 闸门保证至多一条，所以 `page=1,size=1` 足够；没有就是 `items` 空数组，不是错误。
 */
export async function fetchDraftWorkoutSession(): Promise<WorkoutSessionSummary | null> {
  const res = await fetchWorkoutSessions({ status: 'DRAFT', page: 1, size: 1 })
  return res.items.length > 0 ? res.items[0] : null
}

/** `GET /api/workout/sessions/{id}`：聚合详情；越权与不存在都是 404（每个路径单独校验归属）。 */
export function fetchWorkoutSession(id: string): Promise<WorkoutSessionDetail> {
  return get<WorkoutSessionDetail>(`/workout/sessions/${encodeURIComponent(id)}`)
}

/** `PUT /api/workout/sessions/{id}`：聚合保存（子项整体替换）；`lockVersion` 不符即 409。 */
export function saveWorkoutSession(id: string, payload: WorkoutSaveInput): Promise<WorkoutSessionDetail> {
  return put<WorkoutSessionDetail>(`/workout/sessions/${encodeURIComponent(id)}`, payload)
}

/** `POST /api/workout/sessions/{id}/complete`：结束确认；对已 COMPLETED 重复调用是幂等成功。 */
export function completeWorkoutSession(
  id: string,
  payload: WorkoutCompleteInput
): Promise<WorkoutSessionDetail> {
  return post<WorkoutSessionDetail>(`/workout/sessions/${encodeURIComponent(id)}/complete`, payload)
}

/** `POST /api/workout/sessions/{id}/discard`：丢弃草稿（行保留，闸门释放）。 */
export function discardWorkoutSession(
  id: string,
  payload: WorkoutVersionInput
): Promise<WorkoutSessionDetail> {
  return post<WorkoutSessionDetail>(`/workout/sessions/${encodeURIComponent(id)}/discard`, payload)
}

/** `DELETE /api/workout/sessions/{id}`：整棵子树删除（先 set 后 entry 再 session）。 */
export function deleteWorkoutSession(id: string): Promise<null> {
  return del<null>(`/workout/sessions/${encodeURIComponent(id)}`)
}
