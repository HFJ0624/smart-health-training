/**
 * 单位与数值工具。只做「单位/小数/字节数」这类机械换算与自检，不含业务判断。
 *
 * 单位口径写死（docs/p1-plan-import.md §6）：时长只有秒、外部负重只有 kg、日期只有 ISO local date、
 * 星期只有 ISO 1–7。**不做隐式换算**（防止把 30 当成 30 分钟存成 30 秒），页面只给换算提示表。
 */

export const UNIT_CONVERSION_HINTS: string[] = [
  '时长只接受秒：1 分钟 = 60 秒，1 小时 = 3600 秒',
  '外部负重只接受 kg（0 合法，表示自重）；不接受 lb/公斤/kg 字符串',
  '日期只接受 YYYY-MM-DD；不接受 2026/10/12、时间戳、带 Z 的时间',
  '星期只接受 ISO 1–7（1=周一，7=周日）',
  '系统不做隐式换算，填 30 就是 30 秒'
]

/** 秒 -> 「N 分 M 秒」的展示串，仅作展示，提交值仍是秒（§6）。 */
export function secondsToHuman(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return '—'
  if (!Number.isFinite(seconds)) return String(seconds)
  const s = Math.floor(seconds)
  if (s < 60) return `${s} 秒`
  const m = Math.floor(s / 60)
  const rest = s % 60
  if (m < 60) return rest === 0 ? `${m} 分钟` : `${m} 分 ${rest} 秒`
  const h = Math.floor(m / 60)
  const mm = m % 60
  return mm === 0 ? `${h} 小时` : `${h} 小时 ${mm} 分`
}

/** 十进制字符串转数字；非法返回 null（数值型不接受字符串数字，§4）。 */
export function parseDecimal(raw: string): number | null {
  const v = raw.trim()
  if (v === '') return null
  if (!/^-?\d+(\.\d+)?$/.test(v)) return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** 小数位自检：DECIMAL(6,2)/DECIMAL(5,2) 一律 ≤2 位小数（docs/p1-api.md §4.2、§5）。 */
export function countDecimals(value: number): number {
  if (!Number.isFinite(value)) return 0
  const s = String(value)
  const dot = s.indexOf('.')
  if (dot < 0) return 0
  return s.length - dot - 1
}

export function isDecimalWithin(value: number, maxDecimals: number): boolean {
  return countDecimals(value) <= maxDecimals
}

/** DECIMAL(6,2) 的量程自检（超出即后端 `VALIDATION_FAILED`，本地先拦）。 */
export function isWithinDecimal6_2(value: number): boolean {
  return Number.isFinite(value) && value < 10000 && isDecimalWithin(value, 2)
}

/** 展示用：体重/负重保留有效小数，整数不补 .00。 */
export function formatDecimal(value: number | null | undefined, unit = ''): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  const s = Number.isInteger(value) ? String(value) : String(Number(value.toFixed(2)))
  return unit ? `${s} ${unit}` : s
}

export function formatNumber(value: number | null | undefined, unit = ''): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  return unit ? `${value} ${unit}` : String(value)
}

/**
 * UTF-8 字节数（导入上限是 **256 KiB 字节**，不是字符数，docs/p1-plan-import.md §8）。
 * 不依赖 `TextEncoder`（小程序运行时不保证存在），按码点手工计数。
 */
export function utf8ByteLength(text: string): number {
  let bytes = 0
  for (let i = 0; i < text.length; i += 1) {
    const code = text.charCodeAt(i)
    if (code < 0x80) {
      bytes += 1
    } else if (code < 0x800) {
      bytes += 2
    } else if (code >= 0xd800 && code <= 0xdbff) {
      // 代理对：一个码点 4 字节，跳过低位
      bytes += 4
      i += 1
    } else {
      bytes += 3
    }
  }
  return bytes
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MiB`
}

/** 输入框用：把用户输入收敛到「非负、≤2 位小数的数字串」，避免提交时才报错。 */
export function sanitizeDecimalInput(raw: string, opts: { maxDecimals?: number; allowNegative?: boolean } = {}): string {
  const maxDecimals = opts.maxDecimals ?? 2
  let s = raw.replace(/[^\d.]/g, opts.allowNegative ? '[^-0-9.]' : '[^0-9.]')
  if (!opts.allowNegative) s = s.replace(/-/g, '')
  const firstDot = s.indexOf('.')
  if (firstDot >= 0) {
    s = s.slice(0, firstDot + 1) + s.slice(firstDot + 1).replace(/\./g, '')
  }
  if (firstDot >= 0 && maxDecimals === 0) {
    s = s.slice(0, firstDot)
  } else if (firstDot >= 0) {
    const decimals = s.slice(firstDot + 1)
    if (decimals.length > maxDecimals) s = s.slice(0, firstDot + 1 + maxDecimals)
  }
  return s
}
