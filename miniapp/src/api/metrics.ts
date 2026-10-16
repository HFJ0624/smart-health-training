/**
 * 训练统计接口封装（docs/stage2-design.md §6，源码权威：`modules/metrics/controller/MetricsController.java`）。
 *
 * 两条约定：
 *   1. **页面一个指标都不算**。当天对比与周统计都直接渲染服务端返回的 `Metric`（含口径版本、来源与原因码），
 *      这样小程序、Agent 工具、后端验收看的是同一个数。
 *   2. 日期走**路径参数**且必须是 `YYYY-MM-DD`：后端解析失败给 `VALIDATION_FAILED` + `INVALID_DATE_FORMAT`，
 *      所以发请求前先用 `isDateValue` 拦一道，不把明显非法的日期打到网络上。
 */

import { get } from '@/utils/request'
import type { DayComparison, WeekSummary } from '@/types/metrics'
import { addDays, isoWeekday, isDateValue, parseDateValue } from '@/utils/date'

/** `GET /api/metrics/training/days/{businessDate}`：当天计划安排 vs 已确认执行。 */
export function fetchTrainingDay(businessDate: string): Promise<DayComparison> {
  if (!isDateValue(businessDate)) return Promise.reject(new Error('INVALID_DATE_VALUE'))
  return get<DayComparison>(`/metrics/training/days/${encodeURIComponent(businessDate)}`)
}

/** `GET /api/metrics/training/weeks/{weekStartDate}`：本周（周一至周日）场次、天数与有依据时长。 */
export function fetchTrainingWeek(weekStartDate: string): Promise<WeekSummary> {
  if (!isDateValue(weekStartDate)) return Promise.reject(new Error('INVALID_DATE_VALUE'))
  return get<WeekSummary>(`/metrics/training/weeks/${encodeURIComponent(weekStartDate)}`)
}

/**
 * 某一天所在周的**周一**（服务端强校验周起始日必须是周一，传错直接 422）。
 * 用本地日期的 ISO 星期回推，不做 UTC 换算：周统计的口径是「用户眼里的这一周」。
 */
export function mondayOf(dateValue: string): string {
  const d = parseDateValue(dateValue)
  if (!d) return dateValue
  return addDays(dateValue, -(isoWeekday(d) - 1))
}

/** 所在周的周日（展示区间用；服务端也会回 `weekEndDate`，这里只用于加载前的标题）。 */
export function sundayOf(dateValue: string): string {
  return addDays(mondayOf(dateValue), 6)
}
