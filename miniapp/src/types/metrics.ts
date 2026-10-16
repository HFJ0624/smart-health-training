/**
 * 训练统计类型（阶段 2，docs/stage2-design.md §6）。
 *
 * 与后端 `modules/metrics/vo/MetricsVos.java` 一一对应；`code`/`unit`/`unavailableReason` 的取值
 * 权威在 `modules/metrics/MetricsRules.java`，**前端不自己算任何指标**，只按 code 分支展示。
 *
 * 三条口径直接写进类型，避免页面把它们混起来：
 *   1. `value: number | null` —— null 是「无依据」，必须配 `unavailableReason`；0 是真实的零（如场次数）；
 *   2. 计划侧（`PlannedSide`）与实际侧（`ActualSide`）是两组独立字段，结构上就不可能把「安排了」当「练完了」；
 *   3. 场次历时与动作时长分列（`sessionElapsedSeconds*` / `recordedMovementSeconds*`），页面不相加成「总活动时长」。
 */

/** 指标码（MetricsRules 的常量；条目级带 `ITEM_` 前缀）。 */
export type MetricCode =
  | 'SET_COMPLETION_PCT'
  | 'CONFIRMED_SETS_CAPPED'
  | 'PLANNED_TARGET_SETS'
  | 'EXTRA_SETS'
  | 'CAPPED_MOVEMENT_SECONDS'
  | 'RECORDED_MOVEMENT_SECONDS'
  | 'REPS_IN_TARGET_RANGE_PCT'
  | 'SELF_INITIATED_ENTRY_COUNT'
  | 'SELF_INITIATED_CONFIRMED_SETS'
  | 'SELF_INITIATED_MOVEMENT_SECONDS'
  | 'COMPLETED_SESSION_COUNT'
  | 'DISTINCT_TRAINING_DAY_COUNT'
  | 'SESSION_ELAPSED_SECONDS'
  | 'EVIDENCED_MOVEMENT_SECONDS'
  | 'CONFIRMED_SETS_TOTAL'
  | 'ITEM_CONFIRMED_SETS'
  | 'ITEM_CONFIRMED_SETS_CAPPED'
  | 'ITEM_EXTRA_SETS'
  | 'ITEM_PLANNED_TARGET_SETS'
  | 'ITEM_SET_COMPLETION_PCT'
  | 'ITEM_RECORDED_MOVEMENT_SECONDS'
  | 'ITEM_CAPPED_MOVEMENT_SECONDS'
  | 'ITEM_REPS_IN_TARGET_RANGE_PCT'
  | string

/** 单位。 */
export type MetricUnit = 'PERCENT' | 'SETS' | 'SECONDS' | 'SESSIONS' | 'DAYS' | 'ENTRIES' | string

/** 无依据原因码：值必须为 null 时给出，前端据此显示「暂无依据」而不是 0%。 */
export type MetricReason =
  | 'NO_TARGET'
  | 'NO_CONFIRMED_SESSION'
  | 'NO_PLAN_SCHEDULED'
  | 'NO_PLAN_ITEMS'
  | 'DURATION_NOT_RECORDED'
  | 'NO_PLAN_DURATION_TARGET'
  | 'NO_REPS_RECORDED'
  | 'NO_REPS_TARGET'
  | 'PLAN_VERSION_UNAVAILABLE'
  | 'PLAN_ITEM_NOT_IN_REFERENCED_DAY'
  | 'PLAN_ITEM_WITHOUT_PLAN_REFERENCE'
  | 'SESSION_ELAPSED_UNAVAILABLE'
  | 'PLAN_REVISION_SNAPSHOT_MISMATCH'
  | string

/** `MetricsVos.Metric`：一个指标的完整自述（自带口径版本、计划版本、来源与修订号）。 */
export interface Metric {
  code: MetricCode
  value: number | null
  unit: MetricUnit
  ruleVersion: string
  referencePlanId: string | null
  referenceRevisionNo: number | null
  sourceSessionIds: string[]
  sourceEntryIds: string[]
  missingFields: string[]
  unavailableReason: MetricReason | null
  dataRevision: string | null
}

/** `MetricsVos.PlannedSide`：只来自所参考的计划版本。 */
export interface PlannedSide {
  targetSets: number | null
  repsMin: number | null
  repsMax: number | null
  targetExternalLoadKg: number | null
  targetDurationSeconds: number | null
  unavailableReason: MetricReason | null
}

