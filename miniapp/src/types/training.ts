/**
 * 训练计划聚合类型（docs/p1-api.md §7）。
 *
 * 「读取与写入同形，减少两套模型」——写入请求体就是去掉服务端字段后的同一套结构：
 * `id` / `userId` / `planGroupUuid` / `revisionNo` / `basePlanId` / `source` / `status` /
 * `goalSnapshot` / `confirmedAt` / `lockVersion`(仅 PUT 与状态迁移) / `createdAt` / `updatedAt` /
 * `exerciseId` / `planDayId` 都由服务端生成或校验，客户端注入会被拒
 * （docs/p1-plan-import.md §7 服务端字段黑名单；请求 DTO 为 FAIL_ON_UNKNOWN_PROPERTIES，§1「未知字段」）。
 * 动作条目用 `exerciseCode` 标识，与导入契约 §4 的客户端可见键保持一致。
 */

import type { GoalType } from './profile'
import type { ExerciseCategory } from './exercise'

export type PlanStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED' | 'DISCARDED'
export type PlanSource = 'MANUAL' | 'IMPORT' | 'AI'

export const PLAN_STATUS_LABELS: Record<PlanStatus, string> = {
  DRAFT: '草稿',
  ACTIVE: '生效中',
  ARCHIVED: '已归档',
  DISCARDED: '已丢弃'
}

/** ISO 1–7，1=周一（docs/p1-plan-import.md §3、§6）。 */
export const ISO_WEEKDAYS = [1, 2, 3, 4, 5, 6, 7] as const
export type IsoWeekday = 1 | 2 | 3 | 4 | 5 | 6 | 7

export const WEEKDAY_LABELS: Record<number, string> = {
  1: '周一',
  2: '周二',
  3: '周三',
  4: '周四',
  5: '周五',
  6: '周六',
  7: '周日'
}

export function weekdayLabel(isoWeekday: number | null | undefined): string {
  if (isoWeekday == null) return '未安排星期'
  return WEEKDAY_LABELS[isoWeekday] ?? `无法识别的星期值（${isoWeekday}）`
}

/** 服务端生成的目标快照（`docs/p1-database.md` §4.4）；缺失的键列在 `absentFields` 且值为 null，不补造。 */
export type PlanGoalSnapshot = {
  schemaVersion: string
  capturedAt: string | null
  goalType: GoalType | null
  absentFields: string[]
}

/** 动作条目（读取形态，含服务端解析出的动作信息）。 */
export interface PlanItem {
  id: string
  positionNo: number
  /** 字符串型 64 位整型，不参与算术 */
  exerciseId: string
  exerciseCode: string
  exerciseName: string
  category: ExerciseCategory
  targetSets: number | null
  repsMin: number | null
  repsMax: number | null
  targetExternalLoadKg: number | null
  targetDurationSeconds: number | null
  restSeconds: number | null
  notes: string | null
}

/** 计划日（读取形态）。 */
export interface PlanDay {
  id: string
  isoWeekday: number
  title: string | null
  isRestDay: boolean
  notes: string | null
  items: PlanItem[]
}

/** 计划头字段（读取形态，`GET /api/plans` 列表项也用这一组）。 */
export interface PlanHeader {
  id: string
  userId: string
  planGroupUuid: string
  revisionNo: number
  basePlanId: string | null
  title: string
  source: PlanSource
  status: PlanStatus
  startsOn: string
  endsOn: string | null
  confirmedAt: string | null
  lockVersion: number
  createdAt: string
  updatedAt: string
  goalSnapshot: PlanGoalSnapshot | null
}

/** 完整聚合（`GET /api/plans/{id}`、`/active`）。 */
export interface TrainingPlan extends PlanHeader {
  days: PlanDay[]
}

/** 列表项：契约为「版本列表」，字段同计划头；是否附带 days 未声明，故设为可选。 */
export interface PlanSummary extends PlanHeader {
  days?: PlanDay[]
}

/** `GET /api/plans/today`：`day=null` 表示今天该计划不安排（超出 ends_on 或非排程日）；无 ACTIVE 时 `activePlanId=null`。 */
export interface TodayPlanResult {
  activePlanId: string | null
  planGroupUuid: string | null
  revisionNo: number | null
  isoWeekday: number | null
  businessDate: string | null
  day: PlanDay | null
}

/** 写入用的动作条目。 */
export interface PlanItemInput {
  /** ≥1 且在当日唯一；缺省时服务端按数组顺序赋值 */
  positionNo: number
  /** 大小写规范化后必须命中 ACTIVE 字典（docs/p1-plan-import.md §4） */
  exerciseCode: string
  targetSets: number | null
  repsMin: number | null
  repsMax: number | null
  targetExternalLoadKg: number | null
  targetDurationSeconds: number | null
  restSeconds: number | null
  notes: string | null
}

/** 写入用的计划日。 */
export interface PlanDayInput {
  isoWeekday: number
  title: string | null
  isRestDay: boolean
  notes: string | null
  items: PlanItemInput[]
}

/** 聚合写入体（不含 lockVersion）：`POST /api/plans` 用。 */
export interface PlanAggregateInput {
  title: string
  startsOn: string
  endsOn: string | null
  days: PlanDayInput[]
}

/** `PUT /api/plans/{id}`：聚合替换 + 必填乐观锁。 */
export interface PlanAggregateUpdateInput extends PlanAggregateInput {
  lockVersion: number
}

/**
 * 状态迁移请求体（activate / archive / discard，§1「乐观锁」）。
 *
 * 只有 `lockVersion` 一个字段，与后端 `ActivateRequest`/`ArchiveRequest`/`DiscardRequest` 严格一致。
 * 曾经在这里加过一个可选 `replace`：后端 DTO 没有该组件，而全局
 * `FAIL_ON_UNKNOWN_PROPERTIES` 会把任何多余键判成 422 UNKNOWN_FIELD，
 * 于是"二次确认替换"的重试路径必然失败。
 * 更何况后端激活本来就是在同一事务内无条件归档旧 ACTIVE（docs/p1-api.md §7），
 * 替换不需要开关，只需要在调用前把"旧计划会被归档"说清楚——确认弹窗已经做了。
 */
export interface PlanTransitionRequest {
  lockVersion: number
}

export interface PlanListQuery {
  /** 多选逗号分隔，白名单校验（§7） */
  status?: string
  groupId?: string
  page?: number
  size?: number
  sort?: string
}

/**
 * 状态迁移矩阵（docs/p1-api.md §7）——页面按钮显隐与编辑路由的唯一依据。
 * DRAFT: 编辑 / 生效 / 丢弃 / 复制；ACTIVE: 归档 / 另存新草稿 / 复制；
 * ARCHIVED: 另存新草稿 / 复制；DISCARDED: 复制（终态只读）。
 */
export const PLAN_ACTIONS: Record<PlanStatus, string[]> = {
  DRAFT: ['edit', 'activate', 'discard', 'copy'],
  ACTIVE: ['archive', 'revise', 'copy'],
  ARCHIVED: ['revise', 'copy'],
  DISCARDED: ['copy']
}

export function allowsInPlaceEdit(status: PlanStatus): boolean {
  return status === 'DRAFT'
}
