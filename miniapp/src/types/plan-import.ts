/**
 * 训练计划 JSON 导入契约类型（docs/p1-plan-import.md + docs/p1-api.md §8、§10）。
 * 输入文档是 **snake_case**（模板文件即输入格式），业务读取接口是 camelCase，两套不要混。
 */

import type { PlanStatus } from './training'

/** 模板 schema 版本，必须精确等于该值，否则 `UNSUPPORTED_SCHEMA_VERSION`（§2）。 */
export const PLAN_SCHEMA_VERSION = 'fitagent.training_plan/1'

/** 交付模板的仓库路径：导入页把它原样显示给用户，作为「先把模板发到微信聊天」的对象（§1）。 */
export const PLAN_TEMPLATE_PATH = 'docs/templates/training-plan-template.v1.json'

/**
 * preview / confirm 请求体里承载 JSON 文本的键名。
 * 契约只写了「两条路径提交完全相同的请求体（UTF-8 JSON 文本）」（p1-plan-import.md §1）与
 * 「preview 无副作用、confirm 重新完整校验」（§10），没有给出键名。
 * 后端联调时若键名不同，**只需改这一个常量**。
 */
export const PLAN_IMPORT_TEXT_FIELD = 'content'

/** `POST /api/plans/import/preview` 与 `POST /api/plans/import` 的请求体。 */
export type PlanImportRequest = {
  [PLAN_IMPORT_TEXT_FIELD]: string
}

/**
 * 字段级定位信息：`path` 用 JSON 路径、数组用下标，与页面渲染一一对应（§8）。
 * `errors` 非空即不能确认；`warnings` 不阻断确认。
 */
export interface ImportIssue {
  path: string
  code: ImportIssueCode
  message: string
  value?: unknown
}

/** §2 导入类字段级码 + §5 矩阵码 + §8 上限码的并集。 */
export type ImportIssueCode =
  | 'REQUIRED'
  | 'TOO_LONG'
  | 'INVALID_TYPE'
  | 'INVALID_DATE_FORMAT'
  | 'INVALID_ENUM_VALUE'
  | 'INVALID_WEEKDAY'
  | 'MUST_BE_POSITIVE'
  | 'NEGATIVE_LOAD'
  | 'REST_OUT_OF_RANGE'
  | 'REPS_MAX_LT_MIN'
  | 'ENDS_BEFORE_STARTS'
  | 'DUPLICATE_WEEKDAY'
  | 'DUPLICATE_POSITION'
  | 'EXERCISE_NOT_FOUND'
  | 'EXERCISE_DISABLED'
  | 'REST_DAY_HAS_ITEMS'
  | 'EMPTY_TRAINING_DAY'
  | 'TOO_MANY_DAYS'
  | 'TOO_MANY_ITEMS'
  | 'TOO_MANY_ITEMS_TOTAL'
  | 'STRENGTH_DURATION_FORBIDDEN'
  | 'CARDIO_SETS_FORBIDDEN'
  | 'CARDIO_REPS_FORBIDDEN'
  | 'CARDIO_LOAD_FORBIDDEN'
  | 'CARDIO_REST_FORBIDDEN'
  | 'MOBILITY_LOAD_FORBIDDEN'
  | 'MOBILITY_NO_TARGET'
  | 'UNKNOWN_FIELD'
  | 'FORBIDDEN_SERVER_FIELD'
  | 'UNSUPPORTED_SCHEMA_VERSION'
  | 'POSITION_RENUMBERED'
  | 'TOO_MANY_DECIMALS'
  | 'PAYLOAD_TOO_LARGE'
  | string

/**
 * 服务端生成字段黑名单：出现在任何层级都判 `FORBIDDEN_SERVER_FIELD`（§7）。
 * 前端在提交前自查一遍，能立刻在本地给出定位错误；服务端仍然是权威。
 */
