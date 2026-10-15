/**
 * UI 重排后新增展示组件的数据形状。
 *
 * 这些类型**不来自后端契约**：页面负责把接口返回的 VO 映射成它们（例如 `PlanDay[]` → `StripCell[]`），
 * 组件因此不依赖任何接口字段，能在只读页与编辑页之间复用。
 */
import type { FaIconName } from '@/styles/icon-names'

/** FaDayStrip 的一格。`mark` 是图形口径：on=有安排、rest=休息日、off=未安排。 */
export interface StripCell {
  key: string
  label: string
  sub?: string
  mark?: 'on' | 'rest' | 'off'
}

/** FaMoreMenu 的一项。`danger` 只改色调，页面仍必须自己走二次确认。 */
export interface MoreItem {
  key: string
  label: string
  icon: FaIconName
  danger?: boolean
}

/**
 * FaTrendChart 的一个数据点。`t` 是毫秒时间戳——横轴按**真实时间**而不是记录序号定位，
 * 否则稀疏测量会被画成等间隔，视觉上撒谎。
 */
export interface TrendPoint {
  t: number
  label: string
  value: number
}