/** `MetricsVos.ActualSide`：只统计 `is_completed=1` 的组；`cappedSets` 是先汇总再封顶的结果。 */
export interface ActualSide {
  confirmedSets: number | null
  cappedSets: number | null
  extraSets: number | null
  recordedDurationSeconds: number | null
  cappedDurationSeconds: number | null
  durationUnavailableReason: MetricReason | null
}

/** `MetricsVos.RepsCheck`：次数是否落在目标区间，单独一组，不参与「这组有没有被确认」。 */
export interface RepsCheck {
  targetRangeKnown: boolean | null
  confirmedSets: number | null
  setsWithReps: number | null
  setsInRepsRange: number | null
  setsOutOfRepsRange: number | null
  setsWithoutReps: number | null
  inRangePercent: number | null
  unavailableReason: MetricReason | null
}

/** `MetricsVos.ItemComparison`：一个计划条目的对比行。 */
export interface ItemComparison {
  planItemId: string
  exerciseId: string
  exerciseCode: string | null
  exerciseName: string | null
  positionNo: number
  planned: PlannedSide
  actual: ActualSide
  reps: RepsCheck
  metrics: Metric[]
  sourceSessionIds: string[]
  sourceEntryIds: string[]
  missingFields: string[]
}

/** `MetricsVos.PlanVersionComparison`：一个计划版本在这一天的对比（不同版本的分母永不合并）。 */
export interface PlanVersionComparison {
  planId: string
  planDayId: string | null
  planTitle: string | null
  planRevisionNo: number | null
  planStatus: string | null
  /** false = 只是历史场次当时参考的版本，不代表今天本该练什么 */
  scheduledForThisDate: boolean | null
  items: ItemComparison[]
  metrics: Metric[]
  sourceSessionIds: string[]
  missingFields: string[]
}

/** `MetricsVos.SelfInitiatedEntry`：自主动作的一行（没有分母，也就无从谈完成度）。 */
export interface SelfInitiatedEntry {
  sessionId: string
  entryId: string
  exerciseId: string
  exerciseName: string | null
  positionNo: number
  confirmedSets: number | null
  recordedDurationSeconds: number | null
  distanceMeters: number | null
}

/** `MetricsVos.SelfInitiatedSummary`。 */
export interface SelfInitiatedSummary {
  entries: SelfInitiatedEntry[]
  metrics: Metric[]
  missingFields: string[]
}

/** `MetricsVos.DayRecordSummary`。 */
export interface DayRecordSummary {
  completedSessionCount: number | null
  sessionIds: string[]
  recordedEntryCount: number | null
  confirmedSetCount: number | null
  sessionElapsedSecondsTotal: number | null
  recordedMovementSecondsTotal: number | null
  firstStartedAt: string | null
  lastEndedAt: string | null
  metrics: Metric[]
  missingFields: string[]
}

/** `GET /api/metrics/training/days/{businessDate}`（`MetricsVos.DayComparison`）。 */
export interface DayComparison {
  businessDate: string
  isoWeekday: number | null
  ruleVersion: string
  dataRevision: string | null
  planVersions: PlanVersionComparison[]
  selfInitiated: SelfInitiatedSummary
  recordSummary: DayRecordSummary
  missingFields: string[]
}

/** `MetricsVos.WeekDayRow`：周里某一天只给计数与时长，不给百分比。 */
export interface WeekDayRow {
  businessDate: string
  sessionCount: number | null
  confirmedSetCount: number | null
  recordedMovementSeconds: number | null
  movementUnavailableReason: MetricReason | null
  sessionElapsedSeconds: number | null
}

/** `GET /api/metrics/training/weeks/{weekStartDate}`（`MetricsVos.WeekSummary`）。 */
export interface WeekSummary {
  weekStartDate: string
  weekEndDate: string
  ruleVersion: string
  dataRevision: string | null
  metrics: Metric[]
  days: WeekDayRow[]
  missingFields: string[]
}

/** 原因码的中文说法：页面上「没有数」必须说清为什么没有，不能留白让人以为 0。 */
export const METRIC_REASON_LABELS: Record<string, string> = {
  NO_TARGET: '计划没给这一类的目标',
  NO_CONFIRMED_SESSION: '当天没有已确认的训练记录',
  NO_PLAN_SCHEDULED: '这天在当前生效计划里没有安排',
  NO_PLAN_ITEMS: '参考的计划日没有可比对的条目',
  DURATION_NOT_RECORDED: '实际时长没记录（未知不等于 0）',
  NO_PLAN_DURATION_TARGET: '计划没给时长目标，无法封顶',
  NO_REPS_RECORDED: '已确认的组里没记录次数',
  NO_REPS_TARGET: '计划没给次数区间',
  PLAN_VERSION_UNAVAILABLE: '参考的计划版本读不到',
  PLAN_ITEM_NOT_IN_REFERENCED_DAY: '记录引用的条目不在所参考的计划日里',
  PLAN_ITEM_WITHOUT_PLAN_REFERENCE: '本场没参考计划，该条目按自主动作处理',
  SESSION_ELAPSED_UNAVAILABLE: '开始/结束时间不全，历时算不出',
  PLAN_REVISION_SNAPSHOT_MISMATCH: '记录快照的版本号与计划行不一致'
}