export const FORBIDDEN_SERVER_FIELDS: string[] = [
  'id',
  'userId',
  'user_id',
  'owner',
  'ownerUserId',
  'planId',
  'plan_id',
  'planGroupUuid',
  'plan_group_uuid',
  'revisionNo',
  'revision_no',
  'basePlanId',
  'base_plan_id',
  'source',
  'status',
  'activeUserId',
  'active_user_id',
  'lockVersion',
  'lock_version',
  'goalSnapshot',
  'goal_snapshot',
  'confirmedAt',
  'confirmed_at',
  'createdAt',
  'created_at',
  'updatedAt',
  'updated_at',
  'exerciseId',
  'exercise_id',
  'planDayId',
  'plan_day_id',
  'businessDate',
  'dataRevision'
]

/** 输入文档 `days[i].items[j]`（§4）。 */
export interface ImportItemInput {
  exercise_code: string
  position_no?: number | null
  target_sets?: number | null
  reps_min?: number | null
  reps_max?: number | null
  target_external_load_kg?: number | null
  target_duration_seconds?: number | null
  rest_seconds?: number | null
  notes?: string | null
}

/** 输入文档 `days[i]`（§3）。 */
export interface ImportDayInput {
  iso_weekday: number
  title?: string | null
  is_rest_day: boolean
  notes?: string | null
  items: ImportItemInput[]
}

/** 输入文档顶层（§2）。未定义字段一律拒绝。 */
export interface ImportPlanDocument {
  schema_version: string
  title: string
  starts_on: string
  ends_on?: string | null
  notes?: string | null
  days: ImportDayInput[]
}

/** preview 返回（§10）。 */
export interface ImportPreviewResult {
  /** 校验与规范化后的计划结构；服务端解析出的 exerciseId/name/category、预排的 position_no。 */
  plan?: ImportNormalizedPlan | null
  /** 非空即不能确认（§8）。 */
  errors: ImportIssue[]
  /** 不阻断确认（§8）。 */
  warnings: ImportIssue[]
  /** 字节与条目统计（§10）。 */
  byteSize?: number | null
  dayCount?: number | null
  itemCount?: number | null
  errorCount?: number | null
  warningCount?: number | null
  /** 服务端是否判定为「可确认」。前端仍以 `errors.length === 0` 为准。 */
  valid?: boolean | null
}

/**
 * 规范化预览结构：外层沿用 §7 聚合的 camelCase 命名（`days`/`items`/`exerciseName`…），
 * 由服务端解析字典后填充。
 */
export interface ImportNormalizedPlan {
  title?: string | null
  startsOn?: string | null
  endsOn?: string | null
  notes?: string | null
  days?: Array<{
    isoWeekday?: number
    title?: string | null
    isRestDay?: boolean
    notes?: string | null
    items?: Array<{
      positionNo?: number
      exerciseCode?: string
      exerciseName?: string | null
      exerciseId?: string | null
      category?: string | null
      targetSets?: number | null
      repsMin?: number | null
      repsMax?: number | null
      targetExternalLoadKg?: number | null
      targetDurationSeconds?: number | null
      restSeconds?: number | null
      notes?: string | null
    }>
  }>
}

/** confirm 返回（§10）：`status=DRAFT`、`source=IMPORT`，绝不激活。 */
export interface ImportConfirmResult {
  planId: string
  revisionNo: number
  status: PlanStatus
  idempotentReplay: boolean
  /** 缺失 Idempotency-Key 时服务端生成并回传（§10）。 */
  idempotencyKey?: string | null
}

/** §8 上限表：页面据此给出即时提示，服务端仍是权威。 */
export const IMPORT_LIMITS = {
  maxBytes: 256 * 1024,
  maxDays: 7,
  minDays: 1,
  maxItemsPerDay: 30,
  minItemsPerTrainingDay: 1,
  maxItemsTotal: 120,
  maxTitleLength: 160,
  maxDayTitleLength: 120,
  maxNotesLength: 800,
  maxRestSeconds: 3600
} as const
