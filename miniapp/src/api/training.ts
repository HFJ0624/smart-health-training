/**
 * 训练计划与导入接口封装（docs/p1-api.md §7、§8 + docs/p1-plan-import.md §10）。
 *
 * 写接口一律走**聚合提交**：一次请求带完整 `days[]`（含 items），服务端同事务重建子行；
 * P1 不提供 `POST /api/plans/{id}/days` 之类细粒度接口（§7 说明）。
 */

import { get, post, put } from '@/utils/request'
import type { ApiPage } from '@/types/api'
import type {
  PlanAggregateInput,
  PlanAggregateUpdateInput,
  PlanListQuery,
  PlanSummary,
  PlanTransitionRequest,
  TodayPlanResult,
  TrainingPlan
} from '@/types/training'
import { PLAN_IMPORT_TEXT_FIELD, type ImportConfirmResult, type ImportPreviewResult } from '@/types/plan-import'

/** `GET /api/plans`：版本列表，默认含 ARCHIVED/DISCARDED；`status` 多选逗号分隔（§7）。 */
export function fetchPlans(query: PlanListQuery = {}): Promise<ApiPage<PlanSummary>> {
  return get<ApiPage<PlanSummary>>('/plans', {
    status: query.status || undefined,
    groupId: query.groupId || undefined,
    page: query.page,
    size: query.size,
    sort: query.sort ?? 'updatedAt,desc'
  })
}

/** `GET /api/plans/today`：今天的计划日；`day=null` 表示今天不安排，无 ACTIVE 时 `activePlanId=null`（§7）。 */
export function fetchTodayPlan(): Promise<TodayPlanResult> {
  return get<TodayPlanResult>('/plans/today')
}

/** `GET /api/plans/{id}`：单版本详情（`(id,user_id)` 复合校验，越权一律 404）（§7）。 */
export function fetchPlan(id: string): Promise<TrainingPlan> {
  return get<TrainingPlan>(`/plans/${encodeURIComponent(id)}`)
}

/** `POST /api/plans`：新建 DRAFT（聚合）。`source=MANUAL`、group/revision/goalSnapshot 均由服务端生成（§7）。 */
export function createPlan(payload: PlanAggregateInput): Promise<TrainingPlan> {
  return post<TrainingPlan>('/plans', payload)
}

/** `PUT /api/plans/{id}`：就地编辑 **DRAFT**（聚合替换），`lockVersion` 必填；ACTIVE 会得到 409（§7）。 */
export function updatePlan(id: string, payload: PlanAggregateUpdateInput): Promise<TrainingPlan> {
  return put<TrainingPlan>(`/plans/${encodeURIComponent(id)}`, payload)
}

/**
 * `POST /api/plans/{id}/revise`：基于 ACTIVE/ARCHIVED 生成同组新 DRAFT（`revisionNo=max+1`、`basePlanId=id`），
 * **不修改来源版本**（§7、验收 A11）。契约未声明该接口请求体，故不发送 body。
 */
export function revisePlan(id: string): Promise<TrainingPlan> {
  return post<TrainingPlan>(`/plans/${encodeURIComponent(id)}/revise`)
}

/** `POST /api/plans/{id}/copy`：复制成新计划组（新 group、`revisionNo=1`、标题追加「（副本）」）（§7）。 */
export function copyPlan(id: string): Promise<TrainingPlan> {
  return post<TrainingPlan>(`/plans/${encodeURIComponent(id)}/copy`)
}

/**
 * `POST /api/plans/{id}/activate`：确认生效。
 * 已有 ACTIVE 且未指定替换时返回 409 `ACTIVE_PLAN_EXISTS`（§2），页面据此走二次确认。
 */
export function activatePlan(id: string, payload: PlanTransitionRequest): Promise<TrainingPlan> {
  return post<TrainingPlan>(`/plans/${encodeURIComponent(id)}/activate`, payload)
}

/** `POST /api/plans/{id}/archive`：仅 ACTIVE 可归档；归档后首页显示未设置计划（§7）。 */
export function archivePlan(id: string, payload: PlanTransitionRequest): Promise<TrainingPlan> {
  return post<TrainingPlan>(`/plans/${encodeURIComponent(id)}/archive`, payload)
}

/** `POST /api/plans/{id}/discard`：丢弃草稿，仅 DRAFT → `DISCARDED`，行保留不物理删除（§7）。 */
export function discardPlan(id: string, payload: PlanTransitionRequest): Promise<TrainingPlan> {
  return post<TrainingPlan>(`/plans/${encodeURIComponent(id)}/discard`, payload)
}

// ---------------------------------------------------------------------------
// 导入（docs/p1-plan-import.md §10）
// ---------------------------------------------------------------------------

/** 导入请求体：两条入口（会话选文件 / 粘贴）提交**完全相同**的 UTF-8 JSON 文本（§1）。 */
export function buildImportRequestBody(jsonText: string): Record<string, string> {
  return { [PLAN_IMPORT_TEXT_FIELD]: jsonText }
}

/** `POST /api/plans/import/preview`：**无副作用**，不落库、不占用幂等键（§10）。 */
export function previewPlanImport(jsonText: string): Promise<ImportPreviewResult> {
  return post<ImportPreviewResult>('/plans/import/preview', buildImportRequestBody(jsonText))
}

/**
 * `POST /api/plans/import`：确认保存。
 * 服务端**重新完整校验**（不信任 preview），成功后 `status=DRAFT`、`source=IMPORT`，绝不激活。
 * 必用 `Idempotency-Key`（§1、§10；验收 A17）：同键重放返回首次 `planId` + `idempotentReplay=true`，
 * 所以**失败重试必须复用同一个键**，键由页面持有。
 */
export function confirmPlanImport(jsonText: string, idempotencyKey: string): Promise<ImportConfirmResult> {
  return post<ImportConfirmResult>(
    '/plans/import',
    buildImportRequestBody(jsonText),
    { header: { 'Idempotency-Key': idempotencyKey } }
  )
}
