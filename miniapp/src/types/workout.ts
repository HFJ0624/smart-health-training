/**
 * 训练执行记录类型（阶段 2，docs/stage2-design.md §5）。
 *
 * 与后端 `modules/workout/vo/WorkoutVos.java`、`dto/WorkoutRequests.java` 一一对应，
 * **后端没有的字段这里一个都不写**（多余键会被全局 FAIL_ON_UNKNOWN_PROPERTIES 判成 422 UNKNOWN_FIELD）。
 *
 * 三条口径直接来自契约：
 *   1. BIGINT id 在 JSON 里是**字符串**，一律 `string`，不参与算术；
 *   2. `null` 是「未知」，`0` 是「真实已知的零」（§3 V25）——读侧不许用计划值补实际值，写侧不许把空填成 0；
 *   3. 时间是 ISO-8601 UTC 瞬时值，`businessDate` 是 `YYYY-MM-DD` 且**由服务端推导**（补录才可指定）。
 */

import type { ExerciseCategory } from './exercise'

/** `workout_session.status`（§3 V23 的 CHECK 白名单）。 */
export type WorkoutSessionStatus = 'DRAFT' | 'COMPLETED' | 'DISCARDED'

export const WORKOUT_STATUS_LABELS: Record<WorkoutSessionStatus, string> = {
  DRAFT: '进行中',
  COMPLETED: '已确认',
  DISCARDED: '已丢弃'
}

/** `WorkoutVos.SetVo`：一组。 */
export interface WorkoutSetRecord {
  id: string
  setNo: number
  /** 次数；null=未知（不是 0） */
  reps: number | null
  /** DECIMAL(6,2)；null=未记录，0=自重（真实已知的零） */
  externalLoadKg: number | null
  /** 该组实际时长（秒），与场次历时分开（§6） */
  durationSeconds: number | null
  /** 只来自用户点击，服务端与前端都不替用户勾（§3 V25） */
  isCompleted: boolean
  notes: string | null
}

/** `WorkoutVos.EntryVo`：一个动作条目。`planTarget*` 是参考值，`durationSeconds/completedSets` 是实际值。 */
export interface WorkoutEntryRecord {
  id: string
  positionNo: number
  exerciseId: string
  exerciseCode: string | null
  exerciseName: string | null
  /** 字典被删时后端给 null，这里不猜类别 */
  category: ExerciseCategory | null
  planItemId: string | null
  /** 参考条目的计划组数；null = 自主动作或计划未给 */
  planTargetSets: number | null
  planRepsMin: number | null
  planRepsMax: number | null
  planTargetExternalLoadKg: number | null
  planTargetDurationSeconds: number | null
  /** 动作实际时长（秒） */
  durationSeconds: number | null
  distanceMeters: number | null
  completedSets: number
  recordedSets: number
  notes: string | null
  sets: WorkoutSetRecord[]
}

/** `WorkoutVos.SessionSummaryVo`：列表项。 */
export interface WorkoutSessionSummary {
  id: string
  businessDate: string
  status: WorkoutSessionStatus
  planId: string | null
  planDayId: string | null
  planRevisionNo: number | null
  startedAt: string | null
  endedAt: string | null
  /** 场次历时 = ended_at - started_at；草稿态没有 ended_at，故为 null */
  durationSeconds: number | null
  entryCount: number
  completedSetCount: number
  notes: string | null
  lockVersion: number
}

/** `WorkoutVos.SessionDetailVo`：聚合详情（含 entry/set 与参考计划）。 */
export interface WorkoutSessionDetail {
  id: string
  /** 幂等键；服务端规范化小写，前端重试必须复用同值 */
  clientRecordUuid: string
  businessDate: string
  /** 开始时快照的账户时区，之后不随账户改（§3 V23） */
  timezone: string
  status: WorkoutSessionStatus
  planId: string | null
  planTitle: string | null
  planRevisionNo: number | null
  planDayId: string | null
  planDayIsoWeekday: number | null
  startedAt: string | null
  endedAt: string | null
  durationSeconds: number | null
  notes: string | null
  lockVersion: number
  entries: WorkoutEntryRecord[]
}

/** `WorkoutVos.StartResultVo`：`resumedExisting=true` 说明这次拿到的是**已有草稿**而不是新建。 */
export interface WorkoutStartResult {
  session: WorkoutSessionDetail
  resumedExisting: boolean | null
}

/** `POST /api/workout/sessions`（`WorkoutRequests.Start`）。 */
export interface WorkoutStartInput {
  clientRecordUuid: string
  /** 与 planDayId 成对给出；服务端要求所参考的计划版本不是 DRAFT（§4.1） */
  planId?: string | null
  planDayId?: string | null
  /** 只在补录时给；不传即由服务端按账户时区取今天 */
  businessDate?: string | null
}

/** `WorkoutRequests.SetInput`。 */
export interface WorkoutSetInput {
  setNo: number
  reps?: number | null
  externalLoadKg?: number | null
  durationSeconds?: number | null
  /** 必填：服务端缺失即 VALIDATION_FAILED（不替用户确认） */
  isCompleted: boolean
  notes?: string | null
}

/** `WorkoutRequests.EntryInput`。 */
export interface WorkoutEntryInput {
  positionNo: number
  exerciseId: string
  /** 给了就必须属于本场次参考的那个计划日（§4）；自主动作留 null */
  planItemId?: string | null
  durationSeconds?: number | null
  distanceMeters?: number | null
  notes?: string | null
  sets: WorkoutSetInput[]
}

/** `WorkoutRequests.Save`：整棵子树按入参重建（聚合替换），必须带乐观锁版本。 */
export interface WorkoutSaveInput {
  lockVersion: number
  notes?: string | null
  entries: WorkoutEntryInput[]
}

/** `WorkoutRequests.Complete`：endedAt 不传即服务端当前时间；跨午夜不改业务日期（§4.7）。 */
export interface WorkoutCompleteInput {
  lockVersion: number
  endedAt?: string | null
}

/** `WorkoutRequests.VersionOnly`：discard 用。 */
export interface WorkoutVersionInput {
  lockVersion: number
}

/** `GET /api/workout/sessions` 查询参数（status 走后端枚举白名单，日期区间为 `YYYY-MM-DD`）。 */
export interface WorkoutSessionListQuery {
  status?: WorkoutSessionStatus | ''
  from?: string
  to?: string
  page?: number
  size?: number
}

/** 后端硬上限（`WorkoutService.MAX_ENTRIES` / `MAX_SETS_PER_ENTRY`），本地先拦，不等 422。 */
export const WORKOUT_LIMITS = {
  maxEntries: 40,
  maxSetsPerEntry: 60,
  maxNotesLength: 500
} as const
