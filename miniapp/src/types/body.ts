/**
 * 体重记录类型（docs/p1-api.md §5）。
 * `businessDate` 只出现在响应里：业务日期由服务端按用户时区从 `measuredAt` 推导，
 * **客户端不传**（§1「时区」+ §5，验收 A7）。
 */

export type BodyRecordSource = 'MANUAL' | 'IMPORT'

export interface BodyRecord {
  id: string
  /** YYYY-MM-DD，服务端推导，只读 */
  businessDate: string
  /** ISO-8601 UTC 实际测量瞬时值 */
  measuredAt: string
  /** DECIMAL(6,2)，必填 >0 */
  weightKg: number
  waistCm: number | null
  /** 0–100 */
  bodyFatPercent: number | null
  source: BodyRecordSource
  /** 前端生成的 UUID v4；同一逻辑记录重试复用同值（§5 + docs/p1-scope.md §3.3） */
  clientRecordUuid: string
  notes: string | null
  lockVersion: number
  /** 后端 `BodyRecordVo` 一并返回的只读列 */
  userId?: string
  createdAt?: string | null
  updatedAt?: string | null
  /**
   * 仅写响应（POST/PUT）有值：读响应里后端用 `@JsonInclude(NON_NULL)` 省略这两个键
   * （`BodyRecordVo.from()` 传 null）。
   * `created:false` + `idempotentReplay:true` = 同 `clientRecordUuid` 的重试被幂等命中，
   * 返回既有行且历史行数不变（docs/p1-api.md §5、验收 A6）。
   */
  created?: boolean | null
  idempotentReplay?: boolean | null
}

/** `POST /api/body-records`：不含 businessDate。 */
export interface BodyRecordCreateRequest {
  measuredAt: string
  weightKg: number
  waistCm: number | null
  bodyFatPercent: number | null
  clientRecordUuid: string
  notes: string | null
}

/** `PUT /api/body-records/{id}`：需 lockVersion；`clientRecordUuid` 不可变，按原值回传（§3.3「编辑：改数值不改 client_record_uuid」）。 */
export interface BodyRecordUpdateRequest {
  measuredAt: string
  weightKg: number
  waistCm: number | null
  bodyFatPercent: number | null
  clientRecordUuid: string
  notes: string | null
  lockVersion: number
}

/**
 * `GET /api/body-records` 查询参数。
 * 默认窗口最近 90 天（含今天）；排序白名单：`measuredAt` `businessDate` `weightKg`（§5）。
 */
export interface BodyRecordListQuery {
  from?: string
  to?: string
  page?: number
  size?: number
  sort?: string
}

/** §5 声明的工程护栏：measuredAt 晚于当前时间 +5 分钟会被拒绝。 */
export const FUTURE_MEASURED_AT_TOLERANCE_MINUTES = 5
