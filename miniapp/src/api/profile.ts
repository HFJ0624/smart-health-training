/**
 * 档案与目标接口封装（docs/p1-api.md §4）。
 */

import { del, get, post, put } from '@/utils/request'
import type { ApiPage } from '@/types/api'
import type {
  GoalCreateRequest,
  GoalListQuery,
  GoalUpdateRequest,
  ProfileCreateRequest,
  ProfileUpdateRequest,
  UserGoal,
  UserProfile
} from '@/types/profile'

/** `GET /api/me/profile`：无档案时 `data: null`（200，不是 404）——这是正常空状态（§4.1）。 */
export function fetchProfile(): Promise<UserProfile | null> {
  return get<UserProfile | null>('/me/profile')
}

/** `POST /api/me/profile`：首次建立，只有 heightCm 必填（§4.2）。 */
export function createProfile(payload: ProfileCreateRequest): Promise<UserProfile> {
  return post<UserProfile>('/me/profile', payload)
}

/** `PUT /api/me/profile`：修改，必须带 `lockVersion`，不符 409 `VERSION_CONFLICT`（§4.2）。 */
export function updateProfile(payload: ProfileUpdateRequest): Promise<UserProfile> {
  return put<UserProfile>('/me/profile', payload)
}

/** `GET /api/me/goals`：分页历史，默认全部状态，默认排序 `createdAt,desc`（§4.3）。 */
export function fetchGoals(query: GoalListQuery = {}): Promise<ApiPage<UserGoal>> {
  return get<ApiPage<UserGoal>>('/me/goals', {
    status: query.status || undefined,
    page: query.page,
    size: query.size,
    sort: query.sort ?? 'createdAt,desc'
  })
}

/** `GET /api/me/goals/active`：当前 ACTIVE 目标，无则 `data: null`（§4.3）。 */
export function fetchActiveGoal(): Promise<UserGoal | null> {
  return get<UserGoal | null>('/me/goals/active')
}

/** `POST /api/me/goals`：新建；`activate` 为 true 时事务内归档旧 ACTIVE（§4.3）。 */
export function createGoal(payload: GoalCreateRequest): Promise<UserGoal> {
  return post<UserGoal>('/me/goals', payload)
}

/** `PUT /api/me/goals/{id}`：需 lockVersion；DRAFT/ACTIVE 可改，ARCHIVED 不可改（§4.3）。 */
export function updateGoal(id: string, payload: GoalUpdateRequest): Promise<UserGoal> {
  return put<UserGoal>(`/me/goals/${encodeURIComponent(id)}`, payload)
}

/** `DELETE /api/me/goals/{id}`：**只删 DRAFT**（§4.3）。 */
export function deleteGoal(id: string): Promise<null> {
  return del<null>(`/me/goals/${encodeURIComponent(id)}`)
}

/** `POST /api/me/goals/{id}/activate`：归档旧 ACTIVE 后激活（§4.3）。 */
export function activateGoal(id: string, payload: { lockVersion: number }): Promise<UserGoal> {
  return post<UserGoal>(`/me/goals/${encodeURIComponent(id)}/activate`, payload)
}

/** `POST /api/me/goals/{id}/archive`：仅 ACTIVE 可归档（§4.3）。 */
export function archiveGoal(id: string, payload: { lockVersion: number }): Promise<UserGoal> {
  return post<UserGoal>(`/me/goals/${encodeURIComponent(id)}/archive`, payload)
}