export function metricReasonLabel(reason: string | null | undefined): string {
  if (!reason) return ''
  return METRIC_REASON_LABELS[reason] || reason
}

/** 指标码 → 短标签（与 `FaMetric` 的图标 + 短标题同一套命名，避免页面各起一名）。 */
export const METRIC_LABELS: Record<string, string> = {
  SET_COMPLETION_PCT: '组数已记录完成度',
  CONFIRMED_SETS_CAPPED: '计入完成度的组数',
  PLANNED_TARGET_SETS: '计划目标组数',
  EXTRA_SETS: '额外组',
  CAPPED_MOVEMENT_SECONDS: '封顶后动作时长',
  RECORDED_MOVEMENT_SECONDS: '已记录动作时长',
  REPS_IN_TARGET_RANGE_PCT: '次数在区间',
  SELF_INITIATED_ENTRY_COUNT: '自主动作条目',
  SELF_INITIATED_CONFIRMED_SETS: '自主动作组数',
  SELF_INITIATED_MOVEMENT_SECONDS: '自主动作时长',
  COMPLETED_SESSION_COUNT: '已确认场次',
  DISTINCT_TRAINING_DAY_COUNT: '训练天数',
  SESSION_ELAPSED_SECONDS: '场次历时合计',
  EVIDENCED_MOVEMENT_SECONDS: '有依据动作时长',
  CONFIRMED_SETS_TOTAL: '已确认组数',
  ITEM_SET_COMPLETION_PCT: '组数完成度',
  ITEM_CONFIRMED_SETS: '已确认组',
  ITEM_CONFIRMED_SETS_CAPPED: '计入组数',
  ITEM_EXTRA_SETS: '额外组',
  ITEM_PLANNED_TARGET_SETS: '计划组数',
  ITEM_RECORDED_MOVEMENT_SECONDS: '已记录时长',
  ITEM_CAPPED_MOVEMENT_SECONDS: '封顶时长',
  ITEM_REPS_IN_TARGET_RANGE_PCT: '次数在区间'
}

export function metricLabel(code: string): string {
  return METRIC_LABELS[code] || code
}

/**
 * 指标的展示值：null 一律显示「—」并带上原因，绝不用 0 或 100 顶替。
 * 百分比是 BigDecimal 的 2 位小数值（后端 `PERCENT_SCALE`），这里不再二次四舍五入。
 */
export function metricDisplay(m: Metric): { text: string; unit: string; note: string } {
  if (m.value === null || m.value === undefined) {
    return { text: '—', unit: '', note: metricReasonLabel(m.unavailableReason) || '暂无依据' }
  }
  if (m.unit === 'PERCENT') return { text: trimZero(m.value), unit: '%', note: '' }
  if (m.unit === 'SECONDS') return { text: String(m.value), unit: '秒', note: '' }
  if (m.unit === 'SETS') return { text: String(m.value), unit: '组', note: '' }
  if (m.unit === 'SESSIONS') return { text: String(m.value), unit: '场', note: '' }
  if (m.unit === 'DAYS') return { text: String(m.value), unit: '天', note: '' }
  if (m.unit === 'ENTRIES') return { text: String(m.value), unit: '个', note: '' }
  return { text: String(m.value), unit: m.unit, note: '' }
}

/** 百分比去掉无意义的尾零（`50.00` → `50`，`66.67` 保持），只做显示不做计算。 */
function trimZero(n: number): string {
  const s = n.toFixed(2)
  return s.endsWith('.00') ? s.slice(0, -3) : s.replace(/0$/, '')
}

/** 按 code 从一组指标里取第一条：口径重复出现时以服务端顺序为准，前端不合并。 */
export function findMetric(list: Metric[] | undefined, code: string): Metric | undefined {
  return (list || []).find((m) => m.code === code)
}

/** 缺失字段码转可读文本（`missingFields` 是后端给的字段名清单，页面原样列出）。 */
export function missingText(fields: string[] | undefined): string {
  const list = (fields || []).filter((f) => !!f)
  if (list.length === 0) return ''
  return `缺失：${list.join('、')}`
}
