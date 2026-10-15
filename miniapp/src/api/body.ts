/**
 * 体重记录接口封装（docs/p1-api.md §5）。
 * 所有请求体都**不含** `businessDate`：业务日期由服务端按用户时区推导（§1「时区」、验收 A7）。
 */

import { del, get, post, put } from '@/utils/request'
import type { ApiPage } from '@/types/api'
import type {
  BodyRecord,
  BodyRecordCreateRequest,
  BodyRecordListQuery,
  BodyRecordUpdateRequest
} from '@/types/body'

/** `GET /api/body-records`：默认窗口最近 90 天；排序白名单 `measuredAt` `businessDate` `weightKg`。 */
export function fetchBodyRecords(query: BodyRecordListQuery = {}): Promise<ApiPage<BodyRecord>> {
  return get<ApiPage<BodyRecord>>('/body-records', {
    from: query.from,
    to: query.to,
    page: query.page,
    size: query.size,
    sort: query.sort ?? 'measuredAt,desc'
  })
}

/** `GET /api/body-records/latest`：`ORDER BY measured_at DESC, id DESC LIMIT 1`；无记录 `data: null`。 */
export function fetchLatestBodyRecord(): Promise<BodyRecord | null> {
  return get<BodyRecord | null>('/body-records/latest')
}

/**
 * `POST /api/body-records`：新增。
 * 幂等靠 `clientRecordUuid`（§5）：**提交失败重试必须复用同一个 UUID**，因此 UUID 由页面持有、
 * 成功后才更换（验收 A6）。同 UUID 二次提交返回既有行 + `created:false` + `idempotentReplay:true`。
 */
export function createBodyRecord(payload: BodyRecordCreateRequest): Promise<BodyRecord> {
  return post<BodyRecord>('/body-records', payload)
}

/** `PUT /api/body-records/{id}`：修改，需 `lockVersion`；`clientRecordUuid` 不可变（§5）。 */
export function updateBodyRecord(id: string, payload: BodyRecordUpdateRequest): Promise<BodyRecord> {
  return put<BodyRecord>(`/body-records/${encodeURIComponent(id)}`, payload)
}

/** `DELETE /api/body-records/{id}`：删除后 `latest` 自动回退（§5、验收 A8）。 */
export function deleteBodyRecord(id: string): Promise<null> {
  return del<null>(`/body-records/${encodeURIComponent(id)}`)
}
