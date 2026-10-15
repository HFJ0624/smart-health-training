/**
 * 日期与时间工具。
 * 契约口径（docs/p1-api.md §1「时间」）：
 *   - 瞬时值 = ISO-8601 UTC（`2026-10-13T07:20:00.000Z`），前端提交/展示都按设备时区换算；
 *   - 业务日期 = `YYYY-MM-DD`，**由服务端推导**，客户端不提交 `businessDate`（§5）。
 * 小程序 V8 支持 `new Date(...)` 与 `toISOString()`，不使用 `Intl`。
 */

/** 体重记录默认窗口：最近 90 天（含今天）（docs/p1-api.md §5）。 */
export const DEFAULT_BODY_RECORD_WINDOW_DAYS = 90

/** 日期范围跨度上限：≤366 天（docs/p1-api.md §1「日期范围」）。 */
export const MAX_DATE_RANGE_DAYS = 366

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/

export function pad2(n: number): string {
  return n < 10 ? `0${n}` : `${n}`
}

/** 本地日期的 `YYYY-MM-DD`。 */
export function formatDateValue(d: Date): string {
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
}

export function todayDateValue(): string {
  return formatDateValue(new Date())
}

export function addDays(dateValue: string, days: number): string {
  const parsed = parseDateValue(dateValue)
  if (!parsed) return dateValue
  parsed.setDate(parsed.getDate() + days)
  return formatDateValue(parsed)
}

export function parseDateValue(value: string): Date | null {
  if (!DATE_RE.test(value)) return null
  const [y, m, d] = value.split('-').map((s) => Number(s))
  const out = new Date(y, m - 1, d, 0, 0, 0, 0)
  if (out.getFullYear() !== y || out.getMonth() !== m - 1 || out.getDate() !== d) return null
  return out
}

export function isDateValue(value: string): boolean {
  return parseDateValue(value) !== null
}

/** ISO 星期：1=周一 … 7=周日（JS 的 getDay() 0=周日需要转换）。 */
export function isoWeekday(d: Date): number {
  const day = d.getDay()
  return day === 0 ? 7 : day
}

export function todayIsoWeekday(): number {
  return isoWeekday(new Date())
}

/** 本地瞬时值：`YYYY-MM-DDTHH:mm:ss`（设备时区），用于生成 `measuredAt`。 */
export function formatDateTimeValue(dateValue: string, timeValue: string): string {
  return `${dateValue}T${timeValue.length === 5 ? `${timeValue}:00` : timeValue}`
}

export function nowTimeValue(): string {
  const d = new Date()
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

/**
 * 本地日期时间 -> ISO-8601 UTC 瞬时值（提交给后端）。
 *
 * 不交给 `new Date(string)` 解析：iOS/JSCore 对 `YYYY-MM-DDTHH:mm:ss` 不带时区的写法不稳定，
 * 而老实现用 `replace(/-/g, '/')` 绕坑，反而把 `2026-10-13T17:05:00` 变成
 * `2026/10/13T17:05:00`——那是任何引擎都不认的格式，等于对本文件自己生成的字符串恒返回 null。
 * 这里显式拆分量再用本地构造器组装，跨引擎一致，并拒绝被 Date 静默进位的溢出日期。
 */
export function localDateTimeToInstant(localDateTime: string): string | null {
  const parts = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?$/.exec(localDateTime.trim())
  if (!parts) return null
  const [y, mo, d, h, mi, s] = [
    Number(parts[1]), Number(parts[2]), Number(parts[3]),
    Number(parts[4]), Number(parts[5]), Number(parts[6] ?? 0)
  ]
  const date = new Date(y, mo - 1, d, h, mi, s)
  if (Number.isNaN(date.getTime())) return null
  if (date.getFullYear() !== y || date.getMonth() !== mo - 1 || date.getDate() !== d) return null
  return date.toISOString()
}

/** ISO-8601 UTC 瞬时值 -> 本地 `YYYY-MM-DD HH:mm`（展示用）。 */
export function instantToDisplay(instant: string | null | undefined): string {
  if (!instant) return '—'
  const d = new Date(instant)
  if (Number.isNaN(d.getTime())) return instant
  return `${formatDateValue(d)} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

/** ISO-8601 UTC 瞬时值 -> 本地 `YYYY-MM-DDTHH:mm`（回填表单用）。 */
export function instantToLocalDateTime(instant: string | null | undefined): string {
  if (!instant) return ''
  const d = new Date(instant)
  if (Number.isNaN(d.getTime())) return ''
  return `${formatDateValue(d)}T${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

export function splitLocalDateTime(localDateTime: string): { date: string; time: string } {
  const [date = '', rest = '00:00'] = localDateTime.split('T')
  return { date, time: rest.slice(0, 5) }
}

/**
 * 工程护栏自检：`measuredAt` 晚于当前时间 +5 分钟会被后端拒绝
 * （docs/p1-api.md §5 `FUTURE_MEASURED_AT`）。阈值 5 分钟是契约确认清单第 4 项。
 */
export const FUTURE_TOLERANCE_MINUTES = 5

export function isFutureBeyondTolerance(instant: string): boolean {
  const d = new Date(instant)
  if (Number.isNaN(d.getTime())) return false
  return d.getTime() > Date.now() + FUTURE_TOLERANCE_MINUTES * 60 * 1000
}

/** `ends_on >= starts_on` 自检（§4.3 / §2 ENDS_BEFORE_STARTS）。 */
export function isEndsBeforeStarts(startsOn: string, endsOn: string | null): boolean {
  if (!endsOn) return false
  const s = parseDateValue(startsOn)
  const e = parseDateValue(endsOn)
  if (!s || !e) return false
  return e.getTime() < s.getTime()
}
